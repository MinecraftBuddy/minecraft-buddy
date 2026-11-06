package adris.altoclef.tasks.construction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// how many times a placing spot let us down. the progress checker in PlaceBlockNearbyTask resets whenever baritone is
// pathing, so a spot that sent us walking in circles never failed and was retried until the heat death of the bot.
// two strikes and it is out, pathing or not. pure (plain ints) so the rule tests without a world
final class SpotFailures {
    static final int MAX_FAILS = 2;

    // a strike also takes the column, this far up and down. a spot that failed makes the one above it look next best, and
    // that is how a bad spot at y became a staircase of bad spots at y+1, y+2, y+3
    static final int COLUMN_Y = 3;

    private final Map<Long, Integer> fails = new HashMap<>();
    private final List<int[]> columns = new ArrayList<>();

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    // true when this strike was the one that put the spot out
    boolean fail(int x, int y, int z) {
        columns.add(new int[]{x, y, z});
        return fails.merge(key(x, y, z), 1, Integer::sum) >= MAX_FAILS;
    }

    boolean isBad(int x, int y, int z) {
        if (fails.getOrDefault(key(x, y, z), 0) >= MAX_FAILS) {
            return true;
        }
        for (int[] c : columns) {
            if (c[0] == x && c[2] == z && Math.abs(c[1] - y) <= COLUMN_Y) {
                return true;
            }
        }
        return false;
    }

    int count(int x, int y, int z) {
        return fails.getOrDefault(key(x, y, z), 0);
    }

    void clear() {
        fails.clear();
        columns.clear();
    }
}
