package com.sufficit.ai.gateway

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.sufficit.ai.gateway.config.LocalModelCatalog
import com.sufficit.ai.gateway.history.TranscriptHistoryLogger
import java.util.Locale

fun selectedModelOption(
    localModelName: String,
    options: List<LocalModelOption>
): LocalModelOption? {
    val target = localModelName.trim()
    if (target.isEmpty()) {
        return null
    }
    return options.firstOrNull { it.name.equals(target, ignoreCase = true) }
}

fun loadLocalModelOptions(context: Context): List<LocalModelOption> {
    return LocalModelCatalog.bundles.map { bundle ->
        val files = bundle.resolveRequiredFiles(context)
        val localSize = files.sumOf { if (it.exists()) it.length() else 0L }
        val remoteSize = bundle.requiredFiles.sumOf {
            fetchHuggingFaceModelSize("${bundle.id}/$it") ?: 0L
        }.takeIf { it > 0L }
        val missingFiles = files.filterNot { it.exists() && it.length() > 0L }
        val invalidBySize = remoteSize != null && localSize > 0L && localSize != remoteSize
        val isInvalid = missingFiles.isNotEmpty() || invalidBySize
        val status = when {
            missingFiles.isNotEmpty() -> "Incompleto (${missingFiles.size}/${files.size} faltando)"
            invalidBySize -> "Invalido (${formatBytes(localSize)} vs remoto ${formatBytes(remoteSize ?: 0L)})"
            remoteSize != null -> "Valido (${formatBytes(localSize)})"
            else -> "Sem validacao remota (${formatBytes(localSize)})"
        }
        val sha256 = files.joinToString(separator = "+") { file ->
            if (file.exists()) computeSha256(file).take(12) else "missing"
        }
        LocalModelOption(
            name = bundle.id,
            sizeBytes = localSize,
            sha256 = sha256,
            remoteSizeBytes = remoteSize,
            isInvalid = isInvalid,
            status = status
        )
    }
}

fun formatBytes(value: Long): String {
    val bytes = value.coerceAtLeast(0L).toDouble()
    return when {
        bytes < 1024.0 -> "${bytes.toInt()} B"
        bytes < 1024.0 * 1024.0 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024.0 * 1024.0 * 1024.0 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

fun shareTranscriptHistory(context: Context): Boolean {
    val exported = TranscriptHistoryLogger.exportCopy(context) ?: return false
    val exportUri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        exported
    )
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, exportUri)
        putExtra(Intent.EXTRA_SUBJECT, "Historico de transcricao OpenClaw")
        putExtra(Intent.EXTRA_TEXT, "Historico exportado do AI Gateway.")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(shareIntent, "Exportar historico").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
    return true
}
