package io.homeassistant.companion.android.data

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.homeassistant.companion.android.common.data.integration.WebPushKeyRecord
import io.homeassistant.companion.android.common.data.integration.WebPushKeyStorage
import io.homeassistant.companion.android.common.data.integration.WebPushKeys
import io.homeassistant.companion.android.di.OkHttpConfigurator
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object WearDataModule {
    @Provides
    @IntoSet
    @Singleton
    fun bindOkHttpClientConfigurator(wearDns: WearDns): OkHttpConfigurator = object : OkHttpConfigurator {
        override fun invoke(builder: OkHttpClient.Builder) {
            builder.dns(wearDns)
        }
    }

    @Provides
    fun bindMessageClient(@ApplicationContext context: Context): MessageClient = Wearable.getMessageClient(context)

    @Provides
    fun bindCapabilityClient(@ApplicationContext context: Context): CapabilityClient =
        Wearable.getCapabilityClient(context)

    /**
     * Wear OS registers no push endpoint, so there are no subscription keys to keep. It therefore
     * gets no encrypted storage and none of the crypto dependencies that would come with it: a read
     * reports that there are no keys, which is what the registration then sends, and forgetting a
     * record that cannot exist is a no-op. Only a write would mean something went wrong.
     */
    @Provides
    @Singleton
    fun bindWebPushKeyStorage(): WebPushKeyStorage = object : WebPushKeyStorage {
        override suspend fun put(record: WebPushKeyRecord, reference: String, endpoint: String, keys: WebPushKeys) =
            throw UnsupportedOperationException("Wear OS does not register a push endpoint")

        override suspend fun get(record: WebPushKeyRecord, reference: String, endpoint: String): WebPushKeys? = null

        override suspend fun clear(record: WebPushKeyRecord) = Unit
    }
}
