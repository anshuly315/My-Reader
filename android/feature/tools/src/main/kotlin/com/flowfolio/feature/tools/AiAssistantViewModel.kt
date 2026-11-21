@file:Suppress("TooGenericExceptionCaught")

package com.flowfolio.feature.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowfolio.core.ai.AiConfig
import com.flowfolio.core.ai.AiException
import com.flowfolio.core.ai.AiPrompts
import com.flowfolio.core.ai.AiService
import com.flowfolio.core.ai.AiTask
import com.flowfolio.core.ai.ChatMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A passage the reader selected, and the text around it when the reader has some. */
data class AiRequest(val passage: String, val context: String? = null) {
    /** One word or a short phrase: defined rather than explained. */
    val isWord: Boolean get() = passage.trim().let {
        it.isNotEmpty() && it.length <= WORD_MAX &&
            it.count(Char::isWhitespace) <= 1
    }

    private companion object {
        const val WORD_MAX = 40
    }
}

/** One exchange: what was asked (null for the first, which the chosen task stands for) and the answer so far. */
data class AiTurn(val question: String?, val answer: String)

data class AiAssistantState(
    val request: AiRequest? = null,
    val task: AiTask = AiTask.EXPLAIN,
    val turns: List<AiTurn> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

/** Runs the Ask AI sheet: the chosen task on the passage, then follow-up questions in the same conversation. */
@HiltViewModel
class AiAssistantViewModel @Inject constructor(private val ai: AiService) : ViewModel() {
    private val _state = MutableStateFlow(AiAssistantState())
    val state: StateFlow<AiAssistantState> = _state.asStateFlow()
    val config: StateFlow<AiConfig> get() = ai.config

    private var job: Job? = null
    private var messages: List<ChatMessage> = emptyList()

    /** Shows [request]; does nothing when that passage is already on screen, so a rotation keeps the answer. */
    fun show(request: AiRequest) {
        if (_state.value.request == request) return
        val task = if (request.isWord) AiTask.DEFINE else AiTask.EXPLAIN
        _state.value = AiAssistantState(request = request, task = task)
        if (ai.config.value.ready) run(task)
    }

    /** Switches the task and runs it afresh on the same passage. */
    fun choose(task: AiTask) {
        if (_state.value.request == null) return
        _state.update { it.copy(task = task, turns = emptyList(), error = null) }
        if (task != AiTask.ASK) run(task)
    }

    private fun run(task: AiTask, question: String? = null) {
        val request = _state.value.request ?: return
        job?.cancel()
        messages = AiPrompts.build(task, request.passage, request.context, question, languageName())
        stream(question = null, replace = true)
    }

    /** A follow-up in the same conversation (or the first question, on the Ask task). */
    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || _state.value.request == null) return
        job?.cancel()
        if (messages.isEmpty() || (_state.value.task == AiTask.ASK && _state.value.turns.isEmpty())) {
            val request = _state.value.request!!
            messages = AiPrompts.build(AiTask.ASK, request.passage, request.context, q, languageName())
        } else {
            messages = messages + ChatMessage.user(q)
        }
        stream(question = q, replace = false)
    }

    private fun stream(question: String?, replace: Boolean) {
        _state.update {
            it.copy(
                loading = true,
                error = null,
                turns = (if (replace) emptyList() else it.turns) + AiTurn(question, "")
            )
        }
        val sent = messages
        job = viewModelScope.launch {
            try {
                ai.chat(sent).collect { text ->
                    _state.update { it.copy(turns = it.turns.dropLast(1) + AiTurn(question, text)) }
                }
                val answer = _state.value.turns.lastOrNull()?.answer.orEmpty()
                messages = sent + ChatMessage.assistant(answer)
                _state.update { it.copy(loading = false) }
            } catch (e: CancellationException) {
                _state.update { it.copy(loading = false) }
                throw e
            } catch (e: AiException) {
                _state.update { it.copy(loading = false, error = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Something went wrong.") }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    /** Re-runs the current task, for a try again after an error or a change in Settings. */
    fun retry() {
        val s = _state.value
        if (s.request == null) return
        if (s.task == AiTask.ASK) {
            val last = s.turns.lastOrNull()?.question
            if (last != null) ask(last)
        } else {
            run(s.task)
        }
    }

    private fun languageName(): String =
        java.util.Locale.forLanguageTag(ai.config.value.language).getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank {
            "English"
        }
}
