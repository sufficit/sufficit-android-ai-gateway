package com.sufficit.ai.gateway.transcription.local

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.sufficit.ai.gateway.config.LocalExecutionMode
import com.sufficit.ai.gateway.config.LocalModelCatalog
import com.sufficit.ai.gateway.transcription.WhisperTranscriptionResult

class LocalSherpaOnnxEngine(private val context: Context) : AutoCloseable {
    private var recognizer: OfflineRecognizer? = null
    private var loadedModelPath: String? = null
    private var loadedExecutionMode: LocalExecutionMode? = null

    fun transcribePcm16(
        pcmBytes: ByteArray,
        modelPath: String,
        executionMode: LocalExecutionMode,
        language: String
    ): WhisperTranscriptionResult {
        require(pcmBytes.isNotEmpty()) { "Audio PCM vazio para transcricao local." }
        val bundle = requireNotNull(LocalModelCatalog.findByPath(modelPath)) {
            "Modelo local do sherpa-onnx nao reconhecido: $modelPath"
        }
        check(bundle.isInstalled(context)) {
            "Pacote local incompleto para ${bundle.id}. Arquivos obrigatorios: ${bundle.requiredFiles.joinToString(", ")}"
        }

        ensureRecognizer(bundle.id, executionMode, language)
        val samples = pcm16ToFloatArray(pcmBytes)
        val resultText = recognizer!!.createStream().let { stream ->
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                recognizer!!.decode(stream)
                recognizer!!.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }

        return WhisperTranscriptionResult(text = resultText)
    }

    fun inspectModel(modelPath: String, executionMode: LocalExecutionMode): String {
        val bundle = LocalModelCatalog.findByPath(modelPath)
            ?: return "Modelo local nao mapeado para sherpa-onnx."
        val files = bundle.resolveRequiredFiles(context)
        val status = files.joinToString(" | ") { file ->
            "${file.name}=${if (file.exists()) file.length() else 0}B"
        }
        return "sherpa-onnx ${bundle.id} (${executionMode.persistedValue.uppercase()}) | $status"
    }

    @Synchronized
    private fun ensureRecognizer(
        bundleId: String,
        executionMode: LocalExecutionMode,
        language: String
    ) {
        if (recognizer != null && loadedModelPath == bundleId && loadedExecutionMode == executionMode) {
            return
        }

        close()

        val bundle = requireNotNull(LocalModelCatalog.findById(bundleId))
        val files = bundle.resolveRequiredFiles(context)
        val provider = when (executionMode) {
            LocalExecutionMode.CPU -> "cpu"
            LocalExecutionMode.NNAPI -> "nnapi"
        }
        val normalizedLanguage = if (bundle.language == "en") "en" else language.ifBlank { bundle.language }
        val config = OfflineRecognizerConfig(
            featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = files[0].absolutePath,
                    decoder = files[1].absolutePath,
                    language = normalizedLanguage,
                    task = "transcribe"
                ),
                tokens = files[2].absolutePath,
                numThreads = NUM_THREADS,
                provider = provider,
                modelType = "whisper"
            )
        )

        recognizer = OfflineRecognizer(assetManager = null, config = config)
        loadedModelPath = bundle.id
        loadedExecutionMode = executionMode
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

    @Synchronized
    override fun close() {
        recognizer?.release()
        recognizer = null
        loadedModelPath = null
        loadedExecutionMode = null
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val NUM_THREADS = 4

        fun systemInfo(
            context: Context,
            modelPath: String,
            executionMode: LocalExecutionMode
        ): String {
            return runCatching {
                LocalSherpaOnnxEngine(context).use { engine ->
                    engine.inspectModel(modelPath, executionMode)
                }
            }.getOrElse { error ->
                error.message ?: error.javaClass.simpleName
            }
        }
    }
}
