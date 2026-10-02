package io.homeassistant.companion.android.notifications.push

import io.homeassistant.companion.android.common.util.MessagingToken

/**
 * Creates the token a push endpoint is registered with.
 *
 * Home Assistant only echoes this value back to the endpoint, so it must not carry information:
 * neither the messaging token of this device nor the endpoint URL. It identifies one registration,
 * which is why it stays the same while that registration lives.
 */
fun interface OpaquePushTokenProvider {
    operator fun invoke(): MessagingToken
}
