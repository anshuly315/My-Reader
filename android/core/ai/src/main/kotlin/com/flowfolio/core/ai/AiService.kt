package com.flowfolio.core.ai

import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.runningFold

/** What the reader wants done with a passage. */
enum class AiTask { EXPLAIN, SUMMARIZE, TRANSLATE, DEFINE, ASK }

/**
 * The one door to AI. It picks the provider the reader chose in Settings, wraps a passage in the right instructions
 * ([AiPrompts]) and streams the answer back. Every function returns a cold [Flow] of the answer so far, ready to show
 * as it grows.
 */
@Singleton
class AiService @Inject constructor(private val settings: AiSettingsStore, private val onDevice: OnDeviceProvider) {
    val config: StateFlow<AiConfig> get() = settings.config

    private fun provider(c: AiConfig): AiProvider = when (c.backend) {
        AiBackend.ON_DEVICE -> onDevice
        AiBackend.ANTHROPIC -> AnthropicProvider(c.baseUrl, c.model, c.apiKey)
        AiBackend.OPENAI_COMPATIBLE -> OpenAiCompatProvider(c.baseUrl, c.model, c.apiKey)
    }

    /** The answer to a whole conversation, as the text so far. */
    fun chat(messages: List<ChatMessage>): Flow<String> = flow {
        val c = config.value
        if (!c.ready) throw AiException("AI is not set up. Choose a provider and add its key in Settings → AI.")
        emitAll(provider(c).stream(messages).runningFold("") { text, piece -> text + piece })
    }

    /** Runs [task] on [passage] (with the text around it as [context], when there is some). */
    fun run(task: AiTask, passage: String, context: String? = null, question: String? = null): Flow<String> =
        chat(AiPrompts.build(task, passage, context, question, languageName(config.value.language)))

    /** A follow-up in a conversation that began with [first]. */
    fun followUp(history: List<ChatMessage>, question: String): Flow<String> =
        chat(history + ChatMessage.user(question))

    /** A one-word reply that proves the settings work. */
    fun test(): Flow<String> = chat(listOf(ChatMessage.user("Reply with the single word: ready")))

    private fun languageName(code: String): String =
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { "English" }
}

/** The instructions behind each task. Kept apart from the transport so they are easy to read and test. */
object AiPrompts {
    private const val SYSTEM_BASE =
        "You are a reading companion inside an e-book reader. Be accurate and concise. " +
            "Use plain text without markdown headings. If the passage is not enough to answer, say so."

    /** Passages are cut to this many characters so a small on-device model keeps its place. */
    const val MAX_PASSAGE = 6_000
    const val MAX_CONTEXT = 3_000

    fun build(task: AiTask, passage: String, context: String?, question: String?, language: String): List<ChatMessage> {
        val text = passage.trim().take(MAX_PASSAGE)
        val around = context?.trim()?.take(MAX_CONTEXT).orEmpty()
        val system = "$SYSTEM_BASE Answer in $language."
        val user = when (task) {
            AiTask.EXPLAIN ->
                "Explain this passage in simple terms, then give one sentence on why it matters.\n\nPassage:\n$text" +
                    surrounding(around)

            AiTask.SUMMARIZE ->
                "Summarize the following in 3 to 5 key points, one short line each.\n\n$text"

            AiTask.TRANSLATE ->
                // Translation must not be wrapped in chatter: the output is shown as the translation itself.
                "Translate the following into $language. Reply with the translation only, no notes, " +
                    "keeping paragraph breaks.\n\n$text"

            AiTask.DEFINE ->
                "Define \"$text\" as it is used in this context. Give the part of speech, a short definition " +
                    "that fits the sentence, and one other common meaning if there is one.\n\n" +
                    "Context:\n${around.ifEmpty { text }}"

            AiTask.ASK ->
                "Passage from the book:\n$text${surrounding(around)}\n\nQuestion: ${question.orEmpty().trim()}"
        }
        return listOf(ChatMessage.system(system), ChatMessage.user(user))
    }

    private fun surrounding(around: String) = if (around.isEmpty()) "" else "\n\nText around it:\n$around"
}
