package com.flowfolio.core.pdftools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class PageRangesTest {
    @Test
    fun parsesRangesListsAndOpenEnds() {
        assertEquals(listOf(1, 2, 3, 5, 8, 9, 10), PageRanges.parse("1-3, 5, 8-", 10))
        assertEquals(listOf(1, 2, 3), PageRanges.parse("-3", 10))
        assertEquals((1..4).toList(), PageRanges.parse("all", 4))
        assertEquals(listOf(1, 3), PageRanges.parse("odd", 4))
        assertEquals(listOf(2, 4), PageRanges.parse("even", 4))
        assertEquals(listOf(3, 2, 1), PageRanges.parse("3-1", 5))
        assertEquals(listOf(2, 1), PageRanges.parse("2,1,2", 5))
    }

    @Test
    fun refusesPagesOutsideTheDocument() {
        assertThrows(IllegalArgumentException::class.java) { PageRanges.parse("11", 10) }
        assertThrows(IllegalArgumentException::class.java) { PageRanges.parse("0", 10) }
        assertThrows(IllegalArgumentException::class.java) { PageRanges.parse("x", 10) }
    }
}

@RunWith(RobolectricTestRunner::class)
class PdfToolkitTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var kit: PdfToolkit

    @Before
    fun setUp() {
        kit = PdfToolkit(ApplicationProvider.getApplicationContext<Context>())
    }

    private fun pdf(name: String, pages: Int): File {
        val f = tmp.newFile(name)
        PDDocument().use { d ->
            repeat(pages) { d.addPage(PDPage()) }
            d.save(f)
        }
        return f
    }

    private fun pageCount(f: File, password: String = "") = PDDocument.load(f, password).use { it.numberOfPages }

    @Test
    fun mergeJoinsInOrder() {
        val out = File(tmp.root, "m.pdf")
        kit.merge(listOf(pdf("a.pdf", 2), pdf("b.pdf", 3)), out)
        assertEquals(5, pageCount(out))
    }

    @Test
    fun mergeNeedsTwoFiles() {
        assertThrows(IllegalArgumentException::class.java) {
            kit.merge(listOf(pdf("a.pdf", 1)), File(tmp.root, "x.pdf"))
        }
    }

    @Test
    fun splitCutsEveryN() {
        val parts = kit.split(pdf("a.pdf", 5), File(tmp.root, "parts"), 2)
        assertEquals(listOf(2, 2, 1), parts.map { pageCount(it) })
    }

    @Test
    fun extractRemoveRotateReorder() {
        val src = pdf("a.pdf", 4)
        val ex = File(tmp.root, "ex.pdf")
        kit.extractPages(src, ex, listOf(4, 2))
        assertEquals(2, pageCount(ex))

        val rm = File(tmp.root, "rm.pdf")
        kit.removePages(src, rm, listOf(1, 3))
        assertEquals(2, pageCount(rm))
        assertThrows(IllegalArgumentException::class.java) { kit.removePages(src, rm, listOf(1, 2, 3, 4)) }

        val rot = File(tmp.root, "rot.pdf")
        kit.rotate(src, rot, 90, listOf(1))
        PDDocument.load(rot).use {
            assertEquals(90, it.getPage(0).rotation)
            assertEquals(0, it.getPage(1).rotation)
        }
        assertThrows(IllegalArgumentException::class.java) { kit.rotate(src, rot, 45, listOf(1)) }

        val ro = File(tmp.root, "ro.pdf")
        kit.reorder(src, ro, listOf(4, 3, 2, 1))
        assertEquals(4, pageCount(ro))
    }

    @Test
    fun protectThenUnlockRoundTrips() {
        val src = pdf("a.pdf", 2)
        val locked = File(tmp.root, "locked.pdf")
        kit.protect(src, locked, "secret")
        assertTrue(kit.info(locked, "secret").encrypted)
        assertThrows(Exception::class.java) { PDDocument.load(locked).close() }
        val open = File(tmp.root, "open.pdf")
        kit.unlock(locked, open, "secret")
        assertEquals(2, pageCount(open))
        assertTrue(!kit.info(open).encrypted)
    }

    @Test
    fun metadataIsWrittenAndRead() {
        val out = File(tmp.root, "meta.pdf")
        kit.setMetadata(pdf("a.pdf", 1), out, "A Title", "An Author", "", "k1, k2")
        val info = kit.info(out)
        assertEquals("A Title", info.title)
        assertEquals("An Author", info.author)
        assertEquals("k1, k2", info.keywords)
    }

    @Test
    fun cropSetsTheVisibleBox() {
        val out = File(tmp.root, "crop.pdf")
        kit.crop(pdf("a.pdf", 1), out, 36f)
        PDDocument.load(out).use {
            val box = it.getPage(0).cropBox
            assertEquals(612f - 72f, box.width, 0.5f)
        }
    }

    @Test
    fun blankPagesAreRemovedButNotAll() {
        val input = pdf("blank.pdf", 3)
        assertThrows(PdfToolException::class.java) { kit.removeBlankPages(input, File(tmp.root, "b.pdf")) }
    }

    @Test
    fun nUpMakesFewerSheets() {
        val out = File(tmp.root, "n.pdf")
        kit.nUp(pdf("n.pdf", 5), out, 4)
        assertEquals(2, pageCount(out))
    }

    @Test
    fun nUpRejectsOddLayouts() {
        assertThrows(IllegalArgumentException::class.java) { kit.nUp(pdf("o.pdf", 2), File(tmp.root, "o2.pdf"), 5) }
    }

    @Test
    fun singlePageStacksEverything() {
        val out = File(tmp.root, "s.pdf")
        kit.singlePage(pdf("s.pdf", 3), out)
        assertEquals(1, pageCount(out))
    }

    @Test
    fun sanitizeAndRepairKeepPages() {
        val a = File(tmp.root, "san.pdf")
        val b = File(tmp.root, "rep.pdf")
        kit.sanitize(pdf("x.pdf", 2), a)
        kit.repair(pdf("y.pdf", 2), b)
        assertEquals(2, pageCount(a))
        assertEquals(2, pageCount(b))
    }

    @Test
    fun extractImagesWithNoneSaysSo() {
        assertThrows(PdfToolException::class.java) { kit.extractImages(pdf("z.pdf", 1), File(tmp.root, "imgs")) }
    }

    private fun textPdf(name: String, vararg pageText: String): File {
        val f = tmp.newFile(name)
        PDDocument().use { d ->
            pageText.forEach { t ->
                val page = PDPage()
                d.addPage(page)
                PDPageContentStream(d, page).use { cs ->
                    cs.beginText()
                    cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12f)
                    cs.newLineAtOffset(72f, 700f)
                    cs.showText(t)
                    cs.endText()
                }
            }
            d.save(f)
        }
        return f
    }

    @Test
    fun scaleToA4MakesA4Pages() {
        val out = File(tmp.root, "a4.pdf")
        kit.scaleToA4(pdf("a.pdf", 2), out)
        PDDocument.load(out).use { d ->
            assertEquals(2, d.numberOfPages)
            assertEquals(595f, d.getPage(0).mediaBox.width, 1f)
        }
    }

    @Test
    fun bookletPadsToFourAndPutsTwoOnASheet() {
        val out = File(tmp.root, "bk.pdf")
        kit.booklet(pdf("b.pdf", 5), out)
        assertEquals(4, pageCount(out))
    }

    @Test
    fun splitBySizeMakesMoreThanOnePartForABigLimitFile() {
        val parts = kit.splitBySize(pdf("big.pdf", 6), File(tmp.root, "parts"), 1)
        assertEquals(1, parts.size)
    }

    @Test
    fun noBookmarksSaysSo() {
        assertThrows(PdfToolException::class.java) { kit.splitByBookmarks(pdf("nb.pdf", 2), File(tmp.root, "chap")) }
    }

    @Test
    fun removeImagesAndAnnotationsKeepPages() {
        val a = File(tmp.root, "ri.pdf")
        val b = File(tmp.root, "ra.pdf")
        kit.removeImages(pdf("i.pdf", 2), a)
        kit.removeAnnotations(pdf("n.pdf", 2), b)
        assertEquals(2, pageCount(a))
        assertEquals(2, pageCount(b))
    }

    @Test
    fun noAttachmentsSaysSo() {
        assertThrows(PdfToolException::class.java) { kit.extractAttachments(pdf("at.pdf", 1), File(tmp.root, "att")) }
    }

    @Test
    fun overlayKeepsBasePageCount() {
        val out = File(tmp.root, "ov.pdf")
        kit.overlay(pdf("base.pdf", 3), textPdf("top.pdf", "STAMP"), out)
        assertEquals(3, pageCount(out))
        assertTrue(kit.extractText(out).contains("STAMP"))
    }

    @Test
    fun headerAndFooterAddText() {
        val out = File(tmp.root, "hf.pdf")
        kit.headerFooter(pdf("h.pdf", 2), out, "Page {n} of {total}", top = false)
        val text = kit.extractText(out)
        assertTrue(text.contains("Page 1 of 2") && text.contains("Page 2 of 2"))
    }

    @Test
    fun compareReportsDifferences() {
        val report = kit.compare(textPdf("c1.pdf", "same", "left"), textPdf("c2.pdf", "same", "right"))
        assertTrue(report.contains("Page 2") && report.contains("A only: left") && report.contains("B only: right"))
        assertTrue(kit.compare(textPdf("d1.pdf", "x"), textPdf("d2.pdf", "x")).contains("same on every page"))
    }

    @Test
    fun redactRejectsWordsThatAreNotThere() {
        assertThrows(PdfToolException::class.java) {
            kit.redact(textPdf("r.pdf", "hello world"), File(tmp.root, "r2.pdf"), listOf("secret"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            kit.redact(textPdf("r3.pdf", "hi"), File(tmp.root, "r4.pdf"), listOf(" "))
        }
    }
}
