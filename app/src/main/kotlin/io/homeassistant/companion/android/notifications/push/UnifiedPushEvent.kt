package io.homeassistant.companion.android.notifications.push

import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.data.PushEndpoint

/**
 * What a distributor reported, on its way from [UnifiedPushService] to [UnifiedPushManager].
 *
 * The service only translates, because the connector keeps calling it after it was destroyed:
 * the library holds the service in a static field and never clears it on unbind, so anything tied
 * to the service lifetime would silently stop working after the first event of a process. See
 * [UnifiedPushManager.onEvent].
 *
 * @property instance Identifies the registration the event belongs to, see
 * [CloudPushRegistration.instance].
 */
sealed interface UnifiedPushEvent {

    val instance: String

    data class NewEndpoint(val endpoint: PushEndpoint, override val instance: String) : UnifiedPushEvent

    data class Unregistered(override val instance: String) : UnifiedPushEvent

    data class TemporarilyUnavailable(override val instance: String) : UnifiedPushEvent

    data class RegistrationFailed(val reason: FailedReason, override val instance: String) : UnifiedPushEvent
}
