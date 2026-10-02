package io.homeassistant.companion.android.notifications.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import timber.log.Timber

/**
 * Asks the saved distributor to register again after a failure it reported as transient.
 *
 * It registers the generation the user wants when it runs, not the one it was started for, so a
 * retry that waited for a network cannot revive a registration that was replaced meanwhile.
 * WorkManager provides the backoff and the "wait for a network" behaviour, so no retry loop is
 * needed in [UnifiedPushManager].
 */
class UnifiedPushRegisterWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context.applicationContext, params) {

    companion object {
        private const val UNIQUE_WORK_NAME = "UnifiedPushRegister"

        /**
         * A bug in the AndroidX Hilt compiler that caused a StackOverflow in our codebase
         * tracked in https://github.com/google/dagger/issues/4702 forces us to use an entry point.
         */
        @EntryPoint
        @InstallIn(SingletonComponent::class)
        internal interface UnifiedPushRegisterWorkerEntryPoint {
            fun unifiedPushConnector(): UnifiedPushConnector
            fun cloudPushRegistrationRepository(): CloudPushRegistrationRepository
        }

        /**
         * @param requiresNetwork Wait for a connection, for a failure the distributor blamed on the
         * network.
         */
        fun WorkManager.enqueueUnifiedPushRegister(requiresNetwork: Boolean) {
            val builder = OneTimeWorkRequestBuilder<UnifiedPushRegisterWorker>()
            if (requiresNetwork) {
                builder.setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
            }
            enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, builder.build())
        }
    }

    override suspend fun doWork(): Result {
        val entryPoints = EntryPoints.get(applicationContext, UnifiedPushRegisterWorkerEntryPoint::class.java)
        val registration = entryPoints.cloudPushRegistrationRepository().current()
        if (registration.desired !is DesiredCloudPushTransport.UnifiedPush) {
            Timber.i("Skipping a distributor registration the user replaced")
            return Result.success()
        }
        // A worker never runs on the main thread, so the storage the connector reads is safe here.
        entryPoints.unifiedPushConnector().register(registration.instance)
        return Result.success()
    }
}
