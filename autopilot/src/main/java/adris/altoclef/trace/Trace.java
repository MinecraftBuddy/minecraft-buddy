package adris.altoclef.trace;

import adris.altoclef.BuildInfo;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

// the flight recorder. Debug feeds every line it prints into the ring, dump() writes the lead up back out when
// something goes wrong. never throws into the caller: this is on the tick path and a log helper has no business
// taking the bot down
public final class Trace {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final TraceRing RING = new TraceRing();

    private static volatile DumpCooldown cooldown = new DumpCooldown();

    // where the dump goes. a seam so a test can listen without scraping stdout
    private static volatile Consumer<String> sink = System.out::println;

    private Trace() {
    }

    // takes the finished string, nothing is built here beyond the timestamp
    public static void record(String line) {
        if (line == null) {
            return;
        }
        try {
            RING.add(STAMP.format(LocalTime.now()) + " " + line);
        } catch (RuntimeException ignored) {
            // see the class comment
        }
    }

    // straight to the sink and not through Debug, so the dump does not land in the ring it is printing
    public static void dump(String reason) {
        dump(reason, System.currentTimeMillis());
    }

    // the clock comes in so a test does not have to wait a minute
    static void dump(String reason, long nowMs) {
        try {
            if (!cooldown.allow(reason, nowMs)) {
                return;
            }
            Consumer<String> out = sink;
            List<String> lines = RING.snapshot();
            out.accept("TRACE DUMP (" + reason + ") build " + BuildInfo.id());
            for (String line : lines) {
                out.accept(line);
            }
            out.accept("END TRACE DUMP (" + reason + ") " + lines.size() + " lines");
        } catch (RuntimeException e) {
            // record() stays silent (reporting goes through Debug, which feeds it), a dump that broke can say so
            adris.altoclef.Monitors.broke("trace dump", e);
        }
    }

    public static List<String> snapshot() {
        return RING.snapshot();
    }

    // tests only
    static void forgetCooldown() {
        cooldown = new DumpCooldown();
    }

    // tests only
    public static void setSink(Consumer<String> to) {
        sink = to == null ? System.out::println : to;
    }
}
