package io.homeassistant.companion.android.notifications.push

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.homeassistant.companion.android.common.LocalStorageImpl
import io.homeassistant.companion.android.common.data.LocalStorage
import io.homeassistant.companion.android.common.util.getSharedPreferencesSuspend
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val PREF_GENERATION = "generation"
private const val PREF_UNIFIED_PUSH_DESIRED = "unifiedpush_desired"
private const val PREF_ENDPOINT_URL = "endpoint_url"

/** Storage of the desired cloud push registration, kept apart from the server registration. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class CloudPushStorage

/**
 * Single source of truth for which cloud push registration the app wants.
 *
 * It is persisted because a distributor callback or a worker can arrive after a process restart and
 * still has to be recognized as belonging to an older registration.
 */
@Singleton
class CloudPushRegistrationRepository @Inject constructor(
    @param:CloudPushStorage private val localStorage: LocalStorage,
) {

    /** Serializes the read-modify-write of the generation. */
    private val mutex = Mutex()

    /** The registration the user wants right now, which every callback is checked against. */
    suspend fun current(): CloudPushRegistration {
        val generation = localStorage.getInt(PREF_GENERATION) ?: 0
        val desired = if (localStorage.getBoolean(PREF_UNIFIED_PUSH_DESIRED)) {
            DesiredCloudPushTransport.UnifiedPush(localStorage.getString(PREF_ENDPOINT_URL))
        } else {
            DesiredCloudPushTransport.Firebase
        }
        return CloudPushRegistration(generation, desired)
    }

    /** Starts a new generation that wants UnifiedPush, which invalidates everything older. */
    suspend fun startUnifiedPush(): CloudPushRegistration = start(unifiedPushDesired = true)

    /** Starts a new generation that wants Firebase, which invalidates everything older. */
    suspend fun startFirebase(): CloudPushRegistration = start(unifiedPushDesired = false)

    /**
     * Stores the endpoint of [generation].
     *
     * @return `false` when [generation] is no longer the current one, in which case nothing is
     * written.
     */
    suspend fun setEndpoint(generation: Int, endpointUrl: String): Boolean = mutex.withLock {
        if ((localStorage.getInt(PREF_GENERATION) ?: 0) != generation) return@withLock false
        localStorage.putString(PREF_ENDPOINT_URL, endpointUrl)
        true
    }

    private suspend fun start(unifiedPushDesired: Boolean): CloudPushRegistration = mutex.withLock {
        val generation = (localStorage.getInt(PREF_GENERATION) ?: 0) + 1
        localStorage.putInt(PREF_GENERATION, generation)
        localStorage.putBoolean(PREF_UNIFIED_PUSH_DESIRED, unifiedPushDesired)
        // An endpoint always belongs to one generation, so a new one starts without it.
        localStorage.remove(PREF_ENDPOINT_URL)
        current()
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object CloudPushStorageModule {

    @Provides
    @Singleton
    @CloudPushStorage
    fun providesCloudPushStorage(@ApplicationContext context: Context): LocalStorage = LocalStorageImpl {
        context.getSharedPreferencesSuspend("cloud_push_0")
    }
}
