package com.sufficit.openclaw.gateway.audio.wake

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WakeWordConfig(
    val enabled: Boolean = true,
    val threshold: Double = DEFAULT_THRESHOLD,
    val autoThreshold: Boolean = true
) {
    companion object {
        // Escala da distancia cosseno DTW normalizada (0..~1).
        const val DEFAULT_THRESHOLD = 0.18
    }
}

/**
 * Persistencia da palavra de ativacao: amostras PCM 16kHz mono 16-bit
 * gravadas pelo usuario + configuracao (habilitado, limiar de distancia).
 * Independente do GatewaySettings para manter o recurso autocontido.
 */
class WakeWordStore(context: Context) {

    private val dir = File(context.filesDir, "wake_word")
    private val configFile = File(dir, "config.json")

    private fun ensureDir(): File {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun loadConfig(): WakeWordConfig {
        return runCatching {
            val json = JSONObject(configFile.readText())
            WakeWordConfig(
                enabled = json.optBoolean("enabled", true),
                threshold = json.optDouble("threshold", WakeWordConfig.DEFAULT_THRESHOLD),
                autoThreshold = json.optBoolean("autoThreshold", true)
            )
        }.getOrDefault(WakeWordConfig())
    }

    fun saveConfig(config: WakeWordConfig) {
        ensureDir()
        val json = JSONObject()
            .put("enabled", config.enabled)
            .put("threshold", config.threshold)
            .put("autoThreshold", config.autoThreshold)
        configFile.writeText(json.toString())
    }

    private fun sampleFiles(): List<File> =
        dir.listFiles { file -> file.name.startsWith("sample_") && file.extension == "pcm" }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun sampleCount(): Int = sampleFiles().size

    fun loadSamples(): List<ShortArray> = sampleFiles().mapNotNull { file ->
        runCatching {
            val bytes = file.readBytes()
            val shorts = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            shorts
        }.getOrNull()
    }

    fun saveSample(samples: ShortArray): Boolean {
        ensureDir()
        if (sampleCount() >= MAX_SAMPLES) {
            sampleFiles().firstOrNull()?.delete()
        }
        val file = File(dir, "sample_${System.currentTimeMillis()}.pcm")
        return runCatching {
            val bytes = ByteArray(samples.size * 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
            file.writeBytes(bytes)
            true
        }.getOrDefault(false)
    }

    fun clearSamples() {
        sampleFiles().forEach { it.delete() }
    }

    companion object {
        const val MAX_SAMPLES = 5
    }
}
