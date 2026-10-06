package io.homeassistant.companion.android.notifications.push

import android.content.SharedPreferences
import io.homeassistant.companion.android.common.data.integration.WebPushKeyRecord
import io.homeassistant.companion.android.common.data.integration.WebPushKeys
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import timber.log.Timber

// The preference keys the records are stored under, named here as well so that renaming one has to
// be a deliberate change.
private const val RECORD_DESIRED = "desired"
private const val RECORD_REGISTERED = "registered"

private const val ENDPOINT = "https://push.example.com/up1234?up=1"
private const val REFERENCE = "a2a1b0c9-0000-4000-8000-000000000001"

// Unique enough to be recognisable anywhere in a log or a message.
private const val SECRET_AUTH = "AUTH-f7c1a9d4e2b8-MUST-NEVER-BE-LOGGED"
private const val SECRET_PUBLIC_KEY = "P256DH-b3e9f0a1c7d2-MUST-NEVER-BE-LOGGED"

/** Captures what the code under test logged, so a test can assert what it did *not* say. */
private class CapturingTree : Timber.Tree() {
    val messages = mutableListOf<String>()
    val throwables = mutableListOf<Throwable?>()

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        messages += message
        throwables += t
    }

    /** Everything a log sink could end up writing, including the rendering of a throwable. */
    fun emitted(): String = (messages + throwables.map { it?.stackTraceToString().orEmpty() }).joinToString("\n")
}

/**
 * The read and write behaviour of the protected key store.
 *
 * A read has to fail closed, because it runs on the settings screen and in the refresh that is
 * supposed to repair a broken registration: letting a corrupt or unreadable record throw would take
 * down the very paths that recover from it. A write has to do the opposite and report the failure,
 * so that no ordinary value claims a protected record exists.
 */
class WebPushKeyStorageImplTest {

    private val tree = CapturingTree()

    @BeforeEach
    fun plantTree() {
        Timber.plant(tree)
    }

    @AfterEach
    fun uprootTree() {
        Timber.uproot(tree)
    }

    /** A [SharedPreferences] that behaves like a map, which is all this storage needs of it. */
    private fun preferencesHolding(vararg entries: Pair<String, String>): SharedPreferences {
        val values = entries.toMap().toMutableMap()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val preferences = mockk<SharedPreferences>()
        every { preferences.getString(any(), any()) } answers { values[firstArg()] ?: secondArg() }
        every { preferences.edit() } returns editor
        every { editor.putString(any(), any()) } answers {
            values[firstArg()] = secondArg<String>()
            editor
        }
        every { editor.remove(any()) } answers {
            values.remove(firstArg())
            editor
        }
        return preferences
    }

    private fun TestScope.storage(preferences: suspend () -> SharedPreferences) = WebPushKeyStorageImpl(StandardTestDispatcher(testScheduler), preferences)

    private fun record(
        reference: String = REFERENCE,
        endpoint: String = ENDPOINT,
        p256dh: String = SECRET_PUBLIC_KEY,
        auth: String = SECRET_AUTH,
    ) = """{"reference":"$reference","endpoint":"$endpoint","p256dh":"$p256dh","auth":"$auth"}"""

    @Test
    fun `Given a stored record when it is read back then the keys come out of protected storage`() = runTest {
        val preferences = preferencesHolding()
        val storage = storage { preferences }
        val keys = WebPushKeys(p256dh = SECRET_PUBLIC_KEY, auth = SECRET_AUTH)

        storage.put(WebPushKeyRecord.DESIRED, REFERENCE, ENDPOINT, keys)

        assertEquals(keys, storage.get(WebPushKeyRecord.DESIRED, REFERENCE, ENDPOINT))
        // The two records are independent, so one does not answer a read meant for the other.
        assertNull(storage.get(WebPushKeyRecord.REGISTERED, REFERENCE, ENDPOINT))
    }

    @Test
    fun `Given a record of one kind when the other is cleared then it survives`() = runTest {
        val preferences = preferencesHolding()
        val storage = storage { preferences }
        val keys = WebPushKeys(p256dh = SECRET_PUBLIC_KEY, auth = SECRET_AUTH)
        storage.put(WebPushKeyRecord.REGISTERED, REFERENCE, ENDPOINT, keys)

        storage.clear(WebPushKeyRecord.DESIRED)

        assertEquals(keys, storage.get(WebPushKeyRecord.REGISTERED, REFERENCE, ENDPOINT))
    }

    @Test
    fun `Given a malformed record when it is read then there are no keys`() = runTest {
        // Truncated on purpose: this is the shape that makes a deserializer quote its input.
        val malformed = record().dropLast(12)
        val storage = storage { preferencesHolding(RECORD_DESIRED to malformed) }

        assertNull(storage.get(WebPushKeyRecord.DESIRED, REFERENCE, ENDPOINT))
    }

    @Test
    fun `Given a malformed record when it is read then none of its content is logged`() = runTest {
        val malformed = record().dropLast(12)
        val storage = storage { preferencesHolding(RECORD_DESIRED to malformed) }

        assertNull(storage.get(WebPushKeyRecord.DESIRED, REFERENCE, ENDPOINT))

        val emitted = tree.emitted()
        assertFalse(emitted.contains(SECRET_AUTH), "The authentication secret was logged:\n$emitted")
        assertFalse(emitted.contains(SECRET_PUBLIC_KEY), "The public key was logged:\n$emitted")
        assertFalse(emitted.contains(ENDPOINT), "The endpoint was logged:\n$emitted")
        // The deserializer reports what it choked on, so its exception may not reach the log at all.
        assertTrue(
            tree.throwables.all { it == null },
            "A throwable was handed to the log, which can carry the record it failed to read",
        )
        assertTrue(tree.messages.isNotEmpty(), "Discarding a record should still be diagnosable")
    }

    @Test
    fun `Given a record with a blank authentication secret when it is read then there are no keys`() = runTest {
        // Structurally valid, so it gets past the deserializer, but unusable for a sender.
        val storage = storage { preferencesHolding(RECORD_DESIRED to record(auth = "")) }

        assertNull(storage.get(WebPushKeyRecord.DESIRED, REFERENCE, ENDPOINT))
    }

    @Test
    fun `Given a record with a blank public key when it is read then there are no keys`() = runTest {
        val storage = storage { preferencesHolding(RECORD_DESIRED to record(p256dh = "")) }

        assertNull(storage.get(WebPushKeyRecord.DESIRED, REFERENCE, ENDPOINT))
    }

    @Test
    fun `Given a store that cannot be opened when it is read then there are no keys`() = runTest {
        // What a device whose master key is gone looks like.
        val storage = storage { throw GeneralSecurityException("keystore entry missing") }

        assertNull(storage.get(WebPushKeyRecord.DESIRED, REFERENCE, ENDPOINT))
        assertTrue(tree.throwables.all { it == null }, "A throwable was handed to the log")
    }

    @Test
    fun `Given a value that cannot be decrypted when it is read then there are no keys`() = runTest {
        val preferences = mockk<SharedPreferences>()
        // What EncryptedSharedPreferences raises for a value it cannot decrypt.
        every { preferences.getString(any(), any()) } throws SecurityException("Could not decrypt value")
        val storage = storage { preferences }

        assertNull(storage.get(WebPushKeyRecord.DESIRED, REFERENCE, ENDPOINT))
    }

    @Test
    fun `Given a store that cannot be read when it is read then the failure is named without its message`() = runTest {
        val storage = storage { throw IOException("/data/.../webpush_keys_0.xml: $SECRET_AUTH") }

        assertNull(storage.get(WebPushKeyRecord.DESIRED, REFERENCE, ENDPOINT))

        val emitted = tree.emitted()
        assertTrue(emitted.contains("IOException"), "The kind of failure should be diagnosable:\n$emitted")
        assertFalse(emitted.contains(SECRET_AUTH), "The exception message was logged:\n$emitted")
    }

    @Test
    fun `Given a store that cannot be written when a record is stored then the failure is reported`() = runTest {
        val storage = storage { throw GeneralSecurityException("keystore entry missing") }

        // A write that quietly did nothing would leave the state claiming keys that are not there.
        val failure = try {
            storage.put(
                WebPushKeyRecord.DESIRED,
                REFERENCE,
                ENDPOINT,
                WebPushKeys(p256dh = SECRET_PUBLIC_KEY, auth = SECRET_AUTH),
            )
            null
        } catch (e: GeneralSecurityException) {
            e
        }

        assertNotNull(failure, "A failed protected write has to be reported, not swallowed")
    }

    @Test
    fun `Given a record of another reference or endpoint when it is read then there are no keys`() = runTest {
        val storage = storage { preferencesHolding(RECORD_REGISTERED to record()) }

        assertNull(storage.get(WebPushKeyRecord.REGISTERED, "another-reference", ENDPOINT))
        assertNull(storage.get(WebPushKeyRecord.REGISTERED, REFERENCE, "https://push.example.com/other"))
        assertEquals(
            WebPushKeys(p256dh = SECRET_PUBLIC_KEY, auth = SECRET_AUTH),
            storage.get(WebPushKeyRecord.REGISTERED, REFERENCE, ENDPOINT),
        )
    }
}
