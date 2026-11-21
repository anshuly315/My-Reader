@file:Suppress("CyclomaticComplexMethod", "MagicNumber")

package com.flowfolio.core.ai

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Where the on-device model stands. */
sealed interface ModelState {
    data object Missing : ModelState

    data class Downloading(val done: Long, val total: Long) : ModelState

    data class Ready(val bytes: Long) : ModelState

    data class Failed(val reason: String) : ModelState
}

/**
 * The Gemma file for on-device AI: imported from the phone's storage, or downloaded once. The download is pinned to one
 * revision with its SHA-256 and checked before it is used, so a changed file on the server is refused rather than fed
 * to native model code. It resumes from a partial file.
 */
@Singleton
class OnDeviceModel @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir get() = File(context.filesDir, "models").apply { mkdirs() }
    val file: File get() = File(dir, "assistant-model.litertlm")
    private val partial: File get() = File(dir, "download.part")

    private val _state = MutableStateFlow<ModelState>(current())
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private fun current(): ModelState =
        if (file.isFile && file.length() > 0) ModelState.Ready(file.length()) else ModelState.Missing

    fun has(): Boolean = file.isFile && file.length() > 0

    /** Copies a model the reader picked (a `.litertlm` file) into the app. */
    suspend fun import(uri: Uri) = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                partial.outputStream().use { input.copyTo(it) }
            }
            check(partial.renameTo(file)) { "Could not save the model." }
            _state.value = current()
        }.onFailure { _state.value = ModelState.Failed(it.message ?: "Could not import the model.") }
    }

    /** Downloads the pinned Gemma model, resuming a partial file. Progress is on [state]. */
    suspend fun download() = withContext(Dispatchers.IO) {
        runCatching {
            var have = if (partial.exists()) partial.length() else 0L
            _state.value = ModelState.Downloading(have, MODEL_BYTES)
            val conn = (URL(URL_PINNED).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                have = 0L
            } else if (code != HttpURLConnection.HTTP_PARTIAL) {
                error("The download failed (error $code).")
            }
            val total = have + conn.contentLengthLong.coerceAtLeast(0)
            conn.inputStream.use { input ->
                java.io.FileOutputStream(partial, have > 0).use { out ->
                    val buf = ByteArray(BUFFER)
                    var done = have
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > REPORT_STEP) {
                            lastReport = done
                            _state.value = ModelState.Downloading(done, total)
                        }
                    }
                }
            }
            check(sha256(partial) == SHA256) {
                partial.delete()
                "The downloaded file did not match its checksum and was removed."
            }
            check(partial.renameTo(file)) { "Could not save the model." }
            _state.value = current()
        }.onFailure {
            _state.value = if (partial.exists() && partial.length() > 0) {
                ModelState.Failed((it.message ?: "The download stopped.") + " Tap Download to resume.")
            } else {
                ModelState.Failed(it.message ?: "The download failed.")
            }
        }
    }

    fun delete() {
        file.delete()
        partial.delete()
        _state.value = current()
    }

    private fun sha256(f: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Gemma 4 E2B, about 2.6 GB, fixed to one revision of its Hugging Face repository. */
        private const val COMMIT = "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1"
        const val URL_PINNED =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/$COMMIT/gemma-4-E2B-it.litertlm"
        const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
        const val MODEL_BYTES = 2_583_000_000L
        private const val BUFFER = 1 shl 16
        private const val REPORT_STEP = 1L shl 20
    }
}
