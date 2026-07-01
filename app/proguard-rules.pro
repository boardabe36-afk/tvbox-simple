# Default ProGuard rules for TVBox Simple

# Keep model classes used in JSON (Gson)
-keep class com.simple.tvbox.model.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
