package com.sufficit.ai.gateway.state

sealed interface GatewayUiCommand {
    data object LaunchMicrophonePermission : GatewayUiCommand

    data object LaunchNotificationPermission : GatewayUiCommand

    data object LaunchCameraPermission : GatewayUiCommand

    data class StartListening(
        val statusText: String
    ) : GatewayUiCommand

    data class UpdateRuntimeStatus(
        val statusText: String
    ) : GatewayUiCommand

    data object OpenNotificationSettingsForService : GatewayUiCommand

    data object OpenNotificationSettingsPersistent : GatewayUiCommand

    data object OpenNotificationSettingsDenied : GatewayUiCommand

    data class HandleDisabledCameraGestureState(
        val statusReason: String
    ) : GatewayUiCommand

    data class HandlePendingCameraPermissionState(
        val pendingReason: String
    ) : GatewayUiCommand

    data class HandleCameraPermissionResult(
        val granted: Boolean,
        val cameraGestureEnabled: Boolean,
        val pendingCameraGestureStart: Boolean,
        val isGestureDebugPageVisible: Boolean
    ) : GatewayUiCommand

    data class StartCameraGestureCapture(
        val previewVisible: Boolean
    ) : GatewayUiCommand

    data object StopGestureDebugCamera : GatewayUiCommand
}
