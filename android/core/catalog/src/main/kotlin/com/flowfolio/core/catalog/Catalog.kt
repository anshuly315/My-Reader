package com.flowfolio.core.catalog

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A file a book can be downloaded as. */
data class BookFile(val url: String, val format: String)

/** A free book found in an online catalog, with whatever details the catalog gives. */
data class CatalogBook(
    val source: String,
    val id: String,
    val title: String,
    val authors: List<String>,
    val authorYears: String = "",
    val languages: List<String> = emptyList(),
    val subjects: List<String> = emptyList(),
    val summary: String = "",
    val coverUrl: String? = null,
    val pageUrl: String? = null,
    val downloads: Int = 0,
    val files: List<BookFile> = emptyList()
) {
    /** The best file for the reader: EPUB first, then PDF. */
    val preferred: BookFile? get() = files.firstOrNull { it.format == "EPUB" }
        ?: files.firstOrNull { it.format == "PDF" }
}

/** One page of search results. */
data class CatalogPage(val books: List<CatalogBook>, val total: Int, val nextPage: Int?)

/** Something that returns text from a web address; replaced in tests. */
fun interface Http {
    fun get(url: String): String
}

/** Plain `HttpURLConnection`, https only. */
object UrlHttp : Http {
    override fun get(url: String): String {
        require(url.startsWith("https://")) { "Only https addresses are used." }
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT
        c.readTimeout = TIMEOUT
        c.setRequestProperty("User-Agent", "FlowFolioReader/1.0 (free book catalogs)")
        try {
            check(c.responseCode in 200..299) { "The catalog answered ${c.responseCode}." }
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** The raw bytes of a file, for downloads. */
    fun bytes(url: String): ByteArray {
        require(url.startsWith("https://")) { "Only https addresses are used." }
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT
        c.readTimeout = TIMEOUT * 3
        c.setRequestProperty("User-Agent", "FlowFolioReader/1.0 (free book catalogs)")
        try {
            check(c.responseCode in 200..299) { "The download answered ${c.responseCode}." }
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private const val TIMEOUT = 20_000
}

/** A website with free, public-domain books that can be searched. */
interface CatalogSource {
    val name: String
    val about: String

    fun search(query: String, page: Int = 1): CatalogPage

    /** Fills in the download links of a book found by [search] when the catalog needs a second request. */
    fun details(book: CatalogBook): CatalogBook = book
}

internal val json = Json { ignoreUnknownKeys = true }

internal fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

internal fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun JsonObject.list(key: String): List<String> = (this[key] as? JsonArray)?.mapNotNull {
    (it as? JsonPrimitive)?.contentOrNull
}.orEmpty()

/** Project Gutenberg, searched through the Gutendex service. About 70,000 books, most as EPUB. */
class GutenbergSource(private val http: Http = UrlHttp, private val base: String = "https://gutendex.com") :
    CatalogSource {
    override val name = "Project Gutenberg"
    override val about = "More than 70,000 public-domain books, with author details."

    override fun search(query: String, page: Int): CatalogPage {
        val q = if (query.isBlank()) "" else "&search=${enc(query.trim())}"
        val root = json.parseToJsonElement(http.get("$base/books/?page=$page$q")).jsonObject
        val books = root["results"]?.jsonArray.orEmpty().map { parse(it.jsonObject) }
        val total = (root["count"] as? JsonPrimitive)?.intOrNull ?: books.size
        val next = if (root["next"] is JsonPrimitive &&
            root["next"]?.jsonPrimitive?.contentOrNull != null
        ) {
            page + 1
        } else {
            null
        }
        return CatalogPage(books, total, next)
    }

    internal fun parse(o: JsonObject): CatalogBook {
        val id = o["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val authors = o["authors"]?.jsonArray.orEmpty().map { it.jsonObject }
        val formats = o["formats"]?.jsonObject ?: JsonObject(emptyMap())
        val files = buildList {
            formats.forEach { (mime, url) ->
                val u = url.jsonPrimitive.contentOrNull ?: return@forEach
                when {
                    mime.startsWith("application/epub") && !u.endsWith(".zip") -> add(BookFile(u, "EPUB"))
                    mime.startsWith("application/pdf") -> add(BookFile(u, "PDF"))
                    mime.startsWith("text/plain") -> add(BookFile(u, "TXT"))
                }
            }
        }
        return CatalogBook(
            source = name,
            id = id,
            title = o.text("title"),
            authors = authors.map { flipName(it.text("name")) },
            authorYears = authors.firstOrNull()?.let { years(it) }.orEmpty(),
            languages = o.list("languages"),
            subjects = o.list("subjects"),
            summary = o.list("summaries").firstOrNull().orEmpty(),
            coverUrl = formats["image/jpeg"]?.jsonPrimitive?.contentOrNull,
            pageUrl = "https://www.gutenberg.org/ebooks/$id",
            downloads = (o["download_count"] as? JsonPrimitive)?.intOrNull ?: 0,
            files = files
        )
    }

    private fun years(a: JsonObject): String {
        val born = (a["birth_year"] as? JsonPrimitive)?.intOrNull
        val died = (a["death_year"] as? JsonPrimitive)?.intOrNull
        return if (born == null && died == null) "" else "${born ?: "?"}–${died ?: "?"}"
    }

    /** Gutenberg lists "Austen, Jane"; readers expect "Jane Austen". */
    internal fun flipName(name: String): String {
        val parts = name.split(",", limit = 2).map { it.trim() }
        return if (parts.size == 2 && parts[1].isNotEmpty()) "${parts[1]} ${parts[0]}" else name
    }
}

/** The Internet Archive's texts, filtered to items that are free to borrow-less download. */
class ArchiveSource(private val http: Http = UrlHttp) : CatalogSource {
    override val name = "Internet Archive"
    override val about = "Millions of scanned books and texts, many in the public domain."

    override fun search(query: String, page: Int): CatalogPage {
        val subject = if (query.isBlank()) "" else "(${query.trim()}) AND "
        val q = enc("${subject}mediatype:texts AND NOT access-restricted-item:true AND (format:EPUB OR format:PDF)")
        val url = "https://archive.org/advancedsearch.php?q=$q&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=year" +
            "&fl[]=downloads&fl[]=language&sort[]=downloads+desc&rows=$ROWS&page=$page&output=json"
        val response = json.parseToJsonElement(http.get(url)).jsonObject["response"]?.jsonObject
            ?: return CatalogPage(emptyList(), 0, null)
        val docs = response["docs"]?.jsonArray.orEmpty().map { it.jsonObject }
        val total = (response["numFound"] as? JsonPrimitive)?.intOrNull ?: docs.size
        val books = docs.map { d ->
            val id = d.text("identifier")
            CatalogBook(
                source = name,
                id = id,
                title = d.text("title").ifEmpty { id },
                authors = listOf(d["creator"]).filterNotNull().flatMap {
                    if (it is JsonArray) {
                        it.mapNotNull { x ->
                            (x as? JsonPrimitive)?.contentOrNull
                        }
                    } else {
                        listOf(it.jsonPrimitive.content)
                    }
                },
                languages = listOf(d.text("language")).filter { it.isNotEmpty() },
                coverUrl = "https://archive.org/services/img/$id",
                pageUrl = "https://archive.org/details/$id",
                downloads = (d["downloads"] as? JsonPrimitive)?.intOrNull ?: 0
            )
        }
        return CatalogPage(books, total, if (page * ROWS < total) page + 1 else null)
    }

    /** The file list lives in a separate record. */
    override fun details(book: CatalogBook): CatalogBook {
        val meta = json.parseToJsonElement(http.get("https://archive.org/metadata/${enc(book.id)}")).jsonObject
        val files = meta["files"]?.jsonArray.orEmpty().map { it.jsonObject }.mapNotNull { f ->
            val file = f.text("name")
            val fmt = when {
                file.endsWith(".epub", true) -> "EPUB"
                file.endsWith(".pdf", true) -> "PDF"
                else -> return@mapNotNull null
            }
            BookFile(
                "https://archive.org/download/${enc(book.id)}/${file.split('/').joinToString("/") {
                    enc(it).replace("+", "%20")
                }}",
                fmt
            )
        }
        val desc = meta["metadata"]?.jsonObject?.let { it.text("description") }.orEmpty()
        return book.copy(
            files = files,
            summary = desc.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(MAX_SUMMARY)
        )
    }

    private companion object {
        const val ROWS = 24
        const val MAX_SUMMARY = 600
    }
}

/** Downloads a book's file into [dir]; returns the saved file. */
fun download(book: CatalogBook, dir: File, fetch: (String) -> ByteArray = UrlHttp::bytes): File {
    val file = book.preferred ?: error("This book has no EPUB or PDF to download.")
    require(file.url.startsWith("https://")) { "Only https addresses are used." }
    dir.mkdirs()
    val safe =
        (book.title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().take(80).ifEmpty { book.id }) + "." +
            file.format.lowercase()
    return File(dir, safe).also { it.writeBytes(fetch(file.url)) }
}

/** The catalogs the app offers. */
fun defaultSources(): List<CatalogSource> = listOf(GutenbergSource(), ArchiveSource())
