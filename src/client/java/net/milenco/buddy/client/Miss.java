package net.milenco.buddy.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Where a jump over a gap comes down when it goes wrong, and whether the jumper lives through that. A jump that falls
 * short, goes too far or drifts lands in whatever lies under it, so before Follow takes a jump every column the jumper's
 * middle can come down in is looked at: lava, fire or magma anywhere under the arc rules the jump out, and so does a drop
 * deeper than can be taken. Water catches any fall. On 18 Oct 2026 the buddy jumped short over a pool of lava two blocks wide,
 * and then, burning, from a floor five blocks onto a single block three lower above a cave thirteen deep, missed it
 * and died of the fall and the fire (test {@code MissTest}, from that world).
 */
final class Miss {
	private Miss() {}

	/** What lies at one block, as far as a fall is concerned. */
	record Block(int kind, double top) {}

	static final int AIR = 0, SOLID = 1, WATER = 2, HAZARD = 3;
	static final Block AIR_BLOCK = new Block(AIR, 0), WATER_BLOCK = new Block(WATER, 0), HAZARD_BLOCK = new Block(HAZARD, 0);

	/** A block with a collision shape whose top is {@code top} above the block's own y. */
	static Block solid(double top) { return new Block(SOLID, top); }

	/** The world, block by block. */
	interface Probe { Block at(int x, int y, int z); }

	/** How far a jump rises above where it left: the arc clearArc() allows for. */
	static final double RISE = 1.25;
	/** Beyond the middle of the landing a jump that goes too far comes down: a block. */
	static final double OVER = 1.0;
	/** To either side of the line: half the jumper's width at the take-off, growing with the drift to this at the landing. */
	static final double SIDE = 0.6;

	/**
	 * The columns (x, z) the jumper's middle can come down in on a jump from the middle of one cell to the middle of the
	 * other: along the line and a block beyond the landing, 0.3 to either side at the take-off (the cell stood in, not
	 * the ones beside or behind it) up to {@link #SIDE} at the landing and beyond.
	 */
	static List<int[]> columns(int fx, int fz, int tx, int tz) {
		double x0 = fx + 0.5, z0 = fz + 0.5, x1 = tx + 0.5, z1 = tz + 0.5;
		double len = Math.hypot(x1 - x0, z1 - z0);
		double ux = len == 0 ? 0 : (x1 - x0) / len, uz = len == 0 ? 0 : (z1 - z0) / len;
		double end = len + OVER;
		int n = Math.max(2, (int) Math.ceil(end / 0.25));
		Set<Long> seen = new LinkedHashSet<>();
		List<int[]> out = new ArrayList<>();
		for (int i = 0; i <= n; i++) {
			double s = end * i / n, x = x0 + ux * s, z = z0 + uz * s;
			double m = 0.3 + (SIDE - 0.3) * (len == 0 ? 1 : Math.min(1, s / len));
			for (int cx = (int) Math.floor(x - m); cx <= (int) Math.floor(x + m); cx++)
				for (int cz = (int) Math.floor(z - m); cz <= (int) Math.floor(z + m); cz++)
					if (seen.add(((long) cx << 32) ^ (cz & 0xffffffffL))) out.add(new int[] { cx, cz });
		}
		return out;
	}

	/**
	 * Whether a jump from feet height {@code fc} in cell (fx, fz) to feet height {@code fh} in cell (tx, tz) can go wrong
	 * without killing the jumper: in every column the jumper can come down in, the first thing under the top of the arc is
	 * water, or however deep, or a block to fall onto no more than {@code maxFall} below the top of the arc, and nothing on the
	 * way down is lava, fire, magma or the like. With {@code voidOk} (the games' dimension, where the parkour course
	 * hangs in the void and a miss puts the jumper back at the checkpoint) a column with nothing in it down to that depth
	 * counts as safe; lava and fire still do not.
	 */
	static boolean safe(Probe w, int fx, int fz, double fc, int tx, int tz, double fh, double maxFall, boolean voidOk) {
		double apex = fc + RISE;
		int top = (int) Math.floor(Math.max(fc, fh) + RISE), bottom = (int) Math.floor(apex - maxFall) - 1;
		for (int[] col : columns(fx, fz, tx, tz)) {
			if (!safeColumn(w, col[0], col[1], top, bottom, apex, maxFall, voidOk)) return false;
		}
		return true;
	}

	/** Below the depth of a fall allowed, a column is still looked into this far for water, which takes any fall. */
	static final int WATER_LOOK = 64;

	private static boolean safeColumn(Probe w, int x, int z, int top, int bottom, double apex, double maxFall, boolean voidOk) {
		// on the course nothing below the depth matters: the void there is no death, water no help
		int last = voidOk ? bottom : top - WATER_LOOK;
		for (int y = top; y >= last; y--) {
			Block b = w.at(x, y, z);
			switch (b.kind()) {
				case HAZARD: return false;
				case WATER: return true;
				case SOLID: return apex - (y + b.top()) <= maxFall + 1e-6;
				default: break;
			}
		}
		return voidOk;
	}

	/**
	 * How far a fall may be when a jump goes wrong, with this much health: the three blocks that cost nothing, and up to
	 * two hearts more, never so much that fewer than three hearts are left.
	 */
	static double maxFall(float health) {
		return 3 + Math.max(0, Math.min(4, health - 6));
	}
}
