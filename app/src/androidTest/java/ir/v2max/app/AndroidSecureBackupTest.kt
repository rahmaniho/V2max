package ir.v2max.app

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    @Test fun encryptedBackupRoundTripsAndUsesFreshSaltAndIv() {
        val first = requireNotNull(SecureBackup.encrypt(payload, password))
        val second = requireNotNull(SecureBackup.encrypt(payload, password))
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
        assertEquals(payload, SecureBackup.decrypt(vector, password))
    }

    @Test fun wrongPasswordAndTamperingAreRejected() {
        val encrypted = requireNotNull(SecureBackup.encrypt(payload, password))
        assertNull("A wrong password was accepted", SecureBackup.decrypt(encrypted, "not-the-password"))
        val bytes = Base64.decode(encrypted.removePrefix(prefix), Base64.DEFAULT)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val tampered = prefix + Base64.encodeToString(bytes, Base64.NO_WRAP)
        assertNull("A modified authentication tag was accepted", SecureBackup.decrypt(tampered, password))
    }

    @Test fun invalidInputsAreRejectedAndLeadingWhitespaceIsSupported() {
        val encrypted = requireNotNull(SecureBackup.encrypt("", password))
        assertTrue(SecureBackup.isEncrypted(" \n$encrypted"))
        assertNotNull(SecureBackup.decrypt(" \n$encrypted", password))
        assertEquals("", SecureBackup.decrypt(encrypted, password))
        assertFalse(SecureBackup.isEncrypted("not a backup"))
        assertNull(SecureBackup.decrypt(encrypted.removePrefix(prefix), password))
        assertNull(SecureBackup.decrypt(prefix + "AAAA", password))
        assertNull(SecureBackup.decrypt(prefix + "not base64!", password))
    }
}
