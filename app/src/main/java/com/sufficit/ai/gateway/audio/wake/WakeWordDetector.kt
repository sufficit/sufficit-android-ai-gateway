package com.sufficit.ai.gateway.audio.wake

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class WakeWordFeedResult(
    val distance: Double?,
    val matched: Boolean
)

/**
 * Detector de palavra de ativacao sem rede neural: compara o fluxo de
 * audio (MFCC) com templates pre-gravados via DTW de subsequencia.
 * Distancia normalizada abaixo do limiar = palavra reconhecida.
 *
 * Uso: chamado apenas pela thread de captura do servico de audio.
 */
class WakeWordDetector(
    private val mfcc: MfccExtractor = MfccExtractor()
) {

    private class Template(val frames: Array<FloatArray>)

    private var templates: List<Template> = emptyList()
    private var threshold: Double = WakeWordConfig.DEFAULT_THRESHOLD
    private var maxTemplateLength = 0

    // Buffer de amostras pendentes (resto entre frames de hop).
    private var pending = FloatArray(0)
    private var pendingCount = 0

    // Anel de MFCCs + energia por frame do fluxo ao vivo.
    private val ringCapacity = 400
    private val ringFrames = arrayOfNulls<FloatArray>(ringCapacity)
    private val ringEnergy = FloatArray(ringCapacity)
    private var ringStart = 0
    private var ringSize = 0

    private var lastMatchAtMs = 0L
    private var matchStreak = 0

    val hasTemplates: Boolean
        get() = templates.isNotEmpty()

    /** Retorna quantas amostras geraram templates validos. */
    fun configure(samples: List<ShortArray>, threshold: Double): Int {
        this.threshold = threshold
        templates = samples.mapNotNull { prepareTemplate(it) }
        maxTemplateLength = templates.maxOfOrNull { it.frames.size } ?: 0
        reset()
        return templates.size
    }

    fun reset() {
        ringStart = 0
        ringSize = 0
        pendingCount = 0
        matchStreak = 0
    }

    /**
     * Consome um chunk PCM e roda uma checagem de match. Retorna a menor
     * distancia DTW observada (para depuracao/ajuste de limiar).
     */
    fun feed(buffer: ShortArray, count: Int, nowMs: Long): WakeWordFeedResult {
        if (templates.isEmpty()) {
            return WakeWordFeedResult(distance = null, matched = false)
        }
        appendSamples(buffer, count)
        processPendingFrames()

        if (ringSize < minOf(maxTemplateLength, MIN_MATCH_FRAMES)) {
            matchStreak = 0
            return WakeWordFeedResult(distance = null, matched = false)
        }
        // Span energetico CONTIGUO: a palavra falada dura ~o tamanho do
        // template; ruidos (toque, clique, batida, estalo) sao transientes
        // curtos. Exigir um trecho energetico continuo minimo elimina esses
        // falsos positivos ("qualquer ruido virava wake word").
        val requiredSpan = max(MIN_MATCH_FRAMES, (maxTemplateLength * MIN_SPAN_FRACTION).toInt())
        if (longestEnergeticSpan() < requiredSpan) {
            matchStreak = 0
            return WakeWordFeedResult(distance = null, matched = false)
        }

        val distance = bestDistance()
        val below = distance != null && distance < threshold
        // Confirmacao por streak: a palavra real fica no anel por varias
        // janelas consecutivas; um pico de ruido raramente repete.
        matchStreak = if (below) matchStreak + 1 else 0
        val matched = matchStreak >= MATCH_CONFIRM_STREAK &&
            nowMs - lastMatchAtMs >= REFRACTORY_MS
        if (matched) {
            lastMatchAtMs = nowMs
            matchStreak = 0
            // Evita rematch imediato com a mesma fala ainda no anel.
            ringSize = 0
            ringStart = 0
        }
        return WakeWordFeedResult(distance = distance, matched = matched)
    }

    /** Maior sequencia CONTIGUA de frames energeticos na janela recente. */
    private fun longestEnergeticSpan(): Int {
        val lookBack = min(ringSize, maxTemplateLength + ENERGY_LOOKBACK_SLACK)
        var best = 0
        var run = 0
        for (i in 0 until lookBack) {
            val index = (ringStart + ringSize - lookBack + i + ringCapacity) % ringCapacity
            if (ringEnergy[index] >= MIN_SPEECH_FRAME_RMS) {
                run += 1
                if (run > best) best = run
            } else {
                run = 0
            }
        }
        return best
    }

    private fun appendSamples(buffer: ShortArray, count: Int) {
        val needed = pendingCount + count
        if (pending.size < needed) {
            pending = pending.copyOf(max(needed, pending.size * 2 + 1024))
        }
        for (i in 0 until count) {
            pending[pendingCount + i] = buffer[i] / 32768f
        }
        pendingCount = needed
    }

    private fun processPendingFrames() {
        var offset = 0
        while (offset + mfcc.frameLength <= pendingCount) {
            pushFrame(mfcc.extract(pending, offset), mfcc.frameRms(pending, offset))
            offset += mfcc.hopLength
        }
        if (offset > 0) {
            System.arraycopy(pending, offset, pending, 0, pendingCount - offset)
            pendingCount -= offset
        }
    }

    private fun pushFrame(coeffs: FloatArray, energy: Float) {
        val index = (ringStart + ringSize) % ringCapacity
        ringFrames[index] = coeffs
        ringEnergy[index] = energy
        if (ringSize < ringCapacity) {
            ringSize += 1
        } else {
            ringStart = (ringStart + 1) % ringCapacity
        }
    }

    private fun bestDistance(): Double? {
        val windowLength = min(ringSize, (maxTemplateLength * 1.6f).toInt() + QUERY_WINDOW_SLACK)
        if (windowLength < MIN_MATCH_FRAMES) {
            return null
        }
        val window = Array(windowLength) { i ->
            val index = (ringStart + ringSize - windowLength + i) % ringCapacity
            ringFrames[index]!!
        }
        var best: Double? = null
        for (template in templates) {
            val distance = subsequenceDtw(template.frames, window)
            if (best == null || distance < best) {
                best = distance
            }
        }
        return best
    }

    /**
     * Limiar sugerido a partir da distancia DTW entre as proprias amostras
     * (quanto o usuario varia ao falar a mesma palavra), com folga.
     */
    fun suggestedThreshold(): Double? {
        if (templates.size < 2) return null
        var sum = 0.0
        var count = 0
        for (i in templates.indices) {
            for (j in templates.indices) {
                if (i == j) continue
                sum += subsequenceDtw(templates[i].frames, templates[j].frames)
                count += 1
            }
        }
        if (count == 0) return null
        return ((sum / count) * SUGGESTED_THRESHOLD_FACTOR).coerceIn(0.04, 0.45)
    }

    /**
     * DTW de subsequencia: o template pode casar terminando em qualquer
     * ponto da janela (inicio livre, fim livre). Distancia normalizada
     * pelo tamanho do template.
     */
    private fun subsequenceDtw(template: Array<FloatArray>, query: Array<FloatArray>): Double {
        val n = template.size
        val m = query.size
        var previous = DoubleArray(m + 1)
        var current = DoubleArray(m + 1)
        // Linha 0: inicio livre na query.
        for (j in 0..m) {
            previous[j] = 0.0
        }
        for (i in 1..n) {
            current[0] = Double.MAX_VALUE / 2
            for (j in 1..m) {
                val cost = cosineDistance(template[i - 1], query[j - 1])
                current[j] = cost + min(previous[j], min(current[j - 1], previous[j - 1]))
            }
            val swap = previous
            previous = current
            current = swap
        }
        var best = Double.MAX_VALUE
        for (j in 1..m) {
            if (previous[j] < best) {
                best = previous[j]
            }
        }
        return best / n
    }

    // Distancia cosseno: nas amostras reais separa palavra de ruido ~3.6x
    // melhor que euclidiana (que e dominada pelos primeiros coeficientes).
    private fun cosineDistance(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            normA += a[i].toDouble() * a[i]
            normB += b[i].toDouble() * b[i]
        }
        return 1.0 - dot / (sqrt(normA) * sqrt(normB) + 1e-9)
    }

    /**
     * Converte amostra gravada em sequencia MFCC recortada por energia.
     * Usa o maior trecho energetico continuo (pontes de ate ~100ms entre
     * silabas), descartando transientes isolados como o toque no botao.
     */
    private fun prepareTemplate(samples: ShortArray): Template? {
        if (samples.size < mfcc.frameLength) return null
        val floats = FloatArray(samples.size) { samples[it] / 32768f }
        val frames = ArrayList<FloatArray>()
        val energies = ArrayList<Float>()
        var offset = 0
        while (offset + mfcc.frameLength <= floats.size) {
            frames += mfcc.extract(floats, offset)
            energies += mfcc.frameRms(floats, offset)
            offset += mfcc.hopLength
        }
        if (frames.isEmpty()) return null

        val maxEnergy = energies.max()
        val gate = max(maxEnergy * 0.12f, MIN_SPEECH_FRAME_RMS)

        // Trechos energeticos contiguos, unindo lacunas curtas (entre silabas).
        data class Run(var first: Int, var last: Int)
        val runs = ArrayList<Run>()
        for (i in energies.indices) {
            if (energies[i] < gate) continue
            val lastRun = runs.lastOrNull()
            if (lastRun != null && i - lastRun.last <= SYLLABLE_GAP_FRAMES) {
                lastRun.last = i
            } else {
                runs += Run(i, i)
            }
        }
        val bestRun = runs.maxByOrNull { it.last - it.first } ?: return null

        val first = max(0, bestRun.first - TRIM_MARGIN_FRAMES)
        val last = min(frames.size - 1, bestRun.last + TRIM_MARGIN_FRAMES)
        val trimmed = frames.subList(first, last + 1).toTypedArray()
        if (trimmed.size !in MIN_TEMPLATE_FRAMES..MAX_TEMPLATE_FRAMES) return null
        return Template(trimmed)
    }

    companion object {
        private const val REFRACTORY_MS = 3_000L
        private const val MIN_SPEECH_FRAME_RMS = 0.01f
        private const val MIN_MATCH_FRAMES = 20
        private const val MIN_TEMPLATE_FRAMES = 20
        private const val MAX_TEMPLATE_FRAMES = 250
        private const val TRIM_MARGIN_FRAMES = 3
        private const val SYLLABLE_GAP_FRAMES = 20
        private const val SUGGESTED_THRESHOLD_FACTOR = 1.6
        private const val ENERGY_LOOKBACK_SLACK = 30
        private const val QUERY_WINDOW_SLACK = 30
        // Fracao do tamanho do template que o trecho energetico contiguo precisa
        // ter (anti ruido transiente). Confirmacao por janelas consecutivas.
        private const val MIN_SPAN_FRACTION = 0.6f
        private const val MATCH_CONFIRM_STREAK = 2
    }
}
