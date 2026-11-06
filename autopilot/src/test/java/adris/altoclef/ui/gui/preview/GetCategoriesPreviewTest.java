package adris.altoclef.ui.gui.preview;

import adris.altoclef.ui.gui.facade.ItemsAccess.ItemEntry;
import adris.altoclef.ui.gui.kit.CategoryTabs;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.Widget;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class GetCategoriesPreviewTest {

    private GetTabPreviewTest.Rig rig() throws Exception {
        GetTabPreviewTest.Rig r = new GetTabPreviewTest.Rig(1920, 1080);
        Set<String> blocks = Set.of("oak_log", "oak_planks", "crafting_table", "cobblestone", "stone", "furnace", "iron_ore",
                "obsidian", "bed", "white_wool", "torch", "ladder", "chest", "glass", "sand", "gravel", "dirt", "netherrack",
                "gold_block", "iron_block", "diamond_block", "sugar_cane");
        r.f.items.gettable.replaceAll(e -> {
            String n = e.name();
            String group;
            if (n.endsWith("pickaxe") || n.endsWith("bucket") || n.equals("flint_and_steel") || n.equals("fishing_rod")) {
                group = "Tools";
            } else if (n.endsWith("sword") || n.startsWith("iron_") && Set.of("iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots").contains(n)
                    || Set.of("bow", "arrow", "shield").contains(n)) {
                group = "Combat";
            } else if (n.startsWith("cooked_") || n.equals("bread") || n.equals("apple")) {
                group = "Food";
            } else if (blocks.contains(n)) {
                group = "Blocks";
            } else {
                group = n.equals("redstone") ? "Redstone" : "Materials";
            }
            return new ItemEntry(n, e.displayName(), e.iconItemId(), e.maxStack(), group);
        });
        r.f.items.gettable.add(new ItemEntry("zombie_spawn_egg", "Zombie Spawn Egg", "minecraft:zombie_spawn_egg", 64, "Other"));
        r.tab.onShown();
        r.p.settle();
        r.p.advance(400);
        return r;
    }

    @Test
    public void categoryTabsInBothAppearances() throws Exception {
        var r = rig();
        CategoryTabs tabs = (CategoryTabs) r.tab.content().children().get(1);
        assertEquals(List.of("All", "Blocks", "Tools", "Combat", "Food", "Materials", "Redstone", "Other"), tabs.options());
        for (boolean dark : new boolean[]{true, false}) {
            r.p.shell.setDarkMode(dark);
            r.p.shell.focus(tabs);
            tabs.keyDown(Keys.HOME, 0);
            for (int i = 0; i < 2; i++) {
                tabs.keyDown(Keys.RIGHT, 0);
            }
            r.p.settle();
            r.p.advance(500);
            r.p.savePanel("get_categories_" + (dark ? "dark" : "light"));
        }
    }

    @Test
    public void minimumWindowKeepsCategoryNavigationAndItemsReachable() throws Exception {
        var r = rig();
        r.p.shell.setBounds(r.p.shell.x(), r.p.shell.y(), r.p.theme.px(560), r.p.theme.px(360));
        CategoryTabs tabs = (CategoryTabs) r.tab.content().children().get(1);
        Widget grid = r.grid();
        r.p.shell.focus(tabs);
        for (int i = 0; i < tabs.options().size(); i++) {
            tabs.keyDown(Keys.HOME, 0);
            for (int j = 0; j < i; j++) {
                tabs.keyDown(Keys.RIGHT, 0);
            }
            r.p.settle();
            r.p.advance(500);
            r.p.settle();
            assertTrue(grid.h() > 0);
            assertTrue(tabs.y() + tabs.h() <= grid.y());
            assertTrue(grid.y() + grid.h() <= r.panel().y());
        }
        r.p.savePanel("get_categories_minimum");
    }
}
