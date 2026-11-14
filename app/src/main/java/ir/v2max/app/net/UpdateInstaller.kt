package ir.v2max.app.net

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import ir.v2max.app.service.InstallResultReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
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

    /** Android 8+ requires the user to allow this app to request package installs. */
    fun canRequestInstalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /** Called only in response to an explicit in-app install request. */
    fun openInstallSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    /** دانلود فایل APK به همراه بررسی امضا */
    suspend fun download(
        context: Context,
        url: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result = withContext(Dispatchers.IO) {
        var target: File? = null
        try {
            if (!UpdateAssets.isOfficialDownload(url)) {
                return@withContext Result.Failure("آدرس دریافت به‌روزرسانی معتبر نیست")
            }
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            // Only abandoned files are removed. Concurrent downloads use distinct
            // paths, so cancellation cannot delete another update being installed.
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
            dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
            val file = File.createTempFile("v2max-", ".apk", dir)
            target = file

            val request = Request.Builder().url(url).header("User-Agent", "V2max").build()
            when (val transfer = UpdateTransfer.save(client.newCall(request), file, onProgress)) {
                is UpdateTransfer.Result.Failure -> {
                    file.delete()
                    return@withContext Result.Failure(transfer.message)
                }
                UpdateTransfer.Result.Complete -> Unit
            }
            coroutineContext.ensureActive()
            if (!file.exists() || file.length() < 1_000_000L) {
                file.delete()
                return@withContext Result.Failure("فایل دانلودشده معتبر نیست")
            }
            if (!sameSignature(context, file)) {
                file.delete()
                return@withContext Result.Failure("امضای فایل با نسخه‌ی نصب‌شده یکسان نیست؛ نصب لغو شد")
            }
            Result.Ready(file)
        } catch (e: CancellationException) {
            target?.delete()
            throw e
        } catch (t: Exception) {
            target?.delete()
            Log.w(TAG, "download failed", t)
            Result.Failure("دریافت به‌روزرسانی ناموفق بود")
        }
    }

    /** نصب APK با PackageInstaller (بدون خروج از برنامه) */
    fun install(context: Context, apk: File): Boolean {
        var installer: PackageInstaller? = null
        var sessionId: Int? = null
        return try {
            // Recheck immediately before handing the file to Android's installer.
            if (!sameSignature(context, apk)) return false
            val packageInstaller = context.packageManager.packageInstaller
            installer = packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }
            val id = packageInstaller.createSession(params)
            sessionId = id
            packageInstaller.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("v2max.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callbackIntent = Intent(context, InstallResultReceiver::class.java).apply {
                    putExtra(InstallResultReceiver.EXTRA_LOCAL_SESSION_ID, id)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(context, id, callbackIntent, flags)
                session.commit(pending.intentSender)
            }
            true
        } catch (t: Exception) {
            sessionId?.let { id -> runCatching { installer?.abandonSession(id) } }
            Log.w(TAG, "install failed", t)
            false
        } finally {
            // PackageInstaller has its own session copy; remove our private APK copy.
            val isPrivateUpdate = runCatching {
                apk.canonicalFile.parentFile == File(context.cacheDir, "updates").canonicalFile
            }.getOrDefault(false)
            if (isPrivateUpdate) apk.delete()
        }
    }

    /** مقایسه‌ی امضای APK دانلودشده با بسته‌ی نصب‌شده */
    internal fun sameSignature(context: Context, apk: File): Boolean {
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

            if (archiveInfo.packageName != context.packageName) return false
            val downloaded = archiveInfo.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
            downloaded.isNotEmpty() && installed == downloaded
        }.getOrDefault(false)
    }
}
