package adris.altoclef.util.helpers;

// "is there anywhere to run". a hole one block wide with a zombie in it is boxed in, a tunnel with two empty ends is not (it
// is a corridor, and a run along it is a fine run). pure, CombatBrain reads the world and hands the answers over
public final class BoxRules {

    private BoxRules() {
    }

    // one entry per horizontal side. open: feet and head cell both walkable (a wall at either one is a wall: we do not squeeze
    // through a 1 high gap while a mob chews on us). filled: a hostile's box sits in that side's cell, so it is shut for now
    public static boolean boxedIn(boolean[] open, boolean[] filled) {
        for (int i = 0; i < open.length; i++) {
            if (open[i] && !filled[i]) return false;
        }
        return true;
    }
}
