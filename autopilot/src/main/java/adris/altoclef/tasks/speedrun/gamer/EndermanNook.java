package adris.altoclef.tasks.speedrun.gamer;

// the rules of the enderman nook: a one wide tunnel, two deep and two high with a lid, that an enderman (2.9 tall) cannot get into.
// it stands in the mouth and we stand at the back where its reach (1.428 between the middles) does not, and we hit its legs.
// every number and decision is here, NookHuntTask walks, digs and swings. no minecraft in here so the tests can throw numbers at it
public final class EndermanNook {
    // cells the tunnel goes in, and how tall it is. the lid sits one above that
    public static final int DEPTH = 2;
    public static final int HEIGHT = 2;
    // how far in (measured from the middle of the cell outside the mouth, along the tunnel) our middle has to be before an
    // enderman in the mouth cannot reach it. the back cell's middle is only 2.0, so we press against the back wall (0.2 more)
    public static final double STAND_IN = 2.13;
    // the back cell's middle plus the room the 0.6 wide hitbox has before the wall
    public static final double BACK_WALL_IN = 2.2;
    // nothing under this much health stares at an enderman on purpose, a coin flip per kill is a lot worse at low hp
    public static final float AGGRO_MIN_HP = 18;
    // lava this close to us and we do not start anything
    public static final int AGGRO_LAVA_GAP = 2;
    // inside this an angry enderman is somebody to stand and fight, past it it teleports to us on its own
    public static final double ANGRY_RANGE = 32;
    // an enderman this far or closer counts as "here" for the hotspot walk and the nook (the old latch had no distance at all)
    public static final double LATCH_RANGE = 40;
    // staring at one this close makes it teleport every tick, so we wait for it to wander off
    public static final double STARE_MIN = 6;

    private EndermanNook() {
    }

    // what the world says about one candidate tunnel. cellsFree: the four cells are air or something a pick takes down
    // quickly (and nothing that falls). lidMissing: how many of the two lid blocks are air (they get placed), lidBlocked: one of
    // them is something that is neither solid nor placeable-against
    public record Spot(boolean cellsFree, boolean floorSolid, boolean sidesSolid, boolean backSolid, boolean mouthOpen,
                       int lidMissing, boolean lidBlocked, boolean lavaNear, int supply) {
    }

    public static boolean spotOk(Spot s) {
        return s.cellsFree() && s.floorSolid() && s.sidesSolid() && s.backSolid() && s.mouthOpen() && !s.lavaNear()
                && !s.lidBlocked() && s.lidMissing() <= s.supply();
    }

    // the cheaper the better: the walk, plus the digging and the placing
    public static double spotCost(double walk, int cellsToDig, int lidMissing) {
        return walk + cellsToDig * 2.0 + lidMissing * 1.5;
    }

    // our middle along the tunnel, measured from the middle of the cell outside the mouth
    public static boolean standDeepEnough(double inFromMouthMiddle) {
        return inFromMouthMiddle >= STAND_IN;
    }

    // how far to creep further in, 0 = in place. aims a hair past the line, never past the wall
    public static double creepBy(double inFromMouthMiddle) {
        if (inFromMouthMiddle >= STAND_IN) {
            return 0;
        }
        return Math.min(BACK_WALL_IN, STAND_IN + 0.05) - inFromMouthMiddle;
    }

    // starting something on purpose: healthy, not on fire, not next to lava, and nothing angry yet (two at once is a bad day).
    // staring from point blank only makes it blink away
    public static boolean mayAggro(boolean burning, float health, double lavaGap, int angryNow, double targetDistance) {
        return !burning && health >= AGGRO_MIN_HP && lavaGap > AGGRO_LAVA_GAP && angryNow == 0 && targetDistance >= STARE_MIN;
    }

    // how far a calm one can be and still be worth a stare, and how long one stare gets before it counts as ignored
    public static final double STARE_RANGE = 48;
    public static final int STARE_TICKS = 100;
    // vanilla (Enderman$EndermanLookForPlayerGoal): an angry enderman only teleports toward its target from further than this,
    // closer in it walks, and a walk does not cross lava
    public static final double TELEPORT_TOWARDS_RANGE = 16;

    // staring a calm enderman down from where we stand (lava by the foe, so the walk in is off). the bait rules of the nook
    // (alive, calm, in sight, in range) plus mayAggro. across lava it only answers when it can teleport to us, so inside
    // TELEPORT_TOWARDS_RANGE with lava between us the stare is wasted
    public static boolean stareWorthwhile(boolean burning, float health, boolean ourLavaClear, int angryNow, double distance,
                                          boolean alive, boolean creepy, boolean sees, boolean lavaBetween) {
        if (!alive || creepy || !sees || distance > STARE_RANGE) {
            return false;
        }
        if (!mayAggro(burning, health, ourLavaClear ? Double.POSITIVE_INFINITY : 0, angryNow, distance)) {
            return false;
        }
        return distance > TELEPORT_TOWARDS_RANGE || !lavaBetween;
    }

    // the angry one to deal with: the nearest inside the range, -1 for nobody. dead and given up ones come in as NaN
    public static int nearestAngry(double[] distances) {
        int best = -1;
        for (int i = 0; i < distances.length; i++) {
            double d = distances[i];
            if (Double.isNaN(d) || d > ANGRY_RANGE) {
                continue;
            }
            if (best < 0 || d < distances[best]) {
                best = i;
            }
        }
        return best;
    }

    // is there an enderman close enough to wait for, with the old "any tracked one" answer gone
    public static boolean latchSees(double nearestDistance) {
        return nearestDistance <= LATCH_RANGE;
    }

    // the nearest enderman the latch may count: the bait needs line of sight (NookHuntTask.baitFor), so one behind a hill is no reason
    // to dig a nook that idles out and wakes the next one for free. dead ones and ones out of sight come in as NaN, infinity = none
    public static double nearestSeen(double[] distances) {
        double best = Double.POSITIVE_INFINITY;
        for (double d : distances) {
            if (!Double.isNaN(d)) {
                best = Math.min(best, d);
            }
        }
        return best;
    }

    // the hotspot walk is over: close in the plane and not stranded on a hill above (or in a hole under) the forest floor
    public static boolean atHotspot(double dx, double dy, double dz, double radius, double yBand) {
        return dx * dx + dz * dz <= radius * radius && Math.abs(dy) <= yBand;
    }

    // walking into a crowd of endermen is the same call as starting one on purpose
    public static boolean mayWalkIn(float health) {
        return health >= AGGRO_MIN_HP;
    }

    // low hp waits only while it is actually coming back; flat for this long and we hunt anyway
    public static final int HP_FLAT_TICKS = 600;

    // at or under this hp the hunt never starts, rising or not: flat hp this low means nothing is healing us (no food, no regen),
    // and hunting on from there is a shot or a hit from the end. the food trip is what moves us
    public static final float NEVER_HUNT_HP = 6;

    public enum Stance { AWAY, WAIT, HUNT }

    // what the open-field hunt does before it starts one: lava or fire means move (standing still next to either is how a
    // run ends on a watchdog), low hp means wait while hp is rising, everything else hunts
    public static Stance stance(boolean lavaNear, boolean burning, float health, boolean hpRisingRecently) {
        if (lavaNear || burning) {
            return Stance.AWAY;
        }
        if (health <= NEVER_HUNT_HP) {
            return Stance.WAIT;
        }
        return health < AGGRO_MIN_HP && hpRisingRecently ? Stance.WAIT : Stance.HUNT;
    }

    // hp went up within the flat window
    public static boolean hpRising(long now, long lastRiseAt) {
        return now - lastRiseAt <= HP_FLAT_TICKS;
    }

    // lava only counts when it is open to the sky of its own cell: a solid block on top means nothing can reach us from it
    public static boolean lavaExposed(boolean isLava, boolean solidAbove) {
        return isLava && !solidAbove;
    }

    // the combat brain keeps its hands off while the hunt is running and every foe in view is an enderman: running from a
    // teleporter is useless and the nook is the plan
    public static boolean brainStandsDown(boolean huntRunning, int foes, int endermen) {
        return huntRunning && foes > 0 && foes == endermen;
    }

    // the nether watchdog's stall window: the phase default, or longer while the nook hunt sits in its hole
    public static double stallSeconds(boolean inNook, double normal) {
        return inNook ? Math.max(NOOK_STALL_SECONDS, normal) : normal;
    }

    public static final double NOOK_STALL_SECONDS = 300;
}
