package ir.v2max.app

import android.app.Application
import ir.v2max.app.data.Repo

class V2maxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Repo.init(this)
    }

    companion object {
        lateinit var instance: V2maxApp
            private set
    }
}
