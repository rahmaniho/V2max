package ir.v2max.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ir.v2max.app.MainActivity
import ir.v2max.app.core.TunnelController
import ir.v2max.app.data.Repo
import ir.v2max.app.net.LinkParser

/**
 * API اتوماسیون برای Tasker / Automate / اسکریپت‌های adb.
 *
 * نمونه‌ها:
 *   am broadcast -a ir.v2max.app.action.CONNECT -n ir.v2max.app/.service.AutoReceiver
 *   am broadcast -a ir.v2max.app.action.CONNECT -n ir.v2max.app/.service.AutoReceiver --es id <serverId>
 *   am broadcast -a ir.v2max.app.action.DISCONNECT -n ir.v2max.app/.service.AutoReceiver
 *   am broadcast -a ir.v2max.app.action.TOGGLE -n ir.v2max.app/.service.AutoReceiver
 *   am broadcast -a ir.v2max.app.action.IMPORT -n ir.v2max.app/.service.AutoReceiver --es text "vless://…"
 *
 * نکته: برای ID سرور می‌توانید از فهرست سرورهای برنامه (کپی شناسه در صفحه‌ی ویرایش) استفاده کنید.
 */
class AutoReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        runCatching { Repo.init(app) }

        when (intent.action) {
            MainActivity.ACTION_CONNECT -> {
                val id = intent.getStringExtra(MainActivity.EXTRA_ID)
                TunnelController.start(app, id)
            }

            MainActivity.ACTION_DISCONNECT -> TunnelController.stop(app)

            MainActivity.ACTION_TOGGLE -> TunnelController.toggle(app)

            MainActivity.ACTION_SMART -> {
                // انتخاب بهترین سرور سالم و اتصال؛ تست‌های سنگین در سرویس انجام می‌شود
                val best = Repo.servers.value
                    .filter { it.hasUsableEndpoint() }
                    .minByOrNull { it.score() }
                if (best != null) TunnelController.start(app, best.id)
            }

            MainActivity.ACTION_IMPORT -> {
                val text = intent.getStringExtra(MainActivity.EXTRA_TEXT).orEmpty()
                if (text.isNotBlank()) {
                    val parsed = LinkParser.parseAll(text)
                    if (parsed.isNotEmpty()) Repo.addServers(parsed)
                }
            }
        }
    }

    companion object {
        /** برای سازگاری با فراخوانی‌های اختیاری از MainActivity */
        fun consume(context: Context): Intent? = null
    }
}
