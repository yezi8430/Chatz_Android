# ============================================================
# YeGotify ProGuard / R8 规则
# ============================================================

# ---------- 1. 应用自己的数据类（Gson + Room 反射）----------
-keep class asia.guojuice.yezigotify.GotifyMessage { *; }
-keep class asia.guojuice.yezigotify.PagedMessages { *; }
-keep class asia.guojuice.yezigotify.PagingInfo { *; }
-keep class asia.guojuice.yezigotify.GotifyApp { *; }
-keep class asia.guojuice.yezigotify.MessageEntity { *; }
-keep class asia.guojuice.yezigotify.ntfy.NtfyMessage { *; }
-keep class asia.guojuice.yezigotify.ntfy.NtfyMessage$* { *; }
-keep class asia.guojuice.yezigotify.GotifyService$GotifyWSEvent { *; }

#  新增：App 独立勿扰配置（Gson 序列化到 prefs）
-keep class asia.guojuice.yezigotify.ui.dnd.AppDndConfig { *; }
-keepclassmembers class asia.guojuice.yezigotify.ui.dnd.AppDndConfig {
    <fields>;
}
-keep class asia.guojuice.yezigotify.ui.dnd.PerAppDndManager { *; }

# 保底：应用所有类（class + interface）
-keep class asia.guojuice.yezigotify.** { *; }

# ---------- 2. Gson ----------
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ---------- 3. Room ----------
-keep class * extends androidx.room3.RoomDatabase { *; }
-keep @androidx.room3.Entity class * { *; }
-keep @androidx.room3.Dao interface * { *; }
-keepclassmembers class * extends androidx.room3.RoomDatabase {
    <methods>;
}

# ---------- 4. Retrofit 官方推荐规则（关键！）----------
# 保留反射用的泛型、注解、参数注解
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# 保留带 Retrofit 注解的接口方法
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# 保留 Response / Call 泛型
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.jvm.internal.BaseContinuationImpl

# 忽略可选依赖
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn kotlin.Unit

# ---------- 5. OkHttp ----------
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------- 6. Glide ----------
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.resource.bitmap.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# ---------- 7. Compose 保底 ----------
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.platform.** { *; }

# ---------- 8. FTS4 相关（显式声明，避免未来 R8 全模式下误判）----------
-keep @androidx.room3.Fts4 class * { *; }
-keep class asia.guojuice.yezigotify.MessageFtsEntity { *; }
-keepclassmembers class asia.guojuice.yezigotify.MessageFtsEntity {
    <fields>;
    <init>(...);
}

# ---------- 9. Kotlin 协程（suspendCancellableCoroutine 相关）----------
# SafeContinuation / CoroutineSingletons 被协程库内部反射使用，
# R8 full mode 下可能因为"没有直接引用"被移除，导致挂起函数崩溃
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keep class kotlin.coroutines.SafeContinuation { *; }
-keep class kotlinx.coroutines.internal.ScopeCoroutine { *; }
-keepclassmembers class kotlinx.coroutines.flow.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ---------- 10. Room 的 FTS 触发器和虚拟表 ----------
# 触发器名由 Room 生成，字符串引用，R8 不碰；
# 但 Room 生成的 Impl 类如果被 R8 内联合并，可能丢失 openHelper 回调。
# 显式禁止对 Room 生成类做激进内联
-keep class * extends androidx.room3.RoomDatabase { *; }
-keep class androidx.room3.util.* { *; }
-keep class androidx.sqlite.db.framework.** { *; }

# ---------- 11. Material You（即使不主动调用，DynamicColors 也可能被自动初始化）----------
-keep class com.google.android.material.color.** { *; }
-dontwarn com.google.android.material.color.**

# ---------- 12. Kotlin 元数据（保留注解，有助于反射和调试）----------
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep class kotlin.Metadata { *; }

# ---------- 13. R8 full mode 下防止注解剥离 ----------
-keep @interface androidx.annotation.RequiresApi
-keep @interface androidx.annotation.Keep