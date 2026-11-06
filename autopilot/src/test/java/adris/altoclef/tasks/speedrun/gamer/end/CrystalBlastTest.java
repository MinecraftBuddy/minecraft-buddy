package adris.altoclef.tasks.speedrun.gamer.end;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// a crystal's blast on us, worked by hand from vanilla's falloff at power 6
public class CrystalBlastTest {

    @Test
    public void falloffMatchesVanilla() {
        // d = distance / 12, i = (1 - d) * seen, ((i^2 + i) / 2) * 84 + 1
        // standing in it: ((1 + 1) / 2) * 84 + 1
        assertEquals(85, CrystalBlast.raw(0, 1), 1e-9);
        assertEquals(74.791667, CrystalBlast.raw(1, 1), 1e-5);
        assertEquals(29.072917, CrystalBlast.raw(1, 0.5), 1e-5);
        assertEquals(39.791667, CrystalBlast.raw(5, 1), 1e-5);
        assertEquals(16.822917, CrystalBlast.raw(5, 0.5), 1e-5);
        assertEquals(19.666667, CrystalBlast.raw(8, 1), 1e-5);
        assertEquals(9.166667, CrystalBlast.raw(8, 0.5), 1e-5);
        // the edge of the blast is the flat 1, past it nothing
        assertEquals(1, CrystalBlast.raw(12, 1), 1e-9);
        assertEquals(0, CrystalBlast.raw(12.5, 1), 1e-9);
    }

    @Test
    public void coverLeavesTheFlatOne() {
        for (double distance : new double[]{1, 5, 8, 12}) {
            assertEquals(1, CrystalBlast.raw(distance, 0), 1e-9);
        }
    }

    @Test
    public void armorAndDifficultyComeAfter() {
        // armor 16, no toughness: 16 - 39.79/2 is under the 20% floor, so 12.8% off
        assertEquals(39.791667 * 0.872, CrystalBlast.predicted(5, 1, 2, 16, 0), 1e-4);
        // hard is half again before the armor
        assertEquals(BedSafety.afterArmor(39.791667 * 1.5, 16, 0), CrystalBlast.predicted(5, 1, 3, 16, 0), 1e-4);
        // toughness is read, not assumed 0: diamond takes more off
        assertTrue(CrystalBlast.predicted(5, 1, 2, 20, 8) < CrystalBlast.predicted(5, 1, 2, 20, 0));
    }

    @Test
    public void swingsOnlyWhenCovered() {
        // nothing of us in view: the flat 1, 0.38 through armor 16
        double covered = CrystalBlast.predicted(5, 0, 2, 16, 0);
        assertEquals(0.38, covered, 1e-9);
        assertTrue(CrystalBlast.safeToSwing(covered));
        // a bit over half in view from 5 out is ~13.8 through armor 16, two thirds of a full health bar
        double half = CrystalBlast.predicted(5, 0.55, 2, 16, 0);
        assertTrue(half > 13 && half < 14.5);
        assertFalse(CrystalBlast.safeToSwing(half));
        // all of us in view from 4.4 out: ~38 through armor 16, more than a full health bar
        double open = CrystalBlast.predicted(4.4, 1, 2, 16, 0);
        assertTrue(open > 20);
        assertFalse(CrystalBlast.safeToSwing(open));
        // a sliver of it seeing us is already too much
        assertFalse(CrystalBlast.safeToSwing(CrystalBlast.predicted(5, 0.15, 2, 16, 0)));
    }
}
