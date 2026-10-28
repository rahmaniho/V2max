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

    /** تاریخ شمسی به همراه ساعت */
    fun date(millis: Long): String {
        if (millis <= 0L) return "—"
        return try {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = millis
            val (jy, jm, jd) = gregorianToJalali(
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            )
            val hour = String.format(Locale.US, "%02d", cal.get(java.util.Calendar.HOUR_OF_DAY))
            val minute = String.format(Locale.US, "%02d", cal.get(java.util.Calendar.MINUTE))
            toPersianDigits(
                String.format(Locale.US, "%04d/%02d/%02d - %s:%s", jy, jm, jd, hour, minute)
            )
        } catch (t: Throwable) {
            val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)
            toPersianDigits(fmt.format(Date(millis)))
        }
    }

    /** تاریخ شمسی بدون ساعت */
    fun dateOnly(millis: Long): String {
        if (millis <= 0L) return "—"
        return try {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = millis
            val (jy, jm, jd) = gregorianToJalali(
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            )
            toPersianDigits(String.format(Locale.US, "%04d/%02d/%02d", jy, jm, jd))
        } catch (t: Throwable) {
            "—"
        }
    }

    private fun gregorianToJalali(gyIn: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val gDaysInMonth = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gyIn + 1 else gyIn
        var days = 355666 + (365 * gyIn) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) +
            ((gy2 + 399) / 400) + gd + gDaysInMonth[gm - 1]
        var jy = -1595 + (33 * (days / 12053))
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + (days / 31)
            jd = 1 + (days % 31)
        } else {
            jm = 7 + ((days - 186) / 30)
            jd = 1 + ((days - 186) % 30)
        }
        return Triple(jy, jm, jd)
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
