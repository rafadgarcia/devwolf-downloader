# Devwolf Downloader R8 / ProGuard rules
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod

# Keep Capacitor bridge, plugins, and @PluginMethod entry points
-keep class com.getcapacitor.** { *; }
-keep @com.getcapacitor.annotation.CapacitorPlugin class * { *; }
-keepclassmembers class * extends com.getcapacitor.Plugin {
    @com.getcapacitor.annotation.PluginMethod <methods>;
    @com.getcapacitor.annotation.ActivityCallback <methods>;
}

# Keep Devwolf native service, plugin, and resolver contract classes
-keep class com.devwolf.downloader.** { *; }

# Keep youtubedl-android runtime, mappers, & JNI bindings
-keep class com.yausername.** { *; }
-dontwarn com.yausername.**

# Keep Jackson (used via reflection by YoutubeDLUpdater for GitHub Releases JSON & VideoInfo mappers)
-keep class com.fasterxml.jackson.** { *; }
-keep @com.fasterxml.jackson.annotation.** class * { *; }
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* <fields>;
    @com.fasterxml.jackson.annotation.* <methods>;
}
-dontwarn com.fasterxml.jackson.**

# Keep Apache Commons Compress/IO & XZ (used by youtubedl-android binary extraction/updater)
-keep class org.apache.commons.** { *; }
-keep class org.tukaani.xz.** { *; }
-dontwarn org.apache.commons.**
-dontwarn org.tukaani.xz.**

# Keep Kotlin reflection/metadata & Chromium/WebView bridge hooks
-keep class kotlin.Metadata { *; }
-keep class kotlin.** { *; }
-keep class org.chromium.** { *; }
-dontwarn org.chromium.**

# Keep OkHttp / Okio / Jsoup / Tink cleanly
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.api.client.http.**
-dontwarn org.joda.time.**
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
