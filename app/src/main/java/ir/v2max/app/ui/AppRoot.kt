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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.v2max.app.BuildConfig
import ir.v2max.app.data.Repo
import ir.v2max.app.service.InstallConfirmation

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
    val updateProgress by Repo.updateProgress.collectAsStateWithLifecycle()
    val installMessage by Repo.installMessage.collectAsStateWithLifecycle()
    val pendingInstall by InstallConfirmation.pending.collectAsStateWithLifecycle()
    val latencySamples by Repo.latencyHistory.collectAsStateWithLifecycle()
    val poolSize by Repo.poolSize.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val vpnPermission by vm.vpnPermissionState.collectAsStateWithLifecycle()
    val notificationPermissionInFlight by vm.notificationPermissionInFlight.collectAsStateWithLifecycle()
    val recoveryWarning by Repo.recoveryWarning.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var showPerApp by rememberSaveable { mutableStateOf(false) }
    var showQr by rememberSaveable { mutableStateOf(false) }
    var qrDialogContent by remember { mutableStateOf("") }
    var showRouting by rememberSaveable { mutableStateOf(false) }
    var showDiag by rememberSaveable { mutableStateOf(false) }
    var geoSyncPending by remember { mutableStateOf(false) }
    var crashReportPreview by remember { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf("") }
    var backupPassword by remember { mutableStateOf("") }
    var showBackupDialog by remember { mutableStateOf(false) }
    var pendingRestoreText by remember { mutableStateOf("") }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showPlainRestoreDialog by remember { mutableStateOf(false) }
    var restoreError by remember { mutableStateOf("") }
    var deferredInstall by remember { mutableStateOf<InstallConfirmation.Request?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Restore the editor lease after process/activity recreation as well.
    LaunchedEffect(editingId) {
        if (editingId.isNotBlank() && !vm.beginServerEdit(editingId)) editingId = ""
    }

    val canSuggestRemoval = !sync.running && !scan.running && !notificationPermissionInFlight &&
        vpnPermission.pendingId == null && vpnPermission.inFlightId == null &&
        !showPerApp && !showQr && !showRouting && !showDiag && editingId.isBlank() &&
        !showBackupDialog && !showRestoreDialog && !showPlainRestoreDialog && crashReportPreview == null &&
        pendingInstall == null && recoveryWarning.isBlank() && !Repo.isStorageReadOnly()
    val removalSuggestion = if (canSuggestRemoval) subs.firstOrNull { sub ->
        sub.lastError.isBlank() && ir.v2max.core.domain.SubscriptionHealthPolicy.shouldSuggest(
            sub.healthEvidence(), sub.updatedAt, System.currentTimeMillis()
        ) && servers.none { it.subId == sub.id && tunnel.isActive && it.id == tunnel.serverId }
    } else null
    removalSuggestion?.let { sub ->
        AlertDialog(
            onDismissRequest = { vm.deferUnhealthySubscription(sub) },
            containerColor = V2.SurfaceHigh,
            title = { Text("بررسی حذف اشتراک", color = V2.TextPrimary) },
            text = {
                Text(
                    "سرورهای دریافت‌شده از «${sub.name.ifBlank { "اشتراک" }}» در دو بررسی با فاصلهٔ دست‌کم یک روز پاسخ ندادند. " +
                        "مشکل ممکن است از شبکه یا مقصد تست باشد. اشتراک و کانفیگ‌هایش حذف شوند؟",
                    color = V2.TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.removeUnhealthySubscription(sub) }) { Text("حذف اشتراک", color = V2.Red) }
            },
            dismissButton = {
                TextButton(onClick = { vm.deferUnhealthySubscription(sub) }) { Text("بعداً", color = V2.TextMuted) }
            }
        )
    }

    if (recoveryWarning.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { Repo.recoveryWarning.value = "" },
            title = { Text("بررسی داده‌های ذخیره‌شده") },
            text = { Text(recoveryWarning) },
            confirmButton = {
                TextButton(onClick = { Repo.recoveryWarning.value = "" }) { Text("متوجه شدم") }
            }
        )
    }

    fun beginOrContinueInstall() {
        if (pendingInstall != null) deferredInstall = null else vm.downloadAndInstallUpdate()
    }

    fun handleDiagAction(action: String) {
        when (action) {
            ir.v2max.core.domain.ConnectionAdvice.Action.SMART_CONNECT -> vm.requestSmartConnect(null)
            ir.v2max.core.domain.ConnectionAdvice.Action.TEST_SERVERS -> vm.testAll()
            ir.v2max.core.domain.ConnectionAdvice.Action.SYNC_GEO -> {
                if (!geoSyncPending) {
                    geoSyncPending = true
                    scope.launch {
                        vm.showMessage("در حال بررسی فایل‌های مسیریابی…")
                        try {
                            // Reuse a successful refresh already in progress;
                            // retry if the files are still stale or unknown.
                            ir.v2max.app.core.GeoAssets.syncIfNeeded(context)
                            val message = when (ir.v2max.app.core.GeoAssets.diagnosticReadiness.value) {
                                true -> "برچسب‌های لازم فایل‌های مسیریابی پیدا شدند"
                                false -> "برچسب‌های لازم همهٔ فایل‌های مسیریابی پیدا نشدند"
                                null -> "وضعیت فایل‌های مسیریابی مشخص نشد؛ دوباره تلاش کنید"
                            }
                            vm.showMessage(message)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            vm.showMessage("بررسی فایل‌های مسیریابی کامل نشد؛ دوباره تلاش کنید")
                        } finally {
                            geoSyncPending = false
                        }
                    }
                }
            }
            ir.v2max.core.domain.ConnectionAdvice.Action.UNBLOCK -> vm.disconnectTunnel()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val password = backupPassword
        backupPassword = ""
        if (uri != null) scope.launch {
            val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val payload = if (password.isBlank()) vm.exportBackup()
                    // Never silently export credentials in plaintext when encryption fails.
                    else vm.exportEncryptedBackup(password) ?: error("encryption failed")
                    val stream = context.contentResolver.openOutputStream(uri) ?: error("cannot open backup")
                    stream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                }.isSuccess
            }
            vm.showMessage(when {
                !saved -> "ذخیرهٔ پشتیبان انجام نشد؛ دوباره تلاش کنید"
                password.isBlank() -> "فایل پشتیبان ساده ذخیره شد"
                else -> "فایل پشتیبان رمزدار ذخیره شد"
            })
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                        ir.v2max.app.util.BackupFiles.read(it)
                    }
                }.getOrNull()
            }
            when {
                text.isNullOrBlank() -> vm.showMessage("خواندن فایل ناموفق بود یا اندازهٔ آن بیش از حد مجاز است")
                ir.v2max.app.util.SecureBackup.isEncrypted(text) -> {
                    pendingRestoreText = text
                    restoreError = ""
                    showRestoreDialog = true
                }
                else -> {
                    pendingRestoreText = text
                    showPlainRestoreDialog = true
                }
            }
        }
    }

    val backBlocked = showPerApp || showQr
    BackHandler(enabled = backBlocked) {
        if (showQr) showQr = false else showPerApp = false
    }

    LaunchedEffect(installMessage) {
        if (installMessage.isNotBlank()) {
            snackbarHostState.showSnackbar(installMessage)
            Repo.installMessage.value = ""
        }
    }

    LaunchedEffect(message) {
        if (message.isNotBlank()) {
            snackbarHostState.showSnackbar(message)
            vm.clearMessage()
        }
    }

    pendingInstall?.let { request ->
        if (request !== deferredInstall) {
            var installError by remember(request) { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { deferredInstall = request },
                containerColor = V2.SurfaceHigh,
                title = { Text("تأیید نصب به‌روزرسانی", color = V2.TextPrimary) },
                text = {
                    Column {
                        Text("اندروید برای نصب به‌روزرسانی به تأیید شما نیاز دارد. در مرحلهٔ بعد جزئیات نصب را بررسی کنید.", color = V2.TextPrimary)
                        if (installError.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(installError, color = V2.Amber)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (InstallConfirmation.open(context, request)) deferredInstall = request
                        else installError = "باز کردن تأیید نصب ممکن نشد؛ دوباره تلاش کنید."
                    }) { Text("ادامه نصب") }
                },
                dismissButton = {
                    TextButton(onClick = { deferredInstall = request }) { Text("بعداً") }
                }
            )
        }
    }

    crashReportPreview?.let { report ->
        ReportPreviewDialog(
            report = report,
            confirmLabel = "کپی خلاصه",
            onDismiss = { crashReportPreview = null },
            onConfirm = { text ->
                val copied = runCatching {
                    val clipboard = requireNotNull(context.getSystemService(android.content.ClipboardManager::class.java))
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("V2max private report", text))
                }.isSuccess
                vm.showMessage(if (copied) "خلاصهٔ خصوصی کپی شد" else "کپی گزارش ممکن نشد")
                crashReportPreview = null
            },
        )
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
                    if (Repo.isStorageReadOnly()) {
                        Spacer(Modifier.height(8.dp))
                        Text("داده‌های آسیب‌دیده در این فایل نیستند؛ این پشتیبان فقط شامل موارد قابل‌خواندن است.",
                            fontSize = 11.sp, color = V2.Amber)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        label = { Text("رمز (اختیاری)", fontSize = 11.sp) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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

    if (showPlainRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showPlainRestoreDialog = false; pendingRestoreText = "" },
            containerColor = V2.SurfaceHigh,
            title = { Text("بازیابی پشتیبان", color = V2.TextPrimary) },
            text = { Text("سرورها، اشتراک‌ها و تنظیمات فعلی با محتوای پشتیبان جایگزین می‌شوند. " +
                "در صورت آسیب‌دیدگی، سند فعلی نیز جایگزین خواهد شد.", color = V2.TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    val text = pendingRestoreText
                    showPlainRestoreDialog = false
                    pendingRestoreText = ""
                    vm.importBackup(text)
                }) { Text("بازیابی", color = V2.Mint) }
            },
            dismissButton = {
                TextButton(onClick = { showPlainRestoreDialog = false; pendingRestoreText = "" }) {
                    Text("انصراف", color = V2.TextMuted)
                }
            }
        )
    }

    if (showRestoreDialog) {
        var password by remember { mutableStateOf("") }
        var restoring by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!restoring) { showRestoreDialog = false; pendingRestoreText = "" } },
            containerColor = V2.SurfaceHigh,
            title = { Text("بازیابی پشتیبان رمزدار", color = V2.TextPrimary) },
            text = {
                Column {
                    Text("رمز فایل را وارد کنید. سرورها، اشتراک‌ها و تنظیمات فعلی، از جمله سند آسیب‌دیده، " +
                        "با محتوای پشتیبان جایگزین می‌شوند.", fontSize = 11.sp, color = V2.TextMuted)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        label = { Text("رمز", fontSize = 11.sp) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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
                TextButton(enabled = !restoring, onClick = {
                    val encrypted = pendingRestoreText
                    val enteredPassword = password
                    restoring = true
                    restoreError = ""
                    scope.launch {
                        try {
                            if (vm.importEncryptedBackup(encrypted, enteredPassword)) {
                                showRestoreDialog = false
                                pendingRestoreText = ""
                                vm.showMessage(vm.restoreSuccessMessage())
                            } else {
                                restoreError = "رمز، فایل یا فضای ذخیره‌سازی را بررسی کنید"
                            }
                        } finally {
                            restoring = false
                        }
                    }
                }) { Text(if (restoring) "در حال بازیابی…" else "بازیابی", color = V2.Mint) }
            },
            dismissButton = {
                TextButton(enabled = !restoring, onClick = { showRestoreDialog = false; pendingRestoreText = "" }) {
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
                        vm.endServerEdit()
                        editingId = ""
                    } else {
                        ConfigEditorScreen(
                            server = server,
                            dataEpoch = Repo.currentDataEpoch(),
                            onBack = { vm.endServerEdit(); editingId = "" },
                            onSave = { edited, fingerprint, epoch ->
                                if (vm.saveEditedServer(edited, fingerprint, epoch)) {
                                    vm.endServerEdit(); editingId = ""
                                }
                            },
                            onClone = { copy, fingerprint, epoch ->
                                if (vm.cloneServer(copy, server.id, fingerprint, epoch)) {
                                    vm.endServerEdit(); editingId = ""
                                }
                            }
                        )
                    }
                } else if (showDiag) {
                    DiagScreen(
                        appVersion = BuildConfig.VERSION_NAME,
                        coreVersion = remember { vm.coreVersion() },
                        onBack = { showDiag = false },
                        onAction = { action -> handleDiagAction(action) }
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
                        onInstallUpdate = { beginOrContinueInstall() },
                        updateProgress = updateProgress,
                        killSwitchBlocked = tunnel.killSwitchRetained,
                        onUnblock = { vm.disconnectTunnel() },
                        latencySamples = latencySamples,
                        poolSize = poolSize,
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
                        onEdit = { id ->
                            if (vm.beginServerEdit(id)) editingId = id
                            else vm.showMessage("سرور دیگر در فهرست نیست؛ دوباره انتخاب کنید")
                        },
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
                        onAddFreeSource = { vm.addFreeSource(it) },
                        onAddFreeSources = { vm.addFreeSources() },
                        onScanQr = { showQr = true },
                        onQrContent = { qrDialogContent = it }
                    )

                    Tab.SETTINGS -> SettingsScreen(
                        settings = settings,
                        coreVersion = remember { vm.coreVersion() },
                        appVersion = BuildConfig.VERSION_NAME,
                        onChange = { block -> vm.updateSettings(block) },
                        onSecurityChange = { block -> vm.updateSecuritySettings(block) },
                        onClearAll = { vm.resetData() },
                        onOpenPerApp = { showPerApp = true },
                        onSetFreeSources = { vm.setFreeSourcesEnabled(it) },
                        onBackup = {
                            backupPassword = ""
                            showBackupDialog = true
                        },
                        onRestore = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        onCheckUpdate = { vm.checkUpdates(manual = true) },
                        onInstallUpdate = { beginOrContinueInstall() },
                        onScanQr = { showQr = true },
                        onOpenRouting = { showRouting = true },
                        onOpenDiag = { showDiag = true },
                        onOpenCrashReport = {
                            val errorSummary = ir.v2max.app.util.CrashLog.latestSummary()
                            if (errorSummary == null) vm.showMessage("گزارش خطایی ثبت نشده است")
                            else crashReportPreview = ir.v2max.app.net.LocalDiagnostics.report(
                                appVersion = BuildConfig.VERSION_NAME,
                                coreVersion = vm.coreVersion(),
                                androidApi = android.os.Build.VERSION.SDK_INT,
                                tunnel = tunnel,
                                settings = settings,
                                servers = servers,
                                scan = scan,
                                geoReady = ir.v2max.app.core.GeoAssets.diagnosticReadiness.value,
                                localError = errorSummary,
                            )
                        },
                        onClearCrashLog = {
                            val cleared = ir.v2max.app.util.CrashLog.clear()
                            vm.showMessage(if (cleared) "گزارش محلی خطا حذف شد" else "حذف گزارش محلی ممکن نشد")
                        }
                    )
                }
            }
        }
    }
}
