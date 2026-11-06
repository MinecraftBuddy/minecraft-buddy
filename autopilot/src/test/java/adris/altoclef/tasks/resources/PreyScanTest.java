package adris.altoclef.tasks.resources;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the radius is the hunt's weighted distance, applied to every animal and not just the euclidean closest
public class PreyScanTest {
    @Test
    public void aCowStraightAboveIsOutOfRangeButOneOnLevelGroundIsIn() {
        // 13 up is 26 weighted: closer in a straight line, further to actually walk
        assertFalse(PreyScan.inRange(new Vec3(0, 13, 0), 24));
        assertTrue(PreyScan.inRange(new Vec3(20, 0, 0), 24));
    }

    @Test
    public void theLevelCowWinsEvenWhenTheOneAboveIsCloserAsTheCrowFlies() {
        List<Vec3> cows = List.of(new Vec3(0, 13, 0), new Vec3(20, 0, 0));
        assertTrue(PreyScan.anyInRange(cows, v -> v, 24, v -> true));
        assertFalse(PreyScan.anyInRange(List.of(new Vec3(0, 13, 0)), v -> v, 24, v -> true));
        // in range but not a valid target (unreachable, say) does not count
        assertFalse(PreyScan.anyInRange(cows, v -> v, 24, v -> v.y > 0));
    }

    @Test
    public void theLineIsInclusive() {
        assertTrue(PreyScan.inRange(new Vec3(24, 0, 0), 24));
        assertFalse(PreyScan.inRange(new Vec3(24.5, 0, 0), 24));
    }

    // a ring of fence round (0, 64, 0) at distance `r`, its posts at `y`
    private static Set<BlockPos> ring(int r, int y) {
        Set<BlockPos> fence = new HashSet<>();
        for (int i = -r; i <= r; i++) {
            fence.add(new BlockPos(i, y, -r));
            fence.add(new BlockPos(i, y, r));
            fence.add(new BlockPos(-r, y, i));
            fence.add(new BlockPos(r, y, i));
        }
        return fence;
    }

    @Test
    public void anAnimalInsideAFenceRingIsPenned() {
        BlockPos cow = new BlockPos(0, 64, 0);
        assertTrue(PreyScan.fencedIn(cow, ring(4, 64)::contains));
        // the ground slopes up to the fence on one side
        Set<BlockPos> uphill = ring(4, 64);
        uphill.remove(new BlockPos(4, 64, 0));
        uphill.add(new BlockPos(4, 65, 0));
        assertTrue(PreyScan.fencedIn(cow, uphill::contains));
    }

    @Test
    public void oneOpenSideOrAFenceTooFarIsOpenGround() {
        BlockPos cow = new BlockPos(0, 64, 0);
        Set<BlockPos> gap = ring(4, 64);
        gap.remove(new BlockPos(0, 64, 4));
        assertFalse(PreyScan.fencedIn(cow, gap::contains));
        assertFalse(PreyScan.fencedIn(cow, ring(PreyScan.PEN_REACH + 1, 64)::contains));
        assertFalse(PreyScan.fencedIn(cow, pos -> false));
    }
}
