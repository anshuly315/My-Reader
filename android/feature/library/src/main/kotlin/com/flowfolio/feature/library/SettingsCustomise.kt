package com.flowfolio.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.flowfolio.core.designsystem.FlowFolio
import com.flowfolio.core.designsystem.FlowFolioSegmented
import com.flowfolio.core.designsystem.FlowFolioType
import com.flowfolio.core.model.GlassMode
import com.flowfolio.core.model.Options
import com.flowfolio.core.model.UserSettings

/** The tabs a reader may hide; Settings always stays so the choice can be undone. */
internal val HIDEABLE_TABS = listOf("shelves" to R.string.custom_tab_shelves, "notebook" to R.string.custom_tab_notebook, "tools" to R.string.custom_tab_tools)

/** Customise: glass mode, how books look in the grid and shelves, and which tabs show. */
@Composable
internal fun CustomiseGroup(settings: UserSettings, actions: SettingsActions) {
    val o = settings.options
    Group(R.string.custom_glass, 3) {
        Choice(R.string.custom_glass_detail) {
            FlowFolioSegmented(
                listOf(R.string.glass_off, R.string.glass_soft, R.string.glass_frosted, R.string.glass_crystal)
                    .map { stringResource(it) },
                GlassMode.entries.indexOf(Options.glass(o)),
                { actions.onOption(Options.GLASS, GlassMode.entries[it].name) }
            )
        }
    }
    Group(R.string.custom_covers, 4) {
        Choice(R.string.custom_cover_size) {
            FlowFolioSegmented(
                Options.coverSizes.map { "${it}x" },
                Options.coverSizeIndex(o),
                { actions.onOption(Options.COVER_SIZE, it.toString()) }
            )
        }
        Divider()
        Choice(R.string.custom_cover_radius) {
            FlowFolioSegmented(
                Options.coverRadiusDp.map { it.toString() },
                Options.coverRadiusIndex(o),
                { actions.onOption(Options.COVER_RADIUS, it.toString()) }
            )
        }
    }
    Group(R.string.custom_tabs, 5) {
        val hidden = Options.hiddenTabs(o)
        HIDEABLE_TABS.forEachIndexed { i, (route, label) ->
            if (i > 0) Divider()
            SwitchRow(label, null, route !in hidden, { on ->
                val next = if (on) hidden - route else hidden + route
                actions.onOption(Options.HIDDEN_TABS, next.joinToString(","))
            })
        }
    }
}

@Composable
private fun ColumnScope.Choice(label: Int, content: @Composable () -> Unit) {
    Text(
        stringResource(label),
        style = FlowFolioType.rowDetail.copy(color = FlowFolio.palette.ink2),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)
    )
    Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) { content() }
}
