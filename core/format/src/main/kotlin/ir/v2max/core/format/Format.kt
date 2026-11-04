package ir.v2max.core.format

/** A date in the Jalali (Solar Hijri) calendar. */
data class JalaliDate(val year: Int, val month: Int, val day: Int) {
    /** `yyyy/MM/dd`, zero-padded. */
    val formatted: String get() = "%04d/%02d/%02d".format(year, month, day)
}

/**
 * Gregorian → Jalali conversion.
 *
 * Split out of the display formatter on purpose. The previous implementation
 * read year/month/day from `Calendar.getInstance()` inside the same function
 * that rendered the string, so the arithmetic — which is the part actually
 * capable of being wrong — could only be exercised by asserting on formatted
 * output in whatever timezone the test happened to run in.
 *
 * This is a pure function of three integers, so it is deterministic and
 * testable directly against known dates.
 */
object Jalali {

    /**
     * Converts a Gregorian date to Jalali.
     *
     * The algorithm is the standard 33-year-cycle arithmetic used by the
     * previous implementation, preserved exactly: the app has shipped these
     * dates to users, and "correcting" the algorithm would silently move every
     * displayed date by a day in some ranges.
     */
    fun fromGregorian(year: Int, month: Int, day: Int): JalaliDate {
        require(month in 1..12) { "month must be 1..12, was $month" }
        require(day in 1..31) { "day must be 1..31, was $day" }

        // Cumulative days before each month, non-leap.
        val daysInMonth = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)

        val gy2 = if (month > 2) year + 1 else year
        var days = 355666 + (365 * year) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) +
            ((gy2 + 399) / 400) + day + daysInMonth[month - 1]

        var jy = -1595 + (33 * (days / 12053))
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }

        // First six Jalali months have 31 days, the rest 30.
        return if (days < 186) {
            JalaliDate(jy, 1 + (days / 31), 1 + (days % 31))
        } else {
            JalaliDate(jy, 7 + ((days - 186) / 30), 1 + ((days - 186) % 30))
        }
    }
}

/**
 * Display formatting for the Persian UI.
 *
 * All output uses Persian digits, matching the rest of the app. The zone is a
 * parameter rather than an implicit `Calendar.getInstance()` so that callers —
 * and tests — are explicit about which timezone a timestamp is rendered in.
 */
object Format {

    /** Measured speed in kilobits per second. Uses an em dash for "not measured". */
    fun kbps(value: Int): String = when {
        value <= 0 -> "—"
        value >= 1000 -> {
            val mbps = value / 1000.0
            val rounded = (mbps * 10).toInt() / 10.0
            "$rounded Mbps"
        }

        else -> "$value Kbps"
    }

    /** Transfer rate in bytes per second. */
    fun speed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0L) return "۰ B/s"
        val kb = bytesPerSecond / 1024.0
        val mb = kb / 1024.0
        val text = when {
            mb >= 1 -> "%.1f MB/s".format(mb)
            kb >= 1 -> "%.0f KB/s".format(kb)
            else -> "$bytesPerSecond B/s"
        }
        return toPersianDigits(text)
    }

    /** A byte count, scaled to the largest unit that keeps it readable. */
    fun bytes(bytes: Long): String {
        if (bytes <= 0L) return "۰"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        val text = when {
            gb >= 1 -> "%.2f GB".format(gb)
            mb >= 1 -> "%.1f MB".format(mb)
            kb >= 1 -> "%.0f KB".format(kb)
            else -> "$bytes B"
        }
        return toPersianDigits(text)
    }

    /** Elapsed time as `HH:MM:SS`. */
    fun duration(millis: Long): String {
        if (millis <= 0L) return "۰۰:۰۰:۰۰"
        val totalSeconds = millis / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return toPersianDigits("%02d:%02d:%02d".format(h, m, s))
    }

    fun number(value: Int): String = toPersianDigits(value.toString())

    /** Jalali date with time, or an em dash when there is no timestamp. */
    fun date(millis: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        if (millis <= 0L) return "—"
        val zdt = java.time.Instant.ofEpochMilli(millis).atZone(zone)
        val j = Jalali.fromGregorian(zdt.year, zdt.monthValue, zdt.dayOfMonth)
        return toPersianDigits(
            "%04d/%02d/%02d - %02d:%02d".format(
                j.year, j.month, j.day, zdt.hour, zdt.minute,
            )
        )
    }

    /** Jalali date without time. */
    fun dateOnly(millis: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        if (millis <= 0L) return "—"
        val zdt = java.time.Instant.ofEpochMilli(millis).atZone(zone)
        return toPersianDigits(
            Jalali.fromGregorian(zdt.year, zdt.monthValue, zdt.dayOfMonth).formatted
        )
    }

    /** ASCII digits to their Persian forms; other characters pass through. */
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

    /**
     * Persian and Arabic-Indic digits back to ASCII, for parsing user input.
     *
     * Both digit sets are handled because Persian keyboards and some Arabic
     * locales produce different codepoints for the same digits, and the
     * Persian decimal separator `٫` is normalised to `.`.
     */
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

    /** Locale-independent `String.format`, so output never varies by device locale. */
    private fun String.format(vararg args: Any?): String =
        java.lang.String.format(java.util.Locale.US, this, *args)
}
