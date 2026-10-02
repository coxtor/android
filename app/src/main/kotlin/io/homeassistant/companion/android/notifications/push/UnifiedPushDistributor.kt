package io.homeassistant.companion.android.notifications.push

/**
 * A distributor the user can pick.
 *
 * @property packageName Package of the distributor app, which is how the connector identifies it.
 * @property label Name to show, the package name when the app has none.
 * @property selected Whether this is the distributor the user picked.
 */
data class UnifiedPushDistributor(val packageName: String, val label: String, val selected: Boolean)
