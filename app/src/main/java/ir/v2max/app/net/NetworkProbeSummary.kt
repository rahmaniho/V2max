package ir.v2max.app.net

/** Describe only what the sampled DNS/TCP/TLS probes observed, not their cause. */
internal object NetworkProbeSummary {
    fun describe(dnsFailures: Boolean, connectionFailures: Boolean): String = when {
        dnsFailures && connectionFailures -> "بررسی DNS و اتصال برخی مقصدهای نمونه ناموفق بود"
        dnsFailures -> "پاسخ DNS برخی مقصدهای نمونه دریافت نشد"
        connectionFailures -> "اتصال TCP یا TLS برخی مقصدهای نمونه ناموفق بود"
        else -> "اتصال TLS به مقصدهای نمونه برقرار شد"
    }
}
