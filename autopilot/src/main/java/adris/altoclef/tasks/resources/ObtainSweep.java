package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.RunStateStore;
import adris.altoclef.tasks.speedrun.gamer.config.NetherConfig;
import adris.altoclef.tasks.speedrun.gamer.phases.NetherPhase;
import adris.altoclef.tasks.speedrun.gamer.tasks.FindNetherStructureTask;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherGround;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Goal;
import adris.altoclef.tasks.speedrun.gamer.tasks.NetherSweepPlanner.Sight;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.WorldHelper;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

// the buddy's fork, steering 68 (6 Nov 2026), Tuning fortress_sweep: #get's fortress search as #gamer's region sweep. a
// fortress or a bastion starts in each 27 chunk structure cell, so the sweep walks cell after cell (NetherSweepPlanner,
// FindNetherStructureTask) instead of exploring chunks and wandering for ever (attempts 190 and 212: 250 game minutes, no
// fortress). the memory (visited cells, fortress and bastion spots, fortresses given up) is kept per server address and
// dimension, across orders, deaths, relogs and restarts, in a file beside #gamer's. it is never keyed by or reset on the
// seed the server sends: that is a hash only, and an anti-seedcracker sends another one every hour. the world counts as
// changed only on proof: the game time going back, or a remembered fortress whose chunk is loaded and shows none of its
// blocks; then that world's memory is forgotten, and the log says which proof it was
final class ObtainSweep {
    private static final Map<String, ObtainSweep> BY_WORLD = new HashMap<>();
    // a remembered fortress this far off is walked to before the old search takes over by it (blocks)
    static final int WALK_TO_REMEMBERED = 64;
    // the sightings are scanned this often (ticks), one kind a time
    static final int SCAN_TICKS = 20;
    // the game time may go back this much (a lag, a rounding) before it is proof of another world (ticks)
    static final long TIME_SLACK = 1200;

    private final String key;
    private final Path file;
    private RunState state;
    private NetherSweepPlanner planner;
    private FindNetherStructureTask find;
    private long startedAt = -1;
    private long lastScan = Long.MIN_VALUE;
    private int rotation;
    private long lastSave = Long.MIN_VALUE;

    private ObtainSweep(String key, Path file) {
        this.key = key;
        this.file = file;
    }

    // the sweep of the world we are in, made or loaded once per server and dimension
    static ObtainSweep of(AltoClef mod) {
        String key = RunStateStore.fingerprint() + "|" + WorldHelper.getCurrentDimension();
        ObtainSweep s = BY_WORLD.get(key);
        if (s == null) {
            Path gamer = RunStateStore.resolvePath(mod);
            Path file = gamer.resolveSibling("obtain-sweep-" + Integer.toHexString(key.hashCode()) + ".json");
            s = new ObtainSweep(key, file);
            s.load(mod);
            BY_WORLD.put(key, s);
        }
        s.checkWorld(mod);
        return s;
    }

    // the bastion blocks the sweep remembers for the world we are in, without making or loading its memory (none when the sweep
    // never ran here)
    static java.util.List<RunState.Pos> knownBastions() {
        ObtainSweep s = BY_WORLD.get(RunStateStore.fingerprint() + "|" + WorldHelper.getCurrentDimension());
        return s == null || s.state == null ? java.util.List.of() : java.util.List.copyOf(s.state.bastion);
    }

    private void load(AltoClef mod) {
        RunStateStore.Loaded loaded = RunStateStore.load(file, key);
        build(mod, loaded.state());
        Debug.logInternal("fortress sweep: " + (loaded.resumed() ? "the memory of " + key + " loaded, " + state.visitedCells.size()
                + " cells, " + state.fortress.size() + " fortresses" : "a fresh memory for " + key));
    }

    private void build(AltoClef mod, RunState s) {
        state = s;
        NetherConfig n = new NetherConfig();
        planner = new NetherSweepPlanner(state, n);
        planner.setGround(new NetherGround(mod, () -> n.waypointShiftBlocks));
        planner.setLog(Debug::logInternal);
        startedAt = mod.getWorld().getGameTime();
        find = new FindNetherStructureTask(planner, Goal.FORTRESS, () -> (mod.getWorld().getGameTime() - startedAt) / 20.0);
    }

    private void forget(AltoClef mod, String proof) {
        Debug.logInternal("fortress sweep: the world of " + key + " changed (" + proof + "), its memory forgotten");
        RunState fresh = new RunState();
        fresh.fingerprint = key;
        build(mod, fresh);
        save(mod, true);
    }

    // the proofs that the world is another one; the seed is none of them
    private void checkWorld(AltoClef mod) {
        Level level = mod.getWorld();
        long now = level.getGameTime();
        if (state.phaseEnteredGameTime > 0 && now + TIME_SLACK < state.phaseEnteredGameTime) {
            forget(mod, "the game time went back from " + state.phaseEnteredGameTime + " to " + now);
            return;
        }
        state.phaseEnteredGameTime = Math.max(state.phaseEnteredGameTime, now);
        for (RunState.Pos f : state.fortress) {
            BlockPos at = new BlockPos(f.x, f.y, f.z);
            if (!level.hasChunk(at.getX() >> 4, at.getZ() >> 4) || !mod.getChunkTracker().isChunkLoaded(at)) continue;
            if (!bricksNear(level, at)) {
                forget(mod, "the fortress at " + f.x + " " + f.y + " " + f.z + " is loaded and has none of its blocks");
                return;
            }
        }
    }

    private static boolean bricksNear(Level level, BlockPos at) {
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-2, -2, -2), at.offset(2, 2, 2))) {
            var b = level.getBlockState(p);
            if (b.is(Blocks.NETHER_BRICKS) || b.is(Blocks.NETHER_BRICK_FENCE) || b.is(Blocks.NETHER_BRICK_STAIRS)) return true;
        }
        return false;
    }

    // what the tracker knows of fortresses and bastions, one kind a scan, into the planner (and the memory)
    void scan(AltoClef mod) {
        long now = mod.getWorld().getGameTime();
        if (now - lastScan < SCAN_TICKS && now >= lastScan) return;
        lastScan = now;
        Sight sight = rotation++ % 2 == 0 ? Sight.FORTRESS : Sight.BASTION;
        if (planner.scan(s -> NetherPhase.seen(mod, s, planner), sight)) save(mod, true);
        else save(mod, false);
    }

    private void save(AltoClef mod, boolean now) {
        long t = mod.getWorld().getGameTime();
        if (!now && t - lastSave < 1200 && t >= lastSave) return;
        lastSave = t;
        RunStateStore.save(file, state);
    }

    // the blocks the sightings need tracked while the sweep runs
    static void track(AltoClef mod, boolean on) {
        if (on) {
            mod.getBlockTracker().trackBlock(NetherPhase.FORTRESS);
            mod.getBlockTracker().trackBlock(NetherPhase.BASTION);
        } else {
            mod.getBlockTracker().stopTracking(NetherPhase.FORTRESS);
            mod.getBlockTracker().stopTracking(NetherPhase.BASTION);
        }
    }

    // the sweep's step: null when a fortress is known near (the old search takes it from there), else the walk to a
    // remembered one or through the next cell; failed() when the sweep gave up
    Task step(AltoClef mod) {
        Optional<RunState.Pos> known = state.fortress.stream().filter(f -> !planner.isGivenUp(f))
                .min(java.util.Comparator.comparingDouble(f -> mod.getPlayer().blockPosition().distSqr(new BlockPos(f.x, f.y, f.z))));
        if (known.isPresent()) {
            RunState.Pos f = known.get();
            if (mod.getPlayer().blockPosition().distSqr(new BlockPos(f.x, mod.getPlayer().blockPosition().getY(), f.z)) > (double) WALK_TO_REMEMBERED * WALK_TO_REMEMBERED) {
                return new GetToXZTask(f.x, f.z);
            }
            return null;
        }
        if (find.failure() != null) return null;
        return find;
    }

    boolean failed() {
        return find.failure() != null;
    }

    String failure() {
        return find.failure();
    }
}
