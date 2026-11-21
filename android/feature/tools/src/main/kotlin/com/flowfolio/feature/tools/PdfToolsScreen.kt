@file:Suppress("LongMethod", "CyclomaticComplexMethod", "MagicNumber")

package com.flowfolio.feature.tools

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowfolio.core.designsystem.ButtonStyle
import com.flowfolio.core.designsystem.FlowFolio
import com.flowfolio.core.designsystem.FlowFolioButton
import com.flowfolio.core.designsystem.FlowFolioCard
import com.flowfolio.core.designsystem.FlowFolioChip
import com.flowfolio.core.designsystem.FlowFolioGlyph
import com.flowfolio.core.designsystem.FlowFolioIconButton
import com.flowfolio.core.designsystem.FlowFolioType
import com.flowfolio.core.designsystem.Glyph
import com.flowfolio.core.designsystem.LocalChromePadding
import com.flowfolio.core.designsystem.chromeStart

/**
 * The PDF tools (after Stirling-PDF's): a grid of tools, and for the one picked, its files, its choices and its result.
 * Everything runs on the phone.
 */
@Composable
fun PdfToolsScreen(onOpenAi: () -> Unit, viewModel: ToolsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ai by viewModel.ai.collectAsStateWithLifecycle()
    val tool = state.tool
    BackHandler(enabled = tool != null) { viewModel.close() }
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val chrome = LocalChromePadding.current
    Box(Modifier.fillMaxSize().chromeStart()) {
        Column(
            Modifier.fillMaxSize().padding(top = insets.calculateTopPadding()).padding(horizontal = 18.dp)
        ) {
            ToolsHeader(
                title = if (tool == null) stringResource(R.string.tools_title) else stringResource(tool.title),
                sub = if (tool == null) stringResource(R.string.tools_sub) else stringResource(tool.sub),
                backLabel = stringResource(R.string.tools_back),
                onBack = if (tool == null) null else ({ viewModel.close() })
            )
            if (tool == null) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        bottom =
                            chrome.calculateBottomPadding() + 24.dp
                    )
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) { AiCard(ai.preset.label, ai.ready, onOpenAi) }
                    items(PdfTool.entries.toList()) { t -> ToolCard(t) { viewModel.open(t) } }
                }
            } else {
                ToolDetail(tool, state, viewModel, Modifier.weight(1f))
            }
        }
    }
}

/** The way into Settings → AI: what is chosen now and whether it is ready to use. */
@Composable
private fun AiCard(provider: String, ready: Boolean, onClick: () -> Unit) {
    val p = FlowFolio.palette
    FlowFolioCard(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        padding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            FlowFolioGlyph(Glyph.Sparkle, null, color = p.accent, size = 30.dp)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ai_title), style = FlowFolioType.rowLabel.copy(color = p.ink))
                Text(
                    provider + "  ·  " +
                        stringResource(if (ready) R.string.ai_status_ready else R.string.ai_status_setup),
                    style = FlowFolioType.rowDetail.copy(color = if (ready) p.ink2 else p.accent)
                )
            }
        }
    }
}

@Composable
private fun ToolCard(tool: PdfTool, onClick: () -> Unit) {
    val p = FlowFolio.palette
    FlowFolioCard(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        padding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {
        Column {
            FlowFolioGlyph(tool.glyph, null, color = p.accent, size = 26.dp)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(tool.title), style = FlowFolioType.rowLabel.copy(color = p.ink))
            Spacer(Modifier.height(2.dp))
            Text(stringResource(tool.sub), style = FlowFolioType.rowDetail.copy(color = p.ink2), maxLines = 3)
        }
    }
}

@Composable
private fun ToolDetail(tool: PdfTool, state: ToolsState, vm: ToolsViewModel, modifier: Modifier) {
    val p = FlowFolio.palette
    val chrome = LocalChromePadding.current
    val pdfMime = arrayOf("application/pdf")
    val imageMime = arrayOf("image/*")
    val bookMime = arrayOf("*/*")
    val pickMany =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addFiles(it) }
    val pickOne =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { vm.addFiles(listOf(it)) }
        }
    val saveOne =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            uri?.let { vm.saveTo(it) }
        }
    val saveEpub =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/epub+zip")) { uri ->
            uri?.let { vm.saveTo(it) }
        }
    val saveTree =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { vm.saveAllTo(it) }
        }
    val o = state.options

    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = chrome.calculateBottomPadding() + 32.dp)) {
        SectionLabel(
            if (tool.input ==
                ToolInput.IMAGES
            ) {
                stringResource(R.string.tools_choose_images)
            } else {
                stringResource(R.string.tools_choose_pdf)
            }
        )
        if (state.files.isEmpty()) {
            Text(stringResource(R.string.tools_no_files), style = FlowFolioType.body.copy(color = p.ink2))
        }
        state.files.forEachIndexed { i, f ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    f.name,
                    style = FlowFolioType.body.copy(color = p.ink),
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                if (tool.input != ToolInput.ONE_PDF && state.files.size > 1) {
                    FlowFolioIconButton(onClick = {
                        vm.moveFile(i, -1)
                    }, size = 32.dp, ghost = true, tooltip = stringResource(R.string.tools_up)) {
                        FlowFolioGlyph(
                            Glyph.Back,
                            null,
                            color = p.ink2,
                            size = 18.dp,
                            modifier = Modifier.size(18.dp).rotateUp()
                        )
                    }
                    FlowFolioIconButton(onClick = {
                        vm.moveFile(i, 1)
                    }, size = 32.dp, ghost = true, tooltip = stringResource(R.string.tools_down)) {
                        FlowFolioGlyph(
                            Glyph.Back,
                            null,
                            color = p.ink2,
                            size = 18.dp,
                            modifier = Modifier.size(18.dp).rotateDown()
                        )
                    }
                }
                FlowFolioIconButton(onClick = {
                    vm.removeFile(i)
                }, size = 32.dp, ghost = true, tooltip = stringResource(R.string.tools_remove)) {
                    FlowFolioGlyph(Glyph.Close, null, color = p.ink2, size = 18.dp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowFolioButton(
            text = when (tool.input) {
                ToolInput.ONE_PDF -> stringResource(R.string.tools_choose_pdf)
                ToolInput.MANY_PDF -> stringResource(R.string.tools_choose_pdfs)
                ToolInput.IMAGES -> stringResource(R.string.tools_choose_images)
                ToolInput.BOOK -> stringResource(R.string.tools_choose_book)
            },
            onClick = {
                when (tool.input) {
                    ToolInput.ONE_PDF -> pickOne.launch(pdfMime)
                    ToolInput.MANY_PDF -> pickMany.launch(pdfMime)
                    ToolInput.IMAGES -> pickMany.launch(imageMime)
                    ToolInput.BOOK -> pickOne.launch(bookMime)
                }
            },
            style = ButtonStyle.SOFT,
            icon = Glyph.Add
        )

        Options(tool, o, vm)

        Spacer(Modifier.height(20.dp))
        FlowFolioButton(
            text = if (state.running) stringResource(R.string.tools_running) else stringResource(R.string.tools_run),
            onClick = vm::run,
            enabled = state.files.isNotEmpty() && !state.running,
            modifier = Modifier.fillMaxWidth()
        )

        state.message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = FlowFolioType.body.copy(color = if (state.failed) p.danger else p.ink2))
        }

        if (state.results.isNotEmpty()) {
            SectionLabel(stringResource(R.string.tools_result))
            state.results.take(MAX_LISTED).forEach { f ->
                Text(
                    "${f.name}  ·  ${sizeOf(f.length())}",
                    style = FlowFolioType.body.copy(color = p.ink),
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
            if (state.results.size > MAX_LISTED) {
                Text("+ ${state.results.size - MAX_LISTED}", style = FlowFolioType.rowDetail.copy(color = p.ink2))
            }
            Spacer(Modifier.height(10.dp))
            if (state.results.size == 1) {
                val f = state.results.first()
                FlowFolioButton(
                    text = stringResource(R.string.tools_save),
                    onClick = {
                        when (vm.mimeOf(f)) {
                            "application/pdf" -> saveOne.launch(f.name)
                            "application/epub+zip" -> saveEpub.launch(f.name)
                            else -> saveTree.launch(null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Glyph.Share
                )
            } else {
                FlowFolioButton(
                    text = stringResource(R.string.tools_save_all),
                    onClick = { saveTree.launch(null) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Glyph.Folder
                )
            }
        }
    }
}

@Composable
private fun Options(tool: PdfTool, o: ToolOptions, vm: ToolsViewModel) {
    if (tool.usesPages) {
        SectionLabel(stringResource(R.string.tools_pages_hint))
        ToolField(stringResource(R.string.tools_pages_hint), o.pages, { v ->
            vm.options { it.copy(pages = v) }
        }, supporting = stringResource(R.string.opt_all_pages))
    }
    when (tool) {
        PdfTool.SPLIT -> ToolField(stringResource(R.string.opt_every), o.every, { v ->
            vm.options { it.copy(every = v) }
        }, number = true)

        PdfTool.ROTATE -> {
            SectionLabel(stringResource(R.string.opt_degrees))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(90, 180, 270).forEach { d ->
                    FlowFolioChip("$d°", o.degrees == d, { vm.options { it.copy(degrees = d) } })
                }
            }
        }

        PdfTool.WATERMARK -> {
            ToolField(stringResource(R.string.opt_text), o.text, { v -> vm.options { it.copy(text = v) } })
            ToolSlider(
                stringResource(R.string.opt_opacity),
                o.opacity,
                { v ->
                    vm.options { it.copy(opacity = v) }
                },
                "${(o.opacity * 100).toInt()}%",
                0.05f..1f
            )
            ToolField(stringResource(R.string.opt_size), o.size, { v ->
                vm.options { it.copy(size = v) }
            }, number = true)
        }

        PdfTool.SPLIT_SIZE -> ToolField(stringResource(R.string.opt_size_mb), o.every, { v ->
            vm.options { it.copy(every = v) }
        }, number = true)

        PdfTool.HEADER, PdfTool.FOOTER -> ToolField(stringResource(R.string.opt_header_text), o.text, { v ->
            vm.options { it.copy(text = v) }
        })

        PdfTool.REDACT -> ToolField(stringResource(R.string.opt_redact_words), o.text, { v ->
            vm.options { it.copy(text = v) }
        })

        PdfTool.NUMBERS -> ToolField(stringResource(R.string.opt_start), o.start, { v ->
            vm.options { it.copy(start = v) }
        }, number = true)

        PdfTool.COMPRESS -> {
            ToolSlider(
                stringResource(R.string.opt_quality),
                o.quality,
                { v ->
                    vm.options { it.copy(quality = v) }
                },
                "${(o.quality * 100).toInt()}%",
                0.1f..0.95f
            )
            ToolField(stringResource(R.string.opt_max_width), o.maxWidth, { v ->
                vm.options { it.copy(maxWidth = v) }
            }, number = true)
        }

        PdfTool.PROTECT -> {
            ToolField(stringResource(R.string.opt_password), o.password, { v ->
                vm.options { it.copy(password = v) }
            }, secret = true)
            ToolField(stringResource(R.string.opt_owner), o.owner, { v ->
                vm.options { it.copy(owner = v) }
            }, secret = true)
        }

        PdfTool.UNLOCK -> ToolField(stringResource(R.string.opt_password), o.password, { v ->
            vm.options { it.copy(password = v) }
        }, secret = true)

        PdfTool.METADATA -> {
            ToolField(stringResource(R.string.opt_title), o.title, { v -> vm.options { it.copy(title = v) } })
            ToolField(stringResource(R.string.opt_author), o.author, { v -> vm.options { it.copy(author = v) } })
            ToolField(stringResource(R.string.opt_subject), o.subject, { v -> vm.options { it.copy(subject = v) } })
            ToolField(stringResource(R.string.opt_keywords), o.keywords, { v -> vm.options { it.copy(keywords = v) } })
        }

        PdfTool.CROP -> ToolField(stringResource(R.string.opt_margin), o.margin, { v ->
            vm.options { it.copy(margin = v) }
        }, number = true)

        PdfTool.N_UP -> Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp)
        ) {
            listOf(2, 4, 6, 9, 16).forEach { n ->
                FlowFolioChip(n.toString(), o.perSheet == n, { vm.options { it.copy(perSheet = n) } })
            }
        }

        PdfTool.PDF_TO_IMAGES -> {
            ToolField(stringResource(R.string.opt_dpi), o.dpi, { v -> vm.options { it.copy(dpi = v) } }, number = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                FlowFolioChip(stringResource(R.string.opt_png), !o.jpeg, { vm.options { it.copy(jpeg = false) } })
                FlowFolioChip(stringResource(R.string.opt_jpeg), o.jpeg, { vm.options { it.copy(jpeg = true) } })
            }
        }

        else -> Unit
    }
}

/** The back chevron turned to point up or down. */
private fun Modifier.rotateUp(): Modifier = rotate(90f)

private fun Modifier.rotateDown(): Modifier = rotate(-90f)

private fun sizeOf(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1 shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private const val MAX_LISTED = 6
