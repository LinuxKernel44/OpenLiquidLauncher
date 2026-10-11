# CLAUDE.md

Guidance for Claude Code when working on **OpenLiquidLauncher**.

## What this is
An Android home-screen launcher where every surface (dock, app drawer, folders, context menus, widget cards, settings) is *liquid glass* rendered with [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) (`io.github.kyant0:backdrop`) over the user's wallpaper. Visual target: iOS 26-like. Primary test device: OnePlus 15 (Android 16 / OxygenOS).

## Architecture
Kotlin + Jetpack Compose, single `app` module, package `com.linuxkernel44.openliquidlauncher`. It is *inspired by* AOSP Launcher3 (LauncherApps-based app loading, paged grid model, AppWidgetHost, shortcuts) but is **not** a Launcher3 fork: Launcher3 is a View-based codebase meant to be built inside AOSP and does not mix well with Compose/AGSL.

- `data/` — `AppRepository` (LauncherApps), `LayoutStore` (JSON-persisted grid + dock), `SettingsStore`, `WallpaperRepository`.
- `service/` — `BadgeListenerService` (notification counts), `GestureAccessibilityService` (lock screen / open shade only; reads no content).
- `widget/` — `LauncherWidgetHost`, `DragAwareHostView` (long-press + drag for widgets).
- `ui/` — `LauncherScreen` (root), `LauncherState` (overlays + drag & drop + launching), `Workspace`/`Dock`, `Drawer`, `Overlays` (menu, folder, widget picker, drop zones), `SettingsScreen`, `Glass.kt` (the `liquidGlass` modifier).

## Vendored glass library
`app/src/main/java/com/kyant/backdrop/` is a copy of Kyant0's `io.github.kyant0:backdrop` 2.0.1 (Apache-2.0, KMP `commonMain` + `androidMain` merged, `actual` keywords removed) with one addition: `BackdropRendering.scale` (render-resolution scale of the glass). `DrawBackdropNode` records the glass layer at `size * scale` (effect scope density/size are scaled so dp -> px follow), then draws it back at `1/scale`; the node's own `layerBlock` is inverse-transformed in real pixels (`renderScale` passed to `InverseLayerScope`). Keep the diff against upstream small. Settings -> Performance drives it (`glassResolution`), plus `glassQuality` (see `liquidGlass`) and `refreshRate` (`preferredDisplayModeId`, modes read from `Display.supportedModes`).

## Glass rules (important)
- One `LayerBackdrop` holds the wallpaper (`Modifier.layerBackdrop`); all glass surfaces call `Modifier.liquidGlass(backdrop, style, shape, ...)`.
- `lens()` needs a `CornerBasedShape` (e.g. `RoundedCornerShape`) — other shapes throw.
- Animate a glass node via `drawBackdrop(layerBlock = ...)`, not an outer `graphicsLayer`, so the backdrop offset stays correct.
- Real shaders need API 33+ (`minSdk = 33`). Glass parameters are user-tunable (`LauncherSettings.glass*`).

## Gotchas learned the hard way
- Back must never reach the default handler on the home activity (it would finish it and the system recreates the launcher: flicker + replayed intro). `LauncherScreen` keeps a permanent `BackHandler`: dismiss what is open, else go to page 1, else do nothing.
- Compose animations (`Animatable.animateTo`) need a scope with a frame clock: use `LauncherState.uiScope` (from `rememberCoroutineScope`), not `lifecycleScope`.
- A full-screen sibling with a `pointerInput` modifier swallows touches for the layers below it, even if its block returns early. Add such modifiers conditionally.
- Items that must survive a drag (drawer / folder sources) must stay composed while dragging; they are only faded (`dragFromOverlay`).
- `ensureInitialLayout` must stay idempotent (activity can be recreated).

## Layout import
`data/LauncherImporter.kt` has three sources, all producing an `ImportPlan` applied by `LauncherActivity.applyImport`:
- **Provider / launcher.db**: Launcher3-style `favorites` rows (container -100 desktop / -101 hotseat / >0 folder id; itemType 0 app, 2 folder, 4 widget). System launchers usually protect the provider (OxygenOS returns an *empty* cursor), so this rarely works on stock ROMs.
- **dumpsys** (`importFromDump`): `dumpsys activity <launcher>/.Launcher` prints the view hierarchy with `WorkspaceItemInfo(...)` tags; pages are the `CellLayout`s under the `Workspace`, positions come from view bounds / `cellWidth`,`cellHeight` of the `SizeSpacingConfig` line. Folders only expose preview titles (max 9). Needs shell rights: via **Shizuku** (`ShizukuBridge`, `Shizuku.newProcess` through reflection, keep rules in proguard-rules.pro) or a one-time `pm grant ... android.permission.DUMP`.
- Debug builds accept `--es importDb <path>` (db or dump text) and `--ez importDumpsys true [--ez shizuku true]` on the launch intent for testing.
The last raw dump is saved to `files/last_dump.txt` to debug parsing.

## Build & run
```
./gradlew :app:assembleDebug          # APK in app/build/outputs/apk/debug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell cmd package set-home-activity com.linuxkernel44.openliquidlauncher/.LauncherActivity
```
Dev emulator: AVD `olauncher_api36` (Pixel 8, Android 16, google_apis x86_64, `-gpu host`). A stock Galaxy S10 (Android 12) cannot run the glass shaders.

## Conventions
- Everything (code, comments, docs, commits, issues) is in English.
- Keep Compose code allocation-light in drawing paths; prefer `layerBlock`/draw lambdas over recomposition for animation.
- Don't add dependencies without a reason; UI uses Compose foundation only (no Material 3).
