package com.sufficit.openclaw.gateway

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sufficit.openclaw.gateway.config.GatewaySettingsStore
import java.util.Locale

/** Full-screen dictionary page for transcription terms and correction rules. */
@Composable
fun DictionaryPage(
    colloquialNormalizationStrengthInput: String,
    transcriptionTermsInput: String,
    transcriptionDictionaryInput: String,
    onColloquialNormalizationStrengthChange: (String) -> Unit,
    onTranscriptionTermsChange: (String) -> Unit,
    onTranscriptionDictionaryChange: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ConfigTheme.BgTop)
            .padding(ConfigTheme.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Dicionario",
                    tint = ConfigTheme.TextSecondary
                )
                Text(
                    text = "Dicionario de transcricao",
                    style = MaterialTheme.typography.headlineMedium,
                    color = ConfigTheme.TextPrimary
                )
            }
        }
        item {
            SliderSettingRow(
                title = "Agressividade da normalizacao coloquial",
                value = colloquialNormalizationStrengthInput.replace(',', '.').toFloatOrNull()
                    ?.coerceIn(0f, 1f)
                    ?: GatewaySettingsStore.DEFAULT_COLLOQUIAL_NORMALIZATION_STRENGTH.toFloat(),
                valueText = when {
                    (colloquialNormalizationStrengthInput.replace(',', '.').toFloatOrNull()
                        ?: GatewaySettingsStore.DEFAULT_COLLOQUIAL_NORMALIZATION_STRENGTH.toFloat()) <= 0.05f -> "Desligada"
                    (colloquialNormalizationStrengthInput.replace(',', '.').toFloatOrNull()
                        ?: GatewaySettingsStore.DEFAULT_COLLOQUIAL_NORMALIZATION_STRENGTH.toFloat()) < 0.34f -> "Baixa"
                    (colloquialNormalizationStrengthInput.replace(',', '.').toFloatOrNull()
                        ?: GatewaySettingsStore.DEFAULT_COLLOQUIAL_NORMALIZATION_STRENGTH.toFloat()) < 0.67f -> "Media"
                    else -> "Alta"
                },
                valueRange = 0f..1f,
                steps = 19,
                supportingText = "Controla quanto o app tenta corrigir fala coloquial automaticamente antes do dicionario manual.",
                onValueChange = {
                    onColloquialNormalizationStrengthChange(String.format(Locale.US, "%.2f", it))
                }
            )
        }
        item {
            Text(
                text = "Use esta tela para forcar termos da empresa e corrigir palavras reconhecidas com frequencia errada.",
                color = ConfigTheme.TextSecondary
            )
        }
        item {
            OutlinedTextField(
                value = transcriptionTermsInput,
                onValueChange = onTranscriptionTermsChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                label = { Text("Termos preferidos") },
                supportingText = {
                    Text("Um por linha. No modo remoto vai junto como dica; no local serve como memoria de referencia para configuracao.")
                },
                colors = configTextFieldColors()
            )
        }
        item {
            OutlinedTextField(
                value = transcriptionDictionaryInput,
                onValueChange = onTranscriptionDictionaryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
                label = { Text("Regras de correcao") },
                supportingText = {
                    Text("Uma por linha. Formatos aceitos: errado => certo, errado -> certo ou errado = certo")
                },
                colors = configTextFieldColors()
            )
        }
        item {
            MetadataChip(
                "Exemplo",
                "suficit => Sufficit\nopen claw => OpenClaw\ncastrum data => castrum-data"
            )
        }
    }
}
