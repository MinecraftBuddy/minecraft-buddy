package adris.altoclef.tasks.construction;

// what DestroyBlockTask does while it is trying to get beside a block it won't mine from on top of and has nothing to swing
// at yet: keep trying, let go of the back-up-and-crouch keys, or give the block up. plain numbers in, so it can be tested
// without a world
public final class FromSideRules {

    public enum Verdict {
        KEEP_TRYING,
        RELEASE_KEYS,
        GIVE_UP
    }

    // seconds of this branch spent with no live path and no crack. the checker this replaces was reset by anything that
    // looked like movement, this one is only cleared by a swing that really cracks the block
    public static final double IDLE_GIVE_UP_SECONDS = 10;
    // seconds in the branch whatever the path is doing. a walk to a side cell far away is allowed to be long, but a path
    // that keeps ending somewhere useless and getting replanned is not allowed to be forever
    public static final double TOTAL_GIVE_UP_SECONDS = 45;
    // a back-up is a nudge to get a ray, not a grind against the wall behind us. ten ticks in all (until the block really
    // cracks) is about two thirds of a block of crouch walking
    public static final int BACK_UP_MAX_TICKS = 10;

    private FromSideRules() {
    }

    // idleSeconds / totalSeconds: the branch clocks. pathing: baritone has a live path. searchFailed: a search came back
    // empty a moment ago (so "not pathing" means "nowhere to go", not "between two paths"). cracking: the break of this
    // block is under way. backedUpTicks: ticks the keys have been held since the last real crack
    public static Verdict decide(double idleSeconds, double totalSeconds, boolean pathing, boolean searchFailed,
                                 boolean cracking, int backedUpTicks) {
        if (cracking) {
            return Verdict.KEEP_TRYING;
        }
        if (idleSeconds > IDLE_GIVE_UP_SECONDS || totalSeconds > TOTAL_GIVE_UP_SECONDS) {
            return Verdict.GIVE_UP;
        }
        if (!pathing && (searchFailed || backedUpTicks >= BACK_UP_MAX_TICKS)) {
            return Verdict.RELEASE_KEYS;
        }
        return Verdict.KEEP_TRYING;
    }
}
