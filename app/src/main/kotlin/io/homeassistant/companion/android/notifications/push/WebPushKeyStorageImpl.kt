package io.homeassistant.companion.android.notifications.push

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.homeassistant.companion.android.common.data.integration.WebPushKeyRecord
import io.homeassistant.companion.android.common.data.integration.WebPushKeyStorage
import io.homeassistant.companion.android.common.data.integration.WebPushKeys
import io.homeassistant.companion.android.common.util.kotlinJsonMapper
import java.io.IOException
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import timber.log.Timber

/** Excluded from backups, see `app/src/main/res/xml/backup_rules.xml`. */
private const val ENCRYPTED_FILE_NAME = "webpush_keys_0"

private const val MASTER_KEY_ALIAS = "ha_webpush_keys_master"

/**
 * One stored record: the keys plus what identifies them.
 *
 * The identity is stored inside the record and not derived from the preference key, so a read can
 * prove that the record still describes the state that references it. See [WebPushKeyStorage].
 */
@Serializable
private data class StoredWebPushKeys(val reference: String, val endpoint: String, val p256dh: String, val auth: String)

/**
 * Turns a record into what is stored and back, with the matching rule that decides whether a stored
 * record may answer a read.
 *
 * This holds no state and touches no storage, so the rule it implements is the part that can be
 * exercised without a device.
 *
 * Nothing in here ever logs a throwable or any record content. A deserializer reports what it failed
 * on, and what it failed on is the authentication secret, the public key and the endpoint.
 */
internal object WebPushKeyRecordCodec {

    fun encode(reference: String, endpoint: String, keys: WebPushKeys): String = kotlinJsonMapper.encodeToString(
        StoredWebPushKeys(
            reference = reference,
            endpoint = endpoint,
            p256dh = keys.p256dh,
            auth = keys.auth,
        ),
    )

    /**
     * The keys in [stored], or `null` unless it was written for exactly [reference] and [endpoint]
     * and carries a usable key pair.
     *
     * Both identities have to match: an endpoint can renew its keys while its URL stays the same,
     * and a reference can outlive the endpoint it was minted for, so neither identifies a record on
     * its own. Anything unreadable or unusable counts as absent rather than as an error, because
     * losing the keys is recoverable while using the wrong ones is not.
     */
    fun decode(stored: String?, reference: String, endpoint: String): WebPushKeys? = stored
        ?.let { parse(it) }
        ?.takeIf { it.reference == reference && it.endpoint == endpoint }
        ?.toKeys()

    private fun parse(stored: String): StoredWebPushKeys? = try {
        kotlinJsonMapper.decodeFromString<StoredWebPushKeys>(stored)
    } catch (ignored: IllegalArgumentException) {
        // Dropped on purpose, and named so that it stays dropped: a deserializer reports what it
        // choked on, and what it choked on is the authentication secret, the public key and the
        // endpoint. Neither the throwable nor its message may reach a log.
        Timber.w("Discarding a WebPush key record that could not be read")
        null
    }

    /**
     * The key pair of this record, or `null` when it is structurally valid but unusable.
     *
     * A blank half cannot address a subscription, so it is treated like a missing record instead of
     * letting the validation of [WebPushKeys] throw out of a read.
     */
    private fun StoredWebPushKeys.toKeys(): WebPushKeys? = if (p256dh.isBlank() || auth.isBlank()) {
        Timber.w("Discarding a WebPush key record with an incomplete key pair")
        null
    } else {
        WebPushKeys(p256dh = p256dh, auth = auth)
    }
}

/**
 * [WebPushKeyStorage] on an [EncryptedSharedPreferences] file of its own.
 *
 * The file is separate from every other preference file so that it can be excluded from backups and
 * device transfers on its own, and so that nothing else ends up in it. Its master key lives in the
 * Android keystore, which is not part of a backup either.
 *
 * A read fails closed: a file that cannot be opened, a value that cannot be decrypted and a record
 * that cannot be read all report that there are no keys. Losing them costs a registration round,
 * while letting the failure out would take down the settings screen and the refresh that is supposed
 * to repair the state. A write does the opposite and reports the failure, so that nothing claims a
 * protected record exists when it does not.
 */
@Singleton
internal class WebPushKeyStorageImpl @VisibleForTesting constructor(
    // Injected so that the storage can be exercised on a test scheduler and against a preference
    // file that fails on purpose.
    private val backgroundDispatcher: CoroutineDispatcher,
    private val preferencesProvider: suspend () -> SharedPreferences,
) : WebPushKeyStorage {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        Dispatchers.IO,
        { encryptedPreferences(context) },
    )

    private val mutex = Mutex()
    private var preferences: SharedPreferences? = null

    override suspend fun put(record: WebPushKeyRecord, reference: String, endpoint: String, keys: WebPushKeys) {
        val encoded = WebPushKeyRecordCodec.encode(reference, endpoint, keys)
        withContext(backgroundDispatcher) {
            preferences().edit { putString(record.preferenceKey, encoded) }
        }
    }

    override suspend fun get(record: WebPushKeyRecord, reference: String, endpoint: String): WebPushKeys? {
        val stored = readStored(record) ?: return null
        return WebPushKeyRecordCodec.decode(stored, reference, endpoint)
    }

    override suspend fun clear(record: WebPushKeyRecord) {
        withContext(backgroundDispatcher) {
            preferences().edit { remove(record.preferenceKey) }
        }
    }

    /**
     * The stored record, or `null` when this device cannot read it right now.
     *
     * Opening the file touches the keystore and reading a value decrypts it, so both can fail on a
     * device whose key material is gone, and both report that with an exception. Only those three
     * are caught, which keeps a programmer error from being mistaken for a lost key. The exception
     * itself is never logged: a decryption failure quotes what it was working on.
     */
    private suspend fun readStored(record: WebPushKeyRecord): String? = try {
        withContext(backgroundDispatcher) {
            preferences().getString(record.preferenceKey, null)
        }
    } catch (e: GeneralSecurityException) {
        logUnreadableStore(e)
        null
    } catch (e: IOException) {
        logUnreadableStore(e)
        null
    } catch (e: SecurityException) {
        // What EncryptedSharedPreferences raises when a value cannot be decrypted.
        logUnreadableStore(e)
        null
    }

    /** Names the kind of failure without its message, which can quote the value it was reading. */
    private fun logUnreadableStore(cause: Throwable) {
        Timber.w("The WebPush key store cannot be read (${cause::class.simpleName}), continuing without keys")
    }

    /**
     * Opens the encrypted file once. Creating it derives a key and touches the keystore, which is
     * why it never runs on the main thread and is not done before it is needed.
     */
    private suspend fun preferences(): SharedPreferences = mutex.withLock {
        preferences ?: preferencesProvider().also { preferences = it }
    }
}

private fun encryptedPreferences(context: Context): SharedPreferences {
    val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        context,
        ENCRYPTED_FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}

private val WebPushKeyRecord.preferenceKey: String get() = name.lowercase()

@Module
@InstallIn(SingletonComponent::class)
internal interface WebPushKeyStorageModule {

    @Binds
    fun bindWebPushKeyStorage(storage: WebPushKeyStorageImpl): WebPushKeyStorage
}
