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
# EncryptedSharedPreferences depends on Tink, which references errorprone annotations
# at compile time only. R8 can't find them in the release classpath — suppress the warning.
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.errorprone.annotations.**
