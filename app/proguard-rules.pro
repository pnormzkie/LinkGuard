# Add project specific ProGuard rules here.

# Temporary compatibility keeps for Gson 2.10.1
-keep class com.linkguard.app.data.** { *; }

# Keep Gson model classes
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
