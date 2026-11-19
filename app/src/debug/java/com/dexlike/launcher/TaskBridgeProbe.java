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
