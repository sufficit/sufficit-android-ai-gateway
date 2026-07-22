package com.sufficit.ai.gateway

import android.os.Handler
import android.os.Looper
import com.sufficit.ai.gateway.runtime.GatewayRuntime
import com.sufficit.ai.gateway.vision.CameraCaptureCoordinator
import com.sufficit.ai.gateway.vision.CameraGestureEvent
import com.sufficit.ai.gateway.vision.MediaPipeCameraGestureRecognizer

// Debounce do gesto de mao aberta (interromper fala): para FECHAR o punho
// ("parar de ouvir") a mao passa ABERTA na transicao, o que disparava a
// interrupcao da fala sem querer. A interrupcao agora e agendada com um
// pequeno atraso; se um punho chegar nesse intervalo (a mao estava indo para
// o punho), e cancelada. Mao aberta mantida interrompe normalmente.
private object OpenHandInterruptDebounce {
    val handler = Handler(Looper.getMainLooper())
    @Volatile var pending: Runnable? = null
    const val DELAY_MS = 350L

    fun schedule(action: () -> Unit) {
        cancel()
        val r = Runnable { pending = null; action() }
        pending = r
        handler.postDelayed(r, DELAY_MS)
    }

    fun cancel() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }
}

fun handleDisabledCameraGestureState(
 statusReason: String,
 active: Boolean = false
) {
 GatewayRuntime.setCameraGestureStatus("Gesto por camera desativado.")
 GatewayRuntime.setGestureDebugState(
 detectedLabel = null,
 matched = false,
 reason = statusReason,
 active = active
 )
}

fun handlePendingCameraPermissionState(
 pendingReason: String,
 requestCameraGestureStart: () -> Unit
) {
 requestCameraGestureStart()
 GatewayRuntime.setGestureDebugActive(false)
 GatewayRuntime.setCameraGestureStatus("Permita a camera para ativar o gesto.")
 GatewayRuntime.setGestureDebugState(
 detectedLabel = null,
 matched = false,
 reason = pendingReason,
 active = false
 )
}

fun handleCameraPermissionResult(
 granted: Boolean,
 cameraGestureEnabled: Boolean,
 pendingCameraGestureStart: Boolean,
 isGestureDebugPageVisible: Boolean,
 gestureRecognizer: MediaPipeCameraGestureRecognizer,
 clearPendingCameraGestureStart: () -> Unit
) {
 if (!cameraGestureEnabled) {
 GatewayRuntime.setCameraGestureStatus("Gesto por camera desativado.")
 clearPendingCameraGestureStart()
 gestureRecognizer.stop()
 return
 }
 if (granted) {
 if (pendingCameraGestureStart) {
 clearPendingCameraGestureStart()
 GatewayRuntime.setCameraGestureStatus(
 if (isGestureDebugPageVisible) {
 "Camera autorizada. Aguardando gesto."
 } else {
 "Camera ativa em segundo plano. Abra Depuracao para visualizar."
 }
 )
 }
 return
 }

 clearPendingCameraGestureStart()
 gestureRecognizer.stop()
 GatewayRuntime.setCameraGestureStatus("Permissao de camera negada.")
}

fun stopGestureDebugCamera(
 gestureRecognizer: MediaPipeCameraGestureRecognizer,
 clearPendingCameraGestureStart: () -> Unit
) {
 stopCameraGestureCapture(
 gestureRecognizer = gestureRecognizer,
 clearPendingCameraGestureStart = clearPendingCameraGestureStart,
 statusText = "Camera parada para depuracao.",
 reason = "Analise da camera pausada manualmente."
 )
}

fun stopCameraGesturesOutsideChat(
 gestureRecognizer: MediaPipeCameraGestureRecognizer,
 clearPendingCameraGestureStart: () -> Unit
) {
 android.util.Log.i("MainActivity", "Stopping camera gesture recognition outside chat.")
 stopCameraGestureCapture(
 gestureRecognizer = gestureRecognizer,
 clearPendingCameraGestureStart = clearPendingCameraGestureStart,
 statusText = "Gestos pausados fora do chat.",
 reason = "A camera de gestos funciona automaticamente somente na tela de chat."
 )
}

private fun stopCameraGestureCapture(
 gestureRecognizer: MediaPipeCameraGestureRecognizer,
 clearPendingCameraGestureStart: () -> Unit,
 statusText: String,
 reason: String
) {
 clearPendingCameraGestureStart()
 gestureRecognizer.stop()
 GatewayRuntime.setCameraGestureInteractionActive(false)
 GatewayRuntime.setGestureDebugActive(false)
 // Camera parada nao pode trancar o microfone: gate fica aberto (sem
 // camera nao haveria gesto para reabrir).
 GatewayRuntime.setCameraGestureGateOpen(true)
 GatewayRuntime.setCameraGestureStatus(statusText)
 GatewayRuntime.setGestureDebugState(
 detectedLabel = null,
 matched = false,
 reason = reason,
 active = false
 )
}

/**
 * Gesto 2 — dedo indicador levantado ("vou falar"):
 * quando o nivel 2 esta parado, abre o gate do microfone, acende a tela e
 * inicia a escuta em primeiro plano. Com a escuta ambiente ja ativa, o
 * indicador e redundante e deve ser ignorado desde o reconhecedor.
 */
fun handleIndexRaisedEvent(
 event: CameraGestureEvent.IndexRaised,
 screenHoldMillis: Long,
 startForegroundListening: () -> Unit,
 markDirectAddress: () -> Unit
) {
 GatewayRuntime.setCameraGestureGateOpen(true)
 // "Vou falar" = enderecamento direto: a fala seguinte e para o
 // assistente; sem isso o pre-agente retem como conversa ambiente.
 markDirectAddress()
 GatewayRuntime.setCameraGestureStatus("Indicador detectado. Abrindo microfone.")
 GatewayRuntime.setGestureDebugState(
 detectedLabel = event.debugLabel,
 matched = true,
 reason = "Indicador levantado: abrindo gravacao.",
 active = true
 )
 GatewayRuntime.requestScreenAttention(screenHoldMillis)
 startForegroundListening()
}

fun startCameraGestureCapture(
 previewVisible: Boolean,
 cameraGestureEnabled: Boolean,
 hasCameraPermission: Boolean,
 captureCoordinator: CameraCaptureCoordinator,
 requestCameraGestureStart: () -> Unit,
 clearPendingCameraGestureStart: () -> Unit,
 launchCameraPermission: () -> Unit,
 screenHoldMillis: Long,
 startForegroundListening: () -> Unit,
 interruptAssistant: () -> Unit,
 stopListening: () -> Unit,
 markDirectAddress: () -> Unit,
 logStart: (String) -> Unit
) {
 if (!cameraGestureEnabled) {
 handleDisabledCameraGestureState(
 statusReason = "Ative o gesto por camera na configuracao antes de iniciar."
 )
 return
 }
 if (!hasCameraPermission) {
 handlePendingCameraPermissionState(
 pendingReason = "Permissao da camera pendente.",
 requestCameraGestureStart = requestCameraGestureStart
 )
 launchCameraPermission()
 return
 }

 clearPendingCameraGestureStart()
 // Gate NAO fecha ao iniciar a camera: app aberto com escuta ativa ouve de
 // verdade desde o primeiro segundo. Os gestos comandam acoes (gravar,
 // enviar, parar) — nao sao mais pre-requisito para o microfone funcionar.
 GatewayRuntime.setCameraGestureStatus(
 if (previewVisible) {
 "Camera de gestos ativa."
 } else {
 "Camera de gestos ativa em segundo plano."
 }
 )
 GatewayRuntime.setGestureDebugActive(true)
 GatewayRuntime.setGestureDebugState(
 detectedLabel = null,
 matched = false,
 reason = if (previewVisible) {
 "Camera ativa. Aguardando deteccao de gesto."
 } else {
 "Camera ativa em segundo plano. Abra a tela de depuracao para acompanhar o preview."
 },
 active = true
 )
 logStart("Starting camera capture. previewVisible=$previewVisible hasCameraPermission=$hasCameraPermission")
 // Roteamento dos gestos de comando (contrato em CameraGestureEvent):
 //  1. Mao aberta  -> interrompe a fala do assistente imediatamente.
 //  2. Indicador   -> abre a gravacao somente se o nivel 2 estiver parado.
 //  3. Punho       -> para a deteccao de voz/entra em espera.
 // Cada evento tambem acende a linha colorida do rodape via o estado
 // continuo publicado pelo reconhecedor (GatewayRuntime.gestureCommand).
 captureCoordinator.start(previewVisible) eventHandler@{ event ->
 // A camera pode continuar aberta para retomar rapidamente, mas nenhum
 // comando de gesto atravessa o modo de digitacao. Isso impede que mao
 // aberta/punho/indicador interrompam o usuario enquanto usa o teclado.
 if (GatewayRuntime.state().value.textInputModeActive) {
 OpenHandInterruptDebounce.cancel()
 GatewayRuntime.setGestureCommand(null)
 GatewayRuntime.setCameraGestureStatus("Gestos pausados durante a digitacao.")
 GatewayRuntime.setGestureDebugState(
 detectedLabel = null,
 matched = false,
 reason = "Comando ignorado porque a entrada por texto esta ativa.",
 active = true
 )
 return@eventHandler
 }
 when (event) {
 is CameraGestureEvent.IndexRaised -> {
 // Defesa adicional contra evento atrasado: a pose pode ter estabilizado
 // exatamente durante a transicao do standby para a escuta ambiente.
 if (GatewayRuntime.state().value.listening) return@eventHandler
 handleIndexRaisedEvent(
 event = event,
 screenHoldMillis = screenHoldMillis,
 startForegroundListening = startForegroundListening,
 markDirectAddress = markDirectAddress
 )
 }
 is CameraGestureEvent.OpenHandCalm -> {
 // Interrupcao da fala COM atraso: cancelada se um punho vier logo
 // depois (a mao estava so a caminho do punho). Mao aberta mantida
 // executa apos o atraso.
 OpenHandInterruptDebounce.schedule {
 GatewayRuntime.setCameraGestureStatus("Mao aberta: interrompendo fala do assistente.")
 interruptAssistant()
 }
 }
 is CameraGestureEvent.FistClosed -> {
 // O punho cancela uma interrupcao de fala pendente: fechar a mao
 // ("parar de ouvir") nao deve mexer na fala do assistente.
 OpenHandInterruptDebounce.cancel()
 GatewayRuntime.setCameraGestureStatus("Punho fechado: parando a escuta.")
 stopListening()
 }
 // Compatibilidade: se chegar de um reconhecedor antigo, mantem a mesma
 // acao do punho curto.
 is CameraGestureEvent.FistHeldStop -> {
 OpenHandInterruptDebounce.cancel()
 GatewayRuntime.setCameraGestureStatus("Punho mantido: escuta ja foi parada.")
 stopListening()
 }
 }
 }
}
