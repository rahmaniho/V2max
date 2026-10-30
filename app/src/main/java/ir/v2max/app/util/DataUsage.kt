package ir.v2max.app.util

import android.content.Context

/**
 * شمارنده‌ی مصرف روزانه (تقریبی) برای سقف داده.
 * مجموع ترافیک تانل در هر روز ذخیره می‌شود و با تغییر تاریخ صفر می‌گردد.
 */
object DataUsage {

    private const val PREFS = "v2max_usage"
    private const val KEY_DAY = "day"
    private const val KEY_BYTES = "bytes"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun today(): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return fmt.format(java.util.Date())
    }

    @Synchronized
    fun add(context: Context, bytes: Long) {
        if (bytes <= 0) return
        val p = prefs(context)
        val day = today()
        val storedDay = p.getString(KEY_DAY, "") ?: ""
        val base = if (storedDay == day) p.getLong(KEY_BYTES, 0L) else 0L
        p.edit().putString(KEY_DAY, day).putLong(KEY_BYTES, base + bytes).apply()
    }

    @Synchronized
    fun todayBytes(context: Context): Long {
        val p = prefs(context)
        return if (p.getString(KEY_DAY, "") == today()) p.getLong(KEY_BYTES, 0L) else 0L
    }

    @Synchronized
    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
