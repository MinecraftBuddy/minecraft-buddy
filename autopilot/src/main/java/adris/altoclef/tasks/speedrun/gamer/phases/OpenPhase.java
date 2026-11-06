package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.movement.GetWithinRangeOfBlockTask;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.PhaseHandler;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.SmeltSurface;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasks.speedrun.gamer.tasks.FillPortalFramesTask;
import adris.altoclef.tasks.speedrun.gamer.tasks.PearlHuntTask;
import adris.altoclef.tasks.speedrun.gamer.tasks.StrongholdScan;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.world.FrameGeometry;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;
import java.util.Optional;

// put an eye in every empty frame. the portal opens by itself on the 12th, we only notice it
public class OpenPhase implements PhaseHandler {
    private FillPortalFramesTask fillTask;
    private Task craftTask;
    private int craftTarget = -1;
    private boolean readAll;
    private Task toRing;
    // the walk to the ring is not the budget's business (the ring can be a thousand blocks from the portal), see holdsBudget
    private boolean walking;
    private boolean readLogged;
    // the overworld pearl top-up (OpenTopUp): the hunt, the surface spot over the ring it works from, the last enderman seen
    private PearlHuntTask hunt;
    private int huntTotal;
    private boolean huntOn;
    private static final long CLIMB_TICKS = 90 * 20;
    private static final int CLIMB_DEPTH = 6;
    private Task climb;
    private long climbStart = -1;
    private long endermanSeenAt = -1;
    private String hudState = "Opening the End portal";

    @Override
    public GamerPhase phase() {
        return GamerPhase.OPEN;
    }

    @Override
    public String hud() {
        return GamerPhase.OPEN.hud();
    }

    @Override
    public String hudState() {
        return hudState;
    }

    @Override
    public boolean isDone(GamerFacts facts, RunState state, GamerConfig cfg) {
        return state.endPortalOpened;
    }

    @Override
    public Optional<GamerPhase> regressTo(GamerFacts facts, RunState state, GamerConfig cfg) {
        return StrongholdRules.regressForOpen(facts, state);
    }

    @Override
    public void onEnter(AltoClef mod, GamerContext ctx) {
        // the end prep phase turns this back on when it is time to walk in
        ctx.walkOnEndPortal(false);
        fillTask = null;
        craftTask = null;
        craftTarget = -1;
        readAll = false;
        toRing = null;
        walking = false;
        readLogged = false;
        hunt = null;
        huntOn = false;
        climb = null;
        climbStart = -1;
        endermanSeenAt = -1;
        // a stamp from the last visit would read as eyes missing for ages
        ctx.state().openNoEyesSince = 0;
    }

    @Override
    public void onExit(AltoClef mod, GamerContext ctx) {
        walking = false;
        huntOn = false;
    }

    // the walk back to the ring after a nether trip, and the pearl hunt, have their own limits (the stall timer, OpenTopUp.CAP_TICKS)
    @Override
    public boolean holdsBudget() {
        return walking || huntOn;
    }

    // a night must not send us to bed in the middle of the hunt, night is when the endermen are about
    @Override
    public boolean nightHold() {
        return huntOn;
    }

    @Override
    public Task tick(AltoClef mod, GamerContext ctx) {
        RunState state = ctx.state();
        if (state.endPortalCenter == null) {
            ctx.fail("no end portal ring known, nothing to open");
            return null;
        }
        int[] centre = {state.endPortalCenter.x, state.endPortalCenter.y, state.endPortalCenter.z};
        boolean loaded = refresh(mod, ctx, centre);
        readAll |= loaded;
        walking = !loaded;
        hudState = "Putting in Eyes of Ender (" + state.framesFilled + " of " + FrameGeometry.FRAME_COUNT + ")";
        // before the open check: a ring that was already open on arrival still counts as read
        if (loaded && !readLogged) {
            GamerFacts f = ctx.facts();
            readLogged = true;
            state.openRead = true;
            ctx.log("open: read the ring, frames filled " + state.framesFilled + ", eyes held " + f.count(Items.ENDER_EYE)
                    + ", craftable " + StrongholdRules.craftableEyes(f) + ", needed " + (FrameGeometry.FRAME_COUNT - state.framesFilled));
            ctx.save();
        }
        if (state.endPortalOpened) {
            StrongholdScan.swordAfterEye(mod);
            return null;
        }
        GamerFacts facts = ctx.facts();
        // framesFilled starts at 0, so until one full read of the ring "short of eyes" is a guess
        if (readAll) {
            trackShortage(facts, state);
        }
        // a hunt that is running goes on when the ring chunks unload (it wanders), framesFilled is the last full read
        Task topUp = loaded || OpenTopUp.running(state) ? topUp(mod, ctx, facts, centre) : null;
        if (topUp != null) {
            return topUp;
        }
        if (readAll && StrongholdRules.starvedForGood(facts, state)) {
            ctx.fail("not enough Eyes of Ender for the empty frames and no way to make more");
            return null;
        }
        if (!loaded) {
            // frames only read as frames once their chunk is loaded, so go stand by the ring before judging anything
            if (toRing == null) {
                toRing = new GetWithinRangeOfBlockTask(new BlockPos(centre[0], centre[1], centre[2]), 4);
            }
            return toRing;
        }
        int eyes = facts.count(Items.ENDER_EYE);
        int need = FrameGeometry.FRAME_COUNT - state.framesFilled;
        if (eyes == 0 && need > 0 && StrongholdRules.craftableEyes(facts) > 0) {
            return craft(mod, Math.min(StrongholdRules.craftableEyes(facts), need));
        }
        if (eyes == 0) {
            StrongholdScan.swordAfterEye(mod);
            return null;
        }
        if (fillTask == null) {
            fillTask = new FillPortalFramesTask(state.endPortalCenter, ctx.cfg().stronghold.breakSilverfishSpawner);
        }
        return fillTask;
    }

    // frames filled and portal state straight from the blocks, only when all 12 are loaded (else the count would dip)
    private boolean refresh(AltoClef mod, GamerContext ctx, int[] centre) {
        RunState state = ctx.state();
        List<int[]> ring = FrameGeometry.positions(centre);
        for (int[] p : ring) {
            if (!StrongholdScan.isFrame(mod, p)) {
                // a frame that is not there (unloaded or something is off): keep what we knew
                if (StrongholdScan.portalIsOpen(mod, centre)) {
                    state.endPortalOpened = true;
                    state.framesFilled = FrameGeometry.FRAME_COUNT;
                    ctx.save();
                }
                return false;
            }
        }
        int filled = FrameGeometry.filledCount(centre, p -> StrongholdScan.frameHasEye(mod, p));
        if (filled != state.framesFilled) {
            state.framesFilled = filled;
            ctx.progress("eye placed");
        }
        if (StrongholdScan.portalIsOpen(mod, centre)) {
            state.endPortalOpened = true;
            ctx.log("the End portal is open");
            ctx.save();
        }
        return true;
    }

    // a few pearls short with the powder in the bag: hunt endermen on the surface over the ring instead of the nether trip.
    // one hunt per run, it ends when the frames are covered or OpenTopUp.over says so, and then the regress rule has its say
    private Task topUp(AltoClef mod, GamerContext ctx, GamerFacts facts, int[] centre) {
        RunState state = ctx.state();
        huntOn = false;
        if (state.openTopUpDone || state.endPortalOpened) {
            return null;
        }
        int need = FrameGeometry.FRAME_COUNT - state.framesFilled;
        int eyes = facts.count(Items.ENDER_EYE);
        int pearls = facts.count(Items.ENDER_PEARL);
        long now = facts.gameTime();
        if (!OpenTopUp.running(state)) {
            int hold = OpenTopUp.pearlsToHold(need, eyes, pearls, facts.count(Items.BLAZE_POWDER), facts.count(Items.BLAZE_ROD));
            if (hold == 0) {
                return null;
            }
            state.openTopUpStart = Math.max(1, now);
            ctx.log("open: short " + (need - eyes - StrongholdRules.craftableEyes(facts)) + " eyes with the powder for them, hunting endermen up top (eyes "
                    + eyes + ", pearls " + pearls + ", powder " + facts.count(Items.BLAZE_POWDER) + ", rods " + facts.count(Items.BLAZE_ROD) + ")");
            ctx.save();
        }
        if (endermanSeenAt < 0 || mod.getEntityTracker().entityFound(Enderman.class)) {
            endermanSeenAt = now;
        }
        String ending = null;
        if (!StrongholdRules.starved(facts, state)) {
            ending = "the frames are covered";
        } else if (OpenTopUp.over(now - state.openTopUpStart, mod.getWorld().isBrightOutside(), now - endermanSeenAt)) {
            ending = "gave up";
        }
        if (ending != null) {
            state.openTopUpDone = true;
            ctx.log("open: pearl top-up over, " + ending + " (eyes " + eyes + ", pearls " + pearls + ", took " + (now - state.openTopUpStart) / 20 + " s)");
            ctx.save();
            return null;
        }
        huntOn = true;
        // pearls that came in become eyes right away, the hunt only chases what is still missing
        int craftable = StrongholdRules.craftableEyes(facts);
        if (craftable > 0) {
            return craft(mod, eyes + Math.min(craftable, need - eyes));
        }
        // the stronghold is underground, endermen are not: climb to the sky over the ring first (the hunt's own walk only
        // counts flat distance as progress), with a clock so a blocked climb does not eat the whole top-up
        if (climbStart < 0) {
            climbStart = now;
        }
        if (now - climbStart < CLIMB_TICKS && SmeltSurface.depthBelowSky(mod) > CLIMB_DEPTH) {
            if (climb == null) {
                int y = mod.getWorld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, centre[0], centre[2]);
                climb = new GetWithinRangeOfBlockTask(new BlockPos(centre[0], y, centre[2]), 3);
            }
            hudState = "Climbing out to hunt endermen";
            return climb;
        }
        if (hunt == null || huntTotal != need - eyes) {
            huntTotal = need - eyes;
            hunt = new PearlHuntTask(huntTotal, Dimension.OVERWORLD, () -> null, () -> ctx.progress("enderman down"));
        }
        hudState = hunt.step();
        return hunt;
    }

    // pure rule needs a start time for the shortage, this keeps it (0 = no shortage)
    private void trackShortage(GamerFacts facts, RunState state) {
        if (StrongholdRules.starved(facts, state)) {
            if (state.openNoEyesSince == 0) {
                state.openNoEyesSince = Math.max(1, facts.gameTime());
            }
        } else {
            state.openNoEyesSince = 0;
        }
    }

    // top up from pearls + powder in the bag. cached per target so the child is not rebuilt every tick
    private Task craft(AltoClef mod, int count) {
        if (craftTask == null || craftTarget != count) {
            craftTarget = count;
            craftTask = TaskCatalogue.getItemTask(Items.ENDER_EYE, count);
        }
        hudState = "Making Eyes of Ender";
        return craftTask;
    }
}
