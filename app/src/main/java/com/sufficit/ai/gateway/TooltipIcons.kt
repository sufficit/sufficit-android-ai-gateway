@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.sufficit.ai.gateway

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

@Composable
fun QueueBadgeIcon(
    queueCount: Int,
    active: Boolean,
    tooltip: String,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        val hasQueue = queueCount > 0
        TranscriptTopBadge(
            active = active,
            tooltip = tooltip,
            onDismiss = onDismiss,
            onToggle = onToggle
        ) {
            Icon(
                imageVector = Icons.Filled.Notifications,
                contentDescription = "Fila de transcricao",
                tint = if (hasQueue) Color(0xFFFF8A3D) else Color(0xFF8A939F),
                modifier = Modifier.size(18.dp)
            )
        }
        if (hasQueue) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-3).dp)
                    .zIndex(2f)
                    .background(Color(0xFFD32F2F), shape = RoundedCornerShape(100.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text(
                    text = queueCount.toString(),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun OpenClawDispatchBadgeIcon(
    queueCount: Int,
    active: Boolean,
    tooltip: String,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        val hasQueue = queueCount > 0
        TranscriptTopBadge(
            active = active,
            tooltip = tooltip,
            onDismiss = onDismiss,
            onToggle = onToggle,
            accentColor = if (hasQueue) Color(0xFF2D8CFF) else Color(0xFF8A939F)
        ) {
            Text(
                text = "OC",
                color = if (hasQueue) Color(0xFF2D8CFF) else Color(0xFF8A939F),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
        if (hasQueue) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-3).dp)
                    .zIndex(2f)
                    .background(Color(0xFFD32F2F), shape = RoundedCornerShape(100.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text(
                    text = queueCount.toString(),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun ListeningBadgeIcon(
    listening: Boolean,
    speechDetected: Boolean,
    active: Boolean,
    tooltip: String,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = when {
        speechDetected -> Color(0xFFFF8A3D)
        listening -> Color(0xFF2D8CFF)
        else -> Color(0xFF8A939F)
    }
    TranscriptTopBadge(
        modifier = modifier,
        active = active,
        tooltip = tooltip,
        onDismiss = onDismiss,
        onToggle = onToggle,
        accentColor = tint
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_mic_status),
            contentDescription = if (speechDetected) "Ouvindo fala" else "Monitor de escuta",
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
fun CompactMetricBadge(
    tint: Color,
    active: Boolean,
    tooltip: String,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .size(34.dp)
            .background(
                color = tint.copy(alpha = 0.12f),
                shape = RoundedCornerShape(999.dp)
            )
            .combinedClickable(
                onClick = onToggle,
                onLongClick = onToggle
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
        UnifiedTooltipBubble(
            visible = active,
            text = tooltip,
            width = 188.dp,
            anchorType = TooltipAnchorType.Center,
            onDismiss = onDismiss
        )
    }
}

@Composable
fun TranscriptTopBadge(
    onToggle: () -> Unit,
    active: Boolean,
    tooltip: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = Color(0xFF8A939F),
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.size(34.dp)) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(
                    color = accentColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(999.dp)
                )
                .combinedClickable(
                    onClick = onToggle,
                    onLongClick = onToggle
                )
                .align(Alignment.Center),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
        UnifiedTooltipBubble(
            visible = active,
            text = tooltip,
            width = 188.dp,
            anchorType = TooltipAnchorType.Center,
            onDismiss = onDismiss
        )
    }
}

@Composable
fun FieldGuideInfoIcon(
    tooltip: String,
    modifier: Modifier = Modifier
) {
    TooltipBadgeIcon(
        icon = {
            Text(
                text = "i",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        },
        tooltip = tooltip,
        color = Color(0xFF4F6B8A),
        outerSize = 22.dp,
        innerSize = 17.dp,
        tooltipWidth = 210.dp,
        modifier = modifier.padding(top = 10.dp, end = 10.dp)
    )
}

// StatusIcons, ServiceStatusIcon, BackendStatusIcon, GatewayControlIcon
// were extracted to GatewayStatusIcons.kt

@Composable
fun TooltipBadgeIcon(
    icon: @Composable () -> Unit,
    tooltip: String,
    color: Color,
    outerSize: androidx.compose.ui.unit.Dp = 36.dp,
    innerSize: androidx.compose.ui.unit.Dp = 28.dp,
    tooltipWidth: androidx.compose.ui.unit.Dp = 188.dp,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) {
            delay(2200)
            expanded = false
        }
    }
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(outerSize)
                .combinedClickable(
                    onClick = { expanded = !expanded },
                    onLongClick = { expanded = true }
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(innerSize)
                    .background(color = color, shape = RoundedCornerShape(99.dp)),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
        }
        UnifiedTooltipBubble(
            visible = expanded,
            text = tooltip,
            width = tooltipWidth,
            anchorType = TooltipAnchorType.End,
            onDismiss = { expanded = false }
        )
    }
}

@Composable
fun UnifiedTooltipBubble(
    visible: Boolean,
    text: String,
    width: androidx.compose.ui.unit.Dp,
    anchorType: TooltipAnchorType,
    onDismiss: () -> Unit
) {
    if (!visible) {
        return
    }

    val offset = when (anchorType) {
        TooltipAnchorType.Center -> DpOffset(x = (-width / 2) + 17.dp, y = (-92).dp)
        TooltipAnchorType.End -> DpOffset(x = (-width) + 28.dp, y = (-92).dp)
    }

    DropdownMenu(
        expanded = visible,
        onDismissRequest = onDismiss,
        offset = offset,
        properties = PopupProperties(
            focusable = false,
            dismissOnClickOutside = true,
            clippingEnabled = false
        ),
        modifier = Modifier
            .width(width)
            .background(
                color = Color(0xB3102030),
                shape = RoundedCornerShape(14.dp)
            )
    ) {
        Box(
            modifier = Modifier
                .width(width)
                .clickable { onDismiss() }
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                text = text,
                modifier = Modifier.width(width - 20.dp),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Start
            )
        }
    }
}

enum class TooltipAnchorType {
    Center,
    End
}
