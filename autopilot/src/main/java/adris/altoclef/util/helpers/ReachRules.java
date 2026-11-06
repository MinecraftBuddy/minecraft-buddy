package adris.altoclef.util.helpers;

// how close is close enough to hit. eye to the nearest point of the target's box, which is how vanilla measures. the old
// feet to feet line misjudges a mob in our own cell, straight above (the eye is 1.62 up, so it is nearer than its feet),
// straight below (we hit the top of its head, so it is farther) and a wide box (a spider's near face is closer than its feet)
public final class ReachRules {

    // a plain player's interaction range: contact. the corner rules use it as "it is on us", where the setting's looser
    // reach would call a zombie across a gap contact
    public static final double MELEE_REACH = 3.0;

    // the kill tasks' distances, feet to feet, innermost first. they have to nest or one rule undoes the next: closer than
    // BACK_OFF_START we step back (only with no swing due and no hop planned), the approach walks to within
    // KILL_APPROACH, a hop needs the gap inside reach minus CritTiming.JUMP_MARGIN, a swing needs it inside reach
    public static final double BACK_OFF_START = 1.25;
    // the back-off walks this far out and stops, still well inside hop range
    public static final double BACK_OFF_TO = 2.0;
    // a hair over two so the cell two straight out of a centred mob counts. GoalFollowEntity goes by block centres
    public static final double KILL_APPROACH = 2.25;

    // crystals only. the server lets a hit through out to the 3 block interaction range plus its 3 block buffer, eye to box.
    // the covered stand under a radius 4 pillar is ~4 out, so the old 4 was a coin flip and 5 still leaves the server slack
    public static final double CRYSTAL_REACH = 5.0;

    private ReachRules() {
    }

    public static double gap(double eyeX, double eyeY, double eyeZ,
                             double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double dx = Math.max(Math.max(minX - eyeX, 0), eyeX - maxX);
        double dy = Math.max(Math.max(minY - eyeY, 0), eyeY - maxY);
        double dz = Math.max(Math.max(minZ - eyeZ, 0), eyeZ - maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static boolean inReach(double gap, double reach) {
        return gap <= reach;
    }

    // the reach a swing really has: the setting, but never past the interaction range the server gives us
    public static double swingReach(double setting, double interactionRange) {
        return Math.min(setting, interactionRange);
    }

    // the gap is the shortest line to the box, the swing goes down the ray to where CombatAim settles on it, and that
    // ray is longer (a mob off at an angle or up a slope adds a tenth or so). planning from the very edge of reach would
    // stand us where the aimed ray ends just short of the mob
    public static final double KILL_REACH_MARGIN = 0.3;

    // the kill tasks' reach: vanilla's measure (eye to box) inside the swing's own reach, less the margin above. it used to
    // be the feet line OR the gap inside the setting (4), past what a player's arm reaches (3) and a swing that has to be
    // aimed now refuses
    public static boolean killReach(double gap, double setting, double interactionRange) {
        return inReach(gap, swingReach(setting, interactionRange) - KILL_REACH_MARGIN);
    }
}
