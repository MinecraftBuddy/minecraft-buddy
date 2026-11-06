package adris.altoclef.trackers.storage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ContainerTypeTest {
    @Test
    public void aFurnaceIsASmeltInProgressAndNotLoot() {
        assertFalse(ContainerType.FURNACE.lootable());
    }

    @Test
    public void everyOtherContainerIsStillFairGame() {
        for (ContainerType type : ContainerType.values()) {
            if (type != ContainerType.FURNACE) {
                assertTrue(type.name(), type.lootable());
            }
        }
    }
}
