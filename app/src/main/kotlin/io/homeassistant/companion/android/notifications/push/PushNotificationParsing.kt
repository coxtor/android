package io.homeassistant.companion.android.notifications.push

import io.homeassistant.companion.android.common.notifications.NotificationData
import io.homeassistant.companion.android.common.util.MapAnySerializer
import io.homeassistant.companion.android.common.util.kotlinJsonMapper
import io.homeassistant.companion.android.notifications.MessagingManager
import io.homeassistant.companion.android.notifications.flattenNotificationData
import kotlinx.serialization.SerializationException
import timber.log.Timber

private const val REGISTRATION_INFO = "registration_info"

/**
 * Turns the body of a push message into the flat notification data
 * [MessagingManager.handleMessage] expects, or `null` when it is not a usable notification.
 *
 * The body is the JSON Home Assistant posts to the endpoint. A distributor may deliver it
 * truncated, so invalid JSON is expected and drops the notification instead of failing.
 */
internal fun parsePushNotification(content: ByteArray): Map<String, String>? {
    val notification = decodeNotification(content) ?: return null

    val flattened = notification.flattenNotificationData().toMutableMap()
    // Home Assistant names the server the notification belongs to, which is how one endpoint can
    // serve several servers. Without it the active server is used.
    (notification[REGISTRATION_INFO] as? Map<*, *>)
        ?.get(NotificationData.WEBHOOK_ID)
        ?.let { flattened[NotificationData.WEBHOOK_ID] = it.toString() }

    return flattened.takeIf { it.isNotEmpty() }
}

/**
 * Decodes the body, or returns `null` when it is not a JSON object. A distributor may truncate a
 * body that is too large for it, so invalid JSON is expected rather than exceptional.
 */
private fun decodeNotification(content: ByteArray): Map<String, Any?>? = try {
    kotlinJsonMapper.decodeFromString(MapAnySerializer, content.decodeToString())
} catch (e: SerializationException) {
    Timber.e(e, "Dropping push message that is not valid JSON, ${content.size} bytes")
    null
} catch (e: IllegalArgumentException) {
    Timber.e(e, "Dropping push message that is not a JSON object, ${content.size} bytes")
    null
}
