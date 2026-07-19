# Keep AirPlay protocol classes
-keep class com.miui.airplaycast.airplay.** { *; }
-keep class com.miui.airplaycast.discovery.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
