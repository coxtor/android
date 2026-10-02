package io.homeassistant.companion.android.notifications.push

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** The ntfy default limit after which a distributor can hand over a truncated body. */
private const val NTFY_MESSAGE_SIZE_LIMIT = 4096

class PushNotificationParsingTest {

    private fun parse(json: String) = parsePushNotification(json.toByteArray())

    @Test
    fun `Given a message and a title when parsing then both are kept`() {
        val data = parse("""{"message":"Hello","title":"Greetings"}""")

        assertEquals("Hello", data?.get("message"))
        assertEquals("Greetings", data?.get("title"))
    }

    @Test
    fun `Given nested data when parsing then it is flattened next to the message`() {
        val data = parse(
            """{"message":"Hello","data":{"channel":"alerts","importance":"high","sticky":true,"timeout":300}}""",
        )

        assertEquals("Hello", data?.get("message"))
        assertEquals("alerts", data?.get("channel"))
        assertEquals("high", data?.get("importance"))
        assertEquals("true", data?.get("sticky"))
        assertEquals("300", data?.get("timeout"))
    }

    @Test
    fun `Given actions when parsing then they become indexed entries including their extras`() {
        val data = parse(
            """
            {
              "message": "Motion detected",
              "data": {
                "actions": [
                  {"action":"OPEN","title":"Open","uri":"/lovelace/0","authenticationRequired":true},
                  {"action":"DISMISS","title":"Dismiss","behavior":"textInput"}
                ]
              }
            }
            """.trimIndent(),
        )

        assertEquals("OPEN", data?.get("action_1_key"))
        assertEquals("Open", data?.get("action_1_title"))
        assertEquals("/lovelace/0", data?.get("action_1_uri"))
        assertEquals("true", data?.get("action_1_authenticationRequired"))
        assertEquals("DISMISS", data?.get("action_2_key"))
        assertEquals("textInput", data?.get("action_2_behavior"))
        // Absent extras must not create empty entries.
        assertFalse(data!!.containsKey("action_2_uri"))
    }

    @Test
    fun `Given an array and numbers of every kind when parsing then they survive as text`() {
        val data = parse(
            """
            {
              "message": "Numbers",
              "data": {
                "vibrationPattern": [100, 200, 100],
                "count": 42,
                "big": 9007199254740993,
                "ratio": 1.5,
                "enabled": false
              }
            }
            """.trimIndent(),
        )

        assertEquals("[100, 200, 100]", data?.get("vibrationPattern"))
        assertEquals("42", data?.get("count"))
        assertEquals("9007199254740993", data?.get("big"))
        assertEquals("1.5", data?.get("ratio"))
        assertEquals("false", data?.get("enabled"))
    }

    @Test
    fun `Given Unicode content when parsing then it is preserved`() {
        val data = parse("""{"message":"Tür offen 🚪","title":"Küche"}""")

        assertEquals("Tür offen 🚪", data?.get("message"))
        assertEquals("Küche", data?.get("title"))
    }

    @Test
    fun `Given registration info when parsing then the webhook id identifies the server`() {
        val data = parse(
            """{"message":"Hello","registration_info":{"app_id":"io.homeassistant","webhook_id":"abc123"}}""",
        )

        assertEquals("abc123", data?.get("webhook_id"))
    }

    @Test
    fun `Given no registration info when parsing then no webhook id is invented`() {
        val data = parse("""{"message":"Hello"}""")

        assertFalse(data!!.containsKey("webhook_id"))
    }

    @Test
    fun `Given unknown fields when parsing then they are ignored instead of failing`() {
        val data = parse("""{"message":"Hello","somethingNew":{"a":1},"push_token":"irrelevant"}""")

        assertEquals("Hello", data?.get("message"))
        assertFalse(data!!.containsKey("push_token"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["not json", "{\"message\":", "[]", "", "null", "\"text\""])
    fun `Given a body that is not a notification object when parsing then it is dropped`(body: String) {
        assertNull(parse(body))
    }

    @Test
    fun `Given a body truncated at the distributor limit when parsing then it is dropped`() {
        val message = "a".repeat(NTFY_MESSAGE_SIZE_LIMIT)
        val full = """{"message":"$message","data":{"channel":"alerts"}}"""
        // A distributor that only forwards the first bytes cuts the JSON in the middle.
        val truncated = full.toByteArray().copyOf(NTFY_MESSAGE_SIZE_LIMIT)

        assertNull(parsePushNotification(truncated))
    }

    @Test
    fun `Given an empty notification when parsing then it is dropped`() {
        assertNull(parse("{}"))
    }
}
