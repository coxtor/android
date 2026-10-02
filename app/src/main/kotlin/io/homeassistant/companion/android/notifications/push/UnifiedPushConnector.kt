package io.homeassistant.companion.android.notifications.push

import android.content.Context
import android.content.pm.PackageManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.homeassistant.companion.android.common.R as commonR
import javax.inject.Inject
import javax.inject.Singleton
import org.unifiedpush.android.connector.UnifiedPush
import timber.log.Timber

/**
 * The part of the UnifiedPush connector this app uses.
 *
 * The connector exposes static functions that read and write its own storage, which a unit test
 * cannot replace, so the registration logic talks to this instead. It also keeps the one place where
 * the connector is called with all of its arguments.
 *
 * Every function touches storage and must not be called on the main thread.
 */
interface UnifiedPushConnector {

    /** Packages that can act as a distributor on this device. */
    fun availableDistributors(): List<String>

    /** The distributor that was picked, also while it has not acknowledged the registration. */
    fun savedDistributor(): String?

    /** The distributor that acknowledged the registration, `null` while none did. */
    fun acknowledgedDistributor(): String?

    /** The label a user recognizes for [packageName], its package name when it has none. */
    fun distributorLabel(packageName: String): String

    /** Remembers [packageName] as the distributor to register with, without registering yet. */
    fun saveDistributor(packageName: String)

    /**
     * Asks the saved distributor for an endpoint, which arrives in [UnifiedPushService].
     *
     * @param instance Identifies the registration, see [CloudPushRegistration.instance].
     */
    fun register(instance: String)

    /** Tells the distributor to drop the registration of [instance] and its endpoint. */
    fun unregister(instance: String)

    /** Forgets the saved distributor, so no distributor is picked anymore. */
    fun removeDistributor()
}

@Singleton
internal class UnifiedPushConnectorImpl @Inject constructor(@param:ApplicationContext private val context: Context) :
    UnifiedPushConnector {

    override fun availableDistributors(): List<String> = UnifiedPush.getDistributors(context)

    override fun savedDistributor(): String? = UnifiedPush.getSavedDistributor(context)

    override fun acknowledgedDistributor(): String? = UnifiedPush.getAckDistributor(context)

    override fun distributorLabel(packageName: String): String = try {
        val packageManager = context.packageManager
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        Timber.w(e, "No label for distributor $packageName, falling back to its package name")
        packageName
    }

    override fun saveDistributor(packageName: String) = UnifiedPush.saveDistributor(context, packageName)

    override fun register(instance: String) = UnifiedPush.register(
        context = context,
        instance = instance,
        // Shown by the distributor to tell the user which app the registration belongs to.
        messageForDistributor = context.getString(commonR.string.app_name),
        vapid = null,
    )

    override fun unregister(instance: String) = UnifiedPush.unregister(context, instance)

    override fun removeDistributor() = UnifiedPush.removeDistributor(context)
}

@Module
@InstallIn(SingletonComponent::class)
internal interface UnifiedPushConnectorModule {

    @Binds
    fun bindUnifiedPushConnector(connector: UnifiedPushConnectorImpl): UnifiedPushConnector
}
