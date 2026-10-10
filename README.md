# OpenLiquidLauncher

An open-source Android launcher where **everything is liquid glass**. Dock, app drawer, folders, context menus, widget cards and settings are refracting, blurring glass layers over your wallpaper, powered by [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass).

| Home | Drawer | Menu | Settings |
|---|---|---|---|
| ![home](docs/home.png) | ![drawer](docs/drawer.png) | ![menu](docs/menu.png) | ![settings](docs/settings.png) |

## Features
- Paged home grid (default 5×6) with a glass dock; drag & drop, folders (drop an app on another), widgets
- Paged horizontal app drawer with search, as a full-screen glass sheet
- Long-press menu with app shortcuts, app info, remove, uninstall
- Wallpaper: pick an image in-app (optionally also set as system wallpaper) or use the system wallpaper — the glass refracts it
- Notification badges, themed (monochrome) icons, each app's own icon as installed
- Gestures: swipe up (drawer), swipe down (notifications), double tap (lock), pinch (edit mode)
- Tunable glass: blur, refraction height/strength, surface opacity, chromatic dispersion; grid, dock and icon size

## Importing your layout from another launcher (e.g. OnePlus / OxygenOS)
Settings → **Import** copies your dock, pages, folders, widgets and grid size from another Launcher3-based launcher:
1. **Import using Shizuku (recommended on OxygenOS).** With [Shizuku](https://github.com/RikkaApps/Shizuku) running, the app asks for Shizuku permission, runs `dumpsys` with shell rights and rebuilds your layout from the launcher's workspace. Works with the stock OnePlus launcher; no root and no PC.
2. **Import from my current launcher** first tries the launcher's content provider (works for open launchers), then `dumpsys`, which needs the DUMP permission granted once: `adb shell pm grant com.linuxkernel44.openliquidlauncher android.permission.DUMP`.
3. **Import from launcher.db / dump file…** reads a copy of the launcher's `launcher.db`, or the text of `adb shell dumpsys activity <launcher>/.Launcher` (e.g. `com.android.launcher/.Launcher`).

Notes: apps are matched by component so only installed apps are imported. With Shizuku, folders with more than 9 apps are opened one by one in the stock launcher to read every app (keep the screen on and hands off during the import). Widgets can't be copied directly: each one asks you to confirm and is then placed at its original cell. The launcher must be alive: the app briefly opens it if needed.

## Requirements
Android 13+ (API 33): the real glass effect uses AGSL runtime shaders.

## Build
```
./gradlew :app:assembleDebug
```
Then set it as your default home app in Android settings.

## Permissions
- Notification access (optional) — badges
- Accessibility service (optional) — double-tap lock / swipe-down shade; reads no content
- All files access (optional) — only to read the *system* wallpaper on Android 13+

## About Launcher3
This project is inspired by AOSP Launcher3's architecture but is a fresh Compose codebase; see `CLAUDE.md` for why.

## License
Apache-2.0. Liquid glass effect by [Kyant0](https://github.com/Kyant0/AndroidLiquidGlass) (Apache-2.0).
