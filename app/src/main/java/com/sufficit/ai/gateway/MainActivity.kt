package com.sufficit.ai.gateway

import android.Manifest
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sufficit.ai.gateway.audio.RoomAudioForegroundService
import com.sufficit.ai.gateway.config.DeviceModelGuideCatalog
import com.sufficit.ai.gateway.config.GatewaySettings
import com.sufficit.ai.gateway.config.GatewaySettingsStore
import com.sufficit.ai.gateway.config.LocalExecutionMode
import com.sufficit.ai.gateway.config.ScreenMode
import com.sufficit.ai.gateway.config.TranscriptionMode
import com.sufficit.ai.gateway.config.readGatewaySettingsBackup
import com.sufficit.ai.gateway.history.TranscriptHistoryLogger
import com.sufficit.ai.gateway.runtime.GatewayRuntime
import com.sufficit.ai.gateway.state.GatewayUiEvent
import com.sufficit.ai.gateway.state.GatewayViewModel
import com.sufficit.ai.gateway.state.GatewayViewModelFactory
import com.sufficit.ai.gateway.transcription.local.LocalSherpaOnnxEngine
import com.sufficit.ai.gateway.transcription.local.LocalWhisperEngine
import com.sufficit.ai.gateway.ui.theme.SufficitOpenClawGatewayTheme
import com.sufficit.ai.gateway.vision.MediaPipeCameraGestureRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        current = java.lang.ref.WeakReference(this)
        applyWakeScreenFlags()

        setContent {
            SufficitOpenClawGatewayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GatewayScreen()
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_WAKE_SCREEN, false)) {
            // Re-aplica para uma Activity ja viva trazida ao topo com a tela
            // apagada (wake word / fala do agente). setTurnScreenOn so dispara o
            // acendimento no proximo resume, entao reforcamos aqui.
            applyWakeScreenFlags()
        }
    }

    /**
     * Faz a Activity acender a tela e aparecer sobre o bloqueio. Substitui o
     * SCREEN_BRIGHT_WAKE_LOCK (deprecado, nao acende a tela no Android 11+).
     */
    private fun applyWakeScreenFlags() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            (getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)
                ?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_WAKE_SCREEN = "com.sufficit.ai.gateway.extra.WAKE_SCREEN"

        /**
         * Acende a tela trazendo a MainActivity ao topo com as flags de
         * turn-screen-on/show-when-locked. Chamado pelo servico quando a tela
         * esta apagada (wake word, fala do agente) — caminho confiavel no
         * Android moderno, ja que os wake locks de tela foram deprecados.
         */
        fun requestWakeScreen(context: android.content.Context) {
            val intent = android.content.Intent(context, MainActivity::class.java).apply {
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(EXTRA_WAKE_SCREEN, true)
            }
            runCatching { context.startActivity(intent) }
        }

        // Referencia fraca para a API capturar a janela do app (screenshot)
        // sem MediaProjection. Funciona com o app em primeiro plano.
        @Volatile
        private var current: java.lang.ref.WeakReference<MainActivity>? = null

        /**
         * Captura a janela do app em PNG e retorna o arquivo, ou null se a
         * Activity nao estiver viva/visivel. Sincrono (espera o PixelCopy).
         */
        fun captureWindowToFile(): java.io.File? {
            val activity = current?.get() ?: return null
            val view = activity.window?.decorView ?: return null
            // Desenha a hierarquia de views num bitmap no MAIN thread. Captura a
            // UI Compose (chat, status); SurfaceView (camera) sai em branco, o
            // que e aceitavel para um print da interface.
            val result = java.util.concurrent.atomic.AtomicReference<android.graphics.Bitmap?>()
            val latch = java.util.concurrent.CountDownLatch(1)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    if (view.width > 0 && view.height > 0) {
                        val bmp = android.graphics.Bitmap.createBitmap(
                            view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888
                        )
                        view.draw(android.graphics.Canvas(bmp))
                        result.set(bmp)
                    }
                }.onFailure { android.util.Log.w("MainActivity", "screenshot: draw falhou", it) }
                latch.countDown()
            }
            if (!latch.await(2, java.util.concurrent.TimeUnit.SECONDS)) return null
            val bitmap = result.get() ?: return null
            return runCatching {
                val dir = java.io.File(activity.cacheDir, "screenshots").apply { mkdirs() }
                val file = java.io.File(dir, "screen-${System.currentTimeMillis()}.png")
                java.io.FileOutputStream(file).use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it)
                }
                file
            }.getOrNull()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GatewayScreen() {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val settingsStore = remember(context) { GatewaySettingsStore(context) }
    val initialSettings = remember(settingsStore) { settingsStore.load() }
    val gatewayViewModel: GatewayViewModel = viewModel(
        factory = GatewayViewModelFactory(initialSettings)
    )
    val runtimeState by GatewayRuntime.state().collectAsState()
    val startupState = gatewayViewModel.startupState

    var hasPermission by remember { mutableStateOf(context.hasMicrophonePermission()) }
    var hasCameraPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    var hasNotificationPermission by remember { mutableStateOf(context.hasNotificationPermission()) }
    val settingsState = rememberGatewaySettingsState(initialSettings)
    var downloadState by remember {
        mutableStateOf(
            GatewayDownloadState(
                inProgress = false,
                status = "",
                progress = 0f,
                progressLabel = "",
                optionsRefreshTick = 0
            )
        )
    }
    var localModelOptions by remember { mutableStateOf<List<LocalModelOption>>(emptyList()) }
    var modelState by remember {
        mutableStateOf(
            GatewayModelState(
                localOptionsLoading = false,
                localModelExists = isLocalModelReady(context, settingsState.localModelName),
                huggingFaceModelExists = null,
                huggingFaceCheckInProgress = false
            )
        )
    }
    var historyState by remember {
        mutableStateOf(
            GatewayHistoryState(
                refreshTick = 0,
                actionStatus = "",
                settingsBackupStatus = ""
            )
        )
    }
    val uiScope = rememberCoroutineScope()
    val initialPage = DASHBOARD_PAGE_INDEX
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { PAGE_COUNT })


    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(500)
        }
    }

    val effectiveScreenMode = ScreenMode.fromPersistedValue(settingsState.screenMode)
    val screenAttentionActive = runtimeState.screenAttentionUntilEpochMs > now
    val deviceGuide = remember(context) { DeviceModelGuideCatalog.matchCurrentDevice(context) }
    val localSystemInfo = remember(context, settingsState.localExecutionMode, settingsState.localModelName) {
        val executionMode = LocalExecutionMode.fromPersistedValue(settingsState.localExecutionMode)
        runCatching {
            when (executionMode) {
                LocalExecutionMode.CPU -> LocalWhisperEngine.systemInfo()
                LocalExecutionMode.NNAPI -> LocalSherpaOnnxEngine.systemInfo(
                    context = context,
                    modelPath = resolveLocalModelTarget(context, settingsState.localModelName).absolutePath,
                    executionMode = executionMode
                )
            }
        }.getOrDefault("Backend local ainda nao carregado.")
    }
    val historySnapshot by produceState(
        initialValue = TranscriptHistoryLogger.snapshot(context),
        historyState.refreshTick
    ) {
        while (true) {
            value = TranscriptHistoryLogger.snapshot(context)
            delay(2000)
        }
    }
    var uiState by remember {
        mutableStateOf(
            GatewayUiState(
                configDestination = ConfigSectionDestination.HOME,
                lastBackPressedAt = 0L
            )
        )
    }
    val keepScreenOn = when (effectiveScreenMode) {
        ScreenMode.ALWAYS_ON -> true
        ScreenMode.ALWAYS_OFF -> false
        ScreenMode.ACTIVITY -> screenAttentionActive
    }

    LaunchedEffect(settingsState.localModelName) {
        modelState = modelState.copy(localModelExists = isLocalModelReady(context, settingsState.localModelName))
        val normalizedName = settingsState.localModelName.trim()
        if (normalizedName.isBlank()) {
            modelState = modelState.copy(
                huggingFaceModelExists = null,
                huggingFaceCheckInProgress = false
            )
            return@LaunchedEffect
        }
        modelState = modelState.copy(huggingFaceCheckInProgress = true)
        val exists = withContext(Dispatchers.IO) { checkHuggingFaceModelExists(normalizedName) }
        modelState = modelState.copy(
            huggingFaceModelExists = exists,
            huggingFaceCheckInProgress = false
        )
    }

    LaunchedEffect(downloadState.optionsRefreshTick) {
        modelState = modelState.copy(localOptionsLoading = true)
        localModelOptions = withContext(Dispatchers.IO) { loadLocalModelOptions(context) }
        modelState = modelState.copy(localOptionsLoading = false)
    }

    LaunchedEffect(settingsState.transcriptionMode, runtimeState.transcriptionModelLabel) {
        if (TranscriptionMode.fromPersistedValue(settingsState.transcriptionMode) != TranscriptionMode.LOCAL) {
            return@LaunchedEffect
        }

        val actualModel = runtimeState.transcriptionModelLabel.trim()
        if (actualModel.isNotBlank() && actualModel != settingsState.localModelName) {
            settingsState.localModelName = actualModel
        }
    }

    fun reapplyImportedSettings(settings: GatewaySettings) {
        settingsState.applyFrom(settings)
        downloadState = downloadState.copy(status = "", progress = 0f, progressLabel = "")
    }

    val settingsInputSnapshot = settingsState.toSnapshot()

    LaunchedEffect(settingsInputSnapshot) {
        delay(300)
        val settings = buildSettings(
            context = context,
            input = settingsInputSnapshot
        )
        settingsStore.save(settings)
    }

    val selectedModelOption = localModelOptions.firstOrNull {
        it.name.equals(settingsState.localModelName.trim(), ignoreCase = true)
    }
    val selectedModelInvalid = selectedModelOption?.isInvalid == true
    val shouldOfferDownload = !modelState.localModelExists || selectedModelInvalid
    val isGestureDebugPageVisible = pagerState.currentPage == GESTURE_DEBUG_PAGE_INDEX
    val gestureRecognizer = remember(activity) { MediaPipeCameraGestureRecognizer(activity) }
    val gesturePreviewView = remember(gestureRecognizer) { gestureRecognizer.ensurePreviewView() }

    DisposableEffect(gestureRecognizer) {
        onDispose {
            gestureRecognizer.stop()
            gestureRecognizer.close()
        }
    }

    HandleScreenBehavior(
        activity = activity,
        screenMode = effectiveScreenMode,
        keepScreenOn = keepScreenOn,
        wakeRequested = effectiveScreenMode == ScreenMode.ACTIVITY && screenAttentionActive
    )

    // Tela de configuracao = agente em silencio: para de despachar/falar (para
    // nao se intrometer no cadastro de voz/palavra de ativacao) e interrompe
    // qualquer fala em andamento ao entrar. O microfone segue para amostras.
    LaunchedEffect(pagerState.currentPage) {
        val onConfig = pagerState.currentPage == CONFIG_PAGE_INDEX
        GatewayRuntime.setConfigScreenActive(onConfig)
        if (onConfig) {
            RoomAudioForegroundService.interruptAssistant(activity)
        }
    }
    DisposableEffect(Unit) {
        onDispose { GatewayRuntime.setConfigScreenActive(false) }
    }

    BackHandler {
        when {
            pagerState.currentPage == 1 &&
                uiState.configDestination != ConfigSectionDestination.HOME -> {
                uiState = uiState.copy(configDestination = ConfigSectionDestination.HOME)
            }

            pagerState.currentPage != initialPage -> {
                uiScope.launch {
                    pagerState.animateScrollToPage(0)
                }
            }

            else -> {
                val now = SystemClock.elapsedRealtime()
                if (now - uiState.lastBackPressedAt <= EXIT_CONFIRMATION_WINDOW_MS) {
                    activity.finish()
                } else {
                    uiState = uiState.copy(lastBackPressedAt = now)
                    Toast.makeText(
                        context,
                        "Aperte novamente para sair.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    fun startListeningWithStatus(statusText: String) {
        persistSettingsAndStartListening(
            context = context,
            settingsStore = settingsStore,
            input = settingsInputSnapshot,
            statusText = statusText
        )
    }

    fun requestStartForegroundListening(statusText: String = "Iniciando escuta...") {
        gatewayViewModel.onEvent(
            GatewayUiEvent.StartForegroundListeningRequested(
                hasMicrophonePermission = hasPermission,
                hasNotificationPermission = hasNotificationPermission,
                hasNotificationRuntimePermission = context.hasNotificationRuntimePermission(),
                statusText = statusText
            )
        )
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
        val notificationPermissionFullyGranted = context.hasNotificationPermission()
        hasNotificationPermission = notificationPermissionFullyGranted
        gatewayViewModel.onEvent(
            GatewayUiEvent.NotificationPermissionResult(
                granted = granted,
                hasMicrophonePermission = hasPermission,
                notificationPermissionFullyGranted = notificationPermissionFullyGranted
            )
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        gatewayViewModel.onEvent(
            GatewayUiEvent.MicrophonePermissionResult(
                granted = granted,
                hasNotificationPermission = hasNotificationPermission,
                autoStartEnabled = settingsState.autoStartEnabled
            )
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        gatewayViewModel.onEvent(
            GatewayUiEvent.CameraPermissionResult(
                granted = granted,
                cameraGestureEnabled = settingsState.cameraGestureEnabled,
                isGestureDebugPageVisible = isGestureDebugPageVisible
            )
        )
    }

    fun runStartCameraGestureCapture(previewVisible: Boolean) {
        startCameraGestureCapture(
            previewVisible = previewVisible,
            cameraGestureEnabled = settingsState.cameraGestureEnabled,
            hasCameraPermission = hasCameraPermission,
            gestureRecognizer = gestureRecognizer,
            requestCameraGestureStart = {
                gatewayViewModel.onEvent(
                    GatewayUiEvent.PendingCameraGestureStartChanged(
                        value = true
                    )
                )
            },
            clearPendingCameraGestureStart = {
                gatewayViewModel.onEvent(
                    GatewayUiEvent.PendingCameraGestureStartChanged(
                        value = false
                    )
                )
            },
            launchCameraPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
            screenHoldMillis = (settingsState.screenHoldSecondsInput.toLongOrNull() ?: 4L) * 1000L,
            startForegroundListening = ::requestStartForegroundListening,
            // Gesto 1 (mao aberta): corta a fala do assistente na hora.
            interruptAssistant = { RoomAudioForegroundService.interruptAssistant(context) },
            // Gesto 3 (punho fechado): finaliza o segmento e envia.
            finalizeSpeechSegment = { RoomAudioForegroundService.finalizeSegment(context) },
            // Gesto 4 (punho mantido 5s): para a escuta como o botao de parar.
            stopListening = { RoomAudioForegroundService.stop(context) },
            // Indicador/apontar = "vou falar": fala seguinte e enderecada ao
            // assistente (nao deve ser retida como conversa ambiente).
            markDirectAddress = { RoomAudioForegroundService.markDirectAddress(context) },
            logStart = { android.util.Log.i("MainActivity", it) }
        )
    }

    fun startGestureDebugCamera() {
        gatewayViewModel.onEvent(
            GatewayUiEvent.StartCameraGestureCaptureRequested(
                previewVisible = true
            )
        )
    }

    fun runStopGestureDebugCamera() {
        stopGestureDebugCamera(
            gestureRecognizer = gestureRecognizer,
            clearPendingCameraGestureStart = {
                gatewayViewModel.onEvent(
                    GatewayUiEvent.PendingCameraGestureStartChanged(
                        value = false
                    )
                )
            }
        )
    }

    HandleGatewayUiCommands(
        gatewayViewModel = gatewayViewModel,
        permissionLauncher = permissionLauncher,
        notificationPermissionLauncher = notificationPermissionLauncher,
        cameraPermissionLauncher = cameraPermissionLauncher,
        gestureRecognizer = gestureRecognizer,
        onStartListening = { statusText ->
            startListeningWithStatus(statusText)
        },
        onStartCameraGestureCapture = { previewVisible ->
            runStartCameraGestureCapture(previewVisible)
        },
        onStopGestureDebugCamera = {
            runStopGestureDebugCamera()
        },
    )

    val settingsImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            historyState = historyState.copy(settingsBackupStatus = "Importacao cancelada.")
            return@rememberLauncherForActivityResult
        }

        uiScope.launch {
            val currentSettings = settingsStore.load()
            val result = withContext(Dispatchers.IO) {
                readGatewaySettingsBackup(
                    context = context,
                    uri = uri,
                    currentSettings = currentSettings
                )
            }

            if (result.isFailure) {
                historyState = historyState.copy(
                    settingsBackupStatus = "Falha ao importar JSON: ${result.exceptionOrNull()?.message ?: "erro desconhecido"}"
                )
                return@launch
            }

            val imported = result.getOrThrow()
            handleImportedSettingsResult(
                settings = imported.settings,
                appliedKeys = imported.appliedKeys,
                ignoredKeys = imported.ignoredKeys,
                saveSettings = { settingsStore.save(it) },
                reapplyImportedSettings = ::reapplyImportedSettings,
                updateStatus = {
                    historyState = historyState.copy(settingsBackupStatus = it)
                }
            )
        }
    }

    LaunchedEffect(settingsState.cameraGestureEnabled, hasCameraPermission, isGestureDebugPageVisible) {
        gatewayViewModel.onEvent(
            GatewayUiEvent.CameraPolicyChanged(
                cameraGestureEnabled = settingsState.cameraGestureEnabled,
                hasCameraPermission = hasCameraPermission,
                isGestureDebugPageVisible = isGestureDebugPageVisible
            )
        )
    }

    LaunchedEffect(hasPermission, hasNotificationPermission, settingsState.autoStartEnabled, startupState.autoStartAttempted) {
        if (startupState.autoStartAttempted) {
            return@LaunchedEffect
        }

        if (settingsState.autoStartEnabled) {
            delay(1200)
        }

        gatewayViewModel.onEvent(
            GatewayUiEvent.AutoStartTriggered(
                autoStartEnabled = settingsState.autoStartEnabled,
                hasMicrophonePermission = hasPermission,
                hasNotificationPermission = hasNotificationPermission,
                hasNotificationRuntimePermission = context.hasNotificationRuntimePermission()
            )
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        when (page) {
            DASHBOARD_PAGE_INDEX -> DashboardPage(
                state = runtimeState,
                development = settingsState.development,
                onStart = {
                    requestStartForegroundListening()
                },
                onStop = {
                    RoomAudioForegroundService.stop(context)
                },
                onInterruptAssistant = {
                    RoomAudioForegroundService.interruptAssistant(context)
                },
                onSendText = { text ->
                    RoomAudioForegroundService.sendText(context, text)
                }
            )

            CONFIG_PAGE_INDEX -> GatewayConfigPageHost(
                settingsState = settingsState,
                hasPermission = hasPermission,
                hasCameraPermission = hasCameraPermission,
                cameraGestureStatus = runtimeState.cameraGestureStatus,
                downloadState = downloadState,
                modelState = modelState,
                historyState = historyState,
                localModelOptions = localModelOptions,
                selectedModelInvalid = selectedModelInvalid,
                shouldOfferDownload = shouldOfferDownload,
                localSystemInfo = localSystemInfo,
                deviceGuide = deviceGuide,
                historySnapshot = historySnapshot,
                context = context,
                uiScope = uiScope,
                currentDownloadState = { downloadState },
                currentModelState = { modelState },
                currentHistoryState = { historyState },
                launchSettingsImport = settingsImportLauncher,
                requestMicrophonePermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                requestCameraPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                openGestureDebug = {
                    uiScope.launch {
                        uiState = uiState.copy(configDestination = ConfigSectionDestination.DEBUG)
                        pagerState.animateScrollToPage(GESTURE_DEBUG_PAGE_INDEX)
                    }
                },
                updateDownloadState = { downloadState = it },
                updateModelState = { modelState = it },
                updateHistoryState = { historyState = it },
                destination = uiState.configDestination,
                onDestinationChange = { uiState = uiState.copy(configDestination = it) }
            )

            DICTIONARY_PAGE_INDEX -> DictionaryPage(
                colloquialNormalizationStrengthInput = settingsState.colloquialNormalizationStrengthInput,
                transcriptionTermsInput = settingsState.transcriptionTermsInput,
                transcriptionDictionaryInput = settingsState.transcriptionDictionaryInput,
                onColloquialNormalizationStrengthChange = { settingsState.colloquialNormalizationStrengthInput = it },
                onTranscriptionTermsChange = { settingsState.transcriptionTermsInput = it },
                onTranscriptionDictionaryChange = { settingsState.transcriptionDictionaryInput = it }
            )

            else -> GestureDebugPage(
                state = runtimeState,
                previewView = gesturePreviewView,
                onOpenConfig = {
                    uiScope.launch {
                        uiState = uiState.copy(configDestination = ConfigSectionDestination.DEBUG)
                        pagerState.animateScrollToPage(CONFIG_PAGE_INDEX)
                    }
                },
                onOpenDashboard = {
                    uiScope.launch {
                        pagerState.animateScrollToPage(DASHBOARD_PAGE_INDEX)
                    }
                },
                onStartCameraDebug = { startGestureDebugCamera() },
                onStopCameraDebug = {
                    gatewayViewModel.onEvent(GatewayUiEvent.StopCameraGestureDebugRequested)
                }
            )
        }
    }
    // Luvas sobre o chat e a depuracao quando maos forem detectadas;
    // telas de configuracao ficam livres do overlay.
    if (pagerState.currentPage == DASHBOARD_PAGE_INDEX ||
        pagerState.currentPage == GESTURE_DEBUG_PAGE_INDEX
    ) {
        HandGloveOverlay(modifier = Modifier.fillMaxSize())
        // Punho mantido: contagem 3..2..1 estilo jogo de luta no centro da
        // tela nos ultimos 3s antes de parar a escuta.
        FistCountdownOverlay(modifier = Modifier.fillMaxSize())
        // Linha colorida no rodape para cada gesto de comando reconhecido
        // (laranja = parar fala, verde = gravando, azul = enviar).
        GestureCommandFooter(
            modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
        )
    }
    // Efeito de flash/captura (screenshot por API): clarao + etiqueta, sobre
    // todas as paginas. Disparado pelo servico via GatewayRuntime.
    ScreenEffectOverlay(modifier = Modifier.fillMaxSize())
    }
}

private const val DASHBOARD_PAGE_INDEX = 0
private const val CONFIG_PAGE_INDEX = 1
private const val DICTIONARY_PAGE_INDEX = 2
private const val GESTURE_DEBUG_PAGE_INDEX = 3
private const val PAGE_COUNT = 4
private const val EXIT_CONFIRMATION_WINDOW_MS = 2_000L
