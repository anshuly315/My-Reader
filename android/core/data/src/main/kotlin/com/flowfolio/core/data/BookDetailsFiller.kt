package com.flowfolio.core.data

import com.flowfolio.core.model.Book
import com.flowfolio.core.model.BookDetails
import com.flowfolio.core.model.LibrarySort
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Looks up every book that has no description or author, one after another, and fills in only what is missing: a
 * detail the reader typed is never replaced. The reader starts it with a button, so it counts as asking (ADR-0017).
 */
class BookDetailsFiller @Inject constructor(private val library: LibraryRepository, private val lookup: BookLookup) {
    /** Returns how many books gained something; [onProgress] gets (done, total). */
    suspend fun fill(onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val todo = library.observeBooks(LibrarySort.TITLE).first().filter { it.lacksDetails() }
        var changed = 0
        todo.forEachIndexed { i, book ->
            onProgress(i, todo.size)
            val found = runCatching { lookup.find(book) }.getOrNull()
            if (found != null && !found.isEmpty) {
                val merged = merge(book, found)
                if (merged != null) {
                    library.updateDetails(book.id, book.title, merged.first, merged.second)
                    changed++
                }
            }
        }
        onProgress(todo.size, todo.size)
        return changed
    }

    private fun Book.lacksDetails() = author.isNullOrBlank() || details.description.isNullOrBlank()

    internal fun merge(book: Book, found: Found): Pair<String?, BookDetails>? {
        val d = book.details
        val f = found.details
        val author = book.author?.takeIf { it.isNotBlank() } ?: found.author
        val details = d.copy(
            subtitle = d.subtitle ?: f.subtitle,
            publisher = d.publisher ?: f.publisher,
            isbn = d.isbn ?: f.isbn,
            description = d.description ?: f.description,
            language = d.language ?: f.language,
            released = d.released ?: f.released,
            subjects = d.subjects.ifEmpty { f.subjects },
            printPages = d.printPages ?: f.printPages,
            series = d.series ?: f.series,
            rating = d.rating ?: f.rating,
            ratingsCount = d.ratingsCount ?: f.ratingsCount
        )
        return if (author == book.author && details == d) null else author to details
    }
}
