package com.dexlike.launcher;

public final class WidgetResizeBoundsTest {
    public static void main(String[] args) {
        for (boolean left : new boolean[]{false, true}) {
            for (boolean top : new boolean[]{false, true}) {
                WidgetResizeBounds.Result r = resize(30, 20, left, top, true, true);
                check(r, left ? 130 : 100, top ? 120 : 100, left ? 170 : 230, top ? 130 : 170);
                if (left && r.x + r.width != 300) throw new AssertionError("opposite horizontal edge moved");
                if (top && r.y + r.height != 250) throw new AssertionError("opposite vertical edge moved");
            }
        }
        check(resize(5000, 5000, false, false, true, true), 100, 100, 350, 250);
        check(resize(-5000, -5000, false, false, true, true), 100, 100, 64, 48);
        check(resize(-5000, -5000, true, true, true, true), 0, 0, 300, 250);
        check(resize(30, 20, true, true, true, false), 130, 100, 170, 150);
        check(resize(30, 20, true, true, false, true), 100, 120, 200, 130);
        var small = WidgetResizeBounds.resize(0, 0, 40, 30, 100, 100, false, false,
                true, true, 64, 48, 350, 250, 40, 30);
        check(small, 0, 0, 40, 30);
        System.out.println("Widget corner resize checks passed");
    }
    private static WidgetResizeBounds.Result resize(int dx, int dy, boolean left, boolean top, boolean h, boolean v) {
        return WidgetResizeBounds.resize(100, 100, 200, 150, dx, dy, left, top, h, v,
                64, 48, 350, 250, 800, 600);
    }
    private static void check(WidgetResizeBounds.Result r, int x, int y, int width, int height) {
        if (r.x != x || r.y != y || r.width != width || r.height != height) {
            throw new AssertionError(r.x + "," + r.y + " " + r.width + "x" + r.height);
        }
    }
}
