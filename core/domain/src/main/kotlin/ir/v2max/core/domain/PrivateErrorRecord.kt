package ir.v2max.core.domain

/** Fixed categories only; never serialize a throwable's message, cause or stack. */
object PrivateErrorRecord {
    enum class Location(val label: String) {
        UI("رابط"), VPN("سرویس VPN"), SUBSCRIPTION("همگام‌سازی اشتراک"),
        DATA("داده‌های برنامه"), UPDATE("بررسی به‌روزرسانی"), GEO("فایل‌های جغرافیایی"), OTHER("بخش دیگر")
    }
    enum class Category(val label: String) {
        MEMORY("کمبود حافظه"), SECURITY("مجوز یا امنیت"), IO("ورودی/خروجی"),
        INPUT("ورودی نامعتبر"), STATE("وضعیت نامعتبر"), UNKNOWN("نامشخص")
    }
    data class Summary(val location: Location, val category: Category)
    val unknown = Summary(Location.OTHER, Category.UNKNOWN)

    fun classify(error: Throwable, tag: String): Summary {
        val location = when (tag) {
            "viewModel" -> Location.UI
            "vpnService" -> Location.VPN
            "SubSyncWorker", "SubSyncWorker.schedule" -> Location.SUBSCRIPTION
            "Repo.init" -> Location.DATA
            "UpdateCheckWorker.schedule" -> Location.UPDATE
            "GeoAssets" -> Location.GEO
            else -> Location.OTHER
        }
        val category = when (error) {
            is OutOfMemoryError -> Category.MEMORY
            is SecurityException -> Category.SECURITY
            is java.io.IOException -> Category.IO
            is IllegalArgumentException -> Category.INPUT
            is IllegalStateException -> Category.STATE
            else -> Category.UNKNOWN
        }
        return Summary(location, category)
    }

    fun format(summary: Summary): String = "بخش: ${summary.location.label}؛ دستهٔ خطا: ${summary.category.label}"

    /** Only these known enum values can be recovered from a local record. */
    fun encode(summary: Summary): String = "V2MAX_SAFE_V1|${summary.location.name}|${summary.category.name}"

    fun decode(line: String): Summary? {
        val pieces = line.split('|')
        if (pieces.size != 3 || pieces[0] != "V2MAX_SAFE_V1") return null
        val location = Location.entries.firstOrNull { it.name == pieces[1] } ?: return null
        val category = Category.entries.firstOrNull { it.name == pieces[2] } ?: return null
        return Summary(location, category)
    }
}
