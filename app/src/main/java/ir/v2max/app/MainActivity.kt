package ir.v2max.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.v2max.app.data.Repo
import ir.v2max.app.ui.AppRoot
import ir.v2max.app.ui.AppViewModel
import ir.v2max.app.ui.V2maxTheme

class MainActivity : FragmentActivity() {

    private val viewModel: AppViewModel by viewModels()

    /** وضعیت قفل برنامه (اگر در تنظیمات فعال شده باشد) */
    private val unlocked = androidx.compose.runtime.mutableStateOf(false)
    private var lockPromptShown = false

    private lateinit var vpnPermissionLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        Repo.init(this)

        vpnPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            viewModel.onVpnPermissionResult(result.resultCode == RESULT_OK)
        }

        notificationLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* اختیاری */ }

        setContent {
            val pending by viewModel.pendingVpnRequest.collectAsStateWithLifecycle()
            LaunchedEffect(pending) {
                if (pending) {
                    val intent = VpnService.prepare(this@MainActivity)
                    if (intent == null) {
                        viewModel.onVpnPermissionResult(true)
                    } else {
                        runCatching { vpnPermissionLauncher.launch(intent) }
                            .onFailure { viewModel.onVpnPermissionResult(false) }
                    }
                }
            }
            V2maxTheme {
                val locked = Repo.settings.collectAsStateWithLifecycle().value.appLock && !unlocked.value
                if (locked) {
                    LockScreen(
                        onUnlock = { requestUnlock() }
                    )
                    LaunchedEffect(Unit) {
                        if (!lockPromptShown) {
                            lockPromptShown = true
                            requestUnlock()
                        }
                    }
                } else {
                    AppRoot(viewModel)
                }
            }
        }

        askNotificationPermission()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * API اتوماسیون (Tasker / Automate / adb):
     *   am start -a ir.v2max.app.action.CONNECT   [-e id <serverId>]
     *   am start -a ir.v2max.app.action.DISCONNECT
     *   am start -a ir.v2max.app.action.TOGGLE
     *   am start -a ir.v2max.app.action.IMPORT  -e text "vless://…"
     *   am start -a ir.v2max.app.action.SMART   (تست کامل و اتصال خودکار)
     *   am broadcast -a ir.v2max.app.action.CONNECT --es id <serverId>
     */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            ACTION_CONNECT, ACTION_SMART -> {
                val id = intent.getStringExtra(EXTRA_ID)
                if (intent.action == ACTION_SMART || id.isNullOrBlank()) {
                    viewModel.requestSmartConnect(null)
                } else {
                    viewModel.requestConnect(id)
                }
                return
            }
            ACTION_DISCONNECT -> {
                viewModel.disconnectTunnel()
                return
            }
            ACTION_TOGGLE -> {
                viewModel.toggleConnection()
                return
            }
            ACTION_IMPORT -> {
                val text = intent.getStringExtra(EXTRA_TEXT)
                if (!text.isNullOrBlank()) viewModel.importText(text)
                return
            }
        }

        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (!text.isNullOrBlank()) {
            viewModel.importText(text)
        }
    }

    companion object {
        const val ACTION_CONNECT = "ir.v2max.app.action.CONNECT"
        const val ACTION_DISCONNECT = "ir.v2max.app.action.DISCONNECT"
        const val ACTION_TOGGLE = "ir.v2max.app.action.TOGGLE"
        const val ACTION_IMPORT = "ir.v2max.app.action.IMPORT"
        const val ACTION_SMART = "ir.v2max.app.action.SMART"
        const val EXTRA_ID = "id"
        const val EXTRA_TEXT = "text"
    }

    private fun requestUnlock() {
        ir.v2max.app.ui.AppLock.prompt(
            this,
            onSuccess = { unlocked.value = true },
            onFailure = { /* کاربر می‌تواند دوباره تلاش کند */ }
        )
    }

    override fun onStop() {
        super.onStop()
        // بازگشت به برنامه نیازمند احراز هویت مجدد است
        if (Repo.settings.value.appLock) {
            unlocked.value = false
            lockPromptShown = false
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }
}
