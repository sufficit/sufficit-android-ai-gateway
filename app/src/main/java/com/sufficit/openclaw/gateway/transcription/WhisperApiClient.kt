package com.sufficit.openclaw.gateway.transcription

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataOutputStream
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class WhisperTranscriptionResult(
    val text: String,
    val gender: String? = null,
    val emotion: String? = null
)

class WhisperApiClient {
    fun transcribe(
        wavBytes: ByteArray,
        whisperUrl: String,
        authToken: String,
        model: String,
        prompt: String = "",
        vadFilter: Boolean,
        conditionOnPreviousText: Boolean,
        noSpeechThreshold: Double,
        compressionRatioThreshold: Double,
        repetitionPenalty: Double
    ): WhisperTranscriptionResult {
        // Provedor alternativo: ElevenLabs Scribe. Detectado pela URL para
        // permitir alternar temporariamente só trocando endpoint+token na
        // configuracao, sem plumbing novo de settings. Formato proprio:
        // header xi-api-key, campos model_id/language_code, sem parametros
        // de VAD/prompt do Whisper.
        if (whisperUrl.contains("api.elevenlabs.io", ignoreCase = true)) {
            return transcribeElevenLabs(
                wavBytes = wavBytes,
                whisperUrl = whisperUrl,
                apiKey = authToken,
                model = model
            )
        }

        val boundary = "----OpenClaw${UUID.randomUUID()}"
        val normalizedToken = authToken.trim()
        val connection = (URL(whisperUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            doInput = true
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
            if (normalizedToken.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $normalizedToken")
            }
        }

        if (normalizedToken.isNotBlank()) {
            connection.setRequestProperty("Authorization", "Bearer $normalizedToken")
        }

        DataOutputStream(connection.outputStream).use { output ->
            writeFormField(output, boundary, "model", model)
            writeFormField(output, boundary, "language", "pt")
            writeFormField(output, boundary, "response_format", "verbose_json")
            writeFormField(output, boundary, "temperature", "0.0")
            writeFormField(output, boundary, "vad_filter", vadFilter.toString())
            writeFormField(output, boundary, "condition_on_previous_text", conditionOnPreviousText.toString())
            writeFormField(output, boundary, "no_speech_threshold", noSpeechThreshold.toString())
            writeFormField(output, boundary, "compression_ratio_threshold", compressionRatioThreshold.toString())
            writeFormField(output, boundary, "repetition_penalty", repetitionPenalty.toString())
            writeFormField(output, boundary, "beam_size", "5")
            if (prompt.isNotBlank()) {
                writeFormField(output, boundary, "prompt", prompt)
            }
            writeFileField(output, boundary, "file", "segment.wav", wavBytes)
            output.writeBytes("--$boundary--\r\n")
            output.flush()
        }

        val responseCode = connection.responseCode
        val stream = try {
            connection.inputStream
        } catch (_: FileNotFoundException) {
            connection.errorStream
        }

        val responseBody = stream?.use { input ->
            BufferedInputStream(input).readBytes().toString(Charsets.UTF_8)
        }.orEmpty()

        if (responseCode !in 200..299) {
            throw IllegalStateException("Whisper HTTP $responseCode: $responseBody")
        }

        val json = JSONObject(responseBody)
        return WhisperTranscriptionResult(
            text = json.optString("text").trim()
        )
    }

    /**
     * Transcricao via ElevenLabs Scribe (speech-to-text).
     * Endpoint: https://api.elevenlabs.io/v1/speech-to-text
     * Auth: header "xi-api-key" (a chave vai no mesmo campo de token da
     * configuracao). Modelo: model_id "scribe_v1" — se o campo de modelo da
     * configuracao nao for um modelo Scribe (ex.: sobrou "large-v3-turbo" do
     * Whisper), usa scribe_v1 por padrao.
     */
    private fun transcribeElevenLabs(
        wavBytes: ByteArray,
        whisperUrl: String,
        apiKey: String,
        model: String
    ): WhisperTranscriptionResult {
        val boundary = "----OpenClaw${UUID.randomUUID()}"
        val modelId = model.trim().takeIf { it.startsWith("scribe", ignoreCase = true) } ?: "scribe_v1"
        val connection = (URL(whisperUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            doInput = true
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("xi-api-key", apiKey.trim())
        }

        DataOutputStream(connection.outputStream).use { output ->
            writeFormField(output, boundary, "model_id", modelId)
            writeFormField(output, boundary, "language_code", "pt")
            // Sem marcacao de eventos de audio (risadas etc.): o gateway quer
            // texto limpo para o OpenClaw.
            writeFormField(output, boundary, "tag_audio_events", "false")
            writeFileField(output, boundary, "file", "segment.wav", wavBytes)
            output.writeBytes("--$boundary--\r\n")
            output.flush()
        }

        val responseCode = connection.responseCode
        val stream = try {
            connection.inputStream
        } catch (_: FileNotFoundException) {
            connection.errorStream
        }
        val responseBody = stream?.use { input ->
            BufferedInputStream(input).readBytes().toString(Charsets.UTF_8)
        }.orEmpty()

        if (responseCode !in 200..299) {
            throw IllegalStateException("ElevenLabs HTTP $responseCode: $responseBody")
        }

        val json = JSONObject(responseBody)
        return WhisperTranscriptionResult(
            text = json.optString("text").trim()
        )
    }

    private fun writeFormField(
        output: DataOutputStream,
        boundary: String,
        name: String,
        value: String
    ) {
        output.writeBytes("--$boundary\r\n")
        output.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        output.writeBytes("$value\r\n")
    }

    private fun writeFileField(
        output: DataOutputStream,
        boundary: String,
        name: String,
        fileName: String,
        bytes: ByteArray
    ) {
        output.writeBytes("--$boundary\r\n")
        output.writeBytes(
            "Content-Disposition: form-data; name=\"$name\"; filename=\"$fileName\"\r\n"
        )
        output.writeBytes("Content-Type: audio/wav\r\n\r\n")
        output.write(bytes)
        output.writeBytes("\r\n")
    }
}
