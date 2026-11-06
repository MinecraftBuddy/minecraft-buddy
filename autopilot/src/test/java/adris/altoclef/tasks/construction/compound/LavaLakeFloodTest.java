package adris.altoclef.tasks.construction.compound;

import adris.altoclef.util.helpers.ItemFixture;
import net.minecraft.core.BlockPos;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;

// the lava lake flood fill: a big deep lake used to eat the whole stack, and the iterative one has to count exactly
// what the recursive one did
public class LavaLakeFloodTest {

    @BeforeClass
    public static void boot() {
        // the task class drags item statics along when it loads
        ItemFixture.boot();
    }

    // the old recursive version, kept as the oracle
    private static int oldLakeSize(Set<BlockPos> explored, BlockPos origin, Predicate<BlockPos> isFullSource) {
        if (explored.contains(origin)) return 0;
        explored.add(origin);
        if (!isFullSource.test(origin)) return 0;
        int bonus = 0;
        for (BlockPos check : new BlockPos[]{origin.north(), origin.south(), origin.east(), origin.west(), origin.above(), origin.below()}) {
            bonus += oldLakeSize(explored, check, isFullSource);
        }
        return bonus + 1;
    }

    private static Set<BlockPos> box(BlockPos min, int dx, int dy, int dz) {
        Set<BlockPos> out = new HashSet<>();
        for (int x = 0; x < dx; x++) {
            for (int y = 0; y < dy; y++) {
                for (int z = 0; z < dz; z++) {
                    out.add(min.offset(x, y, z));
                }
            }
        }
        return out;
    }

    @Test
    public void aHundredThousandSourceLakeDoesNotBlowTheStack() {
        Set<BlockPos> lake = box(new BlockPos(0, -60, 0), 50, 40, 50);
        Set<BlockPos> explored = new HashSet<>();
        int n = ConstructNetherPortalBucketTask.lakeSize(new BlockPos(25, -40, 25), lake::contains, explored);
        assertEquals(100_000, n);
        // every source plus the one-thick shell around the box that got looked at and turned down
        Set<BlockPos> shell = box(new BlockPos(-1, -61, -1), 52, 42, 52);
        // minus the 12 edges and 8 corners, those are never a neighbour of a source
        int edges = 4 * (50 + 40 + 50);
        assertEquals(shell.size() - edges - 8, explored.size());
    }

    // sources, a flowing / non-source cell inside, a gap, a diagonal-only neighbour, and a second lake
    private static Set<BlockPos> shapes() {
        Set<BlockPos> s = new HashSet<>(box(new BlockPos(0, 0, 0), 4, 2, 3));
        s.remove(new BlockPos(1, 0, 1)); // flowing lava in the middle, not a source
        s.remove(new BlockPos(3, 1, 2));
        s.add(new BlockPos(4, 2, 3)); // only touches diagonally, its own lake
        s.addAll(box(new BlockPos(10, 0, 0), 2, 1, 5)); // a separate lake past a gap
        s.add(new BlockPos(5, 0, 0)); // a lone source one gap off the first lake
        return s;
    }

    @Test
    public void matchesTheRecursiveVersionPerStart() {
        Set<BlockPos> world = shapes();
        List<BlockPos> starts = new ArrayList<>(world);
        starts.add(new BlockPos(1, 0, 1)); // not a source: counts 0 but still lands in explored
        starts.add(new BlockPos(50, 50, 50));
        for (BlockPos start : starts) {
            Set<BlockPos> oldSeen = new HashSet<>();
            Set<BlockPos> newSeen = new HashSet<>();
            int want = oldLakeSize(oldSeen, start, world::contains);
            int got = ConstructNetherPortalBucketTask.lakeSize(start, world::contains, newSeen);
            assertEquals(start.toShortString(), want, got);
            assertEquals(start.toShortString(), oldSeen, newSeen);
        }
    }

    // the findLavaLakes loop: one shared explored set, a start already inside a counted lake is skipped
    private static List<Integer> lakes(List<BlockPos> sources, Predicate<BlockPos> world, boolean old) {
        Set<BlockPos> explored = new HashSet<>();
        List<Integer> sizes = new ArrayList<>();
        for (BlockPos pos : sources) {
            if (explored.contains(pos)) continue;
            sizes.add(old ? oldLakeSize(explored, pos, world) : ConstructNetherPortalBucketTask.lakeSize(pos, world, explored));
        }
        return sizes;
    }

    @Test
    public void sharedExploredSetSkipsTheRestOfALake() {
        Set<BlockPos> world = shapes();
        List<BlockPos> sources = new ArrayList<>(List.of(
                new BlockPos(0, 0, 0), new BlockPos(2, 1, 2), // same lake twice
                new BlockPos(10, 0, 4), new BlockPos(11, 0, 0), // second lake twice
                new BlockPos(4, 2, 3), new BlockPos(5, 0, 0)));
        List<Integer> got = lakes(sources, world::contains, false);
        assertEquals(lakes(sources, world::contains, true), got);
        // 4*2*3 minus the two holes, then the 2x5 lake, then the two loners
        assertEquals(List.of(22, 10, 1, 1), got);
    }

    @Test
    public void startThatWasAlreadyExploredCountsZero() {
        Set<BlockPos> world = shapes();
        Set<BlockPos> explored = new HashSet<>();
        ConstructNetherPortalBucketTask.lakeSize(new BlockPos(0, 0, 0), world::contains, explored);
        int before = explored.size();
        assertEquals(0, ConstructNetherPortalBucketTask.lakeSize(new BlockPos(3, 0, 2), world::contains, explored));
        assertEquals(before, explored.size());
    }
}
