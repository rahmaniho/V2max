package ir.v2max.app

import android.app.Application
import ir.v2max.app.data.Repo
import ir.v2max.app.service.SubSyncWorker

class V2maxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Repo.init(this)
        if (Repo.settings.value.autoRefreshOnOpen) SubSyncWorker.schedule(this)
    }

    companion object {
        lateinit var instance: V2maxApp
            private set
    }
}
