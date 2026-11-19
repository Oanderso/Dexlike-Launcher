package com.dexlike.launcher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {
    private final int background = Color.rgb(21, 31, 47);
    private final int surface = Color.rgb(38, 54, 76);
    private final int textColor = Color.WHITE;
    private LinearLayout dock;
    private FrameLayout root;
    private LinearLayout drawer;
    private SharedPreferences prefs;
    private List<ResolveInfo> apps;
    private final ArrayList<String> recent = new ArrayList<>();
    private boolean drawerOpen = false;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        prefs = getSharedPreferences("desktop", MODE_PRIVATE);
        loadApps();
        buildDesktop();
    }

    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }

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

    private String key(ResolveInfo app) { return app.activityInfo.packageName + "/" + app.activityInfo.name; }

    private ResolveInfo find(String key) {
        for (ResolveInfo a : apps) if (key(a).equals(key)) return a;
        return null;
    }

    private void buildDesktop() {
        root = new FrameLayout(this);
        root.setBackgroundColor(background);
        setContentView(root);

        TextView title = label("Dexlike Desktop", 27);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(-2, dp(72), Gravity.TOP | Gravity.LEFT);
        titleParams.leftMargin = dp(22); titleParams.topMargin = dp(20);
        root.addView(title, titleParams);

        TextView hint = label("Open apps from the taskbar  •  Long-press an app to pin it", 13);
        hint.setTextColor(0xFFB4C4D6);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(-2, dp(35), Gravity.TOP | Gravity.LEFT);
        hintParams.leftMargin = dp(22); hintParams.topMargin = dp(88);
        root.addView(hint, hintParams);

        dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setPadding(dp(8), dp(5), dp(8), dp(5));
        dock.setBackgroundColor(surface);
        FrameLayout.LayoutParams dockParams = new FrameLayout.LayoutParams(-1, dp(64), Gravity.BOTTOM);
        root.addView(dock, dockParams);
        refreshDock();
    }

    private void refreshDock() {
        dock.removeAllViews();
        TextView menu = label("☰  Apps", 17);
        menu.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        menu.setOnClickListener(v -> toggleDrawer());
        dock.addView(menu, new LinearLayout.LayoutParams(dp(112), -1));

        View divider = new View(this); divider.setBackgroundColor(0xFF60738A);
        LinearLayout.LayoutParams div = new LinearLayout.LayoutParams(dp(1), dp(35));
        div.setMargins(dp(5), 0, dp(10), 0); dock.addView(divider, div);

        String pinned = prefs.getString("pinned", "");
        ArrayList<String> visible = new ArrayList<>();
        if (!pinned.isEmpty()) for (String key : pinned.split("\\|")) if (find(key) != null && !visible.contains(key)) visible.add(key);
        for (String key : recent) if (find(key) != null && !visible.contains(key)) visible.add(key);
        for (String key : visible) {
            ResolveInfo app = find(key);
            LinearLayout entry = new LinearLayout(this);
            entry.setOrientation(LinearLayout.VERTICAL);
            entry.setGravity(Gravity.CENTER);
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(app.loadIcon(getPackageManager()));
            entry.addView(icon, new LinearLayout.LayoutParams(dp(27), dp(27)));
            TextView name = label(app.loadLabel(getPackageManager()).toString(), 10);
            name.setSingleLine(true); name.setGravity(Gravity.CENTER);
            name.setPadding(dp(2), 0, dp(2), 0);
            entry.addView(name, new LinearLayout.LayoutParams(dp(90), dp(20)));
            entry.setOnClickListener(v -> launch(app));
            entry.setOnLongClickListener(v -> { togglePin(key); return true; });
            dock.addView(entry, new LinearLayout.LayoutParams(dp(98), -1));
        }
        Space space = new Space(this);
        dock.addView(space, new LinearLayout.LayoutParams(0, 1, 1));
        TextView clock = label(java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new java.util.Date()), 13);
        dock.addView(clock);
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

    private void launch(ResolveInfo app) {
        String key = key(app);
        recent.remove(key); recent.add(0, key);
        if (recent.size() > 8) recent.remove(recent.size() - 1);
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setComponent(new ComponentName(app.activityInfo.packageName, app.activityInfo.name));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            // Freeform mode is device-dependent; fall back to a normal launch if denied.
            ActivityOptions options = ActivityOptions.makeBasic();
            try {
                java.lang.reflect.Method method = ActivityOptions.class.getDeclaredMethod("setLaunchWindowingMode", int.class);
                method.setAccessible(true);
                method.invoke(options, 5);
            } catch (Exception ignored) { /* Samsung may restrict this hidden API. */ }
            try { startActivity(intent, options.toBundle()); }
            catch (Exception freeformError) { startActivity(intent); }
            if (drawerOpen) toggleDrawer();
            refreshDock();
        } catch (Exception e) {
            Toast.makeText(this, "Cannot launch app: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void toggleDrawer() {
        if (drawerOpen) { root.removeView(drawer); drawer = null; drawerOpen = false; return; }
        drawerOpen = true;
        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(0xFF2B4059);
        drawer.setPadding(dp(12), dp(12), dp(12), dp(12));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(380), -1, Gravity.LEFT | Gravity.TOP);
        params.bottomMargin = dp(64);
        root.addView(drawer, params);

        EditText search = new EditText(this);
        search.setSingleLine(true); search.setTextColor(Color.WHITE);
        search.setHintTextColor(0xFFBCCADA); search.setHint("Search applications");
        drawer.addView(search, new LinearLayout.LayoutParams(-1, dp(55)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        drawer.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Runnable render = () -> {
            list.removeAllViews();
            String filter = search.getText().toString().toLowerCase();
            for (ResolveInfo app : apps) {
                String title = app.loadLabel(getPackageManager()).toString();
                if (!title.toLowerCase().contains(filter)) continue;
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                ImageView icon = new ImageView(this);
                icon.setImageDrawable(app.loadIcon(getPackageManager()));
                row.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
                TextView name = label(title, 15);
                row.addView(name, new LinearLayout.LayoutParams(0, dp(52), 1));
                row.setOnClickListener(v -> launch(app));
                row.setOnLongClickListener(v -> { togglePin(key(app)); return true; });
                list.addView(row);
            }
        };
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { render.run(); }
            public void afterTextChanged(android.text.Editable s) {}
        });
        render.run();
    }

    @Override protected void onResume() { super.onResume(); if (dock != null) refreshDock(); }
    @Override public void onBackPressed() { if (drawerOpen) toggleDrawer(); else super.onBackPressed(); }
}
