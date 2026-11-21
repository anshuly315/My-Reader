@file:Suppress("LongMethod", "CyclomaticComplexMethod")

package com.flowfolio.feature.tools

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowfolio.core.ai.AiTask
import com.flowfolio.core.designsystem.ButtonStyle
import com.flowfolio.core.designsystem.FlowFolio
import com.flowfolio.core.designsystem.FlowFolioButton
import com.flowfolio.core.designsystem.FlowFolioChip
import com.flowfolio.core.designsystem.FlowFolioSheet
import com.flowfolio.core.designsystem.FlowFolioType
import com.flowfolio.core.designsystem.Glyph

/**
 * Ask AI, as a sheet over the page: the selected [request] explained, defined, translated or summarized, and a box for
 * follow-up questions. [onOpenSettings] is where "Set up AI" goes when no provider is ready.
 */
@Composable
fun AiAssistantSheet(
    request: AiRequest,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: AiAssistantViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val p = FlowFolio.palette
    LaunchedEffect(request) { viewModel.show(request) }
    var question by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    FlowFolioSheet(onDismissRequest = {
        viewModel.stop()
        onDismiss()
    }, title = stringResource(R.string.ai_assistant)) {
        Text(
            request.passage.trim(),
            style = FlowFolioType.subtitle.copy(color = p.ink2),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TASKS.forEach { (task, label) ->
                FlowFolioChip(stringResource(label), state.task == task, { viewModel.choose(task) })
            }
        }
        Spacer(Modifier.height(12.dp))

        if (!config.ready) {
            Text(stringResource(R.string.ai_not_ready), style = FlowFolioType.body.copy(color = p.ink2))
            Spacer(Modifier.height(10.dp))
            FlowFolioButton(stringResource(R.string.ai_setup), onOpenSettings, icon = Glyph.Settings)
            return@FlowFolioSheet
        }

        Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
            state.turns.forEach { turn ->
                turn.question?.let {
                    Text(
                        it,
                        style = FlowFolioType.rowLabel.copy(color = p.accent),
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                    )
                }
                Text(
                    turn.answer.ifEmpty { if (state.loading) stringResource(R.string.ai_thinking) else "" },
                    style = FlowFolioType.prose.copy(color = p.ink)
                )
            }
            if (state.task == AiTask.ASK && state.turns.isEmpty()) {
                Text(stringResource(R.string.ai_ask_hint), style = FlowFolioType.body.copy(color = p.ink2))
            }
            state.error?.let {
                Text(it, style = FlowFolioType.body.copy(color = p.danger), modifier = Modifier.padding(top = 8.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ToolField(
                label = stringResource(R.string.ai_ask_hint),
                value = question,
                onChange = { question = it },
                modifier = Modifier.weight(1f)
            )
            FlowFolioButton(
                text = if (state.loading) stringResource(R.string.ai_stop) else stringResource(R.string.ai_send),
                onClick = {
                    if (state.loading) {
                        viewModel.stop()
                    } else {
                        viewModel.ask(question)
                        question = ""
                    }
                },
                small = true,
                enabled = state.loading || question.isNotBlank()
            )
        }
        val last = state.turns.lastOrNull()?.answer.orEmpty()
        if (last.isNotEmpty() && !state.loading) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowFolioButton(
                    stringResource(R.string.ai_copy),
                    { clipboard.setText(AnnotatedString(last)) },
                    style = ButtonStyle.GHOST,
                    small = true,
                    icon = Glyph.Copy
                )
                if (state.error !=
                    null
                ) {
                    FlowFolioButton(
                        stringResource(R.string.ai_retry),
                        viewModel::retry,
                        style = ButtonStyle.GHOST,
                        small = true,
                        icon = Glyph.Refresh
                    )
                }
            }
        } else if (state.error != null) {
            FlowFolioButton(
                stringResource(R.string.ai_retry),
                viewModel::retry,
                style = ButtonStyle.GHOST,
                small = true,
                icon = Glyph.Refresh,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

private val TASKS = listOf(
    AiTask.EXPLAIN to R.string.ai_explain,
    AiTask.DEFINE to R.string.ai_define,
    AiTask.TRANSLATE to R.string.ai_translate,
    AiTask.SUMMARIZE to R.string.ai_summarize,
    AiTask.ASK to R.string.ai_ask
)
