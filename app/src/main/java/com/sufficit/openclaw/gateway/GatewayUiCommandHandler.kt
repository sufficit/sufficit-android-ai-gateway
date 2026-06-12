package com.sufficit.openclaw.gateway

import android.Manifest
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.sufficit.openclaw.gateway.runtime.GatewayRuntime
import com.sufficit.openclaw.gateway.state.GatewayUiCommand
import com.sufficit.openclaw.gateway.state.GatewayViewModel
import com.sufficit.openclaw.gateway.vision.MediaPipeCameraGestureRecognizer

@Composable
fun HandleGatewayUiCommands(
    gatewayViewModel: GatewayViewModel,
    permissionLauncher: ActivityResultLauncher<String>,
    notificationPermissionLauncher: ActivityResultLauncher<String>,
    cameraPermissionLauncher: ActivityResultLauncher<String>,
    gestureRecognizer: MediaPipeCameraGestureRecognizer,
    onStartListening: (String) -> Unit,
    onStartCameraGestureCapture: (Boolean) -> Unit,
    onStopGestureDebugCamera: () -> Unit,
) {
    val context = LocalContext.current

    LaunchedEffect(gatewayViewModel) {
        gatewayViewModel.uiCommands.collect { command ->
            when (command) {
                GatewayUiCommand.LaunchMicrophonePermission -> {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }

                GatewayUiCommand.LaunchNotificationPermission -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }

                GatewayUiCommand.LaunchCameraPermission -> {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }

                is GatewayUiCommand.StartListening -> {
                    onStartListening(command.statusText)
                }

                is GatewayUiCommand.UpdateRuntimeStatus -> {
                    GatewayRuntime.update {
                        it.copy(statusText = command.statusText)
                    }
                }

                GatewayUiCommand.OpenNotificationSettingsForService -> {
                    context.openNotificationSettings()
                    showNotificationSettingsToast(context)
                }

                GatewayUiCommand.OpenNotificationSettingsPersistent -> {
                    context.openNotificationSettings()
                    showPersistentNotificationToast(context)
                }

                GatewayUiCommand.OpenNotificationSettingsDenied -> {
                    context.openNotificationSettings()
                    showDeniedNotificationToast(context)
                }

                is GatewayUiCommand.HandleDisabledCameraGestureState -> {
                    handleDisabledCameraGestureState(
                        statusReason = command.statusReason
                    )
                }

                is GatewayUiCommand.HandlePendingCameraPermissionState -> {
                    handlePendingCameraPermissionState(
                        pendingReason = command.pendingReason,
                        requestCameraGestureStart = {
                            gatewayViewModel.onEvent(
                                com.sufficit.openclaw.gateway.state.GatewayUiEvent.PendingCameraGestureStartChanged(
                                    value = true
                                )
                            )
                        }
                    )
                }

                is GatewayUiCommand.HandleCameraPermissionResult -> {
                    handleCameraPermissionResult(
                        granted = command.granted,
                        cameraGestureEnabled = command.cameraGestureEnabled,
                        pendingCameraGestureStart = command.pendingCameraGestureStart,
                        isGestureDebugPageVisible = command.isGestureDebugPageVisible,
                        gestureRecognizer = gestureRecognizer,
                        clearPendingCameraGestureStart = {
                            gatewayViewModel.onEvent(
                                com.sufficit.openclaw.gateway.state.GatewayUiEvent.PendingCameraGestureStartChanged(
                                    value = false
                                )
                            )
                        }
                    )
                }

                is GatewayUiCommand.StartCameraGestureCapture -> {
                    onStartCameraGestureCapture(command.previewVisible)
                }

                GatewayUiCommand.StopGestureDebugCamera -> {
                    onStopGestureDebugCamera()
                }
            }
        }
    }
}
