package com.sufficit.ai.gateway.audio.wake

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class WakeWordTemplateProfile(
    val profileId: String,
    val samples: List<ShortArray>,
    val threshold: Double
)

data class WakeWordFeedResult(
    val distance: Double?,
    val matched: Boolean,
    val matchedProfileId: String? = null
)

/**
 * Detector local MFCC+DTW para varias wake words. Os templates continuam em
 * um unico extrator/buffer de audio, mas limiar, streak e comparacao sao
 * separados por perfil. Um perfil so participa da deteccao depois de gerar
 * ao menos tres templates validos.
 */
class WakeWordDetector(
    private val mfcc: MfccExtractor = MfccExtractor()
) {

    private class Template(val frames: Array<FloatArray>)

    private class ProfileTemplates(
        val profileId: String,
        val threshold: Double,
        val templates: List<Template>
    ) {
        val maxTemplateLength: Int = templates.maxOfOrNull { it.frames.size } ?: 0
        val ready: Boolean = templates.size >= WakeWordStore.REQUIRED_SAMPLES
    }

    private data class ProfileDistance(
        val profileId: String,
        val distance: Double,
        val threshold: Double
    )

    private var profiles: List<ProfileTemplates> = emptyList()
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
    private val matchStreakByProfile = HashMap<String, Int>()

    val hasTemplates: Boolean
        get() = profiles.any { it.ready }

    /** Retorna a quantidade de templates validos por perfil. */
    fun configure(profileSamples: List<WakeWordTemplateProfile>): Map<String, Int> {
        profiles = profileSamples.map { input ->
            ProfileTemplates(
                profileId = input.profileId,
                threshold = input.threshold,
                templates = input.samples.mapNotNull { prepareTemplate(it) }
            )
        }
        reset()
        return profiles.associate { it.profileId to it.templates.size }
    }

    fun reset() {
        ringStart = 0
        ringSize = 0
        pendingCount = 0
        matchStreakByProfile.clear()
    }

    /** Valida a qualidade/duracao antes de persistir uma nova chave de voz. */
    fun isValidSample(samples: ShortArray): Boolean = prepareTemplate(samples) != null

    /**
     * Consome um chunk PCM e compara a mesma janela com todos os perfis
     * prontos. A menor distancia continua disponivel para telemetria.
     */
    fun feed(buffer: ShortArray, count: Int, nowMs: Long): WakeWordFeedResult {
        val readyProfiles = profiles.filter { it.ready }
        if (readyProfiles.isEmpty()) {
            return WakeWordFeedResult(distance = null, matched = false)
        }
        appendSamples(buffer, count)
        processPendingFrames()

        if (ringSize < MIN_MATCH_FRAMES) {
            matchStreakByProfile.clear()
            return WakeWordFeedResult(distance = null, matched = false)
        }

        var bestObserved: ProfileDistance? = null
        var confirmed: ProfileDistance? = null
        val evaluated = HashSet<String>()

        readyProfiles.forEach { profile ->
            val requiredSpan = max(
                MIN_MATCH_FRAMES,
                (profile.maxTemplateLength * MIN_SPAN_FRACTION).toInt()
            )
            if (longestEnergeticSpan(profile.maxTemplateLength) < requiredSpan) {
                matchStreakByProfile[profile.profileId] = 0
                return@forEach
            }
            val distance = bestDistance(profile) ?: return@forEach
            evaluated += profile.profileId
            val result = ProfileDistance(profile.profileId, distance, profile.threshold)
            if (bestObserved == null || distance < bestObserved!!.distance) bestObserved = result

            val streak = if (distance < profile.threshold) {
                (matchStreakByProfile[profile.profileId] ?: 0) + 1
            } else {
                0
            }
            matchStreakByProfile[profile.profileId] = streak
            if (streak >= MATCH_CONFIRM_STREAK) {
                val current = confirmed
                if (current == null || distance / profile.threshold < current.distance / current.threshold) {
                    confirmed = result
                }
            }
        }

        matchStreakByProfile.keys.toList().forEach { profileId ->
            if (profileId !in evaluated) matchStreakByProfile[profileId] = 0
        }

        val matchedResult = confirmed?.takeIf { nowMs - lastMatchAtMs >= REFRACTORY_MS }
        if (matchedResult != null) {
            lastMatchAtMs = nowMs
            matchStreakByProfile.clear()
            // Evita rematch imediato com a mesma fala ainda no anel.
            ringSize = 0
            ringStart = 0
        }
        return WakeWordFeedResult(
            distance = bestObserved?.distance,
            matched = matchedResult != null,
            matchedProfileId = matchedResult?.profileId
        )
    }

    /** Maior sequencia contigua de frames energeticos na janela do perfil. */
    private fun longestEnergeticSpan(profileMaxTemplateLength: Int): Int {
        val lookBack = min(ringSize, profileMaxTemplateLength + ENERGY_LOOKBACK_SLACK)
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
        for (i in 0 until count) pending[pendingCount + i] = buffer[i] / 32768f
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

    private fun bestDistance(profile: ProfileTemplates): Double? {
        val windowLength = min(
            ringSize,
            (profile.maxTemplateLength * 1.6f).toInt() + QUERY_WINDOW_SLACK
        )
        if (windowLength < MIN_MATCH_FRAMES) return null
        val window = Array(windowLength) { i ->
            val index = (ringStart + ringSize - windowLength + i) % ringCapacity
            ringFrames[index]!!
        }
        return profile.templates.minOfOrNull { subsequenceDtw(it.frames, window) }
    }

    /** Limiar sugerido calculado apenas entre amostras do mesmo termo. */
    fun suggestedThreshold(profileId: String): Double? {
        val templates = profiles.firstOrNull { it.profileId == profileId }?.templates ?: return null
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

    private fun subsequenceDtw(template: Array<FloatArray>, query: Array<FloatArray>): Double {
        val n = template.size
        val m = query.size
        var previous = DoubleArray(m + 1)
        var current = DoubleArray(m + 1)
        for (j in 0..m) previous[j] = 0.0
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
        for (j in 1..m) if (previous[j] < best) best = previous[j]
        return best / n
    }

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
        private const val MIN_SPAN_FRACTION = 0.6f
        private const val MATCH_CONFIRM_STREAK = 2
    }
}
