package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalObsidianTask;
import adris.altoclef.tasks.speedrun.gamer.BuildBlocks;
import adris.altoclef.tasks.speedrun.gamer.DetourSpec;
import adris.altoclef.tasks.speedrun.gamer.FurnacePlan;
import adris.altoclef.tasks.speedrun.gamer.FurnaceWatch;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.EnterNetherPortalTask;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.misc.PlaceBedAndSetSpawnTask;
import adris.altoclef.tasks.speedrun.gamer.Respawn;
import adris.altoclef.tasks.speedrun.gamer.end.EndGear;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.KitNeed;
import adris.altoclef.tasks.speedrun.gamer.KitRunner;
import adris.altoclef.tasks.speedrun.gamer.PhaseHandler;
import adris.altoclef.tasks.speedrun.gamer.PiglinGold;
import adris.altoclef.tasks.speedrun.gamer.PortalPlanner;
import adris.altoclef.tasks.speedrun.gamer.PortalPlanner.Method;
import adris.altoclef.tasks.speedrun.gamer.ResourceDetour;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.Timeout;
import adris.altoclef.tasks.speedrun.gamer.WorkbenchRules;
import adris.altoclef.tasks.speedrun.gamer.Workbenches;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalTask;
import adris.altoclef.tasks.speedrun.gamer.portal.ObsidianHold;
import adris.altoclef.tasks.speedrun.gamer.tasks.WalkProgress;
import adris.altoclef.tasksystem.Task;
import baritone.Baritone;
import baritone.altoclef.SettingsOverrides;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// gets us into the nether. first everything we want to hold when we leave (flint and steel, the buckets, water, armor
// on, blocks, food), then the portal: the lava bucket build first (altoLavaPoolPortal), and when that gives up the old
// CAST (lava lake, no diamonds), and after that OBSIDIAN. the cast never finishes on its own when there is no lake, it
// wanders, so a clock decides when to give up on it
public class PortalPhase implements PhaseHandler {
    private final KitRunner runner = new KitRunner();
    // the table the gate crafts on, and whatever else we put down on the way, comes with us before the portal is touched
    private final Workbenches benches = new Workbenches();
    private final FurnaceWatch furnaces = new FurnaceWatch(benches);
    // flint from gravel we walk past while the gate packs (DetourSpec.portalMayDetour). no PrepSupport here, so its own
    private final ResourceDetour gravel = ResourceDetour.gravel();
    private boolean gateDone;
    // the tick the food first sat at the minimum with the fill-up to the trip's stop still going, -1 = not yet
    private long fillUpSince = -1;
    // how long the fill-up past the minimum may take before the gate settles for the minimum
    private static final long FILL_UP_TICKS = 20 * 240;
    // what altoAsyncSmelting was when we came in, null when we are not holding it
    private Boolean userAsync;
    private boolean noGoldSaid;
    private boolean tracking;
    private long castStartTick = -1;
    // the cast's frame progress: the frame we're watching, the most obsidian it has had, and when that last went up
    // (-1 = never while we watched). see trackCastProgress
    private BlockPos progressOrigin;
    private int bestCount;
    private long lastProgressTick = -1;
    // the budget clock stands still while the obsidian count keeps setting new highs (see holdsBudget)
    private final ObsidianHold obsidianHold = new ObsidianHold();
    private boolean obsidianHolding;
    private DefaultGoToDimensionTask cast;
    private LPortalTask pool;
    private boolean poolFailed;
    // the walk to a lava lake between the pool giving up and the cast starting (see castLakeStep)
    private final WalkProgress lakeWalk = new WalkProgress();
    private BlockPos castLake;
    private boolean castLakeAsked;
    private boolean castLakeDone;
    // the lakes the pool gave up on. here and not in RunState or onEnter, so a retry of the phase doesn't walk to them again
    private final List<LPortalPlan.P> deadLakes = new ArrayList<>();
    // the pool task was the one handed the wheel on the last tick. with no layout yet it is the only time a night may take the
    // wheel here (read live, a layout can land between our tick and the night's question)
    private boolean poolActive;
    // the pool was handed to the task at least once this try, so a timeout can tell "stuck building it" from "stuck in prep"
    private boolean poolStarted;
    private ConstructNetherPortalObsidianTask obsidian;
    private EnterNetherPortalTask enter;
    private String hudState;
    // the spawn bed by the overworld portal (see portalBedStep). the task once it starts, the latch so it is one try per entry
    private PlaceBedAndSetSpawnTask portalBed;
    private boolean portalBedDecided;
    private double portalBedSince;
    private static final double PORTAL_BED_SECONDS = 45;
    // close enough to the lake that the tracker's rescan has it in its nearest sources
    private static final int LAKE_ARRIVE = 8;

    @Override
    public GamerPhase phase() {
        return GamerPhase.PORTAL;
    }

    @Override
    public String hud() {
        return GamerPhase.PORTAL.hud();
    }

    @Override
    public KitRunner kitRunner() {
        return runner;
    }

    @Override
    public String hudState() {
        return hudState;
    }

    @Override
    public ResourceDetour detour() {
        return gravel;
    }

    @Override
    public boolean ownsBenches() {
        return true;
    }

    // we are in the nether. an idle station left in the overworld is forgotten by then (Workbenches), a busy one stays registered
    // for the visit when we are back, so the part of "no phase ends with a pickup owed" that matters here is in tick: nothing leads
    // to the portal while one is owed
    @Override
    public boolean isDone(GamerFacts facts, RunState state, GamerConfig cfg) {
        return facts.dimension() == Dimension.NETHER;
    }

    @Override
    public void onRespawn() {
        furnaces.respawned();
    }

    @Override
    public void onEnter(AltoClef mod, GamerContext ctx) {
        runner.reset();
        BuildBlocks.newEntry(ctx.state());
        // the furnace we empty on the way out comes down with us, but never gets a batch of meat on the way
        furnaces.newExitPhase();
        gateDone = false;
        poolActive = false;
        fillUpSince = -1;
        resetCastClock();
        obsidianHold.reset();
        obsidianHolding = false;
        // the cast smelts its bucket iron the old way and waits at the furnace. with fire and forget on it walks off, and the
        // leaving-the-dimension rule below takes the ore straight back out, forever. so it's off for this phase
        var async = Baritone.settings().altoAsyncSmelting;
        userAsync = async.value;
        SettingsOverrides.put(async, false);
        cast = new DefaultGoToDimensionTask(Dimension.NETHER);
        pool = new LPortalTask(deadLakes);
        poolStarted = false;
        castLake = null;
        castLakeAsked = false;
        castLakeDone = false;
        lakeWalk.reset();
        if (ctx.attempt() <= 1) {
            ctx.state().portalPoolTimedOut = false;
            deadLakes.clear();
        }
        // a pool that already stalled the phase once is not tried again, not even after a relog
        poolFailed = ctx.state().portalPoolTimedOut;
        obsidian = new ConstructNetherPortalObsidianTask();
        enter = new EnterNetherPortalTask(Dimension.NETHER);
        hudState = null;
        portalBed = null;
        // one try per run: a retry of the phase does not place a second bed
        if (ctx.attempt() <= 1) {
            portalBedDecided = false;
        }
        gravel.reset();
        if (!tracking) {
            // portals so we can tell when ours exists, lava so "no lava seen" means something during the cast
            mod.getBlockTracker().trackBlock(Blocks.NETHER_PORTAL, Blocks.LAVA);
            tracking = true;
        }
    }

    @Override
    public void onExit(AltoClef mod, GamerContext ctx) {
        if (tracking) {
            mod.getBlockTracker().stopTracking(Blocks.NETHER_PORTAL, Blocks.LAVA);
            tracking = false;
        }
        gravel.reset();
        // same idea as IronPhase.releaseAsync, the other way round: only hand it back if the hold is still ours
        var async = Baritone.settings().altoAsyncSmelting;
        if (userAsync != null && SettingsOverrides.isHeld(async) && !async.value) {
            SettingsOverrides.put(async, userAsync);
        }
        userAsync = null;
        // we get here standing in the nether side of the pair
        PortalPlanner.recordArrival(ctx.state(), ctx.facts());
        if (ctx.state().overworldPortal == null) {
            Optional<BlockPos> used = mod.getMiscBlockTracker().getLastUsedNetherPortal(Dimension.OVERWORLD);
            used.ifPresent(p -> ctx.state().overworldPortal = new RunState.Pos(p.getX(), p.getY(), p.getZ()));
        }
        ctx.save();
    }

    // walking open ground for a spot is safe to leave for a night. everything after it is not: the build with a layout chosen, the
    // cast (its clocks run on game time and a trip would eat them), the obsidian way, and a lit portal about to be entered
    // a cast that keeps landing obsidian is not out of time at 14 minutes. the stall timer still runs, and the hold has a cap
    @Override
    public boolean holdsBudget() {
        return obsidianHolding;
    }

    @Override
    public boolean nightHold() {
        return holdsNight(gateDone, poolActive && pool != null && !pool.failed() && pool.searching());
    }

    public static boolean holdsNight(boolean gateDone, boolean poolSearching) {
        return gateDone && !poolSearching;
    }

    @Override
    public Task tick(AltoClef mod, GamerContext ctx) {
        // (cleared first so any early return below reads as "hold", only the pool branch lifts it)
        poolActive = false;
        // asked once a tick, it walks the tracked portal cells and reads the world for each
        Optional<BlockPos> portal = usablePortal(mod);
        boolean portalExists = portal.isPresent();
        noteOverworldPortal(mod, ctx, portal);
        GamerFacts f = ctx.facts();
        obsidianHolding = f.dimension() == Dimension.OVERWORLD && obsidianHold.step(obsidianTotal(mod, f), f.gameTime());
        // iron still cooking in the overworld is iron we are about to leave behind (an IRON phase that skipped itself on a
        // timeout can get us here). one last visit: take what is done, wait if it is nearly, pull the rest back out
        if (f.dimension() == Dimension.OVERWORLD) {
            Task takingBack = furnaces.finishing(mod, ctx);
            if (takingBack != null) {
                hudState = furnaces.hud();
                return takingBack;
            }
        }
        if (f.dimension() == Dimension.OVERWORLD && !f.furnaceJobs().isEmpty()) {
            furnaces.housekeeping(mod, ctx);
            Task leaving = furnaces.leave(mod, ctx, FurnacePlan.Leaving.DIMENSION);
            if (leaving != null) {
                hudState = furnaces.hud();
                return leaving;
            }
        }
        // PORTAL ends the moment we are in the nether, which is too late for a table left standing in the overworld (this phase
        // used to have no station tracking at all, and the gate crafts a bucket at one). so no portal task runs while one of
        // ours is coming down or still owed: the same rule GATHER and IRON end by, with the gate as the plan
        if (ctx.food().shortOfMinimum()) {
            fillUpSince = -1;
        } else if (fillUpSince < 0) {
            fillUpSince = f.gameTime();
        }
        boolean fillUp = fillUpSince < 0 || f.gameTime() - fillUpSince < FILL_UP_TICKS;
        List<KitNeed> gate = gateDone ? List.of() : PortalPlanner.gate(f, ctx.cfg().overworld, ctx.cfg().end.beds, ctx.food(), ctx.buildBlocks(), fillUp);
        if (f.dimension() == Dimension.OVERWORLD) {
            Task pickup = benches.tick(mod, ctx, gate);
            if (pickup != null) {
                hudState = benches.hud();
                return pickup;
            }
            // (only once the gate has nothing left to craft: a table the gate still wants is standing on purpose)
            // (and not the cast's own station while the cast is at it, see portalHold)
            boolean mayEnd = Workbenches.phaseMayEnd(ctx.state(), f.dimension().name(), f.gameTime());
            if (WorkbenchRules.portalHold(gate.isEmpty(), mayEnd, gateDone, gateDone && !mayEnd && benches.inUse(mod, ctx))) {
                hudState = "Waiting to take the crafting table back";
                return null;
            }
        }
        if (!gateDone) {
            Task flint = gravelSide(mod, ctx, gate);
            if (flint != null) {
                return flint;
            }
            if (!gate.isEmpty()) {
                Task prep = runner.run(ctx, gate);
                hudState = runner.hud();
                return prep;
            }
            // from here the cast juggles its own buckets, checking the gate again would fight it
            gateDone = true;
            // the gate already tried to make or wear gold, so nothing on us now means nothing to make it from. no mining detour
            if (!PiglinGold.worn(f) && !noGoldSaid) {
                noGoldSaid = true;
                ctx.log("no gold for piglins, good luck");
            }
            ctx.progress("ready for the portal");
        }
        Task spawnBed = portalBedStep(mod, ctx, portal);
        if (spawnBed != null) {
            return spawnBed;
        }
        // the lava pool build goes first. when it gives up the cast clock starts from now, not from when the phase did
        if (f.dimension() == Dimension.OVERWORLD
                && PortalPlanner.usePool(Baritone.settings().altoLavaPoolPortal.value, poolFailed, PortalPlanner.parse(ctx.state().portalMethod))) {
            if (portalExists) {
                hudState = "Going through the portal";
                return enter;
            }
            if (!pool.failed()) {
                poolActive = true;
                hudState = pool.searching() ? "Looking for a spot for the portal" : "Building the portal with lava buckets";
                poolStarted = true;
                return pool;
            }
            poolFailed = true;
            resetCastClock();
            // progress throws its text away, and this is the one line that says why we are not building the fast one
            ctx.log("lava pool gave up: " + pool.failReason());
            ctx.progress("lava pool gave up");
        }
        Task toLake = castLakeStep(mod, ctx, portalExists);
        if (toLake != null) {
            return toLake;
        }
        if (castStartTick < 0) {
            castStartTick = f.gameTime();
        }
        Method method = chooseMethod(mod, ctx);
        if (method == Method.CAST) {
            hudState = portalExists ? "Going through the portal" : "Casting obsidian with lava";
            return cast;
        }
        if (portalExists) {
            hudState = "Going through the portal";
            return enter;
        }
        hudState = "Getting obsidian for the portal";
        return obsidian;
    }

    // the pool gave up but its scan saw a lake the cast can flood from. the cast only knows the tracker's 25 nearest lava sources
    // (often all deep ones) and wanders off to find the rest, so it is walked to the lake first and the tracker fills in around
    // it. the cast clock has not started yet, so the walk does not eat it. a stalled walk or no lake is just the cast as before
    private Task castLakeStep(AltoClef mod, GamerContext ctx, boolean portalExists) {
        if (portalExists || castLakeDone || pool == null || !pool.failed() || ctx.facts().dimension() != Dimension.OVERWORLD
                || PortalPlanner.parse(ctx.state().portalMethod) != Method.CAST) {
            return null;
        }
        if (!castLakeAsked) {
            castLakeAsked = true;
            castLake = pool.castLake(mod);
            lakeWalk.reset();
        }
        if (castLake == null) {
            castLakeDone = true;
            return null;
        }
        var me = mod.getPlayer();
        // all three axes, the goal is a sphere and a lake 20 blocks down a cliff is not "here"
        double dist = Math.sqrt(castLake.distToCenterSqr(me.getX(), me.getY(), me.getZ()));
        if (lakeArrived(castLake, mod.getClientBaritone().getPlayerContext().playerFeet()) || lakeWalk.stalled(ctx.secondsInPhase(), dist)) {
            castLakeDone = true;
            return null;
        }
        hudState = "Walking to a lava lake";
        return new GetWithinRangeOfBlockTask(castLake.above(), LAKE_ARRIVE);
    }

    // the walk's own goal asked the way the path asks it, cell against cell. a float distance from the source's centre can
    // stay over 8 where the goal says done, and then the walk stands there being returned until the stall clock is bored
    static boolean lakeArrived(BlockPos lake, BlockPos feet) {
        return new GoalNear(lake.above(), LAKE_ARRIVE).isInGoal(feet);
    }

    // a bed from the bag goes down by the overworld portal on the way in, so a death in the nether lands us here and not at the
    // world spawn. the bed is not claimed as a night bed (NightSleepTask never hears of it), so the morning pickup leaves it, and
    // RunState.respawnPos (RespawnWatch) is the only record of it. same shape as RoomPhase.roomBedStep
    private Task portalBedStep(AltoClef mod, GamerContext ctx, Optional<BlockPos> portal) {
        if (portalBed != null) {
            if (portalBed.isFinished(mod) || ctx.secondsInPhase() - portalBedSince > PORTAL_BED_SECONDS) {
                ctx.progress("respawn bed");
                portalBed = null;
                return null;
            }
            hudState = "Setting the spawn by the portal";
            return portalBed;
        }
        var player = mod.getPlayer();
        if (portalBedDecided || portal.isEmpty() || player == null || ctx.facts().dimension() != Dimension.OVERWORLD) {
            return null;
        }
        BlockPos at = portal.get();
        if (Math.hypot(at.getX() - player.getX(), at.getZ() - player.getZ()) > Respawn.PORTAL_BED_REACH) {
            return null;
        }
        portalBedDecided = true;
        int reserve = EndGear.bedTarget(ctx.cfg().end, EndGear.wantSpawnBed(ctx.state(), ctx.cfg().end));
        if (!Respawn.wantPortalBed(ctx.state(), EndGear.surplusBeds(ctx.facts().bedsInBag(), reserve), at.getX(), at.getZ())) {
            return null;
        }
        portalBed = new PlaceBedAndSetSpawnTask().spawnOnly().awayFrom(at, 4);
        portalBedSince = ctx.secondsInPhase();
        hudState = "Setting the spawn by the portal";
        return portalBed;
    }

    // the gravel detour, asked after the station pickup and before the gate's own task. anything earlier in tick that took the wheel
    // (a furnace on the way out, a table coming down) never reaches here, and the gap rule ends a detour that stops being asked
    private Task gravelSide(AltoClef mod, GamerContext ctx, List<KitNeed> gate) {
        GamerFacts f = ctx.facts();
        if (!DetourSpec.portalMayDetour(f.dimension() == Dimension.OVERWORLD, gateDone)) {
            gravel.preempted(f.gameTime());
            return null;
        }
        Task flint = gravel.tick(mod, ctx, gate.isEmpty() ? null : gate.get(0));
        if (flint != null) {
            hudState = gravel.hud();
        }
        return flint;
    }

    private Method chooseMethod(AltoClef mod, GamerContext ctx) {
        GamerFacts f = ctx.facts();
        Method before = PortalPlanner.parse(ctx.state().portalMethod);
        double castSeconds = (f.gameTime() - castStartTick) / 20.0;
        if (before == Method.CAST) {
            trackCastProgress(mod, ctx, f.gameTime());
        }
        double sinceProgressSeconds = lastProgressTick < 0 ? castSeconds : (f.gameTime() - lastProgressTick) / 20.0;
        boolean sawLava = mod.getBlockTracker().isTracking(Blocks.LAVA) && mod.getBlockTracker().anyFound(Blocks.LAVA)
                && !cast.bucketPortalTask().lavaDrought();
        boolean diamondPick = f.count(Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE) > 0;
        Method now = PortalPlanner.decide(before, castSeconds, sinceProgressSeconds, ctx.cfg().overworld, sawLava, diamondPick);
        if (now != before) {
            ctx.state().portalMethod = now.name();
            ctx.log("giving up on casting, going the obsidian way");
            ctx.save();
        }
        return now;
    }

    // a frame cell turning obsidian is the only proof the cast is getting somewhere. a new frame spot starts from what is
    // already there (obsidian we happened to build next to, or a frame the cast moved) and earns nothing for it
    private void trackCastProgress(AltoClef mod, GamerContext ctx, long now) {
        BlockPos origin = cast.bucketPortalTask().portalOrigin();
        if (origin == null) {
            return;
        }
        int count = cast.bucketPortalTask().castCellsDone(mod);
        if (!origin.equals(progressOrigin)) {
            progressOrigin = origin;
            bestCount = count;
        } else if (count > bestCount) {
            bestCount = count;
            lastProgressTick = now;
            // the phase's no-progress clock only listens to real progress, and a cast standing still pouring is some
            ctx.progress("portal frame cell " + count);
        }
    }

    // obsidian in the bag plus the cast's frame cells still standing
    private int obsidianTotal(AltoClef mod, GamerFacts f) {
        var task = cast == null ? null : cast.bucketPortalTask();
        return f.count(Items.OBSIDIAN) + (task == null ? 0 : task.castCellsDone(mod));
    }

    private void resetCastClock() {
        castStartTick = -1;
        progressOrigin = null;
        bestCount = 0;
        lastProgressTick = -1;
    }

    // the same pick the enter task makes (its default goodPortal is ANY_PORTAL too), so "a portal exists" here is never a
    // cell it can't get its feet into, and it hands back the bottom cell even when the tracker only knows a higher one
    private Optional<BlockPos> usablePortal(AltoClef mod) {
        return EnterNetherPortalTask.usablePortal(mod, EnterNetherPortalTask.ANY_PORTAL, mod.getPlayer().position());
    }

    // remember the overworld end once we are standing next to it, the return trip walks back to this one
    private void noteOverworldPortal(AltoClef mod, GamerContext ctx, Optional<BlockPos> portal) {
        if (ctx.state().overworldPortal != null || ctx.facts().dimension() != Dimension.OVERWORLD) {
            return;
        }
        // a stale tracker cell on solid ground passes the enter task's rules, but this one goes in the run state for the
        // trip back, so it has to really be portal. this close the chunk is loaded and the read means something
        if (portal.isPresent() && portal.get().closerToCenterThan(mod.getPlayer().position(), 6)
                && EnterNetherPortalTask.portalAt(mod).test(portal.get())) {
            ctx.state().overworldPortal = new RunState.Pos(portal.get().getX(), portal.get().getY(), portal.get().getZ());
            ctx.save();
        }
    }

    // the pool stalling means the cast never got its turn, so the retry casts, and that stall does not use up an attempt
    // (see PortalPlanner.onTimeout). otherwise the cast was the problem (or the whole prep was slow) and the retry goes the
    // obsidian way, if there is a diamond pickaxe to mine it with. the last one is the end of the line, default retry would
    // loop the same plans for ever
    @Override
    public Timeout onTimeout(GamerContext ctx, int attempt, String reason) {
        Method current = PortalPlanner.parse(ctx.state().portalMethod);
        boolean poolWasRunning = poolStarted && !poolFailed;
        boolean diamondPick = ctx.facts().count(Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE) > 0;
        PortalPlanner.TimeoutPlan plan = PortalPlanner.onTimeout(current, attempt, ctx.cfg().maxAttempts, poolWasRunning,
                ctx.state().portalPoolTimedOut, diamondPick, obsidianHold.rose());
        if (!plan.retry()) {
            return Timeout.STUCK;
        }
        if (current == Method.CAST) {
            if (poolWasRunning) {
                ctx.log("portal timed out on the lava pool (" + reason + "), trying the cast");
            } else if (plan.method() == Method.OBSIDIAN) {
                ctx.log("portal timed out (" + reason + "), trying obsidian");
            } else if (obsidianHold.rose()) {
                ctx.log("portal timed out (" + reason + "), the cast was landing obsidian, casting again");
            } else {
                ctx.log("portal timed out (" + reason + "), no diamond pickaxe for obsidian, casting again");
            }
        }
        ctx.state().portalPoolTimedOut = plan.poolTimedOut();
        ctx.state().portalMethod = plan.method().name();
        ctx.save();
        return Timeout.RETRY;
    }

    // a death at home takes the pickaxe with it, and the cast buckets would be rebuilt with nothing to mine the iron with
    @Override
    public Optional<GamerPhase> regressTo(GamerFacts facts, RunState state, GamerConfig cfg) {
        return NetherRegress.pickaxeLost(facts);
    }
}
