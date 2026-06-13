package com.sufficit.ai.gateway

import android.content.Context
import com.sufficit.ai.gateway.config.GatewaySettingsStore
import com.sufficit.ai.gateway.config.LocalModelCatalog
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

fun fixedModelsDirectoryPath(): String {
    return File(GatewaySettingsStore.DEFAULT_LOCAL_MODEL_PATH).parent
        ?: "/data/user/0/com.sufficit.ai.gateway/files/models"
}

fun resolveLocalModelTarget(context: Context, modelName: String): File {
    val normalizedName = modelName.trim().ifBlank { File(GatewaySettingsStore.DEFAULT_LOCAL_MODEL_PATH).name }
    val modelDir = File(context.filesDir, "models")
    return File(modelDir, normalizedName)
}

fun isLocalModelReady(context: Context, modelName: String): Boolean {
    val bundle = LocalModelCatalog.findById(modelName)
    if (bundle != null) {
        return bundle.isInstalled(context)
    }
    return resolveLocalModelTarget(context, modelName).exists()
}

fun downloadModelFromHuggingFace(
    context: Context,
    modelName: String,
    onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
): Result<String> {
    return runCatching {
        val bundle = LocalModelCatalog.findById(modelName)
            ?: error("Modelo local nao suportado para download automatico: $modelName")
        val modelDir = bundle.modelDir(context)
        val modelsRootDir = modelDir.parentFile
            ?: error("Nao foi possivel resolver a pasta raiz dos modelos.")
        if (!modelsRootDir.exists() && !modelsRootDir.mkdirs()) {
            error("Nao foi possivel criar a pasta de modelos: ${modelsRootDir.absolutePath}")
        }
        val stagingDir = File(modelsRootDir, ".download-${bundle.id}")
        val backupDir = File(modelsRootDir, ".backup-${bundle.id}")
        if (stagingDir.exists()) {
            stagingDir.deleteRecursively()
        }
        if (backupDir.exists()) {
            backupDir.deleteRecursively()
        }
        if (!stagingDir.mkdirs()) {
            error("Nao foi possivel criar a pasta temporaria do modelo: ${stagingDir.absolutePath}")
        }

        val remoteSizes = bundle.requiredFiles.associateWith {
            fetchHuggingFaceModelSize("${bundle.id}/$it") ?: -1L
        }
        val totalBytes = remoteSizes.values.filter { it > 0L }.sum().takeIf { it > 0L } ?: -1L
        var downloadedBytes = 0L
        onProgress(0L, totalBytes)

        bundle.requiredFiles.forEach { requiredFile ->
            val targetFile = File(stagingDir, requiredFile)
            val tempFile = File(stagingDir, "$requiredFile.part")
            if (tempFile.exists()) {
                tempFile.delete()
            }

            val connection = (URL(huggingFaceModelUrl("${bundle.id}/$requiredFile")).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 30_000
                requestMethod = "GET"
                doInput = true
            }

            connection.connect()
            if (connection.responseCode !in 200..299) {
                error("HTTP ${connection.responseCode} ao baixar $requiredFile")
            }

            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(1024 * 64)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read.toLong()
                        onProgress(downloadedBytes, totalBytes)
                    }
                    output.flush()
                }
            }
            connection.disconnect()

            val expectedSize = remoteSizes[requiredFile] ?: -1L
            if (expectedSize > 0L && tempFile.length() != expectedSize) {
                tempFile.delete()
                error("Download incompleto para $requiredFile: ${formatBytes(tempFile.length())} de ${formatBytes(expectedSize)}.")
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.delete()
                error("Nao foi possivel finalizar o arquivo baixado: ${targetFile.name}")
            }
        }

        val missingStagedFiles = bundle.requiredFiles.filterNot { requiredFile ->
            val stagedFile = File(stagingDir, requiredFile)
            stagedFile.exists() && stagedFile.length() > 0L
        }
        if (missingStagedFiles.isNotEmpty()) {
            error("Pacote temporario incompleto: ${missingStagedFiles.joinToString()}")
        }

        if (modelDir.exists()) {
            if (!modelDir.renameTo(backupDir)) {
                error("Nao foi possivel preparar a substituicao do modelo atual: ${modelDir.absolutePath}")
            }
        }

        if (!stagingDir.renameTo(modelDir)) {
            if (backupDir.exists() && !backupDir.renameTo(modelDir)) {
                error("Falha ao finalizar download e ao restaurar modelo anterior.")
            }
            error("Nao foi possivel promover o download completo para a pasta final do modelo.")
        }

        if (backupDir.exists()) {
            backupDir.deleteRecursively()
        }

        modelDir.absolutePath
    }.onFailure {
        val bundle = LocalModelCatalog.findById(modelName)
        val modelDir = bundle?.modelDir(context)
        val modelsRootDir = modelDir?.parentFile
        File(modelsRootDir, ".download-${bundle?.id.orEmpty()}").takeIf { it.exists() }?.deleteRecursively()
    }
}

fun checkHuggingFaceModelExists(modelName: String): Boolean {
    return runCatching {
        val connection = (URL("https://huggingface.co/api/models/${huggingFaceRepoId(modelName)}").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        connection.connect()
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            return@runCatching false
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        val bundle = LocalModelCatalog.findById(modelName)
        if (bundle != null) {
            return@runCatching bundle.requiredFiles.all { required ->
                body.contains("\"rfilename\":\"${required.trim().replace("\\", "/")}\"")
            }
        }
        body.contains("\"rfilename\":\"${modelName.trim().replace("\\", "/")}\"")
    }.getOrElse { false }
}

fun huggingFaceModelUrl(modelName: String): String {
    val normalizedName = modelName.trim().replace("\\", "/")
    val bundle = LocalModelCatalog.findById(normalizedName)
    if (bundle != null) {
        error("Use explicit bundle file path for URL resolution: $normalizedName")
    }
    if (normalizedName.contains("/")) {
        val prefix = normalizedName.substringBefore('/')
        val bundleByPrefix = LocalModelCatalog.findById(prefix)
        if (bundleByPrefix != null) {
            val relativePath = normalizedName.substringAfter('/', "")
            return "https://huggingface.co/${bundleByPrefix.repoId}/resolve/main/$relativePath"
        }
    }
    return "https://huggingface.co/${huggingFaceRepoId(normalizedName)}/resolve/main/$normalizedName"
}

fun huggingFaceRepoId(modelName: String): String {
    val normalized = modelName.trim().replace("\\", "/").lowercase(Locale.ROOT)
    val bundle = LocalModelCatalog.findById(normalized)
    if (bundle != null) {
        return bundle.repoId
    }
    if (normalized.contains("/")) {
        val prefix = normalized.substringBefore('/')
        val bundleByPrefix = LocalModelCatalog.findById(prefix)
        if (bundleByPrefix != null) {
            return bundleByPrefix.repoId
        }
    }
    return when {
        normalized.endsWith(".onnx") || normalized.endsWith(".ort") -> "onnx-community/whisper-large-v3-turbo"
        else -> "ggerganov/whisper.cpp"
    }
}

internal fun computeSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(8192)
        var read = input.read(buffer)
        while (read != -1) {
            digest.update(buffer, 0, read)
            read = input.read(buffer)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun fetchHuggingFaceModelSize(modelName: String): Long? {
    return runCatching {
        val connection = (URL(huggingFaceModelUrl(modelName)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            requestMethod = "HEAD"
            instanceFollowRedirects = true
        }
        connection.connect()
        val code = connection.responseCode
        val size = if (code in 200..299) connection.contentLengthLong else -1L
        connection.disconnect()
        size.takeIf { it > 0L }
    }.getOrNull()
}
