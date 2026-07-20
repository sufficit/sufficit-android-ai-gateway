package com.sufficit.ai.gateway

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateValue
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.sufficit.ai.gateway.runtime.ChatMessage
import com.sufficit.ai.gateway.runtime.ChatAudioState
import com.sufficit.ai.gateway.runtime.ChatRole
import com.sufficit.ai.gateway.runtime.GatewayRuntime
import com.sufficit.ai.gateway.runtime.PendingAudioCapture
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Chat estilo WhatsApp/Telegram para o dashboard:
//  - historico de baixo para cima (mensagem mais nova rente a barra de envio);
//  - bolha do usuario a direita (verde), do assistente a esquerda (slate);
//  - transcricao parcial em andamento aparece como bolha provisoria.
// ---------------------------------------------------------------------------

private val UserBubble = Color(0xFF1F4D3D)
private val AssistantBubble = Color(0xFF18293E)
private val BubbleText = Color(0xFFF1F6FB)
private val BubbleTime = Color(0xFF93A7BA)
private val AudioErrorText = Color(0xFFFF8A80)
// Midia capturada pelo agente/sistema (foto, screenshot): visual proprio,
// distinto das bolhas de conversa — emoldurado, centralizado, com acento.
private val MediaFrame = Color(0xFF101C2B)
private val MediaBorder = Color(0xFF2C6E7F)
private val MediaAccent = Color(0xFF55C2D8)
private const val PENDING_AUDIO_WAVE_ALPHA = 0.8f
private const val PENDING_AUDIO_MIN_BAR_LEVEL = 0.08f
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val LATEST_MESSAGE_SCROLL_TOLERANCE_PX = 4
private val InputSurface = Color(0xFF101E2E)

private val ChatTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.US).withZone(ZoneId.systemDefault())

@Composable
@Suppress("LongMethod") // Declaracao de itens do LazyColumn; extrair quebra o escopo DSL e nao reduz complexidade.
fun ChatMessagesList(
    currentTranscript: String,
    assistantProcessing: Boolean,
    assistantProcessingLabel: String,
    lastError: String?,
    modifier: Modifier = Modifier
) {
    val messages by GatewayRuntime.chatMessages().collectAsState()
    val pendingAudioCaptures by GatewayRuntime.pendingAudioCaptures().collectAsState()
    val partialTranscript = currentTranscript.trim()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var autoScrollEnabled by rememberSaveable { mutableStateOf(true) }
    val scrollScope = rememberCoroutineScope()
    val isAtLatestMessage by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset <= LATEST_MESSAGE_SCROLL_TOLERANCE_PX
        }
    }
    val manualScrollDetector = remember(listState) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.UserInput && consumed.y != 0f) {
                    val atLatest = listState.firstVisibleItemIndex == 0 &&
                        listState.firstVisibleItemScrollOffset <= LATEST_MESSAGE_SCROLL_TOLERANCE_PX
                    autoScrollEnabled = atLatest
                }
                return Offset.Zero
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(isAtLatestMessage, listState.isScrollInProgress) {
        if (!listState.isScrollInProgress && isAtLatestMessage) autoScrollEnabled = true
    }

    // Auto-scroll: mensagem nova rola a lista para o rodape — mas so se o
    // usuario ja estiver perto do fim (ler historico antigo nao pode ser
    // interrompido por puxao de scroll). Com reverseLayout, indice 0 = rodape.
    androidx.compose.runtime.LaunchedEffect(messages.size, partialTranscript.isNotBlank()) {
        // Segmentos de voz são acompanhamento em tempo real: a bolha recém
        // criada precisa ficar visível, inclusive quando várias entram na
        // fila enquanto o primeiro áudio ainda está sendo transcrito.
        if (autoScrollEnabled) listState.animateScrollToItem(0)
    }

    Box(modifier = modifier) {
        // reverseLayout ancora o conteudo embaixo (estilo WhatsApp) e mantem a
        // lista "grudada" na mensagem mais recente sem precisar de scroll manual.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(manualScrollDetector),
            state = listState,
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 8.dp,
                bottom = 72.dp
            )
        ) {
        // Com reverseLayout, o item de indice 0 fica NO RODAPE: a bolha
        // parcial (fala sendo transcrita) entra primeiro, depois o historico
        // do mais novo para o mais antigo.
        // A transcrição parcial agora atualiza a própria bolha de áudio. Não
        // criamos uma segunda bolha solta para o mesmo segmento.
        // Cada segmento FECHADO e enviado ao Whisper ganha seu proprio WAV
        // tocavel e uma forma de onda congelada. Nao e o espectro ao vivo da
        // barra inferior: representa exatamente o bloco selecionado. O
        // servico o remove quando o texto chega (ou quando falha/cancela).
        pendingAudioCaptures.asReversed().forEach { capture ->
            item(key = "pending-audio-${capture.id}") {
                PendingAudioCaptureBubble(capture)
            }
        }
        // Balao do assistente "processando": aparece enquanto o agente trabalha
        // no pedido, com o que esta sendo processado.
        if (assistantProcessing) {
            item(key = "processing") {
                ProcessingBubble(label = assistantProcessingLabel)
            }
        }
        // Erro tecnico da ultima transcricao (ex.: falha de rede, biblioteca
        // nativa, endpoint mal configurado): antes ficava so num icone de
        // status pequeno, sem nenhuma mensagem no fluxo da conversa.
        if (!lastError.isNullOrBlank() && messages.none { it.audioState == ChatAudioState.ERROR }) {
            item(key = "lastError") {
                ErrorMarker(text = "Erro na transcricao: $lastError")
            }
        }
        items(
            count = messages.size,
            key = { index -> messages[messages.size - 1 - index].id }
        ) { index ->
            val message = messages[messages.size - 1 - index]
            if (!message.imagePath.isNullOrBlank()) {
                // Foto/screenshot capturada pelo agente: card proprio, nao bolha.
                AgentMediaCard(
                    imagePath = message.imagePath!!,
                    caption = message.text,
                    timeLabel = ChatTimeFormatter.format(Instant.ofEpochMilli(message.atEpochMs))
                )
            } else if (message.role == ChatRole.SYSTEM) {
                SystemMarker(text = message.text)
            } else {
                ChatBubble(
                    text = message.text,
                    role = message.role,
                    timeLabel = ChatTimeFormatter.format(Instant.ofEpochMilli(message.atEpochMs)),
                    details = message.details,
                    audioPath = message.audioPath?.takeIf {
                        (message.audioExpiresAtEpochMs ?: 0L) > System.currentTimeMillis() &&
                            java.io.File(it).isFile
                    },
                    audioDurationMs = message.audioDurationMs,
                    audioState = message.audioState,
                    audioError = message.audioError
                )
            }
        }
        if (messages.isEmpty() && partialTranscript.isBlank()) {
            item(key = "empty") {
                Text(
                    text = "Sem conversas ainda. Fale algo, levante o indicador ou digite abaixo.",
                    color = BubbleTime,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        }

        AnimatedVisibility(
            visible = !autoScrollEnabled && !isAtLatestMessage,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .background(AssistantBubble, CircleShape)
                    .border(1.dp, MediaAccent, CircleShape)
                    .clickable {
                        autoScrollEnabled = true
                        scrollScope.launch { listState.animateScrollToItem(0) }
                    }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Rolagem pausada • voltar ao fim",
                    color = BubbleText,
                    style = MaterialTheme.typography.labelMedium
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Retomar rolagem automática e ir para a mensagem mais recente",
                    tint = MediaAccent,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

// Marca de sistema discreta (ex.: palavra de ativacao reconhecida): linha
// horizontal fina com texto bem menor que o normal, centralizado. Nao e
// bolha de conversa — apenas sinaliza um evento do sistema.
@Composable
private fun SystemMarker(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(BubbleTime.copy(alpha = 0.25f))
        )
        Text(
            text = text,
            color = BubbleTime,
            style = MaterialTheme.typography.labelSmall,
            fontStyle = FontStyle.Italic
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(BubbleTime.copy(alpha = 0.25f))
        )
    }
}

// Marca de erro tecnico da transcricao — mesmo estilo do SystemMarker mas em
// vermelho, para o usuario ver IMEDIATAMENTE por que nada foi transcrito
// (endpoint mal configurado, biblioteca nativa faltando, HTTP 4xx/5xx etc.).
@Composable
private fun ErrorMarker(text: String) {
    val errorColor = Color(0xFFD32F2F)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(errorColor.copy(alpha = 0.35f))
        )
        Text(
            text = text,
            color = errorColor,
            style = MaterialTheme.typography.labelSmall,
            fontStyle = FontStyle.Italic
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(errorColor.copy(alpha = 0.35f))
        )
    }
}

@Composable
private fun ChatBubble(
    text: String,
    role: ChatRole,
    timeLabel: String,
    provisional: Boolean = false,
    details: String? = null,
    audioPath: String? = null,
    audioDurationMs: Long? = null,
    audioState: ChatAudioState? = null,
    audioError: String? = null
) {
    val isUser = role == ChatRole.USER
    val shape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (isUser) 16.dp else 4.dp,
        bottomEnd = if (isUser) 4.dp else 16.dp
    )
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        val bubbleColor = if (isUser) UserBubble else AssistantBubble
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    // Balao ainda pendente (fala sendo transcrita, texto vai
                    // mudar): translucido, nao so o texto em italico — o
                    // usuario pediu a bolha em si com transparencia diferente
                    // para marcar "ainda nao e definitivo".
                    color = if (provisional) bubbleColor.copy(alpha = 0.55f) else bubbleColor,
                    shape = shape
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (text.isNotBlank()) {
                Text(
                    text = text,
                    color = if (provisional) BubbleText.copy(alpha = 0.75f) else BubbleText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = if (provisional) FontStyle.Italic else FontStyle.Normal
                )
            }
            if (audioPath != null) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(onClick = { playPendingAudio(audioPath) }) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Reproduzir áudio desta transcrição",
                            tint = BubbleText
                        )
                    }
                    Text(
                        text = "Ouvir áudio${audioDurationMs?.let { " • ${formatAudioDuration(it)}" }.orEmpty()}",
                        color = BubbleTime,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            if (audioState != null) {
                val statusText = when (audioState) {
                    ChatAudioState.TRANSCRIBING -> "Transcrevendo áudio…"
                    ChatAudioState.TRANSCRIBED -> "Texto reconhecido"
                    ChatAudioState.SENDING -> "Enviando para o sistema de IA…"
                    ChatAudioState.ERROR -> "Falha na transcrição${audioError?.let { ": $it" }.orEmpty()}"
                }
                Text(
                    text = statusText,
                    color = if (audioState == ChatAudioState.ERROR) AudioErrorText else BubbleTime,
                    style = MaterialTheme.typography.labelSmall,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            // Conteudo visual-apenas (details): painel expansivel — o que foi
            // dito em voz fica curto; enderecos/links/explicacoes ficam aqui,
            // sem terem sido falados.
            if (!details.isNullOrBlank()) {
                var expanded by rememberSaveable(text, details) { mutableStateOf(false) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clickable { expanded = !expanded }
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "Recolher detalhes" else "Ver detalhes",
                        tint = BubbleTime
                    )
                    Text(
                        text = if (expanded) "Ocultar detalhes" else "Ver detalhes",
                        color = BubbleTime,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Text(
                        text = details,
                        color = BubbleText.copy(alpha = 0.92f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .background(BubbleTime.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
            Text(
                text = timeLabel,
                color = BubbleTime,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

/** Resultado do decode em background de [AgentMediaCard] — ver [decodeAgentMedia]. */
private data class DecodedAgentMedia(val fileExists: Boolean, val bitmap: android.graphics.Bitmap?)

/**
 * File.exists() + BitmapFactory.decodeFile() + leitura de EXIF: I/O e decode
 * de imagem sincronos, nunca na thread principal — chamado de dentro de
 * withContext(Dispatchers.IO) em AgentMediaCard. Antes rodava inline num
 * remember{} durante a composicao (subcomposicao do LazyColumn ao rolar ate
 * a mensagem), travando a thread de UI a cada foto/screenshot que entrava
 * na tela — achado real de performance (scroll do chat travando).
 */
private fun decodeAgentMedia(imagePath: String, isImage: Boolean): DecodedAgentMedia {
    val fileExists = java.io.File(imagePath).exists()
    if (!fileExists || !isImage) return DecodedAgentMedia(fileExists, null)
    val bitmap = runCatching {
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
        val decoded = android.graphics.BitmapFactory.decodeFile(imagePath, opts)
            ?: return@runCatching null
        // Pixels ja sao gravados na orientacao correta (assada no servico);
        // ainda assim honra EXIF caso algum arquivo antigo traga a tag.
        val orientation = android.media.ExifInterface(imagePath).getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.ORIENTATION_NORMAL
        )
        val degrees = when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) decoded else {
            val m = android.graphics.Matrix().apply { postRotate(degrees) }
            android.graphics.Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        }
    }.getOrNull()
    return DecodedAgentMedia(fileExists, bitmap)
}

/**
 * Card de MIDIA capturada pelo agente/sistema (foto da camera ou screenshot).
 * Visual proprio — emoldurado, CENTRALIZADO, com etiqueta de origem e acento —
 * para nao se confundir com as bolhas de conversa do usuario/assistente. O
 * toque abre a imagem REAL no visualizador do sistema (FileProvider).
 */
@Composable
private fun AgentMediaCard(imagePath: String, caption: String, timeLabel: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lower = imagePath.lowercase()
    val isImage = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp").any { lower.endsWith(it) }
    val isScreenshot = lower.endsWith(".png")
    val kindLabel = when {
        !isImage -> "DOCUMENTO"
        isScreenshot -> "CAPTURA DE TELA"
        else -> "FOTO DA CAMERA"
    }
    val mime = when {
        isScreenshot -> "image/png"
        isImage -> "image/jpeg"
        lower.endsWith(".pdf") -> "application/pdf"
        else -> "*/*"
    }
    val media by androidx.compose.runtime.produceState<DecodedAgentMedia?>(initialValue = null, imagePath) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            decodeAgentMedia(imagePath, isImage)
        }
    }
    val loading = media == null
    // Enquanto carrega, assume "existe" para nao piscar o placeholder de
    // indisponivel e corrigir um instante depois.
    val fileExists = media?.fileExists ?: true
    val bitmap = media?.bitmap
    // Indisponivel: arquivo sumiu, ou e imagem que nao decodificou.
    val unavailable = !loading && (!fileExists || (isImage && bitmap == null))
    val openModifier = if (unavailable) Modifier else Modifier.clickable {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", java.io.File(imagePath)
            )
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
    // Centralizado: nem esquerda (usuario) nem direita (assistente).
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MediaFrame)
                .border(1.dp, MediaBorder, RoundedCornerShape(14.dp))
                .then(openModifier)
                .padding(8.dp)
        ) {
            // Cabecalho: etiqueta de origem (acento) + horario.
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp, start = 2.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MediaAccent.copy(alpha = 0.18f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = kindLabel,
                        color = MediaAccent,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                }
                Box(modifier = Modifier.weight(1f))
                Text(text = timeLabel, color = BubbleTime, style = MaterialTheme.typography.labelSmall)
            }
            // Conteudo: imagem, tile de documento, ou placeholder de indisponivel.
            when {
                loading && isImage -> MediaLoadingPlaceholder()
                unavailable -> MediaUnavailablePlaceholder(isImage)
                isImage && bitmap != null -> Box(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "$kindLabel (toque para abrir)",
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
                    )
                }
                else -> DocumentTile(fileName = java.io.File(imagePath).name)
            }
            // Legenda opcional (quando difere da etiqueta de origem).
            if (caption.isNotBlank() && !caption.equals(kindLabel, ignoreCase = true)) {
                Text(
                    text = caption,
                    color = BubbleText.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp)
                )
            }
            Text(
                text = if (unavailable) "Arquivo nao esta mais disponivel" else "Toque para abrir",
                color = BubbleTime,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp)
            )
        }
    }
}

/** Placeholder curto enquanto o decode em background (ver [decodeAgentMedia]) nao terminou. */
@Composable
private fun MediaLoadingPlaceholder() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MediaBorder.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MediaAccent
        )
        Text(
            text = "Carregando...",
            color = BubbleText.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 10.dp)
        )
    }
}

/**
 * Placeholder de midia indisponivel (arquivo removido). Vale para imagem e
 * documentos — bloco emoldurado tracejado com icone neutro e rotulo.
 */
@Composable
private fun MediaUnavailablePlaceholder(wasImage: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MediaBorder.copy(alpha = 0.10f))
            .border(1.dp, MediaBorder.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = BubbleTime,
            modifier = Modifier
                .rotate(45f) // "X" improvisado a partir do icone de + do conjunto core
                .size(20.dp)
        )
        Text(
            text = if (wasImage) "Imagem indisponivel" else "Midia indisponivel",
            color = BubbleText.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 10.dp)
        )
    }
}

/**
 * Tile de documento (arquivo nao-imagem disponivel): mostra o nome do arquivo;
 * o toque no card abre no app externo apropriado.
 */
@Composable
private fun DocumentTile(fileName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MediaBorder.copy(alpha = 0.12f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MediaAccent.copy(alpha = 0.20f))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                text = fileName.substringAfterLast('.', "").uppercase().ifBlank { "DOC" }.take(4),
                color = MediaAccent,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
        }
        Text(
            text = fileName,
            color = BubbleText.copy(alpha = 0.9f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            modifier = Modifier.padding(start = 10.dp)
        )
    }
}

/**
 * Bolha provisoria do assistente enquanto o agente processa o pedido.
 * Pontos animados + o que esta sendo processado (label).
 */
/**
 * Cartao do WAV exato que foi fechado para transcricao. A forma de onda foi
 * calculada uma unica vez do trecho, portanto permanece estatica e nao espelha
 * o espectro de escuta continuo da barra inferior.
 */
@Composable
private fun PendingAudioCaptureBubble(capture: PendingAudioCapture) {
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            modifier = Modifier
                .widthIn(max = 220.dp)
                .background(UserBubble.copy(alpha = 0.5f), shape)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { playPendingAudio(capture.wavPath) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Reproduzir trecho de áudio pendente",
                    tint = BubbleText,
                    modifier = Modifier.size(32.dp)
                )
                FrozenWaveform(
                    values = capture.waveform,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = "Áudio • ${formatAudioDuration(capture.durationMs)} • transcrevendo via ${capture.backendLabel}",
                color = BubbleTime,
                style = MaterialTheme.typography.labelSmall,
                fontStyle = FontStyle.Italic,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun FrozenWaveform(values: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.height(28.dp)) {
        if (values.isEmpty()) return@Canvas
        val spacing = 3.dp.toPx()
        val barWidth = (size.width - spacing * (values.size - 1)) / values.size
        val baseLine = size.height / 2f
        values.forEachIndexed { index, value ->
            val barHeight = value.coerceIn(PENDING_AUDIO_MIN_BAR_LEVEL, 1f) * (size.height * 0.46f)
            val x = index * (barWidth + spacing)
            drawRoundRect(
                color = BubbleText.copy(alpha = PENDING_AUDIO_WAVE_ALPHA),
                topLeft = Offset(x, baseLine - barHeight),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight * 2f),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

private fun playPendingAudio(wavPath: String) {
    ChatAudioPlayer.play(wavPath)
}

/**
 * Mantém uma referência forte ao player durante toda a reprodução. Antes o
 * MediaPlayer existia apenas como variável local; qualquer recomposição/GC
 * disparada por uma nova bolha podia finalizá-lo no meio do áudio.
 */
private object ChatAudioPlayer {
    private var activePlayer: android.media.MediaPlayer? = null

    @Synchronized
    fun play(wavPath: String) {
        val nextPlayer = android.media.MediaPlayer()
        val prepared = runCatching {
            nextPlayer.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            nextPlayer.setDataSource(wavPath)
            nextPlayer.setOnCompletionListener { completed ->
                android.util.Log.i("ChatAudioPlayer", "Reprodução concluída: ${java.io.File(wavPath).name}")
                releaseIfActive(completed)
            }
            nextPlayer.setOnErrorListener { failed, what, extra ->
                android.util.Log.w("ChatAudioPlayer", "Falha na reprodução: what=$what extra=$extra")
                releaseIfActive(failed)
                true
            }
            nextPlayer.prepare()
        }.isSuccess
        if (!prepared) {
            nextPlayer.release()
            return
        }

        activePlayer?.runCatching {
            if (isPlaying) stop()
            release()
        }
        activePlayer = nextPlayer
        nextPlayer.start()
        android.util.Log.i("ChatAudioPlayer", "Reprodução iniciada: ${java.io.File(wavPath).name}")
    }

    @Synchronized
    private fun releaseIfActive(player: android.media.MediaPlayer) {
        if (activePlayer === player) activePlayer = null
        runCatching { player.release() }
    }
}

private fun formatAudioDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / MILLIS_PER_SECOND).coerceAtLeast(1)
    return "%d:%02d".format(Locale.US, totalSeconds / SECONDS_PER_MINUTE, totalSeconds % SECONDS_PER_MINUTE)
}

@Composable
private fun ProcessingBubble(title: String = "Processando", label: String) {
    val transition = rememberInfiniteTransition(label = "processing")
    val dots by transition.animateValue(
        initialValue = 0,
        targetValue = 4,
        typeConverter = Int.VectorConverter,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dots"
    )
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(AssistantBubble, shape)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = title + ".".repeat(dots.coerceIn(0, 3)),
                color = BubbleText,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic
            )
            if (label.isNotBlank()) {
                Text(
                    text = label,
                    color = BubbleTime,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/**
 * Barra inferior do chat. Dois modos:
 *  - OUVINDO (state.listening): o campo de texto da lugar ao espectro de voz
 *    ao vivo — feedback de que o microfone e o canal de entrada no momento;
 *  - PARADO: campo de texto + botao de enviar; com o campo vazio o botao
 *    vira microfone para religar a escuta (padrao WhatsApp).
 */
@Composable
fun ChatInputBar(
    listening: Boolean,
    currentMicrophoneGain: Double?,
    onSendText: (String) -> Unit,
    onStartListening: () -> Unit,
    onAttach: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(InputSurface, RoundedCornerShape(24.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        IconButton(onClick = onAttach) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Anexar",
                tint = BubbleTime
            )
        }

        if (listening) {
            ListeningSpectrum(
                currentMicrophoneGain = currentMicrophoneGain,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
            )
        } else {
            var draft by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Mensagem") },
                maxLines = 4,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = BubbleText,
                    unfocusedTextColor = BubbleText,
                    focusedContainerColor = Color(0xFF16263A),
                    unfocusedContainerColor = Color(0xFF16263A),
                    cursorColor = Color(0xFF35D08C),
                    focusedBorderColor = Color(0xFF2C415C),
                    unfocusedBorderColor = Color(0xFF22354C),
                    focusedPlaceholderColor = BubbleTime,
                    unfocusedPlaceholderColor = BubbleTime
                )
            )
            val hasText = draft.isNotBlank()
            IconButton(
                onClick = {
                    if (hasText) {
                        onSendText(draft.trim())
                        draft = ""
                    } else {
                        onStartListening()
                    }
                },
                modifier = Modifier
                    .size(44.dp)
                    .background(Color(0xFF1F8A5F), CircleShape)
            ) {
                Icon(
                    imageVector = if (hasText) Icons.AutoMirrored.Filled.Send else Icons.Filled.PlayArrow,
                    contentDescription = if (hasText) "Enviar" else "Iniciar escuta",
                    tint = Color.White
                )
            }
        }
    }
}

/**
 * Espectro compacto exibido no lugar do campo de texto enquanto ouve. Coleta
 * seu proprio flow de espectro (GatewayRuntime.spectrum(), ~2Hz durante a
 * escuta) em vez de receber via GatewayUiState — isola a alta frequencia de
 * atualizacao a este Canvas, sem recompor o resto da barra/chat a cada tick.
 */
@Composable
private fun ListeningSpectrum(
    currentMicrophoneGain: Double?,
    modifier: Modifier = Modifier
) {
    val values by GatewayRuntime.spectrum().collectAsState()
    Box(
        modifier = modifier.background(Color(0xFF16263A), RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.CenterStart
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            if (values.isEmpty()) return@Canvas
            val spacing = 3.dp.toPx()
            val barWidth = (size.width - spacing * (values.size - 1)) / values.size
            val baseLine = size.height / 2f
            values.forEachIndexed { index, value ->
                val normalized = value.coerceIn(0.06f, 1f)
                val barHeight = normalized * (size.height * 0.46f)
                val x = index * (barWidth + spacing)
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF35D08C), Color(0xFF39D0FF))
                    ),
                    topLeft = Offset(x, baseLine - barHeight),
                    size = androidx.compose.ui.geometry.Size(barWidth, barHeight * 2f),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                )
            }
        }
        // Ganho atual discreto sobre o espectro (regulagem automatica).
        val gainLabel = currentMicrophoneGain
            ?.let { String.format(Locale.US, "%.1fx", it) }
        if (gainLabel != null) {
            Text(
                text = gainLabel,
                color = BubbleTime,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .background(Color(0x66000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
    }
}
