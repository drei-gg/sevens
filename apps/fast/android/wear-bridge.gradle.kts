// The Wear OS bridge (src/wearBridge) for the personal and store flavors only. The Wearable Data
// Layer is Google Play services, a platform exception like Play Billing: the generic flavor's
// sources, manifest and dependency graph never include it. Applied from build.gradle.kts.

val android = extensions.getByType(com.android.build.api.dsl.ApplicationExtension::class.java)

listOf("personal", "store").forEach { flavor ->
    android.sourceSets.getByName(flavor) {
        java.srcDir("src/wearBridge/kotlin")
        res.srcDir("src/wearBridge/res")
    }
    android.sourceSets.getByName("test" + flavor.replaceFirstChar { it.uppercase() }) {
        java.srcDir("src/testWearBridge/kotlin")
    }
    dependencies {
        "${flavor}Implementation"(project(":fast-wire"))
        "${flavor}Implementation"("com.google.android.gms:play-services-wearable:20.0.1")
        "${flavor}Implementation"("androidx.startup:startup-runtime:1.1.1")
        "test${flavor.replaceFirstChar { it.uppercase() }}Implementation"("junit:junit:4.13.2")
    }
}
