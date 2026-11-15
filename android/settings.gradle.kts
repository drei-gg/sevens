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

include(":core", ":sevenkit", ":billing", ":fast", ":fast-wire", ":wear")

project(":fast").projectDir = file("../apps/fast/android")

// Fast's Wear OS companion and the wire protocol it shares with the phone.
project(":wear").projectDir = file("../apps/fast/wear")

project(":fast-wire").projectDir = file("../apps/fast/wear/wire")
