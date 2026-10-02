package io.homeassistant.companion.android.notifications.push

import org.unifiedpush.android.connector.FailedReason

/**
 * What the app knows about its UnifiedPush registration.
 *
 * Only [Registered] means notifications can arrive. The state is kept in memory, so after a process
 * restart it is resolved again from the stored registration and the distributor.
 */
sealed interface UnifiedPushState {

    /** UnifiedPush is not the registered cloud push transport. */
    data object Disabled : UnifiedPushState

    /** UnifiedPush is registered but no distributor is installed, so nothing can be delivered. */
    data object NoDistributor : UnifiedPushState

    /** A distributor was asked to register and has not answered yet. */
    data object Registering : UnifiedPushState

    /**
     * Notifications are delivered through the distributor.
     *
     * @property temporary The distributor handed out a fallback endpoint because its own backend is
     * unavailable. Delivery works, but the endpoint is replaced once the primary one is back.
     */
    data class Registered(val temporary: Boolean) : UnifiedPushState

    /** The distributor reported that it cannot deliver at the moment. */
    data object Unavailable : UnifiedPushState

    /**
     * Registering failed.
     *
     * @property reason [FailedReason.ACTION_REQUIRED] and [FailedReason.VAPID_REQUIRED] need the
     * user, the other reasons are retried in the background.
     */
    data class Failed(val reason: FailedReason) : UnifiedPushState
}
