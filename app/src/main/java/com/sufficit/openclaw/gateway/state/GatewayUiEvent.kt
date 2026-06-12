package com.sufficit.openclaw.gateway.state

sealed interface GatewayUiEvent {
    data class AutoStartTriggered(
        val autoStartEnabled: Boolean,
        val hasMicrophonePermission: Boolean,
        val hasNotificationPermission: Boolean,
        val hasNotificationRuntimePermission: Boolean
    ) : GatewayUiEvent

    data class StartForegroundListeningRequested(
        val hasMicrophonePermission: Boolean,
        val hasNotificationPermission: Boolean,
        val hasNotificationRuntimePermission: Boolean,
        val statusText: String = "Iniciando escuta..."
    ) : GatewayUiEvent

    data class MicrophonePermissionResult(
        val granted: Boolean,
        val hasNotificationPermission: Boolean,
        val autoStartEnabled: Boolean
    ) : GatewayUiEvent

    data class NotificationPermissionResult(
        val granted: Boolean,
        val hasMicrophonePermission: Boolean,
        val notificationPermissionFullyGranted: Boolean
    ) : GatewayUiEvent

    data class CameraPermissionResult(
        val granted: Boolean,
        val cameraGestureEnabled: Boolean,
        val isGestureDebugPageVisible: Boolean
    ) : GatewayUiEvent

    data class CameraPolicyChanged(
        val cameraGestureEnabled: Boolean,
        val hasCameraPermission: Boolean,
        val isGestureDebugPageVisible: Boolean
    ) : GatewayUiEvent

    data class StartCameraGestureCaptureRequested(
        val previewVisible: Boolean
    ) : GatewayUiEvent

    data object StopCameraGestureDebugRequested : GatewayUiEvent

    data class PendingServiceStartChanged(
        val value: Boolean
    ) : GatewayUiEvent

    data class PendingCameraGestureStartChanged(
        val value: Boolean
    ) : GatewayUiEvent
}
