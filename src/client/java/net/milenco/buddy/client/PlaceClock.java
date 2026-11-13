package net.milenco.buddy.client;

import java.util.HashMap;
import java.util.Map;

/**
 * (the autopilot's place_click_clock, a fix of 13 Nov 2026, on) the Placer's clock per cell: how long it has aimed at a cell with
 * no block in it, and the cells it gave up. A cell aimed at for {@link #AIM_MS} with no block in it fails, and fails at once for
 * {@link #REST_MS} after, so a caller with more cells goes on to them rather than aim at that one again. test heel's night
 * shelter: a one-layer snow in a wall cell took the crosshair before the face aimed at, no click went, and the step stood five
 * minutes with no clock of its own. Cells are BlockPos.asLong keys, so the rule holds no world.
 */
final class PlaceClock {
	/** Aiming at one cell this long with no block in it: the cell fails. */
	static final long AIM_MS = 5000;
	/** A cell given up fails at once this long after. */
	static final long REST_MS = 10_000;
	/** A cell not aimed at for this long starts its clock afresh (the caller waited for a meal, or was elsewhere). */
	static final long GAP_MS = 2000;

	/** Per cell: when the aim at it began, and when it was last aimed at. */
	private final Map<Long, long[]> aim = new HashMap<>();
	/** Per cell given up: when. */
	private final Map<Long, Long> gaveUp = new HashMap<>();

	/** The cell was given up less than {@link #REST_MS} ago. */
	boolean resting(long cell, long now) {
		Long at = gaveUp.get(cell);
		return at != null && now - at < REST_MS;
	}

	/**
	 * One tick of aiming at the cell with no block in it yet; true when that has gone on for more than {@link #AIM_MS}: the cell is
	 * given up from now, and its aim starts afresh after the rest.
	 */
	boolean aimed(long cell, long now) {
		if (aim.size() > 64 || gaveUp.size() > 64) prune(now);
		long[] a = aim.get(cell);
		if (a == null || now - a[1] > GAP_MS) {
			a = new long[] {now, now};
			aim.put(cell, a);
		}
		a[1] = now;
		if (now - a[0] <= AIM_MS) return false;
		aim.remove(cell);
		gaveUp.put(cell, now);
		return true;
	}

	/** The cell has its block: its clock goes. */
	void done(long cell) {
		aim.remove(cell);
		gaveUp.remove(cell);
	}

	private void prune(long now) {
		gaveUp.values().removeIf(at -> now - at >= REST_MS);
		aim.values().removeIf(a -> now - a[1] > GAP_MS);
	}
}
