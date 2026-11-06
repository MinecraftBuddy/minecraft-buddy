package adris.altoclef.tasks.speedrun.gamer.tasks;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

// the pearl hunt's walk when nothing is worth chasing: a known warped nylium block near the forest, then another one. the
// loot task's own wander heads for unloaded chunks hundreds of blocks out, which is the opposite of where the endermen live.
// one goal at a time, latched: a new one only on arrival, a stall or the spot going bad
final class PearlRoam {
    // the leash, flat from the forest block we were sent to
    static final double LEASH_BLOCKS = 64;
    // a spot right next to us is no walk at all, it would be "arrived" on the tick it was picked
    static final double MIN_STEP_BLOCKS = 8;
    // downhill is how the falls into the lava sea happen (same idea as ACQUIRE_BELOW_BLOCKS)
    static final double BELOW_BLOCKS = 6;
    static final double ARRIVE_BLOCKS = 3;
    // no step closer by 2 blocks for 20 s of roaming is a goal the path can't reach
    static final int STALL_TICKS = 400;
    static final double PROGRESS_BLOCKS = 2;
    // four minutes of roaming with nothing to chase: the forest is dead, the old explore takes it from here
    static final int IDLE_TICKS = 4 * 60 * 20;
    // a failed pick waits this long before the known blocks get combed again
    static final int RETRY_TICKS = 100;
    // random picks per try, each one costs a lava scan
    static final int PICK_TRIES = 24;
    // the spot is asked again once a second, the lava next to it does not move faster than that
    static final int RECHECK_TICKS = 20;
    private static final int FAILED_CAP = 64;

    private final Random rng = new Random();
    private final Set<BlockPos> failed = new HashSet<>();
    private BlockPos goal;
    private double best = Double.MAX_VALUE;
    private int stall;
    private int idle;
    private int retry;
    private int recheck;

    // roam only when the forest is known and reachable, nothing is being chased or could be, no loot is due from a kill
    // (swapping the loot task out restarts it, and its drop wait goes with it), and no enderman has shown up for a while
    static boolean wanted(boolean hotspotKnown, boolean walkGaveUp, int pursuingId, boolean anyLootable, boolean awaitingDrop, int idleTicks) {
        return hotspotKnown && !walkGaveUp && pursuingId < 0 && !anyLootable && !awaitingDrop && idleTicks <= IDLE_TICKS;
    }

    // inside the leash and far enough from us to be a walk. flat on purpose, the height rule is in spotOk
    static boolean inLeash(BlockPos p, int hotX, int hotZ, double usX, double usZ) {
        double hx = p.getX() + 0.5 - hotX;
        double hz = p.getZ() + 0.5 - hotZ;
        double ux = p.getX() + 0.5 - usX;
        double uz = p.getZ() + 0.5 - usZ;
        return hx * hx + hz * hz <= LEASH_BLOCKS * LEASH_BLOCKS && ux * ux + uz * uz >= MIN_STEP_BLOCKS * MIN_STEP_BLOCKS;
    }

    // still nylium with room to stand on it, no lava beside it, and not a drop down from where we are
    static boolean spotOk(boolean nylium, boolean airAbove, boolean airAbove2, double spotFeetY, double usY, boolean lavaNear) {
        return nylium && airAbove && airAbove2 && !lavaNear && spotFeetY >= usY - BELOW_BLOCKS;
    }

    // a random known block in the leash that passes ok, null for none. random and not nearest: the nearest is the one we
    // just walked past, and a forest is a lot of floor
    static BlockPos pick(List<BlockPos> known, int hotX, int hotZ, double usX, double usZ, Set<BlockPos> skip, Predicate<BlockPos> ok, Random rng) {
        List<BlockPos> leashed = new ArrayList<>();
        for (BlockPos p : known) {
            if (inLeash(p, hotX, hotZ, usX, usZ) && !skip.contains(p)) {
                leashed.add(p);
            }
        }
        if (leashed.isEmpty()) {
            return null;
        }
        for (int i = 0; i < PICK_TRIES; i++) {
            BlockPos p = leashed.get(rng.nextInt(leashed.size()));
            if (ok.test(p)) {
                return p;
            }
        }
        return null;
    }

    BlockPos goal() {
        return goal;
    }

    // something worth chasing exists: the quiet clock starts over
    void sawEnderman() {
        idle = 0;
    }

    int idleTicks() {
        return idle;
    }

    // one roam tick with no goal or a goal that just ended. false while the last failed pick is cooling down
    boolean mayPick() {
        if (retry > 0) {
            retry--;
            return false;
        }
        return true;
    }

    // a goal was picked (null = nothing qualified, cool down before combing again)
    void picked(BlockPos p) {
        goal = p;
        best = Double.MAX_VALUE;
        stall = 0;
        recheck = RECHECK_TICKS;
        if (p == null) {
            retry = RETRY_TICKS;
        }
    }

    // a roam tick on the current goal. true when it is over: arrived, stalled (that spot is not tried again) or gone bad.
    // the spot itself is only asked once a second, through stillOk
    boolean step(double distToGoal, java.util.function.BooleanSupplier stillOk) {
        idle++;
        if (goal == null) {
            return true;
        }
        if (distToGoal <= ARRIVE_BLOCKS) {
            goal = null;
            return true;
        }
        if (distToGoal <= best - PROGRESS_BLOCKS) {
            best = distToGoal;
            stall = 0;
        } else if (++stall > STALL_TICKS) {
            if (failed.size() >= FAILED_CAP) {
                failed.clear();
            }
            failed.add(goal);
            goal = null;
            return true;
        }
        if (--recheck <= 0) {
            recheck = RECHECK_TICKS;
            if (!stillOk.getAsBoolean()) {
                goal = null;
                return true;
            }
        }
        return false;
    }

    // back on the roam after a chase or anything else: the progress mark was set from wherever we were then
    void resumed() {
        best = Double.MAX_VALUE;
        stall = 0;
    }

    // ticks with no goal still count toward the quiet clock
    void idleTick() {
        idle++;
    }

    Set<BlockPos> failed() {
        return failed;
    }

    Random rng() {
        return rng;
    }
}
