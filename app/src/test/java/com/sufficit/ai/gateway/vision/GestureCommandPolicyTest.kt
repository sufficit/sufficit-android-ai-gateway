package com.sufficit.ai.gateway.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GestureCommandPolicyTest {
    @Test
    fun voicePausedAllowsOnlyResumeWhileAssistantIsSilent() {
        assertEquals(
            GestureCommandIds.INDEX_UP,
            filter(GestureCommandIds.INDEX_UP, listening = false)
        )
        assertNull(filter(GestureCommandIds.OPEN_HAND, listening = false))
        assertNull(filter(GestureCommandIds.FIST, listening = false))
    }

    @Test
    fun ambientListeningAllowsOnlyStopWhileAssistantIsSilent() {
        assertEquals(
            GestureCommandIds.FIST,
            filter(GestureCommandIds.FIST, listening = true)
        )
        assertNull(filter(GestureCommandIds.INDEX_UP, listening = true))
        assertNull(filter(GestureCommandIds.OPEN_HAND, listening = true))
    }

    @Test
    fun assistantSpeechEnablesOpenHandAtEitherListeningLevel() {
        assertEquals(
            GestureCommandIds.OPEN_HAND,
            filter(
                gestureId = GestureCommandIds.OPEN_HAND,
                listening = false,
                assistantSpeaking = true
            )
        )
        assertEquals(
            GestureCommandIds.OPEN_HAND,
            filter(
                gestureId = GestureCommandIds.OPEN_HAND,
                listening = true,
                assistantSpeaking = true
            )
        )
    }

    @Test
    fun focusedTextEditorBlocksEveryGesture() {
        listOf(
            GestureCommandIds.OPEN_HAND,
            GestureCommandIds.INDEX_UP,
            GestureCommandIds.FIST
        ).forEach { gestureId ->
            assertNull(
                filter(
                    gestureId = gestureId,
                    listening = false,
                    assistantSpeaking = true,
                    textInputModeActive = true
                )
            )
        }
    }

    @Test
    fun pageOutsideChatBlocksEveryGesture() {
        listOf(
            GestureCommandIds.OPEN_HAND,
            GestureCommandIds.INDEX_UP,
            GestureCommandIds.FIST
        ).forEach { gestureId ->
            assertNull(
                filter(
                    gestureId = gestureId,
                    listening = false,
                    assistantSpeaking = true,
                    interactionActive = false
                )
            )
        }
    }

    private fun filter(
        gestureId: String,
        listening: Boolean,
        assistantSpeaking: Boolean = false,
        textInputModeActive: Boolean = false,
        interactionActive: Boolean = true
    ): String? = GestureCommandPolicy.filter(
        gestureId = gestureId,
        listening = listening,
        assistantSpeaking = assistantSpeaking,
        textInputModeActive = textInputModeActive,
        interactionActive = interactionActive
    )
}
