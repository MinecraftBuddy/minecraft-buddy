package adris.altoclef.trace;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

// baritone's debug lines can fire every tick of a pause, and a failing search says the same four things over and over
// with different numbers in them. a line is printed the first time it shows up, repeats of it (same text once the
// numbers are blanked out, so "Took 53ms" and "Took 61ms" are one line) are only counted, and one "<text> x<N> over
// <ticks>t" summary goes out when the run has been going for 5 s, when it went quiet, or when newer lines push it out of
// the handful it remembers. a handful and not just the last one, because a cycle of four lines never repeats back to
// back. pure: the clock and the tick come in as arguments and the lines go out through a callback, after the lock is
// let go of (the path calculation threads log too, and a slow stdout should not hold up the client thread)
public final class DebugCollapse {

    public static final long SUMMARY_EVERY_MS = 5_000;
    // a run that stopped repeating for this long is done, the next one prints its own first line
    public static final long QUIET_MS = 1_000;
    // distinct lines kept. a search is about seven, so this holds one and a bit
    private static final int REMEMBER = 12;

    private static final class Run {
        final String text;
        // repeats nobody has been told about yet
        int extra;
        // 1 when the first occurrence was printed as its own line, 0 for the follow-up after a 5 s summary
        int printed = 1;
        long startMs;
        long startTick;
        long lastMs;
        long lastTick;

        Run(String text, long ms, long tick) {
            this.text = text;
            startMs = lastMs = ms;
            startTick = lastTick = tick;
        }
    }

    // oldest first, so the one that gets pushed out is the one that has been quiet longest
    private final Map<String, Run> runs = new LinkedHashMap<>();
    // the newest message of any kind, for the stand line which wants "what did baritone last say, and when"
    private String latest;
    private long latestTick = Long.MIN_VALUE;

    public void offer(String message, long nowMs, long nowTick, Consumer<String> out) {
        List<String> say = null;
        synchronized (this) {
            latest = message;
            latestTick = nowTick;
            String key = key(message);
            Run run = runs.get(key);
            if (run != null && nowMs - run.lastMs < QUIET_MS) {
                run.extra++;
                run.lastMs = nowMs;
                run.lastTick = nowTick;
                if (nowMs - run.startMs >= SUMMARY_EVERY_MS) {
                    say = summarize(run, say);
                    // still the same run, it just gets a fresh clock
                    run.printed = 0;
                    run.startMs = nowMs;
                    run.startTick = nowTick;
                }
            } else {
                if (run != null) {
                    say = summarize(run, say);
                    runs.remove(key);
                }
                // something new turned up, so whatever was repeating gets its count said before the new line. a cycle
                // never lands here after its first lap, it is made of lines already known
                for (Run other : runs.values()) {
                    if (other.extra > 0) {
                        say = summarize(other, say);
                        other.printed = 0;
                        other.startMs = nowMs;
                        other.startTick = other.lastTick;
                    }
                }
                if (runs.size() >= REMEMBER) {
                    Iterator<Run> oldest = runs.values().iterator();
                    say = summarize(oldest.next(), say);
                    oldest.remove();
                }
                runs.put(key, new Run(message, nowMs, nowTick));
                say = add(say, message);
            }
        }
        send(say, out);
    }

    // call every so often (a tick is fine, it is one empty check most of the time): says the summary of runs that went quiet
    public void flush(long nowMs, Consumer<String> out) {
        List<String> say = null;
        synchronized (this) {
            if (runs.isEmpty()) {
                return;
            }
            Iterator<Run> it = runs.values().iterator();
            while (it.hasNext()) {
                Run run = it.next();
                if (nowMs - run.lastMs >= QUIET_MS) {
                    say = summarize(run, say);
                    it.remove();
                }
            }
        }
        send(say, out);
    }

    public synchronized String latest() {
        return latest;
    }

    // Long.MIN_VALUE when nothing was ever said
    public synchronized long latestTick() {
        return latestTick;
    }

    private static List<String> summarize(Run run, List<String> say) {
        if (run.extra <= 0) {
            return say;
        }
        String line = run.text + " x" + (run.printed + run.extra) + " over " + Math.max(0, run.lastTick - run.startTick) + "t";
        run.extra = 0;
        return add(say, line);
    }

    private static List<String> add(List<String> say, String line) {
        List<String> out = say == null ? new ArrayList<>(2) : say;
        out.add(line);
        return out;
    }

    private static void send(List<String> say, Consumer<String> out) {
        if (say != null) {
            for (String line : say) {
                out.accept(line);
            }
        }
    }

    // digits become #, which is all it takes to make "Took 53ms, 12 movements" the same line every time
    private static String key(String message) {
        StringBuilder sb = new StringBuilder(message.length());
        boolean inNumber = false;
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (c >= '0' && c <= '9') {
                if (!inNumber) {
                    sb.append('#');
                }
                inNumber = true;
            } else {
                inNumber = false;
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
