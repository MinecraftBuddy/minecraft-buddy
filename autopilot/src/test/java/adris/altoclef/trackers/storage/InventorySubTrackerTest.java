package adris.altoclef.trackers.storage;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class InventorySubTrackerTest {
    @Test
    public void aDuplicatedItemIsCountedOnce() {
        assertEquals(List.of("dirt", "cobble"), InventorySubTracker.distinct(new String[]{"dirt", "cobble", "dirt"}));
    }
}
