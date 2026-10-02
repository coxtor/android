package io.homeassistant.companion.android.notifications.push

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoints
import io.homeassistant.companion.android.common.data.integration.CloudPushTransport
import io.homeassistant.companion.android.common.data.integration.DeviceRegistration
import io.homeassistant.companion.android.common.data.integration.IntegrationRepository
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.util.MessagingToken
import io.homeassistant.companion.android.common.util.MessagingTokenProvider
import io.homeassistant.companion.android.database.server.Server
import io.homeassistant.companion.android.notifications.push.CloudPushSyncWorker.Companion.CloudPushSyncWorkerEntryPoint
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val ENDPOINT_URL = "https://push.example.com/up1234?up=1"
private const val OTHER_ENDPOINT_URL = "https://push.example.com/up5678?up=1"
private const val KEY_GENERATION = "generation"
private val messagingToken = MessagingToken("fcm-token")
private val opaqueToken = MessagingToken("opaque-token")

class CloudPushSyncWorkerTest {

    private val context: Context = mockk()
    private val serverManager: ServerManager = mockk()
    private val integrationRepository: IntegrationRepository = mockk(relaxed = true)

    // The real repository on an in-memory storage, so generations behave as in production.
    private val storage = InMemoryLocalStorage()
    private val registrationRepository = CloudPushRegistrationRepository(storage)

    @BeforeEach
    fun setUp() {
        every { context.applicationContext } returns context
        coEvery { serverManager.isRegistered() } returns true
        coEvery { serverManager.integrationRepository(any()) } returns integrationRepository
        storedRegistration(DeviceRegistration(cloudPush = CloudPushTransport.Firebase))
        servers(1)

        mockkStatic(EntryPoints::class)
        every {
            EntryPoints.get(any(), CloudPushSyncWorkerEntryPoint::class.java)
        } returns mockk(relaxed = true) {
            every { serverManager() } returns serverManager
            every { messagingTokenProvider() } returns MessagingTokenProvider { messagingToken }
            every { opaquePushTokenProvider() } returns OpaquePushTokenProvider { opaqueToken }
            every { cloudPushRegistrationRepository() } returns registrationRepository
            every { pushWebsocketSupport() } returns true
        }
    }

    private fun servers(vararg ids: Int) {
        coEvery { serverManager.servers() } returns ids.map { id ->
            mockk<Server>(relaxed = true).apply { every { this@apply.id } returns id }
        }
    }

    private fun storedRegistration(registration: DeviceRegistration) {
        coEvery { integrationRepository.getRegistration() } returns registration
    }

    /** Makes UnifiedPush the wanted transport and returns its generation. */
    private suspend fun wantUnifiedPush(endpointUrl: String?): Int {
        val registration = registrationRepository.startUnifiedPush()
        if (endpointUrl != null) {
            registrationRepository.setEndpoint(registration.generation, endpointUrl)
        }
        return registration.generation
    }

    private suspend fun wantFirebase(): Int = registrationRepository.startFirebase().generation

    private fun worker(generation: Int): CloudPushSyncWorker {
        val params: WorkerParameters = mockk(relaxed = true)
        every { params.inputData } returns workDataOf(KEY_GENERATION to generation)
        return CloudPushSyncWorker(context, params)
    }

    private suspend fun sentRegistration(generation: Int): DeviceRegistration {
        val registration = slot<DeviceRegistration>()
        worker(generation).doWork()
        coVerify { integrationRepository.updateRegistration(capture(registration)) }
        return registration.captured
    }

    @Test
    fun `Given an endpoint is wanted when syncing then a new opaque token is used`() = runTest {
        val generation = wantUnifiedPush(ENDPOINT_URL)

        val sent = sentRegistration(generation)

        assertEquals(CloudPushTransport.Endpoint(ENDPOINT_URL), sent.cloudPush)
        assertEquals(opaqueToken, sent.pushToken)
    }

    @Test
    fun `Given an endpoint is registered when its URL is renewed then its token is kept`() = runTest {
        storedRegistration(
            DeviceRegistration(
                pushToken = MessagingToken("already-registered"),
                cloudPush = CloudPushTransport.Endpoint(OTHER_ENDPOINT_URL),
            ),
        )
        val generation = wantUnifiedPush(ENDPOINT_URL)

        val sent = sentRegistration(generation)

        assertEquals(CloudPushTransport.Endpoint(ENDPOINT_URL), sent.cloudPush)
        assertEquals(MessagingToken("already-registered"), sent.pushToken)
    }

    @Test
    fun `Given Firebase is wanted when syncing then the messaging token is used`() = runTest {
        storedRegistration(
            DeviceRegistration(pushToken = opaqueToken, cloudPush = CloudPushTransport.Endpoint(ENDPOINT_URL)),
        )
        val generation = wantFirebase()

        val sent = sentRegistration(generation)

        assertEquals(CloudPushTransport.Firebase, sent.cloudPush)
        assertEquals(messagingToken, sent.pushToken)
    }

    @Test
    fun `When syncing then the WebSocket channel support is sent unchanged`() = runTest {
        val generation = wantUnifiedPush(ENDPOINT_URL)

        assertEquals(true, sentRegistration(generation).pushWebsocket)
    }

    @Test
    fun `Given no endpoint arrived yet when syncing then nothing is registered`() = runTest {
        val generation = wantUnifiedPush(endpointUrl = null)

        val result = worker(generation).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { integrationRepository.updateRegistration(any()) }
    }

    @Test
    fun `Given several servers when syncing then all of them are updated`() = runTest {
        servers(1, 2, 3)
        val generation = wantUnifiedPush(ENDPOINT_URL)

        val result = worker(generation).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 3) { integrationRepository.updateRegistration(any()) }
        coVerify {
            serverManager.integrationRepository(1)
            serverManager.integrationRepository(2)
            serverManager.integrationRepository(3)
        }
    }

    @Test
    fun `Given one server cannot be reached when syncing then the work is retried`() = runTest {
        servers(1, 2)
        val failing: IntegrationRepository = mockk(relaxed = true)
        coEvery { failing.updateRegistration(any()) } throws IllegalStateException()
        coEvery { serverManager.integrationRepository(2) } returns failing
        val generation = wantUnifiedPush(ENDPOINT_URL)

        val result = worker(generation).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        // The server that could be reached is still updated.
        coVerify(exactly = 1) { integrationRepository.updateRegistration(any()) }
    }

    @Test
    fun `Given no server is registered when syncing then nothing is registered`() = runTest {
        coEvery { serverManager.isRegistered() } returns false
        val generation = wantUnifiedPush(ENDPOINT_URL)

        val result = worker(generation).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { integrationRepository.updateRegistration(any()) }
    }

    @Test
    fun `Given a newer endpoint is wanted when the older sync runs then it registers nothing`() = runTest {
        val staleGeneration = wantUnifiedPush(ENDPOINT_URL)
        wantUnifiedPush(OTHER_ENDPOINT_URL)

        val result = worker(staleGeneration).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { integrationRepository.updateRegistration(any()) }
    }

    @Test
    fun `Given a newer endpoint is wanted when the older sync is retried then it registers nothing`() = runTest {
        servers(1, 2)
        val failing: IntegrationRepository = mockk(relaxed = true)
        coEvery { failing.updateRegistration(any()) } throws IllegalStateException()
        coEvery { serverManager.integrationRepository(2) } returns failing
        val staleGeneration = wantUnifiedPush(ENDPOINT_URL)
        // The first attempt only reaches one server and asks to be retried.
        assertEquals(ListenableWorker.Result.retry(), worker(staleGeneration).doWork())

        // A newer endpoint arrives before the retry runs.
        wantUnifiedPush(OTHER_ENDPOINT_URL)
        val retry = worker(staleGeneration).doWork()

        assertEquals(ListenableWorker.Result.success(), retry)
        // Only the single write of the first attempt happened, the retry wrote nothing.
        coVerify(exactly = 1) { integrationRepository.updateRegistration(any()) }
    }

    @Test
    fun `Given Firebase became the wanted transport when an older endpoint sync runs then it registers nothing`() = runTest {
        val staleGeneration = wantUnifiedPush(ENDPOINT_URL)
        wantFirebase()

        val result = worker(staleGeneration).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { integrationRepository.updateRegistration(any()) }
    }
}
