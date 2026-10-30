-keep class libv2ray.** { *; }
-keep class go.** { *; }

# ---------------------------------------------------------------------------
# sing-box (libbox) — هسته‌ی دوم.
# کلاس‌های gomobile با نام کامل از سمت Go پیدا می‌شوند و هر تغییر نام باعث
# کرش نیتیو می‌شود؛ پس همه چیز حفظ می‌شود.
# ---------------------------------------------------------------------------
-keep class io.nekohasekai.libbox.** { *; }
-keep interface io.nekohasekai.libbox.** { *; }
-keepclassmembers class io.nekohasekai.libbox.** { *; }
-keep class ir.v2max.app.core.SingBoxPlatform { *; }
-keep class ir.v2max.app.core.SingBoxCore { *; }
-keep class ir.v2max.app.core.SingBoxConfig { *; }
-dontwarn io.nekohasekai.libbox.**
-dontwarn go.**
-keep class com.google.gson.** { *; }
-keepclassmembers class ir.v2max.app.data.** { *; }
-dontwarn libv2ray.**
-dontwarn go.**

# ---------------------------------------------------------------------------
# V2max — قواعد نگه‌داری برای فعال‌سازی R8
# مدل‌ها با Gson (بازتابی) خوانده می‌شوند و نام فیلدها باید حفظ شود.
# ---------------------------------------------------------------------------
-keep class ir.v2max.app.data.** { *; }
-keepclassmembers class ir.v2max.app.data.** { <fields>; }
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Compose / Activity / ViewModel ها از طریق بازتاب ساخته می‌شوند
-keep class ir.v2max.app.MainActivity { *; }
-keep class ir.v2max.app.service.** { *; }

# OkHttp/Kotlin internals
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
