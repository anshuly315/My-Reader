package com.flowfolio.core.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.flowfolio.core.model.LibrarySort
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** What one run of the folder sync did: settings sent or fetched, and how many books' places were merged. */
data class FolderSyncSummary(val settingsFetched: Boolean, val booksMerged: Int)

/**
 * Keeps a chosen folder on the phone in step with the app: the settings and where each book was left, favourites and
 * finished books, in two small text files. Point the folder at one a sync app (Syncthing, Drive, Dropbox) watches and
 * other devices pick the files up from there. The newer settings file wins; reading places only ever move forward.
 */
class FolderBackup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val library: LibraryRepository
) {
    private val resolver: ContentResolver get() = context.contentResolver

    /** Keeps access to the picked folder across restarts. */
    fun hold(tree: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    /** Syncs both files with the folder [treeUri]; [lastSync] is when this phone last did (millis, 0: never). */
    suspend fun sync(treeUri: String, lastSync: Long): FolderSyncSummary = withContext(Dispatchers.IO) {
        val tree = Uri.parse(treeUri)
        val root = DocumentsContract.getTreeDocumentId(tree)
        val existing = children(tree, root)
        val settingsFile = existing[SETTINGS_FILE]
        var fetched = false
        if (settingsFile != null && settingsFile.second > lastSync) {
            fetched = read(tree, settingsFile.first)?.let { settings.importSettings(it) >= 0 } ?: false
        }
        write(tree, root, SETTINGS_FILE, settingsFile?.first, settings.exportSettings())
        val merged = existing[PROGRESS_FILE]?.let { read(tree, it.first) }?.let { mergeProgress(it) } ?: 0
        write(tree, root, PROGRESS_FILE, existing[PROGRESS_FILE]?.first, progressText())
        FolderSyncSummary(fetched, merged)
    }

    private suspend fun progressText(): String = library.observeBooks(LibrarySort.RECENT).first().joinToString("\n") {
        listOf(it.id, it.lastPage ?: -1, if (it.favorite) 1 else 0, if (it.finishedAtMillis != null) 1 else 0)
            .joinToString("\t")
    }

    private suspend fun mergeProgress(text: String): Int {
        val known = library.observeBooks(LibrarySort.RECENT).first().associateBy { it.id }
        var n = 0
        for (line in text.lines()) {
            val f = line.split('\t')
            val book = known[f.getOrNull(0)] ?: continue
            val page = f.getOrNull(1)?.toIntOrNull() ?: -1
            if (page > (book.lastPage ?: -1)) {
                library.advanceTo(book.id, page)
                n++
            }
            if (f.getOrNull(FAVORITE_AT) == "1" && !book.favorite) library.setFavorite(book.id, true)
            if (f.getOrNull(FINISHED_AT) == "1" && book.finishedAtMillis == null) library.setFinished(book.id, true)
        }
        return n
    }

    /** The files directly in the folder: name to (document id, last modified). */
    private fun children(tree: Uri, id: String): Map<String, Pair<String, Long>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        return resolver.query(uri, columns, null, null, null)?.use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(1), c.getString(0) to c.getLong(2)) }
        }.orEmpty()
    }

    private fun read(tree: Uri, docId: String): String? = runCatching {
        resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, docId))?.use {
            String(it.readBytes())
        }
    }.getOrNull()

    private fun write(tree: Uri, root: String, name: String, docId: String?, text: String) {
        val target = docId?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it) }
            ?: DocumentsContract.createDocument(
                resolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, root),
                "text/plain",
                name
            )
            ?: return
        resolver.openOutputStream(target, "wt")?.use { it.write(text.toByteArray()) }
    }

    private companion object {
        const val SETTINGS_FILE = "flowfolio-settings.txt"
        const val PROGRESS_FILE = "flowfolio-progress.txt"
        const val FAVORITE_AT = 2
        const val FINISHED_AT = 3
    }
}
