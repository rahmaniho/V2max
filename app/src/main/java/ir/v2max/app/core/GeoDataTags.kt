package ir.v2max.app.core

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.util.Locale

/** Reads only the code field of GeoIPList/GeoSiteList protobuf entries, not domain contents. */
internal object GeoDataTags {
    fun find(file: File, candidates: List<String>): String? = runCatching {
        val found = mutableSetOf<String>()
        BufferedInputStream(file.inputStream()).use { input ->
            var remaining = file.length()
            while (remaining > 0) {
                val (key, keyBytes) = readVarint(input)
                remaining -= keyBytes
                if (key != 10L) throw IllegalArgumentException("Unexpected geodata field")
                val (length, lengthBytes) = readVarint(input)
                remaining -= lengthBytes
                require(length in 0..remaining) { "Truncated geodata entry" }
                val code = readEntry(input, length)
                if (code != null) found.add(code.lowercase(Locale.ROOT))
                remaining -= length
            }
        }
        candidates.firstOrNull { it.lowercase(Locale.ROOT) in found }
    }.getOrNull()

    private fun readEntry(input: InputStream, size: Long): String? {
        var remaining = size
        var code: String? = null
        while (remaining > 0) {
            val (key, keyBytes) = readVarint(input)
            remaining -= keyBytes
            require(key > 0 && remaining >= 0)
            val field = key ushr 3
            when ((key and 7).toInt()) {
                0 -> {
                    val (_, n) = readVarint(input)
                    remaining -= n
                }
                1 -> { skipFully(input, 8); remaining -= 8 }
                2 -> {
                    val (length, n) = readVarint(input)
                    remaining -= n
                    require(length in 0..remaining)
                    if (field == 1L && length in 1..128) {
                        val bytes = ByteArray(length.toInt())
                        var offset = 0
                        while (offset < bytes.size) {
                            val count = input.read(bytes, offset, bytes.size - offset)
                            if (count < 0) throw EOFException()
                            offset += count
                        }
                        code = String(bytes, Charsets.UTF_8)
                    } else skipFully(input, length)
                    remaining -= length
                }
                5 -> { skipFully(input, 4); remaining -= 4 }
                else -> throw IllegalArgumentException("Unsupported protobuf wire type")
            }
            require(remaining >= 0)
        }
        return code
    }

    private fun readVarint(input: InputStream): Pair<Long, Int> {
        var result = 0L
        for (index in 0 until 10) {
            val byte = input.read()
            if (byte < 0) throw EOFException()
            if (index == 9 && byte > 1) throw IllegalArgumentException("Invalid varint")
            result = result or ((byte and 0x7f).toLong() shl (index * 7))
            if ((byte and 0x80) == 0) return result to index + 1
        }
        throw IllegalArgumentException("Invalid varint")
    }

    private fun skipFully(input: InputStream, size: Long) {
        var left = size
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) left -= skipped
            else {
                if (input.read() < 0) throw EOFException()
                left--
            }
        }
    }
}
