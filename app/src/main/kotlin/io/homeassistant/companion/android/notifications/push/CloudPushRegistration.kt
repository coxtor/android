package io.homeassistant.companion.android.notifications.push

/** Prefix of the connector instance, so the generation can be read back from a callback. */
private const val INSTANCE_PREFIX = "unifiedpush-"

/** The cloud push transport the user asked for. */
sealed interface DesiredCloudPushTransport {

    /** Notifications go through the push proxy built into the app. */
    data object Firebase : DesiredCloudPushTransport

    /**
     * Notifications go through a UnifiedPush distributor.
     *
     * @property endpointUrl Endpoint of this registration, `null` until a distributor handed one out.
     */
    data class UnifiedPush(val endpointUrl: String?) : DesiredCloudPushTransport
}

/**
 * Which cloud push registration the app currently wants.
 *
 * A distributor answers asynchronously, so a callback or a worker can belong to a registration the
 * user has already replaced. [generation] grows with every choice the user makes and identifies the
 * one registration that is allowed to change anything.
 *
 * @property generation Identifies this registration attempt.
 * @property desired Transport the user asked for.
 */
data class CloudPushRegistration(val generation: Int, val desired: DesiredCloudPushTransport) {

    /**
     * The connector instance used for this registration. The connector passes it back with every
     * callback, which is how a callback of an older generation is recognized.
     */
    val instance: String get() = "$INSTANCE_PREFIX$generation"

    /** Whether a callback carrying [callbackInstance] belongs to this registration. */
    fun owns(callbackInstance: String): Boolean =
        desired is DesiredCloudPushTransport.UnifiedPush && callbackInstance == instance
}
