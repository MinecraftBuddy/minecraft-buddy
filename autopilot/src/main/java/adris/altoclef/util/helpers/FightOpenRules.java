package adris.altoclef.util.helpers;

import baritone.pathing.movement.CliffAvoidance.Cells;
import java.util.ArrayList;
import java.util.List;

// the buddy's fork, steering 94 (7 Nov 2026): the blaze fight's opening and the fight under fire resistance, as answers. no
// world in here: the fight and the rods task read the blazes, the effect and the bag and ask.
//
// open_attack (Tuning, off by default): the lure and the hiding place had no count of the blazes, so with one or two blazes
// about the buddy still looked round its corner or hid in a pocket (24 of 173 lure starts and 88 of 328 hides in one block
// were at one or two blazes within 16). with fewer than OPEN_FEW live blazes within OPEN_RADIUS at the spawner there is no
// lure, no hiding place and no corner leash: the fight goes after the nearest blaze it can reach. OPEN_FEW or more, as before.
//
// fireproof_rush (Tuning, off by default): under fire resistance a blaze hurts only by its bite (a small fireball is fire
// damage, which the effect refuses before any knock). while the effect has more than RUSH_MIN_TICKS left: no lure, no hiding
// place, no box and no fire-proof waits; the camp is by the spawner, every blaze in reach is fought with no leash, a biting one
// first, each rod is picked up at once, and fire is walked through and left burning. with ten seconds or less the potion is
// drunk again as before (FireResRules); with none left the camp goes back to the way without the potion before the effect ends.
//
// fr_hold (Tuning, off by default): any fire resistance potion, the kit's among them, waits for the blazes as fr_trade_hold's
// traded one does (FireResTradeRules.held): merely on fire on the way it is not drunk
public final class FightOpenRules {

    // ---- open_attack

    // fewer live blazes than this within OPEN_RADIUS of the buddy: the open attack
    public static final int OPEN_FEW = 3;
    public static final double OPEN_RADIUS = 16;
    // at the spawner: the buddy this close to the known spawner (the fight's pick range)
    public static final double OPEN_SPAWNER_RANGE = BlazeFightRules.PICK_RANGE;
    // a change of the count's side holds this long before it counts (ticks): blazes bob in and out at 16
    public static final long OPEN_HOLD_TICKS = 20;

    private FightOpenRules() {
    }

    public static boolean openOn() {
        return Tuning.get("open_attack", 0) > 0;
    }

    // the count says open: fewer than OPEN_FEW live blazes within OPEN_RADIUS
    public static boolean open(int blazesWithin) {
        return blazesWithin < OPEN_FEW;
    }

    // the open attack's state at the spawner, a change held OPEN_HOLD_TICKS before it counts. away from the spawner (or with the
    // switch off) no state; the first tick back takes the count's side at once
    public static final class OpenLatch {
        private Boolean state;
        private long since = Long.MIN_VALUE;
        private boolean changed;

        // atSpawner: the switch on and the buddy at the spawner. want: the count's side this tick (open). the state after
        public boolean update(long now, boolean atSpawner, boolean want) {
            changed = false;
            if (!atSpawner) {
                state = null;
                since = Long.MIN_VALUE;
                return false;
            }
            if (state == null) {
                state = want;
                since = Long.MIN_VALUE;
                changed = true;
                return state;
            }
            if (want == state) {
                since = Long.MIN_VALUE;
                return state;
            }
            if (since == Long.MIN_VALUE || now < since) since = now;
            if (now - since >= OPEN_HOLD_TICKS) {
                state = want;
                since = Long.MIN_VALUE;
                changed = true;
            }
            return state;
        }

        // the last update changed the state (one line per change)
        public boolean changed() {
            return changed;
        }

        public boolean open() {
            return state != null && state;
        }
    }

    // the line of a change
    public static String openLine(boolean open, int within) {
        return "open attack: " + within + " within " + (int) OPEN_RADIUS + ", "
                + (open ? "going after the nearest in reach (no lure, no hide, no leash)" : OPEN_FEW + " or more, the lure");
    }

    // ---- fireproof_rush

    // more than this left of the effect (ticks): the rush. at this or less the food chain drinks again (FireResRules.REDRINK_TICKS)
    public static final int RUSH_MIN_TICKS = FireResRules.REDRINK_TICKS;
    // under the rush a rod is picked up at once, unless a blaze is this close (its bite)
    public static final double RUSH_PICKUP_HOLD = 2;
    // the walk to a stand by the spawner not there in this long (ticks): the next stand
    public static final long RUSH_WALK_TICKS = 600;
    // the stands beside the spawner looked at: this far to a side, one up and one down
    static final int STAND_RING = 2;
    static final int STAND_MAX = 8;

    public static boolean rushOn() {
        return Tuning.get("fireproof_rush", 0) > 0;
    }

    public enum Rush {
        // as without the switch
        OFF,
        // more than RUSH_MIN_TICKS of the effect: the rush, fire walkable
        ON,
        // RUSH_MIN_TICKS or less with a potion left: still the rush's camp while the food chain drinks again, fire not walkable
        REDRINK
    }

    // on: the switch. effectTicks: what is left of fire resistance (0 none, MAX_VALUE an endless one). potions: fire resistance
    // potions in the bag that can go down
    public static Rush rush(boolean on, int effectTicks, int potions) {
        if (!on || effectTicks <= 0) return Rush.OFF;
        if (effectTicks > RUSH_MIN_TICKS) return Rush.ON;
        return potions > 0 ? Rush.REDRINK : Rush.OFF;
    }

    // why the rush is off, for its line
    public static String offWhy(boolean on, int effectTicks, int potions) {
        if (!on) return "the switch off";
        if (effectTicks <= 0) return "the effect gone" + (potions > 0 ? ", " + potions + " potions left" : ", no potion left");
        return "no potion left, " + (effectTicks / 20) + " s of the effect, the camp as without the rush";
    }

    // fire walked through and left burning: only with more than RUSH_MIN_TICKS left, so a fire walked into is out of reach of
    // the buddy by the time the effect ends
    public static boolean fireWalkable(Rush r) {
        return r == Rush.ON;
    }

    // the pickup waits under the rush: in a run (at panic health), or with a blaze in bite's reach of the buddy
    public static boolean holdsPickup(boolean running, double nearestBlaze) {
        return running || nearestBlaze <= RUSH_PICKUP_HOLD;
    }

    // a biting blaze first: the target is past the bite and another one is in it
    public static boolean biterFirst(double targetDistance, double biterDistance, double bite) {
        return targetDistance > bite && biterDistance <= bite;
    }

    // under the rush a blaze's sight is no reason to run (its fireballs do nothing): the run starts at panic health alone
    public static boolean sightIsThreat(boolean rush, boolean blazeSees) {
        return !rush && blazeSees;
    }

    // where the rush stands, best first: the spawner's top (the fire-proof camp's spot, where they spawn within reach), then
    // the cells beside the spawner, one up or down, nearest first; a cell with lava or an edge near is no stand
    // (BlazeCover.walkable). none fits: the top all the same
    public static List<int[]> stands(Cells cells, int sx, int sy, int sz) {
        List<int[]> out = new ArrayList<>();
        if (BlazeCover.walkable(cells, sx, sy + 1, sz)) out.add(new int[]{sx, sy + 1, sz});
        List<int[]> beside = new ArrayList<>();
        for (int dx = -STAND_RING; dx <= STAND_RING; dx++) {
            for (int dz = -STAND_RING; dz <= STAND_RING; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    if (BlazeCover.walkable(cells, sx + dx, sy + dy, sz + dz)) beside.add(new int[]{sx + dx, sy + dy, sz + dz});
                }
            }
        }
        beside.sort((a, b) -> {
            int da = sq(a[0] - sx) + sq(a[2] - sz) + sq(a[1] - sy), db = sq(b[0] - sx) + sq(b[2] - sz) + sq(b[1] - sy);
            if (da != db) return Integer.compare(da, db);
            if (a[0] != b[0]) return Integer.compare(a[0], b[0]);
            if (a[2] != b[2]) return Integer.compare(a[2], b[2]);
            return Integer.compare(a[1], b[1]);
        });
        for (int[] c : beside) {
            if (out.size() >= STAND_MAX) break;
            out.add(c);
        }
        if (out.isEmpty()) out.add(new int[]{sx, sy + 1, sz});
        return out;
    }

    private static int sq(int v) {
        return v * v;
    }

    // ---- fr_hold

    public static boolean holdOn() {
        return Tuning.get("fr_hold", 0) > 0;
    }

    // whether a fire resistance potion that would go down now (FireResRules.wantsToDrink said yes) waits for the blazes: the
    // switch on and FireResTradeRules.held's rule for any potion (no blaze in drinking range, not at the fortress, not asked
    // for by a task, not in lava, and not on fire at holdHp or less)
    public static boolean holdPotion(boolean on, boolean blazeNear, boolean atFortress, boolean asked, boolean inLava, boolean onFire,
                                     double health, double holdHp) {
        return on && FireResTradeRules.held(true, blazeNear, atFortress, asked, inLava, onFire, health, holdHp);
    }
}
