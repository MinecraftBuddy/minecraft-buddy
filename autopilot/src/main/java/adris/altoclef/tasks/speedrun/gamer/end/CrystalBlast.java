package adris.altoclef.tasks.speedrun.gamer.end;

// what a popped end crystal does to us: vanilla's falloff (ExplosionDamageCalculator.getEntityDamageAmount) at power 6, then
// difficulty and armor the same way the bed math does it. point blank that is 85 raw and armor 16 takes ~13% off, so the
// only swing worth taking is one where its own pillar eats the whole thing for us
public final class CrystalBlast {
    public static final double POWER = 6;
    // a fully covered blast still does its flat 1 (0.4 through iron). over 2 means some of it can see us, and some of 50 is a lot
    public static final double MAX_SWING_DAMAGE = 2;

    private CrystalBlast() {
    }

    // distance is from our feet to the crystal's feet (where it goes off), seen is getSeenPercent's 0..1
    public static double raw(double distance, double seen) {
        double d = distance / (2 * POWER);
        // past twice the power the explosion doesn't even look at us
        if (d > 1) {
            return 0;
        }
        double i = (1 - d) * seen;
        return (i * i + i) / 2 * 7 * (2 * POWER) + 1;
    }

    // player_explosion scales with difficulty like the bed does, then armor. no blast protection: it only ever helps
    public static double predicted(double distance, double seen, int difficulty, double armor, double toughness) {
        return BedSafety.afterArmor(BedSafety.scaled(raw(distance, seen), difficulty), armor, toughness);
    }

    public static boolean safeToSwing(double predicted) {
        return predicted <= MAX_SWING_DAMAGE;
    }
}
