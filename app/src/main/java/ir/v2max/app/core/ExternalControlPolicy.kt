package ir.v2max.app.core

import ir.v2max.app.data.AppSettings

/** Other apps have no shared identity with V2max. Fail closed unless the user
 * explicitly enables automation; the app lock also disables external commands. */
internal object ExternalControlPolicy {
    fun allowed(settings: AppSettings): Boolean = settings.allowExternalAutomation && !settings.appLock
}
