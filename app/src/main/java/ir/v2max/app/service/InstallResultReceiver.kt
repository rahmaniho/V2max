package ir.v2max.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import ir.v2max.app.data.Repo
import ir.v2max.app.data.TunnelStatus

/**
 * نتیجه‌ی نصب به‌روزرسانی از PackageInstaller را دریافت می‌کند و وضعیت را در مخزن می‌گذارد
 * تا رابط کاربری پیام مناسب نشان دهد.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()

        val text = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // سیستم از کاربر تأیید می‌خواهد
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                }
                "برای تکمیل به‌روزرسانی، نصب را تأیید کنید"
            }
            PackageInstaller.STATUS_SUCCESS -> "برنامه با موفقیت به‌روزرسانی شد"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "نصب لغو شد"
            else -> "نصب ناموفق بود: $message"
        }

        Repo.tunnel.value = Repo.tunnel.value.copy(
            status = if (Repo.tunnel.value.status == TunnelStatus.ERROR) TunnelStatus.ERROR else Repo.tunnel.value.status,
            message = text
        )
        Repo.installMessage.value = text
    }
}
