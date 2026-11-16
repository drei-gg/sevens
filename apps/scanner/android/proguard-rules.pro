# The Rust core's bindings: JNA finds its native structures and callbacks by reflection.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class gg.seven.scanner.core.** { *; }
-dontwarn java.awt.**

# Tesseract through JavaCPP: native code calls back into these classes by name.
-keep class org.bytedeco.** { *; }
-keepclassmembers class * extends org.bytedeco.javacpp.Pointer { *; }
-dontwarn org.bytedeco.**
-dontwarn java.awt.**
-dontwarn javax.**

# PDFBox: fonts and resources load by name; BouncyCastle (encryption) is intentionally absent.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn com.gemalto.jp2.**

# ML Kit document scanner (personal and store): its components are found by name at run time.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_document_scanner.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-dontwarn com.google.mlkit.**
