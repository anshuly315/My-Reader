@file:Suppress("TooGenericExceptionCaught", "SwallowedException")

package com.flowfolio

import android.net.Uri
import com.flowfolio.core.catalog.UrlHttp
import com.flowfolio.core.data.ImportResult
import com.flowfolio.core.data.LibraryRepository
import com.flowfolio.core.model.BookDetails
import com.flowfolio.core.model.StorageMode
import com.flowfolio.feature.tools.AddOutcome
import com.flowfolio.feature.tools.BookImporter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File

/** Hands a downloaded free book to the library, with the author and details the catalog gave. */
@Module
@InstallIn(SingletonComponent::class)
internal object DiscoverModule {
    @Provides
    fun importer(library: LibraryRepository): BookImporter = BookImporter { file, book ->
        when (val r = library.import(Uri.fromFile(file).toString(), StorageMode.COPY)) {
            is ImportResult.Duplicate -> AddOutcome.ALREADY_THERE

            is ImportResult.Imported -> {
                val details = BookDetails(
                    description = book.summary.ifBlank { null },
                    language = book.languages.firstOrNull(),
                    subjects = book.subjects.take(12),
                    authorBio = book.authorYears.ifBlank { null }
                )
                library.updateDetails(r.book.id, book.title, book.authors.joinToString(", ").ifBlank { null }, details)
                book.coverUrl?.let { url ->
                    try {
                        r.book.coverPath?.let { File(it).writeBytes(UrlHttp.bytes(url)) }
                    } catch (e: Exception) {
                        // The book is in; a missing cover is fine.
                    }
                }
                AddOutcome.ADDED
            }

            else -> AddOutcome.FAILED
        }
    }
}
