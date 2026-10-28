package ir.v2max.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ir.v2max.app.core.TunnelController
import ir.v2max.app.data.Repo

/** اتصال خودکار پس از روشن شدن دستگاه */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        Repo.init(context)
        val settings = Repo.settings.value
        if (!settings.autoConnectOnBoot) return
        if (Repo.servers.value.isEmpty()) return
        Log.i("V2maxBoot", "auto connect after boot")
        runCatching { TunnelController.start(context) }
    }
}
