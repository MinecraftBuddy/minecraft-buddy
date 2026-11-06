package adris.altoclef.tasks.construction;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;

import java.util.Collection;
import java.util.function.Supplier;

// the cells nothing may build in while a station of ours stands there: the one above it and the four beside it. pathing that
// pillars up onto a table, or bricks over the cell we stand in to use it, is time spent on a block we then dig out again.
// pure, the gamer hands the station positions in and reads the set from a place-avoid predicate (baritone's costOfPlacingAt asks
// from its own thread, so the set is built once and only ever read)
public final class StationBox {
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    // the picker (StationSpots) asks the same predicate to see if a cell takes a block, and a second station next to the first
    // is exactly what it is allowed to do. it has its own rule for keeping each station reachable. thread local because the
    // path thread reads the predicate too and must never see the picker's exemption
    private static final ThreadLocal<Boolean> PICKING = ThreadLocal.withInitial(() -> false);

    private StationBox() {
    }

    // above, and the four sides at station level. not below (that is its floor, nobody builds in it) and not the corners (nobody
    // stands in a corner to use a table). a station cell that is another station's side is in there too, it is solid anyway
    public static LongSet offLimits(Collection<BlockPos> stations) {
        LongSet out = new LongOpenHashSet();
        for (BlockPos s : stations) {
            out.add(BlockPos.asLong(s.getX(), s.getY() + 1, s.getZ()));
            for (int[] side : SIDES) {
                out.add(BlockPos.asLong(s.getX() + side[0], s.getY(), s.getZ() + side[1]));
            }
        }
        return out;
    }

    // the place-avoid predicate's answer. the common case is no stations at all, then it is one isEmpty
    public static boolean avoids(LongSet cells, BlockPos pos) {
        return !cells.isEmpty() && cells.contains(pos.asLong()) && !PICKING.get();
    }

    // run a question as the station picker: the box says no to nothing while it runs
    public static <T> T asPicker(Supplier<T> question) {
        boolean before = PICKING.get();
        PICKING.set(true);
        try {
            return question.get();
        } finally {
            PICKING.set(before);
        }
    }
}
