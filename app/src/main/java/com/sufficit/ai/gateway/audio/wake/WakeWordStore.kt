package com.sufficit.ai.gateway.audio.wake

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class WakeWordProfileConfig(
    val id: String,
    val phraseLabel: String,
    val enabled: Boolean = true,
    val threshold: Double = WakeWordConfig.DEFAULT_THRESHOLD,
    val autoThreshold: Boolean = true,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

data class WakeWordConfig(
    val enabled: Boolean = true,
    val profiles: List<WakeWordProfileConfig> = emptyList()
) {
    companion object {
        // Escala da distancia cosseno DTW normalizada (0..~1).
        const val DEFAULT_THRESHOLD = WakeWordThresholdPolicy.MIN_AUTOMATIC_THRESHOLD
    }
}

data class WakeWordProfileSummary(
    val profile: WakeWordProfileConfig,
    val sampleCount: Int
) {
    val ready: Boolean
        get() = profile.enabled && sampleCount >= WakeWordStore.REQUIRED_SAMPLES
}

/**
 * Persistencia de varias palavras de ativacao. Cada perfil possui nome,
 * limiar e amostras PCM 16kHz mono 16-bit independentes.
 *
 * O schema v1 (config global + sample_*.pcm na raiz) e migrado sem apagar os
 * arquivos antigos: eles sao copiados para um perfil legado e o config passa
 * a usar o schema v2.
 */
class WakeWordStore(context: Context) {

    private val dir = File(context.filesDir, "wake_word")
    private val profilesDir = File(dir, "profiles")
    private val configFile = File(dir, "config.json")

    private fun ensureDir(): File {
        if (!dir.exists()) dir.mkdirs()
        if (!profilesDir.exists()) profilesDir.mkdirs()
        return dir
    }

    fun loadConfig(): WakeWordConfig = synchronized(STORE_LOCK) {
        ensureDir()
        val json = runCatching {
            JSONObject(AtomicFile(configFile).readFully().toString(Charsets.UTF_8))
        }.getOrNull()
        if (json == null) {
            migrateLegacyConfig(JSONObject())
        } else if (json.optJSONArray("profiles") == null) {
            migrateLegacyConfig(json)
        } else {
            parseV2Config(json)
        }
    }

    fun saveConfig(config: WakeWordConfig) = synchronized(STORE_LOCK) {
        writeConfigLocked(normalizeConfig(config))
    }

    fun createProfile(phraseLabel: String): WakeWordProfileConfig = synchronized(STORE_LOCK) {
        val label = phraseLabel.trim().take(MAX_LABEL_LENGTH)
        require(label.length >= MIN_LABEL_LENGTH) { "A chamada precisa ter ao menos 2 caracteres." }
        val current = loadConfig()
        current.profiles.firstOrNull { it.phraseLabel.equals(label, ignoreCase = true) }?.let {
            return@synchronized it
        }
        val profile = WakeWordProfileConfig(
            id = UUID.randomUUID().toString(),
            phraseLabel = label
        )
        writeConfigLocked(current.copy(enabled = true, profiles = current.profiles + profile))
        profileDir(profile.id).mkdirs()
        profile
    }

    fun updateProfile(
        profileId: String,
        transform: (WakeWordProfileConfig) -> WakeWordProfileConfig
    ): WakeWordProfileConfig? = synchronized(STORE_LOCK) {
        val current = loadConfig()
        var updated: WakeWordProfileConfig? = null
        val profiles = current.profiles.map { profile ->
            if (profile.id == profileId) {
                val transformed = transform(profile)
                transformed.copy(
                    id = profile.id,
                    phraseLabel = transformed.phraseLabel.trim().take(MAX_LABEL_LENGTH)
                        .ifBlank { profile.phraseLabel }
                ).also { updated = it }
            } else {
                profile
            }
        }
        if (updated != null) writeConfigLocked(current.copy(profiles = profiles))
        updated
    }

    fun profileSummaries(): List<WakeWordProfileSummary> = synchronized(STORE_LOCK) {
        loadConfig().profiles.map { profile ->
            WakeWordProfileSummary(profile = profile, sampleCount = sampleFiles(profile.id).size)
        }
    }

    fun sampleCount(profileId: String): Int = synchronized(STORE_LOCK) {
        sampleFiles(profileId).size
    }

    fun totalSampleCount(): Int = synchronized(STORE_LOCK) {
        loadConfig().profiles.sumOf { sampleFiles(it.id).size }
    }

    fun loadSamples(profileId: String): List<ShortArray> = synchronized(STORE_LOCK) {
        sampleFiles(profileId).mapNotNull(::readSample)
    }

    fun saveSample(profileId: String, samples: ShortArray): Boolean = synchronized(STORE_LOCK) {
        ensureDir()
        if (loadConfig().profiles.none { it.id == profileId }) return@synchronized false
        val existing = sampleFiles(profileId)
        if (existing.size >= MAX_SAMPLES_PER_PROFILE) existing.firstOrNull()?.delete()
        val file = File(profileDir(profileId), "sample_${System.currentTimeMillis()}.pcm")
        runCatching {
            val bytes = ByteArray(samples.size * 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            true
        }.getOrDefault(false)
    }

    fun clearSamples(profileId: String) = synchronized(STORE_LOCK) {
        sampleFiles(profileId).forEach { it.delete() }
    }

    private fun migrateLegacyConfig(legacy: JSONObject): WakeWordConfig {
        val legacySamples = legacySampleFiles()
        if (legacySamples.isEmpty() && !configFile.exists()) return WakeWordConfig()

        val label = legacy.optString("phraseLabel", "Minha chamada")
            .trim()
            .take(MAX_LABEL_LENGTH)
            .ifBlank { "Minha chamada" }
        val profile = WakeWordProfileConfig(
            id = LEGACY_PROFILE_ID,
            phraseLabel = label,
            enabled = true,
            threshold = legacy.optDouble("threshold", WakeWordConfig.DEFAULT_THRESHOLD),
            autoThreshold = legacy.optBoolean("autoThreshold", true),
            createdAtEpochMs = legacy.optLong("createdAtEpochMs", System.currentTimeMillis())
        )
        val target = profileDir(profile.id).apply { mkdirs() }
        legacySamples.forEach { source ->
            val destination = File(target, source.name)
            if (!destination.exists()) runCatching { source.copyTo(destination) }
        }
        return WakeWordConfig(
            enabled = legacy.optBoolean("enabled", true),
            profiles = listOf(profile)
        ).also(::writeConfigLocked)
    }

    private fun parseV2Config(json: JSONObject): WakeWordConfig {
        val profilesJson = json.optJSONArray("profiles") ?: JSONArray()
        val profiles = buildList {
            for (index in 0 until profilesJson.length()) {
                val item = profilesJson.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val label = item.optString("phraseLabel").trim().take(MAX_LABEL_LENGTH)
                if (!VALID_PROFILE_ID.matches(id) || label.length < MIN_LABEL_LENGTH) continue
                add(
                    WakeWordProfileConfig(
                        id = id,
                        phraseLabel = label,
                        enabled = item.optBoolean("enabled", true),
                        threshold = item.optDouble("threshold", WakeWordConfig.DEFAULT_THRESHOLD)
                            .coerceIn(MIN_THRESHOLD, MAX_THRESHOLD),
                        autoThreshold = item.optBoolean("autoThreshold", true),
                        createdAtEpochMs = item.optLong("createdAtEpochMs", 0L)
                    )
                )
            }
        }
        return normalizeConfig(
            WakeWordConfig(
                enabled = json.optBoolean("enabled", true),
                profiles = profiles
            )
        )
    }

    private fun normalizeConfig(config: WakeWordConfig): WakeWordConfig {
        val seenIds = HashSet<String>()
        val seenLabels = HashSet<String>()
        val profiles = config.profiles.mapNotNull { profile ->
            val label = profile.phraseLabel.trim().take(MAX_LABEL_LENGTH)
            val normalizedLabel = label.lowercase()
            if (
                !VALID_PROFILE_ID.matches(profile.id) ||
                label.length < MIN_LABEL_LENGTH ||
                !seenIds.add(profile.id) ||
                !seenLabels.add(normalizedLabel)
            ) {
                null
            } else {
                profile.copy(
                    phraseLabel = label,
                    threshold = profile.threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
                )
            }
        }
        return config.copy(profiles = profiles)
    }

    private fun writeConfigLocked(config: WakeWordConfig) {
        ensureDir()
        val profiles = JSONArray()
        config.profiles.forEach { profile ->
            profiles.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("phraseLabel", profile.phraseLabel)
                    .put("enabled", profile.enabled)
                    .put("threshold", profile.threshold)
                    .put("autoThreshold", profile.autoThreshold)
                    .put("createdAtEpochMs", profile.createdAtEpochMs)
            )
        }
        val json = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("enabled", config.enabled)
            .put("profiles", profiles)
        val atomicFile = AtomicFile(configFile)
        val output = atomicFile.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (error: IOException) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private fun profileDir(profileId: String): File {
        require(VALID_PROFILE_ID.matches(profileId)) { "Identificador de wake word invalido." }
        return File(profilesDir, profileId)
    }

    private fun sampleFiles(profileId: String): List<File> {
        val profileDir = profileDir(profileId)
        return profileDir.listFiles { file ->
            file.name.startsWith("sample_") && file.extension == "pcm"
        }?.sortedBy { it.name } ?: emptyList()
    }

    private fun legacySampleFiles(): List<File> =
        dir.listFiles { file -> file.name.startsWith("sample_") && file.extension == "pcm" }
            ?.sortedBy { it.name }
            ?: emptyList()

    private fun readSample(file: File): ShortArray? = runCatching {
        val bytes = file.readBytes()
        if (bytes.size < 2 || bytes.size % 2 != 0) return@runCatching null
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        shorts
    }.getOrNull()

    companion object {
        const val REQUIRED_SAMPLES = 3
        const val MAX_SAMPLES_PER_PROFILE = 5
        const val MAX_LABEL_LENGTH = 28
        private const val MIN_LABEL_LENGTH = 2
        private const val SCHEMA_VERSION = 2
        private const val LEGACY_PROFILE_ID = "legacy-primary"
        private const val MIN_THRESHOLD = 0.05
        private const val MAX_THRESHOLD = 0.50
        private val VALID_PROFILE_ID = Regex("[A-Za-z0-9._-]+")
        private val STORE_LOCK = Any()
    }
}
