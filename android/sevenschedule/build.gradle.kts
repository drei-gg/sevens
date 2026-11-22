// SevenKit's scheduler for Android: system wakes, delivery permissions, the rolling reminder
// scheduler and recovery after boot or clock changes. Alarm, Fast and Bloom use it; when things
// happen comes from each app's Rust core (crates/seven-schedule), never from here.
plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "gg.seven.kit.schedule"
    compileSdk = 37
    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    // The recovery worker. Every app that uses this module already ships this version.
    api("androidx.work:work-runtime-ktx:2.11.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
