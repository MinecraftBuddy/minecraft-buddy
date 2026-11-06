package adris.altoclef.ui.gui.model;

import adris.altoclef.ui.gui.facade.ItemsAccess.BasketLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// the get tab's shopping list, names in the order you added them. a get basket gives every line its own count and drops
// a line the moment it hits zero. a mine basket is just a set of blocks plus ONE shared limit (baritone's mine takes a
// single count for the whole command), where zero means "keep going"
public final class Basket {

    public static final int MAX = 9999;

    // same bits as the kit's Keys.MOD_*. the model does not import the kit, so they live here too
    private static final int SHIFT = 1;
    private static final int CTRL = 2 | 8;

    public record Line(String name, int count, int maxStack) {
    }

    private static final class Entry {
        int count;
        int maxStack;
    }

    private final boolean mine;
    private final Map<String, Entry> lines = new LinkedHashMap<>();
    private int limit;

    public Basket(boolean mine) {
        this.mine = mine;
    }

    public boolean isMine() {
        return mine;
    }

    // how far a click moves a count: plain 1, shift a whole stack, ctrl 10 (ctrl wins when both are down)
    public static int stepFor(int mods, int maxStack) {
        if ((mods & CTRL) != 0) {
            return 10;
        }
        if ((mods & SHIFT) != 0) {
            return Math.max(1, maxStack);
        }
        return 1;
    }

    private static int clamp(long n) {
        return (int) Math.max(0L, Math.min(MAX, n));
    }

    // adds n to the line, making it if it is new. a mine basket only cares that the block is in, n is ignored there
    public void add(String name, int n, int maxStack) {
        if (name == null || name.isBlank()) {
            return;
        }
        Entry e = lines.get(name);
        if (mine) {
            if (e == null) {
                e = new Entry();
                e.maxStack = Math.max(1, maxStack);
                lines.put(name, e);
            }
            return;
        }
        int add = Math.max(0, n);
        if (e == null) {
            if (add == 0) {
                return;
            }
            e = new Entry();
            e.maxStack = Math.max(1, maxStack);
            lines.put(name, e);
        }
        e.count = clamp((long) e.count + add);
    }

    // nudges an existing line, never below zero, never past MAX. unknown names are ignored, and so is a mine basket
    public void step(String name, int delta) {
        Entry e = lines.get(name);
        if (e == null || mine) {
            return;
        }
        apply(name, e, (long) e.count + delta);
    }

    // unknown names are ignored too: a stale row committing a typed number must not bring its item back
    public void set(String name, int n) {
        Entry e = lines.get(name);
        if (e == null || mine) {
            return;
        }
        apply(name, e, n);
    }

    private void apply(String name, Entry e, long next) {
        int c = clamp(next);
        if (c == 0) {
            lines.remove(name);
            return;
        }
        e.count = c;
    }

    // the shared "stop after" of a mine basket, 0 is no limit. a get basket has none
    public int limit() {
        return limit;
    }

    public void setLimit(int n) {
        if (mine) {
            limit = clamp(n);
        }
    }

    public void stepLimit(int delta) {
        setLimit((int) Math.max(0L, Math.min(MAX, (long) limit + delta)));
    }

    public void remove(String name) {
        lines.remove(name);
    }

    public void clear() {
        lines.clear();
        limit = 0;
    }

    public boolean contains(String name) {
        return lines.containsKey(name);
    }

    // 0 for a name that is not in here (and for every block in a mine basket): use contains() to tell them apart
    public int count(String name) {
        Entry e = lines.get(name);
        return e == null ? 0 : e.count;
    }

    public int stackOf(String name) {
        Entry e = lines.get(name);
        return e == null ? 1 : e.maxStack;
    }

    public int size() {
        return lines.size();
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    // everything the button will ask for: the sum of the counts in a get basket, the limit in a mine one
    public int total() {
        if (mine) {
            return limit;
        }
        long sum = 0;
        for (Entry e : lines.values()) {
            sum += e.count;
        }
        return (int) Math.min(Integer.MAX_VALUE, sum);
    }

    public List<Line> lines() {
        List<Line> out = new ArrayList<>(lines.size());
        for (Map.Entry<String, Entry> e : lines.entrySet()) {
            out.add(new Line(e.getKey(), e.getValue().count, e.getValue().maxStack));
        }
        return Collections.unmodifiableList(out);
    }

    // a mine command carries the one shared limit on every line, the facade reads the first
    public List<BasketLine> toCommandLines() {
        List<BasketLine> out = new ArrayList<>(lines.size());
        for (Map.Entry<String, Entry> e : lines.entrySet()) {
            out.add(new BasketLine(e.getKey(), mine ? limit : e.getValue().count));
        }
        return out;
    }

    // "3 items · 131 total", the small line over the button
    public String summaryLine() {
        if (lines.isEmpty()) {
            return "Nothing added yet";
        }
        int n = lines.size();
        if (!mine) {
            return n + (n == 1 ? " item" : " items") + " · " + total() + " total";
        }
        String head = n + (n == 1 ? " block" : " blocks");
        if (limit == 0) {
            return head + " · no limit";
        }
        // baritone counts the drops of every listed block together
        return head + " · stop after " + limit + (n > 1 ? " in total" : "");
    }

    // "Get 131 items" / "Mine 3 blocks", or the bare verb while there is nothing to send
    public String buttonLabel() {
        if (lines.isEmpty()) {
            return mine ? "Mine blocks" : "Get items";
        }
        if (mine) {
            int n = lines.size();
            return "Mine " + n + (n == 1 ? " block" : " blocks");
        }
        int t = total();
        return "Get " + t + (t == 1 ? " item" : " items");
    }
}
