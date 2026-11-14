package ir.v2max.app

import ir.v2max.app.util.Pbkdf2Compat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class Pbkdf2CompatTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test fun `matches independent PBKDF2 SHA256 vectors`() {
        val salt = "salt".toByteArray()
        assertEquals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            hex(Pbkdf2Compat.derive256("password", salt, 1)))
        assertEquals("ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            hex(Pbkdf2Compat.derive256("password", salt, 2)))
        assertEquals("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            hex(Pbkdf2Compat.derive256("password", salt, 4096)))
    }

    @Test fun `handles unicode empty and long passwords`() {
        assertEquals("0b0a02f0e98f65d1d13516973ffde29c3ad3e8d0d7eb70edb19538d668cbfbd2",
            hex(Pbkdf2Compat.derive256("رمز آزمایشی 42", ByteArray(16) { it.toByte() }, 210_000)))
        assertEquals("97398411d6aea43a77acef92226ab8278d4db0668bd1d7a76a725f7680ac45c5",
            hex(Pbkdf2Compat.derive256("", byteArrayOf(), 2)))
        assertEquals("0e1985b2ad6d13f4232670653e22a731ab691dccced2115d67a30e9d4e52ba5b",
            hex(Pbkdf2Compat.derive256("a".repeat(100) + "🚀", "fixture".toByteArray(), 37)))
    }

    @Test fun `matches platform factory and rejects zero iterations`() {
        val salt = ByteArray(16) { (it * 3).toByte() }
        val password = "pass فارسی"
        val spec = PBEKeySpec(password.toCharArray(), salt, 137, 256)
        val expected = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        assertArrayEquals(expected, Pbkdf2Compat.derive256(password, salt, 137))
        assertThrows(IllegalArgumentException::class.java) { Pbkdf2Compat.derive256(password, salt, 0) }
    }
}
