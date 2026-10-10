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

## Glass rules (important)
- One `LayerBackdrop` holds the wallpaper (`Modifier.layerBackdrop`); all glass surfaces call `Modifier.liquidGlass(backdrop, style, shape, ...)`.
- `lens()` needs a `CornerBasedShape` (e.g. `RoundedCornerShape`) — other shapes throw.
- Animate a glass node via `drawBackdrop(layerBlock = ...)`, not an outer `graphicsLayer`, so the backdrop offset stays correct.
- Real shaders need API 33+ (`minSdk = 33`). Glass parameters are user-tunable (`LauncherSettings.glass*`).

## Gotchas learned the hard way
- A full-screen sibling with a `pointerInput` modifier swallows touches for the layers below it, even if its block returns early. Add such modifiers conditionally.
- Items that must survive a drag (drawer / folder sources) must stay composed while dragging; they are only faded (`dragFromOverlay`).
- `ensureInitialLayout` must stay idempotent (activity can be recreated).

## Layout import
`data/LauncherImporter.kt` reads Launcher3-style `favorites` rows (container -100 desktop / -101 hotseat / >0 folder id; itemType 0 app, 2 folder, 4 widget) from either a provider (`content://<authority>/favorites`) or a launcher.db copy, and turns them into an `ImportPlan` applied by `LauncherActivity.applyImport`. System launchers usually protect their provider with a signature-level permission, so the file route is the reliable one. Debug builds accept `--es importDb <path>` on the launch intent to test without the document picker.

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
