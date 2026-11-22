import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.kotlin.dsl.*

/**
 * `seven.base-app`: a Seven app on the shared base. Applies the Android, Kotlin and Compose plugins
 * and `seven.android-app` (identity, flavors, signing, release gates), then adds what every app
 * needs: its Rust core (bindings module `:<app>-core` and the crate's Android libraries), SevenKit,
 * the shared purchase source per distribution, and Play Billing in the store graph only.
 *
 * In the app's build file:
 *
 *     extra["sevenApp"] = "qr"
 *     extra["sevenGenericName"] = "QR Code Kit"
 *     plugins { id("seven.base-app") }   // or apply(plugin = …) after the extras
 */
class SevenBasePlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("org.jetbrains.kotlin.android")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            pluginManager.apply(SevenAppPlugin::class.java)

            val app = extra["sevenApp"] as String
            val repository = rootDir.parentFile
            val crate = repository.resolve("apps/$app/core")
            val flavors = rootDir.resolve("flavors")

            configure<ApplicationExtension> {
                namespace = "gg.seven.$app"
                sourceSets.getByName("main").jniLibs.srcDir(crate.resolve("generated/jniLibs"))
                // gg.seven.flavor.sevenPurchases: local trial, or Play Billing in the store build.
                for (flavor in listOf("generic", "personal", "store")) sourceSets
                    .getByName(flavor)
                    .java
                    .srcDir(flavors.resolve("$flavor/kotlin"))
            }

            val rustAndroid =
                tasks.register<Exec>("rustAndroid") {
                    description = "Builds the $app Rust core's Android libraries."
                    commandLine(repository.resolve("scripts/rust-core"), app, "android")
                    inputs.dir(crate.resolve("src"))
                    inputs.files(crate.resolve("Cargo.toml"), crate.resolve("Cargo.lock"))
                    outputs.dir(crate.resolve("generated/jniLibs"))
                }
            tasks.named("preBuild") { dependsOn(rustAndroid) }

            dependencies {
                "implementation"(project(":$app-core"))
                "implementation"(project(":sevenkit"))
                "storeImplementation"(project(":billing"))
                "implementation"("net.java.dev.jna:jna:5.17.0@aar")
                "implementation"("androidx.activity:activity-compose:1.12.2")
                "implementation"("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
                "implementation"("androidx.core:core-ktx:1.17.0")
                "androidTestImplementation"(platform("androidx.compose:compose-bom:2026.09.00"))
                "androidTestImplementation"("androidx.compose.ui:ui-test-junit4")
                "androidTestImplementation"("androidx.compose.ui:ui-test-junit4-accessibility")
                "androidTestImplementation"("androidx.test:runner:1.7.0")
                "androidTestImplementation"("androidx.test.ext:junit:1.3.0")
                "debugImplementation"("androidx.compose.ui:ui-test-manifest")
            }
        }
}
