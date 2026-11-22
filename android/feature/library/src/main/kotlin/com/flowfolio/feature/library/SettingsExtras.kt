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
internal val HIDEABLE_TABS =
    listOf(
        "shelves" to R.string.custom_tab_shelves,
        "notebook" to R.string.custom_tab_notebook,
        "tools" to R.string.custom_tab_tools
    )

/** Appearance, after the theme: glass, the reader's day and night papers, and the widgets' look. */
@Composable
internal fun AppearanceExtras(settings: UserSettings, actions: SettingsActions) {
    val o = settings.options
    Group(R.string.custom_glass, 2) {
        Choice(R.string.custom_glass_detail) {
            FlowFolioSegmented(
                listOf(R.string.glass_off, R.string.glass_soft, R.string.glass_frosted, R.string.glass_crystal)
                    .map { stringResource(it) },
                GlassMode.entries.indexOf(Options.glass(o)),
                { actions.onOption(Options.GLASS, GlassMode.entries[it].name) }
            )
        }
    }
    PaperGroup(settings, actions)
    WidgetGroup(settings, actions)
}

/** Library, after import: how covers look, then which tabs show, in which order, and what the app opens on. */
@Composable
internal fun LibraryExtras(settings: UserSettings, actions: SettingsActions) {
    val o = settings.options
    Group(R.string.custom_covers, 4) {
        Choice(R.string.custom_cover_size) {
            FlowFolioSegmented(
                Options.coverSizes.map { "${it}x" },
                Options.coverSizeIndex(o),
                { actions.onOption(Options.COVER_SIZE, it.toString()) }
            )
        }
        Divider()
        Choice(R.string.custom_columns) {
            FlowFolioSegmented(
                listOf(stringResource(R.string.custom_columns_auto)) + (2..6).map { it.toString() },
                Options.columns(o).let { if (it == 0) 0 else it - 1 },
                { actions.onOption(Options.COLUMNS, if (it == 0) "0" else (it + 1).toString()) }
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
    StartupGroup(settings, actions)
    LibraryUpkeepGroup(actions, 7)
}

/** Reading, after spreads and turns: what taps and the volume buttons do, the blue-light filter and orientation. */
@Composable
internal fun ReadingExtras(settings: UserSettings, actions: SettingsActions) {
    val o = settings.options
    Group(R.string.reading_taps, 3) {
        listOf(
            R.string.reading_tap_left to Options.TAP_LEFT,
            R.string.reading_tap_centre to Options.TAP_CENTRE,
            R.string.reading_tap_right to Options.TAP_RIGHT
        ).forEachIndexed { i, (label, key) ->
            if (i > 0) Divider()
            Choice(label) {
                FlowFolioSegmented(
                    listOf(
                        R.string.reading_tap_usual,
                        R.string.reading_tap_back,
                        R.string.reading_tap_next,
                        R.string.reading_tap_bars,
                        R.string.reading_tap_nothing
                    ).map { stringResource(it) },
                    Options.tapAction(o, key),
                    { actions.onOption(key, it.toString()) }
                )
            }
        }
        Divider()
        Choice(R.string.reading_volume) {
            FlowFolioSegmented(
                listOf(R.string.reading_volume_off, R.string.reading_volume_down, R.string.reading_volume_up)
                    .map { stringResource(it) },
                Options.volumeKeys(o),
                { actions.onOption(Options.VOLUME_KEYS, it.toString()) }
            )
        }
    }
    Group(R.string.reading_screen, 4) {
        Choice(R.string.reading_blue) {
            FlowFolioSegmented(
                listOf(R.string.reading_off, R.string.reading_low, R.string.reading_medium, R.string.reading_high)
                    .map { stringResource(it) },
                Options.blueLight(o),
                { actions.onOption(Options.BLUE_LIGHT, it.toString()) }
            )
        }
        Divider()
        Choice(R.string.reading_orientation) {
            FlowFolioSegmented(
                listOf(
                    R.string.reading_orient_auto,
                    R.string.reading_orient_portrait,
                    R.string.reading_orient_landscape
                )
                    .map { stringResource(it) },
                Options.orientationIndex(o),
                { actions.onOption(Options.ORIENTATION, Options.orientations[it]) }
            )
        }
    }
}

/** Start-up and tabs: which tab opens, reopening the last book, tab order, and which tabs show. */
@Composable
private fun StartupGroup(settings: UserSettings, actions: SettingsActions) {
    val o = settings.options
    val routes = listOf("library", "shelves", "notebook", "tools")
    Group(R.string.custom_startup, 5) {
        Choice(R.string.custom_start_tab) {
            FlowFolioSegmented(
                (listOf(R.string.custom_tab_library) + HIDEABLE_TABS.map { it.second }).map { stringResource(it) },
                routes.indexOf(Options.startTab(o)).coerceAtLeast(0),
                { actions.onOption(Options.START_TAB, routes[it]) }
            )
        }
        Divider()
        SwitchRow(R.string.custom_open_last, R.string.custom_open_last_detail, Options.openLast(o), {
            actions.onOption(Options.OPEN_LAST, if (it) "1" else "0")
        })
        Divider()
        Choice(R.string.custom_tab_order) {
            FlowFolioSegmented(
                listOf(
                    R.string.custom_order_usual,
                    R.string.custom_order_tools_first,
                    R.string.custom_order_notebook_first
                )
                    .map { stringResource(it) },
                TAB_ORDERS.indexOfFirst { it == o[Options.TAB_ORDER].orEmpty() }.coerceAtLeast(0),
                { actions.onOption(Options.TAB_ORDER, TAB_ORDERS[it]) }
            )
        }
    }
    Group(R.string.custom_tabs, 6) {
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

/** The papers Auto uses by day and by night. */
@Composable
private fun PaperGroup(settings: UserSettings, actions: SettingsActions) {
    val o = settings.options
    Group(R.string.custom_daynight, 3) {
        Choice(R.string.custom_day_paper) {
            FlowFolioSegmented(
                listOf(R.string.custom_paper_auto, R.string.custom_paper_paper, R.string.custom_paper_sepia)
                    .map { stringResource(it) },
                Options.dayPaperIndex(o),
                { actions.onOption(Options.DAY_PAPER, Options.dayPapers[it]) }
            )
        }
        Divider()
        Choice(R.string.custom_night_paper) {
            FlowFolioSegmented(
                listOf(R.string.custom_paper_black, R.string.custom_paper_night, R.string.custom_paper_rustic)
                    .map { stringResource(it) },
                Options.nightPaperIndex(o),
                { actions.onOption(Options.NIGHT_PAPER, Options.nightPapers[it]) }
            )
        }
    }
}

/** The home-screen widgets' colours and how see-through their background is. */
@Composable
private fun WidgetGroup(settings: UserSettings, actions: SettingsActions) {
    val o = settings.options
    Group(R.string.custom_widgets, 4) {
        Choice(R.string.custom_widget_theme) {
            FlowFolioSegmented(
                listOf(R.string.custom_widget_follow, R.string.custom_widget_light, R.string.custom_widget_dark)
                    .map { stringResource(it) },
                Options.widgetTheme(o),
                { actions.onOption(Options.WIDGET_THEME, it.toString()) }
            )
        }
        Divider()
        Choice(R.string.custom_widget_background) {
            FlowFolioSegmented(
                listOf(
                    R.string.custom_widget_solid,
                    R.string.custom_widget_soft,
                    R.string.custom_widget_glassy,
                    R.string.custom_widget_clear
                ).map { stringResource(it) },
                Options.widgetOpacityIndex(o),
                { actions.onOption(Options.WIDGET_OPACITY, it.toString()) }
            )
        }
    }
}

private val TAB_ORDERS = listOf("", "tools,library,shelves,notebook", "notebook,library,shelves,tools")

@Composable
private fun ColumnScope.Choice(label: Int, content: @Composable () -> Unit) {
    Text(
        stringResource(label),
        style = FlowFolioType.rowDetail.copy(color = FlowFolio.palette.ink2),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)
    )
    Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) { content() }
}
