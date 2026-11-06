package adris.altoclef.tasks.construction;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpotFailuresTest {

    @Test
    public void oneStrikeIsNotTheSecondOne() {
        SpotFailures f = new SpotFailures();
        assertFalse(f.fail(1, 64, 1));
        assertEquals(1, f.count(1, 64, 1));
    }

    @Test
    public void twoStrikesAreOutAndItSaysSoOnTheSecond() {
        SpotFailures f = new SpotFailures();
        assertFalse(f.fail(1, 64, 1));
        assertTrue(f.fail(1, 64, 1));
        assertTrue(f.isBad(1, 64, 1));
    }

    // the log: a strike on 6,50 and the next pick was 6,51, then 6,52, 6,53, each one a 15 second strike of its own
    @Test
    public void oneStrikeTakesTheWholeColumnThreeEitherWay() {
        SpotFailures f = new SpotFailures();
        f.fail(6, 50, -200);
        for (int y = 47; y <= 53; y++) {
            assertTrue("y " + y, f.isBad(6, y, -200));
        }
        assertFalse(f.isBad(6, 46, -200));
        assertFalse(f.isBad(6, 54, -200));
    }

    @Test
    public void strikesStayWithTheirColumn() {
        SpotFailures f = new SpotFailures();
        f.fail(1, 64, 1);
        f.fail(1, 64, 1);
        assertFalse(f.isBad(2, 64, 1));
        assertFalse(f.isBad(1, 64, 2));
        assertFalse(f.fail(2, 64, 1));
    }

    @Test
    public void clearGivesEverySpotItsChanceBack() {
        SpotFailures f = new SpotFailures();
        f.fail(-5, 64, 9);
        f.fail(-5, 64, 9);
        f.clear();
        assertFalse(f.isBad(-5, 64, 9));
        assertFalse(f.isBad(-5, 66, 9));
        assertEquals(0, f.count(-5, 64, 9));
    }
}
