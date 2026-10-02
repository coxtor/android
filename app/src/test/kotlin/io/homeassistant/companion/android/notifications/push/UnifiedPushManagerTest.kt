package io.homeassistant.companion.android.notifications.push

import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import io.homeassistant.companion.android.common.data.integration.CloudPushTransport
import io.homeassistant.companion.android.common.data.integration.DeviceRegistration
import io.homeassistant.companion.android.common.data.integration.IntegrationRepository
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.database.server.Server
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.data.PushEndpoint

private const val DISTRIBUTOR = "io.heckel.ntfy"
private const val ENDPOINT_URL = "https://push.example.com/up1234?up=1"
private const val RENEWED_ENDPOINT_URL = "https://push.example.com/up5678?up=1"
private const val WORK_CLOUD_PUSH_SYNC = "CloudPushSync"
private const val WORK_REGISTER_RETRY = "UnifiedPushRegister"

class UnifiedPushManagerTest {

    private val connector = mockk<UnifiedPushConnector>(relaxed = true)
    private val serverManager = mockk<ServerManager>()
    private val integrationRepository = mockk<IntegrationRepository>()
    private val workManager = mockk<WorkManager>(relaxed = true)

    /** Unique work names in the order they were enqueued. */
    private val enqueuedWork = mutableListOf<String>()

    // The real repository on an in-memory storage, so generations are produced as in production.
    private val storage = InMemoryLocalStorage()
    private val registrationRepository = CloudPushRegistrationRepository(storage)

    @BeforeEach
    fun setUp() {
        every {
            workManager.enqueueUniqueWork(capture(enqueuedWork), any(), any<OneTimeWorkRequest>())
        } returns mockk(relaxed = true)

        every { connector.availableDistributors() } returns listOf(DISTRIBUTOR)
        every { connector.savedDistributor() } returns DISTRIBUTOR
        every { connector.acknowledgedDistributor() } returns DISTRIBUTOR

        val server = mockk<Server>(relaxed = true) { every { id } returns 1 }
        coEvery { serverManager.isRegistered() } returns true
        coEvery { serverManager.servers() } returns listOf(server)
        coEvery { serverManager.integrationRepository(any()) } returns integrationRepository
        registeredTransport(CloudPushTransport.Firebase)
    }

    /** A manager on the shared storage. Calling it twice simulates a process restart. */
    private fun TestScope.manager(firebaseAvailable: Boolean = true) = UnifiedPushManager(
        connector = connector,
        registrationRepository = registrationRepository,
        serverManager = serverManager,
        workManager = workManager,
        firebaseAvailable = firebaseAvailable,
        backgroundDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun registeredTransport(transport: CloudPushTransport) {
        coEvery { integrationRepository.getRegistration() } returns DeviceRegistration(cloudPush = transport)
    }

    private fun endpoint(temporary: Boolean, url: String = ENDPOINT_URL) = PushEndpoint(url, null, temporary)

    private suspend fun currentInstance() = registrationRepository.current().instance

    @Test
    fun `Given a distributor when enabling then it is saved and asked to register`() = runTest {
        val manager = manager()

        manager.enable(DISTRIBUTOR)

        val instance = currentInstance()
        verify { connector.saveDistributor(DISTRIBUTOR) }
        verify { connector.register(instance) }
        assertEquals(UnifiedPushState.Registering, manager.state.value)
    }

    @Test
    fun `Given an endpoint when it arrives then it is stored and registered with the servers`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)

        manager.onNewEndpoint(endpoint(temporary = false), currentInstance())

        assertEquals(UnifiedPushState.Registered(temporary = false), manager.state.value)
        assertEquals(
            DesiredCloudPushTransport.UnifiedPush(ENDPOINT_URL),
            registrationRepository.current().desired,
        )
    }

    @Test
    fun `Given a temporary endpoint when it arrives then it is registered and marked temporary`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)

        manager.onNewEndpoint(endpoint(temporary = true), currentInstance())

        assertEquals(UnifiedPushState.Registered(temporary = true), manager.state.value)
    }

    @Test
    fun `Given a temporary endpoint when a permanent one follows then it replaces it`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        val instance = currentInstance()
        manager.onNewEndpoint(endpoint(temporary = true), instance)

        manager.onNewEndpoint(endpoint(temporary = false, url = RENEWED_ENDPOINT_URL), instance)

        assertEquals(UnifiedPushState.Registered(temporary = false), manager.state.value)
        assertEquals(
            DesiredCloudPushTransport.UnifiedPush(RENEWED_ENDPOINT_URL),
            registrationRepository.current().desired,
        )
    }

    @Test
    fun `When disabling then the distributor is dropped and Firebase takes over`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        val unifiedPushInstance = currentInstance()

        manager.disable()

        verify { connector.unregister(unifiedPushInstance) }
        verify { connector.removeDistributor() }
        assertEquals(UnifiedPushState.Disabled, manager.state.value)
        assertEquals(DesiredCloudPushTransport.Firebase, registrationRepository.current().desired)
    }

    @Test
    fun `Given the current distributor unregisters then Firebase takes over`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        val instance = currentInstance()
        manager.onNewEndpoint(endpoint(temporary = false), instance)

        manager.onUnregistered(instance)

        assertEquals(UnifiedPushState.Disabled, manager.state.value)
        assertEquals(DesiredCloudPushTransport.Firebase, registrationRepository.current().desired)
        // A build with Firebase hands the transport back instead of renewing the registration.
        val renewedInstance = currentInstance()
        verify(exactly = 0) { connector.register(renewedInstance) }
    }

    @Test
    fun `Given a build without Firebase when the distributor unregisters then it registers again`() = runTest {
        val manager = manager(firebaseAvailable = false)
        manager.enable(DISTRIBUTOR)
        val instance = currentInstance()
        manager.onNewEndpoint(endpoint(temporary = false), instance)

        manager.onUnregistered(instance)

        // Without Firebase there is nothing to fall back to, so the dead endpoint is replaced by a
        // registration that is really running instead of a state that only claims to be.
        val renewed = registrationRepository.current()
        assertNotEquals(instance, renewed.instance)
        assertEquals(DesiredCloudPushTransport.UnifiedPush(null), renewed.desired)
        verify { connector.register(renewed.instance) }
        assertEquals(UnifiedPushState.Registering, manager.state.value)
    }

    @Test
    fun `Given a build without Firebase when the distributor is gone then nothing is registered`() = runTest {
        val manager = manager(firebaseAvailable = false)
        manager.enable(DISTRIBUTOR)
        val instance = currentInstance()
        manager.onNewEndpoint(endpoint(temporary = false), instance)
        // How a distributor that was uninstalled or disabled reaches us: the connector notices it is
        // gone while resolving the saved distributor and reports the registration as unregistered.
        every { connector.savedDistributor() } returns null
        val workBefore = enqueuedWork.size

        manager.onUnregistered(instance)

        val renewed = registrationRepository.current()
        assertEquals(DesiredCloudPushTransport.UnifiedPush(null), renewed.desired)
        verify(exactly = 0) { connector.register(renewed.instance) }
        assertEquals(UnifiedPushState.NoDistributor, manager.state.value)
        // Registering the empty Firebase token of this build would take the endpoint from the
        // servers without putting anything in its place.
        assertEquals(workBefore, enqueuedWork.size)
    }

    @Test
    fun `Given a build without Firebase when the distributor unregisters then the servers keep it`() = runTest {
        val manager = manager(firebaseAvailable = false)
        manager.enable(DISTRIBUTOR)
        val instance = currentInstance()
        manager.onNewEndpoint(endpoint(temporary = false), instance)
        val workBefore = enqueuedWork.size

        manager.onUnregistered(instance)

        // Registering the empty Firebase token of this build would remove the endpoint without
        // putting anything in its place, so the servers are left untouched.
        assertEquals(workBefore, enqueuedWork.size)
    }

    @Test
    fun `Given the distributor is temporarily unavailable then the state says so`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)

        manager.onTemporarilyUnavailable(currentInstance())

        assertEquals(UnifiedPushState.Unavailable, manager.state.value)
    }

    @ParameterizedTest
    @EnumSource(FailedReason::class)
    fun `Given a failure reason when registering fails then the state reports it`(reason: FailedReason) = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)

        manager.onRegistrationFailed(reason, currentInstance())

        assertEquals(UnifiedPushState.Failed(reason), manager.state.value)
    }

    @ParameterizedTest
    @EnumSource(value = FailedReason::class, names = ["NETWORK", "INTERNAL_ERROR"])
    fun `Given a transient failure when registering fails then it is retried`(reason: FailedReason) = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)

        manager.onRegistrationFailed(reason, currentInstance())

        assertEquals(listOf(WORK_REGISTER_RETRY), enqueuedWork)
    }

    @ParameterizedTest
    @EnumSource(value = FailedReason::class, names = ["ACTION_REQUIRED", "VAPID_REQUIRED"])
    fun `Given a failure the user has to resolve then nothing is retried`(reason: FailedReason) = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)

        manager.onRegistrationFailed(reason, currentInstance())

        assertEquals(emptyList<String>(), enqueuedWork)
    }

    @Test
    fun `Given Firebase is wanted when refreshing then UnifiedPush is disabled`() = runTest {
        val manager = manager()

        manager.refresh()

        assertEquals(UnifiedPushState.Disabled, manager.state.value)
    }

    @Test
    fun `Given a registered endpoint when refreshing then the distributor is reported`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        manager.onNewEndpoint(endpoint(temporary = false), currentInstance())
        registeredTransport(CloudPushTransport.Endpoint(ENDPOINT_URL))

        manager.refresh()

        assertEquals(UnifiedPushState.Registered(temporary = false), manager.state.value)
    }

    @Test
    fun `Given the distributor was uninstalled when refreshing then it is reported as missing`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        manager.onNewEndpoint(endpoint(temporary = false), currentInstance())
        every { connector.availableDistributors() } returns emptyList()

        manager.refresh()

        assertEquals(UnifiedPushState.NoDistributor, manager.state.value)
    }

    @Test
    fun `Given the distributor data was cleared when refreshing then it is reported as missing`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        manager.onNewEndpoint(endpoint(temporary = false), currentInstance())
        every { connector.savedDistributor() } returns null

        manager.refresh()

        assertEquals(UnifiedPushState.NoDistributor, manager.state.value)
    }

    @Test
    fun `Given no endpoint arrived yet when refreshing then it is still registering`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)

        manager.refresh()

        assertEquals(UnifiedPushState.Registering, manager.state.value)
    }

    @Test
    fun `Given a registration without an endpoint when refreshing then it is registered again`() = runTest {
        // Picking a distributor and losing the process before its answer arrives looks like this.
        manager().enable(DISTRIBUTOR)
        enqueuedWork.clear()

        // A second manager on the same storage is what a process restart does.
        manager().refresh()

        // Only a real registration can end the wait, the state alone cannot.
        assertEquals(listOf(WORK_REGISTER_RETRY), enqueuedWork)
    }

    @Test
    fun `Given an endpoint that never reached the server when refreshing then a sync is queued`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        manager.onNewEndpoint(endpoint(temporary = false), currentInstance())
        // The server still has Firebase, so the endpoint never made it there.
        registeredTransport(CloudPushTransport.Firebase)

        enqueuedWork.clear()
        manager.refresh()

        assertEquals(listOf(WORK_CLOUD_PUSH_SYNC), enqueuedWork)
    }

    @Test
    fun `Given UnifiedPush was disabled when a late endpoint arrives then Firebase stays`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        val staleInstance = currentInstance()
        manager.disable()

        manager.onNewEndpoint(endpoint(temporary = false), staleInstance)

        assertEquals(UnifiedPushState.Disabled, manager.state.value)
        assertEquals(DesiredCloudPushTransport.Firebase, registrationRepository.current().desired)
    }

    @Test
    fun `Given a new registration when a late unregistration of the previous one arrives then it is ignored`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        val firstInstance = currentInstance()
        manager.disable()
        manager.enable(DISTRIBUTOR)
        val secondInstance = currentInstance()

        manager.onUnregistered(firstInstance)

        assertEquals(UnifiedPushState.Registering, manager.state.value)
        assertEquals(secondInstance, registrationRepository.current().instance)
        assertEquals(
            DesiredCloudPushTransport.UnifiedPush(null),
            registrationRepository.current().desired,
        )
    }

    @Test
    fun `Given a disable while registering when the endpoint still arrives then it is not stored`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        val registeringInstance = currentInstance()

        manager.disable()
        manager.onNewEndpoint(endpoint(temporary = false), registeringInstance)

        assertEquals(DesiredCloudPushTransport.Firebase, registrationRepository.current().desired)
    }

    @Test
    fun `Given a late failure of a previous registration then the current state is untouched`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        val firstInstance = currentInstance()
        manager.disable()
        manager.enable(DISTRIBUTOR)

        manager.onRegistrationFailed(FailedReason.ACTION_REQUIRED, firstInstance)

        assertEquals(UnifiedPushState.Registering, manager.state.value)
    }

    @Test
    fun `Given a restart when the manager is recreated then the wanted registration is reconstructed`() = runTest {
        val before = manager()
        before.enable(DISTRIBUTOR)
        before.onNewEndpoint(endpoint(temporary = false), currentInstance())
        val instance = currentInstance()
        registeredTransport(CloudPushTransport.Endpoint(ENDPOINT_URL))

        // A new manager on the same storage is what the next process sees.
        val after = manager()
        after.refresh()

        assertEquals(UnifiedPushState.Registered(temporary = false), after.state.value)
        assertEquals(instance, registrationRepository.current().instance)
        assertEquals(
            DesiredCloudPushTransport.UnifiedPush(ENDPOINT_URL),
            registrationRepository.current().desired,
        )
    }

    @Test
    fun `Given a restart when a callback of the surviving registration arrives then it is accepted`() = runTest {
        val before = manager()
        before.enable(DISTRIBUTOR)
        val instance = currentInstance()

        val after = manager()
        after.onNewEndpoint(endpoint(temporary = true), instance)

        assertEquals(UnifiedPushState.Registered(temporary = true), after.state.value)
    }

    @Test
    fun `Given an endpoint event when the service forwards it then the endpoint is stored`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        val instance = currentInstance()

        manager.onEvent(UnifiedPushEvent.NewEndpoint(endpoint(temporary = false), instance))
        advanceUntilIdle()

        assertEquals(UnifiedPushState.Registered(temporary = false), manager.state.value)
        assertEquals(
            DesiredCloudPushTransport.UnifiedPush(ENDPOINT_URL),
            registrationRepository.current().desired,
        )
    }

    @Test
    fun `Given a stale endpoint event when the service forwards it then it is ignored`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        val staleInstance = currentInstance()
        manager.disable()

        manager.onEvent(UnifiedPushEvent.NewEndpoint(endpoint(temporary = false), staleInstance))
        advanceUntilIdle()

        assertEquals(UnifiedPushState.Disabled, manager.state.value)
        assertEquals(DesiredCloudPushTransport.Firebase, registrationRepository.current().desired)
    }

    @Test
    fun `Given an unregistration event when the service forwards it then Firebase takes over`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        val instance = currentInstance()

        manager.onEvent(UnifiedPushEvent.Unregistered(instance))
        advanceUntilIdle()

        assertEquals(UnifiedPushState.Disabled, manager.state.value)
        assertEquals(DesiredCloudPushTransport.Firebase, registrationRepository.current().desired)
    }

    @Test
    fun `Given an unavailable event when the service forwards it then the state says so`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)

        manager.onEvent(UnifiedPushEvent.TemporarilyUnavailable(currentInstance()))
        advanceUntilIdle()

        assertEquals(UnifiedPushState.Unavailable, manager.state.value)
    }

    @Test
    fun `Given a failure event when the service forwards it then the state reports it`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)

        manager.onEvent(UnifiedPushEvent.RegistrationFailed(FailedReason.NETWORK, currentInstance()))
        advanceUntilIdle()

        assertEquals(UnifiedPushState.Failed(FailedReason.NETWORK), manager.state.value)
    }

    @Test
    fun `Given a temporary endpoint event when a permanent one follows then the permanent one wins`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        val instance = currentInstance()

        storage.delayNextReadMs = 50
        manager.onEvent(UnifiedPushEvent.NewEndpoint(endpoint(temporary = true), instance))
        manager.onEvent(
            UnifiedPushEvent.NewEndpoint(endpoint(temporary = false, url = RENEWED_ENDPOINT_URL), instance),
        )
        advanceUntilIdle()

        assertEquals(UnifiedPushState.Registered(temporary = false), manager.state.value)
        assertEquals(
            DesiredCloudPushTransport.UnifiedPush(RENEWED_ENDPOINT_URL),
            registrationRepository.current().desired,
        )
    }

    @Test
    fun `Given several renewals of one registration then the last queued endpoint wins`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        val instance = currentInstance()
        val urls = listOf(ENDPOINT_URL, RENEWED_ENDPOINT_URL, "https://push.example.com/up9999?up=1")

        storage.delayNextReadMs = 50
        urls.forEach { url ->
            manager.onEvent(UnifiedPushEvent.NewEndpoint(endpoint(temporary = false, url = url), instance))
        }
        advanceUntilIdle()

        assertEquals(
            DesiredCloudPushTransport.UnifiedPush(urls.last()),
            registrationRepository.current().desired,
        )
    }

    @Test
    fun `Given an unregistration when a later endpoint of the same instance follows then it is stale`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        val instance = currentInstance()

        manager.onEvent(UnifiedPushEvent.Unregistered(instance))
        manager.onEvent(UnifiedPushEvent.NewEndpoint(endpoint(temporary = false), instance))
        advanceUntilIdle()

        assertEquals(UnifiedPushState.Disabled, manager.state.value)
        assertEquals(DesiredCloudPushTransport.Firebase, registrationRepository.current().desired)
    }

    @Test
    fun `Given a failing handler when the next event arrives then it is still handled`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        val instance = currentInstance()
        every {
            workManager.enqueueUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>())
        } throws IllegalStateException("cannot enqueue")

        manager.onEvent(UnifiedPushEvent.NewEndpoint(endpoint(temporary = true), instance))
        advanceUntilIdle()

        every {
            workManager.enqueueUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>())
        } returns mockk(relaxed = true)
        manager.onEvent(
            UnifiedPushEvent.NewEndpoint(endpoint(temporary = false, url = RENEWED_ENDPOINT_URL), instance),
        )
        advanceUntilIdle()

        assertEquals(UnifiedPushState.Registered(temporary = false), manager.state.value)
        assertEquals(
            DesiredCloudPushTransport.UnifiedPush(RENEWED_ENDPOINT_URL),
            registrationRepository.current().desired,
        )
    }
}
