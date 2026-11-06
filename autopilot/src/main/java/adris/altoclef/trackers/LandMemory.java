package adris.altoclef.trackers;

import adris.altoclef.AltoClef;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ChunkLoadEvent;
import adris.altoclef.tasks.movement.LandFrontier;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import baritone.process.ExploreProcess;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

// which chunks are land and which are open water, as far as we have seen. the wander asks this before it picks a chunk
// to walk at. the overworld only, anywhere else it stays out of the way
public class LandMemory implements ExploreProcess.LandGate {

    // 9 columns a chunk. it runs on every chunk that loads, and a sea is a sea from any 9 columns
    private static final int[] SAMPLES = {2, 8, 14};
    private static final int SWEEP_CHUNKS = 32;

    private final AltoClef _mod;
    private final LongOpenHashSet _land = new LongOpenHashSet();
    private final LongOpenHashSet _water = new LongOpenHashSet();
    // cached by an earlier session and not loaded in this one: explored, but we never got to look
    private final LongOpenHashSet _old = new LongOpenHashSet();
    private final LongOpenHashSet _banned = new LongOpenHashSet();

    private boolean _hasExtent;
    private int _minX, _maxX, _minZ, _maxZ;
    private long _sumX, _sumZ, _landCount;
    // here and not on the process or the wander: every interrupt cancels the process, and a parent can start a new wander
    private BlockPos _crossingStart;

    public LandMemory(AltoClef mod) {
        _mod = mod;
        EventBus.subscribe(ChunkLoadEvent.class, evt -> onLoad(evt.chunkPos));
    }

    private void onLoad(ChunkPos pos) {
        if (active()) {
            look(pos.x(), pos.z());
        }
    }

    private void look(int x, int z) {
        if (known(x, z)) {
            return;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int water = 0;
        int total = 0;
        for (int ox : SAMPLES) {
            for (int oz : SAMPLES) {
                int wx = (x << 4) + ox;
                int wz = (z << 4) + oz;
                // MOTION_BLOCKING has water in it, the height is the air on top so -1 is the block
                pos.set(wx, _mod.getWorld().getHeight(Heightmap.Types.MOTION_BLOCKING, wx, wz) - 1, wz);
                BlockState top = _mod.getWorld().getBlockState(pos);
                if (top.is(Blocks.SNOW)) {
                    top = _mod.getWorld().getBlockState(pos.move(0, -1, 0));
                }
                if (isWet(top)) {
                    water++;
                }
                total++;
            }
        }
        note(x, z, water, total);
    }

    // the fluid and not Blocks.WATER: kelp and seagrass have the sea in them. ice counts, a frozen ocean is just as empty
    // of animals and takes a lot longer to walk across than it looks
    private static boolean isWet(BlockState top) {
        return top.getFluidState().is(FluidTags.WATER) || top.is(BlockTags.ICE);
    }

    void note(int x, int z, int waterCols, int total) {
        long key = ChunkPos.pack(x, z);
        _old.remove(key);
        if (LandFrontier.isWater(waterCols, total)) {
            _water.add(key);
            return;
        }
        if (_land.add(key)) {
            _sumX += x;
            _sumZ += z;
            _landCount++;
        }
        extend(x, z);
    }

    private void extend(int x, int z) {
        if (!_hasExtent) {
            _minX = _maxX = x;
            _minZ = _maxZ = z;
            _hasExtent = true;
            return;
        }
        _minX = Math.min(_minX, x);
        _maxX = Math.max(_maxX, x);
        _minZ = Math.min(_minZ, z);
        _maxZ = Math.max(_maxZ, z);
    }

    private boolean known(int x, int z) {
        long key = ChunkPos.pack(x, z);
        return _land.contains(key) || _water.contains(key);
    }

    private static boolean anyNbr(LongOpenHashSet set, int x, int z) {
        return set.contains(ChunkPos.pack(x + 1, z)) || set.contains(ChunkPos.pack(x - 1, z))
                || set.contains(ChunkPos.pack(x, z + 1)) || set.contains(ChunkPos.pack(x, z - 1));
    }

    private boolean loaded(int x, int z) {
        LevelChunk chunk = _mod.getWorld().getChunkSource().getChunk(x, z, false);
        return chunk != null && !chunk.isEmpty();
    }

    // whatever is loaded that we somehow never heard about: chunks can land ahead of the world join reset, and one load
    // event gone wrong would read as ocean forever. a wander start is rare enough to afford the look
    public void sweep() {
        if (!active()) {
            return;
        }
        ChunkPos me = ChunkPos.containing(_mod.getPlayer().blockPosition());
        for (int dx = -SWEEP_CHUNKS; dx <= SWEEP_CHUNKS; dx++) {
            for (int dz = -SWEEP_CHUNKS; dz <= SWEEP_CHUNKS; dz++) {
                if (!known(me.x() + dx, me.z() + dz) && loaded(me.x() + dx, me.z() + dz)) {
                    look(me.x() + dx, me.z() + dz);
                }
            }
        }
    }

    public void reset() {
        _land.clear();
        _water.clear();
        _old.clear();
        _banned.clear();
        _hasExtent = false;
        _sumX = _sumZ = _landCount = 0;
        _crossingStart = null;
    }

    @Override
    public boolean active() {
        return AltoClef.inGame() && WorldHelper.getCurrentDimension() == Dimension.OVERWORLD;
    }

    @Override
    public boolean frontier(int x, int z) {
        // the box goes first, a ring scan is mostly chunks nowhere near anything
        if (!_hasExtent || x < _minX - 1 || x > _maxX + 1 || z < _minZ - 1 || z > _maxZ + 1) {
            return false;
        }
        if (known(x, z) || _banned.contains(ChunkPos.pack(x, z))) {
            return false;
        }
        return LandFrontier.frontierOk(anyNbr(_land, x, z), anyNbr(_water, x, z), anyNbr(_old, x, z));
    }

    @Override
    public void explored(int x, int z) {
        if (_mod != null && loaded(x, z)) {
            look(x, z);
            return;
        }
        if (_old.add(ChunkPos.pack(x, z))) {
            extend(x, z);
        }
    }

    // the box and not the real farthest chunk: a little extra scanning beats a set walk, and it can only over-reach
    @Override
    public int reach(int originX, int originZ) {
        if (!_hasExtent) {
            return -1;
        }
        return Math.max(Math.abs(_minX - originX), Math.abs(_maxX - originX))
                + Math.max(Math.abs(_minZ - originZ), Math.abs(_maxZ - originZ));
    }

    public void ban(ChunkPos chunk) {
        _banned.add(ChunkPos.pack(chunk.x(), chunk.z()));
    }

    public boolean crossing() {
        return _crossingStart != null;
    }

    public BlockPos crossingStart() {
        return _crossingStart;
    }

    public void endCrossing() {
        _crossingStart = null;
    }

    // aiming again mid crossing keeps the start, that's the shore we are measuring from
    @Override
    public BlockPos startCrossing(int x, int z) {
        if (_crossingStart == null) {
            _crossingStart = new BlockPos(x, 0, z);
        }
        double centroidX = _landCount == 0 ? x : _sumX / (double) _landCount * 16 + 8;
        double centroidZ = _landCount == 0 ? z : _sumZ / (double) _landCount * 16 + 8;
        LandFrontier.Spot target = LandFrontier.crossingTarget(x, z, centroidX, centroidZ);
        return new BlockPos(target.x(), 0, target.z());
    }
}
