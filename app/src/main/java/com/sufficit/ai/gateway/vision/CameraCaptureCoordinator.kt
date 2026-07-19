package com.sufficit.ai.gateway.vision

/**
 * Capture-only facade over [MediaPipeCameraGestureRecognizer]. Capture and
 * preview still share one CameraX session inside the recognizer (see its
 * bindUseCases) - this type only narrows the API surface exposed to
 * capture-concerned callers instead of duplicating the CameraX binding.
 */
class CameraCaptureCoordinator(private val recognizer: MediaPipeCameraGestureRecognizer) {
    fun start(previewVisible: Boolean, onEvent: (CameraGestureEvent) -> Unit) {
        recognizer.start(previewVisible, onEvent)
    }
}
