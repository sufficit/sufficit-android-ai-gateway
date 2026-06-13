package com.sufficit.ai.gateway

import com.sufficit.ai.gateway.runtime.GatewayRuntime
import com.sufficit.ai.gateway.vision.CameraGestureEvent
import com.sufficit.ai.gateway.vision.MediaPipeCameraGestureRecognizer

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
 clearPendingCameraGestureStart()
 gestureRecognizer.stop()
 GatewayRuntime.setGestureDebugActive(false)
 // Camera parada nao pode trancar o microfone: gate fica aberto (sem
 // camera nao haveria gesto para reabrir).
 GatewayRuntime.setCameraGestureGateOpen(true)
 GatewayRuntime.setCameraGestureStatus("Camera parada para depuracao.")
 GatewayRuntime.setGestureDebugState(
 detectedLabel = null,
 matched = false,
 reason = "Analise da camera pausada manualmente.",
 active = false
 )
}

/**
 * Gesto 2 — dedo indicador levantado ("vou falar"):
 * abre o gate do microfone, acende a tela e inicia a escuta em primeiro
 * plano. A partir daqui a captura segue o fluxo normal de deteccao de
 * silencio; enquanto o usuario MANTIVER o indicador levantado, o servico de
 * audio nao finaliza por silencio (ver isIndexFingerHeld no servico).
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
 gestureRecognizer: MediaPipeCameraGestureRecognizer,
 requestCameraGestureStart: () -> Unit,
 clearPendingCameraGestureStart: () -> Unit,
 launchCameraPermission: () -> Unit,
 screenHoldMillis: Long,
 startForegroundListening: () -> Unit,
 interruptAssistant: () -> Unit,
 finalizeSpeechSegment: () -> Unit,
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
 //  2. Indicador   -> abre a gravacao ("vou falar").
 //  3. Punho       -> finaliza o segmento e envia para processamento.
 // Cada evento tambem acende a linha colorida do rodape via o estado
 // continuo publicado pelo reconhecedor (GatewayRuntime.gestureCommand).
 gestureRecognizer.start(previewVisible) { event ->
 when (event) {
 is CameraGestureEvent.IndexRaised -> {
 handleIndexRaisedEvent(
 event = event,
 screenHoldMillis = screenHoldMillis,
 startForegroundListening = startForegroundListening,
 markDirectAddress = markDirectAddress
 )
 }
 is CameraGestureEvent.OpenHandCalm -> {
 GatewayRuntime.setCameraGestureStatus("Mao aberta: interrompendo fala do assistente.")
 interruptAssistant()
 }
 is CameraGestureEvent.FistClosed -> {
 GatewayRuntime.setCameraGestureStatus("Punho fechado: enviando fala para processamento.")
 // Punho = "terminei, ENVIE": intencao explicita de falar com o
 // assistente — marca enderecamento direto para o pre-agente nao
 // reter a frase pedindo confirmacao de wake.
 markDirectAddress()
 finalizeSpeechSegment()
 }
 // Punho mantido por 5s: parar a escuta (standby com palavra de
 // ativacao configurada; parada completa caso contrario).
 is CameraGestureEvent.FistHeldStop -> {
 GatewayRuntime.setCameraGestureStatus("Punho mantido: parando a escuta.")
 stopListening()
 }
 }
 }
}
