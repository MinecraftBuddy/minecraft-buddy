package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.world.FrameGeometry;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StrongholdScanTest {
    @Test
    public void framesOutsideTheRangeAreDropped() {
        List<BlockPos> in = StrongholdScan.withinFlat(List.of(new BlockPos(10, 30, 10), new BlockPos(500, 30, 0), new BlockPos(0, 30, -199)),
                0, 0, StrongholdScan.KNOWN_FRAME_RANGE);
        assertEquals(2, in.size());
    }

    @Test
    public void trackerPositionsGiveTheRingCentre() {
        int[] c = {100, 40, -60};
        List<int[]> frames = new ArrayList<>();
        for (int[] p : FrameGeometry.positions(c)) {
            frames.add(new int[]{p[0], p[1], p[2]});
        }
        assertArrayEquals(c, StrongholdScan.centre(frames).get());
    }

    @Test
    public void tooFewFramesGiveNoCentre() {
        assertTrue(StrongholdScan.centre(List.of(new int[]{1, 2, 3})).isEmpty());
    }
}
