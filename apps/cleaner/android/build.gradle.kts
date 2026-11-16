// Cleaner for Android, on the shared base (android/README.md). Its rules live in the Rust crate
// apps/cleaner/core, bound through :cleaner-core; this module keeps only platform work and UI.
extra["sevenApp"] = "cleaner"

extra["sevenGenericName"] = "Cleaner"

apply(plugin = "seven.base-app")

dependencies {
    // PhoneNumberKit's Android counterpart: the same Google numbering-plan metadata (9.0.43).
    "implementation"("com.googlecode.libphonenumber:libphonenumber:9.0.43")
    // Reading and copying capture metadata on compressed copies (Image I/O's role on iOS).
    "implementation"("androidx.exifinterface:exifinterface:1.4.2")
    // Pinch and double-tap zoom for full-resolution inspection (iOS ZoomImage).
    "implementation"("me.saket.telephoto:zoomable:0.18.0")
    "testImplementation"("junit:junit:4.13.2")
}
