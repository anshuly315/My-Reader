package com.flowfolio.feature.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flowfolio.core.designsystem.ButtonStyle
import com.flowfolio.core.designsystem.FlowFolio
import com.flowfolio.core.designsystem.FlowFolioButton
import com.flowfolio.core.designsystem.FlowFolioType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val PAGE_PX = 1000
private val TEXT_TYPES = setOf("txt", "md", "json", "csv", "html", "xml")
private val PICTURE_TYPES = setOf("png", "jpg", "jpeg", "webp")

/** A tool's result shown before it is saved: PDF pages, a picture or plain text, scrolling. */
@Composable
internal fun ResultPreview(file: File, onClose: () -> Unit) {
    val p = FlowFolio.palette
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(p.bg).padding(12.dp)) {
            Text(file.name, style = FlowFolioType.body.copy(color = p.ink), modifier = Modifier.padding(4.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (file.extension.lowercase()) {
                    "pdf" -> PdfPages(file)

                    in PICTURE_TYPES -> Picture(file)

                    in TEXT_TYPES -> PlainText(file)

                    else -> Text(
                        stringResource(R.string.tools_no_preview),
                        style = FlowFolioType.body.copy(color = p.ink2)
                    )
                }
            }
            FlowFolioButton(
                text = stringResource(R.string.tools_preview_close),
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                style = ButtonStyle.PRIMARY
            )
        }
    }
}

@Composable
private fun PdfPages(file: File) {
    val renderer = remember(file) {
        runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull()
    }
    DisposableEffect(renderer) { onDispose { runCatching { renderer?.close() } } }
    if (renderer == null) {
        Text(stringResource(R.string.tools_no_preview), style = FlowFolioType.body.copy(color = FlowFolio.palette.ink2))
        return
    }
    val lock = remember(renderer) { Mutex() }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items((0 until renderer.pageCount).toList()) { index ->
            val page by produceState<ImageBitmap?>(null, index) {
                value = withContext(Dispatchers.IO) {
                    lock.withLock { runCatching { render(renderer, index) }.getOrNull() }
                }
            }
            val shown = page
            if (shown != null) {
                Image(shown, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            } else {
                Box(Modifier.fillMaxWidth().padding(vertical = 80.dp))
            }
        }
    }
}

private fun render(renderer: PdfRenderer, index: Int): ImageBitmap {
    renderer.openPage(index).use { page ->
        val height = (PAGE_PX.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(PAGE_PX, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap.asImageBitmap()
    }
}

@Composable
private fun Picture(file: File) {
    val image by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }
    }
    image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
}

@Composable
private fun PlainText(file: File) {
    val text by produceState("", file) { value = withContext(Dispatchers.IO) { file.readText().take(200_000) } }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(text, style = FlowFolioType.body.copy(color = FlowFolio.palette.ink))
    }
}
