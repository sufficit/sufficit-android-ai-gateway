package com.sufficit.openclaw.gateway

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
import androidx.compose.material.icons.filled.Add
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
import com.sufficit.openclaw.gateway.runtime.ChatMessage
import com.sufficit.openclaw.gateway.runtime.ChatRole
import com.sufficit.openclaw.gateway.runtime.GatewayRuntime
import com.sufficit.openclaw.gateway.runtime.GatewayUiState
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
        items(
            count = messages.size,
            key = { index -> messages[messages.size - 1 - index].id }
        ) { index ->
            val message = messages[messages.size - 1 - index]
            ChatBubble(
                text = message.text,
                role = message.role,
                timeLabel = ChatTimeFormatter.format(Instant.ofEpochMilli(message.atEpochMs))
            )
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

@Composable
private fun ChatBubble(
    text: String,
    role: ChatRole,
    timeLabel: String,
    provisional: Boolean = false
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
