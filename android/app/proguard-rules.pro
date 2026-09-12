# ✅ ProGuard optimizations for MusicVault

# Preserve Room database entities and DAOs
-keep @androidx.room.Entity class com.musicvault.app.data.** { *; }

# Preserve Media3 classes for audio playback
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Preserve Compose runtime
-keep class androidx.compose.runtime.** { *; }

# Preserve yt-dlp android classes
-keep class com.yausername.youtubedl_android.** { *; }
-dontwarn com.yausername.youtubedl_android.**

# Remove debug logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Optimize bytecode
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# Remove unused code more aggressively
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Preserve serialization for data classes
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Remove reflection warnings for Kotlin coroutines
-dontwarn kotlinx.coroutines.debug.**
