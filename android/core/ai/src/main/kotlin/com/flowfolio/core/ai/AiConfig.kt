package com.flowfolio.core.ai

/** How a provider is spoken to. */
enum class AiBackend {
    /** `POST {base}/chat/completions`: OpenAI, OpenRouter, Gemini's compatibility endpoint, Groq, Ollama and others. */
    OPENAI_COMPATIBLE,

    /** Anthropic's own `POST {base}/v1/messages`. */
    ANTHROPIC,

    /** Gemma through LiteRT-LM on the phone: nothing leaves it. */
    ON_DEVICE
}

/** A ready-made choice in the AI settings: fills in the address and a sensible model. */
data class AiPreset(
    val id: String,
    val label: String,
    val backend: AiBackend,
    val baseUrl: String,
    val defaultModel: String,
    val needsKey: Boolean = true,
    /** Where the user gets a key. */
    val keyHint: String = ""
) {
    companion object {
        const val ON_DEVICE_ID = "on_device"
        const val CUSTOM_ID = "custom"

        val all: List<AiPreset> = listOf(
            AiPreset(
                ON_DEVICE_ID,
                "On this phone (Gemma)",
                AiBackend.ON_DEVICE,
                "",
                "gemma-4-E2B-it",
                needsKey = false
            ),
            AiPreset(
                "anthropic",
                "Anthropic (Claude)",
                AiBackend.ANTHROPIC,
                "https://api.anthropic.com",
                "claude-sonnet-5-5",
                keyHint = "console.anthropic.com"
            ),
            AiPreset(
                "openai",
                "OpenAI",
                AiBackend.OPENAI_COMPATIBLE,
                "https://api.openai.com/v1",
                "gpt-4o-mini",
                keyHint = "platform.openai.com"
            ),
            AiPreset(
                "gemini",
                "Google Gemini",
                AiBackend.OPENAI_COMPATIBLE,
                "https://generativelanguage.googleapis.com/v1beta/openai",
                "gemini-2.0-flash",
                keyHint = "aistudio.google.com"
            ),
            AiPreset(
                "openrouter",
                "OpenRouter",
                AiBackend.OPENAI_COMPATIBLE,
                "https://openrouter.ai/api/v1",
                "openai/gpt-4o-mini",
                keyHint = "openrouter.ai/keys"
            ),
            AiPreset(
                "groq",
                "Groq",
                AiBackend.OPENAI_COMPATIBLE,
                "https://api.groq.com/openai/v1",
                "llama-3.3-70b-versatile",
                keyHint = "console.groq.com"
            ),
            // Any https endpoint that speaks the OpenAI dialect (a self-hosted server behind https, LiteLLM, vLLM).
            // The key may be left empty for one that needs none. Plain http is blocked by Android for apps like this.
            AiPreset(
                CUSTOM_ID,
                "Custom (OpenAI-compatible)",
                AiBackend.OPENAI_COMPATIBLE,
                "https://",
                "",
                needsKey = false
            )
        )

        fun byId(id: String): AiPreset = all.firstOrNull { it.id == id } ?: all.first { it.id == CUSTOM_ID }
    }
}

/** What the reader chose in Settings → AI. The key is kept encrypted ([SecretBox]); here it is in the clear. */
data class AiConfig(
    val presetId: String = AiPreset.ON_DEVICE_ID,
    val backend: AiBackend = AiBackend.ON_DEVICE,
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
    /** The language answers, translations and definitions come in, as a BCP-47 code. */
    val language: String = "en"
) {
    /** Whether a request could be sent: a key where one is needed, an address and a model. */
    val ready: Boolean
        get() = when (backend) {
            AiBackend.ON_DEVICE -> true

            AiBackend.ANTHROPIC -> apiKey.isNotBlank() && model.isNotBlank()

            AiBackend.OPENAI_COMPATIBLE ->
                baseUrl.isNotBlank() && model.isNotBlank() && (apiKey.isNotBlank() || !AiPreset.byId(presetId).needsKey)
        }

    val preset: AiPreset get() = AiPreset.byId(presetId)
}

/** Who said a line of the conversation. */
enum class Role { SYSTEM, USER, ASSISTANT }

data class ChatMessage(val role: Role, val content: String) {
    companion object {
        fun system(text: String) = ChatMessage(Role.SYSTEM, text)

        fun user(text: String) = ChatMessage(Role.USER, text)

        fun assistant(text: String) = ChatMessage(Role.ASSISTANT, text)
    }
}

/** A failure the reader can be told about in one sentence. */
class AiException(message: String, cause: Throwable? = null) : Exception(message, cause)
