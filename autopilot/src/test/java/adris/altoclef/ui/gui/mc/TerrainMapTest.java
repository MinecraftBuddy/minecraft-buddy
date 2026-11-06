package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.facade.SchematicsAccess.Terrain;
import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

// the map sampler on a pretend level: flat grass at y 63 on stone, with columns and chunks changed by hand
public class TerrainMapTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    private static final class Level implements TerrainMap.Source {
        final Set<Long> unloaded = new HashSet<>();
        // x,z -> {top y, top state}
        final Map<Long, Object[]> columns = new HashMap<>();
        int calls;

        static long key(int a, int b) {
            return ((long) a << 32) | (b & 0xFFFFFFFFL);
        }

        void column(int x, int z, int top, BlockState state) {
            columns.put(key(x, z), new Object[]{top, state});
        }

        @Override
        public boolean hasChunk(int chunkX, int chunkZ) {
            return !unloaded.contains(key(chunkX, chunkZ));
        }

        @Override
        public int surfaceY(int x, int z) {
            calls++;
            Object[] c = columns.get(key(x, z));
            return c == null ? 63 : (int) c[0];
        }

        @Override
        public int minY() {
            return -64;
        }

        @Override
        public BlockState state(int x, int y, int z) {
            Object[] c = columns.get(key(x, z));
            int top = c == null ? 63 : (int) c[0];
            if (y > top) {
                return Blocks.AIR.defaultBlockState();
            }
            if (y == top) {
                return c == null ? Blocks.GRASS_BLOCK.defaultBlockState() : (BlockState) c[1];
            }
            // under water is more water for two blocks, then stone
            if (c != null && ((BlockState) c[1]).is(Blocks.WATER) && y >= top - 2) {
                return Blocks.WATER.defaultBlockState();
            }
            return Blocks.STONE.defaultBlockState();
        }

        @Override
        public MapColor color(BlockState state, int x, int y, int z) {
            return state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        }
    }

    private static int argb(MapColor color, MapColor.Brightness b) {
        return 0xFF000000 | color.calculateARGBColor(b);
    }

    @Test
    public void flatGrassIsGrassAtNormalBrightness() {
        Level level = new Level();
        assertEquals(argb(MapColor.GRASS, MapColor.Brightness.NORMAL), TerrainMap.columnColor(level, 5, 5));
    }

    @Test
    public void higherThanTheNorthNeighbourIsBrighterLowerIsDarker() {
        Level level = new Level();
        level.column(5, 5, 70, Blocks.GRASS_BLOCK.defaultBlockState());
        level.column(5, 6, 63, Blocks.GRASS_BLOCK.defaultBlockState());
        assertEquals(argb(MapColor.GRASS, MapColor.Brightness.HIGH), TerrainMap.columnColor(level, 5, 5));
        assertEquals(argb(MapColor.GRASS, MapColor.Brightness.LOW), TerrainMap.columnColor(level, 5, 6));
    }

    @Test
    public void glassIsSeenThrough() {
        Level level = new Level();
        level.column(3, 3, 63, Blocks.GLASS.defaultBlockState());
        assertEquals(MapColor.NONE, Blocks.GLASS.defaultBlockState().getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
        assertEquals(argb(MapColor.STONE, MapColor.Brightness.NORMAL), TerrainMap.columnColor(level, 3, 3));
    }

    @Test
    public void waterIsWaterWithTheDepthDither() {
        Level level = new Level();
        level.column(0, 0, 62, Blocks.WATER.defaultBlockState());
        level.column(1, 0, 62, Blocks.WATER.defaultBlockState());
        // three deep: 0.3 on an even square is bright, 0.5 on an odd one is normal
        assertEquals(argb(MapColor.WATER, MapColor.Brightness.HIGH), TerrainMap.columnColor(level, 0, 0));
        assertEquals(argb(MapColor.WATER, MapColor.Brightness.NORMAL), TerrainMap.columnColor(level, 1, 0));
    }

    @Test
    public void anEmptyColumnIsTheVoidColourNotUnloaded() {
        Level level = new Level();
        level.column(2, 2, -65, Blocks.AIR.defaultBlockState());
        int c = TerrainMap.columnColor(level, 2, 2);
        assertEquals(TerrainMap.VOID, c);
        assertNotEquals(0, c);
    }

    @Test
    public void theWindowIsChunkAlignedAndSizedByTheRadius() {
        TerrainMap map = new TerrainMap(() -> 0);
        Object world = new Object();
        Terrain t = map.snapshot(world, new Level(), 100, -37, Terrain.RADIUS_CHUNKS);
        assertEquals(256, t.size());
        assertEquals(0, t.minX() & 15);
        assertEquals(0, t.minZ() & 15);
        assertTrue(t.minX() <= 100 && 100 < t.minX() + t.size());
        assertTrue(t.minZ() <= -37 && -37 < t.minZ() + t.size());
        // the middle of the window is within half a chunk of the centre asked for
        assertTrue(Math.abs(t.minX() + 128 - 100) <= 8);
        assertTrue(Math.abs(t.minZ() + 128 + 37) <= 8);
        assertEquals(256, t.totalChunks());
        // silly radii are clamped
        assertEquals(32, map.snapshot(world, new Level(), 0, 0, 0).size());
        assertEquals(Terrain.MAX_RADIUS_CHUNKS * 32, map.snapshot(world, new Level(), 0, 0, 99).size());
    }

    @Test
    public void itFillsInAFewChunksAtATimeFromTheMiddle() {
        TerrainMap map = new TerrainMap(() -> 0);
        Object world = new Object();
        Level level = new Level();
        Terrain first = map.snapshot(world, level, 0, 0, 8);
        assertEquals(TerrainMap.CHUNKS_PER_CALL, first.knownChunks());
        assertFalse(first.complete());
        // the player's own chunk is there straight away, a corner isn't
        assertTrue(first.knownAt(0, 0));
        assertFalse(first.knownAt(first.minX(), first.minZ()));
        assertEquals(0, first.colorAt(first.minX(), first.minZ()));
        Terrain t = first;
        for (int i = 0; i < 256 && !t.complete(); i++) {
            t = map.snapshot(world, level, 0, 0, 8);
        }
        assertTrue(t.complete());
        assertTrue(t.knownAt(t.minX(), t.minZ()));
        // nothing new to sample: the same snapshot, no new pixels, no work
        int before = level.calls;
        assertSame(t, map.snapshot(world, level, 0, 0, 8));
        assertEquals(before, level.calls);
    }

    @Test
    public void unknownChunksStayEmptyAndFillInWhenTheyLoad() {
        TerrainMap map = new TerrainMap(() -> 0);
        Object world = new Object();
        Level level = new Level();
        level.unloaded.add(Level.key(3, 3));
        Terrain t = null;
        for (int i = 0; i < 64; i++) {
            t = map.snapshot(world, level, 0, 0, 4);
        }
        assertEquals(t.totalChunks() - 1, t.knownChunks());
        assertFalse(t.knownAt(3 * 16 + 5, 3 * 16 + 5));
        level.unloaded.clear();
        Terrain after = map.snapshot(world, level, 0, 0, 4);
        assertTrue(after.complete());
        assertTrue(after.knownAt(3 * 16 + 5, 3 * 16 + 5));
        assertNotEquals(t.stamp(), after.stamp());
    }

    @Test
    public void staleChunksAreLookedAtAgainSlowly() {
        long[] now = {0};
        TerrainMap map = new TerrainMap(() -> now[0]);
        Object world = new Object();
        Level level = new Level();
        Terrain t = null;
        for (int i = 0; i < 16; i++) {
            t = map.snapshot(world, level, 0, 0, 2);
        }
        assertTrue(t.complete());
        // somebody builds a stone tower
        level.column(1, 1, 90, Blocks.STONE.defaultBlockState());
        assertEquals(argb(MapColor.GRASS, MapColor.Brightness.NORMAL), map.snapshot(world, level, 0, 0, 2).colorAt(1, 1));
        now[0] = TerrainMap.REFRESH_MS;
        // one chunk per call, so within all 16 calls the tower turns up
        for (int i = 0; i < 16; i++) {
            t = map.snapshot(world, level, 0, 0, 2);
        }
        assertEquals(MapColor.STONE.calculateARGBColor(MapColor.Brightness.HIGH) | 0xFF000000, t.colorAt(1, 1));
    }

    @Test
    public void anUnloadedChunkDoesNotHogTheRefresh() {
        long[] now = {0};
        TerrainMap map = new TerrainMap(() -> now[0]);
        Object world = new Object();
        Level level = new Level();
        for (int i = 0; i < 4; i++) {
            map.snapshot(world, level, 0, 0, 2);
        }
        // the middle chunk (the first one sampled, so the stalest) unloads, and the map keeps its colours
        level.unloaded.add(Level.key(0, 0));
        level.column(17, 1, 90, Blocks.STONE.defaultBlockState());
        now[0] = TerrainMap.REFRESH_MS;
        Terrain t = null;
        for (int i = 0; i < 16; i++) {
            t = map.snapshot(world, level, 0, 0, 2);
        }
        // the others still got their turn
        assertEquals(argb(MapColor.STONE, MapColor.Brightness.HIGH), t.colorAt(17, 1));
        // and the unloaded one is remembered, not blanked
        assertTrue(t.knownAt(1, 1));
        assertTrue(t.complete());
    }

    @Test
    public void resetForgetsEverythingEvenForTheSameLevel() {
        TerrainMap map = new TerrainMap(() -> 0);
        Level level = new Level();
        Object world = new Object();
        for (int i = 0; i < 4; i++) {
            map.snapshot(world, level, 0, 0, 2);
        }
        assertTrue(map.snapshot(world, level, 0, 0, 2).complete());
        map.reset();
        assertEquals(TerrainMap.CHUNKS_PER_CALL, map.snapshot(world, level, 0, 0, 2).knownChunks());
    }

    @Test
    public void aNewWorldStartsOver() {
        TerrainMap map = new TerrainMap(() -> 0);
        Level level = new Level();
        Object one = new Object();
        for (int i = 0; i < 16; i++) {
            map.snapshot(one, level, 0, 0, 2);
        }
        assertTrue(map.snapshot(one, level, 0, 0, 2).complete());
        Terrain fresh = map.snapshot(new Object(), level, 0, 0, 2);
        assertEquals(TerrainMap.CHUNKS_PER_CALL, fresh.knownChunks());
    }

    @Test
    public void pixelsCopyRowMajor() {
        TerrainMap map = new TerrainMap(() -> 0);
        Level level = new Level();
        level.column(5, 2, 63, Blocks.SAND.defaultBlockState());
        Terrain t = map.snapshot(new Object(), level, 0, 0, 1);
        int[] px = new int[t.size() * t.size()];
        t.copyPixels(px);
        assertEquals(t.colorAt(5, 2), px[(2 - t.minZ()) * t.size() + (5 - t.minX())]);
        assertEquals(argb(MapColor.SAND, MapColor.Brightness.NORMAL), t.colorAt(5, 2));
        // outside the window is nothing
        assertEquals(0, t.colorAt(t.minX() - 1, 0));
    }
}
