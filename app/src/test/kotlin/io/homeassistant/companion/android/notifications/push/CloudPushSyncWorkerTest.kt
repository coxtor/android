package io.homeassistant.companion.android.notifications.push

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoints
import io.homeassistant.companion.android.common.data.integration.CloudPushTransport
import io.homeassistant.companion.android.common.data.integration.DeviceRegistration
import io.homeassistant.companion.android.common.data.integration.IntegrationRepository
import io.homeassistant.companion.android.common.data.integration.WebPushKeyRecord
import io.homeassistant.companion.android.common.data.integration.WebPushKeys
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.util.MessagingToken
import io.homeassistant.companion.android.common.util.MessagingTokenProvider
import io.homeassistant.companion.android.database.server.Server
import io.homeassistant.companion.android.notifications.push.CloudPushSyncWorker.Companion.CloudPushSyncWorkerEntryPoint
import io.homeassistant.companion.android.notifications.push.CloudPushSyncWorker.Companion.enqueueCloudPushSync
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val ENDPOINT_URL = "https://push.example.com/up1234?up=1"
private const val OTHER_ENDPOINT_URL = "https://push.example.com/up5678?up=1"
private const val KEY_GENERATION = "generation"
private const val KEY_SNAPSHOT_ID = "snapshot_id"
private const val WORK_REGISTER_RETRY = "UnifiedPushRegister"
private val messagingToken = MessagingToken("fcm-token")
private val opaqueToken = MessagingToken("opaque-token")
private val keys = WebPushKeys(p256dh = "BNcRd-public-key", auth = "YXV0aC1zZWNyZXQ")
private val renewedKeys = WebPushKeys(p256dh = "BRnWd-public-key", auth = "cmVuZXdlZC1hdXRo")

class CloudPushSyncWorkerTest {

    private val context: Context = mockk()
    private val serverManager: ServerManager = mockk()
    private val integrationRepository: IntegrationRepository = mockk(relaxed = true)
    private val workManager: WorkManager = mockk(relaxed = true)

    /** Unique work names in the order they were enqueued. */
    private val enqueuedWork = mutableListOf<String>()

    // The real repository on in-memory storage, so generations and snapshots behave as in
    // production and the keys are resolved by the production matching rule.
    private val storage = InMemoryLocalStorage()
    private val keyStorage = InMemoryWebPushKeyStorage()
    private val registrationRepository = CloudPushRegistrationRepository(storage, keyStorage)

    /** What identifies one registration, which is all a worker is handed. */
    private data class WorkIdentity(val generation: Int, val snapshotId: String?)

    @BeforeEach
    fun setUp() {
        every { context.applicationContext } returns context
        every {
            workManager.enqueueUniqueWork(capture(enqueuedWork), any(), any<OneTimeWorkRequest>())
        } returns mockk(relaxed = true)
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
            every { workManager() } returns workManager
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

    /** Makes UnifiedPush the wanted transport and returns what identifies its registration. */
    private suspend fun wantUnifiedPush(endpointUrl: String?, keys: WebPushKeys? = null): WorkIdentity {
        val registration = registrationRepository.startUnifiedPush()
        val snapshot = endpointUrl?.let {
            registrationRepository.acceptEndpoint(registration.generation, it, keys)
        }
        return WorkIdentity(registration.generation, snapshot?.snapshotId)
    }

    /** Accepts another endpoint inside the same generation, which is what a renewal does. */
    private suspend fun renewEndpoint(
        identity: WorkIdentity,
        endpointUrl: String,
        keys: WebPushKeys? = null,
    ): WorkIdentity {
        val snapshot = checkNotNull(
            registrationRepository.acceptEndpoint(identity.generation, endpointUrl, keys),
        )
        return WorkIdentity(identity.generation, snapshot.snapshotId)
    }

    private suspend fun wantFirebase(): WorkIdentity = WorkIdentity(registrationRepository.startFirebase().generation, snapshotId = null)

    private fun worker(identity: WorkIdentity): CloudPushSyncWorker {
        val params: WorkerParameters = mockk(relaxed = true)
        every { params.inputData } returns workDataOf(
            KEY_GENERATION to identity.generation,
            KEY_SNAPSHOT_ID to identity.snapshotId,
        )
        return CloudPushSyncWorker(context, params)
    }

    private suspend fun sentRegistration(identity: WorkIdentity): DeviceRegistration {
        val registration = slot<DeviceRegistration>()
        worker(identity).doWork()
        coVerify { integrationRepository.updateRegistration(capture(registration)) }
        return registration.captured
    }

    /** Records every registration that reached a server, in the order they arrived. */
    private fun recordRegistrations(): MutableList<DeviceRegistration> {
        val registered = mutableListOf<DeviceRegistration>()
        coEvery { integrationRepository.updateRegistration(any()) } coAnswers {
            registered += firstArg<DeviceRegistration>()
        }
        return registered
    }

    @Test
    fun `Given an endpoint is wanted when syncing then a new opaque token is used`() = runTest {
        val identity = wantUnifiedPush(ENDPOINT_URL)

        val sent = sentRegistration(identity)

        assertEquals(CloudPushTransport.Endpoint(ENDPOINT_URL), sent.cloudPush)
        assertEquals(opaqueToken, sent.pushToken)
    }

    @Test
    fun `Given an endpoint with keys is wanted when syncing then the whole snapshot is sent`() = runTest {
        val identity = wantUnifiedPush(ENDPOINT_URL, keys)

        val sent = sentRegistration(identity)

        assertEquals(CloudPushTransport.Endpoint(ENDPOINT_URL, keys), sent.cloudPush)
        assertEquals(opaqueToken, sent.pushToken)
    }

    @Test
    fun `Given a keyless endpoint is wanted when syncing then no keys are sent`() = runTest {
        val identity = wantUnifiedPush(ENDPOINT_URL)

        assertEquals(CloudPushTransport.Endpoint(ENDPOINT_URL, null), sentRegistration(identity).cloudPush)
    }

    @Test
    fun `Given the keys of the wanted endpoint are gone when syncing then nothing is registered`() = runTest {
        val identity = wantUnifiedPush(ENDPOINT_URL, keys)
        // The state still promises keys, but protected storage lost them.
        keyStorage.clear(WebPushKeyRecord.DESIRED)

        val result = worker(identity).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        // Registering this endpoint without its keys would hand the server a subscription that a
        // sender cannot address, so a new snapshot is asked for instead.
        coVerify(exactly = 0) { integrationRepository.updateRegistration(any()) }
        assertEquals(listOf(WORK_REGISTER_RETRY), enqueuedWork)
    }

    @Test
    fun `Given an endpoint is registered when its URL is renewed then its token is kept`() = runTest {
        storedRegistration(
            DeviceRegistration(
                pushToken = MessagingToken("already-registered"),
                cloudPush = CloudPushTransport.Endpoint(OTHER_ENDPOINT_URL),
            ),
        )
        val identity = wantUnifiedPush(ENDPOINT_URL)

        val sent = sentRegistration(identity)

        assertEquals(CloudPushTransport.Endpoint(ENDPOINT_URL), sent.cloudPush)
        assertEquals(MessagingToken("already-registered"), sent.pushToken)
    }

    @Test
    fun `Given an endpoint is registered when only its keys are renewed then its token is kept`() = runTest {
        storedRegistration(
            DeviceRegistration(
                pushToken = MessagingToken("already-registered"),
                cloudPush = CloudPushTransport.Endpoint(ENDPOINT_URL, keys),
            ),
        )
        val identity = wantUnifiedPush(ENDPOINT_URL, renewedKeys)

        val sent = sentRegistration(identity)

        // Renewing the keys is still the same registration, so only the keys change.
        assertEquals(CloudPushTransport.Endpoint(ENDPOINT_URL, renewedKeys), sent.cloudPush)
        assertEquals(MessagingToken("already-registered"), sent.pushToken)
    }

    @Test
    fun `Given Firebase is wanted when syncing then the messaging token is used`() = runTest {
        storedRegistration(
            DeviceRegistration(pushToken = opaqueToken, cloudPush = CloudPushTransport.Endpoint(ENDPOINT_URL)),
        )
        val identity = wantFirebase()

        val sent = sentRegistration(identity)

        assertEquals(CloudPushTransport.Firebase, sent.cloudPush)
        assertEquals(messagingToken, sent.pushToken)
    }

    @Test
    fun `When syncing then the WebSocket channel support is sent unchanged`() = runTest {
        val identity = wantUnifiedPush(ENDPOINT_URL)

        assertEquals(true, sentRegistration(identity).pushWebsocket)
    }

    @Test
    fun `Given no endpoint arrived yet when syncing then nothing is registered`() = runTest {
        val identity = wantUnifiedPush(endpointUrl = null)

        val result = worker(identity).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { integrationRepository.updateRegistration(any()) }
    }

    @Test
    fun `Given several servers when syncing then all of them get the same snapshot`() = runTest {
        servers(1, 2, 3)
        val registered = recordRegistrations()
        val identity = wantUnifiedPush(ENDPOINT_URL, keys)

        val result = worker(identity).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(3, registered.size)
        // One registration is built for all of them, so the endpoint, the token and the keys are
        // the same everywhere.
        assertEquals(1, registered.map { it.cloudPush }.toSet().size)
        assertEquals(CloudPushTransport.Endpoint(ENDPOINT_URL, keys), registered.first().cloudPush)
        assertEquals(1, registered.map { it.pushToken }.toSet().size)
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
        val registered = recordRegistrations()
        val identity = wantUnifiedPush(ENDPOINT_URL, keys)

        val result = worker(identity).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        // The server that could be reached is still updated, with the complete snapshot.
        assertEquals(1, registered.size)
        assertEquals(CloudPushTransport.Endpoint(ENDPOINT_URL, keys), registered.single().cloudPush)
    }

    @Test
    fun `Given no server is registered when syncing then nothing is registered`() = runTest {
        coEvery { serverManager.isRegistered() } returns false
        val identity = wantUnifiedPush(ENDPOINT_URL)

        val result = worker(identity).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { integrationRepository.updateRegistration(any()) }
    }

    @Test
    fun `Given a newer endpoint is wanted when the older sync runs then it registers nothing`() = runTest {
        val stale = wantUnifiedPush(ENDPOINT_URL)
        wantUnifiedPush(OTHER_ENDPOINT_URL)

        val result = worker(stale).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { integrationRepository.updateRegistration(any()) }
    }

    @Test
    fun `Given a newer snapshot of the same generation when the older sync runs then it registers nothing`() = runTest {
        val stale = wantUnifiedPush(ENDPOINT_URL, keys)
        // A renewal does not change the generation, so only the snapshot tells the two apart.
        val current = renewEndpoint(stale, ENDPOINT_URL, renewedKeys)
        assertEquals(stale.generation, current.generation)
        assertNotEquals(stale.snapshotId, current.snapshotId)

        val result = worker(stale).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { integrationRepository.updateRegistration(any()) }
    }

    @Test
    fun `Given a newer endpoint is wanted when the older sync is retried then it registers nothing`() = runTest {
        servers(1, 2)
        val failing: IntegrationRepository = mockk(relaxed = true)
        coEvery { failing.updateRegistration(any()) } throws IllegalStateException()
        coEvery { serverManager.integrationRepository(2) } returns failing
        val stale = wantUnifiedPush(ENDPOINT_URL)
        // The first attempt only reaches one server and asks to be retried.
        assertEquals(ListenableWorker.Result.retry(), worker(stale).doWork())

        // A newer endpoint arrives before the retry runs.
        wantUnifiedPush(OTHER_ENDPOINT_URL)
        val retry = worker(stale).doWork()

        assertEquals(ListenableWorker.Result.success(), retry)
        // Only the single write of the first attempt happened, the retry wrote nothing.
        coVerify(exactly = 1) { integrationRepository.updateRegistration(any()) }
    }

    @Test
    fun `Given Firebase became the wanted transport when an older endpoint sync runs then it registers nothing`() = runTest {
        val stale = wantUnifiedPush(ENDPOINT_URL)
        wantFirebase()

        val result = worker(stale).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { integrationRepository.updateRegistration(any()) }
    }

    @Test
    fun `Given a sync is in flight when a newer one starts then it waits and then decides`() = runTest {
        val registered = mutableListOf<DeviceRegistration>()
        val firstRequestStarted = CompletableDeferred<Unit>()
        val releaseFirstRequest = CompletableDeferred<Unit>()
        coEvery { integrationRepository.updateRegistration(any()) } coAnswers {
            registered += firstArg<DeviceRegistration>()
            if (registered.size == 1) {
                firstRequestStarted.complete(Unit)
                releaseFirstRequest.await()
            }
        }
        val first = wantUnifiedPush(ENDPOINT_URL, keys)

        val firstSync = launch { worker(first).doWork() }
        firstRequestStarted.await()

        // The next snapshot is accepted and scheduled while the first request is still in flight.
        val second = renewEndpoint(first, OTHER_ENDPOINT_URL, renewedKeys)
        val secondSync = launch { worker(second).doWork() }
        advanceUntilIdle()
        assertEquals(1, registered.size, "The second sync ran while the first still held the lock")

        releaseFirstRequest.complete(Unit)
        firstSync.join()
        secondSync.join()

        // The second one follows rather than interleaving, and is the one the server ends up with.
        assertEquals(
            listOf(
                CloudPushTransport.Endpoint(ENDPOINT_URL, keys),
                CloudPushTransport.Endpoint(OTHER_ENDPOINT_URL, renewedKeys),
            ),
            registered.map { it.cloudPush },
        )
    }

    @Test
    fun `Given the newer sync already finished when the older one runs then it sends nothing`() = runTest {
        val registered = recordRegistrations()
        val first = wantUnifiedPush(ENDPOINT_URL, keys)
        val second = renewEndpoint(first, OTHER_ENDPOINT_URL, renewedKeys)

        // The newer snapshot is registered first, for example because the older work was delayed.
        assertEquals(ListenableWorker.Result.success(), worker(second).doWork())
        // Only now does the older work get its turn.
        assertEquals(ListenableWorker.Result.success(), worker(first).doWork())

        // The older one must not undo the newer one.
        assertEquals(listOf(CloudPushTransport.Endpoint(OTHER_ENDPOINT_URL, renewedKeys)), registered.map { it.cloudPush })
    }

    @Test
    fun `Given two snapshots of one generation then each gets work of its own`() = runTest {
        val first = wantUnifiedPush(ENDPOINT_URL, keys)
        val second = renewEndpoint(first, ENDPOINT_URL, renewedKeys)

        val firstName = CloudPushSyncWorker.uniqueWorkName(first.generation, first.snapshotId)
        val secondName = CloudPushSyncWorker.uniqueWorkName(second.generation, second.snapshotId)

        // Different work means the newer snapshot does not have to wait behind the retry backoff of
        // the older one, which one shared work name would force it to.
        assertNotEquals(firstName, secondName)
        assertTrue(firstName.startsWith("CloudPushSync-${first.generation}-"))
    }

    @Test
    fun `Given Firebase when its sync is enqueued then the name carries only the generation`() {
        assertEquals("CloudPushSync-7", CloudPushSyncWorker.uniqueWorkName(7, snapshotId = null))
    }

    @Test
    fun `When a sync is enqueued then its payload carries only the identity of the registration`() {
        val name = slot<String>()
        val policy = slot<ExistingWorkPolicy>()
        val request = slot<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(capture(name), capture(policy), capture(request))
        } returns mockk(relaxed = true)

        workManager.enqueueCloudPushSync(generation = 7, snapshotId = "snapshot-1")

        assertEquals("CloudPushSync-7-snapshot-1", name.captured)
        // An already queued run of the same snapshot is kept: replacing it would cancel a request
        // that is already on its way for no gain, since every run rereads the current state anyway.
        assertEquals(ExistingWorkPolicy.KEEP, policy.captured)
        // Nothing but the identity travels through WorkManager. The endpoint, the keys and the
        // token are read from storage by the worker, so delayed work cannot register stale values.
        assertEquals(
            mapOf(KEY_GENERATION to 7, KEY_SNAPSHOT_ID to "snapshot-1"),
            request.captured.workSpec.input.keyValueMap,
        )
    }
}
