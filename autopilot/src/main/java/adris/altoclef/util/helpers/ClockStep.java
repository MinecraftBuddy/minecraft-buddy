package adris.altoclef.util.helpers;

// the buddy's fork, Tuning clock_no_step_back (a fix of 19 Nov 2026, on): the client's game time never steps back. the client
// counts a tick of its own every tick and the server's time packet sets it to the server's count; when the client ran ahead
// in a catch-up burst (21 to 32 ticks a second against the server's 17.8), the packet set it back a few ticks. every timer
// that reads "now < since" as "a new start" then began again: the poke mine started a new run 77 times in 12 minutes, about
// 20000 times on the four rigs that day (look 150). in place of the step back the client's count holds still for as many
// ticks, so it stays monotonic and meets the server's again (MixinClientLevelClock)
public final class ClockStep {

    // a step back of more than this is a different clock (a world joined again), taken as it comes
    public static final long MAX_HOLD = 1200;

    private ClockStep() {
    }

    public static boolean on() {
        return Tuning.get("clock_no_step_back", 0) > 0;
    }

    // the ticks to hold the client's count still in place of setting it back to the server's: 0 when the server's is not
    // behind (set it), -1 when it is behind by more than MAX_HOLD (set it all the same)
    public static long hold(long current, long fromServer) {
        if (fromServer >= current) return 0;
        long back = current - fromServer;
        return back <= MAX_HOLD ? back : -1;
    }
}
