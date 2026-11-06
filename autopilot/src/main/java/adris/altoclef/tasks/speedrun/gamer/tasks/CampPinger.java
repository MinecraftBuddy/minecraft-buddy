package adris.altoclef.tasks.speedrun.gamer.tasks;

// camping at a spawner makes no inventory change and no chunk change, so the engine's stall timer would call it stuck
// long before RodsWatch gives up. this says "tell the watchdog we are alive" every so often while camping.
// a fresh one per attempt: the clock restarts at 0 on a retry, and an old "last ping" from the previous attempt would
// swallow every ping for minutes
public final class CampPinger {
    private final double everySeconds;
    private double lastPing;

    public CampPinger(double everySeconds) {
        this.everySeconds = everySeconds;
    }

    public boolean ping(double now, boolean camping) {
        if (!camping || now - lastPing < everySeconds) {
            return false;
        }
        lastPing = now;
        return true;
    }
}
