package com.sufficit.openclaw.gateway.config

import android.content.Context
import java.io.File
import java.util.Locale

data class LocalModelBundle(
    val id: String,
    val repoId: String,
    val requiredFiles: List<String>,
    val language: String
) {
    fun modelDir(context: Context): File = File(File(context.filesDir, "models"), id)

    fun resolveRequiredFiles(context: Context): List<File> {
        val dir = modelDir(context)
        return requiredFiles.map { File(dir, it) }
    }

    fun isInstalled(context: Context): Boolean {
        return resolveRequiredFiles(context).all { it.exists() && it.length() > 0L }
    }
}

object LocalModelCatalog {
    val bundles: List<LocalModelBundle> = listOf(
        LocalModelBundle(
            id = "sherpa-whisper-base",
            repoId = "csukuangfj/sherpa-onnx-whisper-base",
            requiredFiles = listOf(
                "base-encoder.int8.onnx",
                "base-decoder.int8.onnx",
                "base-tokens.txt"
            ),
            language = "pt"
        ),
        LocalModelBundle(
            id = "sherpa-whisper-small",
            repoId = "csukuangfj/sherpa-onnx-whisper-small",
            requiredFiles = listOf(
                "small-encoder.int8.onnx",
                "small-decoder.int8.onnx",
                "small-tokens.txt"
            ),
            language = "pt"
        ),
        LocalModelBundle(
            id = "sherpa-whisper-medium",
            repoId = "csukuangfj/sherpa-onnx-whisper-medium",
            requiredFiles = listOf(
                "medium-encoder.int8.onnx",
                "medium-decoder.int8.onnx",
                "medium-tokens.txt"
            ),
            language = "pt"
        ),
        LocalModelBundle(
            id = "sherpa-whisper-turbo",
            repoId = "csukuangfj/sherpa-onnx-whisper-turbo",
            requiredFiles = listOf(
                "turbo-encoder.int8.onnx",
                "turbo-decoder.int8.onnx",
                "turbo-tokens.txt"
            ),
            language = "pt"
        ),
        LocalModelBundle(
            id = "sherpa-whisper-tiny",
            repoId = "csukuangfj/sherpa-onnx-whisper-tiny",
            requiredFiles = listOf(
                "tiny-encoder.int8.onnx",
                "tiny-decoder.int8.onnx",
                "tiny-tokens.txt"
            ),
            language = "pt"
        ),
        LocalModelBundle(
            id = "sherpa-whisper-tiny.en",
            repoId = "csukuangfj/sherpa-onnx-whisper-tiny.en",
            requiredFiles = listOf(
                "tiny.en-encoder.int8.onnx",
                "tiny.en-decoder.int8.onnx",
                "tiny.en-tokens.txt"
            ),
            language = "en"
        )
    )

    fun findById(id: String?): LocalModelBundle? {
        val normalized = id?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (normalized.isEmpty()) {
            return null
        }
        return bundles.firstOrNull { it.id.equals(normalized, ignoreCase = true) }
    }

    fun findByPath(path: String?): LocalModelBundle? {
        val normalized = path?.trim()?.replace('\\', '/').orEmpty()
        if (normalized.isEmpty()) {
            return null
        }

        return bundles.firstOrNull { bundle ->
            normalized.endsWith("/${bundle.id}", ignoreCase = true) ||
                normalized.equals(bundle.id, ignoreCase = true)
        }
    }
}
