plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

extra["sevenApp"] = "fast"

extra["sevenGenericName"] = "Fast Tracker"

apply(plugin = "seven.android-app")

android {
    namespace = "gg.seven.fast"
    // The Rust core (apps/fast/core) for every Android ABI, built by scripts/rust-core.
    sourceSets.getByName("main").jniLibs.srcDir(rootDir.parentFile.resolve("apps/fast/core/generated/jniLibs"))
}

val rustAndroid by
    tasks.registering(Exec::class) {
        description = "Builds the Rust core's Android libraries."
        val repository = rootDir.parentFile
        val crate = repository.resolve("apps/fast/core")
        commandLine(repository.resolve("scripts/rust-core"), "fast", "android")
        inputs.dir(crate.resolve("src"))
        inputs.files(crate.resolve("Cargo.toml"), crate.resolve("Cargo.lock"))
        outputs.dir(crate.resolve("generated/jniLibs"))
    }

tasks.named("preBuild") { dependsOn(rustAndroid) }

dependencies {
    implementation(project(":core"))
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    implementation("com.kizitonwose.calendar:compose:2.9.0")
    implementation("com.patrykandpatrick.vico:compose-m3:2.3.6")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation(project(":sevenkit"))
    "storeImplementation"(project(":billing"))
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
