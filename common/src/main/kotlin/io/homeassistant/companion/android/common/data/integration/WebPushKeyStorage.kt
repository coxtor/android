package io.homeassistant.companion.android.common.data.integration

/** Which of the two records a [WebPushKeyStorage] call addresses. */
enum class WebPushKeyRecord {

    /**
     * The keys of the endpoint the app accepted last. It exists because an accepted endpoint has to
     * survive a process restart and a delayed or retried registration, which is longer than the
     * component that received it lives.
     */
    DESIRED,

    /**
     * The keys of the endpoint that is registered with the servers. It is kept apart from [DESIRED],
     * because the two can describe different endpoints: a newly accepted endpoint does not replace
     * the registered one until it reached the servers, and until then every other registration
     * update still has to reproduce the one that is actually registered.
     */
    REGISTERED,
}

/**
 * Protected storage of the recipient keys of a push subscription.
 *
 * Only the two records of [WebPushKeyRecord] exist, and each holds nothing but one set of keys plus
 * what identifies them: the [reference] they were stored under and the [endpoint] they belong to.
 * This is deliberately not a place for arbitrary secrets.
 *
 * Keys live here and never in an ordinary preference, while the reference that points at them is an
 * ordinary value. The two stores cannot share one transaction, so a read has to prove that a record
 * still describes the state that references it: an implementation returns the keys only when both
 * [reference] and [endpoint] match what the record was written for. A process death can therefore
 * cost the keys, but it can never pair the keys of one endpoint with another.
 */
interface WebPushKeyStorage {

    /** Stores [keys] as [record], as the keys of [endpoint] under [reference]. */
    suspend fun put(record: WebPushKeyRecord, reference: String, endpoint: String, keys: WebPushKeys)

    /**
     * The keys of [record], or `null` unless it was stored for exactly [reference] and [endpoint].
     */
    suspend fun get(record: WebPushKeyRecord, reference: String, endpoint: String): WebPushKeys?

    /** Forgets [record]. */
    suspend fun clear(record: WebPushKeyRecord)
}
