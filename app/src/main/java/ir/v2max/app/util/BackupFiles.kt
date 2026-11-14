package ir.v2max.app.util

import java.io.IOException
import java.io.Reader

/** Bounds untrusted imports before JSON parsing or password derivation. */
internal object BackupFiles {
    private const val MAX_CHARACTERS = 16 * 1024 * 1024

    fun read(reader: Reader, limit: Int = MAX_CHARACTERS): String {
        require(limit > 0)
        val text = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (count > limit - text.length) throw IOException("backup is too large")
            text.append(buffer, 0, count)
        }
        return text.toString()
    }
}
