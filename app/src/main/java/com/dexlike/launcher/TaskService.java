package com.dexlike.launcher;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.graphics.Rect;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Method;
import java.util.List;

/** Runs only in Shizuku's shell-identity user-service process, where hidden APIs are allowed. */
public final class TaskService extends ITaskService.Stub {
    public TaskService() { }

    // Shizuku documents that non-SDK restrictions do not apply to its user-service process.
    @android.annotation.SuppressLint("BlockedPrivateApi")
    private Object manager() throws Exception {
        int uid = android.os.Process.myUid();
        if (uid != 2000 && uid != 0) throw new SecurityException("Task access requires Shizuku shell identity");
        return Class.forName("android.app.ActivityTaskManager").getDeclaredMethod("getService").invoke(null);
    }

    private Object invoke(Object service, String name, Class<?>[] types, Object... args) throws Exception {
        return Class.forName("android.app.IActivityTaskManager").getMethod(name, types).invoke(service, args);
    }

    @SuppressWarnings("unchecked")
    private List<ActivityManager.RunningTaskInfo> tasks(Object service, int display) throws Exception {
        try {
            return (List<ActivityManager.RunningTaskInfo>) invoke(service, "getTasks",
                    new Class<?>[]{int.class, boolean.class, boolean.class, int.class}, 256, false, false, display);
        } catch (NoSuchMethodException olderAndroid) {
            return (List<ActivityManager.RunningTaskInfo>) invoke(service, "getTasks",
                    new Class<?>[]{int.class, boolean.class, boolean.class}, 256, false, false);
        }
    }

    private int integerField(Object object, String name) throws Exception {
        return object.getClass().getField(name).getInt(object);
    }

    private Object windowConfig(ActivityManager.RunningTaskInfo task) throws Exception {
        Object configuration = task.getClass().getField("configuration").get(task);
        return configuration.getClass().getField("windowConfiguration").get(configuration);
    }

    private int mode(ActivityManager.RunningTaskInfo task) throws Exception {
        Object config = windowConfig(task);
        return (Integer) config.getClass().getMethod("getWindowingMode").invoke(config);
    }

    private Rect bounds(ActivityManager.RunningTaskInfo task) throws Exception {
        Object config = windowConfig(task);
        return new Rect((Rect) config.getClass().getMethod("getBounds").invoke(config));
    }

    private boolean eligible(ActivityManager.RunningTaskInfo task, int display, int user) throws Exception {
        Object config = windowConfig(task);
        int type = (Integer) config.getClass().getMethod("getActivityType").invoke(config);
        return integerField(task, "displayId") == display && integerField(task, "userId") == user
                && type == 1 && task.numActivities > 0 && task.baseActivity != null
                && !"com.dexlike.launcher".equals(task.baseActivity.getPackageName());
    }

    @Override public String snapshot(int display, int user) {
        try {
            JSONArray list = new JSONArray();
            Object service = manager();
            java.util.Set<Integer> minimizedIds = new java.util.HashSet<>();
            try {
                Object samsung = samsung();
                List<?> minimizedTasks = (List<?>) samsung.getClass()
                        .getMethod("getMinimizedFreeformTasksForCurrentUser").invoke(samsung);
                for (Object task : minimizedTasks) minimizedIds.add(integerField(task, "taskId"));
            } catch (Exception ignored) { }
            for (ActivityManager.RunningTaskInfo task : tasks(service, display)) {
                if (!eligible(task, display, user)) continue;
                Rect bounds = bounds(task);
                CharSequence label = task.taskDescription == null ? null : task.taskDescription.getLabel();
                boolean minimized = minimizedIds.contains(task.taskId);
                list.put(new JSONObject().put("id", task.taskId).put("component", task.baseActivity.flattenToString())
                        .put("label", label == null ? "" : label.toString()).put("minimized", minimized)
                        .put("visible", visible(task)).put("focused", boolField(task, "isFocused"))
                        .put("mode", mode(task)).put("left", bounds.left).put("top", bounds.top)
                        .put("right", bounds.right).put("bottom", bounds.bottom));
            }
            return new JSONObject().put("tasks", list).toString();
        } catch (Exception error) { return error(error); }
    }

    private ActivityManager.RunningTaskInfo task(Object service, int id, int display, int user) throws Exception {
        for (ActivityManager.RunningTaskInfo task : tasks(service, display)) {
            if (task.taskId == id && eligible(task, display, user)) return task;
        }
        throw new IllegalStateException("That window has closed or moved to another display");
    }

    private Rect fit(Rect original, Rect area) {
        int[] fitted = DesktopGeometry.fit(original.left, original.top, original.width(), original.height(),
                area.left, area.top, area.right, area.bottom);
        return new Rect(fitted[0], fitted[1], fitted[2], fitted[3]);
    }

    @Override public String restore(int id, int display, int user, int left, int top, int right, int bottom) {
        try {
            Object service = manager();
            ActivityManager.RunningTaskInfo task = task(service, id, display, user);
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(display);
            if (mode(task) == 5 && right > left && bottom > top) {
                Rect bounds = fit(bounds(task), new Rect(left, top, right, bottom));
                options.setLaunchBounds(bounds);
            }
            invoke(service, "startActivityFromRecents", new Class<?>[]{int.class, Bundle.class}, id, options.toBundle());
            return "";
        } catch (Exception error) { return message(error); }
    }

    @Override public String fitWindows(int display, int user, int left, int top, int right, int bottom) {
        if (right <= left || bottom <= top) return "";
        try {
            Object service = manager();
            Rect area = new Rect(left, top, right, bottom);
            for (ActivityManager.RunningTaskInfo task : tasks(service, display)) {
                if (!eligible(task, display, user) || mode(task) != 5) continue;
                Rect original = bounds(task);
                if (original.isEmpty()) continue;
                Rect fitted = fit(original, area);
                if (!original.equals(fitted)) {
                    invoke(service, "resizeTask", new Class<?>[]{int.class, Rect.class, int.class}, task.taskId, fitted, 0);
                }
            }
            return "";
        } catch (Exception error) { return message(error); }
    }

    private Object samsung() throws Exception {
        return Class.forName("com.samsung.android.multiwindow.MultiWindowManager").getMethod("getInstance").invoke(null);
    }

    private boolean boolField(Object object, String name) {
        try { return object.getClass().getField(name).getBoolean(object); }
        catch (Exception ignored) { return false; }
    }

    private boolean visible(ActivityManager.RunningTaskInfo task) {
        try { return (Boolean) task.getClass().getMethod("isVisible").invoke(task); }
        catch (Exception ignored) { return boolField(task, "isVisible"); }
    }

    @Override public String minimize(int id, int display, int user) {
        try {
            task(manager(), id, display, user);
            Object samsung = samsung();
            boolean success = (Boolean) samsung.getClass().getMethod("minimizeTaskById", int.class).invoke(samsung, id);
            if (!success) return "Samsung did not allow this window to minimize";
            return "";
        } catch (Exception error) { return message(error); }
    }

    @Override public String lowerDesktop(int id, int display, int user) {
        try {
            Object service = manager();
            ActivityManager.RunningTaskInfo desktop = null;
            for (ActivityManager.RunningTaskInfo info : tasks(service, display)) {
                if (info.taskId == id && integerField(info, "userId") == user && info.baseActivity != null
                        && "com.dexlike.launcher".equals(info.baseActivity.getPackageName())) desktop = info;
            }
            if (desktop == null || mode(desktop) != 5) return ""; // Native HOME tasks already have desktop ordering.
            Object token = desktop.getClass().getField("token").get(desktop);
            Class<?> transactionClass = Class.forName("android.window.WindowContainerTransaction");
            Object transaction = transactionClass.getConstructor().newInstance();
            transactionClass.getMethod("reorder", Class.forName("android.window.WindowContainerToken"), boolean.class)
                    .invoke(transaction, token, false);
            Object organizer = invoke(service, "getWindowOrganizerController", new Class<?>[]{});
            Class.forName("android.window.IWindowOrganizerController").getMethod("applyTransaction", transactionClass)
                    .invoke(organizer, transaction);
            return "";
        } catch (Exception error) { return message(error); }
    }

    @Override public android.os.ParcelFileDescriptor wallpaper(int user) {
        try {
            manager(); // Enforce shell/root identity for wallpaper access too.
            android.os.IBinder binder = (android.os.IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "wallpaper");
            Object service = Class.forName("android.app.IWallpaperManager$Stub")
                    .getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
            return (android.os.ParcelFileDescriptor) Class.forName("android.app.IWallpaperManager")
                    .getMethod("getWallpaper", String.class, Class.forName("android.app.IWallpaperManagerCallback"),
                            int.class, Bundle.class, int.class)
                    .invoke(service, "com.android.shell", null, android.app.WallpaperManager.FLAG_SYSTEM, new Bundle(), user);
        } catch (Exception error) { return null; }
    }

    private String message(Exception error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private String error(Exception error) {
        try { return new JSONObject().put("error", message(error)).toString(); }
        catch (Exception ignored) { return "{\"error\":\"Window access unavailable\"}"; }
    }

    @Override public void destroy() { System.exit(0); }
}
