package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.PhaseHandler;
import adris.altoclef.tasks.misc.PlaceBedAndSetSpawnTask;
import adris.altoclef.tasks.speedrun.gamer.Respawn;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.Timeout;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.end.EndGear;
import adris.altoclef.tasks.speedrun.gamer.tasks.StrongholdRoomSearchTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.world.FrameGeometry;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Optional;

// dig down at the start chunk, walk the chunk spiral, find the 12 frames. when the spiral is dry once, the rays are
// thrown away and LOCATE runs again from a spot 150 blocks sideways (the regress is just "no strongholdStart")
public class RoomPhase implements PhaseHandler {
    private boolean hopelessSaid;
    private StrongholdRoomSearchTask task;
    private boolean keepTask;
    private int movingRetries;
    // the bed by the stronghold start (Respawn.wantRoomBed): decided once on arrival, never again mid dig
    private PlaceBedAndSetSpawnTask roomBed;
    boolean roomBedDecided;
    private double roomBedSince;
    // a bed that will not go down (no spot, mobs on it) does not get to hold up the search
    private static final double ROOM_BED_SECONDS = 60;
    // the bed goes down at the top of the dig. the stronghold's top is never above sea level - 10, so under this we're
    // already in the shaft and a bed down there is a bed in a hole
    private static final double ROOM_BED_BELOW_Y = 50;
    // END_PREP's gate, run on the surface before the dig so we don't climb back out for it after OPEN. its own instance: the real
    // END_PREP keeps its attempts and clocks, and re-checks later anyway (it finds nothing and walks in, or fixes what the dig lost)
    private EndPrepPhase prep = new EndPrepPhase();
    private boolean prepLatched;
    private boolean prepEnded;
    // retries spent while short on beds, free for the same reason as the moving ones
    private int shortRetries;
    private long prepSince;
    private BlockPos prepAt;
    // game ticks, not secondsInPhase: holdsBudget moves the phase start forward, so that clock stands still while we hold it
    // (same reason as OpenTopUp's cap)
    private static final long PREP_CAP_TICKS = 300 * 20;

    @Override
    public GamerPhase phase() {
        return GamerPhase.ROOM;
    }

    @Override
    public String hud() {
        return GamerPhase.ROOM.hud();
    }

    @Override
    public String hudState() {
        if (holdsBudget()) {
            return prep.hudState();
        }
        return task == null ? null : task.step();
    }

    // the prep's errands are what the end needs, not the search taking long (the gear has its own cap, PREP_CAP_TICKS, the beds have none)
    @Override
    public boolean holdsBudget() {
        return prepLatched && !prepEnded;
    }

    @Override
    public boolean isDone(GamerFacts facts, RunState state, GamerConfig cfg) {
        return state.endPortalCenter != null && state.framesSeen >= FrameGeometry.FRAME_COUNT;
    }

    @Override
    public Optional<GamerPhase> regressTo(GamerFacts facts, RunState state, GamerConfig cfg) {
        if (state.strongholdStart == null && state.endPortalCenter == null) {
            return Optional.of(GamerPhase.LOCATE);
        }
        Optional<GamerPhase> back = StrongholdRules.regressForEyes(facts, state);
        // polled every tick, said once per visit
        if (back.isPresent() && !hopelessSaid) {
            hopelessSaid = true;
            Debug.logInternal(StrongholdRules.hopelessLine(facts, state));
        }
        return back;
    }

    @Override
    public void onEnter(AltoClef mod, GamerContext ctx) {
        hopelessSaid = false;
        if (ctx.attempt() == 1) {
            shortRetries = 0;
        }
        // a retry because the spiral is still working keeps the task: a fresh one walks back to the start chunk and forgets
        // the level it searches at. anything else (first entry, a regress back here) starts clean
        if (keepTask && task != null) {
            task.newWindow();
        } else {
            task = null;
            movingRetries = 0;
        }
        keepTask = false;
        roomBed = null;
        roomBedDecided = false;
        prep = new EndPrepPhase();
        prepLatched = false;
        prepEnded = false;
    }

    // the budget is one window: a spiral that reached new chunks in it is a long legit search, not a stuck one
    @Override
    public Timeout onTimeout(GamerContext ctx, int attempt, String reason) {
        // only a held budget getting nibbled by other chains can land here while short. one more go, however many it takes
        if (holdsBudget() && prep.shortOnBeds()) {
            keepTask = true;
            shortRetries++;
            return Timeout.RETRY;
        }
        if (task != null && StrongholdRules.mayRetryMoving(movingRetries) && StrongholdRules.roomStillMoving(task.chunksReached(), task.exhausted(), reason)) {
            keepTask = true;
            movingRetries++;
            return Timeout.RETRY;
        }
        // PhaseMachine bumps the attempt on every retry, the moving ones were free and must not eat the real ones
        return PhaseHandler.super.onTimeout(ctx, Math.max(1, attempt - movingRetries - shortRetries), reason);
    }
    @Override
    public Task tick(AltoClef mod, GamerContext ctx) {
        Task bed = roomBedStep(mod, ctx);
        if (bed != null) {
            return bed;
        }
        Task errand = prepStep(mod, ctx);
        if (errand != null) {
            return errand;
        }
        if (task == null) {
            task = new StrongholdRoomSearchTask(ctx);
        }
        if (task.exhausted()) {
            return handleExhausted(ctx);
        }
        return task;
    }

    // the dig is where the kit gets lost, so a respawn bed goes down on the surface right before it when nothing holds our
    // respawn near here. the search task itself is not touched, this runs ahead of it while we stand at the start
    private Task roomBedStep(AltoClef mod, GamerContext ctx) {
        if (roomBedDecided && roomBed == null) {
            return null;
        }
        if (roomBed != null) {
            if (roomBed.isFinished(mod) || ctx.secondsInPhase() - roomBedSince > ROOM_BED_SECONDS) {
                // (done or out of time, either way the stall clock should not blame the dig for the minute the bed took)
                ctx.progress("respawn bed");
                roomBed = null;
                return null;
            }
            return roomBed;
        }
        RunState state = ctx.state();
        var player = mod.getPlayer();
        if (player == null || ctx.facts().dimension() != Dimension.OVERWORLD || player.getY() < ROOM_BED_BELOW_Y
                || !atStart(mod, ctx)) {
            return null;
        }
        roomBedDecided = true;
        if (!Respawn.wantRoomBed(state, true, EndGear.surplusBeds(ctx.facts().bedsInBag(), ctx.cfg().end.beds))) {
            return null;
        }
        roomBed = new PlaceBedAndSetSpawnTask();
        roomBedSince = ctx.secondsInPhase();
        return roomBed;
    }

    private static boolean atStart(AltoClef mod, GamerContext ctx) {
        RunState.Pos start = ctx.state().strongholdStart;
        var player = mod.getPlayer();
        return start != null && player != null
                && Math.hypot(start.x - player.getX(), start.z - player.getZ()) <= ctx.cfg().stronghold.startReachBlocks;
    }

    // latched once per visit: the first errand walks off the start chunk, and checking the spot again would flip this on and off
    private Task prepStep(AltoClef mod, GamerContext ctx) {
        if (prepEnded) {
            return null;
        }
        long now = ctx.facts().gameTime();
        if (!prepLatched) {
            var player = mod.getPlayer();
            if (player == null || !prepStarts(ctx.facts().dimension() == Dimension.OVERWORLD, player.getY(), atStart(mod, ctx))) {
                return null;
            }
            RunState.Pos start = ctx.state().strongholdStart;
            startPrep(new BlockPos(start.x, start.y, start.z), now);
        }
        // the cap only ends the gear shopping. capping the beds as well would just send us back up the shaft for them after OPEN
        Task step = prep.prepAhead(mod, ctx, prepAt, !prepOutOfTime(prepSince, now));
        return prepResult(mod, ctx, step);
    }

    void startPrep(BlockPos at, long now) {
        prepLatched = true;
        prepSince = now;
        prepAt = at;
    }

    // a stall in here would cost the search one of its attempts for something it did not do, so the stall clock stays out of it
    Task prepResult(AltoClef mod, GamerContext ctx, Task step) {
        if (step != null) {
            ctx.progress("end prep ahead of the dig");
            return step;
        }
        prepEnded = true;
        // we may have turned up with no bed and only just made some: the walk back to the start gets to ask again, once
        roomBedDecided = false;
        ctx.progress("end prep ahead of the dig done");
        ctx.log("room: end prep ahead of the dig is over after " + (ctx.facts().gameTime() - prepSince) / 20 + " s, going down");
        if (task != null) {
            task.backToStart(mod);
        }
        return null;
    }

    // any attempt: a retry that finds us on the surface short of something is better off shopping now than climbing back for it
    static boolean prepStarts(boolean overworld, double y, boolean atStart) {
        return overworld && y >= ROOM_BED_BELOW_Y && atStart;
    }

    static boolean prepOutOfTime(long since, long now) {
        return now - since >= PREP_CAP_TICKS;
    }

    @Override
    public double stallSeconds() {
        // no stall clock on the bed search, it has no end by design
        if (holdsBudget() && prep.shortOnBeds()) {
            return 0;
        }
        // digging through stone is slow, a chunk takes up to 90 s
        return 180;
    }

    private Task handleExhausted(GamerContext ctx) {
        RunState state = ctx.state();
        if (state.roomRetries >= 1 || state.strongholdStart == null) {
            ctx.fail("searched every chunk around the stronghold and found no portal room");
            return null;
        }
        state.roomRetries++;
        state.relocateFrom = new RunState.Pos(state.strongholdStart.x, 0, state.strongholdStart.z);
        state.strongholdRays = new ArrayList<>();
        state.strongholdEstimate = null;
        state.strongholdRadius = 0;
        state.strongholdStart = null;
        state.roomChunksVisited.clear();
        // the throw cap is per locate attempt (the ledger starts counting from this number), the relocated try would
        // otherwise inherit whatever the first one burned and fail after a throw or two
        state.eyeThrows = 0;
        ctx.log("no portal room in the stronghold chunks, throwing eyes again from somewhere else");
        ctx.save();
        task = null;
        return null;
    }
}
