package com.dexlike.launcher;

import android.app.ActivityManager;
import android.app.TaskInfo;
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
    private volatile int[] monitoredArea;
    private volatile String fittingError = "";
    private final java.util.concurrent.ScheduledExecutorService fitter = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();

    public TaskService() {
        fitter.scheduleWithFixedDelay(() -> {
            int[] area = monitoredArea;
            if (area == null) return;
            try { fittingError = fitWindows(area[0], area[1], area[2], area[3], area[4], area[5]); }
            catch (RuntimeException error) { fittingError = error.toString(); }
        }, 0, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Override public void monitorWindows(int display, int user, int left, int top, int right, int bottom, boolean enabled) {
        monitoredArea = enabled && right > left && bottom > top ? new int[]{display, user, left, top, right, bottom} : null;
        if (!enabled) fittingError = "";
    }

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
    private List<TaskInfo> liveTasks(Object service, int display) throws Exception {
        try {
            return (List<TaskInfo>) invoke(service, "getTasks",
                    new Class<?>[]{int.class, boolean.class, boolean.class, int.class}, 256, false, false, display);
        } catch (NoSuchMethodException olderAndroid) {
            return (List<TaskInfo>) invoke(service, "getTasks",
                    new Class<?>[]{int.class, boolean.class, boolean.class}, 256, false, false);
        }
    }

    @SuppressWarnings("unchecked")
    private List<TaskInfo> tasks(Object service, int display, int user) throws Exception {
        java.util.LinkedHashMap<Integer, TaskInfo> result = new java.util.LinkedHashMap<>();
        for (TaskInfo info : liveTasks(service, display)) result.put(info.taskId, info);
        Object slice = invoke(service, "getRecentTasks", new Class<?>[]{int.class, int.class, int.class}, 256, 2, user);
        Method listMethod = slice.getClass().getMethod("getList");
        listMethod.setAccessible(true);
        List<TaskInfo> recent = (List<TaskInfo>) listMethod.invoke(slice);
        for (TaskInfo info : recent) if (info.taskId >= 0) result.putIfAbsent(info.taskId, info);
        return new java.util.ArrayList<>(result.values());
    }

    private int integerField(Object object, String name) throws Exception {
        return object.getClass().getField(name).getInt(object);
    }

    private Object windowConfig(TaskInfo task) throws Exception {
        Object configuration = task.getClass().getField("configuration").get(task);
        return configuration.getClass().getField("windowConfiguration").get(configuration);
    }

    private int mode(TaskInfo task) throws Exception {
        Object config = windowConfig(task);
        return (Integer) config.getClass().getMethod("getWindowingMode").invoke(config);
    }

    private Rect bounds(TaskInfo task) throws Exception {
        Object config = windowConfig(task);
        return new Rect((Rect) config.getClass().getMethod("getBounds").invoke(config));
    }

    private ComponentName component(TaskInfo task) {
        return task.baseActivity != null ? task.baseActivity : task.baseIntent == null ? null : task.baseIntent.getComponent();
    }

    private boolean eligible(TaskInfo task, int display, int user) throws Exception {
        Object config = windowConfig(task);
        int type = (Integer) config.getClass().getMethod("getActivityType").invoke(config);
        return integerField(task, "displayId") == display && integerField(task, "userId") == user
                && type == 1 && task.taskId >= 0 && component(task) != null
                && !"com.dexlike.launcher".equals(component(task).getPackageName());
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
            for (TaskInfo task : tasks(service, display, user)) {
                if (!eligible(task, display, user)) continue;
                Rect bounds = bounds(task);
                CharSequence label = task.taskDescription == null ? null : task.taskDescription.getLabel();
                boolean minimized = minimizedIds.contains(task.taskId);
                list.put(new JSONObject().put("id", task.taskId).put("component", component(task).flattenToString())
                        .put("label", label == null ? "" : label.toString()).put("minimized", minimized)
                        .put("retained", !task.isRunning || task.numActivities == 0)
                        .put("visible", visible(task) && task.isRunning).put("focused", boolField(task, "isFocused"))
                        .put("mode", mode(task)).put("left", bounds.left).put("top", bounds.top)
                        .put("right", bounds.right).put("bottom", bounds.bottom));
            }
            return new JSONObject().put("tasks", list).put("fitError", fittingError).toString();
        } catch (Exception error) { return error(error); }
    }

    private TaskInfo task(Object service, int id, int display, int user) throws Exception {
        for (TaskInfo task : tasks(service, display, user)) {
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
            TaskInfo task = task(service, id, display, user);
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
            android.graphics.Point screen = new android.graphics.Point(right, bottom);
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            metrics.density = 1;
            try {
                Object displays = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null);
                android.view.Display deviceDisplay = (android.view.Display) displays.getClass()
                        .getMethod("getRealDisplay", int.class).invoke(displays, display);
                deviceDisplay.getRealSize(screen);
                deviceDisplay.getRealMetrics(metrics);
            } catch (ReflectiveOperationException ignored) { }
            int tolerance = Math.round(96 * metrics.density);
            String lastError = "";
            for (TaskInfo task : liveTasks(service, display)) {
                if (!eligible(task, display, user) || mode(task) != 5 || !task.isRunning || task.numActivities == 0) continue;
                Rect original = bounds(task);
                if (original.isEmpty()) continue;
                int[] fittedBounds = DesktopGeometry.fitDesktopWindow(original.left, original.top, original.width(), original.height(),
                        left, top, right, bottom, screen.x, screen.y, tolerance);
                Rect fitted = new Rect(fittedBounds[0], fittedBounds[1], fittedBounds[2], fittedBounds[3]);
                if (!original.equals(fitted)) {
                    try { resizeWindow(service, task, fitted); }
                    catch (Exception error) { lastError = "Task " + task.taskId + ": " + message(error); }
                }
            }
            return lastError;
        } catch (Exception error) { return message(error); }
    }

    private void resizeWindow(Object service, TaskInfo task, Rect fitted) throws Exception {
        try {
            // Modern DeX windows belong to a task organizer. Apply their bounds through
            // that organizer rather than relying on the legacy resizeTask path.
            Class<?> transactionClass = Class.forName("android.window.WindowContainerTransaction");
            Object transaction = transactionClass.getConstructor().newInstance();
            Object token = task.getClass().getField("token").get(task);
            transactionClass.getMethod("setBounds", Class.forName("android.window.WindowContainerToken"), Rect.class)
                    .invoke(transaction, token, fitted);
            Object organizer = invoke(service, "getWindowOrganizerController", new Class<?>[]{});
            Class.forName("android.window.IWindowOrganizerController").getMethod("applyTransaction", transactionClass)
                    .invoke(organizer, transaction);
        } catch (ReflectiveOperationException modernUnavailable) {
            invoke(service, "resizeTask", new Class<?>[]{int.class, Rect.class, int.class}, task.taskId, fitted, 0);
        }
    }

    private Object samsung() throws Exception {
        return Class.forName("com.samsung.android.multiwindow.MultiWindowManager").getMethod("getInstance").invoke(null);
    }

    private boolean boolField(Object object, String name) {
        try { return object.getClass().getField(name).getBoolean(object); }
        catch (Exception ignored) { return false; }
    }

    private boolean visible(TaskInfo task) {
        try { return (Boolean) task.getClass().getMethod("isVisible").invoke(task); }
        catch (Exception ignored) { return boolField(task, "isVisible"); }
    }

    @Override public String activate(String packageName, int display, int user, int left, int top, int right, int bottom) {
        try {
            Object service = manager();
            for (TaskInfo task : tasks(service, display, user)) {
                if (eligible(task, display, user) && packageName.equals(component(task).getPackageName()))
                    return restore(task.taskId, display, user, left, top, right, bottom);
            }
            return "NO_TASK";
        } catch (Exception error) { return message(error); }
    }

    @Override public String minimize(int id, int display, int user) {
        try {
            Object service = manager();
            TaskInfo task = task(service, id, display, user);
            Object samsung = samsung();
            boolean success = (Boolean) samsung.getClass().getMethod("minimizeTaskById", int.class).invoke(samsung, id);
            if (success) return "";
            if (android.os.Build.VERSION.SDK_INT < 35 || mode(task) != 5)
                return "Samsung did not allow this window to minimize";
            // Modern DeX uses WM Shell's MINIMIZE transition (1020), not legacy Samsung minimization.
            // Verified against this tablet's FreeformTaskTransitionHandler; this private API may change.
            Class<?> transactionClass = Class.forName("android.window.WindowContainerTransaction");
            Object transaction = transactionClass.getConstructor().newInstance();
            Object token = task.getClass().getField("token").get(task);
            Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
            transactionClass.getMethod("setWindowingMode", tokenClass, int.class).invoke(transaction, token, 5);
            // Native DeX moves a minimized leaf out of its organizer-owned Desk root, below HOME.
            // Reordering inside a one-window Desk is a no-op, so reparent to the default display area.
            transactionClass.getMethod("reparent", tokenClass, tokenClass, boolean.class)
                    .invoke(transaction, token, null, false);
            Object organizer = invoke(service, "getWindowOrganizerController", new Class<?>[]{});
            Class.forName("android.window.IWindowOrganizerController")
                    .getMethod("startNewTransition", int.class, transactionClass).invoke(organizer, 1020, transaction);
            return "";
        } catch (Exception error) { return message(error); }
    }

    @Override public String lowerDesktop(int id, int display, int user) {
        try {
            Object service = manager();
            TaskInfo desktop = null;
            for (TaskInfo info : tasks(service, display, user)) {
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

    @Override public String taskbarPolicy(boolean samsung) {
        try {
            // Change only the two policies used by Dexlike's documented ADB setup.
            // Do not overwrite unrelated policies configured by another application.
            Process read = new ProcessBuilder("settings", "get", "global", "policy_control").start();
            String policy;
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(read.getInputStream()))) {
                policy = reader.readLine();
                policy = policy == null ? "" : policy.trim();
            }
            read.waitFor();
            // Repair preview 27's permanent navigation-bar policy in either icon mode.
            // Selecting Samsung icons must not force the native bar to stay visible.
            if ("immersive.status=*".equals(policy)) {
                Process write = new ProcessBuilder("settings", "put", "global", "policy_control", "immersive.full=*").start();
                return write.waitFor() == 0 ? "" : "Could not restore transient system bars";
            }
            return "";
        } catch (Exception error) { return message(error); }
    }

    @Override public void destroy() { fitter.shutdownNow(); System.exit(0); }
}
