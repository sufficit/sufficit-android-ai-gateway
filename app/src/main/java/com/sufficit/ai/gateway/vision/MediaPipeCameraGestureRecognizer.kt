package com.sufficit.ai.gateway.vision

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmarkList
import com.google.mediapipe.solutions.facemesh.FaceMesh
import com.google.mediapipe.solutions.facemesh.FaceMeshOptions
import com.google.mediapipe.solutions.facemesh.FaceMeshResult
import com.google.mediapipe.solutions.hands.HandLandmark
import com.google.mediapipe.solutions.hands.Hands
import com.google.mediapipe.solutions.hands.HandsOptions
import com.google.mediapipe.solutions.hands.HandsResult
import com.sufficit.ai.gateway.runtime.GatewayRuntime
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MediaPipeCameraGestureRecognizer(
    private val activity: ComponentActivity
) : AutoCloseable {

    @Volatile
    private var hands: Hands? = null

    @Volatile
    private var retiredHands: Hands? = null
    private val runOnGpu = AtomicBoolean(true)

    // Chamado apenas no analysisExecutor (single-thread). Tenta GPU primeiro;
    // qualquer falha de inicializacao ou erro em runtime cai para CPU uma vez.
    private fun obtainHands(): Hands {
        retiredHands?.let {
            runCatching { it.close() }
            retiredHands = null
        }
        hands?.let { return it }
        while (true) {
            val gpu = runOnGpu.get()
            try {
                val created = createHands(gpu)
                hands = created
                return created
            } catch (ex: Throwable) {
                if (gpu && runOnGpu.compareAndSet(true, false)) {
                    Log.w(TAG, "Falha ao iniciar MediaPipe Hands na GPU; tentando CPU.", ex)
                    continue
                }
                throw ex
            }
        }
    }

    private fun createHands(gpu: Boolean): Hands {
        val created = Hands(
            activity,
            HandsOptions.builder()
                .setStaticImageMode(false)
                .setMaxNumHands(MAX_HANDS)
                .setRunOnGpu(gpu)
                .withDefaultValues()
                .build()
        )
        created.setErrorListener { message, throwable ->
            if (gpu && runOnGpu.compareAndSet(true, false)) {
                Log.w(TAG, "Erro MediaPipe na GPU; caindo para CPU: $message", throwable)
                retiredHands = created
                hands = null
                GatewayRuntime.setCameraGestureStatus("GPU indisponivel para gestos; usando CPU.")
                return@setErrorListener
            }
            Log.e(TAG, "MediaPipe Hands error: $message", throwable)
            GatewayRuntime.setCameraGestureStatus("Falha MediaPipe: $message")
            GatewayRuntime.setGestureDebugState(
                detectedLabel = null,
                matched = false,
                reason = "Erro MediaPipe: $message",
                active = false
            )
            GatewayRuntime.setGestureDebugPreviewAvailable(false)
            running.set(false)
        }
        created.setResultListener(::onHandsResult)
        return created
    }

    // ------------------------------------------------------------------
    // FaceMesh: deteccao de movimento labial para correlacionar com a fala
    // do microfone (anti-TV/anti-gravacao). Compartilha os MESMOS frames da
    // sessao de camera dos gestos — nunca abre segunda sessao.
    //
    // Custo de CPU no A51 controlado em duas frentes:
    //  - stride: o FaceMesh processa 1 a cada FACE_FRAME_STRIDE frames
    //    (a variacao da abertura labial nao precisa de 30fps);
    //  - gate por fala: so roda enquanto ha fala detectada no microfone
    //    (ou ate FACE_SPEECH_TAIL_MS depois) — e exatamente nesse periodo
    //    que o sinal labial e consumido pelo servico de audio.
    // ------------------------------------------------------------------
    @Volatile
    private var faceMesh: FaceMesh? = null

    @Volatile
    private var retiredFaceMesh: FaceMesh? = null
    private val faceRunOnGpu = AtomicBoolean(true)
    private val faceMeshDisabled = AtomicBoolean(false)
    private var faceFrameCounter = 0L
    private var lastSpeechSeenAtMs = 0L

    // Chamado apenas no analysisExecutor (mesmo contrato do obtainHands).
    private fun obtainFaceMesh(): FaceMesh? {
        if (faceMeshDisabled.get()) return null
        retiredFaceMesh?.let {
            runCatching { it.close() }
            retiredFaceMesh = null
        }
        faceMesh?.let { return it }
        while (true) {
            val gpu = faceRunOnGpu.get()
            try {
                val created = createFaceMesh(gpu)
                faceMesh = created
                return created
            } catch (ex: Throwable) {
                if (gpu && faceRunOnGpu.compareAndSet(true, false)) {
                    Log.w(TAG, "Falha ao iniciar MediaPipe FaceMesh na GPU; tentando CPU.", ex)
                    continue
                }
                // Labios sao um reforco opcional: falha permanente desliga so
                // o FaceMesh e os gestos de mao continuam funcionando.
                Log.e(TAG, "MediaPipe FaceMesh indisponivel; sinal labial desativado.", ex)
                faceMeshDisabled.set(true)
                GatewayRuntime.setLipActivity(null)
                return null
            }
        }
    }

    private fun createFaceMesh(gpu: Boolean): FaceMesh {
        val created = FaceMesh(
            activity,
            FaceMeshOptions.builder()
                .setStaticImageMode(false)
                .setMaxNumFaces(MAX_FACES)
                .setRefineLandmarks(false)
                .setRunOnGpu(gpu)
                .build()
        )
        created.setErrorListener { message, throwable ->
            if (gpu && faceRunOnGpu.compareAndSet(true, false)) {
                Log.w(TAG, "Erro MediaPipe FaceMesh na GPU; caindo para CPU: $message", throwable)
                retiredFaceMesh = created
                faceMesh = null
                return@setErrorListener
            }
            Log.e(TAG, "MediaPipe FaceMesh error: $message", throwable)
            faceMeshDisabled.set(true)
            GatewayRuntime.setLipActivity(null)
        }
        created.setResultListener(::onFaceMeshResult)
        return created
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var previewUseCase: Preview? = null
    private var previewView: PreviewView? = null
    private var analysisExecutor: ExecutorService? = null
    private var callback: ((CameraGestureEvent) -> Unit)? = null
    private var lastHandednessLabel: String? = null
    private var previewAttached = false
    private val running = AtomicBoolean(false)
    private val frameInFlight = AtomicBoolean(false)

    fun ensurePreviewView(): PreviewView {
        previewView?.let { return it }
        return PreviewView(activity).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }.also {
            previewView = it
        }
    }

    fun start(previewVisible: Boolean, onEvent: (CameraGestureEvent) -> Unit) {
        callback = onEvent
        active = java.lang.ref.WeakReference(this)
        if (running.getAndSet(true)) {
            Log.i(TAG, "Camera gesture recognizer already running; callback refreshed (previewVisible=$previewVisible).")
            if (previewVisible != previewAttached) {
                rebindUseCases(previewVisible)
            }
            return
        }
        Log.i(TAG, "Starting camera gesture recognizer (previewVisible=$previewVisible).")
        resetGestureStability()
        frameInFlight.set(false)

        val executor = Executors.newSingleThreadExecutor()
        analysisExecutor = executor
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener(
            {
                if (!running.get()) {
                    executor.shutdownNow()
                    return@addListener
                }
                try {
                    val provider = future.get()
                    cameraProvider = provider
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                    analysis.setAnalyzer(executor, ::analyzeFrame)
                    imageAnalysis = analysis
                    bindUseCases(provider, previewVisible)
                    GatewayRuntime.setCameraGestureStatus(
                        if (previewVisible) "Camera iniciada para depuracao." else "Camera ativa em segundo plano."
                    )
                    GatewayRuntime.setGestureDebugState(
                        detectedLabel = null,
                        matched = false,
                        reason = "Camera ativa. Aguardando deteccao de gesto.",
                        active = true
                    )
                } catch (ex: Throwable) {
                    handleStartFailure(ex)
                }
            },
            ContextCompat.getMainExecutor(activity)
        )
    }

    // Sem o PreviewView visivel na tela, o use case de Preview nunca recebe
    // surface e a sessao da camera nao entrega frames para a analise. Por isso
    // o Preview so entra no bind quando a tela de depuracao esta visivel.
    private fun bindUseCases(provider: ProcessCameraProvider, previewVisible: Boolean) {
        val analysis = imageAnalysis ?: return
        provider.unbindAll()
        if (previewVisible) {
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(ensurePreviewView().surfaceProvider)
            }
            previewUseCase = preview
            provider.bindToLifecycle(activity, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
        } else {
            previewUseCase = null
            provider.bindToLifecycle(activity, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }
        previewAttached = previewVisible
        GatewayRuntime.setGestureDebugPreviewAvailable(previewVisible)
        Log.i(TAG, "Camera use cases bound (previewVisible=$previewVisible).")
    }

    private fun rebindUseCases(previewVisible: Boolean) {
        val provider = cameraProvider ?: return
        try {
            bindUseCases(provider, previewVisible)
        } catch (ex: Throwable) {
            Log.e(TAG, "Failed to rebind camera use cases.", ex)
        }
    }

    fun stop() {
        callback = null
        if (active?.get() === this) active = null
        if (!running.getAndSet(false)) {
            return
        }
        resetGestureStability()
        frameInFlight.set(false)
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        previewUseCase = null
        previewAttached = false
        cameraProvider?.unbindAll()
        cameraProvider = null
        analysisExecutor?.shutdownNow()
        analysisExecutor = null
        GatewayRuntime.setHandTrackingFrame(null)
        // Camera parada = sem sinal labial (ex.: tela apagada). null avisa o
        // servico de audio para nem amostrar.
        synchronized(lipOpennessWindow) { lipOpennessWindow.clear() }
        GatewayRuntime.setLipActivity(null)
        GatewayRuntime.setGestureDebugPreviewAvailable(false)
    }

    /**
     * Tira uma FOTO com a camera (frontal por padrao, ou traseira) e salva em
     * [output] como JPEG. Coordena com a deteccao de gestos: solta o use case de
     * analise, liga o ImageCapture com a lente pedida, captura e restaura a
     * analise de gestos. Requer a Activity em primeiro plano (lifecycle STARTED);
     * o servico acorda/traz a tela antes de pedir a foto. onDone(true) no sucesso.
     */
    fun capturePhoto(useBackCamera: Boolean, output: java.io.File, onDone: (Boolean) -> Unit) {
        val mainExecutor = ContextCompat.getMainExecutor(activity)
        mainExecutor.execute {
            val providerFuture = ProcessCameraProvider.getInstance(activity)
            providerFuture.addListener({
                val provider = runCatching { providerFuture.get() }.getOrNull()
                if (provider == null) {
                    onDone(false)
                    return@addListener
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val selector = if (useBackCamera) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }
                val restoreGesture = {
                    runCatching {
                        provider.unbindAll()
                        if (running.get()) bindUseCases(provider, previewAttached)
                    }
                }
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(activity, selector, capture)
                } catch (ex: Throwable) {
                    Log.e(TAG, "Falha ao ligar a camera para foto.", ex)
                    restoreGesture()
                    onDone(false)
                    return@addListener
                }
                val options = ImageCapture.OutputFileOptions.Builder(output).build()
                capture.takePicture(
                    options,
                    mainExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                            restoreGesture()
                            onDone(true)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e(TAG, "Falha ao capturar foto.", exception)
                            restoreGesture()
                            onDone(false)
                        }
                    }
                )
            }, mainExecutor)
        }
    }

    override fun close() {
        if (active?.get() === this) active = null
        stop()
        retiredHands?.let { runCatching { it.close() } }
        retiredHands = null
        hands?.close()
        hands = null
        retiredFaceMesh?.let { runCatching { it.close() } }
        retiredFaceMesh = null
        faceMesh?.let { runCatching { it.close() } }
        faceMesh = null
    }

    fun previewViewOrNull(): PreviewView? = previewView

    private fun handleStartFailure(ex: Throwable) {
        running.set(false)
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        previewUseCase = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        analysisExecutor?.shutdownNow()
        analysisExecutor = null
        GatewayRuntime.setGestureDebugPreviewAvailable(false)
        Log.e(TAG, "Failed to start camera gesture recognizer.", ex)
        GatewayRuntime.setCameraGestureStatus("Falha ao iniciar camera: ${ex.message ?: ex.javaClass.simpleName}")
        GatewayRuntime.setGestureDebugState(
            detectedLabel = null,
            matched = false,
            reason = "Falha ao iniciar camera: ${ex.message ?: ex.javaClass.simpleName}",
            active = false
        )
    }

    private fun analyzeFrame(image: ImageProxy) {
        if (!running.get()) {
            image.close()
            return
        }
        if (!frameInFlight.compareAndSet(false, true)) {
            image.close()
            return
        }
        try {
            val bitmap = imageProxyToBitmap(image)
            if (bitmap == null) {
                GatewayRuntime.setGestureDebugState(
                    detectedLabel = null,
                    matched = false,
                    reason = "Falha ao converter frame da camera.",
                    active = true
                )
                return
            }
            val rotated = rotateBitmap(bitmap, image.imageInfo.rotationDegrees)
            // send() copia os pixels de forma sincrona: o mesmo bitmap pode
            // alimentar Hands e FaceMesh em sequencia sem corrida.
            obtainHands().send(rotated, System.currentTimeMillis() * 1000L)
            maybeAnalyzeFace(rotated)
        } catch (ex: Throwable) {
            Log.e(TAG, "Failed to analyze camera frame.", ex)
            GatewayRuntime.setCameraGestureStatus("Falha ao analisar frame: ${ex.message ?: ex.javaClass.simpleName}")
            GatewayRuntime.setGestureDebugState(
                detectedLabel = null,
                matched = false,
                reason = "Falha ao analisar frame: ${ex.message ?: ex.javaClass.simpleName}",
                active = true
            )
        } finally {
            image.close()
            frameInFlight.set(false)
        }
    }

    private fun maybeAnalyzeFace(bitmap: Bitmap) {
        if (faceMeshDisabled.get()) return
        faceFrameCounter += 1
        val now = System.currentTimeMillis()
        if (GatewayRuntime.state().value.speechDetected) {
            lastSpeechSeenAtMs = now
        }
        val speechRecent = lastSpeechSeenAtMs > 0L && now - lastSpeechSeenAtMs <= FACE_SPEECH_TAIL_MS
        if (!speechRecent) {
            // Fora de fala o sinal labial nao e consumido: zera a janela para
            // o proximo segmento nao herdar variacao antiga e poupa CPU.
            synchronized(lipOpennessWindow) {
                if (lipOpennessWindow.isNotEmpty()) {
                    lipOpennessWindow.clear()
                    GatewayRuntime.setLipActivity(null)
                }
            }
            return
        }
        if (faceFrameCounter % FACE_FRAME_STRIDE != 0L) return
        obtainFaceMesh()?.send(bitmap, now * 1000L)
    }

    // Janela deslizante da abertura labial. Tocada por dois threads (o
    // analysisExecutor limpa fora de fala; o listener do FaceMesh acumula):
    // todo acesso dentro de synchronized(lipOpennessWindow).
    private val lipOpennessWindow = ArrayDeque<Pair<Long, Float>>()

    private fun onFaceMeshResult(result: FaceMeshResult) {
        val now = System.currentTimeMillis()
        val faces = result.multiFaceLandmarks()
        if (faces.isEmpty()) {
            // Sem rosto o score nao significa nada: janela zerada para a
            // proxima aparicao do rosto nao medir um "salto" falso.
            synchronized(lipOpennessWindow) { lipOpennessWindow.clear() }
            GatewayRuntime.setLipActivity(
                GatewayRuntime.LipActivity(score = 0.0, faceCount = 0, atEpochMs = now)
            )
            return
        }

        // Multiplas pessoas no quadro: vale o rosto MAIOR (mais proximo da
        // camera = quem esta falando com o aparelho).
        val landmarks = faces.maxByOrNull { faceSpanSquared(it) } ?: return
        val points = landmarks.landmarkList
        if (points.size <= FACE_LANDMARK_CHIN) {
            return
        }

        val faceSpan = kotlin.math.sqrt(
            distanceSquared(points[FACE_LANDMARK_FOREHEAD], points[FACE_LANDMARK_CHIN])
        )
        if (faceSpan < 1e-4f) return

        // Abertura labial normalizada pelo tamanho do rosto: invariante a
        // distancia da camera.
        val openness = kotlin.math.sqrt(
            distanceSquared(points[FACE_LANDMARK_UPPER_LIP], points[FACE_LANDMARK_LOWER_LIP])
        ) / faceSpan

        // Falar = boca abrindo e fechando: a VARIACAO (max-min) da abertura na
        // janela recente discrimina melhor que a abertura instantanea (boca
        // aberta parada nao e fala).
        val score = synchronized(lipOpennessWindow) {
            lipOpennessWindow.addLast(now to openness)
            while (lipOpennessWindow.isNotEmpty() && now - lipOpennessWindow.first().first > LIP_WINDOW_MS) {
                lipOpennessWindow.removeFirst()
            }
            if (lipOpennessWindow.size >= LIP_MIN_WINDOW_SAMPLES) {
                var min = Float.MAX_VALUE
                var max = Float.MIN_VALUE
                for ((_, value) in lipOpennessWindow) {
                    if (value < min) min = value
                    if (value > max) max = value
                }
                ((max - min) / LIP_RANGE_FULL_SCALE).toDouble().coerceIn(0.0, 1.0)
            } else {
                0.0
            }
        }

        GatewayRuntime.setLipActivity(
            GatewayRuntime.LipActivity(score = score, faceCount = faces.size, atEpochMs = now)
        )
    }

    private fun faceSpanSquared(landmarks: NormalizedLandmarkList): Float {
        val points = landmarks.landmarkList
        if (points.size <= FACE_LANDMARK_CHIN) return 0f
        return distanceSquared(points[FACE_LANDMARK_FOREHEAD], points[FACE_LANDMARK_CHIN])
    }

    private fun onHandsResult(result: HandsResult) {
        publishHandTrackingFrame(result)

        val allLandmarks = result.multiHandLandmarks()
        val handednessLabels = result.multiHandedness().map { classification ->
            classification.label.takeIf { it.isNotBlank() }
        }
        lastHandednessLabel = handednessLabels.firstOrNull()

        if (allLandmarks.isEmpty()) {
            GatewayRuntime.setGestureDebugState(
                detectedLabel = null,
                matched = false,
                reason = "Nenhuma mao detectada no quadro.",
                handedness = lastHandednessLabel,
                landmarkCount = 0,
                indexExtended = false,
                middleFolded = false,
                ringFolded = false,
                pinkyFolded = false,
                thumbFolded = false,
                active = true
            )
            updateGestureStability(null)
            return
        }

        // Analise por mao. Com duas maos visiveis, vale a de maior prioridade:
        // OPEN_HAND (parar a fala do assistente = emergencia) > FIST (enviar) >
        // INDEX_UP (abrir gravacao).
        val analyses = allLandmarks.mapIndexed { index, landmarks ->
            lastHandednessLabel = handednessLabels.getOrNull(index)
            analyzeHandGesture(landmarks)
        }
        val gestureId = analyses
            .mapNotNull { it.gestureId }
            .minByOrNull { gesturePriority(it) }
        val analysis = analyses.firstOrNull { it.gestureId == gestureId } ?: analyses.first()
        GatewayRuntime.setGestureDebugState(
            detectedLabel = gestureId?.let { gestureEventFor(it).debugLabel },
            matched = gestureId != null,
            reason = analysis.reason,
            handedness = analysis.handedness,
            landmarkCount = analysis.landmarkCount,
            indexExtended = analysis.indexExtended,
            middleFolded = analysis.middleFolded,
            ringFolded = analysis.ringFolded,
            pinkyFolded = analysis.pinkyFolded,
            thumbFolded = analysis.thumbFolded,
            active = true
        )
        updateGestureStability(gestureId)
        logGestureDiagnostics(analysis, gestureId, allLandmarks.firstOrNull())
    }

    // Diagnostico em logcat (rate-limited): permite ver POR QUE uma pose nao
    // virou comando sem precisar abrir a pagina de depuracao no aparelho.
    private var lastGestureDiagnosticLogAtMs = 0L

    private fun logGestureDiagnostics(
        analysis: GestureDebugAnalysis,
        gestureId: String?,
        landmarks: NormalizedLandmarkList?
    ) {
        val now = System.currentTimeMillis()
        if (now - lastGestureDiagnosticLogAtMs < GESTURE_DIAGNOSTIC_LOG_INTERVAL_MS) return
        lastGestureDiagnosticLogAtMs = now
        // Coordenadas cruas da cadeia do indicador + punho: respondem se o
        // frame chega na orientacao que os testes de eixo Y esperam.
        landmarks?.takeIf { it.landmarkCount >= HandLandmark.NUM_LANDMARKS }?.let { list ->
            val pts = list.landmarkList
            fun fmt(i: Int) = "(%.2f,%.2f)".format(pts[i].x, pts[i].y)
            Log.i(
                TAG,
                "GestoPts: wrist=${fmt(HandLandmark.WRIST)} " +
                    "iMcp=${fmt(HandLandmark.INDEX_FINGER_MCP)} iPip=${fmt(HandLandmark.INDEX_FINGER_PIP)} " +
                    "iDip=${fmt(HandLandmark.INDEX_FINGER_DIP)} iTip=${fmt(HandLandmark.INDEX_FINGER_TIP)} " +
                    "mMcp=${fmt(HandLandmark.MIDDLE_FINGER_MCP)} mTip=${fmt(HandLandmark.MIDDLE_FINGER_TIP)}"
            )
        }
        Log.i(
            TAG,
            "Gesto: id=${gestureId ?: "-"} stable=${stableGestureId ?: "-"} " +
                "idxExt=${analysis.indexExtended} idxFold=${analysis.indexFolded} " +
                "point=${analysis.pointingAtScreen} " +
                "midExt=${analysis.middleExtended} midFold=${analysis.middleFolded} " +
                "ringExt=${analysis.ringExtended} ringFold=${analysis.ringFolded} " +
                "pinkyExt=${analysis.pinkyExtended} pinkyFold=${analysis.pinkyFolded} " +
                "thumbFold=${analysis.thumbFolded} hand=${analysis.handedness ?: "?"} " +
                "motivo=${analysis.reason}"
        )
    }

    // ------------------------------------------------------------------
    // Estabilidade temporal dos gestos de comando.
    //
    // Regras de comportamento (contrato com o usuario, ver CameraGestureEvent):
    //  - Um gesto so vira "ativo" depois de GESTURE_STABLE_FRAMES quadros
    //    consecutivos iguais — um unico quadro ruidoso durante a transicao da
    //    mao nao dispara comando.
    //  - O gesto estavel e publicado CONTINUAMENTE no GatewayRuntime (com
    //    timestamp renovado a cada quadro). E esse estado continuo que o
    //    servico de audio usa para a regra "indicador mantido levantado nao
    //    deixa a gravacao fechar por silencio".
    //  - O callback de evento dispara apenas na TRANSICAO para um novo gesto
    //    (borda de subida). Manter a pose nao repete o evento; soltar a mao e
    //    refazer o gesto dispara de novo.
    // ------------------------------------------------------------------
    private var gestureCandidateId: String? = null
    private var gestureCandidateFrames = 0
    private var stableGestureId: String? = null
    private var stableGestureSinceMs = 0L
    private var fistHoldStopEmitted = false

    private fun updateGestureStability(rawGestureId: String?) {
        // Punho fechado so e COMANDO com a escuta ativa: parado/standby nao
        // ha nada para "parar" — sem aviso no rodape, sem countdown, sem
        // FistHeldStop. O slot do gesto fica livre para futuras funcoes com
        // o app parado. INDEX_UP segue valendo sem escuta (e o gesto que
        // ACORDA o app) e OPEN_HAND tambem (interrompe a fala do assistente).
        val gestureId = if (
            rawGestureId == GestureCommandIds.FIST &&
            !GatewayRuntime.state().value.listening
        ) {
            null
        } else {
            rawGestureId
        }
        if (gestureId == gestureCandidateId) {
            gestureCandidateFrames += 1
        } else {
            gestureCandidateId = gestureId
            gestureCandidateFrames = 1
        }
        if (gestureCandidateFrames < GESTURE_STABLE_FRAMES) {
            return
        }
        val now = System.currentTimeMillis()
        if (gestureCandidateId == stableGestureId) {
            // Pose mantida: renova o timestamp do estado continuo para o
            // servico saber que o gesto ainda esta valido neste instante.
            GatewayRuntime.setGestureCommand(stableGestureId)
            // Punho MANTIDO por FIST_HOLD_STOP_MS: comando de parar a escuta.
            // Dispara uma unica vez por pose (fistHoldStopEmitted); soltar o
            // punho e fechar de novo rearma.
            if (
                stableGestureId == GestureCommandIds.FIST &&
                !fistHoldStopEmitted &&
                now - stableGestureSinceMs >= GestureCommandIds.FIST_HOLD_STOP_MS
            ) {
                fistHoldStopEmitted = true
                Log.i(TAG, "Punho mantido por ${GestureCommandIds.FIST_HOLD_STOP_MS}ms: parar escuta.")
                callback?.invoke(CameraGestureEvent.FistHeldStop)
            }
            return
        }
        stableGestureId = gestureCandidateId
        stableGestureSinceMs = now
        fistHoldStopEmitted = false
        GatewayRuntime.setGestureCommand(stableGestureId)
        stableGestureId?.let { callback?.invoke(gestureEventFor(it)) }
    }

    private fun gestureEventFor(gestureId: String): CameraGestureEvent = when (gestureId) {
        GestureCommandIds.OPEN_HAND -> CameraGestureEvent.OpenHandCalm
        GestureCommandIds.FIST -> CameraGestureEvent.FistClosed
        else -> CameraGestureEvent.IndexRaised
    }

    private fun gesturePriority(gestureId: String): Int = when (gestureId) {
        GestureCommandIds.OPEN_HAND -> 0
        GestureCommandIds.FIST -> 1
        else -> 2
    }

    private fun resetGestureStability() {
        gestureCandidateId = null
        gestureCandidateFrames = 0
        stableGestureId = null
        stableGestureSinceMs = 0L
        fistHoldStopEmitted = false
        GatewayRuntime.setGestureCommand(null)
    }

    private var conversionBitmap: Bitmap? = null

    // Frame chega em RGBA_8888 (ver builder do ImageAnalysis): copia direta do
    // buffer para um Bitmap reutilizado, sem o roundtrip YUV->JPEG->decode.
    // Seguro reutilizar: Hands.send copia os pixels de forma sincrona.
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        if (pixelStride <= 0) return null
        val paddedWidth = plane.rowStride / pixelStride
        var bitmap = conversionBitmap
        if (bitmap == null || bitmap.width != paddedWidth || bitmap.height != image.height) {
            bitmap = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            conversionBitmap = bitmap
        }
        plane.buffer.rewind()
        bitmap.copyPixelsFromBuffer(plane.buffer)
        return if (paddedWidth != image.width) {
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        } else {
            bitmap
        }
    }

    private fun publishHandTrackingFrame(result: HandsResult) {
        val (width, height) = try {
            val bitmap = result.inputBitmap()
            bitmap.width to bitmap.height
        } catch (_: Throwable) {
            0 to 0
        }
        val handednessLabels = result.multiHandedness()
        val hands = result.multiHandLandmarks().mapIndexed { index, landmarkList ->
            TrackedHand(
                handedness = handednessLabels.getOrNull(index)?.label?.takeIf { it.isNotBlank() },
                points = landmarkList.landmarkList.map { HandPoint(it.x, it.y, it.z) }
            )
        }
        GatewayRuntime.setHandTrackingFrame(
            HandTrackingFrame(
                hands = hands,
                imageWidth = width,
                imageHeight = height,
                mirrored = true,
                timestampMs = System.currentTimeMillis()
            )
        )
    }

    /**
     * Classifica a pose de UMA mao em um dos gestos de comando:
     *
     *  - INDEX_UP ("vou falar"): indicador estendido, medio/anelar/mindinho
     *    recolhidos e polegar recolhido — o gesto de "1".
     *  - OPEN_HAND ("calma, pare de falar"): os quatro dedos estendidos.
     *    O polegar fica livre: a deteccao dele e instavel com a mao espalmada
     *    e nao muda a semantica do gesto.
     *  - FIST ("terminei, envie"): os quatro dedos recolhidos com as pontas
     *    proximas a palma. Polegar livre — pode ficar por fora do punho.
     *
     * Retorna gestureId = null quando a pose nao corresponde a nenhum comando
     * (mao em transicao, apontando para o lado etc.).
     */
    private fun analyzeHandGesture(landmarks: NormalizedLandmarkList): GestureDebugAnalysis {
        if (landmarks.landmarkCount < HandLandmark.NUM_LANDMARKS) {
            return GestureDebugAnalysis(
                gestureId = null,
                reason = "Landmarks insuficientes para analisar o gesto.",
                handedness = latestHandednessLabel(),
                landmarkCount = landmarks.landmarkCount,
                indexExtended = false,
                middleFolded = false,
                ringFolded = false,
                pinkyFolded = false,
                thumbFolded = false
            )
        }

        val points = landmarks.landmarkList
        val handedness = latestHandednessLabel()

        val indexExtended = isFingerExtended(points, HandLandmark.INDEX_FINGER_MCP, HandLandmark.INDEX_FINGER_PIP, HandLandmark.INDEX_FINGER_DIP, HandLandmark.INDEX_FINGER_TIP)
        val middleExtended = isFingerExtended(points, HandLandmark.MIDDLE_FINGER_MCP, HandLandmark.MIDDLE_FINGER_PIP, HandLandmark.MIDDLE_FINGER_DIP, HandLandmark.MIDDLE_FINGER_TIP)
        val ringExtended = isFingerExtended(points, HandLandmark.RING_FINGER_MCP, HandLandmark.RING_FINGER_PIP, HandLandmark.RING_FINGER_DIP, HandLandmark.RING_FINGER_TIP)
        val pinkyExtended = isFingerExtended(points, HandLandmark.PINKY_MCP, HandLandmark.PINKY_PIP, HandLandmark.PINKY_DIP, HandLandmark.PINKY_TIP)

        val indexFolded = isFingerFolded(points, HandLandmark.INDEX_FINGER_MCP, HandLandmark.INDEX_FINGER_PIP, HandLandmark.INDEX_FINGER_DIP, HandLandmark.INDEX_FINGER_TIP)
        val middleFolded = isFingerFolded(points, HandLandmark.MIDDLE_FINGER_MCP, HandLandmark.MIDDLE_FINGER_PIP, HandLandmark.MIDDLE_FINGER_DIP, HandLandmark.MIDDLE_FINGER_TIP)
        val ringFolded = isFingerFolded(points, HandLandmark.RING_FINGER_MCP, HandLandmark.RING_FINGER_PIP, HandLandmark.RING_FINGER_DIP, HandLandmark.RING_FINGER_TIP)
        val pinkyFolded = isFingerFolded(points, HandLandmark.PINKY_MCP, HandLandmark.PINKY_PIP, HandLandmark.PINKY_DIP, HandLandmark.PINKY_TIP)
        val thumbFolded = isThumbFolded(points, handedness)

        // INDEX_UP de meio-termo: o criterio totalmente estrito (3 dedos
        // recolhidos + polegar) falhava com a mao natural; o totalmente
        // relaxado (so "nao estendidos") dava falso positivo com a mao
        // parada no quadro — e um indicador "mantido" falso bloqueia o corte
        // por silencio e trava o envio ao OpenClaw. Meio-termo: o dedo MEDIO
        // precisa estar claramente recolhido (e o vizinho do indicador, o
        // mais discriminativo), anelar/mindinho apenas nao-estendidos e
        // polegar livre.
        val othersNotExtended = middleFolded && !ringExtended && !pinkyExtended

        // Apontar para a TELA tambem significa "vou falar com o assistente":
        // o indicador apontado para a camera frontal encurta em 2D
        // (foreshortening) e a ponta fica com z bem menor (mais perto da
        // camera) que a propria base. Exige o indicador nao-recolhido para o
        // punho fechado nao ser confundido com apontar.
        val wristPoint = points[HandLandmark.WRIST]
        val middleMcpPoint = points[HandLandmark.MIDDLE_FINGER_MCP]
        val indexTip = points[HandLandmark.INDEX_FINGER_TIP]
        val indexMcp = points[HandLandmark.INDEX_FINGER_MCP]
        val handSpanSq = distanceSquared(wristPoint, middleMcpPoint)
        val indexLengthSq = distanceSquared(indexTip, indexMcp)
        val pointingAtScreen = !indexFolded &&
            handSpanSq > 1e-6f &&
            indexLengthSq < handSpanSq * POINTING_FORESHORTEN_RATIO_SQ &&
            (indexTip.z - indexMcp.z) < POINTING_MIN_Z_DELTA

        // Caminho alternativo do INDEX_UP: mao real raramente recolhe o medio
        // "de livro" — com os dedos meio enrolados a ponta nao fica mais perto
        // do PUNHO que a propria base e o isFingerFolded nunca fecha (visto em
        // campo: indicador claramente levantado, demais dedos relaxados, e o
        // gesto nao casava). Criterio: nenhum dos tres estendido E a ponta do
        // indicador claramente ACIMA das outras pontas (margem proporcional
        // ao tamanho da mao) — e a pose "1" deliberada. Mao parada/aberta tem
        // as pontas alinhadas e nao dispara (o falso positivo que motivou o
        // criterio estrito original).
        val handSpan = kotlin.math.sqrt(handSpanSq)
        val dominanceMargin = (handSpan * INDEX_DOMINANCE_SPAN_FACTOR)
            .coerceAtLeast(INDEX_DOMINANCE_MIN_MARGIN)
        val indexTipY = points[HandLandmark.INDEX_FINGER_TIP].y
        val indexDominant = !middleExtended && !ringExtended && !pinkyExtended &&
            indexTipY < points[HandLandmark.MIDDLE_FINGER_TIP].y - dominanceMargin &&
            indexTipY < points[HandLandmark.RING_FINGER_TIP].y - dominanceMargin &&
            indexTipY < points[HandLandmark.PINKY_TIP].y - dominanceMargin

        val gestureId = when {
            // Ordem importa: INDEX_UP e mais especifico que OPEN_HAND e FIST.
            (indexExtended || pointingAtScreen) && (othersNotExtended || indexDominant) ->
                GestureCommandIds.INDEX_UP

            indexExtended && middleExtended && ringExtended && pinkyExtended ->
                GestureCommandIds.OPEN_HAND

            indexFolded && middleFolded && ringFolded && pinkyFolded ->
                GestureCommandIds.FIST

            else -> null
        }

        val reason = when {
            gestureId == GestureCommandIds.INDEX_UP && pointingAtScreen && !indexExtended ->
                "Apontando para a tela: abrir gravacao."
            gestureId == GestureCommandIds.INDEX_UP -> "Indicador levantado: abrir gravacao."
            gestureId == GestureCommandIds.OPEN_HAND -> "Mao aberta: interromper fala do assistente."
            gestureId == GestureCommandIds.FIST -> "Punho fechado: enviar para processamento."
            else -> "Pose sem comando associado."
        }

        return GestureDebugAnalysis(
            gestureId = gestureId,
            reason = reason,
            handedness = handedness,
            landmarkCount = landmarks.landmarkCount,
            indexExtended = indexExtended,
            indexFolded = indexFolded,
            middleExtended = middleExtended,
            ringExtended = ringExtended,
            pinkyExtended = pinkyExtended,
            pointingAtScreen = pointingAtScreen,
            middleFolded = middleFolded,
            ringFolded = ringFolded,
            pinkyFolded = pinkyFolded,
            thumbFolded = thumbFolded
        )
    }

    private fun latestHandednessLabel(): String? {
        return lastHandednessLabel
    }

    private fun isFingerExtended(
        points: List<NormalizedLandmark>,
        mcpIndex: Int,
        pipIndex: Int,
        dipIndex: Int,
        tipIndex: Int
    ): Boolean {
        val mcp = points[mcpIndex]
        val pip = points[pipIndex]
        val dip = points[dipIndex]
        val tip = points[tipIndex]
        val wrist = points[HandLandmark.WRIST]

        val tipAboveDip = tip.y < dip.y
        val dipAbovePip = dip.y < pip.y + 0.02f
        val tipFarFromWrist = distanceSquared(tip, wrist) > distanceSquared(pip, wrist)
        val tipFarFromPalm = distanceSquared(tip, mcp) > distanceSquared(dip, mcp)

        return tipAboveDip && dipAbovePip && tipFarFromWrist && tipFarFromPalm
    }

    private fun isFingerFolded(
        points: List<NormalizedLandmark>,
        mcpIndex: Int,
        pipIndex: Int,
        dipIndex: Int,
        tipIndex: Int
    ): Boolean {
        val mcp = points[mcpIndex]
        val pip = points[pipIndex]
        val dip = points[dipIndex]
        val tip = points[tipIndex]
        val wrist = points[HandLandmark.WRIST]

        val tipBelowPip = tip.y > pip.y - 0.01f
        val dipBelowPip = dip.y > pip.y - 0.01f
        val tipCloserToPalm = distanceSquared(tip, wrist) < distanceSquared(mcp, wrist)

        return tipBelowPip && dipBelowPip && tipCloserToPalm && tip.y > mcp.y - FOLDED_FINGER_Y_TOLERANCE
    }

    private fun isThumbFolded(points: List<NormalizedLandmark>, handedness: String?): Boolean {
        val thumbTip = points[HandLandmark.THUMB_TIP]
        val thumbIp = points[HandLandmark.THUMB_IP]
        val indexMcp = points[HandLandmark.INDEX_FINGER_MCP]
        val middleMcp = points[HandLandmark.MIDDLE_FINGER_MCP]
        val wrist = points[HandLandmark.WRIST]

        val closeToPalm = distanceSquared(thumbTip, indexMcp) < THUMB_TO_PALM_DISTANCE_SQUARED ||
            distanceSquared(thumbTip, middleMcp) < THUMB_TO_PALM_DISTANCE_SQUARED
        val belowIndexBase = thumbTip.y > indexMcp.y - THUMB_VERTICAL_TOLERANCE
        val curledAroundPalm = when (handedness?.lowercase()) {
            "left" -> thumbTip.x > thumbIp.x - THUMB_HORIZONTAL_TOLERANCE
            "right" -> thumbTip.x < thumbIp.x + THUMB_HORIZONTAL_TOLERANCE
            else -> kotlin.math.abs(thumbTip.x - wrist.x) < kotlin.math.abs(indexMcp.x - wrist.x) + THUMB_HORIZONTAL_TOLERANCE
        }

        return closeToPalm && belowIndexBase && curledAroundPalm
    }

    private fun distanceSquared(a: NormalizedLandmark, b: NormalizedLandmark): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }

    companion object {
        // Referencia fraca para o servico pedir uma foto (capturePhoto) sem
        // acoplar ao escopo Compose que cria o reconhecedor.
        @Volatile
        var active: java.lang.ref.WeakReference<MediaPipeCameraGestureRecognizer>? = null
            private set

        private const val TAG = "MediaPipeGesture"
        private const val MAX_HANDS = 2

        // Quadros consecutivos iguais para um gesto virar "ativo" (debounce).
        private const val GESTURE_STABLE_FRAMES = 3

        // Apontar para a tela: indicador encurtado em 2D abaixo desta fracao
        // do tamanho da mao (ao quadrado) + ponta mais proxima da camera que
        // a base por pelo menos POINTING_MIN_Z_DELTA.
        // Cadencia do log de diagnostico de gestos no logcat.
        private const val GESTURE_DIAGNOSTIC_LOG_INTERVAL_MS = 700L

        // ---- FaceMesh / atividade labial ----
        private const val MAX_FACES = 2

        // FaceMesh roda em 1 a cada N frames (CPU compartilhada com o Hands
        // no A51; a variacao labial nao precisa da taxa cheia da camera).
        private const val FACE_FRAME_STRIDE = 2L

        // Continua medindo labios por este tempo apos a ultima fala detectada
        // (cobre o rabo do segmento enquanto o corte por silencio decide).
        private const val FACE_SPEECH_TAIL_MS = 3_000L

        // Janela da variacao de abertura labial e minimo de amostras para o
        // score valer (com stride 2 a ~30fps, ~15 medidas por janela).
        private const val LIP_WINDOW_MS = 1_200L
        private const val LIP_MIN_WINDOW_SAMPLES = 4

        // Variacao de abertura (normalizada pela altura do rosto) que satura o
        // score em 1.0. Fala normal oscila ~0.02-0.06; boca parada fica <0.01.
        private const val LIP_RANGE_FULL_SCALE = 0.05f

        // Landmarks canonicos do FaceMesh (468 pontos).
        private const val FACE_LANDMARK_FOREHEAD = 10
        private const val FACE_LANDMARK_UPPER_LIP = 13
        private const val FACE_LANDMARK_LOWER_LIP = 14
        private const val FACE_LANDMARK_CHIN = 152

        // INDEX_UP por dominancia: ponta do indicador precisa estar acima das
        // outras pontas por esta fracao do tamanho da mao (minimo absoluto em
        // coordenadas normalizadas para maos pequenas no quadro).
        private const val INDEX_DOMINANCE_SPAN_FACTOR = 0.5f
        private const val INDEX_DOMINANCE_MIN_MARGIN = 0.04f

        private const val POINTING_FORESHORTEN_RATIO_SQ = 0.55f * 0.55f
        private const val POINTING_MIN_Z_DELTA = -0.04f
        private const val FOLDED_FINGER_Y_TOLERANCE = 0.04f
        private const val THUMB_HORIZONTAL_TOLERANCE = 0.08f
        private const val THUMB_VERTICAL_TOLERANCE = 0.04f
        private const val THUMB_TO_PALM_DISTANCE_SQUARED = 0.08f
    }
}

private data class GestureDebugAnalysis(
    /** Id do gesto reconhecido (GestureCommandIds) ou null sem comando. */
    val gestureId: String?,
    val reason: String,
    val handedness: String?,
    val landmarkCount: Int,
    val indexExtended: Boolean,
    val indexFolded: Boolean = false,
    val middleExtended: Boolean = false,
    val ringExtended: Boolean = false,
    val pinkyExtended: Boolean = false,
    val pointingAtScreen: Boolean = false,
    val middleFolded: Boolean,
    val ringFolded: Boolean,
    val pinkyFolded: Boolean,
    val thumbFolded: Boolean
)

private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
    if (rotationDegrees == 0) {
        return bitmap
    }
    val matrix = Matrix().apply {
        postRotate(rotationDegrees.toFloat())
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}