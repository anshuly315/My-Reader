package com.flowfolio.feature.tools

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What "Set up AI" does where AI is offered (the reader's Ask AI sheet): the app provides it at the top of its
 * navigation, so the reader opens Settings → AI without knowing where that screen lives.
 */
val LocalOpenAiSettings = staticCompositionLocalOf<() -> Unit> { {} }
