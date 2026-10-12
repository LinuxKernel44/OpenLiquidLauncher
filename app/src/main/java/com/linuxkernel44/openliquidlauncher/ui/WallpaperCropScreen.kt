package com.linuxkernel44.openliquidlauncher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap

/**
 * Pinch-to-zoom / drag-to-pan crop over the whole screen, so the exported bitmap matches exactly
 * what the glass will refract (same size as the frame, which is the real screen size in px).
 */
@Composable
fun WallpaperCropScreen(
    source: Bitmap,
    onConfirm: (Bitmap) -> Unit,
    onCancel: () -> Unit,
) {
    var frameW by remember { mutableIntStateOf(1) }
    var frameH by remember { mutableIntStateOf(1) }
    // Scale beyond the minimum "cover" scale (1f = just covers the frame, no gaps).
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    val coverScale = remember(frameW, frameH) {
        maxOf(frameW.toFloat() / source.width, frameH.toFloat() / source.height).let { if (it.isFinite() && it > 0f) it else 1f }
    }
    val displayScale = coverScale * zoom
    val imgW = source.width * displayScale
    val imgH = source.height * displayScale
    val maxPanX = maxOf(0f, (imgW - frameW) / 2f)
    val maxPanY = maxOf(0f, (imgH - frameH) / 2f)
    fun clamp(p: Offset) = Offset(p.x.coerceIn(-maxPanX, maxPanX), p.y.coerceIn(-maxPanY, maxPanY))

    val density = LocalDensity.current
    val baseWdp = with(density) { source.width.toDp() }
    val baseHdp = with(density) { source.height.toDp() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            Modifier.fillMaxSize()
                .onSizeChanged { frameW = it.width; frameH = it.height }
                .pointerInput(Unit) {
                    detectTransformGestures { _, panDelta, zoomDelta, _ ->
                        zoom = (zoom * zoomDelta).coerceIn(1f, 5f)
                        pan = clamp(pan + panDelta)
                    }
                },
        ) {
            Image(
                bitmap = source.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.requiredSize(baseWdp, baseHdp).graphicsLayer {
                    scaleX = displayScale
                    scaleY = displayScale
                    transformOrigin = TransformOrigin(0f, 0f)
                    translationX = (frameW - imgW) / 2f + pan.x
                    translationY = (frameH - imgH) / 2f + pan.y
                },
            )
        }

        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            GText("Pinch to zoom, drag to position", modifier = Modifier.fillMaxWidth(), size = 14f, color = Color.White.copy(alpha = 0.85f), align = TextAlign.Start)
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.weight(1f).background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(50)).clickable { onCancel() }.padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { GText("Cancel", size = 15f, weight = FontWeight.SemiBold) }
                Box(
                    Modifier.weight(1f).background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(50))
                        .clickable {
                            val out = createBitmap(frameW, frameH)
                            val canvas = Canvas(out)
                            val matrix = Matrix().apply {
                                setScale(displayScale, displayScale)
                                postTranslate((frameW - imgW) / 2f + pan.x, (frameH - imgH) / 2f + pan.y)
                            }
                            canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                            onConfirm(out)
                        }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { GText("Use photo", size = 15f, weight = FontWeight.Bold, color = Color.Black) }
            }
        }
    }
}
