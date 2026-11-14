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

    @Synchronized
    fun clear(sessionId: Int) {
        if (state.value?.sessionId == sessionId) state.value = null
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
