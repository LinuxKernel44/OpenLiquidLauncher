package com.linuxkernel44.openliquidlauncher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File

/** A widget of the source launcher that has to be re-created (the user must confirm binding). */
data class PendingWidget(val provider: ComponentName, val page: Int, val x: Int, val y: Int, val spanX: Int, val spanY: Int)

class ImportPlan(
    val placed: List<Placed>,
    val dock: List<Item?>,
    val widgets: List<PendingWidget>,
    val columns: Int,
    val rows: Int,
    val dockSize: Int,
    val apps: Int,
    val folders: Int,
    val skipped: Int,
    val partialFolders: Int = 0,
    /** Folders whose contents exceed the preview (9); opening them in the source launcher reveals the rest. */
    val partial: List<PartialFolder> = emptyList(),
    val stockCurrentPage: Int = 0,
    val screenW: Int = 0,
    /** True when this plan came from a Samsung One UI (Honeyspace) dump rather than a Launcher3-style one. */
    val honeyspace: Boolean = false,
)

/** [itemId] is our FolderItem; [tapX]/[tapY] are screen coordinates of its icon while its [page] is the visible one. */
data class PartialFolder(val itemId: Long, val page: Int, val total: Int, val tapX: Int, val tapY: Int)

/** A launcher whose home layout we may be able to read through its content provider. */
data class LauncherSource(val packageName: String, val label: String, val authorities: List<String>)

class ImportException(message: String) : Exception(message)

/**
 * Imports a home layout from another Launcher3-style launcher (AOSP Launcher3, Pixel, OnePlus/OPlus...).
 * They all keep the layout in a `favorites` table, exposed by their LauncherProvider and stored in launcher.db.
 */
class LauncherImporter(private val context: Context, private val apps: AppRepository, private val layout: LayoutStore) {

    // ---- discovery ------------------------------------------------------------------------------

    fun findSources(): List<LauncherSource> {
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pkgs = pm.queryIntentActivities(home, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }.filter { it != context.packageName && it != "com.android.settings" }.distinct()
        return pkgs.map { pkg ->
            val authorities = runCatching {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_PROVIDERS.toLong())).providers.orEmpty()
                    .filter { p -> p.authority != null && (p.authority.endsWith(".settings") || p.name.contains("LauncherProvider")) }
                    .flatMap { it.authority.split(";") }
            }.getOrDefault(emptyList())
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            // Well-known authorities as a fallback when the provider list is hidden from us.
            val guesses = listOf("$pkg.settings", "com.android.launcher.settings", "com.android.launcher3.settings", "net.oneplus.launcher.settings")
            LauncherSource(pkg, label, (authorities + guesses).distinct())
        }.let { list ->
            // Don't probe the same authority once per launcher: the first source keeps it.
            val seen = HashSet<String>()
            list.map { src -> src.copy(authorities = src.authorities.filter { seen.add(it) }) }.filter { it.authorities.isNotEmpty() }
        }
    }

    // ---- reading --------------------------------------------------------------------------------

    private class Row(
        val id: Long, val title: String?, val intent: String?, val container: Int, val screen: Int,
        val x: Int, val y: Int, val spanX: Int, val spanY: Int, val type: Int, val widgetProvider: String?, val rank: Int,
    )

    /** Tries each authority of [source]; returns a plan or throws with a per-authority explanation. */
    fun importFromProvider(source: LauncherSource): ImportPlan {
        val errors = ArrayList<String>()
        var denied: String? = null
        for (authority in source.authorities) {
            try {
                val base = Uri.parse("content://$authority")
                val rows = context.contentResolver.query(base.buildUpon().appendPath("favorites").build(), null, null, null, null)?.use(::readRows)
                    ?: run { errors += "$authority: no such provider"; null }
                if (rows == null) continue
                val screens = runCatching {
                    context.contentResolver.query(base.buildUpon().appendPath("workspaceScreens").build(), null, null, null, null)?.use(::readScreens)
                }.getOrNull()
                return buildPlan(rows, screens)
            } catch (e: SecurityException) {
                errors += "$authority: access denied"
                if (denied == null) denied = authority
            } catch (e: ImportException) {
                errors += "$authority: ${e.message}"
            } catch (e: Exception) {
                errors += "$authority: ${e.javaClass.simpleName} ${e.message?.take(80)}"
            }
        }
        throw ImportException(
            if (errors.any { !it.endsWith("no such provider") && !it.endsWith("access denied") }) errors.filterNot { it.endsWith("no such provider") }.joinToString("\n")
            else if (denied != null) "${source.label} doesn't let other apps read its layout (access denied on $denied)."
            else "No readable layout found in ${source.label}.",
        )
    }

    fun importFromDatabase(file: File): ImportPlan {
        val db = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            throw ImportException("That file is not a readable SQLite database.")
        }
        db.use {
            val rows = try {
                it.rawQuery("SELECT * FROM favorites", null).use(::readRows)
            } catch (e: Exception) {
                throw ImportException("No \"favorites\" table in that database: is it a launcher.db?")
            }
            val screens = runCatching { it.rawQuery("SELECT * FROM workspaceScreens", null).use(::readScreens) }.getOrNull()
            return buildPlan(rows, screens)
        }
    }

    private fun readRows(c: Cursor): List<Row> {
        // Column names differ slightly between forks (cellX / cell_x / CELLX...): compare case- and underscore-insensitively.
        val names = c.columnNames
        fun norm(n: String) = n.lowercase().replace("_", "")
        val byNorm = names.withIndex().associate { norm(it.value) to it.index }
        fun col(vararg alts: String): Int = alts.firstNotNullOfOrNull { byNorm[norm(it)] } ?: -1
        val idI = col("_id", "id"); val titleI = col("title", "name", "label"); val intentI = col("intent", "intentUri")
        val contI = col("container"); val screenI = col("screen", "screenId")
        val xI = col("cellX", "x"); val yI = col("cellY", "y"); val sxI = col("spanX"); val syI = col("spanY")
        val typeI = col("itemType", "type"); val provI = col("appWidgetProvider", "widgetProvider"); val rankI = col("rank")
        if (idI < 0 || contI < 0 || typeI < 0) {
            throw ImportException("Unexpected columns: " + names.joinToString(", ").take(400))
        }
        fun int(i: Int, d: Int = 0) = if (i >= 0 && !c.isNull(i)) runCatching { c.getInt(i) }.getOrDefault(d) else d
        fun str(i: Int) = if (i >= 0 && !c.isNull(i)) runCatching { c.getString(i) }.getOrNull() else null
        val out = ArrayList<Row>()
        while (c.moveToNext()) out += Row(
            c.getLong(idI), str(titleI), str(intentI), int(contI), int(screenI), int(xI), int(yI),
            int(sxI, 1).coerceAtLeast(1), int(syI, 1).coerceAtLeast(1), int(typeI), str(provI), int(rankI),
        )
        if (out.isEmpty()) throw ImportException("The layout table is empty (${names.size} columns).")
        return out
    }

    /** screen id -> page index, ordered by screenRank when available. */
    private fun readScreens(c: Cursor): Map<Int, Int> {
        val idI = c.getColumnIndex("_id"); val rankI = c.getColumnIndex("screenRank")
        if (idI < 0) return emptyMap()
        val list = ArrayList<Pair<Int, Int>>()
        while (c.moveToNext()) list += c.getInt(idI) to (if (rankI >= 0) c.getInt(rankI) else c.getInt(idI))
        return list.sortedBy { it.second }.mapIndexed { i, p -> p.first to i }.toMap()
    }

    // ---- dumpsys-based import ---------------------------------------------------------------------

    /** Component of [pkg]'s HOME activity, e.g. com.android.launcher/.Launcher. */
    fun homeActivityOf(pkg: String): ComponentName? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg)
        return context.packageManager.queryIntentActivities(home, PackageManager.MATCH_ALL).firstOrNull()
            ?.let { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
    }

    /**
     * Runs `dumpsys activity <launcher>`: through Shizuku when [useShizuku] (shell rights), otherwise directly, which
     * needs `adb shell pm grant <this app> android.permission.DUMP` once.
     */
    fun dumpLauncher(component: ComponentName, useShizuku: Boolean = false): String {
        val args = arrayOf("dumpsys", "activity", component.flattenToShortString())
        val text = try {
            if (useShizuku) ShizukuBridge.run(*args)
            else {
                val proc = ProcessBuilder(*args).redirectErrorStream(true).start()
                proc.inputStream.bufferedReader().readText().also { proc.waitFor() }
            }
        } catch (e: Exception) {
            throw ImportException("Could not run dumpsys${if (useShizuku) " through Shizuku" else ""}: ${e.cause?.message ?: e.message}")
        }
        runCatching { File(context.filesDir, "last_dump.txt").writeText(text) } // for diagnosing failed imports
        if (text.contains("android.permission.DUMP") && text.length < 2000) {
            throw ImportException(
                "Android must allow this app to read the launcher's state: use \"Import using Shizuku\", or run once on a computer:\n" +
                    "adb shell pm grant ${context.packageName} android.permission.DUMP",
            )
        }
        return text
    }

    /** Samsung One UI's launcher ("Honeyspace") uses its own view hierarchy, not Launcher3's. */
    fun hasHoneyspaceLayout(dump: String) = dump.contains("app:id/wsCellLayout") && dump.contains("workspace_fast_recycler_view")

    fun hasLayout(dump: String) = (dump.contains("Workspace{") && dump.contains("CellLayout{")) || hasHoneyspaceLayout(dump)

    /** Dispatches to the Launcher3 or the One UI parser depending on which hierarchy the dump contains. */
    fun importFromDump(dump: String): ImportPlan =
        if (hasHoneyspaceLayout(dump)) importFromHoneyspaceDump(dump) else importFromLauncher3Dump(dump)

    /**
     * Rebuilds the home screen from the view hierarchy that Launcher3-based launchers (incl. OxygenOS) print in
     * `dumpsys activity`. Pages are the CellLayouts under the Workspace; positions come from view bounds / cell size.
     */
    private fun importFromLauncher3Dump(dump: String): ImportPlan {
        if (!hasLayout(dump)) throw ImportException("That text doesn't contain a launcher workspace. Open the launcher's home screen and dump it again.")
        val lines = dump.split('\n')
        fun indentOf(l: String) = l.length - l.trimStart().length
        val boundsRe = Regex("""\s(-?\d+),(-?\d+)-(-?\d+),(-?\d+)\s""")
        val cellCfg = Regex("""cellRow=(\d+),\s*cellCol=(\d+),\s*cellWidth=([\d.]+),\s*cellHeight=([\d.]+)""").find(dump)
        val gridRows = cellCfg?.groupValues?.get(1)?.toInt()
        val gridCols = cellCfg?.groupValues?.get(2)?.toInt()
        var cellW = cellCfg?.groupValues?.get(3)?.toFloat() ?: 0f
        var cellH = cellCfg?.groupValues?.get(4)?.toFloat() ?: 0f

        class Raw(val page: Int, val x: Int, val y: Int, val sx: Int, val sy: Int, val line: String, val tapX: Int = 0, val tapY: Int = 0)
        val desktop = ArrayList<Raw>()
        val hotseat = ArrayList<Raw>()
        var section: String? = null; var sectionIndent = -1; var page = -1; var pageIndent = -1; var hotseatSlotW = 0
        var clTop = 0; var sawcL = 0; var sawcT = 0; var screenW = 0
        val pageLefts = ArrayList<Int>()

        // First pass: find cell size if the config line is absent (smallest icon bounds).
        if (cellW <= 0f) {
            lines.filter { "Tag={WorkspaceItemInfo(" in it }.mapNotNull { boundsRe.find(it) }.firstOrNull()?.let {
                cellW = (it.groupValues[3].toInt() - it.groupValues[1].toInt()).toFloat()
                cellH = (it.groupValues[4].toInt() - it.groupValues[2].toInt()).toFloat()
            }
        }
        if (cellW <= 0f || cellH <= 0f) throw ImportException("Could not work out the launcher's grid size from the dump.")

        for (raw in lines) {
            if (raw.isBlank()) continue
            val ind = indentOf(raw); val l = raw.trimStart()
            if (section != null && ind <= sectionIndent) section = null
            when {
                l.contains("Workspace{") && l.contains("app:id/workspace") -> {
                    section = "workspace"; sectionIndent = ind; page = -1; pageIndent = ind + 2
                    boundsRe.find(l)?.let { screenW = it.groupValues[3].toInt() - it.groupValues[1].toInt() }
                    continue
                }
                l.contains("Hotseat{") && l.contains("app:id/hotseat") -> { section = "hotseat"; sectionIndent = ind; continue }
            }
            if (section == "workspace" && ind == pageIndent && l.contains("CellLayout{")) {
                page++
                boundsRe.find(l)?.let { clTop = it.groupValues[2].toInt(); pageLefts += it.groupValues[1].toInt() }
                continue
            }
            if (section == "workspace" && ind == pageIndent + 2 && l.contains("ShortcutAndWidgetContainer{")) {
                boundsRe.find(l)?.let { sawcL = it.groupValues[1].toInt(); sawcT = it.groupValues[2].toInt() }
                continue
            }
            if (section == null) continue
            val isApp = "Tag={WorkspaceItemInfo(" in l && l.contains("BubbleTextView{")
            val isFolder = l.contains("FolderIcon{") && l.contains("preViewItem")
            val isWidget = l.contains("AppWidgetHostView{") && l.contains("componentName=ComponentInfo{")
            if (!isApp && !isFolder && !isWidget) continue
            val b = boundsRe.find(l) ?: continue
            val (L, T, R, B) = b.destructured
            val w = R.toInt() - L.toInt(); val h = B.toInt() - T.toInt()
            if (section == "hotseat") {
                if (hotseatSlotW == 0) hotseatSlotW = w
                hotseat += Raw(0, Math.round(L.toInt().toFloat() / hotseatSlotW), 0, 1, 1, l)
            } else if (page >= 0) {
                desktop += Raw(
                    page, Math.round(L.toInt() / cellW), Math.round(T.toInt() / cellH),
                    Math.round(w / cellW).coerceAtLeast(1), Math.round(h / cellH).coerceAtLeast(1), l,
                    tapX = sawcL + (L.toInt() + R.toInt()) / 2, tapY = clTop + sawcT + (T.toInt() + B.toInt()) / 2,
                )
            }
        }
        if (desktop.isEmpty() && hotseat.isEmpty()) throw ImportException("No icons were found in the launcher dump.")

        val titleRe = Regex("""title=(.*?)\s+hashCode=""")
        val previewRe = Regex("""\[title, hide\] = \[(.*?), (?:true|false)\]""")
        val countRe = Regex("""count = (\d+)""")
        var appCount = 0; var folderCount = 0; var skipped = 0
        val widgets = ArrayList<PendingWidget>()
        var partialFolders = 0
        val partial = ArrayList<PartialFolder>()

        fun appByTitle(t: String): AppInfo? = apps.apps.value.firstOrNull { it.label.equals(t.trim(), ignoreCase = true) }
        fun toItem(r: Raw): Item? {
            val l = r.line
            return when {
                "Tag={WorkspaceItemInfo(" in l -> {
                    val cn = Regex("""targetComponent=ComponentInfo\{([^}]*)\}""").find(l)?.groupValues?.get(1)?.let(ComponentName::unflattenFromString)
                    val app = cn?.let { appFor("#Intent;component=${it.flattenToString()};end") }
                        ?: titleRe.find(l)?.groupValues?.get(1)?.let(::appByTitle)
                    if (app != null) { appCount++; AppItem(layout.newId(), app.id) } else { skipped++; null }
                }
                "preViewItem" in l -> {
                    val name = Regex("""title = \{ (.*?), \(cellX""").find(l)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: "Folder"
                    val total = countRe.find(l.substringAfter("BF-toString"))?.groupValues?.get(1)?.toInt() ?: 0
                    val titles = previewRe.findAll(l.substringAfter("preViewItem")).map { it.groupValues[1] }.toList()
                    val ids = titles.mapNotNull { appByTitle(it)?.id }.distinct()
                    if (total > titles.size) partialFolders++
                    when {
                        ids.isEmpty() -> { skipped++; null }
                        ids.size == 1 -> { appCount++; AppItem(layout.newId(), ids[0]) }
                        else -> {
                            folderCount++; appCount += ids.size
                            FolderItem(layout.newId(), name, ids).also { f ->
                                if (total > titles.size && r.tapX > 0) partial += PartialFolder(f.id, r.page, total, r.tapX, r.tapY)
                            }
                        }
                    }
                }
                else -> null
            }
        }

        val placed = ArrayList<Placed>()
        for (r in desktop.sortedWith(compareBy({ it.page }, { it.y }, { it.x }))) {
            if ("componentName=ComponentInfo{" in r.line && "AppWidgetHostView{" in r.line) {
                val cn = Regex("""componentName=ComponentInfo\{([^}]*)\}""").find(r.line)?.groupValues?.get(1)?.let(ComponentName::unflattenFromString)
                val spanX = Regex("""spanX=(\d+)""").find(r.line)?.groupValues?.get(1)?.toInt() ?: r.sx
                val spanY = Regex("""spanY=(\d+)""").find(r.line)?.groupValues?.get(1)?.toInt() ?: r.sy
                if (cn != null) widgets += PendingWidget(cn, r.page, r.x, r.y, spanX, spanY) else skipped++
            } else toItem(r)?.let { placed += Placed(it, r.page, r.x, r.y) }
        }

        val dockSize = (if (hotseat.isEmpty()) 5 else maxOf(hotseat.maxOf { it.x } + 1, 5)).coerceIn(3, 6)
        val dock = arrayOfNulls<Item>(dockSize).toMutableList()
        for (r in hotseat.sortedBy { it.x }) { val slot = r.x.coerceIn(0, dockSize - 1); if (dock[slot] == null) dock[slot] = toItem(r) }

        if (appCount == 0) throw ImportException("Found the layout, but none of its apps are installed here.")
        return ImportPlan(
            placed, dock, widgets,
            (gridCols ?: (desktop.maxOf { it.x + it.sx })).coerceIn(4, 6),
            (gridRows ?: (desktop.maxOf { it.y + it.sy })).coerceIn(4, 7),
            dockSize, appCount, folderCount, skipped + 0, partialFolders,
            partial = partial,
            stockCurrentPage = pageLefts.withIndex().minByOrNull { Math.abs(it.value) }?.index ?: 0,
            screenW = screenW,
        )
    }

    /**
     * Rebuilds the home screen from a Samsung One UI ("Honeyspace") `dumpsys activity` dump. Unlike Launcher3,
     * each icon already reports its own cell (x=, y=) so there's no grid math, but folders never list their
     * members inline - they're filled in afterward by [completeHoneyspaceFolders] (tap, dump, read, close).
     */
    private fun importFromHoneyspaceDump(dump: String): ImportPlan {
        val lines = dump.split('\n')
        fun indentOf(l: String) = l.length - l.trimStart().length
        val boundsRe = Regex("""\s(-?\d+),(-?\d+)-(-?\d+),(-?\d+)\s""")
        val labelRe = Regex("""\}\s*-\s*(.+?),\s*visibility:""")
        val xyRe = Regex("""x=(\d+),\s*y=(\d+)""")

        class Raw(val page: Int, val x: Int, val y: Int, val isFolder: Boolean, val label: String, val tapX: Int, val tapY: Int)
        class HRaw(val x: Int, val label: String)
        val desktop = ArrayList<Raw>()
        val hotseat = ArrayList<HRaw>()

        var inWorkspace = false; var wsIndent = -1
        var page = -1; var containerLeft = 0; var containerTop = 0
        var cellIndent = -1; var cellLocalLeft = 0; var cellLocalTop = 0
        var inHotseatCell = false; var screenW = 0

        for (raw in lines) {
            if (raw.isBlank()) continue
            val ind = indentOf(raw); val l = raw.trimStart()
            if (l.contains("WorkspaceFastRecyclerView{") && l.contains("workspace_fast_recycler_view")) {
                inWorkspace = true; wsIndent = ind; page = -1
                boundsRe.find(l)?.let { screenW = it.groupValues[3].toInt() - it.groupValues[1].toInt() }
                continue
            }
            if (inWorkspace && ind <= wsIndent) inWorkspace = false

            if (l.contains("HotseatCellLayout{") && l.contains("hotseat_layout")) { inHotseatCell = true; cellIndent = ind; continue }
            if (inHotseatCell && ind <= cellIndent) inHotseatCell = false
            if (inHotseatCell) {
                val xy = xyRe.find(l) ?: continue
                val label = labelRe.find(l)?.groupValues?.get(1)?.trim() ?: continue
                hotseat += HRaw(xy.groupValues[1].toInt(), label)
                continue
            }

            if (!inWorkspace) continue
            if (l.contains("WorkspaceCellLayoutContainer{")) {
                page++
                boundsRe.find(l)?.let { containerLeft = it.groupValues[1].toInt(); containerTop = it.groupValues[2].toInt() }
                continue
            }
            if (page < 0) continue
            if (l.contains("WorkspaceCellLayout{") && l.contains("wsCellLayout")) {
                cellIndent = ind
                boundsRe.find(l)?.let { cellLocalLeft = it.groupValues[1].toInt(); cellLocalTop = it.groupValues[2].toInt() }
                continue
            }
            if (cellIndent < 0 || ind <= cellIndent) continue
            val isFolder = l.contains("FolderIconView{")
            if (!isFolder && !l.contains("IconView{")) continue
            val xy = xyRe.find(l) ?: continue
            val label = labelRe.find(l)?.groupValues?.get(1)?.trim() ?: continue
            val b = boundsRe.find(l) ?: continue
            val (L, T, R, B) = b.destructured
            desktop += Raw(
                page, xy.groupValues[1].toInt(), xy.groupValues[2].toInt(), isFolder, label,
                tapX = containerLeft + cellLocalLeft + (L.toInt() + R.toInt()) / 2,
                tapY = containerTop + cellLocalTop + (T.toInt() + B.toInt()) / 2,
            )
        }
        if (desktop.isEmpty() && hotseat.isEmpty()) throw ImportException("No icons were found in the launcher dump.")

        fun appByTitle(t: String): AppInfo? = apps.apps.value.firstOrNull { it.label.equals(t.trim(), ignoreCase = true) }
        var appCount = 0; var folderCount = 0; var skipped = 0
        val partial = ArrayList<PartialFolder>()
        fun toItem(r: Raw): Item? = if (r.isFolder) {
            folderCount++
            FolderItem(layout.newId(), r.label.ifBlank { "Folder" }, emptyList())
        } else appByTitle(r.label)?.let { appCount++; AppItem(layout.newId(), it.id) } ?: run { skipped++; null }

        val placed = ArrayList<Placed>()
        for (r in desktop.sortedWith(compareBy({ it.page }, { it.y }, { it.x }))) {
            val item = toItem(r) ?: continue
            placed += Placed(item, r.page, r.x, r.y)
            if (item is FolderItem) partial += PartialFolder(item.id, r.page, 0, r.tapX, r.tapY)
        }

        val dockSize = (if (hotseat.isEmpty()) 5 else maxOf(hotseat.maxOf { it.x } + 1, 5)).coerceIn(3, 6)
        val dock = arrayOfNulls<Item>(dockSize).toMutableList()
        for (r in hotseat.sortedBy { it.x }) {
            val slot = r.x.coerceIn(0, dockSize - 1)
            if (dock[slot] == null) dock[slot] = appByTitle(r.label)?.let { appCount++; AppItem(layout.newId(), it.id) }
        }

        if (appCount == 0 && partial.isEmpty()) throw ImportException("Found the layout, but none of its apps are installed here.")
        return ImportPlan(
            placed, dock, emptyList(),
            (desktop.maxOfOrNull { it.x + 1 } ?: 5).coerceIn(4, 8),
            (desktop.maxOfOrNull { it.y + 1 } ?: 5).coerceIn(4, 8),
            dockSize, appCount, folderCount, skipped, partialFolders = partial.size,
            partial = partial, screenW = screenW, honeyspace = true,
        )
    }

    /**
     * One UI folders never list their members in the dump: tap each one open (it must already be on the visible
     * page), read its `OpenFolderCellLayout`, then close it. [sh] runs a shell command (Shizuku), [dump] re-dumps.
     */
    suspend fun completeHoneyspaceFolders(plan: ImportPlan, sh: (Array<String>) -> String, dump: () -> String): ImportPlan {
        if (plan.partial.isEmpty()) return plan
        val memberRe = Regex("""IconView\{[^\n}]*\}\s*-\s*(.+?),\s*visibility:""")
        fun appByTitle(t: String): AppInfo? = apps.apps.value.firstOrNull { it.label.equals(t.trim(), ignoreCase = true) }
        val updated = HashMap<Long, List<String>>()
        for (pf in plan.partial) {
            sh(arrayOf("input", "tap", pf.tapX.toString(), pf.tapY.toString()))
            kotlinx.coroutines.delay(900)
            val text = dump()
            val folderSection = text.substringAfter("OpenFolderCellLayout{", "").substringBefore("PageIndicatorView")
            val ids = memberRe.findAll(folderSection).map { it.groupValues[1].trim() }.mapNotNull { appByTitle(it)?.id }.distinct().toList()
            sh(arrayOf("input", "keyevent", "KEYCODE_BACK"))
            kotlinx.coroutines.delay(600)
            if (ids.size > 1) updated[pf.itemId] = ids
        }
        if (updated.isEmpty()) return plan
        fun fix(i: Item?): Item? = if (i is FolderItem && updated.containsKey(i.id)) i.copy(appIds = updated.getValue(i.id)) else i
        return ImportPlan(
            plan.placed.map { it.copy(item = fix(it.item)!!) }, plan.dock.map(::fix), plan.widgets, plan.columns, plan.rows, plan.dockSize,
            plan.apps + updated.values.sumOf { it.size }, plan.folders, plan.skipped, plan.partialFolders - updated.size,
            honeyspace = true,
        )
    }

    /**
     * The dump only lists the first 9 apps of a folder. For bigger ones, open the folder in the (visible) source launcher
     * with simulated taps, dump again to read every member, then close it. [sh] runs a shell command (Shizuku).
     */
    suspend fun completeFolders(
        plan: ImportPlan, sh: (Array<String>) -> String, dump: () -> String,
    ): ImportPlan {
        if (plan.honeyspace) return completeHoneyspaceFolders(plan, sh, dump)
        if (plan.partial.isEmpty() || plan.screenW <= 0) return plan
        val updated = HashMap<Long, List<String>>()
        val memberRe = Regex("""WorkspaceItemInfo\(id=(\d+) type=\w+ container=(\d+) targetComponent=ComponentInfo\{([^}]*)\}""")
        val w = plan.screenW
        /**
         * Workspace page currently on screen. CellLayout bounds don't move while scrolling, but every icon reports its
         * real on-screen x (`LocationX`): the page whose icons sit closest to where they'd be unscrolled is the visible one.
         */
        fun visiblePage(text: String): Int {
            val offsets = HashMap<Int, Int>()
            var inWs = false; var page = -1
            val bounds = Regex("""\s(-?\d+),-?\d+-""")
            for (l in text.lineSequence()) {
                if (l.contains("app:id/workspace") && l.contains("Workspace{")) { inWs = true; continue }
                if (!inWs) continue
                if (l.contains("Hotseat{")) break
                val t = l.trimStart()
                if (t.startsWith("com.android.launcher3.") && t.contains("CellLayout{")) { page++; continue }
                if (page >= 0 && !offsets.containsKey(page) && "Tag={WorkspaceItemInfo(" in l) {
                    val left = bounds.find(l)?.groupValues?.get(1)?.toIntOrNull()
                    val locX = Regex("""LocationX=(-?\d+)""").find(l)?.groupValues?.get(1)?.toIntOrNull()
                    if (left != null && locX != null) offsets[page] = locX - left
                }
            }
            return offsets.minByOrNull { Math.abs(it.value - 36) }?.key ?: 0
        }
        var snapshot = dump()
        for (pf in plan.partial.sortedBy { it.page }) {
            var guard = 0
            var cur = visiblePage(snapshot)
            while (cur != pf.page && guard++ < 14) {
                val next = pf.page > cur
                val x1 = (if (next) w * 0.8f else w * 0.2f).toInt(); val x2 = (if (next) w * 0.2f else w * 0.8f).toInt()
                sh(arrayOf("input", "swipe", x1.toString(), "1500", x2.toString(), "1500", "180"))
                kotlinx.coroutines.delay(800)
                snapshot = dump(); cur = visiblePage(snapshot)
            }
            var ids: List<String> = emptyList()
            for (attempt in 0 until 3) {
                sh(arrayOf("input", "tap", pf.tapX.toString(), pf.tapY.toString()))
                kotlinx.coroutines.delay(1100)
                val text = dump()
                ids = memberRe.findAll(text).filter { it.groupValues[2].toLongOrNull()?.let { c -> c > 0 } == true }
                    .mapNotNull { m -> ComponentName.unflattenFromString(m.groupValues[3])?.let { cn -> appFor("#Intent;component=${cn.flattenToString()};end")?.id } }
                    .distinct().toList()
                android.util.Log.d("OLLimp", "folder ${pf.itemId} page ${pf.page} (visible $cur) tap ${pf.tapX},${pf.tapY} attempt $attempt -> ${ids.size} apps")
                sh(arrayOf("input", "keyevent", "KEYCODE_BACK"))
                kotlinx.coroutines.delay(700)
                if (ids.size > 1) break
            }
            snapshot = dump()
            if (ids.size > 1) updated[pf.itemId] = ids
        }
        if (updated.isEmpty()) return plan
        fun fix(i: Item?): Item? = if (i is FolderItem && updated.containsKey(i.id)) i.copy(appIds = updated.getValue(i.id)) else i
        return ImportPlan(
            plan.placed.map { it.copy(item = fix(it.item)!!) }, plan.dock.map(::fix), plan.widgets, plan.columns, plan.rows, plan.dockSize,
            plan.apps + updated.entries.sumOf { (id, ids) -> ids.size - ((plan.placed.firstOrNull { it.item.id == id }?.item as? FolderItem)?.appIds?.size ?: 0) },
            plan.folders, plan.skipped, plan.partialFolders - updated.size,
        )
    }

    // ---- mapping --------------------------------------------------------------------------------

    private fun componentOf(intentUri: String?): ComponentName? =
        runCatching { Intent.parseUri(intentUri ?: return null, 0).component }.getOrNull()

    private fun appFor(intentUri: String?): AppInfo? {
        val cn = componentOf(intentUri) ?: return null
        val all = apps.apps.value
        return all.firstOrNull { it.component == cn && it.user == apps.myUser() }
            ?: all.firstOrNull { it.component == cn }
            ?: all.firstOrNull { it.packageName == cn.packageName }
    }

    private fun buildPlan(rows: List<Row>, screens: Map<Int, Int>?): ImportPlan {
        val desktop = rows.filter { it.container == -100 }
        val hotseat = rows.filter { it.container == -101 }
        val folderMembers = rows.filter { it.container > 0 }.groupBy { it.container }
        val pageOf: Map<Int, Int> = screens?.takeIf { it.isNotEmpty() }
            ?: desktop.map { it.screen }.distinct().sorted().mapIndexed { i, s -> s to i }.toMap()

        var skipped = 0; var appCount = 0; var folderCount = 0
        fun toItem(r: Row): Item? = when (r.type) {
            0, 1 -> appFor(r.intent)?.let { appCount++; AppItem(layout.newId(), it.id) } ?: run { skipped++; null }
            2 -> {
                val ids = folderMembers[r.id.toInt()].orEmpty().sortedBy { it.rank }.mapNotNull { m -> appFor(m.intent)?.id }.distinct()
                when {
                    ids.isEmpty() -> { skipped++; null }
                    ids.size == 1 -> { appCount++; AppItem(layout.newId(), ids[0]) }
                    else -> { folderCount++; appCount += ids.size; FolderItem(layout.newId(), r.title?.takeIf { it.isNotBlank() } ?: "Folder", ids) }
                }
            }
            else -> null // widgets handled separately; other types (e.g. search widget, custom) skipped
        }

        val placed = ArrayList<Placed>()
        val widgets = ArrayList<PendingWidget>()
        for (r in desktop.sortedWith(compareBy({ pageOf[it.screen] ?: 0 }, { it.y }, { it.x }))) {
            val page = pageOf[r.screen] ?: 0
            if (r.type == 4) {
                val cn = r.widgetProvider?.let(ComponentName::unflattenFromString)
                if (cn != null) widgets += PendingWidget(cn, page, r.x, r.y, r.spanX, r.spanY) else skipped++
                continue
            }
            toItem(r)?.let { placed += Placed(it, page, r.x, r.y) }
        }

        val dockSlots = (hotseat.maxOfOrNull { it.screen } ?: -1) + 1
        val dockSize = dockSlots.coerceIn(3, 6)
        val dock = arrayOfNulls<Item>(dockSize).toMutableList()
        for (r in hotseat.sortedBy { it.screen }) {
            val slot = r.screen.coerceIn(0, dockSize - 1)
            if (dock[slot] == null) dock[slot] = toItem(r)
        }

        val cols = ((desktop.maxOfOrNull { it.x + it.spanX } ?: 5)).coerceIn(4, 6)
        val rowsN = ((desktop.maxOfOrNull { it.y + it.spanY } ?: 6)).coerceIn(4, 7)
        if (appCount == 0) throw ImportException("Found the layout, but none of its apps are installed here.")
        return ImportPlan(placed, dock, widgets, cols, rowsN, dockSize, appCount, folderCount, skipped)
    }
}
