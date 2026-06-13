package com.sufficit.ai.gateway.audio.speaker

import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlin.math.sqrt

/**
 * Extrai embeddings de locutor (modelo CAM++ via sherpa-onnx) e calcula a
 * similaridade cosseno entre uma fala e o perfil de voz cadastrado.
 *
 * Verificacao de locutor e (quase) independente de idioma e conteudo: o
 * embedding captura timbre/trato vocal, nao palavras. Custo por segmento:
 * ~100-300ms de CPU no A51 — por isso roda no executor de transcricao,
 * nunca na thread de captura de audio.
 *
 * Thread-safety: usado apenas pelo executor de transcricao (single-thread).
 */
class SpeakerVerifier(private val modelPath: String) : AutoCloseable {

    private var extractor: SpeakerEmbeddingExtractor? = null

    private fun obtainExtractor(): SpeakerEmbeddingExtractor? {
        extractor?.let { return it }
        return try {
            SpeakerEmbeddingExtractor(
                null,
                SpeakerEmbeddingExtractorConfig(
                    modelPath,
                    2,
                    false,
                    "cpu"
                )
            ).also { extractor = it }
        } catch (ex: Throwable) {
            Log.e(TAG, "Falha ao iniciar extrator de embeddings de locutor.", ex)
            null
        }
    }

    /**
     * Extrai o embedding de um trecho PCM16 mono. Retorna null se o modelo
     * nao carregar ou o trecho for curto demais para o modelo.
     */
    fun embed(pcmBytes: ByteArray, sampleRateHz: Int): FloatArray? {
        val ext = obtainExtractor() ?: return null
        val samples = FloatArray(pcmBytes.size / 2) { i ->
            val lo = pcmBytes[i * 2].toInt() and 0xFF
            val hi = pcmBytes[i * 2 + 1].toInt()
            ((hi shl 8) or lo).toShort() / 32768f
        }
        if (samples.size < sampleRateHz / 2) {
            // Menos de 0.5s de audio nao gera embedding confiavel.
            return null
        }
        return try {
            val stream = ext.createStream()
            stream.acceptWaveform(samples, sampleRateHz)
            stream.inputFinished()
            val embedding = if (ext.isReady(stream)) ext.compute(stream) else null
            stream.release()
            embedding
        } catch (ex: Throwable) {
            Log.e(TAG, "Falha ao extrair embedding de locutor.", ex)
            null
        }
    }

    override fun close() {
        runCatching { extractor?.release() }
        extractor = null
    }

    companion object {
        private const val TAG = "SpeakerVerifier"

        /** Similaridade cosseno entre dois embeddings (-1..1; maior = mais parecido). */
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
            if (a.size != b.size || a.isEmpty()) return 0.0
            var dot = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in a.indices) {
                dot += a[i].toDouble() * b[i]
                normA += a[i].toDouble() * a[i]
                normB += b[i].toDouble() * b[i]
            }
            val denom = sqrt(normA) * sqrt(normB)
            return if (denom > 1e-9) dot / denom else 0.0
        }
    }
}
