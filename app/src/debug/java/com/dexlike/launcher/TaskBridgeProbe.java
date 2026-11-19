package com.dexlike.launcher;

/** Read-only ADB smoke test: app_process -cp app-debug.apk /system/bin com.dexlike.launcher.TaskBridgeProbe */
public final class TaskBridgeProbe {
    public static void main(String[] args) {
        System.out.println(new TaskService().snapshot(args.length > 0 ? Integer.parseInt(args[0]) : 0,
                args.length > 1 ? Integer.parseInt(args[1]) : 0));
        System.exit(0);
    }
}
