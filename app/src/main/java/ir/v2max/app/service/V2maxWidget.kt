package ir.v2max.app.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import ir.v2max.app.MainActivity
import ir.v2max.app.R
import ir.v2max.app.core.TunnelController
import ir.v2max.app.data.Repo
import ir.v2max.app.data.TunnelStatus

/**
 * ویجت صفحه‌ی اصلی: نمایش وضعیت و یک ضربه برای اتصال/قطع.
 */
class V2maxWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Repo.init(context)
        ids.forEach { render(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // Old widget PendingIntents can still target this exported provider
            // after an upgrade. Refresh them, but never trust this public action.
            Repo.init(context)
            refresh(context)
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_TOGGLE = "ir.v2max.app.widget.TOGGLE"

        /** Launcher invokes this immutable PendingIntent with the app's identity. */
        internal fun toggleIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            100,
            Intent(context, WidgetActionReceiver::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        /** فراخوانی از رابط کاربری/سرویس برای تازه‌سازی ویجت‌ها */
        fun refresh(context: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(context, V2maxWidget::class.java))
                if (ids.isEmpty()) return
                ids.forEach { render(context, manager, it) }
            }
        }

        private fun render(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_v2max)
            val tunnel = Repo.tunnel.value
            val server = Repo.selectedServer()

            val active = tunnel.isActive
            views.setTextViewText(
                R.id.widget_status,
                when (tunnel.status) {
                    TunnelStatus.CONNECTED -> "متصل"
                    TunnelStatus.CONNECTING -> "در حال اتصال"
                    TunnelStatus.DISCONNECTING -> "در حال قطع"
                    TunnelStatus.ERROR -> "خطا"
                    else -> "قطع"
                }
            )
            views.setTextViewText(
                R.id.widget_server,
                server?.displayName()?.take(28) ?: "سروری انتخاب نشده"
            )
            views.setTextViewText(R.id.widget_action, if (active) "قطع" else "اتصال")

            views.setOnClickPendingIntent(R.id.widget_action, toggleIntent(context))

            val open = PendingIntent.getActivity(
                context,
                101,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_server, open)
            views.setOnClickPendingIntent(R.id.widget_status, open)

            manager.updateAppWidget(id, views)
        }
    }
}

/** The exported widget provider accepts Android's updates, not VPN commands. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != V2maxWidget.ACTION_TOGGLE) return
        Repo.init(context)
        if (Repo.tunnel.value.isActive) TunnelController.stop(context) else TunnelController.start(context)
        V2maxWidget.refresh(context)
    }
}
