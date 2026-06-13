package com.sufficit.ai.gateway.transcription.local

import android.content.Context
import android.util.Log
import com.sufficit.ai.gateway.transcription.WhisperTranscriptionResult
import java.io.File

class LocalWhisperEngine(private val context: Context) : AutoCloseable {
    private var contextHandle: Long = 0L
    private var loadedModelPath: String? = null
    private var loadedUseGpu: Boolean? = null

    fun transcribePcm16(
        pcmBytes: ByteArray,
        modelPath: String,
        useGpu: Boolean,
        language: String
    ): WhisperTranscriptionResult {
        val resolvedModelPath = resolveModelPath(modelPath)
        val modelFile = File(resolvedModelPath)
        require(modelFile.exists()) { "Modelo local nao encontrado em $resolvedModelPath" }
        val modelFileSize = modelFile.length()
        check(modelFileSize > 0L) { "Modelo local vazio: ${modelFile.name}" }
        if (
            useGpu &&
            modelFile.name.equals("ggml-large-v3-turbo-q5_0.bin", ignoreCase = true)
        ) {
            throw IllegalStateException(
                "GPU local com ggml-large-v3-turbo-q5_0.bin costuma falhar com DeviceLost neste aparelho. " +
                    "Use um modelo menor para GPU (ex.: ggml-small-q5_1.bin)."
            )
        }
        if (
            modelFile.name.equals("ggml-large-v3-turbo-q5_0.bin", ignoreCase = true) &&
            modelFileSize < 500_000_000L
        ) {
            throw IllegalStateException(
                "Modelo parece incompleto (${modelFileSize} bytes). Rebaixe o arquivo ${modelFile.name}."
            )
        }
        validateModelProfile(modelFile, useGpu)

        val audioData = pcm16ToFloatArray(pcmBytes)
        val text = transcribeWithContext(
            modelPath = resolvedModelPath,
            useGpu = useGpu,
            language = language,
            audioData = audioData
        )

        return WhisperTranscriptionResult(text = text)
    }

    private fun transcribeWithContext(
        modelPath: String,
        useGpu: Boolean,
        language: String,
        audioData: FloatArray
    ): String {
        ensureContext(modelPath, useGpu)
        val threads = if (useGpu) GPU_THREADS else CPU_THREADS
        LocalWhisperLib.fullTranscribe(contextHandle, threads, language, audioData)
        return buildString {
            val segmentCount = LocalWhisperLib.getTextSegmentCount(contextHandle)
            for (index in 0 until segmentCount) {
                append(LocalWhisperLib.getTextSegment(contextHandle, index))
            }
        }.trim()
    }

    @Synchronized
    private fun resetContext() {
        if (contextHandle != 0L) {
            runCatching { LocalWhisperLib.freeContext(contextHandle) }
                .onFailure { error ->
                    Log.w(TAG, "Failed to free Whisper context cleanly: ${error.message}", error)
                }
            contextHandle = 0L
            loadedModelPath = null
            loadedUseGpu = null
        }
    }

    @Synchronized
    private fun ensureContext(modelPath: String, useGpu: Boolean) {
        if (contextHandle != 0L && loadedModelPath == modelPath && loadedUseGpu == useGpu) {
            return
        }

        resetContext()

        contextHandle = LocalWhisperLib.initContext(
            modelPath = modelPath,
            useGpu = useGpu,
            flashAttn = false,
            gpuDevice = 0
        )
        check(contextHandle != 0L) {
            "Falha ao inicializar Whisper local com ${if (useGpu) "GPU" else "CPU"}"
        }
        loadedModelPath = modelPath
        loadedUseGpu = useGpu
    }

    private fun resolveModelPath(modelPath: String): String {
        if (modelPath.startsWith("/")) {
            return modelPath
        }

        return File(context.filesDir, modelPath).absolutePath
    }

    private fun validateModelProfile(modelFile: File, useGpu: Boolean) {
        val normalized = modelFile.name.lowercase()
        if (useGpu && normalized == "ggml-tiny.bin") {
            return
        }
        check(normalized.contains("q") || normalized.contains("int")) {
            "Modelo local precisa ser quantizado (q*/int*). Arquivo atual: ${modelFile.name}"
        }
    }

    private fun pcm16ToFloatArray(pcmBytes: ByteArray): FloatArray {
        val sampleCount = pcmBytes.size / 2
        val result = FloatArray(sampleCount)
        var byteIndex = 0
        for (sampleIndex in 0 until sampleCount) {
            val low = pcmBytes[byteIndex].toInt() and 0xFF
            val high = pcmBytes[byteIndex + 1].toInt()
            val value = (high shl 8) or low
            result[sampleIndex] = value.toShort() / 32768f
            byteIndex += 2
        }
        return result
    }

    override fun close() {
        resetContext()
    }

    companion object {
        private const val TAG = "LocalWhisperEngine"
        private const val CPU_THREADS = 4
        private const val GPU_THREADS = 1

        fun systemInfo(): String = LocalWhisperLib.getSystemInfo()

        fun vulkanSummary(): String {
            val count = LocalWhisperLib.getVulkanDeviceCount()
            if (count <= 0) {
                return "Vulkan ggml sem dispositivos disponiveis."
            }

            return buildString {
                append("Vulkan ggml devices=$count")
                repeat(count) { index ->
                    append(" | #")
                    append(index)
                    append(": ")
                    append(LocalWhisperLib.getVulkanDeviceDescription(index))
                }
            }
        }
    }
}
