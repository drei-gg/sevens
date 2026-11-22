val android = extensions.getByType(com.android.build.api.dsl.ApplicationExtension::class.java)
android.defaultConfig.buildConfigField("boolean", "WEAR_ENABLED", "false")
listOf("personal", "store").forEach { flavor ->
    android.productFlavors.getByName(flavor).buildConfigField("boolean", "WEAR_ENABLED", "true")
    android.sourceSets.getByName(flavor) {
        java.srcDir("src/wearBridge/kotlin")
        res.srcDir("src/wearBridge/res")
    }
    dependencies {
        "${flavor}Implementation"(project(":bloom-wire"))
        "${flavor}Implementation"("com.google.android.gms:play-services-wearable:20.0.1")
        "${flavor}Implementation"("androidx.startup:startup-runtime:1.1.1")
    }
}
