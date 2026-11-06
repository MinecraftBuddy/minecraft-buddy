package adris.altoclef.util.helpers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

// a lava lookup is just a set of cells, the rule only ever asks "is there lava at x y z"
public class ItemPickupLavaTest {

    private static ItemPickupRules.Lava lavaAt(int... xyz) {
        Set<String> cells = new HashSet<>();
        for (int i = 0; i < xyz.length; i += 3) {
            cells.add(xyz[i] + "," + xyz[i + 1] + "," + xyz[i + 2]);
        }
        return (x, y, z) -> cells.contains(x + "," + y + "," + z);
    }

    @Test
    public void dryGroundIsFine() {
        assertFalse(ItemPickupRules.nextToLava(10.5, 64.1, 10.5, lavaAt()));
    }

    @Test
    public void aDropInLavaIsSkipped() {
        assertTrue(ItemPickupRules.nextToLava(10.5, 64.3, 10.5, lavaAt(10, 64, 10)));
    }

    @Test
    public void aDropRestingOnLavaIsSkipped() {
        // the entity sits at the top of the cell above the lava
        assertTrue(ItemPickupRules.nextToLava(10.5, 64.0, 10.5, lavaAt(10, 63, 10)));
    }

    @Test
    public void lavaOnAnySideAtFeetLevelIsSkipped() {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                assertTrue(dx + "," + dz, ItemPickupRules.nextToLava(10.5, 64.1, 10.5, lavaAt(10 + dx, 64, 10 + dz)));
            }
        }
    }

    @Test
    public void lavaTwoBlocksAwayIsFine() {
        assertFalse(ItemPickupRules.nextToLava(10.5, 64.1, 10.5, lavaAt(12, 64, 10, 10, 64, 12, 8, 64, 8)));
    }

    @Test
    public void aDropOnALakeShoreIsSkipped() {
        // the shore block is level with the lava surface (y 63), the drop lies on it (feet at 64), the lake is one step
        // sideways and one down from the drop's cell
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                assertTrue(dx + "," + dz, ItemPickupRules.nextToLava(10.5, 64.1, 10.5, lavaAt(10 + dx, 63, 10 + dz)));
            }
        }
    }

    @Test
    public void lavaTwoDownOrOneUpFromTheNeighboursIsFine() {
        // a deeper pit is a fall of 2+ before the lava, a ledge above is not something we walk into
        assertFalse(ItemPickupRules.nextToLava(10.5, 64.1, 10.5, lavaAt(11, 62, 10, 9, 65, 10)));
        // two columns over is not beside the drop at either level
        assertFalse(ItemPickupRules.nextToLava(10.5, 64.1, 10.5, lavaAt(12, 63, 10, 12, 64, 10)));
    }

    @Test
    public void negativeCoordinatesFloorTheRightWay() {
        // -0.5 is block -1, not block 0
        assertTrue(ItemPickupRules.nextToLava(-0.5, 64.1, -0.5, lavaAt(-1, 64, -1)));
        assertFalse(ItemPickupRules.nextToLava(-0.5, 64.1, -0.5, lavaAt(1, 64, 1)));
    }
}
