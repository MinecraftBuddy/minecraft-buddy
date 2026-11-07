package adris.altoclef.util.helpers;

import baritone.pathing.movement.CliffAvoidance.Cells;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

// the buddy's fork, Tuning camp_reentry (off by default): the rules of the walk back into the blaze camp, without a world.
// most nether-start runs that died before the first rod were shut out of their own camp: the walk back into the corner or
// to a standing pillbox's open side found no path again and again (the corner's own protected walls, the corner a block
// lower, fire in the door), the goal task wandered, and for minutes the buddy stood a few blocks off the camp in the
// blazes' fire. so a walk back that fails NO_PATHS times or takes WALK_TICKS is "shut out" (Watch), and the camp is
// replaced in this order (choose): a pillbox round the cell the buddy stands on (pillboxHere), another corner a walk
// reaches from there (reachable), else none. CampReentry is the world's half
public final class CampReentryRules {
    // failed searches for the walk back, in one walk: shut out
    public static final int NO_PATHS = 3;
    // a walk back that has not got in in this long (ticks): shut out (the corner run's and the door walk's 20 s)
    public static final long WALK_TICKS = 400;
    // a walk not asked for in this long is over, the next one counts afresh (a fight, a fire put out or a pickup in between
    // are part of the same walk)
    public static final long GAP_TICKS = 100;
    // a failed search counts only when the walk was asked at most this long before (ticks): Baritone's count is every
    // walk's, and the fight's chase after a blaze in the air fails often (the walk and the fight take turns by the tick)
    public static final long STRIDE_TICKS = 3;
    // only within this far of the camp (blocks): from farther off it is the way there, not the way back
    public static final double NEAR = 16;
    // after a camp was left for being shut out: this long no wander within NEAR of it (ticks)
    public static final long NO_WANDER_TICKS = 600;
    // the walk from the feet that another corner must be in (steps, BlazeCover.around)
    public static final int REACH_STEPS = 24;

    private CampReentryRules() {
    }

    public enum Choice { PILLBOX, CORNER, NONE }

    // what replaces a camp the buddy is shut out of: a pillbox where it stands, else another corner, else none
    public static Choice choose(boolean pillboxHere, boolean otherCorner) {
        if (pillboxHere) return Choice.PILLBOX;
        if (otherCorner) return Choice.CORNER;
        return Choice.NONE;
    }

    // the walk back into one camp: the failed searches and the clock. the owner tells it the camp every tick, the goal task
    // every tick it walks to the camp (or its door) with Baritone's count of failed searches
    public static final class Watch {
        private BlockPos camp;
        private boolean in;
        private long since = -1;
        private long last = Long.MIN_VALUE / 2;
        private int armed = -1;
        private int noPaths;
        private boolean said;

        // the camp held now (null: none) and whether the feet are in it; a new camp or the feet in it start afresh
        public void camp(BlockPos c, boolean inNow) {
            if (c == null || !c.equals(camp)) {
                camp = c;
                reset();
            }
            in = inNow;
            if (inNow) reset();
        }

        public BlockPos camp() {
            return camp;
        }

        public boolean in() {
            return in;
        }

        // a walk to the camp asked for at now; failures is Baritone's running count of failed searches, near whether the
        // feet are within NEAR of the camp. a walk asked again within GAP_TICKS goes on (its clock too); the searches that
        // failed since the last ask count only if that ask was at most STRIDE_TICKS ago, the walk's own
        public void walk(long now, int failures, boolean near) {
            if (camp == null || in) return;
            if (!near) {
                reset();
                return;
            }
            if (since < 0 || now < last || now - last > GAP_TICKS) {
                since = now;
                noPaths = 0;
                said = false;
            } else if (failures > armed && now - last <= STRIDE_TICKS) {
                noPaths += failures - armed;
            }
            // (also a count that went down: a new pathfinder starts at 0)
            armed = failures;
            last = now;
        }

        // why the buddy is shut out of the camp ("no path x3", "not reached in 20 s"), once per walk; null while it is not
        public String shutOut(long now) {
            if (said || camp == null || in || since < 0 || now < last || now - last > GAP_TICKS) return null;
            String why = null;
            if (noPaths >= NO_PATHS) why = "no path x" + noPaths;
            else if (now - since >= WALK_TICKS) why = "not reached in " + WALK_TICKS / 20 + " s";
            if (why != null) said = true;
            return why;
        }

        int noPaths() {
            return noPaths;
        }

        private void reset() {
            since = -1;
            noPaths = 0;
            armed = -1;
            said = false;
        }
    }

    // the pillbox round the cell (x, y, z) the buddy stands on, its back cell there: the builder's own checks for one cell,
    // as BlazePillbox.planBent and plan make them for each cell they walk to. the bent box (with the wing, its look out seen by
    // SEEN_MIN of the spawn eyes, the cell hidden from the ring round it), the cheapest that passes, flat from the spawner
    // CELL_MIN to reach(); else the one-cell box with the fewest blocks, CELL_MIN to CELL_MAX. no plan with more blocks than
    // bag. null: none closes the cell
    public static BlazePillbox.Plan pillboxHere(Cells cells, int x, int y, int z, int sx, int sy, int sz, List<Vec3> eyes, boolean bent, int bag) {
        double flat = Math.hypot(x + 0.5 - sx - 0.5, z + 0.5 - sz - 0.5);
        if (flat < BlazePillbox.CELL_MIN) return null;
        if (bent && flat <= BlazePillbox.reach(sy, y)) {
            List<BlazePillbox.Plan> found = new ArrayList<>();
            List<Double> scores = new ArrayList<>();
            for (int[] a : BlazePillbox.SIDES) {
                for (int[] p : BlazePillbox.SIDES) {
                    if (a[0] * p[0] + a[1] * p[1] != 0) continue;
                    BlazePillbox.Plan plan = BlazePillbox.planBentAt(cells, x, y, z, a, p, true, eyes, sx, sz);
                    if (plan == null || plan.blocks().size() > bag) continue;
                    double seen = BlazePillbox.seenShare(cells, plan, eyes);
                    if (seen < BlazePillbox.SEEN_MIN) continue;
                    found.add(plan);
                    scores.add(plan.blocks().size() * 10 + (1 - seen) * BlazePillbox.SEEN_WEIGHT - Math.min(flat, BlazePillbox.LURE) * 3
                            + BlazePillbox.buildSeen(cells, plan, eyes) * BlazePillbox.BUILD_WEIGHT);
                }
            }
            Integer[] order = new Integer[found.size()];
            for (int i = 0; i < order.length; i++) order[i] = i;
            java.util.Arrays.sort(order, java.util.Comparator.comparingDouble(scores::get));
            for (int i : order) {
                BlazePillbox.Plan plan = found.get(i);
                if (BlazePillbox.ringHidden(BlazePillbox.after(cells, plan.blocks()), x, y, z)) return plan;
            }
        }
        if (flat > BlazePillbox.CELL_MAX) return null;
        BlazePillbox.Plan best = null;
        for (int[] open : BlazePillbox.SIDES) {
            BlazePillbox.Plan plan = BlazePillbox.planAt(cells, x, y, z, open, eyes, sx, sz);
            if (plan == null || plan.blocks().size() > bag) continue;
            if (best == null || plan.blocks().size() < best.blocks().size()) best = plan;
        }
        return best;
    }

    // the cells a walk reaches from (x, y, z) in steps steps (BlazeCover.around), the start among them, by BlazeCover.key
    public static Set<Long> reachable(Cells cells, int x, int y, int z, int steps) {
        Set<Long> out = new HashSet<>();
        out.add(BlazeCover.key(x, y, z));
        for (int[] c : BlazeCover.around(cells, x, y, z, steps)) out.add(BlazeCover.key(c[0], c[1], c[2]));
        return out;
    }

    // a corner the camp may move to: not one shut out of, and (reach not null) one a walk from the feet reaches
    public static boolean cornerAllowed(int x, int y, int z, Set<Long> shut, Set<Long> reach) {
        long k = BlazeCover.key(x, y, z);
        return !shut.contains(k) && (reach == null || reach.contains(k));
    }
}
