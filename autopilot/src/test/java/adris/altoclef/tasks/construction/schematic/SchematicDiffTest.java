package adris.altoclef.tasks.construction.schematic;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.utils.schematic.StaticSchematic;
import baritone.utils.schematic.library.MaterialLine;
import baritone.utils.schematic.library.MaterialList;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SchematicDiffTest {

    // set in boot, same reason as SkipItemsTest: touching Blocks before the bootstrap sinks the whole jvm's registries
    private static BlockState AIR;
    private static BlockState STONE;
    private static BlockState GLASS;

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
        AIR = Blocks.AIR.defaultBlockState();
        STONE = Blocks.STONE.defaultBlockState();
        GLASS = Blocks.GLASS.defaultBlockState();
    }

    // a world that is air unless told otherwise, with the chunks listed as unloaded
    private static final class FakeWorld implements SchematicDiff.World {
        final Map<Long, BlockState> blocks = new HashMap<>();
        final Set<Long> unloaded = new java.util.HashSet<>();

        FakeWorld set(int x, int y, int z, BlockState s) {
            blocks.put(key(x, y, z), s);
            return this;
        }

        static long key(int x, int y, int z) {
            return ((long) x & 0xFFFFF) | (((long) z & 0xFFFFF) << 20) | (((long) y & 0xFFF) << 40);
        }

        @Override
        public boolean loaded(int chunkX, int chunkZ) {
            return !unloaded.contains(((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL));
        }

        @Override
        public BlockState state(int x, int y, int z) {
            return blocks.getOrDefault(key(x, y, z), AIR);
        }
    }

    // a wall along x, [x][z][y] the way StaticSchematic wants it
    private static StaticSchematic row(BlockState... states) {
        BlockState[][][] s = new BlockState[states.length][1][1];
        for (int x = 0; x < states.length; x++) {
            s[x][0][0] = states[x];
        }
        return new StaticSchematic(s);
    }

    private static Map<String, Integer> items(MaterialList list) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (MaterialLine line : list.items()) {
            out.put(line.item(), line.count());
        }
        return out;
    }

    private static Map<String, Integer> run(SchematicDiff diff, SchematicDiff.World world) {
        int guard = 0;
        while (!diff.step(world, 2)) {
            assertTrue("diff never finished", ++guard < 1000);
        }
        return items(diff.result());
    }

    @Test
    public void builtCellsAreFree() {
        FakeWorld world = new FakeWorld().set(10, 64, 0, STONE).set(11, 64, 0, STONE);
        SchematicDiff diff = new SchematicDiff(row(STONE, STONE, STONE, GLASS), 10, 64, 0, b -> false);
        assertEquals(Map.of("minecraft:stone", 1, "minecraft:glass", 1), run(diff, world));
        // the two right ones are what the gather has to keep its pickaxe off
        assertEquals(Set.of(net.minecraft.core.BlockPos.asLong(10, 64, 0), net.minecraft.core.BlockPos.asLong(11, 64, 0)),
            new java.util.HashSet<>(diff.built()));
        assertEquals(2, diff.correct());
        assertEquals(2, diff.wrong());
        // the watch counts the same and keeps no positions and no bill
        SchematicDiff watch = SchematicDiff.watching(row(STONE, STONE, STONE, GLASS), 10, 64, 0, b -> false);
        assertEquals(Map.of(), run(watch, world));
        assertEquals(2, watch.correct());
        assertEquals(2, watch.wrong());
        assertTrue(watch.built().isEmpty());
    }

    @Test
    public void theWrongBlockStillNeedsTheRightOne() {
        FakeWorld world = new FakeWorld().set(0, 0, 0, Blocks.DIRT.defaultBlockState());
        SchematicDiff diff = new SchematicDiff(row(STONE), 0, 0, 0, b -> false);
        assertEquals(Map.of("minecraft:stone", 1), run(diff, world));
    }

    @Test
    public void unloadedCellsCountAsNeeded() {
        // the second cell sits in chunk x=1, which nobody has loaded. stone there or not, we can't see it
        FakeWorld world = new FakeWorld().set(15, 0, 0, STONE).set(16, 0, 0, STONE);
        world.unloaded.add((1L << 32));
        SchematicDiff diff = new SchematicDiff(row(STONE, STONE), 15, 0, 0, b -> false);
        assertEquals(Map.of("minecraft:stone", 1), run(diff, world));
    }

    @Test
    public void airAndSkippedBlocksCostNothing() {
        SchematicDiff diff = new SchematicDiff(row(AIR, GLASS, STONE), 0, 0, 0, b -> b == Blocks.GLASS);
        assertEquals(Map.of("minecraft:stone", 1), run(diff, new FakeWorld()));
    }

    @Test
    public void stepsInSlices() {
        SchematicDiff diff = new SchematicDiff(row(STONE, STONE, STONE, STONE, STONE), 0, 0, 0, b -> false);
        FakeWorld world = new FakeWorld();
        assertFalse(diff.step(world, 2));
        assertNull(diff.result());
        assertFalse(diff.step(world, 2));
        assertTrue(diff.step(world, 2));
        assertEquals(Map.of("minecraft:stone", 5), items(diff.result()));
        // asking again after the end is harmless
        assertTrue(diff.step(world, 2));
    }

    @Test
    public void walksEveryAxis() {
        BlockState[][][] s = new BlockState[2][3][4];
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 3; z++) {
                for (int y = 0; y < 4; y++) {
                    s[x][z][y] = STONE;
                }
            }
        }
        FakeWorld world = new FakeWorld().set(101, 53, 7, STONE);
        SchematicDiff diff = new SchematicDiff(new StaticSchematic(s), 100, 50, 5, b -> false);
        assertEquals(Map.of("minecraft:stone", 23), run(diff, world));
    }

    // ----- the builder's missing map, priced

    private static Block block(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse("minecraft:" + id));
    }

    @Test
    public void pricedFollowsTheMaterialRules() {
        Map<BlockState, Integer> missing = new LinkedHashMap<>();
        missing.put(Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE), 3);
        missing.put(Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM), 1);
        missing.put(Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER), 2);
        missing.put(Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), 2);
        missing.put(Blocks.WALL_TORCH.defaultBlockState(), 4);
        missing.put(Blocks.TORCH.defaultBlockState(), 1);
        assertEquals(Map.of("minecraft:oak_slab", 7, "minecraft:oak_door", 2, "minecraft:torch", 5),
            SchematicDiff.priced(missing));
    }

    @Test
    public void aLoneSecondHalfIsNotAMaterial() {
        Map<BlockState, Integer> missing = Map.of(
            block("red_bed").defaultBlockState().setValue(BedBlock.PART, BedPart.FOOT), 1,
            Blocks.WATER.defaultBlockState(), 3);
        assertTrue(SchematicDiff.priced(missing).isEmpty());
    }
}
