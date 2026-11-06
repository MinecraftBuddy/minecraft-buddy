package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.facade.SchematicsAccess.Terrain;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

// the top down map behind the placement widget: one ARGB per block column, the way a vanilla map colours it. sampled
// a few chunks per call on the game thread (the only thread allowed to touch ClientLevel chunks) and kept per chunk,
// so the render side only ever sees a finished immutable Terrain. call snapshot() once a tick, not per frame
final class TerrainMap {

    // a level as far as the map cares. game thread only, like the level behind it
    interface Source {
        boolean hasChunk(int chunkX, int chunkZ);

        // y of the top non-air block in the column (the WORLD_SURFACE heightmap minus one), below minY for an empty one
        int surfaceY(int x, int z);

        int minY();

        BlockState state(int x, int y, int z);

        MapColor color(BlockState state, int x, int y, int z);
    }

    // new chunks sampled per call, 256 columns each. 8 a tick fills the 16 x 16 chunk window in under two seconds
    // without the tick ever noticing
    static final int CHUNKS_PER_CALL = 8;
    // a sampled chunk is looked at again after this long, one per call, so a built house shows up eventually
    static final long REFRESH_MS = 10_000;
    private static final int CACHE_CHUNKS = 1024;
    // loaded, but nothing a map would draw all the way down (the end void, a glass tower over the void)
    static final int VOID = 0xFF101014;
    // how far down to look through blocks with no map colour (glass, air pockets in the heightmap) before giving up
    private static final int SEE_THROUGH = 64;

    private static final class Chunk {
        final int[] argb;
        final long sampledAt;

        Chunk(int[] argb, long sampledAt) {
            this.argb = argb;
            this.sampledAt = sampledAt;
        }
    }

    private final LongSupplier clock;
    private final Map<Long, Chunk> chunks = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Chunk> eldest) {
            return size() > CACHE_CHUNKS;
        }
    };
    private Object world;
    private Terrain last;
    private boolean dirty = true;
    private long stamp;

    TerrainMap(LongSupplier clock) {
        this.clock = clock;
    }

    // left the world: drop everything, so coming back (even to the same level object) starts from nothing
    void reset() {
        world = null;
        chunks.clear();
        last = null;
        dirty = true;
    }

    // world is anything that changes with the level (the level itself): a new one drops every cached chunk
    Terrain snapshot(Object world, Source source, int centerX, int centerZ, int radiusChunks) {
        if (world != this.world) {
            this.world = world;
            chunks.clear();
            last = null;
            dirty = true;
        }
        int r = Math.max(1, Math.min(Terrain.MAX_RADIUS_CHUNKS, radiusChunks));
        // chunk aligned and 2r chunks wide, so radius 8 is exactly 256 x 256 and the centre is within 8 blocks of the middle
        int minChunkX = ((centerX + 8) >> 4) - r;
        int minChunkZ = ((centerZ + 8) >> 4) - r;
        int span = 2 * r;
        if (last == null || last.minX() != minChunkX << 4 || last.minZ() != minChunkZ << 4 || last.size() != span << 4) {
            dirty = true;
        }
        sample(source, minChunkX, minChunkZ, span);
        if (dirty) {
            last = assemble(minChunkX, minChunkZ, span);
            dirty = false;
        }
        return last;
    }

    private void sample(Source source, int minChunkX, int minChunkZ, int span) {
        long now = clock.getAsLong();
        int budget = CHUNKS_PER_CALL;
        int centre = span / 2;
        long stalest = Long.MAX_VALUE;
        int staleX = 0;
        int staleZ = 0;
        // rings out from the middle, so the part around the player fills in first
        for (int ring = 0; ring <= centre && budget > 0; ring++) {
            for (int dx = -ring; dx <= ring && budget > 0; dx++) {
                for (int dz = -ring; dz <= ring && budget > 0; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    int cx = minChunkX + centre + dx;
                    int cz = minChunkZ + centre + dz;
                    if (cx < minChunkX || cz < minChunkZ || cx >= minChunkX + span || cz >= minChunkZ + span) {
                        continue;
                    }
                    Chunk have = chunks.get(key(cx, cz));
                    if (have != null) {
                        // an unloaded chunk can't be refreshed, so it mustn't hog the one refresh a call gets
                        if (have.sampledAt < stalest && source.hasChunk(cx, cz)) {
                            stalest = have.sampledAt;
                            staleX = cx;
                            staleZ = cz;
                        }
                        continue;
                    }
                    if (!source.hasChunk(cx, cz)) {
                        continue;
                    }
                    chunks.put(key(cx, cz), new Chunk(sampleChunk(source, cx, cz), now));
                    dirty = true;
                    budget--;
                }
            }
        }
        if (budget == CHUNKS_PER_CALL && stalest != Long.MAX_VALUE && now - stalest >= REFRESH_MS) {
            int[] fresh = sampleChunk(source, staleX, staleZ);
            Chunk old = chunks.put(key(staleX, staleZ), new Chunk(fresh, now));
            if (old == null || !Arrays.equals(old.argb, fresh)) {
                dirty = true;
            }
        }
    }

    private Terrain assemble(int minChunkX, int minChunkZ, int span) {
        int size = span << 4;
        int[] argb = new int[size * size];
        int known = 0;
        for (int cz = 0; cz < span; cz++) {
            for (int cx = 0; cx < span; cx++) {
                Chunk chunk = chunks.get(key(minChunkX + cx, minChunkZ + cz));
                if (chunk == null) {
                    continue;
                }
                known++;
                for (int z = 0; z < 16; z++) {
                    System.arraycopy(chunk.argb, z << 4, argb, ((cz << 4) + z) * size + (cx << 4), 16);
                }
            }
        }
        return new Terrain(minChunkX << 4, minChunkZ << 4, size, argb, known, span * span, ++stamp);
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    // 16 x 16 colours, index z * 16 + x
    static int[] sampleChunk(Source source, int chunkX, int chunkZ) {
        int[] out = new int[256];
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                out[(z << 4) | x] = columnColor(source, baseX + x, baseZ + z);
            }
        }
        return out;
    }

    // the vanilla map look: the top block's map colour, a step brighter when the column is higher than the one to its
    // north and a step darker when lower, so hills read without any labels. water gets the vanilla depth dither instead
    static int columnColor(Source source, int x, int z) {
        int minY = source.minY();
        int top = source.surfaceY(x, z);
        int y = top;
        BlockState state = null;
        MapColor color = MapColor.NONE;
        for (int steps = 0; y >= minY && steps < SEE_THROUGH; steps++, y--) {
            state = source.state(x, y, z);
            color = colorOf(source, state, x, y, z);
            if (color != MapColor.NONE) {
                break;
            }
        }
        if (color == MapColor.NONE || state == null) {
            return VOID;
        }
        MapColor.Brightness brightness;
        if (color == MapColor.WATER) {
            brightness = waterShade(source, x, y, z, minY);
        } else {
            brightness = slopeShade(source, x, z, top);
        }
        return 0xFF000000 | color.calculateARGBColor(brightness);
    }

    // a block with no colour of its own that holds a fluid (a waterlogged fence) shows the fluid, like vanilla maps
    private static MapColor colorOf(Source source, BlockState state, int x, int y, int z) {
        MapColor color = source.color(state, x, y, z);
        if (color == MapColor.NONE && !state.getFluidState().isEmpty()) {
            BlockState fluid = state.getFluidState().createLegacyBlock();
            return source.color(fluid, x, y, z);
        }
        return color;
    }

    private static MapColor.Brightness slopeShade(Source source, int x, int z, int top) {
        int northZ = z - 1;
        if (!source.hasChunk(x >> 4, northZ >> 4)) {
            return MapColor.Brightness.NORMAL;
        }
        int north = source.surfaceY(x, northZ);
        if (top > north) {
            return MapColor.Brightness.HIGH;
        }
        return top < north ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
    }

    // same numbers as MapItem: deeper is darker, and the checkerboard keeps the shallows from looking flat
    private static MapColor.Brightness waterShade(Source source, int x, int y, int z, int minY) {
        int depth = 0;
        for (int below = y; below >= minY && depth < 10; below--) {
            if (source.state(x, below, z).getFluidState().isEmpty()) {
                break;
            }
            depth++;
        }
        double shade = depth * 0.1 + ((x + z) & 1) * 0.2;
        if (shade < 0.5) {
            return MapColor.Brightness.HIGH;
        }
        return shade > 0.9 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
    }
}
