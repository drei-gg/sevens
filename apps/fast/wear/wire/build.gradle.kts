// The Wear OS wire protocol: plain data types and a JSON codec shared by the watch app and the
// phone's Play-services flavors. No Fast rules live here; the phone applies requests through the
// Rust core.
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin { jvmToolchain(21) }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
