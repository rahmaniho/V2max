package ir.v2max.app.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Bound untrusted subscription responses before decoding or allocating profiles. */
internal object SubscriptionBody {
    internal const val MAX_BYTES = 8 * 1024 * 1024
    internal class TooLarge : IOException("subscription response is too large")

    fun read(input: InputStream, declaredLength: Long, limit: Int = MAX_BYTES): ByteArray {
        require(limit > 0)
        if (declaredLength > limit.toLong()) throw TooLarge()
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        var size = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (count > limit - size) throw TooLarge()
            output.write(buffer, 0, count)
            size += count
        }
        return output.toByteArray()
    }
}
