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
    private final Runnable reconnect = this::connect;
    private IBinder watchedBinder;
    private IBinder.DeathRecipient deathRecipient;
    private int bindAttempt;

    private void forgetBinder() {
        if (watchedBinder != null && deathRecipient != null) {
            watchedBinder.unlinkToDeath(deathRecipient, 0);
        }
        watchedBinder = null; deathRecipient = null;
    }

    private void checkFailure(ITaskService remote) {
        main.post(() -> {
            if (!closed && service == remote && !remote.asBinder().isBinderAlive()) disconnected();
        });
    }
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
                forgetBinder();
                service = ITaskService.Stub.asInterface(binder);
                watchedBinder = binder;
                deathRecipient = () -> main.post(() -> {
                    if (watchedBinder == binder) disconnected();
                });
                try { binder.linkToDeath(deathRecipient, 0); }
                catch (android.os.RemoteException dead) { disconnected(); return; }
                main.removeCallbacks(reconnect);
                activity.applyTaskbarPolicy();
                refresh();
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) { main.post(WindowTracker.this::disconnected); }
    };

    WindowTracker(MainActivity activity, Listener listener) {
        this.activity = activity; this.listener = listener;
        args = new Shizuku.UserServiceArgs(new ComponentName(activity, TaskService.class))
                .daemon(true).processNameSuffix("windows").version(serviceVersion());
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
    boolean ready() { return service != null && service.asBinder().isBinderAlive(); }

    void requestAccess() {
        try {
            if (!Shizuku.pingBinder()) {
                listener.changed(new ArrayList<>(), "Start Shizuku first, then connect again");
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                if (!ready() && service != null) disconnected();
                connect(); refresh();
            }
            else if (Shizuku.shouldShowRequestPermissionRationale()) {
                listener.changed(new ArrayList<>(), "Allow Dexlike in Shizuku’s authorized applications");
            } else Shizuku.requestPermission(71);
        } catch (RuntimeException error) { listener.changed(new ArrayList<>(), "Shizuku is unavailable"); }
    }

    private void connect() {
        if (closed || !active || binding) return;
        if (ready()) return;
        if (service != null) { forgetBinder(); service = null; generation++; }
        main.removeCallbacks(reconnect);
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                binding = true;
                final int attempt = ++bindAttempt;
                Shizuku.bindUserService(args, connection);
                main.postDelayed(() -> {
                    if (!closed && attempt == bindAttempt && binding && service == null) {
                        binding = false;
                        try { Shizuku.unbindUserService(args, connection, false); }
                        catch (RuntimeException ignored) { }
                        listener.changed(new ArrayList<>(), "Shizuku connection timed out; retrying");
                        main.postDelayed(reconnect, 3000);
                    }
                }, 10000);
            } else {
                main.postDelayed(reconnect, 5000);
            }
        } catch (RuntimeException error) {
            main.postDelayed(reconnect, 5000);
            binding = false;
            listener.changed(new ArrayList<>(), "Could not connect to Shizuku");
        }
    }

    private void disconnected() {
        if (closed) return;
        forgetBinder();
        service = null; binding = false; generation++; bindAttempt++;
        try { Shizuku.unbindUserService(args, connection, false); }
        catch (RuntimeException ignored) { }
        main.removeCallbacks(reconnect);
        if (active) main.postDelayed(reconnect, 1500);
        main.removeCallbacks(poll);
        listener.changed(new ArrayList<>(), "Shizuku disconnected; reconnecting when the server is available (Android 16 needs Shizuku 13.6+)");
    }

    void refresh() {
        main.removeCallbacks(poll);
        if (closed || !active || busy || service == null) return;
        busy = true;
        final int version = generation;
        ITaskService remote = service;
        int display = activity.getWindowManager().getDefaultDisplay().getDisplayId();
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
                checkFailure(remote);
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
        int display = activity.getWindowManager().getDefaultDisplay().getDisplayId(), user = android.os.Process.myUid() / 100000;
        worker.execute(() -> {
            String result;
            try { result = remote.restore(window.id, display, user, work.left, work.top, work.right, work.bottom); }
            catch (Exception error) { checkFailure(remote); result = "Could not restore window: " + error.getMessage(); }
            final String error = result;
            main.post(() -> {
                if (closed) return;
                if (!error.isEmpty()) android.widget.Toast.makeText(activity, error, android.widget.Toast.LENGTH_LONG).show();
                refresh();
            });
        });
    }

    void activate(String packageName, Runnable noRetainedTask) {
        if (closed || service == null) {
            android.widget.Toast.makeText(activity, "Connect Shizuku window tracking to restore existing windows", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        ITaskService remote = service;
        Rect area = activity.desktopWorkArea();
        final Rect work = area == null ? new Rect() : area;
        int display = activity.getWindowManager().getDefaultDisplay().getDisplayId(), user = android.os.Process.myUid() / 100000;
        worker.execute(() -> {
            String error;
            try { error = remote.activate(packageName, display, user, work.left, work.top, work.right, work.bottom); }
            catch (Exception failure) { checkFailure(remote); error = failure.getMessage(); }
            final String result = error;
            main.post(() -> {
                if (closed) return;
                if ("NO_TASK".equals(result)) noRetainedTask.run();
                else if (result != null && !result.isEmpty()) android.widget.Toast.makeText(activity, result, android.widget.Toast.LENGTH_LONG).show();
                refresh();
            });
        });
    }

    void forApp(String packageName, java.util.function.Consumer<List<RunningWindow>> callback) {
        if (closed || service == null) {
            android.widget.Toast.makeText(activity, "Connect Shizuku window tracking to list app windows", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        ITaskService remote = service;
        int display = activity.getWindowManager().getDefaultDisplay().getDisplayId(), user = android.os.Process.myUid() / 100000;
        worker.execute(() -> {
            List<RunningWindow> result = new ArrayList<>();
            String error = null;
            try {
                JSONObject snapshot = new JSONObject(remote.snapshot(display, user));
                if (snapshot.has("error")) throw new IllegalStateException(snapshot.getString("error"));
                JSONArray tasks = snapshot.getJSONArray("tasks");
                for (int i = 0; i < tasks.length(); i++) {
                    RunningWindow window = new RunningWindow(tasks.getJSONObject(i));
                    if (packageName.equals(window.packageName)) result.add(window);
                }
            } catch (Exception failure) { checkFailure(remote); error = failure.getMessage(); }
            final String failure = error;
            main.post(() -> {
                if (closed) return;
                if (failure != null) android.widget.Toast.makeText(activity, failure, android.widget.Toast.LENGTH_LONG).show();
                else callback.accept(result);
            });
        });
    }

    void minimize(RunningWindow window) {
        if (closed || service == null) return;
        ITaskService remote = service;
        int display = activity.getWindowManager().getDefaultDisplay().getDisplayId();
        int user = android.os.Process.myUid() / 100000;
        worker.execute(() -> {
            String error;
            try { error = remote.minimize(window.id, display, user); }
            catch (Exception failure) { checkFailure(remote); error = failure.getMessage(); }
            final String result = error;
            main.post(() -> {
                if (closed) return;
                if (result != null && !result.isEmpty()) android.widget.Toast.makeText(activity, result, android.widget.Toast.LENGTH_LONG).show();
                refresh();
            });
        });
    }

    private boolean lowering;
    private String lastOrderingError = "";
    void lowerDesktop() {
        if (closed || service == null || lowering) return;
        lowering = true;
        ITaskService remote = service;
        int task = activity.getTaskId(), display = activity.getWindowManager().getDefaultDisplay().getDisplayId();
        int user = android.os.Process.myUid() / 100000;
        worker.execute(() -> {
            String error;
            try { error = remote.lowerDesktop(task, display, user); }
            catch (Exception failure) { checkFailure(remote); error = failure.getMessage(); }
            final String result = error;
            main.post(() -> {
                lowering = false;
                if (closed) return;
                if (result != null && !result.isEmpty() && !result.equals(lastOrderingError)) {
                    lastOrderingError = result;
                    android.widget.Toast.makeText(activity, "Desktop ordering unavailable: " + result, android.widget.Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    void taskbarPolicy(boolean samsung) {
        if (!ready() || closed) return;
        ITaskService remote = service;
        worker.execute(() -> {
            String result;
            try { result = remote.taskbarPolicy(samsung); }
            catch (Exception error) { checkFailure(remote); result = error.getMessage(); }
            final String error = result;
            main.post(() -> {
                if (!closed && error != null && !error.isEmpty())
                    android.widget.Toast.makeText(activity, error, android.widget.Toast.LENGTH_LONG).show();
            });
        });
    }

    void wallpaper(java.util.function.Consumer<android.os.ParcelFileDescriptor> callback) {
        if (closed || service == null) { callback.accept(null); return; }
        ITaskService remote = service;
        int user = android.os.Process.myUid() / 100000;
        worker.execute(() -> {
            android.os.ParcelFileDescriptor file = null;
            try { file = remote.wallpaper(user); }
            catch (Exception ignored) { }
            final android.os.ParcelFileDescriptor ready = file;
            main.post(() -> {
                if (closed) {
                    if (ready != null) try { ready.close(); } catch (Exception ignored) { }
                } else callback.accept(ready);
            });
        });
    }

    void close() {
        closed = true; active = false;
        main.removeCallbacksAndMessages(null);
        Shizuku.removeBinderReceivedListener(received);
        Shizuku.removeBinderDeadListener(died);
        Shizuku.removeRequestPermissionResultListener(permission);
        forgetBinder();
        if (service != null || binding) {
            // Activity recreation must not kill the service used by the replacement Activity.
            try { Shizuku.unbindUserService(args, connection, false); }
            catch (RuntimeException ignored) { }
        }
        service = null;
        worker.shutdownNow();
    }
}
