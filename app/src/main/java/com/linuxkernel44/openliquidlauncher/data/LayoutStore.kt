package com.linuxkernel44.openliquidlauncher.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Home screen model: paged grid workspace + dock. Mutations are persisted as JSON.
 * All coordinates are in grid cells.
 */
class LayoutStore(private val context: Context, private val settings: SettingsStore) {
    private val file = File(context.filesDir, "layout.json")

    val placed = mutableStateListOf<Placed>()
    val dock = mutableStateListOf<Item?>()
    var pages by mutableStateOf(1)
        private set
    var restored = false
        private set
    fun markInitialized() { restored = true }

    private var nextId = 1L
    fun newId(): Long = nextId++

    val columns get() = settings.current.columns
    val rows get() = settings.current.rows

    fun load(): Boolean {
        if (!file.exists()) return false
        return runCatching {
            val o = JSONObject(file.readText())
            nextId = o.optLong("nextId", 1L)
            val list = o.getJSONArray("placed")
            placed.clear()
            for (i in 0 until list.length()) {
                val p = list.getJSONObject(i)
                placed += Placed(readItem(p.getJSONObject("item")), p.getInt("page"), p.getInt("x"), p.getInt("y"))
            }
            val d = o.getJSONArray("dock")
            dock.clear()
            for (i in 0 until d.length()) dock += d.optJSONObject(i)?.let(::readItem)
            pages = o.optInt("pages", 1).coerceAtLeast(1)
            restored = true
            true
        }.getOrDefault(false)
    }

    fun ensureDockSize() {
        val n = settings.current.dockSize
        while (dock.size < n) dock.add(null)
        while (dock.size > n) {
            val last = dock.removeAt(dock.lastIndex)
            if (last != null) addToFirstFree(last)
        }
    }

    fun save() {
        val o = JSONObject()
        o.put("nextId", nextId)
        o.put("pages", pages)
        o.put("placed", JSONArray().apply {
            placed.forEach {
                put(JSONObject().put("page", it.page).put("x", it.x).put("y", it.y).put("item", writeItem(it.item)))
            }
        })
        o.put("dock", JSONArray().apply { dock.forEach { put(it?.let(::writeItem) ?: JSONObject.NULL) } })
        runCatching { file.writeText(o.toString()) }
    }

    private fun readItem(o: JSONObject): Item = when (o.getString("t")) {
        "app" -> AppItem(o.getLong("id"), o.getString("app"))
        "folder" -> FolderItem(
            o.getLong("id"), o.optString("name", "Folder"),
            o.getJSONArray("apps").let { a -> List(a.length()) { a.getString(it) } },
        )
        else -> WidgetItem(o.getLong("id"), o.getInt("wid"), o.getInt("sx"), o.getInt("sy"))
    }

    private fun writeItem(i: Item): JSONObject = when (i) {
        is AppItem -> JSONObject().put("t", "app").put("id", i.id).put("app", i.appId)
        is FolderItem -> JSONObject().put("t", "folder").put("id", i.id).put("name", i.name)
            .put("apps", JSONArray(i.appIds))
        is WidgetItem -> JSONObject().put("t", "widget").put("id", i.id).put("wid", i.widgetId)
            .put("sx", i.spanX).put("sy", i.spanY)
    }

    // ---- queries -------------------------------------------------------------------------------

    fun placedAt(page: Int, x: Int, y: Int, ignoreId: Long = -1): Placed? =
        placed.firstOrNull { it.page == page && it.item.id != ignoreId && it.covers(x, y) }

    fun isFree(page: Int, x: Int, y: Int, sx: Int, sy: Int, ignoreId: Long = -1): Boolean {
        if (x < 0 || y < 0 || x + sx > columns || y + sy > rows) return false
        for (cx in x until x + sx) for (cy in y until y + sy) if (placedAt(page, cx, cy, ignoreId) != null) return false
        return true
    }

    fun findItem(id: Long): Item? =
        placed.firstOrNull { it.item.id == id }?.item ?: dock.firstOrNull { it?.id == id }

    fun allAppIdsOnHome(): Set<String> {
        val ids = HashSet<String>()
        fun add(i: Item?) { when (i) { is AppItem -> ids += i.appId; is FolderItem -> ids += i.appIds; else -> {} } }
        placed.forEach { add(it.item) }; dock.forEach(::add)
        return ids
    }

    // ---- mutations -----------------------------------------------------------------------------

    private fun detach(id: Long) {
        placed.removeAll { it.item.id == id }
        for (i in dock.indices) if (dock[i]?.id == id) dock[i] = null
    }

    fun placeOnWorkspace(item: Item, page: Int, x: Int, y: Int) {
        detach(item.id)
        placed += Placed(item, page, x, y)
        pages = maxOf(pages, page + 1)
        changed()
    }

    /**
     * Inserts [item] at a cell and pushes what was there forward (row-major) until a free cell absorbs the chain.
     * Widgets stay put; the chain overflows onto the next page when a page is full.
     */
    fun insertPushing(item: Item, page: Int, x: Int, y: Int) {
        detach(item.id)
        val cols = columns; val perPage = cols * rows
        var pg = page; var pos = y * cols + x
        var carry: Item? = item
        while (carry != null) {
            if (pos >= perPage) { pg++; pos = 0; if (pg > 20) break }
            val cx = pos % cols; val cy = pos / cols
            val occ = placedAt(pg, cx, cy)
            if (occ != null && (occ.spanX > 1 || occ.spanY > 1)) { pos++; continue }
            if (occ != null) placed.remove(occ)
            placed += Placed(carry, pg, cx, cy)
            carry = occ?.item
            pos++
        }
        pages = maxOf(pages, pg + 1)
        changed()
    }

    /** Where the icons of [page] would end up if [itemId] were dropped on (x, y): used for the live "make room" animation. */
    fun previewPush(itemId: Long, page: Int, x: Int, y: Int): Map<Long, Pair<Int, Int>> {
        val cols = columns; val perPage = cols * rows
        val cells = HashMap<Int, Placed>()                       // pos -> placed (1x1 and widgets)
        val blocked = HashSet<Int>()
        for (p in placed) if (p.page == page && p.item.id != itemId) {
            if (p.spanX > 1 || p.spanY > 1) { for (cx in p.x until p.x + p.spanX) for (cy in p.y until p.y + p.spanY) blocked += cy * cols + cx }
            else cells[p.y * cols + p.x] = p
        }
        val result = LinkedHashMap<Long, Pair<Int, Int>>()
        var carry: Long? = itemId; var pos = y * cols + x
        while (carry != null) {
            if (pos >= perPage) return emptyMap()                // would overflow to the next page: no preview
            if (pos in blocked) { pos++; continue }
            val occ = cells[pos]
            if (carry != itemId) result[carry] = (pos % cols) to (pos / cols)
            carry = occ?.item?.id
            pos++
        }
        return result
    }

    /** Removes the home-screen shortcuts (not folder members) of [appId]. */
    fun removeAppItems(appId: String) {
        placed.removeAll { (it.item as? AppItem)?.appId == appId }
        for (i in dock.indices) if ((dock[i] as? AppItem)?.appId == appId) dock[i] = null
        changed()
    }

    /** Replaces item [oldId] with [new] in the same slot. */
    fun swapItem(oldId: Long, new: Item) {
        for (i in placed.indices) if (placed[i].item.id == oldId) placed[i] = placed[i].copy(item = new)
        for (i in dock.indices) if (dock[i]?.id == oldId) dock[i] = new
        changed()
    }

    fun folderContaining(appId: String): FolderItem? =
        (placed.map { it.item } + dock.filterNotNull()).filterIsInstance<FolderItem>().firstOrNull { appId in it.appIds }

    /** Same idea in the dock: shift icons to the right; the last one goes to the home screen if the dock is full. */
    fun insertDockPushing(item: Item, slot: Int) {
        detach(item.id)
        var carry: Item? = item; var i = slot
        while (carry != null && i < dock.size) { val occ = dock[i]; dock[i] = carry; carry = occ; i++ }
        carry?.let { addToFirstFree(it) }
        changed()
    }

    fun placeInDock(item: Item, slot: Int) {
        detach(item.id)
        if (slot in dock.indices) dock[slot] = item
        changed()
    }

    fun remove(id: Long) { detach(id); changed() }

    fun removeApp(appId: String) {
        for (p in placed.toList()) {
            val it = p.item
            if (it is AppItem && it.appId == appId) placed.remove(p)
            if (it is FolderItem && appId in it.appIds) replaceItem(it.copy(appIds = it.appIds - appId))
        }
        for (i in dock.indices) {
            val it = dock[i]
            if (it is AppItem && it.appId == appId) dock[i] = null
            if (it is FolderItem && appId in it.appIds) replaceItem(it.copy(appIds = it.appIds - appId))
        }
        changed()
    }

    fun replaceItem(newItem: Item) {
        for (i in placed.indices) if (placed[i].item.id == newItem.id) placed[i] = placed[i].copy(item = newItem)
        for (i in dock.indices) if (dock[i]?.id == newItem.id) dock[i] = newItem
        changed()
    }

    /** Merge [dragged] app into whatever is at the target (an app -> new folder, a folder -> add). */
    fun mergeInto(dragged: AppItem, targetId: Long): Boolean {
        return when (val target = findItem(targetId)) {
            is FolderItem -> {
                if (dragged.appId !in target.appIds) {
                    detach(dragged.id); replaceItem(target.copy(appIds = target.appIds + dragged.appId))
                }
                true
            }
            is AppItem -> {
                if (target.id == dragged.id) return false
                detach(dragged.id)
                val folder = FolderItem(newId(), "Folder", listOf(target.appId, dragged.appId))
                placed.firstOrNull { it.item.id == target.id }?.let {
                    placed[placed.indexOf(it)] = it.copy(item = folder)
                }
                for (i in dock.indices) if (dock[i]?.id == target.id) dock[i] = folder
                changed(); true
            }
            else -> false
        }
    }

    fun removeFromFolder(folder: FolderItem, appId: String) {
        val current = findItem(folder.id) as? FolderItem ?: return
        replaceItem(current.copy(appIds = current.appIds - appId))
    }

    /** Folders with 0/1 app are dissolved. Also drops widgets/empty pages. */
    fun normalize(validApps: Set<String>?) {
        val toReplace = ArrayList<Pair<Item, Item?>>()
        fun check(i: Item?) {
            if (i is FolderItem) {
                val apps = if (validApps != null) i.appIds.filter { it in validApps } else i.appIds
                when {
                    apps.isEmpty() -> toReplace += i to null
                    apps.size == 1 -> toReplace += i to AppItem(newId(), apps[0])
                    apps != i.appIds -> toReplace += i to i.copy(appIds = apps)
                }
            } else if (i is AppItem && validApps != null && i.appId !in validApps) toReplace += i to null
        }
        placed.forEach { check(it.item) }; dock.forEach(::check)
        for ((old, new) in toReplace) {
            val pi = placed.indexOfFirst { it.item.id == old.id }
            if (pi >= 0) { if (new == null) placed.removeAt(pi) else placed[pi] = placed[pi].copy(item = new) }
            val di = dock.indexOfFirst { it?.id == old.id }
            if (di >= 0) dock[di] = new
        }
        val used = placed.maxOfOrNull { it.page } ?: 0
        pages = maxOf(1, used + 1)
        changed()
    }

    fun addToFirstFree(item: Item, sx: Int = 1, sy: Int = 1): Boolean {
        var page = 0
        while (page < 20) {
            for (y in 0 until rows) for (x in 0 until columns)
                if (isFree(page, x, y, sx, sy)) { placeOnWorkspace(item, page, x, y); return true }
            page++
        }
        return false
    }

    /** Replaces the whole home layout (used by the importer). */
    fun replaceAll(newPlaced: List<Placed>, newDock: List<Item?>) {
        placed.clear(); placed += newPlaced
        dock.clear(); dock += newDock
        restored = true
        pages = maxOf(1, (placed.maxOfOrNull { it.page } ?: 0) + 1)
        // Anything that no longer fits the grid is re-flowed.
        val out = placed.filter { it.x + it.spanX > columns || it.y + it.spanY > rows }
        placed.removeAll(out.toSet())
        out.forEach { addToFirstFree(it.item, it.spanX.coerceAtMost(columns), it.spanY.coerceAtMost(rows)) }
        changed()
    }

    fun widgetItems(): List<WidgetItem> = placed.mapNotNull { it.item as? WidgetItem }

    fun addPage() { pages += 1 }

    /** Remove trailing empty pages (never below one). */
    fun trimPages() {
        val used = (placed.maxOfOrNull { it.page } ?: 0) + 1
        pages = maxOf(1, used)
    }

    /** After a grid size change, re-flow everything that no longer fits. */
    fun reflow() {
        val out = placed.filter { it.x + it.spanX > columns || it.y + it.spanY > rows }
        placed.removeAll(out.toSet())
        out.forEach { addToFirstFree(it.item, it.spanX.coerceAtMost(columns), it.spanY.coerceAtMost(rows)) }
        changed()
    }

    private fun changed() { trimPages(); save() }
}
