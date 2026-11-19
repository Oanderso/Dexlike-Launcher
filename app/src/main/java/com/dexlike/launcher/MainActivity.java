package com.dexlike.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.provider.Settings;

import static android.accessibilityservice.AccessibilityService.*;
import android.app.ActivityOptions;
import android.graphics.Rect;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Build;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.graphics.drawable.ColorDrawable;
import java.util.LinkedHashMap;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {
    private static final int MIN_TASKBAR_ICON_SIZE = 16;
    private static final int MAX_TASKBAR_ICON_SIZE = 100;
    private static final int MIN_SHORTCUT_ICON_SIZE = 24;
    private static final int MAX_SHORTCUT_ICON_SIZE = 100;

    private final int background = Color.rgb(21, 31, 47);
    private final int surface = Color.rgb(38, 54, 76);
    private final int textColor = Color.WHITE;
    private LinearLayout dock;
    private FrameLayout root;
    private LinearLayout drawer;
    private SharedPreferences prefs;
    private List<ResolveInfo> apps;
    private final ArrayList<String> recent = new ArrayList<>();
    private LinearLayout dockApps;
    private DesktopWorkspace workspace;
    private WindowTracker windowTracker;
    private IconStyle icons;
    private Runnable renderDrawer;
    private EditText drawerSearch;
    private int lastFocusedWindow = -1;
    private List<RunningWindow> runningWindows = new ArrayList<>();
    private String windowStatus = "Connect Shizuku to track and restore every app window";
    private boolean windowSnapshotAvailable;
    private PopupWindow windowChooser;
    private TextView windowStatusView;
    private final ArrayList<Runnable> hoverActions = new ArrayList<>();
    private String dockSignature = "";
    private TextView quickSettings;
    private final BroadcastReceiver battery = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0);
            if (level >= 0 && scale > 0) {
                String percent = Math.round(level * 100f / scale) + "%";
                quickSettings.setText(percent + " ▾");
                quickSettings.setContentDescription("Quick Settings, battery " + percent);
            }
        }
    };

    private enum Placement { DEFAULT, CENTER, LEFT, RIGHT }

    private boolean drawerOpen = false;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            ArrayList<String> savedRecent = savedInstanceState.getStringArrayList("recent");
            if (savedRecent != null) recent.addAll(savedRecent);
        }
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        prefs = getSharedPreferences("desktop", MODE_PRIVATE);
        loadApps();
        icons = new IconStyle(this, prefs, () -> {
            dockSignature = "";
            renderDockApps();
            if (workspace != null) workspace.updateShortcutIconSizes();
            if (renderDrawer != null) renderDrawer.run();
        });
        buildDesktop(savedInstanceState);
        windowTracker = new WindowTracker(this, (windows, status) -> {
            boolean wasConnected = windowSnapshotAvailable;
            runningWindows = windows;
            for (RunningWindow window : windows) if (window.focused) lastFocusedWindow = window.id;
            windowStatus = status;
            windowSnapshotAvailable = status.startsWith("Connected") || status.startsWith("Tracking windows");
            if (windowStatusView != null) windowStatusView.setText(status);
            if (!wasConnected && windowSnapshotAvailable) workspace.refreshSystemWallpaper();
            renderDockApps();
            if (windowChooser != null && windowChooser.isShowing()
                    && !windowSnapshotAvailable) windowChooser.dismiss();
        });
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                if (insets.isVisible(WindowInsets.Type.navigationBars())) scheduleBarSuppression();
                return insets;
            });
        } else {
            getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(flags -> {
                if ((flags & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) scheduleBarSuppression();
            });
        }
        suppressSystemBars();
    }

    int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }

    private TextView label(String title, int size) {
        TextView t = new TextView(this);
        t.setText(title); t.setTextColor(textColor); t.setTextSize(size);
        t.setGravity(Gravity.CENTER_VERTICAL); t.setPadding(dp(12), 0, dp(12), 0);
        return t;
    }

    private void loadApps() {
        Intent query = new Intent(Intent.ACTION_MAIN);
        query.addCategory(Intent.CATEGORY_LAUNCHER);
        apps = getPackageManager().queryIntentActivities(query, 0);
        Collections.sort(apps, Comparator.comparing(a -> a.loadLabel(getPackageManager()).toString().toLowerCase()));
    }

    String key(ResolveInfo app) { return app.activityInfo.packageName + "/" + app.activityInfo.name; }

    ResolveInfo find(String key) {
        for (ResolveInfo a : apps) if (key(a).equals(key)) return a;
        return null;
    }

    private void buildDesktop(Bundle state) {
        root = new FrameLayout(this);
        root.setBackgroundColor(background);
        setContentView(root);

        workspace = new DesktopWorkspace(this, prefs, state);
        FrameLayout.LayoutParams workspaceParams = new FrameLayout.LayoutParams(-1, -1);
        workspaceParams.bottomMargin = taskbarHeight();
        root.addView(workspace, workspaceParams);

        dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setPadding(dp(8), dp(5), dp(8), dp(5));
        dock.setBackgroundColor(surface);
        FrameLayout.LayoutParams dockParams = new FrameLayout.LayoutParams(-1, taskbarHeight(), Gravity.BOTTOM);
        root.addView(dock, dockParams);
        refreshDock();
    }

    private void refreshDock() {
        if (dockApps != null) {
            renderDockApps();
            return;
        }
        TextView appsButton = taskbarButton("Apps", "Open apps", this::toggleDrawer);
        appsButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        appsButton.setOnLongClickListener(v -> { workspace.showMenu(v); return true; });
        appsButton.setOnContextClickListener(v -> { workspace.showMenu(v); return true; });
        taskbarButton("◁", "Back", () -> {
            if (drawerOpen) toggleDrawer();
            else runSystemAction(GLOBAL_ACTION_BACK);
        });
        taskbarButton("⌂", "Home", () -> {
            if (drawerOpen) toggleDrawer();
            DesktopActionsService.run(GLOBAL_ACTION_HOME);
        });
        taskbarButton("▤", "Recent apps", () -> runSystemAction(GLOBAL_ACTION_RECENTS));

        HorizontalScrollView dockScroll = new HorizontalScrollView(this);
        dockScroll.setHorizontalScrollBarEnabled(false);
        dock.addView(dockScroll, new LinearLayout.LayoutParams(0, -1, 1));
        dockApps = new LinearLayout(this);
        dockScroll.addView(dockApps, new FrameLayout.LayoutParams(-2, -1));

        taskbarButton("⚙", "Dexlike Launcher settings", this::showDesktopSettings);
        taskbarButton("◖))", "Volume and audio controls", this::showVolume);
        taskbarButton("▣", "Take screenshot", () -> runSystemAction(GLOBAL_ACTION_TAKE_SCREENSHOT));
        quickSettings = taskbarButton("▾", "Quick Settings", () -> runSystemAction(GLOBAL_ACTION_QUICK_SETTINGS));

        LinearLayout dateTime = new LinearLayout(this);
        dateTime.setOrientation(LinearLayout.VERTICAL);
        dateTime.setGravity(Gravity.CENTER_VERTICAL);
        dateTime.setPadding(dp(6), 0, dp(4), 0);
        TextClock clock = trayClock();
        dateTime.addView(clock, new LinearLayout.LayoutParams(-1, -2));
        TextClock date = trayClock();
        date.setFormat12Hour("EEE, MMM d");
        date.setFormat24Hour("EEE, MMM d");
        dateTime.addView(date, new LinearLayout.LayoutParams(-1, -2));
        dateTime.setOnClickListener(v -> runSystemAction(GLOBAL_ACTION_NOTIFICATIONS));
        dateTime.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host,
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setContentDescription(clock.getText() + ", " + date.getText() + ", Open notifications");
            }
        });
        dock.addView(dateTime, new LinearLayout.LayoutParams(-2, -1));
        renderDockApps();
    }

    private TextClock trayClock() {
        TextClock view = new TextClock(this);
        view.setTextColor(textColor);
        view.setTextSize(12);
        view.setGravity(Gravity.END);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private TextView taskbarButton(String text, String description, Runnable action) {
        TextView button = label(text, 14);
        button.setMinWidth(dp(40));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(5), 0, dp(5), 0);
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setOnClickListener(v -> action.run());
        dock.addView(button, new LinearLayout.LayoutParams(-2, -1));
        return button;
    }

    private void showVolume() {
        AudioManager audio = getSystemService(AudioManager.class);
        audio.adjustSuggestedStreamVolume(AudioManager.ADJUST_SAME,
                AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
    }

    private void runSystemAction(int action) {
        if (DesktopActionsService.run(action)) return;
        if (DesktopActionsService.isConnected()) {
            Toast.makeText(this, "That action is unavailable on this display", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Enable taskbar controls")
                .setMessage("In Android Accessibility settings, enable Dexlike taskbar controls to open system panels from the taskbar.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open settings", (dialog, choice) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(this, "Accessibility settings are unavailable", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    private int taskbarIconSize() {
        return prefs.getInt("taskbarIconSize", 27);
    }

    private int taskbarHeight() {
        return dp(Math.max(64, taskbarIconSize() + 34));
    }

    void useSystemWallpaper(boolean system) {
        getWindow().setBackgroundDrawable(new ColorDrawable(system ? Color.TRANSPARENT : background));
        root.setBackgroundColor(system ? Color.TRANSPARENT : background);
        if (system) {
            getWindow().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        }
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
    }

    private void updateTaskbarHeight() {
        FrameLayout.LayoutParams dockParams = (FrameLayout.LayoutParams) dock.getLayoutParams();
        dockParams.height = taskbarHeight();
        dock.setLayoutParams(dockParams);
        updateBottomMargin(workspace);
        if (drawer != null) updateBottomMargin(drawer);
        root.post(() -> { if (windowTracker != null) windowTracker.refresh(); });
    }

    private void updateBottomMargin(View view) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
        params.bottomMargin = taskbarHeight();
        view.setLayoutParams(params);
    }

    android.graphics.drawable.Drawable appIcon(ResolveInfo app) { return icons.icon(app); }

    void lowerDesktop() {
        if (windowTracker != null && !drawerOpen) windowTracker.lowerDesktop();
    }

    void requestWallpaper(java.util.function.Consumer<android.os.ParcelFileDescriptor> callback) {
        if (windowTracker != null) windowTracker.wallpaper(callback);
    }

    int shortcutIconSize() {
        return prefs.getInt("shortcutIconSize", 42);
    }

    void chooseDesktopApp() {
        loadApps();
        List<ResolveInfo> choices = new ArrayList<>();
        for (ResolveInfo app : apps) {
            if (!getPackageName().equals(app.activityInfo.packageName)) choices.add(app);
        }
        String[] labels = new String[choices.size()];
        for (int i = 0; i < choices.size(); i++) {
            labels[i] = choices.get(i).loadLabel(getPackageManager()).toString();
        }
        new AlertDialog.Builder(this).setTitle("Add app to desktop")
                .setItems(labels, (dialog, index) -> workspace.addShortcut(choices.get(index)))
                .setNegativeButton("Cancel", null).show();
    }

    void showDesktopSettings() {
        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setPadding(dp(16), dp(8), dp(16), dp(8));

        SeekBar taskbarSize = addSizeSetting(settings, "Taskbar icons", MIN_TASKBAR_ICON_SIZE,
                MAX_TASKBAR_ICON_SIZE, taskbarIconSize());
        SeekBar desktopSize = addSizeSetting(settings, "Desktop shortcut icons", MIN_SHORTCUT_ICON_SIZE,
                MAX_SHORTCUT_ICON_SIZE, shortcutIconSize());

        Switch iconGrid = new Switch(this);
        iconGrid.setText("Apps drawer: icons only, three columns");
        iconGrid.setTextColor(textColor);
        iconGrid.setChecked(prefs.getBoolean("drawerIconGrid", false));
        settings.addView(iconGrid);
        Button iconStyle = new Button(this);
        iconStyle.setText("Icon style: " + icons.title());
        iconStyle.setOnClickListener(v -> icons.choose(() -> iconStyle.setText("Icon style: " + icons.title())));
        settings.addView(iconStyle);
        Button home = new Button(this);
        home.setText("Set Dexlike as default home");
        home.setOnClickListener(v -> {
            android.app.role.RoleManager roles = getSystemService(android.app.role.RoleManager.class);
            if (roles != null && roles.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME))
                startActivityForResult(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), 201);
        });
        settings.addView(home);
        CheckBox suppressBars = new CheckBox(this);
        suppressBars.setText("Hide system bars on the desktop");
        suppressBars.setTextColor(textColor);
        suppressBars.setChecked(prefs.getBoolean("suppressSystemBars", true));
        settings.addView(suppressBars);
        TextView explanation = label("Samsung may briefly reveal its taskbar. Applies while Dexlike is focused.", 12);
        settings.addView(explanation);
        windowStatusView = label(windowStatus, 12);
        settings.addView(windowStatusView);
        Button connectWindows = new Button(this);
        connectWindows.setText("Connect window tracking with Shizuku");
        connectWindows.setOnClickListener(v -> windowTracker.requestAccess());
        settings.addView(connectWindows);
        TextView windowExplanation = label("Start Shizuku and allow Dexlike to list and restore individual windows. Samsung controls maximized window sizes.", 12);
        settings.addView(windowExplanation);
        ScrollView settingsScroll = new ScrollView(this);
        settingsScroll.addView(settings);
        new AlertDialog.Builder(this).setTitle("Dexlike Launcher settings").setView(settingsScroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    prefs.edit().putBoolean("drawerIconGrid", iconGrid.isChecked())
                            .putBoolean("suppressSystemBars", suppressBars.isChecked()).putInt("taskbarIconSize", MIN_TASKBAR_ICON_SIZE + taskbarSize.getProgress())
                            .putInt("shortcutIconSize", MIN_SHORTCUT_ICON_SIZE + desktopSize.getProgress()).apply();
                    updateTaskbarHeight();
                    renderDockApps();
                    workspace.updateShortcutIconSizes();
                    suppressSystemBars();
                    if (drawerOpen) { toggleDrawer(); toggleDrawer(); }
                }).show();
    }

    private SeekBar addSizeSetting(LinearLayout parent, String name, int min, int max, int size) {
        TextView value = label(name + " · " + size + " dp", 15);
        parent.addView(value, new LinearLayout.LayoutParams(-1, dp(36)));
        SeekBar slider = new SeekBar(this);
        slider.setContentDescription(name);
        slider.setMax(max - min);
        slider.setProgress(size - min);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                value.setText(name + " · " + (min + progress) + " dp");
            }

            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        parent.addView(slider, new LinearLayout.LayoutParams(-1, dp(40)));
        return slider;
    }

    private List<RunningWindow> windowsFor(String packageName) {
        List<RunningWindow> result = new ArrayList<>();
        for (RunningWindow window : runningWindows) if (packageName.equals(window.packageName)) result.add(window);
        return result;
    }

    private ResolveInfo launcherFor(String packageName) {
        for (ResolveInfo app : apps) if (packageName.equals(app.activityInfo.packageName)) return app;
        return null;
    }

    private String appTitle(String packageName, ResolveInfo app) {
        if (app != null) return app.loadLabel(getPackageManager()).toString();
        try { return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(packageName, 0)).toString(); }
        catch (PackageManager.NameNotFoundException ignored) { return packageName; }
    }

    private void renderDockApps() {
        if (dockApps == null) return;
        String pinned = prefs.getString("pinned", "");
        StringBuilder signature = new StringBuilder(pinned).append(taskbarIconSize()).append(windowSnapshotAvailable).append(icons.version());
        for (RunningWindow window : runningWindows) signature.append('|').append(window.id).append(window.packageName)
                .append(window.label).append(window.minimized).append(window.visible);
        if (!windowSnapshotAvailable) signature.append(recent.toString());
        if (dockSignature.equals(signature.toString())) return;
        dockSignature = signature.toString();
        for (Runnable action : hoverActions) root.removeCallbacks(action);
        hoverActions.clear();
        if (windowChooser != null) windowChooser.dismiss();
        dockApps.removeAllViews();
        int iconSize = dp(taskbarIconSize());
        LinkedHashMap<String, ResolveInfo> groups = new LinkedHashMap<>();
        if (!pinned.isEmpty()) for (String key : pinned.split("\\|")) {
            ResolveInfo app = find(key);
            if (app != null) groups.putIfAbsent(app.activityInfo.packageName, app);
        }
        for (RunningWindow window : runningWindows) groups.putIfAbsent(window.packageName, launcherFor(window.packageName));
        // Launch history is explicitly a fallback, never presented as a list of real windows.
        if (!windowSnapshotAvailable) for (String key : recent) {
            ResolveInfo app = find(key);
            if (app != null) groups.putIfAbsent(app.activityInfo.packageName, app);
        }
        for (String packageName : groups.keySet()) {
            ResolveInfo app = groups.get(packageName);
            String title = appTitle(packageName, app);
            LinearLayout entry = new LinearLayout(this);
            entry.setOrientation(LinearLayout.VERTICAL);
            entry.setGravity(Gravity.CENTER);
            ImageView icon = new ImageView(this);
            try { icon.setImageDrawable(app != null ? appIcon(app) : getPackageManager().getApplicationIcon(packageName)); }
            catch (PackageManager.NameNotFoundException ignored) { icon.setImageResource(android.R.drawable.sym_def_app_icon); }
            entry.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
            List<RunningWindow> windows = windowsFor(packageName);
            TextView name = label(title + (windows.isEmpty() ? "" : " · " + windows.size()), 10);
            name.setSingleLine(true); name.setGravity(Gravity.CENTER);
            name.setPadding(dp(2), 0, dp(2), 0);
            entry.addView(name, new LinearLayout.LayoutParams(dp(Math.max(90, taskbarIconSize() + 8)), dp(20)));
            entry.setContentDescription(title + (windows.isEmpty() ? "" : ", " + windows.size() + " open windows"));
            entry.setOnClickListener(v -> {
                List<RunningWindow> current = windowsFor(packageName);
                RunningWindow selected = null;
                for (RunningWindow window : current) if (window.id == lastFocusedWindow && window.visible) selected = window;
                if (selected != null) windowTracker.minimize(selected);
                else if (current.size() == 1) {
                    RunningWindow only = current.get(0);
                    if (only.visible && !only.minimized) windowTracker.minimize(only);
                    else windowTracker.restore(only);
                } else if (!current.isEmpty()) showWindows(entry, packageName);
                else if (app != null) launch(app);
            });
            if (!windows.isEmpty()) {
                View indicator = new View(this);
                indicator.setBackgroundColor(0xFF98BDE9);
                entry.addView(indicator, new LinearLayout.LayoutParams(dp(16), dp(2)));
            }
            Runnable show = () -> { if (entry.isAttachedToWindow() && hasWindowFocus()) showWindows(entry, packageName); };
            hoverActions.add(show);
            entry.setOnHoverListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_HOVER_ENTER) root.postDelayed(show, 2000);
                if (event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT) root.removeCallbacks(show);
                return false;
            });
            entry.setOnLongClickListener(v -> {
                if (!windowsFor(packageName).isEmpty()) showWindows(entry, packageName);
                else if (app != null) showAppMenu(v, app);
                return true;
            });
            if (app != null) entry.setOnContextClickListener(v -> { showAppMenu(v, app); return true; });
            dockApps.addView(entry, new LinearLayout.LayoutParams(dp(Math.max(98, taskbarIconSize() + 16)), -1));
        }
    }

    private void showWindows(View anchor, String packageName) {
        List<RunningWindow> windows = windowsFor(packageName);
        if (windows.isEmpty()) return;
        if (windowChooser != null) windowChooser.dismiss();
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        list.setBackgroundColor(surface);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        PopupWindow chooser = new PopupWindow(scroll, Math.min(dp(360), root.getWidth()), -2, true);
        windowChooser = chooser;
        chooser.setBackgroundDrawable(new ColorDrawable(surface));
        chooser.setElevation(dp(12));
        chooser.setOutsideTouchable(true);
        for (RunningWindow window : windows) {
            String title = window.label.isEmpty() ? appTitle(packageName, launcherFor(packageName)) : window.label;
            TextView row = label(title + "\nWindow " + window.id + (window.minimized ? " · Minimized" : ""), 14);
            row.setMaxLines(2);
            row.setFocusable(true);
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            row.setOnClickListener(v -> { chooser.dismiss(); windowTracker.restore(window); });
            list.addView(row, new LinearLayout.LayoutParams(-1, dp(64)));
        }
        ResolveInfo app = launcherFor(packageName);
        if (app != null) {
            TextView create = label("Open new window", 14);
            create.setOnClickListener(v -> { chooser.dismiss(); launch(app, Placement.DEFAULT, true); });
            list.addView(create, new LinearLayout.LayoutParams(-1, dp(48)));
        }
        int height = Math.min(Math.max(dp(64), dock.getTop()), windows.size() * dp(64) + dp(app == null ? 16 : 64));
        chooser.setHeight(height);
        int[] origin = new int[2], location = new int[2];
        root.getLocationInWindow(origin); anchor.getLocationInWindow(location);
        chooser.showAtLocation(root, Gravity.TOP | Gravity.LEFT,
                Math.max(origin[0], Math.min(location[0], origin[0] + root.getWidth() - chooser.getWidth())),
                Math.max(origin[1], origin[1] + dock.getTop() - height));
    }

    private void bindAppMenu(View anchor, ResolveInfo app) {
        anchor.setOnLongClickListener(v -> {
            showAppMenu(v, app);
            return true;
        });
        anchor.setOnContextClickListener(v -> {
            showAppMenu(v, app);
            return true;
        });
    }

    private void showAppMenu(View anchor, ResolveInfo app) {
        String appKey = key(app);
        boolean pinned = java.util.Arrays.asList(prefs.getString("pinned", "").split("\\|")).contains(appKey);
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(pinned ? "Unpin from dock" : "Pin to dock")
                .setOnMenuItemClickListener(item -> { togglePin(appKey); return true; });
        if (!windowsFor(app.activityInfo.packageName).isEmpty()) {
            menu.getMenu().add("Show open windows").setOnMenuItemClickListener(item -> {
                showWindows(anchor, app.activityInfo.packageName); return true;
            });
        }
        menu.getMenu().add("Open new window").setOnMenuItemClickListener(item -> {
            launch(app, Placement.DEFAULT, true); return true;
        });
        menu.getMenu().add("Add to desktop").setOnMenuItemClickListener(item -> {
            workspace.addShortcut(app);
            if (drawerOpen) toggleDrawer();
            return true;
        });
        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)) {
            addPlacement(menu, app, "Open centered", Placement.CENTER);
            addPlacement(menu, app, "Open on left", Placement.LEFT);
            addPlacement(menu, app, "Open on right", Placement.RIGHT);
        }
        if (recent.contains(appKey)) {
            menu.getMenu().add("Clear from recent apps").setOnMenuItemClickListener(item -> {
                recent.remove(appKey);
                refreshDock();
                return true;
            });
        }
        menu.show();
    }

    private void addPlacement(PopupMenu menu, ResolveInfo app, String title, Placement placement) {
        menu.getMenu().add(title).setOnMenuItemClickListener(item -> {
            launch(app, placement);
            return true;
        });
    }

    private void togglePin(String key) {
        ArrayList<String> pins = new ArrayList<>();
        String saved = prefs.getString("pinned", "");
        if (!saved.isEmpty()) Collections.addAll(pins, saved.split("\\|"));
        if (pins.contains(key)) pins.remove(key); else pins.add(key);
        prefs.edit().putString("pinned", android.text.TextUtils.join("|", pins)).apply();
        Toast.makeText(this, pins.contains(key) ? "Pinned" : "Unpinned", Toast.LENGTH_SHORT).show();
        refreshDock();
    }

    void launch(ResolveInfo app) {
        launch(app, Placement.DEFAULT);
    }

    // Keep the existing optional windowing request and fall back when the device blocks it.
    @android.annotation.SuppressLint("BlockedPrivateApi")
    private void launch(ResolveInfo app, Placement placement) {
        launch(app, placement, false);
    }

    @android.annotation.SuppressLint("BlockedPrivateApi")
    private void launch(ResolveInfo app, Placement placement, boolean newWindow) {
        String key = key(app);
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setComponent(new ComponentName(app.activityInfo.packageName, app.activityInfo.name));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (newWindow) intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
        try {
            // Freeform mode is device-dependent; fall back to a normal launch if denied.
            ActivityOptions options = ActivityOptions.makeBasic();
            try {
                java.lang.reflect.Method method = ActivityOptions.class.getDeclaredMethod("setLaunchWindowingMode", int.class);
                method.setAccessible(true);
                method.invoke(options, 5);
            } catch (Exception ignored) { /* Samsung may restrict this hidden API. */ }
            Rect bounds = launchBounds(placement);
            if (bounds != null) options.setLaunchBounds(bounds);
            try { startActivity(intent, options.toBundle()); }
            catch (Exception freeformError) { startActivity(intent); }
            if (!recent.contains(key)) {
                recent.add(key);
                if (recent.size() > 8) recent.remove(0);
            }
            if (drawerOpen) toggleDrawer();
            refreshDock();
        } catch (Exception e) {
            Toast.makeText(this, "Cannot launch app: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    Rect desktopWorkArea() {
        if (root == null || root.getWidth() <= 0 || root.getHeight() <= taskbarHeight()) return null;
        int[] origin = new int[2];
        root.getLocationOnScreen(origin);
        return new Rect(origin[0], origin[1], origin[0] + root.getWidth(),
                origin[1] + root.getHeight() - taskbarHeight());
    }

    private Rect launchBounds(Placement placement) {
        Rect workspace = desktopWorkArea();
        if (workspace == null) return null;
        int gap = dp(8);
        workspace.inset(gap, gap);
        if (workspace.width() < gap * 2 || workspace.height() < gap * 2) return null;

        int middle = workspace.centerX();
        switch (placement) {
            case LEFT:
                workspace.right = middle - gap / 2;
                break;
            case RIGHT:
                workspace.left = middle + gap / 2;
                break;
            case DEFAULT:
            case CENTER:
                workspace.inset(workspace.width() / 8, workspace.height() / 8);
                break;
            default:
                break;
        }
        return workspace;
    }

    private void toggleDrawer() {
        if (drawerOpen) {
            if (drawerSearch != null) getSystemService(android.view.inputmethod.InputMethodManager.class)
                    .hideSoftInputFromWindow(drawerSearch.getWindowToken(), 0);
            root.removeView(drawer); drawer = null; drawerSearch = null;
            renderDrawer = null; drawerOpen = false;
            return;
        }
        drawerOpen = true;
        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setClickable(true);
        drawer.setBackgroundColor(0xFF2B4059);
        drawer.setPadding(dp(12), dp(12), dp(12), dp(12));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(380), -1, Gravity.LEFT | Gravity.TOP);
        params.bottomMargin = taskbarHeight();
        root.addView(drawer, params);

        EditText search = new EditText(this);
        drawerSearch = search;
        search.setSingleLine(true); search.setTextColor(Color.WHITE);
        search.setHintTextColor(0xFFBCCADA); search.setHint("Search applications");
        drawer.addView(search, new LinearLayout.LayoutParams(-1, dp(55)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        drawer.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        renderDrawer = () -> {
            list.removeAllViews();
            String filter = search.getText().toString().toLowerCase(java.util.Locale.ROOT);
            boolean grid = prefs.getBoolean("drawerIconGrid", false);
            LinearLayout gridRow = null;
            int column = 0;
            for (ResolveInfo app : apps) {
                String title = app.loadLabel(getPackageManager()).toString();
                if (!title.toLowerCase(java.util.Locale.ROOT).contains(filter)) continue;
                LinearLayout entry = new LinearLayout(this);
                entry.setGravity(Gravity.CENTER);
                ImageView icon = new ImageView(this);
                icon.setImageDrawable(appIcon(app));
                if (grid) {
                    if (column == 0) {
                        gridRow = new LinearLayout(this);
                        list.addView(gridRow, new LinearLayout.LayoutParams(-1, dp(88)));
                    }
                    entry.setContentDescription(title);
                    entry.setTooltipText(title);
                    entry.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
                    gridRow.addView(entry, new LinearLayout.LayoutParams(0, -1, 1));
                    column = (column + 1) % 3;
                } else {
                    entry.setGravity(Gravity.CENTER_VERTICAL);
                    entry.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
                    TextView name = label(title, 15);
                    entry.addView(name, new LinearLayout.LayoutParams(0, dp(52), 1));
                    list.addView(entry);
                }
                entry.setFocusable(true);
                entry.setBackgroundResource(android.R.drawable.list_selector_background);
                entry.setOnClickListener(v -> launch(app));
                bindAppMenu(entry, app);
            }
            if (grid && column != 0) for (int i = column; i < 3; i++) {
                gridRow.addView(new View(this), new LinearLayout.LayoutParams(0, -1, 1));
            }
        };
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { renderDrawer.run(); }
            public void afterTextChanged(android.text.Editable s) {}
        });
        renderDrawer.run();
        search.requestFocus();
        search.post(() -> {
            if (drawerOpen && drawerSearch == search) {
                search.requestFocus();
                getSystemService(android.view.inputmethod.InputMethodManager.class)
                        .showSoftInput(search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putStringArrayList("recent", new ArrayList<>(recent));
        workspace.saveState(state);
    }

    @Override protected void onStart() {
        super.onStart();
        workspace.onStart();
        if (windowTracker != null) windowTracker.start();
        registerReceiver(battery, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    @Override protected void onStop() {
        unregisterReceiver(battery);
        workspace.onStop();
        if (windowTracker != null) windowTracker.stop();
        for (Runnable action : hoverActions) root.removeCallbacks(action);
        if (windowChooser != null) windowChooser.dismiss();
        root.removeCallbacks(rehideBars);
        super.onStop();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        workspace.onResult(request, result, data);
    }

    // Reassert the request on reveal/focus changes, without a permanent polling loop.
    private final Runnable rehideBars = this::suppressSystemBars;

    private void scheduleBarSuppression() {
        if (root == null || !hasWindowFocus() || !prefs.getBoolean("suppressSystemBars", true)) return;
        root.removeCallbacks(rehideBars);
        root.postDelayed(rehideBars, 100);
    }

    private void suppressSystemBars() {
        if (root == null || !hasWindowFocus()) return;
        boolean hide = prefs.getBoolean("suppressSystemBars", true);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (hide) controller.hide(WindowInsets.Type.systemBars());
                else controller.show(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(hide
                    ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN : 0);
        }
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) {
            suppressSystemBars();
            if (root != null && workspace != null && !workspace.isEditing()) root.post(this::lowerDesktop);
        }
        else if (root != null) root.removeCallbacks(rehideBars);
    }

    @Override protected void onResume() {
        super.onResume();
        if (dock != null) refreshDock();
        scheduleBarSuppression();
    }
    @Override protected void onDestroy() {
        if (windowTracker != null) windowTracker.close();
        if (icons != null) icons.close();
        super.onDestroy();
    }

    @Override public void onBackPressed() { if (drawerOpen) toggleDrawer(); else super.onBackPressed(); }
}
