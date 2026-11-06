package adris.altoclef.tasks.resources;

import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.util.List;

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
}
