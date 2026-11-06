package adris.altoclef.tasks.speedrun.gamer;

// how the running cook task reaches the run's state. the task lives in the alto layer and cannot see the gamer, so GamerTask binds the
// run's RunState.cook here when it starts and lets go when it stops (same seam as AsyncSmelting.watchJobs). the state itself is in
// RunState and the rules are in FurnacePlan, so a fresh run starts with a fresh one and nothing outlives it.
// the facts read RunState.cook directly. two things are kept in there:
// the backoff. the cook cannot fail out of a need (nothing in the kit loop knows how), so when it has been going nowhere it parks
// itself and the planner stops asking for a while.
// the station it picked. making a smoker eats the logs and cobble that made the planner say "smoker" in the first place, and
// placing one is a tick where it is neither in the bag nor in placedSmokers. without this the need flipped to furnace (or to
// nothing) halfway through and the task got rebuilt from scratch
public final class CookTrip {
    private static volatile RunState.Cook live;

    private CookTrip() {
    }

    public static void bind(RunState.Cook cook) {
        live = cook;
    }

    public static void unbind() {
        live = null;
    }

    // not bound (plain alto, no run) means nobody is listening, and the cook has nothing to tell
    public static void suspend(long now) {
        RunState.Cook cook = live;
        if (cook != null) {
            FurnacePlan.cookSuspend(cook, now);
        }
    }

    public static void commit(boolean smoker, long now) {
        RunState.Cook cook = live;
        if (cook != null) {
            FurnacePlan.cookCommit(cook, smoker, now);
        }
    }

    public static void release() {
        RunState.Cook cook = live;
        if (cook != null) {
            FurnacePlan.cookRelease(cook);
        }
    }
}
