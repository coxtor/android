package io.homeassistant.companion.android.notifications.push

import io.homeassistant.companion.android.common.data.LocalStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * [LocalStorage] backed by a map, so tests can use the real
 * [CloudPushRegistrationRepository] instead of mocking it away. It also survives recreating the
 * objects under test, which is how a process restart is simulated.
 */
internal class InMemoryLocalStorage : LocalStorage {

    private val values = mutableMapOf<String, Any>()

    /**
     * Delays the next read by this many milliseconds and resets itself.
     *
     * A test arms this to make exactly one event handler slow. Without ordering the handlers queued
     * behind it would overtake it and then be overwritten by its late result.
     */
    var delayNextReadMs: Long = 0

    /** Everything that is stored, so a test can assert what did *not* end up in here. */
    val storedValues: Map<String, Any> get() = values.toMap()

    private val readGates = mutableMapOf<String, CompletableDeferred<Unit>>()

    /**
     * Makes the next read of [key] wait for the returned gate.
     *
     * A test uses this to hold a reader at a chosen point in a multi-value read and let a writer run
     * in between, which is how it can tell a coherent read from one that saw half of each state.
     */
    fun gateNextReadOf(key: String): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { readGates[key] = it }

    override suspend fun putString(key: String, value: String?) = put(key, value)

    /** Applies every entry in one go, so no read can land between them. */
    override suspend fun putStrings(values: Map<String, String?>) {
        values.forEach { (key, value) -> put(key, value) }
    }

    override suspend fun getString(key: String): String? {
        consumeArmedDelay()
        readGates.remove(key)?.await()
        return values[key] as? String
    }

    override suspend fun putLong(key: String, value: Long?) = put(key, value)

    override suspend fun getLong(key: String): Long? = values[key] as? Long

    override suspend fun putInt(key: String, value: Int?) = put(key, value)

    override suspend fun getInt(key: String): Int? = values[key] as? Int

    override suspend fun putBoolean(key: String, value: Boolean) = put(key, value)

    override suspend fun getBoolean(key: String): Boolean = values[key] as? Boolean ?: false

    override suspend fun getBooleanOrNull(key: String): Boolean? = values[key] as? Boolean

    override suspend fun putStringSet(key: String, value: Set<String>) = put(key, value)

    @Suppress("UNCHECKED_CAST")
    override suspend fun getStringSet(key: String): Set<String>? = values[key] as? Set<String>

    override suspend fun remove(key: String) {
        values.remove(key)
    }

    override fun observeChanges(vararg keys: String): Flow<String> = emptyFlow()

    override suspend fun <T> observeChanges(vararg keys: String, mapper: suspend () -> T): Flow<T> = emptyFlow()

    private suspend fun consumeArmedDelay() {
        val armed = delayNextReadMs
        if (armed > 0) {
            delayNextReadMs = 0
            delay(armed)
        }
    }

    private fun put(key: String, value: Any?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}
