package ir.v2max.app.util

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * PBKDF2-HMAC-SHA256 for a single 32-byte block, as required by V2MAXENC1.
 * Android 7 devices may not expose PBKDF2WithHmacSHA256 as a SecretKeyFactory,
 * although they provide the underlying HmacSHA256 primitive. The result must
 * match the platform factory and independent PBKDF2 test vectors exactly.
 */
internal object Pbkdf2Compat {
    fun derive256(password: String, salt: ByteArray, iterations: Int): ByteArray {
        require(iterations > 0)
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        // Some providers reject an empty SecretKeySpec. HMAC pads an empty key
        // with zeros, so a single zero byte produces the identical HMAC key block.
        val hmacKey = if (passwordBytes.isEmpty()) byteArrayOf(0) else passwordBytes
        val mac = try {
            Mac.getInstance("HmacSHA256").also { it.init(SecretKeySpec(hmacKey, "HmacSHA256")) }
        } finally {
            passwordBytes.fill(0)
            if (hmacKey !== passwordBytes) hmacKey.fill(0)
        }
        check(mac.macLength == 32)
        val first = ByteArray(32)
        val second = ByteArray(32)
        val result = ByteArray(32)
        var previous = first
        var next = second
        try {
            // U1 = PRF(password, salt || INT_32_BE(1)).
            mac.update(salt)
            mac.update(byteArrayOf(0, 0, 0, 1))
            mac.doFinal(previous, 0)
            previous.copyInto(result)
            repeat(iterations - 1) {
                mac.update(previous)
                mac.doFinal(next, 0)
                for (i in result.indices) result[i] = (result[i].toInt() xor next[i].toInt()).toByte()
                val swap = previous
                previous = next
                next = swap
            }
            return result
        } catch (t: Throwable) {
            result.fill(0)
            throw t
        } finally {
            first.fill(0)
            second.fill(0)
        }
    }
}
