import com.android.build.api.dsl.ApplicationExtension
import io.homeassistant.companion.android.getPluginId
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.exclude
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.project

/**
 * This convention plugin has been created to avoid duplicating dependencies
 * in `:app` and `:automotive` modules.
 *
 * This plugin requires the following:
 * - The Android Application Gradle plugin must be applied to the project.
 * - The project must define at least two product flavors: `full` and `minimal`.
 *   These flavors can be automatically configured by applying the
 *   [AndroidFullMinimalFlavorConventionPlugin].
 */
class AndroidApplicationDependenciesConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = libs.plugins.android.application.getPluginId())

            extensions.getByType<ApplicationExtension>().apply {
                dependencies {
                    "implementation"(project(":common"))
                    "implementation"(project(":microwakeword"))

                    "implementation"(libs.blurView)
                    "implementation"(libs.haze)
                    "implementation"(libs.haze.blur.materials)
                    "implementation"(libs.androidx.health.connect.client)

                    "implementation"(libs.kotlin.stdlib)
                    "implementation"(libs.kotlin.reflect)
                    "implementation"(libs.kotlinx.coroutines.core)
                    "implementation"(libs.kotlinx.coroutines.android)
                    "implementation"(libs.androidx.concurrent.ktx)
                    "fullImplementation"(libs.kotlinx.coroutines.play.services)

                    "implementation"(libs.apache.commons.text)

                    "implementation"(libs.appcompat)
                    "implementation"(libs.androidx.lifecycle.runtime.ktx)
                    "implementation"(libs.androidx.lifecycle.service)
                    "implementation"(libs.constraintlayout)
                    "implementation"(libs.recyclerview)
                    "implementation"(libs.preference.ktx)
                    "implementation"(libs.material)
                    "implementation"(libs.fragment.ktx)

                    "implementation"(platform(libs.okhttp.bom))
                    "implementation"(libs.okhttp.android)

                    "implementation"(libs.bundles.coil)

                    "fullImplementation"(libs.play.services.location)
                    "fullImplementation"(libs.play.services.home)
                    "fullImplementation"(libs.play.services.threadnetwork)
                    "fullImplementation"(platform(libs.firebase.bom))
                    "fullImplementation"(libs.firebase.messaging)
                    "fullImplementation"(libs.sentry.android.core)
                    "fullImplementation"(libs.play.services.wearable)
                    "fullImplementation"(libs.wear.remote.interactions)

                    "implementation"(libs.biometric)
                    "implementation"(libs.webkit)

                    "implementation"(libs.bundles.media3)
                    "fullImplementation"(libs.media3.datasource.cronet)
                    "minimalImplementation"(libs.media3.datasource.cronet) {
                        exclude(group = "com.google.android.gms", module = "play-services-cronet")
                    }
                    "minimalImplementation"(libs.cronet.embedded)

                    "implementation"(libs.compose.animation)
                    "implementation"(libs.compose.material)
                    "implementation"(libs.compose.runtime)
                    "implementation"(libs.activity.compose)
                    "implementation"(libs.navigation.compose)
                    "implementation"(libs.core.remoteviews)
                    "implementation"(libs.core.splashscreen)
                    "implementation"(libs.core.ktx)
                    "implementation"(libs.accompanist.permissions)
                    "implementation"(libs.androidx.hilt.navigation.compose)

                    "implementation"(libs.bundles.androidx.glance)

                    "implementation"(libs.bundles.paging)

                    "implementation"(libs.reorderable)
                    "implementation"(libs.aboutlibraries.compose.m3)

                    "implementation"(libs.zxing)
                    "implementation"(libs.improv)

                    "implementation"(libs.car.core)

                    // `:automotive` reuses `:app/src/main` sources, so both application modules
                    // need the connector even though the feature is only offered on `:app`.
                    "implementation"(libs.unifiedpush.connector) {
                        // The connector asks for the JVM build of Tink, which brings the full
                        // protobuf runtime while the app already uses protobuf-javalite. Keeping
                        // both duplicates every com.google.protobuf class and fails the build. The
                        // Android build below provides the same `com.google.crypto.tink.subtle`
                        // classes the connector uses and shades its own protobuf, so exactly one
                        // Tink runtime serves the connector and the encrypted preferences.
                        exclude(group = "com.google.crypto.tink", module = "tink")
                    }

                    // Protected storage for the keys of a push subscription, see
                    // `WebPushKeyStorageImpl`.
                    "implementation"(libs.security.crypto)
                    // Pinned above the version security-crypto asks for, so that the one Tink on
                    // the runtime classpath is also new enough for the connector.
                    "implementation"(libs.tink.android)

                    "androidTestImplementation"(libs.bundles.androidx.test)
                    "androidTestImplementation"(libs.leakcanary.android.instrumentation)
                    "androidTestImplementation"(libs.hilt.android.testing)

                    "testImplementation"(libs.bundles.androidx.glance.testing)
                    "testImplementation"(libs.navigation.test)
                    "testImplementation"(libs.hilt.android.testing)
                    "testImplementation"(libs.androidx.work.testing)

                    "lintChecks"(libs.compose.lint.checks)
                }
            }
        }
    }
}
