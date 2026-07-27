# ── Kotlin ───────────────────────────────────────────────────────────────────
# Keep Kotlin metadata (required for reflection, coroutines, etc.)
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.Unit

# ── Retrofit + Kotlin coroutines ─────────────────────────────────────────────
# Retrofit uses reflection on generic method signatures and parameter annotations.
# InnerClasses + EnclosingMethod are required alongside Signature.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault

# Retrofit ships its own consumer rules (META-INF/proguard/retrofit2.pro) which already cover
# the @retrofit2.http.* member keeps, the R8 full-mode "-if interface" rule and these -dontwarns.
# A blanket "-keep class retrofit2.** { *; }" is therefore redundant AND harmful: it exempts the
# whole library from shrinking, optimisation and obfuscation. Deliberately not used.
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# Keep all @retrofit2.http.* annotated interface methods (the actual API calls).
# Without this R8 can remove interface methods it thinks are uncalled.
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# R8 full mode: proxy-created interfaces have no visible subtypes, so R8 nulls them out.
# These two rules keep any interface that contains @retrofit2.http.* methods.
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface * extends <1>

# Keep generic signatures on Retrofit's Call/Response and Kotlin's Continuation so
# that Gson can read the parameterised type (e.g. PagingObject<Track>) at runtime.
# Without these rules you get: Class cannot be cast to ParameterizedType.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# ── OkHttp ───────────────────────────────────────────────────────────────────
# OkHttp ships its own consumer rules (META-INF/proguard/okhttp3.pro): a -keepnames on
# PublicSuffixDatabase plus the platform/conscrypt/bouncycastle -dontwarns. Blanket-keeping
# okhttp3.** is redundant and exempts the app's largest dependency from shrinking entirely.
# The -dontwarns below are kept (they cost nothing in the output) purely as build-time safety.
-dontwarn okhttp3.**
-dontwarn okio.**

# ── Gson / data models ────────────────────────────────────────────────────────
# Keep ALL classes that are serialised/deserialised with Gson.
# R8 strips and renames fields in release builds, breaking Gson's reflection.
-keep class com.spotifytrueshuffle.api.** { *; }
-keepclassmembers class com.spotifytrueshuffle.api.** {
    <fields>;
}
-keep class com.spotifytrueshuffle.cache.** { *; }
-keepclassmembers class com.spotifytrueshuffle.cache.** {
    <fields>;
}

# Gson TypeToken — R8 strips generic signatures from anonymous TypeToken subclasses.
# Without this, fromJson() on generic types (List<Artist>, Map<String, GapArtistEntry>)
# silently returns null/empty, causing the app to behave as if caches are always empty.
# Both ArtistTrackCache and GapArtistCache use TypeToken — both are broken without this rule.
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# Keep fields annotated with @SerializedName so Gson can map JSON keys to fields
# even if R8 renames the field for other reasons.
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ── Misc annotation libraries ─────────────────────────────────────────────────
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

# ── Security-crypto / Google Tink ─────────────────────────────────────────────
# EncryptedSharedPreferences depends on Tink internally. Tink references several
# optional libraries not shipped in the APK. Suppress the missing-class warnings.
-keep class androidx.security.crypto.** { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
