package io.homeassistant.companion.android.notifications.push

import io.homeassistant.companion.android.common.data.integration.WebPushKeys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private const val REFERENCE = "a2a1b0c9-0000-4000-8000-000000000001"
private const val OTHER_REFERENCE = "a2a1b0c9-0000-4000-8000-000000000002"
private const val ENDPOINT = "https://push.example.com/up1234?up=1"
private const val OTHER_ENDPOINT = "https://push.example.com/up5678?up=1"
private val keys = WebPushKeys(p256dh = "BNcRd...public", auth = "c2VjcmV0")
private val otherKeys = WebPushKeys(p256dh = "BOtHeR...public", auth = "b3RoZXI")

/**
 * The rule that decides whether a stored record may answer a read. This is the part of the
 * protected storage that works without a device, see [WebPushKeyRecordCodec].
 */
class WebPushKeyRecordCodecTest {

    private fun stored(
        reference: String = REFERENCE,
        endpoint: String = ENDPOINT,
        recordKeys: WebPushKeys = keys,
    ) = WebPushKeyRecordCodec.encode(reference, endpoint, recordKeys)

    @Test
    fun `Given a stored record when it is read with what it was stored for then the keys come back`() {
        assertEquals(keys, WebPushKeyRecordCodec.decode(stored(), REFERENCE, ENDPOINT))
    }

    @Test
    fun `Given no stored record when it is read then there are no keys`() {
        assertNull(WebPushKeyRecordCodec.decode(null, REFERENCE, ENDPOINT))
    }

    @Test
    fun `Given a record of another reference when it is read then there are no keys`() {
        assertNull(WebPushKeyRecordCodec.decode(stored(), OTHER_REFERENCE, ENDPOINT))
    }

    @Test
    fun `Given a record of another endpoint when it is read then there are no keys`() {
        assertNull(WebPushKeyRecordCodec.decode(stored(), REFERENCE, OTHER_ENDPOINT))
    }

    @Test
    fun `Given an unreadable record when it is read then there are no keys`() {
        // Losing the keys is recoverable, using the wrong ones is not, so this is absent not an error.
        assertNull(WebPushKeyRecordCodec.decode("not json at all", REFERENCE, ENDPOINT))
        assertNull(WebPushKeyRecordCodec.decode("""{"reference":"$REFERENCE"}""", REFERENCE, ENDPOINT))
    }

    @Test
    fun `Given the same endpoint with renewed keys then neither snapshot can read the other`() {
        // The URL stays the same across a key renewal, so it cannot be what identifies a record.
        // Without the reference the old keys would answer a read meant for the new ones.
        val old = stored(reference = REFERENCE, recordKeys = keys)
        val new = stored(reference = OTHER_REFERENCE, recordKeys = otherKeys)

        assertNull(WebPushKeyRecordCodec.decode(old, OTHER_REFERENCE, ENDPOINT))
        assertNull(WebPushKeyRecordCodec.decode(new, REFERENCE, ENDPOINT))
        assertEquals(keys, WebPushKeyRecordCodec.decode(old, REFERENCE, ENDPOINT))
        assertEquals(otherKeys, WebPushKeyRecordCodec.decode(new, OTHER_REFERENCE, ENDPOINT))
    }

    @Test
    fun `Given a reference that outlived its endpoint when it is read then there are no keys`() {
        // A reference can survive a switch to another endpoint, so it is not enough on its own.
        assertNull(WebPushKeyRecordCodec.decode(stored(endpoint = OTHER_ENDPOINT), REFERENCE, ENDPOINT))
    }
}
