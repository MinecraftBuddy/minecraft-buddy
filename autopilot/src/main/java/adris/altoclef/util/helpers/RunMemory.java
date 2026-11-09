package adris.altoclef.util.helpers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

// what can outlive an order: the trackers' positions, Baritone's cached world, a stand or a frame a task keeps in a static.
// each such memory registers here once, with a name, its size (positions, entries, 0 or 1 for one remembered thing) and how
// it is forgotten. at every order's start (orderStart) the sizes go into one measure line, and with fresh_run on
// (Tuning; the practice rig, whose world is put back after each run while the game plays on) all of it is forgotten then,
// so each run is a first visit. off, nothing is cleared: a real buddy keeps what it learnt. a new memory is one register
// call; RunMemoryGuardTest fails on a static that is neither registered here nor allowed with a reason
public final class RunMemory {
    public static final String SWITCH = "fresh_run";

    // name, size, forget, and the static fields it covers ("adris.altoclef.Some#field", for the guard test; none for a
    // memory held by an instance, such as a tracker of the one AltoClef)
    public record Memory(String name, LongSupplier size, Runnable clear, List<String> fields) {
    }

    private static final Map<String, Memory> MEMORIES = new LinkedHashMap<>();

    static {
        RunMemoryFork.register();
    }

    private RunMemory() {
    }

    // a second registration under a name replaces the first (a reload of the buddy's list), it never adds a twin
    public static synchronized void register(String name, LongSupplier size, Runnable clear, String... fields) {
        MEMORIES.put(name, new Memory(name, size, clear, List.of(fields)));
    }

    public static synchronized Collection<Memory> all() {
        return new ArrayList<>(MEMORIES.values());
    }

    // the run token cleared last: an order that sees the same token again (the rig's ORDER2, a second order in the same run)
    // keeps what the run's first order learnt
    private static double lastToken;

    // the switch as the file says it now (the rig writes it just before the run). 0: off. 1: every order starts fresh.
    // above 1: a run token (the rig writes the run's start in seconds), and only the first order that sees a token starts
    // fresh
    public static boolean freshRun() {
        return Tuning.now(SWITCH, 0) > 0;
    }

    // whether the switch names a run this client has not started fresh yet (no change): an order left open at a disconnect
    // belongs to an earlier run then, and is not taken up; within the same run (a kick, a lost connection) it is
    public static synchronized boolean newRun() {
        double value = Tuning.now(SWITCH, 0);
        return value > 0 && (value <= 1 || value != lastToken);
    }

    // whether this order clears: the switch on, and for a run token only once per token
    static synchronized boolean clearsNow(double value) {
        if (value <= 0) {
            return false;
        }
        if (value <= 1) {
            return true;
        }
        if (value == lastToken) {
            return false;
        }
        lastToken = value;
        return true;
    }

    // each memory's size, in the order registered; -1 for one whose size could not be read (no game, a world going away)
    public static Map<String, Long> sizes() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Memory m : all()) {
            long n;
            try {
                n = m.size().getAsLong();
            } catch (Throwable t) {
                n = -1;
            }
            out.put(m.name(), n);
        }
        return out;
    }

    // an order starts (the buddy's Obtain.start, the fork's #get): what it starts with, measured before anything is cleared,
    // and with fresh_run on all of it forgotten. the fields of the measure line: each memory's size under "memory", the
    // positions known per block, the farthest wart known, Baritone's regions in RAM and region files on disk, and cleared
    public static Map<String, Object> orderStart(String source) {
        double value = Tuning.now(SWITCH, 0);
        boolean fresh = clearsNow(value);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source);
        if (value > 1 && !fresh) {
            out.put("same_run", true);
        }
        out.put("memory", sizes());
        try {
            out.putAll(RunMemoryFork.measure());
        } catch (Throwable t) {
            out.put("measure_error", String.valueOf(t));
        }
        List<String> failed = fresh ? clearAll() : List.of();
        out.put("cleared", fresh);
        if (!failed.isEmpty()) {
            out.put("clear_failed", failed);
        }
        return out;
    }

    // the same, for an order with no buddy around to write the event: one game log line with a fixed prefix
    public static void logOrderStart(String source) {
        System.out.println("[run_memory] " + new com.google.gson.Gson().toJson(orderStart(source)));
    }

    // every memory forgotten, each on its own: one that throws does not keep the rest. the names that failed come back
    public static List<String> clearAll() {
        List<String> failed = new ArrayList<>();
        for (Memory m : all()) {
            try {
                m.clear().run();
            } catch (Throwable t) {
                failed.add(m.name() + ": " + t);
            }
        }
        return failed;
    }
}
