package adris.altoclef.tasks.speedrun.gamer.tasks;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;

// when a chunk of the stronghold counts as searched, and which chunk to look at next when we have already seen bricks.
// pure. the portal room is only found by line of sight, so standing in a chunk (a dry tunnel through the rock) is not
// searching it: be near its middle, at the level the stronghold is on
public final class RoomCoverage {
    // within this of the chunk centre horizontally (a chunk is 16 wide, the room is 11 x 16 so this sees most of it)
    static final double HORIZONTAL_BLOCKS = 24;
    // and this close to the working level. a stronghold level is 5-8 tall, stairs link them, so a room on the next
    // level is still seen from a corridor within this
    static final double VERTICAL_BLOCKS = 12;

    public static boolean covers(double px, double py, double pz, int chunkX, int chunkZ, double workY) {
        double cx = chunkX * 16 + 8;
        double cz = chunkZ * 16 + 8;
        return Math.hypot(px - cx, pz - cz) <= HORIZONTAL_BLOCKS && Math.abs(py - workY) <= VERTICAL_BLOCKS;
    }

    // a leg ends when the chunk is covered, the walk finished, the timer ran out, we stopped moving or baritone searched
    // everything and found no way there. being inside the chunk at the wrong height is none of those, the room is only
    // seen from its own level
    public static boolean legOver(boolean covered, boolean walkFinished, boolean timerOut, boolean stalled, boolean noPath) {
        return covered || walkFinished || timerOut || stalled || noPath;
    }

    // the staircase column is the only place the dig may call a floor. the spiral is 5 wide, so 3 from the middle is
    // inside it plus a block of slack for baritone clipping corners
    static final double COLUMN_SLACK = 3;
    // each dig goal is this far below the lowest point we got to. a search to the floor 130 blocks down never finishes, so
    // baritone hands back a partial path that drifts off the column. 8 down is one the search can actually complete
    static final int SEGMENT_DEPTH = 8;
    // a dig that never got this far below where it started did not dig
    static final int STUCK_DEPTH = 8;

    public static boolean onColumn(double px, double pz, int columnX, int columnZ) {
        return Math.hypot(px - (columnX + 0.5), pz - (columnZ + 0.5)) <= COLUMN_SLACK;
    }

    // where the first segment ends: SEGMENT_DEPTH under the lowest point reached, never under the floor
    public static int firstSegmentY(int lowestY, int floorY) {
        return Math.max(floorY, lowestY - SEGMENT_DEPTH);
    }

    // the segment steps down once we are at it (or past it) on the column. off the column it stays, so baritone replans back to the column by itself
    public static int nextSegmentY(int segmentY, double playerY, boolean onColumn, int floorY) {
        if (!onColumn || playerY > segmentY + 0.999) {
            return segmentY;
        }
        return Math.max(floorY, (int) Math.min(segmentY, Math.floor(playerY)) - SEGMENT_DEPTH);
    }

    // a dig whose budget ran out without getting STUCK_DEPTH under its entry y, or without getting under the band the
    // stronghold can be in at all (maxY): a spiral from up there searches the surface. a dig that dropped into the band and
    // found no bricks is a different story and is not this
    public static boolean stuckDig(int entryY, int lowestY, int maxY) {
        return lowestY > entryY - STUCK_DEPTH || lowestY > maxY;
    }

    public enum DigOutcome {KEEP_DIGGING, SPIRAL, FAIL}

    // what the dig does this tick. the budget is the config's (task time seconds), not a constant here. the depth is the
    // lowest point reached ON the column: a cave fall is not a dig, and a spiral from cave depth searches nothing
    public static DigOutcome digOutcome(boolean bricksSeen, double elapsed, double budget, int entryY, int columnLowestY,
                                        int maxY) {
        if (bricksSeen) {
            return DigOutcome.SPIRAL;
        }
        if (elapsed <= budget) {
            return DigOutcome.KEEP_DIGGING;
        }
        return stuckDig(entryY, columnLowestY, maxY) ? DigOutcome.FAIL : DigOutcome.SPIRAL;
    }

    // the dig column can shift to these cells around the original middle, in this order. all within 3 of it, so every one
    // is still inside the 5 wide staircase
    static final int[][] FOOTPRINT = {{0, 0}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}};
    // this long without a new low on the current column and the column moves
    static final double WATCHDOG_SECONDS = 60;
    // close enough to the column that a y counts as dug on it
    static final double DUG_RADIUS = 1.5;

    // tracks the dig down the column: the lowest y reached on it, and when it last got lower
    public static final class DigWatch {
        private int columnLowestY;
        private double since;
        private int cell;

        public DigWatch(int entryY, double nowSeconds) {
            columnLowestY = entryY;
            since = nowSeconds;
        }

        // only counts while within DUG_RADIUS of the column being dug, a fall into a cave beside it is not progress
        public void update(double px, double py, double pz, int columnX, int columnZ, double nowSeconds) {
            if (Math.hypot(px - (columnX + 0.5), pz - (columnZ + 0.5)) > DUG_RADIUS) {
                return;
            }
            int y = (int) Math.floor(py);
            // the clock only restarts on a new low for the whole dig, a shifted cell has to beat the old one
            if (y < columnLowestY) {
                columnLowestY = y;
                since = nowSeconds;
            }
        }

        public int columnLowestY() {
            return columnLowestY;
        }

        public boolean stuck(double nowSeconds) {
            return nowSeconds - since >= WATCHDOG_SECONDS;
        }

        // the next untried cell's middle, or null once all of them have had their minute
        public int[] shift(int centreX, int centreZ, double nowSeconds) {
            cell++;
            if (cell >= FOOTPRINT.length) {
                return null;
            }
            since = nowSeconds;
            return new int[]{centreX + FOOTPRINT[cell][0], centreZ + FOOTPRINT[cell][1]};
        }
    }

    // the work level changed, so every chunk marked at the old one was seen from the wrong height. true if it cleared
    public static boolean clearForNewLevel(Set<String> visited, Set<String> skipped, double oldY, double newY) {
        if (Double.compare(oldY, newY) == 0) {
            return false;
        }
        visited.clear();
        skipped.clear();
        return true;
    }

    // stand-still check: re-anchors whenever we get further than STALL_BLOCKS from the anchor, stalled once STALL_SECONDS
    // of task time pass without one. the walk task resets its own progress checker while baritone is pathing, so a bot
    // wedged mid-path would never be noticed by it
    public static final class Stall {
        static final double STALL_BLOCKS = 2;
        static final double STALL_SECONDS = 20;

        private double ax = Double.NaN;
        private double ay;
        private double az;
        private double since;

        public void reset() {
            ax = Double.NaN;
        }

        public boolean stalled(double x, double y, double z, double nowSeconds) {
            if (Double.isNaN(ax) || Math.sqrt((x - ax) * (x - ax) + (y - ay) * (y - ay) + (z - az) * (z - az)) > STALL_BLOCKS) {
                ax = x;
                ay = y;
                az = z;
                since = nowSeconds;
                return false;
            }
            return nowSeconds - since >= STALL_SECONDS;
        }
    }

    public enum DigVerdict {KEEP_DIGGING, BRICKS, FLOOR}

    // the dig down the start column ends on bricks (we are in the stronghold) or on the floor with none (the estimate is
    // wrong). never on a fixed depth: the stronghold sits anywhere from the floor up to y 53. a floor reached off the
    // column proves nothing (a cave carried us around the staircase), so it only counts on the column
    public static DigVerdict digVerdict(boolean bricksSeen, double y, int floorY, boolean onColumn) {
        if (bricksSeen) {
            return DigVerdict.BRICKS;
        }
        return onColumn && y <= floorY + 2 ? DigVerdict.FLOOR : DigVerdict.KEEP_DIGGING;
    }

    // a target chunk we are done with (arrived at the wrong level, or the timer ran out): if nothing saw it, it goes in
    // the skipped set and never in visited. returns true when it was skipped rather than covered
    public static boolean giveUp(Set<String> visited, Set<String> skipped, String key) {
        if (visited.contains(key)) {
            return false;
        }
        skipped.add(key);
        return true;
    }

    // the level to search on is where the bricks are, not where the dig happened to end
    public static double workY(OptionalInt brickY, double playerY) {
        return brickY.isPresent() ? brickY.getAsInt() : playerY;
    }

    // a bunch of bricks around us says the level better than the one nearest brick, which can be a ceiling or a floor.
    // median of the few nearest within this horizontally, plus one for the floor they mostly are
    static final double LEVEL_RADIUS = 16;
    static final int LEVEL_SAMPLE = 5;

    // bricks nearest first. under three close enough to vote and a median is just a coin flip between a floor and a ceiling:
    // the nearest one alone, as it always was
    public static OptionalInt walkLevel(List<BlockPos> nearestFirst, double px, double pz) {
        if (nearestFirst.isEmpty()) {
            return OptionalInt.empty();
        }
        List<Integer> ys = new ArrayList<>();
        for (BlockPos b : nearestFirst) {
            if (ys.size() < LEVEL_SAMPLE && Math.hypot(b.getX() + 0.5 - px, b.getZ() + 0.5 - pz) <= LEVEL_RADIUS) {
                ys.add(b.getY());
            }
        }
        if (ys.size() < 3) {
            return OptionalInt.of(nearestFirst.get(0).getY());
        }
        ys.sort(null);
        return OptionalInt.of(ys.get(ys.size() / 2) + 1);
    }

    // a leg whose search keeps timing out is going nowhere just as much as an exhausted one: a timed out search already
    // means baritone could not get 5 blocks closer. one is noise, two in the same leg is the leg
    static final int TIMED_OUT_ENDS = 2;
    // this many legs in a row ending on no path and the fault is where we stand or the level we aim at, not the chunks
    static final int NO_PATH_CASCADE = 4;

    public static boolean noPathEnds(boolean exhausted, int timedOutInLeg) {
        return exhausted || timedOutInLeg >= TIMED_OUT_ENDS;
    }

    public static boolean noPathCascade(int streak) {
        return streak >= NO_PATH_CASCADE;
    }

    // air cells we have stood in or near, quantised to 2 block cells so it stays small. oldest go first past the cap
    public static final class Visited {
        static final int CAP = 4096;
        private final LinkedHashSet<Long> keys = new LinkedHashSet<>();

        private static long key(int x, int y, int z) {
            return BlockPos.asLong(x >> 1, y >> 1, z >> 1);
        }

        public void add(int x, int y, int z) {
            long k = key(x, y, z);
            // re-adding moves it to the back, so the cells we keep standing in are the last to go
            keys.remove(k);
            keys.add(k);
            if (keys.size() > CAP) {
                Iterator<Long> it = keys.iterator();
                it.next();
                it.remove();
            }
        }

        // within about 4 blocks of somewhere we have been
        public boolean near(int x, int y, int z) {
            int qx = x >> 1;
            int qy = y >> 1;
            int qz = z >> 1;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        if (keys.contains(BlockPos.asLong(qx + dx, qy + dy, qz + dz))) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        public int size() {
            return keys.size();
        }
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    // places to stand next to bricks we saw: above a floor brick, beside a wall brick at foot or head height. the face we saw
    // is the air in front of it, so those cells are known. standable says feet and head free with a floor under
    public static List<BlockPos> corridorCells(Collection<BlockPos> bricks, Predicate<BlockPos> standable) {
        Set<BlockPos> tried = new LinkedHashSet<>();
        for (BlockPos b : bricks) {
            // plain offsets: Direction would drag minecraft's static init into a pure class the unit tests load
            tried.add(b.offset(0, 1, 0));
            for (int[] d : SIDES) {
                BlockPos side = b.offset(d[0], 0, d[1]);
                tried.add(side);
                tried.add(side.offset(0, -1, 0));
            }
        }
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos c : tried) {
            if (standable.test(c)) {
                out.add(c.immutable());
            }
        }
        return out;
    }

    // nearest cell we have not been by, nudged toward the ones further from the staircase so the walk pushes outward
    public static Optional<BlockPos> pickCorridorCell(Collection<BlockPos> cells, Visited visited, double px, double py,
                                                      double pz, double startX, double startZ, double workY) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos c : cells) {
            if (!Double.isNaN(workY) && Math.abs(c.getY() - workY) > VERTICAL_BLOCKS) {
                continue;
            }
            if (visited.near(c.getX(), c.getY(), c.getZ())) {
                continue;
            }
            double dist = Math.hypot(c.getX() + 0.5 - px, c.getZ() + 0.5 - pz) + 2 * Math.abs(c.getY() - py);
            double out = Math.hypot(c.getX() + 0.5 - startX, c.getZ() + 0.5 - startZ);
            double score = dist - 0.25 * out;
            if (score < bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return Optional.ofNullable(best);
    }

    // tracked bricks that can be the stronghold's, before any cap. the cap has to come after this: a village or a ruin
    // next to us is nearer than the real thing and would eat every slot
    public static List<BlockPos> brickCandidates(List<BlockPos> tracked, int maxY, boolean hasStart, double startX,
                                                 double startZ, double maxDist, double nearY) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos pos : tracked) {
            if (pos.getY() > maxY) {
                continue;
            }
            // a level of the stronghold, not a ruin at the surface or a corridor seen way down a cave
            if (!Double.isNaN(nearY) && Math.abs(pos.getY() - nearY) > VERTICAL_BLOCKS) {
                continue;
            }
            if (hasStart && Math.hypot(pos.getX() - startX, pos.getZ() - startZ) > maxDist) {
                continue;
            }
            out.add(pos);
        }
        return out;
    }

    // seen bricks mark where corridors are. of the chunks they sit in, the nearest one inside the search square that
    // is not visited yet, so we follow the stronghold before walking the blind spiral. bricks are {x, z}
    public static Optional<int[]> nearestUnvisitedBrickChunk(List<int[]> seenBricks, Set<String> visited, double px, double pz,
                                                            int startCx, int startCz, int radius) {
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        for (int[] b : seenBricks) {
            int cx = Math.floorDiv(b[0], 16);
            int cz = Math.floorDiv(b[1], 16);
            if (Math.abs(cx - startCx) > radius || Math.abs(cz - startCz) > radius || visited.contains(cx + "," + cz)) {
                continue;
            }
            double d = Math.hypot(cx * 16 + 8 - px, cz * 16 + 8 - pz);
            if (d < bestD) {
                bestD = d;
                best = new int[]{cx, cz};
            }
        }
        return Optional.ofNullable(best);
    }

    private RoomCoverage() {
    }
}
