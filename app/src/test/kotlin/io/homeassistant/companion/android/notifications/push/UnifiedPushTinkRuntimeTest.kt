package io.homeassistant.companion.android.notifications.push

import com.google.crypto.tink.apps.fixed_webpush.WebPushHybridDecrypt
import com.google.crypto.tink.apps.fixed_webpush.WebPushHybridEncrypt
import com.google.crypto.tink.subtle.EllipticCurves
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

/**
 * Guards the Tink runtime the UnifiedPush connector decrypts with.
 *
 * The connector asks for the JVM build of Tink, which drags in the full protobuf runtime next to the
 * `protobuf-javalite` this app uses. It is therefore excluded in favour of the Android build, which
 * shades its own protobuf. That swap is invisible at compile time, because both builds publish the
 * same `com.google.crypto.tink` classes, so it is checked by actually running the code path the
 * connector uses rather than by compiling against it.
 */
class UnifiedPushTinkRuntimeTest {

    @Test
    fun `Given the resolved Tink then it is the Android build with its own shaded protobuf`() {
        val classLoader = EllipticCurves::class.java.classLoader!!

        // Only the Android build relocates protobuf into Tink's own namespace. Finding it proves the
        // exclusion took effect, and the app keeps one protobuf runtime instead of two.
        assertDoesNotThrow {
            Class.forName("com.google.crypto.tink.shaded.protobuf.MessageLite", false, classLoader)
        }
    }

    @Test
    fun `Given a subscription key pair when the connector encrypts and decrypts then the message survives`() {
        // Exactly what travels in app_data: the uncompressed public point and the auth secret.
        val keyPair = EllipticCurves.generateKeyPair(EllipticCurves.CurveType.NIST_P256)
        val publicKey = keyPair.public as ECPublicKey
        val privateKey = keyPair.private as ECPrivateKey
        val p256dh = EllipticCurves.pointEncode(
            EllipticCurves.CurveType.NIST_P256,
            EllipticCurves.PointFormatType.UNCOMPRESSED,
            publicKey.w,
        )
        val auth = ByteArray(16) { it.toByte() }
        val message = """{"message":"hello"}""".toByteArray()

        // What a sender would do once Home Assistant encrypts, and what the connector does on the
        // device. Both halves live in the connector and run on the Tink resolved above.
        val ciphertext = WebPushHybridEncrypt.Builder()
            .withAuthSecret(auth)
            .withRecipientPublicKey(p256dh)
            .build()
            .encrypt(message, null)
        val decrypted = WebPushHybridDecrypt.Builder()
            .withAuthSecret(auth)
            .withRecipientPublicKey(p256dh)
            .withRecipientPrivateKey(privateKey)
            .build()
            .decrypt(ciphertext, null)

        assertArrayEquals(message, decrypted)
    }
}
