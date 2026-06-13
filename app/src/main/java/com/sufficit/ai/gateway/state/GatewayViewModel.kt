package com.sufficit.ai.gateway.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.sufficit.ai.gateway.config.GatewaySettings

/**
 * Initial top-level state holder introduced during architecture migration.
 *
 * Scope: UI-facing startup, permission, and gate summaries only.
 * Runtime-heavy orchestration still remains in legacy layers for now.
 */
class GatewayViewModel(
    initialSettings: GatewaySettings
) : ViewModel() {

    private val _uiCommands = MutableSharedFlow<GatewayUiCommand>(
        extraBufferCapacity = 32
    )
    val uiCommands = _uiCommands.asSharedFlow()

    var startupState by mutableStateOf(GatewayStartupState())
        private set

    var settingsDraftState by mutableStateOf(GatewaySettingsDraftState.fromSettings(initialSettings))
        private set

    val settings: GatewaySettings = initialSettings

    fun onEvent(event: GatewayUiEvent) {
        when (event) {
            is GatewayUiEvent.AutoStartTriggered -> {
                if (startupState.autoStartAttempted) {
                    return
                }

                startupState = startupState.copy(autoStartAttempted = true)
                if (!event.autoStartEnabled) {
                    emitCommand(
                        GatewayUiCommand.UpdateRuntimeStatus(
                            statusText = "Pronto para iniciar. Escuta desabilitada."
                        )
                    )
                    return
                }

                resolveStartForegroundListening(
                    hasMicrophonePermission = event.hasMicrophonePermission,
                    hasNotificationPermission = event.hasNotificationPermission,
                    hasNotificationRuntimePermission = event.hasNotificationRuntimePermission,
                    statusText = "Iniciando escuta..."
                )
            }

            is GatewayUiEvent.StartForegroundListeningRequested -> {
                resolveStartForegroundListening(
                    hasMicrophonePermission = event.hasMicrophonePermission,
                    hasNotificationPermission = event.hasNotificationPermission,
                    hasNotificationRuntimePermission = event.hasNotificationRuntimePermission,
                    statusText = event.statusText
                )
            }

            is GatewayUiEvent.MicrophonePermissionResult -> {
                emitCommand(
                    GatewayUiCommand.UpdateRuntimeStatus(
                        statusText = if (event.granted) {
                            "Permissao de microfone concedida."
                        } else {
                            "Permissao de microfone negada."
                        }
                    )
                )

                if (!event.granted) {
                    return
                }

                if (!event.hasNotificationPermission) {
                    startupState = startupState.copy(
                        pendingServiceStart = event.autoStartEnabled
                    )
                    emitCommand(GatewayUiCommand.LaunchNotificationPermission)
                    return
                }

                startupState = startupState.copy(pendingServiceStart = false)
                if (event.autoStartEnabled) {
                    emitCommand(
                        GatewayUiCommand.StartListening(
                            statusText = "Iniciando escuta automaticamente..."
                        )
                    )
                }
            }

            is GatewayUiEvent.NotificationPermissionResult -> {
                emitCommand(
                    GatewayUiCommand.UpdateRuntimeStatus(
                        statusText = if (event.granted) {
                            "Permissao de notificacoes concedida."
                        } else {
                            "Permissao de notificacoes negada. A escuta precisa da notificacao fixa."
                        }
                    )
                )

                if (event.granted && event.hasMicrophonePermission && startupState.pendingServiceStart) {
                    startupState = startupState.copy(pendingServiceStart = false)
                    if (!event.notificationPermissionFullyGranted) {
                        emitCommand(GatewayUiCommand.OpenNotificationSettingsPersistent)
                        return
                    }

                    emitCommand(
                        GatewayUiCommand.StartListening(
                            statusText = "Iniciando escuta..."
                        )
                    )
                    return
                }

                if (!event.granted) {
                    startupState = startupState.copy(pendingServiceStart = false)
                    emitCommand(GatewayUiCommand.OpenNotificationSettingsDenied)
                }
            }

            is GatewayUiEvent.CameraPermissionResult -> {
                emitCommand(
                    GatewayUiCommand.HandleCameraPermissionResult(
                        granted = event.granted,
                        cameraGestureEnabled = event.cameraGestureEnabled,
                        pendingCameraGestureStart = startupState.pendingCameraGestureStart,
                        isGestureDebugPageVisible = event.isGestureDebugPageVisible
                    )
                )

                val shouldClearPending =
                    !event.cameraGestureEnabled || !event.granted || startupState.pendingCameraGestureStart
                if (shouldClearPending) {
                    startupState = startupState.copy(pendingCameraGestureStart = false)
                }
            }

            is GatewayUiEvent.CameraPolicyChanged -> {
                if (!event.cameraGestureEnabled) {
                    startupState = startupState.copy(pendingCameraGestureStart = false)
                    emitCommand(GatewayUiCommand.StopGestureDebugCamera)
                    emitCommand(
                        GatewayUiCommand.HandleDisabledCameraGestureState(
                            statusReason = "Gesto por camera desativado na configuracao."
                        )
                    )
                    return
                }

                if (!event.hasCameraPermission) {
                    startupState = startupState.copy(pendingCameraGestureStart = true)
                    emitCommand(
                        GatewayUiCommand.HandlePendingCameraPermissionState(
                            pendingReason = "Permissao da camera pendente."
                        )
                    )
                    emitCommand(GatewayUiCommand.LaunchCameraPermission)
                    return
                }

                startupState = startupState.copy(pendingCameraGestureStart = false)
                emitCommand(
                    GatewayUiCommand.StartCameraGestureCapture(
                        previewVisible = event.isGestureDebugPageVisible
                    )
                )
            }

            is GatewayUiEvent.StartCameraGestureCaptureRequested -> {
                emitCommand(
                    GatewayUiCommand.StartCameraGestureCapture(
                        previewVisible = event.previewVisible
                    )
                )
            }

            GatewayUiEvent.StopCameraGestureDebugRequested -> {
                startupState = startupState.copy(pendingCameraGestureStart = false)
                emitCommand(GatewayUiCommand.StopGestureDebugCamera)
            }

            is GatewayUiEvent.PendingServiceStartChanged -> {
                startupState = startupState.copy(pendingServiceStart = event.value)
            }

            is GatewayUiEvent.PendingCameraGestureStartChanged -> {
                startupState = startupState.copy(pendingCameraGestureStart = event.value)
            }
        }
    }

    fun markAutoStartAttempted() {
        startupState = startupState.copy(autoStartAttempted = true)
    }

    fun setPendingServiceStart(value: Boolean) {
        startupState = startupState.copy(pendingServiceStart = value)
    }

    fun setPendingCameraGestureStart(value: Boolean) {
        startupState = startupState.copy(pendingCameraGestureStart = value)
    }

    fun clearPendingActions() {
        startupState = startupState.copy(
            pendingServiceStart = false,
            pendingCameraGestureStart = false
        )
    }

    fun requestServiceStart() {
        startupState = startupState.copy(pendingServiceStart = true)
    }

    fun requestCameraGestureStart() {
        startupState = startupState.copy(pendingCameraGestureStart = true)
    }

    private fun resolveStartForegroundListening(
        hasMicrophonePermission: Boolean,
        hasNotificationPermission: Boolean,
        hasNotificationRuntimePermission: Boolean,
        statusText: String
    ) {
        if (!hasMicrophonePermission) {
            startupState = startupState.copy(pendingServiceStart = true)
            emitCommand(GatewayUiCommand.LaunchMicrophonePermission)
            return
        }

        if (!hasNotificationPermission) {
            startupState = startupState.copy(pendingServiceStart = true)
            if (hasNotificationRuntimePermission) {
                emitCommand(GatewayUiCommand.OpenNotificationSettingsForService)
            } else {
                emitCommand(GatewayUiCommand.LaunchNotificationPermission)
            }
            return
        }

        startupState = startupState.copy(pendingServiceStart = false)
        emitCommand(
            GatewayUiCommand.StartListening(
                statusText = statusText
            )
        )
    }

    private fun emitCommand(command: GatewayUiCommand) {
        _uiCommands.tryEmit(command)
    }

    fun updateSettingsDraft(update: GatewaySettingsDraftState.() -> GatewaySettingsDraftState) {
        settingsDraftState = settingsDraftState.update()
    }

    fun replaceSettingsDraftFrom(settings: GatewaySettings) {
        settingsDraftState = GatewaySettingsDraftState.fromSettings(settings)
    }

    fun permissionState(
        microphoneGranted: Boolean,
        cameraGranted: Boolean,
        notificationsGranted: Boolean
    ): GatewayPermissionState {
        return GatewayPermissionState(
            microphoneGranted = microphoneGranted,
            cameraGranted = cameraGranted,
            notificationsGranted = notificationsGranted
        )
    }

    fun cameraCaptureState(active: Boolean, previewVisible: Boolean): CameraCaptureState {
        return CameraCaptureState(
            enabled = settings.cameraGestureEnabled,
            active = active,
            previewVisible = previewVisible
        )
    }

    fun whisperAuthState(currentToken: String, currentEndpoint: String): WhisperAuthState {
        val trimmedToken = currentToken.trim()
        val defaultToken = settings.whisperAuthToken.trim()
        return WhisperAuthState(
            configured = trimmedToken.isNotBlank(),
            usingSeedFallback = trimmedToken.isNotBlank() && trimmedToken == defaultToken,
            endpoint = currentEndpoint.trim().ifBlank { settings.whisperUrl }
        )
    }

    fun gestureGateState(
        permissionsGranted: Boolean,
        cameraActive: Boolean,
        gateOpen: Boolean,
        lastStatus: String
    ): GestureGateState {
        val phase = when {
            !settings.cameraGestureEnabled -> GestureGatePhase.DISABLED
            !permissionsGranted -> GestureGatePhase.WAITING_FOR_PERMISSION
            !cameraActive -> GestureGatePhase.WAITING_FOR_CAMERA
            gateOpen -> GestureGatePhase.MICROPHONE_OPEN
            else -> GestureGatePhase.WAITING_FOR_GESTURE
        }
        return GestureGateState(
            phase = phase,
            gateOpen = gateOpen,
            statusText = lastStatus
        )
    }
}
