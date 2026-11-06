package baritone.pathing.movement;

// the pure half of "a bat is sitting where the block goes, so punch it". PlaceWait waits a mob out and then gives up,
// which is fine for a zombie but a bat never leaves a 1x1 shaft and never gets angry enough for self defense to care.
// the planner then picks the same pillar again, forever. so for a short list of harmless critters we hit them instead
public final class HitBlocker {
    // ticks of hitting before we fall back to giving up. a bat is 6 hp and the hand holds a block (1 damage), one hit per
    // SWING_GAP is 50 ticks, plus room for a few hits that lag eats. kept under PathExecutor's movement timeout (cost +
    // movementTimeoutTicks) so it's us giving up with a reason, not the executor yanking the movement
    public static final int HIT_PATIENCE = 90;
    // a mob that just got hurt ignores the next 10 ticks of equal damage (invulnerableTime), swinging faster does nothing
    public static final int SWING_GAP = 10;

    // what is standing in the cell
    public enum Cell {
        // nobody, place away
        CLEAR,
        // only critters we may hit, and at least one still alive
        HIT,
        // only critter corpses. a dying mob keeps blocking until its death animation is done (20 ticks), punching it
        // does nothing and it leaves on its own
        CLEARING,
        // something we won't hit, PlaceWait's problem
        BLOCKED
    }

    // what the movement should do about it this tick
    public enum Action {
        PLACE,
        HIT,
        WAIT,
        GIVE_UP
    }

    private HitBlocker() {
    }

    // bats, chickens, rabbits. nothing that fights back, nothing someone owns. a named or leashed one is somebody's pet,
    // and anything riding or being ridden is a jockey or a player's thing, not ours to punch. out of reach is out too,
    // the server would just drop the attack
    public static boolean hittable(boolean critter, boolean named, boolean leashed, boolean riding, boolean inReach) {
        return critter && !named && !leashed && !riding && inReach;
    }

    // one stubborn blocker spoils it for everyone, same rule as the server's "is anything in the way"
    public static Cell cell(int blockers, int stubborn, int alive) {
        if (blockers <= 0) return Cell.CLEAR;
        if (stubborn > 0) return Cell.BLOCKED;
        return alive > 0 ? Cell.HIT : Cell.CLEARING;
    }

    // full cooldown and the last hit's invulnerability is over. lastSwing is a long so "never swung" can be way back
    public static boolean swingNow(float attackStrength, long now, long lastSwing) {
        return attackStrength >= 1 && (now - lastSwing >= SWING_GAP || now < lastSwing);
    }
}
