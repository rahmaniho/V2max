package ir.v2max.app.core

/**
 * libv2ray reports elapsed whole milliseconds and uses -1 for failure. A
 * successful local or very fast response can therefore round down to zero.
 * The rest of the app reserves non-positive values for failed/unmeasured tests.
 */
internal object NativeDelay {
    const val NO_RESPONSE = -1L
    const val INDETERMINATE = -2L

    fun normalize(milliseconds: Long): Long =
        if (milliseconds < 0) NO_RESPONSE else milliseconds.coerceAtLeast(1L)

    /**
     * libv2ray reports setup errors and HTTP errors through the same exception
     * channel. Only recognizable I/O failures of an actual GET are negative
     * health evidence; configuration/setup errors and unfamiliar messages are
     * indeterminate. Never retain or display the raw message (it can contain a URL).
     */
    fun outboundFailure(message: String?): Long {
        val reason = message.orEmpty().trim()
        if (!reason.startsWith("Get \"")) return INDETERMINATE
        val detail = reason.substringAfter("\": ", "").lowercase(java.util.Locale.ROOT)
        if (detail.isEmpty()) return INDETERMINATE
        // Xray's in-process dispatcher reports a closed proxy pipe when the
        // remote outbound cannot be reached. Android's real native fixture
        // produces this exact GET error for a refused server endpoint.
        val noResponse = detail == "eof" || detail == "io: read/write on closed pipe" || listOf(
            "unexpected eof", "i/o timeout", "deadline exceeded", "connection refused",
            "connection reset", "broken pipe", "no such host", "network is unreachable",
            "no route to host", "connection timed out", "host is down"
        ).any(detail::contains)
        return if (noResponse) NO_RESPONSE else INDETERMINATE
    }
}
