package adris.altoclef.util.helpers;

import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import java.util.List;
import java.util.Set;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// the buddy's fork: the blaze fight's run turned its back on the blazes and ran on in their sight, with a fireball in the
// back and five seconds of fire after each (attempts 188, 199, 201, 204, 217, 226 and 237, 4 Nov 2026). a blaze only
// shoots what it sees, and one that has lost sight of us for a few ticks stops coming. so the run goes to the nearest cell
// round us that no blaze sees, safe to stand on, and heals there
public final class BlazeCover {
    // how far round us (flat) a cover cell is looked for, and how far up and down: from a fortress bridge's end the nearest
    // corridor is a dozen blocks off
    public static final int RADIUS = 14;
    static final int DOWN = 3;
    static final int UP = 3;
    // the sight is tested for this many cells at most, nearest first (rays cost, and a far cell is a long walk in their fire)
    static final int TRIES = 600;
    // a blaze this close counts for the sight test: they shoot from further, but past it their balls spread wide
    public static final double SIGHT = 32;
    // a cover cell this close to a wither skeleton is theirs, not ours
    public static final double WITHER_CLEAR = 6;
    // the points round the head that no blaze may see: the eye and a third of a block to each side, so a blaze that drifts
    // a little does not see us at once
    static final double[][] HEAD = {{0, 0}, {0.3, 0}, {-0.3, 0}, {0, 0.3}, {0, -0.3}};
    static final double EYE = 1.62;

    private BlazeCover() {
    }

    // whether no blaze sees a cell (the world half, so pick can be tested without one)
    public interface Hidden {
        boolean at(int x, int y, int z);
    }

    // how closed in a cell is: of RAYS rays out from the head, how many meet a block within ENCLOSE_REACH (the world half)
    public interface Enclosure {
        int at(int x, int y, int z);
    }

    // the hidden cells (and corners) looked at for the most closed in one
    static final int CHOICES = 12;
    static final double ENCLOSE_REACH = 6;
    // eight ways round, level, a third up and two thirds up (blazes hover)
    static final double[] ELEVATIONS = {0, Math.toRadians(35), Math.toRadians(65)};

    // a pocket, a cell to hold against a crowd from: closed all round but for one way in (the ways round open level or a third
    // up are one run of at most POCKET_OPEN of the eight), with a roof (at most one of the eight steep rays open). its enclosure,
    // or -1 when it is no pocket. an open corner lets them round it from behind (4 Nov 2026: a choke point open at the back
    // still lets them shoot from behind)
    static final int POCKET_OPEN = 3;

    public static int pocket(Level level, int x, int y, int z, Entity who) {
        Vec3 head = new Vec3(x + 0.5, y + EYE, z + 0.5);
        boolean[] open = new boolean[8];
        int n = 0;
        int steepOpen = 0;
        for (int e = 0; e < ELEVATIONS.length; e++) {
            double el = ELEVATIONS[e];
            for (int i = 0; i < 8; i++) {
                double yaw = i * Math.PI / 4;
                Vec3 dir = new Vec3(Math.cos(yaw) * Math.cos(el), Math.sin(el), Math.sin(yaw) * Math.cos(el));
                boolean miss = level.clip(new ClipContext(head, head.add(dir.scale(ENCLOSE_REACH)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, who))
                        .getType() == HitResult.Type.MISS;
                if (!miss) n++;
                else if (e < 2) open[i] = true;
                else steepOpen++;
            }
        }
        return onePocket(open, steepOpen) ? n : -1;
    }

    // the open ways round (8, in order) are one run of at most POCKET_OPEN, none open is a pocket too, and the roof holds
    static boolean onePocket(boolean[] open, int steepOpen) {
        if (steepOpen > 1) return false;
        int count = 0;
        for (boolean o : open) if (o) count++;
        if (count == 0) return true;
        if (count > POCKET_OPEN || count == 8) return false;
        // one run round the circle: exactly one place where closed turns open
        int starts = 0;
        for (int i = 0; i < 8; i++) {
            if (open[i] && !open[(i + 7) % 8]) starts++;
        }
        return starts == 1;
    }

    // the world's enclosure of the cell at (x, y, z), 0 to 24
    public static int enclosure(Level level, int x, int y, int z, Entity who) {
        Vec3 head = new Vec3(x + 0.5, y + EYE, z + 0.5);
        int n = 0;
        for (double el : ELEVATIONS) {
            for (int i = 0; i < 8; i++) {
                double yaw = i * Math.PI / 4;
                Vec3 dir = new Vec3(Math.cos(yaw) * Math.cos(el), Math.sin(el), Math.sin(yaw) * Math.cos(el));
                Vec3 end = head.add(dir.scale(ENCLOSE_REACH));
                if (level.clip(new ClipContext(head, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, who)).getType() != HitResult.Type.MISS) n++;
            }
        }
        return n;
    }

    // a cell to heal in: floor under it, room for the body, no lava within a block, no edge or drop by it, and not by a wither
    // skeleton ({x, z} each)
    static boolean fit(Cells cells, int x, int y, int z, List<double[]> withers) {
        if (cells.at(x, y - 1, z) != Cell.SOLID || cells.at(x, y, z) != Cell.OPEN || cells.at(x, y + 1, z) != Cell.OPEN) {
            return false;
        }
        double cx = x + 0.5;
        double cz = z + 0.5;
        if (LavaShore.lavaNear(cells, cx, y, cz, 1) || LavaShore.edgeNear(cells, cx, y, cz) || LavaShore.dropBeside(cells, x, y, z)) {
            return false;
        }
        for (double[] w : withers) {
            if (Math.hypot(w[0] - cx, w[1] - cz) < WITHER_CLEAR) {
                return false;
            }
        }
        return true;
    }

    // a walk to cover this many steps long at the most: a long one goes through the blazes' fire
    public static final int MAX_WALK = 12;
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    // a cell the walk to cover may pass: floor, room for the body, no lava by it, no edge (the start excepted: we may be on one)
    public static boolean walkable(Cells cells, int x, int y, int z) {
        if (cells.at(x, y - 1, z) != Cell.SOLID || cells.at(x, y, z) != Cell.OPEN || cells.at(x, y + 1, z) != Cell.OPEN) {
            return false;
        }
        return !LavaShore.lavaNear(cells, x + 0.5, y, z + 0.5, 1) && !LavaShore.edgeNear(cells, x + 0.5, y, z + 0.5);
    }

    // the nearest fit cell by walk from (ux, uy, uz) that hidden says no blaze sees, banned cells left out: {x, y, z}, or null
    // when there is none. the cells are walked out from us (a step to a side, up one or down two), so a cell behind a wall or
    // past a barrier is never picked: one out past the drill's barrier sent the walk to the open end of a bridge and off it,
    // six falls into the lava in ten runs (4 Nov 2026). the sight is tested in the order of the walk, so the rays stop at the
    // first hidden cell
    public static int[] pick(Cells cells, int ux, int uy, int uz, List<double[]> withers, Set<Long> banned, Hidden hidden) {
        return pick(cells, ux, uy, uz, withers, banned, hidden, (x, y, z) -> 0);
    }

    // the same, the most closed in of the first CHOICES hidden cells (Enclosure), of two as closed in the nearer
    public static int[] pick(Cells cells, int ux, int uy, int uz, List<double[]> withers, Set<Long> banned, Hidden hidden, Enclosure enclosure) {
        return pick(cells, ux, uy, uz, withers, banned, hidden, enclosure, MAX_WALK);
    }

    // the same, walking out maxWalk steps at the most
    public static int[] pick(Cells cells, int ux, int uy, int uz, List<double[]> withers, Set<Long> banned, Hidden hidden, Enclosure enclosure,
                             int maxWalk) {
        List<int[]> found = new java.util.ArrayList<>();
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        queue.add(new int[]{ux, uy, uz, 0});
        seen.add(key(ux, uy, uz));
        int tries = 0;
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            int x = c[0], y = c[1], z = c[2];
            if (!banned.contains(key(x, y, z)) && fit(cells, x, y, z, withers)) {
                if (tries++ >= TRIES) break;
                if (hidden.at(x, y, z)) {
                    found.add(new int[]{x, y, z});
                    if (found.size() >= CHOICES) break;
                }
            }
            if (c[3] >= maxWalk) continue;
            boolean jump = cells.at(x, y + 2, z) == Cell.OPEN;
            for (int[] side : SIDES) {
                int nx = x + side[0], nz = z + side[1];
                if ((nx - ux) * (nx - ux) + (nz - uz) * (nz - uz) > (double) maxWalk * maxWalk) continue;
                for (int dy = 1; dy >= -2; dy--) {
                    int ny = y + dy;
                    if (ny < uy - DOWN || ny > uy + UP || seen.contains(key(nx, ny, nz))) continue;
                    if (dy == 1 && !jump) continue;
                    // (down: the cells the body falls through are open)
                    boolean clear = true;
                    for (int k = ny + 2; k <= y + 1 && clear; k++) clear = cells.at(nx, k, nz) == Cell.OPEN;
                    if (!clear || !walkable(cells, nx, ny, nz)) continue;
                    seen.add(key(nx, ny, nz));
                    queue.add(new int[]{nx, ny, nz, c[3] + 1});
                    // (one cell per column and side: the first height that fits is the step)
                    break;
                }
            }
        }
        return best(found, enclosure, 0);
    }

    // of cells found nearest first, the most closed in (the first of the best); null for none. at is where in each the cell is
    static <T> T best(List<T> found, Enclosure enclosure, int at) {
        T best = null;
        int bestScore = -1;
        for (T c : found) {
            int[] cell = c instanceof int[][] two ? two[at] : (int[]) c;
            int score = enclosure.at(cell[0], cell[1], cell[2]);
            // (below 0: not wanted at all)
            if (score >= 0 && score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    // ---- the corner camp (the buddy's fork, 4 Nov 2026, a tip from someone watching the drills): blazes that see us hover where
    // they are and shoot; one that loses sight of us flies at us for a few ticks. so the camp is a cell by the spawner that the
    // spawn area cannot see, a step from one that it can: a look round the corner draws them, and back behind the wall they
    // come round it into the sword's reach, one or two at a time

    // the corner is this far from the spawner at the most (flat; the spawner only spawns with us within 16 of it) and at least
    public static final double CORNER_MAX = 12;
    public static final double CORNER_MIN = 3;
    // the walk from the spawner to the corner, at the most
    public static final int CORNER_WALK = 18;

    // {corner, peek} as {x, y, z} each, or null: the nearest cell by walk from start (a cell by the spawner) that is fit, not
    // seen from the spawn area (hidden) and has a walkable neighbour that is seen from it (the peek cell)
    public static int[][] corner(Cells cells, int[] start, int sx, int sz, List<double[]> withers, Hidden hidden) {
        return corner(cells, start, sx, sz, withers, hidden, (x, y, z) -> 0);
    }

    // the same, the most closed in of the first CHOICES corners: one open behind is no corner, the blazes come round it from
    // behind as well (4 Nov 2026: an open corner lets the blazes attack from behind; the bent corridor, a pocket with one way
    // in, a kind of choke point, worked)
    public static int[][] corner(Cells cells, int[] start, int sx, int sz, List<double[]> withers, Hidden hidden, Enclosure enclosure) {
        List<int[][]> found = new java.util.ArrayList<>();
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        java.util.HashMap<Long, int[]> seen = new java.util.HashMap<>();
        queue.add(new int[]{start[0], start[1], start[2], 0});
        seen.put(key(start[0], start[1], start[2]), start);
        int tries = 0;
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            int x = c[0], y = c[1], z = c[2];
            double flat = Math.hypot(x + 0.5 - sx - 0.5, z + 0.5 - sz - 0.5);
            List<int[]> next = steps(cells, x, y, z, start[1]);
            if (flat >= CORNER_MIN && fit(cells, x, y, z, withers)) {
                if (tries++ >= TRIES) break;
                if (hidden.at(x, y, z)) {
                    for (int[] n : next) {
                        if (!hidden.at(n[0], n[1], n[2])) {
                            found.add(new int[][]{{x, y, z}, {n[0], n[1], n[2]}});
                            break;
                        }
                    }
                    if (found.size() >= CHOICES) break;
                }
            }
            if (c[3] >= CORNER_WALK) continue;
            for (int[] n : next) {
                if (Math.hypot(n[0] + 0.5 - sx - 0.5, n[2] + 0.5 - sz - 0.5) > CORNER_MAX || seen.containsKey(key(n[0], n[1], n[2]))) continue;
                seen.put(key(n[0], n[1], n[2]), n);
                queue.add(new int[]{n[0], n[1], n[2], c[3] + 1});
            }
        }
        return best(found, enclosure, 0);
    }

    // the cells one step from (x, y, z) the walk may take (to a side, up one or down two), within DOWN and UP of baseY
    public static List<int[]> steps(Cells cells, int x, int y, int z, int baseY) {
        List<int[]> out = new java.util.ArrayList<>();
        boolean jump = cells.at(x, y + 2, z) == Cell.OPEN;
        for (int[] side : SIDES) {
            int nx = x + side[0], nz = z + side[1];
            for (int dy = 1; dy >= -2; dy--) {
                int ny = y + dy;
                if (ny < baseY - DOWN || ny > baseY + UP) continue;
                if (dy == 1 && !jump) continue;
                boolean clear = true;
                for (int k = ny + 2; k <= y + 1 && clear; k++) clear = cells.at(nx, k, nz) == Cell.OPEN;
                if (!clear || !walkable(cells, nx, ny, nz)) continue;
                out.add(new int[]{nx, ny, nz});
                break;
            }
        }
        return out;
    }

    // the eyes of blazes as they spawn: over the spawner and round it, within the spawn range, at a blaze's eye height
    public static List<Vec3> spawnEyes(int sx, int sy, int sz) {
        List<Vec3> eyes = new java.util.ArrayList<>();
        for (int[] d : new int[][]{{0, 0}, {3, 0}, {-3, 0}, {0, 3}, {0, -3}, {2, 2}, {2, -2}, {-2, 2}, {-2, -2}}) {
            eyes.add(new Vec3(sx + 0.5 + d[0], sy + 1.5, sz + 0.5 + d[1]));
        }
        return eyes;
    }

    public static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | ((long) y & 0xFFF);
    }

    // any of three points of a body (its eyes, its middle, a third of a block over its feet) seen from a point: one in the door
    // round the corner bit the buddy from 1.7 blocks with its eyes out of line of the buddy's, and the fight let it go each
    // tick (4 Nov 2026, corner14)
    public static boolean bodySeen(Level level, Vec3 from, Entity body, Entity who) {
        double[] ys = {body.getEyeY(), body.getY() + body.getBbHeight() / 2, body.getY() + 0.3};
        for (double y : ys) {
            Vec3 to = new Vec3(body.getX(), y, body.getZ());
            if (level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, who)).getType() == HitResult.Type.MISS) return true;
        }
        return false;
    }

    // the cells a walk reaches from (x, y, z) in one to depth steps (BlazeCover.steps, within DOWN and UP of y), nearest first,
    // the start left out; each with its step count as the fourth number
    public static List<int[]> around(Cells cells, int x, int y, int z, int depth) {
        List<int[]> out = new java.util.ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        seen.add(key(x, y, z));
        List<int[]> ring = List.of(new int[]{x, y, z});
        for (int d = 1; d <= depth; d++) {
            List<int[]> next = new java.util.ArrayList<>();
            for (int[] c : ring) {
                for (int[] s : steps(cells, c[0], c[1], c[2], y)) {
                    if (!seen.add(key(s[0], s[1], s[2]))) continue;
                    next.add(s);
                    out.add(new int[]{s[0], s[1], s[2], d});
                }
            }
            ring = next;
        }
        return out;
    }

    // no blaze eye in the list sees any of the head points of a body standing in (x, y, z)
    public static boolean hidden(Level level, List<Vec3> eyes, int x, int y, int z, Entity who) {
        for (Vec3 eye : eyes) {
            for (double[] h : HEAD) {
                Vec3 head = new Vec3(x + 0.5 + h[0], y + EYE, z + 0.5 + h[1]);
                if (eye.distanceTo(head) > SIGHT) continue;
                if (level.clip(new ClipContext(eye, head, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, who)).getType() == HitResult.Type.MISS) {
                    return false;
                }
            }
        }
        return true;
    }
}
