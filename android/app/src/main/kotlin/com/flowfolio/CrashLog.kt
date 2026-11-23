package com.flowfolio

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.flowfolio.core.designsystem.ButtonStyle
import com.flowfolio.core.designsystem.DialogAction
import com.flowfolio.core.designsystem.FlowFolioDialog
import com.flowfolio.core.designsystem.Glyph
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash reports kept on the phone, with no service and nothing sent by itself: when the app crashes, what happened
 * (the error and where in the code) is written to a file before Android closes it; the next time it opens, it says so
 * and offers to share the report. Release builds keep line numbers, and CI keeps the matching `mapping.txt`, so a
 * shared report can be read back to the exact line.
 */
object CrashLog {
    private const val DIR = "crashes"
    private const val PENDING = "pending.txt"
    private const val KEEP = 5

    /** Catches any crash in this process, writes it down, then lets Android close the app as usual. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        val text = buildString {
            appendLine("FlowFolio crash report")
            appendLine("When: $stamp")
            appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine(
                "Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            )
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(trace)
        }
        File(dir, PENDING).writeText(text)
        File(dir, "crash-${System.currentTimeMillis()}.txt").writeText(text)
        dir.listFiles { f -> f.name.startsWith("crash-") }?.sortedByDescending { it.name }?.drop(KEEP)
            ?.forEach { it.delete() }
    }

    /** The report of a crash not yet seen, or null. */
    fun pending(context: Context): String? = File(context.filesDir, "$DIR/$PENDING").takeIf { it.isFile }?.readText()

    fun clearPending(context: Context) {
        File(context.filesDir, "$DIR/$PENDING").delete()
    }

    /** The newest report kept (the last five are), for Settings → Help. */
    fun latest(context: Context): String? = File(context.filesDir, DIR)
        .listFiles { f -> f.name.startsWith("crash-") }?.maxByOrNull { it.name }?.readText()

    /** Opens the share sheet with [report] as plain text (email, WhatsApp, a note), chosen by the reader. */
    fun share(context: Context, report: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "FlowFolio crash report")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** After a crash: says the app closed unexpectedly and offers to share the report, once. */
@Composable
internal fun CrashReportHost() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashLog.pending(context)) }
    report?.let { text ->
        val close = {
            CrashLog.clearPending(context)
            report = null
        }
        FlowFolioDialog(
            onDismissRequest = close,
            title = stringResource(R.string.crash_title),
            body = stringResource(R.string.crash_body),
            glyph = Glyph.Help,
            actions = listOf(
                DialogAction(stringResource(R.string.crash_dismiss)) { close() },
                DialogAction(stringResource(R.string.crash_share), ButtonStyle.PRIMARY) {
                    CrashLog.share(context, text)
                    close()
                }
            )
        )
    }
}
