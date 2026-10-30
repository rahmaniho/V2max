package ir.v2max.app

import android.app.Application
import ir.v2max.app.data.Repo
import ir.v2max.app.core.GeoAssets
import ir.v2max.app.service.SubSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class V2maxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Repo.init(this)
        if (Repo.settings.value.autoRefreshOnOpen) SubSyncWorker.schedule(this)

        // دانلود/به‌روزرسانی فایل‌های geoip/geosite در پس‌زمینه (بدون بلاک کردن شروع برنامه)
        if (Repo.settings.value.geoAssets) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { GeoAssets.syncIfNeeded(this@V2maxApp) }
            }
        }
    }

    companion object {
        lateinit var instance: V2maxApp
            private set
    }
}
