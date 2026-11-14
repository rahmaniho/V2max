package ir.v2max.app.core

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import ir.v2max.app.data.Repo
import ir.v2max.app.data.TunnelStatus
import ir.v2max.app.service.Notifier
import ir.v2max.app.service.V2maxVpnService

/**
 * فرمان‌دهی به سرویس VPN از هر جای برنامه (UI، ویجت، تایل، BootReceiver).
 */
object TunnelController {

    /** Keep visible controls bound to the latest action while a live VPN is preserved. */
    private fun refreshNotification(context: Context) {
        val state = Repo.tunnel.value
        if (V2maxVpnService.running && state.status == TunnelStatus.CONNECTED) {
            val settings = Repo.settings.value
            Notifier.update(context.applicationContext, state,
                settings.showSpeedInNotification && !settings.hideNotificationContent,
                settings.hideNotificationContent)
        }
    }

    fun start(context: Context, serverId: String? = null, smart: Boolean = false) {
        SmartConnect.cancelScan()
        val generation = V2maxVpnService.invalidatePendingActions()
        refreshNotification(context)
        val id = serverId ?: Repo.selectedId.value.ifBlank {
            Repo.servers.value.firstOrNull()?.id.orEmpty()
        }
        if (id.isNotBlank()) Repo.setSelected(id)
        sendStart(context, id, smart, null, generation)
    }

    internal fun startVerified(context: Context, selection: VerifiedSelection) {
        val generation = V2maxVpnService.currentActionGeneration()
        sendStart(context, selection.id, false, PreflightTickets.issue(selection), generation)
    }

    internal fun matchesActiveProfile(server: ir.v2max.app.data.ServerConfig, settings: ir.v2max.app.data.AppSettings): Boolean =
        V2maxVpnService.activeProfileMatches(server, settings)

    private fun sendStart(context: Context, id: String, smart: Boolean, ticket: String?, generation: Int) {
        val intent = Intent(context.applicationContext, V2maxVpnService::class.java).apply {
            action = V2maxVpnService.ACTION_START
            putExtra(V2maxVpnService.EXTRA_SERVER_ID, id)
            putExtra(V2maxVpnService.EXTRA_PREFLIGHT_TICKET, ticket)
            putExtra(V2maxVpnService.EXTRA_SMART_SELECT, smart)
            putExtra(V2maxVpnService.EXTRA_ACTION_GENERATION, generation)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            } else {
                context.applicationContext.startService(intent)
            }
        } catch (t: Throwable) {
            val previous = Repo.tunnel.value
            Repo.tunnel.value = previous.copy(
                status = if (previous.status == ir.v2max.app.data.TunnelStatus.CONNECTED && XrayCore.isRunning)
                    previous.status else ir.v2max.app.data.TunnelStatus.ERROR,
                message = "اجرای سرویس ممکن نشد: ${t.message ?: "خطای ناشناخته"}"
            )
        }
    }

    /** Cancel a replacement search without stopping the currently connected VPN. */
    fun cancelPreflight(context: Context) {
        // Revoke scan receipts before advancing the service generation; otherwise
        // an old scan could issue a fresh-looking receipt in the gap.
        SmartConnect.cancelScan()
        val generation = V2maxVpnService.invalidatePendingActions()
        refreshNotification(context)
        val intent = Intent(context.applicationContext, V2maxVpnService::class.java).apply {
            action = V2maxVpnService.ACTION_CANCEL_PREFLIGHT
            putExtra(V2maxVpnService.EXTRA_ACTION_GENERATION, generation)
        }
        runCatching { context.applicationContext.startService(intent) }
    }

    fun stop(context: Context) {
        SmartConnect.cancelScan()
        val generation = V2maxVpnService.invalidatePendingActions()
        refreshNotification(context)
        val intent = Intent(context.applicationContext, V2maxVpnService::class.java).apply {
            action = V2maxVpnService.ACTION_STOP
            putExtra(V2maxVpnService.EXTRA_ACTION_GENERATION, generation)
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
