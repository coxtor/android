package io.homeassistant.companion.android.notifications.push

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Name of the encrypted preference file, see `WebPushKeyStorageImpl`. */
private const val ENCRYPTED_FILE = "webpush_keys_0.xml"

/**
 * Guards that the keys of a push subscription stay out of a backup and a device transfer.
 *
 * The rules are declarative XML that nothing else checks, so a copy that leaves the device would
 * only be noticed once it was already made.
 */
class WebPushKeyBackupRulesTest {

    private fun backupRules(name: String): String {
        val candidates = listOf(
            File("src/main/res/xml/$name"),
            File("app/src/main/res/xml/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
        assertTrue(file != null, "Could not find $name, looked in ${candidates.map { it.absolutePath }}")
        return checkNotNull(file).readText()
    }

    @Test
    fun `Given the legacy backup rules then the encrypted key file is excluded`() {
        val rules = backupRules("backup_rules.xml")

        assertTrue(
            rules.contains("""<exclude domain="sharedpref" path="$ENCRYPTED_FILE" />"""),
            "The subscription keys would be part of a full backup:\n$rules",
        )
    }

    @Test
    fun `Given the data extraction rules then the encrypted key file is excluded from both paths`() {
        val rules = backupRules("backup_rules_android12.xml")

        // Once for <cloud-backup> and once for <device-transfer>: the two are independent, and an
        // exclude in only one of them would still let the keys leave the device.
        assertEquals(
            2,
            Regex("""<exclude domain="sharedpref" path="$ENCRYPTED_FILE" />""").findAll(rules).count(),
            "The subscription keys have to be excluded from the cloud backup and the device transfer:\n$rules",
        )
    }
}
