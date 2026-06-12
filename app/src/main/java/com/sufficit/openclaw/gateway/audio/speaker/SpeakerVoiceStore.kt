package com.sufficit.openclaw.gateway.audio.speaker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

data class SpeakerVoiceConfig(
    val enabled: Boolean = false,
    val threshold: Double = DEFAULT_THRESHOLD
) {
    companion object {
        // Similaridade cosseno minima para aceitar o segmento como sendo a
        // voz cadastrada. Faixa tipica de verificacao com CAM++: match do
        // mesmo locutor ~0.6-0.8; locutor diferente ~0.1-0.4.
        const val DEFAULT_THRESHOLD = 0.55
    }
}

/**
 * Perfil de voz do usuario para verificacao de locutor ("so a minha voz").
 *
 * Persistencia autocontida em filesDir/speaker_voice/:
 *  - config.json: habilitado + limiar de similaridade;
 *  - embeddings.json: lista de embeddings (vetores float) extraidos das
 *    falas de cadastro pelo modelo CAM++ do sherpa-onnx.
 *
 * O perfil efetivo e a MEDIA L2-normalizada dos embeddings cadastrados —
 * mais amostras (em distancias/entonacoes diferentes) = perfil mais robusto.
 */
class SpeakerVoiceStore(context: Context) {

    private val dir = File(context.filesDir, "speaker_voice")
    private val configFile = File(dir, "config.json")
    private val embeddingsFile = File(dir, "embeddings.json")

    // Modelo ONNX de embeddings de locutor (download unico, ~28MB).
    private val modelFile = File(context.filesDir, "models/speaker/$MODEL_FILE_NAME")

    fun modelFile(): File = modelFile

    fun isModelReady(): Boolean =
        modelFile.exists() && modelFile.length() > MODEL_MIN_VALID_BYTES

    private fun ensureDir(): File {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun loadConfig(): SpeakerVoiceConfig {
        return runCatching {
            val json = JSONObject(configFile.readText())
            SpeakerVoiceConfig(
                enabled = json.optBoolean("enabled", false),
                threshold = json.optDouble("threshold", SpeakerVoiceConfig.DEFAULT_THRESHOLD)
            )
        }.getOrDefault(SpeakerVoiceConfig())
    }

    fun saveConfig(config: SpeakerVoiceConfig) {
        ensureDir()
        configFile.writeText(
            JSONObject()
                .put("enabled", config.enabled)
                .put("threshold", config.threshold)
                .toString()
        )
    }

    fun loadEmbeddings(): List<FloatArray> {
        return runCatching {
            val array = JSONArray(embeddingsFile.readText())
            (0 until array.length()).map { i ->
                val inner = array.getJSONArray(i)
                FloatArray(inner.length()) { j -> inner.getDouble(j).toFloat() }
            }
        }.getOrDefault(emptyList())
    }

    fun addEmbedding(embedding: FloatArray): Int {
        ensureDir()
        val all = loadEmbeddings().toMutableList()
        all += embedding
        // Mantem um numero razoavel de amostras; a mais antiga sai.
        while (all.size > MAX_EMBEDDINGS) {
            all.removeAt(0)
        }
        val json = JSONArray()
        all.forEach { emb ->
            val inner = JSONArray()
            emb.forEach { value -> inner.put(value.toDouble()) }
            json.put(inner)
        }
        embeddingsFile.writeText(json.toString())
        return all.size
    }

    fun embeddingCount(): Int = loadEmbeddings().size

    fun clearEmbeddings() {
        embeddingsFile.delete()
    }

    /**
     * Perfil de voz efetivo: media dos embeddings cadastrados, normalizada
     * em L2 (a similaridade cosseno espera vetores comparaveis em norma).
     */
    fun meanEmbedding(): FloatArray? {
        val all = loadEmbeddings()
        if (all.isEmpty()) return null
        val dim = all.first().size
        val mean = FloatArray(dim)
        all.forEach { emb ->
            if (emb.size == dim) {
                for (i in 0 until dim) {
                    mean[i] += emb[i]
                }
            }
        }
        for (i in 0 until dim) {
            mean[i] /= all.size
        }
        var norm = 0.0
        for (value in mean) {
            norm += value.toDouble() * value
        }
        val l2 = sqrt(norm).toFloat()
        if (l2 > 1e-6f) {
            for (i in mean.indices) {
                mean[i] /= l2
            }
        }
        return mean
    }

    companion object {
        const val MODEL_FILE_NAME = "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
        const val MODEL_DOWNLOAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/$MODEL_FILE_NAME"
        const val MODEL_SIZE_BYTES = 28_281_164L
        private const val MODEL_MIN_VALID_BYTES = 20_000_000L
        private const val MAX_EMBEDDINGS = 10
    }
}
