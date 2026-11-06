package adris.altoclef.world;

import adris.altoclef.world.StrongholdEstimator.Advice;
import adris.altoclef.world.StrongholdEstimator.Estimate;
import adris.altoclef.world.StrongholdEstimator.Params;
import adris.altoclef.world.StrongholdEstimator.Ray;
import adris.altoclef.world.StrongholdEstimator.Step;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

// exact geometry, so every expectation here can be worked out on paper. noise lives in StrongholdMonteCarloTest
public class StrongholdEstimatorTest {
    // a start chunk corner (multiples of 16) with |T| = 2039, ring 0
    private static final double TX = 1824;
    private static final double TZ = -912;

    private static StrongholdEstimator est() {
        return new StrongholdEstimator(Params.defaults());
    }

    private static Ray toward(double ox, double oz, double tx, double tz) {
        return StrongholdEstimator.rayFromPoints(ox, oz, tx, tz, false);
    }

    private static Ray toTarget(double ox, double oz) {
        return toward(ox, oz, TX, TZ);
    }

    // two rays 44 degrees apart, a decent baseline
    private static StrongholdEstimator twoGood() {
        StrongholdEstimator e = est();
        assertTrue(e.addRay(toTarget(0, 0)));
        assertTrue(e.addRay(toTarget(1400, 300)));
        return e;
    }

    // two rays only 6 degrees apart: the estimate stays blurry (radius well over 24)
    private static StrongholdEstimator twoBlurry() {
        StrongholdEstimator e = est();
        assertTrue(e.addRay(toTarget(0, 0)));
        assertTrue(e.addRay(toTarget(200, -300)));
        return e;
    }

    // four rays from 300 blocks out in the four directions, then the estimate is dead tight and snapped
    private static StrongholdEstimator tight() {
        StrongholdEstimator e = est();
        for (int i = 0; i < 4; i++) {
            double a = Math.PI / 2 * i + 0.3;
            assertTrue(e.addRay(toTarget(TX + 300 * Math.cos(a), TZ + 300 * Math.sin(a))));
        }
        return e;
    }

    private static Estimate get(StrongholdEstimator e) {
        return e.estimate().orElseThrow();
    }

    private static double dist(Estimate e, double x, double z) {
        return Math.hypot(e.x() - x, e.z() - z);
    }

    // ---- ring constants ----

    @Test
    public void ringBandsMatchTheRouteDoc() {
        assertEquals(1296, StrongholdEstimator.ringLo(0), 1e-9);
        assertEquals(2800, StrongholdEstimator.ringHi(0), 1e-9);
        assertEquals(4368, StrongholdEstimator.ringLo(1), 1e-9);
        assertEquals(5872, StrongholdEstimator.ringHi(1), 1e-9);
        int sum = 0;
        for (int i = 0; i < StrongholdEstimator.rings(); i++) {
            sum += StrongholdEstimator.ringCount(i);
        }
        assertEquals(128, sum);
        assertEquals(8, StrongholdEstimator.rings());
    }

    // ---- one ray: the prior and nothing else ----

    @Test
    public void noRaysMeansNoEstimateAndThrow() {
        StrongholdEstimator e = est();
        assertTrue(e.estimate().isEmpty());
        Advice a = e.advise(10, 20, 0);
        assertEquals(Step.THROW, a.step());
        assertEquals(10, a.x(), 0);
        assertEquals(20, a.z(), 0);
    }

    @Test
    public void oneRayFromSpawnIsTheMiddleOfRingZero() {
        StrongholdEstimator e = est();
        assertTrue(e.addRay(new Ray(0, 0, 1, 0, false)));
        Estimate s = get(e);
        assertEquals(2048, s.x(), 1e-6);
        assertEquals(0, s.z(), 1e-6);
        assertEquals(752, s.radius(), 1e-6);
        assertEquals(0, s.ring());
        assertEquals(1, s.rays());
        assertFalse(s.snapped());
        assertTrue("one ray is not much to go on", s.confidence() < 0.05);
    }

    @Test
    public void oneRayFromAFewHundredBlocksOffStillMeansRingZero() {
        StrongholdEstimator e = est();
        assertTrue(e.addRay(new Ray(-150, 100, 0, -1, false)));
        Estimate s = get(e);
        assertEquals(-150, s.x(), 1e-6);
        assertEquals(0, s.ring());
        // the band along x = -150: |z| from sqrt(1296^2-150^2) to sqrt(2800^2-150^2), starting from z = 100 downwards
        double lo = -Math.sqrt(2800.0 * 2800 - 150 * 150);
        double hi = -Math.sqrt(1296.0 * 1296 - 150 * 150);
        assertEquals((lo + hi) / 2, s.z(), 1e-6);
        assertEquals((hi - lo) / 2, s.radius(), 1e-6);
    }

    @Test
    public void oneRayFromFarOutPointsAtTheNextRingAhead() {
        // standing out at x = 3500, past ring 0 (ends 2800) and before ring 1 (starts 4368)
        StrongholdEstimator outward = est();
        assertTrue(outward.addRay(new Ray(3500, 0, 1, 0, false)));
        assertEquals(1, get(outward).ring());
        assertEquals(5120, get(outward).x(), 1e-6);

        StrongholdEstimator inward = est();
        assertTrue(inward.addRay(new Ray(3500, 0, -1, 0, false)));
        assertEquals(0, get(inward).ring());
        // enters the band at 2800, leaves it through the inner edge at 1296 (the hole), midpoint 2048
        assertEquals(2048, get(inward).x(), 1e-6);
    }

    // ---- two and more rays ----

    @Test
    public void twoExactRaysRecoverTheTarget() {
        Estimate s = get(twoGood());
        assertEquals(TX, s.x(), 1.0);
        assertEquals(TZ, s.z(), 1.0);
        assertEquals(2, s.rays());
        assertEquals(0, s.ring());
    }

    @Test
    public void exactRaysFromAnywhereRecoverTheTarget() {
        double[][] spots = {{0, 0}, {-400, 700}, {900, 900}, {1500, -1800}, {2600, 100}, {300, -2000}};
        for (int a = 0; a < spots.length; a++) {
            for (int b = a + 1; b < spots.length; b++) {
                StrongholdEstimator e = est();
                assertTrue(e.addRay(toTarget(spots[a][0], spots[a][1])));
                // two spots can be nearly in line with the target, that is allowed to be rejected, not to be wrong
                if (!e.addRay(toTarget(spots[b][0], spots[b][1]))) {
                    continue;
                }
                Estimate s = get(e);
                assertEquals("spots " + a + "," + b, TX, s.x(), 3.0);
                assertEquals("spots " + a + "," + b, TZ, s.z(), 3.0);
            }
        }
    }

    @Test
    public void manyCloseRaysSnapToTheLattice() {
        Estimate s = get(tight());
        assertTrue(s.snapped());
        assertTrue(s.radius() < 12);
        assertEquals(TX, s.x(), 0);
        assertEquals(TZ, s.z(), 0);
        assertEquals(0, s.x() % 16, 0);
        assertEquals(0, s.z() % 16, 0);
    }

    @Test
    public void snapPicksTheLatticePointThatFitsNotJustTheNearest() {
        // target is the lattice point (1824,-912); the exact rays all pass through it, so the fit sits on it
        // and the answer is that point even though the nearest rounding of a slightly off fit could be a neighbour
        StrongholdEstimator e = est();
        for (int i = 0; i < 4; i++) {
            double a = Math.PI / 2 * i + 0.3;
            // aim 5 blocks off to the side of the corner, the fit lands 5 off but still rounds to the corner
            assertTrue(e.addRay(toward(TX + 200 * Math.cos(a), TZ + 200 * Math.sin(a), TX + 5, TZ - 5)));
        }
        Estimate s = get(e);
        assertTrue(s.snapped());
        assertEquals(TX, s.x(), 0);
        assertEquals(TZ, s.z(), 0);
    }

    @Test
    public void aThrowThatDidNotDiveRulesOutLatticePointsWithinTwelveBlocks() {
        // rays all point at (1824,-912) but one of them was thrown from 8 blocks away from it WITHOUT diving. that is
        // impossible for the real corner (it would have dived) so the snap must go to a neighbour, not the corner
        StrongholdEstimator e = est();
        for (int i = 0; i < 3; i++) {
            double a = Math.PI / 2 * i + 0.3;
            assertTrue(e.addRay(toTarget(TX + 200 * Math.cos(a), TZ + 200 * Math.sin(a))));
        }
        assertTrue(e.addRay(toTarget(TX + 8, TZ)));
        Estimate s = get(e);
        assertTrue(s.snapped());
        assertNotEquals(TX, s.x(), 0);
        assertTrue(Math.hypot(s.x() - (TX + 8), s.z() - TZ) > 12);
    }

    @Test
    public void radiusShrinksAndConfidenceGrowsAsRaysComeIn() {
        double[][] spots = {{0, 0}, {1400, 300}, {1700, -300}, {1500, -700}, {1800, -800}, {1700, -1000}};
        StrongholdEstimator e = est();
        double lastRadius = Double.MAX_VALUE;
        double lastConf = -1;
        for (double[] p : spots) {
            assertTrue(e.addRay(toTarget(p[0], p[1])));
            Estimate s = get(e);
            assertTrue("radius " + s.radius() + " vs " + lastRadius, s.radius() < lastRadius);
            assertTrue("confidence " + s.confidence() + " vs " + lastConf, s.confidence() > lastConf);
            assertTrue(s.confidence() >= 0 && s.confidence() <= 1);
            lastRadius = s.radius();
            lastConf = s.confidence();
        }
        assertTrue(lastRadius < 12);
    }

    @Test
    public void estimateIsRotationInvariantSoNoAngleWrapCanHurt() {
        for (int deg = 0; deg < 360; deg += 15) {
            double c = Math.cos(Math.toRadians(deg));
            double s = Math.sin(Math.toRadians(deg));
            double[][] spots = {{0, 0}, {1400, 300}, {1500, -700}};
            StrongholdEstimator e = est();
            double rtx = TX * c - TZ * s;
            double rtz = TX * s + TZ * c;
            for (double[] p : spots) {
                double ox = p[0] * c - p[1] * s;
                double oz = p[0] * s + p[1] * c;
                assertTrue("deg " + deg, e.addRay(toward(ox, oz, rtx, rtz)));
            }
            Estimate est = get(e);
            assertEquals("deg " + deg, rtx, est.x(), 1.5);
            assertEquals("deg " + deg, rtz, est.z(), 1.5);
        }
    }

    @Test
    public void bearingsOnBothSidesOfTheWrapStillIntersect() {
        // target out west, one throw from the north side and one from the south: yaws about +170 and -170 degrees
        double tx = -1824;
        double tz = 0;
        StrongholdEstimator e = est();
        assertTrue(e.addRay(toward(0, 300, tx, tz)));
        assertTrue(e.addRay(toward(0, -300, tx, tz)));
        Estimate s = get(e);
        assertEquals(tx, s.x(), 1.0);
        assertEquals(tz, s.z(), 1.0);
    }

    // ---- rejection ----

    @Test
    public void aRayParallelToEverythingIsRejected() {
        StrongholdEstimator e = est();
        assertTrue(e.addRay(new Ray(0, 0, 1, 0, false)));
        // same direction from further along the same line
        assertFalse(e.addRay(new Ray(300, 0, 1, 0, false)));
        // the other way round, same line
        assertFalse(e.addRay(new Ray(300, 0, -1, 0, false)));
        assertEquals(1, e.rays().size());
        assertFalse(e.lastRejection().isEmpty());
    }

    @Test
    public void aRayThatIsOnlyParallelToSomeIsFine() {
        StrongholdEstimator e = est();
        assertTrue(e.addRay(new Ray(0, 0, 1, 0, false)));
        assertTrue(e.addRay(toward(0, 400, 2000, 0)));
        // parallel to the first, not to the second
        assertTrue(e.addRay(new Ray(200, 0, 1, 0, false)));
    }

    @Test
    public void aRayPointingAwayFromTheEstimateIsRejected() {
        StrongholdEstimator e = tight();
        Estimate before = get(e);
        // stand beside the corner and look straight away from it: the line passes through the corner, the direction does not
        assertFalse(e.addRay(new Ray(TX + 100, TZ, 1, 0, false)));
        assertEquals(before, get(e));
        assertEquals(4, e.rays().size());
    }

    @Test
    public void anOutlierIsRejectedAndDoesNotMoveTheFit() {
        StrongholdEstimator e = twoGood();
        assertTrue(e.addRay(toTarget(1500, -700)));
        Estimate before = get(e);
        // eight degrees off from 600 blocks away: about 85 blocks sideways, many sigmas
        double ox = 1700;
        double oz = -300;
        double base = Math.atan2(TZ - oz, TX - ox);
        double off = base + Math.toRadians(8);
        assertFalse(e.addRay(new Ray(ox, oz, Math.cos(off), Math.sin(off), false)));
        assertEquals(before, get(e));
        // and the honest ray right after is welcome
        assertTrue(e.addRay(toTarget(ox, oz)));
        assertEquals(TX, get(e).x(), 2);
        assertEquals(4, e.rays().size());
    }

    @Test
    public void aSmallWobbleIsNotAnOutlier() {
        StrongholdEstimator e = twoGood();
        assertTrue(e.addRay(toTarget(1500, -700)));
        double ox = 1700;
        double oz = -300;
        double base = Math.atan2(TZ - oz, TX - ox);
        double off = base + Math.toRadians(0.3);
        assertTrue(e.addRay(new Ray(ox, oz, Math.cos(off), Math.sin(off), false)));
    }

    @Test
    public void severalRejectedRaysInARowThatAgreeTakeOverTheFit() {
        // the first fit points at stronghold A. then we cross the bisector, the eye now points at B, and every new ray
        // disagrees with the old fit. the third one in a row replaces it
        double bx = -1500;
        double bz = -1400;
        StrongholdEstimator e = twoGood();
        assertTrue(e.addRay(toTarget(1500, -700)));
        assertFalse(e.addRay(toward(200, 300, bx, bz)));
        assertFalse(e.addRay(toward(300, -200, bx, bz)));
        assertTrue(e.addRay(toward(100, -500, bx, bz)));
        Estimate s = get(e);
        assertEquals(bx, s.x(), 5);
        assertEquals(bz, s.z(), 5);
        assertEquals(3, e.rays().size());
    }

    @Test
    public void aGoodRayInBetweenResetsTheRejectionStreak() {
        double bx = -1500;
        double bz = -1400;
        StrongholdEstimator e = twoGood();
        assertTrue(e.addRay(toTarget(1500, -700)));
        assertFalse(e.addRay(toward(200, 300, bx, bz)));
        assertFalse(e.addRay(toward(300, -200, bx, bz)));
        assertTrue(e.addRay(toTarget(1600, -400)));
        assertFalse(e.addRay(toward(100, -500, bx, bz)));
        assertEquals(TX, get(e).x(), 2);
    }

    @Test
    public void zeroDirectionAndNanAreRejectedWithoutBlowingUp() {
        StrongholdEstimator e = est();
        assertFalse(e.addRay(new Ray(0, 0, 0, 0, false)));
        assertFalse(e.addRay(new Ray(0, 0, Double.NaN, 1, false)));
        assertFalse(e.addRay(new Ray(Double.NaN, 0, 1, 0, false)));
        assertTrue(e.estimate().isEmpty());
        assertTrue(e.rays().isEmpty());
    }

    @Test
    public void nonUnitDirectionsAreNormalised() {
        StrongholdEstimator e = est();
        assertTrue(e.addRay(new Ray(0, 0, 10, 0, false)));
        assertEquals(1, e.rays().get(0).dx(), 1e-12);
        assertEquals(2048, get(e).x(), 1e-6);
    }

    // ---- ring prior ----

    @Test
    public void aFitJustInsideTheHoleIsPulledIntoTheRing() {
        // the rays cross at (1250, 0), a hair inside ring 0's inner edge (1296) and well inside the blur: a real
        // stronghold cannot be there so the estimate ends up in the band
        StrongholdEstimator e = est();
        assertTrue(e.addRay(toward(0, 0, 1250, 0)));
        assertTrue(e.addRay(toward(200, 300, 1250, 0)));
        Estimate s = get(e);
        double r = Math.hypot(s.x(), s.z());
        assertTrue("radius from origin " + r, r >= StrongholdEstimator.ringLo(0) - 1 && r <= StrongholdEstimator.ringHi(0) + 1);
        assertTrue("and it moved the right way", r > 1296 && s.x() > 1296);
    }

    @Test
    public void aFitJustBeyondTheRingIsPulledBackToo() {
        StrongholdEstimator e = est();
        assertTrue(e.addRay(toward(0, 0, 2850, 0)));
        assertTrue(e.addRay(toward(300, 400, 2850, 0)));
        Estimate s = get(e);
        double r = Math.hypot(s.x(), s.z());
        assertTrue("radius from origin " + r, r <= StrongholdEstimator.ringHi(0) + 1 && r > 2000);
    }

    @Test
    public void aSharpFitFarOutsideEveryRingIsNotBelieved() {
        // exact rays crossing at (500,0): 800 blocks inside the inner edge with a blur of a few dozen. that is not a
        // stronghold, that is a bad ray, so the second one is turned away
        StrongholdEstimator e = est();
        assertTrue(e.addRay(toward(0, 0, 500, 0)));
        assertFalse(e.addRay(toward(100, 400, 500, 0)));
        assertFalse(e.lastRejection().isEmpty());
        // and between ring 0 and ring 1
        StrongholdEstimator g = est();
        assertTrue(g.addRay(toward(0, 0, 3600, 0)));
        assertFalse(g.addRay(toward(2000, 900, 3600, 0)));
    }
    // ---- dives ----

    @Test
    public void aDivedRayAloneIsAlreadyAnAnswer() {
        StrongholdEstimator e = est();
        assertTrue(e.addRay(new Ray(TX + 5, TZ + 3, -5 / Math.hypot(5, 3), -3 / Math.hypot(5, 3), true)));
        Estimate s = get(e);
        assertTrue(s.snapped());
        assertEquals(TX, s.x(), 0);
        assertEquals(TZ, s.z(), 0);
        Advice a = e.advise(TX + 5, TZ + 3, 0);
        assertEquals(Step.DIG, a.step());
        assertEquals(TX + 4, a.x(), 0);
        assertEquals(TZ + 4, a.z(), 0);
    }

    @Test
    public void aDivedRayTightensABlurryEstimate() {
        StrongholdEstimator e = twoBlurry();
        double blurry = get(e).radius();
        assertTrue(blurry > 24);
        assertTrue(e.addRay(StrongholdEstimator.rayFromPoints(TX - 7, TZ + 4, TX, TZ, true)));
        Estimate s = get(e);
        assertTrue(s.radius() < 12);
        assertTrue(s.snapped());
        assertEquals(TX, s.x(), 0);
        assertEquals(TZ, s.z(), 0);
    }

    @Test
    public void aDiveOverrulesOldRaysThatDisagree() {
        // earlier rays all pointed somewhere else, then an eye dives right here: the dive wins, the old rays are not used
        StrongholdEstimator e = est();
        assertTrue(e.addRay(toward(0, 0, 600, 1500)));
        assertTrue(e.addRay(toward(500, 100, 600, 1500)));
        assertTrue(e.addRay(StrongholdEstimator.rayFromPoints(TX + 6, TZ, TX, TZ, true)));
        Estimate s = get(e);
        assertEquals(TX, s.x(), 0);
        assertEquals(TZ, s.z(), 0);
        assertEquals(Step.DIG, e.advise(TX + 6, TZ, 0).step());
    }

    @Test
    public void aRayThatMissesTheDiveSpotIsRejected() {
        StrongholdEstimator e = est();
        assertTrue(e.addRay(StrongholdEstimator.rayFromPoints(TX - 7, TZ + 4, TX, TZ, true)));
        assertFalse(e.addRay(toward(0, 0, 600, 1500)));
        assertTrue(e.addRay(toTarget(0, 0)));
    }

    @Test
    public void diveSnapsToTheLatticePointInTheDiscThatTheRayPointsAt() {
        // standing 11 blocks from the corner there are two lattice points inside 12 blocks, the ray picks the right one
        double ox = TX + 11;
        double oz = TZ + 2;
        StrongholdEstimator e = est();
        assertTrue(e.addRay(StrongholdEstimator.rayFromPoints(ox, oz, TX, TZ, true)));
        assertEquals(TX, get(e).x(), 0);
        StrongholdEstimator other = est();
        // same spot but the eye was looking at the neighbour (TX + 16, TZ) instead
        assertTrue(other.addRay(StrongholdEstimator.rayFromPoints(ox, oz, TX + 16, TZ, true)));
        assertEquals(TX + 16, get(other).x(), 0);
    }

    // ---- plumbing ----

    @Test
    public void raysAreCappedAndTheNewestSurvives() {
        StrongholdEstimator e = new StrongholdEstimator(new Params(0.25, 5, 12));
        Ray newest = null;
        for (int i = 0; i < 30; i++) {
            double a = 0.2 + i * 0.37;
            newest = toTarget(TX + (200 + i * 30) * Math.cos(a), TZ + (200 + i * 30) * Math.sin(a));
            assertTrue(e.addRay(newest));
            assertTrue(e.rays().size() <= 5);
        }
        assertEquals(5, e.rays().size());
        Ray kept = e.rays().get(4);
        assertEquals(newest.ox(), kept.ox(), 1e-9);
        assertEquals(newest.oz(), kept.oz(), 1e-9);
        // the ones kept are the close, heavy ones
        for (Ray r : e.rays()) {
            assertTrue(Math.hypot(r.ox() - TX, r.oz() - TZ) > 0);
        }
        assertEquals(TX, get(e).x(), 2);
    }

    @Test
    public void trimmingDropsTheFarOldOnesFirst() {
        StrongholdEstimator e = new StrongholdEstimator(new Params(0.25, 3, 12));
        assertTrue(e.addRay(toTarget(0, 0)));          // 2039 away
        assertTrue(e.addRay(toTarget(1500, -1700)));   // 900 away
        assertTrue(e.addRay(toTarget(1900, -400)));    // 520 away
        assertTrue(e.addRay(toTarget(1100, -1300)));   // 560 away
        List<Ray> kept = e.rays();
        assertEquals(3, kept.size());
        for (Ray r : kept) {
            assertTrue("the 2000 block ray should be gone", r.ox() != 0);
        }
    }

    @Test
    public void clearForgetsEverything() {
        StrongholdEstimator e = twoGood();
        e.clear();
        assertTrue(e.estimate().isEmpty());
        assertTrue(e.rays().isEmpty());
        assertEquals(0, e.throwsUsed());
        assertEquals(Step.THROW, e.advise(5, 5, 0).step());
        assertTrue(e.addRay(toTarget(0, 0)));
    }

    @Test
    public void throwBudgetCountsRejectsToo() {
        StrongholdEstimator e = new StrongholdEstimator(new Params(0.25, 12, 12, 3));
        assertFalse(e.throwsExhausted());
        e.addRay(toTarget(0, 0));
        e.addRay(toTarget(0, 0));
        assertFalse(e.throwsExhausted());
        e.addRay(toTarget(0, 0));
        assertTrue(e.throwsExhausted());
        assertEquals(3, e.throwsUsed());
    }

    @Test
    public void rayFromPointsNormalisesAndSurvivesZeroLength() {
        Ray r = StrongholdEstimator.rayFromPoints(10, 10, 40, 50, true);
        assertEquals(0.6, r.dx(), 1e-12);
        assertEquals(0.8, r.dz(), 1e-12);
        assertTrue(r.dived());
        Ray z = StrongholdEstimator.rayFromPoints(1, 1, 1, 1, false);
        assertEquals(0, z.dx(), 0);
        assertEquals(0, z.dz(), 0);
    }

    @Test
    public void sameRaysSameAnswersEveryTime() {
        for (int run = 0; run < 3; run++) {
            StrongholdEstimator a = twoGood();
            StrongholdEstimator b = twoGood();
            assertEquals(get(a), get(b));
            for (double w : new double[]{0, 50, 130, 400}) {
                assertEquals(a.advise(900, -300, w), b.advise(900, -300, w));
            }
            a.addRay(toTarget(1500, -700));
            b.addRay(toTarget(1500, -700));
            assertEquals(get(a), get(b));
        }
    }

    @Test
    public void sigmaParamChangesTheBlurNotThePoint() {
        StrongholdEstimator tight = new StrongholdEstimator(new Params(0.05, 12, 12));
        StrongholdEstimator loose = new StrongholdEstimator(new Params(1.0, 12, 12));
        for (StrongholdEstimator e : List.of(tight, loose)) {
            e.addRay(toTarget(0, 0));
            e.addRay(toTarget(1400, 300));
        }
        assertEquals(get(tight).x(), get(loose).x(), 1.0);
        assertTrue(get(loose).radius() > get(tight).radius() * 5);
    }

    // ---- advise: one branch per rule of the design ----

    @Test
    public void oneRayWalksOffTheLineAtTwentyFiveDegreesTwoHundredBlocks() {
        StrongholdEstimator e = est();
        assertTrue(e.addRay(new Ray(0, 0, 1, 0, false)));
        Advice a = e.advise(0, 0, 0);
        assertEquals(Step.WALK, a.step());
        assertEquals(200, Math.hypot(a.x(), a.z()), 1e-6);
        assertEquals(25, Math.toDegrees(Math.atan2(Math.abs(a.z()), a.x())), 1e-6);
        // exact tie between the two sides, it goes counterclockwise
        assertTrue(a.z() > 0);
    }

    @Test
    public void oneRayChoosesTheSideTowardsTheMiddleOfTheRing() {
        // the middle of ring 0 is 2048 from the origin. from 2000 out the turn that ends further out is closer to it...
        StrongholdEstimator inside = est();
        assertTrue(inside.addRay(new Ray(0, 2000, 1, 0, false)));
        assertTrue(inside.advise(0, 2000, 0).z() > 2000);
        // ...and from 2600 out the turn that bends back towards the origin is
        StrongholdEstimator outside = est();
        assertTrue(outside.addRay(new Ray(0, 2600, 1, 0, false)));
        assertTrue(outside.advise(0, 2600, 0).z() < 2600);
    }
    @Test
    public void oneRayThrowsOnceWalkedOneTwentyAndAwayFromTheThrowSpot() {
        StrongholdEstimator e = est();
        assertTrue(e.addRay(new Ray(0, 0, 1, 0, false)));
        assertEquals(Step.WALK, e.advise(100, 40, 119).step());
        assertEquals(Step.THROW, e.advise(100, 40, 120).step());
        // walked in circles: plenty of distance, still standing on the first throw
        assertEquals(Step.WALK, e.advise(3, 3, 500).step());
    }

    @Test
    public void oneRayKeepsWalkingPastTheWaypointWhenTheSecondRayWasTurnedAway() {
        StrongholdEstimator e = est();
        assertTrue(e.addRay(new Ray(0, 0, 1, 0, false)));
        Advice first = e.advise(0, 0, 0);
        // we reached the first waypoint without a throw (the second ray got rejected, the walked counter restarted): a new
        // waypoint, further along the same heading, never a goal we are already standing on
        Advice again = e.advise(first.x(), first.z(), 80);
        assertEquals(Step.WALK, again.step());
        assertEquals(2 * first.x(), again.x(), 1e-6);
        assertEquals(2 * first.z(), again.z(), 1e-6);
        // and the waypoint is stable while we walk towards it
        assertEquals(first, e.advise(first.x() * 0.4, first.z() * 0.4, 30));
    }
    @Test
    public void farFromTheEstimateWalkUntilFortyPercentOfTheRange() {
        StrongholdEstimator e = twoGood();
        Estimate s = get(e);
        double px = s.x() + 1000;
        double pz = s.z();
        assertEquals(Step.WALK, e.advise(px, pz, 399).step());
        assertEquals(Step.THROW, e.advise(px, pz, 400).step());
        Advice w = e.advise(px, pz, 10);
        assertEquals(Step.WALK, w.step());
        // heads towards the estimate (bent sideways by at most the lead), never away from it
        assertTrue(Math.hypot(w.x() - s.x(), w.z() - s.z()) < 1000);
    }

    @Test
    public void theBentWalkGoalStaysPutWhileWeWalkToIt() {
        // the walk leg replans when its goal moves more than 8 blocks, so a goal that slides as we walk repaths every leg
        StrongholdEstimator e = twoGood();
        Estimate s = get(e);
        double px = s.x() + 1000;
        double pz = s.z();
        Advice first = e.advise(px, pz, 10);
        assertEquals(Step.WALK, first.step());
        double dx = first.x() - px;
        double dz = first.z() - pz;
        double len = Math.hypot(dx, dz);
        Advice later = e.advise(px + dx / len * 200, pz + dz / len * 200, 210);
        assertEquals(Step.WALK, later.step());
        assertEquals(first.x(), later.x(), 1e-6);
        assertEquals(first.z(), later.z(), 1e-6);
    }

    @Test
    public void farButNotThatFarUsesTheOneTwentyFloor() {
        StrongholdEstimator e = twoGood();
        Estimate s = get(e);
        double px = s.x() + 200;
        double pz = s.z();
        assertEquals(Step.WALK, e.advise(px, pz, 119).step());
        assertEquals(Step.THROW, e.advise(px, pz, 120).step());
    }

    @Test
    public void farButSettledWalksInsteadOfThrowingAnotherBaseline() {
        // a tight fit that is not snapped (a snapped one walks on its own), slid in from a few third-ray origins
        boolean found = false;
        for (int back = 200; back <= 1400 && !found; back += 100) {
            StrongholdEstimator e = twoGood();
            if (!e.addRay(toTarget(back, -300 - back / 2.0))) {
                continue;
            }
            Estimate s = get(e);
            double px = s.x() + 1000;
            if (s.snapped() || s.radius() > 24 || e.advise(px, s.z(), 400, 14).step() != Step.WALK) {
                continue;
            }
            // but a very long silent walk earns a new baseline anyway
            assertEquals(Step.THROW, e.advise(px, s.z(), 900, 14).step());
            found = true;
        }
        assertTrue(found);
        // two rays is not enough to call it settled
        StrongholdEstimator two = twoGood();
        assertEquals(Step.THROW, two.advise(get(two).x() + 1000, get(two).z(), 400, 14).step());
    }

    @Test
    public void restoredRejectsKeepAFitFromCountingAsSettled() {
        boolean found = false;
        for (int back = 200; back <= 1400 && !found; back += 100) {
            StrongholdEstimator e = twoGood();
            if (!e.addRay(toTarget(back, -300 - back / 2.0))) {
                continue;
            }
            Estimate s = get(e);
            double px = s.x() + 1000;
            if (s.snapped() || s.radius() > 24 || e.advise(px, s.z(), 400, 14).step() != Step.WALK) {
                continue;
            }
            // a reloaded estimator that lost a rejected throw must stop trusting the fit
            e.restoreRejects(1);
            assertEquals(Step.THROW, e.advise(px, s.z(), 400, 14).step());
            found = true;
        }
        assertTrue(found);
    }

    @Test
    public void lowEyesWalkOnAMidSizedFitButFullBagsKeepThrowing() {
        // slide a third ray's origin until the fit lands between the settled radius and the low-eyes one
        boolean found = false;
        for (int back = 200; back <= 1400 && !found; back += 100) {
            StrongholdEstimator e = twoBlurry();
            if (!e.addRay(toTarget(back, -300 - back / 2.0))) {
                continue;
            }
            Estimate s = get(e);
            if (e.advise(s.x() + 1000, s.z(), 400).step() == Step.THROW && s.radius() <= 100) {
                assertEquals(Step.THROW, e.advise(s.x() + 1000, s.z(), 400, 14).step());
                assertEquals(Step.WALK, e.advise(s.x() + 1000, s.z(), 400, 12).step());
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test
    public void closeButBlurryThrowsAfterFortyBlocksOrInsideSixty() {
        StrongholdEstimator e = twoBlurry();
        Estimate s = get(e);
        assertTrue(s.radius() > 24);
        double px = s.x() + 140;
        double pz = s.z();
        assertEquals(Step.WALK, e.advise(px, pz, 39).step());
        assertEquals(Step.THROW, e.advise(px, pz, 40).step());
        // inside 60 it throws without needing the walk
        assertEquals(Step.THROW, e.advise(s.x() + 50, s.z(), 0).step());
        assertEquals(Step.WALK, e.advise(s.x() + 61, s.z(), 0).step());
    }

    @Test
    public void tightEstimateWithinEightyArrivesAtTheSnappedPoint() {
        StrongholdEstimator e = tight();
        Advice a = e.advise(TX + 60, TZ, 0);
        assertEquals(Step.ARRIVE, a.step());
        assertEquals(TX, a.x(), 0);
        assertEquals(TZ, a.z(), 0);
        a = e.advise(TX + 25, TZ + 25, 300);
        assertEquals(Step.ARRIVE, a.step());
    }

    @Test
    public void tightEstimateBeyondEightyJustWalksThere() {
        StrongholdEstimator e = tight();
        Advice a = e.advise(TX + 120, TZ, 0);
        assertEquals(Step.WALK, a.step());
        assertEquals(TX, a.x(), 1.0);
        assertEquals(TZ, a.z(), 1.0);
    }

    @Test
    public void snappedEstimateFarOutWalksWithoutThrowingNoMatterHowFarWeWalked() {
        StrongholdEstimator e = tight();
        assertTrue(get(e).snapped());
        for (double walked : new double[]{0, 150, 400, 5000}) {
            Advice a = e.advise(TX + 1000, TZ, walked);
            assertEquals(Step.WALK, a.step());
            assertEquals(TX, a.x(), 1.0);
            assertEquals(TZ, a.z(), 1.0);
        }
    }

    @Test
    public void snappedEstimateStillDoesTheDiveThrowOnTheSpot() {
        StrongholdEstimator e = tight();
        assertEquals(Step.WALK, e.advise(TX + 151, TZ, 1000).step());
        assertEquals(Step.THROW, e.advise(TX + 5, TZ - 4, 30).step());
    }

    @Test
    public void standingOnATightEstimateThrowsToTestForTheDive() {
        StrongholdEstimator e = tight();
        assertEquals(Step.THROW, e.advise(TX + 5, TZ - 4, 30).step());
        assertEquals(Step.THROW, e.advise(TX, TZ, 0).step());
    }

    @Test
    public void aLatestRayThatDivedMeansDigAtTheChunksFourFour() {
        StrongholdEstimator e = tight();
        assertTrue(e.addRay(StrongholdEstimator.rayFromPoints(TX + 9, TZ - 3, TX, TZ, true)));
        for (double[] p : new double[][]{{TX + 9, TZ - 3}, {0, 0}, {TX + 500, TZ}}) {
            Advice a = e.advise(p[0], p[1], 99);
            assertEquals(Step.DIG, a.step());
            assertEquals(TX + 4, a.x(), 0);
            assertEquals(TZ + 4, a.z(), 0);
        }
    }

    @Test
    public void digTargetIsInsideTheStartChunk() {
        // chunk (cx, cz) covers [16cx, 16cx+15], the dig spot is +4/+4 of the corner
        Advice a = digAt(-1824, 912);
        assertEquals(-1820, a.x(), 0);
        assertEquals(916, a.z(), 0);
        assertEquals(Math.floorDiv(-1820, 16), Math.floorDiv(-1824, 16));
    }

    private static Advice digAt(double tx, double tz) {
        StrongholdEstimator e = est();
        assertTrue(e.addRay(StrongholdEstimator.rayFromPoints(tx + 3, tz + 3, tx, tz, true)));
        return e.advise(tx + 3, tz + 3, 0);
    }

    @Test
    public void justThrewHereOnTheEstimateStepsOutOfTheTenBlockCircle() {
        // last throw 15 blocks east of the corner (no dive, 15 > 12), now we stand 8 east of the corner: on the spot
        // for the dive test but only 7 from the last throw. so: no throw, step 11 from the last throw towards the corner
        StrongholdEstimator e = tight();
        assertTrue(e.addRay(toTarget(TX + 15, TZ)));
        Advice a = e.advise(TX + 8, TZ, 20);
        assertEquals(Step.WALK, a.step());
        assertEquals(TX + 4, a.x(), 1e-6);
        assertEquals(TZ, a.z(), 1e-6);
        // from the stepped out spot a throw is fine again
        assertEquals(Step.THROW, e.advise(a.x(), a.z(), 4).step());
    }

    @Test
    public void neverAdvisesAThrowWithinTenBlocksOfTheLastThrow() {
        StrongholdEstimator[] fits = {twoGood(), twoBlurry(), tight()};
        for (StrongholdEstimator e : fits) {
            Ray last = e.rays().get(e.rays().size() - 1);
            for (double dx = -9; dx <= 9; dx += 3) {
                for (double dz = -9; dz <= 9; dz += 3) {
                    if (Math.hypot(dx, dz) >= 10) {
                        continue;
                    }
                    for (double walked : new double[]{0, 45, 130, 900}) {
                        Advice a = e.advise(last.ox() + dx, last.oz() + dz, walked);
                        assertNotEquals("at " + dx + "," + dz + " walked " + walked + ": " + a.why(), Step.THROW, a.step());
                    }
                }
            }
        }
        // same for the one ray case
        StrongholdEstimator one = est();
        one.addRay(new Ray(50, 50, 1, 0, false));
        assertNotEquals(Step.THROW, one.advise(52, 51, 500).step());
    }

    @Test
    public void aRejectedThrowStillCountsAsAThrowSpot() {
        StrongholdEstimator e = est();
        assertTrue(e.addRay(new Ray(0, 0, 1, 0, false)));
        // rejected as parallel, but the eye was thrown from (300, 0) all the same
        assertFalse(e.addRay(new Ray(300, 0, 1, 0, false)));
        // two rays? no, one. standing at (300,0) walked 500 must not throw again right on top of that spot
        assertNotEquals(Step.THROW, e.advise(302, 0, 500).step());
        assertEquals(Step.THROW, e.advise(330, 0, 500).step());
    }

    @Test
    public void adviceNeverComesBackWithNaN() {
        StrongholdEstimator[] fits = {est(), twoGood(), twoBlurry(), tight()};
        double[] pos = {-3000, -100, 0, 7, 400, 2000};
        for (StrongholdEstimator e : fits) {
            for (double px : pos) {
                for (double pz : pos) {
                    for (double w : new double[]{0, 60, 700}) {
                        Advice a = e.advise(px, pz, w);
                        assertTrue(Double.isFinite(a.x()) && Double.isFinite(a.z()));
                        assertFalse(a.why().isEmpty());
                    }
                }
            }
        }
    }
}
