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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.sufficit.ai.gateway.runtime.ChatMessage
import com.sufficit.ai.gateway.runtime.ChatRole
import com.sufficit.ai.gateway.runtime.GatewayRuntime
import com.sufficit.ai.gateway.runtime.GatewayUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
private val InputSurface = Color(0xFF101E2E)

private val ChatTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.US).withZone(ZoneId.systemDefault())

@Composable
fun ChatMessagesList(
    state: GatewayUiState,
    modifier: Modifier = Modifier
) {
    val messages by GatewayRuntime.chatMessages().collectAsState()
    val partialTranscript = state.currentTranscript.trim()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // Auto-scroll: mensagem nova rola a lista para o rodape — mas so se o
    // usuario ja estiver perto do fim (ler historico antigo nao pode ser
    // interrompido por puxao de scroll). Com reverseLayout, indice 0 = rodape.
    androidx.compose.runtime.LaunchedEffect(messages.size, partialTranscript.isNotBlank()) {
        if (listState.firstVisibleItemIndex <= 1) {
            listState.animateScrollToItem(0)
        }
    }

    // reverseLayout ancora o conteudo embaixo (estilo WhatsApp) e mantem a
    // lista "grudada" na mensagem mais recente sem precisar de scroll manual.
    LazyColumn(
        modifier = modifier,
        state = listState,
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 12.dp,
            vertical = 8.dp
        )
    ) {
        // Com reverseLayout, o item de indice 0 fica NO RODAPE: a bolha
        // parcial (fala sendo transcrita) entra primeiro, depois o historico
        // do mais novo para o mais antigo.
        if (partialTranscript.isNotBlank()) {
            item(key = "partial") {
                ChatBubble(
                    text = partialTranscript,
                    role = ChatRole.USER,
                    timeLabel = "transcrevendo...",
                    provisional = true
                )
            }
        }
        // Balao do assistente "processando": aparece enquanto o agente trabalha
        // no pedido, com o que esta sendo processado.
        if (state.assistantProcessing) {
            item(key = "processing") {
                ProcessingBubble(label = state.assistantProcessingLabel)
            }
        }
        items(
            count = messages.size,
            key = { index -> messages[messages.size - 1 - index].id }
        ) { index ->
            val message = messages[messages.size - 1 - index]
            if (message.role == ChatRole.SYSTEM) {
                SystemMarker(text = message.text)
            } else {
                ChatBubble(
                    text = message.text,
                    role = message.role,
                    timeLabel = ChatTimeFormatter.format(Instant.ofEpochMilli(message.atEpochMs)),
                    details = message.details
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

@Composable
private fun ChatBubble(
    text: String,
    role: ChatRole,
    timeLabel: String,
    provisional: Boolean = false,
    details: String? = null
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
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    color = if (isUser) UserBubble else AssistantBubble,
                    shape = shape
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = text,
                color = if (provisional) BubbleText.copy(alpha = 0.75f) else BubbleText,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = if (provisional) FontStyle.Italic else FontStyle.Normal
            )
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

/**
 * Bolha provisoria do assistente enquanto o agente processa o pedido.
 * Pontos animados + o que esta sendo processado (label).
 */
@Composable
private fun ProcessingBubble(label: String) {
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
                text = "Processando" + ".".repeat(dots.coerceIn(0, 3)),
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
    state: GatewayUiState,
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

        if (state.listening) {
            ListeningSpectrum(
                state = state,
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

/** Espectro compacto exibido no lugar do campo de texto enquanto ouve. */
@Composable
private fun ListeningSpectrum(
    state: GatewayUiState,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(Color(0xFF16263A), RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.CenterStart
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            val values = state.spectrum
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
        val gainLabel = state.currentMicrophoneGain
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
