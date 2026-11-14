package adris.altoclef.util.helpers;

// (the buddy's fork, Tuning witch_cover, witch_melee_reach and poison_eat, fixes of 14 Nov 2026, on) the overworld's witches, the
// rules with no minecraft in them: the brain (CombatBrain) asks them, SkelCover.Hold holds the cover, CombatCommit keeps the
// LOBBER run and the melee, FoodChain the golden apple. test heel since 13 Nov: 14 deaths to a witch, 11 at the gold mine. the
// witch throws poison first (about 1 hp a 1.25 s, never under 1 hp), then harming every 3 s while it is within 10 with a line;
// the poison took 10 to 17 hp of each loss and one or two harming hits of 4 to 6 finished it, 15 to 30 s from the first tick.
// the run from it (Why.LOBBER) ran 24 blocks from the buddy's own spot, in a tunnel along the witch's line, ended clear at 12
// with the witch following to 16 and throwing from 10, and the heal-up after it stood still while the witch walked back; no
// bite while it had a line (a shooter), and no swing at 1 m with the run stuck (witch_no_melee)
public final class WitchRules {
    private WitchRules() {
    }

    // (witch_cover) a witch's hit, or a poison tick with a witch about, is the skeleton cover: two down with the top shut, the
    // pillbox from the bag where the ground will not dig (the witch throws only with a line, and a shut top stops an arcing
    // splash); out once the poison is over, the hp is back to the cover's rule and no witch has had a line for 5 s
    public static boolean coverOn() {
        return Tuning.get("witch_cover", 0) > 0;
    }

    // (witch_melee_reach) a witch within reach is a melee foe: a sword or an axe at hand and no other shooter with a line, or any
    // hand once the run is cornered (or the cover could not keep it off)
    public static boolean meleeReachOn() {
        return Tuning.get("witch_melee_reach", 0) > 0;
    }

    // (poison_eat) poisoned, a witch 5 or more off or with no line keeps no meal shut; a golden apple under 10 hp
    public static boolean poisonEatOn() {
        return Tuning.get("poison_eat", 0) > 0;
    }

    // a witch this close counts for the cover (it throws from 10 and walks in to that; its follow range is 16)
    public static final double RANGE = 16;
    // a witch's hit this fresh takes the cover (10 s, the hit memory: the poison's ticks blame it again while it has a line)
    public static final long HIT_TICKS = CombatCommit.HIT_MEMORY;
    // (poison_eat) a witch closer than this with a line still keeps a bite shut: its next throw lands before the bite is down
    public static final double EAT_CLOSE = 5;
    // (poison_eat) under this hp while poisoned the golden apple goes down (its regen outheals the poison)
    public static final float GAPPLE_HP = 10;

    // (witch_cover) the cover is wanted for a witch: the overworld, alive, a witch within RANGE, and a witch's hit lately or the
    // poison on
    public static boolean coverWanted(boolean overworld, float hp, boolean hitLately, boolean poisoned, double nearest) {
        return overworld && hp > 0 && nearest <= RANGE && (hitLately || poisoned);
    }

    // (witch_melee_reach) this witch is a melee foe: the key, within reach, and a sword or an axe at hand with no other shooter
    // (a skeleton, a stray, another witch) with a line on the buddy within 16; stuck (the run cornered there, the cover hit
    // through), any hand. the 13 Nov rule (witch_no_melee) came from a fight with skeletons about: one went 20 hp to 1 in 6 s
    public static boolean melee(boolean key, boolean inReach, boolean blade, boolean otherLine, boolean stuck) {
        if (!key || !inReach) return false;
        return stuck || (blade && !otherLine);
    }

    // (poison_eat) this witch keeps a meal shut: with the key off or no poison on, as before (every shooter in range with a line);
    // poisoned, only one closer than EAT_CLOSE with a line
    public static boolean keepsMealShut(boolean key, boolean poisoned, double distance, boolean line) {
        if (!key || !poisoned) return true;
        return distance < EAT_CLOSE && line;
    }

    // (poison_eat) the golden apple: poisoned under GAPPLE_HP with one in the bag, where a meal may go down at all
    public static boolean gapple(boolean key, boolean poisoned, float hp, boolean hasGapple, boolean mayEat) {
        return key && poisoned && hp < GAPPLE_HP && hasGapple && mayEat;
    }
}
