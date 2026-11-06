package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.util.helpers.CombatRules;
import adris.altoclef.util.helpers.RecoverHold;

// the narrow night shelter: a respawn with no armor and no bed at night is a skeleton snack on the surface, and a sealed hole in
// the dirt for the rest of the night is the cheap answer. narrow on purpose (a recent death, or hurt with nothing to heal with,
// is part of the trigger): hiding on every first night would make the run a scaredy cat, but half health, no food and no armor
// is the exact state that dies twice outside. every decision is here, ShelterTask does the digging
public final class NightShelter {
    // worn armor points under this and a night on the surface is worth hiding from (a full iron kit is 15, a chestplate alone 6)
    public static final int ARMOR_LINE = 6;
    // ten minutes of game time: a death at dusk still covers the whole night
    public static final long RECENT_DEATH_TICKS = 12000;
    // a surface is this close to the open sky or less (a ditch counts)
    public static final int SURFACE_DEPTH = 1;
    // the game ticks past the morning we are willing to sit in the hole before giving up on the clock
    public static final long SLACK_TICKS = 1200;
    // how many cells the hole goes down, the cap goes in the top one (flush with the ground, it has walls to be clicked against)
    public static final int DEPTH = 3;
    // the cap plus the pillar out of a 3 deep hole (breaking the cap hands its block back, so this is the cap and two more)
    public static final int BLOCKS_NEEDED = 3;

    // a pick walk is up to 300 blocks, about a minute: night falling mid walk with the shelter blocked is the case this covers
    public static final long PILE_LEAD_TICKS = 1200;

    private NightShelter() {
    }

    // is it night as far as the hole is concerned. windowOpen is !isBrightOutside. past the earliest window that wins (a daytime
    // thunderstorm opens it too, time of day says no). the lead counts the last `lead` ticks before the window as night, and
    // that part is clock only because before the window the sky is still bright
    public static boolean dark(boolean windowOpen, long clockTime, long lead) {
        long tod = Math.floorMod(clockTime, NightSleep.DAY_LENGTH);
        if (tod >= NightSleep.EARLIEST_WINDOW) {
            return windowOpen;
        }
        return lead > 0 && tod >= NightSleep.EARLIEST_WINDOW - lead;
    }

    // everything the branch can see. tripNight = a nether or eyes leg that is home in the overworld (the nether phase only lets a food trip through, NetherPhase.nightHold): the trip has no bed to use and
    // was not just killed, so a recent death is not asked. armor still is: a kitted trip walks through the night, a naked one hides. note what is not here: low health and a spent bed trip. a sealed hole is the safest place
    // to regen, and the bed trip's tries have nothing to do with this
    public record Trigger(boolean windowOpen, boolean overworld, int armorPoints, boolean noBed, int depthBelowSky, boolean recoveryActive,
                          boolean fighting, boolean busy, boolean usedThisNight, long ticksSinceDeath, boolean tripNight,
                          float health, int hunger, boolean healFood) {
    }

    public static boolean wanted(Trigger t) {
        return t.windowOpen() && t.overworld() && t.armorPoints() < ARMOR_LINE && t.noBed() && t.depthBelowSky() <= SURFACE_DEPTH
                && !t.recoveryActive() && !t.fighting() && !t.busy() && !t.usedThisNight()
                && (t.tripNight() || recentDeath(t.ticksSinceDeath()) || hurtNoHeal(t.health(), t.hunger(), t.healFood()));
    }

    // really hurt, no armor, and nothing to heal with (an empty belly does not regen): a night outside like that is a second
    // death waiting to happen, so it counts like a death did. one heart down is every first night, that is not it
    public static final float HURT_AT = 12;

    public static boolean hurtNoHeal(float health, int hunger, boolean healFood) {
        return health <= HURT_AT && !CombatRules.canRegen(hunger, healFood);
    }

    // why no column made the cut: how many of the candidates fail each check, for the log line when the hole has nowhere to go
    public static String failCounts(java.util.List<Column> columns) {
        int[] n = new int[8];
        for (Column c : columns) {
            n[0] += c.topDiggable() && c.midDiggable() && c.lowDiggable() ? 0 : 1;
            n[1] += c.floorSolid() ? 0 : 1;
            n[2] += c.floorFluid() ? 1 : 0;
            n[3] += c.fluidNear() ? 1 : 0;
            n[4] += c.wallsSolid() ? 0 : 1;
            n[5] += c.openAbove() ? 0 : 1;
            n[6] += c.capSupply() >= BLOCKS_NEEDED ? 0 : 1;
        }
        return "no column: " + columns.size() + " looked at, " + n[0] + " not diggable, " + n[1] + " no floor, " + n[2] + " fluid floor, "
                + n[3] + " fluid near, " + n[4] + " open walls, " + n[5] + " no open sky, " + n[6] + " short on cap blocks";
    }

    // no bed for tonight: none to be had, or one the trip may not use because it would move the spawn bed (NightSleep.keepsSpawnBed)
    public static boolean noBedTonight(GamerPhase phase, NightSleep.BedSource source, boolean spawnBedStands) {
        return source == NightSleep.BedSource.NONE || NightSleep.keepsSpawnBed(phase, source, spawnBedStands);
    }

    // -1 = no death on record (or the clock went backwards, a relog)
    public static boolean recentDeath(long ticksSinceDeath) {
        return ticksSinceDeath >= 0 && ticksSinceDeath <= RECENT_DEATH_TICKS;
    }

    // dig to walk bounces one attempt may take before it counts as lost (each one is a trip out of the column and back)
    public static final int MAX_BOUNCES = 3;

    public static boolean bounceOver(int bounces) {
        return bounces >= MAX_BOUNCES;
    }

    // ---- the once per night flag, keyed on the day count (NightSleep.nightOf). the bed trip's budget is not touched

    public static final class Once {
        private long night = Long.MIN_VALUE;
        private long retried = Long.MIN_VALUE;

        public boolean used(long nightOf) {
            return night == nightOf;
        }

        // a try started: the night is spent unless it gives up and reopen() hands it back
        public void mark(long nightOf) {
            night = nightOf;
        }

        // a try gave up: the night gets one more at another spot, once. false = the retry was already used
        public boolean reopen(long nightOf) {
            if (night != nightOf || retried == nightOf) {
                return false;
            }
            retried = nightOf;
            night = Long.MIN_VALUE;
            return true;
        }
    }

    // ---- the hole

    // what the world says about one candidate column. top/mid/low are the three cells the hole is made of
    public record Column(boolean topDiggable, boolean midDiggable, boolean lowDiggable, boolean floorSolid, boolean floorFluid,
                         boolean fluidNear, boolean wallsSolid, boolean openAbove, int capSupply) {
    }

    public static boolean columnOk(Column c) {
        return c.topDiggable() && c.midDiggable() && c.lowDiggable() && c.floorSolid() && !c.floorFluid() && !c.fluidNear()
                && c.wallsSolid() && c.openAbove() && c.capSupply() >= BLOCKS_NEEDED;
    }

    // first good column in the order given (ours first, then the nearest ones), -1 = none
    public static int pickColumn(java.util.List<Column> columns) {
        for (int i = 0; i < columns.size(); i++) {
            if (columnOk(columns.get(i))) {
                return i;
            }
        }
        return -1;
    }

    // what a bare hand (or the shovel we may not have) takes down fast. a pick opens up the harder stuff, but never the
    // unbreakable (hardness -1) or obsidian
    public static boolean diggable(boolean shovelMineable, boolean pickMineable, float hardness, boolean havePick) {
        if (hardness < 0 || hardness > 3) {
            return false;
        }
        return shovelMineable || (pickMineable && havePick);
    }

    // the first thing in the bag we may put over our head. a falling block (sand, gravel) would land on us and suffocate, so
    // those are never picked even when they are all we have
    public static int pickCap(boolean[] falling, boolean[] have) {
        for (int i = 0; i < falling.length; i++) {
            if (have[i] && !falling[i]) {
                return i;
            }
        }
        return -1;
    }

    // ---- the wait

    // the morning: the day count moved on from the one we sealed in. day time 0 is the sunrise
    public static boolean morning(long nightWeSealedIn, long dayTime) {
        return NightSleep.nightOf(dayTime) > nightWeSealedIn;
    }

    // game ticks to wait at the most: what is left of the day count plus some slack. game time, not wall time, a lagging
    // client has fewer ticks per second and the night is made of ticks
    public static long waitCap(long dayTime) {
        return NightSleep.DAY_LENGTH - Math.floorMod(dayTime, NightSleep.DAY_LENGTH) + SLACK_TICKS;
    }

    // cap = waitCap taken once when the cap went on: it shrinks as the night goes, asking again each tick would end the wait early
    public static boolean waitOver(long nightWeSealedIn, long dayTime, long ticksSealed, long cap) {
        return morning(nightWeSealedIn, dayTime) || ticksSealed > cap;
    }

    // hit while sealed: whoever it is got in (or an arrow found a gap), mob defense gets its hands back
    public static boolean hurtWhileSealed(float before, float now) {
        return now < before - 0.01f;
    }
}
