package com.flowfolio.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTest {
    @Test
    fun chatUrlAddsThePathOnce() {
        assertEquals("https://x.y/v1/chat/completions", OpenAiCompatProvider.chatUrl("https://x.y/v1"))
        assertEquals("https://x.y/v1/chat/completions", OpenAiCompatProvider.chatUrl("https://x.y/v1/"))
        assertEquals("https://x.y/v1/chat/completions", OpenAiCompatProvider.chatUrl("https://x.y/v1/chat/completions"))
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            AnthropicProvider.messagesUrl("https://api.anthropic.com")
        )
    }

    @Test
    fun deltaIsReadFromAStreamedChunk() {
        val chunk = """{"choices":[{"delta":{"content":"Hel"},"index":0}]}"""
        assertEquals("Hel", OpenAiCompatProvider.deltaOf(chunk))
        assertNull(OpenAiCompatProvider.deltaOf("""{"choices":[{"delta":{"role":"assistant"}}]}"""))
        assertNull(OpenAiCompatProvider.deltaOf("""{"choices":[]}"""))
    }

    @Test
    fun errorsAreDescribedWithTheServicesOwnWords() {
        val msg = describeHttpError(401, """{"error":{"message":"Incorrect API key provided"}}""")
        assertTrue(msg, msg.contains("Incorrect API key provided"))
        assertTrue(msg, msg.contains("Settings"))
        assertTrue(describeHttpError(500, "not json").startsWith("Error 500"))
    }

    @Test
    fun readinessNeedsAKeyOnlyWhereOneIsUsed() {
        assertTrue(AiConfig().ready)
        val anthropic = AiConfig("anthropic", AiBackend.ANTHROPIC, "https://api.anthropic.com", "m", "")
        assertFalse(anthropic.ready)
        assertTrue(anthropic.copy(apiKey = "k").ready)
        val custom = AiConfig(AiPreset.CUSTOM_ID, AiBackend.OPENAI_COMPATIBLE, "https://llm.example.org/v1", "m", "")
        assertTrue(custom.ready)
        assertFalse(custom.copy(model = "").ready)
        val openai = AiConfig("openai", AiBackend.OPENAI_COMPATIBLE, "https://api.openai.com/v1", "gpt-4o-mini", "")
        assertFalse(openai.ready)
    }

    @Test
    fun promptsCarryThePassageAndTheLanguage() {
        val m = AiPrompts.build(AiTask.TRANSLATE, " Bonjour ", null, null, "German")
        assertEquals(Role.SYSTEM, m[0].role)
        assertTrue(m[0].content.contains("German"))
        assertTrue(m[1].content.contains("Bonjour"))
        val ask = AiPrompts.build(AiTask.ASK, "text", "around", "Why?", "English")
        assertTrue(ask[1].content.endsWith("Question: Why?"))
        val long = AiPrompts.build(AiTask.SUMMARIZE, "a".repeat(AiPrompts.MAX_PASSAGE * 2), null, null, "English")
        assertTrue(long[1].content.length < AiPrompts.MAX_PASSAGE + 200)
    }

    @Test
    fun presetsAreComplete() {
        assertTrue(AiPreset.all.map { it.id }.toSet().size == AiPreset.all.size)
        assertEquals(AiPreset.CUSTOM_ID, AiPreset.byId("nope").id)
    }
}
