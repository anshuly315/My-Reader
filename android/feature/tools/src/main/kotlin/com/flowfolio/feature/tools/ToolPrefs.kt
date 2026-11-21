package com.flowfolio.feature.tools

import androidx.compose.runtime.staticCompositionLocalOf

/** Which tools the reader hid, and how to save a new choice. The app provides it from its settings. */
data class ToolPrefs(val hidden: Set<String> = emptySet(), val onChange: (Set<String>) -> Unit = {})

val LocalToolPrefs = staticCompositionLocalOf { ToolPrefs() }
