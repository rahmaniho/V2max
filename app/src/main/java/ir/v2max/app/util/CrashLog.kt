package ir.v2max.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ثبت خطاهای کشنده و غیرکشنده در یک فایل کوچک داخل حافظه‌ی خصوصی برنامه.
 *
 * هدف: وقتی برنامه «ناگهان بسته می‌شود»، کاربر بتواند علت را ببیند و بفرستد،
 * به‌جای اینکه هیچ سرنخی باقی نماند.
 */
object CrashLog {

    private const val TAG = "V2maxCrash"
    private const val FILE_NAME = "crash.log"
    private const val MAX_BYTES = 96 * 1024

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun file(): File? = appContext?.let { File(it.filesDir, FILE_NAME) }

    /** ثبت یک خطا با برچسب کوتاه */
    fun record(throwable: Throwable, tag: String = "") {
        Log.e(TAG, "crash[$tag]", throwable)
        val target = file() ?: return
        runCatching {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val head = "=== $time ${if (tag.isBlank()) "" else "[$tag] "}${throwable.javaClass.simpleName}: " +
                "${throwable.message.orEmpty()}\n"
            val body = throwable.stackTraceToString().take(6000)
            val existing = if (target.exists()) target.readText() else ""
            val combined = (head + body + "\n\n" + existing).take(MAX_BYTES)
            target.writeText(combined)
        }
    }

    /** آخرین خطای ثبت‌شده (برای نمایش در تنظیمات) */
    fun last(): String = runCatching {
        file()?.takeIf { it.exists() }?.readText().orEmpty()
    }.getOrDefault("")

    /** خلاصه‌ی یک‌خطی آخرین خطا */
    fun lastSummary(): String {
        val first = last().lineSequence().firstOrNull { it.startsWith("===") } ?: return ""
        return first.removePrefix("===").trim()
    }

    fun clear() {
        runCatching { file()?.delete() }
    }
}
