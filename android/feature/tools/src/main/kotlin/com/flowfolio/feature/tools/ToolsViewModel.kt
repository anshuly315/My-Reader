@file:Suppress("LongMethod", "CyclomaticComplexMethod", "ThrowsCount", "MagicNumber")

package com.flowfolio.feature.tools

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowfolio.core.pdftools.BookConverter
import com.flowfolio.core.pdftools.PageRanges
import com.flowfolio.core.pdftools.PdfToolException
import com.flowfolio.core.pdftools.PdfToolkit
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A file the reader chose, copied into the app's cache so the tools can read it as a plain file. */
data class PickedFile(val file: File, val name: String)

data class ToolsState(
    val tool: PdfTool? = null,
    val files: List<PickedFile> = emptyList(),
    val options: ToolOptions = ToolOptions(),
    val running: Boolean = false,
    val results: List<File> = emptyList(),
    /** One line about how the run went: an error, or what was saved. */
    val message: String? = null,
    val failed: Boolean = false
)

@HiltViewModel
class ToolsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val kit: PdfToolkit,
    aiSettings: com.flowfolio.core.ai.AiSettingsStore
) : ViewModel() {
    /** The AI provider in use, for the AI card on the Tools tab. */
    val ai: StateFlow<com.flowfolio.core.ai.AiConfig> = aiSettings.config

    private val _state = MutableStateFlow(ToolsState())
    val state: StateFlow<ToolsState> = _state.asStateFlow()

    private val work get() = File(context.cacheDir, "pdf-tools").apply { mkdirs() }

    fun open(tool: PdfTool) {
        val text = when (tool) {
            PdfTool.HEADER, PdfTool.FOOTER -> "{n} / {total}"
            PdfTool.REDACT, PdfTool.BOOKMARKS -> ""
            else -> ToolOptions().text
        }
        _state.value = ToolsState(tool = tool, options = ToolOptions(text = text))
    }

    fun close() {
        work.deleteRecursively()
        _state.value = ToolsState()
    }

    fun options(change: (ToolOptions) -> ToolOptions) = _state.update { it.copy(options = change(it.options)) }

    fun addFiles(uris: List<Uri>) {
        viewModelScope.launch {
            val tool = _state.value.tool ?: return@launch
            val added = withContext(Dispatchers.IO) { uris.mapNotNull { copyIn(it) } }
            _state.update {
                val single = tool.input == ToolInput.ONE_PDF || tool.input == ToolInput.BOOK
                val files = if (single) added.take(1) else it.files + added
                it.copy(files = files, results = emptyList(), message = null, failed = false)
            }
        }
    }

    fun removeFile(index: Int) = _state.update { it.copy(files = it.files.filterIndexed { i, _ -> i != index }) }

    fun moveFile(index: Int, delta: Int) = _state.update {
        val to = index + delta
        if (to !in
            it.files.indices
        ) {
            it
        } else {
            it.copy(files = it.files.toMutableList().apply { add(to, removeAt(index)) })
        }
    }

    private fun copyIn(uri: Uri): PickedFile? = runCatching {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "file"
        val dir = File(work, "in").apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        var f = File(dir, safe)
        var n = 1
        while (f.exists()) f = File(dir, "${n++}-$safe")
        context.contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        PickedFile(f, name)
    }.getOrNull()

    /** Runs the open tool on the chosen files. */
    fun run() {
        val s = _state.value
        val tool = s.tool ?: return
        if ((s.files.isEmpty() && tool.input != ToolInput.NONE) || s.running) return
        _state.update { it.copy(running = true, message = null, failed = false, results = emptyList()) }
        viewModelScope.launch {
            try {
                val results = kit.run { execute(tool, s.files.map { it.file }, s.options) }
                _state.update { it.copy(running = false, results = results) }
            } catch (e: PdfToolException) {
                _state.update { it.copy(running = false, message = e.message, failed = true) }
            }
        }
    }

    private fun execute(tool: PdfTool, input: List<File>, o: ToolOptions): List<File> {
        val out = File(work, "out/${System.currentTimeMillis()}").apply { mkdirs() }
        val first = input.firstOrNull() ?: File(out, "blank")
        val stem = first.nameWithoutExtension
        fun pdf(suffix: String) = File(out, "$stem-$suffix.pdf")
        val pageCount by lazy { kit.info(first, if (tool == PdfTool.UNLOCK) o.password else "").pages }
        fun pages() = PageRanges.parse(o.pages, pageCount)
        return when (tool) {
            PdfTool.MERGE -> listOf(File(out, "merged.pdf").also { kit.merge(input, it) })

            PdfTool.SPLIT -> kit.split(
                first,
                out,
                o.every.toIntOrNull() ?: throw bad("Pages per file must be a number.")
            )

            PdfTool.EXTRACT -> listOf(pdf("extract").also { kit.extractPages(first, it, pages()) })

            PdfTool.REMOVE -> listOf(pdf("trimmed").also { kit.removePages(first, it, pages()) })

            PdfTool.ROTATE -> listOf(pdf("rotated").also { kit.rotate(first, it, o.degrees, pages()) })

            PdfTool.REORDER -> listOf(
                pdf("reordered").also {
                    kit.reorder(first, it, PageRanges.parse(o.pages, pageCount))
                }
            )

            PdfTool.STAMP_IMAGE -> {
                val pdfFile = input.firstOrNull { it.extension.equals("pdf", true) }
                    ?: throw PdfToolException("Choose a PDF and a picture.")
                val picture = input.firstOrNull { !it.extension.equals("pdf", true) }
                    ?: throw PdfToolException("Choose a PDF and a picture.")
                listOf(
                    File(out, "stamped.pdf").also {
                        kit.stampImage(pdfFile, picture, it, o.opacity, o.size.toIntOrNull() ?: 30)
                    }
                )
            }

            PdfTool.LINK_ADDRESSES -> {
                val target = pdf("linked")
                val n = kit.linkAddresses(first, target)
                if (n == 0) throw PdfToolException("No web addresses were found in this PDF's text.")
                listOf(target)
            }

            PdfTool.WATERMARK -> listOf(
                pdf("watermarked").also {
                    kit.watermark(
                        first,
                        it,
                        o.text,
                        o.opacity,
                        o.size.toFloatOrNull() ?: throw bad("Size must be a number.")
                    )
                }
            )

            PdfTool.NUMBERS -> listOf(pdf("numbered").also { kit.pageNumbers(first, it, o.start.toIntOrNull() ?: 1) })

            PdfTool.COMPRESS -> {
                val f = pdf("compressed")
                kit.compress(
                    first,
                    f,
                    (o.quality * 100).toInt(),
                    o.maxWidth.toIntOrNull() ?: throw bad("Width must be a number.")
                )
                listOf(f)
            }

            PdfTool.PROTECT -> listOf(
                pdf("protected").also {
                    kit.protect(first, it, o.password, o.owner.ifEmpty { o.password })
                }
            )

            PdfTool.UNLOCK -> listOf(pdf("unlocked").also { kit.unlock(first, it, o.password) })

            PdfTool.METADATA -> listOf(
                pdf("details").also {
                    kit.setMetadata(first, it, o.title, o.author, o.subject, o.keywords)
                }
            )

            PdfTool.CROP -> listOf(
                pdf("cropped").also {
                    kit.crop(
                        first,
                        it,
                        o.margin.toFloatOrNull() ?: throw bad("Margin must be a number.")
                    )
                }
            )

            PdfTool.FLATTEN -> listOf(pdf("flat").also { kit.flattenForms(first, it) })

            PdfTool.BLANK_PAGES -> listOf(pdf("no-blanks").also { kit.removeBlankPages(first, it) })

            PdfTool.SANITIZE -> listOf(pdf("clean").also { kit.sanitize(first, it) })

            PdfTool.REPAIR -> listOf(pdf("repaired").also { kit.repair(first, it) })

            PdfTool.N_UP -> listOf(pdf("n-up").also { kit.nUp(first, it, o.perSheet) })

            PdfTool.SINGLE_PAGE -> listOf(pdf("single").also { kit.singlePage(first, it) })

            PdfTool.EXTRACT_IMAGES -> kit.extractImages(first, out)

            PdfTool.CONVERT_BOOK -> when {
                first.extension.lowercase() in BookConverter.COMIC_INPUTS ->
                    listOf(pdf("comic").also { BookConverter.comicToPdf(first, it, File(out, "tmp"), kit) })

                BookConverter.isSupported(first) ->
                    listOf(File(out, "$stem.epub").also { BookConverter.toEpub(first, it) })

                else -> throw bad("That format is not supported yet. Supported: TXT, HTML, FB2, RTF, CBZ.")
            }

            PdfTool.SCALE_A4 -> listOf(pdf("a4").also { kit.scaleToA4(first, it) })

            PdfTool.BOOKLET -> listOf(pdf("booklet").also { kit.booklet(first, it) })

            PdfTool.SPLIT_SIZE -> kit.splitBySize(first, out, o.every.toIntOrNull() ?: 0)

            PdfTool.SPLIT_BOOKMARKS -> kit.splitByBookmarks(first, out)

            PdfTool.REMOVE_IMAGES -> listOf(pdf("no-images").also { kit.removeImages(first, it) })

            PdfTool.REMOVE_ANNOTATIONS -> listOf(pdf("no-notes").also { kit.removeAnnotations(first, it) })

            PdfTool.EXTRACT_ATTACHMENTS -> kit.extractAttachments(first, out)

            PdfTool.OVERLAY -> {
                if (input.size != 2) throw bad("Choose exactly two PDFs: the page first, then the one to lay over it.")
                listOf(pdf("overlay").also { kit.overlay(first, input[1], it) })
            }

            PdfTool.HEADER -> listOf(pdf("header").also { kit.headerFooter(first, it, o.text, top = true) })

            PdfTool.FOOTER -> listOf(pdf("footer").also { kit.headerFooter(first, it, o.text, top = false) })

            PdfTool.COMPARE -> {
                if (input.size != 2) throw bad("Choose exactly two PDFs to compare.")
                listOf(File(out, "comparison.txt").also { it.writeText(kit.compare(first, input[1])) })
            }

            PdfTool.REDACT -> listOf(pdf("redacted").also { kit.redact(first, it, o.text.split(',')) })

            PdfTool.GRAYSCALE -> listOf(pdf("grey").also { kit.grayscale(first, it) })

            PdfTool.INVERT -> listOf(pdf("inverted").also { kit.invert(first, it) })

            PdfTool.TEXT_TO_PDF -> listOf(pdf("text").also { kit.textToPdf(first, it) })

            PdfTool.BOOKMARKS -> listOf(pdf("bookmarked").also { kit.addBookmarks(first, it, o.text) })

            PdfTool.BLANK_PDF -> listOf(File(out, "blank.pdf").also { kit.blankPdf(it, o.every.toIntOrNull() ?: 1) })

            PdfTool.HEALTH -> listOf(File(out, "health-check.txt").also { it.writeText(kit.healthCheck(first)) })

            PdfTool.IMAGES_TO_PDF -> listOf(File(out, "pictures.pdf").also { kit.imagesToPdf(input, it) })

            PdfTool.PDF_TO_IMAGES -> kit.pdfToImages(
                first,
                out,
                pages(),
                (o.dpi.toIntOrNull() ?: 150).coerceIn(36, 600),
                o.jpeg
            )

            PdfTool.TEXT -> listOf(File(out, "$stem.txt").also { it.writeText(kit.extractText(first)) })
        }
    }

    private fun bad(message: String) = PdfToolException(message)

    /** Copies the one result to the place the reader chose. */
    fun saveTo(uri: Uri) {
        val file = _state.value.results.firstOrNull() ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
                }.isSuccess
            }
            _state.update { it.copy(message = if (ok) "Saved." else "Could not save the file.", failed = !ok) }
        }
    }

    /** Copies every result into the folder the reader chose. */
    fun saveAllTo(tree: Uri) {
        val files = _state.value.results
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val parent = DocumentsContract.buildDocumentUriUsingTree(
                        tree,
                        DocumentsContract.getTreeDocumentId(tree)
                    )
                    files.forEach { f ->
                        val doc = DocumentsContract.createDocument(context.contentResolver, parent, mimeOf(f), f.name)!!
                        context.contentResolver.openOutputStream(doc)!!.use { o ->
                            f.inputStream().use { it.copyTo(o) }
                        }
                    }
                }.isSuccess
            }
            _state.update {
                it.copy(message = if (ok) "Saved ${files.size} files." else "Could not save the files.", failed = !ok)
            }
        }
    }

    fun mimeOf(f: File): String = when (f.extension.lowercase()) {
        "pdf" -> "application/pdf"
        "epub" -> "application/epub+zip"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "text/plain"
    }
}
