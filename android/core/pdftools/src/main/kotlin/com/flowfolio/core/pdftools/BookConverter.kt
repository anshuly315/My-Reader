@file:Suppress(
    "MagicNumber",
    "TooManyFunctions",
    "SwallowedException",
    "MaxLineLength",
    "ReturnCount",
    "NestedBlockDepth",
    "ComplexCondition",
    "TooGenericExceptionCaught",
    "CyclomaticComplexMethod",
    "LongMethod",
    "ComplexMethod"
)

package com.flowfolio.core.pdftools

import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/** A chapter of a converted book: [title] and its paragraphs of plain text. */
data class Chapter(val title: String, val paragraphs: List<String>)

/**
 * Brings other book formats the phone can read in one step to EPUB or PDF, so the reader opens them like any other
 * book. Plain text, HTML, FB2 and RTF become an EPUB; comic archives (CBZ) become a PDF with one page per picture.
 * All of it runs on the phone. MOBI, DJVU and CBR need native decoders and are not handled here.
 */
object BookConverter {
    val EPUB_INPUTS = setOf("txt", "text", "md", "html", "htm", "xhtml", "fb2", "rtf")
    val COMIC_INPUTS = setOf("cbz", "zip")

    fun isSupported(file: File): Boolean = file.extension.lowercase() in EPUB_INPUTS + COMIC_INPUTS

    /** Reads [input] and writes an EPUB to [output]. */
    fun toEpub(input: File, output: File) {
        val ext = input.extension.lowercase()
        val fallback = input.nameWithoutExtension.replace('_', ' ').trim().ifEmpty { "Untitled" }
        val (title, author, chapters) = when (ext) {
            "fb2" -> parseFb2(input, fallback)

            "html", "htm", "xhtml" -> Triple(
                fallback,
                "",
                listOf(Chapter(fallback, htmlToParagraphs(input.readText())))
            )

            "rtf" -> Triple(fallback, "", listOf(Chapter(fallback, rtfToParagraphs(input.readText()))))

            "txt", "text", "md" -> Triple(fallback, "", textChapters(input.readText(), fallback))

            else -> throw PdfToolException("This file type cannot be converted to an EPUB.")
        }
        if (chapters.all { it.paragraphs.isEmpty() }) throw PdfToolException("There is no text in this file.")
        writeEpub(output, title, author, chapters)
    }

    /** Writes the pictures inside a comic archive, in name order, to one PDF at [output]; [work] holds temporary files. */
    fun comicToPdf(input: File, output: File, work: File, kit: PdfToolkit) {
        work.mkdirs()
        val images = mutableListOf<File>()
        ZipFile(input).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in IMAGE_EXT }
                .filter { !it.name.substringAfterLast('/').startsWith(".") && !it.name.startsWith("__MACOSX") }
                .sortedWith(compareBy(NaturalOrder) { it.name })
                .forEachIndexed { i, e ->
                    val f = File(work, "%05d.%s".format(i, e.name.substringAfterLast('.').lowercase()))
                    zip.getInputStream(e).use { s -> f.outputStream().use { s.copyTo(it) } }
                    images += f
                }
        }
        if (images.isEmpty()) throw PdfToolException("There are no pictures in this archive.")
        kit.imagesToPdf(images, output)
    }

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    /** Names with numbers inside sort by value, so page 2 comes before page 10. */
    internal object NaturalOrder : Comparator<String> {
        private val part = Regex("\\d+|\\D+")
        override fun compare(a: String, b: String): Int {
            val x = part.findAll(a.lowercase()).map { it.value }.toList()
            val y = part.findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(x.size, y.size)) {
                val p = x[i]
                val q = y[i]
                val c = if (p[0].isDigit() && q[0].isDigit()) {
                    p.toBigInteger().compareTo(q.toBigInteger())
                } else {
                    p.compareTo(q)
                }
                if (c != 0) return c
            }
            return x.size.compareTo(y.size)
        }
    }

    internal fun textChapters(text: String, fallbackTitle: String): List<Chapter> {
        val paragraphs = text.replace("\r\n", "\n").split(Regex("\n\\s*\n")).map {
            it.replace(Regex("\\s*\n\\s*"), " ").trim()
        }
            .filter { it.isNotEmpty() }
        val heading = Regex("^(chapter|part|book)\\s+[\\divxlc]+.{0,60}$", RegexOption.IGNORE_CASE)
        val chapters = mutableListOf<Chapter>()
        var title = fallbackTitle
        var current = mutableListOf<String>()
        for (p in paragraphs) {
            if (heading.matches(p) && (current.isNotEmpty() || chapters.isNotEmpty())) {
                if (current.isNotEmpty()) chapters += Chapter(title, current)
                title = p
                current = mutableListOf()
            } else {
                current += p
            }
        }
        if (current.isNotEmpty()) chapters += Chapter(title, current)
        return chapters
    }

    internal fun htmlToParagraphs(html: String): List<String> {
        val body = Regex("(?is)<body[^>]*>(.*)</body>").find(html)?.groupValues?.get(1) ?: html
        val cleaned = body
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)</(p|div|h[1-6]|li|tr|blockquote)>|<br\\s*/?>"), "\n\n")
            .replace(Regex("(?s)<[^>]+>"), "")
        return decodeEntities(cleaned).split(Regex("\n\\s*\n")).map {
            it.replace(Regex("\\s+"), " ").trim()
        }.filter { it.isNotEmpty() }
    }

    internal fun rtfToParagraphs(rtf: String): List<String> {
        val sb = StringBuilder()
        var depth = 0
        var skipDepth = -1
        var i = 0
        while (i < rtf.length) {
            val c = rtf[i]
            when {
                c == '{' -> {
                    depth++
                    if (rtf.startsWith(
                            "{\\*",
                            i
                        ) || rtf.startsWith("{\\fonttbl", i) || rtf.startsWith("{\\colortbl", i) ||
                        rtf.startsWith("{\\stylesheet", i) ||
                        rtf.startsWith("{\\info", i)
                    ) {
                        if (skipDepth < 0) skipDepth = depth
                    }
                    i++
                }

                c == '}' -> {
                    if (depth == skipDepth) skipDepth = -1
                    depth--
                    i++
                }

                c == '\\' -> {
                    val m = Regex("^\\\\([a-zA-Z]+)(-?\\d+)? ?").find(rtf.substring(i, minOf(i + 40, rtf.length)))
                    if (m != null) {
                        if (skipDepth < 0) {
                            when (m.groupValues[1]) {
                                "par", "line" -> sb.append("\n\n")

                                "tab" -> sb.append(' ')

                                "u" -> m.groupValues[2].toIntOrNull()?.let {
                                    sb.append(
                                        (
                                            if (it <
                                                0
                                            ) {
                                                it + 65536
                                            } else {
                                                it
                                            }
                                            ).toChar()
                                    )
                                }
                            }
                        }
                        i += m.value.length
                        if (m.groupValues[1] == "u" && i < rtf.length && rtf[i] != '\\') i++
                    } else if (i + 1 < rtf.length) {
                        val n = rtf[i + 1]
                        if (n == '\'' && i + 3 < rtf.length) {
                            if (skipDepth <
                                0
                            ) {
                                rtf.substring(i + 2, i + 4).toIntOrNull(16)?.let { sb.append(it.toChar()) }
                            }
                            i += 4
                        } else {
                            if (skipDepth < 0 && (n == '\\' || n == '{' || n == '}')) sb.append(n)
                            i += 2
                        }
                    } else {
                        i++
                    }
                }

                else -> {
                    if (skipDepth < 0 && c != '\r' && c != '\n') sb.append(c)
                    i++
                }
            }
        }
        return sb.toString().split(Regex("\n\\s*\n")).map {
            it.replace(Regex("\\s+"), " ").trim()
        }.filter { it.isNotEmpty() }
    }

    private fun parseFb2(file: File, fallback: String): Triple<String, String, List<Chapter>> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val doc = try {
            factory.newDocumentBuilder().parse(file)
        } catch (e: Exception) {
            throw PdfToolException("This FB2 file is damaged.", e)
        }
        fun first(parent: Node, name: String): Element? =
            (parent as? Element)?.getElementsByTagName(name)?.item(0) as? Element
        val info = first(doc.documentElement, "title-info")
        val title = info?.let { first(it, "book-title")?.textContent?.trim() }.orEmpty().ifEmpty { fallback }
        val author = info?.let { first(it, "author") }?.let { a ->
            listOf("first-name", "middle-name", "last-name").mapNotNull {
                first(a, it)?.textContent?.trim()
            }.filter { it.isNotEmpty() }.joinToString(" ")
        }.orEmpty()
        val chapters = mutableListOf<Chapter>()
        fun walk(section: Element, inherited: String) {
            var name = inherited
            val paras = mutableListOf<String>()
            var child = section.firstChild
            while (child != null) {
                if (child is Element) {
                    when (child.tagName) {
                        "title" -> child.textContent.replace(Regex("\\s+"), " ").trim().takeIf {
                            it.isNotEmpty()
                        }?.let {
                            name =
                                it
                        }

                        "p", "v", "subtitle", "text-author" -> child.textContent.replace(
                            Regex("\\s+"),
                            " "
                        ).trim().takeIf {
                            it.isNotEmpty()
                        }?.let {
                            paras +=
                                it
                        }

                        "epigraph", "cite", "poem" -> child.textContent.replace(Regex("\\s+"), " ").trim().takeIf {
                            it.isNotEmpty()
                        }?.let {
                            paras +=
                                it
                        }
                    }
                }
                child = child.nextSibling
            }
            if (paras.isNotEmpty()) chapters += Chapter(name, paras)
            child = section.firstChild
            while (child != null) {
                if (child is Element && child.tagName == "section") walk(child, name)
                child = child.nextSibling
            }
        }
        val bodies = doc.documentElement.getElementsByTagName("body")
        for (b in 0 until bodies.length) {
            val body = bodies.item(b) as Element
            if (body.getAttribute("name") == "notes") continue
            var n = body.firstChild
            while (n != null) {
                if (n is Element && n.tagName == "section") walk(n, title)
                n = n.nextSibling
            }
        }
        return Triple(title, author, chapters)
    }

    private fun decodeEntities(s: String): String {
        val named = mapOf(
            "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"",
            "&apos;" to "'", "&#39;" to "'", "&mdash;" to "—", "&ndash;" to "–", "&hellip;" to "…"
        )
        var out = s
        named.forEach { (k, v) -> out = out.replace(k, v) }
        out = Regex("&#x([0-9a-fA-F]+);").replace(out) { it.groupValues[1].toInt(16).toChar().toString() }
        return Regex("&#(\\d+);").replace(out) { it.groupValues[1].toInt().toChar().toString() }
    }

    private fun esc(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    internal fun writeEpub(output: File, title: String, author: String, chapters: List<Chapter>) {
        val id = "urn:uuid:" + UUID.randomUUID()
        val items = chapters.filter { it.paragraphs.isNotEmpty() }
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            val mime = "application/epub+zip".toByteArray()
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mime.size.toLong()
                    compressedSize = mime.size.toLong()
                    crc = CRC32().also { it.update(mime) }.value
                }
            )
            zip.write(mime)
            zip.closeEntry()
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
            )
            put(
                "OEBPS/style.css",
                "body{line-height:1.5;margin:0 .5em}h2{margin:1.5em 0 1em;font-size:1.3em}p{margin:0 0 .8em;text-indent:0;text-align:left}"
            )
            val manifest = StringBuilder()
            val spine = StringBuilder()
            val nav = StringBuilder()
            items.forEachIndexed { i, c ->
                val n = "c%04d.xhtml".format(i + 1)
                manifest.append("""<item id="c$i" href="$n" media-type="application/xhtml+xml"/>""")
                spine.append("""<itemref idref="c$i"/>""")
                nav.append("""<li><a href="$n">${esc(c.title)}</a></li>""")
                val body = c.paragraphs.joinToString("") { "<p>${esc(it)}</p>" }
                put(
                    "OEBPS/$n",
                    """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>${esc(
                        c.title
                    )}</title><link rel="stylesheet" type="text/css" href="style.css"/></head><body><h2>${esc(
                        c.title
                    )}</h2>$body</body></html>"""
                )
            }
            put(
                "OEBPS/nav.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol>$nav</ol></nav></body></html>"""
            )
            val creator = if (author.isNotEmpty()) "<dc:creator>${esc(author)}</dc:creator>" else ""
            put(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">$id</dc:identifier><dc:title>${esc(
                    title
                )}</dc:title>$creator<dc:language>en</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="css" href="style.css" media-type="text/css"/>$manifest</manifest><spine>$spine</spine></package>"""
            )
        }
    }
}
