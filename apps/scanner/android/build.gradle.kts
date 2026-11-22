// Document Scanner for Android, on the shared base (android/README.md). Its rules live in the Rust crate
// apps/scanner/core, bound through :scanner-core; this module keeps only platform work and UI.
extra["sevenApp"] = "scanner"

extra["sevenGenericName"] = "Document Scanner"

apply(plugin = "seven.base-app")

configure<com.android.build.api.dsl.ApplicationExtension> {
    // AssetManager.openFd requires the model uncompressed in the final APK.
    androidResources { noCompress += "traineddata" }
    // ML Kit's document scanner is a Google Play services exception, like Play Billing: personal
    // and store only. Generic captures with CameraX alone (src/generic).
    sourceSets.getByName("personal").java.srcDir("src/gms/kotlin")
    sourceSets.getByName("store").java.srcDir("src/gms/kotlin")
    // Instrumented tests read the Swift-written backup index the core's golden tests use.
    sourceSets.getByName("androidTest").assets.srcDir("../core/tests/fixtures")
    // JavaCPP's jars also carry headers, CMake files and the tesseract CLI under lib/<abi>/; only
    // the shared libraries belong in the APK.
    packaging {
        resources.excludes += listOf("META-INF/native-image/**", "META-INF/versions/**")
        for (abi in listOf("arm64-v8a", "x86_64")) {
            jniLibs.excludes += listOf("lib/$abi/include/**", "lib/$abi/lib/**", "lib/$abi/tesseract")
            resources.excludes += listOf("lib/$abi/include/**", "lib/$abi/lib/**", "lib/$abi/tesseract")
        }
    }
}

dependencies {
    // Stable native stroke authoring; portable persistence remains in scanner-core.
    "implementation"("androidx.ink:ink-authoring:1.0.0")
    "implementation"("androidx.ink:ink-brush:1.0.0")
    "implementation"("androidx.ink:ink-strokes:1.0.0")
    // Maintained crop gestures; our Material screen owns actions, review and exact edge entry.
    "implementation"("com.vanniktech:android-image-cropper:4.7.0")
    // Camera capture: generic's scanner, and the fallback wherever ML Kit's is unavailable.
    "implementation"("androidx.camera:camera-camera2:1.6.2")
    "implementation"("androidx.camera:camera-lifecycle:1.6.2")
    "implementation"("androidx.camera:camera-compose:1.6.2")
    "implementation"("androidx.exifinterface:exifinterface:1.4.2")
    // Drag to reorder pages in Arrange (the iOS list's edit-mode move).
    "implementation"("sh.calvin.reorderable:reorderable:3.1.0")
    // Searchable PDFs: an invisible, positioned text layer (text render mode 3). BouncyCastle is
    // only for encrypted PDFs, which Scanner never writes or reads with PDFBox.
    "implementation"("com.tom-roush:pdfbox-android:2.0.27.0") { exclude(group = "org.bouncycastle") }
    "implementation"(project(":sevenocr"))
    "personalImplementation"("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    "storeImplementation"("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    "testImplementation"("junit:junit:4.13.2")
}
