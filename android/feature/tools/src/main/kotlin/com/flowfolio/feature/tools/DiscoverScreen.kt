@file:Suppress("LongMethod", "MagicNumber", "TooGenericExceptionCaught", "SwallowedException")

package com.flowfolio.feature.tools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowfolio.core.catalog.CatalogBook
import com.flowfolio.core.catalog.UrlHttp
import com.flowfolio.core.designsystem.ButtonStyle
import com.flowfolio.core.designsystem.FlowFolio
import com.flowfolio.core.designsystem.FlowFolioButton
import com.flowfolio.core.designsystem.FlowFolioCard
import com.flowfolio.core.designsystem.FlowFolioChip
import com.flowfolio.core.designsystem.FlowFolioType
import com.flowfolio.core.designsystem.LocalChromePadding
import com.flowfolio.core.designsystem.chromeStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Free, public-domain books: pick a catalog, search, and add a book (with its author and details) to the library. */
@Composable
fun DiscoverScreen(onBack: () -> Unit, viewModel: DiscoverViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    val p = FlowFolio.palette
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val chrome = LocalChromePadding.current
    Box(Modifier.fillMaxSize().chromeStart()) {
        Column(Modifier.fillMaxSize().padding(top = insets.calculateTopPadding()).padding(horizontal = 18.dp)) {
            ToolsHeader(
                stringResource(R.string.discover_title),
                stringResource(R.string.discover_sub),
                stringResource(R.string.tools_back),
                onBack
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                viewModel.sources.forEachIndexed { i, s ->
                    FlowFolioChip(s.name, state.sourceIndex == i, { viewModel.pick(i) })
                }
            }
            Text(
                viewModel.sources[state.sourceIndex].about,
                style = FlowFolioType.rowDetail.copy(color = p.ink2),
                modifier = Modifier.padding(top = 8.dp)
            )
            ToolField(stringResource(R.string.discover_search), state.query, viewModel::query)
            FlowFolioButton(
                stringResource(R.string.discover_go),
                viewModel::search,
                style = ButtonStyle.PRIMARY,
                modifier = Modifier.fillMaxWidth()
            )
            state.message?.let {
                Text(
                    it,
                    style = FlowFolioType.rowDetail.copy(color = p.accent),
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 14.dp, bottom = chrome.calculateBottomPadding() + 24.dp)
            ) {
                items(state.books, key = { it.source + it.id }) { b ->
                    val key = b.source + b.id
                    BookRow(b, busy = key in state.busy, added = key in state.added) { viewModel.add(b) }
                }
                if (state.loading) {
                    item {
                        Text(
                            stringResource(R.string.discover_loading),
                            style = FlowFolioType.rowDetail.copy(color = p.ink2)
                        )
                    }
                } else if (state.nextPage != null) {
                    item {
                        FlowFolioButton(
                            stringResource(R.string.discover_more),
                            viewModel::more,
                            style = ButtonStyle.SOFT,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BookRow(book: CatalogBook, busy: Boolean, added: Boolean, onAdd: () -> Unit) {
    val p = FlowFolio.palette
    FlowFolioCard(Modifier.fillMaxWidth(), padding = PaddingValues(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RemoteCover(book.coverUrl, Modifier.width(72.dp).height(104.dp).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.weight(1f)) {
                Text(book.title, style = FlowFolioType.rowLabel.copy(color = p.ink), maxLines = 3)
                val by = book.authors.joinToString(", ").ifEmpty { stringResource(R.string.discover_unknown_author) }
                Text(
                    by + if (book.authorYears.isNotEmpty()) "  (${book.authorYears})" else "",
                    style = FlowFolioType.rowDetail.copy(color = p.ink2),
                    maxLines = 2
                )
                val facts = listOfNotNull(
                    book.languages.firstOrNull()?.uppercase(),
                    book.preferred?.format,
                    book.downloads.takeIf { it > 0 }?.let { stringResource(R.string.discover_downloads, it) }
                ).joinToString("  ·  ")
                if (facts.isNotEmpty()) Text(facts, style = FlowFolioType.rowDetail.copy(color = p.ink3))
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                FlowFolioButton(
                    stringResource(
                        when {
                            added -> R.string.discover_in_library
                            busy -> R.string.discover_adding
                            else -> R.string.discover_add
                        }
                    ),
                    onAdd,
                    style = if (added) ButtonStyle.SOFT else ButtonStyle.PRIMARY
                )
            }
        }
    }
}

private val covers = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 60
}

/** A cover from a web address, shown when it arrives; a quiet block stands in until then. */
@Composable
private fun RemoteCover(url: String?, modifier: Modifier) {
    val p = FlowFolio.palette
    var image by remember(url) { mutableStateOf(url?.let { synchronized(covers) { covers[it] } }) }
    LaunchedEffect(url) {
        if (url == null || image != null) return@LaunchedEffect
        image = withContext(Dispatchers.IO) {
            try {
                val bytes = UrlHttp.bytes(url)
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
                    ?.also { synchronized(covers) { covers[url] = it } }
            } catch (e: Exception) {
                null
            }
        }
    }
    val shown = image
    if (shown != null) {
        Image(shown, null, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(p.line))
    }
}
