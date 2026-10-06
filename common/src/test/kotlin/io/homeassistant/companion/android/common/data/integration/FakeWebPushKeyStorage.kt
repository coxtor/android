package io.homeassistant.companion.android.common.data.integration

/**
 * [WebPushKeyStorage] backed by a map, so a test can see the keys really travel through the
 * registration instead of stubbing the answer.
 *
 * It enforces the documented rule that a record only answers a read for exactly the reference and
 * the endpoint it was stored for, which is what keeps the keys of one registration from being
 * paired with another.
 */
internal class FakeWebPushKeyStorage : WebPushKeyStorage {

    private data class Record(val reference: String, val endpoint: String, val keys: WebPushKeys)

    private val records = mutableMapOf<WebPushKeyRecord, Record>()

    /** Whether [record] holds anything, so a test can assert that it was forgotten. */
    fun has(record: WebPushKeyRecord): Boolean = records.containsKey(record)

    /** The reference [record] is stored under, to check it against the ordinary value. */
    fun referenceOf(record: WebPushKeyRecord): String? = records[record]?.reference

    /** Stores [keys] without a matching reference, which is how a lost record is simulated. */
    suspend fun putUnreferenced(record: WebPushKeyRecord, endpoint: String, keys: WebPushKeys) {
        put(record, reference = "reference-of-another-registration", endpoint = endpoint, keys = keys)
    }

    override suspend fun put(
        record: WebPushKeyRecord,
        reference: String,
        endpoint: String,
        keys: WebPushKeys,
    ) {
        records[record] = Record(reference = reference, endpoint = endpoint, keys = keys)
    }

    override suspend fun get(record: WebPushKeyRecord, reference: String, endpoint: String): WebPushKeys? = records[record]?.takeIf { it.reference == reference && it.endpoint == endpoint }?.keys

    override suspend fun clear(record: WebPushKeyRecord) {
        records.remove(record)
    }
}
