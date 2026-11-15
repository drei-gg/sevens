plugins {
    id("com.android.library")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "gg.seven.kit"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(platform("androidx.compose:compose-bom:2026.09.00"))
    // Material 3 Expressive (button groups, loading indicator, toolbars) is in 1.5; the BOM still pins 1.4.
    api("androidx.compose.material3:material3:1.5.0-rc01")
    api("androidx.compose.material:material-icons-extended")
    api("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
}
