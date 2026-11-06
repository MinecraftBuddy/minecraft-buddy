package adris.altoclef.tasks.movement;

// baritone only tells us "a search came back empty" as a counter that goes up. this turns that into "did it happen
// since I handed over my goal", once per failure, so a task can give up on a target that cannot be pathed to
// instead of re-searching it forever (a cod in a pond was worth 5s of standing around per attempt)
final class CalcFailureWatch {
    private int seen = -1;

    // call when we hand baritone a goal
    void arm(int failuresNow) {
        seen = failuresNow;
    }

    // call when we take the goal back ourselves or start over
    void disarm() {
        seen = -1;
    }

    // true once for every failure that landed after we armed. failures from before that are not ours
    boolean failed(int failuresNow) {
        if (seen < 0 || failuresNow <= seen) return false;
        seen = failuresNow;
        return true;
    }
}
