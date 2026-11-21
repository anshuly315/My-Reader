@file:Suppress("TooGenericExceptionCaught")

package com.flowfolio.feature.tools

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowfolio.core.ai.AiConfig
import com.flowfolio.core.ai.AiPreset
import com.flowfolio.core.ai.AiService
import com.flowfolio.core.ai.AiSettingsStore
import com.flowfolio.core.ai.ModelState
import com.flowfolio.core.ai.OnDeviceModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How the connection test went. */
sealed interface TestResult {
    data object Idle : TestResult

    data object Running : TestResult

    data class Done(val ok: Boolean, val message: String) : TestResult
}

@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val store: AiSettingsStore,
    private val ai: AiService,
    private val model: OnDeviceModel
) : ViewModel() {
    val config: StateFlow<AiConfig> get() = store.config
    val modelState: StateFlow<ModelState> get() = model.state
    private val _test = MutableStateFlow<TestResult>(TestResult.Idle)
    val test: StateFlow<TestResult> = _test.asStateFlow()
    private var downloading: Job? = null

    fun choose(preset: AiPreset) {
        store.choosePreset(preset)
        _test.value = TestResult.Idle
    }

    fun setBaseUrl(v: String) = store.setBaseUrl(v)

    fun setModel(v: String) = store.setModel(v)

    fun setApiKey(v: String) = store.setApiKey(v)

    fun setLanguage(v: String) = store.setLanguage(v.trim().lowercase())

    fun runTest() {
        _test.value = TestResult.Running
        viewModelScope.launch {
            _test.value = try {
                var reply = ""
                ai.test().collect { reply = it }
                TestResult.Done(reply.isNotBlank(), reply.take(MAX_REPLY))
            } catch (e: Exception) {
                TestResult.Done(false, e.message ?: "The test failed.")
            }
        }
    }

    fun download() {
        if (downloading?.isActive == true) return
        downloading = viewModelScope.launch { model.download() }
    }

    fun import(uri: Uri) {
        viewModelScope.launch { model.import(uri) }
    }

    fun deleteModel() {
        downloading?.cancel()
        model.delete()
    }

    private companion object {
        const val MAX_REPLY = 80
    }
}
