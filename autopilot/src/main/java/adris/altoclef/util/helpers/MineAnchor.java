package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

// finish what you started. the last block we broke is the anchor, and the next one comes from right around it until
// there is nothing left there: a trunk goes up, an ore vein goes wherever it goes. before this the pick was redone from
// scratch after every break with baritone's heuristic, which thinks up costs about the same as sideways, so "the log
// above me" tied with "the log next door" and the bot hopped between trees. pure, the caller feeds the known blocks
public final class MineAnchor {
    // trunks are one wide (two for dark oak and jungle), a branch or a vein bends two over
    public static final int AROUND = 2;
    // a log trunk keeps going up from the break, and a block below is the stump we are standing next to
    public static final int LOG_UP = 6;
    public static final int LOG_DOWN = 1;

    private MineAnchor() {
    }

    // every block we were asked for is a log (or a stem, same shape of tree)
    public static boolean logsOnly(Block... blocks) {
        if (blocks.length == 0) {
            return false;
        }
        for (Block block : blocks) {
            if (!block.defaultBlockState().is(BlockTags.LOGS)) {
                return false;
            }
        }
        return true;
    }

    // is this block part of the same thing the anchor was. tall is the tree shape (long way up), otherwise a box
    public static boolean near(BlockPos anchor, BlockPos at, boolean tall) {
        int dy = at.getY() - anchor.getY();
        boolean upDown = tall ? (dy >= -LOG_DOWN && dy <= LOG_UP) : Math.abs(dy) <= AROUND;
        return upDown && Math.abs(at.getX() - anchor.getX()) <= AROUND && Math.abs(at.getZ() - anchor.getZ()) <= AROUND;
    }

    // the block around the anchor that is nearest to us (plain distance, so the log above us is not a tie with the one
    // next door), or empty when there is nothing left around it. `usable` is the caller's validity check and only gets
    // asked about blocks that are in the neighbourhood, it reads the world
    public static Optional<BlockPos> nearest(BlockPos anchor, List<BlockPos> known, boolean tall, double fromX, double fromY, double fromZ, Predicate<BlockPos> usable) {
        BlockPos best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        double bestAnchorDist = Double.POSITIVE_INFINITY;
        for (BlockPos at : known) {
            if (!near(anchor, at, tall)) {
                continue;
            }
            double dist = distSq(fromX, fromY, fromZ, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5);
            double anchorDist = anchor.distSqr(at);
            // a tie goes to whoever is closer to where we were working, so the order of the cache doesn't pick the tree
            if ((dist < bestDist || (dist == bestDist && anchorDist < bestAnchorDist)) && usable.test(at)) {
                best = at;
                bestDist = dist;
                bestAnchorDist = anchorDist;
            }
        }
        return Optional.ofNullable(best);
    }

    public static double distSq(double fx, double fy, double fz, double tx, double ty, double tz) {
        double dx = tx - fx;
        double dy = ty - fy;
        double dz = tz - fz;
        return dx * dx + dy * dy + dz * dz;
    }
}
