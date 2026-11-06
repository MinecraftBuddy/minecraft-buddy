package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.movement.GetDownColumnTask;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.StrongholdConfig;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.NoPathScope;
import adris.altoclef.util.helpers.SeenFilter;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.world.FrameGeometry;
import adris.altoclef.world.StrongholdRoomPlan;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;

// dig down at the start chunk until stone bricks show, then walk the corridors looking for end portal frames. frames the
// client has loaded count from the start (checkFrames), bricks and corridors still go by what we see. once three frames
// agree on a ring the other nine are given (the ring is rigid)
public class StrongholdRoomSearchTask extends Task {
    private enum Stage {TO_START, DIG, SPIRAL, HINT, APPROACH}

    // everything only a stronghold builds with. the infested ones look the same from outside and the silverfish rooms are
    // some of the first walls we touch. the height and distance caps are what keep a stray bar from a village out
    static final Block[] BRICKS = {Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS,
            Blocks.CHISELED_STONE_BRICKS, Blocks.INFESTED_STONE_BRICKS, Blocks.INFESTED_MOSSY_STONE_BRICKS,
            Blocks.INFESTED_CRACKED_STONE_BRICKS, Blocks.INFESTED_CHISELED_STONE_BRICKS, Blocks.IRON_BARS,
            Blocks.END_PORTAL_FRAME};
    // the whole stronghold sits under this (the top is at most sea level - 10) and surface ruins do not
    static final int BRICK_MAX_Y = 56;
    private static final int BRICK_MAX_DIST = 140;
    private static final int SCAN_EVERY_TICKS = 5;
    private static final double SAVE_EVERY_SECONDS = 15;
    private static final double HINT_SECONDS = 40;
    private static final int APPROACH_RANGE = 4;
    private static final double NO_DRY_RETRY_SECONDS = 30;

    private final GamerContext ctx;

    private StrongholdConfig cfg;
    private Stage stage = Stage.TO_START;
    private double stageSince;
    private int tickCounter;
    private boolean done;
    private boolean exhausted;
    private boolean bricksSeen;
    private boolean pushedBehaviour;
    private boolean started;
    // the y the stronghold is on as far as we can tell: the bricks we hit digging down, else where the dig ended. chunks
    // only count as searched when we were near this level
    private double workY = Double.NaN;
    private boolean workFromBricks;
    private boolean retriedSkipped;
    private boolean hintGivenUp;
    private int chunksReached;
    private boolean rearmChunkTimer;
    private double lastSave;
    private double startedAt;

    private Task goStartTask;
    private LocateLegPlanner.Leg startLeg;
    private Task digTask;
    private Task chunkTask;
    // chunks we gave up on (timer, the walk ending short, or a stall) without ever seeing them. not covered, not saved
    private final Set<String> skippedChunks = new HashSet<>();
    private StrongholdRoomPlan.Chunk chunkTarget;
    private double chunkSince;
    private final RoomCoverage.Stall stall = new RoomCoverage.Stall();
    private static final int CHUNK_GOAL_RANGE = 7;
    // a corridor cell is a spot we can stand on, so get right onto it
    private static final int CORRIDOR_GOAL_RANGE = 2;
    // set while the leg in progress is a walk to a corridor cell instead of a chunk's middle
    private BlockPos corridorGoal;
    private RoomCoverage.Visited visitedCells = new RoomCoverage.Visited();
    private int timedOutInLeg;
    private int noPathStreak;
    // chunks that ended on no path while the cascade guard was on. not picked again, but they are not skipped either: a new
    // level or any leg that gets somewhere gives them back
    private final Set<String> heldChunks = new HashSet<>();
    private Task hintTask;
    private BlockPos hintPos;
    private Task approachTask;
    // times a far-off frame sent us to dry land to dig down instead of walking at it, and whether there was no such land to find
    private int retargets;
    private double noDryUntil;
    // closest we have been to the ring this approach, and when: the walk is stuck if it has not got closer in a while
    private double approachBest;
    private double approachBestAt;
    private final SilverfishSpawnerBreaker spawnerBreaker = new SilverfishSpawnerBreaker();
    private int lastDigY = Integer.MAX_VALUE;
    // task time, like the chunk timer: a fight or a night trip in the middle of the dig is not the dig being slow
    private double digSince;
    // the column being dug (the start column, or a footprint cell the watchdog moved to) and the segment goal under way
    private int digX;
    private int digZ;
    private int digEntryY;
    private RoomCoverage.DigWatch watch;
    private int segmentY;
    private int digTaskY;
    private boolean failed;
    private final NoPathScope.Watch noPath = new NoPathScope.Watch();
    private String step = "Searching the stronghold";

    public StrongholdRoomSearchTask(GamerContext ctx) {
        this.ctx = ctx;
    }

    public String step() {
        return step;
    }

    // chunks newly covered since the last newWindow (RoomPhase asks this when the budget runs out)
    public int chunksReached() {
        return chunksReached;
    }

    public void newWindow() {
        chunksReached = 0;
        // the chunk timer kept running while the phase retried, the chunk in progress gets its 90 s again
        rearmChunkTimer = true;
    }

    // somebody ran errands ahead of us (RoomPhase.prepStep). the old leg expired while they did, and an expired leg digs from
    // wherever we happen to stand, so walk the leg again like a fresh one
    public void backToStart(AltoClef mod) {
        enter(mod, Stage.TO_START);
    }

    // the whole spiral came up empty
    public boolean exhausted() {
        return exhausted;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.END_PORTAL_FRAME, Blocks.SPAWNER);
        mod.getBlockTracker().trackBlock(BRICKS);
        // marvion: with a diamond pickaxe tunnelling is cheap enough that the pathfinder should stop flinching at stone
        mod.getBehaviour().push();
        pushedBehaviour = true;
        if (mod.getItemStorage().hasItem(Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE)) {
            mod.getBehaviour().setBlockBreakAdditionalPenalty(0);
        }
        // an interrupt (mob defense, eating) stops and restarts us: keep the stage, the chunk we were on and what we
        // have seen. onStop popped the behaviour and untracked, so those two are redone above, the rest only once
        if (started) {
            return;
        }
        started = true;
        cfg = ctx.cfg().stronghold;
        done = false;
        exhausted = false;
        bricksSeen = false;
        startedAt = seconds(mod);
        lastSave = startedAt;
        if (ctx.state().endPortalCenter != null) {
            routeToRing(mod);
        } else {
            enter(mod, Stage.TO_START);
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (done || exhausted || failed) {
            return null;
        }
        if (mod.getWorld() == null || WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
            ctx.fail("not in the overworld while searching the stronghold");
            return null;
        }
        tickCounter++;
        // every tick, before any stage can give up: a ring the client already has loaded beats whatever the dig was about to decide
        checkFrames(mod);
        if (tickCounter % SCAN_EVERY_TICKS == 0) {
            scan(mod);
            if (done) {
                return null;
            }
        }
        Task spawner = spawnerBreaker.next(mod, cfg.breakSilverfishSpawner, tickCounter);
        if (spawner != null) {
            step = "Breaking the silverfish spawner";
            // the stall clock is task time, this detour must not count as standing still
            stall.reset();
            // its own searches are not the leg's no path
            noPath.disarm();
            setDebugState("Breaking the silverfish spawner", step);
            return spawner;
        }
        return switch (stage) {
            case TO_START -> toStart(mod);
            case DIG -> dig(mod);
            case SPIRAL -> spiral(mod);
            case HINT -> hint(mod);
            case APPROACH -> approach(mod);
        };
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.END_PORTAL_FRAME, Blocks.SPAWNER);
        mod.getBlockTracker().stopTracking(BRICKS);
        noPath.disarm();
        if (pushedBehaviour) {
            mod.getBehaviour().pop();
            pushedBehaviour = false;
        }
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return done;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof StrongholdRoomSearchTask;
    }

    @Override
    protected String toHudString() {
        return step;
    }

    @Override
    protected String toDebugString() {
        return "Searching the stronghold for the portal room (" + stage + ")";
    }

    private double seconds(AltoClef mod) {
        return mod.getWorld().getGameTime() / 20.0;
    }

    private void enter(AltoClef mod, Stage next) {
        if (next == Stage.TO_START) {
            // coming back from a hint walk: the old leg is long expired and would skip the walk, then we dig at the hint spot
            goStartTask = null;
            startLeg = null;
        }
        if (next == Stage.DIG) {
            digSince = timeOnTaskSeconds();
            digTask = null;
            RunState.Pos start = ctx.state().strongholdStart;
            digX = start.x;
            digZ = start.z;
            digEntryY = (int) Math.floor(mod.getPlayer().getY());
            watch = new RoomCoverage.DigWatch(digEntryY, timeOnTaskSeconds());
            segmentY = RoomCoverage.firstSegmentY(digEntryY, cfg.digFloorY);
        }
        if (next == Stage.APPROACH) {
            approachBest = Double.MAX_VALUE;
            approachBestAt = seconds(mod);
        }
        stage = next;
        stageSince = seconds(mod);
    }

    private boolean onStartColumn(AltoClef mod) {
        RunState.Pos start = ctx.state().strongholdStart;
        return RoomCoverage.onColumn(mod.getPlayer().getX(), mod.getPlayer().getZ(), start.x, start.z);
    }

    private boolean onDigColumn(AltoClef mod) {
        return RoomCoverage.onColumn(mod.getPlayer().getX(), mod.getPlayer().getZ(), digX, digZ);
    }

    // the estimate was wrong, the column bottomed out with no bricks anywhere. RoomPhase throws eyes again from somewhere
    // else (once), instead of us spiralling around a floor guess
    private void estimateWrong() {
        ctx.save();
        exhausted = true;
    }

    private void enterSpiral(AltoClef mod) {
        if (Double.isNaN(workY)) {
            // the level of the bricks we hit, not wherever the dig stopped. no bricks = the floor, the estimate was off
            OptionalInt level = brickLevel(mod);
            workFromBricks = level.isPresent();
            workY = RoomCoverage.workY(level, mod.getPlayer().getY());
        }
        stall.reset();
        enter(mod, Stage.SPIRAL);
    }

    // frames the client has loaded count, line of sight or not (the user's call, a #goto end_portal_frame): the dig brings their
    // chunks in range and then we just go. centre if three agree, APPROACH from any stage once we are near it (far off it is dry
    // land and a dig first, see retarget), a hint if fewer
    private void checkFrames(AltoClef mod) {
        RunState state = ctx.state();
        if (state.endPortalCenter != null) {
            // asked again every tick, nothing latches: the walk and the dig down are what get us near
            if (stage != Stage.APPROACH && !far(mod, centreOf())) {
                enter(mod, Stage.APPROACH);
            }
            return;
        }
        RunState.Pos est = state.strongholdStart;
        List<int[]> frames = est == null
                ? StrongholdScan.knownFrames(mod, mod.getPlayer().getX(), mod.getPlayer().getZ())
                : StrongholdScan.knownFrames(mod, est.x, est.z);
        if (state.endPortalCenter == null && !frames.isEmpty()) {
            Optional<int[]> centre = StrongholdScan.centre(frames);
            if (centre.isPresent()) {
                int[] c = centre.get();
                state.endPortalCenter = new RunState.Pos(c[0], c[1], c[2]);
                refreshFilled(mod, c);
                ctx.progress("portal ring found");
                ctx.log("found the end portal room at " + c[0] + ", " + c[2]);
                ctx.save();
                routeToRing(mod);
            } else if (stage != Stage.HINT && stage != Stage.APPROACH && !hintGivenUp) {
                hintPos = nearestFrame(mod, frames);
                if (far(mod, new int[]{hintPos.getX(), hintPos.getY(), hintPos.getZ()})
                        && (retargets > 0 || retarget(mod, hintPos.getX(), hintPos.getY(), hintPos.getZ(), hintPos.getX(), hintPos.getZ()))) {
                    // already on the way down to it
                    return;
                }
                hintTask = new GetWithinRangeOfBlockTask(hintPos, APPROACH_RANGE);
                enter(mod, Stage.HINT);
            }
        }
    }

    // the straight walk is only for a ring that is near. one across an ocean or over a mountain is not walkable, the dig from dry
    // land is (no dry land found: the straight walk for a while, the shore chunks may just not be loaded yet)
    private boolean far(AltoClef mod, int[] at) {
        return seconds(mod) >= noDryUntil && !RoomRetarget.near(mod.getPlayer().getX(), mod.getPlayer().getY(), mod.getPlayer().getZ(), at[0], at[1], at[2]);
    }

    private void routeToRing(AltoClef mod) {
        int[] c = centreOf();
        if (far(mod, c) && retarget(mod, c[0], c[1], c[2], c[0], c[2])) {
            return;
        }
        enter(mod, Stage.APPROACH);
    }

    // a frame we cannot walk to: do the usual dry land walk and dig down from the nearest dry column to it instead. cx/cz is the ring
    // middle (the lava pool is under it) or the frame itself while the ring is not known. false = capped or no dry land, the caller
    // keeps the straight walk
    private boolean retarget(AltoClef mod, int fx, int fy, int fz, int cx, int cz) {
        if (retargets >= RoomRetarget.MAX_RETARGETS) {
            return false;
        }
        RoomRetarget.Probe probe = probe(mod);
        Optional<int[]> column = RoomRetarget.pickColumn(probe, fx, fz, cx, cz);
        if (column.isEmpty()) {
            noDryUntil = seconds(mod) + NO_DRY_RETRY_SECONDS;
            ctx.log("room: no dry land within " + RoomRetarget.SEARCH_RADIUS + " of the frame at " + fx + ", " + fy + ", " + fz
                    + ", walking straight at it");
            return false;
        }
        int[] xz = column.get();
        ctx.log("room: frame at " + fx + ", " + fy + ", " + fz + " is "
                + Math.round(Math.hypot(fx - mod.getPlayer().getX(), fz - mod.getPlayer().getZ())) + " away and "
                + Math.round(mod.getPlayer().getY() - fy) + " below, digging down from dry land at " + xz[0] + ", " + xz[1]);
        resetForRetarget(new RunState.Pos(xz[0], probe.topY(xz[0], xz[1]) + 1, xz[1]));
        ctx.save();
        enter(mod, Stage.TO_START);
        return true;
    }

    // a new start is a new search: nothing learned at the old one (level, chunks, held legs) carries over
    void resetForRetarget(RunState.Pos start) {
        retargets++;
        RunState state = ctx.state();
        state.strongholdStart = start;
        state.roomChunksVisited.clear();
        workY = Double.NaN;
        workFromBricks = false;
        bricksSeen = false;
        hintGivenUp = false;
        hintTask = null;
        hintPos = null;
        approachTask = null;
        retriedSkipped = false;
        chunksReached = 0;
        skippedChunks.clear();
        heldChunks.clear();
        visitedCells = new RoomCoverage.Visited();
        noPathStreak = 0;
        timedOutInLeg = 0;
        noPath.disarm();
        stall.reset();
        lastDigY = Integer.MAX_VALUE;
        dropLeg();
    }

    int retargets() {
        return retargets;
    }

    static RoomRetarget.Probe probe(AltoClef mod) {
        Level level = mod.getWorld();
        return new RoomRetarget.Probe() {
            @Override
            public boolean loaded(int x, int z) {
                return mod.getChunkTracker().isChunkLoaded(new BlockPos(x, 0, z));
            }

            @Override
            public int topY(int x, int z) {
                return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            }

            @Override
            public boolean solid(int x, int y, int z) {
                BlockPos p = new BlockPos(x, y, z);
                return level.getBlockState(p).isFaceSturdy(level, p, Direction.UP);
            }

            @Override
            public boolean fluid(int x, int y, int z) {
                return !level.getFluidState(new BlockPos(x, y, z)).isEmpty();
            }
        };
    }

    // bricks: are we inside yet. frames counted here are the ones we SEE, not the loaded ones: a loaded ring counted as seen would
    // finish the room before APPROACH walked a step, and the fill would start from wherever the dig left us
    private void scan(AltoClef mod) {
        RunState state = ctx.state();
        if (state.endPortalCenter != null) {
            countSeen(mod, StrongholdScan.seenFrames(mod));
        }
        // bricks at our own level mean we are in the stronghold. ones seen far above or below (a ruin, a corridor through
        // a cave) are no reason to skip the dig
        bricksSeen = !seenBricks(mod, 1, mod.getPlayer().getY()).isEmpty();
        if (stage == Stage.SPIRAL && !workFromBricks) {
            // we searched a level that was only a guess (a timed out dig): the first bricks we see correct it.
            // only ones near where we are, a level far from us would never count
            OptionalInt level = brickLevel(mod);
            if (level.isPresent()) {
                workFromBricks = true;
                adoptLevel(level.getAsInt(), state);
            }
        }
    }

    // the level of the bricks around us (a few of them, not the one nearest, which may be a ceiling)
    private OptionalInt brickLevel(AltoClef mod) {
        return RoomCoverage.walkLevel(seenBricks(mod, 8, mod.getPlayer().getY()), mod.getPlayer().getX(), mod.getPlayer().getZ());
    }

    private void adoptLevel(double newY, RunState state) {
        double oldY = workY;
        workY = newY;
        // a new level is a new try at everything the no paths said
        noPathStreak = 0;
        heldChunks.clear();
        // chunks marked at the guessed level were never seen from the real one: both sets start over. a retry of the
        // skipped ones is owed again too
        if (RoomCoverage.clearForNewLevel(state.roomChunksVisited, skippedChunks, oldY, workY)) {
            retriedSkipped = false;
            ctx.save();
        }
        // the walk in progress aims at the old level: drop it, the next pick rebuilds the goal at the new one
        dropLeg();
    }

    private void dropLeg() {
        chunkTarget = null;
        chunkTask = null;
        corridorGoal = null;
    }

    private BlockPos nearestFrame(AltoClef mod, List<int[]> frames) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int[] f : frames) {
            BlockPos p = new BlockPos(f[0], f[1], f[2]);
            double d = p.distSqr(mod.getPlayer().blockPosition());
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private void countSeen(AltoClef mod, List<int[]> frames) {
        int[] c = centreOf();
        int seen = FrameGeometry.seenCount(c, p -> {
            for (int[] f : frames) {
                if (f[0] == p[0] && f[1] == p[1] && f[2] == p[2]) {
                    return true;
                }
            }
            return false;
        });
        RunState state = ctx.state();
        if (seen > state.framesSeen) {
            state.framesSeen = seen;
        }
        refreshFilled(mod, c);
        if (state.framesSeen >= FrameGeometry.FRAME_COUNT) {
            finish();
        }
    }

    // same guard as OpenPhase.refresh: an unloaded frame reads as empty, so the count only moves when all 12 are loaded
    private void refreshFilled(AltoClef mod, int[] c) {
        for (int[] p : FrameGeometry.positions(c)) {
            if (!StrongholdScan.isFrame(mod, p)) {
                return;
            }
        }
        ctx.state().framesFilled = FrameGeometry.filledCount(c, p -> StrongholdScan.frameHasEye(mod, p));
    }

    private int[] centreOf() {
        RunState.Pos p = ctx.state().endPortalCenter;
        return new int[]{p.x, p.y, p.z};
    }

    private void finish() {
        ctx.state().framesSeen = FrameGeometry.FRAME_COUNT;
        ctx.save();
        done = true;
    }

    // seen stone bricks of the stronghold family, nearest first, at most limit of them, within a stronghold level of nearY
    // (NaN = any level). tracker order is scan order, so sort before asking the seen filter or the same few hidden ones
    // eat its budget forever
    private List<BlockPos> seenBricks(AltoClef mod, int limit, double nearY) {
        return seenBricks(mod, limit, nearY, p -> false);
    }

    // skip drops candidates before the nearest 200 cut: bricks all around where we have been would fill it and push the
    // frontier out
    private List<BlockPos> seenBricks(AltoClef mod, int limit, double nearY, Predicate<BlockPos> skip) {
        RunState.Pos start = ctx.state().strongholdStart;
        List<BlockPos> out = new ArrayList<>();
        // filter first, then take the nearest 200: a village next to us would fill all of them with the wrong bricks
        List<BlockPos> candidates = RoomCoverage.brickCandidates(mod.getBlockTracker().getKnownLocations(BRICKS), BRICK_MAX_Y,
                start != null, start == null ? 0 : start.x, start == null ? 0 : start.z, BRICK_MAX_DIST, nearY);
        candidates.removeIf(skip);
        for (BlockPos pos : StrongholdScan.nearest(candidates, mod.getPlayer().blockPosition(), StrongholdScan.MAX_SCAN)) {
            if (SeenFilter.isSeen(mod, pos)) {
                out.add(pos);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    private Task toStart(AltoClef mod) {
        RunState.Pos start = ctx.state().strongholdStart;
        if (start == null) {
            ctx.fail("no stronghold start to search from");
            return null;
        }
        double dist = Math.hypot(start.x - mod.getPlayer().getX(), start.z - mod.getPlayer().getZ());
        if (goStartTask == null) {
            goStartTask = new GetToXZTask(start.x, start.z, Dimension.OVERWORLD);
            startLeg = new LocateLegPlanner.Leg(seconds(mod), dist);
        }
        startLeg.update(seconds(mod), dist);
        if (dist <= cfg.startReachBlocks || startLeg.expired(seconds(mod))) {
            // frames seen on the way in are handled by scan(), so heading down is the only thing left to decide
            RoomCoverage.DigVerdict verdict = RoomCoverage.digVerdict(bricksSeen, mod.getPlayer().getY(), cfg.digFloorY,
                    onStartColumn(mod));
            if (verdict == RoomCoverage.DigVerdict.KEEP_DIGGING) {
                enter(mod, Stage.DIG);
            } else if (verdict == RoomCoverage.DigVerdict.FLOOR) {
                estimateWrong();
            } else {
                enterSpiral(mod);
            }
            return null;
        }
        step = "Walking to the stronghold";
        setDebugState("Walking to the start chunk", step);
        return goStartTask;
    }

    private Task dig(AltoClef mod) {
        step = "Digging down";
        setDebugState("Digging down at the start chunk", step);
        int y = (int) Math.floor(mod.getPlayer().getY());
        if (y < lastDigY) {
            lastDigY = y;
            ctx.progress("dug down");
        }
        double now = timeOnTaskSeconds();
        watch.update(mod.getPlayer().getX(), mod.getPlayer().getY(), mod.getPlayer().getZ(), digX, digZ, now);
        // the floor only counts on the original column (the footprint cells are inside its slack), so a shift does not
        // turn a staircase that was fine into a floor verdict
        RoomCoverage.DigVerdict verdict = RoomCoverage.digVerdict(bricksSeen, mod.getPlayer().getY(), cfg.digFloorY,
                onStartColumn(mod));
        if (verdict == RoomCoverage.DigVerdict.FLOOR) {
            // on the column, at the bottom, nothing: the chunk is not the stronghold's
            estimateWrong();
            return null;
        }
        // a stuck dig never reaches the spiral: columnLowestY is on the column only, a cave fall does not count
        RoomCoverage.DigOutcome outcome = RoomCoverage.digOutcome(verdict == RoomCoverage.DigVerdict.BRICKS,
                now - digSince, cfg.digDownSeconds, digEntryY, watch.columnLowestY(), BRICK_MAX_Y);
        if (outcome == RoomCoverage.DigOutcome.FAIL) {
            // not estimateWrong, that spends the only relocate on a mechanical stall
            failed = true;
            ctx.fail("the stronghold dig never got below y " + watch.columnLowestY());
            return null;
        }
        if (outcome == RoomCoverage.DigOutcome.SPIRAL) {
            if (!bricksSeen && Double.isNaN(workY)) {
                // the budget ran out deep on the column: search at the depth it reached, not in the cave we may be standing in
                workY = watch.columnLowestY();
            }
            Debug.logMessage("done digging down (bricks " + bricksSeen + ", y " + (int) mod.getPlayer().getY() + ")");
            enterSpiral(mod);
            return null;
        }
        if (watch.stuck(now)) {
            // a minute with no new low on this column: try the next cell around the original middle
            RunState.Pos start = ctx.state().strongholdStart;
            int[] cellXZ = watch.shift(start.x, start.z, now);
            if (cellXZ == null) {
                failed = true;
                ctx.fail("the stronghold dig got nowhere on any column");
                return null;
            }
            digX = cellXZ[0];
            digZ = cellXZ[1];
            // from where we are, not from the column's old low
            segmentY = RoomCoverage.firstSegmentY(y, cfg.digFloorY);
            digTask = null;
        }
        // GoalColumnDown is only satisfied on the exact column, so off it baritone plans back by itself and the segment
        // goal stays put. no leash: the paths it picks are good ones even when they detour through a cave
        int nextSeg = RoomCoverage.nextSegmentY(segmentY, mod.getPlayer().getY(), onDigColumn(mod), cfg.digFloorY);
        if (digTask == null || nextSeg != digTaskY) {
            segmentY = nextSeg;
            digTaskY = segmentY;
            // no wander: a failed progress check walking off into the open is how the column got lost in the first place.
            // a short segment every time, from the exact column, so the search finishes and the partial path never drifts
            digTask = new GetDownColumnTask(digX, digZ, segmentY, false);
        }
        return digTask;
    }

    private Task spiral(AltoClef mod) {
        step = "Searching the stronghold";
        RunState state = ctx.state();
        ChunkPos here = mod.getPlayer().chunkPosition();
        markCovered(mod, here, state);
        visitedCells.add(mod.getPlayer().getBlockX(), mod.getPlayer().getBlockY(), mod.getPlayer().getBlockZ());
        if (rearmChunkTimer) {
            rearmChunkTimer = false;
            chunkSince = timeOnTaskSeconds();
        }
        // a corridor leg is about its cell, the chunk it sits in being covered already is no reason to drop it
        boolean corridorLeg = corridorGoal != null;
        boolean covered = chunkTarget != null && !corridorLeg && state.roomChunksVisited.contains(chunkTarget.key());
        boolean walkFinished = chunkTarget != null && chunkTask != null && chunkTask.isFinished(mod);
        boolean timerOut = chunkTarget != null && timeOnTaskSeconds() - chunkSince >= cfg.perChunkSeconds;
        boolean stalled = chunkTarget != null && stall.stalled(mod.getPlayer().getX(), mod.getPlayer().getY(),
                mod.getPlayer().getZ(), timeOnTaskSeconds());
        boolean noPathHere = noPathNow(mod);
        if (chunkTarget != null && RoomCoverage.legOver(covered, walkFinished, timerOut, stalled, noPathHere)) {
            boolean noPathEnd = noPathHere && !covered && !walkFinished;
            noPathStreak = noPathEnd ? noPathStreak + 1 : 0;
            if (!noPathEnd) {
                heldChunks.clear();
            }
            boolean cascade = RoomCoverage.noPathCascade(noPathStreak);
            if (corridorLeg) {
                // reached or not, never aim at this cell again
                visitedCells.add(corridorGoal.getX(), corridorGoal.getY(), corridorGoal.getZ());
            } else if (cascade && noPathEnd) {
                // not the chunk's fault, so no skip. but picking it again at once would just fail it again, forever
                heldChunks.add(chunkTarget.key());
            } else {
                // only markCovered says a chunk is searched. the walk ending short, the timer or a stall
                // just mean we will not pick it again, the room was never seen from there
                if (!RoomCoverage.giveUp(state.roomChunksVisited, skippedChunks, chunkTarget.key())) {
                    ctx.progress("chunk searched");
                }
            }
            dropLeg();
            if (cascade) {
                OptionalInt level = brickLevel(mod);
                // a couple of blocks of jitter is not a new level, it would wipe the chunks we have covered
                if (level.isPresent() && Math.abs(level.getAsInt() - workY) >= 2) {
                    adoptLevel(level.getAsInt(), state);
                }
            }
        }
        if (chunkTarget == null && !pickNextChunk(mod, here)) {
            return null;
        }
        setDebugState("Searching chunk " + chunkTarget.cx() + ", " + chunkTarget.cz(), step);
        return chunkTask;
    }

    // standing in a dry tunnel is not searching the chunk (bricks and corridors are found by line of sight): the chunks around
    // us count once we are within ~24 blocks of their middle and near the level the stronghold is on
    private void markCovered(AltoClef mod, ChunkPos here, RunState state) {
        double px = mod.getPlayer().getX();
        double py = mod.getPlayer().getY();
        double pz = mod.getPlayer().getZ();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = here.x() + dx;
                int cz = here.z() + dz;
                if (RoomCoverage.covers(px, py, pz, cx, cz, workY) && state.roomChunksVisited.add(cx + "," + cz)) {
                    chunksReached++;
                    maybeSave(mod);
                }
            }
        }
    }

    private boolean pickNextChunk(AltoClef mod, ChunkPos here) {
        RunState.Pos start = ctx.state().strongholdStart;
        StrongholdRoomPlan.Chunk startChunk = new StrongholdRoomPlan.Chunk(Math.floorDiv(start.x, 16), Math.floorDiv(start.z, 16));
        // seen bricks have air in front of them: walk to the nearest such spot we have not been by. every step of it shows
        // more bricks, so the frontier grows on its own. the chunk centre is usually solid rock and baritone tunnels at it
        Optional<BlockPos> cell = nextCorridorCell(mod, start);
        if (cell.isPresent()) {
            BlockPos c = cell.get();
            step = "Following the stronghold corridors";
            corridorGoal = c;
            chunkTarget = new StrongholdRoomPlan.Chunk(Math.floorDiv(c.getX(), 16), Math.floorDiv(c.getZ(), 16));
            chunkTask = new GetWithinRangeOfBlockTask(c, CORRIDOR_GOAL_RANGE);
            chunkSince = timeOnTaskSeconds();
            stall.reset();
            armNoPath(mod);
            return true;
        }
        corridorGoal = null;
        // no corridor left to walk: the bricks' chunks, then the blind spiral
        Optional<StrongholdRoomPlan.Chunk> next = guidedChunk(mod, startChunk);
        if (next.isPresent()) {
            step = "Following the stronghold corridors";
        } else {
            next = StrongholdRoomPlan.next(startChunk, cfg.spiralRadiusChunks, doneOrSkipped(),
                    new StrongholdRoomPlan.Chunk(here.x(), here.z()));
        }
        if (next.isEmpty() && !skippedChunks.isEmpty() && !retriedSkipped) {
            // skipped is not searched: every chunk we gave up on gets one more go before the spiral calls itself empty
            retriedSkipped = true;
            skippedChunks.clear();
            next = StrongholdRoomPlan.next(startChunk, cfg.spiralRadiusChunks, doneOrSkipped(),
                    new StrongholdRoomPlan.Chunk(here.x(), here.z()));
        }
        if (next.isEmpty()) {
            Debug.logMessage("stronghold spiral exhausted after " + ctx.state().roomChunksVisited.size() + " chunks");
            ctx.save();
            exhausted = true;
            return false;
        }
        chunkTarget = next.get();
        // baritone picks its own height for an xz-only goal (the surface, a cave), where the room can never be seen. aim at
        // the stronghold's level instead and let it tunnel there
        chunkTask = new GetWithinRangeOfBlockTask(new BlockPos(chunkTarget.cx() * 16 + 8, (int) Math.round(workY),
                chunkTarget.cz() * 16 + 8), CHUNK_GOAL_RANGE);
        chunkSince = timeOnTaskSeconds();
        stall.reset();
        armNoPath(mod);
        return true;
    }

    private Optional<BlockPos> nextCorridorCell(AltoClef mod, RunState.Pos start) {
        List<BlockPos> bricks = seenBricks(mod, StrongholdScan.MAX_SCAN, workY,
                b -> visitedCells.near(b.getX(), b.getY(), b.getZ()));
        if (bricks.isEmpty()) {
            return Optional.empty();
        }
        List<BlockPos> cells = RoomCoverage.corridorCells(bricks, p -> standable(mod, p));
        return RoomCoverage.pickCorridorCell(cells, visitedCells, mod.getPlayer().getX(), mod.getPlayer().getY(),
                mod.getPlayer().getZ(), start.x, start.z, workY);
    }

    // feet and head free of anything solid or wet (a lava pool beside a wall is not a corridor), something to stand on
    private boolean standable(AltoClef mod, BlockPos feet) {
        if (!mod.getChunkTracker().isChunkLoaded(feet)) {
            return false;
        }
        BlockGetter level = mod.getWorld();
        BlockPos below = feet.below();
        return passable(level, feet) && passable(level, feet.above())
                && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    private static boolean passable(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    private void armNoPath(AltoClef mod) {
        // a fresh leg (or a fresh look after someone else had the wheel) starts its timed out count over
        timedOutInLeg = 0;
        var pathing = mod.getClientBaritone().getPathingBehavior();
        // failures first, then exhausted: the pathing thread writes them the other way round
        int failures = pathing.calcFailures();
        noPath.arm(failures, pathing.calcExhausted());
    }

    // baritone searched everything and found no way there, or twice gave up on getting 5 blocks closer: waiting out the 90 s
    // timer would be standing still
    private boolean noPathNow(AltoClef mod) {
        if (!noPath.armed()) {
            // another task had the wheel (spawner, interrupt): whatever failed then was not this leg's
            armNoPath(mod);
            return false;
        }
        var pathing = mod.getClientBaritone().getPathingBehavior();
        int failures = pathing.calcFailures();
        int exhaustedNow = pathing.calcExhausted();
        NoPathScope.Verdict verdict = noPath.poll(failures, exhaustedNow, pathing.isPathing());
        if (verdict == NoPathScope.Verdict.TIMED_OUT) {
            timedOutInLeg++;
        }
        return RoomCoverage.noPathEnds(verdict == NoPathScope.Verdict.EXHAUSTED, timedOutInLeg);
    }

    private Optional<StrongholdRoomPlan.Chunk> guidedChunk(AltoClef mod, StrongholdRoomPlan.Chunk startChunk) {
        List<int[]> xz = new ArrayList<>();
        for (BlockPos p : seenBricks(mod, StrongholdScan.MAX_SCAN, Double.NaN)) {
            xz.add(new int[]{p.getX(), p.getZ()});
        }
        return RoomCoverage.nearestUnvisitedBrickChunk(xz, doneOrSkipped(), mod.getPlayer().getX(),
                mod.getPlayer().getZ(), startChunk.cx(), startChunk.cz(), cfg.spiralRadiusChunks)
                .map(c -> new StrongholdRoomPlan.Chunk(c[0], c[1]));
    }

    // chunks we are done with, one way or the other. only the first set is saved and counted
    private Set<String> doneOrSkipped() {
        Set<String> all = new HashSet<>(ctx.state().roomChunksVisited);
        all.addAll(skippedChunks);
        all.addAll(heldChunks);
        return all;
    }

    private void maybeSave(AltoClef mod) {
        if (seconds(mod) - lastSave >= SAVE_EVERY_SECONDS) {
            lastSave = seconds(mod);
            ctx.save();
        }
    }

    // one or two frames seen, not enough for a ring: go closer and look
    private Task hint(AltoClef mod) {
        step = "Looking at the portal frames";
        setDebugState("Walking up to a seen frame", step);
        if (seconds(mod) - stageSince > HINT_SECONDS || mod.getPlayer().blockPosition().closerThan(hintPos, APPROACH_RANGE + 1)) {
            hintGivenUp = true;
            if (bricksSeen) {
                enterSpiral(mod);
            } else {
                enter(mod, Stage.TO_START);
            }
            return null;
        }
        return hintTask;
    }

    // ring known: go and see the rest. baritone stays out of lava, and we never swim it
    private Task approach(AltoClef mod) {
        step = "Looking at the portal frames";
        setDebugState("Approaching the end portal", step);
        if (approachTask == null) {
            approachTask = new GetWithinRangeOfBlockTask(new BlockPos(centreOf()[0], centreOf()[1], centreOf()[2]), APPROACH_RANGE);
        }
        // a ring we cannot get closer to (ocean in the way) is not worth waiting out: go down from dry land instead
        int[] c = centreOf();
        double d = Math.sqrt(mod.getPlayer().distanceToSqr(c[0], c[1], c[2]));
        if (RoomRetarget.progressed(approachBest, d)) {
            approachBest = d;
            approachBestAt = seconds(mod);
        } else if (seconds(mod) - approachBestAt >= RoomRetarget.PROGRESS_SECONDS && far(mod, c)
                && retarget(mod, c[0], c[1], c[2], c[0], c[2])) {
            return null;
        }
        // we're there. OPEN reads the frames and walks itself, so there is nothing left to wait for. waiting anyway is how you
        // get a finished goal task re-armed every tick and a very nervous goal marker
        if (approachTask.isFinished(mod)) {
            ctx.log("room: at the ring, on to the frames");
            finish();
            return null;
        }
        if (seconds(mod) - stageSince >= cfg.approachSeconds) {
            Debug.logMessage("taking the frames we have not seen as given, the ring is rigid");
            finish();
            return null;
        }
        return approachTask;
    }
}
