package io.homeassistant.companion.android.notifications

import io.homeassistant.companion.android.common.notifications.NotificationData

/** Keys of an action that are forwarded as `action_<n>_<key>` when present. */
private val ACTION_EXTRA_KEYS = listOf("uri", "behavior", "authenticationRequired")

/**
 * Flattens a notification as Home Assistant sends it into the flat map
 * [MessagingManager.handleMessage] consumes.
 *
 * Everything except `message` and `title` arrives inside a nested `data` object, and the actions in
 * there become indexed `action_<n>_*` entries. `webhook_id` is deliberately not resolved here: only
 * the caller knows whether it comes from its own connection or from the payload.
 */
internal fun Map<String, Any?>.flattenNotificationData(): Map<String, String> {
    val flattened = mutableMapOf<String, String>()

    (this["data"] as? Map<*, *>)?.forEach { (key, value) ->
        if (key == "actions" && value is List<*>) {
            value.forEachIndexed { index, action ->
                if (action is Map<*, *>) {
                    flattened["action_${index + 1}_key"] = action["action"].toString()
                    flattened["action_${index + 1}_title"] = action["title"].toString()
                    for (extraKey in ACTION_EXTRA_KEYS) {
                        action[extraKey]?.let { flattened["action_${index + 1}_$extraKey"] = it.toString() }
                    }
                }
            }
        } else {
            flattened[key.toString()] = value.toString()
        }
    }

    // Message and title are in the root unlike all the others.
    for (key in listOf(NotificationData.MESSAGE, NotificationData.TITLE)) {
        if (containsKey(key)) {
            flattened[key] = this[key].toString()
        }
    }

    return flattened
}
