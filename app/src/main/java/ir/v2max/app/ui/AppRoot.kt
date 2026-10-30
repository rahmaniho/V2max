package ir.v2max.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.sp
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
    val sync by vm.sync.collectAsStateWithLifecycle()
    val network by vm.network.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var showPerApp by rememberSaveable { mutableStateOf(false) }
    var showQr by rememberSaveable { mutableStateOf(false) }
    var qrDialogContent by remember { mutableStateOf("") }
    var showRouting by rememberSaveable { mutableStateOf(false) }
    var showDiag by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf("") }
    var backupPassword by remember { mutableStateOf("") }
    var showBackupDialog by remember { mutableStateOf(false) }
    var pendingRestoreText by remember { mutableStateOf("") }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var restoreError by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            runCatching {
                val payload = if (backupPassword.isBlank()) {
                    vm.exportBackup()
                } else {
                    vm.exportEncryptedBackup(backupPassword) ?: vm.exportBackup()
                }
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(payload.toByteArray())
                }
            }
            vm.showMessage(
                if (backupPassword.isBlank()) "فایل پشتیبان ساده ذخیره شد"
                else "فایل پشتیبان رمزدار ذخیره شد"
            )
            backupPassword = ""
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            when {
                text.isNullOrBlank() -> vm.showMessage("خواندن فایل ناموفق بود")
                ir.v2max.app.util.SecureBackup.isEncrypted(text) -> {
                    pendingRestoreText = text
                    restoreError = ""
                    showRestoreDialog = true
                }
                else -> vm.importBackup(text)
            }
        }
    }

    val backBlocked = showPerApp || showQr
    BackHandler(enabled = backBlocked) {
        if (showQr) showQr = false else showPerApp = false
    }

    LaunchedEffect(message) {
        if (message.isNotBlank()) {
            snackbarHostState.showSnackbar(message)
            vm.clearMessage()
        }
    }

    if (qrDialogContent.isNotBlank()) {
        QrDialog(
            title = "کد QR اشتراک",
            content = qrDialogContent,
            onDismiss = { qrDialogContent = "" },
            onCopy = {
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("V2max", qrDialogContent))
                qrDialogContent = ""
                vm.showMessage("لینک کپی شد")
            }
        )
    }

    if (showBackupDialog) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            containerColor = V2.SurfaceHigh,
            title = { Text("پشتیبان‌گیری", color = V2.TextPrimary) },
            text = {
                Column {
                    Text(
                        "اگر رمز وارد کنید، فایل با AES-256 رمزگذاری می‌شود و بدون رمز قابل بازیابی نیست. " +
                            "خالی گذاشتن رمز = فایل ساده (فقط برای انتقال امن نگه‌داری کنید).",
                        fontSize = 11.sp,
                        color = V2.TextMuted
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        label = { Text("رمز (اختیاری)", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = V2.Mint,
                            unfocusedBorderColor = V2.Stroke,
                            cursorColor = V2.Mint
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    backupPassword = password
                    showBackupDialog = false
                    exportLauncher.launch("v2max-backup.json")
                }) { Text("ذخیره", color = V2.Mint) }
            },
            dismissButton = {
                TextButton(onClick = { showBackupDialog = false }) { Text("انصراف", color = V2.TextMuted) }
            }
        )
    }

    if (showRestoreDialog) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false; pendingRestoreText = "" },
            containerColor = V2.SurfaceHigh,
            title = { Text("بازیابی پشتیبان رمزدار", color = V2.TextPrimary) },
            text = {
                Column {
                    Text("رمز فایل پشتیبان را وارد کنید.", fontSize = 11.sp, color = V2.TextMuted)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        label = { Text("رمز", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = V2.Mint,
                            unfocusedBorderColor = V2.Stroke,
                            cursorColor = V2.Mint
                        )
                    )
                    if (restoreError.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(restoreError, fontSize = 11.sp, color = V2.Red)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (vm.importEncryptedBackup(pendingRestoreText, password)) {
                        showRestoreDialog = false
                        pendingRestoreText = ""
                        vm.showMessage("پشتیبان بازیابی شد")
                    } else {
                        restoreError = "رمز اشتباه است یا فایل خراب است"
                    }
                }) { Text("بازیابی", color = V2.Mint) }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false; pendingRestoreText = "" }) {
                    Text("انصراف", color = V2.TextMuted)
                }
            }
        )
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
                if (!showPerApp && !showQr && !showRouting && !showDiag && editingId.isBlank()) {
                    Box(Modifier.navigationBarsPadding()) {
                        BottomBar(tab) { tab = it }
                    }
                }
            }
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .statusBarsPadding()
            ) {
                if (editingId.isNotBlank()) {
                    val server = servers.firstOrNull { it.id == editingId }
                    if (server == null) {
                        editingId = ""
                    } else {
                        ConfigEditorScreen(
                            server = server,
                            onBack = { editingId = "" },
                            onSave = { vm.saveEditedServer(it); editingId = "" },
                            onClone = { copy ->
                                vm.cloneServer(copy)
                                editingId = ""
                            }
                        )
                    }
                } else if (showDiag) {
                    DiagScreen(
                        appVersion = BuildConfig.VERSION_NAME,
                        coreVersion = remember { vm.coreVersion() },
                        onBack = { showDiag = false }
                    )
                } else if (showRouting) {
                    RoutingScreen(
                        settings = settings,
                        onBack = { showRouting = false },
                        onChange = { block -> vm.updateSettings(block) }
                    )
                } else if (showQr) {
                    QrScanScreen(
                        onBack = { showQr = false },
                        onScanned = { text ->
                            showQr = false
                            vm.onQrScanned(text)
                            tab = Tab.SUBS
                        }
                    )
                } else if (showPerApp) {
                    PerAppScreen(
                        settings = settings,
                        onBack = { showPerApp = false },
                        onChange = { block -> vm.updateSettings(block) }
                    )
                } else when (tab) {
                    Tab.HOME -> HomeScreen(
                        servers = servers,
                        selectedId = selectedId,
                        tunnel = tunnel,
                        scan = scan,
                        sync = sync,
                        network = network,
                        update = update,
                        settings = settings,
                        onToggle = { vm.toggleConnection() },
                        onSmart = { vm.requestSmartConnect(null) },
                        onOpenSubs = { tab = Tab.SUBS },
                        onOpenServers = { tab = Tab.SERVERS },
                        onRestart = { vm.restartScan() },
                        onRefreshNetwork = { vm.refreshNetworkAssessment() },
                        onOpenUpdate = { vm.openUpdatePage() },
                        onDismissUpdate = { vm.dismissUpdate() },
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
                        showFlags = settings.showFlags,
                        onQrContent = { id -> vm.shareLink(id).orEmpty() },
                        onEdit = { id -> editingId = id },
                        onBulkDelete = { ids -> vm.deleteServers(ids) },
                        onBulkFavorite = { ids, fav -> vm.setFavorite(ids, fav) },
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
                        sync = sync,
                        busy = busy,
                        onAdd = { name, url -> vm.addSubscription(name, url) },
                        onRefresh = { vm.refreshSubscription(it) },
                        onRefreshAll = { vm.refreshAllSubscriptions() },
                        onDelete = { vm.removeSubscription(it) },
                        onSmartScan = { vm.requestSmartConnect(it) },
                        onImportText = { vm.importText(it) },
                        onAddFreeSources = { vm.addFreeSources() },
                        onScanQr = { showQr = true },
                        onQrContent = { qrDialogContent = it }
                    )

                    Tab.SETTINGS -> SettingsScreen(
                        settings = settings,
                        coreVersion = remember { vm.coreVersion() },
                        appVersion = BuildConfig.VERSION_NAME,
                        onChange = { block -> vm.updateSettings(block) },
                        onClearAll = { vm.resetData() },
                        onOpenPerApp = { showPerApp = true },
                        onSetFreeSources = { vm.setFreeSourcesEnabled(it) },
                        onBackup = {
                            backupPassword = ""
                            showBackupDialog = true
                        },
                        onRestore = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        onCheckUpdate = { vm.checkUpdates(manual = true) },
                        onScanQr = { showQr = true },
                        onOpenRouting = { showRouting = true },
                        onOpenDiag = { showDiag = true }
                    )
                }
            }
        }
    }
}
