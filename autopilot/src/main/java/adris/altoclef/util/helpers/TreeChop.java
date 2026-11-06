package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.function.Predicate;

// the order a tree comes down in. the drops from a trunk fall to the ground on their own, so walking down for each one and
// climbing back up to the next log is all walk and no chop. finish the tree, then sweep up. pure, the caller reads the world
public final class TreeChop {
    // 6 blocks, squared. off a tree anything this close gets picked up before the next block
    public static final double DROP_FIRST_RANGE_SQ = 36;
    // feet this far over the lowest log of the tree is standing on a tower, not on the ground next to the stump
    public static final int ABOVE_BASE = 2;
    // a drop this far over the feet sitting on leaves is in the canopy, and the leaf is quicker to cut than a path up there
    public static final int CANOPY_DROP = 2;

    private TreeChop() {
    }

    // does the drop beat the block. on a tree the block wins unless the drop is about to vanish or there is nowhere to put
    // what the next log drops. off a tree it's the old rule: close drops just win (the + 10 is on a squared distance, any
    // block within ~3 blocks would beat a drop sitting on our feet, and in a tunnel there is always one that close)
    public static boolean dropWins(boolean onTree, boolean despawning, boolean bagFull, double rawDropSq, double blockSq) {
        if (rawDropSq == Double.POSITIVE_INFINITY) {
            return false;
        }
        if (blockSq == Double.POSITIVE_INFINITY) {
            return true;
        }
        if (onTree && !despawning && !bagFull) {
            return false;
        }
        return rawDropSq <= DROP_FIRST_RANGE_SQ || rawDropSq + 10 <= blockSq;
    }

    // a drop we can count on for the target: not banned (a drop DropPatience gave up on stops counting, so the chop goes
    // on instead of everybody waiting on a drop nobody can reach) and not about to despawn
    public static boolean countable(boolean banned, int age) {
        return !banned && !MineStick.aboutToDespawn(age);
    }

    // what we hold plus what is lying around the tree already covers it. more logs is overchopping, go pick those up
    public static boolean enough(int held, int lying, int want) {
        return held + lying >= want;
    }

    // the trunk the anchor is on, all the way up and down: MineAnchor's sideways box, TreeStrikes' height
    public static boolean inTrunk(BlockPos anchor, BlockPos at) {
        return Math.abs(at.getX() - anchor.getX()) <= MineAnchor.AROUND && Math.abs(at.getZ() - anchor.getZ()) <= MineAnchor.AROUND
                && Math.abs(at.getY() - anchor.getY()) <= TreeStrikes.HEIGHT;
    }

    // standing on a tower beside the trunk: the highest log we can hit from here, so the tree comes down top first instead
    // of a log under the tower and a climb back up for the one over it. null on the ground, or with nothing in reach
    // (nearest does the rest)
    public static BlockPos topDown(List<BlockPos> tree, int feetY, Predicate<BlockPos> inReach) {
        if (tree.isEmpty()) {
            return null;
        }
        int base = Integer.MAX_VALUE;
        for (BlockPos log : tree) {
            base = Math.min(base, log.getY());
        }
        if (feetY <= base + ABOVE_BASE) {
            return null;
        }
        BlockPos best = null;
        for (BlockPos log : tree) {
            if ((best == null || log.getY() > best.getY()) && inReach.test(log)) {
                best = log;
            }
        }
        return best;
    }

    // a drop up in the leaves: cut the leaf under it and it falls to us. dy is drop minus feet
    public static boolean cutLeafUnder(double dy, boolean leafUnder) {
        return leafUnder && dy > CANOPY_DROP;
    }

    // deep in a mine every log is a canopy 30 blocks up and the dirt trip is a tower to nowhere (the 120 s watchdog ate a whole
    // run on that), so the trip is only for a surface that is close. depth and line are blocks under the open sky
    public static boolean dirtDepthOk(int depth, int shallowLine) {
        return depth <= shallowLine;
    }

    // baritone found no way to a log. with placing on and nothing to place, a tower is the way up and dirt is how we get
    // one, so the log is not given up on yet. a trip that already happened (or one that fell short, the lockout) means
    // the tower didn't help, and then it is banned like any other block
    public static boolean noPathDirt(boolean allowPlace, boolean hasThrowaway, boolean lockedOut, int tripsDone, int tripsAllowed) {
        return allowPlace && !hasThrowaway && !lockedOut && tripsDone < tripsAllowed;
    }

    // only a log past a swing from where we'd stand is worth digging a tower for. one at our feet with no path is behind
    // water or a wall, and dirt won't fix that, it just wastes a trip before the ban. no floor known counts as high
    public static boolean highUp(int gap) {
        return gap > TreeReach.MAX_UNDER;
    }

    // one trip of TreeReach's cap gets us up most trees. one taller than that gets a second when the first ran out
    public static int tripsFor(int gap) {
        return gap != Integer.MAX_VALUE && gap > TreeReach.DIRT_MAX ? 2 : 1;
    }

    // what the stripped log task does about the log it stripped last
    public enum StripStep {
        // it is stripped, break it
        BREAK,
        // still bark on it, keep at it with the axe
        STRIP,
        // gone (or never was), on to the next log of the same tree
        NEXT
    }

    public static StripStep stripStep(boolean haveLast, boolean stripped, boolean strippable) {
        if (!haveLast) {
            return StripStep.NEXT;
        }
        if (stripped) {
            return StripStep.BREAK;
        }
        return strippable ? StripStep.STRIP : StripStep.NEXT;
    }
}
