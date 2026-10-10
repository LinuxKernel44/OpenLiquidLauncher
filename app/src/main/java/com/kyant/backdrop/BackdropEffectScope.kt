package com.kyant.backdrop

import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * OpenLiquidLauncher addition: global render-resolution scale of the glass (1 = native). The glass is computed in a layer
 * of `size * scale` and drawn back at full size, so a lower value is cheaper (and slightly softer) and a higher value
 * is a supersampled, smoother edge. Read inside draw, so changing it redraws every glass surface.
 */
object BackdropRendering {
    var scale by androidx.compose.runtime.mutableFloatStateOf(1f)
}

sealed interface BackdropEffectScope : Density, RuntimeShaderCache {

    val size: Size

    val layoutDirection: LayoutDirection

    val shape: Shape

    var padding: Float

    var renderEffect: RenderEffect?
}

internal abstract class BackdropEffectScopeImpl : BackdropEffectScope, RuntimeShaderCache {

    override var density: Float = 1f
    override var fontScale: Float = 1f
    override var size: Size = Size.Unspecified
    override var layoutDirection: LayoutDirection = LayoutDirection.Ltr
    override var padding: Float = 0f
    /** Scale this scope was last updated with (effects work in `scale`-scaled pixels). */
    var renderScale: Float = 1f
    override var renderEffect: RenderEffect? = null

    private val runtimeShaderCache = RuntimeShaderCacheImpl()

    override fun obtainRuntimeShader(key: String, string: String): RuntimeShader {
        return runtimeShaderCache.obtainRuntimeShader(key, string)
    }

    fun update(scope: DrawScope): Boolean {
        val s = BackdropRendering.scale.coerceIn(0.1f, 3f)
        // Effects see a smaller (or bigger) world: dp -> px conversions and the size are both scaled.
        val newDensity = scope.density * s
        val newFontScale = scope.fontScale
        val newSize = Size(scope.size.width * s, scope.size.height * s)
        val newLayoutDirection = scope.layoutDirection

        val changed = newDensity != density ||
                newFontScale != fontScale ||
                newSize != size ||
                s != renderScale ||
                newLayoutDirection != layoutDirection

        if (changed) {
            renderScale = s
            density = newDensity
            fontScale = newFontScale
            size = newSize
            layoutDirection = newLayoutDirection
        }

        return changed
    }

    fun apply(effects: BackdropEffectScope.() -> Unit) {
        padding = 0f
        renderEffect = null
        effects()
    }

    fun reset() {
        density = 1f
        fontScale = 1f
        size = Size.Unspecified
        layoutDirection = LayoutDirection.Ltr
        padding = 0f
        renderEffect = null
        runtimeShaderCache.clear()
    }
}
