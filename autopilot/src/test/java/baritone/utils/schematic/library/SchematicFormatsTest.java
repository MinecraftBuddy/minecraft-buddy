package baritone.utils.schematic.library;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.schematic.IStaticSchematic;
import baritone.utils.schematic.format.DefaultSchematicFormats;
import baritone.utils.schematic.format.ParseReport;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SchematicFormatsTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static IStaticSchematic parse(DefaultSchematicFormats format, CompoundTag root) throws Exception {
        return format.parse(new ByteArrayInputStream(SchematicFixtures.gzip(root)));
    }

    private static String failure(DefaultSchematicFormats format, CompoundTag root) throws Exception {
        try {
            parse(format, root);
        } catch (UnsupportedOperationException e) {
            return e.getMessage();
        }
        fail("expected the parse to be refused");
        return null;
    }

    // 3 wide, 2 tall, 2 long: stone at the bottom, an oak stair on top with one bogus block in the middle
    private static final String[] PALETTE = {
        "minecraft:air", "minecraft:stone", "minecraft:oak_stairs[facing=east]", "minecraft:not_a_real_block"
    };

    private static int[] ids() {
        int[] ids = new int[12];
        for (int z = 0; z < 2; z++) {
            for (int x = 0; x < 3; x++) {
                ids[SchematicFixtures.index(x, 0, z, 3, 2)] = 1;
                ids[SchematicFixtures.index(x, 1, z, 3, 2)] = x == 1 ? 3 : (x == 0 ? 2 : 0);
            }
        }
        return ids;
    }

    private static void checkShape(IStaticSchematic s) {
        assertEquals(3, s.widthX());
        assertEquals(2, s.heightY());
        assertEquals(2, s.lengthZ());
        assertSame(Blocks.STONE, s.getDirect(2, 0, 1).getBlock());
        BlockState stair = s.getDirect(0, 1, 1);
        assertSame(Blocks.OAK_STAIRS, stair.getBlock());
        assertEquals(Direction.EAST, stair.getValue(StairBlock.FACING));
        assertTrue(s.getDirect(1, 1, 0).isAir());
        assertTrue(s.getDirect(2, 1, 0).isAir());
    }

    private static void checkUnknown(IStaticSchematic s, int version) {
        ParseReport report = (ParseReport) s;
        assertEquals(version, report.formatVersion());
        // two positions used the bogus block (x == 1, both z)
        assertEquals(Map.of("minecraft:not_a_real_block", 2), report.unknownBlocks());
    }

    @Test
    public void spongeV2StillParses() throws Exception {
        IStaticSchematic s = parse(DefaultSchematicFormats.SPONGE, SchematicFixtures.spongeV2(3, 2, 2, PALETTE, ids()));
        checkShape(s);
        checkUnknown(s, 2);
    }

    @Test
    public void spongeV3Parses() throws Exception {
        IStaticSchematic s = parse(DefaultSchematicFormats.SPONGE, SchematicFixtures.spongeV3(3, 2, 2, PALETTE, ids()));
        checkShape(s);
        checkUnknown(s, 3);
    }

    @Test
    public void spongeV3AndV2GiveTheSameBlocks() throws Exception {
        IStaticSchematic a = parse(DefaultSchematicFormats.SPONGE, SchematicFixtures.spongeV2(3, 2, 2, PALETTE, ids()));
        IStaticSchematic b = parse(DefaultSchematicFormats.SPONGE, SchematicFixtures.spongeV3(3, 2, 2, PALETTE, ids()));
        for (int y = 0; y < 2; y++) {
            for (int z = 0; z < 2; z++) {
                for (int x = 0; x < 3; x++) {
                    assertSame(a.getDirect(x, y, z), b.getDirect(x, y, z));
                }
            }
        }
    }

    @Test
    public void spongeV3WithMultiByteVarints() throws Exception {
        // 200 palette entries so the ids past 127 take two bytes each
        String[] palette = new String[200];
        for (int i = 0; i < palette.length; i++) {
            // keys have to be distinct or the compound collapses them, unknown ids are fine as filler
            palette[i] = "minecraft:filler_" + i;
        }
        palette[1] = "minecraft:stone";
        palette[150] = "minecraft:dirt";
        IStaticSchematic s = parse(DefaultSchematicFormats.SPONGE,
            SchematicFixtures.spongeV3(2, 1, 1, palette, new int[]{150, 1}));
        assertSame(Blocks.DIRT, s.getDirect(0, 0, 0).getBlock());
        assertSame(Blocks.STONE, s.getDirect(1, 0, 0).getBlock());
    }

    @Test
    public void spongeV3WithoutBlocksIsAnEmptyShell() throws Exception {
        CompoundTag root = SchematicFixtures.spongeV3(0, 0, 0, new String[0], new int[0]);
        IStaticSchematic s = parse(DefaultSchematicFormats.SPONGE, root);
        assertEquals(0, s.widthX());
    }

    @Test
    public void spongeUnknownVersionsAreRefusedWithTheNumber() throws Exception {
        CompoundTag root = SchematicFixtures.spongeV2(1, 1, 1, new String[]{"minecraft:air"}, new int[]{0});
        root.putInt("Version", 9);
        assertTrue(failure(DefaultSchematicFormats.SPONGE, root).contains("9"));
    }

    @Test
    public void mceditReportsItemOnlyAndUnmappedIds() throws Exception {
        // 1 stone, 0 air, 256 is an iron shovel (item, not a block), 4000 isn't anything
        int[] blocks = {1, 0, 256, 4000, 1};
        IStaticSchematic s = parse(DefaultSchematicFormats.MCEDIT, SchematicFixtures.mcedit(5, 1, 1, blocks));
        assertSame(Blocks.STONE, s.getDirect(0, 0, 0).getBlock());
        assertTrue(s.getDirect(2, 0, 0).isAir());
        assertTrue(s.getDirect(3, 0, 0).isAir());
        ParseReport report = (ParseReport) s;
        assertEquals(0, report.formatVersion());
        assertEquals(2, report.unknownBlocks().size());
        assertTrue(report.unknownBlocks().containsKey("minecraft:iron_shovel"));
        assertTrue(report.unknownBlocks().containsKey("id 4000"));
        // the plain air at id 0 is not a miss
        assertEquals(Integer.valueOf(1), report.unknownBlocks().get("id 4000"));
    }

    @Test
    public void mceditWithNothingUnknownReportsNothing() throws Exception {
        IStaticSchematic s = parse(DefaultSchematicFormats.MCEDIT, SchematicFixtures.mcedit(2, 1, 1, new int[]{1, 0}));
        assertTrue(((ParseReport) s).unknownBlocks().isEmpty());
    }

    @Test
    public void litematicaVersions5To7ParseTheSame() throws Exception {
        for (int version = 5; version <= 7; version++) {
            IStaticSchematic s = parse(DefaultSchematicFormats.LITEMATICA, SchematicFixtures.litematic(version, 3, 2, 2, PALETTE, ids()));
            checkShape(s);
            checkUnknown(s, version);
        }
    }

    @Test
    public void litematicaPaletteThatStraddlesLongs() throws Exception {
        // 20 entries is 5 bits each, and 5 does not divide 64 so entries cross word boundaries. 4x4x4 = 64 positions
        List<String> names = List.of("stone", "dirt", "granite", "diorite", "andesite", "cobblestone", "oak_planks",
            "spruce_planks", "birch_planks", "jungle_planks", "acacia_planks", "sand", "gravel", "gold_ore",
            "iron_ore", "coal_ore", "oak_log", "glass", "lapis_ore", "sandstone");
        String[] palette = names.stream().map(n -> "minecraft:" + n).toArray(String[]::new);
        int[] ids = new int[64];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = (i * 7) % 20;
        }
        for (int version = 5; version <= 7; version++) {
            IStaticSchematic s = parse(DefaultSchematicFormats.LITEMATICA, SchematicFixtures.litematic(version, 4, 4, 4, palette, ids));
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        String id = MaterialList.blockId(s.getDirect(x, y, z).getBlock());
                        assertEquals("minecraft:" + names.get(ids[SchematicFixtures.index(x, y, z, 4, 4)]), id);
                    }
                }
            }
        }
    }

    @Test
    public void litematicaPrePaletteVersionsAreRefusedHonestly() throws Exception {
        CompoundTag old = SchematicFixtures.litematic(4, 3, 2, 2, PALETTE, ids());
        String message = failure(DefaultSchematicFormats.LITEMATICA, old);
        assertTrue(message, message.contains("1.13"));
        assertTrue(message, message.contains("5 to 7"));
    }

    @Test
    public void litematicaNewerThanWeKnowIsNotCalledTooOld() throws Exception {
        String message = failure(DefaultSchematicFormats.LITEMATICA, SchematicFixtures.litematic(8, 3, 2, 2, PALETTE, ids()));
        assertTrue(message, message.contains("8"));
        assertTrue(message, !message.contains("too old"));
    }

    @Test
    public void oldPropertyValuesKeepTheBlockAndAreReported() throws Exception {
        // 1.13 walls said east=true, now it's none/low/tall. that used to throw and lose the whole file
        String[] palette = {"minecraft:air", "minecraft:cobblestone_wall[east=true,up=true]"};
        for (CompoundTag root : List.of(
            SchematicFixtures.litematic(5, 1, 1, 1, palette, new int[]{1}),
            SchematicFixtures.spongeV2(1, 1, 1, palette, new int[]{1}))) {
            DefaultSchematicFormats format = root.contains("Regions") ? DefaultSchematicFormats.LITEMATICA : DefaultSchematicFormats.SPONGE;
            IStaticSchematic s = parse(format, root);
            BlockState wall = s.getDirect(0, 0, 0);
            assertSame(Blocks.COBBLESTONE_WALL, wall.getBlock());
            // the good property still applied
            assertTrue(wall.getValue(net.minecraft.world.level.block.WallBlock.UP));
            assertEquals(Map.of("minecraft:cobblestone_wall[east=true]", 1), ((ParseReport) s).unknownBlocks());
        }
    }

    @Test
    public void litematicaMultiRegionCountsUnknownsAcrossRegions() throws Exception {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 6);
        CompoundTag regions = new CompoundTag();
        String[] palette = {"minecraft:air", "minecraft:stone", "minecraft:old_name"};
        regions.put("a", SchematicFixtures.litematicRegion(0, 0, 0, 2, 1, 1, palette, new int[]{1, 2}));
        regions.put("b", SchematicFixtures.litematicRegion(5, 0, 0, 2, 1, 1, palette, new int[]{2, 2}));
        root.put("Regions", regions);
        IStaticSchematic s = parse(DefaultSchematicFormats.LITEMATICA, root);
        assertEquals(7, s.widthX());
        assertEquals(Map.of("minecraft:old_name", 3), ((ParseReport) s).unknownBlocks());
    }
}
