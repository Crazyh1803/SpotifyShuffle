# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keep class okhttp3.** { *; }

# Gson / data models
# Keep ALL classes that are serialized/deserialized with Gson — R8 strips and renames
# fields in release builds which breaks Gson's reflection-based serialization entirely.
-keep class com.spotifytrueshuffle.api.** { *; }
-keepclassmembers class com.spotifytrueshuffle.api.** {
    <fields>;
}
-keep class com.spotifytrueshuffle.cache.** { *; }
-keepclassmembers class com.spotifytrueshuffle.cache.** {
    <fields>;
}

# Security-crypto / Google Tink
# EncryptedSharedPreferences depends on Tink internally. Tink in turn references several
# compile-time-only or optional libraries (errorprone annotations, Google API Client,
# Joda Time) that are not shipped with the APK. Suppress R8 missing-class warnings for
# all of them — none are used by the code paths we actually call.
-keep class androidx.security.crypto.** { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
