package adris.altoclef.tasks.speedrun.gamer.tasks;

// "are we getting anywhere on this walk": the best distance to the target has to improve by MIN_GAIN every WINDOW_SECONDS
// or the walk is a stand. pure, the caller feeds it the clock and the distance. the walk tasks never report "no path",
// they just stand there, so distance is the only honest signal
public final class WalkProgress {
    public static final double MIN_GAIN = 4;
    public static final double WINDOW_SECONDS = 45;
    // not asked for this long = something else had the wheel, the old best means nothing
    private static final double IDLE_SECONDS = 5;

    private final double minGain;
    private final double windowSeconds;
    private double best = Double.NaN;
    private double since;
    private double lastAt = Double.NaN;

    public WalkProgress() {
        this(MIN_GAIN, WINDOW_SECONDS);
    }

    public WalkProgress(double minGain, double windowSeconds) {
        this.minGain = minGain;
        this.windowSeconds = windowSeconds;
    }

    public void reset() {
        best = Double.NaN;
        lastAt = Double.NaN;
    }

    // once per tick that we are walking at the target. true = no gain for a whole window
    public boolean stalled(double now, double dist) {
        if (Double.isNaN(best) || now - lastAt > IDLE_SECONDS) {
            best = dist;
            since = now;
        } else if (dist <= best - minGain) {
            best = dist;
            since = now;
        }
        lastAt = now;
        return now - since >= windowSeconds;
    }
}
