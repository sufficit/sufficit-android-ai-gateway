package com.sufficit.ai.gateway

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Paleta e escala unicas das telas de configuracao, alinhadas ao tema dark
 * do restante do app (dashboard/debug usam a familia #040A12).
 * Toda tela de config deve consumir daqui — nada de Color(0x...) inline.
 */
object ConfigTheme {
    val BgTop = Color(0xFF040A12)
    val BgBottom = Color(0xFF0A1421)
    val Surface = Color(0xFF0E1A28)
    val SurfaceVariant = Color(0xFF16263A)
    val Border = Color(0xFF22354C)

    val TextPrimary = Color(0xFFF1F6FB)
    val TextSecondary = Color(0xFF9FB2C4)
    val TextMuted = Color(0xFF647A8F)

    val Accent = Color(0xFF35D08C)
    val Danger = Color(0xFFFF8A80)

    val RadiusCard = 20.dp
    val RadiusInner = 12.dp
    val ScreenPadding = 16.dp
    val CardPadding = 16.dp
}
