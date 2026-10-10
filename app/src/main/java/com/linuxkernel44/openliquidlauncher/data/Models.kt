package com.linuxkernel44.openliquidlauncher.data

import android.content.ComponentName
import android.os.UserHandle
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

@Immutable
class AppInfo(
    val id: String,
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val icon: ImageBitmap,
    /** Pre-rendered "themed" variant (Android 13+ monochrome layer), if the app provides one. */
    val themedIcon: ImageBitmap?,
    /** Adaptive icon layers without the system mask (the visible 72dp window), so we can apply our own shape. Null for legacy icons. */
    val rawIcon: ImageBitmap? = null,
) {
    val packageName: String get() = component.packageName
}

sealed interface Item {
    val id: Long
}

data class AppItem(override val id: Long, val appId: String) : Item
data class FolderItem(override val id: Long, val name: String, val appIds: List<String>) : Item
data class WidgetItem(
    override val id: Long,
    val widgetId: Int,
    val spanX: Int,
    val spanY: Int,
) : Item

data class Placed(val item: Item, val page: Int, val x: Int, val y: Int) {
    val spanX: Int get() = (item as? WidgetItem)?.spanX ?: 1
    val spanY: Int get() = (item as? WidgetItem)?.spanY ?: 1
    fun covers(px: Int, py: Int) = px in x until x + spanX && py in y until y + spanY
}
