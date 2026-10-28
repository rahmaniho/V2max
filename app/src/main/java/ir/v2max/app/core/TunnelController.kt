package ir.v2max.app.core

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import ir.v2max.app.data.Repo
import ir.v2max.app.service.V2maxVpnService

/**
 * فرمان‌دهی به سرویس VPN از هر جای برنامه (UI، ویجت، تایل، BootReceiver).
 */
object TunnelController {

    fun start(context: Context, serverId: String? = null) {
        val id = serverId ?: Repo.selectedId.value.ifBlank {
            Repo.servers.value.firstOrNull()?.id.orEmpty()
        }
        if (id.isNotBlank()) Repo.setSelected(id)

        val intent = Intent(context.applicationContext, V2maxVpnService::class.java).apply {
            action = V2maxVpnService.ACTION_START
            putExtra(V2maxVpnService.EXTRA_SERVER_ID, id)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            } else {
                context.applicationContext.startService(intent)
            }
        } catch (t: Throwable) {
            Repo.tunnel.value = Repo.tunnel.value.copy(
                status = ir.v2max.app.data.TunnelStatus.ERROR,
                message = "اجرای سرویس ممکن نشد: ${t.message ?: "خطای ناشناخته"}"
            )
        }
    }

    fun stop(context: Context) {
        val intent = Intent(context.applicationContext, V2maxVpnService::class.java).apply {
            action = V2maxVpnService.ACTION_STOP
        }
        try {
            context.applicationContext.startService(intent)
        } catch (t: Throwable) {
            // اگر سرویس در حال اجرا نباشد، صرفاً وضعیت را صفر می‌کنیم
            Repo.tunnel.value = Repo.tunnel.value.copy(status = ir.v2max.app.data.TunnelStatus.DISCONNECTED)
        }
    }

    fun toggle(context: Context) {
        if (Repo.tunnel.value.isActive) stop(context) else start(context)
    }
}
