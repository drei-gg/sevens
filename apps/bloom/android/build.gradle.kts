extra["sevenApp"] = "bloom"

extra["sevenGenericName"] = "Cycle & Health"

apply(plugin = "seven.base-app")

// The calendar engine is a Rust path dependency. Changes must invalidate both native tasks.
tasks.named("rustAndroid") { inputs.dir(projectDir.resolve("../../alarm/core/src")) }

dependencies {
    "implementation"("androidx.fragment:fragment-ktx:1.9.1")
    "implementation"("androidx.biometric:biometric:1.1.0")
    "implementation"("androidx.health.connect:connect-client:1.1.0")
    "implementation"("androidx.glance:glance-appwidget:1.2.0")
    "implementation"("androidx.glance:glance-material3:1.2.0")
    "implementation"("com.kizitonwose.calendar:compose:2.9.0")
    "implementation"("com.patrykandpatrick.vico:compose-m3:2.3.6")
    // Reminders, wakes and boot/clock recovery, shared with Alarm and Fast.
    "implementation"(project(":sevenschedule"))
    "implementation"("androidx.work:work-runtime-ktx:2.11.0")
}

// Google Play services transport is confined to personal/store phone builds.
apply(from = "wear-bridge.gradle.kts")
