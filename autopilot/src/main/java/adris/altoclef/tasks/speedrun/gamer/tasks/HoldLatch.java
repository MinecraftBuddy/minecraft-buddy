package adris.altoclef.tasks.speedrun.gamer.tasks;

// a yes that sticks for a while after it stops being true. an enderman walks behind a pillar and the entity tracker
// forgets it, which used to flip the phase between sweeping and hunting every other tick. pure, now is any monotonic
// clock in seconds
public final class HoldLatch {
    private final double holdSeconds;
    private double lastTrueAt = Double.NEGATIVE_INFINITY;

    public HoldLatch(double holdSeconds) {
        this.holdSeconds = holdSeconds;
    }

    public boolean update(boolean raw, double now) {
        if (raw) {
            lastTrueAt = now;
            return true;
        }
        return now - lastTrueAt < holdSeconds;
    }

    public void reset() {
        lastTrueAt = Double.NEGATIVE_INFINITY;
    }
}
