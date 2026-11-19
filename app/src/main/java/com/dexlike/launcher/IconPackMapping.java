package com.dexlike.launcher;

final class IconPackMapping {
    static String component(String value) {
        if (value == null) return null;
        value = value.trim();
        if (value.startsWith("ComponentInfo{") && value.endsWith("}")) value = value.substring(14, value.length() - 1);
        int slash = value.indexOf('/');
        if (slash <= 0 || slash == value.length() - 1) return null;
        String pack = value.substring(0, slash), activity = value.substring(slash + 1);
        if (activity.startsWith(".")) activity = pack + activity;
        return pack + "/" + activity;
    }
}
