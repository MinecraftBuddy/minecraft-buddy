package adris.altoclef.trace;

import java.util.ArrayList;
import java.util.List;

// the last few hundred log lines, kept so a dump has the lead up to whatever went wrong. a plain array that wraps, no
// allocation per line beyond the string the caller already built. synchronized because logInternal gets called from
// other threads too (it is a println, nobody ever checked)
public final class TraceRing {

    public static final int CAPACITY = 300;

    private final String[] lines;
    private int next;
    private int size;

    public TraceRing() {
        this(CAPACITY);
    }

    TraceRing(int capacity) {
        lines = new String[capacity];
    }

    public synchronized void add(String line) {
        lines[next] = line;
        next = (next + 1) % lines.length;
        if (size < lines.length) {
            size++;
        }
    }

    // oldest first, and a copy: the dump walks it while the game keeps logging
    public synchronized List<String> snapshot() {
        List<String> out = new ArrayList<>(size);
        int start = size < lines.length ? 0 : next;
        for (int i = 0; i < size; i++) {
            out.add(lines[(start + i) % lines.length]);
        }
        return out;
    }

    public synchronized int size() {
        return size;
    }
}
