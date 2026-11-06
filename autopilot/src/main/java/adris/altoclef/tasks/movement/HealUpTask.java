package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.GhastHole;
import adris.altoclef.util.helpers.LavaShore;
import baritone.pathing.movement.CliffAvoidance;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BlockStateInterface;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;

// the heal-up hold's wheel (RecoverHold): stand still and let the food chain and regen do their thing. never finishes,
// the machine says when it is over. the whole point is that the user task does not get to walk us back to the skeletons
public class HealUpTask extends Task {

    // one step, five seconds to make it. same edge test as the fight's rim step (LavaShore), no distance of our own
    private static final int STEP_TICKS = 100;

    // the hold is a final instance in three places, so onStart is the reset. a hold that comes back after the lava escape
    // chain took over goes through onStart again, which is also why it gets a fresh step
    private int stepTicks;
    private BlockPos stepCell;
    // the ghast hole, picked once per hold like stepCell: the feet cell at the bottom, and the same five second budget
    private int holeTicks;
    private BlockPos holeCell;
    // the last ghast ball hit, read off the damage source every tick by MobDefenseChain (same idea as CombatBrain.rangedSource,
    // but the game forgets the source after 40 ticks and the hit that sent us into the hold is usually older). static because
    // there is one of us, and the clock says when it is stale
    private static DamageSource ghastSeen;
    private static long ghastHitAt = Long.MIN_VALUE / 2;
    // whose hit it is: a portal, a respawn or a new world is a new level or a new player, and an old hit is not this one's
    private static Object ghastLevel;
    private static Object ghastPlayer;

    @Override
    protected void onStart(AltoClef mod) {
        stepTicks = 0;
        stepCell = null;
        holeTicks = 0;
        holeCell = null;
        stopWalking(mod);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        Task step = stepOffEdge(mod);
        // our own step is a path: cancelling it every tick would be the hold fighting itself
        if (step != null) return step;
        Task hole = hideFromGhast(mod);
        if (hole != null) return hole;
        // the user task's path can still be going when we take over (or its search about to land), and a stand still that
        // walks is not one
        var pathing = mod.getClientBaritone().getPathingBehavior();
        if (pathing.isPathing() || pathing.getInProgress().isPresent()) stopWalking(mod);
        return null;
    }

    // standing on a lava rim to heal is how a magma cube bite becomes a death, so one bounded step off it first
    private Task stepOffEdge(AltoClef mod) {
        if (stepTicks >= STEP_TICKS) return null;
        var us = mod.getPlayer();
        BlockPos here = us.blockPosition();
        if (stepCell == null) {
            int[] s = LavaShore.edgeStep(CliffAvoidance.of(mod.getClientBaritone().bsi), us.getX(), us.getY(), us.getZ());
            if (s != null) stepCell = here.offset(s[0], 0, s[1]);
        }
        // in the block is not off the rim yet: the cell's near half can still be inside the edge radius
        boolean arrived = here.equals(stepCell) && !LavaShore.edgeNear(CliffAvoidance.of(mod.getClientBaritone().bsi), us.getX(), us.getY(), us.getZ());
        if (!wantsStep(stepCell != null, arrived, stepTicks)) {
            // fine where we are, nowhere better, or there: done for this hold
            stepTicks = STEP_TICKS;
            return null;
        }
        stepTicks++;
        return new GetToBlockTask(stepCell);
    }

    // healing in a ghast's sight is standing still for its next ball, so get under the ground first. no cover search and no
    // dodging, one hole and then the same stand still as always
    private Task hideFromGhast(AltoClef mod) {
        if (holeTicks >= STEP_TICKS) return null;
        LocalPlayer us = mod.getPlayer();
        long now = mod.getWorld().getGameTime();
        // baritone's feet, not blockPosition: on soul sand that one is the soul sand, and the hole would be one block off
        BlockPos f = mod.getClientBaritone().getPlayerContext().playerFeet();
        BlockPos here = new BlockPos(f.getX(), f.getY(), f.getZ());
        if (holeCell == null) {
            // nothing shooting at us: ask again next tick, a ghast can show up halfway through the meal
            if (!GhastHole.hideWanted(ghastInSight(mod, us), now - ghastHitAt)) return null;
            holeCell = pickHole(mod.getClientBaritone().bsi, here);
        }
        if (!wantsStep(holeCell != null, here.equals(holeCell), holeTicks)) {
            // no safe column here, or we are in it: stand as before for the rest of the hold
            holeTicks = STEP_TICKS;
            return null;
        }
        holeTicks++;
        return new GetToBlockTask(holeCell);
    }

    public static void noteGhastHit(LocalPlayer us, long now) {
        if (us != ghastPlayer || us.level() != ghastLevel || now < ghastHitAt) {
            ghastPlayer = us;
            ghastLevel = us.level();
            ghastSeen = null;
            ghastHitAt = Long.MIN_VALUE / 2;
        }
        DamageSource source = us.getLastDamageSource();
        if (source == null || source == ghastSeen) return;
        ghastSeen = source;
        // the ball itself or its blast (both carry the ghast as the owner)
        if (source.getDirectEntity() instanceof LargeFireball || source.getEntity() instanceof Ghast) ghastHitAt = now;
    }

    private static boolean ghastInSight(AltoClef mod, LocalPlayer us) {
        try {
            for (Ghast ghast : mod.getEntityTracker().getTrackedEntities(Ghast.class)) {
                // the ray only for the ones close enough to charge at all
                if (ghast.isAlive() && us.distanceTo(ghast) < GhastHole.SIGHT_RANGE && ghast.hasLineOfSight(us)) return true;
            }
        } catch (ConcurrentModificationException ignored) {
            // one tick of stale ghasts is fine
        }
        return false;
    }

    // our own cell first (no walking at all, just dig), then the four next to it. the bottom feet cell, or null
    private static BlockPos pickHole(BlockStateInterface bsi, BlockPos here) {
        if (columnOk(bsi, here)) return here.below(GhastHole.DEPTH);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos next = here.relative(d);
            // a neighbour we cannot stand in is a wall to dig through first, and its lava check is not done
            if (open(bsi, next) && open(bsi, next.above()) && columnOk(bsi, next)) return next.below(GhastHole.DEPTH);
        }
        return null;
    }

    private static boolean columnOk(BlockStateInterface bsi, BlockPos top) {
        if (!bsi.isLoaded(top.getX(), top.getZ())) return false;
        List<GhastHole.Cell> dug = new ArrayList<>(GhastHole.DEPTH);
        for (int i = 1; i <= GhastHole.DEPTH; i++) dug.add(cell(bsi, top.below(i)));
        return GhastHole.columnOk(dug, cell(bsi, top.below(GhastHole.DEPTH + 1)));
    }

    // a full cube, or soul sand (not a full cube, digs and holds us just fine). fire, cobwebs and ladders are none of that
    private static GhastHole.Cell cell(BlockStateInterface bsi, BlockPos p) {
        int x = p.getX(), y = p.getY(), z = p.getZ();
        BlockState state = bsi.get0(x, y, z);
        boolean liquid = MovementHelper.isLiquid(state);
        boolean solid = !liquid && (MovementHelper.isBlockNormalCube(state) || state.getBlock() == Blocks.SOUL_SAND);
        boolean beside = false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            beside |= MovementHelper.isLiquid(bsi.get0(x + d.getStepX(), y, z + d.getStepZ()));
        }
        return new GhastHole.Cell(solid, liquid, state.getBlock() instanceof FallingBlock, state.getBlock() == Blocks.MAGMA_BLOCK, beside);
    }

    private static boolean open(BlockStateInterface bsi, BlockPos p) {
        BlockState state = bsi.get0(p.getX(), p.getY(), p.getZ());
        return !MovementHelper.isLiquid(state) && MovementHelper.canWalkThrough(bsi, p.getX(), p.getY(), p.getZ(), state);
    }

    // the soft cancel: a movement that is not safe to stop (a fall, a bucket) finishes first, same as everyone else
    private static void stopWalking(AltoClef mod) {
        mod.getClientBaritone().getPathingBehavior().cancelEverything();
    }

    // step while there is a cell to go to, we are not in it yet, and the budget is not spent
    static boolean wantsStep(boolean haveCell, boolean arrived, int ticksUsed) {
        return haveCell && !arrived && ticksUsed < STEP_TICKS;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof HealUpTask;
    }

    @Override
    protected String toHudString() {
        return "Healing up before going back in";
    }

    @Override
    protected String toDebugString() {
        return "Healing up before going back in";
    }
}
