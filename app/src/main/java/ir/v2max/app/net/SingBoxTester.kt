package ir.v2max.app.net

import android.content.Context
import ir.v2max.app.core.SingBoxConfig
import ir.v2max.app.core.SingBoxCore
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * تست پروتکل‌های sing-box (Hysteria2 / TUIC / AnyTLS / WireGuard) پیش از اتصال.
 *
 * هسته با یک inbound SOCKS محلی اجرا می‌شود، تأخیر واقعی از مسیر همان سرور سنجیده می‌شود
 * و بعد هسته بسته می‌شود. چون فقط یک نمونه از هسته می‌تواند اجرا شود، تست‌ها سریالی‌اند.
 */
object SingBoxTester {

    private const val PORT = 10889

    private val mutex = Mutex()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        SingBoxCore.setup(context.applicationContext)
    }

    /** تأخیر واقعی (میلی‌ثانیه) یا -1 در صورت شکست */
    suspend fun delay(cfg: ServerConfig, settings: AppSettings, url: String): Int =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val context = appContext ?: return@withLock -1
                if (SingBoxCore.isRunning) return@withLock -1
                val config = runCatching {
                    SingBoxConfig.socksTestConfig(cfg, settings, PORT)
                }.getOrNull() ?: return@withLock -1

                return@withLock try {
                    val started = SingBoxCore.start(context, config, null, settings)
                    if (!started) {
                        -1
                    } else {
                        val delay = SocksProbe.delay(PORT, url)
                        if (delay in 1..60_000) delay.toInt() else -1
                    }
                } catch (t: Throwable) {
                    -1
                } finally {
                    SingBoxCore.stop()
                }
            }
        }

    fun version(): String = SingBoxCore.version()
}
