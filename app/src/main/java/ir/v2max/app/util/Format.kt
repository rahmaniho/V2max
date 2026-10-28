package ir.v2max.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** قالب‌بندی اعداد، سرعت و زمان برای نمایش در رابط فارسی */
object Format {

    fun speed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0L) return "۰ B/s"
        val kb = bytesPerSecond / 1024.0
        val mb = kb / 1024.0
        val text = when {
            mb >= 1 -> String.format(Locale.US, "%.1f MB/s", mb)
            kb >= 1 -> String.format(Locale.US, "%.0f KB/s", kb)
            else -> "$bytesPerSecond B/s"
        }
        return toPersianDigits(text)
    }

    fun bytes(bytes: Long): String {
        if (bytes <= 0L) return "۰"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        val text = when {
            gb >= 1 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1 -> String.format(Locale.US, "%.0f KB", kb)
            else -> "$bytes B"
        }
        return toPersianDigits(text)
    }

    fun duration(millis: Long): String {
        if (millis <= 0L) return "۰۰:۰۰:۰۰"
        val totalSeconds = millis / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return toPersianDigits(String.format(Locale.US, "%02d:%02d:%02d", h, m, s))
    }

    fun number(value: Int): String = toPersianDigits(value.toString())

    fun date(millis: Long): String {
        if (millis <= 0L) return "—"
        val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)
        return toPersianDigits(fmt.format(Date(millis)))
    }

    fun toPersianDigits(input: String): String {
        val builder = StringBuilder(input.length)
        for (ch in input) {
            builder.append(
                when (ch) {
                    '0' -> '۰'
                    '1' -> '۱'
                    '2' -> '۲'
                    '3' -> '۳'
                    '4' -> '۴'
                    '5' -> '۵'
                    '6' -> '۶'
                    '7' -> '۷'
                    '8' -> '۸'
                    '9' -> '۹'
                    else -> ch
                }
            )
        }
        return builder.toString()
    }

    /** حذف ارقام فارسی برای پردازش ورودی کاربر */
    fun toEnglishDigits(input: String): String {
        val builder = StringBuilder(input.length)
        for (ch in input) {
            builder.append(
                when (ch) {
                    '۰', '٠' -> '0'
                    '۱', '١' -> '1'
                    '۲', '٢' -> '2'
                    '۳', '٣' -> '3'
                    '۴', '٤' -> '4'
                    '۵', '٥' -> '5'
                    '۶', '٦' -> '6'
                    '۷', '٧' -> '7'
                    '۸', '٨' -> '8'
                    '۹', '٩' -> '9'
                    else -> ch
                }
            )
        }
        return builder.toString().replace("٫", ".")
    }
}
