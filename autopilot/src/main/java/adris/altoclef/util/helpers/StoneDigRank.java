package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

// which stone to chew on next. plain distance is wrong for this and baritone's heuristic is worse (it thinks down is
// nearly free, so the nearest "stone" was always the floor and we dug a shaft, then had to climb out of it with cobble we
// are not allowed to place anymore). so: a walk cost like WalkCost, with everything below our feet priced as a hole,
// the block we stand on priced as a last resort, and buried stone priced above stone with an air face. pure, the caller
// reads the world and says if it is exposed
public final class StoneDigRank {
    // stone you mine for cobble. anything else (ores, logs, dirt) keeps its old pick
    private static final Set<Block> STONEISH = Set.of(Blocks.STONE, Blocks.COBBLESTONE, Blocks.DEEPSLATE,
            Blocks.COBBLED_DEEPSLATE, Blocks.BLACKSTONE, Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE);

    // on top of the 4 per block of height, for every block of it below the feet: that is the shaft
    public static final double BELOW_EXTRA = 6.0;
    // no air face means the pick has to open the block up first, and often it is the tail of a column we would tunnel down
    public static final double BURIED = 8.0;
    // the block under us (or the column below it): only wins when every other candidate costs more than this, which at
    // 4 vertical per block is roughly "nothing else within 16"
    public static final double STANDING_ON = 20.0;

    private StoneDigRank() {
    }

    // every block we were asked for is the kind we mine for cobble
    public static boolean stoneOnly(Block... blocks) {
        if (blocks.length == 0) {
            return false;
        }
        for (Block block : blocks) {
            if (!STONEISH.contains(block)) {
                return false;
            }
        }
        return true;
    }

    // per block of sand or gravel stacked on the stone, up to FALLING_CAP of them. mining it brings the stack down the hole
    // (mined from the side that is only a mess and a wait for it to land, from below it is the end of us), and any other
    // stone in the neighbourhood doesn't do that
    public static final double FALLING_EXTRA = 6.0;
    public static final int FALLING_CAP = 3;

    public static double score(double fromX, double fromY, double fromZ, int bx, int by, int bz, boolean exposed) {
        return score(fromX, fromY, fromZ, bx, by, bz, exposed, 0);
    }

    // lower is better. from is our feet (the exact position), the block is a cell. feet level and the cell above it are
    // free, up is 4 a block from there, down is 4 + BELOW_EXTRA a block. fallingAbove is how many falling blocks sit on it
    public static double score(double fromX, double fromY, double fromZ, int bx, int by, int bz, boolean exposed, int fallingAbove) {
        double dx = bx + 0.5 - fromX;
        double dz = bz + 0.5 - fromZ;
        // the 0.01 is for standing on a block top at exactly y.0 with float noise under it
        int feet = (int) Math.floor(fromY + 0.01);
        int dy = by - feet;
        double cost = Math.sqrt(dx * dx + dz * dz);
        if (dy < 0) {
            cost += (WalkCost.VERTICAL_WEIGHT + BELOW_EXTRA) * -dy;
            if (underFeet(fromX, fromZ, bx, bz)) {
                cost += STANDING_ON;
            }
        } else if (dy > 1) {
            cost += WalkCost.VERTICAL_WEIGHT * (dy - 1);
        }
        return cost + extras(exposed, fallingAbove);
    }

    private static double extras(boolean exposed, int fallingAbove) {
        double cost = exposed ? 0 : BURIED;
        return cost + FALLING_EXTRA * Math.min(Math.max(fallingAbove, 0), FALLING_CAP);
    }

    private static boolean underFeet(double fromX, double fromZ, int bx, int bz) {
        return (int) Math.floor(fromX) == bx && (int) Math.floor(fromZ) == bz;
    }

    // the STANDING_ON that score and tunnelScore put on this block from here, same test both use. the switch call takes it
    // back out: it flips the moment our feet step into the next column, which is exactly what chasing a stone under us does
    public static double standingOnPart(double fromX, double fromY, double fromZ, int bx, int by, int bz) {
        int feet = (int) Math.floor(fromY + 0.01);
        return by < feet && underFeet(fromX, fromZ, bx, bz) ? STANDING_ON : 0;
    }

    // the stone line under the dirt is made of these. no cobble: that one is ours (pillars, scaffolding) or a dungeon
    private static final Set<Block> STONE_LINE = Set.of(Blocks.STONE, Blocks.DEEPSLATE, Blocks.ANDESITE, Blocks.DIORITE,
            Blocks.GRANITE, Blocks.TUFF);

    public static boolean stoneLine(Block block) {
        return STONE_LINE.contains(block);
    }

    // nothing scores under this: no wall, no cheap floor block one over. that is when a column straight down started
    public static final double LATCH_SCORE = 12.0;
    // how far down we look for the stone line before calling it a cave, a ravine or the ocean
    public static final int FLOOR_SCAN = 24;
    // per block under the tunnel floor. way past BELOW_EXTRA, the whole point of the tunnel is not going under it
    public static final double BELOW_FLOOR = 30.0;
    // a new pick has to be this much cheaper than the one we are on to take over. about a BURIED's half, so a stone that
    // just got an air face is worth turning around for and two near ties trading places as we step are not
    public static final double SWITCH_MARGIN = 4.0;

    // the best stone is under our feet and nothing near is cheap: dig to the stone line and go sideways instead
    public static boolean shouldTunnel(int feetY, int bestY, double bestScore) {
        return bestY < feetY && bestScore > LATCH_SCORE;
    }

    // the y our feet walk the tunnel at. underLine is "the block under us is stone and so is one beside our feet", which
    // means we are in it already and stay at this level. otherwise two blocks into the first stone down the column, so the
    // head is in the top layer. idempotent on purpose: asked again from inside the tunnel it gives the same floor, a
    // rebuilt task must not end up two deeper every time it starts (a dug out room has no stone beside the feet, that one
    // is StoneTunnel.reuse's job). empty when there is no stone in reach of the scan, or there is a hole on the way down:
    // stone under a cave, a pond or our own old shaft is that thing's floor, not a stone line to walk
    public static OptionalInt tunnelFloor(int feetY, boolean underLine, IntPredicate stoneAt, IntPredicate openAt) {
        if (underLine) {
            return OptionalInt.of(feetY);
        }
        for (int y = feetY - 1; y >= feetY - FLOOR_SCAN; y--) {
            if (stoneAt.test(y)) {
                return OptionalInt.of(y - 1);
            }
            if (openAt.test(y)) {
                return OptionalInt.empty();
            }
        }
        return OptionalInt.empty();
    }

    // the world's answer (tunnelFloor) against the floor we remembered around here (StoneTunnel.reuse). in a room we dug
    // out the world reads two deeper, so a memory one or two over it is that room and wins. anything else the world knows
    // better: a remembered floor from down in a dip is way under a hill's stone line, and one from over a cave is not
    // what is under us now. no stone line in the world's answer is no tunnel, whatever we remember
    public static OptionalInt pickFloor(OptionalInt world, OptionalInt remembered) {
        if (world.isEmpty() || remembered.isEmpty()) {
            return world;
        }
        int up = remembered.getAsInt() - world.getAsInt();
        return up > 0 && up <= 2 ? remembered : world;
    }

    // score() for when we committed to a tunnel at floorY. the band (floor and the cell above it) is free whatever our
    // feet say, so the way down goes to the band and the band goes sideways. above it is a climb, under it is BELOW_FLOOR
    // a block. the column under our feet stays STANDING_ON: the way down is one column over, not the floor we stand on
    public static double tunnelScore(double fromX, double fromY, double fromZ, int bx, int by, int bz, boolean exposed,
                                     int fallingAbove, int floorY) {
        double dx = bx + 0.5 - fromX;
        double dz = bz + 0.5 - fromZ;
        int feet = (int) Math.floor(fromY + 0.01);
        double cost = Math.sqrt(dx * dx + dz * dz);
        if (by > floorY + 1) {
            cost += WalkCost.VERTICAL_WEIGHT * (by - floorY - 1);
        } else if (by < floorY) {
            cost += BELOW_FLOOR * (floorY - by);
        }
        if (by < feet && underFeet(fromX, fromZ, bx, bz)) {
            cost += STANDING_ON;
        }
        return cost + extras(exposed, fallingAbove);
    }

    // in the band of the tunnel at floorY
    public static boolean inBand(int y, int floorY) {
        return y >= floorY && y <= floorY + 1;
    }

    // take the new pick over the one we are on. lower is better, see score
    public static boolean switches(double currentScore, double candidateScore) {
        return candidateScore + SWITCH_MARGIN <= currentScore;
    }

    // a stone and what score() gave it
    public record Scored(BlockPos pos, double score) {
    }

    // how much worse than the best a stone may score and still win by being hittable from right here. 2 is about two
    // blocks of extra walk, way less than BELOW_EXTRA or BURIED, so this never talks us down a shaft or into the floor.
    // it only breaks near-ties, which is the "walk to the dirt-covered one with a good one in reach" case
    public static final double REACH_MARGIN = 2.0;
    // reach raycasts per fresh pick, on top of the one for the best. the rest of the pick is cheap, these aren't
    public static final int REACH_CHECKS = 4;

    // the best is out of reach from where we stand: the first stone (by score) within margin of it that reachable says
    // yes to, checking at most cap of them. null when none of them is. pure, reachable is the caller's raycast
    public static BlockPos preferReachable(List<Scored> candidates, double bestScore, double margin, int cap, Predicate<BlockPos> reachable) {
        List<Scored> close = candidates.stream()
                .filter(c -> c.score() <= bestScore + margin)
                .sorted(Comparator.comparingDouble(Scored::score))
                .limit(cap)
                .toList();
        for (Scored c : close) {
            if (reachable.test(c.pos())) {
                return c.pos();
            }
        }
        return null;
    }
}
