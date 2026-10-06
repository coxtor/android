package io.homeassistant.companion.android.common.data.integration

/**
 * The recipient keys of a push subscription, as
 * [RFC 8291](https://www.rfc-editor.org/rfc/rfc8291) describes them.
 *
 * The app neither derives nor uses them. It only keeps what the subscription handed out and
 * forwards it with the registration, so that a sender can address this subscription. Encrypting a
 * notification is the sender's part and happens nowhere in this app.
 *
 * Both values are passed on exactly as they were received, which is base64url without padding.
 *
 * @property p256dh Public key of the subscription.
 * @property auth Authentication secret of the subscription. This is secret key material: it belongs
 * in protected storage only, and never in a log or in an ordinary preference.
 */
data class WebPushKeys(val p256dh: String, val auth: String) {
    init {
        require(p256dh.isNotBlank()) { "A WebPush public key cannot be blank" }
        require(auth.isNotBlank()) { "A WebPush authentication secret cannot be blank" }
    }

    /** Keeps [auth] out of logs and crash reports, which the generated one would not. */
    override fun toString(): String = "WebPushKeys(p256dh=$p256dh, auth=REDACTED)"
}
