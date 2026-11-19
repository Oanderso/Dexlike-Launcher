package com.dexlike.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ActivityNotFoundException;
import android.content.Intent;
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

    private static final class Item {
        String shortcut;
        int widget = -1;
        int x, y, width, height;
        FrameLayout view;
        ImageView icon;
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

    void onStart() { host.startListening(); }
    void onStop() { host.stopListening(); }
    void saveState(Bundle state) { state.putInt("pendingWidget", pendingWidget); }

    void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        menu.getMenu().add("Choose wallpaper").setOnMenuItemClickListener(item -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                    .addCategory(Intent.CATEGORY_OPENABLE);
            try {
                activity.startActivityForResult(pick, PICK_WALLPAPER);
            } catch (ActivityNotFoundException e) {
                message("No image picker is available");
            }
            return true;
        });
        if (prefs.contains("wallpaper")) {
            menu.getMenu().add("Clear wallpaper").setOnMenuItemClickListener(item -> {
                changeWallpaper(null);
                return true;
            });
        }
        menu.getMenu().add("Add widget").setOnMenuItemClickListener(item -> {
            chooseWidget();
            return true;
        });
        menu.getMenu().add("Desktop settings").setOnMenuItemClickListener(item -> {
            activity.showDesktopSettings();
            return true;
        });
        menu.show();
    }

    void updateShortcutIconSizes() {
        int size = dp(activity.shortcutIconSize());
        for (Item item : items) {
            if (item.icon != null) item.icon.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        }
    }

    private void changeWallpaper(Uri next) {
        String previous = prefs.getString("wallpaper", null);
        if (previous != null && (next == null || !previous.equals(next.toString()))) {
            try {
                activity.getContentResolver().releasePersistableUriPermission(Uri.parse(previous),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) { }
        }
        prefs.edit().putString("wallpaper", next == null ? null : next.toString()).apply();
        loadWallpaper();
    }

    private void loadWallpaper() {
        final int version = ++wallpaperVersion;
        String source = prefs.getString("wallpaper", null);
        if (source == null) {
            wallpaper.setImageDrawable(null);
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
        item.width = SHORTCUT_WIDTH;
        item.height = SHORTCUT_HEIGHT;
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
        String[] names = providers.stream()
                .map(info -> info.loadLabel(pm) + " · " + info.provider.getPackageName())
                .toArray(String[]::new);
        new AlertDialog.Builder(activity).setTitle("Add widget").setItems(names, (dialog, index) ->
                bindWidget(providers.get(index))).setNegativeButton("Cancel", null).show();
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
        item.height = Math.max(96, units(info.minHeight)) + TOOLBAR;
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
        for (int row = Math.min(GRID_MARGIN, maxY); row <= maxY; row += SHORTCUT_HEIGHT) {
            for (int column = Math.min(GRID_MARGIN, maxX); column <= maxX; column += SHORTCUT_WIDTH) {
                Point slot = new Point(column, row);
                if (occupied.contains(slot)) continue;
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
            save();
        }
    }

    private boolean attach(Item item) {
        FrameLayout tile = new FrameLayout(activity);
        item.view = tile;
        if (item.shortcut != null) {
            ResolveInfo app = activity.find(item.shortcut);
            if (app == null) return false;
            LinearLayout iconTile = new LinearLayout(activity);
            iconTile.setOrientation(LinearLayout.VERTICAL);
            iconTile.setGravity(Gravity.CENTER);
            item.icon = new ImageView(activity);
            item.icon.setImageDrawable(app.loadIcon(activity.getPackageManager()));
            int iconSize = dp(activity.shortcutIconSize());
            iconTile.addView(item.icon, new LinearLayout.LayoutParams(iconSize, iconSize));
            TextView title = text(app.loadLabel(activity.getPackageManager()), 12);
            title.setMaxLines(2);
            title.setShadowLayer(3, 0, 1, 0xFF000000);
            iconTile.addView(title);
            tile.addView(iconTile, new LayoutParams(-1, -1));
            iconTile.setOnClickListener(v -> activity.launch(app));
            installDrag(iconTile, item, false);
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
            contentParams.topMargin = dp(TOOLBAR);
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
            installDrag(handle, item, false);
            int axes = info.resizeMode & AppWidgetProviderInfo.RESIZE_BOTH;
            if (axes != 0) {
                TextView resize = text("Resize", 12);
                resize.setContentDescription("Resize widget");
                resize.setTooltipText("Drag to resize or click for size options");
                toolbar.addView(resize, new LinearLayout.LayoutParams(dp(56), -1));
                installDrag(resize, item, true);
                resize.setOnClickListener(v -> showResizeMenu(v, item, axes));
            }
            TextView more = text("⋮", 18);
            more.setContentDescription("Widget options");
            toolbar.addView(more, new LinearLayout.LayoutParams(dp(40), -1));
            more.setOnClickListener(v -> showItemMenu(v, item));
            tile.addView(toolbar, new LayoutParams(-1, dp(TOOLBAR)));
        }
        addView(tile, params(item));
        return true;
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

    private void installDrag(View handle, Item item, boolean resize) {
        handle.setClickable(true);
        handle.setOnTouchListener(new OnTouchListener() {
            private float downX, downY;
            private int startX, startY, startWidth, startHeight;
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
                        startWidth = units(lp.width);
                        startHeight = units(lp.height);
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
                        if (resize) resize(item, startWidth + units(dx), startHeight + units(dy));
                        else {
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
            int min = Math.max(48, units(info.minResizeHeight)) + TOOLBAR;
            int max = units(getHeight() - lp.topMargin);
            if (Build.VERSION.SDK_INT >= 31 && info.maxResizeHeight > 0) max = Math.min(max, units(info.maxResizeHeight) + TOOLBAR);
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
            save();
            return true;
        });
    }

    private void showItemMenu(View anchor, Item item) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        menu.getMenu().add("Remove from desktop").setOnMenuItemClickListener(option -> {
            removeView(item.view);
            items.remove(item);
            if (item.widget != -1) host.deleteAppWidgetId(item.widget);
            else layoutShortcuts();
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
                if ((item.shortcut != null || item.widget != -1) && attach(item)) items.add(item);
            }
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
