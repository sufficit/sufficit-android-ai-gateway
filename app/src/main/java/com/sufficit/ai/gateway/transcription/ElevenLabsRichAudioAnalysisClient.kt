package com.sufficit.ai.gateway.transcription

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataOutputStream
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Analise opcional Scribe v2 para enriquecer um commit Realtime.
 *
 * Esta chamada e separada deliberadamente: ela duplica o audio faturado e
 * por isso so deve ser usada quando o experimento estiver habilitado.
 */
class ElevenLabsRichAudioAnalysisClient {
    fun analyzePcm16(
        pcmBytes: ByteArray,
        apiKey: String
    ): TranscriptionAudioMetadata {
        require(apiKey.isNotBlank()) { "Chave ElevenLabs nao configurada." }
        if (pcmBytes.size < MINIMUM_PCM_BYTES) {
            return TranscriptionAudioMetadata(richAnalysisPerformed = false)
        }

        val boundary = "----SufficitAudioAnalysis${UUID.randomUUID()}"
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            doInput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("xi-api-key", apiKey.trim())
        }

        DataOutputStream(connection.outputStream).use { output ->
            writeField(output, boundary, "model_id", "scribe_v2")
            writeField(output, boundary, "language_code", "por")
            writeField(output, boundary, "file_format", "pcm_s16le_16")
            writeField(output, boundary, "timestamps_granularity", "word")
            writeField(output, boundary, "tag_audio_events", "true")
            writeField(output, boundary, "diarize", "true")
            writeFile(output, boundary, pcmBytes)
            output.writeBytes("--$boundary--\r\n")
            output.flush()
        }

        val responseCode = connection.responseCode
        val stream = try {
            connection.inputStream
        } catch (_: FileNotFoundException) {
            connection.errorStream
        }
        val body = stream?.use { input ->
            BufferedInputStream(input).readBytes().toString(Charsets.UTF_8)
        }.orEmpty()
        connection.disconnect()

        if (responseCode !in 200..299) {
            throw WhisperHttpException(
                responseCode,
                "ElevenLabs rich analysis HTTP $responseCode: ${body.take(320)}"
            )
        }
        return ElevenLabsTranscriptionEventParser.metadata(
            event = JSONObject(body),
            richAnalysisPerformed = true,
            source = "elevenlabs_scribe_v2_batch",
            signals = listOf(
                TranscriptionSignal.LANGUAGE_DETECTION,
                TranscriptionSignal.LANGUAGE_PROBABILITY,
                TranscriptionSignal.WORD_TIMESTAMPS,
                TranscriptionSignal.SPEAKER_DIARIZATION,
                TranscriptionSignal.AUDIO_EVENTS
            )
        )
    }

    private fun writeField(
        output: DataOutputStream,
        boundary: String,
        name: String,
        value: String
    ) {
        output.writeBytes("--$boundary\r\n")
        output.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        output.writeBytes("$value\r\n")
    }

    private fun writeFile(
        output: DataOutputStream,
        boundary: String,
        bytes: ByteArray
    ) {
        output.writeBytes("--$boundary\r\n")
        output.writeBytes(
            "Content-Disposition: form-data; name=\"file\"; filename=\"segment.pcm\"\r\n"
        )
        output.writeBytes("Content-Type: application/octet-stream\r\n\r\n")
        output.write(bytes)
        output.writeBytes("\r\n")
    }

    private companion object {
        const val ENDPOINT = "https://api.elevenlabs.io/v1/speech-to-text"
        const val MINIMUM_PCM_BYTES = 3_200 // 100 ms de PCM16 mono/16 kHz.
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 60_000
    }
}
