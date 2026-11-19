package com.dexlike.launcher;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import rikka.shizuku.Shizuku;

/** Optional task access. No app task is fabricated from launch history. */
final class WindowTracker {
    interface Listener { void changed(List<RunningWindow> windows, String status); }
    private final MainActivity activity;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Shizuku.UserServiceArgs args;
    private ITaskService service;
    private boolean active, binding, busy, closed;
    private int generation;
    private final Runnable poll = this::refresh;
    private final Shizuku.OnBinderReceivedListener received = () -> main.post(this::connect);
    private final Shizuku.OnBinderDeadListener died = () -> main.post(this::disconnected);
    private final Shizuku.OnRequestPermissionResultListener permission = this::permissionResult;
    private void permissionResult(int code, int result) {
        if (code == 71) main.post(() -> {
            if (closed) return;
            if (result == PackageManager.PERMISSION_GRANTED) connect();
            else listener.changed(new ArrayList<>(), "Shizuku permission denied");
        });
    }
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            main.post(() -> {
                if (closed) return;
                binding = false;
                service = ITaskService.Stub.asInterface(binder);
                refresh();
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) { main.post(WindowTracker.this::disconnected); }
    };

    WindowTracker(MainActivity activity, Listener listener) {
        this.activity = activity; this.listener = listener;
        args = new Shizuku.UserServiceArgs(new ComponentName(activity, TaskService.class))
                .daemon(false).processNameSuffix("windows").version(serviceVersion());
        Shizuku.addBinderReceivedListenerSticky(received);
        Shizuku.addBinderDeadListener(died);
        Shizuku.addRequestPermissionResultListener(permission);
    }

    private int serviceVersion() {
        try { return (int) activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).getLongVersionCode(); }
        catch (PackageManager.NameNotFoundException impossible) { return 1; }
    }

    void start() { active = true; connect(); refresh(); }
    void stop() { active = false; main.removeCallbacks(poll); }
    boolean ready() { return service != null; }

    void requestAccess() {
        try {
            if (!Shizuku.pingBinder()) {
                listener.changed(new ArrayList<>(), "Start Shizuku first, then connect again");
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) connect();
            else if (Shizuku.shouldShowRequestPermissionRationale()) {
                listener.changed(new ArrayList<>(), "Allow Dexlike in Shizuku’s authorized applications");
            } else Shizuku.requestPermission(71);
        } catch (RuntimeException error) { listener.changed(new ArrayList<>(), "Shizuku is unavailable"); }
    }

    private void connect() {
        if (closed || !active || binding || service != null) return;
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                binding = true;
                Shizuku.bindUserService(args, connection);
                main.postDelayed(() -> {
                    if (!closed && binding && service == null) {
                        binding = false;
                        listener.changed(new ArrayList<>(), "Shizuku connection timed out; connect again");
                    }
                }, 10000);
            }
        } catch (RuntimeException error) {
            binding = false;
            listener.changed(new ArrayList<>(), "Could not connect to Shizuku");
        }
    }

    private void disconnected() {
        if (closed) return;
        service = null; binding = false; generation++;
        main.removeCallbacks(poll);
        listener.changed(new ArrayList<>(), "Window tracking disconnected; start Shizuku and reconnect");
    }

    void refresh() {
        main.removeCallbacks(poll);
        if (closed || !active || busy || service == null) return;
        busy = true;
        final int version = generation;
        ITaskService remote = service;
        int display = activity.getDisplay().getDisplayId();
        int user = android.os.Process.myUid() / 100000;
        Rect area = activity.desktopWorkArea();
        worker.execute(() -> {
            List<RunningWindow> windows = new ArrayList<>();
            String status = "Connected — tracking windows";
            try {
                JSONObject snapshot = new JSONObject(remote.snapshot(display, user));
                if (snapshot.has("error")) throw new IllegalStateException(snapshot.getString("error"));
                JSONArray tasks = snapshot.getJSONArray("tasks");
                for (int i = 0; i < tasks.length(); i++) {
                    RunningWindow window = new RunningWindow(tasks.getJSONObject(i));
                    if (window.id >= 0 && !window.packageName.isEmpty()) windows.add(window);
                }
                if (area != null) {
                    String error = remote.fitWindows(display, user, area.left, area.top, area.right, area.bottom);
                    if (!error.isEmpty()) status = "Tracking windows; resizing unavailable: " + error;
                }
            } catch (Exception error) {
                status = "Window tracking unavailable: " + error.getMessage();
                windows.clear();
            }
            final String message = status;
            main.post(() -> {
                busy = false;
                if (closed) return;
                if (version != generation) { if (active) refresh(); return; }
                if (active) {
                    listener.changed(windows, message);
                    main.postDelayed(poll, 2000);
                }
            });
        });
    }

    void restore(RunningWindow window) {
        if (closed || service == null) return;
        ITaskService remote = service;
        Rect area = activity.desktopWorkArea();
        if (area == null) area = new Rect();
        final Rect work = area;
        int display = activity.getDisplay().getDisplayId(), user = android.os.Process.myUid() / 100000;
        worker.execute(() -> {
            String result;
            try { result = remote.restore(window.id, display, user, work.left, work.top, work.right, work.bottom); }
            catch (Exception error) { result = "Could not restore window: " + error.getMessage(); }
            final String error = result;
            main.post(() -> {
                if (closed) return;
                if (!error.isEmpty()) android.widget.Toast.makeText(activity, error, android.widget.Toast.LENGTH_LONG).show();
                refresh();
            });
        });
    }

    void close() {
        closed = true; active = false;
        main.removeCallbacksAndMessages(null);
        Shizuku.removeBinderReceivedListener(received);
        Shizuku.removeBinderDeadListener(died);
        Shizuku.removeRequestPermissionResultListener(permission);
        if (service != null || binding) {
            try { Shizuku.unbindUserService(args, connection, true); }
            catch (RuntimeException ignored) { }
        }
        service = null;
        worker.shutdownNow();
    }
}
