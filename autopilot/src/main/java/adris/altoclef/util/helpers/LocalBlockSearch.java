package adris.altoclef.util.helpers;

import net.minecraft.core.BlockPos;

import java.util.Optional;
import java.util.function.Predicate;

// pure: a look at the world itself, one cube shell at a time out from a spot, for when the tracker's short list has nothing
// we'd take. the first block that passes wins, so a wall right next to us costs next to nothing
public final class LocalBlockSearch {

    // how long an empty look stands. a shore with lots of netherrack that all fails nearLava is the whole cube of it
    static final int EMPTY_STANDS_TICKS = 10;

    private LocalBlockSearch() {
    }

    // only while there is something else to chase, a drop we are walking to asks every tick. with nothing to chase this answer
    // is what calls it wandering, and a caller may stand down on that for good, so it has to be about where the feet are now
    public static boolean emptyStands(boolean pursuing, int now, int emptyAt) {
        return pursuing && now >= emptyAt && now - emptyAt < EMPTY_STANDS_TICKS;
    }

    // radius counts shells, so it is a cube and not a ball. the predicate gets the same moving cursor every time, it can look
    // but not keep. it may well be asked about 4913 spots, one pos per spot is for people who want slow code
    public static Optional<BlockPos> nearest(BlockPos from, int radius, Predicate<BlockPos> ok) {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int r = 0; r <= radius; r++) {
            // inside a shell our own level first, then up, then down. every spot in a shell is about as close, and going for the
            // floor every time is how you dig a shaft under yourself and not a room
            for (int i = 0; i <= 2 * r; i++) {
                int dy = i % 2 == 1 ? (i + 1) / 2 : -(i / 2);
                if (walkLayer(from, r, dy, at, ok)) {
                    return Optional.of(at.immutable());
                }
            }
        }
        return Optional.empty();
    }

    // one height of shell r. a full square on the lid and the base, only the ring in between: the inside was shell r-1's
    private static boolean walkLayer(BlockPos from, int r, int dy, BlockPos.MutableBlockPos at, Predicate<BlockPos> ok) {
        boolean lid = Math.abs(dy) == r;
        for (int dx = -r; dx <= r; dx++) {
            // on the ring's two long sides only the two ends of the row are in the shell, r = 0 never gets here without lid
            int step = lid || Math.abs(dx) == r ? 1 : 2 * r;
            for (int dz = -r; dz <= r; dz += step) {
                at.set(from.getX() + dx, from.getY() + dy, from.getZ() + dz);
                if (ok.test(at)) {
                    return true;
                }
            }
        }
        return false;
    }
}
