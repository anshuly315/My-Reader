package com.flowfolio.feature.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.flowfolio.core.designsystem.ButtonStyle
import com.flowfolio.core.designsystem.FlowFolio
import com.flowfolio.core.designsystem.FlowFolioButton
import com.flowfolio.core.designsystem.FlowFolioCard
import com.flowfolio.core.designsystem.FlowFolioGlyph
import com.flowfolio.core.designsystem.FlowFolioToggle
import com.flowfolio.core.designsystem.FlowFolioType
import com.flowfolio.core.designsystem.LocalChromePadding

/** Every tool with a switch: a tool turned off disappears from the Tools tab until it is turned back on. */
@Composable
internal fun ManageTools(prefs: ToolPrefs, onDone: () -> Unit) {
    val p = FlowFolio.palette
    val chrome = LocalChromePadding.current
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = chrome.calculateBottomPadding() + 24.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FlowFolioButton(stringResource(R.string.tools_show_all), {
                    prefs.onChange(emptySet())
                }, style = ButtonStyle.SOFT)
                FlowFolioButton(stringResource(R.string.tools_done), onDone, style = ButtonStyle.PRIMARY)
            }
        }
        items(prefs.ordered, key = { it.name }) { tool ->
            val shown = tool.name !in prefs.hidden
            FlowFolioCard(Modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(shown, role = Role.Switch) { on ->
                            prefs.onChange(if (on) prefs.hidden - tool.name else prefs.hidden + tool.name)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FlowFolioGlyph(tool.glyph, null, color = p.accent, size = 22.dp)
                    Text(
                        stringResource(tool.title),
                        style = FlowFolioType.rowLabel.copy(color = p.ink),
                        modifier = Modifier.weight(1f).padding(horizontal = 14.dp)
                    )
                    MoveButton("\u2191", tool.name, -1, prefs)
                    MoveButton("\u2193", tool.name, 1, prefs)
                    FlowFolioToggle(checked = shown, onCheckedChange = null)
                }
            }
        }
    }
}

/** Moves [name] one place up (-1) or down (1) in the reader's own order of the tools. */
@Composable
private fun MoveButton(arrow: String, name: String, by: Int, prefs: ToolPrefs) {
    Text(
        arrow,
        style = FlowFolioType.rowLabel.copy(color = FlowFolio.palette.accent),
        modifier = Modifier
            .clickable {
                val names = prefs.ordered.map { it.name }.toMutableList()
                val at = names.indexOf(name)
                val to = at + by
                if (to in names.indices) {
                    names.add(to, names.removeAt(at))
                    prefs.onOrder(names)
                }
            }
            .padding(horizontal = 12.dp, vertical = 4.dp)
    )
}
