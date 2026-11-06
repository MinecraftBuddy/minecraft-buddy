package adris.altoclef.trace;

// a dump is 300 lines written on the client thread, and a monitor can legitimately fire for something that is just busy
// (slot clicks while crafting get started a lot). so the monitors get one dump a minute between them, the one line flag
// they print is not covered by this. death, stuck and the command are somebody asking, those are never limited
public final class DumpCooldown {
    static final String MONITOR_PREFIX = "monitor:";
    static final long MONITOR_WINDOW_MS = 60_000;

    private final long windowMs;
    private long lastMonitorMs;
    private boolean any;

    public DumpCooldown() {
        this(MONITOR_WINDOW_MS);
    }

    DumpCooldown(long windowMs) {
        this.windowMs = windowMs;
    }

    // true when this dump should go out. a refused dump does not restart the window
    public boolean allow(String reason, long nowMs) {
        if (reason == null || !reason.startsWith(MONITOR_PREFIX)) {
            return true;
        }
        // a clock that went backwards counts as a new window, not as a minute of silence
        if (any && nowMs >= lastMonitorMs && nowMs - lastMonitorMs < windowMs) {
            return false;
        }
        any = true;
        lastMonitorMs = nowMs;
        return true;
    }
}
