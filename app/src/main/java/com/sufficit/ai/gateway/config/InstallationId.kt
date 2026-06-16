package com.sufficit.ai.gateway.config

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Identificador estavel desta INSTALACAO do app, independente do
 * Settings.Secure.ANDROID_ID (que muda com repackage/assinatura e quebrava o
 * vinculo com a identidade da pessoa no servidor).
 *
 * UUID gerado uma vez e persistido em filesDir. Sobrevive a updates do mesmo
 * pacote; uma reinstalacao/uninstall limpa o filesDir e gera um novo — nesse
 * caso o vinculo com o userId e refeito pelo enrollment (hoje pelo campo
 * userId da configuracao; login no futuro).
 */
object InstallationId {

    private const val FILE_NAME = "installation_id"

    @Volatile
    private var cached: String? = null

    fun get(context: Context): String {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: load(context).also { cached = it }
        }
    }

    private fun load(context: Context): String {
        val file = File(context.filesDir, FILE_NAME)
        val existing = runCatching { file.readText().trim() }.getOrNull()
        if (!existing.isNullOrBlank()) {
            return existing
        }
        val generated = UUID.randomUUID().toString()
        runCatching { file.writeText(generated) }
        return generated
    }
}
