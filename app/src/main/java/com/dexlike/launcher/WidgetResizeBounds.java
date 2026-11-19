package com.dexlike.launcher;

/** Corner resizing keeps the opposite edge fixed and honors provider/desktop bounds. */
final class WidgetResizeBounds {
    static final class Result {
        final int x, y, width, height;
        Result(int x, int y, int width, int height) {
            this.x = x; this.y = y; this.width = width; this.height = height;
        }
    }

    static Result resize(int x, int y, int width, int height, int dx, int dy,
            boolean left, boolean top, boolean horizontal, boolean vertical,
            int minWidth, int minHeight, int maxWidth, int maxHeight, int areaWidth, int areaHeight) {
        int right = x + width, bottom = y + height;
        if (horizontal) {
            int available = Math.max(0, left ? right : areaWidth - x);
            int max = Math.min(available, maxWidth);
            width = clamp(width + (left ? -dx : dx), Math.min(minWidth, max), max);
            if (left) x = right - width;
        }
        if (vertical) {
            int available = Math.max(0, top ? bottom : areaHeight - y);
            int max = Math.min(available, maxHeight);
            height = clamp(height + (top ? -dy : dy), Math.min(minHeight, max), max);
            if (top) y = bottom - height;
        }
        return new Result(x, y, width, height);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
