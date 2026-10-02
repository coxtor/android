package io.homeassistant.companion.android.notifications.push

import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.notifications.MessagingManager
import javax.inject.Inject
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Receives the UnifiedPush events of the distributor.
 *
 * The connector embeds its own receiver and forwards every event to this service, which only
 * translates them for [UnifiedPushManager] and [MessagingManager].
 *
 * This class deliberately owns no state and no coroutine scope: the connector keeps the service in a
 * static field, never clears it on unbind, and goes on invoking these callbacks after `onDestroy()`.
 * Anything tied to the service lifetime would therefore be dropped from the second event of a
 * process on. Both managers are `@Singleton`, so forwarding to them stays correct on an instance the
 * system already destroyed.
 */
@AndroidEntryPoint
class UnifiedPushService : PushService() {

    @Inject
    lateinit var unifiedPushManager: UnifiedPushManager

    @Inject
    lateinit var messagingManager: MessagingManager

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        unifiedPushManager.onEvent(UnifiedPushEvent.NewEndpoint(endpoint, instance))
    }

    override fun onMessage(message: PushMessage, instance: String) {
        // Home Assistant posts the notification as plain JSON, so a message that was not decrypted
        // is the normal case here and not an error.
        val notification = parsePushNotification(message.content) ?: return
        messagingManager.handleMessage(notification, SOURCE)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        unifiedPushManager.onEvent(UnifiedPushEvent.RegistrationFailed(reason, instance))
    }

    override fun onUnregistered(instance: String) {
        unifiedPushManager.onEvent(UnifiedPushEvent.Unregistered(instance))
    }

    override fun onTempUnavailable(instance: String) {
        unifiedPushManager.onEvent(UnifiedPushEvent.TemporarilyUnavailable(instance))
    }

    companion object {
        /** Shown in the notification history to tell where a notification came from. */
        const val SOURCE = "UnifiedPush"
    }
}
