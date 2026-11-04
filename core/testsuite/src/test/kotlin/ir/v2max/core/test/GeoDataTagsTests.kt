package ir.v2max.core.test

import ir.v2max.core.geo.GeoDataTags
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Tests for the GeoIP/GeoSite protobuf reader.
 *
 * The parser is hand-rolled and reads a binary format, so it is verified
 * against real encoded bytes rather than described in prose. The fixture
 * helpers below emit the same wire format Xray's geo assets use.
 *
 * These cases were previously asserted only in the app's JUnit suite; they are
 * here so the parser is exercised by the local harness too.
 */
object GeoDataTagsTests {

    private fun varint(value: Int): ByteArray {
        var v = value
        val out = ByteArrayOutputStream()
        while (v >= 128) {
            out.write((v and 127) or 128)
            v = v ushr 7
        }
        out.write(v)
        return out.toByteArray()
    }

    /** A length-delimited field: tag, length, payload. */
    private fun field(number: Int, bytes: ByteArray): ByteArray =
        byteArrayOf(((number shl 3) or 2).toByte()) + varint(bytes.size) + bytes

    /** A geodata entry: field 1 holds the code, field 2 stands in for domains. */
    private fun entry(code: String, text: String): ByteArray =
        field(1, field(1, code.toByteArray()) + field(2, text.toByteArray()))

    private fun <T> withTempFile(body: (File) -> T): T {
        val file = File.createTempFile("geodata", ".dat")
        try {
            return body(file)
        } finally {
            file.delete()
        }
    }

    fun run() {
        T.group("GeoDataTags")

        // ---- the code field is matched, never text inside a domain
        // This is the reason the parser exists: "persia.ir" inside a US entry
        // must not be read as an Iranian category.
        withTempFile { file ->
            file.writeBytes(entry("US", "persia.ir"))
            T.eq("domain text is not a match", null, GeoDataTags.find(file, listOf("ir")))
        }

        // ---- a real code entry is found
        withTempFile { file ->
            file.writeBytes(entry("US", "persia.ir"))
            T.eq("absent code not found", null, GeoDataTags.find(file, listOf("ir")))
            file.writeBytes(entry("US", "x") + entry("IR", "domain"))
            T.eq("code entry found", "ir", GeoDataTags.find(file, listOf("ir")))
        }

        // ---- a truncated entry invalidates the whole file
        // Better to report "no geo data" than to trust a half-read file.
        withTempFile { file ->
            file.writeBytes(entry("IR", "domain"))
            T.eq("valid file before truncation", "ir", GeoDataTags.find(file, listOf("ir")))
            file.appendBytes(byteArrayOf(10, 100)) // claims 100 bytes, carries none
            T.eq("truncated file rejected", null, GeoDataTags.find(file, listOf("ir")))
        }

        // ---- matching is case-insensitive on both sides
        withTempFile { file ->
            file.writeBytes(entry("IrAn", "x"))
            T.eq("code case-insensitive", "iran", GeoDataTags.find(file, listOf("iran")))
            // The comparison is case-insensitive, but the value returned is the
            // candidate the caller passed, verbatim. That matters because
            // GeoAssets uses the result as a tag name, so it must be the string
            // it asked for rather than a normalised copy.
            T.eq("candidate returned verbatim", "IRAN", GeoDataTags.find(file, listOf("IRAN")))
        }

        // ---- candidates are tried in order, first present wins
        withTempFile { file ->
            file.writeBytes(entry("IR", "a") + entry("DE", "b"))
            T.eq("first candidate wins", "de", GeoDataTags.find(file, listOf("de", "ir")))
            T.eq("candidate order respected", "ir", GeoDataTags.find(file, listOf("ir", "de")))
        }

        // ---- multi-byte varint lengths are decoded, not just single-byte ones
        // A 200-byte payload needs two varint bytes; the entry must still be
        // skipped correctly so later entries stay readable.
        withTempFile { file ->
            val long = "d".repeat(200)
            file.writeBytes(entry("US", long) + entry("IR", "domain"))
            T.eq("multi-byte varint length skipped", "ir", GeoDataTags.find(file, listOf("ir")))
        }

        // ---- other wire types inside an entry are skipped without misreading
        withTempFile { file ->
            val inner = field(1, "IR".toByteArray()) +
                // field 3, varint (wire type 0)
                byteArrayOf(((3 shl 3) or 0).toByte()) + varint(1234) +
                // field 4, fixed64 (wire type 1)
                byteArrayOf(((4 shl 3) or 1).toByte()) + ByteArray(8) +
                // field 5, fixed32 (wire type 5)
                byteArrayOf(((5 shl 3) or 5).toByte()) + ByteArray(4) +
                // field 6, length-delimited but not the code
                field(6, "not-a-code".toByteArray())
            file.writeBytes(field(1, inner))
            T.eq("code read despite other fields", "ir", GeoDataTags.find(file, listOf("ir")))
        }

        // ---- a top-level field that is not an entry is rejected
        withTempFile { file ->
            file.writeBytes(field(2, "junk".toByteArray())) // key 18, not 10
            T.eq("unexpected top-level field rejected", null, GeoDataTags.find(file, listOf("ir")))
        }

        // ---- empty and missing files
        withTempFile { file ->
            file.writeBytes(ByteArray(0))
            T.eq("empty file", null, GeoDataTags.find(file, listOf("ir")))
        }
        withTempFile { file ->
            file.delete()
            T.eq("missing file", null, GeoDataTags.find(file, listOf("ir")))
        }

        // ---- no candidates can never match
        withTempFile { file ->
            file.writeBytes(entry("IR", "x"))
            T.eq("no candidates", null, GeoDataTags.find(file, emptyList()))
        }

        // ---- garbage is handled without throwing
        withTempFile { file ->
            file.writeBytes("this is not protobuf at all".toByteArray())
            T.eq("garbage input returns null", null, GeoDataTags.find(file, listOf("ir")))
        }
    }
}
