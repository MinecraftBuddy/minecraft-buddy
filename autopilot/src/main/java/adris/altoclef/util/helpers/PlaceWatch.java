package adris.altoclef.util.helpers;

// a place a task keeps coming back to (the buddy's fork, 10 Nov 2026, look 39): fires once the walker has stayed within
// radius blocks, level distance, of one spot for limit game ticks of the task's own time, then starts again from the next
// spot. time away from the task (a gap of more than gap ticks between two ticks: a meal, a fight, the smelting) starts it
// again too, so only time spent in the task counts. no static state: one per task
public final class PlaceWatch {
    private final double radius;
    private final long limit, gap;
    private boolean has;
    private int ax, az;
    private long since, last;

    public PlaceWatch(double radius, long limit, long gap) {
        this.radius = radius;
        this.limit = limit;
        this.gap = gap;
    }

    // true when it fires: within radius of the spot for longer than limit
    public boolean tick(int x, int z, long now) {
        double dx = x - ax, dz = z - az;
        if (!has || now < since || now - last > gap || dx * dx + dz * dz > radius * radius) {
            has = true;
            ax = x;
            az = z;
            since = now;
            last = now;
            return false;
        }
        last = now;
        if (now - since > limit) {
            has = false;
            return true;
        }
        return false;
    }

    public int x() {
        return ax;
    }

    public int z() {
        return az;
    }
}
