package adris.altoclef.util.helpers;

// (Tuning wither_turn_swing, off: test C4 of 28 Nov 2026) a run that begins with a wither skeleton in reach waits for its swing
// behind the shield before it turns. Of 63 wither skeleton hits in 22 deaths at the spawner (27 Nov), 18 landed in the run's
// first second: the turn walks off while the skeleton's next blow is due, back first. Its blows come 20 ticks apart
// (MeleeAttackGoal), and the client sees each one as the mob's swing (ClientboundSwingAnimationPacket) in the same tick as the
// blow, so the swing is no warning but a clock: a blow taken on a raised shield, then the turn, leaves a whole cycle for the
// sprint. 26.3 blocks only after 5 ticks of use (BlocksAttacks block_delay_seconds 0.25) and only what comes from within 90
// degrees of the head's yaw (LivingEntity.applyItemBlocking), so the hold faces the skeleton (the run's shield guard, as on a
// rim). Any swing ends the hold, blocked or not: the next blow is a cycle away either way (the server counts its own 5 ticks
// from the use packet, so a shield the client calls blocking can still take a blow: smoke of 28 Nov, 4.5 at 4 degrees). Not on
// a rim (its own guard), not at 5 hp or less, not with another melee foe in reach behind the shield's line, not in lava;
// capped at CAP ticks, so a skeleton that never swings cannot pin the run.
public final class WitherTurnHold {
    // centre to centre, on our floor: the skeleton's blow reaches about this far
    public static final double REACH = 2.5;
    public static final double FLOOR = 1.5;
    // the hold ends once the skeleton is this far: it is no longer about to hit
    public static final double LEAVE = 3.5;
    // at or below this the run goes at once: one more blow (4.5 to 4.8 with the wither on top) is the end
    public static final float MIN_HP = 5;
    // 5 ticks to raise the shield and one swing cycle of 20
    public static final int CAP = 25;
    // ticks of use before 26.3 counts the shield as blocking
    public static final int RAISE = 5;
    // a second melee foe this close behind the shield's line ends the hold: the shield cannot face both
    public static final double BEHIND_RANGE = 3;

    public enum End { NONE, BLOCKED, SWUNG, CAP, GONE, FAR, LOW_HP, BEHIND, LAVA, RUN_OVER }

    private int foe = -1;
    private int held;
    private boolean lastSwing;

    public static boolean on() {
        return Tuning.get("wither_turn_swing", 0) > 0;
    }

    // the skeleton the shield is up for, -1 for none
    public int holding() {
        return foe;
    }

    public int held() {
        return held;
    }

    public void reset() {
        foe = -1;
        held = 0;
        lastSwing = false;
    }

    // at a run's start: whether the hold begins on a skeleton this far (centre to centre on the flat) and this high over us
    public static boolean wants(double distance, double dy, float hp, boolean rim, boolean lava, boolean behind) {
        return whyNot(distance, dy, hp, rim, lava, behind) == null;
    }

    // why the hold does not begin, null when it does: for the line of a run start with a skeleton near that is let be
    public static String whyNot(double distance, double dy, float hp, boolean rim, boolean lava, boolean behind) {
        if (distance > REACH) return "out of reach";
        if (Math.abs(dy) > FLOOR) return "not on our floor";
        if (hp <= MIN_HP) return "hp " + Math.round(hp);
        if (rim) return "on a rim";
        if (lava) return "in lava";
        if (behind) return "another one behind";
        return null;
    }

    // swinging: its swing state now, so a swing already under way is not read as the next blow
    public void begin(int foeId, boolean swinging) {
        foe = foeId;
        held = 0;
        lastSwing = swinging;
    }

    // one tick of the hold. run: the commit is still on the run; present: the skeleton is alive and tracked. NONE keeps the
    // shield up; anything else ends the hold (the caller resets it) and the run goes on
    public End step(boolean run, boolean present, double distance, float hp, boolean lava, boolean behind, boolean blocking,
                    boolean swinging) {
        if (foe < 0) return End.GONE;
        boolean edge = swinging && !lastSwing;
        lastSwing = swinging;
        held++;
        if (!run) return End.RUN_OVER;
        if (!present) return End.GONE;
        if (lava) return End.LAVA;
        if (hp <= MIN_HP) return End.LOW_HP;
        if (behind) return End.BEHIND;
        if (distance > LEAVE) return End.FAR;
        if (edge) return blocking ? End.BLOCKED : End.SWUNG;
        if (held >= CAP) return End.CAP;
        return End.NONE;
    }

    // a melee foe at (bx, bz) from us is behind the shield's line when the shield faces the skeleton at (ax, az): more than 90
    // degrees between the two, as applyItemBlocking measures it
    public static boolean behind(double ax, double az, double bx, double bz) {
        return ax * bx + az * bz < 0;
    }

    // the angle in degrees between the head's yaw (degrees, the game's convention) and a source at (dx, dz) from us: over 90 the
    // shield does not block it
    public static double angleTo(float yaw, double dx, double dz) {
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-6) return 0;
        double rad = Math.toRadians(yaw);
        // calculateViewVector(0, yaw): (-sin, 0, cos)
        double vx = -Math.sin(rad);
        double vz = Math.cos(rad);
        double dot = (vx * dx + vz * dz) / len;
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
    }
}
