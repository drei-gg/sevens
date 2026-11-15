plugins { `kotlin-dsl` }

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("com.android.tools.build:gradle:9.5.1")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
    implementation("org.jetbrains.kotlin:kotlin-serialization:2.2.21")
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.2.21")
}

gradlePlugin {
    plugins {
        register("sevenApp") {
            id = "seven.android-app"
            implementationClass = "SevenAppPlugin"
        }
    }
}
