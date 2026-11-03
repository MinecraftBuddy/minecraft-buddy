package net.milenco.buddy.client;

import java.util.HashMap;
import java.util.Map;

/**
 * The same thing failing twice is not tried a third time for a while (after Player2's RepeatedCommandFailureGuard and
 * Botcraft's retry counters; the panel showed the alt walking to the same chest every few seconds, "cannot get there"
 * each time). A failure is named by what was
 * tried and where (a walk to a cell, a chest, a furnace); the second within ten minutes blocks it for ten minutes and
 * is told once, as the event {@code hulp} {@code geeft op}. A success clears it.
 * <p>
 * A block that comes back is longer each time (21 Oct 2026: the bone meal chest under the composter, out of the buddy's
 * reach, was walked to and given up 21 times in a night): ten minutes, then thirty, then an hour and a half, at most
 * six hours, counted per goal over the last day.
 */
final class Failures {
	private Failures() {}

	private static final long WINDOW = 10 * 60_000, BLOCK = 10 * 60_000;

	private record Count(int times, long first) {}

	private static final Map<String, Count> COUNTS = new HashMap<>();
	private static final Map<String, Long> BLOCKED = new HashMap<>();
	private record Strikes(int n, long since) {}
	private static final Map<String, Strikes> STRIKES = new HashMap<>();
	private static final long DAY = 24 * 3_600_000L, MOST = 6 * 3_600_000L;

	/** Blocked after failing twice: do not try it now. */
	static boolean blocked(String what) {
		Long until = BLOCKED.get(what);
		if (until == null) return false;
		if (until > System.currentTimeMillis()) return true;
		BLOCKED.remove(what);
		return false;
	}

	/** It failed; true when that was the second time and it is blocked now. */
	static boolean failed(String what, String why, int x, int y, int z) {
		long now = System.currentTimeMillis();
		Count c = COUNTS.get(what);
		c = c == null || now - c.first > WINDOW ? new Count(1, now) : new Count(c.times + 1, c.first);
		COUNTS.put(what, c);
		if (c.times < 2) return false;
		COUNTS.remove(what);
		Strikes s = STRIKES.get(what);
		s = s == null || now - s.since > DAY ? new Strikes(1, now) : new Strikes(s.n() + 1, s.since());
		STRIKES.put(what, s);
		long block = Math.min(MOST, BLOCK * (long) Math.pow(3, s.n() - 1));
		BLOCKED.put(what, now + block);
		Exchange.emit("helper", "action", "gives up", "what", what, "reason", why, "minutes", block / 60_000, "times", s.n(), "x", x, "y", y, "z", z);
		return true;
	}

	/** It worked: start counting again. */
	static void succeeded(String what) {
		COUNTS.remove(what);
		STRIKES.remove(what);
		BLOCKED.remove(what);
	}

	/** A name for a place, coarse enough that the cells beside a chest count as one goal. */
	static String near(String kind, double x, double y, double z) {
		return kind + " " + (int) Math.floor(x / 3) + "," + (int) Math.floor(y / 3) + "," + (int) Math.floor(z / 3);
	}
}
