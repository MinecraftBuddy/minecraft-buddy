package adris.altoclef.util.helpers;

import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.phys.Vec3;

// the buddy's fork, Tuning brew_box: the brew of fire resistance walled in. in the open by the spawner the brew began four times
// in ten runs, was cut off by a fight five times and never finished, and eight of the ten died, mostly before the potion. so the
// box: two cells side by side on firm floor, B the buddy's and F the stand's, every side wall two high, a roof over both,
// nothing open, so no fireball, arrow or melee hit gets in. this is the plan only (the world as cells, tested without one): the
// bent pillbox's two cells (BlazePillbox.planBentAt) with its look out walled and its wing left off. BrewBoxTask builds it with
// BlazePillboxTask and brews in it
public final class BrewBox {
    // under this the fight or the escape may act even with the box shut (hp)
    public static final float LOW_HP = 6;
    // the walk out from the buddy for sites, in steps
    public static final int WALK = 8;
    // a site this close to the spawner (flat) is in its spawn area: a blaze could spawn inside the box
    static final double SPAWNER_CLEAR = 5;
    // no lava within this of the box's walls, at the floor's level and the walls' (a placed block next to lava, or a stumble)
    static final int LAVA_CLEAR = 1;
    // a site with more to place is no site (six wall columns of two, the roof's support and the roof over both)
    static final int MOST = 15;
    // the score: a block is 10, the build seen by every spawn eye against none 40 (BlazePillbox.buildSeen), a step out 2
    static final double SEEN_WEIGHT = 40;
    static final double STEP_WEIGHT = 2;

    private BrewBox() {
    }

    // whether the tuning is on (brew_box 1 and up); off, the brew is the open one as before
    public static boolean on(double tuning) {
        return tuning >= 1;
    }

    // back B (the buddy's cell), front F = B + dir (the stand's), the blocks to place in BlazePillboxTask's order (low walls,
    // high walls, the roof), the shell (every cell that must be solid for the box to be shut: the floor under both, the six
    // wall columns two high, the roof over both) and the wall columns as {x, z, outward x, outward z}: B's three first, F's after
    public record Box(int[] back, int[] front, int[] dir, List<int[]> blocks, List<int[]> shell, List<int[]> walls) {
        // as a pillbox plan for BlazePillboxTask: the cell B, the front F (the roof row is placed from there), no look out (F)
        public BlazePillbox.Plan build() {
            return new BlazePillbox.Plan(back, front, blocks, front);
        }

        public int y() {
            return back[1];
        }
    }

    static final int[][] SIDES = BlazePillbox.SIDES;

    // the cheapest box near start (the buddy's cell), or null with none. eyes are the spawn area's in open cells (empty with no
    // spawner known: then the sight counts nothing), sx and sz the spawner's (NaN with none), avoid the cells the box may not
    // take (a pillbox under way), as BlazeCover.key
    public static Box plan(Cells cells, int[] start, List<Vec3> eyes, double sx, double sz, Set<Long> avoid) {
        return plan(cells, cells, start, eyes, sx, sz, avoid);
    }

    // two views of the world: cells for the shell (what a block can go in and what is solid, as the box's wholeness is judged:
    // a fireball's fire in a wall's cell is open, a block goes in over it) and stand for where the buddy and the stand stand and
    // the walk there (as Baritone reads it: fire is no cell to stand in)
    public static Box plan(Cells cells, Cells stand, int[] start, List<Vec3> eyes, double sx, double sz, Set<Long> avoid) {
        Box best = null;
        double bestScore = Double.MAX_VALUE;
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        queue.add(new int[]{start[0], start[1], start[2], 0});
        seen.add(BlazeCover.key(start[0], start[1], start[2]));
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            int x = c[0], y = c[1], z = c[2];
            if (BlazeCover.walkable(stand, x, y, z)) {
                for (int[] a : SIDES) {
                    Box box = planAt(cells, stand, x, y, z, a, sx, sz, avoid);
                    if (box == null) continue;
                    double score = box.blocks().size() * 10 + BlazePillbox.buildSeen(cells, box.build(), eyes) * SEEN_WEIGHT + c[3] * STEP_WEIGHT;
                    if (score < bestScore) {
                        bestScore = score;
                        best = box;
                    }
                }
            }
            if (c[3] >= WALK) continue;
            for (int[] n : BlazeCover.steps(stand, x, y, z, start[1])) {
                if (!seen.add(BlazeCover.key(n[0], n[1], n[2]))) continue;
                queue.add(new int[]{n[0], n[1], n[2], c[3] + 1});
            }
        }
        return best;
    }

    // the box with B at (x, y, z) and F at B + a, or null: a cell without floor or room, a drop or lava by it, inside the
    // spawner's spawn area, a wall cell that cannot be filled (fluid, or open with nothing under it), more than MOST blocks, or a
    // cell of avoid in it
    public static Box planAt(Cells cells, int x, int y, int z, int[] a, double sx, double sz, Set<Long> avoid) {
        return planAt(cells, cells, x, y, z, a, sx, sz, avoid);
    }

    public static Box planAt(Cells cells, Cells stand, int x, int y, int z, int[] a, double sx, double sz, Set<Long> avoid) {
        int fx = x + a[0], fz = z + a[1];
        // (across a: the sides of the two cells)
        int[] p = {a[1], a[0]};
        if (!BlazeCover.walkable(stand, x, y, z) || !BlazeCover.walkable(stand, fx, y, fz)) return null;
        // (and room in the world's own reading: a cell Baritone keeps off is no cell for the box either)
        for (int k = 0; k <= 1; k++) {
            if (cells.at(x, y + k, z) != Cell.OPEN || cells.at(fx, y + k, fz) != Cell.OPEN) return null;
        }
        if (!Double.isNaN(sx) && (Math.hypot(x + 0.5 - sx - 0.5, z + 0.5 - sz - 0.5) < SPAWNER_CLEAR
                || Math.hypot(fx + 0.5 - sx - 0.5, fz + 0.5 - sz - 0.5) < SPAWNER_CLEAR)) return null;
        // (no drop by either cell: by a rim the builder stood on the rim's fence to set the wall above it, as the pillbox's)
        if (BlazePillbox.dropNear(stand, x, y, z, BlazePillbox.DROP_CLEAR) || BlazePillbox.dropNear(stand, fx, y, fz, BlazePillbox.DROP_CLEAR)) return null;
        if (lavaBy(cells, x, y, z, fx, fz)) return null;
        int[][] walls = {{x - a[0], z - a[1], -a[0], -a[1]}, {x + p[0], z + p[1], p[0], p[1]}, {x - p[0], z - p[1], -p[0], -p[1]},
                {fx + a[0], fz + a[1], a[0], a[1]}, {fx + p[0], fz + p[1], p[0], p[1]}, {fx - p[0], fz - p[1], -p[0], -p[1]}};
        List<int[]> low = new ArrayList<>(), high = new ArrayList<>(), shell = new ArrayList<>(), wallList = new ArrayList<>();
        shell.add(new int[]{x, y - 1, z});
        shell.add(new int[]{fx, y - 1, fz});
        for (int[] w : walls) {
            wallList.add(w);
            shell.add(new int[]{w[0], y, w[1]});
            shell.add(new int[]{w[0], y + 1, w[1]});
            Cell c0 = cells.at(w[0], y, w[1]);
            if (c0 == Cell.OPEN) {
                // (a low wall is clicked on the floor under it)
                if (cells.at(w[0], y - 1, w[1]) != Cell.SOLID) return null;
                low.add(new int[]{w[0], y, w[1]});
            } else if (c0 != Cell.SOLID) {
                return null;
            }
            Cell c1 = cells.at(w[0], y + 1, w[1]);
            if (c1 == Cell.OPEN) high.add(new int[]{w[0], y + 1, w[1]});
            else if (c1 != Cell.SOLID) return null;
        }
        shell.add(new int[]{x, y + 2, z});
        shell.add(new int[]{fx, y + 2, fz});
        // (the walls that face the spawner first, as the pillbox's: the cells are in the spawn area's sight least long)
        if (!Double.isNaN(sx)) {
            java.util.Comparator<int[]> nearest = java.util.Comparator.comparingDouble(b -> Math.hypot(b[0] - sx, b[2] - sz));
            low.sort(nearest);
            high.sort(nearest);
        }
        List<int[]> blocks = new ArrayList<>(low);
        blocks.addAll(high);
        // the roof row, placed from F (BlazePillboxTask): B's needs a solid face beside it at its height to be clicked against,
        // one there already (the top of a wall that was there, a ceiling's edge) or one put on top of B's back wall, as the bent
        // box's; F's then goes against B's
        Cell rb = cells.at(x, y + 2, z);
        if (rb == Cell.OPEN) {
            boolean face = cells.at(x - a[0], y + 2, z - a[1]) == Cell.SOLID || cells.at(x + p[0], y + 2, z + p[1]) == Cell.SOLID
                    || cells.at(x - p[0], y + 2, z - p[1]) == Cell.SOLID;
            if (!face) {
                if (cells.at(x - a[0], y + 2, z - a[1]) != Cell.OPEN) return null;
                blocks.add(new int[]{x - a[0], y + 2, z - a[1]});
            }
            blocks.add(new int[]{x, y + 2, z});
        } else if (rb != Cell.SOLID) {
            return null;
        }
        Cell rf = cells.at(fx, y + 2, fz);
        if (rf == Cell.OPEN) blocks.add(new int[]{fx, y + 2, fz});
        else if (rf != Cell.SOLID) return null;
        if (blocks.size() > MOST) return null;
        if (avoid != null && !avoid.isEmpty()) {
            for (int[] b : shell) if (avoid.contains(BlazeCover.key(b[0], b[1], b[2]))) return null;
            for (int[] b : blocks) if (avoid.contains(BlazeCover.key(b[0], b[1], b[2]))) return null;
            for (int k = 0; k <= 1; k++) {
                if (avoid.contains(BlazeCover.key(x, y + k, z)) || avoid.contains(BlazeCover.key(fx, y + k, fz))) return null;
            }
        }
        return new Box(new int[]{x, y, z}, new int[]{fx, y, fz}, new int[]{a[0], a[1]}, blocks, shell, wallList);
    }

    // lava in the ring of the walls or one past it, from the floor's level to the walls' top (a placed block next to lava, a
    // stumble while building, a flow into the box)
    static boolean lavaBy(Cells cells, int x, int y, int z, int fx, int fz) {
        int r = 1 + LAVA_CLEAR;
        int minX = Math.min(x, fx) - r, maxX = Math.max(x, fx) + r, minZ = Math.min(z, fz) - r, maxZ = Math.max(z, fz) + r;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                for (int cy = y - 1; cy <= y + 2; cy++) {
                    if (cells.at(cx, cy, cz) == Cell.LAVA) return true;
                }
            }
        }
        return false;
    }

    // every cell of the shell is solid in these cells: the box is shut
    public static boolean shut(Cells cells, Box box) {
        for (int[] s : box.shell()) if (cells.at(s[0], s[1], s[2]) != Cell.SOLID) return false;
        return true;
    }

    // the fight sits out: the box is whole, the buddy is in it, nothing hostile is inside, no fire in a cell and the health at
    // LOW_HP or more. any one of those failing lets the fight or the escape act as anywhere else
    public static boolean holds(boolean whole, boolean inside, boolean hostileInside, boolean fireInside, float health) {
        return whole && inside && !hostileInside && !fireInside && health >= LOW_HP;
    }

    // a door's walk out is this many steps of floor beyond it, at least (a nook or a ledge one cell deep is no way out)
    static final int DOOR_STEPS = 3;

    // a wall to break to get out: its two cells (low first), the cell beyond it a walk out ends in (null when the cells read none:
    // a drop, a wall, lava) and whether that cell leads on to DOOR_STEPS steps of floor
    public record Door(List<int[]> cells, int[] beyond, boolean out) {
    }

    // the walls to break to get out, best first, every one a candidate: B's three, and F's three too when F holds no stand (one
    // there shuts the way through F). first a wall with a walk out beyond it (the door that led nowhere left the buddy in the
    // box twelve game minutes, Baritone finding no path every 2 s), then one whose cells were both placed by the box (a fortress
    // wall stays whole), then the one nearest the spawner (the order goes on there; with none, B's first). cells as the shell is
    // judged, stand as the walk reads the world (the planner's walkable and steps); placed holds BlazeCover.key
    public static List<Door> doors(Cells cells, Cells stand, Box box, Set<Long> placed, boolean frontFree, double sx, double sz) {
        int y = box.y();
        int n = frontFree ? 6 : 3;
        List<Door> out = new ArrayList<>();
        List<double[]> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int[] w = box.walls().get(i);
            int ours = (placed.contains(BlazeCover.key(w[0], y, w[1])) ? 1 : 0) + (placed.contains(BlazeCover.key(w[0], y + 1, w[1])) ? 1 : 0);
            int ox = w[0] + w[2], oz = w[1] + w[3];
            int[] beyond = beyond(cells, stand, box, w);
            boolean way = beyond != null && leadsOn(stand, box, beyond);
            double toward = Double.isNaN(sx) ? i : Math.hypot(ox + 0.5 - sx - 0.5, oz + 0.5 - sz - 0.5);
            out.add(new Door(List.of(new int[]{w[0], y, w[1]}, new int[]{w[0], y + 1, w[1]}), beyond, way));
            order.add(new double[]{way ? 0 : 1, 2 - ours, toward, i});
        }
        java.util.Comparator<double[]> best = java.util.Comparator.<double[]>comparingDouble(o -> o[0]).thenComparingDouble(o -> o[1])
                .thenComparingDouble(o -> o[2]).thenComparingDouble(o -> o[3]);
        order.sort(best);
        List<Door> sorted = new ArrayList<>();
        for (double[] o : order) sorted.add(out.get((int) o[3]));
        return sorted;
    }

    // the cell a walk through the wall w ends in: beyond it on the floor's level or one down, with the wall's own floor under the
    // door (its passage is walked, not jumped); null with none
    static int[] beyond(Cells cells, Cells stand, Box box, int[] w) {
        int y = box.y();
        if (cells.at(w[0], y - 1, w[1]) != Cell.SOLID) return null;
        int ox = w[0] + w[2], oz = w[1] + w[3];
        if (BlazeCover.walkable(stand, ox, y, oz)) return new int[]{ox, y, oz};
        if (stand.at(ox, y + 1, oz) == Cell.OPEN && BlazeCover.walkable(stand, ox, y - 1, oz)) return new int[]{ox, y - 1, oz};
        return null;
    }

    // from the cell beyond a door, a walk of DOOR_STEPS steps or more away from the box (BlazeCover.steps, as the planner walks),
    // never back into the box's two cells or through a wall's column
    static boolean leadsOn(Cells stand, Box box, int[] from) {
        Set<Long> keep = new HashSet<>();
        for (int[] c : new int[][]{box.back(), box.front()}) keep.add(BlazeCover.key(c[0], 0, c[2]));
        for (int[] w : box.walls()) keep.add(BlazeCover.key(w[0], 0, w[1]));
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        queue.add(new int[]{from[0], from[1], from[2], 0});
        seen.add(BlazeCover.key(from[0], from[1], from[2]));
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            if (c[3] >= DOOR_STEPS) return true;
            for (int[] s : BlazeCover.steps(stand, c[0], c[1], c[2], box.y())) {
                if (keep.contains(BlazeCover.key(s[0], 0, s[2]))) continue;
                if (!seen.add(BlazeCover.key(s[0], s[1], s[2]))) continue;
                queue.add(new int[]{s[0], s[1], s[2], c[3] + 1});
            }
        }
        return false;
    }
}
