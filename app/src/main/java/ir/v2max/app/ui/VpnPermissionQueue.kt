package ir.v2max.app.ui

/** Observable IDs only; connection actions and credentials never go into an Intent. */
internal data class VpnPermissionState(
    val pendingId: Long? = null,
    val inFlightId: Long? = null
)

/**
 * A system permission dialog can outlive an Activity or a user's earlier action.
 * Keep its one in-flight result separate from the latest pending action so an
 * old result can neither start a canceled connection nor consume a newer one.
 * The ViewModel owns this queue across Activity recreation, not process death.
 */
internal class VpnPermissionQueue<A> {
    private data class Request<A>(val id: Long, val action: A)

    private var nextId = 0L
    private var pending: Request<A>? = null
    private var inFlight: Long? = null

    @Synchronized fun snapshot() = VpnPermissionState(pending?.id, inFlight)

    @Synchronized fun enqueue(action: A) {
        pending = Request(++nextId, action)
    }

    @Synchronized fun cancel() {
        pending = null
        // The system dialog may still return. Its ID must stay in flight until
        // that result arrives, otherwise it could be mistaken for a new request.
    }

    @Synchronized fun claim(): Boolean {
        val request = pending ?: return false
        if (inFlight != null) return false
        inFlight = request.id
        return true
    }

    @Synchronized fun complete(): A? {
        val completed = inFlight ?: return null
        inFlight = null
        val request = pending
        if (request == null || request.id != completed) return null
        pending = null
        return request.action
    }
}
