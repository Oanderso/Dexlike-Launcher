package com.dexlike.launcher;

public final class DesktopGeometryTest {
    public static void main(String[] args) {
        if (!DesktopGeometry.overlaps(12, 12, 96, 92, 70, 60, 300, 200)) throw new AssertionError("widget overlap accepted");
        if (DesktopGeometry.overlaps(12, 12, 96, 92, 108, 12, 300, 200)) throw new AssertionError("touching edges rejected");
        if (!DesktopGeometry.overlaps(108, 104, 96, 92, 110, 106, 1, 1)) throw new AssertionError("tiny widget missed");
        for (int dock : new int[]{64, 70, 82}) {
            int[] fitted = DesktopGeometry.fit(0, 0, 1920, 1200, 0, 28, 1920, 1200 - dock);
            if (fitted[3] != 1200 - dock || fitted[1] != 28) throw new AssertionError("dock height not reserved");
        }
        for (int thickness : new int[]{64, 72, 134}) {
            int[] bottom = DesktopGeometry.workArea(10, 20, 1920, 1200, thickness, false);
            int[] side = DesktopGeometry.workArea(10, 20, 1920, 1200, thickness, true);
            if (bottom[2] != 1930 || bottom[3] != 1220 - thickness) throw new AssertionError("bottom work area incorrect");
            if (side[2] != 1930 - thickness || side[3] != 1220) throw new AssertionError("side dock shrank desktop height");
            int[] window = DesktopGeometry.fit(10, 20, 1920, 1200, side[0], side[1], side[2], side[3]);
            if (window[2] != side[2] || window[3] != 1220) throw new AssertionError("window overlaps right dock");
        }
        for (DesktopGeometry.Edge edge : DesktopGeometry.Edge.values()) {
            for (int thickness : new int[]{64, 72, 134}) {
                int[] area = DesktopGeometry.workArea(10, 20, 1920, 1200, thickness, edge);
                for (boolean rightHalf : new boolean[]{false, true}) {
                    int[] half = DesktopGeometry.snapHalf(area[0], area[1], area[2], area[3], rightHalf);
                    if (half[1] != area[1] || half[3] != area[3]) throw new AssertionError("snap left a status/taskbar gap");
                    if ((!rightHalf && half[0] != area[0]) || (rightHalf && half[2] != area[2]))
                        throw new AssertionError("snap crossed dock edge");
                }
                int[] snapped = DesktopGeometry.fit(10, 20, 1920, 1200, area[0], area[1], area[2], area[3]);
                if (!java.util.Arrays.equals(area, snapped)) throw new AssertionError("snap overlaps " + edge);
                if (area[0] != 10 + (edge == DesktopGeometry.Edge.LEFT ? thickness : 0)
                        || area[1] != 20 + (edge == DesktopGeometry.Edge.TOP ? thickness : 0)
                        || area[2] != 1930 - (edge == DesktopGeometry.Edge.RIGHT ? thickness : 0)
                        || area[3] != 1220 - (edge == DesktopGeometry.Edge.BOTTOM ? thickness : 0))
                    throw new AssertionError("incorrect work area for " + edge);
            }
        }
        for (DesktopGeometry.Edge edge : new DesktopGeometry.Edge[]{DesktopGeometry.Edge.RIGHT, DesktopGeometry.Edge.LEFT}) {
            int[] area = DesktopGeometry.workArea(0, 0, 1920, 1200, 72, edge);
            for (boolean rightHalf : new boolean[]{false, true}) {
                int[] nativeSnap = DesktopGeometry.fitDesktopWindow(rightHalf ? 960 : 0, 28, 960, 1108,
                        area[0], area[1], area[2], area[3], 1920, 1200, 96);
                int[] expected = DesktopGeometry.snapHalf(area[0], area[1], area[2], area[3], rightHalf);
                if (!java.util.Arrays.equals(nativeSnap, expected)) throw new AssertionError("native snap retained system bar gaps");
            }
        }
        int[] ordinary = DesktopGeometry.fitDesktopWindow(100, 120, 600, 400, 0, 0, 1848, 1200, 1920, 1200, 96);
        if (!java.util.Arrays.equals(ordinary, new int[]{100, 120, 700, 520})) throw new AssertionError("ordinary window mistaken for snap");
        int[] unchanged = DesktopGeometry.fit(100, 120, 600, 400, 0, 28, 1920, 1118);
        if (unchanged[0] != 100 || unchanged[1] != 120 || unchanged[2] != 700 || unchanged[3] != 520)
            throw new AssertionError("fitting changed a window already within the desktop");
        System.out.println("Desktop overlap and window work-area checks passed");
    }
}
