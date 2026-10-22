# ✅ ProGuard optimizations for MusicVault

# Preserve Room database entities and DAOs
-keep @androidx.room.Entity class com.musicvault.app.data.** { *; }

# Preserve Media3 classes for audio playback
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Preserve Compose runtime
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class androidx.compose.runtime.** { *; }


# NewPipeExtractor (primary streaming resolver)
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
# Rhino bundles optional Java SE scripting, bean introspection, and Dynalink
# integrations that are unavailable on Android. They are not used by the
# extractor's Android runtime.
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-keep class org.schabi.newpipe.extractor.** { *; }

# Preserve yt-dlp android classes
# ExtraFieldUtils registers ZIP extra fields through Class.newInstance().
# Keep their public no-arg constructors for yt-dlp's Python ZIP extraction.
-keep class * implements org.apache.commons.compress.archivers.zip.ZipExtraField {
    public <init>();
}
-keep class com.yausername.youtubedl_android.** { *; }
-dontwarn com.yausername.youtubedl_android.**

# Preserve FFmpeg native interface
-keep class com.yausername.ffmpeg.** { *; }
-keepclassmembers class com.yausername.ffmpeg.** { *; }

# Preserve full yt-dlp/android native classes
-keep class com.yausername.youtubedl_android.** { *; }
-keepclassmembers class com.yausername.youtubedl_android.** { *; }

# Preserve app UI classes (NativeTrackHolder, PlaylistDetailAdapter, etc.)
-keep class com.musicvault.app.ui.FastFeedAdapters** { *; }
-keepclassmembers class com.musicvault.app.ui.FastFeedAdapters** { *; }

# Preserve MusicVaultApp and ViewModel classes
-keep class com.musicvault.app.ui.MusicVaultAppKt { *; }
-keep class com.musicvault.app.ui.MusicVaultViewModel { *; }
-keepclassmembers class com.musicvault.app.ui.MusicVaultViewModel { *; }

# Preserve RecyclerView and adapter classes used in AndroidView
-keep class androidx.recyclerview.widget.RecyclerView { *; }
-keep class androidx.recyclerview.widget.LinearLayoutManager { *; }
-keep class androidx.recyclerview.widget.DefaultItemAnimator { *; }

# Preserve Compose AndroidView classes
-keep class androidx.compose.ui.viewinterop.AndroidView { *; }
-keep class androidx.compose.ui.platform.LocalView { *; }

# Remove debug logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Optimize bytecode more gently for debug
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

# ✅ CRITICAL: Preserve native libs and avoid stripping in debug
-keepclassmembers class * extends android.app.Application {
    public void *;
}

# Preserve all classes in the data package for debug
-keep class com.musicvault.app.data.** { *; }
-keepclassmembers class com.musicvault.app.data.** { *; }

# Cast instantiates this provider from the string in AndroidManifest metadata.
-keep class com.musicvault.app.cast.MusicVaultCastOptionsProvider { public <init>(); public *; }
