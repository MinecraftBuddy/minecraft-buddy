package adris.altoclef.util.helpers;

import baritone.pathing.movement.CliffAvoidance;
import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;

// fighting next to lava, no world: cells come in through a lookup so the whole thing can be tested without minecraft.
//
// a hit throws us about two blocks when we stand still and next to nothing when we lean into it, and walking up to a foe
// on a shore puts us in the lava or leaves us on the rim for the next hit. so no approach walks near lava, and when the way
// behind us is a drop we press toward the foe instead of standing there to be thrown
public final class LavaShore {
    // how close lava has to be (horizontally) for a spot to count as a shore
    public static final int NEAR = 2;
    // a knock is about two blocks, three is the margin
    static final double BACK_LENGTH = 3.0;
    // half the player's width, the swept box
    static final double HALF_WIDTH = 0.3;
    static final double STEP = 0.5;
    // how close a drop has to be, in any direction, to count as an edge we are standing on
    static final double EDGE_RADIUS = 1.5;
    // finer than STEP: a drop a block away has to land in a sample, 0.5 jumps over the corner of a cell
    static final double EDGE_STEP = 0.25;
    // the lava check around the feet after a landing
    static final int LANDING_RADIUS = 3;

    private LavaShore() {
    }

    // who counts as a melee foe on a rim: anything that walks (or hovers) up and hits, in range. blazes are in on purpose
    public static boolean rimFoe(boolean ghast, boolean ranged, boolean animal, boolean alive, double distance, double range) {
        return !ghast && !ranged && !animal && alive && distance <= range;
    }

    // lava in the columns within r of (x, z), at the feet cell and the one under it
    public static boolean lavaNear(Cells cells, double x, double y, double z, int r) {
        int cx = (int) Math.floor(x);
        int cy = (int) Math.floor(y);
        int cz = (int) Math.floor(z);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (cells.at(cx + dx, cy, cz + dz) == Cell.LAVA || cells.at(cx + dx, cy - 1, cz + dz) == Cell.LAVA) {
                    return true;
                }
            }
        }
        return false;
    }

    // lava at the bottom of an open column: walks down from the feet cell through open air (and water) and says yes only when
    // the walk ends on lava inside CliffAvoidance.DEPTH. a one wide bridge has solid under our feet and lava two down beside it,
    // which lavaNear never sees
    static boolean lavaBelow(Cells cells, int x, int y, int z) {
        for (int d = 0; d <= CliffAvoidance.DEPTH; d++) {
            Cell c = cells.at(x, y - d, z);
            if (c == Cell.LAVA) {
                return true;
            }
            if (c != Cell.OPEN && c != Cell.WATER) {
                return false;
            }
        }
        return false;
    }

    // lavaBelow in any column within r of (x, z)
    public static boolean lavaDeepNear(Cells cells, double x, double y, double z, int r) {
        int cx = (int) Math.floor(x);
        int cy = (int) Math.floor(y);
        int cz = (int) Math.floor(z);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (lavaBelow(cells, cx + dx, cy, cz + dz)) {
                    return true;
                }
            }
        }
        return false;
    }

    // the straight walk from a to b crosses a lava cell (feet level or the one under it, the y slides from one end to the other)
    public static boolean lineHitsLava(Cells cells, double ax, double ay, double az, double bx, double by, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double len = Math.hypot(dx, dz);
        int steps = Math.max(1, (int) Math.ceil(len / STEP));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int cx = (int) Math.floor(ax + dx * t);
            int cz = (int) Math.floor(az + dz * t);
            int cy = (int) Math.floor(ay + (by - ay) * t);
            if (cells.at(cx, cy, cz) == Cell.LAVA || cells.at(cx, cy - 1, cz) == Cell.LAVA) {
                return true;
            }
        }
        return false;
    }

    // the approach guard: no walking at a foe that is by the lava, from a spot that is by the lava, or across it
    public static boolean approachBlocked(Cells cells, double ux, double uy, double uz, double tx, double ty, double tz) {
        return lavaNear(cells, tx, ty, tz, NEAR) || lavaNear(cells, ux, uy, uz, NEAR)
                || lavaDeepNear(cells, ux, uy, uz, NEAR) || lineHitsLava(cells, ux, uy, uz, tx, ty, tz);
    }

    // how long a foe by the lava gets to walk up to us before we stop waiting on it: 8 s. a wait with no end
    // is a bot standing still at a pool until something else gives
    // (was 240 once. CombatCommit.STALL_TICKS is 200 and ends the fight first, so the ban never landed and the same cube got
    // picked again with its count still running. it has to be under the stall to ever matter)
    public static final int WAIT_CAP_TICKS = 160;

    // approachBlocked with the kind of target in it. a cow does not knock us in and baritone paths around lava on its own,
    // so the only thing that matters for a passive one is the drop burning when it dies by the pool. a hostile still gets the
    // whole check (our spot, the line)
    public static boolean blockedFor(boolean passive, Cells cells, double ux, double uy, double uz, double tx, double ty, double tz) {
        return passive ? lavaNear(cells, tx, ty, tz, NEAR) : approachBlocked(cells, ux, uy, uz, tx, ty, tz);
    }

    // should this foe be let go of right now: passive ones by the pool never get a wait, hostiles get WAIT_CAP_TICKS of it
    public static boolean giveUp(boolean passive, int waitedTicks) {
        return passive || waitedTicks >= WAIT_CAP_TICKS;
    }

    // is a spot a bad place to land: lava in it, or open air over lava or over nothing for CliffAvoidance.DEPTH blocks.
    // the box is the player's width, so a knock that clips a corner counts
    public static boolean spotUnsafe(Cells cells, double x, double y, double z) {
        int cy = (int) Math.floor(y);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                int cx = (int) Math.floor(x + sx * HALF_WIDTH);
                int cz = (int) Math.floor(z + sz * HALF_WIDTH);
                if (cells.at(cx, cy, cz) == Cell.LAVA || cells.at(cx, cy - 1, cz) == Cell.LAVA
                        || CliffAvoidance.columnUnsafe(cells, cx, cy, cz)) {
                    return true;
                }
            }
        }
        return false;
    }

    // the line from the foe through us, BACK_LENGTH long, as a swept box at STEP spacing: does the knock land somewhere bad
    public static boolean backUnsafe(Cells cells, double ux, double uy, double uz, double fx, double fz) {
        double dx = ux - fx;
        double dz = uz - fz;
        double len = Math.hypot(dx, dz);
        // right on top of each other there is no "behind", the foe's own spot is the best guess for none
        if (len < 0.05) {
            return false;
        }
        dx /= len;
        dz /= len;
        for (double d = STEP; d <= BACK_LENGTH + 1e-9; d += STEP) {
            if (spotUnsafe(cells, ux + dx * d, uy, uz + dz * d)) {
                return true;
            }
        }
        return false;
    }

    // a lethal drop within EDGE_RADIUS of us in any direction, not only behind along the foe line: a split or a second foe hits from
    // wherever it likes, and the knock goes the other way. sampled on a quarter block grid (strictly inside the radius, so a drop exactly 1.5 away from a cell centre is the next cell's business), same unsafe test as the knock line. a
    // wall at the feet or head of a cell stops a knock (same rule as dropBeside), so that cell is no edge
    public static boolean edgeNear(Cells cells, double ux, double uy, double uz) {
        int cy = (int) Math.floor(uy);
        for (double dx = -EDGE_RADIUS; dx < EDGE_RADIUS; dx += EDGE_STEP) {
            for (double dz = -EDGE_RADIUS; dz < EDGE_RADIUS; dz += EDGE_STEP) {
                if (Math.hypot(dx, dz) >= EDGE_RADIUS) {
                    continue;
                }
                int cx = (int) Math.floor(ux + dx);
                int cz = (int) Math.floor(uz + dz);
                if (cells.at(cx, cy, cz) == Cell.SOLID || cells.at(cx, cy + 1, cz) == Cell.SOLID) {
                    continue;
                }
                if (cells.at(cx, cy, cz) == Cell.LAVA || cells.at(cx, cy - 1, cz) == Cell.LAVA || CliffAvoidance.columnUnsafe(cells, cx, cy, cz)) {
                    return true;
                }
            }
        }
        return false;
    }

    // the stepping check: the knock line behind us, or a drop anywhere close
    public static boolean rimUnsafe(Cells cells, double ux, double uy, double uz, double fx, double fz) {
        return backUnsafe(cells, ux, uy, uz, fx, fz) || edgeNear(cells, ux, uy, uz);
    }

    // a back-off walks away from the foe and only checks headroom, never edges, so on a rim it is not ours to start
    public static boolean backOffAllowed(boolean rim) {
        return !rim;
    }

    // on a rim a landed hit is worth more than a crit, and waiting for the ground after the first knock is how the second
    // one comes. the attack cooldown still applies downstream
    public static boolean swingReady(boolean gatesOpen, boolean onGround, boolean critInFlight, boolean backUnsafe) {
        return gatesOpen && (onGround || critInFlight || backUnsafe);
    }

    // the knock behind us, or any rim at all while the last bite is still fresh: a side-on bite throws us airborne with the
    // drop off to the side, and waiting for the ground then is how the second bite comes (hurtTime counts ten down from a hit)
    public static boolean rimSwing(boolean backUnsafe, boolean rimWide, int hurtTime) {
        return backUnsafe || (rimWide && hurtTime > 0);
    }

    // bitten lately with the foe in reach: a rim step never lands under that knockback (it gets re-picked every hit), so the
    // swing is the only way out. lately is a bit longer than hurtTime: an enderman bites every 20 ticks and hurtTime is only
    // 10 of them, so the rim step got every gap and flip-flopped between two cells while the bites kept landing
    public static final long UNDER_FIRE_TICKS = 30;

    public static boolean underFire(long ticksSinceHurt, boolean inReach) {
        return ticksSinceHurt <= UNDER_FIRE_TICKS && inReach;
    }

    // a rim step that has gone this many ticks without getting any closer to its cell is not going to land (a knock mid step, a
    // path that cannot be walked)
    static final int STEP_STALL_TICKS = 6;
    static final double STEP_STALL_PROGRESS = 0.2;

    // the closest we have been, moved only by a real step closer so a wobble does not count as progress
    public static double stepBest(double best, double now) {
        return now < best - STEP_STALL_PROGRESS ? now : best;
    }

    public static boolean stepStalled(int idleTicks) {
        return idleTicks >= STEP_STALL_TICKS;
    }

    // is a cell fit to stand on while a foe is on us: floor under it, room for the body, no lava next to it, and nothing
    // bad behind it relative to the foe, and no drop within EDGE_RADIUS either (so a stair step ledge does not trade one edge for the next)
    static boolean standable(Cells cells, int x, int y, int z, double fx, double fz) {
        if (cells.at(x, y - 1, z) != Cell.SOLID || cells.at(x, y, z) != Cell.OPEN || cells.at(x, y + 1, z) != Cell.OPEN) {
            return false;
        }
        double cx = x + 0.5;
        double cz = z + 0.5;
        return !lavaNear(cells, cx, y, cz, 1) && !spotUnsafe(cells, cx, y, cz) && !backUnsafe(cells, cx, y, cz, fx, fz)
                && !edgeNear(cells, cx, y, cz) && !dropBeside(cells, x, y, z);
    }

    // a knock does not only go along the line from the foe: a hit from the side can throw us off a drop beside the cell, and
    // the line check never looked sideways. every neighbour has to be a wall (nothing gets thrown into one) or
    // have a landing (solid or water) within 3 cells down. an unloaded cell reads solid, same as everywhere else
    static boolean dropBeside(Cells cells, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = x + dx;
                int nz = z + dz;
                if (cells.at(nx, y, nz) == Cell.SOLID || cells.at(nx, y + 1, nz) == Cell.SOLID) {
                    continue;
                }
                boolean landing = false;
                for (int d = 1; d <= 3; d++) {
                    Cell c = cells.at(nx, y - d, nz);
                    if (c != Cell.OPEN) {
                        landing = c == Cell.SOLID || c == Cell.WATER;
                        break;
                    }
                }
                if (!landing) {
                    return true;
                }
            }
        }
        return false;
    }

    // standable, and not the foe's own cell
    public static boolean stepFit(Cells cells, int x, int y, int z, double fx, double fz) {
        return standable(cells, x, y, z, fx, fz) && Math.hypot(x + 0.5 - fx, z + 0.5 - fz) >= 1.0;
    }

    // the camp spot is this far out from the spawner (horizontal): blazes spawn within 4 of it, so six to eight out they have to
    // fly to us and come one or two at a time, standing in the spawn area meant four of them shooting at once
    public static final double CAMP_MIN = 6;
    public static final double CAMP_MAX = 8;
    // the second look, when the room has nothing six to eight out with a line: still out of the spawn area (4) by a block, and
    // as far as a fortress room's far wall. without it the camp fell back to beside the spawner, where four blazes spawned on
    // top of the buddy and hit it from behind, 20 to 5 hp in a second with six rods in the bag (attempt 177)
    public static final double WIDE_MIN = 5;
    public static final double WIDE_MAX = 10;

    // a standable cell CAMP_MIN..CAMP_MAX out from the spawner with a clear line to it (no solid block between, so the blazes
    // that spawn see us and come over), nearest to us. {x, y, z} or null when the room has no such cell
    public static int[] campSpot(Cells cells, int sx, int sy, int sz, double ux, double uz) {
        return campSpot(cells, sx, sy, sz, ux, uz, CAMP_MIN, CAMP_MAX);
    }

    // the same, min..max out
    // what the last look saw, for its log line: cells to stand on in the ring, and of those the ones with a line
    public static int lastStandable, lastWithLine;

    public static int[] campSpot(Cells cells, int sx, int sy, int sz, double ux, double uz, double min, double max) {
        int[] best = null;
        lastStandable = 0;
        lastWithLine = 0;
        double bestGap = Double.MAX_VALUE;
        int r = (int) Math.ceil(max);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double out = Math.hypot(dx, dz);
                if (out < min || out > max) {
                    continue;
                }
                for (int dy = -2; dy <= 2; dy++) {
                    int x = sx + dx;
                    int y = sy + dy;
                    int z = sz + dz;
                    if (!standable(cells, x, y, z, sx + 0.5, sz + 0.5)) {
                        continue;
                    }
                    lastStandable++;
                    if (!clearLine(cells, x, y, z, sx, sy, sz)) {
                        continue;
                    }
                    lastWithLine++;
                    double gap = Math.hypot(x + 0.5 - ux, z + 0.5 - uz);
                    if (gap < bestGap) {
                        bestGap = gap;
                        best = new int[]{x, y, z};
                    }
                }
            }
        }
        return best;
    }

    // eye height over the cell to the air over the spawner, where its blazes appear and hover, no solid cell in between.
    // (to the spawner's own middle the line went through the stairs and fences round its platform: a real fortress room
    // had no spot five to ten out, and the camp fell back to beside the spawner, attempts 177 and 186)
    static boolean clearLine(Cells cells, int x, int y, int z, int sx, int sy, int sz) {
        double ax = x + 0.5;
        double ay = y + 1.6;
        double az = z + 0.5;
        double bx = sx + 0.5;
        double by = sy + 1.5;
        double bz = sz + 0.5;
        int steps = (int) Math.ceil(Math.hypot(bx - ax, bz - az) / STEP);
        // the last stretch is the spawner's own cell and its frame, that is not in the way
        for (int i = 0; i < steps - 1; i++) {
            double t = (double) i / steps;
            if (cells.at((int) Math.floor(ax + (bx - ax) * t), (int) Math.floor(ay + (by - ay) * t), (int) Math.floor(az + (bz - az) * t)) == Cell.SOLID) {
                return false;
            }
        }
        return true;
    }

    // the one-block step off a rim: null when our spot is fine (or nothing around is better), else {dx, dz} to a neighbour
    // cell that is standable. no lava within 2 wins over a cell that is merely not-on-fire, then the one nearest the foe, so
    // the step also closes the gap instead of backing off. a cell the foe is standing in is no step
    public static int[] safeStep(Cells cells, double ux, double uy, double uz, double fx, double fz) {
        if (!rimUnsafe(cells, ux, uy, uz, fx, fz)) {
            return null;
        }
        int cx = (int) Math.floor(ux);
        int cy = (int) Math.floor(uy);
        int cz = (int) Math.floor(uz);
        int[] best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx == 0 && dz == 0) || !stepFit(cells, cx + dx, cy, cz + dz, fx, fz)) {
                    continue;
                }
                double foeGap = Math.hypot(cx + dx + 0.5 - fx, cz + dz + 0.5 - fz);
                // the deep one too: from a rim the lava is two down, and lavaNear stops looking after one
                boolean byLava = lavaNear(cells, cx + dx + 0.5, cy, cz + dz + 0.5, NEAR) || lavaDeepNear(cells, cx + dx + 0.5, cy, cz + dz + 0.5, NEAR);
                double score = foeGap + (byLava ? 100 : 0);
                if (score < bestScore) {
                    bestScore = score;
                    best = new int[]{dx, dz};
                }
            }
        }
        return best;
    }

    // a knock carries about two and a half blocks, plus half our width. closer to lava than this and one hit can put us in it.
    // same line for "leave here" and "this is ground", so a walk can't end somewhere that starts the next walk
    public static final int GROUND_CLEAR = 3;
    // how far we'll walk for it, past that the fight happens where it is
    static final int GROUND_SEARCH = 6;
    // nether floors are never flat, a cell one or two up or down still counts
    static final int GROUND_CLIMB = 2;
    // a one cell step gets STEP_STALL_TICKS, a walk gets longer: a path search can take 500 ms by itself (10 ticks) and the way
    // round a wall starts out sideways, which isn't closer and isn't stuck either. GROUND_WALK_TICKS still ends it
    static final int WALK_STALL_TICKS = 20;

    public static boolean walkStalled(int idleTicks) {
        return idleTicks >= WALK_STALL_TICKS;
    }

    // cells the walk has already given up on
    public interface Skip {
        boolean skip(int x, int y, int z);
    }

    // safeStep looks one cell away and one cell away from a channel is still in knock range. this is the walk: the nearest cell
    // with no lava within GROUND_CLEAR, {x, y, z}. null when there's no lava in range or nowhere to go (a basalt delta). doesn't
    // ask rimUnsafe first like safeStep does: that only sees the knock line and 1.5 blocks around us
    public static int[] groundCell(Cells cells, double ux, double uy, double uz, double fx, double fz) {
        return groundCell(cells, ux, uy, uz, fx, fz, (x, y, z) -> false);
    }

    public static int[] groundCell(Cells cells, double ux, double uy, double uz, double fx, double fz, Skip skip) {
        if (!lavaDeepNear(cells, ux, uy, uz, GROUND_CLEAR)) {
            return null;
        }
        int cx = (int) Math.floor(ux);
        int cy = (int) Math.floor(uy);
        int cz = (int) Math.floor(uz);
        int[] best = null;
        double bestGap = Double.MAX_VALUE;
        for (int dx = -GROUND_SEARCH; dx <= GROUND_SEARCH; dx++) {
            for (int dz = -GROUND_SEARCH; dz <= GROUND_SEARCH; dz++) {
                double flat = Math.hypot(dx, dz);
                if (flat > GROUND_SEARCH) {
                    continue;
                }
                for (int dy = -GROUND_CLIMB; dy <= GROUND_CLIMB; dy++) {
                    double gap = flat + Math.abs(dy);
                    int x = cx + dx;
                    int y = cy + dy;
                    int z = cz + dz;
                    if (gap < bestGap && !skip.skip(x, y, z) && groundFit(cells, x, y, z, fx, fz) && !wayCrossesLava(cells, ux, uy, uz, x, y, z)) {
                        bestGap = gap;
                        best = new int[]{x, y, z};
                    }
                }
            }
        }
        return best;
    }

    // the floor first: most of the search box is rock or air and never gets as far as the slow questions
    public static boolean groundFit(Cells cells, int x, int y, int z, double fx, double fz) {
        return cells.at(x, y - 1, z) == Cell.SOLID && !lavaDeepNear(cells, x + 0.5, y, z + 0.5, GROUND_CLEAR) && stepFit(cells, x, y, z, fx, fz);
    }

    // the far bank of a channel is close as the crow flies and a lake away on foot. lineHitsLava stops one under the feet, this
    // goes all the way down the open column like lavaBelow does
    static boolean wayCrossesLava(Cells cells, double ux, double uy, double uz, int x, int y, int z) {
        double dx = x + 0.5 - ux;
        double dz = z + 0.5 - uz;
        int steps = Math.max(1, (int) Math.ceil(Math.hypot(dx, dz) / STEP));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            if (lavaBelow(cells, (int) Math.floor(ux + dx * t), (int) Math.floor(uy + (y - uy) * t), (int) Math.floor(uz + dz * t))) {
                return true;
            }
        }
        return false;
    }

    // safeStep with no foe: a standing-still spot that is on a rim by the same edge test. null when we are fine or nothing
    // next door is better. every neighbour is judged with itself as the "foe", which switches the knock line off, so only
    // the edge and lava rules are left
    public static int[] edgeStep(Cells cells, double ux, double uy, double uz) {
        if (!edgeNear(cells, ux, uy, uz)) {
            return null;
        }
        int cx = (int) Math.floor(ux);
        int cy = (int) Math.floor(uy);
        int cz = (int) Math.floor(uz);
        int[] best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                if ((dx == 0 && dz == 0) || !standable(cells, x, cy, z, x + 0.5, z + 0.5)) {
                    continue;
                }
                double score = Math.hypot(x + 0.5 - ux, z + 0.5 - uz) + (lavaNear(cells, x + 0.5, cy, z + 0.5, NEAR) ? 100 : 0);
                if (score < bestScore) {
                    bestScore = score;
                    best = new int[]{dx, dz};
                }
            }
        }
        return best;
    }

    // the keys to press to lean toward the foe, from where the head points. no look added: whatever the yaw is, the keys
    // are picked to walk at the foe from there. yaw is minecraft's (0 = south, +z), dx/dz is us to the foe
    public record Keys(boolean forward, boolean back, boolean left, boolean right) {
        public static final Keys NONE = new Keys(false, false, false, false);

        public boolean any() {
            return forward || back || left || right;
        }
    }

    public static Keys keysToward(float yawDegrees, double dx, double dz) {
        double len = Math.hypot(dx, dz);
        if (len < 0.05) {
            return Keys.NONE;
        }
        double yaw = Math.toRadians(yawDegrees);
        double fwd = (dx * -Math.sin(yaw) + dz * Math.cos(yaw)) / len;
        double rgt = (dx * -Math.cos(yaw) + dz * -Math.sin(yaw)) / len;
        // sin(22.5): eight directions, a key is down when the foe is more than that far off the other axis
        double edge = 0.3827;
        return new Keys(fwd > edge, fwd < -edge, rgt < -edge, rgt > edge);
    }

    // the whole hold call. nether only, and only with no path: a running movement wipes the keys every tick, so a knock that
    // comes mid-path is the landing cancel's job. on the ground it is standing with an unsafe back, in the air a fresh mob hit
    // with an unsafe back. never when the cell ahead is itself bad
    public static boolean hold(boolean nether, boolean onGround, boolean freshHit, boolean pathLive, boolean backUnsafe, boolean aheadUnsafe) {
        if (!nether || aheadUnsafe || pathLive || !backUnsafe) {
            return false;
        }
        return onGround || freshHit;
    }

    // landed after a hit that came with a path: the executor would walk the old dest across whatever lava the knock put
    // between us, so throw the path away when we are off it and lava is close. the replan is next tick
    public static boolean cancelOnLanding(boolean nether, boolean armed, boolean onGround, boolean feetInValid, boolean lavaClose) {
        return nether && armed && onGround && !feetInValid && lavaClose;
    }

    public static boolean lavaClose(Cells cells, double x, double y, double z) {
        return lavaNear(cells, x, y, z, LANDING_RADIUS);
    }
}
