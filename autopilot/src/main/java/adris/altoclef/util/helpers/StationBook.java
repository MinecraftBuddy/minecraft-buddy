package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

// the book plain alto keeps when no run has a registry behind StationHook: what the container tasks reported placing and which
// station they are working at right now. in memory only, no saving, no pickups, no states: the gamer's registry (Workbenches) is the
// real book and replaces this one while a run is installed. same key as there, kind + dimension + position, so a station told twice is
// one entry. cleared when the world is left (AltoClef.onWorldLeft)
public final class StationBook {
    // nobody places this many stations in one session, but a bot that places a table every few blocks should not grow this for ever
    static final int MAX = 64;

    // what the caller's eyes say about a remembered block: it is there, it is gone for sure (loaded chunk, other block), or we can not
    // tell (unloaded chunk, not reachable). gone ones are dropped from the book on the spot, unknown ones are left alone and not offered
    public enum Standing {
        STANDS, GONE, UNKNOWN
    }

    private static final class Entry {
        final StationHook.Kind kind;
        final String dimension;
        final BlockPos pos;
        final long placedTick;
        long leasedTick = Long.MIN_VALUE;

        Entry(StationHook.Kind kind, String dimension, BlockPos pos, long placedTick) {
            this.kind = kind;
            this.dimension = dimension;
            this.pos = pos;
            this.placedTick = placedTick;
        }

        boolean is(StationHook.Kind k, String d, BlockPos p) {
            return kind == k && dimension.equals(d) && pos.equals(p);
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    // false when we already had it (reported twice, or put down again on the same spot)
    public boolean report(StationHook.Kind kind, BlockPos pos, String dimension, long now) {
        Objects.requireNonNull(kind);
        if (find(kind, dimension, pos) != null) {
            return false;
        }
        entries.add(new Entry(kind, dimension, pos.immutable(), now));
        if (entries.size() > MAX) {
            entries.remove(0);
        }
        return true;
    }

    // a task is working at this one, true when the book knows it
    public boolean touch(StationHook.Kind kind, BlockPos pos, String dimension, long now) {
        Entry e = find(kind, dimension, pos);
        if (e == null) {
            return false;
        }
        e.leasedTick = now;
        return true;
    }

    // same clock rules as WorkbenchRules.leased: a clock that went backwards is no lease
    public boolean leased(StationHook.Kind kind, BlockPos pos, String dimension, long now, long leaseTicks) {
        Entry e = find(kind, dimension, pos);
        return e != null && e.leasedTick != Long.MIN_VALUE && now >= e.leasedTick && now - e.leasedTick <= leaseTicks;
    }

    public boolean has(StationHook.Kind kind, BlockPos pos, String dimension) {
        return find(kind, dimension, pos) != null;
    }

    public long placedTick(StationHook.Kind kind, BlockPos pos, String dimension) {
        Entry e = find(kind, dimension, pos);
        return e == null ? Long.MIN_VALUE : e.placedTick;
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
    }

    // the closest one of this kind in this dimension within `radius` (a straight line, height counts, WalkCost.stationDistance) that
    // `ok` likes and `standing` says is really there. a GONE one is forgotten here and now
    public BlockPos nearest(StationHook.Kind kind, String dimension, double x, double y, double z, double radius, Predicate<BlockPos> ok,
                            Function<BlockPos, Standing> standing) {
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (e.kind != kind || !e.dimension.equals(dimension)) {
                continue;
            }
            double d = WalkCost.stationDistance(e.pos.getX(), e.pos.getY(), e.pos.getZ(), x, y, z);
            if (d > radius || d >= bestDistance) {
                continue;
            }
            Standing seen = standing.apply(e.pos);
            if (seen == Standing.GONE) {
                it.remove();
                continue;
            }
            if (seen == Standing.STANDS && ok.test(e.pos)) {
                best = e.pos;
                bestDistance = d;
            }
        }
        return best;
    }

    private Entry find(StationHook.Kind kind, String dimension, BlockPos pos) {
        for (Entry e : entries) {
            if (e.is(kind, dimension, pos)) {
                return e;
            }
        }
        return null;
    }
}
