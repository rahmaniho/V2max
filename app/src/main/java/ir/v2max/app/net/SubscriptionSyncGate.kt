package ir.v2max.app.net

import kotlinx.coroutines.sync.Mutex

/** Serialize startup refresh plus its health snapshot against the periodic worker. */
internal object SubscriptionSyncGate {
    val mutex = Mutex()
}
