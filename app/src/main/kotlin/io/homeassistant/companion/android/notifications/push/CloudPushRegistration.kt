package io.homeassistant.companion.android.notifications.push

/** Prefix of the connector instance, so the generation can be read back from a callback. */
private const val INSTANCE_PREFIX = "unifiedpush-"

/**
 * One endpoint a distributor handed out, exactly as it was accepted.
 *
 * A callback carries a URL together with the key material that belongs to it, and a distributor may
 * renew either of them without the other changing. The two are therefore kept and replaced as one
 * snapshot, never merged: [snapshotId] identifies this one pair, so the keys of one callback can
 * never end up paired with the URL of another.
 *
 * This is not the same thing as [CloudPushRegistration.generation]: the generation says which
 * selection owns the registration, the snapshot says which accepted endpoint it currently is.
 *
 * @property url Endpoint Home Assistant posts the notification to.
 * @property snapshotId Opaque id of this snapshot. `null` for an endpoint that was stored before
 * the app kept key material, where whether keys exist is unknown rather than known to be no.
 * @property keysPresent Whether the distributor supplied a usable key pair with this endpoint,
 * which is then expected in protected storage under [snapshotId].
 */
data class EndpointSnapshot(val url: String, val snapshotId: String?, val keysPresent: Boolean)

/** The cloud push transport the user asked for. */
sealed interface DesiredCloudPushTransport {

    /** Notifications go through the push proxy built into the app. */
    data object Firebase : DesiredCloudPushTransport

    /**
     * Notifications go through a UnifiedPush distributor.
     *
     * @property endpoint Endpoint of this registration, `null` until a distributor handed one out.
     */
    data class UnifiedPush(val endpoint: EndpointSnapshot?) : DesiredCloudPushTransport
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
