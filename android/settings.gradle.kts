pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SevensAndroid"

include(":core", ":sevenkit", ":sevenocr", ":billing", ":fast", ":fast-wire", ":wear")

project(":fast").projectDir = file("../apps/fast/android")

// Fast's Wear OS companion and the wire protocol it shares with the phone.
project(":wear").projectDir = file("../apps/fast/wear")

project(":fast-wire").projectDir = file("../apps/fast/wear/wire")

// The other Sevens, on the shared base (seven.base-app): each app and its Rust core's bindings.
for (app in listOf("qr", "alarm", "scanner", "cleaner", "vault", "bloom")) {
    include(":$app", ":$app-core")
    project(":$app").projectDir = file("../apps/$app/android")
    project(":$app-core").projectDir = file("../apps/$app/android/core")
}

include(":bloom-wear", ":bloom-wire")
project(":bloom-wear").projectDir = file("../apps/bloom/wear")
project(":bloom-wire").projectDir = file("../apps/bloom/wear/wire")
