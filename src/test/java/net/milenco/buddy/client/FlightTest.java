package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

/**
 * The glide against the numbers of the 26.3 source, and the planner flown in a closed loop (a plan every tick, one tick
 * flown) through made-up worlds: it must never hit a block, and must land slower than the game's 0.5 a tick.
 */
class FlightTest {
	/** Ground: everything at y 63 and below is solid. */
	private static final Flight.World FLAT = (x, y, z) -> y <= 63;

	/** The yaw that flies toward +x. */
	private static final double EAST = -90;

	@Test
	void levelGlideComesDownSlowly() {
		Flight.State s = new Flight.State(0, 200, 0, 0.8, 0, 0, EAST, 0, 0);
		for (int t = 0; t < 400; t++) Flight.step(FLAT, s, EAST, 0);
		// the steady state of updateFallFlyingMovement at pitch 0: gravity first (-0.02), then the lift takes a tenth of
		// that back, then the drag: vy = 0.98 * 0.9 * (vy - 0.02), so -0.1495
		assertEquals(-0.1495, s.vy, 0.001);
		assertTrue(s.vy > -Flight.SAFE_DESCENT);
	}

	@Test
	void aRocketPullsToItsSpeed() {
		Flight.State s = new Flight.State(0, 200, 0, 0.2, 0, 0, EAST, 0, 40);
		for (int t = 0; t < 40; t++) Flight.step(FLAT, s, EAST, 0);
		assertTrue(s.horizontal() > 1.4 && s.horizontal() < 1.9, "speed " + s.horizontal());
	}

	@Test
	void theLookIsTheGames() {
		double[] l = Flight.look(EAST, 0);
		assertEquals(1, l[0], 1e-9);
		assertEquals(0, l[1], 1e-9);
		assertEquals(0, l[2], 1e-9);
		assertEquals(-1, Flight.look(0, 90)[1], 1e-9);   // pitch 90 is straight down
		assertEquals(EAST, Flight.yawTo(0, 0, 10, 0), 1e-9);
	}

	/** A plan every tick, one tick flown; fails at a hit or a hard landing, returns the state at the end. */
	private static Flight.State loop(Flight.World w, Flight.State s, Flight.Goal goal, int ticks, int rockets) {
		int left = rockets;
		for (int t = 0; t < ticks; t++) {
			Flight.Plan p = Flight.best(w, s, goal, left > 0, 15);
			if (p.rocket && s.rocket == 0) { s.rocket = 15; left--; }
			Flight.Event e = Flight.step(w, s, p.yaw, p.pitch);
			if (e == Flight.Event.HIT) fail("hit at tick " + t + " at " + Math.round(s.x) + "," + Math.round(s.y) + "," + Math.round(s.z) + " speed " + s.horizontal());
			if (e == Flight.Event.WATER) return s;
			if (e == Flight.Event.LANDED) {
				if (s.vy <= -0.5) fail("hard landing at tick " + t + ": " + s.vy);
				if (s.horizontal() >= Flight.SAFE_SIDEWAYS) fail("fast landing at tick " + t + ": " + s.horizontal() + " sideways");
				return s;
			}
		}
		return s;
	}

	@Test
	void neverIntoAWallItCannotPass() {
		// a wall from the ground to the sky across the whole way: he must turn away, not fly into it
		Flight.World w = (x, y, z) -> y <= 63 || x == 60 || x == 61;
		Flight.State s = new Flight.State(0, 90, 0, 1.2, 0, 0, EAST, 0, 0);
		Flight.State end = loop(w, s, new Flight.Goal(120, 90, 0, 0, 0, 0, false), 500, 5);
		assertTrue(end.x < 60, "through the wall to " + end.x);
	}

	@Test
	void overAWallWithRockets() {
		Flight.World w = (x, y, z) -> y <= 63 || (x >= 60 && x <= 62 && y <= 105);
		Flight.State s = new Flight.State(0, 90, 0, 1.0, 0, 0, EAST, 0, 0);
		Flight.State end = loop(w, s, new Flight.Goal(160, 95, 0, 0, 0, 0, false), 600, 6);
		assertTrue(end.x > 70, "not past the wall: " + Math.round(end.x));
	}

	@Test
	void landsSlowlyNearTheSpot() {
		Flight.State s = new Flight.State(0, 95, 0, 1.0, 0, 0, EAST, 0, 0);
		Flight.State end = loop(FLAT, s, new Flight.Goal(70, 64, 0, 0, 0, 0, true), 800, 0);
		assertNotEquals(95, end.y);
		assertEquals(64, end.y, 1e-6, "did not come down");
		assertTrue(Math.abs(end.x - 70) < 25, "landed at " + Math.round(end.x));
	}

	@Test
	void landsBesideAHillWithoutHittingIt() {
		// a hill of 20 high just before the spot: down past it, never into its side
		Flight.World w = (x, y, z) -> y <= 63 || (x >= 40 && x <= 55 && Math.abs(z) <= 30 && y <= 83);
		Flight.State s = new Flight.State(0, 92, 0, 1.0, 0, 0, EAST, 0, 0);
		Flight.State end = loop(w, s, new Flight.Goal(75, 64, 0, 0, 0, 0, true), 900, 0);
		assertTrue(end.y < 90, "did not land");
	}

	@Test
	void landsSlowlyOnSteps() {
		// terraces of one block every four blocks: coming down fast, the edge of the next one is a hit
		Flight.World w = (x, y, z) -> y <= 63 + Math.max(0, Math.min(8, Math.floorDiv(x - 40, 4)));
		Flight.State s = new Flight.State(0, 95, 0, 1.2, 0, 0, EAST, 0, 0);
		Flight.State end = loop(w, s, new Flight.Goal(60, 68, 0, 0, 0, 0, true), 900, 0);
		assertTrue(end.y < 80, "did not land");
	}

	@Test
	void aFastTouchdownIsNoSafeOne() {
		// level ground, coming in at 1.2 a tick just above it: the step lands him, but too fast to count as safe
		Flight.State s = new Flight.State(0, 64.1, 0, 1.2, -0.2, 0, EAST, 0, 0);
		assertEquals(Flight.Event.LANDED, Flight.step(FLAT, s, EAST, 0));
		assertTrue(s.horizontal() > Flight.SAFE_SIDEWAYS);
		// and into a step at that speed is a hit
		Flight.World step = (x, y, z) -> y <= 63 || (x >= 2 && y <= 64);
		Flight.State t = new Flight.State(0.9, 64.1, 0, 1.2, -0.2, 0, EAST, 0, 0);
		assertEquals(Flight.Event.HIT, Flight.step(step, t, EAST, 0));
	}

	@Test
	void keepsAboveTheSea() {
		// sea floor at 40, water up to 62: water is no wall, but it is the surface he keeps his height above
		Flight.World sea = new Flight.World() {
			@Override public boolean solid(int x, int y, int z) { return y <= 40; }
			@Override public boolean floor(int x, int y, int z) { return y <= 62; }
		};
		// with rockets enough: without them a glide over the sea must end in it some time (safe, and Glide lands then)
		Flight.State s = new Flight.State(0, 72, 0, 1.2, 0, 0, EAST, 0, 0);
		int left = 200;
		double lowest = s.y;
		for (int t = 0; t < 500; t++) {
			Flight.Plan p = Flight.best(sea, s, new Flight.Goal(2000, 70, 0, 0, 0, 0, false), left > 0, 15);
			if (p.rocket && s.rocket == 0) { s.rocket = 15; left--; }
			Flight.step(sea, s, p.yaw, p.pitch);
			lowest = Math.min(lowest, s.y);
		}
		assertTrue(lowest > 64, "down to " + lowest + " over the sea");
	}

	@Test
	void notIntoWhatIsNotLoaded() {
		// beyond x 100 nothing is known yet: it counts as solid, so he must turn or wait in front of it
		Flight.World w = (x, y, z) -> y <= 63 || x >= 100;
		Flight.State s = new Flight.State(0, 90, 0, 1.4, 0, 0, EAST, 0, 0);
		Flight.State end = loop(w, s, new Flight.Goal(300, 90, 0, 0, 0, 0, false), 400, 4);
		assertTrue(end.x < 100);
	}

	@Test
	void followsAFlyingTarget() {
		// a target that flies off east at 1.2 a tick: he keeps up within reach using rockets, and hits nothing
		Flight.State s = new Flight.State(0, 90, 0, 0.9, 0, 0, EAST, 0, 0);
		int left = 20;
		double tx = 20;
		for (int t = 0; t < 400; t++) {
			tx += 1.2;
			Flight.Goal goal = new Flight.Goal(tx - 6, 93, 0, 1.2, 0, 0, false);
			Flight.Plan p = Flight.best(FLAT, s, goal, left > 0, 15);
			if (p.rocket && s.rocket == 0) { s.rocket = 15; left--; }
			assertEquals(Flight.Event.NONE, Flight.step(FLAT, s, p.yaw, p.pitch), "tick " + t);
		}
		assertTrue(Math.abs(tx - s.x) < 60, "fell behind: " + Math.round(tx - s.x));
	}
}
