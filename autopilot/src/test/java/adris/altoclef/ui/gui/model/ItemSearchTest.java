package adris.altoclef.ui.gui.model;

import adris.altoclef.ui.gui.facade.ItemsAccess.ItemEntry;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ItemSearchTest {

    private static List<ItemEntry> items(String... names) {
        List<ItemEntry> out = new ArrayList<>();
        for (String n : names) {
            out.add(new ItemEntry(n, ItemEntry.titleCase(n), "minecraft:" + n, 64));
        }
        return out;
    }

    private static List<String> names(List<ItemEntry> es) {
        return es.stream().map(ItemEntry::name).toList();
    }

    @Test
    public void emptyQueryKeepsEverythingInOrder() {
        List<ItemEntry> all = items("b", "a", "c");
        assertEquals(List.of("b", "a", "c"), names(ItemSearch.filter(all, "")));
        assertEquals(List.of("b", "a", "c"), names(ItemSearch.filter(all, "   ")));
        assertEquals(List.of("b", "a", "c"), names(ItemSearch.filter(all, null)));
    }

    @Test
    public void prefixBeatsWordStartBeatsContains() {
        List<ItemEntry> all = items("gold_ingot", "ingot_mold", "raw_ingots", "bingot", "iron_ingot");
        // ingot_mold: prefix. gold_ingot, raw_ingots, iron_ingot: word start. bingot: contains
        assertEquals(List.of("ingot_mold", "gold_ingot", "raw_ingots", "iron_ingot", "bingot"),
                names(ItemSearch.filter(all, "ingot")));
    }

    @Test
    public void exactMatchComesFirst() {
        List<ItemEntry> all = items("stone_pickaxe", "stone", "stone_axe");
        assertEquals(List.of("stone", "stone_pickaxe", "stone_axe"), names(ItemSearch.filter(all, "stone")));
    }

    @Test
    public void insideATierTheOriginalOrderSurvives() {
        List<ItemEntry> all = items("iron_sword", "iron_ingot", "iron_axe", "iron");
        assertEquals(List.of("iron", "iron_sword", "iron_ingot", "iron_axe"), names(ItemSearch.filter(all, "iron")));
    }

    @Test
    public void caseAndSpacesDoNotMatter() {
        List<ItemEntry> all = items("iron_ingot", "gold_ingot", "oak_log");
        assertEquals(List.of("iron_ingot"), names(ItemSearch.filter(all, "  IRON Ingot ")));
        assertEquals(List.of("iron_ingot"), names(ItemSearch.filter(all, "iron_ingot")));
    }

    @Test
    public void noMatchGivesAnEmptyList() {
        assertTrue(ItemSearch.filter(items("oak_log"), "zzz").isEmpty());
    }

    @Test
    public void firstPicksTheBestRankedHit() {
        List<ItemEntry> all = items("gold_ingot", "ingot_mold");
        assertEquals("ingot_mold", ItemSearch.first(all, "ingot").name());
        assertNull(ItemSearch.first(all, "zzz"));
    }

    @Test
    public void rankReportsTheTier() {
        assertEquals(ItemSearch.EXACT, ItemSearch.rank("stick", "stick"));
        assertEquals(ItemSearch.PREFIX, ItemSearch.rank("stick_x", "stick"));
        assertEquals(ItemSearch.WORD, ItemSearch.rank("a_stick", "stick"));
        assertEquals(ItemSearch.CONTAINS, ItemSearch.rank("astick", "stick"));
        assertEquals(ItemSearch.NONE, ItemSearch.rank("oak", "stick"));
    }

    @Test
    public void whatThePlayerReadsMatchesToo() {
        List<ItemEntry> all = List.of(
                new ItemEntry("iron_ingot", "Eisenbarren", "minecraft:iron_ingot", 64),
                new ItemEntry("oak_log", "Eichen Stamm", "minecraft:oak_log", 64),
                new ItemEntry("stick", "", "minecraft:stick", 64));
        assertEquals(List.of("iron_ingot"), names(ItemSearch.filter(all, "eisen")));
        assertEquals(List.of("oak_log"), names(ItemSearch.filter(all, "eichen stamm")));
        // a blank display name must not break matching by command name
        assertEquals(List.of("stick"), names(ItemSearch.filter(all, "stick")));
        assertEquals(List.of("iron_ingot"), names(ItemSearch.filter(all, "iron_ingot")));
    }
}
