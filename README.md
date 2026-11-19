# Dexlike-Launcher
A launcher for Android tablets similar to the classic DEX mode.

## Desktop

- Right-click or touch and hold an empty area for **Add app**, **Add widget**, **Settings**, and wallpaper options. The same menu is available by right-clicking or holding **Apps** in the taskbar.
- In the app drawer, right-click or touch and hold an app and choose **Add to desktop**. Drag desktop shortcuts to move them between grid cells.
- Open **Settings** from the desktop context menu or click the taskbar gear to adjust taskbar and desktop shortcut icon sizes independently.
- Drag a widget's title to move it. Drag **Resize**, or click it for step-by-step size adjustments. The widget provider determines which axes can be resized and the size limits. Widgets may open a setup screen on first use.
- Select the date and time to open Android notifications. The taskbar also has Back, Home, Recents, audio, Screenshot and Quick Settings controls.
- To open Android system panels from the taskbar, enable **Dexlike taskbar controls** in Android Accessibility settings. The service requests system actions when you press a taskbar button and has no access to window content. The notification shade and Quick Settings are Android's own panels.

The taskbar is part of Dexlike's home screen. Android and One UI control other apps' title bars, window placement and minimization.

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
