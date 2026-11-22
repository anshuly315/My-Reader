package com.flowfolio.core.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.flowfolio.core.database.AnnotationDao
import com.flowfolio.core.database.AnnotationEntity
import com.flowfolio.core.database.BookmarkDao
import com.flowfolio.core.database.BookmarkEntity
import com.flowfolio.core.model.LibrarySort
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** What one run of the folder sync did: settings sent or fetched, and how many books' places were merged. */
data class FolderSyncSummary(val settingsFetched: Boolean, val booksMerged: Int, val marksMerged: Int = 0)

/**
 * Keeps a chosen folder on the phone in step with the app: the settings and where each book was left, favourites and
 * finished books, in two small text files. Point the folder at one a sync app (Syncthing, Drive, Dropbox) watches and
 * other devices pick the files up from there. The newer settings file wins; reading places only ever move forward.
 */
@Suppress(
    "TooManyFunctions",
    "LongMethod",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "LongParameterList",
    "LoopWithTooManyJumpStatements"
)
class FolderBackup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val library: LibraryRepository,
    private val annotations: AnnotationDao,
    private val bookmarks: BookmarkDao
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

    /**
     * Syncs with the folder chosen in Settings, if one is, and notes when; null when no folder is chosen or the
     * folder can no longer be reached.
     */
    suspend fun syncSaved(): FolderSyncSummary? {
        val o = settings.settings.first().options
        val folder = o[com.flowfolio.core.model.Options.SYNC_FOLDER] ?: return null
        val last = o[com.flowfolio.core.model.Options.SYNC_LAST]?.toLongOrNull() ?: 0L
        return runCatching { sync(folder, last) }.getOrNull()
            ?.also {
                settings.setOption(com.flowfolio.core.model.Options.SYNC_LAST, System.currentTimeMillis().toString())
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
        val marksFile = existing[MARKS_FILE]
        val marks = marksFile?.let { read(tree, it.first) }?.let { mergeMarks(it) } ?: 0
        write(tree, root, MARKS_FILE, marksFile?.first, marksText())
        FolderSyncSummary(fetched, merged, marks)
    }

    private suspend fun progressText(): String = library.observeBooks(LibrarySort.RECENT).first().joinToString("\n") {
        listOf(
            it.id,
            it.lastPage ?: -1,
            if (it.favorite) 1 else 0,
            if (it.finishedAtMillis != null) 1 else 0,
            esc(it.lastLocator),
            esc(it.flowLocator),
            it.lastOpenedAtMillis ?: 0
        ).joinToString("\t")
    }

    private fun esc(t: String?): String = (t ?: "").replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unesc(t: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '\\' && i + 1 < t.length) {
                i++
                out.append(
                    when (t[i]) {
                        't' -> '\t'
                        'n' -> '\n'
                        else -> t[i]
                    }
                )
            } else {
                out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** One line per highlight (H) and ribbon (B): kind, book, page, locator, colour, then the rest. */
    private suspend fun marksText(): String {
        val lines = annotations.observeAll().first().map {
            listOf(
                "H", it.bookId, it.page, esc(it.locator), it.color, it.charStart, it.charEnd, esc(it.text),
                esc(it.rects), esc(it.note), esc(it.pageLabel), it.createdAt, it.updatedAt
            ).joinToString("\t")
        } + bookmarks.observeAll().first().map {
            listOf("B", it.bookId, it.page, esc(it.locator), it.color, it.createdAt).joinToString("\t")
        }
        return lines.joinToString("\n")
    }

    /** Adds the marks this phone lacks, for books it has; nothing is ever removed. */
    private suspend fun mergeMarks(text: String): Int {
        val known = library.observeBooks(LibrarySort.RECENT).first().map { it.id }.toSet()
        val haveH = annotations.observeAll().first().map {
            listOf(it.bookId, it.page, it.charStart, it.charEnd, it.locator)
        }.toMutableSet()
        val haveB = bookmarks.observeAll().first().map { listOf(it.bookId, it.page, it.locator) }.toMutableSet()
        var n = 0
        for (line in text.lines()) {
            val f = line.split('\t')
            val book = f.getOrNull(1)?.takeIf { it in known } ?: continue
            val page = f.getOrNull(2)?.toIntOrNull() ?: continue
            val locator = f.getOrNull(LOCATOR)?.let(::unesc)?.ifEmpty { null }
            val color = f.getOrNull(COLOR) ?: continue
            if (f[0] == "B" && haveB.add(listOf(book, page, locator))) {
                bookmarks.insert(
                    BookmarkEntity(0, book, page, color, f.getOrNull(B_CREATED)?.toLongOrNull() ?: 0, locator)
                )
                n++
            } else if (f[0] == "H" && f.size > H_UPDATED) {
                val start = f[H_START].toIntOrNull() ?: continue
                val end = f[H_END].toIntOrNull() ?: continue
                if (haveH.add(listOf(book, page, start, end, locator))) {
                    annotations.insert(
                        AnnotationEntity(
                            0, book, page,
                            unesc(f[H_LABEL]).ifEmpty {
                                null
                            },
                            start, end, unesc(f[H_TEXT]), unesc(f[H_RECTS]),
                            color, unesc(f[H_NOTE]).ifEmpty { null }, f[H_CREATED].toLongOrNull() ?: 0,
                            f[H_UPDATED].toLongOrNull() ?: 0, locator
                        )
                    )
                    n++
                }
            }
        }
        return n
    }

    private suspend fun mergeProgress(text: String): Int {
        val known = library.observeBooks(LibrarySort.RECENT).first().associateBy { it.id }
        var n = 0
        for (line in text.lines()) {
            val f = line.split('\t')
            val book = known[f.getOrNull(0)] ?: continue
            val page = f.getOrNull(1)?.toIntOrNull() ?: -1
            val openedThere = f.getOrNull(OPENED_AT)?.toLongOrNull() ?: 0L
            if (openedThere > (book.lastOpenedAtMillis ?: 0L) && page >= 0) {
                // Read more recently on the other device: its place wins, back as well as forward, EPUB places too.
                val locator = f.getOrNull(LOCATOR_AT)?.let(::unesc)?.ifBlank { null }
                val flow = f.getOrNull(FLOW_AT)?.let(::unesc)?.ifBlank { null }
                library.setSyncedPlace(book.id, page, locator, flow, openedThere)
                n++
            } else if (openedThere == 0L && page > (book.lastPage ?: -1)) {
                // A file from an older build carries no times: only move forward, as it used to.
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
        const val MARKS_FILE = "flowfolio-marks.txt"
        const val LOCATOR = 3
        const val COLOR = 4
        const val B_CREATED = 5
        const val H_START = 5
        const val H_END = 6
        const val H_TEXT = 7
        const val H_RECTS = 8
        const val H_NOTE = 9
        const val H_LABEL = 10
        const val H_CREATED = 11
        const val H_UPDATED = 12
        const val FAVORITE_AT = 2
        const val FINISHED_AT = 3
        const val LOCATOR_AT = 4
        const val FLOW_AT = 5
        const val OPENED_AT = 6
    }
}
