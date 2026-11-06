package adris.altoclef.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// pure chunk spiral around the stronghold start chunk (gamer-design.md 5.6/5.7). the portal room is somewhere in
// the +-112 block box around the start staircase, so the room search just eats chunks nearest first
public final class StrongholdRoomPlan {
    public record Chunk(int cx, int cz) {
        public String key() {
            return cx + "," + cz;
        }
    }

    // every chunk within radius of start (square), nearest first, start itself first.
    // chebyshev ring first so the walk finishes a whole ring before going out, then euclid so a ring is not a
    // zigzag, then z then x so two runs always agree on the order
    public static List<Chunk> spiralOrder(Chunk start, int radiusChunks) {
        int r = Math.max(0, radiusChunks);
        List<Chunk> out = new ArrayList<>((2 * r + 1) * (2 * r + 1));
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                out.add(new Chunk(start.cx() + dx, start.cz() + dz));
            }
        }
        out.sort(Comparator.<Chunk>comparingInt(c -> cheb(start, c))
                .thenComparingLong(c -> dist2(start, c))
                .thenComparingInt(Chunk::cz)
                .thenComparingInt(Chunk::cx));
        return out;
    }

    // nearest not yet visited chunk of the spiral to where the player is, empty when everything is visited.
    // ties go to whichever comes first in spiralOrder, so it keeps hugging the start
    public static Optional<Chunk> next(Chunk start, int radiusChunks, Set<String> visitedKeys, Chunk player) {
        Chunk best = null;
        long bestD = Long.MAX_VALUE;
        for (Chunk c : spiralOrder(start, radiusChunks)) {
            if (visitedKeys.contains(c.key())) {
                continue;
            }
            long d = dist2(player, c);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return Optional.ofNullable(best);
    }

    private static int cheb(Chunk a, Chunk b) {
        return Math.max(Math.abs(a.cx() - b.cx()), Math.abs(a.cz() - b.cz()));
    }

    private static long dist2(Chunk a, Chunk b) {
        long dx = a.cx() - b.cx();
        long dz = a.cz() - b.cz();
        return dx * dx + dz * dz;
    }

    private StrongholdRoomPlan() {
    }
}
