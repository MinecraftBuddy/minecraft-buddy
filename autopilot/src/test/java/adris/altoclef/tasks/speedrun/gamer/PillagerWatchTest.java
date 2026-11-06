package adris.altoclef.tasks.speedrun.gamer;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PillagerWatchTest {
    private static Map<Integer, double[]> at(int id, double x, double z) {
        return Map.of(id, new double[]{x, z});
    }

    @Test
    public void aWalkingPatrolIsNeverAnOutpost() {
        PillagerWatch watch = new PillagerWatch(1200);
        for (int t = 0; t <= 6000; t += 40) {
            // 0.1 blocks a tick, well over the stay radius every few seconds
            watch.update(t, at(1, 100 + t * 0.1, 50));
        }
        assertEquals(0, watch.outposts());
        assertFalse(watch.nearOutpost(100, 50, 500));
    }

    private static Map<Integer, double[]> pair(double x, double z) {
        return Map.of(7, new double[]{x, z}, 8, new double[]{x + 4, z + 3});
    }

    @Test
    public void pillagersStandingAroundMarkAnOutpost() {
        PillagerWatch watch = new PillagerWatch(1200);
        for (int t = 0; t < 1200; t += 40) {
            watch.update(t, pair(300 + (t % 80) * 0.05, -200));
            assertEquals(0, watch.outposts());
        }
        watch.update(1200, pair(300, -200));
        assertEquals(1, watch.outposts());
        assertTrue(watch.nearOutpost(320, -200, 40));
        assertFalse(watch.nearOutpost(400, -200, 40));
    }

    @Test
    public void oneLoneStandingPillagerIsNotAnOutpost() {
        // a patrol captain stopping for a while is what a lone pillager looks like
        PillagerWatch watch = new PillagerWatch(1200);
        for (int t = 0; t <= 6000; t += 40) {
            watch.update(t, at(7, 300, -200));
        }
        assertEquals(0, watch.outposts());
    }

    @Test
    public void twoPillagersFarApartAreNotAnOutpostEither() {
        PillagerWatch watch = new PillagerWatch(100);
        Map<Integer, double[]> far = Map.of(1, new double[]{0, 0}, 2, new double[]{200, 0});
        watch.update(0, far);
        watch.update(100, far);
        assertEquals(0, watch.outposts());
    }

    @Test
    public void anOutpostNobodyHasSeenForAWhileExpires() {
        PillagerWatch watch = new PillagerWatch(100, 1000);
        watch.update(0, pair(0, 0));
        watch.update(100, pair(0, 0));
        assertEquals(1, watch.outposts());
        // killed them all, nothing to see for a while
        watch.update(1000, Map.of());
        assertEquals(1, watch.outposts());
        assertTrue(watch.drainExpired().isEmpty());
        watch.update(1101, Map.of());
        assertEquals(0, watch.outposts());
        assertFalse(watch.nearOutpost(0, 0, 100));
        // the caller hears about it once
        assertEquals(1, watch.drainExpired().size());
        assertTrue(watch.drainExpired().isEmpty());
    }

    @Test
    public void aPillagerStillAroundKeepsItAlive() {
        PillagerWatch watch = new PillagerWatch(100, 1000);
        watch.update(0, pair(0, 0));
        watch.update(100, pair(0, 0));
        for (int t = 200; t <= 5000; t += 100) {
            watch.update(t, at(7, 3, 2));
        }
        assertEquals(1, watch.outposts());
    }

    @Test
    public void neighboursOfTheSameOutpostAreOneOutpost() {
        PillagerWatch watch = new PillagerWatch(100);
        Map<Integer, double[]> two = Map.of(1, new double[]{0, 0}, 2, new double[]{5, 5});
        watch.update(0, two);
        watch.update(100, two);
        assertEquals(1, watch.outposts());
    }

    @Test
    public void aPillagerThatLeavesViewStartsOver() {
        PillagerWatch watch = new PillagerWatch(1200);
        watch.update(0, pair(0, 0));
        watch.update(1000, Map.of());
        // back after 1300 ticks: the 1200 they stood there before do not count any more
        watch.update(1300, pair(0, 0));
        assertEquals(0, watch.outposts());
        watch.update(2400, pair(0, 0));
        assertEquals(0, watch.outposts());
        watch.update(2500, pair(0, 0));
        assertEquals(1, watch.outposts());
    }
}
