package ir.v2max.core.model

/**
 * Usage figures reported by a subscription server.
 *
 * Replaces the `LongArray(4)` the previous `parseUserInfo` returned, where
 * callers indexed positionally (`usage[0]`, `usage[1]`, …). Swapping upload for
 * download at a call site was undetectable at compile time; named fields make
 * that impossible.
 */
data class SubscriptionUsage(
    val upload: Long = 0L,
    val download: Long = 0L,
    val total: Long = 0L,
    /** Unix timestamp in seconds, as the header specifies; 0 means not given. */
    val expireAt: Long = 0L,
) {
    val usedBytes: Long get() = upload + download

    /** Fraction of the quota consumed, 0f..1f. Zero when no total was given. */
    fun usedPercent(): Float =
        if (total <= 0L) 0f else (usedBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    /** True when an expiry was reported and has passed. */
    fun isExpired(nowEpochSeconds: Long = System.currentTimeMillis() / 1000): Boolean =
        expireAt > 0L && expireAt < nowEpochSeconds

    val isEmpty: Boolean get() = this == EMPTY

    companion object {
        val EMPTY = SubscriptionUsage()

        /**
         * Parses the `subscription-userinfo` response header:
         *
         *     upload=1; download=2; total=3; expire=4
         *
         * Keys are matched case-insensitively and the separator is `;`. A pair
         * whose value is not an integer is skipped rather than failing the whole
         * header, and an absent or blank header yields [EMPTY] — a server that
         * omits the header is normal, not an error.
         *
         * Values are byte counts (and a Unix timestamp for `expire`), despite
         * some providers labelling them differently.
         */
        fun parseHeader(header: String?): SubscriptionUsage {
            if (header.isNullOrBlank()) return EMPTY
            var upload = 0L
            var download = 0L
            var total = 0L
            var expire = 0L
            for (part in header.split(';')) {
                val key = part.substringBefore('=').trim().lowercase()
                val value = part.substringAfter('=', "").trim().toLongOrNull() ?: continue
                when (key) {
                    "upload" -> upload = value
                    "download" -> download = value
                    "total" -> total = value
                    "expire" -> expire = value
                }
            }
            return SubscriptionUsage(upload, download, total, expire)
        }
    }
}

/**
 * A subscription source.
 *
 * Immutable replacement for the previous mutable `Subscription`.
 */
data class Subscription(
    val id: String = "",
    val name: String = "",
    val url: String = "",
    val updatedAt: Long = 0L,
    val count: Int = 0,
    val usage: SubscriptionUsage = SubscriptionUsage.EMPTY,
    val lastError: String = "",
    /** A built-in free public source shipped with the app. */
    val builtIn: Boolean = false,
) {
    val usedBytes: Long get() = usage.usedBytes

    fun usedPercent(): Float = usage.usedPercent()
}
