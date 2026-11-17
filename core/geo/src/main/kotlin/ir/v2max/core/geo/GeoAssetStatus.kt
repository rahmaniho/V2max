package ir.v2max.core.geo

import java.io.File
import java.io.IOException

/** Evidence about the particular geodata files used by the Android app. */
object GeoAssetStatus {
    private const val MIN_BYTES = 1024L

    /**
     * Success with a tag means it was found in a structurally read file, not
     * that the complete file is semantically valid. Success with null means
     * the file is absent, too small, or has no matching tag. Failure means the
     * file could not be inspected, not that it is absent. Exception details
     * must not be included in diagnostic reports.
     */
    fun inspect(file: File, candidates: List<String>): Result<String?> = try {
        if (!file.exists()) {
            // File.exists() also returns false for some I/O errors. Only claim
            // absence when a readable parent directory confirms the name is gone.
            val names = file.absoluteFile.parentFile?.list()
            if (names != null && file.name !in names) Result.success(null)
            else Result.failure(IOException("Unable to inspect geodata"))
        } else if (!file.isFile) {
            Result.failure(IOException("Unable to inspect geodata"))
        } else if (file.length() < MIN_BYTES) {
            // Open even small files so access errors are not mistaken for size 0.
            file.inputStream().use { }
            Result.success(null)
        } else {
            GeoDataTags.inspect(file, candidates)
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** A newer file must not hide the age or absence of the other file. */
    fun needsRefresh(geoip: File, geosite: File, nowMillis: Long, maxAgeMillis: Long): Boolean = try {
        nowMillis <= 0 || maxAgeMillis < 0 || listOf(geoip, geosite).any { file ->
            val modified = file.lastModified()
            !file.isFile || file.length() < 100_000L || modified <= 0L || modified > nowMillis ||
                nowMillis - modified > maxAgeMillis
        }
    } catch (_: Exception) {
        true
    }

    /** Both files are needed for the diagnostic's ready state. */
    fun readiness(geoip: Result<String?>, geosite: Result<String?>): Boolean? {
        fun state(result: Result<String?>): Boolean? =
            if (result.isFailure) null else result.getOrNull() != null
        val ip = state(geoip)
        val site = state(geosite)
        return when {
            ip == false || site == false -> false
            ip == true && site == true -> true
            else -> null
        }
    }
}
