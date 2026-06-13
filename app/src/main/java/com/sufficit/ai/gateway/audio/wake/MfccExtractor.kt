package com.sufficit.ai.gateway.audio.wake

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Extrator MFCC classico (sem rede neural): janela de Hamming + FFT +
 * banco de filtros mel + DCT. Usado para comparar a fala ao vivo com os
 * templates pre-gravados da palavra de ativacao.
 */
class MfccExtractor(
    sampleRateHz: Int = 16_000,
    val frameLength: Int = 400,
    val hopLength: Int = 160,
    private val fftSize: Int = 512,
    melBands: Int = 26,
    val coefficientCount: Int = 12
) {
    private val bins = fftSize / 2 + 1
    private val window = FloatArray(frameLength) { i ->
        (0.54 - 0.46 * cos(2.0 * PI * i / (frameLength - 1))).toFloat()
    }
    private val melFilters: Array<FloatArray>
    private val dctTable: Array<FloatArray>

    private val real = FloatArray(fftSize)
    private val imag = FloatArray(fftSize)
    private val power = FloatArray(bins)
    private val melEnergies = FloatArray(melBands)

    init {
        melFilters = buildMelFilterBank(sampleRateHz, melBands)
        // DCT-II para c1..cN (c0 descartado: carrega so o nivel de energia,
        // que ja e removido pela normalizacao cepstral).
        dctTable = Array(coefficientCount) { k ->
            FloatArray(melBands) { m ->
                cos(PI * (k + 1) * (m + 0.5) / melBands).toFloat()
            }
        }
    }

    /** Energia RMS do frame, util para recorte por silencio. */
    fun frameRms(samples: FloatArray, offset: Int): Float {
        var sum = 0.0
        for (i in 0 until frameLength) {
            val s = samples[offset + i]
            sum += s * s
        }
        return sqrt(sum / frameLength).toFloat()
    }

    /** Calcula os MFCCs de um frame iniciado em [offset]. */
    fun extract(samples: FloatArray, offset: Int): FloatArray {
        for (i in 0 until frameLength) {
            real[i] = samples[offset + i] * window[i]
        }
        java.util.Arrays.fill(real, frameLength, fftSize, 0f)
        java.util.Arrays.fill(imag, 0f)
        fft(real, imag)
        for (i in 0 until bins) {
            power[i] = real[i] * real[i] + imag[i] * imag[i]
        }
        for (m in melEnergies.indices) {
            var acc = 0f
            val filter = melFilters[m]
            for (i in 0 until bins) {
                acc += filter[i] * power[i]
            }
            melEnergies[m] = ln(acc.coerceAtLeast(1e-10f))
        }
        val coeffs = FloatArray(coefficientCount)
        for (k in 0 until coefficientCount) {
            var acc = 0f
            val row = dctTable[k]
            for (m in melEnergies.indices) {
                acc += row[m] * melEnergies[m]
            }
            coeffs[k] = acc
        }
        return coeffs
    }

    private fun buildMelFilterBank(sampleRateHz: Int, melBands: Int): Array<FloatArray> {
        val lowHz = 80.0
        val highHz = minOf(7_600.0, sampleRateHz / 2.0)
        val lowMel = hzToMel(lowHz)
        val highMel = hzToMel(highHz)
        val centers = DoubleArray(melBands + 2) { i ->
            melToHz(lowMel + (highMel - lowMel) * i / (melBands + 1))
        }
        val binHz = sampleRateHz.toDouble() / fftSize
        return Array(melBands) { m ->
            val left = centers[m]
            val center = centers[m + 1]
            val right = centers[m + 2]
            FloatArray(bins) { i ->
                val hz = i * binHz
                when {
                    hz <= left || hz >= right -> 0f
                    hz <= center -> ((hz - left) / (center - left)).toFloat()
                    else -> ((right - hz) / (right - center)).toFloat()
                }
            }
        }
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)

    private fun melToHz(mel: Double): Double = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

    // FFT radix-2 iterativa, in-place.
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wRe = cos(angle).toFloat()
            val wIm = kotlin.math.sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
