package com.dexlike.launcher;

public final class IconPackMappingTest {
    public static void main(String[] args) {
        if (!"example.app/example.app.Main".equals(IconPackMapping.component("ComponentInfo{example.app/.Main}")))
            throw new AssertionError("relative pack activity was not expanded");
        if (!"example.app/vendor.Activity".equals(IconPackMapping.component("ComponentInfo{example.app/vendor.Activity}")))
            throw new AssertionError("explicit activity was changed");
        if (IconPackMapping.component("bad component") != null || IconPackMapping.component("bad/") != null)
            throw new AssertionError("invalid mappings accepted");
        for (int icon = 24; icon <= 100; icon++) {
            if (DesktopGeometry.cellWidth(icon) < icon + 24 || DesktopGeometry.cellHeight(icon) < icon + 50)
                throw new AssertionError("large icon clipped by shortcut grid");
        }
        System.out.println("Icon pack mapping and 100dp shortcut cell checks passed");
    }
}
