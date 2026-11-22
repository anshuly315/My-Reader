package com.flowfolio.feature.tools

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What the reader chose for the Tools tab: tools hidden, their order, and the layout (0 icons, 1 compact, 2 list,
 * 3 large). The app provides it from its settings, and each change saves itself.
 */
data class ToolPrefs(
    val hidden: Set<String> = emptySet(),
    val onChange: (Set<String>) -> Unit = {},
    val order: List<String> = emptyList(),
    val onOrder: (List<String>) -> Unit = {},
    val view: Int = 1,
    val onView: (Int) -> Unit = {}
) {
    /** Every tool in the reader's order; tools it does not mention keep their usual place after the rest. */
    val ordered: List<PdfTool>
        get() = PdfTool.entries.sortedBy { order.indexOf(it.name).let { i -> if (i < 0) Int.MAX_VALUE else i } }
}

val LocalToolPrefs = staticCompositionLocalOf { ToolPrefs() }
