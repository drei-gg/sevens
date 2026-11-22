import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.*
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * `seven.rust-core`: the Kotlin face of an app's Rust crate (apps/<app>/core). The module holds the
 * crate's generated UniFFI bindings plus any thin façade, and runs JVM tests against the crate built
 * for this machine. It has no rules of its own: those live in Rust.
 *
 * The app is the directory above `android/core`, so `apps/qr/android/core` binds `apps/qr/core`.
 */
class SevenRustCorePlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            val repository = rootDir.parentFile
            val app = projectDir.parentFile.parentFile.name
            val crate = repository.resolve("apps/$app/core")
            require(crate.resolve("Cargo.toml").isFile) { "seven.rust-core: no crate at $crate" }

            configure<KotlinJvmProjectExtension> { jvmToolchain(21) }

            val rustBindings =
                tasks.register<Exec>("rustBindings") {
                    description = "Generates the Kotlin bindings for the $app Rust core."
                    commandLine(repository.resolve("scripts/rust-core"), app, "bindings")
                    inputs.dir(crate.resolve("src"))
                    // Shared Rust the crates depend on by path (crates/seven-schedule).
                    inputs.dir(repository.resolve("crates"))
                    inputs.files(
                        crate.resolve("Cargo.toml"),
                        crate.resolve("Cargo.lock"),
                        crate.resolve("uniffi.toml"),
                    )
                    outputs.dir(crate.resolve("generated/kotlin"))
                }

            extensions
                .getByType<org.gradle.api.tasks.SourceSetContainer>()
                .named("main") { java.srcDir(crate.resolve("generated/kotlin")) }
            tasks.named("compileKotlin") { dependsOn(rustBindings) }

            // JVM tests load the crate's debug build for this machine; the app ships Android builds.
            tasks.named<Test>("test") {
                dependsOn(rustBindings)
                systemProperty("jna.library.path", crate.resolve("target/debug").absolutePath)
            }

            dependencies {
                // The app supplies JNA as an Android library; JVM tests use the jar.
                "compileOnly"("net.java.dev.jna:jna:5.17.0")
                "testImplementation"("net.java.dev.jna:jna:5.17.0")
                "testImplementation"("junit:junit:4.13.2")
            }
        }
}
