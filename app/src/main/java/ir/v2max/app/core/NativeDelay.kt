package ir.v2max.app.core

/**
 * libv2ray reports elapsed whole milliseconds and uses -1 for failure. A
 * successful local or very fast response can therefore round down to zero.
 * The rest of the app reserves non-positive values for failed/unmeasured tests.
 */
internal object NativeDelay {
    fun normalize(milliseconds: Long): Long =
        if (milliseconds < 0) -1L else milliseconds.coerceAtLeast(1L)
}
