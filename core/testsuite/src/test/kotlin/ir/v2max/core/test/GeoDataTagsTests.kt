package ir.v2max.core.test

import ir.v2max.core.geo.GeoAssetInstaller
import ir.v2max.core.geo.GeoAssetStatus
import ir.v2max.core.geo.GeoDataTags
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

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
            T.ok("diagnostic inspection preserves the read error", GeoDataTags.inspect(file, listOf("ir")).isFailure)
        }

        T.group("Geo asset evidence and safe refresh")
        val dir = Files.createTempDirectory("v2max-geo-test").toFile()
        try {
            val target = File(dir, "geoip.dat")
            val temporary = File(dir, "geoip.dat.part")
            val site = File(dir, "geosite.dat")
            val candidate = listOf("ir")
            val ready = Result.success<String?>("ir")
            val missing = Result.success<String?>(null)
            val unknown = Result.failure<String?>(IOException("fixture"))
            T.eq("two tags are ready", true, GeoAssetStatus.readiness(ready, ready))
            T.eq("a confirmed absence makes the pair incomplete", false, GeoAssetStatus.readiness(unknown, missing))
            T.eq("a failed read is unknown when the other tag exists", null, GeoAssetStatus.readiness(ready, unknown))
            T.eq("two failed reads are unknown", null, GeoAssetStatus.readiness(unknown, unknown))

            val absent = GeoAssetStatus.inspect(target, candidate)
            T.ok("a readable directory confirms a missing file", absent.isSuccess && absent.getOrNull() == null)
            target.writeText("small")
            val small = GeoAssetStatus.inspect(target, candidate)
            T.ok("a readable but too-small file is incomplete", small.isSuccess && small.getOrNull() == null)
            target.writeBytes(entry("US", "x".repeat(1200)))
            val noTag = GeoAssetStatus.inspect(target, candidate)
            T.ok("a valid file without the tag is incomplete", noTag.isSuccess && noTag.getOrNull() == null)
            target.writeBytes(entry("IR", "x".repeat(1200)))
            T.eq("a valid tag is found", "ir", GeoAssetStatus.inspect(target, candidate).getOrNull())
            target.appendBytes(byteArrayOf(10, 100))
            T.ok("a truncated existing file is unknown", GeoAssetStatus.inspect(target, candidate).isFailure)
            val notFile = File(dir, "not-a-file").apply { mkdir() }
            T.ok("a directory is not silently treated as an absent file", GeoAssetStatus.inspect(notFile, candidate).isFailure)
            val inaccessibleParent = File(dir, "missing-parent/geosite.dat")
            T.ok("an unreadable or missing parent leaves absence unknown", GeoAssetStatus.inspect(inaccessibleParent, candidate).isFailure)

            val old = "previous-working-file".toByteArray()
            target.writeBytes(old)
            temporary.writeBytes(entry("IR", "x".repeat(1200)))
            T.ok("a short download is rejected", !GeoAssetInstaller.install(temporary, target, candidate))
            T.ok("short download preserves the old file", target.readBytes().contentEquals(old))
            temporary.writeBytes(ByteArray(100_100) { 0x7f })
            T.ok("malformed download is rejected", !GeoAssetInstaller.install(temporary, target, candidate))
            T.ok("malformed download preserves the old file", target.readBytes().contentEquals(old))
            temporary.writeBytes(entry("US", "x".repeat(100_100)))
            T.ok("download without the required tag is rejected", !GeoAssetInstaller.install(temporary, target, candidate))
            T.ok("missing tag preserves the old file", target.readBytes().contentEquals(old))
            val valid = entry("IR", "x".repeat(100_100))
            temporary.writeBytes(valid + byteArrayOf(10, 100))
            T.ok("truncated download is rejected even with a tag", !GeoAssetInstaller.install(temporary, target, candidate))
            T.ok("truncated download preserves the old file", target.readBytes().contentEquals(old))
            temporary.writeBytes(valid)
            T.ok("a validated sibling download replaces the old file", GeoAssetInstaller.install(temporary, target, candidate))
            T.ok("replacement has the exact validated content", target.readBytes().contentEquals(valid) && !temporary.exists())
            T.ok("a target cannot be its own temporary file", !GeoAssetInstaller.install(target, target, candidate))
            val otherDir = File(dir, "other").apply { mkdir() }
            val foreign = File(otherDir, "other.part").apply { writeBytes(valid) }
            T.ok("cross-directory replacement is refused", !GeoAssetInstaller.install(foreign, target, candidate))
            T.ok("refused replacement leaves the installed file intact", target.readBytes().contentEquals(valid))
            temporary.writeBytes(valid)
            T.ok("a refused rename leaves the destination directory intact",
                !GeoAssetInstaller.install(temporary, notFile, candidate) && notFile.isDirectory && temporary.exists())

            site.writeBytes(valid)
            val now = System.currentTimeMillis()
            val maxAge = 10_000L
            T.ok("set stale geoip timestamp", target.setLastModified(now - 20_000L))
            T.ok("set recent geosite timestamp", site.setLastModified(now - 1_000L))
            T.ok("a newer geosite does not hide stale geoip", GeoAssetStatus.needsRefresh(target, site, now, maxAge))
            T.ok("set recent geoip timestamp", target.setLastModified(now - 2_000L))
            T.ok("both recent files do not trigger refresh", !GeoAssetStatus.needsRefresh(target, site, now, maxAge))
            T.ok("set future timestamp", site.setLastModified(now + 20_000L))
            T.ok("future timestamps do not appear fresh", GeoAssetStatus.needsRefresh(target, site, now, maxAge))
            site.writeText("too short")
            T.ok("a short asset requires refresh", GeoAssetStatus.needsRefresh(target, site, now, maxAge))
            site.delete()
            T.ok("a missing asset requires refresh", GeoAssetStatus.needsRefresh(target, site, now, maxAge))
        } finally {
            dir.deleteRecursively()
        }
    }
}
