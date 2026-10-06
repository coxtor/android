package io.homeassistant.companion.android.notifications.push

import android.content.Context
import androidx.annotation.GuardedBy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.homeassistant.companion.android.common.LocalStorageImpl
import io.homeassistant.companion.android.common.data.LocalStorage
import io.homeassistant.companion.android.common.data.integration.WebPushKeyRecord
import io.homeassistant.companion.android.common.data.integration.WebPushKeyStorage
import io.homeassistant.companion.android.common.data.integration.WebPushKeys
import io.homeassistant.companion.android.common.util.getSharedPreferencesSuspend
import java.util.UUID
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Every value of the desired state is stored as a string so that all of them fit into the one
// transaction of LocalStorage.putStrings. They describe a single registration, so a reader or a
// process death may never see a generation next to the endpoint of the previous one.
private const val PREF_GENERATION = "desired_generation"
private const val PREF_UNIFIED_PUSH_DESIRED = "desired_unifiedpush"
private const val PREF_ENDPOINT_URL = "endpoint_url"
private const val PREF_ENDPOINT_SNAPSHOT_ID = "endpoint_snapshot_id"
private const val PREF_ENDPOINT_KEYS_PRESENT = "endpoint_keys_present"

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
    private val webPushKeyStorage: WebPushKeyStorage,
) {

    /** Serializes the read-modify-write of the desired state. Never held across network calls. */
    private val mutex = Mutex()

    /**
     * Serializes the cloud push syncs, see [withSyncLock]. Separate from [mutex] so that accepting a
     * callback is never blocked behind a request to a server.
     */
    private val syncMutex = Mutex()

    /**
     * The registration the user wants right now, which every callback is checked against.
     *
     * Under [mutex] because it reads five values and every read suspends. A write applies all of
     * them at once, but without the lock a renewal could still land between two of these reads and
     * hand out an endpoint URL next to the snapshot id of the one that replaced it.
     */
    suspend fun current(): CloudPushRegistration = mutex.withLock { currentUnderLock() }

    /**
     * Runs [block] as the only cloud push sync.
     *
     * Two syncs would otherwise interleave their requests and the older one could be the last to
     * reach a server. Each of them rereads the desired state once it holds this, so the one that
     * runs second is also the one that decides.
     *
     * This is taken before the lock that serializes a registration update, never the other way
     * round, and it is the only lock held while talking to a server.
     */
    suspend fun <T> withSyncLock(block: suspend () -> T): T = syncMutex.withLock { block() }

    /** Starts a new generation that wants UnifiedPush, which invalidates everything older. */
    suspend fun startUnifiedPush(): CloudPushRegistration = start(unifiedPushDesired = true)

    /** Starts a new generation that wants Firebase, which invalidates everything older. */
    suspend fun startFirebase(): CloudPushRegistration = start(unifiedPushDesired = false)

    /**
     * Stores [url] and [keys] of [generation] as one new snapshot, replacing whatever was stored
     * before it. The keys of the previous snapshot are never carried over, so an endpoint that comes
     * without keys really has none.
     *
     * @return the accepted snapshot, or `null` when [generation] is no longer the current one, in
     * which case nothing is written.
     */
    suspend fun acceptEndpoint(generation: Int, url: String, keys: WebPushKeys?): EndpointSnapshot? = mutex.withLock {
        // Checked before anything is written, so a callback of a replaced registration cannot touch
        // the keys of the snapshot that is current.
        if (storedGeneration() != generation) return@withLock null

        val snapshotId = UUID.randomUUID().toString()
        if (keys != null) {
            // Written before the state that points at it. A process death in between leaves a
            // record that no state references and that no read can return, while the other order
            // would leave a state promising keys that are not there.
            webPushKeyStorage.put(WebPushKeyRecord.DESIRED, snapshotId, url, keys)
        } else {
            // This snapshot supersedes the previous one, so its keys are not wanted any more.
            webPushKeyStorage.clear(WebPushKeyRecord.DESIRED)
        }
        localStorage.putStrings(
            mapOf(
                PREF_ENDPOINT_URL to url,
                PREF_ENDPOINT_SNAPSHOT_ID to snapshotId,
                PREF_ENDPOINT_KEYS_PRESENT to (keys != null).toString(),
            ),
        )
        EndpointSnapshot(url = url, snapshotId = snapshotId, keysPresent = keys != null)
    }

    /**
     * The keys of [snapshot], or `null` while they are not available.
     *
     * Not available covers a snapshot that never had keys, one stored before the app kept them, and
     * one whose record no longer matches. The caller cannot tell those apart here, which is
     * deliberate: in all three cases there is nothing to send.
     */
    suspend fun endpointKeys(snapshot: EndpointSnapshot): WebPushKeys? {
        val snapshotId = snapshot.snapshotId?.takeIf { snapshot.keysPresent } ?: return null
        return webPushKeyStorage.get(WebPushKeyRecord.DESIRED, snapshotId, snapshot.url)
    }

    private suspend fun start(unifiedPushDesired: Boolean): CloudPushRegistration = mutex.withLock {
        val generation = storedGeneration() + 1
        localStorage.putStrings(
            mapOf(
                PREF_GENERATION to generation.toString(),
                PREF_UNIFIED_PUSH_DESIRED to unifiedPushDesired.toString(),
                // An endpoint always belongs to one generation, so a new one starts without it.
                PREF_ENDPOINT_URL to null,
                PREF_ENDPOINT_SNAPSHOT_ID to null,
                PREF_ENDPOINT_KEYS_PRESENT to null,
            ),
        )
        webPushKeyStorage.clear(WebPushKeyRecord.DESIRED)
        currentUnderLock()
    }

    /**
     * Reads the desired state without taking [mutex], for the callers that already hold it. The
     * lock is not reentrant, so [current] cannot be used from under it.
     */
    @GuardedBy("mutex")
    private suspend fun currentUnderLock(): CloudPushRegistration {
        val generation = storedGeneration()
        val desired = if (localStorage.getString(PREF_UNIFIED_PUSH_DESIRED).toBoolean()) {
            DesiredCloudPushTransport.UnifiedPush(storedEndpoint())
        } else {
            DesiredCloudPushTransport.Firebase
        }
        return CloudPushRegistration(generation, desired)
    }

    private suspend fun storedGeneration(): Int = localStorage.getString(PREF_GENERATION)?.toIntOrNull() ?: 0

    private suspend fun storedEndpoint(): EndpointSnapshot? {
        val url = localStorage.getString(PREF_ENDPOINT_URL) ?: return null
        return EndpointSnapshot(
            url = url,
            snapshotId = localStorage.getString(PREF_ENDPOINT_SNAPSHOT_ID),
            keysPresent = localStorage.getString(PREF_ENDPOINT_KEYS_PRESENT).toBoolean(),
        )
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
