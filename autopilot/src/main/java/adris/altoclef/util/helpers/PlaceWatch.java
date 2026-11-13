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

    // the fires of a watch, counted: true once count of them fell within window ticks of each other and within radius
    // blocks, level distance, of the latest. a place the watch keeps firing at is one the task does not get out of
    public static final class Fires {
        private final int count;
        private final long window;
        private final double radius;
        private final java.util.ArrayDeque<long[]> fires = new java.util.ArrayDeque<>();
        private int last;

        public Fires(int count, long window, double radius) {
            this.count = count;
            this.window = window;
            this.radius = radius;
        }

        // a fire at (x, z): true when it makes count
        public boolean add(int x, int z, long now) {
            fires.removeIf(f -> now < f[0] || now - f[0] > window);
            fires.addLast(new long[]{now, x, z});
            int n = 0;
            for (long[] f : fires) {
                double dx = f[1] - x, dz = f[2] - z;
                if (dx * dx + dz * dz <= radius * radius) n++;
            }
            last = n;
            return n >= count;
        }

        // the fires near the latest at the last add
        public int last() {
            return last;
        }

        public void clear() {
            fires.clear();
            last = 0;
        }
    }
}
