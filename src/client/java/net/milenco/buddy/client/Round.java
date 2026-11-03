package net.milenco.buddy.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * The jump around a wall, as on a parkour course's obsidian stretch, which is faster than the way round. The straight
 * line from one block to the next runs through a pillar between them, and past its side, through the air, it does not:
 * from the corner of the block, crouched to its edge, a sprinting jump angled outward, and in the air steered back in
 * so that he comes down on the block behind the pillar, not beside it. That course holds that jump at 1.6 blocks of
 * travel along the wall; it leaves about 0.13 of a block to spare on either side, so it is worked out tick by tick with
 * the game's own numbers and the real blocks, and worked out again every tick in the air from where he is.
 * Directions are angles in degrees from straight on (along the jump), positive toward the side he passes the pillar.
 */
final class Round {
	private Round() {}

	/** In the air with the sprint key down: what the facing direction adds a tick (0.02 * 1.3 * 0.98). */
	static final double ACC = 0.02548;
	/** The take-off tick: the ground's 0.1274 with sprint and the sprinting jump's 0.2, both along the facing direction. */
	static final double TAKE_OFF = 0.3274;
	/** Where he stands for it: 0.15 back from the middle of the block, at its outer edge (a crouched player stops there). */
	static final double START_U = -0.15, START_W = 0.77;
	/** Where the jump onto that block aims: half a block short of the corner, as far as the speed he lands with slides him. */
	static final double LAND_U = -0.6, LAND_W = 0.6;
	/** What a jump from where he happens to stand has to leave over before he takes it without going to the corner. */
	static final double GO = 0.04;

	/** Take-off angles to try, and the steering afterwards: one direction for k ticks, then another. */
	static final double[] FIRSTS = { 6, 9, 12, 15, 18, 21, 24, 27, 30, 33, 36, 39, 42, 45, 48, 51, 54, 57, 60 };
	static final double[] AIRS = { -90, -75, -60, -45, -30, -15, 0, 15, 30, 45, 60 };
	static final int[] KS = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 };
	// fewer, for the look every tick while he crouches to the corner (the whole lot takes a tenth of a second)
	static final double[] FIRSTS_M = { 12, 18, 24, 30, 36, 42, 48, 54 };
	static final double[] AIRS_M = { -90, -75, -60, -45, -30, -15 };
	// the planner's quick look: a few of each, enough to know the jump is there
	private static final double[] PLAN_FIRSTS = { 18, 24, 30, 36 };
	private static final double[] PLAN_A1 = { -45, -30, -15, 0 };
	private static final int[] PLAN_KS = { 1, 2, 3, 4, 5, 6 };
	private static final double[] PLAN_A2 = { -75, -60, -45 };
	/** What the planner's look has to leave over: clearance from the pillar and room on the landing. */
	private static final double PLAN_SPARE = 0.06;

	/** Along the jump (u), sideways toward the side he passes (w), from the middle of the block he leaves. */
	static final class Frame {
		final double ox, oz, fx, fz, sx, sz;

		Frame(AABB from, BlockPos a, BlockPos b, int side) {
			ox = (from.minX + from.maxX) / 2;
			oz = (from.minZ + from.maxZ) / 2;
			fx = Integer.signum(b.getX() - a.getX());
			fz = Integer.signum(b.getZ() - a.getZ());
			sx = side > 0 ? -fz : fz;
			sz = side > 0 ? fx : -fx;
		}

		double u(double x, double z) { return (x - ox) * fx + (z - oz) * fz; }
		double w(double x, double z) { return (x - ox) * sx + (z - oz) * sz; }
		double x(double u, double w) { return ox + u * fx + w * sx; }
		double z(double u, double w) { return oz + u * fz + w * sz; }

		double[] dir(double a) {
			double r = Math.toRadians(a), c = Math.cos(r), s = Math.sin(r);
			return new double[] { fx * c + sx * s, fz * c + sz * s };
		}

		/** The game's yaw for a direction: moving along (-sin yaw, cos yaw). */
		float yaw(double a) {
			double[] d = dir(a);
			return (float) Math.toDegrees(Math.atan2(-d[0], d[1]));
		}

		/** The angle of a yaw. */
		double rel(float yaw) {
			double r = Math.toRadians(yaw), dx = -Math.sin(r), dz = Math.cos(r);
			return Math.toDegrees(Math.atan2(dx * sx + dz * sz, dx * fx + dz * fz));
		}

		/** Is the strafe-left key toward the side, looking straight on? Left of where he looks is (cos yaw, sin yaw). */
		boolean sideIsLeft() {
			double r = Math.toRadians(yaw(0));
			return Math.cos(r) * sx + Math.sin(r) * sz > 0;
		}
	}

	/** A way round: the take-off angle (NaN in the air), a1 for k ticks, then a2; how much it leaves over. */
	static final class Plan {
		final double first, a1, a2, score;
		final int k;
		/** Where it has him after the next tick: to hold the arithmetic against what happened. */
		final double nx, nz;

		Plan(double first, double a1, int k, double a2, double score, double nx, double nz) {
			this.first = first; this.a1 = a1; this.k = k; this.a2 = a2; this.score = score; this.nx = nx; this.nz = nz;
		}
	}

	/** Every block's collision box around the two cells, from a block under his feet to above where a jump takes his head. */
	static List<AABB> obstacles(Level level, BlockPos a, BlockPos b, double feet) {
		List<AABB> out = new ArrayList<>();
		int y0 = (int) Math.floor(feet) - 1, y1 = (int) Math.floor(feet) + 4;
		for (int x = Math.min(a.getX(), b.getX()) - 2; x <= Math.max(a.getX(), b.getX()) + 2; x++)
			for (int z = Math.min(a.getZ(), b.getZ()) - 2; z <= Math.max(a.getZ(), b.getZ()) + 2; z++)
				for (int y = y0; y <= y1; y++) {
					BlockPos pos = new BlockPos(x, y, z);
					for (AABB box : level.getBlockState(pos).getCollisionShape(level, pos).toAabbs()) out.add(box.move(pos));
				}
		return out;
	}

	private static final double E = 1e-7;

	/** His body (0.6 wide, 1.8 high) at this place against the blocks: the clearance in the open, or -1 where it hits. */
	private static double clear(List<AABB> obs, double x, double y, double z) {
		double c = 1.0;
		for (AABB o : obs) {
			if (o.maxY <= y + E || o.minY >= y + 1.8 - E) continue;
			double gx = Math.max(o.minX - (x + 0.3), (x - 0.3) - o.maxX), gz = Math.max(o.minZ - (z + 0.3), (z - 0.3) - o.maxZ);
			double sep = Math.max(gx, gz);
			if (sep <= E) return -1;
			c = Math.min(c, sep);
		}
		return c;
	}

	/** The top of what he comes down on at this place, or NaN when nothing is under him there. */
	private static double ground(List<AABB> obs, double x, double y, double z) {
		double g = Double.NaN;
		for (AABB o : obs) {
			if (o.maxY <= y + E || o.minY >= y + 1.8 - E) continue;
			if (o.minX >= x + 0.3 - E || o.maxX <= x - 0.3 + E || o.minZ >= z + 0.3 - E || o.maxZ <= z - 0.3 + E) continue;
			g = Double.isNaN(g) ? o.maxY : Math.max(g, o.maxY);
		}
		return g;
	}

	/** How far inside the place his middle may come down and still stand on the landing. */
	private static double margin(AABB land, double x, double z) {
		return Math.min(Math.min(x - (land.minX - 0.3), land.maxX + 0.3 - x), Math.min(z - (land.minZ - 0.3), land.maxZ + 0.3 - z));
	}

	/**
	 * The game's movement, tick by tick: the jump's first tick (when {@code takeOff}), then the facing direction a1 for
	 * k ticks and a2 after; the height moved first, then the larger of the two across, as the game does. NaN when he
	 * hits something or comes down beside the landing; else the least of the clearance on the way and the room on the
	 * landing. {@code next} gets where he is after the first tick.
	 */
	static double sim(List<AABB> obs, Frame fr, double x, double y, double z, double vx, double vy, double vz, boolean takeOff,
			double first, double a1, int k, double a2, AABB land, double top, double[] next) {
		double c = 1.0;
		int air = 0;
		for (int tick = 1; tick <= 40; tick++) {
			if (takeOff && tick == 1) {
				double[] d = fr.dir(first);
				vx += TAKE_OFF * d[0];
				vz += TAKE_OFF * d[1];
				vy = 0.42;
			} else {
				double[] d = fr.dir(air < k ? a1 : a2);
				air++;
				vx += ACC * d[0];
				vz += ACC * d[1];
			}
			double ny = y + vy;
			boolean landed = false;
			if (clear(obs, x, ny, z) < 0) {
				if (vy >= 0) return Double.NaN;   // his head against something
				double g = ground(obs, x, ny, z);
				if (Double.isNaN(g) || Math.abs(g - top) > 1e-3 || x + 0.3 <= land.minX || x - 0.3 >= land.maxX
						|| z + 0.3 <= land.minZ || z - 0.3 >= land.maxZ) return Double.NaN;
				ny = top;
				landed = true;
			}
			y = ny;
			double h;
			if (Math.abs(vx) >= Math.abs(vz)) {
				x += vx; if ((h = clear(obs, x, y, z)) < 0) return Double.NaN; c = Math.min(c, h);
				z += vz; if ((h = clear(obs, x, y, z)) < 0) return Double.NaN; c = Math.min(c, h);
			} else {
				z += vz; if ((h = clear(obs, x, y, z)) < 0) return Double.NaN; c = Math.min(c, h);
				x += vx; if ((h = clear(obs, x, y, z)) < 0) return Double.NaN; c = Math.min(c, h);
			}
			if (tick == 1 && next != null) { next[0] = x; next[1] = z; }
			double f = takeOff && tick == 1 ? 0.546 : 0.91;
			vx *= f;
			vz *= f;
			if (landed) return Math.min(c, margin(land, x, z));
			vy = (vy - 0.08) * 0.98;
			if (y < top - 3) return Double.NaN;
		}
		return Double.NaN;
	}

	/** The way that leaves most over, of all those tried; null when none comes down on the landing. */
	static Plan best(List<AABB> obs, Frame fr, double x, double y, double z, double vx, double vy, double vz, double[] firsts,
			double[] a1s, int[] ks, double[] a2s, AABB land, double top) {
		Plan best = null;
		double[] next = new double[2];
		double[] fs = firsts == null ? new double[] { Double.NaN } : firsts;
		for (double first : fs) for (double a1 : a1s) for (int k : ks) for (double a2 : a2s) {
			double s = sim(obs, fr, x, y, z, vx, vy, vz, firsts != null, first, a1, k, a2, land, top, next);
			if (Double.isNaN(s) || (best != null && s <= best.score)) continue;
			best = new Plan(first, a1, k, a2, s, next[0], next[1]);
		}
		return best;
	}

	private static final Map<String, Long> known = new HashMap<>();
	private static final Map<String, Boolean> knownWay = new HashMap<>();

	/**
	 * For the planner: can he get round from cell a (standing on {@code from}) into cell b (landing on {@code land})
	 * past this side, from a standstill at the corner? A quick look, kept half a minute.
	 */
	static boolean possible(Level level, BlockPos a, BlockPos b, int side, AABB from, AABB land) {
		String key = a.asLong() + ":" + b.asLong() + ":" + side;
		long now = System.currentTimeMillis();
		Long at = known.get(key);
		if (at != null && now - at < 30_000) return knownWay.get(key);
		if (known.size() > 500) { known.clear(); knownWay.clear(); }
		Frame fr = new Frame(from, a, b, side);
		List<AABB> obs = obstacles(level, a, b, from.maxY);
		double x = fr.x(START_U, START_W), z = fr.z(START_U, START_W);
		boolean ok = clear(obs, x, from.maxY, z) > 0;
		if (ok) {
			Plan p = best(obs, fr, x, from.maxY, z, 0, 0, 0, PLAN_FIRSTS, PLAN_A1, PLAN_KS, PLAN_A2, land, land.maxY);
			ok = p != null && p.score >= PLAN_SPARE;
		}
		known.put(key, now);
		knownWay.put(key, ok);
		return ok;
	}
}
