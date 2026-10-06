package io.homeassistant.companion.android.notifications.push

import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import io.homeassistant.companion.android.common.data.integration.CloudPushTransport
import io.homeassistant.companion.android.common.data.integration.DeviceRegistration
import io.homeassistant.companion.android.common.data.integration.IntegrationRepository
import io.homeassistant.companion.android.common.data.integration.WebPushKeyRecord
import io.homeassistant.companion.android.common.data.integration.WebPushKeyStorage
import io.homeassistant.companion.android.common.data.integration.WebPushKeys
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.database.server.Server
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.security.GeneralSecurityException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.data.PublicKeySet
import org.unifiedpush.android.connector.data.PushEndpoint

private const val DISTRIBUTOR = "io.heckel.ntfy"
private const val ENDPOINT_URL = "https://push.example.com/up1234?up=1"
private const val RENEWED_ENDPOINT_URL = "https://push.example.com/up5678?up=1"
private const val WORK_CLOUD_PUSH_SYNC = "CloudPushSync"
private const val WORK_REGISTER_RETRY = "UnifiedPushRegister"

// Named here as well so that renaming a stored key has to be a deliberate change: a device that
// already stored a registration reads it back under exactly these names.
private const val PREF_ENDPOINT_URL = "endpoint_url"
private const val PREF_ENDPOINT_SNAPSHOT_ID = "endpoint_snapshot_id"
private const val PREF_ENDPOINT_KEYS_PRESENT = "endpoint_keys_present"

private val keys = WebPushKeys(p256dh = "BNcRd-public-key", auth = "YXV0aC1zZWNyZXQ")
private val renewedKeys = WebPushKeys(p256dh = "BRnWd-public-key", auth = "cmVuZXdlZC1hdXRo")

/** Protected storage that cannot be written, which is what a device with a lost master key does. */
private class UnwritableWebPushKeyStorage : WebPushKeyStorage {
    override suspend fun put(
        record: WebPushKeyRecord,
        reference: String,
        endpoint: String,
        keys: WebPushKeys,
    ): Unit = throw GeneralSecurityException("keystore entry missing")

    override suspend fun get(record: WebPushKeyRecord, reference: String, endpoint: String): WebPushKeys? = null

    override suspend fun clear(record: WebPushKeyRecord) = Unit
}

class UnifiedPushManagerTest {

    private val connector = mockk<UnifiedPushConnector>(relaxed = true)
    private val serverManager = mockk<ServerManager>()
    private val integrationRepository = mockk<IntegrationRepository>()
    private val workManager = mockk<WorkManager>(relaxed = true)

    /** Unique work names in the order they were enqueued. */
    private val enqueuedWork = mutableListOf<String>()

    /**
     * The enqueued work reduced to which worker it belongs to. A cloud push sync carries the
     * identity of the registration in its name, which is not what most of these tests are about.
     */
    private val enqueuedWorkers: List<String> get() = enqueuedWork.map { it.substringBefore('-') }

    // The real repository on in-memory storage, so generations, snapshots and the rule about which
    // record may answer a read all behave as in production.
    private val storage = InMemoryLocalStorage()
    private val keyStorage = InMemoryWebPushKeyStorage()
    private val registrationRepository = CloudPushRegistrationRepository(storage, keyStorage)

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

    private fun endpoint(temporary: Boolean, url: String = ENDPOINT_URL, keys: WebPushKeys? = null) = PushEndpoint(url, keys?.let { PublicKeySet(it.p256dh, it.auth) }, temporary)

    private suspend fun currentInstance() = registrationRepository.current().instance

    private suspend fun desired() = registrationRepository.current().desired

    /** The accepted endpoint snapshot, `null` while no endpoint is wanted. */
    private suspend fun desiredEndpoint(): EndpointSnapshot? = (desired() as? DesiredCloudPushTransport.UnifiedPush)?.endpoint

    private suspend fun desiredUrl(): String? = desiredEndpoint()?.url

    /** The accepted endpoint snapshot, which the test that asks for it requires to exist. */
    private suspend fun storedSnapshot(): EndpointSnapshot = checkNotNull(desiredEndpoint()) { "No endpoint snapshot is stored" }

    /** The keys of the accepted endpoint, resolved the way the worker resolves them. */
    private suspend fun desiredKeys(): WebPushKeys? = desiredEndpoint()?.let { registrationRepository.endpointKeys(it) }

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
        assertEquals(ENDPOINT_URL, desiredUrl())
    }

    @Test
    fun `Given an endpoint with keys when it arrives then the whole snapshot is stored`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)

        manager.onNewEndpoint(endpoint(temporary = false, keys = keys), currentInstance())

        val snapshot = storedSnapshot()
        assertEquals(ENDPOINT_URL, snapshot.url)
        assertTrue(snapshot.keysPresent)
        assertNotNull(snapshot.snapshotId)
        // Exactly the pair the distributor handed out, nothing derived and nothing merged.
        assertEquals(keys, desiredKeys())
    }

    @Test
    fun `Given an endpoint without a key set when it arrives then it is stored as keyless`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)

        manager.onNewEndpoint(endpoint(temporary = false), currentInstance())

        val snapshot = storedSnapshot()
        assertFalse(snapshot.keysPresent)
        assertNotNull(snapshot.snapshotId)
        assertNull(desiredKeys())
    }

    @Test
    fun `Given an incomplete key set when the endpoint arrives then it counts as keyless`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        // A half key pair would only produce a registration no sender could use, so it is not kept.
        val incomplete = PushEndpoint(ENDPOINT_URL, PublicKeySet("BNcRd-public-key", ""), false)

        manager.onNewEndpoint(incomplete, currentInstance())

        assertEquals(ENDPOINT_URL, desiredUrl())
        assertFalse(storedSnapshot().keysPresent)
        assertNull(desiredKeys())
    }

    @Test
    fun `Given every accepted endpoint then it gets a snapshot of its own`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        val instance = currentInstance()

        manager.onNewEndpoint(endpoint(temporary = false, keys = keys), instance)
        val first = storedSnapshot().snapshotId
        // The same URL and the same keys again is still a later callback, not the same one.
        manager.onNewEndpoint(endpoint(temporary = false, keys = keys), instance)
        val second = storedSnapshot().snapshotId

        assertNotEquals(first, second)
    }

    @Test
    fun `Given a renewal with the same URL and new keys then the whole tuple is replaced`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        val instance = currentInstance()
        manager.onNewEndpoint(endpoint(temporary = false, keys = keys), instance)
        val before = storedSnapshot().snapshotId

        manager.onNewEndpoint(endpoint(temporary = false, keys = renewedKeys), instance)

        assertEquals(ENDPOINT_URL, desiredUrl())
        assertNotEquals(before, storedSnapshot().snapshotId)
        assertEquals(renewedKeys, desiredKeys())
    }

    @Test
    fun `Given a renewal with a new URL and the same keys then the whole tuple is replaced`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        val instance = currentInstance()
        manager.onNewEndpoint(endpoint(temporary = false, keys = keys), instance)
        val before = storedSnapshot().snapshotId

        manager.onNewEndpoint(endpoint(temporary = false, url = RENEWED_ENDPOINT_URL, keys = keys), instance)

        assertEquals(RENEWED_ENDPOINT_URL, desiredUrl())
        assertNotEquals(before, storedSnapshot().snapshotId)
        // The keys are readable for the new snapshot, which is what the registration will send.
        assertEquals(keys, desiredKeys())
    }

    @Test
    fun `Given a keyed endpoint when a keyless one follows then the keys are gone`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        val instance = currentInstance()
        manager.onNewEndpoint(endpoint(temporary = false, keys = keys), instance)

        manager.onNewEndpoint(endpoint(temporary = false, url = RENEWED_ENDPOINT_URL), instance)

        assertFalse(storedSnapshot().keysPresent)
        assertNull(desiredKeys())
        // Nothing of the previous snapshot is left behind in protected storage either.
        assertNull(keyStorage.storedRecords[WebPushKeyRecord.DESIRED])
    }

    @Test
    fun `Given a temporary endpoint when it arrives then it is registered and marked temporary`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)

        manager.onNewEndpoint(endpoint(temporary = true), currentInstance())

        assertEquals(UnifiedPushState.Registered(temporary = true), manager.state.value)
    }

    @Test
    fun `Given a temporary endpoint when a permanent one follows then it replaces the whole tuple`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        val instance = currentInstance()
        manager.onNewEndpoint(endpoint(temporary = true, keys = keys), instance)
        val before = storedSnapshot().snapshotId

        manager.onNewEndpoint(
            endpoint(temporary = false, url = RENEWED_ENDPOINT_URL, keys = renewedKeys),
            instance,
        )

        assertEquals(UnifiedPushState.Registered(temporary = false), manager.state.value)
        assertEquals(RENEWED_ENDPOINT_URL, desiredUrl())
        assertNotEquals(before, storedSnapshot().snapshotId)
        assertEquals(renewedKeys, desiredKeys())
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
        assertEquals(DesiredCloudPushTransport.Firebase, desired())
    }

    @Test
    fun `When disabling then the keys of the endpoint are forgotten`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        manager.onNewEndpoint(endpoint(temporary = false, keys = keys), currentInstance())

        manager.disable()

        assertNull(keyStorage.storedRecords[WebPushKeyRecord.DESIRED])
    }

    @Test
    fun `Given the current distributor unregisters then Firebase takes over`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        val instance = currentInstance()
        manager.onNewEndpoint(endpoint(temporary = false), instance)

        manager.onUnregistered(instance)

        assertEquals(UnifiedPushState.Disabled, manager.state.value)
        assertEquals(DesiredCloudPushTransport.Firebase, desired())
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

        assertEquals(listOf(WORK_REGISTER_RETRY), enqueuedWorkers)
    }

    @ParameterizedTest
    @EnumSource(value = FailedReason::class, names = ["ACTION_REQUIRED", "VAPID_REQUIRED"])
    fun `Given a failure the user has to resolve then nothing is retried`(reason: FailedReason) = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)

        manager.onRegistrationFailed(reason, currentInstance())

        assertEquals(emptyList<String>(), enqueuedWorkers)
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
        assertEquals(listOf(WORK_REGISTER_RETRY), enqueuedWorkers)
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

        assertEquals(listOf(WORK_CLOUD_PUSH_SYNC), enqueuedWorkers)
    }

    @Test
    fun `Given an endpoint stored without a snapshot when refreshing then the distributor is asked again`() = runTest {
        // What an endpoint stored before the app kept key material looks like. Whether it has keys
        // is unknown, and only the distributor can answer that with a new snapshot.
        manager().enable(DISTRIBUTOR)
        storage.putString(PREF_ENDPOINT_URL, ENDPOINT_URL)
        assertNull(storedSnapshot().snapshotId)
        enqueuedWork.clear()

        manager().refresh()

        assertEquals(listOf(WORK_REGISTER_RETRY), enqueuedWorkers)
    }

    @Test
    fun `Given keys that are expected but gone when refreshing then the distributor is asked again`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        manager.onNewEndpoint(endpoint(temporary = false, keys = keys), currentInstance())
        registeredTransport(CloudPushTransport.Endpoint(ENDPOINT_URL, keys))
        // The state still promises keys, but protected storage lost them.
        keyStorage.clear(WebPushKeyRecord.DESIRED)
        enqueuedWork.clear()

        manager.refresh()

        // Registering the endpoint without them would claim a subscription nothing can address.
        assertEquals(listOf(WORK_REGISTER_RETRY), enqueuedWorkers)
    }

    @Test
    fun `Given an endpoint that never had keys when refreshing then nothing is registered again`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        manager.onNewEndpoint(endpoint(temporary = false), currentInstance())
        registeredTransport(CloudPushTransport.Endpoint(ENDPOINT_URL))
        enqueuedWork.clear()

        manager.refresh()

        // A keyless endpoint is a complete snapshot, so asking again would never end.
        assertEquals(emptyList<String>(), enqueuedWorkers)
    }

    @Test
    fun `Given UnifiedPush was disabled when a late endpoint arrives then Firebase stays`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        val staleInstance = currentInstance()
        manager.disable()

        manager.onNewEndpoint(endpoint(temporary = false), staleInstance)

        assertEquals(UnifiedPushState.Disabled, manager.state.value)
        assertEquals(DesiredCloudPushTransport.Firebase, desired())
    }

    @Test
    fun `Given a stale endpoint with keys when it arrives then nothing is stored`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        val staleInstance = currentInstance()
        manager.enable(DISTRIBUTOR)
        manager.onNewEndpoint(endpoint(temporary = false, keys = keys), currentInstance())
        val current = storedSnapshot()

        manager.onNewEndpoint(
            endpoint(temporary = false, url = RENEWED_ENDPOINT_URL, keys = renewedKeys),
            staleInstance,
        )

        // Neither the URL nor the keys of the registration that is wanted may be touched by this.
        assertEquals(current, desiredEndpoint())
        assertEquals(keys, desiredKeys())
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
        assertEquals(DesiredCloudPushTransport.UnifiedPush(null), desired())
    }

    @Test
    fun `Given a disable while registering when the endpoint still arrives then it is not stored`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        val registeringInstance = currentInstance()

        manager.disable()
        manager.onNewEndpoint(endpoint(temporary = false), registeringInstance)

        assertEquals(DesiredCloudPushTransport.Firebase, desired())
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
        before.onNewEndpoint(endpoint(temporary = false, keys = keys), currentInstance())
        val instance = currentInstance()
        val snapshot = storedSnapshot()
        registeredTransport(CloudPushTransport.Endpoint(ENDPOINT_URL, keys))

        // A new manager on the same storage is what the next process sees.
        val after = manager()
        after.refresh()

        assertEquals(UnifiedPushState.Registered(temporary = false), after.state.value)
        assertEquals(instance, registrationRepository.current().instance)
        // The complete tuple survives, including the keys that belong to this snapshot.
        assertEquals(snapshot, desiredEndpoint())
        assertEquals(keys, desiredKeys())

        // The next process builds a new repository as well, not only a new manager, so nothing may
        // depend on state that lives in the object rather than in storage.
        val recreated = CloudPushRegistrationRepository(storage, keyStorage)
        val reconstructed = recreated.current()
        assertEquals(instance, reconstructed.instance)
        val reconstructedEndpoint = checkNotNull(
            (reconstructed.desired as? DesiredCloudPushTransport.UnifiedPush)?.endpoint,
        )
        assertEquals(snapshot, reconstructedEndpoint)
        assertTrue(reconstructedEndpoint.keysPresent)
        assertEquals(keys, recreated.endpointKeys(reconstructedEndpoint))
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

        manager.onEvent(UnifiedPushEvent.NewEndpoint(endpoint(temporary = false, keys = keys), instance))
        advanceUntilIdle()

        assertEquals(UnifiedPushState.Registered(temporary = false), manager.state.value)
        assertEquals(ENDPOINT_URL, desiredUrl())
        assertEquals(keys, desiredKeys())
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
        assertEquals(DesiredCloudPushTransport.Firebase, desired())
    }

    @Test
    fun `Given an unregistration event when the service forwards it then Firebase takes over`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        val instance = currentInstance()

        manager.onEvent(UnifiedPushEvent.Unregistered(instance))
        advanceUntilIdle()

        assertEquals(UnifiedPushState.Disabled, manager.state.value)
        assertEquals(DesiredCloudPushTransport.Firebase, desired())
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
        assertEquals(RENEWED_ENDPOINT_URL, desiredUrl())
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

        assertEquals(urls.last(), desiredUrl())
    }

    @Test
    fun `Given several keyed renewals then the keys of the last endpoint are the stored ones`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        val instance = currentInstance()

        storage.delayNextReadMs = 50
        manager.onEvent(UnifiedPushEvent.NewEndpoint(endpoint(temporary = false, keys = keys), instance))
        manager.onEvent(
            UnifiedPushEvent.NewEndpoint(
                endpoint(temporary = false, url = RENEWED_ENDPOINT_URL, keys = renewedKeys),
                instance,
            ),
        )
        advanceUntilIdle()

        // The keys of the earlier callback must not survive next to the later URL.
        assertEquals(RENEWED_ENDPOINT_URL, desiredUrl())
        assertEquals(renewedKeys, desiredKeys())
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
        assertEquals(DesiredCloudPushTransport.Firebase, desired())
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
        assertEquals(RENEWED_ENDPOINT_URL, desiredUrl())
    }

    @Test
    fun `Given an accepted endpoint whose sync never ran when refreshing then it is scheduled`() = runTest {
        val before = manager()
        before.enable(DISTRIBUTOR)
        before.onNewEndpoint(endpoint(temporary = false, keys = keys), currentInstance())
        // The servers have that first endpoint.
        registeredTransport(CloudPushTransport.Endpoint(ENDPOINT_URL, keys))
        // A renewal is accepted and persisted, then the process dies before its sync was queued.
        val generation = registrationRepository.current().generation
        registrationRepository.acceptEndpoint(generation, RENEWED_ENDPOINT_URL, renewedKeys)
        enqueuedWork.clear()

        manager().refresh()

        // Nothing else would ever notice: an endpoint is registered, it is just the wrong one.
        assertEquals(listOf(WORK_CLOUD_PUSH_SYNC), enqueuedWorkers)
    }

    @Test
    fun `Given the registered endpoint has the same URL with other keys when refreshing then a sync is queued`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        manager.onNewEndpoint(endpoint(temporary = false, keys = renewedKeys), currentInstance())
        // Same URL, but what reached the servers were the keys from before the rotation.
        registeredTransport(CloudPushTransport.Endpoint(ENDPOINT_URL, keys))
        enqueuedWork.clear()

        manager.refresh()

        assertEquals(listOf(WORK_CLOUD_PUSH_SYNC), enqueuedWorkers)
    }

    @Test
    fun `Given the registered endpoint has no key association when refreshing then a sync is queued`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        manager.onNewEndpoint(endpoint(temporary = false, keys = keys), currentInstance())
        // The endpoint is registered, but the servers never got the keys that belong to it.
        registeredTransport(CloudPushTransport.Endpoint(ENDPOINT_URL))
        enqueuedWork.clear()

        manager.refresh()

        assertEquals(listOf(WORK_CLOUD_PUSH_SYNC), enqueuedWorkers)
    }

    @Test
    fun `Given the registered endpoint already matches when refreshing then no sync is queued`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        manager.onNewEndpoint(endpoint(temporary = false, keys = keys), currentInstance())
        registeredTransport(CloudPushTransport.Endpoint(ENDPOINT_URL, keys))
        enqueuedWork.clear()

        manager.refresh()

        // The servers are up to date, so repeating the registration would be pure churn.
        assertEquals(emptyList<String>(), enqueuedWorkers)
    }

    @Test
    fun `Given a read in progress when a renewal writes then the reader sees one coherent state`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        manager.onNewEndpoint(endpoint(temporary = false, keys = keys), currentInstance())
        val generation = registrationRepository.current().generation
        val before = storedSnapshot()

        // Hold the reader after it read the endpoint URL and before it read the snapshot id, which
        // is where an unsynchronised read would pick up half of each state.
        val gate = storage.gateNextReadOf(PREF_ENDPOINT_SNAPSHOT_ID)
        val read = async { registrationRepository.current() }
        runCurrent()
        val renewal = async {
            registrationRepository.acceptEndpoint(generation, RENEWED_ENDPOINT_URL, renewedKeys)
        }
        runCurrent()
        gate.complete(Unit)

        val observed = (read.await().desired as? DesiredCloudPushTransport.UnifiedPush)?.endpoint
        val renewed = renewal.await()

        // Either everything from before the renewal or everything from after it. These carry no key
        // material, so naming them in a failure is safe.
        assertTrue(
            observed == before || observed == renewed,
            "current() observed a mixed state: $observed, before=$before, renewed=$renewed",
        )
    }

    @Test
    fun `Given protected storage that cannot be written when an endpoint is accepted then nothing claims its keys`() = runTest {
        val repository = CloudPushRegistrationRepository(storage, UnwritableWebPushKeyStorage())
        val registration = repository.startUnifiedPush()

        val failure = try {
            repository.acceptEndpoint(registration.generation, ENDPOINT_URL, keys)
            null
        } catch (e: GeneralSecurityException) {
            e
        }

        assertNotNull(failure, "A failed protected write has to be reported, not swallowed")
        // No ordinary value may suggest that an endpoint with keys was stored.
        assertNull((repository.current().desired as? DesiredCloudPushTransport.UnifiedPush)?.endpoint)
        assertNull(storage.storedValues[PREF_ENDPOINT_URL])
        assertNull(storage.storedValues[PREF_ENDPOINT_KEYS_PRESENT])
    }

    @Test
    fun `Given a stale generation when its endpoint is accepted then the keys of the current one survive`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)
        val staleGeneration = registrationRepository.current().generation
        manager.enable(DISTRIBUTOR)
        manager.onNewEndpoint(endpoint(temporary = false, keys = keys), currentInstance())
        val current = storedSnapshot()

        // A callback of the replaced generation that reached the storage after the current one.
        // Writing its keys first and only then noticing it is stale would leave the snapshot that is
        // current pointing at a record it no longer matches.
        val accepted = registrationRepository.acceptEndpoint(staleGeneration, RENEWED_ENDPOINT_URL, renewedKeys)

        assertNull(accepted)
        assertEquals(current, desiredEndpoint())
        assertEquals(keys, desiredKeys())
    }

    @Test
    fun `Given a stored snapshot then no key material is in ordinary storage`() = runTest {
        val manager = manager()
        manager.enable(DISTRIBUTOR)

        manager.onNewEndpoint(endpoint(temporary = false, keys = keys), currentInstance())

        // Only the marker and the reference may be ordinary values, never the secret itself.
        assertEquals("true", storage.storedValues[PREF_ENDPOINT_KEYS_PRESENT])
        assertNotNull(storage.storedValues[PREF_ENDPOINT_SNAPSHOT_ID])
        val ordinary = storage.storedValues.values.map { it.toString() }
        assertFalse(ordinary.any { it.contains(keys.auth) }, "The auth secret is in ordinary storage")
        assertFalse(ordinary.any { it.contains(keys.p256dh) }, "The public key is in ordinary storage")
    }
}
