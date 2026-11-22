import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "gg.seven.ocr"
    compileSdk = 37
    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources { noCompress += "traineddata" }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

/**
 * Tesseract's English model (tessdata_fast 4.1.0, Apache-2.0), fetched once at a pinned tag,
 * checked against its SHA-256 and bundled as an asset. Recognition itself never uses the network.
 */
abstract class FetchTessdata : DefaultTask() {
    @get:Input abstract val url: Property<String>

    @get:Input abstract val sha256: Property<String>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val file = outputDir.get().file("tessdata/eng.traineddata").asFile
        fun digest(): String =
            MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
                "%02x".format(it)
            }
        if (file.isFile && digest() == sha256.get()) return
        file.parentFile.mkdirs()
        URI(url.get()).toURL().openStream().use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        check(digest() == sha256.get()) { "eng.traineddata does not match its pinned SHA-256" }
    }
}

val fetchTessdata =
    tasks.register<FetchTessdata>("fetchTessdata") {
        url.set("https://github.com/tesseract-ocr/tessdata_fast/raw/4.1.0/eng.traineddata")
        sha256.set("7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2")
        outputDir.set(layout.buildDirectory.dir("generated/tessdata"))
    }

configure<com.android.build.api.variant.LibraryAndroidComponentsExtension> {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(fetchTessdata, FetchTessdata::outputDir)
    }
}

val tesseract = "5.5.3-1.5.14"
val leptonica = "1.87.0-1.5.14"
val javacpp = "1.5.14"

dependencies {
    // On-device OCR: Tesseract through JavaCPP's presets (arm64 and x86_64 builds exist).
    "implementation"("org.bytedeco:javacpp:$javacpp")
    "implementation"("org.bytedeco:leptonica:$leptonica")
    "implementation"("org.bytedeco:tesseract:$tesseract")
    for (abi in listOf("android-arm64", "android-x86_64")) {
        "implementation"("org.bytedeco:javacpp:$javacpp:$abi")
        "implementation"("org.bytedeco:leptonica:$leptonica:$abi")
        "implementation"("org.bytedeco:tesseract:$tesseract:$abi")
    }
}
