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
import com.flowfolio.core.model.Options
import com.flowfolio.core.model.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A one-line note under a group's rows: what the last action did. */
@Composable
private fun Note(message: String?) {
    message?.let {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(it, style = FlowFolioType.rowDetail.copy(color = FlowFolio.palette.accent))
        }
    }
}

/**
 * Settings → Backup and sync: a folder kept in step with this phone (when it last synced, Sync now, and syncing each
 * time the app opens), then saving the settings to a file and restoring them.
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun SyncBackupGroups(settings: UserSettings, actions: SettingsActions) {
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
                    }.isSuccess
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
                message = if (n < 0) {
                    context.getString(R.string.backup_not_a_backup)
                } else {
                    context.getString(R.string.backup_restored, n)
                }
            }
        }
    }
    var syncNote by remember { mutableStateOf<String?>(null) }
    val pickSync = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch {
                syncNote = context.getString(R.string.sync_running)
                syncNote = syncMessage(context, actions.onSyncFolder(uri))
            }
        }
    }
    val o = settings.options
    val folder = o[Options.SYNC_FOLDER]
    Group(R.string.sync_group, 1) {
        StatusLine(
            when {
                folder == null -> stringResource(R.string.sync_status_none)

                else -> stringResource(
                    R.string.sync_status,
                    folderName(folder),
                    lastSynced(o[Options.SYNC_LAST]?.toLongOrNull() ?: 0L)
                )
            }
        )
        Divider()
        ActionRow(
            if (folder == null) R.string.sync_pick else R.string.sync_change,
            R.string.sync_pick_detail
        ) { pickSync.launch(null) }
        if (folder != null) {
            Divider()
            ActionRow(R.string.sync_now, R.string.sync_now_detail) {
                scope.launch {
                    syncNote = context.getString(R.string.sync_running)
                    syncNote = syncMessage(context, actions.onSyncFolder(null))
                }
            }
            Divider()
            SwitchRow(R.string.sync_on_open, R.string.sync_on_open_detail, o[Options.SYNC_ON_OPEN] == "1", {
                actions.onOption(Options.SYNC_ON_OPEN, if (it) "1" else "0")
            })
        }
        Note(syncNote)
    }
    Group(R.string.backup_group, 2) {
        ActionRow(R.string.backup_save, R.string.backup_save_detail) { save.launch("flowfolio-settings.txt") }
        Divider()
        ActionRow(R.string.backup_restore, R.string.backup_restore_detail) { restore.launch(arrayOf("*/*")) }
        Note(message)
    }
}

/** Library → Finding books: scan the remembered folders, the whole phone, and fill in missing details. */
@Composable
internal fun LibraryUpkeepGroup(actions: SettingsActions, order: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    Group(R.string.library_upkeep_group, order) {
        ActionRow(R.string.library_scan, R.string.library_scan_detail) {
            scope.launch {
                message = context.getString(R.string.library_scanning)
                message = context.getString(R.string.library_scanned, actions.onScanFolders())
            }
        }
        Divider()
        ActionRow(R.string.library_phone, R.string.library_phone_detail) { actions.onOption("phone_scan_asked", "0") }
        Divider()
        ActionRow(R.string.library_fill, R.string.library_fill_detail) {
            scope.launch {
                message = context.getString(R.string.library_filling)
                val n = actions.onFillDetails { done, total ->
                    message = context.getString(R.string.library_fill_progress, done, total)
                }
                message = context.getString(R.string.library_filled, n)
            }
        }
        Note(message)
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text,
        style = FlowFolioType.rowDetail.copy(color = FlowFolio.palette.ink2),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
    )
}

/** The folder's own name from its tree address ("primary:Sync/Books" reads "Books"). */
private fun folderName(tree: String): String =
    android.net.Uri.decode(tree).substringAfterLast(':').substringAfterLast('/').ifBlank { tree }

@Composable
private fun lastSynced(at: Long): String = if (at <= 0L) {
    stringResource(R.string.sync_never)
} else {
    android.text.format.DateUtils.getRelativeTimeSpanString(
        at,
        System.currentTimeMillis(),
        android.text.format.DateUtils.MINUTE_IN_MILLIS
    ).toString()
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
