package ir.v2max.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ir.v2max.app.MainActivity
import ir.v2max.app.R
import ir.v2max.app.data.TunnelUiState
import ir.v2max.core.format.Format

/**
 * نوتیفیکیشن دائمی سرویس VPN همراه با نمایش سرعت لحظه‌ای.
 */
object Notifier {

    const val CHANNEL_ID = "v2max_tunnel"
    const val NOTIFICATION_ID = 1001
    private const val UPDATE_CHANNEL_ID = "v2max_updates"
    private const val UPDATE_NOTIFICATION_ID = 1003

    /** Distinct PendingIntent identity per generation keeps queued old actions stale. */
    private fun serviceAction(context: Context, action: String, requestCode: Int, generation: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, V2maxVpnService::class.java).apply {
                this.action = action
                data = Uri.parse("v2max-action://service/$requestCode/$generation")
                putExtra(V2maxVpnService.EXTRA_ACTION_GENERATION, generation)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "وضعیت تانل",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "نمایش وضعیت و سرعت اتصال V2max"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(
        context: Context,
        state: TunnelUiState,
        showSpeed: Boolean,
        hideDetails: Boolean = false
    ): Notification {
        ensureChannel(context)

        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            flags
        )

        val generation = V2maxVpnService.currentActionGeneration()
        val stopIntent = serviceAction(context, V2maxVpnService.ACTION_STOP, 1, generation)
        val nextIntent = serviceAction(context, V2maxVpnService.ACTION_NEXT, 2, generation)
        val retestIntent = serviceAction(context, V2maxVpnService.ACTION_RETEST, 3, generation)

        val title = if (hideDetails) {
            "V2max"
        } else when {
            state.status == ir.v2max.app.data.TunnelStatus.CONNECTED ->
                state.serverName.ifBlank { "متصل" }

            state.status == ir.v2max.app.data.TunnelStatus.CONNECTING -> "در حال اتصال…"
            else -> "V2max"
        }

        val text = if (hideDetails) {
            when (state.status) {
                ir.v2max.app.data.TunnelStatus.CONNECTED -> "اتصال برقرار است"
                ir.v2max.app.data.TunnelStatus.CONNECTING -> "در حال اتصال…"
                ir.v2max.app.data.TunnelStatus.ERROR -> "اتصال ناموفق بود"
                else -> "اتصال قطع است"
            }
        } else when (state.status) {
            ir.v2max.app.data.TunnelStatus.CONNECTED ->
                if (showSpeed) "↓ ${Format.speed(state.downSpeed)}   ↑ ${Format.speed(state.upSpeed)}" else "اتصال برقرار است"

            ir.v2max.app.data.TunnelStatus.CONNECTING -> "لطفاً کمی صبر کنید…"
            ir.v2max.app.data.TunnelStatus.ERROR -> state.message.ifBlank { "اتصال ناموفق بود" }
            else -> "اتصال قطع است"
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(if (!hideDetails && state.ping > 0) "پینگ ${state.ping} ms" else null)
            .setOngoing(state.status != ir.v2max.app.data.TunnelStatus.ERROR)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openIntent)
            .addAction(0, "سرور بعدی", nextIntent)
            .addAction(0, "تست مجدد", retestIntent)
            .addAction(0, "قطع", stopIntent)
            .build()
    }

    fun update(
        context: Context,
        state: TunnelUiState,
        showSpeed: Boolean,
        hideDetails: Boolean = false
    ) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.notify(NOTIFICATION_ID, build(context, state, showSpeed, hideDetails))
        }
    }

    /** اعلان رسیدن به سقف مصرف روزانه */
    fun dailyLimitReached(context: Context, limitMb: Int) {
        ensureChannel(context)
        val intent = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("سقف مصرف روزانه")
            .setContentText("$limitMb مگابایت مصرف شد؛ اتصال قطع شد")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(intent)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID + 1, notification)
        }
    }

    /** اعلان حالت Kill Switch: ترافیک مسدود است و دکمه‌ی رفع مسدودی دارد */
    fun blocked(context: Context, reason: String) {
        ensureChannel(context)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            context,
            20,
            Intent(context, MainActivity::class.java),
            flags
        )
        val unblock = serviceAction(context, V2maxVpnService.ACTION_UNBLOCK, 21, V2maxVpnService.currentActionGeneration())
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("ترافیک مسدود است (Kill Switch)")
            .setContentText(reason.ifBlank { "تانل قطع شد؛ برای بازگشت اینترنت، مسدودی را بردارید" })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, "رفع مسدودی", unblock)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification)
        }
    }

    /** Best-effort system notification; opening it shows the in-app installer. */
    fun updateAvailable(context: Context, version: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(
                UPDATE_CHANNEL_ID, "به‌روزرسانی برنامه", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "اطلاع از انتشار نسخهٔ جدید V2max" })
            if (manager.getNotificationChannel(UPDATE_CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        val open = PendingIntent.getActivity(context, 50,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("نسخهٔ جدید V2max")
            .setContentText("نسخهٔ $version منتشر شده است؛ برای بررسی و نصب باز کنید")
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(UPDATE_NOTIFICATION_ID, notification)
        true
    }.getOrDefault(false)

    fun cancelUpdate(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(UPDATE_NOTIFICATION_ID) }
    }

    fun cancel(context: Context) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.cancel(NOTIFICATION_ID)
        }
    }
}
