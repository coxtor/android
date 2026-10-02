package io.homeassistant.companion.android.notifications.push

import android.content.Context
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
 * Only the generation is passed in, never the endpoint itself: the endpoint is a capability that
 * belongs in app storage, and a worker that waited or retried has to work with the registration the
 * user wants now rather than the one it was started for.
 */
class CloudPushSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context.applicationContext, params) {

    companion object {
        private const val UNIQUE_WORK_NAME = "CloudPushSync"
        private const val KEY_GENERATION = "generation"
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

            @PushWebsocketSupport
            fun pushWebsocketSupport(): Boolean
        }

        /**
         * Registers what generation [generation] of [CloudPushRegistrationRepository] asks for.
         * Replaces a pending sync because only the newest generation is wanted.
         */
        fun WorkManager.enqueueCloudPushSync(generation: Int) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build()

            val worker = OneTimeWorkRequestBuilder<CloudPushSyncWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_GENERATION to generation))
                .build()

            enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, worker)
        }
    }

    override suspend fun doWork(): Result {
        return coroutineScope {
            val entryPoints = EntryPoints.get(applicationContext, CloudPushSyncWorkerEntryPoint::class.java)
            val repository = entryPoints.cloudPushRegistrationRepository()
            val generation = inputData.getInt(KEY_GENERATION, NO_GENERATION)

            val registration = repository.current()
            if (registration.generation != generation) {
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
            ) ?: return@coroutineScope Result.success()

            // Reading the servers and the stored registration suspends, so the user may have made
            // another choice in the meantime.
            if (repository.current().generation != generation) {
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
    }

    /**
     * The registration sent to every server, or `null` when there is nothing to register yet. It is
     * built once so that a newly created token is the same everywhere.
     */
    private suspend fun buildRegistration(
        desired: DesiredCloudPushTransport,
        storedRegistration: DeviceRegistration,
        entryPoints: CloudPushSyncWorkerEntryPoint,
    ): DeviceRegistration? = when (desired) {
        DesiredCloudPushTransport.Firebase -> DeviceRegistration(
            pushToken = entryPoints.messagingTokenProvider()(),
            pushWebsocket = entryPoints.pushWebsocketSupport(),
            cloudPush = CloudPushTransport.Firebase,
        )
        is DesiredCloudPushTransport.UnifiedPush -> desired.endpointUrl?.let { endpointUrl ->
            DeviceRegistration(
                pushToken = endpointToken(storedRegistration, entryPoints.opaquePushTokenProvider()),
                pushWebsocket = entryPoints.pushWebsocketSupport(),
                cloudPush = CloudPushTransport.Endpoint(endpointUrl),
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
