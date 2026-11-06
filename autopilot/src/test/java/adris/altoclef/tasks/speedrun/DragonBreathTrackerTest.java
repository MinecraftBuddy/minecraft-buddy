package adris.altoclef.tasks.speedrun;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.Test;

public class DragonBreathTrackerTest {

    @Test
    public void nearMeansLessThanThatManyBlocksFromAnyCell() {
        List<BlockPos> cells = List.of(new BlockPos(0, 60, 0), new BlockPos(1, 60, 0));
        assertTrue(DragonBreathTracker.anyWithin(cells, new BlockPos(1, 60, 0), 3));
        assertTrue(DragonBreathTracker.anyWithin(cells, new BlockPos(3, 60, 0), 3));
        assertFalse(DragonBreathTracker.anyWithin(cells, new BlockPos(4, 60, 0), 3));
        assertFalse(DragonBreathTracker.anyWithin(List.of(), new BlockPos(0, 60, 0), 3));
    }
}
