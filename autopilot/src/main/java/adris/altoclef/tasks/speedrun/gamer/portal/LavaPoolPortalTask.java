package adris.altoclef.tasks.speedrun.gamer.portal;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.ClearLiquidTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.construction.PlaceObsidianBucketTask;
import adris.altoclef.tasks.construction.PlaceStructureBlockTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasks.speedrun.gamer.portal.PortalMold.Cast;
import adris.altoclef.tasks.speedrun.gamer.portal.PortalMold.Inv;
import adris.altoclef.tasks.speedrun.gamer.portal.PortalMold.Kind;
import adris.altoclef.tasks.speedrun.gamer.portal.PortalMold.Layout;
import adris.altoclef.tasks.speedrun.gamer.portal.PortalMold.Mat;
import adris.altoclef.tasks.speedrun.gamer.portal.PortalMold.P;
import adris.altoclef.tasks.speedrun.gamer.portal.PortalMold.Step;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.input.Input;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

// the lava pool portal (see PortalMold for the geometry). this class is the hands: it asks PortalMold what the next
// step is from nothing but what the world looks like, does that one step, and asks again. no counters decide what is
// done, so a fight or a snack in the middle just means the next tick re-reads the world and carries on.
//
// it only ever makes the portal faster. anything that goes wrong (no pool, a step that will not happen, the clock)
// ends in failed(), and the phase falls back to the old per block cast. a single bad cell gets that old cast on its own
public class LavaPoolPortalTask extends Task {
    // looking for a pool before we say there is none. the cast wanders for lava better than we do
    private static final int SEARCH_TICKS = 20 * 25;
    // from the first step to the lit portal. the old cast takes about 105 s so this is already generous
    private static final int BUILD_TICKS = 20 * 90;
    // one step that does not change the world for this long is stuck
    private static final int STEP_TICKS = 20 * 14;
    private static final int WAIT_TICKS = 20 * 6;
    private static final int FALLBACK_TICKS = 20 * 45;
    private static final int STRIKES = 3;
    // on the stand and the click still can't reach or can't get its aim right. a second is plenty, the stand was picked so
    // the face is right there
    private static final int REACH_TICKS = 20;
    private static final int BLOCKED_READS = 5;
    // running out of lava, or stepping off a stand that didn't work. the walk gets this long before we look again
    private static final int RETREAT_TICKS = 20 * 5;
    private static final int[][] SIDE_FRAME = {{0, 1, 0}, {0, 2, 0}, {0, 3, 0}, {3, 1, 0}, {3, 2, 0}, {3, 3, 0}, {0, 4, 0}, {3, 4, 0}};

    private final Consumer<String> log;
    private int tick;
    private int searchStart = -1;
    private int buildStart = -1;
    private Layout layout;
    private List<Layout> pending = new ArrayList<>();
    private int pendingAt;
    private long lastScan = -100000;
    private List<P> skins = List.of();
    private int skinIdx;
    private boolean skinBurned;
    private int skinSeenAt = -1;
    private String stage = "";
    private String stepKey = "";
    private int stepSince;
    private int strikes;
    private Cast fallbackCast;
    private int fallbackSince;
    private int cleanupSince = -1;
    private String failReason;
    private boolean finished;
    // why we are giving up, while the loose lava gets scooped back up first
    private String pendingFail;
    private TimeoutWanderTask unstick;
    // the pour or scoop we hand out from a stand. kept as the same object so its click status is the one it really had
    // last tick, a fresh copy has never ticked and always reads CANT_REACH
    private InteractWithBlockTask reachChild;
    private String reachKey = "";
    private int reachLastTick = -1;
    // first tick on the stand for this step and whether its click ever went out. goStand swapping the child in and out
    // doesn't restart this, or a bot that keeps slipping off the stand would never time out
    private String standKey = "";
    private int standSince;
    private boolean reachClicked;
    private int blockedReads;
    private P stepBackTo;
    private int stepBackSince;
    private P retreatTo;
    private int retreatSince;
    private boolean nudging;

    public LavaPoolPortalTask(Consumer<String> log) {
        this.log = log;
    }

    // set once it has given up, and why. the phase reads this and goes back to the cast
    public String failReason() {
        return failReason;
    }

    public boolean failed() {
        return failReason != null;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.LAVA);
        mod.getBehaviour().push();
        mod.getBehaviour().addProtectedItems(Items.WATER_BUCKET, Items.LAVA_BUCKET, Items.BUCKET, Items.FLINT_AND_STEEL, Items.FIRE_CHARGE);
        // the frame and the base are the whole point, nothing gets to path through them
        mod.getBehaviour().avoidBlockBreaking(p -> layout != null && isMade(mod, p));
        // cells that have to stay empty until the cast fills them
        mod.getBehaviour().avoidBlockPlacing(p -> layout != null && isReserved(p));
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.LAVA);
        mod.getBehaviour().pop();
        letGoOfTheNudge(mod);
    }

    private boolean isMade(AltoClef mod, BlockPos p) {
        for (int[] f : PortalMold.FRAME) {
            if (layout.cell(f).equals(toP(p)) && mod.getWorld().getBlockState(p).getBlock() == Blocks.OBSIDIAN) {
                return true;
            }
        }
        return false;
    }

    private boolean isReserved(BlockPos p) {
        P q = toP(p);
        for (int[] f : SIDE_FRAME) {
            if (layout.cell(f).equals(q)) {
                return true;
            }
        }
        return false;
    }

    private static Direction faceOf(int dx, int dy, int dz) {
        for (Direction d : Direction.values()) {
            if (d.getUnitVec3i().getX() == dx && d.getUnitVec3i().getY() == dy && d.getUnitVec3i().getZ() == dz) {
                return d;
            }
        }
        throw new IllegalArgumentException("not a face: " + dx + " " + dy + " " + dz);
    }

    private static P toP(BlockPos p) {
        return new P(p.getX(), p.getY(), p.getZ());
    }

    private static BlockPos toPos(P p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }

    @Override
    protected Task onTick(AltoClef mod) {
        tick++;
        letGoOfTheNudge(mod);
        if (failReason != null || finished) {
            return null;
        }
        // before everything, wandering and the slow cast included: nothing else matters while we're standing in lava
        if (layout != null) {
            LiveTerrain here = new LiveTerrain(mod.getWorld());
            boolean burning = PortalMold.lavaOnUs(here, body(mod));
            Task run = retreat(mod, here, burning);
            if (run != null || burning) {
                setDebugState("getting out of the lava");
                // the time out here isn't the step's fault
                stepKey = "";
                standKey = "";
                return run;
            }
        }
        if (unstick != null && unstick.isActive() && !unstick.isFinished(mod)) {
            setDebugState("shaking loose");
            return unstick;
        }
        if (layout == null) {
            return search(mod);
        }
        if (stepBackTo != null) {
            if (toP(mod.getPlayer().blockPosition()).equals(stepBackTo) || tick - stepBackSince > RETREAT_TICKS) {
                stepBackTo = null;
            } else {
                setDebugState("stepping off the stand to try it again");
                return new GetToBlockTask(toPos(stepBackTo), false);
            }
        }
        if (tick - buildStart > BUILD_TICKS) {
            return giveUp(mod, "ran out of time");
        }
        if (cleanupSince >= 0) {
            return cleanup(mod);
        }
        if (fallbackCast != null) {
            return slowCast(mod);
        }
        LiveTerrain terrain = new LiveTerrain(mod.getWorld());
        P skin = skins.isEmpty() || skinIdx >= skins.size() ? null : skins.get(skinIdx);
        long skinAge = -1;
        if (skin != null && terrain.at(skin.x(), skin.y(), skin.z()) == Mat.WATER) {
            if (skinSeenAt < 0) {
                skinSeenAt = tick;
            }
            skinAge = tick - skinSeenAt;
        } else {
            skinSeenAt = -1;
        }
        Inv inv = new Inv(mod.getItemStorage().hasItem(Items.LAVA_BUCKET), mod.getItemStorage().hasItem(Items.WATER_BUCKET),
                mod.getItemStorage().getItemCount(Items.BUCKET));
        Step step = PortalMold.next(layout, terrain, inv, skin, skinAge);
        noteStage(terrain, step);
        watchdog(mod, step);
        // giving up may have started the loose lava cleanup instead of failing, that runs from the next tick
        if (failReason != null || cleanupSince >= 0) {
            return null;
        }
        setDebugState(step.toString());
        return doStep(mod, step, skin);
    }

    // ---- finding the pool ----

    private Task search(AltoClef mod) {
        if (searchStart < 0) {
            searchStart = tick;
            // we stand still for this part, say so or it looks like the bot fell asleep
            log.accept("portal: scanning for lava lakes");
        }
        if (tick - searchStart > SEARCH_TICKS) {
            return giveUp(mod, "no 4 wide lava pool with a shore nearby");
        }
        LiveTerrain terrain = new LiveTerrain(mod.getWorld());
        if (tick - lastScan > 40 && pendingAt >= pending.size()) {
            lastScan = tick;
            List<P> seeds = new ArrayList<>();
            for (BlockPos p : mod.getBlockTracker().getKnownLocations(Blocks.LAVA)) {
                // nearest first, and plenty: a big pool is mostly the same row over and over
                if (seeds.size() >= 80) {
                    break;
                }
                if (p.distSqr(mod.getPlayer().blockPosition()) < 80 * 80) {
                    seeds.add(toP(p));
                }
            }
            pending = PortalMold.candidates(terrain, seeds, mod.getPlayer().getBlockX(), mod.getPlayer().getBlockZ());
            pendingAt = 0;
        }
        // a couple a tick, the full check runs a pile of water simulations
        for (int i = 0; i < 2 && pendingAt < pending.size(); i++) {
            Layout l = pending.get(pendingAt++);
            if (PortalMold.fits(l, terrain)) {
                layout = l;
                skins = PortalMold.skinSources(l, terrain);
                buildStart = tick;
                P c = l.cell(1, 0, 0);
                log.accept("portal: lava pool row found at " + c.x() + " " + c.y() + " " + c.z() + ", building the mold");
                return null;
            }
        }
        setDebugState("looking for a lava pool");
        return null;
    }

    // ---- one step ----

    private Task doStep(AltoClef mod, Step step, P skin) {
        switch (step.kind()) {
            case DONE -> {
                finished = true;
                log.accept("portal: lit");
                return null;
            }
            case WAIT, SKIN_WAIT -> {
                return null;
            }
            case STUCK -> {
                if (step.cast() != null) {
                    startFallback(mod, step.cast(), step.why());
                    return null;
                }
                return giveUp(mod, step.why());
            }
            case SKIN_SCOOP -> {
                if (!baseDone(mod)) {
                    skinBurned = true;
                }
                return scoop(mod, step, null);
            }
            case WATER_SCOOP, LAVA_SCOOP, LAVA_UNDO -> {
                return scoop(mod, step, step.stand());
            }
            case SKIN_PLACE -> {
                if (skinBurned) {
                    skinBurned = false;
                    skinIdx++;
                    if (skinIdx >= skins.size()) {
                        return giveUp(mod, "the base never turned to obsidian");
                    }
                    return null;
                }
                mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.NONE);
                return new InteractWithBlockTask(new ItemTarget(Items.WATER_BUCKET, 1), Direction.UP, toPos(step.support()), false);
            }
            case LAVA_PLACE, WATER_PLACE -> {
                Task there = goStand(mod, step.stand());
                if (there != null) {
                    return there;
                }
                // a side cast's water goes in straight after its lava, so it has to be in the bag before the lava goes
                // out. the planner already asks for that, this is the hands double checking
                if (step.kind() == Kind.LAVA_PLACE && !step.cast().top && !mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
                    return null;
                }
                if (PortalMold.inTheWay(layout, body(mod), step.cast())) {
                    return nudgeBack(mod, step);
                }
                mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.NONE);
                Item bucket = step.kind() == Kind.LAVA_PLACE ? Items.LAVA_BUCKET : Items.WATER_BUCKET;
                Direction face = faceOf(step.fx(), step.fy(), step.fz());
                return watchReach(mod, step, () -> new InteractWithBlockTask(new ItemTarget(bucket, 1), face, toPos(step.support()), false).stayPut());
            }
            case GUIDE_PLACE -> {
                mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.NONE);
                return new PlaceStructureBlockTask(toPos(step.cell()));
            }
            case GUIDE_BREAK -> {
                Task there = step.stand() == null ? null : goStand(mod, step.stand());
                if (there != null) {
                    return there;
                }
                BlockPos junk = toPos(step.cell());
                // a stray obsidian in the mold is not a guide block, breaking it takes minutes. the cast goes elsewhere
                if (WorldHelper.tooSlowToBreak(mod, junk)) {
                    return giveUp(mod, BuiltInRegistries.BLOCK.getKey(mod.getWorld().getBlockState(junk).getBlock()).getPath()
                            + " in the way at " + junk.toShortString() + ", takes too long to break");
                }
                mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.NONE);
                return new DestroyBlockTask(junk);
            }
            case LIGHT -> {
                mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.NONE);
                return new InteractWithBlockTask(new ItemTarget(new Item[]{Items.FLINT_AND_STEEL, Items.FIRE_CHARGE}, 1),
                        Direction.UP, toPos(step.support()), true);
            }
            default -> {
                return giveUp(mod, "unknown step " + step);
            }
        }
    }

    // the bucket scoop. ClearLiquidTask sets the ray to hit sources itself, and puts a block in the liquid if we have no
    // bucket, which is why the planner never asks for a scoop without one. from a stand it's the same click minus the
    // walking: ClearLiquidTask's own goal is the cell over the liquid, i.e. standing in it
    private Task scoop(AltoClef mod, Step step, P stand) {
        if (mod.getItemStorage().getItemCount(Items.BUCKET) < 1) {
            return giveUp(mod, "nothing to scoop with");
        }
        if (stand == null) {
            return new ClearLiquidTask(toPos(step.cell()));
        }
        Task there = goStand(mod, stand);
        if (there != null) {
            return there;
        }
        mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.SOURCE_ONLY);
        return watchReach(mod, step, () -> new InteractWithBlockTask(new ItemTarget(Items.BUCKET, 1), toPos(step.cell()), false).stayPut());
    }

    // the click from a stand, which never walks (stayPut). if it can't reach or can't aim for REACH_TICKS from the first
    // tick we stood there, that's a strike and we step off and stand again
    private Task watchReach(AltoClef mod, Step step, Supplier<InteractWithBlockTask> make) {
        String key = step.kind() + String.valueOf(step.cell());
        if (!key.equals(standKey)) {
            standKey = key;
            standSince = tick;
            reachClicked = false;
        }
        if (reachChild == null || !key.equals(reachKey) || reachLastTick != tick - 1) {
            reachChild = make.get();
            reachKey = key;
            blockedReads = 0;
        } else {
            // it ran last tick, so this is what it really saw
            InteractWithBlockTask.ClickResponse r = reachChild.getClickStatus();
            reachClicked |= r == InteractWithBlockTask.ClickResponse.CLICK_ATTEMPTED;
            boolean blocked = r == InteractWithBlockTask.ClickResponse.CANT_REACH || reachChild.stillAiming();
            blockedReads = blocked ? blockedReads + 1 : 0;
            // a few in a row, a fresh click spends its first tick or two turning to the face and that's not stuck
            if (blockedReads >= BLOCKED_READS && !reachClicked && tick - standSince >= REACH_TICKS) {
                reachLastTick = -1;
                return reachStrike(mod, step);
            }
        }
        reachLastTick = tick;
        return reachChild;
    }

    // same strikes as the watchdog. the stand gets another go from a step back, a new spot in the cell is often all it
    // takes since baritone parks us wherever in it. the last strike goes where the watchdog's does: the slow cast for
    // this one cell, which only gives the whole mold up if that was one too many
    private Task reachStrike(AltoClef mod, Step step) {
        standKey = "";
        if (strikes + 1 >= STRIKES) {
            if (step.cast() != null) {
                startFallback(mod, step.cast(), "can't reach it from the stand");
                return null;
            }
            strikes++;
            return giveUp(mod, "can't reach " + step + " from its stand");
        }
        strikes++;
        P back = PortalMold.stepBackSpot(layout, new LiveTerrain(mod.getWorld()), layout.local(step.stand()));
        if (back == null) {
            unstick = new TimeoutWanderTask(3);
            return null;
        }
        stepBackTo = back;
        stepBackSince = tick;
        return null;
    }

    // the body pokes into the cell the bucket fills. back off it, away from the frame: looking at the block we click and
    // walking backwards is exactly that
    private Task nudgeBack(AltoClef mod, Step step) {
        setDebugState("backing off the cast cell");
        LookHelper.lookAt(mod, Vec3.atCenterOf(toPos(step.support())));
        mod.getInputControls().hold(Input.MOVE_BACK);
        nudging = true;
        return null;
    }

    private void letGoOfTheNudge(AltoClef mod) {
        if (nudging) {
            nudging = false;
            mod.getInputControls().release(Input.MOVE_BACK);
        }
    }

    // standing in lava: walk to a spot that isn't, and keep walking there until we arrive (or it stops being safe, or it
    // takes too long). no spot means null and the survival chain's lava escape does its thing
    private Task retreat(AltoClef mod, LiveTerrain terrain, boolean burning) {
        if (retreatTo != null) {
            boolean there = toP(mod.getPlayer().blockPosition()).equals(retreatTo);
            if (there || tick - retreatSince > RETREAT_TICKS || !PortalMold.safeToStand(terrain, retreatTo)) {
                retreatTo = null;
            }
        }
        if (retreatTo == null && burning) {
            retreatTo = PortalMold.retreatSpot(layout, terrain, toP(mod.getPlayer().blockPosition()));
            retreatSince = tick;
        }
        return retreatTo == null ? null : new GetToBlockTask(toPos(retreatTo), false);
    }

    private static PortalMold.Box body(AltoClef mod) {
        AABB b = mod.getPlayer().getBoundingBox();
        return new PortalMold.Box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
    }

    // null when we are already on the cell, otherwise the walk there. the top row sights only work from the exact spot
    private Task goStand(AltoClef mod, P stand) {
        if (stand == null) {
            return null;
        }
        BlockPos me = mod.getPlayer().blockPosition();
        if (me.getX() == stand.x() && me.getZ() == stand.z() && Math.abs(me.getY() - stand.y()) <= 1) {
            return null;
        }
        setDebugState("walking to " + stand);
        return new GetToBlockTask(toPos(stand), false);
    }

    private boolean baseDone(AltoClef mod) {
        for (int[] b : PortalMold.BASE) {
            P p = layout.cell(b);
            if (mod.getWorld().getBlockState(toPos(p)).getBlock() != Blocks.OBSIDIAN) {
                return false;
            }
        }
        return true;
    }

    // ---- when a step goes nowhere ----

    private void watchdog(AltoClef mod, Step step) {
        String key = step.kind() + String.valueOf(step.cell());
        if (!key.equals(stepKey)) {
            stepKey = key;
            stepSince = tick;
            return;
        }
        int limit = step.kind() == Kind.WAIT ? WAIT_TICKS : STEP_TICKS;
        if (step.kind() == Kind.SKIN_WAIT || tick - stepSince < limit) {
            return;
        }
        stepSince = tick;
        strikes++;
        Debug.logMessage("portal: " + step + " is not going anywhere (" + strikes + ")");
        if (step.cast() != null && step.kind() != Kind.GUIDE_PLACE) {
            startFallback(mod, step.cast(), "stuck on " + step.kind());
        } else if (strikes >= STRIKES) {
            // through giveUp like every other way out, it scoops the loose lava before the phase walks off
            giveUp(mod, "stuck on " + step);
        } else {
            unstick = new TimeoutWanderTask(3);
        }
    }

    // the old per cell cast for this one frame cell, then back to the mold for the rest
    private void startFallback(AltoClef mod, Cast c, String why) {
        strikes++;
        if (strikes > STRIKES) {
            giveUp(mod, "too many bad casts (" + why + ")");
            return;
        }
        fallbackCast = c;
        fallbackSince = tick;
        log.accept("portal: " + c.name + " fell back to the slow cast (" + why + ")");
    }

    private Task slowCast(AltoClef mod) {
        P t = layout.cell(fallbackCast.t);
        boolean made = mod.getWorld().getBlockState(toPos(t)).getBlock() == Blocks.OBSIDIAN
                && mod.getWorld().getBlockState(toPos(t).above()).getBlock() != Blocks.WATER;
        if (made) {
            fallbackCast = null;
            stepKey = "";
            return null;
        }
        // the slow cast bans its cell when something in it takes too long to break, waiting out the clock after that is pointless
        if (mod.getBlockTracker().unreachable(toPos(t))) {
            return giveUp(mod, fallbackCast.name + " is ruined, the slow cast can't clear it");
        }
        if (tick - fallbackSince > FALLBACK_TICKS) {
            return giveUp(mod, fallbackCast.name + " would not cast even the slow way");
        }
        setDebugState("slow cast " + fallbackCast.name);
        // the mold's other cells may still hold their cast water, the slow cast scoops that before it asks for a bucket
        List<BlockPos> others = new ArrayList<>();
        for (Cast c : PortalMold.CASTS) {
            others.add(toPos(layout.cell(c.t)));
        }
        return new PlaceObsidianBucketTask(toPos(t), others);
    }

    // lava we put out and never cemented is a hazard for the next thing that walks by, scoop it back before leaving
    private Task giveUp(AltoClef mod, String why) {
        if (layout != null && cleanupSince < 0 && strayLava(mod) != null && mod.getItemStorage().getItemCount(Items.BUCKET) > 0) {
            cleanupSince = tick;
            failReason = null;
            pendingFail = why;
            return null;
        }
        failReason = why;
        log.accept("portal: lava pool method failed (" + why + "), going back to the cast");
        return null;
    }

    private Task cleanup(AltoClef mod) {
        Cast stray = strayCast(mod);
        LiveTerrain terrain = new LiveTerrain(mod.getWorld());
        P stand = stray == null ? null : layout.cell(stray.stand);
        // a top cast whose pad is gone has no stand left to use, that one gets the old scoop
        boolean padGone = stand != null && !terrain.at(stand.x(), stand.y() - 1, stand.z()).solid();
        // a stand the lava already spread over is no stand, walking at it just bounces off the retreat until the clock
        // runs out. leave it, the survival chain knows where the lava is
        boolean standGone = stand != null && !padGone && !toP(mod.getPlayer().blockPosition()).equals(stand)
                && !PortalMold.safeToStand(terrain, stand);
        if (stray == null || standGone || tick - cleanupSince > 20 * 8 || mod.getItemStorage().getItemCount(Items.BUCKET) < 1) {
            cleanupSince = -1;
            failReason = pendingFail == null ? "gave up" : pendingFail;
            log.accept("portal: lava pool method failed (" + failReason + "), going back to the cast");
            return null;
        }
        setDebugState("scooping loose lava back up");
        if (padGone) {
            return new ClearLiquidTask(toPos(layout.cell(stray.t)));
        }
        // from the cast's own stand, same as the build's scoops. ClearLiquidTask would walk to the cell over the lava,
        // which is the frame cell next to it. the 8 s clock above is this one's timeout
        Task there = goStand(mod, stand);
        if (there != null) {
            return there;
        }
        mod.getBehaviour().setRayTracingFluidHandling(ClipContext.Fluid.SOURCE_ONLY);
        return new InteractWithBlockTask(new ItemTarget(Items.BUCKET, 1), toPos(layout.cell(stray.t)), false).stayPut();
    }

    private P strayLava(AltoClef mod) {
        Cast c = strayCast(mod);
        return c == null ? null : layout.cell(c.t);
    }

    private Cast strayCast(AltoClef mod) {
        for (Cast c : PortalMold.CASTS) {
            P t = layout.cell(c.t);
            // a flow can't be scooped, waiting on one would eat the whole cleanup clock
            BlockState s = mod.getWorld().getBlockState(toPos(t));
            if (s.is(Blocks.LAVA) && s.getFluidState().isSource()) {
                return c;
            }
        }
        return null;
    }

    // ---- one log line per stage ----

    private void noteStage(LiveTerrain terrain, Step step) {
        String now = switch (step.kind()) {
            case SKIN_PLACE, SKIN_WAIT, SKIN_SCOOP -> "base";
            case GUIDE_PLACE -> step.cast() != null ? step.cast().name : "slab";
            case LIGHT -> "light";
            case DONE -> "done";
            default -> step.cast() != null ? step.cast().name : stage;
        };
        if (step.kind() == Kind.GUIDE_BREAK && step.cast() == null) {
            now = "clear";
        }
        if (now.equals(stage)) {
            return;
        }
        stage = now;
        int made = 0;
        for (Cast c : PortalMold.CASTS) {
            P t = layout.cell(c.t);
            if (terrain.at(t.x(), t.y(), t.z()) == Mat.OBSIDIAN) {
                made++;
            }
        }
        String text = switch (now) {
            case "base" -> "portal: washing the base from the shore";
            case "slab" -> "portal: base done, 2/2 obsidian, putting the slab up";
            case "clear" -> "portal: all 8 cast, taking the slab out";
            case "light" -> "portal: frame done, lighting it";
            case "done" -> null;
            default -> "portal: " + now + " (" + made + "/8 cast)";
        };
        if (text != null) {
            log.accept(text);
        }
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return finished;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof LavaPoolPortalTask;
    }

    @Override
    protected String toHudString() {
        return searching() ? "Scanning for lava lakes" : "Building a portal on a lava pool";
    }

    // no pool picked yet, we're still looking through the lava we know about
    public boolean searching() {
        return layout == null;
    }

    @Override
    protected String toDebugString() {
        return "Lava pool portal " + (layout == null ? "(searching)" : stage);
    }

    // the live level as a PortalMold terrain. unloaded chunks read as rock so nothing gets planned into them
    private static final class LiveTerrain implements PortalMold.Terrain {
        private final ClientLevel level;

        LiveTerrain(ClientLevel level) {
            this.level = level;
        }

        @Override
        public Mat at(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.isLoaded(pos)) {
                return Mat.STONE;
            }
            BlockState s = level.getBlockState(pos);
            if (s.isAir()) {
                return Mat.AIR;
            }
            if (s.is(Blocks.LAVA)) {
                return s.getFluidState().isSource() ? Mat.LAVA : Mat.LAVA_FLOW;
            }
            if (s.is(Blocks.WATER)) {
                return s.getFluidState().isSource() ? Mat.WATER : Mat.WATER_FLOW;
            }
            if (s.is(Blocks.OBSIDIAN)) {
                return Mat.OBSIDIAN;
            }
            if (s.is(Blocks.NETHER_PORTAL)) {
                return Mat.PORTAL;
            }
            if (!s.getFluidState().isEmpty()) {
                // waterlogged things and kelp: wet, not ours to build in
                return Mat.WATER_FLOW;
            }
            if (s.getCollisionShape(level, pos).isEmpty()) {
                return Mat.AIR;
            }
            return Mat.STONE;
        }
    }
}
