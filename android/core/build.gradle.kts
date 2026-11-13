// Fast's shared logic is the Rust crate apps/fast/core. This module holds its generated Kotlin
// bindings (UniFFI) and a small Kotlin façade; it has no fasting rules of its own.
plugins { kotlin("jvm") }

kotlin { jvmToolchain(21) }

val repository = rootDir.parentFile
val crate = repository.resolve("apps/fast/core")

val rustBindings by
    tasks.registering(Exec::class) {
        description = "Generates the Kotlin bindings for the Rust core."
        commandLine(repository.resolve("scripts/rust-core"), "fast", "bindings")
        inputs.dir(crate.resolve("src"))
        inputs.files(crate.resolve("Cargo.toml"), crate.resolve("Cargo.lock"), crate.resolve("uniffi.toml"))
        outputs.dir(crate.resolve("generated/kotlin"))
    }

sourceSets.main { kotlin.srcDir(crate.resolve("generated/kotlin")) }

tasks.named("compileKotlin") { dependsOn(rustBindings) }

// JVM tests load the crate built for this machine (target/debug); the app ships Android builds.
tasks.named<Test>("test") {
    dependsOn(rustBindings)
    systemProperty("jna.library.path", crate.resolve("target/debug").absolutePath)
}

dependencies {
    // The app supplies JNA as an Android library; JVM tests use the jar.
    compileOnly("net.java.dev.jna:jna:5.17.0")
    testImplementation("net.java.dev.jna:jna:5.17.0")
    testImplementation("junit:junit:4.13.2")
}
