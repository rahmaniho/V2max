package ir.v2max.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ir.v2max.app.service.Notifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidUpdatePermissionTest {
    @Test fun updateNotificationRespectsDeniedPermission() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        // The dedicated API 35 smoke run revokes permission before launching us.
        // A normal test run with notifications granted does not alter user state.
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("expectNotificationDenied") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(PackageManager.PERMISSION_DENIED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS))
        assertFalse(Notifier.updateAvailable(context, "99.0.0"))
    }
}
