package adris.altoclef.chains;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import org.junit.Test;

// every fire we touch gets hit, not just the first one, or we stand in the second one and burn anyway
public class FireWeTouchTest {

    @Test
    public void picksEveryFireAndCopiesThem() {
        // same object every step, the way a region scan hands them out
        BlockPos.MutableBlockPos reused = new BlockPos.MutableBlockPos();
        Iterable<BlockPos> scan = () -> Stream.of(1, 2, 3).<BlockPos>map(x -> reused.set(x, 64, 0)).iterator();
        List<BlockPos> hits = WorldSurvivalChain.allMatches(scan, p -> p.getX() != 2);
        assertEquals(List.of(new BlockPos(1, 64, 0), new BlockPos(3, 64, 0)), hits);
    }

    @Test
    public void noFireMeansNothing() {
        assertTrue(WorldSurvivalChain.allMatches(List.of(new BlockPos(0, 64, 0)), p -> false).isEmpty());
    }
}
