package io.homeassistant.companion.android.notifications.push

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.homeassistant.companion.android.common.data.integration.CloudPushTransport
import io.homeassistant.companion.android.common.data.integration.DeviceRegistration
import io.homeassistant.companion.android.common.data.integration.PushWebsocketSupport
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.util.MessagingToken
import io.homeassistant.companion.android.common.util.MessagingTokenProvider
import io.homeassistant.companion.android.notifications.push.UnifiedPushRegisterWorker.Companion.enqueueUnifiedPushRegister
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Registers the cloud push transport the user wants with every server.
 *
 * This runs as a worker for two reasons: a push endpoint arrives in [UnifiedPushService], whose
 * lifetime ends with the event it was started for, and a server that cannot be reached has to be
 * retried later.
 *
 * Only the identity of the registration is passed in, never the endpoint or the keys themselves:
 * those are capabilities that belong in app storage, and a worker that waited or retried has to work
 * with the registration the user wants now rather than the one it was started for.
 */
class CloudPushSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context.applicationContext, params) {

    companion object {
        private const val UNIQUE_WORK_PREFIX = "CloudPushSync"
        private const val KEY_GENERATION = "generation"
        private const val KEY_SNAPSHOT_ID = "snapshot_id"
        private const val NO_GENERATION = -1

        /**
         * A bug in the AndroidX Hilt compiler that caused a StackOverflow in our codebase
         * tracked in https://github.com/google/dagger/issues/4702 forces us to use an entry point.
         */
        @EntryPoint
        @InstallIn(SingletonComponent::class)
        internal interface CloudPushSyncWorkerEntryPoint {
            fun serverManager(): ServerManager
            fun messagingTokenProvider(): MessagingTokenProvider
            fun opaquePushTokenProvider(): OpaquePushTokenProvider
            fun cloudPushRegistrationRepository(): CloudPushRegistrationRepository
            fun workManager(): WorkManager

            @PushWebsocketSupport
            fun pushWebsocketSupport(): Boolean
        }

        /**
         * Identifies the work of one registration.
         *
         * An endpoint and its keys are accepted as one snapshot, so a newly accepted snapshot is
         * different work from the one before it even when the generation and the URL did not change.
         * Firebase and a generation without an endpoint have no snapshot and keep the plain name.
         */
        @VisibleForTesting
        internal fun uniqueWorkName(generation: Int, snapshotId: String?): String = if (snapshotId == null) {
            "$UNIQUE_WORK_PREFIX-$generation"
        } else {
            "$UNIQUE_WORK_PREFIX-$generation-$snapshotId"
        }

        /**
         * Registers the snapshot [snapshotId] of generation [generation].
         *
         * Each snapshot gets work of its own and an already queued one is kept rather than replaced:
         * a newer snapshot therefore does not have to wait behind the retry backoff of an older one,
         * and the one that runs last still decides because every run rereads the current state.
         */
        fun WorkManager.enqueueCloudPushSync(generation: Int, snapshotId: String?) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build()

            val worker = OneTimeWorkRequestBuilder<CloudPushSyncWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_GENERATION to generation, KEY_SNAPSHOT_ID to snapshotId))
                .build()

            enqueueUniqueWork(uniqueWorkName(generation, snapshotId), ExistingWorkPolicy.KEEP, worker)
        }
    }

    override suspend fun doWork(): Result {
        val entryPoints = EntryPoints.get(applicationContext, CloudPushSyncWorkerEntryPoint::class.java)
        val repository = entryPoints.cloudPushRegistrationRepository()
        // Only one sync may talk to the servers at a time, otherwise two of them interleave their
        // requests and the older one can be the last to arrive. Taken before the lock that
        // serializes a registration update, never the other way round.
        return repository.withSyncLock { sync(entryPoints, repository) }
    }

    private suspend fun sync(
        entryPoints: CloudPushSyncWorkerEntryPoint,
        repository: CloudPushRegistrationRepository,
    ): Result = coroutineScope {
        val generation = inputData.getInt(KEY_GENERATION, NO_GENERATION)
        val snapshotId = inputData.getString(KEY_SNAPSHOT_ID)

        val registration = repository.current()
        if (!registration.describes(generation, snapshotId)) {
            Timber.i("Skipping a cloud push registration the user replaced")
            return@coroutineScope Result.success()
        }

        val serverManager = entryPoints.serverManager()
        if (!serverManager.isRegistered()) {
            Timber.i("No server registered, skipping cloud push registration")
            return@coroutineScope Result.success()
        }
        val servers = serverManager.servers()
        val referenceServer = servers.firstOrNull() ?: return@coroutineScope Result.success()
        val deviceRegistration = buildRegistration(
            desired = registration.desired,
            storedRegistration = serverManager.integrationRepository(referenceServer.id).getRegistration(),
            entryPoints = entryPoints,
            repository = repository,
        ) ?: return@coroutineScope Result.success()

        // Reading the servers and the stored registration suspends, so the user may have made
        // another choice in the meantime.
        if (!repository.current().describes(generation, snapshotId)) {
            Timber.i("Skipping a cloud push registration that was replaced while preparing it")
            return@coroutineScope Result.success()
        }

        var result = Result.success()
        servers.map { server ->
            launch {
                try {
                    serverManager.integrationRepository(server.id).updateRegistration(deviceRegistration)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to register cloud push transport for server ${server.id}")
                    result = Result.retry()
                }
            }
        }.joinAll()

        result
    }

    /**
     * The registration sent to every server, or `null` when there is nothing to register yet. It is
     * built once so that a newly created token is the same everywhere.
     */
    private suspend fun buildRegistration(
        desired: DesiredCloudPushTransport,
        storedRegistration: DeviceRegistration,
        entryPoints: CloudPushSyncWorkerEntryPoint,
        repository: CloudPushRegistrationRepository,
    ): DeviceRegistration? = when (desired) {
        DesiredCloudPushTransport.Firebase -> DeviceRegistration(
            pushToken = entryPoints.messagingTokenProvider()(),
            pushWebsocket = entryPoints.pushWebsocketSupport(),
            cloudPush = CloudPushTransport.Firebase,
        )
        is DesiredCloudPushTransport.UnifiedPush -> desired.endpoint?.let { snapshot ->
            val keys = repository.endpointKeys(snapshot)
            if (snapshot.keysPresent && keys == null) {
                // The state promises keys that are not there any more. Registering this endpoint
                // without them would hand the servers a subscription a sender cannot address, so a
                // new snapshot is asked for instead of registering a broken one.
                Timber.w("The keys of the wanted endpoint are gone, registering with the distributor again")
                entryPoints.workManager().enqueueUnifiedPushRegister(requiresNetwork = false)
                return@let null
            }
            DeviceRegistration(
                pushToken = endpointToken(storedRegistration, entryPoints.opaquePushTokenProvider()),
                pushWebsocket = entryPoints.pushWebsocketSupport(),
                cloudPush = CloudPushTransport.Endpoint(snapshot.url, keys),
            )
        }
    }

    /**
     * Keeps the token of an endpoint that is already registered, so renewing its URL stays the same
     * registration, and creates one for a new registration.
     */
    private fun endpointToken(
        storedRegistration: DeviceRegistration,
        opaquePushTokenProvider: OpaquePushTokenProvider,
    ): MessagingToken {
        val registeredToken = storedRegistration.pushToken?.takeIf {
            storedRegistration.cloudPush is CloudPushTransport.Endpoint && !it.isBlank()
        }
        return registeredToken ?: opaquePushTokenProvider()
    }
}

/**
 * Whether this is still the registration that work for [generation] and [snapshotId] was started
 * for.
 *
 * The snapshot has to match as well as the generation: a distributor can renew the keys of an
 * endpoint while its URL stays the same, and the generation does not change for that.
 */
private fun CloudPushRegistration.describes(generation: Int, snapshotId: String?): Boolean {
    if (this.generation != generation) return false
    val currentSnapshotId = (desired as? DesiredCloudPushTransport.UnifiedPush)?.endpoint?.snapshotId
    return currentSnapshotId == snapshotId
}
