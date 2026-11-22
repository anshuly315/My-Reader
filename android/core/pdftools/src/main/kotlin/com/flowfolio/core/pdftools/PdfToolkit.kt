@file:Suppress(
    "MagicNumber",
    "TooManyFunctions",
    "LongParameterList",
    "SwallowedException",
    "LoopWithTooManyJumpStatements",
    "MaxLineLength",
    "LargeClass",
    "ktlint:standard:max-line-length"
)

package com.flowfolio.core.pdftools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.multipdf.Splitter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A problem the reader can be told about in one sentence (wrong password, damaged file, a bad page range). */
class PdfToolException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The pieces of a PDF [PdfToolkit.info] reports. */
data class PdfInfo(
    val pages: Int,
    val title: String?,
    val author: String?,
    val subject: String?,
    val keywords: String?,
    val encrypted: Boolean
)

/** What [PdfToolkit.compressToSize] made: its size, whether the target was met, and whether pages became pictures. */
data class SizeResult(
    val bytes: Long,
    val reached: Boolean,
    val flattened: Boolean,
    /** False when the reader kept the text, so pages were never redrawn as pictures. */
    val triedPictures: Boolean = true
)

/**
 * The PDF tools, after Stirling-PDF's, running on the phone: nothing is uploaded. Each function reads files and writes a
 * new one; the input is never changed. They block, so [run] moves them off the main thread and turns the engine's
 * failures into a [PdfToolException] with a readable message.
 */
@Singleton
class PdfToolkit @Inject constructor(@ApplicationContext private val context: Context) {
    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    /** Runs [block] off the main thread, with the engine's errors made readable. */
    suspend fun <T> run(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: PdfToolException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw PdfToolException(e.message ?: "Those choices do not work for this file.", e)
        } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            throw PdfToolException("The password is wrong, or this PDF needs one.", e)
        } catch (e: java.io.IOException) {
            throw PdfToolException("This file could not be read as a PDF: ${e.message ?: e.javaClass.simpleName}", e)
        } catch (e: OutOfMemoryError) {
            throw PdfToolException("This file is too large to process on the phone.")
        }
    }

    private fun load(file: File, password: String = ""): PDDocument = PDDocument.load(file, password)

    private inline fun <T> PDDocument.use2(block: (PDDocument) -> T): T = try {
        block(this)
    } finally {
        close()
    }

    private fun save(doc: PDDocument, output: File) {
        output.parentFile?.mkdirs()
        doc.save(output)
    }

    fun info(input: File, password: String = ""): PdfInfo = load(input, password).use2 { d ->
        val i = d.documentInformation
        PdfInfo(d.numberOfPages, i.title, i.author, i.subject, i.keywords, d.isEncrypted)
    }

    /** One PDF from several, in the order given. */
    fun merge(inputs: List<File>, output: File) {
        require(inputs.size >= 2) { "Choose at least two PDFs to merge." }
        val merger = PDFMergerUtility()
        inputs.forEach { merger.addSource(it) }
        output.parentFile?.mkdirs()
        merger.destinationFileName = output.absolutePath
        merger.mergeDocuments(null)
    }

    /** Cuts [input] into files of [every] pages each; returns them in order. */
    fun split(input: File, outDir: File, every: Int): List<File> {
        require(every >= 1) { "Pages per file must be at least 1." }
        outDir.mkdirs()
        val stem = input.nameWithoutExtension
        return load(input).use2 { d ->
            val parts = Splitter().apply { setSplitAtPage(every) }.split(d)
            val digits = parts.size.toString().length
            parts.mapIndexed { i, part ->
                val f = File(outDir, "$stem-part-${(i + 1).toString().padStart(digits, '0')}.pdf")
                part.use2 { save(it, f) }
                f
            }
        }
    }

    /** A new PDF of just [pages] (1-based), in that order. */
    fun extractPages(input: File, output: File, pages: List<Int>) {
        load(input).use2 { src ->
            val ordered = checked(pages, src.numberOfPages)
            PDDocument().use2 { dst ->
                ordered.forEach { dst.importPage(src.getPage(it - 1)) }
                save(dst, output)
            }
        }
    }

    /** [input] without [pages]. */
    fun removePages(input: File, output: File, pages: List<Int>) {
        load(input).use2 { d ->
            val drop = checked(pages, d.numberOfPages).toSortedSet()
            require(drop.size < d.numberOfPages) { "That would remove every page." }
            drop.reversed().forEach { d.removePage(it - 1) }
            save(d, output)
        }
    }

    /** Turns [pages] by [degrees] (a multiple of 90, clockwise). */
    fun rotate(input: File, output: File, degrees: Int, pages: List<Int>) {
        require(degrees % 90 == 0) { "Rotation must be a multiple of 90 degrees." }
        load(input).use2 { d ->
            checked(pages, d.numberOfPages).forEach {
                val page = d.getPage(it - 1)
                page.rotation = ((page.rotation + degrees) % 360 + 360) % 360
            }
            save(d, output)
        }
    }

    /** The pages in a new order: [order] lists the old page numbers as they should now follow each other. */
    fun reorder(input: File, output: File, order: List<Int>) = extractPages(input, output, order)

    /** Locks [input] with a password to open it ([userPassword]) and, optionally, a separate owner password. */
    fun protect(input: File, output: File, userPassword: String, ownerPassword: String = userPassword) {
        require(userPassword.isNotEmpty()) { "Enter a password." }
        load(input).use2 { d ->
            val permissions = AccessPermission()
            val policy = StandardProtectionPolicy(ownerPassword.ifEmpty { userPassword }, userPassword, permissions)
            policy.encryptionKeyLength = 256
            d.protect(policy)
            save(d, output)
        }
    }

    /** Removes the password from a PDF it is opened with. */
    fun unlock(input: File, output: File, password: String) {
        load(input, password).use2 { d ->
            d.isAllSecurityToBeRemoved = true
            save(d, output)
        }
    }

    /** Sets the title, author, subject and keywords; a blank value clears it. */
    fun setMetadata(input: File, output: File, title: String, author: String, subject: String, keywords: String) {
        load(input).use2 { d ->
            d.documentInformation.apply {
                this.title = title.ifBlank { null }
                this.author = author.ifBlank { null }
                this.subject = subject.ifBlank { null }
                this.keywords = keywords.ifBlank { null }
            }
            save(d, output)
        }
    }

    /** Fills form fields into the page so they can no longer be edited. */
    fun flattenForms(input: File, output: File) {
        load(input).use2 { d ->
            d.documentCatalog.acroForm?.flatten()
            save(d, output)
        }
    }

    /** Trims [margin] points from every side of every page (the visible area; the content is not erased). */
    fun crop(input: File, output: File, margin: Float) {
        require(margin >= 0f) { "The margin cannot be negative." }
        load(input).use2 { d ->
            for (page in d.pages) {
                val box = page.mediaBox
                require(box.width > margin * 2 && box.height > margin * 2) { "The margin is larger than the page." }
                page.cropBox = PDRectangle(
                    box.lowerLeftX + margin,
                    box.lowerLeftY + margin,
                    box.width - margin * 2,
                    box.height - margin * 2
                )
            }
            save(d, output)
        }
    }

    /** Draws [pages] to PNG or JPEG files at [dpi]; returns them in order. */
    fun pdfToImages(input: File, outDir: File, pages: List<Int>, dpi: Int, jpeg: Boolean): List<File> {
        outDir.mkdirs()
        val stem = input.nameWithoutExtension
        ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                val list = checked(pages, renderer.pageCount)
                return list.map { n ->
                    renderer.openPage(n - 1).use { page ->
                        val w = (page.width * dpi / PDF_DPI).coerceAtLeast(1)
                        val h = (page.height * dpi / PDF_DPI).coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val f = File(outDir, "$stem-page-$n.${if (jpeg) "jpg" else "png"}")
                        FileOutputStream(f).use {
                            bmp.compress(
                                if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG,
                                JPEG_QUALITY,
                                it
                            )
                        }
                        bmp.recycle()
                        f
                    }
                }
            }
        }
    }

    /**
     * Shrinks the pictures inside: each is re-encoded as JPEG at [quality] (1..100) and, when wider than [maxWidth]
     * pixels, scaled down. Text and vector drawings are untouched. Returns the saving in bytes (0 or less means the
     * file did not get smaller and [output] is the unchanged copy).
     */
    fun compress(input: File, output: File, quality: Int, maxWidth: Int): Long {
        load(input).use2 { d ->
            val seen = HashSet<Any>()
            for (page in d.pages) page.resources?.let { shrinkPictures(d, it, seen, quality, maxWidth) }
            save(d, output)
        }
        return input.length() - output.length()
    }

    /** Recompresses the pictures in [resources], and in the forms inside them; a picture is kept if it is no smaller. */
    private fun shrinkPictures(
        d: PDDocument,
        resources: com.tom_roush.pdfbox.pdmodel.PDResources,
        seen: HashSet<Any>,
        quality: Int,
        maxWidth: Int
    ) {
        for (name in resources.xObjectNames) {
            val x = resources.getXObject(name)
            if (!seen.add(x.cosObject)) continue
            if (x is com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject) {
                x.resources?.let { shrinkPictures(d, it, seen, quality, maxWidth) }
                continue
            }
            if (x !is PDImageXObject) continue
            val bmp = runCatching { x.image }.getOrNull() ?: continue
            val scaled = if (bmp.width > maxWidth) {
                Bitmap.createScaledBitmap(
                    bmp,
                    maxWidth,
                    (bmp.height.toLong() * maxWidth / bmp.width).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bmp
            }
            val replacement = JPEGFactory.createFromImage(d, scaled, quality.coerceIn(1, 100) / 100f)
            val before = runCatching { x.stream.length.toLong() }.getOrDefault(Long.MAX_VALUE)
            val after = runCatching { replacement.stream.length.toLong() }.getOrDefault(0L)
            if (after < before) resources.put(name, replacement)
        }
    }

    /**
     * Makes the whole PDF no bigger than [targetBytes] if it can. First the pictures inside are recompressed, a little
     * more each round; if even the smallest setting is still too big, every page is redrawn as a picture (text can no
     * longer be selected) at lower and lower sharpness. The smallest file made is kept in [output] either way.
     */
    fun compressToSize(
        input: File,
        output: File,
        targetBytes: Long,
        allowPictures: Boolean = true,
        onStep: (String) -> Unit = {}
    ): SizeResult {
        if (input.length() <= targetBytes) {
            input.copyTo(output, overwrite = true)
            return SizeResult(input.length(), reached = true, flattened = false)
        }
        val trial = File(output.parentFile, "${output.name}.trial")
        var passed = false
        var size = Long.MAX_VALUE
        var flat = false

        // A trial that fits replaces the output (later ones are gentler); otherwise the smallest miss is kept.
        fun finish(isFlat: Boolean): Boolean {
            val s = trial.length()
            val fits = s <= targetBytes
            if (fits || (!passed && s < size)) {
                trial.copyTo(output, overwrite = true)
                size = s
                flat = isFlat
                passed = passed || fits
            }
            return fits
        }
        try {
            onStep("Shrinking the pictures…")
            val fitted = searchSteps(IMAGE_STEPS.size) { i ->
                compress(input, trial, IMAGE_STEPS[i].first, IMAGE_STEPS[i].second)
                finish(false)
            }
            if (!fitted && allowPictures) {
                onStep("Redrawing the pages…")
                searchSteps(PAGE_STEPS.size) { i ->
                    rasterize(input, trial, PAGE_STEPS[i].first, quality = PAGE_STEPS[i].second) { _, _ -> }
                    finish(true)
                }
            }
        } finally {
            trial.delete()
        }
        return SizeResult(size, passed, flat, triedPictures = allowPictures)
    }

    /**
     * Finds the gentlest of [count] steps (0 gentlest) for which [fits] is true, trying the harshest first: if even
     * that misses, nothing else is tried. Sizes only fall as steps get harsher, so the rest is a binary search.
     */
    private fun searchSteps(count: Int, fits: (Int) -> Boolean): Boolean {
        if (!fits(count - 1)) return false
        var lo = 0
        var hi = count - 2
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (fits(mid)) hi = mid - 1 else lo = mid + 1
        }
        return true
    }

    /** Drops pages with no text and no pictures (scanner separators, accidental blanks). Returns how many were removed. */
    fun removeBlankPages(input: File, output: File): Int = load(input).use2 { d ->
        val stripper = PDFTextStripper()
        val blank = mutableListOf<PDPage>()
        for (i in 1..d.numberOfPages) {
            stripper.startPage = i
            stripper.endPage = i
            val page = d.getPage(i - 1)
            val hasText = stripper.getText(d).isNotBlank()
            val hasImage = page.resources?.xObjectNames?.any { name ->
                page.resources.getXObject(name) is PDImageXObject
            } ?: false
            if (!hasText && !hasImage) blank += page
        }
        if (blank.size == d.numberOfPages) throw PdfToolException("Every page is blank.")
        blank.forEach { d.removePage(it) }
        save(d, output)
        blank.size
    }

    /** Puts [perSheet] pages (2, 4, 6, 9 or 16) on each sheet, on the size of the first page. */
    fun nUp(input: File, output: File, perSheet: Int) {
        val grid = mapOf(2 to (2 to 1), 4 to (2 to 2), 6 to (3 to 2), 9 to (3 to 3), 16 to (4 to 4))[perSheet]
            ?: throw IllegalArgumentException("Pick 2, 4, 6, 9 or 16 pages per sheet.")
        val (cols, rows) = grid
        load(input).use2 { src ->
            PDDocument().use2 { out ->
                val layer = com.tom_roush.pdfbox.multipdf.LayerUtility(out)
                val first = src.getPage(0).mediaBox
                val sheet = if (cols >
                    rows
                ) {
                    PDRectangle(first.height, first.width)
                } else {
                    PDRectangle(first.width, first.height)
                }
                val cellW = sheet.width / cols
                val cellH = sheet.height / rows
                var i = 0
                while (i < src.numberOfPages) {
                    val page = PDPage(sheet)
                    out.addPage(page)
                    PDPageContentStream(out, page, PDPageContentStream.AppendMode.APPEND, true).use { cs ->
                        for (slot in 0 until perSheet) {
                            if (i >= src.numberOfPages) break
                            val form = layer.importPageAsForm(src, i)
                            val box = src.getPage(i).mediaBox
                            val scale = minOf(cellW / box.width, cellH / box.height)
                            val col = slot % cols
                            val row = slot / cols
                            cs.saveGraphicsState()
                            cs.transform(
                                com.tom_roush.pdfbox.util.Matrix.getTranslateInstance(
                                    col * cellW + (cellW - box.width * scale) / 2,
                                    sheet.height - (row + 1) * cellH + (cellH - box.height * scale) / 2
                                )
                            )
                            cs.transform(com.tom_roush.pdfbox.util.Matrix.getScaleInstance(scale, scale))
                            cs.drawForm(form)
                            cs.restoreGraphicsState()
                            i++
                        }
                    }
                }
                save(out, output)
            }
        }
    }

    /** Fits every page on an A4 sheet, centred and shrunk or grown to fit. */
    fun scaleToA4(input: File, output: File) {
        load(input).use2 { src ->
            PDDocument().use2 { out ->
                val layer = com.tom_roush.pdfbox.multipdf.LayerUtility(out)
                val sheet = PDRectangle.A4
                for (i in 0 until src.numberOfPages) {
                    val box = src.getPage(i).mediaBox
                    val page = PDPage(sheet)
                    out.addPage(page)
                    val scale = minOf(sheet.width / box.width, sheet.height / box.height)
                    PDPageContentStream(out, page, PDPageContentStream.AppendMode.APPEND, true).use { cs ->
                        cs.transform(
                            com.tom_roush.pdfbox.util.Matrix.getTranslateInstance(
                                (sheet.width - box.width * scale) / 2,
                                (sheet.height - box.height * scale) / 2
                            )
                        )
                        cs.transform(com.tom_roush.pdfbox.util.Matrix.getScaleInstance(scale, scale))
                        cs.drawForm(layer.importPageAsForm(src, i))
                    }
                }
                save(out, output)
            }
        }
    }

    /** Orders pages two to a sheet so that, printed double-sided and folded, they read as a booklet. */
    fun booklet(input: File, output: File) {
        load(input).use2 { src ->
            PDDocument().use2 { out ->
                val layer = com.tom_roush.pdfbox.multipdf.LayerUtility(out)
                val n = src.numberOfPages
                val padded = (n + 3) / 4 * 4
                val first = src.getPage(0).mediaBox
                val sheet = PDRectangle(first.width * 2, first.height)
                val order = mutableListOf<Int>()
                for (s in 0 until padded / 4) {
                    order += listOf(padded - 1 - 2 * s, 2 * s, 2 * s + 1, padded - 2 - 2 * s)
                }
                order.chunked(2).forEach { pair ->
                    val page = PDPage(sheet)
                    out.addPage(page)
                    PDPageContentStream(out, page, PDPageContentStream.AppendMode.APPEND, true).use { cs ->
                        pair.forEachIndexed { slot, idx ->
                            if (idx >= n) return@forEachIndexed
                            val box = src.getPage(idx).mediaBox
                            val scale = minOf(first.width / box.width, first.height / box.height)
                            cs.saveGraphicsState()
                            cs.transform(
                                com.tom_roush.pdfbox.util.Matrix.getTranslateInstance(
                                    slot * first.width + (first.width - box.width * scale) / 2,
                                    (first.height - box.height * scale) / 2
                                )
                            )
                            cs.transform(com.tom_roush.pdfbox.util.Matrix.getScaleInstance(scale, scale))
                            cs.drawForm(layer.importPageAsForm(src, idx))
                            cs.restoreGraphicsState()
                        }
                    }
                }
                save(out, output)
            }
        }
    }

    /** One file per top-level bookmark (chapter), named after it. */
    fun splitByBookmarks(input: File, outDir: File): List<File> {
        outDir.mkdirs()
        val result = mutableListOf<File>()
        load(input).use2 { d ->
            val outline = d.documentCatalog.documentOutline ?: throw PdfToolException("This document has no bookmarks.")
            val starts = mutableListOf<Pair<String, Int>>()
            var item = outline.firstChild
            while (item != null) {
                val page = runCatching { item!!.findDestinationPage(d) }.getOrNull()
                val index = page?.let { d.pages.indexOf(it) } ?: -1
                if (index >= 0) starts += (item.title ?: "Part ${starts.size + 1}") to index
                item = item.nextSibling
            }
            val sorted = starts.sortedBy { it.second }.distinctBy { it.second }
            if (sorted.isEmpty()) throw PdfToolException("The bookmarks do not point to pages.")
            sorted.forEachIndexed { i, (title, start) ->
                val end = if (i + 1 < sorted.size) sorted[i + 1].second - 1 else d.numberOfPages - 1
                val name = "%02d-%s".format(
                    i + 1,
                    title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().take(60).ifEmpty {
                        "part"
                    }
                )
                val f = File(outDir, "$name.pdf")
                extractPages(input, f, (start + 1..end + 1).toList())
                result += f
            }
        }
        return result
    }

    /** Removes notes, highlights and drawn marks; links stay so the contents still work. */
    fun removeAnnotations(input: File, output: File) {
        load(input).use2 { d ->
            for (page in d.pages) {
                page.annotations =
                    page.annotations.filterIsInstance<com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink>()
            }
            save(d, output)
        }
    }

    /** Lists, page by page, the lines that are in one document and not the other. */
    fun compare(a: File, b: File): String {
        fun lines(f: File): List<List<String>> = load(f).use2 { d ->
            val stripper = PDFTextStripper()
            (1..d.numberOfPages).map { p ->
                stripper.startPage = p
                stripper.endPage = p
                stripper.getText(d).lines().map { it.trim() }.filter { it.isNotEmpty() }
            }
        }
        val x = lines(a)
        val y = lines(b)
        val sb = StringBuilder("Comparing ${a.name} (A) with ${b.name} (B)\n")
        var same = true
        for (i in 0 until maxOf(x.size, y.size)) {
            val la = x.getOrElse(i) { emptyList() }
            val lb = y.getOrElse(i) { emptyList() }
            val onlyA = la.filter { it !in lb.toSet() }
            val onlyB = lb.filter { it !in la.toSet() }
            if (onlyA.isEmpty() && onlyB.isEmpty()) continue
            same = false
            sb.append("\nPage ${i + 1}\n")
            onlyA.forEach { sb.append("  A only: $it\n") }
            onlyB.forEach { sb.append("  B only: $it\n") }
        }
        if (same) sb.append("\nThe text is the same on every page.\n")
        if (x.size != y.size) sb.append("\nA has ${x.size} pages, B has ${y.size}.\n")
        return sb.toString()
    }

    private fun rasterize(
        input: File,
        output: File,
        dpi: Int,
        colorFilter: android.graphics.ColorMatrix? = null,
        quality: Float = RASTER_QUALITY,
        draw: (Bitmap, Int) -> Unit
    ) {
        ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                PDDocument().use2 { d ->
                    for (n in 0 until renderer.pageCount) {
                        renderer.openPage(n).use { page ->
                            val w = (page.width * dpi / PDF_DPI).coerceAtLeast(1)
                            val h = (page.height * dpi / PDF_DPI).coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                                eraseColor(Color.WHITE)
                            }
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val shown = if (colorFilter == null) {
                                bmp
                            } else {
                                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                                    val paint = android.graphics.Paint().apply {
                                        setColorFilter(android.graphics.ColorMatrixColorFilter(colorFilter))
                                    }
                                    android.graphics.Canvas(it).drawBitmap(bmp, 0f, 0f, paint)
                                    bmp.recycle()
                                }
                            }
                            draw(shown, n + 1)
                            val pdfPage = PDPage(PDRectangle(page.width.toFloat(), page.height.toFloat()))
                            d.addPage(pdfPage)
                            val image = JPEGFactory.createFromImage(d, shown, quality)
                            PDPageContentStream(d, pdfPage).use {
                                it.drawImage(image, 0f, 0f, page.width.toFloat(), page.height.toFloat())
                            }
                            shown.recycle()
                        }
                    }
                    save(d, output)
                }
            }
        }
    }

    private fun checked(pages: List<Int>, count: Int): List<Int> {
        require(pages.isNotEmpty()) { "No pages chosen." }
        pages.firstOrNull {
            it !in 1..count
        }?.let { throw IllegalArgumentException("Page $it is not in this document (1 to $count).") }
        return pages
    }

    private companion object {
        const val PDF_DPI = 72
        const val RASTER_QUALITY = 0.85f

        /** Picture quality (percent) and widest side, from gentle to harsh. */
        val IMAGE_STEPS = listOf(
            90 to 2400,
            80 to 1800,
            70 to 1400,
            60 to 1100,
            50 to 900,
            40 to 700,
            30 to 550,
            20 to 400
        )

        /** Resolution and quality for pages redrawn as pictures, from sharp to coarse. */
        val PAGE_STEPS = listOf(
            150 to 0.7f,
            120 to 0.6f,
            100 to 0.5f,
            80 to 0.45f,
            65 to 0.4f,
            50 to 0.35f
        )
        const val JPEG_QUALITY = 92
    }
}
