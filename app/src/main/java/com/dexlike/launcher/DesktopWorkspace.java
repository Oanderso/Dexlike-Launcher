package com.dexlike.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.ImageDecoder;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.ListView;
import android.widget.BaseAdapter;
import android.widget.RemoteViews;
import android.view.ViewGroup;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class DesktopWorkspace extends FrameLayout {
    private static final int PICK_WALLPAPER = 101;
    private static final int BIND_WIDGET = 102;
    private static final int CONFIGURE_WIDGET = 103;
    private static final int TOOLBAR = 32;
    private static final int STEP = 16;
    private static final int GRID_MARGIN = 12;
    private static final int SHORTCUT_WIDTH = 96;
    private static final int SHORTCUT_HEIGHT = 92;

    private final MainActivity activity;
    private final SharedPreferences prefs;
    private final AppWidgetHost host;
    private final AppWidgetManager manager;
    private final ImageView wallpaper;
    private final ArrayList<Item> items = new ArrayList<>();
    private int pendingWidget = -1;
    private int wallpaperVersion;
    private float menuX, menuY;
    private Item selectedWidget;
    private PopupWindow desktopMenu;
    private boolean blankGesture, blankMenuOpened;
    private final BroadcastReceiver wallpaperChanges = new BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, Intent intent) { refreshSystemWallpaper(); }
    };

    private static final class Item {
        String shortcut;
        int widget = -1;
        int x, y, width, height;
        FrameLayout view;
        ImageView icon;
        final ArrayList<View> controls = new ArrayList<>();
    }

    DesktopWorkspace(MainActivity activity, SharedPreferences prefs, Bundle state) {
        super(activity);
        this.activity = activity;
        this.prefs = prefs;
        host = new AppWidgetHost(activity, 1);
        manager = AppWidgetManager.getInstance(activity);
        if (state != null) pendingWidget = state.getInt("pendingWidget", -1);
        wallpaper = new ImageView(activity);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(wallpaper, new LayoutParams(-1, -1));
        setOnLongClickListener(v -> { showMenu(this); return true; });
        setOnContextClickListener(v -> { showMenu(this); return true; });
        restore();
        loadWallpaper();
    }

    void onStart() {
        host.startListening();
        activity.registerReceiver(wallpaperChanges, new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED));
        refreshSystemWallpaper();
    }
    void onStop() {
        host.stopListening();
        activity.unregisterReceiver(wallpaperChanges);
        selectWidget(null);
        if (desktopMenu != null) desktopMenu.dismiss();
    }
    void saveState(Bundle state) { state.putInt("pendingWidget", pendingWidget); }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            blankGesture = true;
            blankMenuOpened = false;
            for (Item item : items) {
                if (item.view.getVisibility() != VISIBLE) continue;
                Rect hit = new Rect();
                item.view.getHitRect(hit);
                if (hit.contains((int) event.getX(), (int) event.getY())) { blankGesture = false; break; }
            }
        }
        if (blankGesture && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
        }
        // Route empty-desktop gestures directly to the workspace, never to a retained child target.
        boolean handled = blankGesture ? onTouchEvent(event) : super.dispatchTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP && selectedWidget == null && !blankMenuOpened) {
            activity.lowerDesktop();
        }
        return handled;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            menuX = event.getX();
            menuY = event.getY();
            selectWidget(null);
        }
        return super.onTouchEvent(event);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        menuX = event.getX();
        menuY = event.getY();
        return super.dispatchGenericMotionEvent(event);
    }

    void showMenu(View anchor) {
        if (anchor == this) blankMenuOpened = true;
        selectWidget(null);
        if (desktopMenu != null) desktopMenu.dismiss();
        LinearLayout entries = new LinearLayout(activity);
        entries.setOrientation(LinearLayout.VERTICAL);
        entries.setPadding(dp(4), dp(4), dp(4), dp(4));
        entries.setBackgroundColor(0xFF26364C);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(entries);
        PopupWindow menu = new PopupWindow(scroll, Math.min(dp(240), getWidth()), -2, true);
        desktopMenu = menu;
        menu.setBackgroundDrawable(new ColorDrawable(0xFF26364C));
        menu.setElevation(dp(12));
        menu.setOutsideTouchable(true);
        addDesktopMenuEntry(entries, menu, "Add app", activity::chooseDesktopApp);
        addDesktopMenuEntry(entries, menu, "Add widget", this::chooseWidget);
        addDesktopMenuEntry(entries, menu, "Settings", activity::showDesktopSettings);
        addDesktopMenuEntry(entries, menu, "Use system wallpaper", () -> {
            prefs.edit().putBoolean("systemWallpaper", true).apply();
            loadWallpaper();
        });
        addDesktopMenuEntry(entries, menu, "Choose wallpaper", () -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                    .addCategory(Intent.CATEGORY_OPENABLE);
            try { activity.startActivityForResult(pick, PICK_WALLPAPER); }
            catch (ActivityNotFoundException e) { message("No image picker is available"); }
        });
        if (prefs.contains("wallpaper")) {
            addDesktopMenuEntry(entries, menu, "Clear wallpaper", () -> changeWallpaper(null));
        }
        int[] origin = new int[2];
        getLocationInWindow(origin);
        int x = Math.round(menuX), y = Math.round(menuY);
        if (anchor != this) {
            int[] location = new int[2];
            anchor.getLocationInWindow(location);
            x = location[0] - origin[0];
            y = location[1] - origin[1];
        }
        int height = Math.min(getHeight(), entries.getChildCount() * dp(48) + dp(8));
        menu.setHeight(height);
        menu.showAtLocation(this, Gravity.TOP | Gravity.LEFT,
                origin[0] + clamp(x, 0, Math.max(0, getWidth() - menu.getWidth())),
                origin[1] + clamp(y, 0, Math.max(0, getHeight() - height)));
    }

    private void addDesktopMenuEntry(LinearLayout entries, PopupWindow menu, String title, Runnable action) {
        TextView entry = text(title, 15);
        entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setPadding(dp(16), 0, dp(16), 0);
        entry.setBackgroundResource(android.R.drawable.list_selector_background);
        entry.setOnClickListener(v -> { menu.dismiss(); action.run(); });
        entries.addView(entry, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    private int shortcutWidth() { return DesktopGeometry.cellWidth(activity.shortcutIconSize()); }
    private int shortcutHeight() { return DesktopGeometry.cellHeight(activity.shortcutIconSize()); }

    void updateShortcutIconSizes() {
        int size = dp(activity.shortcutIconSize());
        for (Item item : items) {
            if (item.icon != null) {
                item.width = shortcutWidth(); item.height = shortcutHeight();
                item.icon.setLayoutParams(new LinearLayout.LayoutParams(size, size));
                ResolveInfo app = activity.find(item.shortcut);
                if (app != null) item.icon.setImageDrawable(activity.appIcon(app));
            }
        }
        layoutShortcuts();
        rememberVisibleShortcutSlots();
        save();
    }

    private void changeWallpaper(Uri next) {
        String previous = prefs.getString("wallpaper", null);
        if (previous != null && (next == null || !previous.equals(next.toString()))) {
            try {
                activity.getContentResolver().releasePersistableUriPermission(Uri.parse(previous),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) { }
        }
        prefs.edit().putBoolean("systemWallpaper", next == null)
                .putString("wallpaper", next == null ? null : next.toString()).apply();
        loadWallpaper();
    }

    private void loadWallpaper() {
        final int version = ++wallpaperVersion;
        boolean system = prefs.getBoolean("systemWallpaper", !prefs.contains("wallpaper"));
        activity.useSystemWallpaper(system);
        String source = system ? null : prefs.getString("wallpaper", null);
        if (source == null) {
            wallpaper.setImageDrawable(null);
            if (system) loadSystemWallpaperFallback(version);
            return;
        }
        int maxEdge = Math.min(4096, Math.max(
                getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels));
        new Thread(() -> {
            try {
                var bitmap = ImageDecoder.decodeBitmap(
                        ImageDecoder.createSource(activity.getContentResolver(), Uri.parse(source)),
                        (decoder, info, imageSource) -> {
                            int edge = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                            decoder.setTargetSampleSize(Math.max(1, (edge + maxEdge - 1) / maxEdge));
                        });
                activity.runOnUiThread(() -> {
                    if (version == wallpaperVersion && !activity.isDestroyed()) wallpaper.setImageBitmap(bitmap);
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    if (version == wallpaperVersion && !activity.isDestroyed()) message("Could not load wallpaper");
                });
            }
        }, "desktop-wallpaper").start();
    }

    boolean isEditing() { return selectedWidget != null; }

    void refreshSystemWallpaper() {
        if (prefs.getBoolean("systemWallpaper", !prefs.contains("wallpaper"))) loadWallpaper();
    }

    private void loadSystemWallpaperFallback(int version) {
        activity.requestWallpaper(file -> {
            if (file == null) return; // The native wallpaper surface is still requested without Shizuku.
            new Thread(() -> {
                try (android.os.ParcelFileDescriptor descriptor = file) {
                    android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeFileDescriptor(descriptor.getFileDescriptor(), null, options);
                    int edge = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
                    options.inSampleSize = Math.max(1, Math.max(options.outWidth, options.outHeight) / Math.max(1, edge));
                    options.inJustDecodeBounds = false;
                    android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFileDescriptor(descriptor.getFileDescriptor(), null, options);
                    activity.runOnUiThread(() -> {
                        if (!activity.isDestroyed() && version == wallpaperVersion && bitmap != null) wallpaper.setImageBitmap(bitmap);
                    });
                } catch (Exception ignored) { }
            }, "system-wallpaper").start();
        });
    }

    void addShortcut(ResolveInfo app) {
        String key = activity.key(app);
        for (Item item : items) {
            if (key.equals(item.shortcut)) {
                message("Shortcut is already on the desktop");
                return;
            }
        }
        Item item = new Item();
        item.shortcut = key;
        item.width = shortcutWidth();
        item.height = shortcutHeight();
        Point slot = nearestShortcutSlot(item, GRID_MARGIN, GRID_MARGIN, occupiedShortcutSlots(item));
        if (slot == null) {
            message("The desktop shortcut grid is full");
            return;
        }
        rememberVisibleShortcutSlots();
        item.x = slot.x;
        item.y = slot.y;
        addItem(item);
    }

    private void chooseWidget() {
        if (pendingWidget != -1) return;
        List<AppWidgetProviderInfo> providers = new ArrayList<>(manager.getInstalledProviders());
        var pm = activity.getPackageManager();
        providers.sort(Comparator.comparing(info -> info.loadLabel(pm), String.CASE_INSENSITIVE_ORDER));
        if (providers.isEmpty()) {
            message("No widgets are available");
            return;
        }
        ListView list = new ListView(activity);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Add widget")
                .setView(list).setNegativeButton("Cancel", null).create();
        java.util.concurrent.ExecutorService previews = java.util.concurrent.Executors.newFixedThreadPool(2);
        dialog.setOnDismissListener(ignored -> previews.shutdownNow());
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return providers.size(); }
            @Override public Object getItem(int position) { return providers.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                AppWidgetProviderInfo info = providers.get(position);
                LinearLayout card = recycled instanceof LinearLayout ? (LinearLayout) recycled : new LinearLayout(activity);
                card.removeAllViews();
                card.setTag(info);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(16), dp(12), dp(16), dp(12));
                FrameLayout preview = new FrameLayout(activity);
                preview.setBackgroundColor(0xFFEDF1F6);
                card.addView(preview, new LinearLayout.LayoutParams(-1, dp(160)));
                TextView title = text(info.loadLabel(pm), 16);
                card.addView(title, new LinearLayout.LayoutParams(-1, dp(36)));
                TextView details = text(info.provider.getPackageName(), 12);
                details.setSingleLine(true);
                card.addView(details, new LinearLayout.LayoutParams(-1, dp(24)));
                TextView placeholder = text("Loading preview…", 13);
                placeholder.setTextColor(0xFF26364C);
                preview.addView(placeholder, new LayoutParams(-1, -1));
                previews.execute(() -> {
                    Drawable image = null;
                    try { image = info.loadPreviewImage(activity, getResources().getDisplayMetrics().densityDpi); }
                    catch (RuntimeException ignored) { }
                    final Drawable loaded = image;
                    activity.runOnUiThread(() -> {
                        if (!dialog.isShowing() || activity.isDestroyed() || card.getTag() != info) return;
                        preview.removeAllViews();
                        if (loaded != null) {
                            ImageView picture = new ImageView(activity);
                            picture.setImageDrawable(loaded);
                            picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
                            picture.setContentDescription(info.loadLabel(pm) + " widget preview");
                            preview.addView(picture, new LayoutParams(-1, -1));
                            return;
                        }
                        if (Build.VERSION.SDK_INT >= 31 && info.previewLayout != 0) {
                            try {
                                View layout = new RemoteViews(info.provider.getPackageName(), info.previewLayout)
                                        .apply(activity, preview);
                                preview.addView(layout, new LayoutParams(-1, -1));
                                // Preview must not steal selection taps or operate real widget controls.
                                View shield = new View(activity);
                                preview.addView(shield, new LayoutParams(-1, -1));
                                shield.setOnClickListener(v -> { dialog.dismiss(); bindWidget(info); });
                                return;
                            } catch (RuntimeException ignored) { }
                        }
                        ImageView icon = new ImageView(activity);
                        try { icon.setImageDrawable(info.loadIcon(activity, getResources().getDisplayMetrics().densityDpi)); }
                        catch (RuntimeException ignored) { }
                        preview.addView(icon, new LayoutParams(dp(56), dp(56), Gravity.CENTER));
                        TextView unavailable = text("No preview provided", 12);
                        unavailable.setTextColor(0xFF26364C);
                        preview.addView(unavailable, new LayoutParams(-1, dp(30), Gravity.BOTTOM));
                    });
                });
                return card;
            }
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            bindWidget(providers.get(position));
        });
        dialog.show();
        dialog.getWindow().setLayout(Math.min(dp(560), getResources().getDisplayMetrics().widthPixels),
                Math.round(getResources().getDisplayMetrics().heightPixels * .8f));
    }

    private void bindWidget(AppWidgetProviderInfo provider) {
        pendingWidget = host.allocateAppWidgetId();
        try {
            if (manager.bindAppWidgetIdIfAllowed(pendingWidget, provider.provider)) {
                configureWidget();
                return;
            }
            Intent bind = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidget)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider);
            activity.startActivityForResult(bind, BIND_WIDGET);
        } catch (ActivityNotFoundException | SecurityException e) {
            cancelWidget();
            message("Widget binding is unavailable");
        }
    }

    private void configureWidget() {
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(pendingWidget);
        if (info == null) {
            cancelWidget();
            return;
        }
        if (info.configure == null ||
                (info.widgetFeatures & AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0) {
            finishWidget(info);
            return;
        }
        try {
            host.startAppWidgetConfigureActivityForResult(activity, pendingWidget, 0, CONFIGURE_WIDGET, null);
        } catch (ActivityNotFoundException | SecurityException e) {
            cancelWidget();
            message("Widget configuration is unavailable");
        }
    }

    boolean onResult(int request, int result, Intent data) {
        if (request == PICK_WALLPAPER) {
            if (result == Activity.RESULT_OK && data != null && data.getData() != null) {
                try {
                    activity.getContentResolver().takePersistableUriPermission(data.getData(),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    changeWallpaper(data.getData());
                } catch (SecurityException e) {
                    message("Could not retain access to that image");
                }
            }
            return true;
        }
        if (request != BIND_WIDGET && request != CONFIGURE_WIDGET) return false;
        if (pendingWidget == -1) return true;
        if (result != Activity.RESULT_OK) cancelWidget();
        else if (request == BIND_WIDGET) configureWidget();
        else {
            AppWidgetProviderInfo info = manager.getAppWidgetInfo(pendingWidget);
            if (info == null) cancelWidget();
            else finishWidget(info);
        }
        return true;
    }

    private void cancelWidget() {
        if (pendingWidget != -1) host.deleteAppWidgetId(pendingWidget);
        pendingWidget = -1;
    }

    private void finishWidget(AppWidgetProviderInfo info) {
        Item item = new Item();
        item.widget = pendingWidget;
        pendingWidget = -1;
        item.width = Math.max(144, units(info.minWidth));
        item.height = Math.max(96, units(info.minHeight));
        place(item);
        addItem(item);
    }

    private void place(Item item) {
        int width = units(getWidth());
        int height = units(getHeight());
        for (int y = 12; y + item.height <= height; y += STEP) {
            for (int x = 12; x + item.width <= width; x += STEP) {
                Rect candidate = new Rect(x, y, x + item.width, y + item.height);
                boolean occupied = false;
                for (Item existing : items) {
                    if (Rect.intersects(candidate, new Rect(existing.x, existing.y,
                            existing.x + existing.width, existing.y + existing.height))) {
                        occupied = true;
                        break;
                    }
                }
                if (!occupied) {
                    item.x = x;
                    item.y = y;
                    return;
                }
            }
        }
        item.x = item.y = 12 + STEP * (items.size() % 5);
    }

    private Point nearestShortcutSlot(Item item, int x, int y, List<Point> occupied) {
        Point best = null;
        long bestDistance = Long.MAX_VALUE;
        int maxX = Math.max(0, units(getWidth()) - item.width);
        int maxY = Math.max(0, units(getHeight()) - item.height);
        for (int row = Math.min(GRID_MARGIN, maxY); row <= maxY; row += shortcutHeight()) {
            for (int column = Math.min(GRID_MARGIN, maxX); column <= maxX; column += shortcutWidth()) {
                Point slot = new Point(column, row);
                if (occupied.contains(slot)) continue;
                boolean onWidget = false;
                for (Item widget : items) {
                    if (widget.widget == -1 || widget.view == null) continue;
                    LayoutParams bounds = (LayoutParams) widget.view.getLayoutParams();
                    if (DesktopGeometry.overlaps(column, row, item.width, item.height,
                            units(bounds.leftMargin), units(bounds.topMargin), units(bounds.width), units(bounds.height))) {
                        onWidget = true; break;
                    }
                }
                if (onWidget) continue;
                long dx = column - x;
                long dy = row - y;
                long distance = dx * dx + dy * dy;
                if (distance < bestDistance) {
                    best = slot;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private List<Point> occupiedShortcutSlots(Item excluded) {
        List<Point> slots = new ArrayList<>();
        for (Item item : items) {
            if (item != excluded && item.shortcut != null && item.view.getVisibility() == VISIBLE) {
                LayoutParams lp = (LayoutParams) item.view.getLayoutParams();
                slots.add(new Point(units(lp.leftMargin), units(lp.topMargin)));
            }
        }
        return slots;
    }

    private void rememberVisibleShortcutSlots() {
        for (Item item : items) {
            if (item.shortcut != null && item.view.getVisibility() == VISIBLE) {
                LayoutParams lp = (LayoutParams) item.view.getLayoutParams();
                item.x = units(lp.leftMargin);
                item.y = units(lp.topMargin);
            }
        }
    }

    private void layoutShortcuts() {
        List<Point> occupied = new ArrayList<>();
        for (Item item : items) {
            if (item.shortcut == null) continue;
            Point slot = nearestShortcutSlot(item, item.x, item.y, occupied);
            item.view.setVisibility(slot == null ? GONE : VISIBLE);
            if (slot != null) {
                occupied.add(slot);
                item.view.setLayoutParams(params(item, slot.x, slot.y));
            }
        }
    }

    private void addItem(Item item) {
        if (attach(item)) {
            items.add(item);
            layoutShortcuts();
            rememberVisibleShortcutSlots();
            save();
        }
    }

    private boolean attach(Item item) {
        FrameLayout tile = item.shortcut == null ? new WidgetFrame(item) : new FrameLayout(activity);
        item.view = tile;
        if (item.shortcut != null) {
            item.width = shortcutWidth(); item.height = shortcutHeight();
            ResolveInfo app = activity.find(item.shortcut);
            if (app == null) return false;
            LinearLayout iconTile = new LinearLayout(activity);
            iconTile.setOrientation(LinearLayout.VERTICAL);
            iconTile.setGravity(Gravity.CENTER);
            item.icon = new ImageView(activity);
            item.icon.setImageDrawable(activity.appIcon(app));
            int iconSize = dp(activity.shortcutIconSize());
            iconTile.addView(item.icon, new LinearLayout.LayoutParams(iconSize, iconSize));
            TextView title = text(app.loadLabel(activity.getPackageManager()), 12);
            title.setMaxLines(2);
            title.setShadowLayer(3, 0, 1, 0xFF000000);
            iconTile.addView(title);
            tile.addView(iconTile, new LayoutParams(-1, -1));
            iconTile.setOnClickListener(v -> activity.launch(app));
            installDrag(iconTile, item);
            iconTile.setOnLongClickListener(v -> { showItemMenu(v, item); return true; });
            iconTile.setOnContextClickListener(v -> { showItemMenu(v, item); return true; });
        } else {
            AppWidgetProviderInfo info = manager.getAppWidgetInfo(item.widget);
            if (info == null) {
                host.deleteAppWidgetId(item.widget);
                return false;
            }
            AppWidgetHostView content = host.createView(activity, item.widget, info);
            LayoutParams contentParams = new LayoutParams(-1, -1);
            tile.addView(content, contentParams);
            content.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l == or - ol && b - t == ob - ot) return;
                int width = units(r - l);
                int height = units(b - t);
                if (width <= 0 || height <= 0) return;
                if (Build.VERSION.SDK_INT >= 31) {
                    content.updateAppWidgetSize(new Bundle(), Collections.singletonList(new SizeF(width, height)));
                } else {
                    content.updateAppWidgetSize(new Bundle(), width, height, width, height);
                }
            });
            LinearLayout toolbar = new LinearLayout(activity);
            toolbar.setBackgroundColor(0xE622344D);
            TextView handle = text(info.loadLabel(activity.getPackageManager()), 12);
            handle.setSingleLine(true);
            toolbar.addView(handle, new LinearLayout.LayoutParams(0, -1, 1));
            handle.setContentDescription("Move widget");
            installDrag(handle, item);
            TextView more = text("⋮", 18);
            more.setContentDescription("Widget options");
            toolbar.addView(more, new LinearLayout.LayoutParams(dp(40), -1));
            more.setOnClickListener(v -> showItemMenu(v, item));
            TextView done = text("Done", 12);
            done.setContentDescription("Finish editing widget");
            toolbar.addView(done, new LinearLayout.LayoutParams(dp(48), -1));
            done.setOnClickListener(v -> selectWidget(null));
            LayoutParams toolbarParams = new LayoutParams(-1, dp(TOOLBAR));
            toolbarParams.leftMargin = toolbarParams.rightMargin = dp(28);
            tile.addView(toolbar, toolbarParams);
            item.controls.add(toolbar);
            if ((info.resizeMode & AppWidgetProviderInfo.RESIZE_BOTH) != 0) {
                addCorner(item, true, true);
                addCorner(item, false, true);
                addCorner(item, true, false);
                addCorner(item, false, false);
            }
            for (View control : item.controls) control.setVisibility(GONE);
        }
        addView(tile, params(item));
        return true;
    }

    private void selectWidget(Item next) {
        if (selectedWidget != null) for (View control : selectedWidget.controls) control.setVisibility(GONE);
        selectedWidget = next;
        if (next != null) {
            next.view.bringToFront();
            for (View control : next.controls) control.setVisibility(VISIBLE);
        }
    }

    /** Observe long presses without intercepting normal widget taps and scrolling. */
    private final class WidgetFrame extends FrameLayout {
        private float downX, downY;
        private boolean editingGesture;
        private final Item item;
        private final Runnable hold;

        WidgetFrame(Item item) {
            super(activity);
            this.item = item;
            hold = () -> {
            editingGesture = true;
            MotionEvent cancel = MotionEvent.obtain(0, android.os.SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_CANCEL, downX, downY, 0);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
            selectWidget(item);
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            };
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    editingGesture = false;
                    downX = event.getX(); downY = event.getY();
                    if (selectedWidget != item) postDelayed(hold, ViewConfiguration.getLongPressTimeout());
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(event.getX() - downX, event.getY() - downY)
                            > ViewConfiguration.get(activity).getScaledTouchSlop()) removeCallbacks(hold);
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(hold);
                    break;
            }
            if (editingGesture) return true;
            boolean handled = super.dispatchTouchEvent(event);
            return handled || event.getActionMasked() == MotionEvent.ACTION_DOWN;
        }

        @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS
                    && event.getActionButton() == MotionEvent.BUTTON_SECONDARY) {
                removeCallbacks(hold);
                selectWidget(item);
                return true;
            }
            return super.dispatchGenericMotionEvent(event);
        }

        @Override protected void onDetachedFromWindow() {
            removeCallbacks(hold);
            super.onDetachedFromWindow();
        }
    }

    private void addCorner(Item item, boolean left, boolean top) {
        TextView corner = text(left ? (top ? "◤" : "◣") : (top ? "◥" : "◢"), 22);
        corner.setBackgroundColor(0xE622344D);
        corner.setContentDescription("Resize widget from " + (top ? "top " : "bottom ") + (left ? "left" : "right"));
        corner.setTooltipText(corner.getContentDescription());
        corner.setFocusable(true);
        LayoutParams position = new LayoutParams(dp(28), dp(28),
                (left ? Gravity.LEFT : Gravity.RIGHT) | (top ? Gravity.TOP : Gravity.BOTTOM));
        item.view.addView(corner, position);
        item.controls.add(corner);
        corner.setOnTouchListener(new OnTouchListener() {
            private float downX, downY;
            private int x, y, width, height;
            private boolean dragging;
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        LayoutParams lp = (LayoutParams) item.view.getLayoutParams();
                        x = units(lp.leftMargin); y = units(lp.topMargin);
                        width = units(lp.width); height = units(lp.height);
                        downX = event.getRawX(); downY = event.getRawY();
                        dragging = false;
                        view.getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!dragging && Math.hypot(event.getRawX() - downX, event.getRawY() - downY)
                                < ViewConfiguration.get(activity).getScaledTouchSlop()) return true;
                        dragging = true;
                        AppWidgetProviderInfo info = manager.getAppWidgetInfo(item.widget);
                        if (info == null) return true;
                        WidgetResizeBounds.Result bounds = WidgetResizeBounds.resize(x, y, width, height,
                                units(event.getRawX() - downX), units(event.getRawY() - downY), left, top,
                                (info.resizeMode & AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0,
                                (info.resizeMode & AppWidgetProviderInfo.RESIZE_VERTICAL) != 0,
                                Math.max(64, units(info.minResizeWidth)), Math.max(48, units(info.minResizeHeight)),
                                Build.VERSION.SDK_INT >= 31 && info.maxResizeWidth > 0 ? units(info.maxResizeWidth) : Integer.MAX_VALUE,
                                Build.VERSION.SDK_INT >= 31 && info.maxResizeHeight > 0 ? units(info.maxResizeHeight) : Integer.MAX_VALUE,
                                units(getWidth()), units(getHeight()));
                        item.x = bounds.x; item.y = bounds.y;
                        item.width = bounds.width; item.height = bounds.height;
                        item.view.setLayoutParams(params(item));
                        return true;
                    case MotionEvent.ACTION_UP:
                        view.getParent().requestDisallowInterceptTouchEvent(false);
                        if (dragging) {
                            layoutShortcuts();
                            rememberVisibleShortcutSlots();
                            save();
                        }
                        else view.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        item.x = x; item.y = y; item.width = width; item.height = height;
                        item.view.setLayoutParams(params(item));
                        view.getParent().requestDisallowInterceptTouchEvent(false);
                        return true;
                    default: return true;
                }
            }
        });
        corner.setOnClickListener(v -> {
            AppWidgetProviderInfo info = manager.getAppWidgetInfo(item.widget);
            if (info != null) showResizeMenu(corner, item, info.resizeMode);
        });
    }

    private LayoutParams params(Item item) {
        return params(item, item.x, item.y);
    }

    private LayoutParams params(Item item, int x, int y) {
        int areaWidth = getWidth();
        int areaHeight = getHeight();
        int width = areaWidth > 0 ? Math.min(dp(item.width), areaWidth) : dp(item.width);
        int height = areaHeight > 0 ? Math.min(dp(item.height), areaHeight) : dp(item.height);
        LayoutParams lp = new LayoutParams(width, height, Gravity.TOP | Gravity.LEFT);
        lp.leftMargin = clamp(dp(x), 0, Math.max(0, areaWidth - width));
        lp.topMargin = clamp(dp(y), 0, Math.max(0, areaHeight - height));
        return lp;
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        for (Item item : items) {
            if (item.shortcut == null) item.view.setLayoutParams(params(item));
        }
        layoutShortcuts();
    }

    private void installDrag(View handle, Item item) {
        handle.setClickable(true);
        handle.setOnTouchListener(new OnTouchListener() {
            private float downX, downY;
            private int startX, startY;
            private int savedX, savedY, savedWidth, savedHeight;
            private boolean dragging;
            private final int slop = ViewConfiguration.get(activity).getScaledTouchSlop();

            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)
                        && (event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) return false;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        LayoutParams lp = (LayoutParams) item.view.getLayoutParams();
                        startX = units(lp.leftMargin);
                        startY = units(lp.topMargin);
                        savedX = item.x;
                        savedY = item.y;
                        savedWidth = item.width;
                        savedHeight = item.height;
                        dragging = false;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        if (!dragging && Math.hypot(dx, dy) < slop) break;
                        if (!dragging) {
                            dragging = true;
                            MotionEvent cancel = MotionEvent.obtain(event);
                            cancel.setAction(MotionEvent.ACTION_CANCEL);
                            view.onTouchEvent(cancel);
                            cancel.recycle();
                            item.view.bringToFront();
                        }
                        {
                            item.x = clamp(startX + units(dx), 0, Math.max(0, units(getWidth()) - item.width));
                            item.y = clamp(startY + units(dy), 0, Math.max(0, units(getHeight()) - item.height));
                        }
                        item.view.setLayoutParams(params(item));
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragging) break;
                        if (item.shortcut != null) {
                            int x = startX + units(event.getRawX() - downX);
                            int y = startY + units(event.getRawY() - downY);
                            Point slot = nearestShortcutSlot(item, x, y, occupiedShortcutSlots(item));
                            item.x = slot == null ? savedX : slot.x;
                            item.y = slot == null ? savedY : slot.y;
                            item.view.setLayoutParams(params(item));
                            rememberVisibleShortcutSlots();
                        }
                        layoutShortcuts();
                        rememberVisibleShortcutSlots();
                        save();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        if (!dragging) break;
                        item.x = savedX;
                        item.y = savedY;
                        item.width = savedWidth;
                        item.height = savedHeight;
                        item.view.setLayoutParams(item.shortcut == null
                                ? params(item) : params(item, startX, startY));
                        return true;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    private void resize(Item item, int width, int height) {
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(item.widget);
        if (info == null) return;
        LayoutParams lp = (LayoutParams) item.view.getLayoutParams();
        if ((info.resizeMode & AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0) {
            int min = Math.max(64, units(info.minResizeWidth));
            int max = units(getWidth() - lp.leftMargin);
            if (Build.VERSION.SDK_INT >= 31 && info.maxResizeWidth > 0) max = Math.min(max, units(info.maxResizeWidth));
            item.width = clamp(width, min, max);
        }
        if ((info.resizeMode & AppWidgetProviderInfo.RESIZE_VERTICAL) != 0) {
            int min = Math.max(48, units(info.minResizeHeight));
            int max = units(getHeight() - lp.topMargin);
            if (Build.VERSION.SDK_INT >= 31 && info.maxResizeHeight > 0) max = Math.min(max, units(info.maxResizeHeight));
            item.height = clamp(height, min, max);
        }
    }

    private void showResizeMenu(View anchor, Item item, int axes) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        if ((axes & AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0) {
            addResizeOption(menu, item, "Wider", 32, 0);
            addResizeOption(menu, item, "Narrower", -32, 0);
        }
        if ((axes & AppWidgetProviderInfo.RESIZE_VERTICAL) != 0) {
            addResizeOption(menu, item, "Taller", 0, 32);
            addResizeOption(menu, item, "Shorter", 0, -32);
        }
        menu.show();
    }

    private void addResizeOption(PopupMenu menu, Item item, String title, int widthDelta, int heightDelta) {
        menu.getMenu().add(title).setOnMenuItemClickListener(option -> {
            resize(item, item.width + widthDelta, item.height + heightDelta);
            item.view.setLayoutParams(params(item));
            layoutShortcuts();
            rememberVisibleShortcutSlots();
            save();
            return true;
        });
    }

    private void showItemMenu(View anchor, Item item) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        if (item.widget != -1) {
            AppWidgetProviderInfo info = manager.getAppWidgetInfo(item.widget);
            if (info != null && (info.resizeMode & AppWidgetProviderInfo.RESIZE_BOTH) != 0) {
                menu.getMenu().add("Resize options").setOnMenuItemClickListener(option -> {
                    showResizeMenu(anchor, item, info.resizeMode);
                    return true;
                });
            }
        }
        menu.getMenu().add("Remove from desktop").setOnMenuItemClickListener(option -> {
            if (selectedWidget == item) selectWidget(null);
            removeView(item.view);
            items.remove(item);
            if (item.widget != -1) host.deleteAppWidgetId(item.widget);
            layoutShortcuts();
            rememberVisibleShortcutSlots();
            save();
            return true;
        });
        menu.show();
    }

    private void restore() {
        try {
            JSONArray saved = new JSONArray(prefs.getString("workspace", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject data = saved.optJSONObject(i);
                if (data == null) continue;
                Item item = new Item();
                item.shortcut = data.optString("shortcut", null);
                item.widget = data.optInt("widget", -1);
                item.x = data.optInt("x");
                item.y = data.optInt("y");
                item.width = data.optInt("width", SHORTCUT_WIDTH);
                item.height = data.optInt("height", SHORTCUT_HEIGHT);
                if (item.widget != -1 && !prefs.getBoolean("contentOnlyWidgets", false)) {
                    item.height = Math.max(48, item.height - TOOLBAR);
                }
                if ((item.shortcut != null || item.widget != -1) && attach(item)) items.add(item);
            }
            prefs.edit().putBoolean("contentOnlyWidgets", true).apply();
            save();
        } catch (JSONException ignored) { }
    }

    private void save() {
        JSONArray saved = new JSONArray();
        for (Item item : items) {
            try {
                JSONObject data = new JSONObject();
                if (item.shortcut != null) data.put("shortcut", item.shortcut);
                else data.put("widget", item.widget);
                data.put("x", item.x).put("y", item.y).put("width", item.width).put("height", item.height);
                saved.put(data);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
        prefs.edit().putString("workspace", saved.toString()).apply();
    }

    private TextView text(CharSequence value, int size) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextColor(0xFFFFFFFF);
        text.setTextSize(size);
        text.setGravity(Gravity.CENTER);
        return text;
    }

    private void message(String message) { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return activity.dp(value); }
    private int units(float pixels) { return Math.round(pixels / getResources().getDisplayMetrics().density); }
    private static int clamp(int value, int min, int max) { return Math.min(Math.max(value, min), max); }
}
