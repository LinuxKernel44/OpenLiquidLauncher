package com.linuxkernel44.openliquidlauncher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shadow as TextShadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.linuxkernel44.openliquidlauncher.data.LauncherSettings

/**
 * Cold start: the first draw of the glass shaders (blur, lens, highlight, shadow) is expensive. Until [ready] the launcher
 * draws a light flat tint instead, so icons appear at once; the real liquid glass switches on right after the first frames.
 */
object GlassGate {
    var ready by androidx.compose.runtime.mutableStateOf(false)
}

/** User-tunable parameters of the liquid glass material (see Settings). */
@Immutable
data class GlassStyle(
    val blur: Float = 4f,
    val refractionHeight: Float = 14f,
    val refractionAmount: Float = 24f,
    val surfaceAlpha: Float = 0.18f,
    val dispersion: Boolean = false,
    /** 0 Minimum · 1 Low · 2 Current · 3 High · 4 Maximum. */
    val quality: Int = 2,
    /** 0..1 droplet look for small elements (see `small` in [liquidGlass]). */
    val droplet: Float = 0f,
    /** 0..1 corner roundness (0.5 = original). */
    val roundness: Float = 0.5f,
    /** 0..1 roundness of folders (icon and opened panel). */
    val folderRoundness: Float = 0.5f,
) {
    companion object {
        fun from(s: LauncherSettings) = GlassStyle(
            s.glassBlur, s.glassRefractionHeight, s.glassRefractionAmount, s.glassSurfaceAlpha, s.glassDispersion, s.glassQuality, s.glassDroplet, s.glassRoundness, s.folderRoundness,
        )
    }
}

/**
 * The core of the launcher's look: draws the wallpaper backdrop through a refracting, blurred,
 * highlighted glass layer (Kyant0's AndroidLiquidGlass).
 */
fun Modifier.liquidGlass(
    backdrop: Backdrop,
    style: GlassStyle,
    shape: Shape,
    surfaceAlpha: Float = style.surfaceAlpha,
    tint: Color = Color.Unspecified,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    /** Rim light + drop shadow; turn off for full-screen sheets, where they just draw a frame along the screen edges. */
    edges: Boolean = true,
    /** Small element (folder, dock, button...): the "Droplet" setting makes it a clearer, rounder, more refractive drop. */
    small: Boolean = false,
): Modifier = if (!GlassGate.ready) {
    (if (layerBlock != null) Modifier.graphicsLayer(layerBlock) else Modifier)
        .background(Color.White.copy(alpha = surfaceAlpha + 0.06f), shape)
} else {
    val d = if (small) style.droplet else 0f
    drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        val q = style.quality
        // Quality ladder: Minimum = blur only; Low adds colour boost + refraction; Current is the original look;
        // High adds the depth effect (and an inner shadow); Maximum adds chromatic dispersion and a softer blur.
        if (q >= 1) vibrancy()
        // Droplet: clearer (less blur), a much stronger and deeper lens, like a real drop of water.
        val blurK = (if (q >= 4) 1.25f else 1f) * (1f - 0.7f * d)
        if (style.blur * blurK > 0f) blur((style.blur * blurK).dp.toPx())
        if (q >= 1) lens(
            (style.refractionHeight * (1f + 1.2f * d)).dp.toPx(),
            (style.refractionAmount * (1f + 1.6f * d)).dp.toPx(),
            depthEffect = small && (q >= 3 || d > 0.5f),
            chromaticAberration = style.dispersion || q >= 4,
        )
    },
    highlight = if (edges && style.quality >= 1) ({ if (d > 0f) Highlight(width = (0.5f + 1.5f * d).dp, alpha = 1f) else Highlight.Default }) else null,
    shadow = if (edges && style.quality >= 2) ({ if (d > 0f) Shadow(radius = (24f + 10f * d).dp, color = Color.Black.copy(alpha = 0.1f + 0.1f * d)) else Shadow.Default }) else null,
    // The inner shadow reads as a second contour on big cards and sheets, so only small elements get it.
    innerShadow = if (small && (style.quality >= 3 || d > 0f)) ({ InnerShadow(radius = (10f + 8f * d).dp, color = Color.Black.copy(alpha = 0.08f + 0.10f * d)) }) else null,
    layerBlock = layerBlock,
    onDrawSurface = {
        drawRect(Color.Black.copy(alpha = 0.10f * (1f - 0.5f * d))) // keeps white text readable on bright wallpapers
        if (tint != Color.Unspecified) drawRect(tint)
        drawRect(Color.White.copy(alpha = surfaceAlpha * (1f - 0.55f * d)))
    },
    )
}

val GlassText = TextStyle(
    color = Color.White,
    fontSize = 12.sp,
    fontWeight = FontWeight.Medium,
    shadow = TextShadow(Color.Black.copy(alpha = 0.45f), blurRadius = 6f),
    textAlign = TextAlign.Center,
)

@Composable
fun GText(
    text: String,
    modifier: Modifier = Modifier,
    size: Float = 12f,
    weight: FontWeight = FontWeight.Medium,
    color: Color = Color.White,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Center,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = GlassText.copy(fontSize = size.sp, fontWeight = weight, color = color, textAlign = align),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Press-scale feedback modifier used by glass buttons. */
@Composable
fun Modifier.pressScale(onClick: () -> Unit): Modifier {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, label = "press")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
                pressed = true
                val up = waitForUpOrCancellation(PointerEventPass.Initial)
                pressed = false
                if (up != null) onClick()
            }
        }
}

@Composable
fun GlassButton(
    label: String,
    backdrop: Backdrop,
    style: GlassStyle,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .liquidGlass(backdrop, style, style.round(50, small = true), surfaceAlpha = style.surfaceAlpha + 0.06f, small = true)
            .pressScale(onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) { GText(label, size = 14f, weight = FontWeight.SemiBold) }
}

@Composable
fun GlassCircleButton(
    backdrop: Backdrop,
    style: GlassStyle,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier.liquidGlass(backdrop, style, CircleShape, surfaceAlpha = style.surfaceAlpha + 0.06f, small = true).pressScale(onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}


val GlassTextFieldStyle = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)

/**
 * Lays the node out larger than its slot (by [extend] on each side, but not the top unless [top]) so it
 * bleeds off-screen. Backdrop blur has nothing to sample past the wallpaper's edge, which would leave a
 * sharp strip along full-width sheets; pushing that zone off-screen hides it. Pad the content back by [extend].
 */
fun Modifier.bleed(extend: androidx.compose.ui.unit.Dp = 48.dp, top: Boolean = true): Modifier =
    layout { measurable, c ->
        val e = extend.roundToPx()
        val t = if (top) e else 0
        val p = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(c.maxWidth + 2 * e, c.maxHeight + e + t))
        layout(c.maxWidth, c.maxHeight) { p.place(-e, -t) }
    }

/**
 * Corner shape of a glass element. [basePercent] is the original corner (percent of the shorter side, 50 = capsule/circle);
 * the Roundness setting scales it (0 = nearly square, 0.5 = original, 1 = capsule) and, for [small] elements, the Droplet
 * setting pushes it further toward a full capsule.
 */
fun GlassStyle.round(basePercent: Int, small: Boolean = false, r: Float = roundness, dropletShape: Boolean = true): RoundedCornerShape {
    var p = if (r <= 0.5f) basePercent * (r / 0.5f) else basePercent + (50 - basePercent) * ((r - 0.5f) / 0.5f)
    if (small && dropletShape) p += (50f - p) * droplet
    return RoundedCornerShape(percent = p.toInt().coerceIn(0, 50))
}

/** Same, for big containers sized in dp (cards, sheets, dialogs): 0 -> nearly square, 0.5 -> [base], 1 -> twice [base]. */
fun GlassStyle.roundDp(base: androidx.compose.ui.unit.Dp, r: Float = roundness): RoundedCornerShape =
    RoundedCornerShape((base * 2f * r).coerceAtLeast(2.dp))
