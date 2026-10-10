package com.linuxkernel44.openliquidlauncher.data

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Provides the bitmap that is drawn behind the UI (and refracted by the glass). */
class WallpaperRepository(private val context: Context, private val settings: SettingsStore) {
    private val customFile = File(context.filesDir, "wallpaper.jpg")
    private val _bitmap = MutableStateFlow<ImageBitmap?>(null)
    val bitmap: StateFlow<ImageBitmap?> = _bitmap.asStateFlow()

    private val dm = context.resources.displayMetrics
    private val targetW = dm.widthPixels.coerceAtLeast(720)
    private val targetH = dm.heightPixels.coerceAtLeast(1280)

    suspend fun refresh() {
        _bitmap.value = withContext(Dispatchers.IO) {
            val bmp = when (settings.current.wallpaperMode) {
                WallpaperMode.Custom -> loadCustom()
                WallpaperMode.System -> loadSystem()
            }
            (bmp ?: generateDefault(targetW, targetH)).asImageBitmap()
        }
    }

    val hasCustom: Boolean get() = customFile.exists()

    /** Copies the picked image into app storage; optionally also applies it as the system wallpaper. */
    suspend fun importCustom(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bmp = decodeSampled { context.contentResolver.openInputStream(uri) } ?: return@runCatching false
            customFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            if (settings.current.setSystemWallpaperToo) {
                context.contentResolver.openInputStream(uri)?.use { WallpaperManager.getInstance(context).setStream(it) }
            }
            true
        }.getOrDefault(false)
    }

    private fun loadCustom(): Bitmap? = if (customFile.exists()) decodeSampled { customFile.inputStream() } else null

    private fun loadSystem(): Bitmap? = runCatching {
        // Needs all-files access on Android 13+; falls back to the built-in wallpaper when denied.
        WallpaperManager.getInstance(context).peekDrawable()?.toBitmap()
    }.getOrNull()

    private fun decodeSampled(open: () -> java.io.InputStream?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW * 1.2f && bounds.outHeight / (sample * 2) >= targetH * 1.2f) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return open()?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /** A colorful mesh-style default so the glass has something nice to refract. */
    private fun generateDefault(w: Int, h: Int): Bitmap {
        val bmp = createBitmap(w, h)
        val c = Canvas(bmp)
        c.drawColor(Color.parseColor("#0B1030"))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun blob(cx: Float, cy: Float, r: Float, color: Int) {
            p.shader = RadialGradient(cx * w, cy * h, r * w, color, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        }
        blob(0.15f, 0.10f, 0.95f, Color.parseColor("#E6FF4D8D"))
        blob(0.90f, 0.22f, 0.85f, Color.parseColor("#E63D7BFF"))
        blob(0.20f, 0.55f, 0.80f, Color.parseColor("#E69B4DFF"))
        blob(0.85f, 0.70f, 0.90f, Color.parseColor("#E600D1B2"))
        blob(0.35f, 0.95f, 0.85f, Color.parseColor("#E6FFB347"))
        return bmp
    }
}
