package com.flowfolio.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * The screen content that glass surfaces blur (Haze, owner-approved 2026-10-17). The app shell provides it; a glass
 * surface outside any provider falls back to its tint alone.
 */
val LocalGlassState = staticCompositionLocalOf<HazeState?> { null }

/**
 * Overrides the glass look below it: [tint] over the blur, the [edge] hairline and the [blur] radius (null keeps the
 * default). The reader
 * uses a lighter, frosted tint over dark pages, where the default dark tint would read as a hole rather than glass.
 */
@Immutable
data class GlassStyle(
    val tint: Color? = null,
    val edge: Color? = null,
    val blur: Dp? = null,
    /** Frosted grain, 0..1 (null keeps the default). */
    val grain: Float? = null,
    /** A light sheen from the top edge down, 0..1 (0 = none). */
    val shine: Float = 0f,
    /** False for the Off mode: a solid surface, nothing behind is blurred. */
    val see: Boolean = true
)

val LocalGlassStyle = staticCompositionLocalOf { GlassStyle() }

/** The corner radius, in dp, of book covers: the reader's choice on the Customise page. */
val LocalCoverRadius = staticCompositionLocalOf { 8 }

/** Books across the library grid (0 = by screen width). */
val LocalLibraryColumns = staticCompositionLocalOf { 0 }

/** Scales the books in the grid and shelf views: 1 is the standard size. */
val LocalCoverScale = staticCompositionLocalOf { 1f }

/** Marks this content as what glass surfaces above it blur (the app's screens, the reader's pages). */
@Composable
fun Modifier.glassSource(): Modifier = LocalGlassState.current?.let { hazeSource(it) } ?: this

/**
 * The mock's glass (`.glass`, ADR-0052): what is behind is blurred ([blur], `saturate` approximated by the tint), then
 * tinted with [tint] (default: the surface at 84%), with a hairline edge and a soft shadow. Real blur needs
 * Android 12+; older phones get the tint alone (Haze's fallback).
 */
@Composable
fun Modifier.glass(
    shape: Shape,
    tint: Color = LocalGlassStyle.current.tint ?: FlowFolio.palette.surface.copy(alpha = GLASS_ALPHA),
    blur: Dp = LocalGlassStyle.current.blur ?: 18.dp
): Modifier {
    val p = FlowFolio.palette
    val edge = LocalGlassStyle.current.edge ?: p.edge.copy(alpha = EDGE_ALPHA)
    val style = LocalGlassStyle.current
    val state = LocalGlassState.current.takeIf { style.see }
    val shadowed =
        softShadow(shape, Color.Black.copy(alpha = SHADOW_ALPHA), blur = 28.dp, offsetY = 8.dp, spread = (-4).dp)
    val blurred = if (state != null) {
        shadowed.clip(shape).hazeBlur(
            HazeInput.Sources(state),
            HazeBlurStyle {
                blurRadius(blur)
                backgroundColor(p.background)
                colorEffects(listOf(HazeColorEffect.tint(tint)))
                fallbackColorEffect(HazeColorEffect.tint(tint.copy(alpha = FALLBACK_ALPHA)))
                noiseFactor(style.grain ?: NOISE)
            }
        )
    } else {
        shadowed.clip(shape).drawWithContent {
            drawRect(tint)
            drawContent()
        }
    }
    val shine = style.shine
    return blurred.drawWithContent {
        if (shine > 0f) {
            // The sheen sits under the content, so labels stay crisp on top of it.
            drawRect(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = shine),
                    SHINE_STOP to Color.White.copy(alpha = shine * SHINE_FADE),
                    1f to Color.Transparent
                )
            )
        }
        drawContent()
        drawOutline(shape.createOutline(size, layoutDirection, this), edge, style = Stroke(1.dp.toPx()))
    }
}

/**
 * A small sample of the current glass over a colourful strip, for the Glass setting: the bar above shows a plain
 * page, where every mode can look alike, so the sample makes the choice visible as it is made.
 */
@Composable
fun GlassPreview(modifier: Modifier = Modifier) {
    val p = FlowFolio.palette
    val haze = rememberHazeState()
    Box(modifier.height(PREVIEW_HEIGHT).clip(RoundedCornerShape(16.dp))) {
        Row(Modifier.matchParentSize().hazeSource(haze)) {
            PREVIEW_COLOURS.forEach { c ->
                Box(Modifier.weight(1f).fillMaxHeight().background(c)) {
                    Text(
                        "Aa",
                        style = FlowFolioType.rowLabel.copy(color = Color.White),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
        CompositionLocalProvider(LocalGlassState provides haze) {
            Box(
                Modifier.align(Alignment.Center).padding(horizontal = 24.dp).fillMaxWidth().height(PREVIEW_BAR)
                    .glass(RoundedCornerShape(PREVIEW_BAR / 2)),
                contentAlignment = Alignment.Center
            ) {
                Text("Glass", style = FlowFolioType.rowLabel.copy(color = p.ink))
            }
        }
    }
}

private val PREVIEW_HEIGHT = 112.dp
private val PREVIEW_BAR = 56.dp
private val PREVIEW_COLOURS = listOf(
    Color(0xFFE76F51),
    Color(0xFFF4A261),
    Color(0xFF2A9D8F),
    Color(0xFF264653),
    Color(0xFF8E7DBE)
)
private const val SHINE_STOP = 0.45f
private const val SHINE_FADE = 0.25f

private const val GLASS_ALPHA = 0.84f
private const val EDGE_ALPHA = 0.75f
private const val FALLBACK_ALPHA = 0.9f
private const val SHADOW_ALPHA = 0.14f
private const val NOISE = 0.05f
