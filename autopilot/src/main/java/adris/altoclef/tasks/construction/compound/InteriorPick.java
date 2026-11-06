package adris.altoclef.tasks.construction.compound;

import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.LookHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

// which portal interior cell the two builders clear next. both used to take the first cell that wasn't air and sit on it
// until it was, sight or no sight, so a ring cell in the way of an inside one (or a cell nobody can get a ray to) meant
// staring at it forever
final class InteriorPick {
    // how long the cell we're on can go without a ray before one that has a ray wins. not one tick: ReachHold flickers,
    // and every switch is a fresh DestroyBlockTask with its stall strikes back at zero, so switching on every flicker
    // would be a brand new way to never finish
    static final int NO_REACH_SWITCH_TICKS = 20;
    // and how many of those switches one build gets. a cell can lose its ray for good reasons (stepping off it, wading
    // out of the water to swing dry), and two cells that each send us somewhere the other one has the ray would trade
    // places forever with every stall clock reset on the way. a ban still moves us on after this
    static final int MAX_REACH_SWITCHES = 6;

    private InteriorPick() {
    }

    // the pure part. cells in the order they're listed, current is the cell we're on (null for none), noReachTicks how
    // long it has gone without a ray and reachSwitches how many times we already moved off a cell for that. null when
    // everything is air
    static <T> T pick(List<T> cells, Predicate<T> isAir, Predicate<T> banned, Predicate<T> hasReach, T current, int noReachTicks, int reachSwitches) {
        if (current == null || isAir.test(current)) {
            return fresh(cells, isAir, banned, hasReach);
        }
        boolean currentBanned = banned.test(current);
        boolean stale = noReachTicks >= NO_REACH_SWITCH_TICKS && reachSwitches < MAX_REACH_SWITCHES;
        if (!currentBanned && !stale) {
            return current;
        }
        T better = fresh(cells, isAir, banned, hasReach);
        if (better == null || better.equals(current) || banned.test(better)) {
            // everything else is banned too, hopping between banned cells fixes nothing
            return current;
        }
        if (currentBanned || hasReach.test(better)) {
            return better;
        }
        // stale, but nothing else has a ray either. a different cell to walk to is just a reset stall clock
        return current;
    }

    // first cell with a ray that isn't banned, else the first that isn't banned, else the first, all in list order
    private static <T> T fresh(List<T> cells, Predicate<T> isAir, Predicate<T> banned, Predicate<T> hasReach) {
        T firstAny = null;
        T firstFree = null;
        for (T cell : cells) {
            if (isAir.test(cell)) {
                continue;
            }
            if (firstAny == null) {
                firstAny = cell;
            }
            if (banned.test(cell)) {
                continue;
            }
            if (hasReach.test(cell)) {
                return cell;
            }
            if (firstFree == null) {
                firstFree = cell;
            }
        }
        return firstFree != null ? firstFree : firstAny;
    }

    // the world side, same for both builders
    static BlockPos pick(AltoClef mod, BlockPos origin, Vec3i[] interior, Predicate<BlockPos> isAir, BlockPos current, int noReachTicks, int reachSwitches) {
        return pick(mod, origin, interior, interior.length, isAir, current, noReachTicks, reachSwitches);
    }

    // vanilla only wants the cells in the portal's own plane to be air to light it, the ring round them never blocks the
    // fire. once every ring cell has been seen clear once, only the first inPlane cells may stop the flint (a ring cell
    // that refills afterwards, lava cobble and friends, is not worth a restart). no timer, no counter
    static int preemptCount(int total, int inPlane, boolean ringSeenClear) {
        return ringSeenClear ? Math.min(inPlane, total) : total;
    }

    // notes every ring cell (past the first inPlane ones) that is air right now into seen, true once all of them have
    // been air at some point. they don't have to be air together, clearing one can let another refill
    static <T> boolean ringSeenClear(List<T> cells, int inPlane, java.util.Set<T> seen, Predicate<T> isAir) {
        for (int i = inPlane; i < cells.size(); i++) {
            if (isAir.test(cells.get(i))) {
                seen.add(cells.get(i));
            }
        }
        return seen.size() >= cells.size() - inPlane;
    }

    // the first count entries of interior are the ones in the running
    static BlockPos pick(AltoClef mod, BlockPos origin, Vec3i[] interior, int count, Predicate<BlockPos> isAir, BlockPos current, int noReachTicks, int reachSwitches) {
        List<BlockPos> cells = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            cells.add(origin.offset(interior[i]));
        }
        return pick(cells, isAir, mod.getBlockTracker()::unreachable, p -> reachNow(mod, p), current, noReachTicks, reachSwitches);
    }

    // a ray to it from where we stand. fluids come out with a bucket, a ray to a lava cell isn't the question, so they
    // always say yes and keep the old order. far cells skip the ray casts, there are 18 of these
    static boolean reachNow(AltoClef mod, BlockPos pos) {
        if (ConstructNetherPortalBucketTask.isLiquid(mod.getWorld().getBlockState(pos))) {
            return true;
        }
        double reach = mod.getClientBaritone().getPlayerContext().playerController().getBlockReachDistance() + 1;
        if (mod.getPlayer().getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > reach * reach) {
            return false;
        }
        return LookHelper.getReach(pos).isPresent();
    }
}
