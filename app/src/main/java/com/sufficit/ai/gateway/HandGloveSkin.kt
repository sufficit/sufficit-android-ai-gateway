package com.sufficit.ai.gateway

import android.content.Context

enum class HandGloveSkin(val id: String, val label: String) {
    CARTOON("cartoon", "Desenho Animado"),
    HOLOGRAM("hologram", "Holograma");

    companion object {
        val DEFAULT = CARTOON

        fun fromId(id: String?): HandGloveSkin =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

object HandGloveSkinStore {
    private const val PREFS = "hand_overlay"
    private const val KEY_SKIN = "skin"

    fun load(context: Context): HandGloveSkin {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SKIN, null)
        return HandGloveSkin.fromId(id)
    }

    fun save(context: Context, skin: HandGloveSkin) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SKIN, skin.id)
            .apply()
    }
}
