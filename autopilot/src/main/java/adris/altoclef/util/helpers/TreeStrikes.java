package adris.altoclef.util.helpers;

import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

// failed breaks counted per tree instead of per log. a canopy full of logs we can't reach fails once per log, six seconds
// each, and every ban is per block and over in a minute, so the same logs came back. two strikes on one tree and the whole
// thing is the caller's to give up on. pure, the caller passes the clock (game ticks)
public final class TreeStrikes {
    // the second strike on a tree is the last
    public static final int ALLOWED = 2;
    // a strike is a strike for this long, then the count starts over (the world changed, or it was a one off)
    public static final long DECAY = 2400;
    // what counts as the same tree: a canopy reaches this far sideways from a trunk, and a spruce is this tall
    public static final int RADIUS = 4;
    public static final int HEIGHT = 16;

    private static final class Entry {
        final Dimension dim;
        final List<BlockPos> spots = new ArrayList<>();
        // the same log failing twice is two strikes on one spot, so this is not spots.size()
        int count;
        long last;

        Entry(Dimension dim) {
            this.dim = dim;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public static boolean sameTree(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) <= RADIUS && Math.abs(a.getZ() - b.getZ()) <= RADIUS && Math.abs(a.getY() - b.getY()) <= HEIGHT;
    }

    // one more failure at `at`. empty until it is the one that ends the tree, then every spot that failed there (the caller
    // bans whatever logs are around them for as long as it likes) and the count for this tree is gone
    public List<BlockPos> strike(Dimension dim, BlockPos at, long now) {
        // game time only goes way back with a new world, and then none of this means anything (a tick or two is lag)
        entries.removeIf(e -> GameClock.since(e.last, now) >= DECAY);
        Entry hit = null;
        for (Entry e : entries) {
            if (e.dim == dim && near(e, at)) {
                hit = e;
                break;
            }
        }
        if (hit == null) {
            hit = new Entry(dim);
            entries.add(hit);
        }
        hit.last = now;
        hit.count++;
        if (!hit.spots.contains(at)) {
            hit.spots.add(at.immutable());
        }
        if (hit.count < ALLOWED) {
            return List.of();
        }
        entries.remove(hit);
        return List.copyOf(hit.spots);
    }

    private static boolean near(Entry e, BlockPos at) {
        for (BlockPos spot : e.spots) {
            if (sameTree(spot, at)) {
                return true;
            }
        }
        return false;
    }

    // strikes standing against trees, for the tests
    int open() {
        return entries.size();
    }
}
