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
)

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
        if (appCount == 0 && widgets.isEmpty()) throw ImportException("Found the layout, but none of its apps are installed here.")
        return ImportPlan(placed, dock, widgets, cols, rowsN, dockSize, appCount, folderCount, skipped)
    }
}
