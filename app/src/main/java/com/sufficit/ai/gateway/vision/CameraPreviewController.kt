package com.sufficit.ai.gateway.vision

import androidx.camera.view.PreviewView

/**
 * Preview-only facade over [MediaPipeCameraGestureRecognizer]. Preview and
 * background capture still share one CameraX session inside the recognizer
 * (see its bindUseCases) - this type only narrows the API surface exposed
 * to preview-concerned callers instead of duplicating the CameraX binding.
 */
class CameraPreviewController(private val recognizer: MediaPipeCameraGestureRecognizer) {
    fun ensurePreviewView(): PreviewView = recognizer.ensurePreviewView()

    fun previewViewOrNull(): PreviewView? = recognizer.previewViewOrNull()
}
