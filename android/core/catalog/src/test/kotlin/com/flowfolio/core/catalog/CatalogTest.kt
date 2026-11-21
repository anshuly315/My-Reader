package com.flowfolio.core.catalog

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {
    private val gutendex = """
        {"count": 2, "next": "https://gutendex.com/books/?page=2&search=austen", "previous": null,
         "results": [
          {"id": 1342, "title": "Pride and Prejudice",
           "authors": [{"name": "Austen, Jane", "birth_year": 1775, "death_year": 1817}],
           "subjects": ["Courtship -- Fiction"], "languages": ["en"], "summaries": ["A classic."],
           "download_count": 9000,
           "formats": {"application/epub+zip": "https://www.gutenberg.org/ebooks/1342.epub3.images",
                       "image/jpeg": "https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg",
                       "text/plain; charset=us-ascii": "https://www.gutenberg.org/ebooks/1342.txt.utf-8"}},
          {"id": 7, "title": "No files", "authors": [], "subjects": [], "languages": ["fr"], "download_count": 1, "formats": {}}
         ]}
    """.trimIndent()

    @Test
    fun gutenbergParsesDetails() {
        var asked = ""
        val page = GutenbergSource({ asked = it; gutendex }).search("jane austen", 1)
        assertTrue(asked.contains("search=jane+austen"))
        assertEquals(2, page.total)
        assertEquals(2, page.nextPage)
        val b = page.books.first()
        assertEquals("Jane Austen", b.authors.single())
        assertEquals("1775–1817", b.authorYears)
        assertEquals("EPUB", b.preferred?.format)
        assertEquals("https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg", b.coverUrl)
        assertEquals("A classic.", b.summary)
        assertNull(page.books[1].preferred)
    }

    @Test
    fun lastPageHasNoNext() {
        val page = GutenbergSource({ """{"count":0,"next":null,"previous":null,"results":[]}""" }).search("", 3)
        assertNull(page.nextPage)
        assertTrue(page.books.isEmpty())
    }

    @Test
    fun archiveSearchAndDetails() {
        val search = """{"response":{"numFound":50,"docs":[{"identifier":"moby00melv","title":"Moby Dick","creator":"Herman Melville","downloads":12,"language":"eng"}]}}"""
        val meta = """{"files":[{"name":"moby.epub"},{"name":"scan files/page.jpg"},{"name":"moby book.pdf"}],"metadata":{"description":"<p>A whale.</p>"}}"""
        val src = ArchiveSource({ if (it.contains("advancedsearch")) search else meta })
        val page = src.search("moby", 1)
        assertEquals(2, page.nextPage)
        val book = page.books.single()
        assertEquals(listOf("Herman Melville"), book.authors)
        assertEquals("https://archive.org/services/img/moby00melv", book.coverUrl)
        val full = src.details(book)
        assertEquals(listOf("EPUB", "PDF"), full.files.map { it.format })
        assertTrue(full.files[1].url.endsWith("moby%20book.pdf"))
        assertEquals("A whale.", full.summary)
    }

    @Test
    fun downloadSavesPreferredFileWithSafeName() {
        val dir = createTempDirectory().toFile()
        val book = CatalogBook("x", "1", "Odd/Name: Book?", emptyList(), files = listOf(BookFile("https://e.org/a.pdf", "PDF"), BookFile("https://e.org/a.epub", "EPUB")))
        val f = download(book, dir) { url -> url.toByteArray() }
        assertEquals("OddName Book.epub", f.name)
        assertEquals("https://e.org/a.epub", f.readText())
        assertNotNull(File(dir, f.name))
    }

    @Test(expected = IllegalArgumentException::class)
    fun plainHttpIsRefused() {
        UrlHttp.get("http://example.com")
    }

    @Test
    fun flipName() {
        assertEquals("Mark Twain", GutenbergSource().flipName("Twain, Mark"))
        assertEquals("Homer", GutenbergSource().flipName("Homer"))
    }
}
