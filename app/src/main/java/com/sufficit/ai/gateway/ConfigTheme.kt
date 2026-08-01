package com.sufficit.ai.gateway

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Paleta e escala unicas das telas de configuracao, alinhadas ao tema dark
 * do restante do app (dashboard/debug usam a familia #040A12).
 * Toda tela de config deve consumir daqui — nada de Color(0x...) inline.
 */
object ConfigTheme {
    val BgTop = Color(0xFF050B14)
    val BgBottom = Color(0xFF0B1826)
    val Surface = Color(0xFF102033)
    val SurfaceRaised = Color(0xFF172B42)
    val SurfaceVariant = Color(0xFF1B314A)
    val Border = Color(0xFF2A435D)

    val TextPrimary = Color(0xFFF1F6FB)
    val TextSecondary = Color(0xFFB4C5D6)
    val TextMuted = Color(0xFF8298AD)

    val Accent = Color(0xFF35D08C)
    val AccentBlue = Color(0xFF55B8F5)
    val AccentGold = Color(0xFFFFC857)
    val AccentPurple = Color(0xFFC8A5FF)
    val AccentTeal = Color(0xFF5DD7C6)
    val Danger = Color(0xFFFF8A80)

    val RadiusCard = 16.dp
    val RadiusInner = 12.dp
    val ScreenPadding = 16.dp
    val CardPadding = 16.dp
}
