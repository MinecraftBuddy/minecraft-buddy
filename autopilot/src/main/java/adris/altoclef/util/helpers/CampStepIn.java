package adris.altoclef.util.helpers;

import baritone.pathing.movement.CliffAvoidance.Cells;
import java.util.ArrayList;
import java.util.List;

// the buddy's fork, Tuning camp_step_in (on by default since 20 Nov 2026, steering 203; built 17 Nov 2026, test staven): the fire-proof camp at the pillbox's open
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

    // ---- (Tuning potion_step_in, off by default, 25 Nov 2026) the step-in under a short potion. a traded potion is 3:00 in all,
    // so DRY_TICKS never ran out under one: in phase 4 the camp stood still about 55% of four potions' time, about 1 rod a potion,
    // and every death read came as the effect ended with 6 to 12 blazes about. under a short potion the step-in waits
    // POTION_DRY_TICKS with no rod and no hit on a blaze (a chase standing still with no path is no hit), only while
    // POTION_IN_TICKS or more of the effect are left; with POTION_OUT_TICKS or less left and no potion in the bag the buddy leaves
    // the stand for the open side and steps in no more till a new potion is on. an endless effect and the brewed 8:00 potions of
    // phase 6 keep DRY_TICKS: their last three minutes are no short potion's

    // no rod and no hit on a blaze this long under a short potion (game ticks): 30 s
    public static final long POTION_DRY_TICKS = 600;
    // the effect left for that step-in, at least: 60 s
    public static final int POTION_IN_TICKS = 1200;
    // the effect left at which the buddy leaves the stand, at most: 25 s
    public static final int POTION_OUT_TICKS = 500;
    // a potion this long in all, or shorter, is a short one: the traded potion's 3:00 (a splash one thrown at the feet less)
    public static final int SHORT_FULL_TICKS = 3600;
    // the effect's ticks rising by more than this is a new potion, not a sync of the same one
    public static final int NEW_POTION_RISE = 100;

    public static boolean potionOn() {
        return Tuning.get("potion_step_in", 0) > 0;
    }

    // whether the effect's ticks went from was (the last look; -1: no look yet) to now (0 none, MAX_VALUE endless) by a new
    // potion: on from none, or up by more than NEW_POTION_RISE
    public static boolean newPotion(int was, int now) {
        return now > 0 && was >= 0 && (was == 0 || now > (long) was + NEW_POTION_RISE);
    }

    // the full length of the potion on now, from the last look (was), now and the full length known (full): a new potion's is
    // what it has now; one on before the first look is of no known length (MAX_VALUE, never short); 0 with none
    public static int fullTicks(int was, int now, int full) {
        if (now <= 0) return 0;
        if (was < 0) return Integer.MAX_VALUE;
        return newPotion(was, now) ? now : full;
    }

    // whether the effect is a short potion's: on, finite and SHORT_FULL_TICKS or less in all
    public static boolean shortPotion(int effectTicks, int fullTicks) {
        return effectTicks > 0 && effectTicks != Integer.MAX_VALUE && fullTicks > 0 && fullTicks <= SHORT_FULL_TICKS;
    }

    // whether the camp steps in now, with potion_step_in. potionOn: that switch. shortPotion: as above. barred: the buddy left a
    // stand under this potion (leaves). potionDryTicks: since the last rod, the last hit on a blaze, the camp's pick or the
    // potion's start. the switch off or no short potion: due as without the switch
    public static boolean due(boolean on, boolean potionOn, boolean shortPotion, boolean fireproofCamp, boolean waiting, boolean stepped,
                              boolean barred, int effectTicks, long dryTicks, long potionDryTicks) {
        if (!potionOn || !shortPotion) return due(on, fireproofCamp, waiting, stepped, effectTicks, dryTicks);
        return on && fireproofCamp && waiting && !stepped && !barred && effectTicks >= POTION_IN_TICKS && potionDryTicks >= POTION_DRY_TICKS;
    }

    // whether the buddy leaves the stand by the spawner now (potion_step_in). steppedIn: the camp is a step-in stand. a short
    // potion with POTION_OUT_TICKS or less left and no fire resistance potion in the bag to go down next
    public static boolean leaves(boolean potionOn, boolean steppedIn, boolean shortPotion, int effectTicks, int potionsLeft) {
        return potionOn && steppedIn && shortPotion && effectTicks <= POTION_OUT_TICKS && potionsLeft <= 0;
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
