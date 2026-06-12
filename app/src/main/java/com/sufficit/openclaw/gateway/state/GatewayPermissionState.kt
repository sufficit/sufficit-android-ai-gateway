package com.sufficit.openclaw.gateway.state

/**
 * Lifecycle-aware snapshot of Android runtime permissions required by the gateway.
 */
data class GatewayPermissionState(
    val microphoneGranted: Boolean,
    val cameraGranted: Boolean,
    val notificationsGranted: Boolean
)
