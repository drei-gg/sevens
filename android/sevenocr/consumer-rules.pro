# Tesseract through JavaCPP: native code calls back into these classes by name.
-keep class org.bytedeco.** { *; }
-keepclassmembers class * extends org.bytedeco.javacpp.Pointer { *; }
-dontwarn org.bytedeco.**
-dontwarn java.awt.**
-dontwarn javax.**
