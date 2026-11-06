package adris.altoclef.tasks.speedrun.gamer.end;

// the dragon status line: every few seconds, and right away when the dragon changes what it is doing
public final class DragonLogGate {
    public static final double EVERY_SECONDS = 5;

    private double _last = Double.NEGATIVE_INFINITY;
    private Object _phase;

    public boolean shouldLog(double now, Object phase) {
        boolean changed = !java.util.Objects.equals(phase, _phase);
        if (!changed && now - _last < EVERY_SECONDS) {
            return false;
        }
        _phase = phase;
        _last = now;
        return true;
    }

    public void reset() {
        _last = Double.NEGATIVE_INFINITY;
        _phase = null;
    }
}
