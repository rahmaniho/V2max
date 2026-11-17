package ir.v2max.core.geo

import java.io.File

/** Install a downloaded geodata file without first deleting the working copy. */
object GeoAssetInstaller {
    private const val MIN_DOWNLOAD_BYTES = 100_000L

    /**
     * Reject short, unreadable, structurally invalid or missing-tag downloads
     * before replacing an installed file. This is not an integrity signature or
     * full semantic validation. On Android the sibling-file rename is atomic;
     * if a platform refuses replacement, leave the old file intact. Callers own
     * and remove the temporary file after success or failure.
     */
    fun install(temporary: File, target: File, candidates: List<String>): Boolean = try {
        val sourcePath = temporary.canonicalFile
        val targetPath = target.canonicalFile
        if (sourcePath == targetPath || sourcePath.parentFile != targetPath.parentFile) false
        else if (temporary.length() < MIN_DOWNLOAD_BYTES) false
        else if (GeoAssetStatus.inspect(temporary, candidates).getOrNull() == null) false
        else temporary.renameTo(target)
    } catch (_: Exception) {
        false
    }
}
