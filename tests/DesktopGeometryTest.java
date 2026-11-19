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
        int[] unchanged = DesktopGeometry.fit(100, 120, 600, 400, 0, 28, 1920, 1118);
        if (unchanged[0] != 100 || unchanged[1] != 120 || unchanged[2] != 700 || unchanged[3] != 520)
            throw new AssertionError("fitting changed a window already within the desktop");
        System.out.println("Desktop overlap and window work-area checks passed");
    }
}
