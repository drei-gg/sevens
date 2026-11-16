# The Rust core's bindings: JNA finds its native structures and callbacks by reflection.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class gg.seven.qr.core.** { *; }
-dontwarn java.awt.**
