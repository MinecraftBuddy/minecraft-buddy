package adris.altoclef.tasks.construction;

import adris.altoclef.util.helpers.ItemFixture;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

// what a schematic build can go and get by itself. the catalogue answers, this pins the answer for the blocks people
// actually build with, so a catalogue change that drops one (or finally adds concrete) shows up here
public class SchematicGettableTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static final String[] WOODS = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak"};
    // colours whose dye has a source (flowers, bone meal, lapis, cactus, mixes). gray, brown and black have none
    private static final String[] DYED = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "light_gray",
        "cyan", "purple", "blue", "green", "red"};
    private static final String[] COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};

    private static final List<String> GETTABLE = List.of(
        "glass", "glass_pane", "quartz_block", "quartz_bricks", "smooth_quartz", "quartz_pillar", "chiseled_quartz_block",
        "quartz_stairs", "quartz_slab", "copper_block", "cut_copper", "deepslate", "cobbled_deepslate", "polished_deepslate",
        "deepslate_bricks", "deepslate_tiles", "chiseled_deepslate", "bricks", "brick_stairs", "smooth_stone",
        "smooth_stone_slab", "stone", "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "mossy_cobblestone",
        "cobblestone", "cobblestone_stairs", "stone_brick_stairs", "polished_andesite", "polished_diorite",
        "polished_granite", "andesite", "diorite", "granite", "nether_bricks", "red_nether_bricks", "glowstone",
        "blackstone", "polished_blackstone", "polished_blackstone_bricks", "netherrack", "obsidian", "sand", "red_sand",
        "sandstone", "smooth_sandstone", "cut_sandstone", "red_sandstone", "gravel", "dirt", "coarse_dirt", "clay",
        "hay_block", "bookshelf", "lantern", "torch", "chest", "barrel", "crafting_table", "furnace", "ladder",
        "iron_bars", "iron_block", "gold_block", "tuff", "calcite", "crimson_stem", "warped_stem", "crimson_planks",
        "warped_planks", "bamboo_block", "bamboo_planks", "bamboo_stairs", "bamboo_slab", "amethyst_block", "basalt",
        "polished_basalt", "soul_sand", "magma_block", "terracotta", "crimson_hyphae", "warped_hyphae", "stripped_oak_wood");

    // no catalogue entry, the build goes around them and says so
    private static final List<String> NOT_GETTABLE = List.of(
        "prismarine", "prismarine_bricks", "dark_prismarine", "sea_lantern", "mud", "packed_mud", "mud_bricks",
        "end_stone", "end_stone_bricks", "purpur_block", "end_rod", "waxed_copper_block", "exposed_copper",
        "grass_block", "snow_block", "ice", "packed_ice",
        // creative only, and nothing at all
        "bedrock", "air", "not_an_item");

    private static List<String> gettable() {
        List<String> out = new ArrayList<>(GETTABLE);
        for (String wood : WOODS) {
            for (String kind : new String[]{"log", "planks", "leaves", "stairs", "slab", "fence", "door", "trapdoor"}) {
                out.add(wood + "_" + kind);
            }
        }
        for (String color : COLORS) {
            out.add(color + "_wool");
            out.add(color + "_bed");
            out.add(color + "_carpet");
        }
        for (String color : DYED) {
            for (String kind : new String[]{"dye", "stained_glass", "stained_glass_pane", "terracotta", "glazed_terracotta"}) {
                out.add(color + "_" + kind);
            }
        }
        for (String wood : WOODS) {
            out.add(wood + "_wood");
            out.add("stripped_" + wood + "_wood");
        }
        return out;
    }

    private static List<String> notGettable() {
        List<String> out = new ArrayList<>(NOT_GETTABLE);
        for (String color : COLORS) {
            for (String kind : new String[]{"concrete", "concrete_powder"}) {
                out.add(color + "_" + kind);
            }
        }
        // no dye source, so no dyed copies either (an entry would stall the build instead of skipping)
        for (String color : new String[]{"gray", "brown", "black"}) {
            for (String kind : new String[]{"stained_glass", "stained_glass_pane", "terracotta", "glazed_terracotta"}) {
                out.add(color + "_" + kind);
            }
        }
        return out;
    }

    @Test
    public void theCommonBlocks() {
        List<String> wrong = new ArrayList<>();
        for (String id : gettable()) {
            if (!SchematicBuildTask.gettable("minecraft:" + id)) {
                wrong.add(id + " should be gettable");
            }
        }
        for (String id : notGettable()) {
            if (SchematicBuildTask.gettable("minecraft:" + id)) {
                wrong.add(id + " should not be gettable");
            }
        }
        assertEquals(List.of(), wrong);
    }
}
