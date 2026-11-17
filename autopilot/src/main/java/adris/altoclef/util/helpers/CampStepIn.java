package adris.altoclef.util.helpers;

import baritone.pathing.movement.CliffAvoidance.Cells;
import java.util.ArrayList;
import java.util.List;

// the buddy's fork, Tuning camp_step_in (off by default, 17 Nov 2026, test staven): the fire-proof camp at the pillbox's open
// side, six to eight off the spawner and a few blocks down, has every blaze of the spawner in sight of the buddy, and a blaze
// that sees its target hovers and shoots (fireballs that do nothing under the potion) rather than coming: two phase-6 stalls of
// whole runs waited there 15 minutes, "Waiting for the blazes to come in reach", with no rod. after DRY_TICKS at that camp with
// no rod, no blaze gone after and no new camp, nothing in reach now and more than FightOpenRules.RUSH_MIN_TICKS of the effect
// left, the camp steps in: to a stand within FLAT_MAX of the spawner, where its blazes spawn within reach. the stands are the
// rush's kind of cell (BlazeCover.walkable: floor, room for the body, no lava within a block, no edge near, so no hit throws the
// buddy into lava from it), never the spawner's own top (rejected as the camp, steering 78), nearest the spawner first, and at
// least STEP_GAIN nearer than the camp; the walk gives a stand not reached up for the next as the rush's does. once a camp.
// no world in here: the rods task reads the camp, the fight and the effect, and asks.
public final class CampStepIn {

    // no rod, no blaze gone after and no camp picked for this long at the open side (game ticks): three game minutes
    public static final long DRY_TICKS = 3600;
    // the stands looked at: this far from the spawner on the flat, from three below its block to one above
    public static final int FLAT_MAX = 4;
    public static final int BELOW = 3;
    public static final int ABOVE = 1;
    // a stand this much nearer the spawner than the camp, or more
    public static final double STEP_GAIN = 2;
    public static final int STAND_MAX = 8;

    private CampStepIn() {
    }

    public static boolean on() {
        return Tuning.get("camp_step_in", 0) > 0;
    }

    // whether the camp steps in now. on: the switch. fireproofCamp: the camp is the fire-proof one at the pillbox's open side (a
    // spot, not by the spawner). waiting: at the camp with no blaze in reach this tick. stepped: this camp stepped in already.
    // effectTicks: what is left of fire resistance (MAX_VALUE an endless one). dryTicks: since the last rod, blaze gone after
    // or camp picked
    public static boolean due(boolean on, boolean fireproofCamp, boolean waiting, boolean stepped, int effectTicks, long dryTicks) {
        return on && fireproofCamp && waiting && !stepped && effectTicks > FightOpenRules.RUSH_MIN_TICKS && dryTicks >= DRY_TICKS;
    }

    // the stands by the spawner at (sx, sy, sz) for a camp at (cx, cy, cz), best first: {x, y, z} feet cells; empty when none fits
    public static List<int[]> stands(Cells cells, int sx, int sy, int sz, int cx, int cy, int cz) {
        double campDist = dist(cx, cy, cz, sx, sy, sz);
        List<int[]> out = new ArrayList<>();
        for (int dx = -FLAT_MAX; dx <= FLAT_MAX; dx++) {
            for (int dz = -FLAT_MAX; dz <= FLAT_MAX; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (dx * dx + dz * dz > FLAT_MAX * FLAT_MAX) continue;
                for (int dy = -BELOW; dy <= ABOVE; dy++) {
                    int x = sx + dx, y = sy + dy, z = sz + dz;
                    if (dist(x, y, z, sx, sy, sz) > campDist - STEP_GAIN) continue;
                    if (BlazeCover.walkable(cells, x, y, z)) out.add(new int[]{x, y, z});
                }
            }
        }
        out.sort((a, b) -> {
            int c = Double.compare(dist(a[0], a[1], a[2], sx, sy, sz), dist(b[0], b[1], b[2], sx, sy, sz));
            if (c != 0) return c;
            if (a[0] != b[0]) return Integer.compare(a[0], b[0]);
            if (a[2] != b[2]) return Integer.compare(a[2], b[2]);
            return Integer.compare(a[1], b[1]);
        });
        return out.size() > STAND_MAX ? new ArrayList<>(out.subList(0, STAND_MAX)) : out;
    }

    private static double dist(int x, int y, int z, int sx, int sy, int sz) {
        double dx = x - sx, dy = y - sy, dz = z - sz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
