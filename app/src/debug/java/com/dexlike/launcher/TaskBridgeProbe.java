package com.dexlike.launcher;

/** Explicit ADB smoke checks without replacing the installed launcher. Default is read-only. */
public final class TaskBridgeProbe {
    public static void main(String[] args) throws Exception {
        TaskService service = new TaskService();
        if (args.length > 0 && "wallpaper".equals(args[0])) {
            try (android.os.ParcelFileDescriptor file = service.wallpaper(0)) {
                android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                if (file != null) android.graphics.BitmapFactory.decodeFileDescriptor(file.getFileDescriptor(), null, options);
                System.out.println("wallpaper=" + options.outWidth + "x" + options.outHeight);
            }
        } else if (args.length == 6 && "test-bounds".equals(args[0])) {
            java.lang.reflect.Method manager = TaskService.class.getDeclaredMethod("manager");
            manager.setAccessible(true);
            Object remote = manager.invoke(service);
            java.lang.reflect.Method find = TaskService.class.getDeclaredMethod("task", Object.class, int.class, int.class, int.class);
            find.setAccessible(true);
            int id = Integer.parseInt(args[1]);
            android.app.TaskInfo task = (android.app.TaskInfo) find.invoke(service, remote, id, 0, 0);
            java.lang.reflect.Method bounds = TaskService.class.getDeclaredMethod("bounds", android.app.TaskInfo.class);
            bounds.setAccessible(true);
            android.graphics.Rect original = (android.graphics.Rect) bounds.invoke(service, task);
            java.lang.reflect.Method resize = TaskService.class.getDeclaredMethod("resizeWindow", Object.class, android.app.TaskInfo.class, android.graphics.Rect.class);
            resize.setAccessible(true);
            android.graphics.Rect target = new android.graphics.Rect(Integer.parseInt(args[2]), Integer.parseInt(args[3]),
                    Integer.parseInt(args[4]), Integer.parseInt(args[5]));
            try {
                resize.invoke(service, remote, task, target);
                Thread.sleep(700);
                System.out.println("requested=" + target + " actual=" + bounds.invoke(service, find.invoke(service, remote, id, 0, 0)));
            } finally {
                resize.invoke(service, remote, task, original);
                System.out.println("restored=" + original);
            }
        } else if (args.length > 1 && "lower".equals(args[0])) {
            System.out.println("lower=" + service.lowerDesktop(Integer.parseInt(args[1]), 0, 0));
        } else if (args.length > 1 && "test-window".equals(args[0])) {
            int id = Integer.parseInt(args[1]);
            System.out.println("minimize=" + service.minimize(id, 0, 0));
            Thread.sleep(600);
            printState(service, id);
            System.out.println("restore=" + service.restore(id, 0, 0, 0, 0, 0, 0));
            Thread.sleep(600);
            printState(service, id);
        } else {
            System.out.println(service.snapshot(args.length > 0 ? Integer.parseInt(args[0]) : 0,
                    args.length > 1 ? Integer.parseInt(args[1]) : 0));
        }
        System.exit(0);
    }
    private static void printState(TaskService service, int id) throws Exception {
        org.json.JSONArray tasks = new org.json.JSONObject(service.snapshot(0, 0)).getJSONArray("tasks");
        for (int i = 0; i < tasks.length(); i++) {
            org.json.JSONObject task = tasks.getJSONObject(i);
            if (task.getInt("id") == id) System.out.println("task=" + id + " visible=" + task.optBoolean("visible")
                    + " minimized=" + task.optBoolean("minimized"));
        }
    }
}
