package com.sufficit.ai.gateway

import android.Manifest
import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import com.sufficit.ai.gateway.audio.RoomAudioForegroundService
import com.sufficit.ai.gateway.state.GatewayUiEvent
import com.sufficit.ai.gateway.state.GatewayViewModel
import com.sufficit.ai.gateway.vision.CameraCaptureCoordinator
import com.sufficit.ai.gateway.vision.MediaPipeCameraGestureRecognizer

data class GatewayCameraGestureCallbacks(
    val startCapture: (Boolean) -> Unit,
    val startDebugCamera: () -> Unit,
    val stopDebugCamera: () -> Unit
)

fun buildCameraGestureCallbacks(
    context: Context,
    settingsState: GatewaySettingsState,
    hasCameraPermission: Boolean,
    gestureRecognizer: MediaPipeCameraGestureRecognizer,
    gatewayViewModel: GatewayViewModel,
    cameraPermissionLauncher: ActivityResultLauncher<String>,
    requestStartForegroundListening: () -> Unit
): GatewayCameraGestureCallbacks {
    val startCapture: (Boolean) -> Unit = { previewVisible ->
        startCameraGestureCapture(
            previewVisible = previewVisible,
            cameraGestureEnabled = settingsState.cameraGestureEnabled,
            hasCameraPermission = hasCameraPermission,
            captureCoordinator = CameraCaptureCoordinator(gestureRecognizer),
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
            startForegroundListening = requestStartForegroundListening,
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

    val startDebugCamera: () -> Unit = {
        gatewayViewModel.onEvent(
            GatewayUiEvent.StartCameraGestureCaptureRequested(
                previewVisible = true
            )
        )
    }

    val stopDebugCamera: () -> Unit = {
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

    return GatewayCameraGestureCallbacks(
        startCapture = startCapture,
        startDebugCamera = startDebugCamera,
        stopDebugCamera = stopDebugCamera
    )
}
