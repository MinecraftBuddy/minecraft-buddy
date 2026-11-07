package baritone.altoclef;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.ItemFixture;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.precompute.Ternary;
import net.minecraft.world.level.block.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;

// the buddy's fork, Tuning fireproof_rush: fire walkable for the pathfinder only while the rods task keeps the toggle ahead
public class FireWalkTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Test
    public void fireIsNoWayUnlessTheToggleIsOn() {
        AltoClefSettings s = AltoClefSettings.getInstance();
        try {
            s.walkThroughFire(false);
            assertEquals(Ternary.NO, MovementHelper.canWalkThroughBlockState(Blocks.FIRE.defaultBlockState()));
            assertTrue(MovementHelper.avoidWalkingInto(Blocks.FIRE.defaultBlockState()));
            assertEquals(0, s.blockToggleBits() & AltoClefSettings.TOGGLE_FIRE);

            s.walkThroughFire(true);
            assertTrue(s.canWalkThroughFire());
            assertEquals(Ternary.YES, MovementHelper.canWalkThroughBlockState(Blocks.FIRE.defaultBlockState()));
            assertEquals(Ternary.YES, MovementHelper.canWalkThroughBlockState(Blocks.SOUL_FIRE.defaultBlockState()));
            assertFalse(MovementHelper.avoidWalkingInto(Blocks.FIRE.defaultBlockState()));
            // (the table of block answers is rebuilt for it)
            assertEquals(AltoClefSettings.TOGGLE_FIRE, s.blockToggleBits() & AltoClefSettings.TOGGLE_FIRE);
            // (the rest of the list still bites)
            assertEquals(Ternary.NO, MovementHelper.canWalkThroughBlockState(Blocks.COBWEB.defaultBlockState()));

            s.walkThroughFire(false);
            assertFalse(s.canWalkThroughFire());
            assertEquals(Ternary.NO, MovementHelper.canWalkThroughBlockState(Blocks.FIRE.defaultBlockState()));
        } finally {
            s.walkThroughFire(false);
        }
    }

    @Test
    public void itRunsOutBySelfInASecondAndAHalf() {
        assertEquals(1_500_000_000L, AltoClefSettings.FIRE_WALK_NANOS);
    }
}
