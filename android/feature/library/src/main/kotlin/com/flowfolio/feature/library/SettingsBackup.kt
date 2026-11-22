package com.flowfolio.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.flowfolio.core.designsystem.FlowFolio
import com.flowfolio.core.designsystem.FlowFolioType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backup and library upkeep in Settings → Storage: save the settings to a file (a Drive, Dropbox or Syncthing folder
 * works too, since the file picker lists them), restore them, look for new books in the remembered folders, and fill
 * in the details and authors books are missing.
 */
@Composable
@Suppress("LongMethod")
internal fun BackupRows(actions: SettingsActions) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            scope.launch {
                val text = actions.onExportSettings()
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
                    }
                        .isSuccess
                }
                message = context.getString(if (ok) R.string.backup_saved else R.string.backup_failed)
            }
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) }
                    }.getOrNull()
                }
                val n = text?.let { actions.onImportSettings(it) } ?: -1
                message =
                    if (n <
                        0
                    ) {
                        context.getString(R.string.backup_not_a_backup)
                    } else {
                        context.getString(R.string.backup_restored, n)
                    }
            }
        }
    }
    val pickSync = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch {
                message = context.getString(R.string.sync_running)
                val done = actions.onSyncFolder(uri)
                message = syncMessage(context, done)
            }
        }
    }
    ActionRow(R.string.sync_pick, R.string.sync_pick_detail) { pickSync.launch(null) }
    Divider()
    ActionRow(R.string.sync_now, R.string.sync_now_detail) {
        scope.launch {
            message = context.getString(R.string.sync_running)
            message = syncMessage(context, actions.onSyncFolder(null))
        }
    }
    Divider()
    ActionRow(R.string.backup_save, R.string.backup_save_detail) { save.launch("flowfolio-settings.txt") }
    Divider()
    ActionRow(R.string.backup_restore, R.string.backup_restore_detail) { restore.launch(arrayOf("*/*")) }
    Divider()
    ActionRow(R.string.library_scan, R.string.library_scan_detail) {
        scope.launch {
            message = context.getString(R.string.library_scanning)
            val n = actions.onScanFolders()
            message = context.getString(R.string.library_scanned, n)
        }
    }
    Divider()
    ActionRow(R.string.library_phone, R.string.library_phone_detail) { actions.onOption("phone_scan_asked", "0") }
    Divider()
    ActionRow(R.string.library_fill, R.string.library_fill_detail) {
        scope.launch {
            message = context.getString(R.string.library_filling)
            val n = actions.onFillDetails { done, total ->
                message =
                    context.getString(R.string.library_fill_progress, done, total)
            }
            message = context.getString(R.string.library_filled, n)
        }
    }
    message?.let {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(it, style = FlowFolioType.rowDetail.copy(color = FlowFolio.palette.accent))
        }
    }
    Divider()
}

private fun syncMessage(context: android.content.Context, done: com.flowfolio.core.data.FolderSyncSummary?): String =
    when {
        done == null -> context.getString(R.string.sync_failed)

        else -> context.getString(
            R.string.sync_done,
            done.booksMerged,
            if (done.settingsFetched) 1 else 0,
            done.marksMerged
        )
    }
