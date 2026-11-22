// QR Code Kit for Android, on the shared base (android/README.md). Its rules live in the Rust crate
// apps/qr/core, bound through :qr-core; this module keeps only platform work and UI.
import java.util.Properties

extra["sevenApp"] = "qr"

extra["sevenGenericName"] = "QR Code Kit"

apply(plugin = "seven.base-app")

// Where "Check for newer lists" looks and which Ed25519 keys may sign the lists (SPEC §4.3), the
// counterpart of iOS Brand.xcconfig. Public builds have no key, so the button hides; a brand sets
// listsKeyCurrent / listsKeyNext (64 hex characters each) in its private identity.properties or
// with -Psevens.listsKeyCurrent=… . The support address works the same way (supportEmail).
val qrIdentity =
    Properties().apply {
        (findProperty("sevens.brandDir") as String?)
            ?.let { file(it).resolve("qr/android/identity.properties") }
            ?.takeIf { it.isFile }
            ?.inputStream()
            ?.use(::load)
    }

fun qrSetting(key: String, fallback: String = ""): String =
    ((findProperty("sevens.$key") as String?) ?: qrIdentity.getProperty(key, fallback)).trim()

fun qrLiteral(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

configure<com.android.build.api.dsl.ApplicationExtension> {
    defaultConfig {
        buildConfigField("String", "REPOSITORY_URL", qrLiteral(qrSetting("repositoryUrl", "https://github.com/drei-gg/sevens")))
        buildConfigField("String", "LISTS_KEY_CURRENT", qrLiteral(qrSetting("listsKeyCurrent")))
        buildConfigField("String", "LISTS_KEY_NEXT", qrLiteral(qrSetting("listsKeyNext")))
        buildConfigField("String", "SUPPORT_EMAIL", qrLiteral(qrSetting("supportEmail")))
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

// What's new (SPEC §11): apps/qr/CHANGELOG.md, the one changelog both phones ship, copied into
// generated assets at build time so it is never duplicated by hand (iOS bundles the same file).
abstract class QrChangelogAssets : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val changelog: RegularFileProperty

    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = output.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        changelog.get().asFile.copyTo(dir.resolve("CHANGELOG.md"))
    }
}

val qrChangelogAssets =
    tasks.register<QrChangelogAssets>("qrChangelogAssets") {
        changelog.set(rootDir.parentFile.resolve("apps/qr/CHANGELOG.md"))
    }

configure<com.android.build.api.variant.ApplicationAndroidComponentsExtension> {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(qrChangelogAssets, QrChangelogAssets::output)
    }
}

dependencies {
    // Camera: CameraX preview, analysis and torch (the DataScannerViewController counterpart).
    "implementation"("androidx.camera:camera-core:1.6.2")
    "implementation"("androidx.camera:camera-camera2:1.6.2")
    "implementation"("androidx.camera:camera-lifecycle:1.6.2")
    "implementation"("androidx.camera:camera-view:1.6.2")
    // Reading codes from the camera, photos, the clipboard and shared images (Vision's counterpart).
    "implementation"("io.github.zxing-cpp:android:3.1.1")
    // Making codes: QR, Aztec, PDF417 and Code 128 (CoreImage's generators' counterpart).
    "implementation"("com.google.zxing:core:3.5.4")
    // Peek and Check for newer lists: the only two places that use the network.
    "implementation"("com.squareup.okhttp3:okhttp:4.12.0")
    // Ed25519 signature check of downloaded lists (CryptoKit's Curve25519 counterpart).
    "implementation"("org.bouncycastle:bcprov-jdk18on:1.83")
    // Opening links in the in-app browser (SFSafariViewController's counterpart).
    "implementation"("androidx.browser:browser:1.10.0")
    // Make's colour picker (SwiftUI ColorPicker's counterpart).
    "implementation"("com.github.skydoves:colorpicker-compose:1.1.2")
    "testImplementation"("junit:junit:4.13.2")
}

// JVM tests that call the core load the crate's host build, as :qr-core's own tests do.
tasks.withType<Test>().configureEach {
    systemProperty("jna.library.path", rootDir.parentFile.resolve("apps/qr/core/target/debug").absolutePath)
}
dependencies { "testImplementation"("net.java.dev.jna:jna:5.17.0") }
