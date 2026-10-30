package ir.v2max.app.net

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import ir.v2max.app.service.InstallResultReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * دانلود و نصب نسخه‌ی جدید از داخل برنامه.
 *
 * امنیت:
 *  • APK دانلودشده فقط وقتی نصب می‌شود که **امضای آن با نسخه‌ی نصب‌شده یکسان** باشد؛
 *    این کار جلوی نصب بسته‌ی جعلی از مسیر CDN/آینه‌ی آلوده را می‌گیرد.
 *  • فایل در cache خصوصی برنامه ذخیره و بعد از نصب پاک می‌شود.
 */
object UpdateInstaller {

    private const val TAG = "UpdateInstaller"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    sealed interface Result {
        data class Progress(val downloaded: Long, val total: Long) : Result
        data class Ready(val file: File) : Result
        data class Failure(val message: String) : Result
    }

    /** دانلود فایل APK به همراه بررسی امضا */
    suspend fun download(
        context: Context,
        url: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "v2max-update.apk")

            val request = Request.Builder().url(url).header("User-Agent", "V2max").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.Failure("دانلود ناموفق (${response.code})")
                }
                val body = response.body ?: return@withContext Result.Failure("پاسخ خالی بود")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
            }

            if (!target.exists() || target.length() < 1_000_000L) {
                target.delete()
                return@withContext Result.Failure("فایل دانلودشده معتبر نیست")
            }

            if (!sameSignature(context, target)) {
                target.delete()
                return@withContext Result.Failure("امضای فایل با نسخه‌ی نصب‌شده یکسان نیست؛ نصب لغو شد")
            }

            Result.Ready(target)
        } catch (t: Throwable) {
            Log.w(TAG, "download failed", t)
            Result.Failure(t.message ?: "خطای شبکه")
        }
    }

    /** نصب APK با PackageInstaller (بدون خروج از برنامه) */
    fun install(context: Context, apk: File): Boolean = runCatching {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("v2max.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val callbackIntent = Intent(context, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE
                else 0
            val pending = PendingIntent.getBroadcast(context, sessionId, callbackIntent, flags)
            session.commit(pending.intentSender)
        }
        true
    }.getOrElse {
        Log.w(TAG, "install failed", it)
        false
    }

    /** مقایسه‌ی امضای APK دانلودشده با بسته‌ی نصب‌شده */
    private fun sameSignature(context: Context, apk: File): Boolean {
        return runCatching {
            val pm = context.packageManager
            val installed = pm.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES
            ).signatures?.map { it.toCharsString() }?.toSet().orEmpty()

            val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(
                    apk.absolutePath,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNATURES.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
            } ?: return false

            val downloaded = archiveInfo.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
            downloaded.isNotEmpty() && installed == downloaded
        }.getOrDefault(false)
    }
}
