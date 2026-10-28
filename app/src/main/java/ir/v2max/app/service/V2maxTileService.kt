package ir.v2max.app.service

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import ir.v2max.app.MainActivity
import ir.v2max.app.core.TunnelController
import ir.v2max.app.data.Repo
import ir.v2max.app.data.TunnelStatus

/** تایل تنظیمات سریع برای اتصال/قطع سریع */
@RequiresApi(Build.VERSION_CODES.N)
class V2maxTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val connected = Repo.tunnel.value.status == TunnelStatus.CONNECTED
        tile.state = if (connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "V2max"
        runCatching { tile.updateTile() }
    }

    override fun onClick() {
        super.onClick()
        Repo.init(this)
        if (Repo.servers.value.isEmpty()) {
            openApp()
            return
        }
        if (Repo.tunnel.value.isActive) {
            TunnelController.stop(this)
        } else {
            val pending = VpnService.prepare(this)
            if (pending != null) {
                openApp()
            } else {
                TunnelController.start(this)
            }
        }
        refresh()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0, intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
