package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

// the boxes' budgets: a scale multiplies, nonsense leaves the budget as it is, and the scale is capped at ten
public class WallScaleTest {

    @Test
    public void oneLeavesTheBudget() {
        assertEquals(2000, WallScale.scaled(2000, 1));
    }

    @Test
    public void threeTriples() {
        assertEquals(6000, WallScale.scaled(2000, 3));
        assertEquals(1500, WallScale.scaled(500, 3));
    }

    @Test
    public void nonsenseLeavesTheBudget() {
        assertEquals(2000, WallScale.scaled(2000, 0));
        assertEquals(2000, WallScale.scaled(2000, -2));
        assertEquals(2000, WallScale.scaled(2000, Double.NaN));
        assertEquals(2000, WallScale.scaled(2000, Double.POSITIVE_INFINITY));
    }

    @Test
    public void cappedAtTen() {
        assertEquals(20000, WallScale.scaled(2000, 50));
    }
}
