package adris.altoclef.tasks.speedrun.gamer.tasks;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PearlHuntHeavySkipTest {
    private static final List<double[]> BRUTE = List.of(new double[]{100, 100});

    @Test
    public void aBruteWithinSixteenSkipsTheEnderman() {
        assertTrue(PearlHuntTask.skipNearHeavy(110, 100, BRUTE, false));
        assertTrue(PearlHuntTask.skipNearHeavy(116, 100, BRUTE, false));
    }

    @Test
    public void aBruteAtTwentyDoesNot() {
        assertFalse(PearlHuntTask.skipNearHeavy(120, 100, BRUTE, false));
    }

    @Test
    public void aBastionSkipsEvenWithNoBruteInSight() {
        assertTrue(PearlHuntTask.skipNearHeavy(0, 0, List.of(), true));
    }

    @Test
    public void nothingAroundMeansChase() {
        assertFalse(PearlHuntTask.skipNearHeavy(0, 0, List.of(), false));
    }
}
