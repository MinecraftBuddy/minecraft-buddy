package adris.altoclef.tasks;

import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// a throwaway dig hands the reserve call nothing at all, anything else keeps its per item map
public class ResourceTaskReserveTest {

    @Test
    public void aThrowawayDigReservesNothing() {
        Map<String, Integer> reserve = Map.of("netherrack", 64, "cobblestone", 64);
        assertTrue(ResourceTask.reserveOrNone(true, reserve).isEmpty());
    }

    @Test
    public void theDefaultKeepsThePerItemMap() {
        Map<String, Integer> reserve = Map.of("netherrack", 64, "cobblestone", 6);
        assertEquals(reserve, ResourceTask.reserveOrNone(false, reserve));
    }
}
