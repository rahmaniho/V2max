package ir.v2max.app

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import ir.v2max.app.util.Pbkdf2Compat
import ir.v2max.app.util.SecureBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the actual Android crypto and Base64 providers with disposable data. */
@RunWith(AndroidJUnit4::class)
class AndroidSecureBackupTest {
    private val payload = """{"servers":[{"uuid":"secret-fixture","name":"تهران"}]}"""
    private val password = "رمز آزمایشی 42"
    private val prefix = "V2MAXENC1:"

    private fun encryptOrExplain(plain: String): String {
        SecureBackup.encrypt(plain, password)?.let { return it }
        fun status(block: () -> Unit): String = runCatching(block).exceptionOrNull()
            ?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "available"
        val factory = status { javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256") }
        val hmac = status {
            javax.crypto.Mac.getInstance("HmacSHA256").apply {
                init(javax.crypto.spec.SecretKeySpec(ByteArray(32), "HmacSHA256"))
                doFinal(ByteArray(1))
            }
        }
        val gcm = status {
            javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(ByteArray(32), "AES"),
                    javax.crypto.spec.GCMParameterSpec(128, ByteArray(12)))
                doFinal(ByteArray(1))
            }
        }
        throw AssertionError("Encryption failed; PBKDF2=$factory, HMAC=$hmac, AES-GCM=$gcm")
    }

    @Test fun encryptedBackupRoundTripsAndUsesFreshSaltAndIv() {
        val first = encryptOrExplain(payload)
        val second = encryptOrExplain(payload)
        assertTrue(SecureBackup.isEncrypted(first))
        assertNotEquals("Repeated encryption must not reuse salt and IV", first, second)
        assertFalse("Plain credentials appeared in encrypted output", first.contains("secret-fixture"))
        assertEquals(payload, SecureBackup.decrypt(first, password))
        assertEquals(payload, SecureBackup.decrypt(second, password))
    }

    @Test fun decryptsPortableUnicodePasswordFixture() {
        // Deterministic PBKDF2-HMAC-SHA256/AES-GCM fixture generated independently
        // with UTF-8, a fixed salt and IV. Production encryption uses random values.
        val vector = "V2MAXENC1:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGyxIhX1kJ/lw2iao8g+M4jz+ssw5Apfoes6CI9EcuSSMJNfpKdDbDx1W0SDOeXmFppOB0if/lI6UjV/KLWJfddjnWfLMuL2G8TIefQ=="
        val fallbackKey = Pbkdf2Compat.derive256(password, ByteArray(16) { it.toByte() }, 210_000)
        assertEquals("0b0a02f0e98f65d1d13516973ffde29c3ad3e8d0d7eb70edb19538d668cbfbd2",
            fallbackKey.joinToString("") { "%02x".format(it.toInt() and 0xff) })
        fallbackKey.fill(0)
        assertEquals(payload, SecureBackup.decrypt(vector, password))
    }

    @Test fun wrongPasswordAndTamperingAreRejected() {
        val encrypted = encryptOrExplain(payload)
        assertNull("A wrong password was accepted", SecureBackup.decrypt(encrypted, "not-the-password"))
        val bytes = Base64.decode(encrypted.removePrefix(prefix), Base64.DEFAULT)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val tampered = prefix + Base64.encodeToString(bytes, Base64.NO_WRAP)
        assertNull("A modified authentication tag was accepted", SecureBackup.decrypt(tampered, password))
    }

    @Test fun invalidInputsAreRejectedAndLeadingWhitespaceIsSupported() {
        val encrypted = encryptOrExplain("")
        assertTrue(SecureBackup.isEncrypted(" \n$encrypted"))
        assertNotNull(SecureBackup.decrypt(" \n$encrypted", password))
        assertEquals("", SecureBackup.decrypt(encrypted, password))
        assertFalse(SecureBackup.isEncrypted("not a backup"))
        assertNull(SecureBackup.decrypt(encrypted.removePrefix(prefix), password))
        assertNull(SecureBackup.decrypt(prefix + "AAAA", password))
        assertNull(SecureBackup.decrypt(prefix + "not base64!", password))
    }
}
