package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A creeper's fuse as the client can count it, and the sums of the pen's blow at it (Fuse), held against 26.3's own
 * rules, written out here as the server and the client go through them tick by tick:
 * <ul>
 * <li>the server: Creeper.tick adds the swell direction to its count (from 0 to maxSwell, 30), then super.tick moves it,
 * and a landing (Creeper.causeFallDamage) adds (int) (the distance fallen x 1.5) and leaves it at 25 at most;</li>
 * <li>the client: Creeper.tick adds the same direction, as the server sends it, and knows nothing of the landing.</li>
 * </ul>
 * Both on one timeline: the client's lag behind the server is its ping, which the pen counts apart.
 */
class FuseTest {
	/** The server's count of one creeper's swell. */
	private static final class Server {
		int swell;

		void tick(int dir, double landedFrom) {
			swell = Math.max(0, Math.min(Fuse.FUSE, swell + dir));
			if (landedFrom > 0) {
				swell += (int) (landedFrom * 1.5);
				if (swell > Fuse.FUSE - 5) swell = Fuse.FUSE - 5;
			}
		}
	}

	/** The client's own count (Creeper.tick there). */
	private static int client(int client, int dir) { return Math.max(0, Math.min(Fuse.FUSE, client + dir)); }

	/**
	 * A creeper that walks about a spawning floor at y 80, steps off into a shaft and lands on the floor of a pen at y 60:
	 * the heights of each tick (gravity 0.08 and drag 0.98 a tick, as a mob falls), and whether it stands.
	 */
	private static final class Drop {
		final double[] y;
		final boolean[] ground;
		final int landing;

		Drop(int before, double top, double floor, int after) {
			java.util.List<Double> ys = new java.util.ArrayList<>();
			java.util.List<Boolean> gs = new java.util.ArrayList<>();
			for (int i = 0; i < before; i++) { ys.add(top); gs.add(true); }
			double at = top, vy = 0;
			while (true) {
				at += vy;
				vy = (vy - 0.08) * 0.98;
				if (at <= floor) break;
				ys.add(at);
				gs.add(false);
			}
			landing = ys.size();
			for (int i = 0; i < after; i++) { ys.add(floor); gs.add(true); }
			y = new double[ys.size()];
			ground = new boolean[gs.size()];
			for (int i = 0; i < y.length; i++) { y[i] = ys.get(i); ground[i] = gs.get(i); }
		}
	}

	@Test
	void aLandingAddsWhatTheServerAdds() {
		assertEquals(0, Fuse.fallBoost(0));
		assertEquals(0, Fuse.fallBoost(0.5));
		assertEquals(1, Fuse.fallBoost(1.0));
		assertEquals(4, Fuse.fallBoost(3.0));
		assertEquals(25, Fuse.fallBoost(16.7));
		assertEquals(30, Fuse.fallBoost(20));
	}

	@Test
	void aCreeperThatDroppedDownAShaftIsCountedAsTheServerCountsIt() {
		// walking the spawning floor for two seconds, twenty blocks down, then four ticks before it sees the buddy and swells
		Drop d = new Drop(40, 80, 60, 30);
		Server s = new Server();
		Fuse.Count n = new Fuse.Count();
		int client = 0, swellFrom = d.landing + 4;
		for (int t = 0; t < d.y.length; t++) {
			int dir = t >= swellFrom ? 1 : -1;
			client = client(client, dir);
			s.tick(dir, t == d.landing ? 80 - 60 : 0);
			n.tick(d.ground[t], false, d.y[t], dir, client);
			assertTrue(n.est >= s.swell, "under the server's count at tick " + t + ": " + n.est + " < " + s.swell);
			// a creeper in sight long enough: the count is the server's to the tick
			if (t >= Fuse.FUSE) assertEquals(Fuse.FUSE - s.swell, Fuse.fuse(client, n.est), "the fuse at tick " + t);
			if (s.swell >= Fuse.FUSE) break;
		}
		assertEquals(Fuse.FUSE, s.swell, "it went off");
	}

	@Test
	void aCreeperFirstSeenOnItsWayDownIsNeverCountedLow() {
		// the server saw it fall twenty blocks; the client's count began halfway down, as it came into sight
		Drop d = new Drop(10, 80, 60, 40);
		int first = 0;
		while (d.y[first] > 70) first++;
		Server s = new Server();
		Fuse.Count n = new Fuse.Count();
		int client = 0;
		for (int t = 0; t < d.y.length; t++) {
			int dir = t >= d.landing + 2 ? 1 : -1;
			s.tick(dir, t == d.landing ? 20 : 0);
			if (t < first) continue;
			client = client(client, dir);
			n.tick(d.ground[t], false, d.y[t], dir, client);
			assertTrue(n.est >= s.swell, "under the server's count at tick " + t + ": " + n.est + " < " + s.swell);
			if (s.swell >= Fuse.FUSE) break;
		}
	}

	@Test
	void aCreeperFirstSeenSwellingIsCountedAsNearlyGoneOff() {
		// it swelled at someone else for twelve ticks before it came into sight: the client's own count begins at 0
		Server s = new Server();
		for (int t = 0; t < 12; t++) s.tick(1, 0);
		Fuse.Count n = new Fuse.Count();
		int client = 0;
		int[] dirs = { 1, 1, 1, -1, -1, -1, -1, -1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1 };
		for (int dir : dirs) {
			s.tick(dir, 0);
			client = client(client, dir);
			n.tick(true, false, 64, dir, client);
			assertTrue(n.est >= s.swell, "under the server's count: " + n.est + " < " + s.swell);
			assertTrue(Fuse.fuse(client, n.est) <= Fuse.FUSE - s.swell, "more fuse than the server leaves");
			if (s.swell >= Fuse.FUSE) break;
		}
	}

	@Test
	void waterOnTheWayDownStartsTheFallAnew() {
		// pushed off the spawning floor into a pool three blocks up from the pen's floor: the fall counts from the water
		Fuse.Count n = new Fuse.Count();
		for (int t = 0; t < 40; t++) n.tick(true, false, 80, -1, 0);
		double at = 80, vy = 0;
		while (at > 63) { at += vy; vy = (vy - 0.08) * 0.98; n.tick(false, false, Math.max(at, 63), -1, 0); }
		for (int t = 0; t < 5; t++) n.tick(false, true, 63, -1, 0);
		n.tick(false, false, 61.5, -1, 0);
		n.tick(true, false, 60, -1, 0);
		assertEquals(Fuse.fallBoost(3), n.est, "only the three blocks under the water");
	}

	@Test
	void aJumpOrAStepDownAddsLittle() {
		Fuse.Count n = new Fuse.Count();
		for (int t = 0; t < 40; t++) n.tick(true, false, 64, -1, 0);
		assertEquals(0, n.est, "in sight long enough, not swelling: nothing");
		double[] jump = { 64.42, 64.75, 65.0, 65.17, 65.25, 65.25, 65.18, 65.02, 64.8, 64.5, 64.12 };
		for (double y : jump) n.tick(false, false, y, -1, 0);
		n.tick(true, false, 64, -1, 0);
		assertEquals(1, n.est, "the way down of a jump on flat ground");
		n.tick(true, false, 64, -1, 0);
		assertEquals(0, n.est);
	}

	@Test
	void theOldCountWouldHaveStruckAJustLandedCreeper() {
		// the review's drop farm: twenty blocks, at one heart, it sees the buddy two ticks after it landed. Its fall's damage
		// event set the cooldown on the client too (handleDamageEvent: 20), which runs down a tick at a time
		Drop d = new Drop(40, 80, 60, 30);
		Server s = new Server();
		Fuse.Count n = new Fuse.Count();
		int client = 0, swellFrom = d.landing + 2;
		for (int t = 0; t <= swellFrom; t++) {
			int dir = t >= swellFrom ? 1 : -1;
			client = client(client, dir);
			s.tick(dir, t == d.landing ? 20 : 0);
			n.tick(d.ground[t], false, d.y[t], dir, client);
		}
		int cooldown = 20 - (swellFrom - d.landing);
		// turned to it already, a full blow not needed, the reaction, the cooldown, a ping of two ticks, the margin
		int sum = Fuse.sum(6, 0, 5, Fuse.cooldownWait(cooldown), 0, 2, 3);
		assertEquals(Fuse.FUSE - s.swell, Fuse.fuse(client, n.est), "the server's fuse");
		assertFalse(Fuse.strike(false, 0, false, false, sum, Fuse.fuse(client, n.est), false, 3), "it goes off first: away from it");
		assertTrue(Fuse.strike(false, 0, false, false, sum, Fuse.FUSE - client, false, 3), "by the client's own count it seemed to have time");
	}

	@Test
	void aBlowWithinTheCooldownOfAFallWaitsForIt() {
		assertEquals(10, Fuse.cooldownWait(20));
		assertEquals(1, Fuse.cooldownWait(11));
		assertEquals(0, Fuse.cooldownWait(10));
		assertEquals(0, Fuse.cooldownWait(0));
	}

	@Test
	void theSumIsTheSlowestOfItsPartsAndWhatComesAfter() {
		assertEquals(9 + 1 + 2 + 3, Fuse.sum(9, 4, 5, 0, 0, 2, 3));
		assertEquals(7 + 1 + 2 + 3, Fuse.sum(3, 4, 5, 7, 0, 2, 3));
		assertEquals(11 + 1 + 0 + 3, Fuse.sum(3, 4, 5, 7, 11, 0, 3));
	}

	@Test
	void aStrikeBegunGoesOnThroughAHitchItCanCount() {
		// knocked into the air for six ticks with a fuse of fourteen left: the blow still fits
		assertTrue(Fuse.strike(true, 6, false, false, Fuse.sum(0, 0, 0, 0, 6, 2, 3), 14, true, 3));
		// a new one waits for no hitch at all
		assertFalse(Fuse.strike(false, 6, false, false, Fuse.sum(0, 0, 0, 0, 6, 2, 3), 14, false, 3));
		// a mob in the line early on, while getting away still clears the buddy: waited for while it fits and is not late
		assertTrue(Fuse.strike(true, 0, true, false, Fuse.sum(0, 0, 0, 0, 2, 2, 3), 25, false, 3));
		// ... and given up as getting away stops clearing the buddy, while that is still in time
		assertFalse(Fuse.strike(true, 0, true, false, Fuse.sum(0, 0, 0, 0, 2, 2, 3), 22, true, 3));
		// a mob in the line once it was late already: kept while it fits but for the margin
		assertTrue(Fuse.strike(true, 0, true, true, Fuse.sum(0, 0, 0, 0, 2, 2, 3), 7, true, 3));
		assertFalse(Fuse.strike(true, 0, true, true, Fuse.sum(0, 0, 0, 0, 2, 2, 3), 5, true, 3));
		// late, no hitch, the sum over the fuse by less than the margin: the walk off leads nowhere clear, kept
		assertTrue(Fuse.strike(true, 0, false, false, 12, 10, true, 3));
		// not late: away from it while that still clears the buddy
		assertFalse(Fuse.strike(true, 0, false, false, 12, 10, false, 3));
	}

	@Test
	void aStrikeBegunIsKeptThroughAMomentOutOfSightAndBesideASecondOne() {
		int grace = Pen.URGENT_GRACE;
		// the creeper out of the client's own sight for a tick, or a second one swelling: its plan still fits
		assertTrue(Fuse.keeps(true, true, false, true, false, 0, grace), "alone");
		assertTrue(Fuse.keeps(true, true, true, false, true, 0, grace), "a second one swells and the walk off clears the buddy of nothing");
		assertFalse(Fuse.keeps(true, true, false, false, false, 0, grace), "a second one swells and the walk off clears the buddy of both");
		assertFalse(Fuse.keeps(true, false, true, true, true, 0, grace), "no longer in time: the strike says so");
		// no plan for a moment (a bystander in a sword's sweep): held while late, for the grace
		assertTrue(Fuse.keeps(false, false, false, true, true, grace, grace));
		assertFalse(Fuse.keeps(false, false, false, true, true, grace + 1, grace), "the grace over");
		assertFalse(Fuse.keeps(false, false, false, true, false, 1, grace), "not late: away from it while that clears the buddy");
		assertFalse(Fuse.keeps(false, false, false, false, true, 1, grace), "and not beside a second one");
	}

	@Test
	void aWalkOutOfTheBlastTakesItsBlocks() {
		assertEquals(0, Fuse.walkOut(6.5, 6));
		assertEquals(Fuse.WALK_START + 18, Fuse.walkOut(2.5, 6));
		// upright beside the fence of a pit too (the test of 22 Oct 2026): from 2.5 off a creeper in it, a walk off at once
		// clears the walker within its fuse, where with the first block crouched it never did, and every strike there was late
		assertTrue(Fuse.walkOut(2.5, 6) < Fuse.FUSE);
	}

	@Test
	void hisTimeInTheAirIsAPlayersFall() {
		assertEquals(12, Fuse.airTicks(0, 0.42), "a jump");
		assertEquals(6, Fuse.airTicks(1, 0), "a block down");
		int knocked = Fuse.airTicks(0, 0.361);
		assertTrue(knocked >= 9 && knocked <= 12, "an arrow's knock: " + knocked);
		assertEquals(40, Fuse.airTicks(200, 0), "at most two seconds");
	}

	@Test
	void theDeathOfOneStruckIsWaitedForNoShorterThanAClick() {
		assertEquals(167, Fuse.struckHold(0, PenShape.MIN_GAP));
		assertEquals(167, Fuse.struckHold(1, PenShape.MIN_GAP));
		assertEquals(250, Fuse.struckHold(3, PenShape.MIN_GAP));
	}
}
