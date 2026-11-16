// Vault for Android, on the shared base (android/README.md). Its rules and its encryption (format
// version 2, age) live in the Rust crate apps/vault/core, bound through :vault-core; this module
// keeps only platform work and UI.
extra["sevenApp"] = "vault"

extra["sevenGenericName"] = "Vault"

apply(plugin = "seven.base-app")

configure<com.android.build.api.dsl.ApplicationExtension> {
    testOptions.unitTests.all { test ->
        // Kotlin unit tests call the crate through JNA, built for this machine by :vault-core:test.
        test.systemProperty(
            "jna.library.path",
            rootDir.parentFile.resolve("apps/vault/core/target/debug").absolutePath,
        )
        test.dependsOn(":vault-core:test")
    }
}

dependencies {
    "implementation"("androidx.fragment:fragment-ktx:1.9.1")
    "implementation"("androidx.biometric:biometric:1.1.0")
    "implementation"("androidx.work:work-runtime-ktx:2.12.0")
    "implementation"("androidx.documentfile:documentfile:1.1.0")
    "implementation"("androidx.exifinterface:exifinterface:1.4.1")
    "implementation"("androidx.camera:camera-core:1.6.2")
    "implementation"("androidx.camera:camera-camera2:1.6.2")
    "implementation"("androidx.camera:camera-lifecycle:1.6.2")
    "implementation"("androidx.camera:camera-view:1.6.2")
    "implementation"("androidx.media3:media3-exoplayer:1.11.1")
    "implementation"("androidx.media3:media3-ui:1.11.1")
    "implementation"("me.saket.telephoto:zoomable:0.19.0")
    "testImplementation"("junit:junit:4.13.2")
    "testImplementation"("net.java.dev.jna:jna:5.17.0")
    "testImplementation"("org.json:json:20250517")
}
