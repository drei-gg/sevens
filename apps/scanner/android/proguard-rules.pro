# The Rust core's bindings: JNA finds its native structures and callbacks by reflection.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class gg.seven.scanner.core.** { *; }
-dontwarn java.awt.**
