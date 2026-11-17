package ir.v2max.app.util

import android.content.Context
import android.util.Log
import ir.v2max.core.domain.PrivateErrorRecord
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Private local error categories. Raw exception messages/stacks are never exported. */
object CrashLog {
    private const val TAG = "V2maxCrash"
    private const val FILE_NAME = "crash.log"
    private const val MAX_CHARS = 96 * 1024

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun file(): File? = appContext?.let { File(it.filesDir, FILE_NAME) }

    /** Do not write user-controlled exception messages, stack traces or thread names. */
    @Synchronized
    fun record(throwable: Throwable, tag: String = "") {
        val safe = PrivateErrorRecord.classify(throwable, tag)
        Log.e(TAG, "A local error category was recorded")
        val target = file() ?: return
        runCatching {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            // A log created by an older version may still contain raw details.
            // Keep it private and unexported until the user chooses to delete it.
            val existing = if (target.exists()) target.bufferedReader().use { reader ->
                val prefix = CharArray(MAX_CHARS)
                val count = reader.read(prefix).coerceAtLeast(0)
                String(prefix, 0, count)
            } else ""
            val record = "${PrivateErrorRecord.encode(safe)}\n=== $time ===\n${PrivateErrorRecord.format(safe)}\n\n"
            target.writeText((record + existing).take(MAX_CHARS))
        }
    }

    /** Legacy logs may contain arbitrary text: never parse or return their content. */
    @Synchronized
    fun latestSummary(): PrivateErrorRecord.Summary? = runCatching {
        val target = file() ?: return@runCatching null
        if (!target.exists() || target.length() == 0L) return@runCatching null
        val firstLine = target.bufferedReader().use { reader ->
            val prefix = CharArray(128)
            val count = reader.read(prefix).coerceAtLeast(0)
            String(prefix, 0, count).substringBefore('\n').removeSuffix("\r")
        }
        PrivateErrorRecord.decode(firstLine) ?: PrivateErrorRecord.unknown
    }.getOrElse { PrivateErrorRecord.unknown }

    fun hasRecorded(): Boolean = latestSummary() != null

    @Synchronized
    fun clear(): Boolean = runCatching {
        file()?.let { !it.exists() || it.delete() } ?: false
    }.getOrDefault(false)
}
