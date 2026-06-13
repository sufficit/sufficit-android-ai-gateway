package com.sufficit.ai.gateway.vision

/**
 * Ponto de landmark normalizado (0..1 em x/y, z relativo ao pulso;
 * z negativo significa mais perto da camera).
 */
data class HandPoint(
    val x: Float,
    val y: Float,
    val z: Float
)

data class TrackedHand(
    val handedness: String?,
    val points: List<HandPoint>
)

/**
 * Snapshot de um quadro de rastreamento de maos para renderizacao do overlay.
 * As coordenadas sao normalizadas em relacao a imagem analisada (ja rotacionada
 * para orientacao de exibicao). [mirrored] indica preview espelhado (camera frontal).
 */
data class HandTrackingFrame(
    val hands: List<TrackedHand>,
    val imageWidth: Int,
    val imageHeight: Int,
    val mirrored: Boolean,
    val timestampMs: Long
)
