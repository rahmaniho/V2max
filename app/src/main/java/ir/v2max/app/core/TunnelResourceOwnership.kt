package ir.v2max.app.core

/**
 * Ownership of the process-global native VPN core and its TUN descriptor.
 * All calls must be made while holding the service's lifecycle lock. An old
 * coroutine may outlive its Service instance while a native probe finishes;
 * it must never tear down resources claimed by a newer instance.
 */
internal class TunnelResourceOwnership {
    private var generation: Int? = null

    fun claim(owner: Int) { generation = owner }

    fun isOwner(owner: Int): Boolean = generation == owner

    fun release(owner: Int): Boolean {
        if (generation != owner) return false
        generation = null
        return true
    }

    fun clear() { generation = null }
}
