package adris.altoclef.tasks.speedrun.gamer.phases;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.RunAwayFromPositionTask;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.GamerPhase;
import adris.altoclef.tasks.speedrun.gamer.PhaseHandler;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.Optional;

// back through the portal we came in by. DefaultGoToDimensionTask does the real work (last used portal, else builds one
// from obsidian). what it cannot do: remember the portal over a relog (RunState does, we walk there first), relight a
// portal the ghasts blew out (the frame is still standing and we hold flint and steel), and notice that building a new
// one in the Nether is hopeless (it asks the catalogue for obsidian, which wants a diamond pickaxe in the overworld,
// which sends it back here, one task deeper every tick). there is no clock of our own: the engine's budget and stall
// timer already say "never gets anywhere", a slow but healthy trip from a far fortress must not be killed by one
public class ReturnPhase implements PhaseHandler {
    // close enough that the portal blocks are loaded and the tracker can take over
    private static final double PORTAL_NEAR_BLOCKS = 8;
    // a portal needs ten obsidian (the frame is 4x5 minus corners, two of them are already there when we build from a ruin)
    private static final int OBSIDIAN_FOR_PORTAL = 10;
    private static final int STEP_OFF_BLOCKS = 6;

    enum Step {
        // portal known, let the default task walk and enter
        DEFAULT,
        // we know where home is but the game does not
        WALK_HOME,
        // frame standing, portal dark
        RELIGHT,
        // no portal, no frame, no obsidian: say so now
        FAIL
    }

    private Task goOverworld;
    private GetToBlockTask walkToPortal;
    private RunState.Pos walkingTo;
    private InteractWithBlockTask relight;
    private BlockPos relightAt;
    private Task stepOff;
    private boolean failedAlready;
    // written by tick, read by isDone: the tick the dimension flips we are still standing in the portal
    private boolean standingInPortal;
    private String hudState;

    @Override
    public GamerPhase phase() {
        return GamerPhase.RETURN;
    }

    @Override
    public String hud() {
        return GamerPhase.RETURN.hud();
    }

    @Override
    public String hudState() {
        return hudState;
    }

    // in the overworld AND off the portal block: the first eye throw of the next phase takes seconds and standing in
    // a portal that long sends us straight back
    @Override
    public boolean isDone(GamerFacts facts, RunState state, GamerConfig cfg) {
        return facts.dimension() == Dimension.OVERWORLD && !standingInPortal;
    }

    @Override
    public void onEnter(AltoClef mod, GamerContext ctx) {
        goOverworld = new DefaultGoToDimensionTask(Dimension.OVERWORLD);
        walkToPortal = null;
        walkingTo = null;
        relight = null;
        relightAt = null;
        stepOff = null;
        failedAlready = false;
        standingInPortal = false;
        hudState = null;
    }

    @Override
    public Task tick(AltoClef mod, GamerContext ctx) {
        if (goOverworld == null) {
            onEnter(mod, ctx);
        }
        standingInPortal = WorldHelper.isStandingInPortal(mod);
        recordPortal(mod, ctx.state());
        if (ctx.facts().dimension() == Dimension.OVERWORLD) {
            return stepOffPortal(mod);
        }
        return switch (decide(mod, ctx)) {
            case WALK_HOME -> walkHome(ctx.state());
            case RELIGHT -> relightPortal(ctx.state());
            case FAIL -> fail(ctx);
            default -> through();
        };
    }

    private Task through() {
        hudState = "Going through the portal";
        return goOverworld;
    }

    private Task stepOffPortal(AltoClef mod) {
        if (!standingInPortal) {
            stepOff = null;
            return null;
        }
        if (stepOff == null) {
            stepOff = new RunAwayFromPositionTask(STEP_OFF_BLOCKS, mod.getPlayer().blockPosition());
        }
        hudState = "Stepping off the portal";
        return stepOff;
    }

    private Step decide(AltoClef mod, GamerContext ctx) {
        RunState.Pos home = ctx.state().netherPortal;
        boolean known = mod.getMiscBlockTracker().getLastUsedNetherPortal(Dimension.NETHER).isPresent()
                || mod.getBlockTracker().anyFound(Blocks.NETHER_PORTAL);
        boolean near = home != null && new BlockPos(home.x, home.y, home.z).closerToCenterThan(mod.getPlayer().position(), PORTAL_NEAR_BLOCKS);
        boolean frame = near && mod.getWorld().getBlockState(new BlockPos(home.x, home.y - 1, home.z)).is(Blocks.OBSIDIAN);
        GamerFacts f = ctx.facts();
        return decide(known, home != null, near, frame, f.count(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE) > 0, f.count(Items.OBSIDIAN));
    }

    // pure: what to do about the way home
    static Step decide(boolean portalKnown, boolean haveHome, boolean nearHome, boolean frameStanding, boolean haveLighter, int obsidian) {
        if (portalKnown) {
            return Step.DEFAULT;
        }
        if (!haveHome) {
            return obsidian >= OBSIDIAN_FOR_PORTAL ? Step.DEFAULT : Step.FAIL;
        }
        if (!nearHome) {
            return Step.WALK_HOME;
        }
        if (frameStanding && haveLighter) {
            return Step.RELIGHT;
        }
        return obsidian >= OBSIDIAN_FOR_PORTAL ? Step.DEFAULT : Step.FAIL;
    }

    private Task walkHome(RunState state) {
        RunState.Pos home = state.netherPortal;
        if (!home.equals(walkingTo)) {
            walkingTo = home;
            walkToPortal = new GetToBlockTask(new BlockPos(home.x, home.y, home.z));
        }
        hudState = "Walking back to the portal";
        return walkToPortal;
    }

    // the frame is still there, only the portal blocks are gone: fire on the obsidian under the portal's first block
    private Task relightPortal(RunState state) {
        RunState.Pos home = state.netherPortal;
        BlockPos below = new BlockPos(home.x, home.y - 1, home.z);
        if (relight == null || !below.equals(relightAt)) {
            relightAt = below;
            relight = new InteractWithBlockTask(new ItemTarget(new Item[]{Items.FLINT_AND_STEEL, Items.FIRE_CHARGE}, 1), Direction.UP, below, true);
        }
        hudState = "Lighting the portal again";
        return relight;
    }

    private Task fail(GamerContext ctx) {
        if (!failedAlready) {
            failedAlready = true;
            ctx.fail("the portal is gone and there is no obsidian to build another");
        }
        return null;
    }

    // the nether side portal the game says we last went through. the game's word wins over what we stored: after a
    // rebuilt portal the old position is a lie and walking there is a walk to nowhere
    static void recordPortal(AltoClef mod, RunState state) {
        Optional<BlockPos> used = mod.getMiscBlockTracker().getLastUsedNetherPortal(Dimension.NETHER);
        if (used.isEmpty()) {
            return;
        }
        BlockPos p = used.get();
        RunState.Pos have = state.netherPortal;
        if (have == null || have.x != p.getX() || have.y != p.getY() || have.z != p.getZ()) {
            state.netherPortal = new RunState.Pos(p.getX(), p.getY(), p.getZ());
        }
    }
}
