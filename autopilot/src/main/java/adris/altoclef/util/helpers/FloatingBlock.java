package adris.altoclef.util.helpers;

import java.util.function.IntPredicate;

// a block with nothing under it for a few blocks is a bridge or a ledge. in the nether nothing that hangs there is worth
// breaking: the only ones that float are ours, and we are probably standing on them
public final class FloatingBlock {
    // how many empty blocks straight down make it a bridge and not a lip
    public static final int DROP = 3;

    private FloatingBlock() {
    }

    // emptyBelow.test(k) = is the cell k blocks under the block air (or lava)
    public static boolean floating(IntPredicate emptyBelow) {
        for (int k = 1; k <= DROP; k++) {
            if (!emptyBelow.test(k)) {
                return false;
            }
        }
        return true;
    }
}
