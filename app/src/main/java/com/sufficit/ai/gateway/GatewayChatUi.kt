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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.activity.compose.BackHandler
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.sufficit.ai.gateway.runtime.ChatMessage
import com.sufficit.ai.gateway.runtime.ChatAttachment
import com.sufficit.ai.gateway.runtime.ChatAgentActivityState
import com.sufficit.ai.gateway.runtime.ChatAudioState
import com.sufficit.ai.gateway.runtime.ChatDeliveryState
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
private val AuditInfoText = Color(0xFF8FCBFF)
private val AuditSuccessText = Color(0xFF83E6B7)
private val AuditWarningText = Color(0xFFFFCD7A)
private val AuditHoldText = Color(0xFFD6B8FF)
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
private val AuditTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM HH:mm:ss", Locale.US).withZone(ZoneId.systemDefault())

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
    val hasPersistentAgentActivity = messages.any { message ->
        message.agentActivityState != null &&
            message.agentActivityState != ChatAgentActivityState.FAILED
    }
    val latestMessageVersion = messages.lastOrNull()?.let { message ->
        Triple(
            message.id,
            message.agentActivityUpdatedAtEpochMs ?: message.deliveryUpdatedAtEpochMs,
            message.text
        )
    }
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
    androidx.compose.runtime.LaunchedEffect(
        messages.size,
        latestMessageVersion,
        partialTranscript.isNotBlank(),
        pendingAudioCaptures.size,
        assistantProcessing
    ) {
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
        if (assistantProcessing && !hasPersistentAgentActivity) {
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
            } else if (
                message.role == ChatRole.ASSISTANT &&
                    message.agentActivityState != null
            ) {
                AgentActivityMessage(
                    message = message,
                    timeLabel = ChatTimeFormatter.format(Instant.ofEpochMilli(message.atEpochMs))
                )
            } else if (
                message.role == ChatRole.ASSISTANT &&
                    message.deliveryState != null &&
                    message.deliverySourceTexts.isNotEmpty()
            ) {
                AgentDeliveryAuditMessage(
                    message = message,
                    timeLabel = ChatTimeFormatter.format(Instant.ofEpochMilli(message.atEpochMs))
                )
            } else {
                val timeLabel = ChatTimeFormatter.format(Instant.ofEpochMilli(message.atEpochMs))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (
                        message.text.isNotBlank() || message.details != null ||
                        message.audioPath != null || message.audioState != null
                    ) {
                        ChatBubble(
                            text = message.text,
                            role = message.role,
                            timeLabel = timeLabel,
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
                    message.attachments.forEach { attachment ->
                        AgentAttachmentCard(attachment, timeLabel)
                    }
                }
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

/**
 * Bolha operacional do agente. Estados ativos animam; falhas permanecem no
 * histórico com causa e horário para o usuário nunca precisar inferir silêncio.
 */
@Composable
private fun AgentActivityMessage(
    message: ChatMessage,
    timeLabel: String
) {
    val state = message.agentActivityState ?: return
    val sourceLabel = message.deliverySourceTexts
        .joinToString(" ")
        .trim()
        .take(180)
    if (state != ChatAgentActivityState.FAILED) {
        val title = when (state) {
            ChatAgentActivityState.QUEUED -> "Preparando"
            ChatAgentActivityState.PROCESSING -> "Processando"
            ChatAgentActivityState.EXECUTING_ACTION -> "Executando ação"
            ChatAgentActivityState.FAILED -> error("Estado tratado acima")
        }
        ProcessingBubble(title = title, label = sourceLabel)
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        ChatBubble(
            text = message.text,
            role = ChatRole.ASSISTANT,
            timeLabel = timeLabel
        )
        Text(
            text = "Falha registrada • envie novamente para tentar de novo",
            color = AudioErrorText,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 12.dp, top = 2.dp)
        )
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

private data class DeliveryAuditPresentation(
    val title: String,
    val color: Color
)

private fun deliveryAuditPresentation(
    state: ChatDeliveryState,
    reason: String?
): DeliveryAuditPresentation = when (state) {
    ChatDeliveryState.TRANSCRIBING ->
        DeliveryAuditPresentation("Transcrevendo áudio", AuditInfoText)
    ChatDeliveryState.TRANSCRIBED ->
        DeliveryAuditPresentation("Transcrito · aguardando pausa para envio", AuditInfoText)
    ChatDeliveryState.SENT_TO_AGENT ->
        DeliveryAuditPresentation("Enviado ao agente · aguardando decisão", AuditInfoText)
    ChatDeliveryState.IGNORED ->
        DeliveryAuditPresentation("Ignorado · ${deliveryReasonLabel(reason)}", AuditWarningText)
    ChatDeliveryState.HELD_FOR_REVIEW ->
        DeliveryAuditPresentation("Retido · ${deliveryReasonLabel(reason)}", AuditHoldText)
    ChatDeliveryState.AGENT_REPLIED ->
        DeliveryAuditPresentation("Respondido pelo agente", AuditSuccessText)
    ChatDeliveryState.ACTION_EXECUTED ->
        DeliveryAuditPresentation("Ação local solicitada pelo agente", AuditSuccessText)
    ChatDeliveryState.NO_AGENT_REPLY ->
        DeliveryAuditPresentation("Enviado, mas sem resposta do agente", AuditWarningText)
    ChatDeliveryState.FAILED ->
        DeliveryAuditPresentation("Falha no processamento", AudioErrorText)
}

private fun deliveryReasonLabel(reason: String?): String = when (reason?.trim()?.lowercase()) {
    "ambient_not_directed_to_agent" -> "sem chamada direta"
    "ambient_conversation" -> "conversa ambiente"
    "different_speaker" -> "voz não confirmada"
    "multi_voice_overlap" -> "sobreposição de vozes"
    "wake_confirmation_required", "idle_confirmation_window" -> "aguardando confirmação"
    "neutral_marker_only" -> "trecho sem fala útil"
    "empty_transcript" -> "sem texto reconhecido"
    "final_agent_empty" -> "modelo não gerou texto"
    "agent_error" -> "erro do agente"
    "transcription_failed" -> "falha na transcrição"
    "transcription_interrupted" -> "transcrição interrompida"
    "wake_term" -> "chamada reconhecida"
    "follow_up_window" -> "continuação da conversa"
    "accepted" -> "chamada aceita"
    else -> reason?.trim()?.replace('_', ' ')?.ifBlank { null } ?: "sem motivo informado"
}

private fun deliveryReasonDetail(reason: String?): String = when (reason?.trim()?.lowercase()) {
    "ambient_not_directed_to_agent" ->
        "O trecho não trouxe uma chamada direta válida ao assistente; foi mantido apenas como histórico da sala."
    "ambient_conversation" ->
        "O pré-agente classificou a fala como conversa ambiente, não como pedido ao assistente."
    "different_speaker" ->
        "A verificação de voz não associou o trecho com segurança ao perfil configurado."
    "multi_voice_overlap" ->
        "Havia mais de uma voz no mesmo trecho; o envio foi retido para evitar uma resposta fora de contexto."
    "wake_confirmation_required", "idle_confirmation_window" ->
        "A fala chegou fora da janela de continuidade e precisa de uma chamada explícita, como “xuxu”."
    "final_agent_empty" ->
        "O pré-agente encaminhou a fala, mas o modelo não devolveu texto nem uma ação executável."
    "agent_error" ->
        "O gateway recebeu uma falha do agente. O detalhe técnico fica registrado no status do aplicativo."
    "transcription_failed" ->
        "A transcrição falhou antes de chegar ao OpenClaw."
    "transcription_interrupted" ->
        "O aplicativo foi reiniciado ou a captura foi interrompida durante a transcrição."
    "wake_term" -> "A chamada explícita foi reconhecida e o trecho foi encaminhado ao agente."
    "follow_up_window" -> "O trecho foi aceito como continuação de uma chamada recente ao agente."
    "accepted" -> "O pré-agente aceitou o trecho e o encaminhou ao agente final."
    else -> "A decisão foi registrada pelo gateway para auditoria."
}

/** Registro único do agente, inserido logo depois das bolhas que formaram o turno. */
@Composable
private fun AgentDeliveryAuditMessage(
    message: ChatMessage,
    timeLabel: String
) {
    val state = message.deliveryState ?: return
    val presentation = deliveryAuditPresentation(state, message.deliveryReason)
    Column(modifier = Modifier.fillMaxWidth()) {
        ChatBubble(
            text = message.text,
            role = ChatRole.ASSISTANT,
            timeLabel = timeLabel
        )
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterStart
        ) {
            Column(modifier = Modifier.widthIn(max = 300.dp)) {
                Box(
                    modifier = Modifier
                        .padding(start = 20.dp)
                        .size(width = 2.dp, height = 8.dp)
                        .background(presentation.color.copy(alpha = 0.55f))
                )
                DeliveryAuditPanel(
                    state = state,
                    reason = message.deliveryReason,
                    tags = message.deliveryTags,
                    updatedAtEpochMs = message.deliveryUpdatedAtEpochMs,
                    sourceTexts = message.deliverySourceTexts
                )
            }
        }
    }
}

/**
 * Detalhes do turno como cartão irmão do balão do agente. Cor nunca é o único
 * sinal: o rótulo explica a decisão e o toque revela conjunto, código e hora.
 */
@Composable
private fun DeliveryAuditPanel(
    state: ChatDeliveryState,
    reason: String?,
    tags: List<String>,
    updatedAtEpochMs: Long?,
    sourceTexts: List<String>
) {
    val presentation = deliveryAuditPresentation(state, reason)
    val auditKey = listOf(state.name, reason.orEmpty(), tags.joinToString("|"), updatedAtEpochMs ?: 0L)
        .joinToString("#")
    var expanded by rememberSaveable(auditKey) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(presentation.color.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
            .clickable(
                onClickLabel = if (expanded) {
                    "Recolher detalhes do turno consolidado"
                } else {
                    "Ver detalhes do turno consolidado"
                },
                role = Role.Button
            ) { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "${presentation.title} · ${sourceTexts.size} ${if (sourceTexts.size == 1) "trecho" else "trechos"}",
                color = presentation.color,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = presentation.color,
                modifier = Modifier.size(20.dp)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(bottom = 6.dp)) {
                Text(
                    text = "Mensagens enviadas em conjunto:",
                    color = BubbleText,
                    style = MaterialTheme.typography.labelMedium
                )
                sourceTexts.forEachIndexed { index, sourceText ->
                    Text(
                        text = "${index + 1}. $sourceText",
                        color = BubbleText.copy(alpha = 0.94f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Text(
                    text = "Motivo: ${deliveryReasonDetail(reason)}",
                    color = BubbleText.copy(alpha = 0.94f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
                reason?.trim()?.takeIf { it.isNotBlank() }?.let { code ->
                    Text(
                        text = "Código: $code",
                        color = BubbleTime,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (tags.isNotEmpty()) {
                    Text(
                        text = "Sinais: ${tags.joinToString(", ")}",
                        color = BubbleTime,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                updatedAtEpochMs?.takeIf { it > 0L }?.let { at ->
                    Text(
                        text = "Atualizado: ${AuditTimeFormatter.format(Instant.ofEpochMilli(at))}",
                        color = BubbleTime,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
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
    val audioTapModifier = audioPath?.let { wavPath ->
        Modifier
            .heightIn(min = 48.dp)
            .clickable(
                onClickLabel = audioDurationMs?.let { "Reproduzir áudio de ${formatAudioDuration(it)}" }
                    ?: "Reproduzir áudio",
                role = Role.Button
            ) {
                playPendingAudio(wavPath)
            }
    } ?: Modifier
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
                // A bolha e o controle de reprodução. Mantém uma área grande
                // de toque e remove o botão/linha de áudio redundante.
                .then(audioTapModifier)
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
            // Estados normais ficam compactos junto ao horário, como os tiques
            // do WhatsApp: um após transcrever e dois após entregar ao agente.
            // A falha continua explícita para não se confundir com uma entrega.
            if (audioState == ChatAudioState.ERROR) {
                Text(
                    text = "Falha na transcrição${audioError?.let { ": $it" }.orEmpty()}",
                    color = AudioErrorText,
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
            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (audioPath != null && audioDurationMs != null) {
                    Text(
                        text = formatAudioDuration(audioDurationMs),
                        color = BubbleTime,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Text(
                    text = timeLabel,
                    color = BubbleTime,
                    style = MaterialTheme.typography.labelSmall
                )
                if (isUser) {
                    when (audioState) {
                        ChatAudioState.TRANSCRIBED, ChatAudioState.SENDING -> Text(
                            text = if (audioState == ChatAudioState.TRANSCRIBED) "✓" else "✓✓",
                            color = BubbleTime,
                            style = if (audioState == ChatAudioState.SENDING) {
                                // O par de tiques e um unico indicador: fecha
                                // levemente o kerning para nao parecer dois
                                // estados independentes ao lado do horario.
                                MaterialTheme.typography.labelSmall.copy(letterSpacing = (-0.12).em)
                            } else {
                                MaterialTheme.typography.labelSmall
                            }
                        )
                        else -> Unit
                    }
                }
            }
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

/** Anexo remoto compacto, tocavel e separado do texto falado da resposta. */
@Composable
private fun AgentAttachmentCard(attachment: ChatAttachment, timeLabel: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val rawUri = attachment.uri.trim()
    val localPath = when {
        rawUri.startsWith("file://", ignoreCase = true) ->
            android.net.Uri.parse(rawUri).path
        rawUri.startsWith("/") -> rawUri
        else -> null
    }
    val localFile = localPath?.let { java.io.File(it) }
    val isLocalAvailable = localFile?.isFile == true
    val remoteScheme = runCatching { android.net.Uri.parse(rawUri).scheme?.lowercase() }.getOrNull()
    val remoteOpenable = remoteScheme in setOf("https", "http", "content")
    val openable = isLocalAvailable || remoteOpenable
    val isLocalImage = isLocalAvailable && (
        attachment.mimeType?.startsWith("image/", ignoreCase = true) == true ||
            listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp")
                .any { localFile!!.name.lowercase().endsWith(it) }
        )
    if (isLocalImage) {
        AgentMediaCard(
            imagePath = requireNotNull(localFile).absolutePath,
            caption = attachment.name,
            timeLabel = timeLabel
        )
        return
    }

    val kindLabel = when {
        attachment.mimeType?.startsWith("image/", ignoreCase = true) == true -> "IMAGEM"
        attachment.mimeType == "application/pdf" -> "PDF"
        attachment.kind.isNotBlank() -> attachment.kind.uppercase(Locale.getDefault()).take(24)
        else -> "ANEXO"
    }
    val openModifier = if (!openable) Modifier else Modifier.clickable(
        role = Role.Button,
        onClick = {
            runCatching {
                val uri = if (isLocalAvailable) {
                    androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        requireNotNull(localFile)
                    )
                } else {
                    android.net.Uri.parse(rawUri)
                }
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, attachment.mimeType ?: "*/*")
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }
    )
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MediaFrame)
                .border(1.dp, MediaBorder, RoundedCornerShape(14.dp))
                .then(openModifier)
                .semantics {
                    contentDescription = if (openable) {
                        "Abrir $kindLabel ${attachment.name}"
                    } else {
                        "$kindLabel ${attachment.name} indisponivel"
                    }
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MediaAccent.copy(alpha = 0.18f))
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            ) {
                Text(
                    text = kindLabel,
                    color = MediaAccent,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.name,
                    color = BubbleText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        attachment.sizeBytes?.takeIf { it > 0L }?.let {
                            append(formatAttachmentBytes(it))
                            append(" • ")
                        }
                        append(if (openable) "Toque para abrir" else "Indisponivel neste aparelho")
                    },
                    color = BubbleTime,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Text(text = timeLabel, color = BubbleTime, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun formatAttachmentBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(Locale.US, bytes / 1024.0)
    else -> "$bytes B"
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
 *  - OUVINDO (nivel 2/state.listening): o campo de texto da lugar ao espectro de voz
 *    ao vivo — feedback de que o microfone e o canal de entrada no momento;
 *  - TEXTO/STANDBY: campo de texto + botao de enviar; o monitor local de
 *    wake word (nivel 1) continua ativo sem exibir o espectro ambiente.
 */
@Composable
fun ChatInputBar(
    ambientListening: Boolean,
    isActivePage: Boolean,
    currentMicrophoneGain: Double?,
    onSendText: (String) -> Unit,
    onStartListening: () -> Unit,
    onSwitchToTextInput: () -> Unit,
    onAttach: () -> Unit,
    modifier: Modifier = Modifier
) {
    var textInputRequested by rememberSaveable { mutableStateOf(!ambientListening) }
    var textFieldFocused by remember { mutableStateOf(false) }
    var requestTextFieldFocus by remember { mutableStateOf(false) }
    val textFieldFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardVisible = rememberKeyboardVisible()
    val showListeningSpectrum = ambientListening && !textInputRequested

    // Se a captura for iniciada externamente (botao, API ou wake flow), o
    // espectro volta a representar o modo real. Ao sair desta pagina, o modo
    // texto nao pode continuar bloqueando a tela de depuracao de gestos.
    androidx.compose.runtime.LaunchedEffect(ambientListening) {
        if (ambientListening) {
            textInputRequested = false
            requestTextFieldFocus = false
            focusManager.clearFocus()
            GatewayRuntime.setTextInputModeActive(false)
        }
    }
    // O campo visivel nao significa que o usuario esta digitando: voz pausada
    // e standby tambem exibem o campo. Gestos so ficam bloqueados enquanto o
    // editor realmente tem foco (teclado/entrada de texto em uso).
    androidx.compose.runtime.LaunchedEffect(isActivePage, textFieldFocused, keyboardVisible) {
        val typingActive = isActivePage && textFieldFocused && keyboardVisible
        GatewayRuntime.setTextInputModeActive(typingActive)
        android.util.Log.i(
            "ChatInputBar",
            "Gesture typing guard: active=$typingActive " +
                "focus=$textFieldFocused keyboard=$keyboardVisible page=$isActivePage"
        )
    }
    BackHandler(enabled = isActivePage && textFieldFocused) {
        focusManager.clearFocus()
        GatewayRuntime.setTextInputModeActive(false)
    }
    androidx.compose.runtime.LaunchedEffect(showListeningSpectrum, requestTextFieldFocus) {
        if (!showListeningSpectrum && requestTextFieldFocus) {
            textFieldFocusRequester.requestFocus()
            requestTextFieldFocus = false
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { GatewayRuntime.setTextInputModeActive(false) }
    }

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

        if (showListeningSpectrum) {
            ListeningSpectrum(
                currentMicrophoneGain = currentMicrophoneGain,
                onSwitchToTextInput = {
                    textInputRequested = true
                    requestTextFieldFocus = true
                    onSwitchToTextInput()
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            )
        } else {
            var draft by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(textFieldFocusRequester)
                    .onFocusChanged { textFieldFocused = it.isFocused },
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
                        focusManager.clearFocus()
                        GatewayRuntime.setTextInputModeActive(false)
                    } else {
                        textInputRequested = false
                        focusManager.clearFocus()
                        GatewayRuntime.setTextInputModeActive(false)
                        onStartListening()
                    }
                },
                modifier = Modifier
                    .size(48.dp)
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

@Composable
private fun rememberKeyboardVisible(): Boolean {
    val view = LocalView.current
    var visible by remember(view) { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(view) {
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            visible = ViewCompat.getRootWindowInsets(view)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        listener.onGlobalLayout()
        onDispose {
            if (view.viewTreeObserver.isAlive) {
                view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            }
        }
    }
    return visible
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
    onSwitchToTextInput: () -> Unit,
    modifier: Modifier = Modifier
) {
    val values by GatewayRuntime.spectrum().collectAsState()
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color(0xFF16263A), shape)
            .clickable(
                role = Role.Button,
                onClickLabel = "Alternar para digitacao",
                onClick = onSwitchToTextInput
            )
            .semantics {
                contentDescription = "Entrada de audio ativa. Toque para digitar."
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 52.dp, top = 6.dp, bottom = 6.dp)
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
        Text(
            text = "ABC",
            color = BubbleText,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
                .background(Color(0xFF22354C), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}
