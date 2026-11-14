package ir.v2max.app.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

/** Download into a private file; cancellation closes the underlying network call. */
internal object UpdateTransfer {
    private const val MAX_APK_BYTES = 256L * 1024L * 1024L

    sealed interface Result {
        data object Complete : Result
        data class Failure(val message: String) : Result
    }

    suspend fun save(
        call: Call,
        target: File,
        onProgress: (Long, Long) -> Unit,
        maxBytes: Long = MAX_APK_BYTES
    ): Result = suspendCancellableCoroutine { continuation ->
        require(maxBytes > 0)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(Result.Failure("خطای شبکه در دریافت به‌روزرسانی"))
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.close()
                    target.delete()
                    return
                }
                val result = try {
                    response.use responseUse@ { received ->
                        if (!received.isSuccessful) return@responseUse Result.Failure("دانلود ناموفق (${received.code})")
                        val body = received.body ?: return@responseUse Result.Failure("پاسخ خالی بود")
                        val total = body.contentLength()
                        if (total > maxBytes) return@responseUse Result.Failure("فایل به‌روزرسانی بیش از حد بزرگ است")
                        body.byteStream().use { input ->
                            target.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var downloaded = 0L
                                while (continuation.isActive) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    if (read == 0) continue
                                    if (read.toLong() > maxBytes - downloaded) {
                                        return@responseUse Result.Failure("فایل به‌روزرسانی بیش از حد بزرگ است")
                                    }
                                    output.write(buffer, 0, read)
                                    downloaded += read
                                    onProgress(downloaded, total)
                                }
                                if (!continuation.isActive) {
                                    target.delete()
                                    return
                                }
                            }
                        }
                        Result.Complete
                    }
                } catch (_: Exception) {
                    Result.Failure("خواندن فایل به‌روزرسانی ناموفق بود")
                }
                if (continuation.isActive) continuation.resume(result) else target.delete()
            }
        })
    }
}
