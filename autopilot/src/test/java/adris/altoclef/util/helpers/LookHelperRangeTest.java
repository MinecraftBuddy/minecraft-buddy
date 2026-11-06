package adris.altoclef.util.helpers;

import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LookHelperRangeTest {
    @Test
    public void aTargetPastTheRangeIsBeyondIt() {
        assertTrue(LookHelper.beyondRange(new Vec3(0, 0, 0), new Vec3(21, 0, 0), 20));
        assertTrue(LookHelper.beyondRange(new Vec3(0, 0, 0), new Vec3(15, 15, 0), 20));
    }

    @Test
    public void aTargetInsideTheRangeIsNot() {
        assertFalse(LookHelper.beyondRange(new Vec3(0, 0, 0), new Vec3(19, 0, 0), 20));
        assertFalse(LookHelper.beyondRange(new Vec3(5, 5, 5), new Vec3(5, 5, 5), 0));
    }

    @Test
    public void aTargetAimedExactlyAtTheRangeIsNotBeyondIt() {
        // the pearl look check ends its ray at start + dir * range, float noise must not flip that
        Vec3 dir = new Vec3(0.3, -0.5, 0.8).normalize();
        Vec3 start = new Vec3(123.456, 64.62, -987.654);
        assertFalse(LookHelper.beyondRange(start, start.add(dir.scale(3)), 3));
    }
}
