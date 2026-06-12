package com.sufficit.openclaw.gateway.audio

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Gravador de depuracao de audio no proprio aparelho.
 *
 * Mantem duas visoes dos ultimos minutos (RETENTION_MS) em
 * filesDir/audio_debug/ para diagnosticar transcricoes erradas comparando o
 * que o usuario falou com o que realmente foi enviado ao Whisper:
 *
 *  - rolling/: TUDO que o microfone captou (pos-ganho), em arquivos WAV de
 *    ROLLING_FILE_MS — inclusive o que a segmentacao descartou;
 *  - segments/: o WAV EXATO de cada segmento enviado para transcricao, com
 *    transcripts.jsonl associando arquivo -> texto transcrito.
 *
 * Coleta via adb:
 *   adb shell run-as com.sufficit.openclaw.gateway sh -c 'tar cf - files/audio_debug' > audio_debug.tar
 *
 * Threading: appendRolling so na thread de captura; saveSegment/appendTranscript
 * na thread de transcricao — diretorios separados, sem estado compartilhado
 * alem do prune (idempotente por mtime).
 */
class AudioDebugStore(context: Context) {

    private val baseDir = File(context.filesDir, "audio_debug")
    private val rollingDir = File(baseDir, "rolling")
    private val segmentsDir = File(baseDir, "segments")
    private val transcriptsFile = File(baseDir, "transcripts.jsonl")

    private val rollingBuffer = ByteArrayOutputStream()
    private var rollingStartedAtMs = 0L

    init {
        rollingDir.mkdirs()
        segmentsDir.mkdirs()
        Log.i(TAG, "Audio debug ativo em ${baseDir.absolutePath} (retencao ${RETENTION_MS / 60_000} min).")
    }

    /** Chamar a cada chunk pos-ganho da captura (qualquer estado de fala). */
    fun appendRolling(buffer: ShortArray, readCount: Int) {
        if (readCount <= 0) return
        val now = System.currentTimeMillis()
        if (rollingStartedAtMs == 0L) {
            rollingStartedAtMs = now
        }
        val bytes = ByteBuffer.allocate(readCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until readCount) {
            bytes.putShort(buffer[index])
        }
        rollingBuffer.write(bytes.array())
        if (now - rollingStartedAtMs >= ROLLING_FILE_MS || rollingBuffer.size() >= ROLLING_MAX_BYTES) {
            flushRolling()
        }
    }

    /** Persiste o trecho rolante atual (chamar tambem ao parar a captura). */
    fun flushRolling() {
        if (rollingBuffer.size() == 0) return
        val pcm = rollingBuffer.toByteArray()
        rollingBuffer.reset()
        val startedAt = rollingStartedAtMs
        rollingStartedAtMs = 0L
        runCatching {
            File(rollingDir, "rolling-$startedAt.wav").writeBytes(wrapWavPcm16(pcm))
            prune(rollingDir)
        }.onFailure { Log.w(TAG, "Falha ao gravar rolling de depuracao", it) }
    }

    /**
     * Salva o WAV exato enviado para transcricao. Retorna o nome do arquivo
     * para associar a transcricao depois (ou null em falha).
     */
    fun saveSegment(wavBytes: ByteArray, durationMs: Long, preRollPrefixBytes: Int): String? {
        val name = "segment-${System.currentTimeMillis()}-dur${durationMs}ms-pre$preRollPrefixBytes.wav"
        return runCatching {
            File(segmentsDir, name).writeBytes(wavBytes)
            prune(segmentsDir)
            name
        }.onFailure { Log.w(TAG, "Falha ao gravar segmento de depuracao", it) }.getOrNull()
    }

    /** Associa o resultado da transcricao ao arquivo do segmento. */
    fun appendTranscript(segmentFileName: String?, transcript: String, extra: JSONObject? = null) {
        runCatching {
            val entry = JSONObject()
                .put("atEpochMs", System.currentTimeMillis())
                .put("file", segmentFileName ?: JSONObject.NULL)
                .put("transcript", transcript)
            extra?.keys()?.forEach { key -> entry.put(key, extra.opt(key)) }
            transcriptsFile.appendText(entry.toString() + "\n")
            if (transcriptsFile.length() > TRANSCRIPTS_MAX_BYTES) {
                // Mantem so o rabo do log (depuracao recente).
                val tail = transcriptsFile.readLines().takeLast(TRANSCRIPTS_KEEP_LINES)
                transcriptsFile.writeText(tail.joinToString("\n") + "\n")
            }
        }.onFailure { Log.w(TAG, "Falha ao registrar transcricao de depuracao", it) }
    }

    private fun prune(dir: File) {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        dir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    private fun wrapWavPcm16(pcm: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = SAMPLE_RATE * 2
        header.put("RIFF".toByteArray())
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(SAMPLE_RATE)
        header.putInt(byteRate)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(pcm.size)
        return header.array() + pcm
    }

    companion object {
        private const val TAG = "AudioDebugStore"
        private const val SAMPLE_RATE = 16_000

        // Janela de retencao dos artefatos de depuracao no aparelho.
        private const val RETENTION_MS = 5 * 60_000L

        // Tamanho de cada arquivo do audio rolante (30s ~ 960KB em WAV).
        private const val ROLLING_FILE_MS = 30_000L
        private const val ROLLING_MAX_BYTES = SAMPLE_RATE * 2 * 40 // hard cap ~40s

        private const val TRANSCRIPTS_MAX_BYTES = 256 * 1024L
        private const val TRANSCRIPTS_KEEP_LINES = 300
    }
}
