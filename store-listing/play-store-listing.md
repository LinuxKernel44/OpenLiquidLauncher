# Play Store listing — copy to paste into Play Console

Not published anywhere — internal reference for filling out the Play Console store listing form.

## App details

- **App name:** OpenLiquidLauncher
- **Category:** Personalization
- **Tags:** Launcher, Customization, Home screen
- **Contact email:** davidpallier30@gmail.com
- **Privacy policy URL:** https://linuxkernel44.github.io/OpenLiquidLauncher/privacy.html

## Short description (max 80 chars)

```
Every surface is liquid glass. An open-source, iOS 26-style Android launcher.
```
(78 chars)

## Full description (max 4000 chars)

```
OpenLiquidLauncher is a free, open-source Android launcher where every surface — dock, app drawer, folders, context menus, widget cards, settings — is real liquid glass: refracting, blurring layers rendered live over your wallpaper.

NO ADS. NO TRACKING. NO INTERNET PERMISSION.
This app cannot connect to the internet — it doesn't even ask for the permission. Nothing you do is ever collected, measured, or sent anywhere. Your layout and wallpaper stay in the app's private storage, on your device, full stop.

GLASS, TUNED TO YOUR TASTE
• Blur, refraction height & strength, surface opacity, chromatic dispersion
• Roundness (per-element or folders only), Droplet look for a clearer, wetter glass
• Quality and render-resolution controls so it runs well on your specific phone

A REAL HOME SCREEN
• Paged grid (default 5×6) with a glass dock
• Drag & drop, folders (drop an app on another, or hold one and tap others with a second finger), widgets
• Full-screen glass app drawer with search
• Notification badges, themed monochrome icons or each app's own icon
• New apps (Play Store, sideload, adb) can auto-place themselves after your layout
• Gestures: swipe up for the drawer, swipe down for notifications, double-tap to lock, pinch to edit
• Five launch animations: None, Soft fade, Zoom, Cascade, Slide & spring

BRING YOUR LAYOUT WITH YOU
Settings → Import can copy your dock, pages, folders, widgets and grid size from another launcher (Shizuku-assisted import works especially well on OnePlus/OxygenOS and Samsung One UI).

OPEN SOURCE
Full source code, issue tracker and releases on GitHub: the "OpenLiquidLauncher" project. Built with Kotlin and Jetpack Compose, using the open-source AndroidLiquidGlass rendering library.

Requires Android 13 (API 33) or newer, with a GPU/driver that supports the real-time shader effects (most phones from the last few years).
```

## Graphics checklist (to produce separately)

- [ ] App icon 512×512 PNG (already have mipmap source — export at full res)
- [ ] Feature graphic 1024×500 PNG/JPG
- [ ] Phone screenshots: at least 2, recommend 4-6 — home, drawer, folder open, settings (have docs/home.png, docs/drawer.png, docs/settings.png as a starting point, but Play wants device-frame-free, portrait, ideally 9:16 or device native res)
- [ ] (Optional) Tablet screenshots since Tab S7 FE testing is already happening

## Content rating questionnaire — expected answers

- No user-generated content, no violence, no in-app purchases, no ads, no gambling, no location sharing.
- Expect **PEGI 3 / Everyone**.

## Data safety form — expected answers

- **Does your app collect or share any of the required user data types?** No.
- **Is all user data encrypted in transit?** N/A (no data transmitted — no INTERNET permission).
- **Do you provide a way for users to request data deletion?** N/A (nothing is collected; uninstalling removes all local data).

## Sensitive permissions declarations (Play Console will ask for these separately)

### Accessibility API (`GestureAccessibilityService`)
- **Core functionality it enables:** Lock-screen and open-notification-shade gestures (double-tap to lock, swipe down for notifications) — standard launcher conveniences that have no public Android API outside AccessibilityService's global actions.
- **What it reads:** Nothing. `onAccessibilityEvent` is a no-op; the service never inspects screen content, other apps' UI, or input events — see `service/GestureAccessibilityService.kt`.
- Google requires a short demo video for this category — record one showing Settings → enable the gesture accessibility toggle → double-tap home screen → screen locks.

### Notification access (`BadgeListenerService`)
- **Core functionality it enables:** Unread-count badges on app icons, off by default, opt-in from Settings.
- **What it reads:** Notification counts per app only, to render a number on the icon; no notification content is stored, logged, or transmitted anywhere (no INTERNET permission exists to send it, even if the code wanted to).

## Suggested next steps (not doable by an agent — need you directly)

1. Create a Play Console developer account (one-time $25 fee, needs your Google account + ID verification — can take a few days for new accounts).
2. Set up a payment profile (even for a free app, Play Console requires this for publishing).
3. Produce the feature graphic + polished screenshots (I can help design/generate these once you're ready).
4. Fill the store listing using the copy above, upload the privacy policy URL, submit for review.
