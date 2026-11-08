package adris.altoclef.trackers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ChunkLoadEvent;
import adris.altoclef.eventbus.events.ChunkUnloadEvent;
import adris.altoclef.util.helpers.WorldHelper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Keeps track of currently loaded chunks. That's it.
 */
public class SimpleChunkTracker {

    private final AltoClef _mod;
    private final Set<ChunkPos> _loaded = new HashSet<>();

    public SimpleChunkTracker(AltoClef mod) {
        _mod = mod;

        // When chunks load...
        EventBus.subscribe(ChunkLoadEvent.class, evt -> onLoad(evt.chunkPos));
        EventBus.subscribe(ChunkUnloadEvent.class, evt -> onUnload(evt.chunkPos));
    }

    private void onLoad(ChunkPos pos) {
        //Debug.logInternal("LOADED: " + pos);
        _loaded.add(pos);
    }

    private void onUnload(ChunkPos pos) {
        //Debug.logInternal("unloaded: " + pos);
        _loaded.remove(pos);
    }

    public boolean isChunkLoaded(ChunkPos pos) {
        return !(_mod.getWorld().getChunk(pos.x(), pos.z()) instanceof EmptyLevelChunk);
    }

    public boolean isChunkLoaded(BlockPos pos) {
        return isChunkLoaded(ChunkPos.containing(pos));
    }

    public List<ChunkPos> getLoadedChunks() {
        List<ChunkPos> result = new ArrayList<>(_loaded);
        // Only show LOADED chunks.
        result = result.stream()
                .filter(this::isChunkLoaded)
                .distinct()
                .collect(Collectors.toList());
        return result;
    }

    // the fast one. asks each 16x16x16 section's palette "could you even contain this?" first, and almost every
    // section in a chunk that lacks the block says no without us touching a single block. the old position loop
    // did ~90k getBlockStates plus a BlockPos each per chunk, on the client thread. get rekt
    // returns the first match or null. the BlockPos is only built for the actual hit
    public BlockPos findInChunk(ChunkPos chunk, Predicate<BlockState> state) {
        LevelChunk levelChunk = _mod.getWorld().getChunk(chunk.x(), chunk.z());
        // not loaded hands back an EmptyLevelChunk, which is a LevelChunk, so it has to be asked about by name
        if (levelChunk instanceof EmptyLevelChunk) {
            return null;
        }
        return findInSections(levelChunk.getSections(), levelChunk.getMinY(), chunk.getMinBlockX(), chunk.getMinBlockZ(), state);
    }

    public boolean chunkHas(ChunkPos chunk, Predicate<BlockState> state) {
        return findInChunk(chunk, state) != null;
    }

    // a server's anti-xray (Paper's engine-mode 2, as Tweakcraft runs it) sends every block hidden from view as a random
    // one of its hidden list, nether bricks and polished blackstone bricks among them, so a palette hit says nothing about
    // what is there. a block counts here only with an open face (air or a fluid next to it), which such a server always
    // sends as it is: the no x-ray rule, for bricks as for ores. faces across the chunk's sides are not looked at
    public boolean chunkHasExposed(ChunkPos chunk, Predicate<BlockState> state) {
        LevelChunk levelChunk = _mod.getWorld().getChunk(chunk.x(), chunk.z());
        if (levelChunk instanceof EmptyLevelChunk) {
            return false;
        }
        return findExposedInSections(levelChunk.getSections(), levelChunk.getMinY(), chunk.getMinBlockX(), chunk.getMinBlockZ(), state) != null;
    }

    // findInSections, a hit only with an open face inside the chunk (see chunkHasExposed)
    public static BlockPos findExposedInSections(LevelChunkSection[] sections, int minY, int minX, int minZ, Predicate<BlockState> state) {
        for (int i = 0; i < sections.length; ++i) {
            LevelChunkSection section = sections[i];
            if (section == null || !section.maybeHas(state)) {
                continue;
            }
            for (int y = 0; y < 16; ++y) {
                for (int z = 0; z < 16; ++z) {
                    for (int x = 0; x < 16; ++x) {
                        if (state.test(section.getBlockState(x, y, z)) && openFace(sections, i, x, y, z)) {
                            return new BlockPos(minX + x, minY + i * 16 + y, minZ + z);
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean openFace(LevelChunkSection[] sections, int i, int x, int y, int z) {
        return open(sections, i, x - 1, y, z) || open(sections, i, x + 1, y, z) || open(sections, i, x, y, z - 1)
                || open(sections, i, x, y, z + 1) || open(sections, i, x, y - 1, z) || open(sections, i, x, y + 1, z);
    }

    // air or a fluid at local x y z of section i (y may step into the section below or above); outside the chunk's sides
    // or its height, no. a missing section is all air
    private static boolean open(LevelChunkSection[] sections, int i, int x, int y, int z) {
        if (x < 0 || x > 15 || z < 0 || z > 15) {
            return false;
        }
        if (y < 0) {
            i--;
            y += 16;
        } else if (y > 15) {
            i++;
            y -= 16;
        }
        if (i < 0 || i >= sections.length) {
            return false;
        }
        LevelChunkSection section = sections[i];
        if (section == null) {
            return true;
        }
        BlockState s = section.getBlockState(x, y, z);
        return s.isAir() || !s.getFluidState().isEmpty();
    }

    // split out from findInChunk so a test can feed it hand built sections. sections[i] covers world y
    // minY + i * 16, NOT i * 16, which is the bug waiting to happen in the overworld where minY is -64
    public static BlockPos findInSections(LevelChunkSection[] sections, int minY, int minX, int minZ, Predicate<BlockState> state) {
        for (int i = 0; i < sections.length; ++i) {
            LevelChunkSection section = sections[i];
            // no hasOnlyAir shortcut: the predicate is allowed to want air, and a single value palette is O(1) anyway.
            // maybeHas only ever lies in the "yes" direction (stale palette entries), so skipping on "no" is safe
            if (section == null || !section.maybeHas(state)) {
                continue;
            }
            for (int y = 0; y < 16; ++y) {
                for (int z = 0; z < 16; ++z) {
                    for (int x = 0; x < 16; ++x) {
                        if (state.test(section.getBlockState(x, y, z))) {
                            return new BlockPos(minX + x, minY + i * 16 + y, minZ + z);
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Loops through every block in a chunk if it is loaded.
     * If the chunk isn't loaded, it doesn't scan anything.
     * Slow, prefer {@link #findInChunk} when all you care about is the block state.
     *
     * @param chunk       The chunk pos to scan
     * @param onBlockStop Run for every block until it returns true, where it stops scanning. The BlockPos is reused
     *                    between calls, so copy it with immutable() if you keep it.
     * @return whether `onBlockStop` returned true at any point.
     */
    public boolean scanChunk(ChunkPos chunk, Predicate<BlockPos> onBlockStop) {
        if (!isChunkLoaded(chunk)) return false;
        //Debug.logInternal("SCANNED CHUNK " + chunk.toString());
        // one mutable pos for the whole chunk instead of 90k new ones
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int xx = chunk.getMinBlockX(); xx <= chunk.getMaxBlockX(); ++xx) {
            for (int yy = WorldHelper.WORLD_FLOOR_Y; yy <= WorldHelper.WORLD_CEILING_Y; ++yy) {
                for (int zz = chunk.getMinBlockZ(); zz <= chunk.getMaxBlockZ(); ++zz) {
                    if (onBlockStop.test(pos.set(xx, yy, zz))) return true;
                }
            }
        }
        return false;
    }

    public void scanChunk(ChunkPos chunk, Consumer<BlockPos> onBlock) {
        scanChunk(chunk, (block) -> {
            // consumers love to keep what they're handed
            onBlock.accept(block.immutable());
            return false;
        });
    }

    public void reset(AltoClef mod) {
        Debug.logInternal("CHUNKS RESET");
        _loaded.clear();
    }
}
