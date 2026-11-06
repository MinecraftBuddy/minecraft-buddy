package adris.altoclef.util.helpers;

import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ProjectileHelperTest {
    private static final double EPS = 1.0E-9;

    @Test
    public void anArrowShotUpwardIsAboveItsOriginAtFirst() {
        // 10 ticks out: 70 + 0.5 * 10 - 0.5 * 0.05 * 10^2 = 72.5. the old sign put it at 62.5, under the floor
        Vec3 hit = ProjectileHelper.calculateArrowClosestApproach(
                new Vec3(0, 70, 0), new Vec3(1, 0.5, 0), 0.05, new Vec3(10, 70, 0));
        assertEquals(10, hit.x, EPS);
        assertEquals(72.5, hit.y, EPS);
        assertEquals(0, hit.z, EPS);
    }

    @Test
    public void anArrowShotDownwardDropsFasterThanGravityAlone() {
        // 70 - 0.5 * 10 - 2.5 = 62.5
        Vec3 hit = ProjectileHelper.calculateArrowClosestApproach(
                new Vec3(0, 70, 0), new Vec3(1, -0.5, 0), 0.05, new Vec3(10, 70, 0));
        assertEquals(62.5, hit.y, EPS);
    }

    @Test
    public void aLevelShotOnlyFalls() {
        Vec3 hit = ProjectileHelper.calculateArrowClosestApproach(
                new Vec3(0, 70, 0), new Vec3(2, 0, 0), 0.05, new Vec3(20, 70, 0));
        assertEquals(70 - 0.5 * 0.05 * 100, hit.y, EPS);
    }

    @Test
    public void aFireballIsAStraightLineNoMatterHowFastItGetsThere() {
        // a ball coming down at an angle: the closest point is on the line, gravity has nothing to say about it
        Vec3 hit = ProjectileHelper.calculateArrowClosestApproach(
                new Vec3(0, 80, 0), new Vec3(1, -1, 0), 0, new Vec3(10, 70, 0));
        assertEquals(10, hit.x, EPS);
        assertEquals(70, hit.y, EPS);
    }

    @Test
    public void aFireballFallingStraightDownDoesNotComeOutAsNaN() {
        Vec3 hit = ProjectileHelper.calculateArrowClosestApproach(
                new Vec3(5, 90, 5), new Vec3(0, -1, 0), 0, new Vec3(5.5, 70, 5));
        assertEquals(5, hit.x, EPS);
        assertEquals(70, hit.y, EPS);
    }

    @Test
    public void aFireballFlyingAwayStaysBehindItsOrigin() {
        Vec3 hit = ProjectileHelper.calculateArrowClosestApproach(
                new Vec3(0, 70, 0), new Vec3(1, 0, 0), 0, new Vec3(-4, 70, 0));
        assertEquals(-4, hit.x, EPS);
    }

    @Test
    public void onlyAGhastBallIsWatchedFromFurtherOut() {
        assertEquals(28 * 28, ProjectileHelper.watchRangeSq(LargeFireball.class), EPS);
        assertEquals(150, ProjectileHelper.watchRangeSq(Arrow.class), EPS);
        assertEquals(150, ProjectileHelper.watchRangeSq(SmallFireball.class), EPS);
    }

    @Test
    public void theShieldLineSaysWhatHowSoonAndHowFar() {
        assertEquals("shield: up for LargeFireball, impact 12t, dist 21.4",
                ProjectileHelper.shieldLine(LargeFireball.class, 11.6, 21.38));
    }
}
