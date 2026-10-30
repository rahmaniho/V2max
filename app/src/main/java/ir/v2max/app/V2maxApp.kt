package ir.v2max.app

import android.app.Application
import ir.v2max.app.data.Repo
import ir.v2max.app.util.CrashLog
import ir.v2max.app.core.GeoAssets
import ir.v2max.app.service.SubSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class V2maxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this

        CrashLog.init(this)
        installCrashHandler()

        // هر خطای پیش‌بینی‌نشده در راه‌اندازی نباید برنامه را ببندد
        runCatching { Repo.init(this) }
            .onFailure { CrashLog.record(it, "Repo.init") }

        val settings = runCatching { Repo.settings.value }.getOrNull()
        if (settings != null && settings.autoRefreshOnOpen) {
            runCatching { SubSyncWorker.schedule(this) }
                .onFailure { CrashLog.record(it, "SubSyncWorker.schedule") }
        }

        // دانلود/به‌روزرسانی فایل‌های geoip/geosite در پس‌زمینه (بدون بلاک کردن شروع برنامه)
        if (settings != null && settings.geoAssets) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { GeoAssets.syncIfNeeded(this@V2maxApp) }
                    .onFailure { CrashLog.record(it, "GeoAssets") }
            }
        }
    }

    /**
     * ثبت خطاهای کشنده‌ی مدیریت‌نشده؛ برنامه همچنان بسته می‌شود (رفتار سیستم)
     * اما دلیل آن در فایل ثبت می‌ماند و دفعه‌ی بعد به کاربر نشان داده می‌شود.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { CrashLog.record(throwable, thread.name) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        lateinit var instance: V2maxApp
            private set
    }
}
