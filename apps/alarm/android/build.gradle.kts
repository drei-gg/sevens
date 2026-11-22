// Alarm & Loops for Android, on the shared base (android/README.md). Its rules live in the Rust crate
// apps/alarm/core, bound through :alarm-core; this module keeps only platform work and UI.
extra["sevenApp"] = "alarm"

extra["sevenGenericName"] = "Alarm & Loops"

apply(plugin = "seven.base-app")

configure<com.android.build.api.dsl.ApplicationExtension> {
    // The three original tones, shared with iOS (apps/alarm/App/Sounds), as assets.
    sourceSets.getByName("main").assets.srcDir(projectDir.resolve("../App/Sounds"))
}

dependencies {
    // Wakes, delivery access and boot/clock recovery, shared with Fast and Bloom.
    "implementation"(project(":sevenschedule"))
    "implementation"("androidx.glance:glance-appwidget:1.2.0")
    "implementation"("androidx.glance:glance-material3:1.2.0")
    // Match Fast/Bloom: Glance's old transitive WorkManager/Room cannot construct
    // WorkDatabase after release shrinking. Keep the supported worker runtime explicit.
    "implementation"("androidx.work:work-runtime-ktx:2.11.0")
    // Scan-a-code mission, every flavor: CameraX for the camera, ZXing (Java) to decode.
    "implementation"("androidx.camera:camera-camera2:1.6.2")
    "implementation"("androidx.camera:camera-lifecycle:1.6.2")
    "implementation"("androidx.camera:camera-view:1.6.2")
    "implementation"("com.google.zxing:core:3.5.4")
    // History's chart: Vico, the Swift Charts counterpart (as Fast).
    "implementation"("com.patrykandpatrick.vico:compose-m3:2.3.6")
    "testImplementation"("junit:junit:4.13.2")
}
