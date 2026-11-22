// Cleaner for Android, on the shared base (android/README.md). Its rules live in the Rust crate
// apps/cleaner/core, bound through :cleaner-core; this module keeps only platform work and UI.
extra["sevenApp"] = "cleaner"

extra["sevenGenericName"] = "Cleaner"

apply(plugin = "seven.base-app")

configure<com.android.build.api.dsl.ApplicationExtension> {
    sourceSets.getByName("androidTest").assets.srcDir("../Kit/Tests/CleanerPlatformTests/Fixtures")
}

dependencies {
    // Native bounded video exports; MediaCodec/GL and the maintained MP4 muxer stay in Media3.
    "implementation"("androidx.media3:media3-transformer:1.11.1")
    "implementation"("androidx.media3:media3-effect:1.11.1")
    // PhoneNumberKit's Android counterpart: the same Google numbering-plan metadata (9.0.43).
    "implementation"("com.googlecode.libphonenumber:libphonenumber:9.0.43")
    // Reading and copying capture metadata on compressed copies (Image I/O's role on iOS).
    "implementation"("androidx.exifinterface:exifinterface:1.4.2")
    // HEIC still copies (ImageIO's HEIC destination on iOS); Bitmap.compress has no HEIC format.
    "implementation"("androidx.heifwriter:heifwriter:1.2.0")
    // Pinch and double-tap zoom for full-resolution inspection (iOS ZoomImage).
    "implementation"("me.saket.telephoto:zoomable:0.18.0")
    "testImplementation"("junit:junit:4.13.2")
}
