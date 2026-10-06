package io.homeassistant.companion.android.notifications.push

import androidx.annotation.VisibleForTesting
import androidx.work.WorkManager
import io.homeassistant.companion.android.common.data.integration.CloudPushTransport
import io.homeassistant.companion.android.common.data.integration.WebPushKeys
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.frontend.permissions.FcmSupport
import io.homeassistant.companion.android.notifications.push.CloudPushSyncWorker.Companion.enqueueCloudPushSync
import io.homeassistant.companion.android.notifications.push.UnifiedPushRegisterWorker.Companion.enqueueUnifiedPushRegister
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.data.PushEndpoint
import timber.log.Timber

/**
 * Owns the UnifiedPush registration and the state the cloud push settings show.
 *
 * A distributor answers asynchronously, so every callback is checked against
 * [CloudPushRegistrationRepository]: only the generation the user currently wants may change
 * anything. Registering an endpoint with Home Assistant is handed to [CloudPushSyncWorker], because
 * the endpoint arrives in [UnifiedPushService] whose lifetime ends with that event. Retrying a
 * failed registration is handed to [UnifiedPushRegisterWorker].
 */
@Singleton
class UnifiedPushManager @VisibleForTesting constructor(
    private val connector: UnifiedPushConnector,
    private val registrationRepository: CloudPushRegistrationRepository,
    private val serverManager: ServerManager,
    private val workManager: WorkManager,
    private val firebaseAvailable: Boolean,
    // Injected so tests can pin the connector calls onto the test scheduler.
    private val backgroundDispatcher: CoroutineDispatcher,
) {

    @Inject
    constructor(
        connector: UnifiedPushConnector,
        registrationRepository: CloudPushRegistrationRepository,
        serverManager: ServerManager,
        workManager: WorkManager,
        @FcmSupport firebaseAvailable: Boolean,
    ) : this(connector, registrationRepository, serverManager, workManager, firebaseAvailable, Dispatchers.IO)

    /**
     * Owns the handling of distributor events.
     *
     * It belongs here and not to [UnifiedPushService], because the connector holds the service in a
     * static field that it never clears on unbind and keeps invoking it after `onDestroy()`. Work
     * tied to the service lifetime would be dropped silently from the second event of a process on.
     * This manager is a `@Singleton`, so the scope lives as long as the process and needs no
     * cancellation.
     */
    private val scope = CoroutineScope(SupervisorJob() + backgroundDispatcher)

    private val _state = MutableStateFlow<UnifiedPushState>(UnifiedPushState.Disabled)

    /** Resolved by [refresh] and updated by the callbacks of [UnifiedPushService]. */
    val state: StateFlow<UnifiedPushState> = _state.asStateFlow()

    /**
     * Events waiting to be handled.
     *
     * Unbounded on purpose: a distributor reports rarely, [onEvent] is called from a synchronous
     * callback and therefore must not suspend, and an event may never be dropped because it carries
     * the endpoint the server needs. A bounded channel could only offer dropping or blocking, and
     * both are wrong here.
     */
    private val events = Channel<UnifiedPushEvent>(Channel.UNLIMITED)

    init {
        // Exactly one consumer, so events are handled in the order onEvent() accepted them and a
        // suspending handler holds the next event back instead of racing it. A failing handler must
        // not end the loop, so each event is guarded on its own; SupervisorJob would not help,
        // because the loop is a single coroutine and not a set of siblings.
        scope.launch {
            for (event in events) {
                try {
                    when (event) {
                        is UnifiedPushEvent.NewEndpoint -> onNewEndpoint(event.endpoint, event.instance)
                        is UnifiedPushEvent.Unregistered -> onUnregistered(event.instance)
                        is UnifiedPushEvent.TemporarilyUnavailable -> onTemporarilyUnavailable(event.instance)
                        is UnifiedPushEvent.RegistrationFailed -> onRegistrationFailed(event.reason, event.instance)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to handle a distributor event")
                }
            }
        }
    }

    /**
     * The distributors installed on this device, with the one the user picked marked as selected.
     * A selected distributor may still be waiting to acknowledge the registration.
     */
    suspend fun distributors(): List<UnifiedPushDistributor> = withContext(backgroundDispatcher) {
        val selected = connector.savedDistributor()
        connector.availableDistributors().map { packageName ->
            UnifiedPushDistributor(
                packageName = packageName,
                label = connector.distributorLabel(packageName),
                selected = packageName == selected,
            )
        }
    }

    /** Resolves [state] from the desired registration and the distributor, and heals both gaps. */
    suspend fun refresh() {
        val registration = registrationRepository.current()
        val state = resolveState(registration)
        _state.value = state

        val desired = registration.desired
        if (desired !is DesiredCloudPushTransport.UnifiedPush) return
        val endpoint = desired.endpoint
        if (endpoint == null) {
            // A generation can want UnifiedPush without ever having received an endpoint, for
            // example when the process died between asking the distributor and its answer. Nobody
            // else would ask again, so [UnifiedPushState.Registering] would describe a registration
            // that is not actually running.
            if (state == UnifiedPushState.Registering) {
                workManager.enqueueUnifiedPushRegister(requiresNetwork = false)
            }
            return
        }

        val keys = registrationRepository.endpointKeys(endpoint)
        when {
            // Stored before the app kept key material, so whether this endpoint has keys is
            // unknown. Only the distributor can answer that, and only with a new snapshot.
            endpoint.snapshotId == null -> workManager.enqueueUnifiedPushRegister(requiresNetwork = false)
            // The state promises keys that are not there. Registering the endpoint without them
            // would claim a subscription that a sender cannot address, so a new one is asked for
            // instead. A snapshot that never had keys does not end up here, so this cannot loop.
            endpoint.keysPresent && keys == null -> {
                Timber.w("The keys of the wanted endpoint are gone, registering with the distributor again")
                workManager.enqueueUnifiedPushRegister(requiresNetwork = false)
            }
            // What the servers have is not what the user wants. The process can die after an
            // endpoint was accepted but before its sync was even queued, so this is the only place
            // that notices, and it has to compare the whole tuple to do so.
            !registeredTransport().isUpToDateFor(endpoint, keys) -> {
                workManager.enqueueCloudPushSync(registration.generation, endpoint.snapshotId)
            }
        }
    }

    /**
     * Saves [distributor] and asks it to register under a new generation, which invalidates the
     * callbacks of the previous one. The registration is only usable once [onNewEndpoint] delivered
     * an endpoint, so the state stays [UnifiedPushState.Registering].
     */
    suspend fun enable(distributor: String) {
        val previous = registrationRepository.current()
        val registration = registrationRepository.startUnifiedPush()
        withContext(backgroundDispatcher) {
            // Drop the previous registration so the app never holds two of them.
            if (previous.desired is DesiredCloudPushTransport.UnifiedPush) {
                connector.unregister(previous.instance)
            }
            connector.saveDistributor(distributor)
            connector.register(registration.instance)
        }
        _state.value = UnifiedPushState.Registering
    }

    /** Unregisters from the distributor and gives the registration back to Firebase. */
    suspend fun disable() {
        val previous = registrationRepository.current()
        val registration = registrationRepository.startFirebase()
        withContext(backgroundDispatcher) {
            if (previous.desired is DesiredCloudPushTransport.UnifiedPush) {
                connector.unregister(previous.instance)
            }
            connector.removeDistributor()
        }
        _state.value = UnifiedPushState.Disabled
        workManager.enqueueCloudPushSync(registration.generation, snapshotId = null)
    }

    /**
     * Queues what a distributor reported. The caller may already be destroyed, so this neither
     * suspends nor runs on the caller's lifetime; [events] keeps the order.
     */
    fun onEvent(event: UnifiedPushEvent) {
        events.trySend(event).onFailure { cause ->
            Timber.e(cause, "Dropped a distributor event")
        }
    }

    /** A distributor handed out an endpoint, which has to reach every server. */
    suspend fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val registration = registrationRepository.current()
        if (!registration.owns(instance)) {
            Timber.i("Ignoring an endpoint of a registration that is no longer wanted")
            return
        }
        val snapshot = registrationRepository.acceptEndpoint(
            generation = registration.generation,
            url = endpoint.url,
            keys = endpoint.webPushKeys(),
        )
        if (snapshot == null) {
            Timber.i("Ignoring an endpoint that was replaced while it was stored")
            return
        }
        Timber.i(
            "Received a push endpoint, temporary=${endpoint.temporary}, keys=${snapshot.keysPresent}",
        )
        _state.value = UnifiedPushState.Registered(temporary = endpoint.temporary)
        workManager.enqueueCloudPushSync(registration.generation, snapshot.snapshotId)
    }

    /**
     * The distributor dropped the registration, so this device needs another transport.
     *
     * A distributor also reports this when it was uninstalled or disabled, which the connector
     * notices while resolving the saved distributor.
     */
    suspend fun onUnregistered(instance: String) {
        if (!registrationRepository.current().owns(instance)) {
            Timber.i("Ignoring the unregistration of a registration that is no longer wanted")
            return
        }
        Timber.i("The distributor unregistered this app")
        if (firebaseAvailable) {
            val firebase = registrationRepository.startFirebase()
            _state.value = UnifiedPushState.Disabled
            workManager.enqueueCloudPushSync(firebase.generation, snapshotId = null)
        } else {
            // This build has no Firebase to fall back to, so the registration has to be renewed
            // with the distributor instead: without an endpoint nothing would deliver at all. The
            // connector reports no saved distributor once it is gone, and only the user can resolve
            // that. The servers keep the previous endpoint either way, because replacing it with the
            // empty Firebase token of this build would leave them without any transport.
            val distributor = withContext(backgroundDispatcher) { connector.savedDistributor() }
            if (distributor != null) {
                enable(distributor)
            } else {
                registrationRepository.startUnifiedPush()
                _state.value = UnifiedPushState.NoDistributor
            }
        }
    }

    /** The distributor cannot deliver at the moment, the registration itself stays valid. */
    suspend fun onTemporarilyUnavailable(instance: String) {
        if (!registrationRepository.current().owns(instance)) return
        Timber.i("The distributor reported itself as temporarily unavailable")
        _state.value = UnifiedPushState.Unavailable
    }

    /** Retries what the distributor called transient and leaves the rest to the user. */
    suspend fun onRegistrationFailed(reason: FailedReason, instance: String) {
        if (!registrationRepository.current().owns(instance)) return
        Timber.w("Registering with the distributor failed: $reason")
        _state.value = UnifiedPushState.Failed(reason)
        when (reason) {
            FailedReason.NETWORK -> workManager.enqueueUnifiedPushRegister(requiresNetwork = true)
            FailedReason.INTERNAL_ERROR -> workManager.enqueueUnifiedPushRegister(requiresNetwork = false)
            // Retrying these would only repeat the failure, the user has to act in the distributor.
            FailedReason.ACTION_REQUIRED, FailedReason.VAPID_REQUIRED -> Unit
        }
    }

    private suspend fun resolveState(registration: CloudPushRegistration): UnifiedPushState {
        val desired = registration.desired as? DesiredCloudPushTransport.UnifiedPush
            ?: return UnifiedPushState.Disabled

        return withContext(backgroundDispatcher) {
            val distributor = connector.savedDistributor()
            when {
                // The choice outlives the distributor being uninstalled or having its data cleared.
                distributor == null || distributor !in connector.availableDistributors() -> {
                    UnifiedPushState.NoDistributor
                }
                desired.endpoint == null || connector.acknowledgedDistributor() == null -> {
                    UnifiedPushState.Registering
                }
                // Whether the current endpoint is a temporary fallback is only known from the callback.
                else -> UnifiedPushState.Registered(temporary = false)
            }
        }
    }

    private suspend fun registeredTransport(): CloudPushTransport? {
        val server = if (serverManager.isRegistered()) serverManager.servers().firstOrNull() else null
        // The cloud push transport is stored per device, so every server reports the same one.
        return server?.let { serverManager.integrationRepository(it.id).getRegistration().cloudPush }
    }
}

/**
 * Whether the servers already have exactly [endpoint] with [keys].
 *
 * Asking only whether some endpoint is registered is not enough, because three different states all
 * report one: a registration that is still the previous endpoint, one whose keys were rotated while
 * the URL stayed the same, and one whose key association was lost. All three need the sync to run,
 * so the whole tuple has to be compared.
 *
 * The comparison is on the values and never on their text form, so no key material is formatted
 * anywhere. The opaque push token is deliberately not part of it: a renewal keeps the token of the
 * registration it renews, which is decided when the registration is built, not here.
 */
private fun CloudPushTransport?.isUpToDateFor(endpoint: EndpointSnapshot, keys: WebPushKeys?): Boolean {
    val registered = this as? CloudPushTransport.Endpoint ?: return false
    return registered.url == endpoint.url && registered.webPushKeys == keys
}

/**
 * The recipient keys this endpoint was handed out with, `null` when it came without usable ones.
 *
 * A distributor that does not expect an encrypted notification supplies no key set at all. One that
 * supplies an incomplete set is treated the same way rather than rejected, because an endpoint
 * without keys still delivers while a half key pair would only produce a registration no sender can
 * use.
 */
private fun PushEndpoint.webPushKeys(): WebPushKeys? = pubKeySet?.let { keys ->
    if (keys.pubKey.isBlank() || keys.auth.isBlank()) {
        Timber.w("The distributor supplied an incomplete key set, treating the endpoint as keyless")
        null
    } else {
        WebPushKeys(p256dh = keys.pubKey, auth = keys.auth)
    }
}
