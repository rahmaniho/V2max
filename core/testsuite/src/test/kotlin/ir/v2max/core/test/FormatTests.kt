package ir.v2max.core.test

import ir.v2max.core.format.Format
import ir.v2max.core.format.Jalali
import ir.v2max.core.format.JalaliDate
import java.time.ZoneId

/**
 * Tests for display formatting and Jalali conversion.
 *
 * The Jalali cases are anchored to dates that can be checked independently of
 * this code, rather than to whatever the implementation happens to produce —
 * otherwise a wrong algorithm would generate its own expected values.
 */
object FormatTests {

    private val UTC = ZoneId.of("UTC")

    fun run() {
        T.group("Format")

        // ---- Jalali anchors
        // Nowruz, the first day of the Persian year, for several years.
        T.eq("1403 Nowruz", JalaliDate(1403, 1, 1), Jalali.fromGregorian(2024, 3, 20))
        T.eq("1402 Nowruz", JalaliDate(1402, 1, 1), Jalali.fromGregorian(2023, 3, 21))
        T.eq("1400 Nowruz", JalaliDate(1400, 1, 1), Jalali.fromGregorian(2021, 3, 21))
        T.eq("1399 Nowruz", JalaliDate(1399, 1, 1), Jalali.fromGregorian(2020, 3, 20))

        // 22 Bahman 1357 — a date fixed in the Iranian calendar.
        T.eq("22 Bahman 1357", JalaliDate(1357, 11, 22), Jalali.fromGregorian(1979, 2, 11))

        // A widely cited anchor near the 2000 boundary.
        T.eq("2000-01-01", JalaliDate(1378, 10, 11), Jalali.fromGregorian(2000, 1, 1))

        // Year boundaries: the day before Nowruz is the last day of Esfand, which
        // is 29 days in a common year and 30 in a leap year.
        //
        //   1402 was common: 2023-03-21 -> 2024-03-20 is 365 days, so Esfand 1402
        //   ended on the 29th.
        //   1399 was a leap year: 2020-03-20 -> 2021-03-21 is 366 days, so Esfand
        //   1399 ended on the 30th.
        T.eq("Esfand 1402 (common year)", JalaliDate(1402, 12, 29), Jalali.fromGregorian(2024, 3, 19))
        T.eq("Esfand 1401 (common year)", JalaliDate(1401, 12, 29), Jalali.fromGregorian(2023, 3, 20))
        T.eq("Esfand 1399 (leap year)", JalaliDate(1399, 12, 30), Jalali.fromGregorian(2021, 3, 20))

        // Month lengths: the first six months have 31 days, the rest 30.
        T.eq("Farvardin has 31 days", JalaliDate(1403, 2, 1), Jalali.fromGregorian(2024, 4, 20))
        T.eq("Mehr starts day 186", JalaliDate(1403, 7, 1), Jalali.fromGregorian(2024, 9, 22))

        // Consecutive Gregorian days must advance the Jalali date by exactly one
        // day. Expressed as a valid-successor relation rather than assumed month
        // lengths, because Esfand is 29 or 30 depending on the year.
        var previous = Jalali.fromGregorian(2024, 1, 1)
        var monthRolls = 0
        var yearRolls = 0
        for (offset in 1..400) {
            val d = java.time.LocalDate.of(2024, 1, 1).plusDays(offset.toLong())
            val current = Jalali.fromGregorian(d.year, d.monthValue, d.dayOfMonth)
            val relation = when {
                current.year == previous.year + 1 && current.month == 1 && current.day == 1 -> {
                    yearRolls++
                    "year"
                }

                current.year == previous.year && current.month == previous.month + 1 && current.day == 1 -> {
                    monthRolls++
                    "month"
                }

                current.year == previous.year && current.month == previous.month &&
                    current.day == previous.day + 1 -> "day"

                else -> "INVALID"
            }
            T.ok(
                "valid successor at offset $offset",
                relation != "INVALID",
                { "$previous -> $current" },
            )
            previous = current
        }
        T.ok("year advanced within 400 days", yearRolls >= 1, { "yearRolls=$yearRolls" })
        T.ok("month rollovers happened", monthRolls > 10, { "monthRolls=$monthRolls" })

        // A Jalali year is 365 or 366 days — never anything else.
        val startOf1403 = java.time.LocalDate.of(2024, 3, 20)
        var daysIn1403 = 0
        while (Jalali.fromGregorian(
                startOf1403.plusDays((daysIn1403 + 1).toLong()).year,
                startOf1403.plusDays((daysIn1403 + 1).toLong()).monthValue,
                startOf1403.plusDays((daysIn1403 + 1).toLong()).dayOfMonth,
            ).year == 1403
        ) {
            daysIn1403++
        }
        T.ok(
            "year 1403 is 365 or 366 days",
            daysIn1403 == 364 || daysIn1403 == 365,
            { "measured $daysIn1403 days after Nowruz" },
        )

        // Invalid input is rejected rather than silently producing a wrong date.
        var threw = false
        try { Jalali.fromGregorian(2024, 13, 1) } catch (_: IllegalArgumentException) { threw = true }
        T.ok("month 13 rejected", threw)
        threw = false
        try { Jalali.fromGregorian(2024, 0, 1) } catch (_: IllegalArgumentException) { threw = true }
        T.ok("month 0 rejected", threw)

        T.eq("JalaliDate formatting", "1403/01/01", JalaliDate(1403, 1, 1).formatted)
        T.eq("JalaliDate pads month", "1403/07/05", JalaliDate(1403, 7, 5).formatted)

        // ---- kbps
        T.eq("kbps zero", "—", Format.kbps(0))
        T.eq("kbps negative", "—", Format.kbps(-5))
        T.eq("kbps below 1000", "850 Kbps", Format.kbps(850))
        T.eq("kbps exactly 1000", "1.0 Mbps", Format.kbps(1000))
        T.eq("kbps truncates, not rounds", "1.5 Mbps", Format.kbps(1599))
        T.eq("kbps large", "12.3 Mbps", Format.kbps(12345))

        // ---- speed
        T.eq("speed zero", "۰ B/s", Format.speed(0))
        T.eq("speed negative", "۰ B/s", Format.speed(-1))
        T.eq("speed bytes", "۵۱۲ B/s", Format.speed(512))
        T.eq("speed kb", "۵ KB/s", Format.speed(5 * 1024))
        T.eq("speed mb", "۲.۵ MB/s", Format.speed((2.5 * 1024 * 1024).toLong()))

        // ---- bytes
        T.eq("bytes zero", "۰", Format.bytes(0))
        T.eq("bytes negative", "۰", Format.bytes(-10))
        T.eq("bytes small", "۹۹۹ B", Format.bytes(999))
        T.eq("bytes kb", "۱۰ KB", Format.bytes(10 * 1024))
        T.eq("bytes mb", "۱.۵ MB", Format.bytes((1.5 * 1024 * 1024).toLong()))
        T.eq("bytes gb", "۲.۰۰ GB", Format.bytes(2L * 1024 * 1024 * 1024))

        // ---- duration
        T.eq("duration zero", "۰۰:۰۰:۰۰", Format.duration(0))
        T.eq("duration negative", "۰۰:۰۰:۰۰", Format.duration(-1))
        T.eq("duration one second", "۰۰:۰۰:۰۱", Format.duration(1_000))
        T.eq("duration one minute", "۰۰:۰۱:۰۰", Format.duration(60_000))
        T.eq("duration one hour", "۰۱:۰۰:۰۰", Format.duration(3_600_000))
        T.eq("duration mixed", "۰۲:۰۳:۰۴", Format.duration(2 * 3_600_000L + 3 * 60_000 + 4_000))
        T.eq("duration over a day keeps counting hours", "۲۵:۰۰:۰۰", Format.duration(25 * 3_600_000L))

        // ---- number
        T.eq("number", "۱۲۳۴", Format.number(1234))
        T.eq("number zero", "۰", Format.number(0))
        T.eq("number negative", "-۵", Format.number(-5))

        // ---- Persian digits
        T.eq("toPersian all digits", "۰۱۲۳۴۵۶۷۸۹", Format.toPersianDigits("0123456789"))
        T.eq("toPersian leaves text alone", "abc ۱۲۳", Format.toPersianDigits("abc 123"))
        T.eq("toPersian empty", "", Format.toPersianDigits(""))

        T.eq("toEnglish persian", "0123456789", Format.toEnglishDigits("۰۱۲۳۴۵۶۷۸۹"))
        // Arabic-Indic codepoints must work too — keyboards differ.
        T.eq("toEnglish arabic-indic", "0123456789", Format.toEnglishDigits("٠١٢٣٤٥٦٧٨٩"))
        T.eq("toEnglish decimal separator", "1.5", Format.toEnglishDigits("۱٫۵"))
        T.eq("toEnglish leaves text alone", "abc 123", Format.toEnglishDigits("abc 123"))

        // Round-trip: ASCII -> Persian -> ASCII is the identity.
        val sample = "0123456789"
        T.eq("digit round-trip", sample, Format.toEnglishDigits(Format.toPersianDigits(sample)))

        // ---- date rendering is deterministic because the zone is explicit
        // 2024-03-20T12:34:56Z is 1403/01/01.
        val millis = java.time.ZonedDateTime.of(2024, 3, 20, 12, 34, 56, 0, UTC)
            .toInstant().toEpochMilli()
        T.eq("date with time", "۱۴۰۳/۰۱/۰۱ - ۱۲:۳۴", Format.date(millis, UTC))
        T.eq("dateOnly", "۱۴۰۳/۰۱/۰۱", Format.dateOnly(millis, UTC))

        // The same instant in a different zone renders a different local time,
        // which is the point of making the zone explicit.
        val tehran = ZoneId.of("Asia/Tehran")
        T.eq("date in Tehran zone", "۱۴۰۳/۰۱/۰۱ - ۱۶:۰۴", Format.date(millis, tehran))

        T.eq("date of zero", "—", Format.date(0, UTC))
        T.eq("dateOnly of zero", "—", Format.dateOnly(0, UTC))
        T.eq("date of negative", "—", Format.date(-1, UTC))
    }
}
