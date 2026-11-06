package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.tasks.speedrun.gamer.tasks.EyeThrowObserver.Outcome;
import adris.altoclef.tasks.speedrun.gamer.tasks.EyeThrowObserver.Result;
import adris.altoclef.world.StrongholdEstimator;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// synthetic eyes: leaves the throw spot on a line toward the target, 12 blocks out, rises 8, hovers
public class EyeThrowObserverTest {
    private static final double OX = 100.5;
    private static final double OZ = -50.5;
    private static final double Y0 = 64.9;

    // horizontal distance flown after t ticks: speeds up slowly, settles on 12 blocks around tick 30
    private static double flown(int t, double max) {
        return max * (1 - Math.exp(-t * t / 400.0));
    }

    private static Result fly(EyeThrowObserver obs, double angleDeg, double maxDist, boolean rises, double noise, long seed, int ticks) {
        Random rnd = new Random(seed);
        double a = Math.toRadians(angleDeg);
        Result r = obs.result();
        for (int t = 1; t <= ticks; t++) {
            double d = flown(t, maxDist);
            double x = OX + Math.sin(a) * d + rnd.nextGaussian() * noise;
            double z = OZ + Math.cos(a) * d + rnd.nextGaussian() * noise;
            double y = rises ? Y0 + 8 * (1 - Math.exp(-t / 20.0)) : Y0 - Math.min(3, t / 10.0);
            r = obs.observe(1000 + t, true, x, y, z);
            if (obs.isDone()) {
                return r;
            }
        }
        return r;
    }

    private static double errorDeg(StrongholdEstimator.Ray ray, double angleDeg) {
        double a = Math.toRadians(angleDeg);
        double dot = ray.dx() * Math.sin(a) + ray.dz() * Math.cos(a);
        return Math.toDegrees(Math.acos(Math.min(1, dot)));
    }

    @Test
    public void straightFlightGivesTheBearing() {
        EyeThrowObserver obs = new EyeThrowObserver(OX, OZ, 1000);
        Result r = fly(obs, 37, 12, true, 0, 1, 120);
        assertEquals(Outcome.RAY, r.outcome());
        assertFalse(r.dived());
        assertTrue(errorDeg(r.ray(), 37) < 0.01);
        // the origin snaps to the first sighting, which is a hair along the line from the throw spot
        assertEquals(OX, r.ray().ox(), 0.1);
        assertEquals(OZ, r.ray().oz(), 0.1);
    }

    @Test
    public void everyCompassDirectionIncludingTheYawWrap() {
        // around 180 / -180 and 0 / 360 an atan2 compare would flip, the vector never does
        for (double angle : new double[]{0, 0.3, 90, 179.9, 180, 180.1, -135, -179.9, 270, 359.9}) {
            EyeThrowObserver obs = new EyeThrowObserver(OX, OZ, 1000);
            Result r = fly(obs, angle, 12, true, 0, 2, 120);
            assertEquals("angle " + angle, Outcome.RAY, r.outcome());
            assertTrue("angle " + angle, errorDeg(r.ray(), angle) < 0.01);
            assertEquals(1.0, Math.hypot(r.ray().dx(), r.ray().dz()), 1e-9);
        }
    }

    @Test
    public void positionNoiseStaysUnderAHalfDegree() {
        for (int seed = 0; seed < 50; seed++) {
            EyeThrowObserver obs = new EyeThrowObserver(OX, OZ, 1000);
            // 0.01 block sigma per axis is still several times the 1/4096 quantisation the server sends
            Result r = fly(obs, 123.4, 12, true, 0.01, seed, 120);
            assertEquals(Outcome.RAY, r.outcome());
            assertTrue("seed " + seed, errorDeg(r.ray(), 123.4) < 0.5);
        }
    }

    @Test
    public void finishesEarlyOnceItHovers() {
        EyeThrowObserver obs = new EyeThrowObserver(OX, OZ, 1000);
        Result r = fly(obs, 10, 12, true, 0, 3, 200);
        assertEquals(Outcome.RAY, r.outcome());
        // not waiting for the 80 tick despawn
        for (int t = 1; t <= 200; t++) {
            EyeThrowObserver o2 = new EyeThrowObserver(OX, OZ, 1000);
            fly(o2, 10, 12, true, 0, 3, t);
            if (o2.isDone()) {
                assertTrue("done at " + t, t <= EyeThrowObserver.EARLY_AGE_TICKS + 1);
                return;
            }
        }
        throw new AssertionError("never finished");
    }

    @Test
    public void closeToTheTargetTheEyeDives() {
        EyeThrowObserver obs = new EyeThrowObserver(OX, OZ, 1000);
        Result r = fly(obs, 250, 7, false, 0, 4, 120);
        assertEquals(Outcome.RAY, r.outcome());
        assertTrue(r.dived());
        assertTrue(r.ray().dived());
        assertTrue(errorDeg(r.ray(), 250) < 0.01);
    }

    @Test
    public void aRisingEyeIsNeverADive() {
        // exactly 12 blocks away horizontally is the far side of the threshold, it rises
        EyeThrowObserver obs = new EyeThrowObserver(OX, OZ, 1000);
        assertFalse(fly(obs, 15, 12, true, 0, 5, 120).dived());
    }

    @Test
    public void diveRightOnTopStillReportsTheDive() {
        // the target is under our feet: it barely moves sideways but sinks, the ray direction is junk, the dive is the news
        EyeThrowObserver obs = new EyeThrowObserver(OX, OZ, 1000);
        Result r = fly(obs, 0, 0.3, false, 0, 6, 120);
        assertEquals(Outcome.RAY, r.outcome());
        assertTrue(r.dived());
    }

    @Test
    public void noEyeEverIsAnEmptyThrowAfterThreeSeconds() {
        EyeThrowObserver obs = new EyeThrowObserver(OX, OZ, 1000);
        for (int t = 1; t < EyeThrowObserver.EMPTY_TICKS; t++) {
            assertEquals(Outcome.PENDING, obs.observe(1000 + t, false, 0, 0, 0).outcome());
        }
        Result r = obs.observe(1000 + EyeThrowObserver.EMPTY_TICKS, false, 0, 0, 0);
        assertEquals(Outcome.EMPTY, r.outcome());
        assertNull(r.ray());
        assertFalse(obs.sawEye());
    }

    @Test
    public void eyeThatNeverLeftTheSpotIsUnusableNotEmpty() {
        EyeThrowObserver obs = new EyeThrowObserver(OX, OZ, 1000);
        obs.observe(1003, true, OX, Y0, OZ);
        obs.observe(1004, true, OX + 0.1, Y0 + 0.1, OZ);
        Result r = null;
        for (int t = 0; t < 5; t++) {
            r = obs.observe(1005 + t, false, 0, 0, 0);
        }
        assertEquals(Outcome.UNUSABLE, r.outcome());
        assertTrue(obs.sawEye());
    }

    @Test
    public void eyeGoneEarlyStillGivesARayFromWhatWeSaw() {
        EyeThrowObserver obs = new EyeThrowObserver(OX, OZ, 1000);
        // shot out 4 blocks along +x in 10 ticks, then the entity vanished (picked off, chunk unloaded)
        for (int t = 1; t <= 10; t++) {
            obs.observe(1000 + t, true, OX + 0.4 * t, Y0 + t * 0.2, OZ);
        }
        Result r = null;
        for (int t = 11; t < 15; t++) {
            r = obs.observe(1000 + t, false, 0, 0, 0);
        }
        assertEquals(Outcome.RAY, r.outcome());
        assertEquals(1.0, r.ray().dx(), 1e-9);
        assertEquals(0.0, r.ray().dz(), 1e-9);
    }

    @Test
    public void aBlinkOfLostTrackingDoesNotEndTheThrow() {
        EyeThrowObserver obs = new EyeThrowObserver(OX, OZ, 1000);
        obs.observe(1001, true, OX + 1, Y0, OZ);
        obs.observe(1002, false, 0, 0, 0);
        obs.observe(1003, false, 0, 0, 0);
        assertFalse(obs.isDone());
        obs.observe(1004, true, OX + 2, Y0, OZ);
        // the gone counter restarted, two more missing ticks are still not enough
        obs.observe(1005, false, 0, 0, 0);
        obs.observe(1006, false, 0, 0, 0);
        assertFalse(obs.isDone());
    }

    @Test
    public void originSnapsToTheTrueSpawnWhenWeDrifted() {
        // we stood at (OX, OZ) when we sent the use, the server had us 0.25 blocks east: the eye spawned there
        double trueX = OX + 0.25;
        double angle = 60;
        double a = Math.toRadians(angle);
        EyeThrowObserver obs = new EyeThrowObserver(OX, OZ, 1000);
        obs.observe(1001, true, trueX, Y0, OZ);
        Result r = null;
        for (int t = 2; t <= 80 && !obs.isDone(); t++) {
            double d = flown(t, 12);
            r = obs.observe(1000 + t, true, trueX + Math.sin(a) * d, Y0 + 8, OZ + Math.cos(a) * d);
        }
        assertEquals(Outcome.RAY, r.outcome());
        assertEquals(trueX, r.ray().ox(), 1e-9);
        assertTrue(errorDeg(r.ray(), angle) < 0.01);
    }

    @Test
    public void afterItIsDoneNothingChanges() {
        EyeThrowObserver obs = new EyeThrowObserver(OX, OZ, 1000);
        Result first = fly(obs, 33, 12, true, 0, 7, 120);
        Result again = obs.observe(2000, true, 0, 0, 0);
        assertEquals(first, again);
    }

    @Test
    public void interruptedBeforeTheFirstSightingIsNotAnEmptyThrow() {
        EyeThrowObserver obs = new EyeThrowObserver(OX, OZ, 1000);
        obs.observe(1005, false, 0, 0, 0);
        obs.markInterrupted();
        Result r = obs.observe(1000 + EyeThrowObserver.EMPTY_TICKS, false, 0, 0, 0);
        assertEquals(Outcome.INTERRUPTED, r.outcome());
    }

    @Test
    public void aGenuinelyEmptyThrowStaysEmpty() {
        EyeThrowObserver obs = new EyeThrowObserver(OX, OZ, 1000);
        Result r = obs.observe(1000 + EyeThrowObserver.EMPTY_TICKS, false, 0, 0, 0);
        assertEquals(Outcome.EMPTY, r.outcome());
    }

    @Test
    public void interruptAfterTheEyeWasSeenChangesNothing() {
        EyeThrowObserver obs = new EyeThrowObserver(OX, OZ, 1000);
        obs.observe(1003, true, OX, Y0, OZ);
        obs.markInterrupted();
        Result r = obs.observe(1000 + EyeThrowObserver.EMPTY_TICKS, false, 0, 0, 0);
        assertFalse(r.outcome() == Outcome.INTERRUPTED || r.outcome() == Outcome.EMPTY);
    }

    @Test
    public void thirdInterruptedThrowInARowBooksAsEmpty() {
        EyeThrowObserver.Streak streak = new EyeThrowObserver.Streak();
        assertEquals(Outcome.INTERRUPTED, streak.book(Outcome.INTERRUPTED));
        // (a pending tick says nothing about the streak)
        assertEquals(Outcome.PENDING, streak.book(Outcome.PENDING));
        assertEquals(Outcome.INTERRUPTED, streak.book(Outcome.INTERRUPTED));
        assertEquals(Outcome.EMPTY, streak.book(Outcome.INTERRUPTED));
        // and it starts over after that
        assertEquals(Outcome.INTERRUPTED, streak.book(Outcome.INTERRUPTED));
    }

    @Test
    public void aSightingOrAnyOtherOutcomeBreaksTheStreak() {
        EyeThrowObserver.Streak streak = new EyeThrowObserver.Streak();
        streak.book(Outcome.INTERRUPTED);
        streak.book(Outcome.INTERRUPTED);
        assertEquals(Outcome.RAY, streak.book(Outcome.RAY));
        assertEquals(Outcome.INTERRUPTED, streak.book(Outcome.INTERRUPTED));
        assertEquals(Outcome.INTERRUPTED, streak.book(Outcome.INTERRUPTED));
        assertEquals(Outcome.UNUSABLE, streak.book(Outcome.UNUSABLE));
        assertEquals(Outcome.INTERRUPTED, streak.book(Outcome.INTERRUPTED));
        assertEquals(Outcome.INTERRUPTED, streak.book(Outcome.INTERRUPTED));
        assertEquals(Outcome.EMPTY, streak.book(Outcome.INTERRUPTED));
    }
}
