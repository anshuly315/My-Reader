package com.flowfolio.feature.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.flowfolio.core.data.CoverChoice
import com.flowfolio.core.designsystem.FlowFolio
import com.flowfolio.core.designsystem.FlowFolioAlertDialog
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Find a cover": looks on Open Library only now that the reader asked, and saves the one picture they tap. */
@Composable
internal fun FindCoverDialog(
    title: String,
    search: suspend () -> List<CoverChoice>,
    choose: suspend (CoverChoice) -> Boolean,
    onDismiss: () -> Unit
) {
    var choices by remember { mutableStateOf<List<CoverChoice>?>(null) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { choices = search() }
    FlowFolioAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cover_find_title, title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val list = choices
                when {
                    list == null -> Text(stringResource(R.string.cover_searching))

                    list.isEmpty() -> Text(stringResource(R.string.cover_none))

                    else -> {
                        Text(stringResource(R.string.cover_pick))
                        LazyVerticalGrid(
                            GridCells.Fixed(3),
                            Modifier.heightIn(max = 360.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(list) { choice ->
                                RemoteImage(
                                    choice.thumbnail,
                                    Modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).clickable(!saving) {
                                        saving = true
                                        failed = false
                                        scope.launch {
                                            if (choose(choice)) onDismiss() else failed = true
                                            saving = false
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                if (saving) Text(stringResource(R.string.cover_saving))
                if (failed) Text(stringResource(R.string.cover_failed))
            }
        },
        confirmButton = { DialogButton(R.string.action_cancel, onDismiss) }
    )
}

@Composable
private fun RemoteImage(url: String, modifier: Modifier) {
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        image = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = URL(url).openStream().use { it.readBytes() }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val shown = image
    if (shown != null) {
        Image(shown, null, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(FlowFolio.palette.line))
    }
}
