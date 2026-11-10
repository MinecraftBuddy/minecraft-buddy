package adris.altoclef.util.helpers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import java.util.List;
import org.junit.Test;

// shooter_pack: a skeleton among two or more wither skeletons (within 8 of it) is not walked up to
public class CombatCommitShooterPackTest {

    private static final long NEVER = CombatCommit.NEVER;

    private static Foe at(int id, boolean ranged, boolean wither, double x, double y, double z) {
        return new Foe(id, 12, ranged, false, NEVER, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER, false, null, false, x, y, z,
                false, false, false, wither, false);
    }

    @Test
    public void twoWitherSkeletonsByTheShooterAreAPack() {
        Foe shooter = at(1, true, false, 100, 60, 100);
        assertTrue(CombatCommit.shooterInPack(List.of(shooter, at(2, false, true, 103, 60, 101), at(3, false, true, 99, 60, 106)), shooter));
    }

    @Test
    public void oneWitherSkeletonOrFarOnesAreNot() {
        Foe shooter = at(1, true, false, 100, 60, 100);
        assertFalse(CombatCommit.shooterInPack(List.of(shooter, at(2, false, true, 103, 60, 101)), shooter));
        assertFalse(CombatCommit.shooterInPack(List.of(shooter, at(2, false, true, 120, 60, 101), at(3, false, true, 100, 60, 115)), shooter));
    }

    @Test
    public void noPlaceKnownIsNoPack() {
        Foe shooter = at(1, true, false, 0, 0, 0);
        assertFalse(CombatCommit.shooterInPack(List.of(shooter, at(2, false, true, 0, 0, 0), at(3, false, true, 0, 0, 0)), shooter));
    }
}
