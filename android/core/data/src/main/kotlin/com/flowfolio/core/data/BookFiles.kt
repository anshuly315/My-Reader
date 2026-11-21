package com.flowfolio.core.data

import com.flowfolio.core.model.BookFormat
import java.io.File

/** App-private locations for copied books and covers. Everything lives under [root] (Context.filesDir in the app). */
class BookFiles(private val root: File) {
    private val books get() = File(root, "books").apply { mkdirs() }
    private val covers get() = File(root, "covers").apply { mkdirs() }
    private val temp get() = File(root, "import-tmp").apply { mkdirs() }

    fun book(id: String, format: BookFormat = BookFormat.PDF) = File(books, "$id.${format.name.lowercase()}")

    fun cover(id: String) = File(covers, "$id.jpg")

    /** A new file for a cover the reader chose; its name changes each time so no old picture stays cached. */
    fun newCover(id: String) = File(covers, "$id-${System.currentTimeMillis()}.jpg")

    /** Every cover kept for [id]. */
    fun deleteCovers(id: String) {
        covers.listFiles { f -> f.name == "$id.jpg" || f.name.startsWith("$id-") }?.forEach { it.delete() }
    }

    fun newTemp(): File = File.createTempFile("import-", ".part", temp)

    /** Free space for new books. A missing directory reports 0 bytes, so make sure it exists first. */
    fun usableBytes(): Long = root.apply { mkdirs() }.usableSpace

    /** Removes leftovers from imports interrupted by a crash or process death. */
    fun clearTemp() {
        temp.listFiles()?.forEach { it.delete() }
    }
}
