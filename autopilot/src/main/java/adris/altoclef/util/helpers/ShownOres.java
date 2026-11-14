package adris.altoclef.util.helpers;

// (the buddy's fork, Tuning poke_take_shown, a fix of 14 Nov 2026, on) the gold mine's poke holes take the wanted ores their own
// digging showed, from where the buddy stands. the collect task was to take them, through the block tracker, and with the
// golden helmet on it took 74% of them within three minutes; before the helmet, 3 of 18: "poke segment 42 ... 7 ores seen, 2
// wanted" and no gold ore broken in 45 minutes (test heel). an ore within TAKE_REACH of the eye, still standing, with a ray
// to it, a pick for it in the bag, and no hostile mob within HOSTILE: broken at once, as a hole's cell; not broken in STUCK
// ticks, left for good. pure, the callers pass what they see
public final class ShownOres {
    // blocks from the eye (squared in the check), and the hostile mob that holds the take back
    public static final double TAKE_REACH = 6;
    public static final double HOSTILE = 12;
    // ticks an ore may take to break, the poke holes' HOLE_STUCK
    public static final long STUCK = 300;

    private ShownOres() {
    }

    public static boolean on() {
        return Tuning.get("poke_take_shown", 0) > 0;
    }

    // an ore the holes showed is taken now: near the eye, still the wanted block, a ray to it, a pick that drops it, not given up
    public static boolean takeable(double distSqFromEye, boolean stillWanted, boolean reach, boolean pickInBag, boolean givenUp) {
        return !givenUp && stillWanted && pickInBag && reach && distSqFromEye <= TAKE_REACH * TAKE_REACH;
    }

    // a hostile mob this near (blocks; negative for none) holds the take back: the pattern goes on, the defence has the tick
    public static boolean held(double nearestHostile) {
        return nearestHostile >= 0 && nearestHostile <= HOSTILE;
    }

    // the take under way is over without the ore: past STUCK, or a clock from another world
    public static boolean stuck(long since, long now) {
        return now < since || now - since > STUCK;
    }
}
