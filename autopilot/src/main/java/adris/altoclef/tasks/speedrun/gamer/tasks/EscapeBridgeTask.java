package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.CustomBaritoneGoalTask;
import adris.altoclef.tasks.speedrun.gamer.EscapeRoute;
import adris.altoclef.tasks.speedrun.gamer.EscapeRoute.Cell;
import adris.altoclef.tasks.speedrun.gamer.EscapeRoute.Route;
import adris.altoclef.tasksystem.Task;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// walks an EscapeRoute off a portal that floats over nothing: one hop of EscapeHops.HOP_CELLS at a time, each a plain GoalBlock
// so baritone searches straight to it (a far goal only ever got a partial path that never accepted a 1 wide bridge). no wander in
// here, the hops are not allowed to give up by shuffling around on the bridge. a hop that stops getting closer gets the route
// recomputed around its cell, twice, then failed() says the sweep can have us back
public class EscapeBridgeTask extends Task {
    private Route route;
    private int reached = -1;
    private int recomputes;
    private boolean failed;
    private HopTask hop;
    private int[] hopCell;
    private double best = Double.NaN;
    private double since;
    // columns (x, z) a hop got stuck on, the next route stays off them
    private final Set<Long> avoid = new HashSet<>();
    // columns of the bridge we laid: the blocks in them are ours, not ground, or a recompute standing on its own cobble would
    // call that floor and report the escape done
    private final Set<Long> laid = new HashSet<>();
    private boolean holding;
    private long heldTicks;

    public EscapeBridgeTask(Route route) {
        this.route = route;
        noteLaid();
    }

    private void noteLaid() {
        // (not the end: that is the ground we are going to, a recompute must still see it as ground)
        for (int i = 0; i < route.cells().size() - 1; i++) {
            int[] c = route.cells().get(i);
            laid.add(key(c[0], c[2]));
        }
    }

    // standing still for a fireball this tick
    public boolean holding() {
        return holding;
    }

    public boolean failed() {
        return failed;
    }

    public int placements() {
        return route.placements();
    }

    // hops done so far, the phase pings its watchdog off this going up
    public int hopsDone() {
        return reached + 1;
    }

    // on the end cell itself, feet and all: the bridge cell before a step down is close enough to pass a tolerance check
    public boolean done(AltoClef mod) {
        Player p = mod.getPlayer();
        int[] e = route.end();
        int feetY = (int) Math.ceil(p.getY() - 0.01);
        return !failed && p.onGround() && p.getBlockX() == e[0] && p.getBlockZ() == e[2] && feetY == e[1];
    }

    @Override
    protected void onStart(AltoClef mod) {
        // the shield for a ghast ball would skip the punch that deflects it (KillAura), and the bridge is 1 wide either way
        mod.getBehaviour().push();
        mod.getBehaviour().setNoFireballShield(true);
        best = Double.NaN;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        holding = false;
        if (failed || done(mod)) {
            return null;
        }
        Player p = mod.getPlayer();
        double now = mod.getWorld().getGameTime() / 20.0;
        Optional<Entity> ball = mod.getEntityTracker().getClosestEntity(LargeFireball.class);
        // (capped: a ghast that never leaves must not freeze the bridge for good)
        if (ball.isPresent() && EscapeHops.holdForFireball(ball.get().distanceTo(p)) && heldTicks < EscapeHops.MAX_HOLD_SECONDS * 20) {
            // stand still, no placing, and let the chain's punch have it. the stall clock does not run while we wait
            mod.getClientBaritone().getPathingBehavior().requestPause();
            since = now;
            heldTicks++;
            holding = true;
            setDebugState("Holding still for a fireball");
            return null;
        }
        int last = route.cells().size() - 1;
        if (hop == null) {
            int idx = EscapeHops.nextHop(route.cells().size(), reached);
            hopCell = route.cells().get(idx);
            hop = new HopTask(new BlockPos(hopCell[0], hopCell[1], hopCell[2]));
            best = Double.NaN;
        }
        // (the last hop is done() or nothing, a tolerance check there would call the cell before it a landing)
        if (EscapeHops.nextHop(route.cells().size(), reached) < last && atCell(p, hopCell)) {
            reached = EscapeHops.nextHop(route.cells().size(), reached);
            hop = null;
            return null;
        }
        double dist = Math.hypot(hopCell[0] + 0.5 - p.getX(), hopCell[2] + 0.5 - p.getZ());
        if (Double.isNaN(best) || dist <= best - 1) {
            best = dist;
            since = now;
        } else if (now - since >= EscapeHops.STALL_SECONDS) {
            return recompute(mod);
        }
        setDebugState("Bridging off the portal, hop to " + hopCell[0] + "," + hopCell[2]);
        return hop;
    }

    // from wherever we stand now, around the cell that jammed
    private Task recompute(AltoClef mod) {
        avoid.add(key(hopCell[0], hopCell[2]));
        hop = null;
        best = Double.NaN;
        if (++recomputes > EscapeHops.MAX_RECOMPUTES) {
            failed = true;
            return null;
        }
        BlockPos at = mod.getPlayer().blockPosition();
        int held = NetherBlockRestock.heldThrowaways(mod);
        Route next = EscapeRoute.find(terrain(mod, at.getY(), avoid, laid), at.getX(), at.getY(), at.getZ(), EscapeRoute.budget(held));
        if (next == null) {
            failed = true;
            return null;
        }
        route = next;
        noteLaid();
        reached = -1;
        return null;
    }

    // feet inside the cell, give or take the half block the bridge wobbles by
    private static boolean atCell(Player p, int[] c) {
        return Math.abs(p.getX() - (c[0] + 0.5)) < 0.8 && Math.abs(p.getZ() - (c[2] + 0.5)) < 0.8 && Math.abs(p.getY() - c[1]) < 1.5;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return failed || done(mod);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof EscapeBridgeTask task && task.route == route;
    }

    @Override
    protected String toDebugString() {
        return "Bridging off the portal";
    }

    @Override
    protected String toHudString() {
        return "Bridging off the portal";
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    // ---- the world, as EscapeRoute sees it

    public static EscapeRoute.Plan plan(AltoClef mod) {
        BlockPos at = mod.getPlayer().blockPosition();
        return EscapeRoute.plan(terrain(mod, at.getY(), Set.of(), Set.of()), at.getX(), at.getY(), at.getZ(), NetherBlockRestock.heldThrowaways(mod));
    }

    public static boolean lavaLake(AltoClef mod) {
        BlockPos at = mod.getPlayer().blockPosition();
        return EscapeRoute.lavaLakeNoWay(terrain(mod, at.getY(), Set.of(), Set.of()), at.getX(), at.getY(), at.getZ());
    }

    // chunks around us are in, so unloaded cells mean "really nothing" less often than "not here yet"
    public static boolean chunksLoaded(AltoClef mod, int chunks) {
        BlockPos at = mod.getPlayer().blockPosition();
        for (int cx = -chunks; cx <= chunks; cx++) {
            for (int cz = -chunks; cz <= chunks; cz++) {
                if (!mod.getChunkTracker().isChunkLoaded(at.offset(cx * 16, 0, cz * 16))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static EscapeRoute.Terrain terrain(AltoClef mod, int planeY, Set<Long> avoid, Set<Long> laid) {
        Level level = mod.getWorld();
        Map<Long, Boolean> loaded = new HashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        return (x, y, z) -> {
            if (level.isOutsideBuildHeight(y)) {
                return Cell.HAZARD;
            }
            if ((y == planeY || y == planeY + 1) && avoid.contains(key(x, z))) {
                return Cell.HAZARD;
            }
            pos.set(x, y, z);
            long chunk = key(x >> 4, z >> 4);
            if (!loaded.computeIfAbsent(chunk, k -> mod.getChunkTracker().isChunkLoaded(pos))) {
                return Cell.UNLOADED;
            }
            Cell cell = classify(level, pos);
            return cell == Cell.NATURAL && y == planeY - 1 && laid.contains(key(x, z)) ? Cell.ARTIFICIAL : cell;
        };
    }

    static Cell classify(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getFluidState().is(FluidTags.LAVA)) {
            return Cell.LAVA;
        }
        Block b = state.getBlock();
        if (b instanceof BaseFireBlock) {
            return Cell.HAZARD;
        }
        // we spawn inside a portal and can walk through one
        if (state.isAir() || b == Blocks.NETHER_PORTAL) {
            return Cell.AIR;
        }
        // cobble and dirt never generate down here, so they are a bridge some earlier trip laid: walk on it for free, never stop
        // on it. counting it as ground made a dead run's half bridge look like land and skipped the escape on the way back
        if (b == Blocks.OBSIDIAN || b == Blocks.CRYING_OBSIDIAN || b == Blocks.COBBLESTONE || b == Blocks.COBBLED_DEEPSLATE
                || b == Blocks.DIRT) {
            return Cell.ARTIFICIAL;
        }
        if (!state.getFluidState().isEmpty()) {
            return Cell.HAZARD;
        }
        // roots, fungi and the like have no collision: baritone walks through them, so do we
        return state.getCollisionShape(level, pos).isEmpty() ? Cell.AIR : Cell.NATURAL;
    }

    // a block goal with no wander, see the class comment
    private static final class HopTask extends CustomBaritoneGoalTask {
        private final BlockPos target;

        HopTask(BlockPos target) {
            super(false);
            this.target = target;
        }

        @Override
        protected Goal newGoal(AltoClef mod) {
            return new GoalBlock(target);
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof HopTask task && task.target.equals(target);
        }

        @Override
        protected String toDebugString() {
            return "Hopping to " + target.toShortString();
        }
    }
}
