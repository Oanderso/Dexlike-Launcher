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
        int[] unchanged = DesktopGeometry.fit(100, 120, 600, 400, 0, 28, 1920, 1118);
        if (unchanged[0] != 100 || unchanged[1] != 120 || unchanged[2] != 700 || unchanged[3] != 520)
            throw new AssertionError("fitting changed a window already within the desktop");
        System.out.println("Desktop overlap and window work-area checks passed");
    }
}
