plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "gg.seven.billing"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":sevenkit"))
    implementation("com.android.billingclient:billing:9.1.0")
}
