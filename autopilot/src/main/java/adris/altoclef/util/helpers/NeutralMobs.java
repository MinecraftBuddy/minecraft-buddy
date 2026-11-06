package adris.altoclef.util.helpers;

import java.util.Set;

// the pure half of "is this thing actually after us, or just standing there looking hostile". no world, entity types
// come in as registry paths ("zombified_piglin"), so the whole table can be tested without minecraft.
//
// the bug this exists for: isAggressive() was the only question asked, so a zombified piglin that had been poked by
// anything (or one the bot tapped) read as "hostile", the bot walked into the group and the group killed it. neutral
// mobs only count once they have a reason: we hit them, they hit us, they say they are aiming at us, or one is
// swinging from right next to us
public final class NeutralMobs {
    // we hit it or it hit us, this long ago still counts. a zombified piglin stays angry for 20 to 39 s
    public static final long MEMORY_TICKS = 600;
    // an aggressive neutral this close is hitting us whatever the history says
    public static final double CLOSE = 3.5;
    // a zombie only has its aggressive flag up for 9 ticks out of every 20 while it chews on us, and it is down on the
    // hit tick itself. two attack intervals of "it just bit us" papers over the off ticks without turning into a grudge
    public static final long ANGRY_AFTER_HIT = 40;

    // mind their own business until poked
    private static final Set<String> NEUTRAL = Set.of("zombified_piglin", "wolf", "bee", "iron_golem", "polar_bear",
            "llama", "trader_llama", "dolphin", "goat", "panda");
    // only hostile in the dark (the spider target goal asks the light level, daylight turns it off)
    private static final Set<String> DAYLIGHT_NEUTRAL = Set.of("spider", "cave_spider");

    private NeutralMobs() {
    }

    // is this type calm right now. piglins are neutral while we wear gold, spiders while the sun is up
    public static boolean neutralNow(String type, boolean bright, boolean wearingGold) {
        if (NEUTRAL.contains(type)) return true;
        if (DAYLIGHT_NEUTRAL.contains(type)) return bright;
        return type.equals("piglin") && wearingGold;
    }

    // a neutral mob that has a reason to be after us
    public static boolean afterUs(boolean provoked, boolean targetsUs, boolean aggressive, double distance) {
        return provoked || targetsUs || (aggressive && distance <= CLOSE);
    }

    // the whole call. a mob that is not neutral keeps the old rule (the aggressive flag), a neutral one needs a reason
    public static boolean hostile(boolean neutral, boolean aggressive, boolean provoked, boolean targetsUs, double distance) {
        if (!neutral) return aggressive;
        return afterUs(provoked, targetsUs, aggressive, distance);
    }

    // same call, plus "it hit us a moment ago". the aggressive flag blinks off for half of every zombie swing, so a mob
    // that is busy biting us dropped off the hostile list on the off ticks. neutral ones already count a hit as a reason
    // through provoked, so they don't need it
    public static boolean hostile(boolean neutral, boolean aggressive, boolean provoked, boolean targetsUs, double distance,
                                  boolean hitRecently) {
        if (!neutral) return aggressive || hitRecently;
        return hostile(true, aggressive, provoked, targetsUs, distance);
    }

    // sinceHit straight from Provocations, NEVER included
    public static boolean hitRecently(long sinceHit) {
        return sinceHit >= 0 && sinceHit <= ANGRY_AFTER_HIT;
    }
}
