package adris.altoclef.ui.gui.mc.ghost;

import adris.altoclef.ui.gui.mc.ghost.GhostScan.Ghost;
import adris.altoclef.ui.gui.mc.ghost.GhostScan.Kind;
import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.process.BuilderProcess;
import baritone.utils.schematic.StaticSchematic;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// the diff, against a world made of a hash map
public class GhostScanTest {

    static final BlockState AIR = Blocks.AIR.defaultBlockState();
    static final BlockState STONE = Blocks.STONE.defaultBlockState();
    static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    static final BlockState GLASS = Blocks.GLASS.defaultBlockState();

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    // air everywhere unless set, every chunk loaded unless said otherwise
    static final class FakeWorld implements GhostScan.World {
        final Map<Long, BlockState> blocks = new HashMap<>();
        final Set<Long> unloaded = new HashSet<>();
        int reads;

        void set(int x, int y, int z, BlockState s) {
            blocks.put(key(x, y, z), s);
        }

        void unload(int chunkX, int chunkZ) {
            unloaded.add(((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL));
        }

        @Override
        public boolean loaded(int chunkX, int chunkZ) {
            return !unloaded.contains(((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL));
        }

        @Override
        public BlockState state(int x, int y, int z) {
            reads++;
            return blocks.getOrDefault(key(x, y, z), AIR);
        }

        private static long key(int x, int y, int z) {
            return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
        }
    }

    // [x][z][y] like StaticSchematic, everything one state
    static StaticSchematic box(int sx, int sy, int sz, BlockState s) {
        BlockState[][][] states = new BlockState[sx][sz][sy];
        for (BlockState[][] plane : states) {
            for (BlockState[] column : plane) {
                java.util.Arrays.fill(column, s);
            }
        }
        return new StaticSchematic(states);
    }

    private static Ghost at(List<Ghost> ghosts, int x, int y, int z) {
        for (Ghost g : ghosts) {
            if (g.x() == x && g.y() == y && g.z() == z) {
                return g;
            }
        }
        return null;
    }

    private static boolean face(Ghost g, Direction d) {
        return (g.faces() & (1 << d.get3DDataValue())) != 0;
    }

    @Test
    public void classifyCoversEveryCase() {
        BlockState north = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH);
        BlockState south = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH);
        assertNull(GhostScan.classify(STONE, STONE));
        assertNull(GhostScan.classify(AIR, AIR));
        assertNull(GhostScan.classify(null, STONE));
        assertEquals(Kind.MISSING, GhostScan.classify(STONE, AIR));
        // grass and water get placed over, they're not in the way
        assertEquals(Kind.MISSING, GhostScan.classify(STONE, Blocks.SHORT_GRASS.defaultBlockState()));
        assertEquals(Kind.MISSING, GhostScan.classify(STONE, Blocks.WATER.defaultBlockState()));
        assertEquals(Kind.WRONG_STATE, GhostScan.classify(north, south));
        assertEquals(Kind.WRONG_BLOCK, GhostScan.classify(STONE, DIRT));
        assertEquals(Kind.EXTRA, GhostScan.classify(AIR, DIRT));
        assertEquals(Kind.EXTRA, GhostScan.classify(Blocks.CAVE_AIR.defaultBlockState(), DIRT));
        // the builder breaks grass in the way, it can't do anything about water
        assertEquals(Kind.EXTRA, GhostScan.classify(AIR, Blocks.SHORT_GRASS.defaultBlockState()));
        assertNull(GhostScan.classify(AIR, Blocks.WATER.defaultBlockState()));
        assertNull(GhostScan.classify(AIR, Blocks.LAVA.defaultBlockState()));
    }

    // every leniency setting, checked against BuilderProcess.valid on the same pair so the two can't drift
    @Test
    public void theBuildersLeniencySettingsCountAsRight() {
        Settings s = BaritoneAPI.getSettings();
        BlockState north = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH);
        BlockState south = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH);
        BlockState bottom = Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        BlockState top = Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
        try {
            assertEquals(Kind.WRONG_STATE, GhostScan.classify(north, south));
            s.buildIgnoreDirection.value = true;
            assertAgrees(north, south);
            s.buildIgnoreDirection.value = false;

            assertEquals(Kind.WRONG_STATE, GhostScan.classify(bottom, top));
            s.buildIgnoreProperties.value = new ArrayList<>(List.of("type"));
            assertAgrees(bottom, top);
            s.buildIgnoreProperties.value = new ArrayList<>();

            s.buildValidSubstitutes.value = new HashMap<>(Map.of(Blocks.STONE, List.of(Blocks.DIRT)));
            assertAgrees(STONE, DIRT);
            s.buildValidSubstitutes.value = new HashMap<>();

            s.buildIgnoreBlocks.value = new ArrayList<>(List.of(Blocks.DIRT));
            assertAgrees(AIR, DIRT);
            s.buildIgnoreBlocks.value = new ArrayList<>();

            s.okIfAir.value = new ArrayList<>(List.of(Blocks.STONE));
            assertAgrees(STONE, AIR);
            s.okIfAir.value = new ArrayList<>();

            // and back to strict, the ghost shows again
            assertEquals(Kind.WRONG_STATE, GhostScan.classify(north, south));
            assertEquals(Kind.EXTRA, GhostScan.classify(AIR, DIRT));
            assertEquals(Kind.MISSING, GhostScan.classify(STONE, AIR));
        } finally {
            s.buildIgnoreDirection.reset();
            s.buildIgnoreProperties.reset();
            s.buildValidSubstitutes.reset();
            s.buildIgnoreBlocks.reset();
            s.okIfAir.reset();
        }
    }

    private static void assertAgrees(BlockState want, BlockState have) {
        assertTrue(BuilderProcess.valid(have, want, false));
        assertNull(GhostScan.classify(want, have));
    }

    @Test
    public void onlyWhatDiffersAndOnlyInsideTheFootprint() {
        FakeWorld world = new FakeWorld();
        // 3x1x1 stone at 2,64,2 .. 4,64,2. one already built, one wrong, one missing
        world.set(2, 64, 2, STONE);
        world.set(3, 64, 2, DIRT);
        world.set(1, 64, 2, DIRT); // outside, never a ghost
        List<Ghost> ghosts = new GhostScan().section(box(3, 1, 1, STONE), 2, 64, 2, world, 0, 4, 0);
        assertEquals(2, ghosts.size());
        assertEquals(Kind.WRONG_BLOCK, at(ghosts, 3, 64, 2).kind());
        assertEquals(Kind.MISSING, at(ghosts, 4, 64, 2).kind());
        assertEquals(STONE, at(ghosts, 4, 64, 2).want());
    }

    @Test
    public void schematicAirOverABlockIsSomethingToRemove() {
        FakeWorld world = new FakeWorld();
        world.set(0, 64, 0, DIRT);
        List<Ghost> ghosts = new GhostScan().section(box(2, 1, 1, AIR), 0, 64, 0, world, 0, 4, 0);
        assertEquals(1, ghosts.size());
        assertEquals(Kind.EXTRA, ghosts.get(0).kind());
    }

    @Test
    public void anUnloadedChunkIsNotOneBigGhost() {
        FakeWorld world = new FakeWorld();
        world.unload(1, 0);
        // 32 wide, the half in chunk 1 isn't there
        List<Ghost> ghosts = new GhostScan().section(box(32, 1, 1, STONE), 0, 64, 0, world, 1, 4, 0);
        assertTrue(ghosts.isEmpty());
        List<Ghost> loaded = new GhostScan().section(box(32, 1, 1, STONE), 0, 64, 0, world, 0, 4, 0);
        assertEquals(16, loaded.size());
    }

    @Test
    public void sidesAgainstSolidNeighboursAreCulled() {
        FakeWorld world = new FakeWorld();
        // two stones side by side on a dirt floor, glass to the north of the first
        world.set(0, 63, 0, DIRT);
        world.set(1, 63, 0, DIRT);
        world.set(0, 64, -1, GLASS);
        List<Ghost> ghosts = new GhostScan().section(box(2, 1, 1, STONE), 0, 64, 0, world, 0, 4, 0);
        Ghost first = at(ghosts, 0, 64, 0);
        // the floor hides the bottom, the other ghost stone hides the inside face
        assertTrue(!face(first, Direction.DOWN));
        assertTrue(!face(first, Direction.EAST));
        // glass doesn't hide anything, the rest is open air
        assertTrue(face(first, Direction.NORTH));
        assertTrue(face(first, Direction.UP));
        assertTrue(face(first, Direction.WEST));
        assertTrue(face(first, Direction.SOUTH));
        assertTrue(!face(at(ghosts, 1, 64, 0), Direction.WEST));
    }

    @Test
    public void aBlockWalledInOnEverySideIsNotAGhostAtAll() {
        FakeWorld world = new FakeWorld();
        List<Ghost> ghosts = new GhostScan().section(box(3, 3, 3, STONE), 0, 64, 0, world, 0, 4, 0);
        assertEquals(26, ghosts.size());
        assertNull(at(ghosts, 1, 65, 1));
        // same for a block to dig out from the middle of a lump of dirt
        FakeWorld ground = new FakeWorld();
        for (int x = 0; x <= 2; x++) {
            for (int y = 64; y <= 66; y++) {
                for (int z = 0; z <= 2; z++) {
                    ground.set(x, y, z, DIRT);
                }
            }
        }
        List<Ghost> extras = new GhostScan().section(box(3, 3, 3, AIR), 0, 64, 0, ground, 0, 4, 0);
        assertEquals(26, extras.size());
        assertNull(at(extras, 1, 65, 1));
    }

    @Test
    public void theCullingSeesAcrossTheSectionEdge() {
        FakeWorld world = new FakeWorld();
        // a stone wall straddling x=15|16, the neighbour sits in the next section
        List<Ghost> ghosts = new GhostScan().section(box(2, 1, 1, STONE), 15, 64, 0, world, 0, 4, 0);
        assertEquals(1, ghosts.size());
        assertTrue(!face(ghosts.get(0), Direction.EAST));
    }

    @Test
    public void anEdgeSectionOnlyReadsTheFootprintAndItsRim() {
        FakeWorld world = new FakeWorld();
        // a 1x1x1 schematic in the corner of a section: 3x3x3 reads, not 18^3
        new GhostScan().section(box(1, 1, 1, STONE), 0, 64, 0, world, 0, 4, 0);
        assertEquals(27, world.reads);
    }
}
