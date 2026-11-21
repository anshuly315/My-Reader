@file:Suppress("MagicNumber", "MaxLineLength")

package com.flowfolio.core.pdftools

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookConverterTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun names(f: File) = ZipFile(f).use { z -> z.entries().asSequence().map { it.name }.toList() }

    @Test fun textSplitsChaptersAndParagraphs() {
        val ch = BookConverter.textChapters(
            "Intro line\nwrapped\n\nChapter 1 The start\n\nHello.\n\nChapter 2\n\nBye.",
            "Book"
        )
        assertEquals(listOf("Book", "Chapter 1 The start", "Chapter 2"), ch.map { it.title })
        assertEquals("Intro line wrapped", ch[0].paragraphs[0])
    }

    @Test fun htmlDropsTagsScriptsAndDecodesEntities() {
        val p = BookConverter.htmlToParagraphs(
            "<html><body><script>x()</script><h1>Hi &amp; bye</h1><p>One<br>two &#65;</p></body></html>"
        )
        assertEquals(listOf("Hi & bye", "One", "two A"), p)
    }

    @Test fun rtfKeepsTextAndSkipsControlGroups() {
        val p = BookConverter.rtfToParagraphs(
            "{\\rtf1\\ansi{\\fonttbl{\\f0 Arial;}}\\pard Hello \\b world\\b0\\par Second \\'e9\\par}"
        )
        assertEquals("Hello world", p[0])
        assertEquals("Second é", p[1])
    }

    @Test fun naturalOrderSortsNumbers() {
        val s = listOf("p10.jpg", "p2.jpg", "p1.jpg").sortedWith(BookConverter.NaturalOrder)
        assertEquals(listOf("p1.jpg", "p2.jpg", "p10.jpg"), s)
    }

    @Test fun fb2BecomesEpub() {
        val fb2 = tmp.newFile("book.fb2")
        fb2.writeText(
            """<?xml version="1.0" encoding="utf-8"?><FictionBook><description><title-info><author><first-name>Ann</first-name><last-name>Lee</last-name></author><book-title>My Tale</book-title></title-info></description>
            <body><section><title><p>One</p></title><p>First para.</p></section><section><title><p>Two</p></title><p>Second para.</p></section></body></FictionBook>"""
        )
        val out = tmp.newFile("out.epub")
        BookConverter.toEpub(fb2, out)
        val n = names(out)
        assertEquals("mimetype", n.first())
        assertTrue("OEBPS/c0001.xhtml" in n && "OEBPS/c0002.xhtml" in n)
        val opf = ZipFile(out).use { z ->
            z.getInputStream(z.getEntry("OEBPS/content.opf")).readBytes().decodeToString()
        }
        assertTrue(opf.contains("My Tale") && opf.contains("Ann Lee"))
    }

    @Test(expected = PdfToolException::class)
    fun emptyTextIsRejected() {
        val f = tmp.newFile("e.txt")
        f.writeText("   ")
        BookConverter.toEpub(f, tmp.newFile("o.epub"))
    }

    @Test fun supportedTypes() {
        assertTrue(BookConverter.isSupported(File("a.FB2")) && BookConverter.isSupported(File("a.cbz")))
        assertTrue(!BookConverter.isSupported(File("a.mobi")))
    }
}
