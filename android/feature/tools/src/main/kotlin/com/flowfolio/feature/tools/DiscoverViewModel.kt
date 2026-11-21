@file:Suppress("TooGenericExceptionCaught", "SwallowedException")

package com.flowfolio.feature.tools

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowfolio.core.catalog.CatalogBook
import com.flowfolio.core.catalog.CatalogSource
import com.flowfolio.core.catalog.defaultSources
import com.flowfolio.core.catalog.download
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What happened to a book handed to the library. */
enum class AddOutcome { ADDED, ALREADY_THERE, FAILED }

/** Puts a downloaded book file in the library with the details the catalog gave. Implemented by the app. */
fun interface BookImporter {
    suspend fun import(file: File, book: CatalogBook): AddOutcome
}

data class DiscoverState(
    val sourceIndex: Int = 0,
    val query: String = "",
    val books: List<CatalogBook> = emptyList(),
    val loading: Boolean = false,
    val nextPage: Int? = null,
    val message: String? = null,
    val busy: Set<String> = emptySet(),
    val added: Set<String> = emptySet()
)

/** The free-books section: search a public-domain catalog and add a book to the library in one tap. */
@HiltViewModel
class DiscoverViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importer: BookImporter
) : ViewModel() {
    val sources: List<CatalogSource> = defaultSources()
    private val _state = MutableStateFlow(DiscoverState())
    val state: StateFlow<DiscoverState> = _state.asStateFlow()

    init {
        search()
    }

    fun pick(index: Int) {
        _state.update { it.copy(sourceIndex = index, books = emptyList(), nextPage = null) }
        search()
    }

    fun query(text: String) = _state.update { it.copy(query = text) }

    fun search() = load(1)

    fun more() {
        _state.value.nextPage?.let { load(it) }
    }

    private fun load(page: Int) {
        val s = _state.value
        if (s.loading) return
        _state.update { it.copy(loading = true, message = null, books = if (page == 1) emptyList() else it.books) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { sources[s.sourceIndex].search(s.query, page) }
                _state.update {
                    it.copy(
                        loading = false,
                        books = if (page == 1) result.books else it.books + result.books,
                        nextPage = result.nextPage,
                        message = if (result.books.isEmpty() &&
                            page == 1
                        ) {
                            context.getString(R.string.discover_none)
                        } else {
                            null
                        }
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, message = context.getString(R.string.discover_offline)) }
            }
        }
    }

    fun add(book: CatalogBook) {
        val key = book.source + book.id
        if (key in _state.value.busy || key in _state.value.added) return
        _state.update { it.copy(busy = it.busy + key, message = null) }
        viewModelScope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) {
                    val source = sources.first { it.name == book.source }
                    val full = if (book.files.isEmpty()) source.details(book) else book
                    val file = download(full, File(context.cacheDir, "discover"))
                    try {
                        importer.import(file, full)
                    } finally {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                AddOutcome.FAILED
            }
            _state.update {
                it.copy(
                    busy = it.busy - key,
                    added = if (outcome == AddOutcome.FAILED) it.added else it.added + key,
                    message = when (outcome) {
                        AddOutcome.FAILED -> context.getString(R.string.discover_failed, book.title)
                        AddOutcome.ALREADY_THERE -> context.getString(R.string.discover_already, book.title)
                        AddOutcome.ADDED -> context.getString(R.string.discover_added, book.title)
                    }
                )
            }
        }
    }
}
