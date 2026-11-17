# Devwolf Downloader R8 / ProGuard rules
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*

# Keep Capacitor bridge, plugins, and @PluginMethod entry points
-keep class com.getcapacitor.** { *; }
-keep @com.getcapacitor.annotation.CapacitorPlugin class * { *; }
-keepclassmembers class * extends com.getcapacitor.Plugin {
    @com.getcapacitor.annotation.PluginMethod <methods>;
    @com.getcapacitor.annotation.ActivityCallback <methods>;
}

# Keep Devwolf native service, plugin, and resolver contract classes
-keep class com.devwolf.downloader.** { *; }

# Keep youtubedl-android runtime & JNI bindings
-keep class com.yausername.** { *; }
-dontwarn com.yausername.**

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
