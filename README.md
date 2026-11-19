# Dexlike-Launcher
A launcher for Android tablets similar to the classic DEX mode.

## Desktop

- Right-click or touch and hold an empty area for **Add app**, **Add widget**, **Settings**, and wallpaper options. The same menu is available by right-clicking or holding **Apps** in the taskbar.
- In the app drawer, right-click or touch and hold an app and choose **Add to desktop**. Drag desktop shortcuts to move them between grid cells.
- Open **Settings** from the desktop context menu or click the taskbar gear to adjust taskbar and desktop shortcut icon sizes independently.
- Widgets display only their content. Long-press or right-click a widget to reveal editing controls. Drag a corner to resize, or drag its title to move it. Click **Done** or empty desktop space to hide the controls. **Widget options → Resize options** also provides accessible size adjustments. Provider limits and supported axes are respected.
- **Add widget** shows provider preview images or preview layouts. Providers without either show their icon and an explicit preview-unavailable label. Binding/configuration starts only after selecting a widget.
- The desktop menu opens at the touch or pointer position, moving inward near screen edges.
- **Hide system bars on the desktop** is enabled by default in Dexlike settings. Dexlike requests immersive mode again after focus returns or Android reports that navigation bars are visible. This affects Dexlike only; Samsung can override it, reveal the bar briefly, or show it in other apps. It does not disable system gestures or alter global ADB settings.
- Select the date and time to open Android notifications. The taskbar also has Back, Home, Recents, audio, Screenshot and Quick Settings controls.
- To open Android system panels from the taskbar, enable **Dexlike taskbar controls** in Android Accessibility settings. The service requests system actions when you press a taskbar button and has no access to window content. The notification shade and Quick Settings are Android's own panels.

## Drawer, icons and desktop input

- **Apps** focuses search immediately, including physical-keyboard typing. Settings can switch between a named app list and an **icons-only three-column** vertical scroll. Icon names remain available as tooltips/accessibility descriptions.
- **Icon style** in settings lists installed ADW/Nova-compatible packs and **System icons**. Component mappings from `appfilter.xml` are applied to desktop, drawer and dock. Missing mappings use the original app icon. Generated icon masks/backgrounds and proprietary icon-pack formats are not supported.
- Taskbar and desktop shortcut icon sizes can each reach **100 dp**. Shortcut grid cells, dock entries and dock/window work-area height expand to fit.
- The idle desktop does not claim keyboard focus; opening Apps enables focus and hides widgets until the drawer closes. A taskbar click minimizes the visible single/last-focused app window through Samsung's window manager, or restores a minimized window. Multiple windows remain accessible from the two-second hover selector. A rejected system operation reports an error rather than silently doing nothing.
- Empty-desktop gestures are explicitly kept away from widget touch targets. With Shizuku connected, a full-screen DeX instance of Dexlike is reordered below app windows after background interaction. Widgets are normal desktop views, never application overlays. Use **Set Dexlike as default home** for native HOME-task ordering. Without Shizuku, Samsung controls standalone-app window stacking.
- **Use system wallpaper** retains the native wallpaper-layer request and uses a Shizuku-readable system-wallpaper image as a fallback when DeX provides no wallpaper surface. Live wallpaper animation still depends on native wallpaper support. Reconnect Shizuku if the system-wallpaper fallback is unavailable.

## Windows and system wallpaper

- Choose **Use system wallpaper** from the desktop menu to use Android's actual wallpaper layer, including live wallpaper. New desktops use this by default; existing custom images are retained until you switch. The system Recents screen uses Samsung's wallpaper; selecting this option makes Dexlike use that same source. A custom Dexlike image does not change the system wallpaper.
- Newly launched window bounds use the space above Dexlike's current dock height, including the default launch. Samsung can override launch bounds, especially for maximized or non-resizable apps.
- In **Dexlike settings → Connect window tracking with Shizuku**, start Shizuku and grant Dexlike access. Shizuku is optional; it supplies the shell identity needed to list and restore other apps' individual tasks. The accessibility service alone cannot do this.
- With window tracking connected, the dock groups actual running windows by app, including minimized windows and duplicates opened from other launchers. Hover for **two seconds** to see the window selector; clicks toggle a visible/minimized single or last-focused window, or show the selector for other multi-window groups. Choose a specific window to restore its existing task; **Open new window** makes an explicit new-window request (app support varies). Each selector row includes the task ID to distinguish duplicate titles. These are title-based window choices, not screenshots.
- Tracking covers live and retained recent standard app tasks for the current Android user and display. It refreshes every two seconds while the desktop is started. The task list includes retained Android recent tasks so a previous session can be restored by its task ID. Tasks actually removed from Android cannot be resurrected; only when no recoverable task remains does a normal icon click launch the app. Connection failures clear the task list instead of pretending launch history represents windows. Without tracking, the dock falls back to pinned apps and Dexlike launch history.
- While tracking, freeform windows that extend beyond the desktop work area are fitted above the scaled dock, and existing windows are fitted on restore. This is not a system-wide reserved inset: Samsung still controls maximized/fullscreen windows, minimum sizes and task visibility. Window listing and resizing use internal Android task APIs inside Shizuku's user service and need testing after One UI updates.
- Adding, moving and restoring desktop shortcuts avoids widget rectangles. Moving/resizing widgets reflows shortcuts into remaining free grid cells; if the desktop is full, shortcuts with no available cell are hidden until space is available.

The taskbar is part of Dexlike's home screen. Android and One UI control other apps' title bars, window placement and minimization.

## ADB setup for window tracking and taskbar controls

With USB debugging authorized and Shizuku installed, run:

```sh
adb devices
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

Then open **Dexlike settings → Connect window tracking with Shizuku** and approve the Shizuku permission prompt. Starting the Shizuku server alone does not grant Dexlike permission. Repeat the starter command after each tablet reboot, or start Shizuku through its wireless-debugging workflow. See [Shizuku's setup guide](https://shizuku.rikka.app/guide/setup/).

For taskbar system buttons, open Android's accessibility settings:

```sh
adb shell am start -a android.settings.ACCESSIBILITY_SETTINGS
```

Enable **Installed apps → Dexlike taskbar controls**. If blocked as a restricted setting, first allow restricted settings under **Settings → Apps → Dexlike Launcher → ⋮**. Do not replace the entire enabled-accessibility-services setting: that would turn off other services. No WRITE_SECURE_SETTINGS, overlay, or usage-access grant is required for this implementation.

Optional global navigation suppression, if Samsung ignores Dexlike's own immersive request:

```sh
adb shell settings put global policy_control immersive.navigation=*
```

This applies beyond Dexlike and allows edge-swipe reveal. To remove this override (when no previous policy was configured):

```sh
adb shell settings delete global policy_control
```

## APK updates

Use the **DexlikeLauncher-signed-update-apk** artifact for ongoing installations. Opening a newer signed APK on the tablet updates Dexlike in place when the installed app uses the same signing key. The GitHub workflow assigns increasing version codes. Preview APKs use the runner's debug key and are for isolated tests.

Create the signing key once with a JDK:

```sh
keytool -genkeypair -keystore dexlike.p12 -storetype PKCS12 -alias dexlike -keyalg RSA -keysize 3072 -validity 10000
```

In GitHub **Settings → Secrets and variables → Actions**, set `DEXLIKE_KEYSTORE_PASSWORD` to the keystore password and `DEXLIKE_KEYSTORE_BASE64` to the base64-encoded file. With an authenticated GitHub CLI, load the file without putting its contents on the command line:

```sh
base64 < dexlike.p12 | tr -d '\n' | gh secret set DEXLIKE_KEYSTORE_BASE64 --repo Oanderso/Dexlike-Launcher
gh secret set DEXLIKE_KEYSTORE_PASSWORD --repo Oanderso/Dexlike-Launcher
```

Keep a secure backup of the key and password. Once the workflow changes are merged, builds of `main` produce the signed update APK. An existing GitHub debug APK signed with a different key requires a one-time migration before you can install the signed build. Android clears app data on uninstall.
