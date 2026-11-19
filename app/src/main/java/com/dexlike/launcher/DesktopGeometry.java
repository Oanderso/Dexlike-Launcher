package com.dexlike.launcher;

final class DesktopGeometry {
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
