package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

// (Tuning wood_fuel) the wood the task under way keeps, and the logs a fuel trip asks for instead of coal
public class WoodFuelTest {

    @Test
    public void theShieldKeepsItsSixPlanksAndATable() {
        // a shield to craft (six planks, one ingot) with no table in the bag: ten planks kept
        List<WoodFuel.Recipe> shield = List.of(new WoodFuel.Recipe(6, 0, 0, 1));
        assertEquals(10, WoodFuel.planPlanks(shield, true, 0));
        // with a table in the bag: the six
        assertEquals(6, WoodFuel.planPlanks(shield, false, 0));
        // nothing crafted: nothing kept
        assertEquals(0, WoodFuel.planPlanks(List.of(), false, 0));
    }

    @Test
    public void sticksTheBagLacksCostTwoPlanksAFour() {
        // a stone pickaxe takes two sticks: none held, two planks make four
        List<WoodFuel.Recipe> pick = List.of(new WoodFuel.Recipe(0, 0, 2, 1));
        assertEquals(2, WoodFuel.planPlanks(pick, false, 0));
        // two held: none to make
        assertEquals(0, WoodFuel.planPlanks(pick, false, 2));
        // a log slot is four planks, crafts multiply
        assertEquals(8, WoodFuel.planPlanks(List.of(new WoodFuel.Recipe(0, 1, 0, 2)), false, 0));
    }

    @Test
    public void aLogTripAsksForTheSmeltsAndThePlansWood() {
        // one smelt short, nothing in the bag, ten planks planned: one log for the smelt, three for the plan
        assertEquals(4, WoodFuel.logsWanted(1, 0, 0, 10));
        // the plan covered by the bag: the smelts alone, a log 1.5 smelts
        assertEquals(2 + 6, WoodFuel.logsWanted(8, 2, 6, 6));
        assertEquals(3 + 1, WoodFuel.logsWanted(1.5, 3, 0, 0));
        assertEquals(3 + 2, WoodFuel.logsWanted(1.6, 3, 0, 0));
        // nothing missing: what the bag holds
        assertEquals(3, WoodFuel.logsWanted(0, 3, 0, 0));
    }

    @Test
    public void logsWhenFewSmeltsAreShortOrNoCoalIsNear() {
        assertTrue(WoodFuel.logsInstead(1, true));
        assertTrue(WoodFuel.logsInstead(8, true));
        assertFalse(WoodFuel.logsInstead(9, true));
        assertTrue(WoodFuel.logsInstead(24, false));
    }

    @Test
    public void thePlanIsAReserveOfPlanksFirstThenLogs() {
        // ten planks planned, six held: the six, and one log for the other four
        int[] r = FuelPolicy.planReserve(6, 10);
        assertEquals(1, r[0]);
        assertEquals(6, r[1]);
        // none held: three logs
        r = FuelPolicy.planReserve(0, 10);
        assertEquals(3, r[0]);
        assertEquals(0, r[1]);
        assertEquals(0, FuelPolicy.planReserve(20, 0)[1]);
    }
}
