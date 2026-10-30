package ir.v2max.app.util

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * پشتیبان رمزدار: فایل پشتیبان V2max شامل UUID، رمز و لینک‌های اشتراک است؛
 * ذخیره‌ی متن ساده‌ی آن در تلگرام/گوگل‌درایو امن نیست.
 *
 * قالب خروجی:
 *   V2MAXENC1:<base64(salt[16] || iv[12] || ciphertext+tag)>
 * رمز با PBKDF2-HMAC-SHA256 (۲۱۰٬۰۰۰ دور) به کلید AES-256-GCM تبدیل می‌شود.
 */
object SecureBackup {

    private const val PREFIX = "V2MAXENC1:"
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    fun isEncrypted(text: String): Boolean = text.trimStart().startsWith(PREFIX)

    fun encrypt(plain: String, password: String): String? = runCatching {
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))

        val payload = ByteArray(salt.size + iv.size + cipherText.size)
        salt.copyInto(payload, 0)
        iv.copyInto(payload, salt.size)
        cipherText.copyInto(payload, salt.size + iv.size)

        PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
    }.getOrNull()

    fun decrypt(text: String, password: String): String? = runCatching {
        val body = text.trimStart().removePrefix(PREFIX)
        val payload = Base64.decode(body, Base64.DEFAULT)
        if (payload.size <= SALT_LEN + IV_LEN) return@runCatching null

        val salt = payload.copyOfRange(0, SALT_LEN)
        val iv = payload.copyOfRange(SALT_LEN, SALT_LEN + IV_LEN)
        val cipherText = payload.copyOfRange(SALT_LEN + IV_LEN, payload.size)
        val key = deriveKey(password, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }.getOrNull()

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(key, "AES")
    }
}
