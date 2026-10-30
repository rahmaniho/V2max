package ir.v2max.app.data

/**
 * تشخیص کشور سرور از روی نام کانفیگ (پرچم ایموجی یا نام کشور/کد دوحرفی).
 * کاملاً آفلاین است و هیچ درخواست شبکه‌ای نمی‌فرستد.
 */
object CountryFlags {

    private data class Country(val code: String, val name: String, val english: String, val emoji: String)

    private val COUNTRIES = listOf(
        Country("IR", "ایران", "iran", "🇮🇷"),
        Country("DE", "آلمان", "germany", "🇩🇪"),
        Country("NL", "هلند", "netherlands", "🇳🇱"),
        Country("FR", "فرانسه", "france", "🇫🇷"),
        Country("GB", "انگلستان", "united kingdom", "🇬🇧"),
        Country("US", "آمریکا", "united states", "🇺🇸"),
        Country("CA", "کانادا", "canada", "🇨🇦"),
        Country("TR", "ترکیه", "turkey", "🇹🇷"),
        Country("AE", "امارات", "emirates", "🇦🇪"),
        Country("RU", "روسیه", "russia", "🇷🇺"),
        Country("FI", "فنلاند", "finland", "🇫🇮"),
        Country("SE", "سوئد", "sweden", "🇸🇪"),
        Country("NO", "نروژ", "norway", "🇳🇴"),
        Country("DK", "دانمارک", "denmark", "🇩🇰"),
        Country("PL", "لهستان", "poland", "🇵🇱"),
        Country("AT", "اتریش", "austria", "🇦🇹"),
        Country("CH", "سوئیس", "switzerland", "🇨🇭"),
        Country("IT", "ایتالیا", "italy", "🇮🇹"),
        Country("ES", "اسپانیا", "spain", "🇪🇸"),
        Country("PT", "پرتغال", "portugal", "🇵🇹"),
        Country("RO", "رومانی", "romania", "🇷🇴"),
        Country("CZ", "چک", "czech", "🇨🇿"),
        Country("HU", "مجارستان", "hungary", "🇭🇺"),
        Country("BG", "بلغارستان", "bulgaria", "🇧🇬"),
        Country("UA", "اوکراین", "ukraine", "🇺🇦"),
        Country("LT", "لیتوانی", "lithuania", "🇱🇹"),
        Country("LV", "لتونی", "latvia", "🇱🇻"),
        Country("EE", "استونی", "estonia", "🇪🇪"),
        Country("IE", "ایرلند", "ireland", "🇮🇪"),
        Country("BE", "بلژیک", "belgium", "🇧🇪"),
        Country("SG", "سنگاپور", "singapore", "🇸🇬"),
        Country("JP", "ژاپن", "japan", "🇯🇵"),
        Country("KR", "کره", "korea", "🇰🇷"),
        Country("CN", "چین", "china", "🇨🇳"),
        Country("HK", "هنگ‌کنگ", "hong kong", "🇭🇰"),
        Country("IN", "هند", "india", "🇮🇳"),
        Country("AU", "استرالیا", "australia", "🇦🇺"),
        Country("BR", "برزیل", "brazil", "🇧🇷"),
        Country("ZA", "آفریقای جنوبی", "south africa", "🇿🇦"),
        Country("AM", "ارمنستان", "armenia", "🇦🇲"),
        Country("GE", "گرجستان", "georgia", "🇬🇪"),
        Country("AZ", "آذربایجان", "azerbaijan", "🇦🇿"),
        Country("QA", "قطر", "qatar", "🇶🇦"),
        Country("KW", "کویت", "kuwait", "🇰🇼"),
        Country("SA", "عربستان", "saudi", "🇸🇦"),
        Country("OM", "عمان", "oman", "🇴🇲"),
        Country("BH", "بحرین", "bahrain", "🇧🇭"),
        Country("IL", "اسرائیل", "israel", "🇮🇱"),
        Country("CY", "قبرس", "cyprus", "🇨🇾"),
        Country("GR", "یونان", "greece", "🇬🇷"),
        Country("MX", "مکزیک", "mexico", "🇲🇽"),
        Country("AR", "آرژانتین", "argentina", "🇦🇷"),
        Country("ID", "اندونزی", "indonesia", "🇮🇩"),
        Country("MY", "مالزی", "malaysia", "🇲🇾"),
        Country("TH", "تایلند", "thailand", "🇹🇭"),
        Country("VN", "ویتنام", "vietnam", "🇻🇳"),
        Country("PH", "فیلیپین", "philippines", "🇵🇭")
    )

    private val byEmoji: Map<String, Country> = COUNTRIES.associateBy { it.emoji }

    /**
     * کشور را از نام سرور تشخیص می‌دهد.
     * اولویت: پرچم ایموجی → نام کامل فارسی/انگلیسی → کد دوحرفی جدا.
     */
    fun resolve(name: String): Pair<String, String>? {
        if (name.isBlank()) return null

        // ۱) پرچم ایموجی
        COUNTRIES.forEach { country ->
            if (name.contains(country.emoji)) return country.code to country.name
        }

        val lower = name.lowercase()

        // ۲) نام کامل
        COUNTRIES.forEach { country ->
            if (lower.contains(country.english)) return country.code to country.name
            if (name.contains(country.name)) return country.code to country.name
        }

        // ۳) کد دوحرفی به‌صورت جدا (DE, NL, ...)
        val tokens = name.uppercase().split(' ', '_', '-', '|', '·', '(', ')', '[', ']', ',', ':')
            .map { it.trim() }
        COUNTRIES.forEach { country ->
            if (tokens.any { it == country.code }) return country.code to country.name
        }
        return null
    }

    /** پرچم را از کد کشور می‌سازد (بدون جدول) */
    fun emojiFor(code: String): String {
        if (code.length != 2) return ""
        byCodeEmoji(code)?.let { return it }
        val upper = code.uppercase()
        val first = 0x1F1E6 + (upper[0] - 'A')
        val second = 0x1F1E6 + (upper[1] - 'A')
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    private fun byCodeEmoji(code: String): String? =
        COUNTRIES.firstOrNull { it.code.equals(code, true) }?.emoji
}
