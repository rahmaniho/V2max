package ir.v2max.app

import ir.v2max.app.net.NetworkProbeSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NetworkProbeSummaryTest {
    @Test fun `sample failures are reported without claiming censorship or guaranteed connectivity`() {
        val samples = listOf(
            NetworkProbeSummary.describe(dnsFailures = true, connectionFailures = true) to
                "بررسی DNS و اتصال برخی مقصدهای نمونه ناموفق بود",
            NetworkProbeSummary.describe(dnsFailures = true, connectionFailures = false) to
                "پاسخ DNS برخی مقصدهای نمونه دریافت نشد",
            NetworkProbeSummary.describe(dnsFailures = false, connectionFailures = true) to
                "اتصال TCP یا TLS برخی مقصدهای نمونه ناموفق بود",
            NetworkProbeSummary.describe(dnsFailures = false, connectionFailures = false) to
                "اتصال TLS به مقصدهای نمونه برقرار شد"
        )
        for ((actual, expected) in samples) {
            assertEquals(expected, actual)
            assertFalse(actual.contains("فیلترینگ"))
            assertFalse(actual.contains("DPI"))
        }
    }
}
