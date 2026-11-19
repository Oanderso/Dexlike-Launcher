package com.dexlike.launcher;

import android.content.ComponentName;
import org.json.JSONObject;

final class RunningWindow {
    final int id;
    final String packageName, label;
    final boolean minimized;

    RunningWindow(JSONObject task) {
        id = task.optInt("id", -1);
        ComponentName component = ComponentName.unflattenFromString(task.optString("component"));
        packageName = component == null ? "" : component.getPackageName();
        label = task.optString("label");
        minimized = task.optBoolean("minimized");
    }
}
