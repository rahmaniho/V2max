package ir.v2max.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.v2max.app.data.Repo
import ir.v2max.app.ui.AppRoot
import ir.v2max.app.ui.AppViewModel
import ir.v2max.app.ui.V2maxTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

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
                AppRoot(viewModel)
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

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (!text.isNullOrBlank()) {
            viewModel.importText(text)
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
