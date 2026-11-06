package adris.altoclef.util.helpers;

import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// pools here are just sets of cells, the flood is FluidSourcesTest's business
public class PoolMissesTest {
    private static final Dimension OW = Dimension.OVERWORLD;

    // a 4 long strip: sources at both ends, flowing in the middle
    private static final BlockPos S1 = new BlockPos(0, 20, 0);
    private static final BlockPos F1 = new BlockPos(1, 20, 0);
    private static final BlockPos F2 = new BlockPos(2, 20, 0);
    private static final BlockPos S2 = new BlockPos(3, 20, 0);
    private static final Set<BlockPos> STRIP = new LinkedHashSet<>(List.of(S1, F1, F2, S2));
    private final Set<BlockPos> sources = new HashSet<>(List.of(S1, S2));
    private final Predicate<BlockPos> isSource = sources::contains;

    @Test
    public void twoMissesInOnePoolBanItsSources() {
        PoolMisses m = new PoolMisses();
        assertTrue(m.miss(OW, S1, STRIP, isSource).isEmpty());
        // a different column of the same pool, the one the next pick goes for
        assertEquals("only the sources, not the flowing cells", List.of(S1, S2), m.miss(OW, S2, STRIP, isSource));
    }

    @Test
    public void twoSeparatePoolsAreOneMissEach() {
        PoolMisses m = new PoolMisses();
        BlockPos far = new BlockPos(50, 20, 50);
        sources.add(far);
        assertTrue(m.miss(OW, S1, STRIP, isSource).isEmpty());
        assertTrue(m.miss(OW, far, Set.of(far), isSource).isEmpty());
        assertEquals(2, m.open());
    }

    @Test
    public void aOneCellPoolGoesOnItsSecondMiss() {
        PoolMisses m = new PoolMisses();
        Set<BlockPos> puddle = Set.of(S1);
        assertTrue(m.miss(OW, S1, puddle, isSource).isEmpty());
        assertEquals(List.of(S1), m.miss(OW, S1, puddle, isSource));
    }

    @Test
    public void bannedMissesAreForgotten() {
        PoolMisses m = new PoolMisses();
        BlockPos far = new BlockPos(50, 20, 50);
        sources.add(far);
        m.miss(OW, S1, STRIP, isSource);
        m.miss(OW, far, Set.of(far), isSource);
        m.miss(OW, S2, STRIP, isSource);
        // the strip's miss is gone, the other pool's is still waiting
        assertEquals(1, m.open());
        // so the strip starts over: one more miss there is a first miss again
        assertTrue(m.miss(OW, F1, STRIP, isSource).isEmpty());
    }

    @Test
    public void theSameCoordinatesInAnotherDimensionAreAnotherPool() {
        PoolMisses m = new PoolMisses();
        assertTrue(m.miss(OW, S1, STRIP, isSource).isEmpty());
        assertTrue(m.miss(Dimension.NETHER, S2, STRIP, isSource).isEmpty());
    }
}
