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

    /** Diagonal text across every page, [opacity] 0..1. Text is limited to what the PDF base fonts can draw. */
    fun watermark(input: File, output: File, text: String, opacity: Float, fontSize: Float) {
        val safe = text.filter { it.code in 32..126 || it.code in 160..255 }.trim()
        require(safe.isNotEmpty()) { "The watermark needs text the standard PDF fonts can draw (Latin letters)." }
        load(input).use2 { d ->
            val font = PDType1Font.HELVETICA_BOLD
            val state = PDExtendedGraphicsState().apply {
                nonStrokingAlphaConstant = opacity.coerceIn(0.05f, 1f)
            }
            for (page in d.pages) {
                val box = page.mediaBox
                PDPageContentStream(d, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.setGraphicsStateParameters(state)
                    cs.setNonStrokingColor(GREY, GREY, GREY)
                    cs.beginText()
                    cs.setFont(font, fontSize)
                    val width = font.getStringWidth(safe) / 1000f * fontSize
                    val x = (box.width - width / 1.4142f) / 2f
                    val y = box.height / 2f - width / 1.4142f / 2f
                    val rad = Math.toRadians(45.0)
                    cs.setTextMatrix(
                        com.tom_roush.pdfbox.util.Matrix(
                            Math.cos(rad).toFloat(),
                            Math.sin(rad).toFloat(),
                            -Math.sin(rad).toFloat(),
                            Math.cos(rad).toFloat(),
                            x,
                            y
                        )
                    )
                    cs.showText(safe)
                    cs.endText()
                }
            }
            save(d, output)
        }
    }

    /** "Page N of M" along the bottom of each page. */
    fun pageNumbers(input: File, output: File, start: Int) {
        load(input).use2 { d ->
            val font = PDType1Font.HELVETICA
            val total = d.numberOfPages
            d.pages.forEachIndexed { i, page ->
                val label = "Page ${start + i} of ${start + total - 1}"
                val width = font.getStringWidth(label) / 1000f * NUMBER_SIZE
                val box = page.mediaBox
                PDPageContentStream(d, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.beginText()
                    cs.setFont(font, NUMBER_SIZE)
                    cs.newLineAtOffset((box.width - width) / 2f, NUMBER_MARGIN)
                    cs.showText(label)
                    cs.endText()
                }
            }
            save(d, output)
        }
    }

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

    /** All the text, page by page; scanned pages have none (use OCR first). */
    fun extractText(input: File, password: String = ""): String =
        load(input, password).use2 { PDFTextStripper().getText(it) }

    /** One page per image, each scaled to the image's own size, on a white sheet. */
    fun imagesToPdf(images: List<File>, output: File) {
        require(images.isNotEmpty()) { "Choose at least one picture." }
        PDDocument().use2 { d ->
            for (file in images) {
                val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    ?: throw PdfToolException("${file.name} is not a picture that can be read.")
                try {
                    val image = LosslessFactory.createFromImage(d, bitmap)
                    val page = PDPage(PDRectangle(bitmap.width.toFloat(), bitmap.height.toFloat()))
                    d.addPage(page)
                    PDPageContentStream(d, page).use {
                        it.drawImage(image, 0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
                    }
                } finally {
                    bitmap.recycle()
                }
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
            for (page in d.pages) {
                val resources = page.resources ?: continue
                for (name in resources.xObjectNames) {
                    val x = resources.getXObject(name)
                    if (x !is PDImageXObject || !seen.add(x.cosObject)) continue
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
                    resources.put(name, replacement)
                }
            }
            save(d, output)
        }
        return input.length() - output.length()
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

    /** Strips scripts, attached files, form-submit actions and metadata, the things a document can carry that you may not want to share. */
    fun sanitize(input: File, output: File) {
        load(input).use2 { d ->
            val catalog = d.documentCatalog
            catalog.names?.cosObject?.apply {
                removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("JavaScript"))
                removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("EmbeddedFiles"))
            }
            catalog.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("OpenAction"))
            catalog.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("AA"))
            catalog.metadata = null
            d.documentInformation = com.tom_roush.pdfbox.pdmodel.PDDocumentInformation()
            for (page in d.pages) {
                page.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("AA"))
                page.annotations.forEach { a ->
                    a.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("A"))
                }
            }
            save(d, output)
        }
    }

    /** Rewrites the file from its parsed contents, which fixes many files other readers refuse to open. */
    fun repair(input: File, output: File) {
        load(input).use2 { d -> save(d, output) }
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

    /** All pages stacked into one long page, handy for reading a document as a scroll. */
    fun singlePage(input: File, output: File) {
        load(input).use2 { src ->
            PDDocument().use2 { out ->
                val layer = com.tom_roush.pdfbox.multipdf.LayerUtility(out)
                val width = (0 until src.numberOfPages).maxOf { src.getPage(it).mediaBox.width }
                val height = (0 until src.numberOfPages).sumOf { src.getPage(it).mediaBox.height.toDouble() }.toFloat()
                val page = PDPage(PDRectangle(width, height))
                out.addPage(page)
                PDPageContentStream(out, page, PDPageContentStream.AppendMode.APPEND, true).use { cs ->
                    var y = height
                    for (i in 0 until src.numberOfPages) {
                        val box = src.getPage(i).mediaBox
                        y -= box.height
                        cs.saveGraphicsState()
                        cs.transform(com.tom_roush.pdfbox.util.Matrix.getTranslateInstance(0f, y))
                        cs.drawForm(layer.importPageAsForm(src, i))
                        cs.restoreGraphicsState()
                    }
                }
                save(out, output)
            }
        }
    }

    /** Saves each embedded picture as a PNG into [outDir]. */
    fun extractImages(input: File, outDir: File): List<File> {
        outDir.mkdirs()
        val files = mutableListOf<File>()
        load(input).use2 { d ->
            d.pages.forEachIndexed { p, page ->
                val res = page.resources ?: return@forEachIndexed
                res.xObjectNames.forEachIndexed { n, name ->
                    val image = res.getXObject(name) as? PDImageXObject ?: return@forEachIndexed
                    val f = File(outDir, "page%03d-%02d.png".format(p + 1, n + 1))
                    FileOutputStream(f).use { image.image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    files += f
                }
            }
        }
        if (files.isEmpty()) throw PdfToolException("There are no pictures in this document.")
        return files
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

    /** Cuts the file into parts that are each about [maxMegabytes] or smaller. */
    fun splitBySize(input: File, outDir: File, maxMegabytes: Int): List<File> {
        require(maxMegabytes > 0) { "Enter a size in megabytes." }
        val pages = load(input).use2 { it.numberOfPages }
        val limit = maxMegabytes * BYTES_PER_MB
        val parts = ((input.length() + limit - 1) / limit).toInt().coerceAtLeast(1)
        return split(input, outDir, ((pages + parts - 1) / parts).coerceAtLeast(1))
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

    /** Takes the pictures out of every page, leaving the text. Pictures inside other drawings are kept. */
    fun removeImages(input: File, output: File) {
        load(input).use2 { d ->
            for (page in d.pages) {
                val res = page.resources ?: continue
                val dict = res.cosObject.getDictionaryObject(com.tom_roush.pdfbox.cos.COSName.XOBJECT)
                    as? com.tom_roush.pdfbox.cos.COSDictionary ?: continue
                res.xObjectNames.filter { res.getXObject(it) is PDImageXObject }.forEach { dict.removeItem(it) }
            }
            save(d, output)
        }
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

    /** Saves the files attached inside the document. */
    fun extractAttachments(input: File, outDir: File): List<File> {
        outDir.mkdirs()
        val files = mutableListOf<File>()
        load(input).use2 { d ->
            fun walk(
                node:
                com.tom_roush.pdfbox.pdmodel.common.PDNameTreeNode<com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification>
            ) {
                node.names?.forEach { (name, spec) ->
                    val bytes = spec.embeddedFile?.toByteArray() ?: return@forEach
                    val f = File(outDir, name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "attachment" })
                    f.writeBytes(bytes)
                    files += f
                }
                node.kids?.forEach { walk(it) }
            }
            d.documentCatalog.names?.embeddedFiles?.let { walk(it) }
        }
        if (files.isEmpty()) throw PdfToolException("This document has no attached files.")
        return files
    }

    /** Lays the first page of [top] over every page of [base]. */
    fun overlay(base: File, top: File, output: File) {
        load(base).use2 { b ->
            load(top).use2 { t ->
                val layer = com.tom_roush.pdfbox.multipdf.LayerUtility(b)
                val form = layer.importPageAsForm(t, 0)
                for (page in b.pages) {
                    PDPageContentStream(b, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        val box = page.mediaBox
                        val tb = t.getPage(0).mediaBox
                        val scale = minOf(box.width / tb.width, box.height / tb.height)
                        cs.transform(com.tom_roush.pdfbox.util.Matrix.getScaleInstance(scale, scale))
                        cs.drawForm(form)
                    }
                }
                save(b, output)
            }
        }
    }

    /** Writes [text] at the top or bottom of every page; {n} is the page number and {total} the page count. */
    fun headerFooter(input: File, output: File, text: String, top: Boolean) {
        require(text.isNotBlank()) { "Enter the text to add." }
        load(input).use2 { d ->
            val font = PDType1Font.HELVETICA
            val total = d.numberOfPages
            d.pages.forEachIndexed { i, page ->
                val line = text.replace("{n}", (i + 1).toString()).replace("{total}", total.toString())
                val box = page.mediaBox
                val width = font.getStringWidth(line) / FONT_UNITS * HEADER_SIZE
                PDPageContentStream(d, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.beginText()
                    cs.setFont(font, HEADER_SIZE)
                    cs.newLineAtOffset((box.width - width) / 2, if (top) box.height - HEADER_MARGIN else HEADER_MARGIN)
                    cs.showText(line)
                    cs.endText()
                }
            }
            save(d, output)
        }
    }

    /** Draws the picture [image] on every page, [widthPercent] of the page width, centred, at [opacity] 0..1. */
    fun stampImage(pdf: File, image: File, output: File, opacity: Float, widthPercent: Int) {
        val bitmap = android.graphics.BitmapFactory.decodeFile(image.absolutePath)
            ?: throw PdfToolException("${image.name} is not a picture that can be read.")
        try {
            load(pdf).use2 { d ->
                val stamp = LosslessFactory.createFromImage(d, bitmap)
                val state = PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = opacity.coerceIn(0.05f, 1f) }
                for (page in d.pages) {
                    val box = page.mediaBox
                    val w = box.width * widthPercent.coerceIn(MIN_STAMP, MAX_STAMP) / PERCENT
                    val h = w * bitmap.height / bitmap.width
                    PDPageContentStream(d, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.setGraphicsStateParameters(state)
                        cs.drawImage(stamp, (box.width - w) / 2f, (box.height - h) / 2f, w, h)
                    }
                }
                save(d, output)
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Turns web addresses written in the text into links that open when tapped; returns how many were made. */
    fun linkAddresses(input: File, output: File): Int {
        var made = 0
        load(input).use2 { d ->
            val found = ArrayList<Pair<Int, com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink>>()
            val stripper = object : com.tom_roush.pdfbox.text.PDFTextStripper() {
                override fun writeString(text: String, positions: MutableList<com.tom_roush.pdfbox.text.TextPosition>) {
                    for (m in URL_PATTERN.findAll(text)) {
                        val span = positions.subList(
                            m.range.first.coerceAtMost(positions.size - 1),
                            (m.range.last + 1).coerceAtMost(positions.size)
                        )
                        if (span.isEmpty()) continue
                        val box = PDRectangle(
                            span.minOf { it.xDirAdj },
                            d.getPage(currentPageNo - 1).mediaBox.height - span.maxOf { it.yDirAdj },
                            span.maxOf { it.xDirAdj + it.widthDirAdj } - span.minOf { it.xDirAdj },
                            span.maxOf { it.heightDir }
                        )
                        val address = m.value.trimEnd('.', ',', ')', ';')
                        val link = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink().apply {
                            rectangle = box
                            action = com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI().apply {
                                uri = if (address.startsWith("www.")) "https://$address" else address
                            }
                            setBorderStyle(
                                com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary().apply {
                                    width =
                                        0f
                                }
                            )
                        }
                        found += (currentPageNo - 1) to link
                    }
                    super.writeString(text, positions)
                }
            }
            stripper.getText(d)
            for ((index, link) in found) {
                d.getPage(index).annotations.add(link)
                made++
            }
            save(d, output)
        }
        return made
    }

    /** A comic book archive (.cbz, a zip of page pictures) as a PDF, the pictures in name order. */
    fun comicToPdf(archive: File, output: File, workDir: File) {
        val pictures = ArrayList<File>()
        workDir.mkdirs()
        try {
            java.util.zip.ZipFile(archive).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && COMIC_PICTURE.containsMatchIn(it.name) }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                    .forEachIndexed { i, entry ->
                        val file = File(workDir, "page-${i.toString().padStart(COMIC_DIGITS, '0')}.img")
                        zip.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
                        pictures += file
                    }
            }
            if (pictures.isEmpty()) throw PdfToolException("No page pictures were found in ${archive.name}.")
            imagesToPdf(pictures, output)
        } catch (e: java.util.zip.ZipException) {
            throw PdfToolException("${archive.name} is not a comic archive (.cbz).")
        } finally {
            pictures.forEach { it.delete() }
        }
    }

    /** A new PDF of [pages] empty A4 pages (1 to 500). */
    fun blankPdf(output: File, pages: Int) {
        require(pages in 1..MAX_BLANK_PAGES) { "Choose between 1 and $MAX_BLANK_PAGES pages." }
        val doc = PDDocument()
        try {
            repeat(pages) { doc.addPage(PDPage(PDRectangle.A4)) }
            save(doc, output)
        } finally {
            doc.close()
        }
    }

    /** A PDF made from a text, Markdown or HTML file: A4 pages, wrapped lines, "#" headings in bold. */
    @Suppress("NestedBlockDepth")
    fun textToPdf(input: File, output: File) {
        val raw = input.readText()
        val html = input.extension.lowercase() in setOf("html", "htm", "xhtml")
        val lines = if (html) BookConverter.htmlToParagraphs(raw) else raw.lines()
        require(lines.any { it.isNotBlank() }) { "That file has no text." }
        val doc = PDDocument()
        try {
            val box = PDRectangle.A4
            val margin = TEXT_MARGIN
            var page = PDPage(box)
            doc.addPage(page)
            var cs = PDPageContentStream(doc, page)
            var y = box.height - margin
            fun fresh() {
                cs.close()
                page = PDPage(box)
                doc.addPage(page)
                cs = PDPageContentStream(doc, page)
                y = box.height - margin
            }
            for (line in lines) {
                val heading = !html && line.startsWith("#")
                val text = pdfSafe(if (heading) line.trimStart('#', ' ') else line.replace("**", "").replace("`", ""))
                val font = if (heading) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
                val size = if (heading) TEXT_HEAD else TEXT_BODY
                val lead = size * TEXT_LEADING
                for (part in wrap(text, font, size, box.width - 2 * margin).ifEmpty { listOf("") }) {
                    if (y < margin) fresh()
                    cs.beginText()
                    cs.setFont(font, size)
                    cs.newLineAtOffset(margin, y)
                    cs.showText(part)
                    cs.endText()
                    y -= lead
                }
                if (heading) y -= lead / 2
            }
            cs.close()
            save(doc, output)
        } finally {
            doc.close()
        }
    }

    private fun pdfSafe(text: String) = buildString {
        for (c in text) {
            append(
                if (c.code in 32..126 ||
                    c.code in 161..255
                ) {
                    c
                } else if (c == '\t') {
                    "    "
                } else if (c.code < 32) {
                    ""
                } else {
                    "?"
                }
            )
        }
    }

    private fun wrap(text: String, font: PDType1Font, size: Float, width: Float): List<String> {
        val out = mutableListOf<String>()
        var cur = ""
        for (word in text.split(' ')) {
            val next = if (cur.isEmpty()) word else "$cur $word"
            if (font.getStringWidth(next) / FONT_UNITS * size <= width) {
                cur = next
            } else {
                if (cur.isNotEmpty()) out += cur
                cur = word
            }
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** Adds bookmarks from lines like "Introduction, 3" (a title, a comma, the page); replaces the old ones. */
    fun addBookmarks(input: File, output: File, spec: String) {
        val entries = spec.lines().map { it.trim() }.filter { it.isNotEmpty() }.map {
            val at = it.lastIndexOf(',')
            require(at > 0) { "Write each bookmark as: title, page number." }
            it.substring(0, at).trim() to (
                it.substring(at + 1).trim().toIntOrNull()
                    ?: throw IllegalArgumentException("\"${it.substring(at + 1).trim()}\" is not a page number.")
                )
        }
        require(entries.isNotEmpty()) { "Enter at least one bookmark." }
        load(input).use2 { d ->
            val outline = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline()
            d.documentCatalog.documentOutline = outline
            for ((title, page) in entries) {
                require(page in 1..d.numberOfPages) { "Page $page is not in this PDF (1 to ${d.numberOfPages})." }
                val item = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem()
                item.title = title
                item.destination = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination
                    .PDPageFitDestination().also { it.page = d.getPage(page - 1) }
                outline.addLast(item)
            }
            outline.openNode()
            save(d, output)
        }
    }

    /** A plain-language report on a PDF: size, pages, text or scanned, fonts, forms, attachments, bookmarks, locks. */
    @Suppress("CyclomaticComplexMethod")
    fun healthCheck(input: File): String = load(input).use2 { d ->
        val fonts = mutableSetOf<String>()
        var unembedded = 0
        var images = 0
        for (page in d.pages) {
            val res = page.resources ?: continue
            for (name in res.fontNames) {
                val f = runCatching { res.getFont(name) }.getOrNull() ?: continue
                if (fonts.add(f.name ?: name.name) && !f.isEmbedded && !f.isStandard14) unembedded++
            }
            images += res.xObjectNames.count { runCatching { res.isImageXObject(it) }.getOrDefault(false) }
        }
        val text = PDFTextStripper().also { it.endPage = minOf(d.numberOfPages, HEALTH_SAMPLE_PAGES) }.getText(d)
        val info = d.documentInformation
        val files = d.documentCatalog.names?.embeddedFiles?.names?.size ?: 0
        val forms = d.documentCatalog.acroForm?.fields?.size ?: 0
        val links = d.pages.sumOf { p -> p.annotations.count { it.subtype == "Link" } }
        buildString {
            appendLine("PDF health check")
            appendLine("File size: ${"%.1f".format(input.length() / BYTES_PER_MB.toFloat())} MB")
            appendLine("Pages: ${d.numberOfPages}   PDF version: ${d.version}")
            appendLine("Locked: ${if (d.isEncrypted) "yes (needs a password or has permissions)" else "no"}")
            appendLine(
                "Text: " +
                    if (text.isBlank()) {
                        "none found in the first pages: this looks scanned, so run OCR to search it"
                    } else {
                        "searchable"
                    }
            )
            appendLine("Pictures on pages: $images")
            appendLine(
                "Fonts: ${fonts.size}" +
                    if (unembedded >
                        0
                    ) {
                        " ($unembedded not embedded; may look different on other devices)"
                    } else {
                        " (all embedded)"
                    }
            )
            appendLine("Form fields: $forms   Attachments: $files   Links: $links")
            appendLine("Bookmarks: ${if (d.documentCatalog.documentOutline?.hasChildren() == true) "yes" else "none"}")
            appendLine("Title: ${info.title ?: "not set"}   Author: ${info.author ?: "not set"}")
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

    /**
     * Blacks out every place [terms] appear. The pages are redrawn as pictures with the boxes burned in, so the
     * hidden words are gone from the file and cannot be copied out. The result is no longer searchable.
     */
    fun redact(input: File, output: File, terms: List<String>, dpi: Int = RASTER_DPI) {
        val wanted = terms.map { it.trim() }.filter { it.isNotEmpty() }
        require(wanted.isNotEmpty()) { "Enter the words to black out, separated by commas." }
        val boxes = mutableMapOf<Int, MutableList<android.graphics.RectF>>()
        var hits = 0
        load(input).use2 { d ->
            for (p in 1..d.numberOfPages) {
                val stripper = object : PDFTextStripper() {
                    override fun writeString(
                        text: String,
                        positions: MutableList<com.tom_roush.pdfbox.text.TextPosition>
                    ) {
                        for (term in wanted) {
                            var from = text.indexOf(term, ignoreCase = true)
                            while (from >= 0) {
                                val span = positions.subList(from, minOf(from + term.length, positions.size))
                                if (span.isNotEmpty()) {
                                    val left = span.minOf { it.xDirAdj }
                                    val right = span.maxOf { it.xDirAdj + it.widthDirAdj }
                                    val top = span.minOf { it.yDirAdj - it.heightDir }
                                    val bottom = span.maxOf { it.yDirAdj }
                                    boxes.getOrPut(p) {
                                        mutableListOf()
                                    }.add(android.graphics.RectF(left, top, right, bottom))
                                    hits++
                                }
                                from = text.indexOf(term, from + term.length, ignoreCase = true)
                            }
                        }
                    }
                }
                stripper.startPage = p
                stripper.endPage = p
                stripper.getText(d)
            }
        }
        if (hits == 0) throw PdfToolException("None of those words were found.")
        val k = dpi / PDF_DPI.toFloat()
        rasterize(input, output, dpi) { bmp, page ->
            val c = android.graphics.Canvas(bmp)
            val paint = android.graphics.Paint().apply { color = Color.BLACK }
            boxes[page]?.forEach { r ->
                c.drawRect(r.left * k - 1, r.top * k - 1, r.right * k + 1, r.bottom * k + 1, paint)
            }
        }
    }

    /** Redraws every page in grey tones (pictures; the text stops being searchable). */
    fun grayscale(input: File, output: File) = rasterize(
        input,
        output,
        RASTER_DPI,
        colorFilter = android.graphics.ColorMatrix().apply {
            setSaturation(0f)
        }
    ) { _, _ -> }

    /** Redraws every page with the colours reversed, which saves ink and suits night reading. */
    fun invert(input: File, output: File) = rasterize(
        input,
        output,
        RASTER_DPI,
        colorFilter = android.graphics.ColorMatrix(
            floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)
        )
    ) { _, _ -> }

    private fun rasterize(
        input: File,
        output: File,
        dpi: Int,
        colorFilter: android.graphics.ColorMatrix? = null,
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
                            val image = JPEGFactory.createFromImage(d, shown, RASTER_QUALITY)
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
        const val GREY = 0.5f
        const val NUMBER_SIZE = 10f
        const val NUMBER_MARGIN = 18f
        const val PDF_DPI = 72
        const val MAX_BLANK_PAGES = 500
        const val COMIC_DIGITS = 5
        val COMIC_PICTURE = Regex("""\.(jpe?g|png|webp)$""", RegexOption.IGNORE_CASE)
        const val MIN_STAMP = 5
        const val MAX_STAMP = 100
        const val PERCENT = 100f
        val URL_PATTERN = Regex("""(https?://|www\.)[^\s<>"]+""")
        const val TEXT_MARGIN = 56f
        const val TEXT_BODY = 11f
        const val TEXT_HEAD = 16f
        const val TEXT_LEADING = 1.35f
        const val HEALTH_SAMPLE_PAGES = 5
        const val BYTES_PER_MB = 1024L * 1024L
        const val FONT_UNITS = 1000f
        const val HEADER_SIZE = 10f
        const val HEADER_MARGIN = 24f
        const val RASTER_DPI = 150
        const val RASTER_QUALITY = 0.85f
        const val JPEG_QUALITY = 92
    }
}
