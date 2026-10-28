package ir.v2max.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.v2max.app.BuildConfig

@Composable
fun AppRoot(vm: AppViewModel) {
    val servers by vm.servers.collectAsStateWithLifecycle()
    val subs by vm.subs.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val tunnel by vm.tunnel.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        if (message.isNotBlank()) {
            snackbarHostState.showSnackbar(message)
            vm.clearMessage()
        }
    }

    AppBackground {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = {
                SnackbarHost(snackbarHostState) { data ->
                    Snackbar(
                        containerColor = V2.SurfaceHigh,
                        contentColor = V2.TextPrimary,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                    ) {
                        Text(data.visuals.message, color = V2.TextPrimary)
                    }
                }
            },
            bottomBar = {
                Box(Modifier.navigationBarsPadding()) {
                    BottomBar(tab) { tab = it }
                }
            }
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .statusBarsPadding()
            ) {
                when (tab) {
                    Tab.HOME -> HomeScreen(
                        servers = servers,
                        selectedId = selectedId,
                        tunnel = tunnel,
                        scan = scan,
                        settings = settings,
                        onToggle = { vm.toggleConnection() },
                        onSmart = { vm.requestSmartConnect(null) },
                        onOpenSubs = { tab = Tab.SUBS },
                        onOpenServers = { tab = Tab.SERVERS },
                        coreVersion = remember { vm.coreVersion() }
                    )

                    Tab.SERVERS -> ServersScreen(
                        servers = servers,
                        selectedId = selectedId,
                        tunnel = tunnel,
                        scan = scan,
                        onSelect = { vm.selectServer(it) },
                        onConnect = { vm.requestConnect(it) },
                        onTest = { vm.testServer(it) },
                        onTestAll = { vm.testAll() },
                        onToggleFavorite = { vm.toggleFavorite(it) },
                        onDelete = { vm.deleteServer(it) },
                        onCopyLink = { id ->
                            val link = vm.shareLink(id)
                            if (link != null) {
                                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(
                                    android.content.ClipData.newPlainText("V2max", link)
                                )
                                vm.showMessage("لینک کپی شد")
                            }
                        }
                    )

                    Tab.SUBS -> SubsScreen(
                        servers = servers,
                        subs = subs,
                        scan = scan,
                        busy = busy,
                        onAdd = { name, url -> vm.addSubscription(name, url) },
                        onRefresh = { vm.refreshSubscription(it) },
                        onRefreshAll = { vm.refreshAllSubscriptions() },
                        onDelete = { vm.removeSubscription(it) },
                        onSmartScan = { vm.requestSmartConnect(it) },
                        onImportText = { vm.importText(it) }
                    )

                    Tab.SETTINGS -> SettingsScreen(
                        settings = settings,
                        coreVersion = remember { vm.coreVersion() },
                        appVersion = BuildConfig.VERSION_NAME,
                        onChange = { block -> vm.updateSettings(block) },
                        onClearAll = { vm.resetData() }
                    )
                }
            }
        }
    }
}
