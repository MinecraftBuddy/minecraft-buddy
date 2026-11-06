package adris.altoclef.trace;

import java.util.HashSet;
import java.util.Set;

// the monitors swallow whatever they throw so a log helper can never take down a tick, which also meant a broken one went
// quiet for good and looked exactly like a healthy one with nothing to say. the first throw from each monitor gets a
// line, the rest stay swallowed. nothing is allocated until something actually threw
public final class MonitorErrors {

    private final Set<String> told = new HashSet<>();

    // the line to log, or null when this monitor already told us
    public synchronized String first(String monitor, Throwable error) {
        if (!told.add(monitor)) {
            return null;
        }
        return "MONITOR: " + monitor + " threw " + error.getClass().getSimpleName() + ": " + error.getMessage() + " (said once, it keeps being skipped)";
    }
}
