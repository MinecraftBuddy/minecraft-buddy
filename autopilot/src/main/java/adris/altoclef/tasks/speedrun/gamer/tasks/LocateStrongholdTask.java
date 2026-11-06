package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetToXZTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.StrongholdConfig;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.world.StrongholdEstimator;
import baritone.api.utils.Dimension;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// replaces LocateStrongholdCoordinatesTask + GoToStrongholdPortalTask. the estimator decides throw / walk / arrive / dig,
// this runs the legs: throw an eye, watch it, pick it up, walk. the old one cached its first guess and stood on it forever
// (BM2:330 todo), this one keeps folding every throw in until the estimator says dig
public class LocateStrongholdTask extends Task {
    private enum Stage {DECIDE, THROW, OBSERVE, COLLECT, WALK, PAUSE}

    // how long the player must stand still before a throw. the eye spawns where the SERVER has us
    private static final int STILL_TICKS = 4;
    // after a throw we wait this long for the eye entity to be gone so the item drop exists
    private static final double COLLECT_WAIT_SECONDS = 9;
    // a drop over rough ground can take longer than 20 to reach, and a lost eye costs way more than the extra wait
    private static final double PICKUP_SECONDS = 40;
    private static final double PICKUP_RANGE = 40;
    private static final int ADVISE_EVERY_TICKS = 10;
    private static final int FRAME_CHECK_EVERY_TICKS = 5;
    // two walk legs in a row that went nowhere = this place is not walkable the way the estimator wants
    private static final int MAX_STALLED_LEGS = 2;

    private final GamerContext ctx;
    private final Task _overworldTask = new DefaultGoToDimensionTask(Dimension.OVERWORLD);

    private StrongholdConfig cfg;
    private StrongholdEstimator estimator;
    private LocateLegPlanner.ThrowLedger ledger;
    private EyeThrowObserver observer;
    private LocateLegPlanner.Leg leg;
    private Stage stage = Stage.DECIDE;
    private Task legTask;
    private PickupDroppedItemTask pickupTask;
    // where the eye was the last tick we saw it, for the per throw log line. null = never seen
    private double[] lastEyePos;
    private double legX;
    private double legZ;
    private boolean legIsArrive;
    private boolean legIsRelocate;
    private boolean forceThrow;
    private final EyeThrowObserver.Streak interruptStreak = new EyeThrowObserver.Streak();
    // after a throw we got nothing from, throw somewhere else (the same spot would give the same nothing)
    private boolean forceWalk;
    private boolean started;
    private int stalledLegs;
    private double lastThrowX = Double.NaN;
    private double lastThrowZ = Double.NaN;
    private int stillTicks;
    private int tickCounter;
    private boolean done;
    private double stageSince;
    // when the eye entity was first seen gone while collecting. NaN = not yet
    private double _eyeGoneAt = Double.NaN;
    private final TimerGame _pickupTimer = new TimerGame(PICKUP_SECONDS);
    private String step = "Throwing an Eye of Ender";

    public LocateStrongholdTask(GamerContext ctx) {
        this.ctx = ctx;
    }

    // what the phase shows after its headline
    public String step() {
        return step;
    }

    public StrongholdEstimator estimator() {
        return estimator;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.END_PORTAL_FRAME);
        // an interrupt (mob defense, eating) stops and restarts us: keep the stage, the observer and the leg so an eye
        // in the air is still being watched when we come back, only the first start sets things up
        if (started) {
            stillTicks = 0;
            return;
        }
        started = true;
        cfg = ctx.cfg().stronghold;
        RunState state = ctx.state();
        estimator = new StrongholdEstimator(new StrongholdEstimator.Params(cfg.sigmaDeg,
                StrongholdEstimator.Params.defaults().maxRays(), cfg.snapRadius));
        for (RunState.Ray r : state.strongholdRays) {
            estimator.addRay(new StrongholdEstimator.Ray(r.ox, r.oz, r.dx, r.dz, r.dived));
        }
        // eyeThrows counts every throw, rays only the accepted ones: the gap is rejected throws the fit has to remember
        estimator.restoreRejects(state.eyeThrows - state.strongholdRays.size());
        List<StrongholdEstimator.Ray> known = estimator.rays();
        if (!known.isEmpty()) {
            lastThrowX = known.get(known.size() - 1).ox();
            lastThrowZ = known.get(known.size() - 1).oz();
        }
        ledger = new LocateLegPlanner.ThrowLedger(state.eyeThrows, cfg.maxThrows, cfg.maxEmptyThrows);
        done = false;
        enter(mod, Stage.DECIDE);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
            setDebugState("Going to the overworld", "Going to the overworld");
            return _overworldTask;
        }
        tickCounter++;
        if (done) {
            return null;
        }
        if (tickCounter % FRAME_CHECK_EVERY_TICKS == 0 && framesSeenOnTheWay(mod)) {
            return null;
        }
        return switch (stage) {
            case DECIDE -> decide(mod);
            case THROW -> throwEye(mod);
            case OBSERVE -> observe(mod);
            case COLLECT -> collect(mod);
            case WALK -> walk(mod);
            case PAUSE -> pause(mod);
        };
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // an eye thrown right before a stop is gone by the time we are back, that is not a dud throw. interrupt or a real
        // stop of this same instance (the night trip, the food hunt), it keeps running afterwards either way
        if (stage == Stage.OBSERVE && observer != null) {
            observer.markInterrupted();
        }
        mod.getBlockTracker().stopTracking(Blocks.END_PORTAL_FRAME);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return done;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof LocateStrongholdTask;
    }

    @Override
    protected String toHudString() {
        return step;
    }

    @Override
    protected String toDebugString() {
        return "Locating the stronghold (" + stage + ")";
    }

    private void enter(AltoClef mod, Stage next) {
        stage = next;
        stageSince = seconds(mod);
        _eyeGoneAt = Double.NaN;
        stillTicks = 0;
    }

    // the task's own clock, not game time: it only ticks while we are the running task, so a night trip or a fight
    // in the middle of a leg (or of THROW) does not eat the leg budget. restarts keep it, see timeOnTaskSeconds
    private double seconds(AltoClef mod) {
        return timeOnTaskSeconds();
    }

    private long ticks(AltoClef mod) {
        return mod.getWorld().getGameTime();
    }

    // a frame in line of sight on the way (a lucky ravine, a library window): the room phase can take it from here
    private boolean framesSeenOnTheWay(AltoClef mod) {
        List<int[]> frames = StrongholdScan.seenFrames(mod);
        boolean seen = !frames.isEmpty();
        if (!seen) {
            // loaded but behind a wall still counts, same trust as #goto end_portal_frame
            frames = StrongholdScan.knownFrames(mod, mod.getPlayer().getX(), mod.getPlayer().getZ());
        }
        if (frames.isEmpty()) {
            return false;
        }
        int[] f = frames.get(0);
        ctx.state().strongholdStart = new RunState.Pos(f[0], f[1], f[2]);
        ctx.save();
        ctx.log(seen ? "saw an end portal frame, going straight for it"
                : "an end portal frame is loaded " + Math.round(Math.hypot(f[0] - mod.getPlayer().getX(), f[2] - mod.getPlayer().getZ()))
                + " blocks away, going straight for it");
        done = true;
        return true;
    }

    private Task decide(AltoClef mod) {
        RunState state = ctx.state();
        double px = mod.getPlayer().getX();
        double pz = mod.getPlayer().getZ();
        // an eye still in the air or on the floor from before an interrupt: go and get it before anything else
        if (collectPending(mod)) {
            enter(mod, Stage.COLLECT);
            return null;
        }
        if (estimator.rays().isEmpty() && state.relocateFrom != null && !legIsRelocate) {
            double[] to = LocateLegPlanner.relocateTarget(state.relocateFrom.x, state.relocateFrom.z, LocateLegPlanner.RELOCATE_BLOCKS);
            legIsRelocate = true;
            startLeg(mod, to[0], to[1], false);
            step = "Stepping away before trying again";
            return null;
        }
        if (forceWalk) {
            forceWalk = false;
            double[] to = forcedWalkTarget(px, pz);
            startLeg(mod, to[0], to[1], false);
            step = "Moving to a new spot for the next throw";
            return null;
        }
        if (forceThrow) {
            forceThrow = false;
            return beginThrow(mod);
        }
        StrongholdEstimator.Advice a = estimator.advise(px, pz, walkedSinceThrow(px, pz), mod.getItemStorage().getItemCount(Items.ENDER_EYE));
        switch (a.step()) {
            case THROW -> {
                return beginThrow(mod);
            }
            case WALK -> {
                // already standing on the walk target and the estimator still says walk: nothing left to walk, throw
                if (Math.hypot(a.x() - px, a.z() - pz) <= LocateLegPlanner.ARRIVE_BLOCKS) {
                    return beginThrow(mod);
                }
                startLeg(mod, a.x(), a.z(), false);
            }
            case ARRIVE -> {
                if (Math.hypot(a.x() - px, a.z() - pz) <= LocateLegPlanner.ARRIVE_BLOCKS) {
                    return beginThrow(mod);
                }
                startLeg(mod, a.x(), a.z(), true);
            }
            case DIG -> finishDig(a);
        }
        return null;
    }

    // toward the current guess if there is one, else along the ring tangent
    private double[] forcedWalkTarget(double px, double pz) {
        Optional<StrongholdEstimator.Estimate> e = estimator.estimate();
        return LocateLegPlanner.forcedWalkTarget(px, pz, e.isPresent(), e.map(StrongholdEstimator.Estimate::x).orElse(0.0),
                e.map(StrongholdEstimator.Estimate::z).orElse(0.0));
    }

    // baseline = how many eyes we held before the last throw. below it, with the eye still flying or lying close by,
    // there is something to collect. no longer true = forget the baseline
    private boolean collectPending(AltoClef mod) {
        int baseline = ctx.state().eyeBaseline;
        if (baseline <= 0) {
            return false;
        }
        // same blind spot as collect(): a falling eye item reads as no drop, so this only holds up after an interrupt
        boolean pending = LocateLegPlanner.collectPending(baseline, mod.getItemStorage().getItemCount(Items.ENDER_EYE),
                mod.getEntityTracker().entityFound(EyeOfEnder.class), nearDrop(mod));
        if (!pending) {
            ctx.state().eyeBaseline = 0;
        }
        return pending;
    }

    private double walkedSinceThrow(double px, double pz) {
        return Double.isNaN(lastThrowX) ? 0 : Math.hypot(px - lastThrowX, pz - lastThrowZ);
    }

    private void finishDig(StrongholdEstimator.Advice a) {
        ctx.state().strongholdStart = new RunState.Pos((int) Math.floor(a.x()), 0, (int) Math.floor(a.z()));
        persist();
        ctx.progress("stronghold start found");
        ctx.log("the eye dived, the stronghold is right under " + (int) a.x() + ", " + (int) a.z());
        done = true;
    }

    private void startLeg(AltoClef mod, double x, double z, boolean arrive) {
        legX = x;
        legZ = z;
        legIsArrive = arrive;
        double dist = Math.hypot(x - mod.getPlayer().getX(), z - mod.getPlayer().getZ());
        leg = new LocateLegPlanner.Leg(seconds(mod), dist);
        legTask = new GetToXZTask((int) Math.round(x), (int) Math.round(z), Dimension.OVERWORLD);
        // a sword in hand for the walk, the throw left an eye there
        StrongholdScan.swordAfterEye(mod);
        enter(mod, Stage.WALK);
    }

    private Task walk(AltoClef mod) {
        double px = mod.getPlayer().getX();
        double pz = mod.getPlayer().getZ();
        double dist = Math.hypot(legX - px, legZ - pz);
        double now = seconds(mod);
        leg.update(now, dist);
        step = legIsArrive ? "Walking toward the stronghold" : "Following the eyes";
        setDebugState("Walking leg to " + (int) legX + ", " + (int) legZ, step);
        if (dist <= LocateLegPlanner.ARRIVE_BLOCKS) {
            return legDone(mod);
        }
        if (leg.expired(now)) {
            Debug.logMessage("walk leg gave up: " + leg.why(now) + ", throwing from here");
            stalledLegs++;
            if (stalledLegs > MAX_STALLED_LEGS) {
                ctx.fail("cannot make progress walking toward the stronghold");
                return null;
            }
            legIsRelocate = false;
            clearRelocate();
            forceThrow = true;
            enter(mod, Stage.DECIDE);
            return null;
        }
        if (tickCounter % ADVISE_EVERY_TICKS == 0 && adviceChanged(mod, px, pz)) {
            enter(mod, Stage.DECIDE);
            return null;
        }
        return legTask;
    }

    // true when the estimator now wants something other than this walk (throw time, a new target, arrive, dig)
    private boolean adviceChanged(AltoClef mod, double px, double pz) {
        if (legIsRelocate) {
            return false;
        }
        StrongholdEstimator.Advice a = estimator.advise(px, pz, walkedSinceThrow(px, pz), mod.getItemStorage().getItemCount(Items.ENDER_EYE));
        if (a.step() == StrongholdEstimator.Step.WALK || a.step() == StrongholdEstimator.Step.ARRIVE) {
            return Math.hypot(a.x() - legX, a.z() - legZ) > 8;
        }
        return true;
    }

    private Task legDone(AltoClef mod) {
        stalledLegs = 0;
        if (legIsRelocate) {
            legIsRelocate = false;
            clearRelocate();
        }
        enter(mod, Stage.DECIDE);
        return null;
    }

    private void clearRelocate() {
        ctx.state().relocateFrom = null;
        ctx.save();
    }

    private Task beginThrow(AltoClef mod) {
        if (mod.getItemStorage().getItemCount(Items.ENDER_EYE) <= 0) {
            ctx.fail("out of Eyes of Ender while locating the stronghold");
            return null;
        }
        if (ledger.outOfThrows()) {
            ctx.fail("threw " + ledger.counted() + " eyes and still no stronghold");
            return null;
        }
        // a throw from (nearly) the same spot is the same bearing again and costs a fifth of an eye. the estimator
        // keeps that rule for its own THROW advice, the shortcuts in decide() and a stalled leg go through here
        if (LocateLegPlanner.tooCloseToLastThrow(mod.getPlayer().getX(), mod.getPlayer().getZ(), lastThrowX, lastThrowZ)) {
            forceWalk = true;
            enter(mod, Stage.DECIDE);
            return null;
        }
        enter(mod, Stage.THROW);
        return null;
    }

    private Task throwEye(AltoClef mod) {
        step = "Throwing an Eye of Ender";
        setDebugState("Throwing an eye", step);
        if (seconds(mod) - stageSince > 15) {
            ctx.fail("could not throw an Eye of Ender (equip or aim kept failing)");
            return null;
        }
        if (!mod.getSlotHandler().forceEquipItem(Items.ENDER_EYE)) {
            return null;
        }
        // standing still: the eye appears where the server has us, one step of drift is a degree of bearing
        if (mod.getPlayer().getDeltaMovement().horizontalDistanceSqr() > 1.0E-4) {
            stillTicks = 0;
            return null;
        }
        if (++stillTicks < STILL_TICKS || !LookHelper.tryAvoidingInteractable(mod)) {
            return null;
        }
        // persisted so a relog or an interrupt between the throw and the pickup still knows an eye is out there
        ctx.state().eyeBaseline = mod.getItemStorage().getItemCount(Items.ENDER_EYE);
        Debug.logInternal(throwLine(mod));
        lastEyePos = null;
        observer = new EyeThrowObserver(mod.getPlayer().getX(), mod.getPlayer().getZ(), ticks(mod));
        Minecraft.getInstance().gameMode.useItem(mod.getPlayer(), InteractionHand.MAIN_HAND);
        enter(mod, Stage.OBSERVE);
        return null;
    }

    private Task observe(AltoClef mod) {
        step = "Following the eyes";
        setDebugState("Watching the eye", step);
        Optional<Entity> eye = mod.getEntityTracker().getClosestEntity(EyeOfEnder.class);
        eye.ifPresent(e -> lastEyePos = new double[]{e.getX(), e.getY(), e.getZ()});
        EyeThrowObserver.Result r = eye.isPresent()
                ? observer.observe(ticks(mod), true, eye.get().getX(), eye.get().getY(), eye.get().getZ())
                : observer.observe(ticks(mod), false, 0, 0, 0);
        EyeThrowObserver.Outcome booked = interruptStreak.book(r.outcome());
        if (booked != EyeThrowObserver.Outcome.PENDING) {
            Debug.logInternal("eyes: observed " + booked + " (" + r.why() + "), last eye pos " + describePos(lastEyePos));
        }
        switch (booked) {
            case PENDING -> {
            }
            case INTERRUPTED -> {
                // no dud on the ledger, the throw just never got watched. the baseline stays, and decide() runs collectPending
                // first so a stray eye gets picked up before the next throw overwrites it. forceThrow skips the estimator advice and
                // throws again from here, and it stays set across a collect detour so the throw still happens after it)
                observer = null;
                forceThrow = true;
                enter(mod, Stage.DECIDE);
            }
            case EMPTY -> {
                ledger.empty();
                Debug.logMessage("empty eye throw: " + r.why());
                if (ledger.tooManyEmpty()) {
                    ctx.fail("threw an Eye of Ender " + ledger.counted() + " times and no eye ever appeared");
                    return null;
                }
                enter(mod, Stage.PAUSE);
            }
            case UNUSABLE -> {
                ledger.eyeAppeared();
                ctx.state().eyeThrows = ledger.counted();
                lastThrowX = observer.originX();
                lastThrowZ = observer.originZ();
                persist();
                // no bearing from here, so the next throw must come from a different spot or it burns another eye for the same nothing
                forceWalk = true;
                enter(mod, Stage.COLLECT);
            }
            case RAY -> acceptRay(mod, r);
        }
        return null;
    }

    private void acceptRay(AltoClef mod, EyeThrowObserver.Result r) {
        ledger.eyeAppeared();
        ctx.state().eyeThrows = ledger.counted();
        lastThrowX = observer.originX();
        lastThrowZ = observer.originZ();
        if (!estimator.addRay(r.ray())) {
            Debug.logMessage("estimator rejected the ray (parallel, behind us or an outlier)");
        }
        persist();
        ctx.progress("eye thrown");
        enter(mod, Stage.COLLECT);
    }

    // eyes thrown, now wait for the entity to go and pick the drop up (80% of them survive)
    private Task collect(AltoClef mod) {
        step = "Picking the Eye of Ender back up";
        setDebugState("Collecting the thrown eye", step);
        boolean flying = mod.getEntityTracker().entityFound(EyeOfEnder.class);
        double waited = seconds(mod) - stageSince;
        int baseline = Math.max(1, ctx.state().eyeBaseline);
        if (pickupTask != null) {
            boolean back = mod.getItemStorage().getItemCount(Items.ENDER_EYE) >= baseline;
            String gaveUp = pickupTask.gaveUpReason();
            if (_pickupTimer.elapsed() || back || gaveUp != null) {
                pickupTask = null;
                return endCollect(mod, baseline, back ? "back" : gaveUp != null ? "gave up, " + gaveUp : "timed out");
            }
            return pickupTask;
        }
        if (flying && waited < COLLECT_WAIT_SECONDS) {
            return null;
        }
        if (!flying && Double.isNaN(_eyeGoneAt)) {
            _eyeGoneAt = seconds(mod);
        }
        // the item falls from ~8 blocks up and the tracker only lists it once it is near the ground, so the clock
        // runs from when the eye went, not from when we entered collect
        if (LocateLegPlanner.settleWait(flying, seconds(mod) - _eyeGoneAt,
                mod.getEntityTracker().itemDropped(Items.ENDER_EYE))) {
            return null;
        }
        if (mod.getItemStorage().getItemCount(Items.ENDER_EYE) < baseline && nearDrop(mod)) {
            pickupTask = new PickupDroppedItemTask(Items.ENDER_EYE, baseline);
            Debug.logInternal("eyes: drop at " + describePos(mod.getEntityTracker().getClosestItemDrop(Items.ENDER_EYE)
                    .map(d -> new double[]{d.getX(), d.getY(), d.getZ()}).orElse(null)) + ", walking to it");
            _pickupTimer.reset();
            return pickupTask;
        }
        boolean back = mod.getItemStorage().getItemCount(Items.ENDER_EYE) >= baseline;
        Debug.logInternal("eyes: drop " + (back ? "already picked up" : "none within " + (int) PICKUP_RANGE));
        if (!back) {
            Debug.logInternal("eyes: raw eye items in the world " + rawEyeItems(mod));
        }
        return endCollect(mod, baseline, back ? "back" : "shattered or never seen");
    }

    // one line per throw: what we held going in, which throw it is, and where the guess stands
    private String throwLine(AltoClef mod) {
        String est = estimator.estimate().map(e -> " est=" + Math.round(e.x()) + "," + Math.round(e.z()) + " dist="
                + Math.round(Math.hypot(e.x() - mod.getPlayer().getX(), e.z() - mod.getPlayer().getZ()))
                + " radius=" + Math.round(e.radius())).orElse(" est=none");
        return "eyes: throw #" + (ledger.counted() + 1) + " held=" + ctx.state().eyeBaseline + est;
    }

    // the eye is back, or it shattered, or we gave up on it: nothing left to collect
    private Task endCollect(AltoClef mod, int baseline, String how) {
        Debug.logInternal("eyes: collect over, " + how + " (held " + mod.getItemStorage().getItemCount(Items.ENDER_EYE)
                + ", before the throw " + baseline + ")");
        ctx.state().eyeBaseline = 0;
        enter(mod, Stage.DECIDE);
        return null;
    }

    private static String describePos(double[] p) {
        return p == null ? "none" : Math.round(p[0]) + "," + Math.round(p[1]) + "," + Math.round(p[2]);
    }

    // every ender eye item the client knows of, grounded or not, past the tracker's filters: a lost throw with one of these in it
    // is a pickup we missed, an empty list is a real shatter (debug: losses well over the 20% break rate need telling apart)
    private String rawEyeItems(AltoClef mod) {
        StringBuilder out = new StringBuilder();
        for (net.minecraft.world.entity.Entity e : mod.getWorld().entitiesForRendering()) {
            if (e instanceof net.minecraft.world.entity.item.ItemEntity item && item.getItem().is(Items.ENDER_EYE)) {
                out.append(" [").append(describePos(new double[]{e.getX(), e.getY(), e.getZ()}))
                        .append(" ground=").append(e.onGround()).append(" d=").append(Math.round(e.distanceTo(mod.getPlayer())))
                        .append("]");
            }
        }
        return (out.length() == 0 ? "none" : out.toString()) + ", last eye " + describePos(lastEyePos);
    }

    private boolean nearDrop(AltoClef mod) {
        return mod.getEntityTracker().getClosestItemDrop(Items.ENDER_EYE)
                .map(e -> e.distanceTo(mod.getPlayer()) <= PICKUP_RANGE).orElse(false);
    }

    // a dud throw: breathe a second before the next one
    private Task pause(AltoClef mod) {
        setDebugState("Waiting before the next throw", step);
        if (seconds(mod) - stageSince >= 1.0) {
            enter(mod, Stage.DECIDE);
        }
        return null;
    }

    private void persist() {
        RunState state = ctx.state();
        List<RunState.Ray> out = new ArrayList<>();
        for (StrongholdEstimator.Ray r : estimator.rays()) {
            RunState.Ray s = new RunState.Ray();
            s.ox = r.ox();
            s.oz = r.oz();
            s.dx = r.dx();
            s.dz = r.dz();
            s.dived = r.dived();
            out.add(s);
        }
        state.strongholdRays = out;
        estimator.estimate().ifPresent(e -> {
            state.strongholdEstimate = new RunState.Pos((int) Math.round(e.x()), 0, (int) Math.round(e.z()));
            state.strongholdRadius = e.radius();
        });
        ctx.save();
    }
}
