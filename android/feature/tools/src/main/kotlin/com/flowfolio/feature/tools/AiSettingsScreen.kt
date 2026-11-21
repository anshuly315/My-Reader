@file:Suppress("LongMethod", "CyclomaticComplexMethod", "MagicNumber")

package com.flowfolio.feature.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowfolio.core.ai.AiBackend
import com.flowfolio.core.ai.AiPreset
import com.flowfolio.core.ai.ModelState
import com.flowfolio.core.designsystem.ButtonStyle
import com.flowfolio.core.designsystem.FlowFolio
import com.flowfolio.core.designsystem.FlowFolioButton
import com.flowfolio.core.designsystem.FlowFolioCard
import com.flowfolio.core.designsystem.FlowFolioChip
import com.flowfolio.core.designsystem.FlowFolioType
import com.flowfolio.core.designsystem.Glyph
import com.flowfolio.core.designsystem.LocalChromePadding
import com.flowfolio.core.designsystem.chromeStart

/** Settings → AI: the provider and its key, or the on-device model, and a button that proves it works. */
@Composable
fun AiSettingsScreen(onBack: () -> Unit, viewModel: AiSettingsViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val test by viewModel.test.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val p = FlowFolio.palette
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val chrome = LocalChromePadding.current
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.import(it) }
        }

    Box(Modifier.fillMaxSize().chromeStart()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding())
                .padding(horizontal = 18.dp)
                .padding(bottom = chrome.calculateBottomPadding() + 32.dp)
        ) {
            ToolsHeader(
                stringResource(R.string.ai_title),
                stringResource(R.string.ai_sub),
                stringResource(R.string.tools_back),
                onBack
            )
            SectionLabel(stringResource(R.string.ai_provider))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiPreset.all.forEach { preset ->
                    FlowFolioChip(preset.label, config.presetId == preset.id, { viewModel.choose(preset) })
                }
            }
            Spacer(Modifier.height(8.dp))

            if (config.backend == AiBackend.ON_DEVICE) {
                OnDeviceCard(modelState, viewModel, onImport = { picker.launch(arrayOf("*/*")) })
            } else {
                ToolField(stringResource(R.string.ai_base_url), config.baseUrl, viewModel::setBaseUrl)
                ToolField(stringResource(R.string.ai_model), config.model, viewModel::setModel)
                if (config.preset.needsKey || config.presetId == AiPreset.CUSTOM_ID) {
                    ToolField(
                        stringResource(R.string.ai_key),
                        config.apiKey,
                        viewModel::setApiKey,
                        secret = true,
                        supporting =
                            config.preset.keyHint.ifEmpty { null }?.let {
                                "$it · " +
                                    stringResource(R.string.ai_key_hint)
                            }
                                ?: stringResource(R.string.ai_key_hint)
                    )
                }
            }
            ToolField(stringResource(R.string.ai_language), config.language, viewModel::setLanguage)

            Spacer(Modifier.height(12.dp))
            FlowFolioButton(
                text = stringResource(R.string.ai_test),
                onClick = viewModel::runTest,
                enabled = config.ready && test !is TestResult.Running,
                icon = Glyph.Sparkle
            )
            when (val t = test) {
                is TestResult.Running -> Text(
                    stringResource(R.string.ai_thinking),
                    style = FlowFolioType.body.copy(color = p.ink2),
                    modifier = Modifier.padding(top = 8.dp)
                )

                is TestResult.Done -> Text(
                    if (t.ok) stringResource(R.string.ai_test_ok) else t.message,
                    style = FlowFolioType.body.copy(color = if (t.ok) p.ink2 else p.danger),
                    modifier = Modifier.padding(top = 8.dp)
                )

                TestResult.Idle -> Unit
            }
        }
    }
}

@Composable
private fun OnDeviceCard(state: ModelState, vm: AiSettingsViewModel, onImport: () -> Unit) {
    val p = FlowFolio.palette
    FlowFolioCard(Modifier.fillMaxWidth()) {
        Column {
            Text(stringResource(R.string.ai_on_device), style = FlowFolioType.rowLabel.copy(color = p.ink))
            Text(
                stringResource(R.string.ai_on_device_sub),
                style = FlowFolioType.rowDetail.copy(color = p.ink2),
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )
            when (state) {
                is ModelState.Missing -> {
                    Text(stringResource(R.string.ai_model_missing), style = FlowFolioType.body.copy(color = p.ink2))
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowFolioButton(stringResource(R.string.ai_download), vm::download, small = true)
                        FlowFolioButton(
                            stringResource(R.string.ai_import),
                            onImport,
                            small = true,
                            style = ButtonStyle.GHOST
                        )
                    }
                }

                is ModelState.Downloading -> {
                    Text(
                        stringResource(R.string.ai_model_downloading) + " " + percent(state.done, state.total),
                        style = FlowFolioType.body.copy(color = p.ink2)
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (state.total >
                                0
                            ) {
                                (state.done.toFloat() / state.total).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                        },
                        color = p.accent,
                        trackColor = p.line,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowFolioButton(
                        stringResource(R.string.ai_delete_model),
                        vm::deleteModel,
                        small = true,
                        style = ButtonStyle.GHOST
                    )
                }

                is ModelState.Ready -> {
                    Text(
                        stringResource(R.string.ai_model_ready) + "  ·  %.1f GB".format(state.bytes / 1.0e9),
                        style = FlowFolioType.body.copy(color = p.ink2)
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowFolioButton(
                        stringResource(R.string.ai_delete_model),
                        vm::deleteModel,
                        small = true,
                        style = ButtonStyle.DANGER
                    )
                }

                is ModelState.Failed -> {
                    Text(state.reason, style = FlowFolioType.body.copy(color = p.danger))
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowFolioButton(stringResource(R.string.ai_download), vm::download, small = true)
                        FlowFolioButton(
                            stringResource(R.string.ai_import),
                            onImport,
                            small = true,
                            style = ButtonStyle.GHOST
                        )
                    }
                }
            }
        }
    }
}

private fun percent(done: Long, total: Long): String = if (total >
    0
) {
    "${(done * 100 / total).coerceIn(0, 100)}%"
} else {
    ""
}
