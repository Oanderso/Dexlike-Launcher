package com.dexlike.launcher;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/** Routes taskbar button presses to Android's system UI. */
public final class DesktopActionsService extends AccessibilityService {
    private static DesktopActionsService connected;

    static boolean isConnected() { return connected != null; }

    static boolean run(int action) {
        return connected != null && connected.performGlobalAction(action);
    }

    @Override protected void onServiceConnected() { connected = this; }

    @Override public void onDestroy() {
        if (connected == this) connected = null;
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
}
