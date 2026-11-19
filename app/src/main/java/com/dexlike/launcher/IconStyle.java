package com.dexlike.launcher;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import org.xmlpull.v1.XmlPullParser;
import android.util.Xml;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Supports the component-to-drawable appfilter format used by ADW/Nova-compatible packs. */
final class IconStyle {
    private final MainActivity activity;
    private final SharedPreferences prefs;
    private final Runnable changed;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Map<String, String> mappings = new HashMap<>();
    private Resources resources;
    private String loadedPackage = "";
    private int version, request;
    private boolean closed;

    IconStyle(MainActivity activity, SharedPreferences prefs, Runnable changed) {
        this.activity = activity; this.prefs = prefs; this.changed = changed;
        load(prefs.getString("iconPack", ""));
    }

    int version() { return version; }

    String title() {
        String pack = prefs.getString("iconPack", "");
        if (pack.isEmpty()) return "System icons";
        try { return activity.getPackageManager().getApplicationLabel(activity.getPackageManager().getApplicationInfo(pack, 0)).toString(); }
        catch (Exception ignored) { return "System icons (pack unavailable)"; }
    }

    Drawable icon(ResolveInfo app) {
        String key = new ComponentName(app.activityInfo.packageName, app.activityInfo.name).flattenToString();
        String name = mappings.get(key);
        if (resources != null && name != null) {
            try {
                int id = resources.getIdentifier(name, "drawable", loadedPackage);
                if (id != 0) return resources.getDrawable(id, null);
            } catch (RuntimeException ignored) { }
        }
        return app.loadIcon(activity.getPackageManager());
    }

    void choose(Runnable selectionChanged) {
        LinkedHashMap<String, ResolveInfo> packs = new LinkedHashMap<>();
        for (String category : new String[]{"org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.anddoes.launcher.THEME"}) {
            for (Intent query : new Intent[]{new Intent(category), new Intent(Intent.ACTION_MAIN).addCategory(category)}) {
                for (ResolveInfo pack : activity.getPackageManager().queryIntentActivities(query, 0)) {
                    packs.putIfAbsent(pack.activityInfo.packageName, pack);
                }
            }
        }
        List<String> packages = new ArrayList<>(packs.keySet());
        packages.sort((a, b) -> packs.get(a).loadLabel(activity.getPackageManager()).toString()
                .compareToIgnoreCase(packs.get(b).loadLabel(activity.getPackageManager()).toString()));
        String[] labels = new String[packages.size() + 1];
        labels[0] = "System icons";
        int selected = 0;
        for (int i = 0; i < packages.size(); i++) {
            labels[i + 1] = packs.get(packages.get(i)).loadLabel(activity.getPackageManager()).toString();
            if (packages.get(i).equals(prefs.getString("iconPack", ""))) selected = i + 1;
        }
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Icon style")
                .setSingleChoiceItems(labels, selected, (choice, which) -> {
                    String pack = which == 0 ? "" : packages.get(which - 1);
                    prefs.edit().putString("iconPack", pack).apply();
                    load(pack); selectionChanged.run(); choice.dismiss();
                }).setNegativeButton("Cancel", null).create();
        dialog.show();
    }

    private void load(String pack) {
        final int current = ++request;
        worker.execute(() -> {
            Map<String, String> result = new HashMap<>();
            Resources res = null;
            String error = null;
            if (!pack.isEmpty()) {
                try {
                    res = activity.getPackageManager().getResourcesForApplication(pack);
                    int xml = res.getIdentifier("appfilter", "xml", pack);
                    if (xml != 0) {
                        try (android.content.res.XmlResourceParser parser = res.getXml(xml)) { parse(parser, result); }
                    } else {
                        try (InputStream stream = res.getAssets().open("appfilter.xml")) {
                            XmlPullParser parser = Xml.newPullParser();
                            parser.setInput(stream, "UTF-8"); parse(parser, result);
                        }
                    }
                    if (result.isEmpty()) error = "This icon pack has no supported app mappings";
                } catch (Exception failure) { error = "Could not load icon pack; using system icons"; }
            }
            final Resources ready = res;
            final String message = error;
            activity.runOnUiThread(() -> {
                if (closed || current != request || activity.isDestroyed()) return;
                mappings = result; resources = ready; loadedPackage = pack; version++;
                changed.run();
                if (message != null) android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show();
            });
        });
    }

    private void parse(XmlPullParser parser, Map<String, String> output) throws Exception {
        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event != XmlPullParser.START_TAG || !"item".equals(parser.getName())) continue;
            String component = parser.getAttributeValue(null, "component");
            String drawable = parser.getAttributeValue(null, "drawable");
            if (component == null || drawable == null) continue;
            String key = IconPackMapping.component(component);
            if (key != null) output.put(key, drawable);
        }
    }

    void close() { closed = true; worker.shutdownNow(); }
}
