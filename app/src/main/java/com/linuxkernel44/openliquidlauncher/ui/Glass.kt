package com.linuxkernel44.openliquidlauncher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import com.kyant.backdrop.shadow.Shadow
import com.linuxkernel44.openliquidlauncher.data.LauncherSettings

/** User-tunable parameters of the liquid glass material (see Settings). */
@Immutable
data class GlassStyle(
    val blur: Float = 4f,
    val refractionHeight: Float = 14f,
    val refractionAmount: Float = 24f,
    val surfaceAlpha: Float = 0.18f,
    val dispersion: Boolean = false,
) {
    companion object {
        fun from(s: LauncherSettings) = GlassStyle(
            s.glassBlur, s.glassRefractionHeight, s.glassRefractionAmount, s.glassSurfaceAlpha, s.glassDispersion,
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
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        vibrancy()
        if (style.blur > 0f) blur(style.blur.dp.toPx())
        lens(
            style.refractionHeight.dp.toPx(),
            style.refractionAmount.dp.toPx(),
            chromaticAberration = style.dispersion,
        )
    },
    highlight = if (edges) ({ Highlight.Default }) else null,
    shadow = if (edges) ({ Shadow.Default }) else null,
    layerBlock = layerBlock,
    onDrawSurface = {
        if (tint != Color.Unspecified) drawRect(tint)
        drawRect(Color.White.copy(alpha = surfaceAlpha))
    },
)

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
            .liquidGlass(backdrop, style, RoundedCornerShape(50), surfaceAlpha = style.surfaceAlpha + 0.06f)
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
        modifier.liquidGlass(backdrop, style, CircleShape, surfaceAlpha = style.surfaceAlpha + 0.06f).pressScale(onClick),
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
