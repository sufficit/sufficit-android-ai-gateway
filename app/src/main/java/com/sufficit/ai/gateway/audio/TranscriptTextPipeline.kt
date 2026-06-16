package com.sufficit.ai.gateway.audio

import android.content.Context
import com.sufficit.ai.gateway.config.GatewaySettings

internal object TranscriptTextPipeline {
    private val commonPortugueseConnectors = setOf(
        "a", "o", "as", "os", "um", "uma", "uns", "umas",
        "de", "da", "do", "das", "dos",
        "e", "em", "no", "na", "nos", "nas",
        "para", "por", "com", "sem",
        "que", "eu", "voce", "voces", "tu",
        "ele", "ela", "eles", "elas",
        "meu", "minha", "seu", "sua",
        "isso", "isto", "essa", "esse",
        "vamos", "vai", "vou", "foi", "era"
    )

    fun buildPrompt(settings: GatewaySettings): String {
        val terms = (settings.transcriptionTerms.lineSequence() + settings.voiceChannelWakeTerms.lineSequence())
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        val replacements = parseDictionary(settings.transcriptionDictionary)

        if (terms.isEmpty() && replacements.isEmpty()) {
            return ""
        }

        return buildString {
            append("Use portugues do Brasil e priorize estes termos da empresa. ")
            append("Interprete variantes coloquiais e sotaques brasileiros de forma canonica, por exemplo ")
            append("\"intendi\" como \"entendi\", sem inventar palavras.")
            append(" Transcreva apenas o que for realmente ouvido.")
            append(" Nao adicione fechamentos ou cortesias como \"obrigado\", \"obrigada\", \"tchau\", \"ate a proxima\" ou similares se nao estiverem claramente audiveis.")
            if (terms.isNotEmpty()) {
                append(" Termos preferidos: ")
                append(terms.joinToString(", "))
                append('.')
            }
            if (replacements.isNotEmpty()) {
                append(" Correcos desejadas: ")
                append(
                    replacements.joinToString("; ") { (wrong, right) ->
                        "\"$wrong\" -> \"$right\""
                    }
                )
                append('.')
            }
        }
    }

    fun applyCorrections(
        context: Context,
        text: String,
        settings: GatewaySettings,
        onDiscardedShortTranscript: ((String) -> Unit)? = null
    ): String {
        var corrected = applySafePortugueseColloquialNormalization(
            context = context,
            text = text.trim(),
            strength = settings.colloquialNormalizationStrength
        )
        if (corrected.isBlank()) {
            return corrected
        }

        parseDictionary(settings.transcriptionDictionary).forEach { (wrong, right) ->
            val normalizedWrong = wrong.trim()
            val normalizedRight = right.trim()
            if (normalizedWrong.isBlank() || normalizedRight.isBlank()) {
                return@forEach
            }

            val pattern = Regex("\\b${Regex.escape(normalizedWrong)}\\b", RegexOption.IGNORE_CASE)
            corrected = pattern.replace(corrected, normalizedRight)
        }

        corrected = sanitizeImplausibleShortTranscript(
            text = corrected,
            settings = settings,
            onDiscardedShortTranscript = onDiscardedShortTranscript
        )

        corrected = removeLikelyHallucinatedCourtesyTail(corrected)
        corrected = discardLikelyHallucinatedCourtesyOnlyTranscript(
            text = corrected,
            onDiscardedShortTranscript = onDiscardedShortTranscript
        )

        return corrected.replace(Regex("\\s+"), " ").trim()
    }

    fun shouldIgnoreAmbientTranscript(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            return false
        }

        val normalized = trimmed
            .lowercase()
            .replace("[", " ")
            .replace("]", " ")
            .replace("(", " ")
            .replace(")", " ")
            .replace("♪", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (normalized.isBlank()) {
            return true
        }

        val ambientPatterns = listOf(
            "musica",
            "música",
            "music",
            "tocando musica",
            "tocando música",
            "musica ao fundo",
            "música ao fundo",
            "som ambiente",
            "audio ambiente",
            "áudio ambiente",
            "aplausos",
            "aplauso",
            "ruido",
            "ruído",
            "barulho",
            "instrumental"
        )

        if (ambientPatterns.any { normalized == it }) {
            return true
        }

        val tokens = normalized.splitWhitespace()
        if (tokens.isEmpty()) {
            return true
        }

        val ambientVocabulary = setOf(
            "musica",
            "música",
            "music",
            "tocando",
            "fundo",
            "som",
            "ambiente",
            "audio",
            "áudio",
            "aplausos",
            "aplauso",
            "ruido",
            "ruído",
            "barulho",
            "instrumental"
        )

        return tokens.all { it in ambientVocabulary }
    }

    fun isNeutralMarkerTranscript(text: String): Boolean {
        val normalized = text.trim()
            .replace(Regex("\\s+"), "")
        return normalized == "[*]"
    }

    /**
     * Detecta alucinacao repetitiva do Whisper ("xuxu, xuxu, xuxu, ..."):
     * em ruido/silencio o decoder pode entrar em loop e devolver a mesma
     * palavra dezenas de vezes. Criterio: 4+ palavras com no maximo 2
     * distintas, ou frase longa com menos de 20% de vocabulario distinto.
     * Transcricao detectada aqui deve ser DESCARTADA pelo chamador.
     */
    fun isLikelyHallucinatedRepetition(text: String): Boolean {
        val words = text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (words.size < 2) {
            return false
        }
        // Piso baixo: 2+ palavras todas iguais ("xuxu xuxu") ja e loop tipico
        // de wake word alucinada em ruido. Antes exigia 4 palavras e deixava
        // passar o par.
        val distinct = words.distinct().size
        if (words.size in 2..3) {
            return distinct == 1
        }
        if (distinct <= 2) {
            return true
        }
        return words.size >= 8 && distinct.toDouble() / words.size < 0.2
    }

    /**
     * True quando a transcricao e composta APENAS por palavra(s) de ativacao
     * (wake terms), ex.: "xuxu", "xuxu xuxu", "openclaw". Ela ja cumpriu o
     * papel de acordar a escuta: nao deve virar bolha de conversa nem ir para
     * o OpenClaw. O chamador deve registrar uma marca de sistema discreta.
     * Retorna o termo canonico reconhecido (primeiro token) ou null.
     */
    fun wakeTermOnlyTranscript(text: String, settings: GatewaySettings): String? {
        val wakeTerms = settings.voiceChannelWakeTerms
            .lineSequence()
            .map { normalizeTokenForLexicalCheck(it.replace(Regex("\\s+"), " ").trim()) }
            .filter { it.isNotBlank() }
            .toSet()
        if (wakeTerms.isEmpty()) {
            return null
        }
        val normalizedFull = normalizeTokenForLexicalCheck(
            text.replace(Regex("\\s+"), " ").trim()
        )
        if (normalizedFull.isBlank()) {
            return null
        }
        // Termo multi-palavra ("open claw") casa direto na frase inteira.
        if (normalizedFull in wakeTerms) {
            return normalizedFull
        }
        val tokens = text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .map { normalizeTokenForLexicalCheck(it) }
            .filter { it.isNotBlank() }
        if (tokens.isEmpty() || tokens.size > 3) {
            return null
        }
        if (tokens.all { it in wakeTerms }) {
            return tokens.first()
        }
        return null
    }

    private fun applySafePortugueseColloquialNormalization(
        context: Context,
        text: String,
        strength: Double
    ): String {
        var normalized = text.trim()
        if (normalized.isBlank()) {
            return normalized
        }
        if (strength <= 0.01) {
            return normalized
        }

        ColloquialNormalizationCatalog.load(context)
            .asSequence()
            .filter { strength >= it.minStrength }
            .forEach { rule ->
                normalized = Regex(rule.pattern, RegexOption.IGNORE_CASE)
                    .replace(normalized, rule.replacement)
            }

        return normalized.replace(Regex("\\s+"), " ").trim()
    }

    private fun sanitizeImplausibleShortTranscript(
        text: String,
        settings: GatewaySettings,
        onDiscardedShortTranscript: ((String) -> Unit)? = null
    ): String {
        val tokens = text.splitWhitespace()
        if (tokens.isEmpty() || tokens.size > 2) {
            return text
        }

        val knownWords = buildKnownWordAllowList(settings)
        val hasHyphenatedUnknown = tokens.any { token ->
            '-' in token &&
                normalizeTokenForLexicalCheck(token).let { normalized ->
                    normalized.isNotBlank() &&
                        normalized !in knownWords &&
                        isImprobableIsolatedWordCandidate(normalized, knownWords)
                }
        }

        if (hasHyphenatedUnknown) {
            return text
        }

        return text
    }

    private fun removeLikelyHallucinatedCourtesyTail(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            return trimmed
        }

        val suffixes = listOf(
            "obrigado",
            "obrigada",
            "muito obrigado",
            "muito obrigada",
            "obrigado por assistir",
            "obrigada por assistir",
            "tchau",
            "ate a proxima",
            "até a próxima"
        )

        val normalizedTrimmed = normalizeTokenForLexicalCheck(trimmed).replace(Regex("\\s+"), " ").trim()
        val words = trimmed.splitWhitespace()

        suffixes.forEach { suffix ->
            val normalizedSuffix = normalizeTokenForLexicalCheck(suffix).replace(Regex("\\s+"), " ").trim()
            val suffixWords = suffix.splitWhitespace().size
            if (words.size <= suffixWords + 1) {
                return@forEach
            }
            if (normalizedTrimmed.endsWith(normalizedSuffix)) {
                val remainingWords = words.dropLast(suffixWords).joinToString(" ").trim().trimEnd(',', '.', ';', ':', '!', '?')
                if (remainingWords.isNotBlank()) {
                    return remainingWords
                }
            }
        }

        return trimmed
    }

    private fun discardLikelyHallucinatedCourtesyOnlyTranscript(
        text: String,
        onDiscardedShortTranscript: ((String) -> Unit)? = null
    ): String {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            return trimmed
        }

        val normalized = normalizeTokenForLexicalCheck(trimmed)
            .replace(Regex("\\s+"), " ")
            .trim()
        val courtesyPatterns = setOf(
            "obrigado",
            "obrigada",
            "muito obrigado",
            "muito obrigada",
            "obrigado por assistir",
            "obrigada por assistir",
            "tchau",
            "xau",
            "ate a proxima",
            "ate mais",
            "falou",
            "valeu"
        )

        if (normalized in courtesyPatterns) {
            return trimmed
        }

        return trimmed
    }

    private fun buildKnownWordAllowList(settings: GatewaySettings): Set<String> {
        val preferredTerms = settings.transcriptionTerms
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .flatMap { it.splitWhitespace().asSequence() }
            .map { normalizeTokenForLexicalCheck(it) }
            .filter { it.isNotBlank() }
            .toSet()

        val dictionaryWords = parseDictionary(settings.transcriptionDictionary)
            .flatMap { (wrong, right) ->
                (wrong.splitWhitespace() + right.splitWhitespace())
                    .map { normalizeTokenForLexicalCheck(it) }
            }
            .filter { it.isNotBlank() }
            .toSet()

        return preferredTerms + dictionaryWords + commonPortugueseConnectors
    }

    private fun isImprobableIsolatedWordCandidate(
        normalizedToken: String,
        knownWords: Set<String>
    ): Boolean {
        if (normalizedToken.length < 6) {
            return false
        }
        if (!normalizedToken.all { it.isLetter() }) {
            return false
        }
        if (normalizedToken in knownWords) {
            return false
        }
        if (normalizedToken.any { it in "áàâãéêíóôõúç" }) {
            return false
        }
        return true
    }

    private fun normalizeTokenForLexicalCheck(token: String): String {
        return token
            .lowercase()
            .replace(Regex("[^\\p{L}]"), "")
            .trim()
    }

    private fun parseDictionary(raw: String): List<Pair<String, String>> {
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                when {
                    "=>" in line -> line.split("=>", limit = 2)
                    "->" in line -> line.split("->", limit = 2)
                    "=" in line -> line.split("=", limit = 2)
                    else -> null
                }?.let { parts ->
                    val wrong = parts.getOrNull(0)?.trim().orEmpty()
                    val right = parts.getOrNull(1)?.trim().orEmpty()
                    if (wrong.isBlank() || right.isBlank()) null else wrong to right
                }
            }
            .toList()
    }

    private fun String.splitWhitespace(): List<String> {
        return trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
    }
}
