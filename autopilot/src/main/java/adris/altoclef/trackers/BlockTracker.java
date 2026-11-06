package adris.altoclef.trackers;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.BlockPlaceEvent;
import adris.altoclef.eventbus.events.ChunkLoadEvent;
import adris.altoclef.eventbus.events.ChunkUnloadEvent;
import baritone.api.utils.Dimension;
import adris.altoclef.util.helpers.BaritoneHelper;
import adris.altoclef.util.helpers.ConfigHelper;
import adris.altoclef.util.helpers.FluidSources;
import adris.altoclef.util.helpers.PlacedByUs;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.Baritone;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.cache.FasterWorldScanner;
import baritone.pathing.movement.CalculationContext;
import baritone.process.MineProcess;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Tracks blocks the way we want it, when we want it.
 * <p>
 * Gives you a "Check and don't care" interface where you can check for blocks and their locations over and over again
 * without scanning the world over and over again.
 * <p>
 * Banned blocks (see Bans) are never offered
 */
public class BlockTracker extends Tracker {

    // This should be moved to an instance variable
    // but if set to true, block scanning will happen
    // asynchronously to spread out the expensive cost of scanning.
    private static final boolean ASYNC_SCANNING = true;
    // seconds. the first scan after a task tracks something new is the one it will wander off without, so it can't wait 7
    private static final double FIRST_LOOK_GAP = 0.5;
    // same reach as MineProcess.searchWorld's scan
    public static final int FLUID_SCAN_CHUNK_RADIUS = 32;
    private static BlockTrackerConfig _config = new BlockTrackerConfig();

    static {
        ConfigHelper.loadConfig("configs/block_tracker.json", BlockTrackerConfig::new, BlockTrackerConfig.class, newConfig -> _config = newConfig);
    }

    private final HashMap<Dimension, PosCache> _caches = new HashMap<>();

    //private final PosCache _cache = new PosCache(100, 64*1.5);

    private final TimerGame _timer = new TimerGame(_config.scanInterval);

    // a block type nobody has scanned for yet doesn't wait for the lazy timer above, but this keeps a task that
    // tracks and untracks a new type every few ticks from turning that into a scan every few ticks
    private final TimerGame _firstLookGap = new TimerGame(FIRST_LOOK_GAP);

    // A scan can last no more than 15 seconds
    private final TimerGame _asyncForceResetScanFlag = new TimerGame(15);

    private final Map<Block, Integer> _trackingBlocks = new HashMap<>();

    private final Object _scanMutex = new Object();
    // Only perform scans at the END of our frame
    private final Semaphore _endOfFrameMutex = new Semaphore(1);
    //private Block _currentlyTracking = null;
    private final AltoClef _mod;
    // the scan thread flips it and the main thread reads it
    private volatile boolean _scanning = false;

    public BlockTracker(AltoClef mod, TrackerManager manager) {
        super(manager);
        _mod = mod;
        // First time, track immediately
        _timer.forceElapse();
        _firstLookGap.forceElapse();

        // Listen for block placement
        EventBus.subscribe(BlockPlaceEvent.class, evt -> {
            addBlock(evt.blockState.getBlock(), evt.blockPos);
            rememberOurPlacement(evt.blockPos);
        });
        // "until the chunk reloads" bans (the n-failures rule) end here. both halves, a load on its own is also what a furnace
        // going lit looks like (Bans.chunkLoaded)
        EventBus.subscribe(ChunkUnloadEvent.class, evt -> {
            if (Minecraft.getInstance().level != null) {
                _mod.getBans().chunkUnloaded(evt.chunkPos.x(), evt.chunkPos.z());
            }
        });
        EventBus.subscribe(ChunkLoadEvent.class, evt -> {
            if (Minecraft.getInstance().level != null) {
                _mod.getBans().chunkLoaded(WorldHelper.getCurrentDimension(), evt.chunkPos.x(), evt.chunkPos.z());
            }
        });
    }

    // the hook publishes every conducting block that shows up on the client level, so "within placing reach" is our only
    // evidence it was us. the gather skips these (see PlacedByUs) so it stops eating the scaffold it just built
    private static void rememberOurPlacement(BlockPos pos) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }
        if (PlacedByUs.withinReach(client.player.getX(), client.player.getEyeY(), client.player.getZ(), pos.getX(), pos.getY(), pos.getZ())) {
            PlacedByUs.GLOBAL.record(pos.getX(), pos.getY(), pos.getZ(), client.level.getGameTime());
        }
    }

    @Override
    protected void updateState() {
        if (shouldUpdate()) {
            update();
        }
    }

    // We want our `_trackingBlocks` value to be read AFTER tasks have finished ticking
    public void preTickTask() {
        try {
            _endOfFrameMutex.acquire();
        } catch (InterruptedException e) {
            Debug.logWarning("Pre-tick failed to acquire block track mutex! (send logs)");
            e.printStackTrace();
        }
    }

    public void postTickTask() {
        _endOfFrameMutex.release();
    }

    @Override
    protected void reset() {
        // Tasks will handle de-tracking blocks.
        if (!_caches.values().isEmpty()) {
            for (PosCache cache : _caches.values()) {
                cache.clear();
            }
        }
    }

    public boolean isTracking(Block block) {
        synchronized (_trackingBlocks) {
            return _trackingBlocks.containsKey(block) && _trackingBlocks.get(block) > 0;
        }
    }

    /**
     * Starts tracking/pay attention to some blocks.
     * <b>IMPORTANT:</b> ALWAYS pair with {@link #stopTracking(Block...) stopTracking}! Otherwise this block type will be
     * tracked forever (not the end of the world, but other block types will be lost.
     */
    public void trackBlock(Block... blocks) {
        synchronized (_trackingBlocks) {
            for (Block block : blocks) {
                if (!_trackingBlocks.containsKey(block)) {
                    // We're tracking a new block, so we're not updated.
                    // no rescan is forced from here anymore: updateState sees the block isn't covered by any scan and
                    // goes right away. (this used to wait on a 2 second timer, which is how a bot with logs 5 blocks
                    // away decided to go exploring)
                    setDirty();
                    _trackingBlocks.put(block, 0);
                }
                _trackingBlocks.put(block, _trackingBlocks.get(block) + 1);
            }
        }
    }

    /**
     * Stops tracking some blocks, after calling {@link #trackBlock(Block...) trackBlock}.
     * <p>
     * Only call this once for every {@link #trackBlock(Block...) trackBlock}.
     */
    public void stopTracking(Block... blocks) {
        List<Block> dropped = null;
        synchronized (_trackingBlocks) {
            for (Block block : blocks) {
                if (_trackingBlocks.containsKey(block)) {
                    int current = _trackingBlocks.get(block);
                    if (current == 0) {
                        Debug.logWarning("Untracked block " + block + " more times than necessary. BlockTracker stack is unreliable from this point on.");
                    } else {
                        _trackingBlocks.put(block, current - 1);
                        if (_trackingBlocks.get(block) <= 0) {
                            _trackingBlocks.remove(block);
                            if (dropped == null) dropped = new ArrayList<>();
                            dropped.add(block);
                        }
                    }
                }
            }
        }
        if (dropped != null) {
            // once nobody tracks it the cache for it rots, so the next trackBlock is a first look all over again.
            // after the _trackingBlocks lock is gone: the scan merge takes these two in the other order
            synchronized (_scanMutex) {
                for (PosCache cache : _caches.values()) {
                    for (Block block : dropped) {
                        cache.coverage.forget(block);
                    }
                }
            }
        }
    }

    /**
     * Whether a finished scan has looked for every one of these blocks since they were tracked. An empty answer from
     * the tracker only means "there are none" once this is true; before that it means "haven't looked yet".
     */
    public boolean hasBeenScanned(Block... blocks) {
        synchronized (_scanMutex) {
            return currentCache().coverage.coversAll(blocks);
        }
    }

    /**
     * True if any of these blocks is tracked but no scan has covered it yet, so one is on its way.
     */
    public boolean scanPending(Block... blocks) {
        synchronized (_scanMutex) {
            ScanCoverage<Block> coverage = currentCache().coverage;
            synchronized (_trackingBlocks) {
                for (Block block : blocks) {
                    if (_trackingBlocks.containsKey(block) && !coverage.covers(block)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /**
     * Manually add a block at a position.
     */
    /** An ore block: the ones an anti-xray plugin fakes. */
    static boolean isOre(Block block) {
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).getPath();
        return id.endsWith("_ore") || id.equals("ancient_debris");
    }

    /** A face of this block touches air or a fluid, so what the client has here is what the server has. */
    static boolean exposed(ClientLevel level, BlockPos pos) {
        if (level == null) return true;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            BlockState n = level.getBlockState(pos.relative(d));
            if (n.isAir() || !n.getFluidState().isEmpty()) return true;
        }
        return false;
    }

    public void addBlock(Block block, BlockPos pos) {
        if (blockIsValid(pos, block)) {
            synchronized (_scanMutex) {
                currentCache().addBlock(block, pos);
            }
        } else {
            Debug.logInternal("INVALID SET: " + block + " " + pos);
        }
    }

    public boolean anyFound(Block... blocks) {
        updateState();
        synchronized (_scanMutex) {
            return currentCache().anyFound(blocks);
        }
    }

    /**
     * Checks whether any blocks of a type have been found.
     *
     * @param isValidTest A filter predicate, returns true if a block at a position should be included.
     * @param blocks      The blocks to check for
     */
    public boolean anyFound(Predicate<BlockPos> isValidTest, Block... blocks) {
        updateState();
        synchronized (_scanMutex) {
            return currentCache().anyFound(isValidTest, blocks);
        }
    }

    public Optional<BlockPos> getNearestTracking(Block... blocks) {
        // Add juuust a little, to prevent digging down all the time/bias towards blocks BELOW the player
        return getNearestTracking(_mod.getPlayer().position().add(0, 0.6f, 0), blocks);
    }

    public Optional<BlockPos> getNearestTracking(Vec3 pos, Block... blocks) {
        return getNearestTracking(pos, p -> true, blocks);
    }

    public Optional<BlockPos> getNearestTracking(Predicate<BlockPos> isValidTest, Block... blocks) {
        return getNearestTracking(_mod.getPlayer().position().add(0, 0.6f, 0), isValidTest, blocks);
    }

    /**
     * Gets the nearest tracked block.
     *
     * @param pos         From what position? (defaults to the player's position)
     * @param isValidTest Filter predicate
     * @param blocks      The blocks to check for
     * @return Optional.of(block position) if found, otherwise Optional.empty
     */
    public Optional<BlockPos> getNearestTracking(Vec3 pos, Predicate<BlockPos> isValidTest, Block... blocks) {
        synchronized (_trackingBlocks) {
            for (Block block : blocks) {
                if (!_trackingBlocks.containsKey(block)) {
                    Debug.logWarning("BlockTracker: Not tracking block " + block + " right now.");
                    return Optional.empty();
                }
            }
        }
        // Make sure we've scanned the first time if we need to.
        updateState();
        synchronized (_scanMutex) {
            return currentCache().getNearest(_mod, pos, isValidTest, blocks);
        }
    }

    // how a caller ranks the tracked blocks when plain distance is the wrong question (MineAndCollectTask on stone: the
    // default heuristic is baritone's, which thinks down is cheap, which is how we dug shafts). lowest score wins,
    // infinite never does. positions are the block's center
    public interface Scorer {
        double score(double fromX, double fromY, double fromZ, double toX, double toY, double toZ);
    }

    // getNearestTracking with the caller's idea of near
    public Optional<BlockPos> getNearestTracking(Vec3 pos, Predicate<BlockPos> isValidTest, Scorer scorer, Block... blocks) {
        synchronized (_trackingBlocks) {
            for (Block block : blocks) {
                if (!_trackingBlocks.containsKey(block)) {
                    Debug.logWarning("BlockTracker: Not tracking block " + block + " right now.");
                    return Optional.empty();
                }
            }
        }
        updateState();
        synchronized (_scanMutex) {
            return currentCache().getNearest(pos, isValidTest, p -> blockIsValid(p, blocks), scorer::score, blocks);
        }
    }

    /**
     * Returns the locations of all tracked blocks of a given type
     */
    public List<BlockPos> getKnownLocations(Block... blocks) {
        updateState();
        synchronized (_scanMutex) {
            return currentCache().getKnownLocations(blocks);
        }
    }

    // what the last scan saw at pos, null when nothing tracked was there. no updateState: this gets asked from inside
    // getNearestTracking's own filter, a rescan halfway through that walk is not what anybody wants
    public Block knownBlockAt(BlockPos pos) {
        synchronized (_scanMutex) {
            return currentCache().knownBlockAt(pos);
        }
    }

    public Optional<BlockPos> getNearestWithinRange(BlockPos pos, double range, Block... blocks) {
        return getNearestWithinRange(new Vec3(pos.getX(), pos.getY(), pos.getZ()), range, blocks);
    }

    /**
     * Scans a radius for the closest block of a given type .
     *
     * @param pos    The center of this radius
     * @param range  Radius to scan for
     * @param blocks What blocks to check for
     */
    public Optional<BlockPos> getNearestWithinRange(Vec3 pos, double range, Block... blocks) {
        int minX = (int) Math.floor(pos.x - range),
                maxX = (int) Math.floor(pos.x + range),
                minY = (int) Math.floor(pos.y - range),
                maxY = (int) Math.floor(pos.y + range),
                minZ = (int) Math.floor(pos.z - range),
                maxZ = (int) Math.floor(pos.z + range);
        double closestDistance = Float.POSITIVE_INFINITY;
        BlockPos nearest = null;
        ClientLevel level = Minecraft.getInstance().level;
        assert level != null;
        // one pos for the whole cube. this used to be a new BlockPos and a mutex per cell, and a radius of 10 is 9261 cells
        BlockPos.MutableBlockPos check = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; ++x) {
            for (int y = minY; y <= maxY; ++y) {
                for (int z = minZ; z <= maxZ; ++z) {
                    check.set(x, y, z);
                    Block b = level.getBlockState(check).getBlock();
                    boolean valid = false;
                    for (Block type : blocks) {
                        if (type == b) {
                            valid = true;
                            break;
                        }
                    }
                    if (!valid) continue;
                    // nearly every cell is air, so the lock only gets taken for the handful that are actually the thing
                    synchronized (_scanMutex) {
                        if (currentCache().blockUnreachable(check)) continue;
                    }
                    if (check.closerToCenterThan(pos, range)) {
                        double sq = check.distToCenterSqr(pos);
                        if (sq < closestDistance) {
                            closestDistance = sq;
                            nearest = check.immutable();
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(nearest);
    }

    private boolean shouldUpdate() {
        if (_timer.elapsed()) return true;
        // something tracked that no scan has covered. a scan already running was snapshotted before it was tracked,
        // so wait it out (when it ends this is still true and the next one goes)
        return !_scanning && _firstLookGap.elapsed() && needsFirstLook();
    }

    // a couple of hash lookups under the mutex every query matters less than a bot wandering off from a tree
    private boolean needsFirstLook() {
        synchronized (_scanMutex) {
            ScanCoverage<Block> coverage = currentCache().coverage;
            synchronized (_trackingBlocks) {
                return coverage.anyUncovered(_trackingBlocks.keySet());
            }
        }
    }

    private void update() {
        // Perform a baritone scan
        _timer.reset();
        _timer.setInterval(_config.scanInterval);
        Dimension scanDimension = WorldHelper.getCurrentDimension();
        if (_config.scanAsynchronously) {
            if (_scanning && _asyncForceResetScanFlag.elapsed()) {
                Debug.logMessage("SCANNING TOOK TOO LONG! Will assume it ended mid way. Hopefully this won't break anything...");
                _scanning = false;
            }
            if (!_scanning) {
                // the thread safe context copies the whole client chunk cache, so only make one if a scan is really going
                // to use it. it used to be built first and thrown away whenever the last scan was still running
                CalculationContext ctx = new CalculationContext(_mod.getClientBaritone(), true);
                // claimed here and not in the task so a second update before the thread wakes up can't start a second scan
                _scanning = true;
                _asyncForceResetScanFlag.reset();
                _firstLookGap.reset();
                Baritone.getExecutor().execute(() -> {
                    try {
                        rescanWorld(ctx, true, scanDimension);
                    } finally {
                        _scanning = false;
                    }
                });
            }
        } else {
            // Synchronous scanning.
            _firstLookGap.reset();
            rescanWorld(new CalculationContext(_mod.getClientBaritone(), false), false, scanDimension);
        }
    }

    private static boolean isScannedAsFluid(Block block) {
        return block == Blocks.WATER || block == Blocks.LAVA;
    }

    private void rescanWorld(CalculationContext ctx, boolean async, Dimension scanDimension) {
        Block[] blocksToScan;
        if (async) {
            // Wait for end of frame
            try {
                _endOfFrameMutex.acquire();
                _endOfFrameMutex.release();
            } catch (InterruptedException e) {
                Debug.logWarning("RESCAN INTERRUPTED! Will SKIP the scan (see logs)");
                _endOfFrameMutex.release();
                e.printStackTrace();
                return;
            }
        }
        synchronized (_trackingBlocks) {
            Debug.logInternal("Rescanning world for " + _trackingBlocks.size() + " blocks... Hopefully not dummy slow.");
            blocksToScan = new Block[_trackingBlocks.size()];
            _trackingBlocks.keySet().toArray(blocksToScan);
        }

        List<BlockPos> knownBlocks;
        synchronized (_scanMutex) {
            knownBlocks = currentCache().getKnownLocations(blocksToScan);
        }

        // Clear invalid block pos before rescan
        if (!knownBlocks.isEmpty()) {
            for (BlockPos check : knownBlocks) {
                if (!blockIsValid(check, blocksToScan)) {
                    //Debug.logInternal("Removed at " + check);
                    synchronized (_scanMutex) {
                        currentCache().removeBlock(check, blocksToScan);
                    }
                }
            }
        }

        // The scanning may run asynchronously.
        if (Minecraft.getInstance().level == null) {
            return;
        }
        // one scan has one budget for everything it's looking for, so each type gets its own share. see PerTypeScan
        Function<BlockPos, Block> blockAt = pos -> {
            ClientLevel level = Minecraft.getInstance().level;
            return level == null ? null : level.getBlockState(pos).getBlock();
        };
        List<Block> fluids = new ArrayList<>();
        List<Block> logs = new ArrayList<>();
        List<Block> solids = new ArrayList<>();
        for (Block block : blocksToScan) {
            (isScannedAsFluid(block) ? fluids : BlockTrackerConfig.isLog(block) ? logs : solids).add(block);
        }
        PerTypeScan.Scan worldScan = (types, max) -> {
            int[] raw = {0};
            List<BlockPos> hits = MineProcess.searchWorld(ctx, new BlockOptionalMetaLookup(types), max, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), n -> raw[0] = n);
            return new PerTypeScan.Scanned(hits, raw[0]);
        };
        Map<Block, List<BlockPos>> found = PerTypeScan.run(solids.toArray(new Block[0]), _config.maxCacheSizePerBlockType, worldScan, blockAt);
        // their own scan for their own share, same split as the fluids below
        found.putAll(PerTypeScan.run(logs.toArray(new Block[0]), _config.maxCacheSizePerLogType, worldScan, blockAt));
        if (!fluids.isEmpty()) {
            // water and lava are mostly flowing or buried, and the plain scan spends its whole budget on those before it
            // ever reaches a surface you could bucket (a lake 10 blocks away came back as "no water"). the filter runs
            // at the hit, so the junk never counts
            found.putAll(PerTypeScan.run(
                    fluids.toArray(new Block[0]),
                    _config.maxCacheSizePerBlockType,
                    (types, max) -> PerTypeScan.Scanned.of(FasterWorldScanner.INSTANCE.scanNearest(
                            ctx.getBaritone().getPlayerContext(),
                            new BlockOptionalMetaLookup(types),
                            max,
                            FLUID_SCAN_CHUNK_RADIUS,
                            (chunk, x, y, z) -> FluidSources.exposedSource(chunk.getFluidState(x, y, z), chunk.getFluidState(x, y + 1, z)))),
                    blockAt
            ));
        }

        synchronized (_scanMutex) {
            if (Minecraft.getInstance().level != null) {
                // hits were found in scanDimension, so they don't go into whatever cache is current now
                boolean sameDimension = WorldHelper.getCurrentDimension() == scanDimension;
                for (Map.Entry<Block, List<BlockPos>> entry : found.entrySet()) {
                    if (!sameDimension) break;
                    synchronized (_trackingBlocks) {
                        if (!_trackingBlocks.containsKey(entry.getKey())) continue;
                    }
                    boolean legitOre = Baritone.settings().legitMine.value && isOre(entry.getKey());
                    for (BlockPos pos : entry.getValue()) {
                        // with legitMine on, an ore counts only where it touches air or a fluid: an anti-xray plugin (Paper's
                        // engine mode 2) sends fake ores in every hidden block, and the real ones once something opens them up
                        if (legitOre && !exposed(Minecraft.getInstance().level, pos)) continue;
                        currentCache().addBlock(entry.getKey(), pos);
                    }
                }

                // Purge if we have too many blocks tracked at once.
                currentCache().smartPurge(_mod, _mod.getPlayer().position());

                // what we looked for counts as looked at, found or not. skipped if we changed dimension on the way,
                // because then it's the other dimension's cache that would get told it was scanned
                if (WorldHelper.getCurrentDimension() == scanDimension) {
                    List<Block> stillTracked = new ArrayList<>(blocksToScan.length);
                    synchronized (_trackingBlocks) {
                        for (Block block : blocksToScan) {
                            if (_trackingBlocks.containsKey(block)) stillTracked.add(block);
                        }
                    }
                    currentCache().coverage.scanned(stillTracked);
                }
            }
        }
    }

    // Checks whether it would be WRONG to say "at pos the block is block"
    // Returns true if wrong, false if correct OR undetermined/unsure.
    public boolean blockIsValid(BlockPos pos, Block... blocks) {
        synchronized (_scanMutex) {
            // We can't reach it, don't even try.
            if (currentCache().blockUnreachable(pos)) {
                return false;
            }
        }
        // It might be OK to remove this. Will have to test.
        if (!_mod.getChunkTracker().isChunkLoaded(pos)) {
            //Debug.logInternal("(failed chunkcheck: " + new ChunkPos(pos) + ")");
            //Debug.logStack();
            return true;
        }
        // I'm bored
        ClientLevel zaWarudo = Minecraft.getInstance().level;
        // No world, therefore we don't assume block is invalid.
        if (zaWarudo == null) {
            return true;
        }
        try {
            for (Block block : blocks) {
                if (zaWarudo.isEmptyBlock(pos) && WorldHelper.isAir(block)) {
                    return true;
                }
                BlockState state = zaWarudo.getBlockState(pos);
                if (state.getBlock() == block) {
                    return true;
                }
            }
            return false;
        } catch (NullPointerException e) {
            // Probably out of chunk. This means we can't judge its state.
            return true;
        }
    }

    /**
     * @param pos BlockPos to check for
     * @return Whether that block is considered unreachable
     */
    public boolean unreachable(BlockPos pos) {
        synchronized (_scanMutex) {
            return currentCache().blockUnreachable(pos);
        }
    }

    /**
     * Inform the block tracker that the bot was NOT able to reach a block.
     *
     * @param pos             block that we were unable to reach
     * @param allowedFailures how many times we can try reaching before we finally declare this block "unreachable"
     */
    public void requestBlockUnreachable(BlockPos pos, int allowedFailures) {
        requestBlockUnreachable(pos, allowedFailures, "couldn't reach it");
    }

    // the n-failures rule lives in the ban book now (BanPolicy.blockStrike), the reason is what its log line says
    public void requestBlockUnreachable(BlockPos pos, int allowedFailures, String reason) {
        double distSq = WorldHelper.toVec3d(pos).distanceToSqr(_mod.getPlayer().position());
        BanPolicy.blockStrike(_mod.getBans(), WorldHelper.getCurrentDimension(), pos.getX(), pos.getY(), pos.getZ(), allowedFailures, distSq, reason);
    }

    public void requestBlockUnreachable(BlockPos pos) {
        requestBlockUnreachable(pos, _config.defaultUnreachableAttemptsAllowed);
    }

    private PosCache currentCache() {
        Dimension dimension = WorldHelper.getCurrentDimension();
        if (!_caches.containsKey(dimension)) {
            _caches.put(dimension, new PosCache(_mod.getBans(), dimension));
        }
        return _caches.get(dimension);
    }


    static class PosCache {
        private final HashMap<Block, List<BlockPos>> _cachedBlocks = new HashMap<>();

        private final HashMap<BlockPos, Block> _cachedByPosition = new HashMap<>();

        // the ban book and which dimension of it we are. the cache used to keep its own blacklist, now everybody's bans count
        private final Bans _bans;
        private final Dimension _dimension;

        final ScanCoverage<Block> coverage = new ScanCoverage<>();

        PosCache(Bans bans, Dimension dimension) {
            _bans = bans;
            _dimension = dimension;
        }

        // for tests: an empty book of its own
        PosCache() {
            this(new Bans(line -> {
            }), Dimension.OVERWORLD);
        }

        public boolean anyFound(Block... blocks) {
            for (Block block : blocks) {
                if (_cachedBlocks.containsKey(block)) return true;
            }
            return false;
        }

        public boolean anyFound(Predicate<BlockPos> isValidTest, Block... blocks) {
            for (Block block : blocks) {
                if (_cachedBlocks.containsKey(block)) {
                    for (BlockPos pos : _cachedBlocks.get(block)) {
                        if (isValidTest.test(pos)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        public List<BlockPos> getKnownLocations(Block... blocks) {
            List<BlockPos> result = new ArrayList<>();
            for (Block block : blocks) {
                List<BlockPos> found = _cachedBlocks.get(block);
                if (found != null) {
                    result.addAll(found);
                }
            }
            return result;
        }

        public Block knownBlockAt(BlockPos pos) {
            return _cachedByPosition.get(pos);
        }

        public void removeBlock(BlockPos pos, Block... blocks) {
            for (Block block : blocks) {
                if (_cachedBlocks.containsKey(block)) {
                    _cachedBlocks.get(block).remove(pos);
                    _cachedByPosition.remove(pos);
                    if (_cachedBlocks.get(block).size() == 0) {
                        _cachedBlocks.remove(block);
                    }
                }
            }
        }

        public void addBlock(Block block, BlockPos pos) {
            if (blockUnreachable(pos)) return;
            if (_cachedByPosition.containsKey(pos)) {
                if (_cachedByPosition.get(pos) == block) {
                    // We're already tracked
                    return;
                } else {
                    // We're tracked incorrectly, fix
                    removeBlock(pos, _cachedByPosition.get(pos));
                }
            }
            if (!anyFound(block)) {
                _cachedBlocks.put(block, new ArrayList<>());
            }
            _cachedBlocks.get(block).add(pos);
            _cachedByPosition.put(pos, block);
        }


        public void clear() {
            Debug.logInternal("CLEARED BLOCK CACHE");
            _cachedBlocks.clear();
            _cachedByPosition.clear();
            coverage.clear();
        }

        public int getBlockTrackCount() {
            int count = 0;
            if (!_cachedBlocks.values().isEmpty()) {
                for (List<BlockPos> list : _cachedBlocks.values()) {
                    count += list.size();
                }
            }
            return count;
        }

        public boolean blockUnreachable(BlockPos pos) {
            return _bans.blockBanned(_dimension, pos.getX(), pos.getY(), pos.getZ());
        }

        // Gets nearest block. For now does linear search. In the future might optimize this a bit
        public Optional<BlockPos> getNearest(AltoClef mod, Vec3 position, Predicate<BlockPos> isValid, Block... blocks) {
            return getNearest(position, isValid, pos -> mod.getBlockTracker().blockIsValid(pos, blocks), BaritoneHelper::calculateGenericHeuristic, blocks);
        }

        interface Scorer {
            double score(double fromX, double fromY, double fromZ, double toX, double toY, double toZ);
        }

        // blockIsValid and the scorer are parameters so a test can drive this without a client (the heuristic wants
        // baritone's settings and those want a whole game). the real validity check takes a mutex, allocs a ChunkPos
        // and reads the world, so it only gets asked about entries that are actually in the running
        Optional<BlockPos> getNearest(Vec3 position, Predicate<BlockPos> isValid, Predicate<BlockPos> blockIsValid, Scorer scorer, Block... blocks) {
            if (!anyFound(blocks)) {
                //Debug.logInternal("(failed cataloguecheck for " + block.getTranslationKey() + ")");
                return Optional.empty();
            }

            List<BlockPos> blockList = getKnownLocations(blocks);
            int n = blockList.size();

            // scores first, with raw doubles. this used to alloc a Vec3 per position per tick and then ask the world
            // and the predicate about every single one even though only the nearest valid one matters
            double[] scores = new double[n];
            for (int i = 0; i < n; i++) {
                BlockPos pos = blockList.get(i);
                scores[i] = scorer.score(position.x, position.y, position.z, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            }

            // then hand out candidates nearest first and stop at the first one that survives. n is like 25 so picking the
            // min over and over beats sorting, and strict < keeps the earliest in list order on ties like the old loop did.
            // infinite scores never won the old loop either, so they never win here
            for (int round = 0; round < n; round++) {
                int best = -1;
                double bestScore = Double.POSITIVE_INFINITY;
                for (int i = 0; i < n; i++) {
                    if (scores[i] < bestScore) {
                        bestScore = scores[i];
                        best = i;
                    }
                }
                if (best == -1) {
                    break;
                }
                scores[best] = Double.NaN; // NaN < anything is false, so it's out of the running
                BlockPos pos = blockList.get(best);
                // banned is skipped, not removed: it comes back as the answer the moment the ban runs out
                if (blockUnreachable(pos)) {
                    continue;
                }
                // If our current block isn't valid, fix it up. This cleans while we're iterating.
                // anything we never get to stays put, which is fine: every rescan walks the whole cache and does the same cleanup
                if (!blockIsValid.test(pos)) {
                    removeBlock(pos, blocks);
                    continue;
                }
                if (isValid.test(pos)) {
                    // the old "purge far away blocks while we're here" bit is gone. smartPurge already keeps the nearest
                    // maxCacheSizePerBlockType per scan, and this one could delete the answer it was about to return
                    return Optional.of(pos);
                }
            }
            return Optional.empty();
        }

        /**
         * Purge enough blocks so our size is small enough
         */
        public void smartPurge(AltoClef mod, Vec3 playerPos) {

            // Clear cached by position blocks, as they can be a handful.
            try {
                int MAX_CACHE_SIZE = _config.maxTotalCacheSize;
                if (_cachedByPosition.size() > MAX_CACHE_SIZE) {
                    List<BlockPos> toRemoveList = new ArrayList<>(_cachedByPosition.size() - MAX_CACHE_SIZE);
                    // Just purge randomly.
                    if (!_cachedByPosition.keySet().isEmpty()) {
                        for (BlockPos pos : _cachedByPosition.keySet()) {
                            if (_cachedByPosition.size() - toRemoveList.size() < MAX_CACHE_SIZE) {
                                break;
                            }
                            toRemoveList.add(pos);
                        }
                    }
                    if (!toRemoveList.isEmpty()) {
                        for (BlockPos toDelete : toRemoveList) {
                            _cachedByPosition.remove(toDelete);
                        }
                    }
                }
            } catch (Exception e) {
                Debug.logWarning("Failed to purge/reduce _cachedByPosition cache.: Its size remains at " + _cachedByPosition.size());
            }

            // ^^^ TODO: Something about that feels fishy, particularly how it's disconnected from the _cachedBlocks purging.
            // I smell a dangerous edge case bug.
            if (!_cachedBlocks.keySet().isEmpty()) {
                for (Block block : _cachedBlocks.keySet()) {
                    List<BlockPos> tracking = _cachedBlocks.get(block);

                    // Clear blacklisted blocks
                    try {
                        // Untrack the blocks further away
                        // the old version was three streams and a comparator that worked out two distances per compare, all
                        // while holding the mutex the main thread's block queries wait on. now it's distances once, into an array
                        // (blacklisted ones and dupes still go; This is invalid, because some blocks we may want to GO TO not BREAK,
                        // so no shouldAvoidBreaking filter here either)
                        int size = tracking.size();
                        BlockPos[] kept = new BlockPos[size];
                        double[] dist = new double[size];
                        Set<BlockPos> seen = new HashSet<>(size * 2);
                        int count = 0;
                        for (BlockPos pos : tracking) {
                            if (blockUnreachable(pos) || !seen.add(pos)) continue;
                            kept[count] = pos;
                            dist[count] = pos.distToCenterSqr(playerPos.x, playerPos.y, playerPos.z);
                            count++;
                        }
                        // boxed indices are cached below 128 so this doesn't even allocate for a normal list. object sorts are
                        // stable, which is what the old sorted() gave us for equal distances
                        Integer[] order = new Integer[count];
                        for (int i = 0; i < count; i++) order[i] = i;
                        Arrays.sort(order, (l, r) -> Double.compare(dist[l], dist[r]));
                        int keep = Math.min(count, _config.capFor(block));
                        List<BlockPos> trimmed = new ArrayList<>(keep);
                        for (int i = 0; i < keep; i++) trimmed.add(kept[order[i]]);
                        // addBlock bails on a byPosition hit, so anything dropped here has to leave that map too or it's never seen again
                        Set<BlockPos> keptSet = new HashSet<>(trimmed);
                        for (BlockPos pos : tracking) {
                            if (!keptSet.contains(pos)) _cachedByPosition.remove(pos, block);
                        }
                        tracking = trimmed;
                        // This won't update otherwise.
                        _cachedBlocks.put(block, tracking);
                    } catch (IllegalArgumentException e) {
                        // Comparison method violates its general contract: Sometimes transitivity breaks.
                        // In which case, ignore it.
                        Debug.logWarning("Failed to purge/reduce block search count for " + block + ": It remains at " + tracking.size());
                    }
                }
            }
        }
    }

    static class BlockTrackerConfig {
        public double scanInterval = 7;
        public boolean scanAsynchronously = true;
        public int maxTotalCacheSize = 2500;
        public int maxCacheSizePerBlockType = 25;
        // one mineshaft has more oak logs than 25 and the slots go nearest first, so standing near one the real oak further out
        // never got cached and every log we knew about was a post the pick throws out (TreeReach.buried). no filter in the scan:
        // what it finds is what the tracker knows, the pick is what has opinions
        public int maxCacheSizePerLogType = 64;
        public double cutoffDistance = 128;
        public int defaultUnreachableAttemptsAllowed = 4;

        static boolean isLog(Block block) {
            return block.defaultBlockState().is(BlockTags.LOGS);
        }

        int capFor(Block block) {
            return isLog(block) ? maxCacheSizePerLogType : maxCacheSizePerBlockType;
        }
    }
}
