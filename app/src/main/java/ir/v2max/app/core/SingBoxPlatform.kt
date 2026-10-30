package ir.v2max.app.core

import android.net.VpnService
import android.util.Log
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import ir.v2max.app.data.AppSettings

/**
 * پیاده‌سازی پل اندروید ↔ sing-box.
 *
 *  • [openTun] رابط TUN را با همان تنظیمات V2max (MTU، DNS، split-tunneling) می‌سازد.
 *  • [autoDetectInterfaceControl] سوکت‌های خروجی هسته را از حلقه‌ی VPN خارج می‌کند
 *    (بدون این کار، ترافیک هسته داخل خود تانل می‌افتد و اتصال قطع می‌شود).
 *  • بقیه‌ی قابلیت‌های پلتفرمی (SSH/Taildrop/Bridge) در V2max استفاده نمی‌شوند.
 */
class SingBoxPlatform(
    private val vpnService: VpnService?,
    private val settings: AppSettings,
    private val buildTun: () -> Int,
    private val onLog: (String) -> Unit = {}
) : PlatformInterface {

    @Volatile
    var tunFd: Int = -1
        private set

    override fun openTun(options: TunOptions): Int {
        if (vpnService == null) {
            onLog("openTun بدون VpnService (حالت تست) نادیده گرفته شد")
            return -1
        }
        val fd = buildTun()
        tunFd = fd
        onLog(if (fd > 0) "TUN ساخته شد (fd=$fd)" else "ساخت TUN ناموفق بود")
        return fd
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        // حیاتی: خروج سوکت‌های هسته از تانل تا حلقه ایجاد نشود
        val service = vpnService ?: return
        runCatching { service.protect(fd) }
            .onFailure { Log.w("V2maxSingBox", "protect failed: ${it.message}") }
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun includeAllNetworks(): Boolean = false

    override fun underNetworkExtension(): Boolean = false

    override fun usePlatformShell(): Boolean = false

    override fun usePlatformBridge(): Boolean = false

    override fun checkPlatformShell() = Unit

    override fun registerMyInterface(name: String) = Unit

    override fun clearDNSCache() = Unit

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit

    override fun startNeighborMonitor(listener: NeighborUpdateListener) = Unit

    override fun closeNeighborMonitor(listener: NeighborUpdateListener) = Unit

    override fun getInterfaces(): NetworkInterfaceIterator? = null

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner? = null

    override fun readWIFIState(): WIFIState? = null

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun sendNotification(notification: Notification) = Unit

    override fun cancelNotification(identifier: String, typeID: Int) = Unit

    override fun lookupUser(username: String): PlatformUser? = null

    override fun openShellSession(
        user: PlatformUser,
        command: String,
        environ: StringIterator,
        term: String,
        rows: Int,
        cols: Int
    ): ShellSession? = null

    override fun lookupSFTPServer(): String? = null

    override fun readSystemSSHHostKey(): String? = null

    override fun tailscaleHostname(): String = ""

    override fun createBridge(options: BridgeOptions): BridgeSession? = null
}
