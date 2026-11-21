package com.flowfolio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowfolio.core.designsystem.ButtonStyle
import com.flowfolio.core.designsystem.DialogAction
import com.flowfolio.core.designsystem.FlowFolioDialog
import com.flowfolio.core.designsystem.Glyph
import com.flowfolio.core.model.StorageMode

/**
 * The first-start question: may FlowFolio look for books across the whole phone, and should it keep them where they
 * are or copy them into the app? Choosing either opens Android's "All files access" page; coming back with access
 * granted starts the scan.
 */
@Composable
internal fun PhoneAccessHost(appViewModel: AppViewModel) {
    val ask by appViewModel.askAboutPhone.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var waiting by rememberSaveable { mutableStateOf<String?>(null) }
    LifecycleResumeEffect(waiting) {
        val mode = waiting?.let(StorageMode::valueOf)
        if (mode != null && Environment.isExternalStorageManager()) {
            waiting = null
            appViewModel.answerPhone(mode)
        }
        onPauseOrDispose {}
    }
    if (!ask) return
    fun choose(mode: StorageMode) {
        if (Environment.isExternalStorageManager()) {
            appViewModel.answerPhone(mode)
        } else {
            waiting = mode.name
            openAllFilesAccess(context)
        }
    }
    FlowFolioDialog(
        onDismissRequest = { appViewModel.answerPhone(null) },
        title = stringResource(R.string.phone_title),
        glyph = Glyph.Page,
        body = stringResource(R.string.phone_body),
        actions = listOf(
            DialogAction(stringResource(R.string.phone_not_now)) { appViewModel.answerPhone(null) },
            DialogAction(stringResource(R.string.phone_copy)) { choose(StorageMode.COPY) },
            DialogAction(stringResource(R.string.phone_keep), ButtonStyle.PRIMARY) { choose(StorageMode.LINK) }
        )
    )
}

private fun openAllFilesAccess(context: Context) {
    val own =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName))
    runCatching { context.startActivity(own) }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
}
