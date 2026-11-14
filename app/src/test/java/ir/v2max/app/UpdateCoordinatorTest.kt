package ir.v2max.app

import ir.v2max.app.data.Repo
import ir.v2max.app.data.UpdateInfo
import ir.v2max.app.net.UpdateChecker
import ir.v2max.app.net.UpdateCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UpdateCoordinatorTest {
    private var oldSettings = Repo.settings.value.copy()
    private var oldUpdate = Repo.update.value

    @Before fun setUp() {
        oldSettings = Repo.settings.value.copy()
        oldUpdate = Repo.update.value
        Repo.updateSettings { it.copy(autoUpdateCheck = true) }
        Repo.saveUpdateInfo(UpdateInfo())
    }

    @After fun tearDown() {
        Repo.saveSettings(oldSettings)
        Repo.saveUpdateInfo(oldUpdate)
    }

    @Test fun `disabling automatic checks rejects an in flight result and notification`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var notifications = 0
        val task = async {
            UpdateCoordinator.check(automatic = true, fetch = {
                started.complete(Unit)
                release.await()
                UpdateChecker.Result.Available(UpdateInfo(version = "9.0.0"))
            }, onApplied = { notifications++ })
        }
        withTimeout(5_000) { started.await() }
        Repo.updateSettings { it.copy(autoUpdateCheck = false) }
        // Re-enabling must not resurrect a request started under the old setting.
        Repo.updateSettings { it.copy(autoUpdateCheck = true) }
        release.complete(Unit)
        assertNull(withTimeout(5_000) { task.await() })
        assertEquals("", Repo.update.value.version)
        assertEquals(0, notifications)
    }

    @Test fun `periodic and manual checks publish in the same order they are fetched`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val first = async {
            UpdateCoordinator.check(automatic = true, fetch = {
                firstStarted.complete(Unit)
                releaseFirst.await()
                UpdateChecker.Result.Available(UpdateInfo(version = "3.0.0"))
            })
        }
        withTimeout(5_000) { firstStarted.await() }
        val second = async {
            UpdateCoordinator.check(automatic = false, fetch = {
                secondStarted.complete(Unit)
                UpdateChecker.Result.Current
            })
        }
        // Give the queued coroutine an opportunity to reach the mutex.
        kotlinx.coroutines.yield()
        assertFalse(secondStarted.isCompleted)
        releaseFirst.complete(Unit)
        assertTrue(withTimeout(5_000) { first.await() } is UpdateChecker.Result.Available)
        assertEquals(UpdateChecker.Result.Current, withTimeout(5_000) { second.await() })
        assertTrue(secondStarted.isCompleted)
        assertEquals("", Repo.update.value.version)
    }

    @Test fun `network failure leaves the last known update intact`() = runBlocking {
        Repo.saveUpdateInfo(UpdateInfo(version = "3.0.0"))
        val result = UpdateCoordinator.check(automatic = false, fetch = { UpdateChecker.Result.Unavailable })
        assertEquals(UpdateChecker.Result.Unavailable, result)
        assertEquals("3.0.0", Repo.update.value.version)
    }
}
