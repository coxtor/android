package io.homeassistant.companion.android.notifications.push

import io.homeassistant.companion.android.common.data.integration.WebPushKeyRecord
import io.homeassistant.companion.android.common.data.integration.WebPushKeyStorage
import io.homeassistant.companion.android.common.data.integration.WebPushKeys

/**
 * [WebPushKeyStorage] backed by a map.
 *
 * It stores what the real implementation stores and matches a read the same way, because both use
 * [WebPushKeyRecordCodec]. A test therefore exercises the production rule about which record may
 * answer which read, and only the encrypted file itself is left out. It also survives recreating the
 * objects under test, which is how a process restart is simulated.
 */
internal class InMemoryWebPushKeyStorage : WebPushKeyStorage {

    private val records = mutableMapOf<WebPushKeyRecord, String>()

    /** Everything that is stored, so a test can assert what a record does and does not contain. */
    val storedRecords: Map<WebPushKeyRecord, String> get() = records.toMap()

    override suspend fun put(
        record: WebPushKeyRecord,
        reference: String,
        endpoint: String,
        keys: WebPushKeys,
    ) {
        records[record] = WebPushKeyRecordCodec.encode(reference, endpoint, keys)
    }

    override suspend fun get(record: WebPushKeyRecord, reference: String, endpoint: String): WebPushKeys? = WebPushKeyRecordCodec.decode(records[record], reference, endpoint)

    override suspend fun clear(record: WebPushKeyRecord) {
        records.remove(record)
    }
}
