package adris.altoclef.tasks.speedrun.gamer.end;

// explosion damage to us after difficulty and armor. pure, the crystal gate asks it (see CrystalBlast)
public final class BedSafety {
    private BedSafety() {
    }

    // bed damage scales with difficulty for us (bad_respawn_point is "always"). 0 peaceful .. 3 hard, like Difficulty.getId
    public static double scaled(double raw, int difficulty) {
        return switch (difficulty) {
            case 0 -> 0;
            case 1 -> Math.min(raw / 2 + 1, raw);
            case 3 -> raw * 1.5;
            default -> raw;
        };
    }

    // CombatRules.getDamageAfterAbsorb without the enchants: protection only ever makes this smaller, so it is the safe guess
    public static double afterArmor(double damage, double armor, double toughness) {
        double f = 2 + toughness / 4;
        double kept = Math.max(armor * 0.2, Math.min(armor - damage / f, 20));
        return damage * (1 - kept / 25);
    }
}
