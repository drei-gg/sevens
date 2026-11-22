// Fast's Wear OS companion. It holds no records: it shows the phone's last snapshot and keeps at
// most one pending request, like the Apple Watch app (apps/fast/Watch). The shared convention
// gives it the phone's application IDs per flavor, which the Data Layer requires to pair them.
plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

extra["sevenApp"] = "fast"

extra["sevenGenericName"] = "Fast Tracker"

apply(plugin = "seven.android-app")

android {
    namespace = "gg.seven.fast.wear"
    // Wear OS 3 and later.
    defaultConfig.minSdk = 30
}

dependencies {
    implementation(project(":fast-wire"))
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.wear.compose:compose-material3:1.7.1")
    implementation("androidx.wear.compose:compose-foundation:1.7.1")
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-expression:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-material3:1.4.2")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.3.0")
    implementation("androidx.wear:wear-ongoing:1.1.0")
    implementation("androidx.concurrent:concurrent-futures:1.3.0")
    // Google Play services' Wearable Data Layer: the platform transport between watch and phone.
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    debugImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
}
