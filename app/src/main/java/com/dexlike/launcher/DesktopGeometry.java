package com.dexlike.launcher;

final class DesktopGeometry {
    enum Edge { BOTTOM, RIGHT, LEFT, TOP }

    static int[] workArea(int left, int top, int width, int height, int thickness, boolean rightDock) {
        return workArea(left, top, width, height, thickness, rightDock ? Edge.RIGHT : Edge.BOTTOM);
    }

    static int[] workArea(int left, int top, int width, int height, int thickness, Edge edge) {
        int right = left + Math.max(0, width), bottom = top + Math.max(0, height);
        thickness = Math.max(0, thickness);
        if (edge == Edge.LEFT) left = Math.min(right, left + thickness);
        if (edge == Edge.RIGHT) right = Math.max(left, right - thickness);
        if (edge == Edge.TOP) top = Math.min(bottom, top + thickness);
        if (edge == Edge.BOTTOM) bottom = Math.max(top, bottom - thickness);
        return new int[]{left, top, right, bottom};
    }

    static int[] snapHalf(int left, int top, int right, int bottom, boolean rightHalf) {
        return snapHalf(left, top, right, bottom, rightHalf, left, right);
    }

    static int[] snapHalf(int left, int top, int right, int bottom, boolean rightHalf, int displayLeft, int displayRight) {
        int middle = displayLeft + (displayRight - displayLeft) / 2;
        return new int[]{rightHalf ? Math.max(left, middle) : left, top,
                rightHalf ? right : Math.min(right, middle), bottom};
    }

    static int[] fitDesktopWindow(int x, int y, int width, int height, int left, int top, int right, int bottom,
            int displayWidth, int displayHeight, int tolerance) {
        // Samsung's native half-screen snap uses system stable bounds, including status/navigation
        // gaps. Recognize that geometry, then use our edge-aware full-height work area instead.
        boolean tall = height >= displayHeight * .75 && Math.abs(y) <= tolerance
                && Math.abs(y + height - displayHeight) <= tolerance;
        boolean halfWidth = Math.abs(width - displayWidth / 2) <= tolerance;
        boolean maximized = Math.abs(x) <= tolerance && Math.abs(x + width - displayWidth) <= tolerance;
        boolean fillsWorkWidth = Math.abs(x - left) <= tolerance && Math.abs(x + width - right) <= tolerance;
        if (tall && (maximized || fillsWorkWidth)) return new int[]{left, top, right, bottom};
        if (tall && halfWidth) {
            if (Math.abs(x) <= tolerance) return snapHalf(left, top, right, bottom, false, 0, displayWidth);
            if (Math.abs(x + width - displayWidth) <= tolerance) return snapHalf(left, top, right, bottom, true, 0, displayWidth);
        }
        return fit(x, y, width, height, left, top, right, bottom);
    }

    static int cellWidth(int icon) { return Math.max(96, icon + 24); }
    static int cellHeight(int icon) { return Math.max(92, icon + 50); }
    static boolean overlaps(int x, int y, int width, int height, int otherX, int otherY, int otherWidth, int otherHeight) {
        return x < otherX + otherWidth && x + width > otherX
                && y < otherY + otherHeight && y + height > otherY;
    }

    static int[] fit(int x, int y, int width, int height, int left, int top, int right, int bottom) {
        width = Math.max(0, Math.min(width, right - left));
        height = Math.max(0, Math.min(height, bottom - top));
        x = Math.max(left, Math.min(x, right - width));
        y = Math.max(top, Math.min(y, bottom - height));
        return new int[]{x, y, x + width, y + height};
    }
}
