package adris.altoclef.util.helpers;

// the buddy's fork (S137, the repair after smoke6): the burn still to come. the client never learns how long the buddy will
// burn: the server keeps the fire ticks and sends only the on-fire flag, and the client clears its own copy every tick. so
// this clock keeps the time from what lit the buddy, as the server sets it (Entity.igniteForSeconds, at least what was left):
// a blaze's small fireball 5 s, a fire block 8 s (and more while in it), lava 15 s (held while in it), each times the burning
// time attribute (fire protection). a burn takes 1 hp each second left, armour does not stop it, fire resistance does.
// smoke6 of S137 step 3: fire took 42% of phase 4's lost hp, and three of its four deaths burned while the meter read calm,
// fight or cover at 4 to 10 hp, the buddy going after the next blaze alight. pure: the game side (DangerShadow) hands in the
// flag and the causes it saw each tick
public final class BurnClock {
    public static final int FIREBALL_TICKS = 100;
    public static final int FIRE_TICKS = 160;
    public static final int LAVA_TICKS = 300;
    // the flag came on with no cause seen: a blaze's fireball is the common one (the damage event and the flag can come a tick
    // apart, and a cause seen later still raises it)
    public static final int UNKNOWN_TICKS = FIREBALL_TICKS;
    // while the flag holds the burn has at least this left: the server clears the flag only when its ticks run out
    public static final int FLOOR_TICKS = 20;

    private int ticks;
    private boolean was;

    // one game tick. onFire: the flag; lit: the most ticks a cause seen this tick sets (0: none); scale: the burning time
    // attribute (1 without fire protection). the ticks left after it
    public int step(boolean onFire, int lit, double scale) {
        if (!onFire) {
            ticks = 0;
            was = false;
            return 0;
        }
        double s = Double.isFinite(scale) ? Math.max(0, scale) : 1;
        if (ticks > 0) ticks--;
        int set = (int) Math.ceil(lit * s);
        if (!was && set <= 0) set = (int) Math.ceil(UNKNOWN_TICKS * s);
        ticks = Math.max(Math.max(ticks, set), FLOOR_TICKS);
        was = true;
        return ticks;
    }

    public int ticks() {
        return ticks;
    }

    public void reset() {
        ticks = 0;
        was = false;
    }

    // the hp a burn of this many ticks still takes: one at each whole second left (the server hurts when its ticks left are a
    // multiple of 20), none under fire resistance
    public static double hp(int ticks, boolean fireRes) {
        if (fireRes || ticks <= 0) return 0;
        return Math.ceil(ticks / 20.0);
    }

    // (Tuning witch_no_melee, a fix of 13 Nov 2026, on under risk_flee) the hp a poison still takes: a point each 25 ticks at level I,
    // halved for each level above (at least each tick), never the last one (poison stops at 1 hp). unlike the burn the client
    // knows the poison's time left and level itself. a witch's poison took the buddy from 20 to 1 in 6 s with its harming
    // potions while the meter's hp left read the hp as it was (M3.3's smoke 5, run 1)
    public static double poisonHp(int ticksLeft, int amplifier, double hp) {
        if (ticksLeft <= 0 || hp <= 1) return 0;
        int every = Math.max(1, 25 >> Math.max(0, amplifier));
        return Math.min(ticksLeft / every, Math.floor(hp - 1));
    }
}
