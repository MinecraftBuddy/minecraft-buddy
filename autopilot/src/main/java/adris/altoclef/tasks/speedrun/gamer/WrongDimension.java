package adris.altoclef.tasks.speedrun.gamer;

import baritone.api.utils.Dimension;

// the overworld phases are only ever run from the overworld. the nether trips (death pile, the nether phase itself) are not
// phases of theirs, and GamerTask asks this after those have had their say, so a bot in the nether in one of these is there by
// accident: a portal that fired while we stood in it, a nether trip that ended with us still over there
public final class WrongDimension {
    // PORTAL is left out on purpose, it ends by walking into the nether and the machine hands over on the same tick. EYES runs
    // anywhere, RETURN is the walk home, NETHER is the nether. the End phases are not asked about the nether here
    public static boolean overworldPhase(GamerPhase p) {
        return switch (p) {
            case GATHER, IRON, LOCATE, ROOM, OPEN, END_PREP -> true;
            default -> false;
        };
    }

    public static boolean fires(GamerPhase p, Dimension d) {
        return d == Dimension.NETHER && overworldPhase(p);
    }

    private WrongDimension() {
    }
}
