@file:Suppress(
    "MagicNumber",
    "TooManyFunctions",
    "LongParameterList",
    "SwallowedException",
    "LoopWithTooManyJumpStatements",
    "MaxLineLength"
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
        const val JPEG_QUALITY = 92
    }
}
