# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keep class okhttp3.** { *; }

# Gson / data models
-keep class com.spotifytrueshuffle.api.** { *; }
-keepclassmembers class com.spotifytrueshuffle.api.** {
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
