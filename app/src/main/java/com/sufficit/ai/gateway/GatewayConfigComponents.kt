package com.sufficit.ai.gateway

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// DictionaryPage extracted to GatewayDictionaryPage.kt
// DeviceGuideCard, buildGuideRecommendationLabel, resolveFieldGuideTooltip,
// translateGuideStatus, translateExperienceLevel extracted to GatewayDeviceGuideSupport.kt

@Composable
fun ConfigSection(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = ConfigTheme.Surface,
                shape = RoundedCornerShape(ConfigTheme.RadiusCard)
            )
            .border(
                width = 1.dp,
                color = ConfigTheme.Border,
                shape = RoundedCornerShape(ConfigTheme.RadiusCard)
            )
            .padding(ConfigTheme.CardPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = ConfigTheme.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ConfigTheme.TextSecondary
                )
            }
        }
        content()
    }
}

@Composable
fun SettingToggleRow(
    title: String,
    supportingText: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                color = ConfigTheme.TextPrimary,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = supportingText,
                color = ConfigTheme.TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
fun MetadataChip(label: String, value: String) {
    Column(
        modifier = Modifier
            .background(
                color = ConfigTheme.SurfaceVariant,
                shape = RoundedCornerShape(ConfigTheme.RadiusInner)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            color = ConfigTheme.TextMuted,
            style = MaterialTheme.typography.labelMedium
        )
        Text(
            text = value,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = ConfigTheme.TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun configTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = ConfigTheme.TextPrimary,
    unfocusedTextColor = ConfigTheme.TextPrimary,
    disabledTextColor = ConfigTheme.TextMuted,
    focusedContainerColor = ConfigTheme.SurfaceVariant,
    unfocusedContainerColor = ConfigTheme.SurfaceVariant,
    disabledContainerColor = ConfigTheme.Surface,
    cursorColor = ConfigTheme.Accent,
    focusedBorderColor = ConfigTheme.Accent,
    unfocusedBorderColor = ConfigTheme.Border,
    focusedLabelColor = ConfigTheme.Accent,
    unfocusedLabelColor = ConfigTheme.TextSecondary,
    focusedSupportingTextColor = ConfigTheme.TextSecondary,
    unfocusedSupportingTextColor = ConfigTheme.TextSecondary,
    focusedPlaceholderColor = ConfigTheme.TextMuted,
    unfocusedPlaceholderColor = ConfigTheme.TextMuted
)

@Composable
fun SliderSettingRow(
    title: String,
    value: Float,
    valueText: String,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    supportingText: String,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = ConfigTheme.SurfaceVariant,
                shape = RoundedCornerShape(ConfigTheme.RadiusInner)
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = ConfigTheme.TextPrimary,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = valueText,
                color = ConfigTheme.Accent,
                style = MaterialTheme.typography.labelLarge
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps
        )
        Text(
            text = supportingText,
            color = ConfigTheme.TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun OptionalSliderSettingRow(
    title: String,
    rawValue: String,
    effectiveValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    supportingText: String,
    onValueChange: (Float) -> Unit,
    onReset: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SliderSettingRow(
            title = title,
            value = effectiveValue.coerceIn(valueRange.start, valueRange.endInclusive),
            valueText = rawValue.ifBlank { "Automatico" },
            valueRange = valueRange,
            steps = steps,
            supportingText = supportingText,
            onValueChange = onValueChange
        )
        if (rawValue.isNotBlank()) {
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Usar automatico")
            }
        }
    }
}

data class LocalModelOption(
    val name: String,
    val sizeBytes: Long,
    val sha256: String,
    val remoteSizeBytes: Long?,
    val isInvalid: Boolean,
    val status: String
)

fun formatHistoryTimestamp(epochMillis: Long): String {
    return java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
        .withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.ofEpochMilli(epochMillis))
}
