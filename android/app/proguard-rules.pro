# Keep UniFFI Kotlin bindings and JNA native interop types intact under R8
-keep class uniffi.plasmawave_core.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }
-keepclassmembers class * extends com.sun.jna.Callback { *; }

-dontwarn com.sun.jna.**
-dontwarn java.awt.**
-dontwarn javax.naming.**
