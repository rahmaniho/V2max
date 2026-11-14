package ir.v2max.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import ir.v2max.app.data.Repo

/** Receives the outcome of our own PackageInstaller session. */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(
            PackageInstaller.EXTRA_SESSION_ID,
            intent.getIntExtra(EXTRA_LOCAL_SESSION_ID, -1)
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty().take(160)
        val text = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmation == null) {
                    InstallConfirmation.clear(sessionId)
                    Notifier.cancelInstallConfirmation(context, sessionId)
                    "صفحهٔ تأیید نصب دریافت نشد؛ دوباره تلاش کنید"
                } else {
                    InstallConfirmation.offer(sessionId, confirmation)
                    val notified = Notifier.installConfirmation(context, sessionId, confirmation)
                    if (notified) "برای تکمیل نصب، از برنامه یا اعلان تأیید اندروید را باز کنید"
                    else "برای تکمیل نصب، برنامه را باز و تأیید اندروید را انجام دهید"
                }
            }
            else -> {
                InstallConfirmation.clear(sessionId)
                Notifier.cancelInstallConfirmation(context, sessionId)
                when (status) {
                    PackageInstaller.STATUS_SUCCESS -> "برنامه با موفقیت به‌روزرسانی شد"
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "نصب لغو شد"
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "نصب مسدود شد؛ اجازهٔ نصب برنامه از این منبع را در تنظیمات اندروید بررسی کنید"
                    else -> if (message.isBlank()) "نصب ناموفق بود؛ دوباره تلاش کنید" else "نصب ناموفق بود: $message"
                }
            }
        }
        // Installation is unrelated to VPN state: never replace its status text.
        Repo.installMessage.value = text
    }

    companion object {
        // Preserved in our explicit, mutable callback PendingIntent if the
        // platform's result omits EXTRA_SESSION_ID.
        const val EXTRA_LOCAL_SESSION_ID = "ir.v2max.app.extra.INSTALL_SESSION_ID"
    }
}
