package com.flowfolio.core.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/** One cover picture Open Library offers: a small [thumbnail] to look at and the large [image] to keep. */
data class CoverChoice(val label: String, val thumbnail: String, val image: String)

/** Looking for a cover on Open Library and saving the one the reader picks. Never runs by itself. */
internal object CoverSearch {
    private const val BASE = "https://openlibrary.org/search.json"
    private const val TIMEOUT_MS = 10_000
    private const val MAX_IMAGE_BYTES = 6_000_000
    private const val JPEG_QUALITY = 88
    private const val RESULTS = 16

    fun search(title: String, author: String?): List<CoverChoice> {
        val url = buildString {
            append(BASE).append("?limit=").append(RESULTS)
            append("&fields=title,author_name,cover_i,first_publish_year&title=").append(enc(title))
            if (!author.isNullOrBlank()) append("&author=").append(enc(author))
        }
        val docs = JSONObject(text(url)).optJSONArray("docs") ?: return emptyList()
        return (0 until docs.length()).mapNotNull { i ->
            val d = docs.optJSONObject(i) ?: return@mapNotNull null
            val id = d.optLong("cover_i", 0L).takeIf { it > 0 } ?: return@mapNotNull null
            val by = d.optJSONArray("author_name")?.optString(0).orEmpty()
            val year = d.optInt("first_publish_year", 0).takeIf { it > 0 }?.toString().orEmpty()
            CoverChoice(
                label = listOf(d.optString("title"), by, year).filter { it.isNotBlank() }.joinToString(" · "),
                thumbnail = "https://covers.openlibrary.org/b/id/$id-M.jpg",
                image = "https://covers.openlibrary.org/b/id/$id-L.jpg"
            )
        }
    }

    /** Fetches [choice] into [target] as a JPEG; false when the picture is missing or not an image. */
    fun save(choice: CoverChoice, target: File): Boolean {
        val bytes = bytes(choice.image)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        bitmap.recycle()
        return true
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun text(url: String): String = bytes(url).toString(Charsets.UTF_8)

    private fun bytes(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.setRequestProperty("User-Agent", "FlowFolio (Android)")
            check(c.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${c.responseCode}" }
            c.inputStream.use { it.readBytes() }.also { check(it.size <= MAX_IMAGE_BYTES) { "too large" } }
        } finally {
            c.disconnect()
        }
    }
}
