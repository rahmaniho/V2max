package ir.v2max.app

import ir.v2max.app.service.ConnectionCue
import ir.v2max.app.service.ConnectionCuePlayer
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class ConnectionCueTest {
    private class Dispatcher {
        val ready = ArrayDeque<() -> Unit>()
        val delayed = ArrayDeque<() -> Unit>()
        var failImmediate = false
        var failDelayed = false
        var throwOnDispatch = false

        fun post(delay: Long, task: () -> Unit): Boolean {
            if (throwOnDispatch) error("dispatcher failed")
            if (delay == 0L && failImmediate || delay != 0L && failDelayed) return false
            if (delay == 0L) ready.add(task) else delayed.add(task)
            return true
        }

        fun runNext() = ready.removeFirst().invoke()
        fun drain() { while (ready.isNotEmpty()) runNext() }
        fun timeOut() { while (delayed.isNotEmpty()) delayed.removeFirst().invoke() }
    }

    private class Player : ConnectionCuePlayer {
        var prepared: (() -> Unit)? = null
        var completed: (() -> Unit)? = null
        var error: (() -> Unit)? = null
        var starts = 0
        var releases = 0
        var failPrepare = false
        var failStart = false
        var failRelease = false

        override fun prepare(onPrepared: () -> Unit, onCompletion: () -> Unit, onError: () -> Unit) {
            prepared = onPrepared
            completed = onCompletion
            error = onError
            if (failPrepare) kotlin.error("prepare failed")
        }

        override fun start() {
            if (failStart) kotlin.error("start failed")
            starts++
        }

        override fun release() {
            releases++
            if (failRelease) kotlin.error("release failed")
        }
    }

    @Test fun `verified connection plays once and releases on completion`() {
        val dispatcher = Dispatcher()
        val player = Player()
        val cue = ConnectionCue(dispatcher::post, { player })
        cue.announce { true }
        assertEquals(0, player.starts) // the caller never waits for preparation or playback
        dispatcher.drain()
        assertEquals(0, player.starts)
        player.prepared!!.invoke()
        player.prepared!!.invoke() // even an unexpected duplicate callback is harmless
        assertEquals(1, player.starts)
        player.completed!!.invoke()
        assertEquals(1, player.releases)
        dispatcher.timeOut()
        assertEquals(1, player.releases)
    }

    @Test fun `disabled or no longer connected never creates a player`() {
        val dispatcher = Dispatcher()
        var valid = true
        var created = 0
        val cue = ConnectionCue(dispatcher::post, { created++; Player() })
        cue.announce { valid }
        valid = false
        dispatcher.drain()
        assertEquals(0, created)
        cue.announce { false }
        dispatcher.drain()
        assertEquals(0, created)
        cue.announce { error("eligibility unavailable") }
        dispatcher.drain()
        assertEquals(0, created)
    }

    @Test fun `stop before queued request or before preparation prevents late sound`() {
        val dispatcher = Dispatcher()
        val players = mutableListOf<Player>()
        val cue = ConnectionCue(dispatcher::post, { Player().also { players.add(it) } })
        cue.announce { true }
        cue.cancel()
        dispatcher.drain()
        assertTrue(players.isEmpty())

        cue.announce { true }
        dispatcher.drain()
        val pending = players.single()
        cue.cancel() // invalidate immediately, before the queued cleanup runs
        pending.prepared!!.invoke()
        assertEquals(0, pending.starts)
        assertEquals(1, pending.releases)
        dispatcher.drain()
        pending.prepared!!.invoke()
        assertEquals(0, pending.starts)
        assertEquals(1, pending.releases)
    }

    @Test fun `setting or connection becoming invalid during preparation releases silently`() {
        val dispatcher = Dispatcher()
        val player = Player()
        val cue = ConnectionCue(dispatcher::post, { player })
        var enabledAndConnected = true
        cue.announce { enabledAndConnected }
        dispatcher.drain()
        enabledAndConnected = false
        player.prepared!!.invoke()
        enabledAndConnected = true
        player.prepared!!.invoke()
        assertEquals(0, player.starts)
        assertEquals(1, player.releases)
    }

    @Test fun `new connection replaces the old player and stale callbacks cannot release it`() {
        val dispatcher = Dispatcher()
        val players = mutableListOf<Player>()
        val cue = ConnectionCue(dispatcher::post, { Player().also { players.add(it) } })
        cue.announce { true }
        dispatcher.drain()
        val first = players.single()
        first.prepared!!.invoke()
        assertEquals(1, first.starts)

        cue.cancel()
        cue.announce { true }
        // Even if a dispatcher delivered the newer request before the old cancel,
        // the old cancel and the old timeout must not touch the new player.
        dispatcher.ready.removeLast().invoke()
        val second = players.last()
        assertEquals(1, first.releases)
        dispatcher.drain()
        first.completed!!.invoke()
        first.error!!.invoke()
        second.prepared!!.invoke()
        assertEquals(1, second.starts)
        assertEquals(0, second.releases)
        second.completed!!.invoke()
        dispatcher.timeOut()
        assertEquals(1, second.releases)
    }

    @Test fun `cancel stops an already playing sound without repeating it`() {
        val dispatcher = Dispatcher()
        val player = Player()
        val cue = ConnectionCue(dispatcher::post, { player })
        cue.announce { true }
        dispatcher.drain()
        player.prepared!!.invoke()
        cue.cancel()
        dispatcher.drain()
        player.completed!!.invoke()
        assertEquals(1, player.starts)
        assertEquals(1, player.releases)
    }

    @Test fun `stuck preparation or playback is released by the timeout`() {
        val dispatcher = Dispatcher()
        val player = Player()
        val cue = ConnectionCue(dispatcher::post, { player })
        cue.announce { true }
        dispatcher.drain()
        dispatcher.timeOut()
        player.prepared!!.invoke()
        assertEquals(0, player.starts)
        assertEquals(1, player.releases)
    }

    @Test fun `player failures never escape and a later connection can still play`() {
        val dispatcher = Dispatcher()
        var failureCount = 0
        var factoryFailure = true
        var next = Player().apply { failPrepare = true; failRelease = true }
        val cue = ConnectionCue(dispatcher::post, {
            if (factoryFailure) error("factory failed")
            next
        }, { failureCount++ })
        cue.announce { true }
        dispatcher.drain()
        assertEquals(1, failureCount)
        factoryFailure = false
        cue.announce { true }
        dispatcher.drain()
        assertEquals(1, next.releases)
        assertEquals(3, failureCount) // preparation and release both failed

        next = Player().apply { failStart = true }
        cue.announce { true }
        dispatcher.drain()
        next.prepared!!.invoke()
        assertEquals(1, next.releases)
        assertEquals(4, failureCount)

        next = Player()
        cue.announce { true }
        dispatcher.drain()
        next.error!!.invoke()
        assertEquals(1, next.releases)
        assertEquals(5, failureCount)

        next = Player()
        cue.announce { true }
        dispatcher.drain()
        next.prepared!!.invoke()
        assertEquals(1, next.starts)
        next.completed!!.invoke()
        assertEquals(1, next.releases)
    }

    @Test fun `dispatcher failures do not escape or retain a prepared player`() {
        val dispatcher = Dispatcher()
        var failures = 0
        val player = Player()
        val cue = ConnectionCue(dispatcher::post, { player }, { failures++; error("logger failed") })
        dispatcher.throwOnDispatch = true
        cue.announce { true }
        cue.cancel()
        assertEquals(2, failures)
        dispatcher.throwOnDispatch = false
        dispatcher.failImmediate = true
        cue.announce { true }
        assertEquals(3, failures)
        dispatcher.failImmediate = false
        dispatcher.failDelayed = true
        cue.announce { true }
        dispatcher.drain()
        assertEquals(4, failures)
        assertEquals(1, player.releases)
        assertNull(player.prepared)
    }
}
