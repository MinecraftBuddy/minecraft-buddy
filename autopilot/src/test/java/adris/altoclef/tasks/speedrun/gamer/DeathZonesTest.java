package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertArrayEquals;

import adris.altoclef.util.helpers.BlazeFightRules;
import java.util.List;
import org.junit.Test;

public class DeathZonesTest {

    private static RunState.Death death(String dimension, String cause, long at, int x, int z) {
        RunState.Death d = new RunState.Death();
        d.dimension = dimension;
        d.cause = cause;
        d.gameTime = at;
        d.x = x;
        d.z = z;
        return d;
    }

    @Test
    public void onlyRecentNetherDeathsThatWereNotLavaOrVoidCount() {
        long now = 100_000;
        List<RunState.Death> deaths = List.of(
                death("NETHER", "other", now - 100, 1, 2),
                death("OVERWORLD", "other", now - 100, 3, 4),
                death("NETHER", "lava", now - 100, 5, 6),
                death("NETHER", "void", now - 100, 7, 8),
                death("NETHER", null, now - 100, 9, 10),
                death("NETHER", "other", now - DeathZones.MAX_AGE_TICKS, 11, 12),
                death("NETHER", "other", now - DeathZones.MAX_AGE_TICKS + 1, 13, 14),
                death("NETHER", "other", now + 5, 15, 16));
        List<double[]> spots = DeathZones.spots(deaths, now);
        assertEquals(3, spots.size());
        assertArrayEquals(new double[]{1, 2}, spots.get(0), 0);
        assertArrayEquals(new double[]{9, 10}, spots.get(1), 0);
        assertArrayEquals(new double[]{13, 14}, spots.get(2), 0);
    }

    @Test
    public void packCenterIsTheMeanOfAPackAndNothingForLess() {
        assertNull(BlazeFightRules.packCenter(List.of(new double[]{0, 0}, new double[]{10, 10})));
        double[] c = BlazeFightRules.packCenter(List.of(new double[]{0, 0}, new double[]{6, 0}, new double[]{0, 9}));
        assertArrayEquals(new double[]{2, 3}, c, 1e-9);
    }
}
