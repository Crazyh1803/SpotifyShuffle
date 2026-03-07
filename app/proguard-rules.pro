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

# Security-crypto
-keep class androidx.security.crypto.** { *; }
