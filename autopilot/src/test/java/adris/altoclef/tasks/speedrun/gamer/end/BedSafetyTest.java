package adris.altoclef.tasks.speedrun.gamer.end;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

// a blast's real cost to us, against the armor we wear
public class BedSafetyTest {

    @Test
    public void armorFollowsCombatRules() {
        // no armor, all of it
        assertEquals(27, BedSafety.afterArmor(27, 0, 0), 1e-9);
        // full iron: 15 - 27/2 = 1.5 is under the 20% floor (3), so 12% off
        assertEquals(27 * 0.88, BedSafety.afterArmor(27, 15, 0), 1e-9);
        // full diamond: 20 - 27/4 = 13.25
        assertEquals(27 * (1 - 13.25 / 25), BedSafety.afterArmor(27, 20, 8), 1e-9);
    }

    @Test
    public void difficultyScalesTheBed() {
        assertEquals(0, BedSafety.scaled(27, 0), 1e-9);
        assertEquals(14.5, BedSafety.scaled(27, 1), 1e-9);
        assertEquals(27, BedSafety.scaled(27, 2), 1e-9);
        assertEquals(40.5, BedSafety.scaled(27, 3), 1e-9);
        // easy never makes a tiny hit bigger
        assertEquals(1.5, BedSafety.scaled(1.5, 1), 1e-9);
    }
}
