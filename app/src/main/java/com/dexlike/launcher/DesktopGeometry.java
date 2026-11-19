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
        int middle = left + (right - left) / 2;
        return new int[]{rightHalf ? middle : left, top, rightHalf ? right : middle, bottom};
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
