package ir.v2max.app.service

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pending PackageInstaller confirmation for the running app process. A separate
 * system notification also holds the action, so it can survive process death
 * when notifications are allowed. No confirmation is launched from a background
 * broadcast; the in-app action is started only after an explicit user tap.
 */
internal object InstallConfirmation {
    data class Request(val sessionId: Int, val intent: Intent)

    private val state = MutableStateFlow<Request?>(null)
    val pending = state.asStateFlow()

    @Synchronized
    fun offer(sessionId: Int, confirmation: Intent) {
        state.value = Request(sessionId, Intent(confirmation))
    }

    /** Clear the matching request, and say whether its result may be shown. */
    @Synchronized
    fun clear(sessionId: Int): Boolean {
        val active = state.value ?: return true
        if (active.sessionId != sessionId) return false
        state.value = null
        return true
    }

    /** False also covers stale requests and Android rejecting the activity. */
    @Synchronized
    fun open(context: Context, request: Request): Boolean {
        if (state.value !== request) return false
        return runCatching {
            context.startActivity(Intent(request.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }
}
