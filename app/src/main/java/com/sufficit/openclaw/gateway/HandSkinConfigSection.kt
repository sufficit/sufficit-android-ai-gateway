package com.sufficit.openclaw.gateway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sufficit.openclaw.gateway.runtime.GatewayRuntime

/** Escolha da skin das maos desenhadas sobre a tela. */
@Composable
fun HandSkinConfigSection() {
    val context = LocalContext.current
    val skinId by GatewayRuntime.handSkin().collectAsState()
    val selected = HandGloveSkin.fromId(skinId)

    ConfigSection(
        title = "Maos na tela",
        subtitle = "Estilo das maos desenhadas quando a camera detecta gestos"
    ) {
        Text(
            text = "Aparecem em qualquer tela enquanto a camera de gestos estiver ativa.",
            style = MaterialTheme.typography.bodySmall,
            color = ConfigTheme.TextSecondary
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            HandGloveSkin.entries.forEach { skin ->
                val onClick = {
                    HandGloveSkinStore.save(context, skin)
                    GatewayRuntime.setHandSkin(skin.id)
                }
                if (skin == selected) {
                    Button(onClick = onClick, modifier = Modifier.weight(1f)) {
                        Text(skin.label)
                    }
                } else {
                    OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f)) {
                        Text(skin.label)
                    }
                }
            }
        }
    }
}
