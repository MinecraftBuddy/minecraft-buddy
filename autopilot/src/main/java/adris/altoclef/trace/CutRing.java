package adris.altoclef.trace;

// who keeps cutting off whom. a task stopping is a rare event next to a tick, so this keeps the last few hundred of them
// in plain arrays (no allocation when one is written) and only does the counting when a storm is flagged, to put a name
// on the other side of the fight. victim = the task class that was stopped, cutter = the class that took over from it
// (or the class of the parent that stopped asking for it). pure, the clock is an argument
public final class CutRing {

    public static final int CAPACITY = 256;
    // not worth a name below this many
    private static final int MIN_COUNT = 3;

    public record Culprit(Class<?> by, boolean parent, int count) {
    }

    private final Class<?>[] victims = new Class<?>[CAPACITY];
    private final Class<?>[] cutters = new Class<?>[CAPACITY];
    private final boolean[] byParent = new boolean[CAPACITY];
    private final long[] times = new long[CAPACITY];
    private int next;
    private int size;

    public void record(Class<?> victim, Class<?> cutter, boolean parent, long nowMs) {
        victims[next] = victim;
        cutters[next] = cutter;
        byParent[next] = parent;
        times[next] = nowMs;
        next = (next + 1) % CAPACITY;
        if (size < CAPACITY) {
            size++;
        }
    }

    // the cutter that stopped this class most inside the window, or null when nobody did it often enough to blame
    public Culprit mostCommon(Class<?> victim, long nowMs, long windowMs) {
        Culprit best = null;
        for (int i = 0; i < size; i++) {
            if (!matches(i, victim, nowMs, windowMs)) {
                continue;
            }
            int count = 0;
            for (int j = 0; j < size; j++) {
                if (matches(j, victim, nowMs, windowMs) && cutters[j] == cutters[i] && byParent[j] == byParent[i]) {
                    count++;
                }
            }
            if (count >= MIN_COUNT && (best == null || count > best.count())) {
                best = new Culprit(cutters[i], byParent[i], count);
            }
        }
        return best;
    }

    private boolean matches(int i, Class<?> victim, long nowMs, long windowMs) {
        return victims[i] == victim && cutters[i] != null && nowMs - times[i] <= windowMs;
    }
}
