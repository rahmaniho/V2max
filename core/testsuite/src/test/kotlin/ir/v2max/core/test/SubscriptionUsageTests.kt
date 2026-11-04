package ir.v2max.core.test

import ir.v2max.core.model.Subscription
import ir.v2max.core.model.SubscriptionUsage

/**
 * Tests for `subscription-userinfo` header parsing.
 *
 * Providers are inconsistent about this header — casing, spacing, missing keys
 * and occasionally junk values — and it drives the quota display and the
 * auto-refresh decision, so it has to degrade quietly rather than throw.
 */
object SubscriptionUsageTests {

    fun run() {
        T.group("SubscriptionUsage")

        // ---- absent / blank header is normal, not an error
        T.eq("null header is empty", SubscriptionUsage.EMPTY, SubscriptionUsage.parseHeader(null))
        T.eq("blank header is empty", SubscriptionUsage.EMPTY, SubscriptionUsage.parseHeader("   "))
        T.ok("empty flag set", SubscriptionUsage.parseHeader(null).isEmpty)

        // ---- the standard form
        val std = SubscriptionUsage.parseHeader(
            "upload=1073741824; download=2147483648; total=10737418240; expire=1893456000"
        )
        T.eq("upload", 1073741824L, std.upload)
        T.eq("download", 2147483648L, std.download)
        T.eq("total", 10737418240L, std.total)
        T.eq("expire", 1893456000L, std.expireAt)
        T.ok("non-empty", !std.isEmpty)

        // ---- keys are case-insensitive, values tolerate whitespace
        val messy = SubscriptionUsage.parseHeader(
            "  Upload = 5 ;  DOWNLOAD=7 ;Total =  100 ;  EXPIRE = 2000  "
        )
        T.eq("case-insensitive upload", 5L, messy.upload)
        T.eq("case-insensitive download", 7L, messy.download)
        T.eq("case-insensitive total", 100L, messy.total)
        T.eq("case-insensitive expire", 2000L, messy.expireAt)

        // ---- a junk value is skipped without losing the rest
        val partial = SubscriptionUsage.parseHeader(
            "upload=10; download=notanumber; total=500; expire="
        )
        T.eq("valid upload kept", 10L, partial.upload)
        T.eq("junk download defaults to 0", 0L, partial.download)
        T.eq("total after junk still parsed", 500L, partial.total)
        T.eq("blank expire defaults to 0", 0L, partial.expireAt)

        // ---- unknown keys are ignored, missing keys default to 0
        val sparse = SubscriptionUsage.parseHeader("total=999; somethingelse=42")
        T.eq("known key parsed", 999L, sparse.total)
        T.eq("unknown key ignored", 0L, sparse.upload)
        T.eq("missing download is 0", 0L, sparse.download)

        // ---- no separator at all
        T.eq("garbage header yields empty", SubscriptionUsage.EMPTY, SubscriptionUsage.parseHeader("nonsense"))

        // ---- trailing/leading semicolons
        val padded = SubscriptionUsage.parseHeader(";;upload=3;;total=30;;")
        T.eq("empty segments tolerated", 3L, padded.upload)
        T.eq("total after empty segments", 30L, padded.total)

        // ---- usedBytes and usedPercent
        val half = SubscriptionUsage(upload = 25, download = 25, total = 100)
        T.eq("usedBytes sums both", 50L, half.usedBytes)
        T.eq("usedPercent half", 0.5f, half.usedPercent())

        T.eq("no total means 0 percent", 0f, SubscriptionUsage(upload = 5, total = 0).usedPercent())
        T.eq("negative total means 0 percent", 0f, SubscriptionUsage(upload = 5, total = -1).usedPercent())

        val over = SubscriptionUsage(upload = 80, download = 80, total = 100)
        T.eq("over-quota clamps to 1", 1f, over.usedPercent())

        val none = SubscriptionUsage()
        T.eq("no usage means 0 percent", 0f, none.usedPercent())

        // ---- expiry
        T.ok("not expired without an expiry", !SubscriptionUsage(expireAt = 0).isExpired(nowEpochSeconds = 1_000))
        T.ok("expired in the past", SubscriptionUsage(expireAt = 500).isExpired(nowEpochSeconds = 1_000))
        T.ok("not expired in the future", !SubscriptionUsage(expireAt = 2_000).isExpired(nowEpochSeconds = 1_000))
        T.ok("exact boundary is not expired", !SubscriptionUsage(expireAt = 1_000).isExpired(nowEpochSeconds = 1_000))

        // ---- Subscription delegates to its usage
        val sub = Subscription(id = "s", name = "Sub", url = "https://x/y", usage = half)
        T.eq("subscription usedBytes", 50L, sub.usedBytes)
        T.eq("subscription usedPercent", 0.5f, sub.usedPercent())
        T.eq("subscription defaults to empty usage", 0L, Subscription(id = "s").usedBytes)
    }
}
