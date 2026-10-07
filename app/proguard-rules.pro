-keep class com.adb.kitty.** { *; }
-dontwarn com.adb.kitty.**

-keep class libs.libs.libs.** { *; }
-dontwarn libs.libs.libs.**

-keep class com.topjohnwu.** { *; }
-dontwarn com.topjohnwu.**

-keep class com.android.tools.build.** { *; }
-dontwarn com.android.tools.build.**

-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

-keep class okio.** { *; }
-dontwarn okio.**

-keep class dalvik.** { *; }
-dontwarn dalvik.**

-keep class org.** { *; }
-dontwarn org.**

-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keep class kotlin.coroutines.** { *; }
-dontwarn kotlin.coroutines.**

-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn org.lsposed.hiddenapibypass.**

-keep class io.nayuki.qrcodegen.** { *; }
-dontwarn io.nayuki.qrcodegen.**

-keep class com.google.** { *; }
-dontwarn com.google.**

-dontrepackage

-keep class com.android.tools.r8.RecordTag { *; }
-dontwarn com.android.tools.r8.RecordTag

-keepattributes RuntimeInvisibleAnnotations, RuntimeInvisibleParameterAnnotations, RuntimeInvisibleTypeAnnotations

-ignorewarnings