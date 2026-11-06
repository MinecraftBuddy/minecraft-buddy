package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CrowdRepulsion.Crowd;
import org.junit.Test;

public class CrowdRepulsionTest {

    private static Crowd crowd(double[][] mobs, double... weights) {
        int n = mobs.length;
        double[] x = new double[n], y = new double[n], z = new double[n], w = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = mobs[i][0];
            y[i] = mobs[i][1];
            z[i] = mobs[i][2];
            w[i] = weights.length == 0 ? 1 : weights[i];
        }
        return new Crowd(x, y, z, w);
    }

    @Test
    public void nobodyToRunFromIsAlreadyThere() {
        assertTrue(CrowdRepulsion.inGoal(CrowdRepulsion.EMPTY, 12, false, 0, 0, 0));
        assertEquals(0, CrowdRepulsion.heuristic(CrowdRepulsion.EMPTY, 12, false, 0, 0, 0), 0);
    }

    @Test
    public void safeMeansFarFromEveryOne() {
        Crowd c = crowd(new double[][]{{0, 0, 0}, {20, 0, 0}});
        assertFalse(CrowdRepulsion.inGoal(c, 12, false, 15, 0, 0));
        assertFalse(CrowdRepulsion.inGoal(c, 12, false, 5, 0, 0));
        assertTrue(CrowdRepulsion.inGoal(c, 12, false, 10, 30, 0));
        assertTrue(CrowdRepulsion.inGoal(c, 12, true, 10, 30, 0) == false);
    }

    @Test
    public void thePullPointsAwayFromTheMiddleOfTheCrowd() {
        // five on the east side, one on the west: west is where there is least to run from
        Crowd c = crowd(new double[][]{{4, 0, 0}, {4, 0, 2}, {5, 0, -2}, {5, 0, 1}, {6, 0, 0}, {-4, 0, 0}});
        double east = CrowdRepulsion.heuristic(c, 12, false, 2, 0, 0);
        double north = CrowdRepulsion.heuristic(c, 12, false, 0, 0, 2);
        double west = CrowdRepulsion.heuristic(c, 12, false, -2, 0, 0);
        assertTrue(east > north);
        assertTrue(north > west);
    }

    @Test
    public void everyBlockAwayHelps() {
        Crowd c = crowd(new double[][]{{0, 0, 0}, {2, 0, 3}, {-2, 0, 3}});
        double last = Double.MAX_VALUE;
        for (int z = -1; z >= -12; z--) {
            double h = CrowdRepulsion.heuristic(c, 12, false, 0, 0, z);
            assertTrue("z " + z, h < last);
            last = h;
        }
        assertEquals(0, last, 0);
    }

    @Test
    public void aCreeperCountsForSeveralZombies() {
        // one creeper to the east, one zombie to the west, same distance. the way out is west of the creeper... which is
        // towards the zombie, and the heuristic prefers it over the creeper
        Crowd c = crowd(new double[][]{{4, 0, 0}, {-4, 0, 0}}, CrowdRepulsion.CREEPER_WEIGHT, 1);
        double towardsZombie = CrowdRepulsion.heuristic(c, 12, false, -2, 0, 0);
        double towardsCreeper = CrowdRepulsion.heuristic(c, 12, false, 2, 0, 0);
        assertTrue(towardsCreeper > towardsZombie);
    }

    @Test
    public void tenZombiesAreNotTenTimesTheWork() {
        // averaged, not summed, so the number stays in the same ballpark as the search's step costs
        double[][] ten = new double[10][];
        for (int i = 0; i < 10; i++) ten[i] = new double[]{4, 0, 0};
        double one = CrowdRepulsion.heuristic(crowd(new double[][]{{4, 0, 0}}), 12, false, 0, 0, 0);
        double many = CrowdRepulsion.heuristic(crowd(ten), 12, false, 0, 0, 0);
        assertEquals(one, many, 1e-9);
    }

    // the committed run: far from where it started is part of the goal, and it pulls like the crowd does
    @Test
    public void farFromTheOriginIsFlatDistance() {
        assertFalse(CrowdRepulsion.farFromOrigin(100, 100, 50, 100, 149.9));
        assertTrue(CrowdRepulsion.farFromOrigin(100, 100, 50, 100, 150));
        // a hill is not a reason to run less (or more)
        assertTrue(CrowdRepulsion.farFromOrigin(0, 0, 50, 30, 40));
        assertFalse(CrowdRepulsion.farFromOrigin(0, 0, 50, 30, 39.9));
    }

    @Test
    public void theOriginPullShrinksToZeroAtTheDistance() {
        double last = Double.MAX_VALUE;
        for (int x = 0; x <= 50; x += 5) {
            double h = CrowdRepulsion.originHeuristic(0, 0, 50, x, 0);
            assertTrue("x " + x, h < last);
            last = h;
        }
        assertEquals(0, last, 0);
        // and past it there is nothing left to want
        assertEquals(0, CrowdRepulsion.originHeuristic(0, 0, 50, 80, 0), 0);
    }

    @Test
    public void standingOnTheOriginIsTheWholeDistanceToGo() {
        // the same ticks per block as the crowd's pull, so the two add up in one currency
        double perBlock = CrowdRepulsion.heuristic(crowd(new double[][]{{0, 0, 0}}), 12, true, 0, 0, 0) / 12;
        assertEquals(50 * perBlock, CrowdRepulsion.originHeuristic(7, 7, 50, 7, 7), 1e-9);
    }
}
