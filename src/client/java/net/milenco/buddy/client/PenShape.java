package net.milenco.buddy.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Whether the place where a mob stands is the kill area of a mob farm (a pen), and where around it a player stands to
 * hit what comes in (a hole of 1x1, 2x2 or 3x3, for instance), so that the buddy can walk around it and farm it. Pure:
 * it sees the world only through {@link World}, as boxes and a few flags, so that {@code PenShapeTest} can hold it
 * against pits drawn in characters.
 * <ul>
 * <li>{@link #floor}: what the mob stands on; {@link #inShaft} for one without a floor, whether it drops down a shaft.</li>
 * <li>{@link #flood}: the columns a mob of that size can walk to from there, never more than 3 by 3 and nine. A step up
 * of more than 0.6 is a wall to it, a drop of more than 1.2 beside it a hole, and either way past the limits the place
 * is open ground, no pen.</li>
 * <li>{@link #evidence}: a closed space alone is no farm (a cave nook with one zombie is one too, the lesson of
 * {@code PitShape} in 0.4.44): it takes two points of a collector under the floor, a weakened mob, a shaft above it, a
 * crowd.</li>
 * <li>{@link Search}: the cells around it to stand in, with what each reaches through what a crosshair stops at, and
 * whether a mob in there can hit back.</li>
 * <li>{@link #pit} and {@link #pitFoe}: for the fight, whether a mob stands where the way to it is a fall the buddy does not
 * climb back out of, or in a farm's cell.</li>
 * </ul>
 */
final class PenShape {
	private PenShape() {}

	/**
	 * Flags of a cell. OPENS: a door, gate or trapdoor (never a place to stand); OPENABLE: of those, what the walker
	 * opens (Follow.openable: a door a hand opens, a fence gate; no iron door, no trapdoor); FLOWING: the fluid there is
	 * no source, it runs.
	 */
	static final int FLUID = 1, WATER = 2, COLLECTOR = 4, OPENS = 8, HAZARD = 16, OPENABLE = 32, FLOWING = 64;

	/** What a hit may be from the eye: 0.1 under the player's 3.0, as Grinder holds it. */
	static final double REACH = 2.9;
	/** A mob steps up this far without a jump; more is a wall to it. */
	static final double STEP = 0.6;
	/** A start column's floor this far from the mob's own counts as where it stands. */
	static final double FLOOR_TOL = 0.6;
	/** A drop beside the mobs deeper than this is a hole: open ground, no pen. */
	static final double HOLE_DROP = 1.2;
	/**
	 * A swimmer at the top of water climbs onto a block whose top is this much higher at most (LivingEntity
	 * .jumpOutOfFluid lifts a swimmer once the box raised 0.6 holds no water).
	 */
	static final double SWIM_OUT = 0.5;
	/** How far down the floor of a falling mob and of a column is looked for. */
	static final double DROP_SCAN = 4;
	static final int MAX_SIDE = 3, MAX_COLS = 9;
	/** A shaft is this many walled levels, starting within SHAFT_GAP of the mobs' heads (the band players stand in). */
	static final int SHAFT_RUN = 5, SHAFT_GAP = 6, SHAFT_SCAN = 40;
	/** A player: eye and height standing and crouched, half the width. */
	static final double STAND_EYE = 1.62, STAND_H = 1.8, CROUCH_EYE = 1.27, CROUCH_H = 1.5, HALF = 0.3;
	/** How far a mob's melee reaches beyond its box: Mob.getAttackBoundingBox's sqrt(2.04) - 0.6, checked in 26.3. */
	static final double MELEE = Math.sqrt(2.04) - 0.6;
	/** A creeper starts to swell within three blocks. */
	static final double CREEPER2 = 9;
	/** A mob sees this far for the danger test. */
	static final double SIGHT = 16;
	/** The hits of the pen never come faster than Grinder.MAX_CPS, six a second. */
	static final double MIN_GAP = 1000.0 / 6.0;
	/** The strength a blow of the pen waits for: a full blow (and so one that sweeps, with a sword, from 0.9). */
	static final double STRONG = 0.95;

	/** The world as boxes in world coordinates. */
	interface World {
		/** What blocks movement in this cell, empty for air. */
		List<AABB> collision(int x, int y, int z);
		/** What a crosshair stops at in this cell (lower than the collision for a fence or wall). */
		List<AABB> outline(int x, int y, int z);
		/**
		 * {@link #FLUID}, {@link #WATER}, {@link #FLOWING}, {@link #COLLECTOR}, {@link #OPENS}, {@link #OPENABLE},
		 * {@link #HAZARD}.
		 */
		int flags(int x, int y, int z);
		/** The feet height of a player standing in this cell, NaN when a player cannot stand there. */
		double stand(int x, int y, int z);
		boolean loaded(int x, int z);
	}

	/** The mob that was hit: its size (a baby counts as grown), its box, and whether it stood. */
	record Mob(double w, double h, double eye, AABB box, boolean onGround) {
		/** A mob of this size standing in the middle of a column on that floor (the re-check, a probe). */
		static Mob at(double w, double h, double eye, int x, int z, double floor) {
			return new Mob(w, h, eye, new AABB(x + 0.5 - w / 2, floor, z + 0.5 - w / 2, x + 0.5 + w / 2, floor + h, z + 0.5 + w / 2), true);
		}
	}

	/**
	 * The columns of the pen with each one's floor, the mob's floor F, the box round them, and why it is no pen (null:
	 * a closed pen).
	 */
	record Flood(Set<Long> cols, Map<Long, Double> floorOf, double floor, int minX, int minZ, int maxX, int maxZ, String refusal) {
		boolean closed() { return refusal == null; }

		boolean has(int x, int z) { return cols.contains(key(x, z)); }

		int width() { return maxX - minX + 1; }

		int depth() { return maxZ - minZ + 1; }

		/** "2x2". */
		String size() { return cols.isEmpty() ? "" : width() + "x" + depth(); }

		/** The middle of the box round the columns, at this height. */
		Vec3 centre(double y) { return new Vec3((minX + maxX + 1) / 2.0, y, (minZ + maxZ + 1) / 2.0); }

		/** The columns in a fixed order: a spot's reach is a bit per column of this list. */
		List<Long> list() { return new ArrayList<>(cols); }
	}

	/** Shaft, collector and the points that make a closed space a mob farm. */
	record Evidence(boolean shaft, int shaftTop, double top, boolean collector, int points, List<String> why) {
		boolean enough() { return points >= 2; }
	}

	/**
	 * A cell to stand in: its feet height, where exactly (pushed toward the pen), crouched, on an edge, its side (0 north,
	 * 1 east, 2 south, 3 west), the columns it reaches (a bit per column of {@link Flood#list}), how many sample points it
	 * reaches, whether a mob in there sees the buddy or a creeper would swell, its angle round the pen, and whether its rays
	 * pass over a fence or wall.
	 */
	record Spot(BlockPos cell, double feet, Vec3 at, boolean crouch, boolean edge, int side, long reach, int samples,
			boolean seen, boolean creeper, double angle, boolean overFence) {
		int columns() { return Long.bitCount(reach); }
	}

	record Result(Flood flood, int shaftTop, double top, boolean shaft, boolean collector, int points, List<String> evidence,
			String kind, List<Spot> spots, Spot[] ring) {}

	// ---------- keys ----------

	static long key(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }

	static int kx(long k) { return (int) (k >> 32); }

	static int kz(long k) { return (int) k; }

	private static int fl(double v) { return (int) Math.floor(v); }

	// ---------- the floor ----------

	/**
	 * F, what the mob stands on: its feet when it stands, else the highest collision top in its footprint within
	 * {@link #DROP_SCAN} below it (a mob that drops down a shaft). NaN when there is none.
	 */
	static double floor(World w, Mob m) {
		AABB b = m.box();
		if (m.onGround()) return b.minY;
		AABB f = b.deflate(0.05);
		double best = Double.NaN;
		for (int x = fl(f.minX); x <= fl(f.maxX); x++) for (int z = fl(f.minZ); z <= fl(f.maxZ); z++)
			for (int y = fl(b.minY - DROP_SCAN); y <= fl(b.minY); y++)
				for (AABB c : w.collision(x, y, z)) {
					if (c.maxX <= f.minX || c.minX >= f.maxX || c.maxZ <= f.minZ || c.minZ >= f.maxZ) continue;
					if (c.maxY > b.minY + 1e-6 || c.maxY < b.minY - DROP_SCAN) continue;
					if (Double.isNaN(best) || c.maxY > best) best = c.maxY;
				}
		return best;
	}

	/**
	 * A mob without a floor that drops down a shaft: at one of the levels of its box (its feet still in the open band
	 * under the walls, or its head already over them), the cells it is in and those it reaches from them through cells
	 * without collision, at most 3 by 3 and nine, closed all round by walls of full height (glass, a fence, a wall).
	 * Open water, a lava lake, the air beside a cliff run on past the limits; a cell that is partly blocked or not loaded
	 * is no wall of a shaft either. What is not in a shaft is open ground to the gesture (Pen).
	 */
	static boolean inShaft(World w, Mob m) {
		AABB b = m.box(), foot = b.deflate(0.05);
		for (int y = fl(b.minY); y <= fl(b.maxY - 1e-6); y++) if (shaftLevel(w, foot, y)) return true;
		return false;
	}

	private static boolean shaftLevel(World w, AABB foot, int y) {
		Set<Long> cells = new HashSet<>();
		ArrayDeque<Long> queue = new ArrayDeque<>();
		int[] box = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };
		for (int x = fl(foot.minX); x <= fl(foot.maxX); x++) for (int z = fl(foot.minZ); z <= fl(foot.maxZ); z++) {
			if (!w.loaded(x, z) || !w.collision(x, y, z).isEmpty()) return false;
			if (cells.add(key(x, z))) { queue.add(key(x, z)); grow(box, x, z); }
		}
		int[][] dirs = { { 0, -1 }, { 1, 0 }, { 0, 1 }, { -1, 0 } };
		while (!queue.isEmpty()) {
			long c = queue.poll();
			for (int[] d : dirs) {
				int nx = kx(c) + d[0], nz = kz(c) + d[1];
				long k = key(nx, nz);
				if (cells.contains(k)) continue;
				if (!w.loaded(nx, nz)) return false;
				if (!w.collision(nx, y, nz).isEmpty()) {
					if (!fullHeight(w, nx, y, nz)) return false;
					continue;
				}
				cells.add(k);
				queue.add(k);
				grow(box, nx, nz);
				if (box[2] - box[0] + 1 > MAX_SIDE || box[3] - box[1] + 1 > MAX_SIDE || cells.size() > MAX_COLS) return false;
			}
		}
		return true;
	}

	private static void grow(int[] box, int x, int z) {
		box[0] = Math.min(box[0], x); box[1] = Math.min(box[1], z);
		box[2] = Math.max(box[2], x); box[3] = Math.max(box[3], z);
	}

	// ---------- the flood ----------

	private static final double HOLE = Double.NEGATIVE_INFINITY, BLOCKED = Double.POSITIVE_INFINITY;

	/**
	 * Into column (x, z) from a floor f, for a mob h high: its floor, or {@link #HOLE} or {@link #BLOCKED}. Every
	 * collision box of the column that overlaps its square in any part counts (conservative), merged into heights.
	 */
	static double enter(World w, int x, int z, double f, double h) {
		List<double[]> iv = new ArrayList<>();
		for (int y = fl(f - DROP_SCAN); y <= fl(f + h + 1); y++)
			for (AABB b : w.collision(x, y, z))
				if (b.maxX > x && b.minX < x + 1 && b.maxZ > z && b.minZ < z + 1 && b.maxY > b.minY) iv.add(new double[] { b.minY, b.maxY });
		List<double[]> m = merge(iv);
		double s = f + STEP, t = Double.NaN;
		for (double[] a : m) if (a[1] <= s + 1e-6) t = Double.isNaN(t) ? a[1] : Math.max(t, a[1]);
		// whether the column stops the mob comes first: a wall that rises past its step into its body is a wall, also
		// where a room or a gap lies under it (a pit's walls over the collection room, whose floor would read as a drop)
		double lo = Double.isNaN(t) ? f : Math.max(t, f), hi = lo + h;
		for (double[] a : m) if ((Double.isNaN(t) || a[1] > t + 1e-6) && a[0] < hi - 1e-6) return BLOCKED;
		// nothing in its way and nothing to stand on within the drop: a hole beside it
		if (Double.isNaN(t) || t < f - HOLE_DROP) return HOLE;
		return t;
	}

	private static List<double[]> merge(List<double[]> iv) {
		iv.sort(Comparator.comparingDouble(a -> a[0]));
		List<double[]> out = new ArrayList<>();
		for (double[] a : iv) {
			double[] last = out.isEmpty() ? null : out.get(out.size() - 1);
			if (last != null && a[0] <= last[1] + 1e-6) last[1] = Math.max(last[1], a[1]);
			else out.add(new double[] { a[0], a[1] });
		}
		return out;
	}

	/**
	 * The pen: the columns a mob of this size walks to from where it stands, four ways, at most 3 by 3 and nine. An L
	 * is fine. A hole beside the mobs, a column not loaded or past either limit and it is no pen.
	 */
	static Flood flood(World w, Mob m, double F) {
		double h = m.h();
		AABB b = m.box().deflate(0.05);
		Set<Long> cols = new LinkedHashSet<>();
		Map<Long, Double> floorOf = new HashMap<>();
		ArrayDeque<Long> queue = new ArrayDeque<>();
		int[] box = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };
		for (int x = fl(b.minX); x <= fl(b.maxX); x++) for (int z = fl(b.minZ); z <= fl(b.maxZ); z++) {
			if (!w.loaded(x, z)) return refused(cols, floorOf, F, box, "not loaded");
			double t = enter(w, x, z, F, h);
			if (t == HOLE) return refused(cols, floorOf, F, box, "a hole beside the mobs");
			if (t == BLOCKED || Math.abs(t - F) > FLOOR_TOL) continue;
			add(cols, floorOf, queue, box, x, z, t);
		}
		if (cols.isEmpty()) return refused(cols, floorOf, F, box, "no floor under the mob");
		int[][] dirs = { { 0, -1 }, { 1, 0 }, { 0, 1 }, { -1, 0 } };
		while (!queue.isEmpty()) {
			long c = queue.poll();
			double fc = floorOf.get(c);
			for (int[] d : dirs) {
				int nx = kx(c) + d[0], nz = kz(c) + d[1];
				if (cols.contains(key(nx, nz))) continue;
				if (!w.loaded(nx, nz)) return refused(cols, floorOf, F, box, "not loaded");
				double t = enter(w, nx, nz, fc, h);
				if (t == HOLE) return refused(cols, floorOf, F, box, "a hole beside the mobs");
				if (t == BLOCKED) continue;
				add(cols, floorOf, queue, box, nx, nz, t);
				// stop as soon as a limit is passed: a field or a room is never walked through
				if (box[2] - box[0] + 1 > MAX_SIDE || box[3] - box[1] + 1 > MAX_SIDE) return refused(cols, floorOf, F, box, "larger than 3x3");
				if (cols.size() > MAX_COLS) return refused(cols, floorOf, F, box, "too big");
			}
		}
		return new Flood(cols, floorOf, F, box[0], box[1], box[2], box[3], null);
	}

	private static void add(Set<Long> cols, Map<Long, Double> floorOf, ArrayDeque<Long> queue, int[] box, int x, int z, double t) {
		long k = key(x, z);
		cols.add(k);
		floorOf.put(k, t);
		queue.add(k);
		box[0] = Math.min(box[0], x); box[1] = Math.min(box[1], z);
		box[2] = Math.max(box[2], x); box[3] = Math.max(box[3], z);
	}

	private static Flood refused(Set<Long> cols, Map<Long, Double> floorOf, double F, int[] box, String why) {
		return cols.isEmpty() ? new Flood(cols, floorOf, F, 0, 0, -1, -1, why) : new Flood(cols, floorOf, F, box[0], box[1], box[2], box[3], why);
	}

	/**
	 * The closed space a mob stands in, as the fight sees it (Pen.enclosed): its columns, the top of its shaft, a farm
	 * by its blocks, how a player who lands in it gets out again unaided ({@link #climbOut}: open ground, or the
	 * places a closed climb stands on), and a door or gate to open in its wall with ground beyond it
	 * ({@link #throughDoor}), which lets the player out at any height.
	 */
	record Pit(Flood flood, double top, boolean farm, Climb climb, boolean door) {
		/**
		 * The highest floor outside its columns the climb gets to: positive infinity for open ground, negative infinity
		 * for none. Only open ground counts as a way out by itself: how high a closed climb tops out says nothing of
		 * where it ends ({@link Climb#backUp}).
		 */
		double up() { return climb.up(); }
	}

	/**
	 * Where a player who lands in a pit gets to unaided ({@link #climbOut}): {@code up}, the highest floor outside
	 * its columns (positive infinity: open ground; negative infinity: nowhere), and for a climb that stays closed the
	 * floors it stands on, per column ({@link #key}); empty for open ground. Not measured against anyone's ground: the
	 * shape is kept a second per mob (Pen.enclosedWhy), {@link #backUp} is asked with the player's feet every time.
	 */
	record Climb(double up, Map<Long, List<Double>> reached) {
		/**
		 * Whether the climb leads back to {@code feet}: open ground, or a column under the box there (those it overlaps by
		 * more than a hair: pressed against a block, the box only touches the next one) where the climb stood on a floor at
		 * {@code ground} or up to a step above it. A column's floor is the top of its merged boxes, so on a stair's low half
		 * the feet are half a block under what the climb records there, in a hopper's bowl 5/16 under it; two floors the
		 * climb records in one column lie more than STAND_H apart, so the window takes no other. A cauldron (3/4 under its
		 * rim) or anything else deeper than a step is no match: the conservative side, and the mob is left be. Until the
		 * review of the fourth round (22 Oct 2026) {@code up} was held against the ground instead: a climb that topped out at
		 * it or over it counted as one back, wherever it ended.
		 */
		boolean backUp(Vec3 feet, double ground) {
			if (up == Double.POSITIVE_INFINITY) return true;
			for (int x = fl(feet.x - HALF + 1e-6); x <= fl(feet.x + HALF - 1e-6); x++)
				for (int z = fl(feet.z - HALF + 1e-6); z <= fl(feet.z + HALF - 1e-6); z++) {
					List<Double> floors = reached.get(key(x, z));
					if (floors != null) for (double v : floors) if (v > ground - 1e-3 && v < ground + STEP + 1e-6) return true;
				}
			return false;
		}
	}

	/**
	 * The closed space a mob stands in, or null for open ground (no floor, a hole beside it, past 3 by 3). A farm here by
	 * its blocks alone: a collector in or under its floor, or a shaft over it, either by itself, as Pen.fromEntry takes a
	 * farm after a restart. Neither how hurt the mob is (in a fight that is the fight's own doing) nor water at its feet
	 * (a hole in a field, waded into and out of) counts. Its ways out start from each column's own floor (a
	 * hopper's rim, not its bowl: a step up onto that).
	 */
	static Pit pit(World w, Mob m) {
		double F = floor(w, m);
		if (Double.isNaN(F)) return null;
		Flood f = flood(w, m, F);
		if (!f.closed()) return null;
		Evidence ev = evidence(w, f, m, false, 1, false, false);
		boolean door = false;
		int[][] dirs = { { 0, -1 }, { 1, 0 }, { 0, 1 }, { -1, 0 } };
		for (long c : f.cols()) for (int[] d : dirs) {
			int nx = kx(c) + d[0], nz = kz(c) + d[1];
			if (!f.has(nx, nz) && throughDoor(w, f, nx, nz, d[0], d[1], f.floorOf().get(c))) door = true;
		}
		return new Pit(f, ev.top(), ev.collector() || ev.shaft(), climbOut(w, f), door);
	}

	/**
	 * Past this many cells beside the pit's box the climb out of it stands on open ground, as the flood takes what runs
	 * past its limits.
	 */
	static final int CLIMB_RANGE = 3;
	/** The most places the climb stands on; more is open ground too. */
	static final int CLIMB_MAX = 128;
	/** The deepest drop the walker takes (Follow's planner). */
	static final double DROP = 3.2;

	/** A place to stand: a column and the height of the feet on its floor. */
	private record Stand(int x, int z, double feet) {}

	/**
	 * Where outside the pit's columns a player who lands in it gets to unaided, and the highest floor of it: from
	 * each column's own floor over the cells beside it (four ways) onto a floor less than a jump up (HOLE_DROP), or
	 * from water standing over that floor onto one within SWIM_OUT of its top, or down a drop of DROP at most, as often
	 * as it takes ({@link #floorsBeside}). Open ground (up positive infinity, no places kept) once a place lies more
	 * than CLIMB_RANGE beside the pit's box or the climb has stood on CLIMB_MAX places; up negative infinity when it
	 * gets nowhere outside the columns. Not measured against anyone's ground: the shape is kept a second per mob
	 * (Pen.enclosedWhy), the ground every tick ({@link Climb#backUp}). Until the third review of 22 Oct 2026 only the
	 * tops of the blocks right beside the columns counted, one jump each against the ground stood on: a dip one deep seen from
	 * two up and a crater with two steps were pits, and the fight left their mobs be. A fixed distance from the pit is
	 * no end of the climb: out of a hole one deep in the floor of a pit five by five and three deep it walks two cells
	 * over that floor, and that pit is the trap.
	 */
	static Climb climbOut(World w, Flood f) {
		Map<Long, List<Double>> seen = new HashMap<>();
		ArrayDeque<Stand> queue = new ArrayDeque<>();
		for (long c : f.cols()) queue.add(new Stand(kx(c), kz(c), f.floorOf().get(c)));
		double up = Double.NEGATIVE_INFINITY;
		int places = 0;
		int[][] dirs = { { 0, -1 }, { 1, 0 }, { 0, 1 }, { -1, 0 } };
		while (!queue.isEmpty()) {
			Stand s = queue.poll();
			double water = waterTop(w, s.x(), s.z(), s.feet());
			for (int[] d : dirs) {
				int nx = s.x() + d[0], nz = s.z() + d[1];
				// the columns themselves are where the player lands; a column not loaded is no way out
				if (f.has(nx, nz) || !w.loaded(nx, nz)) continue;
				for (double t : floorsBeside(w, nx, nz, s.feet(), water, ceiling(w, s.x(), s.z(), s.feet()))) {
					List<Double> was = seen.computeIfAbsent(key(nx, nz), k -> new ArrayList<>());
					boolean known = false;
					for (double v : was) if (Math.abs(v - t) < 1e-6) { known = true; break; }
					if (known) continue;
					if (Math.max(f.minX() - nx, nx - f.maxX()) > CLIMB_RANGE || Math.max(f.minZ() - nz, nz - f.maxZ()) > CLIMB_RANGE || ++places > CLIMB_MAX)
						return new Climb(Double.POSITIVE_INFINITY, Map.of());
					was.add(t);
					up = Math.max(up, t);
					queue.add(new Stand(nx, nz, t));
				}
			}
		}
		return new Climb(up, seen);
	}

	/**
	 * The bottom of the lowest box over a floor at {@code h} in this column, within a jump and a player's height, or positive
	 * infinity for none: what the head meets on a jump from there.
	 */
	static double ceiling(World w, int x, int z, double h) {
		double c = Double.POSITIVE_INFINITY;
		for (int y = fl(h); y <= fl(h + HOLE_DROP + STAND_H) + 1; y++)
			for (AABB b : w.collision(x, y, z))
				if (b.maxX > x && b.minX < x + 1 && b.maxZ > z && b.minZ < z + 1 && b.maxY > b.minY && b.minY > h - 1e-6) c = Math.min(c, b.minY);
		return c;
	}

	/**
	 * The top of the water standing over a floor at {@code feet} in this column: of the run of water cells from the
	 * cell above the floor's block up, the cell the feet are in (a whole block, mud, soul sand or farmland under them), NaN
	 * for none. Until the third review of 22 Oct 2026 the run began in the floor block's own cell, which for a floor that
	 * is no whole block holds no water: water two deep over mud or soul sand was nothing to swim out of.
	 */
	static double waterTop(World w, int x, int z, double feet) {
		int y0 = fl(feet - 1e-6) + 1;
		double top = Double.NaN;
		for (int y = y0; y <= y0 + SHAFT_SCAN && (w.flags(x, y, z) & WATER) != 0; y++) top = y + 1;
		return top;
	}

	/**
	 * The floors in column (x, z) a player standing on {@code h} beside it gets onto: a collision top less than a jump
	 * up (HOLE_DROP), within SWIM_OUT of the top of the water stood in ({@code water}, NaN: none), or down as far
	 * as DROP, with STAND_H free over it and over h, every box that overlaps the column's square in any part counting
	 * (as the flood takes it). Not a floor, as Follow.floorY refuses them: one over lava or fire or beside lava, nor a
	 * block that holds water or lava under the cell the feet are in, nor a low block in that cell (a slab, a carpet, a
	 * lily pad, a lantern, a candle on a fence) that holds a fluid itself or stands on one or on a block that burns, nor
	 * the top of something that hangs low in its cell (a chain lying flat: floorY stands in neither that cell nor the one
	 * over it). A top with no room over it is no floor, so stacked blocks that leave a gap (mud, soul sand: 1/8) are one wall to the top of the stack: until the
	 * third review of 22 Oct 2026 such a wall's first block decided, it had no room over it, and a wall of mud was none
	 * to climb out over. Until the review of the fourth round (22 Oct 2026) neither a low block's own cell nor the one
	 * under it was looked at, and a fence's or wall's top was taken for a low block in the cell over it: a corridor of
	 * carpets on waterlogged mangrove roots, or of lily pads on water, was a way out of a pit, and the walker refuses
	 * every cell of it.
	 */
	static List<Double> floorsBeside(World w, int x, int z, double h, double water) { return floorsBeside(w, x, z, h, water, Double.POSITIVE_INFINITY); }

	/**
	 * The same, with {@code ceil} the bottom of the lowest box over the player in the column stood in ({@link #ceiling}): a
	 * jump wants room over the head there up to the floor jumped onto and STAND_H and a little more, as the walker's
	 * Follow.roomToJump does. Without it a step under a low overhang was a way out of a crater that cannot be climbed (the
	 * check of the third review of 22 Oct 2026).
	 */
	static List<Double> floorsBeside(World w, int x, int z, double h, double water, double ceil) {
		double reach = Double.isNaN(water) ? h + HOLE_DROP : Math.max(h + HOLE_DROP, water + SWIM_OUT);
		List<double[]> iv = new ArrayList<>();
		for (int y = fl(h - DROP) - 1; y <= fl(reach + STAND_H) + 1; y++)
			for (AABB b : w.collision(x, y, z))
				if (b.maxX > x && b.minX < x + 1 && b.maxZ > z && b.minZ < z + 1 && b.maxY > b.minY) iv.add(new double[] { b.minY, b.maxY });
		List<double[]> m = merge(iv);
		List<Double> out = new ArrayList<>();
		for (int i = 0; i < m.size(); i++) {
			double t = m.get(i)[1];
			boolean jump = t - h < HOLE_DROP - 1e-6, swim = !Double.isNaN(water) && t - water <= SWIM_OUT + 1e-6;
			if (t < h - DROP - 1e-6 || !(jump || swim)) continue;
			double next = i + 1 < m.size() ? m.get(i + 1)[0] : Double.POSITIVE_INFINITY;
			if (next - Math.max(t, h) < STAND_H - 1e-6) continue;
			// a jump (more than a step, not out of water) wants room over the head where it starts
			if (t - h > STEP + 1e-6 && !swim && ceil - t < STAND_H + 0.05 - 1e-6) continue;
			// the cell the feet are in: a low block's own (a slab, a carpet, a lily pad: Follow.floorY's own
			// collision), else the one over the block stood on, a fence or wall too (its 1.5 reaches into that cell)
			double frac = t - Math.floor(t);
			boolean low = frac > 1e-6 && frac <= STEP + 1e-6;
			int feet = low ? fl(t) : fl(t - 1e-6) + 1;
			// something that hangs low in that cell (a chain lying flat): floorY stands in neither it nor the cell over it
			if (low && hangs(w, x, feet, z)) continue;
			boolean own = low && ownFloor(w, x, feet, z);
			int under = own ? feet : feet - 1;
			if (burns(w, x, feet, z) || burns(w, x, under, z) || besideLava(w, x, feet, z)) continue;
			// the block stood on holds no fluid (a waterlogged block, fence or wall); water over it, in the feet's cell,
			// is water to swim in
			if (under != feet && (w.flags(x, under, z) & FLUID) != 0) continue;
			// nor a low block in the feet's cell, nor what it lies on (floorY's Grinder.hazard on the cell and the one under
			// it): a waterlogged slab, a lily pad on water, a carpet on waterlogged roots or on magma, a slab over lava
			if (own && ((w.flags(x, feet, z) & FLUID) != 0 || burns(w, x, feet - 1, z) || (w.flags(x, feet - 1, z) & FLUID) != 0)) continue;
			out.add(t);
		}
		return out;
	}

	/**
	 * Whether the cell y has a low block of its own, what Follow.floorY takes as the cell's own collision: its boxes taken
	 * together start at the cell's bottom and end within a step of it (a slab, a carpet, a lily pad, a lantern, a candle),
	 * whatever reaches higher in the column, as a fence's or wall's 1.5 under a candle does. Not the 1.5 alone, which
	 * reaches into the cell from the block under it. Until the check of the review of the fourth round (22 Oct 2026) one
	 * box had to start at the bottom and reach the column's top: a lantern is two (its body, its cap on it), and a
	 * waterlogged one was a floor, as was a waterlogged candle on a fence, where the walker refuses both cells.
	 */
	private static boolean ownFloor(World w, int x, int y, int z) {
		double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
		for (AABB b : w.collision(x, y, z)) { lo = Math.min(lo, b.minY); hi = Math.max(hi, b.maxY); }
		return lo <= y + 0.01 && hi <= y + STEP + 1e-6;
	}

	/**
	 * Whether the cell y holds something that hangs low in it: its boxes taken together start over the cell's bottom and
	 * end within a step of it (a chain lying flat, 6.5/16 to 9.5/16). Follow.floorY stands in neither that cell nor the
	 * one over it ("something hanging", a low block below); until the review of the fourth round its top was taken for a
	 * fence's reaching up from the block under it, and a bridge of chains was a way out of a pit.
	 */
	private static boolean hangs(World w, int x, int y, int z) {
		double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
		for (AABB b : w.collision(x, y, z)) { lo = Math.min(lo, b.minY); hi = Math.max(hi, b.maxY); }
		return lo != Double.POSITIVE_INFINITY && lo > y + 0.01 && hi <= y + STEP + 1e-6;
	}

	/** Lava, or fire, magma, a cactus and the like (HAZARD without a fluid: water is HAZARD to Grinder too). */
	private static boolean burns(World w, int x, int y, int z) {
		int g = w.flags(x, y, z);
		return ((g & FLUID) != 0 && (g & WATER) == 0) || ((g & HAZARD) != 0 && (g & FLUID) == 0);
	}

	private static boolean besideLava(World w, int x, int y, int z) {
		int[][] dirs = { { 0, -1 }, { 1, 0 }, { 0, 1 }, { -1, 0 } };
		for (int[] d : dirs) {
			int g = w.flags(x + d[0], y, z + d[1]);
			if ((g & FLUID) != 0 && (g & WATER) == 0) return true;
		}
		return false;
	}

	/**
	 * A door that opens by hand or a fence gate in this cell of the pit's wall ({@link #OPENABLE}), with room for a player in
	 * the doorway (World.stand, which lets only those through, with a player's height free over it) less than a jump up or down
	 * from the floor fc beside it, and a place to stand beyond it ({@code dx, dz} on) less than a jump up or down from
	 * the doorway, no column of the pit. An iron door, a trapdoor, a gate under a block or a door to rock is a wall like
	 * any other: until the third review of 22 Oct 2026 every door, gate or trapdoor counted as a way out at any height, and the
	 * fight walked the buddy into such a pit.
	 */
	static boolean throughDoor(World w, Flood f, int nx, int nz, int dx, int dz, double fc) {
		int bx = nx + dx, bz = nz + dz;
		if (f.has(bx, bz) || !w.loaded(bx, bz)) return false;
		for (int y = fl(fc) - 1; y <= fl(fc) + 2; y++) {
			if ((w.flags(nx, y, nz) & OPENABLE) == 0) continue;
			double in = w.stand(nx, y, nz);
			if (Double.isNaN(in) || Math.abs(in - fc) >= HOLE_DROP - 1e-6) continue;
			for (int by = y - 1; by <= y + 1; by++) {
				double out = w.stand(bx, by, bz);
				if (!Double.isNaN(out) && Math.abs(out - in) < HOLE_DROP - 1e-6) return true;
			}
		}
		return false;
	}

	/**
	 * Why the fight does not go to a mob in this closed space (Follow.fight), or null: "in a pit" when its floor is
	 * HOLE_DROP or more below the buddy's ground, there is no door out of it, and the climb out of it does not lead back
	 * to where the buddy stands ({@link Climb#backUp}: open ground, or a place of the climb under the buddy's box at that
	 * ground; the way there is a fall with no climb back out), "in a pen" when it is a farm (a cell of it at the buddy's
	 * own level would be walked into). Never while the buddy stands in it. A dip one deep, a crater to climb out of in
	 * steps, seen from wherever, a water hole to swim out of, a room behind a door at the same level with no shaft over
	 * it: those are stepped in and out of, and fought in as ever. Asked every tick with the ground stood on: a nook two
	 * deep seen from lower ground is none, for every way into it goes over its rim, and on the rim it is one, so out of
	 * reach there the fight is given up before any drop. Until the review of the fourth round (22 Oct 2026) the highest
	 * floor the climb got to, {@link Pit#up}, was held against that ground, and a climb that topped out at it or over it
	 * was a way back, wherever it ended: a stair up the inside of a slope to a closed ledge at the same height was one,
	 * and the fight walked the buddy into that pit (until the check of the third review a climb ending within a jump below
	 * ground was one too).
	 */
	static String pitFoe(Pit pit, Vec3 feet, double ground) {
		if (pit == null || in(pit.flood(), pit.top(), feet)) return null;
		if (pit.flood().floor() <= ground - HOLE_DROP + 1e-6 && !pit.door() && !pit.climb().backUp(feet, ground)) return "in a pit";
		return pit.farm() ? "in a pen" : null;
	}

	/**
	 * Feet in a pen column between F - 1 and the top of its shaft + 2: "you stand in it" for the followed player, "{me}
	 * stands in it" for the buddy ({me} is the buddy's name, filled in by Pen.refuse). A pen nobody may stand in or over: that is where the blows go, and a 1x1 mining shaft
	 * stood over is no farm.
	 */
	static String inside(Flood f, double top, Vec3 owner, Vec3 bot) {
		if (owner != null && in(f, top, owner)) return "you stand in it";
		if (bot != null && in(f, top, bot)) return "{me} stands in it";
		return null;
	}

	static boolean in(Flood f, double top, Vec3 v) {
		return f.has(fl(v.x), fl(v.z)) && v.y >= f.floor() - 1 && v.y <= top + 2;
	}

	// ---------- the evidence ----------

	/**
	 * Whether a closed space is a farm. A shaft: from the mobs' heads up, levels where every pen cell is open and every
	 * cell beside the pen is closed over its whole height (glass, fences and walls are), five or more in a row starting
	 * within six (the band the buddy stands in may be open). Points: a collector under the floor 2 (a hopper, chest or barrel
	 * in or under a floor cell, water there, a hopper minecart, or a stream at the mobs' feet that runs on out of the
	 * pen), a weakened mob 2, a shaft 1, a crowd of two or more 1; a farm at 2.
	 */
	static Evidence evidence(World w, Flood f, Mob m, boolean weakened, int crowd, boolean cart) {
		return evidence(w, f, m, weakened, crowd, cart, true);
	}

	/** The same; without {@code stream} water where the mobs' feet are is no collector (the fight's pit, {@link #pit}). */
	static Evidence evidence(World w, Flood f, Mob m, boolean weakened, int crowd, boolean cart, boolean stream) {
		int y0 = (int) Math.ceil(f.floor() + m.h() - 1e-9);
		int runStart = Integer.MIN_VALUE, shaftEnd = Integer.MIN_VALUE;
		for (int y = y0; y <= y0 + SHAFT_SCAN; y++) {
			if (walled(w, f, y)) {
				if (runStart == Integer.MIN_VALUE) runStart = y;
				if (y - runStart + 1 >= SHAFT_RUN && runStart - y0 <= SHAFT_GAP) shaftEnd = y;
			} else {
				if (shaftEnd != Integer.MIN_VALUE) break;
				runStart = Integer.MIN_VALUE;
				if (y - y0 >= SHAFT_GAP) break;   // a run starting later is too far up
			}
		}
		boolean shaft = shaftEnd != Integer.MIN_VALUE;
		double top = shaft ? shaftEnd + 1 : f.floor() + m.h() + 1;
		boolean collector = cart || collector(w, f, stream);
		List<String> why = new ArrayList<>();
		int points = 0;
		if (collector) { points += 2; why.add("collector"); }
		if (weakened) { points += 2; why.add("weakened"); }
		if (shaft) { points += 1; why.add("shaft"); }
		if (crowd >= 2) { points += 1; why.add("crowd"); }
		return new Evidence(shaft, shaft ? shaftEnd : Integer.MIN_VALUE, top, collector, points, why);
	}

	/** Every pen cell open at this level and every cell beside the pen closed over its whole height. */
	static boolean walled(World w, Flood f, int y) {
		for (long c : f.cols()) if (!w.collision(kx(c), y, kz(c)).isEmpty()) return false;
		int[][] dirs = { { 0, -1 }, { 1, 0 }, { 0, 1 }, { -1, 0 } };
		for (long c : f.cols()) for (int[] d : dirs) {
			int nx = kx(c) + d[0], nz = kz(c) + d[1];
			if (f.has(nx, nz)) continue;
			if (!fullHeight(w, nx, y, nz)) return false;
		}
		return true;
	}

	/** Collision covering the cell from its bottom to its top: its own boxes, and what a fence or wall below reaches up. */
	static boolean fullHeight(World w, int x, int y, int z) {
		List<double[]> iv = new ArrayList<>();
		for (int dy = -1; dy <= 0; dy++)
			for (AABB b : w.collision(x, y + dy, z)) {
				double lo = Math.max(b.minY, y), hi = Math.min(b.maxY, y + 1);
				if (hi > lo) iv.add(new double[] { lo, hi });
			}
		for (double[] a : merge(iv)) if (a[0] <= y + 1e-6 && a[1] >= y + 1 - 1e-6) return true;
		return false;
	}

	/**
	 * A collector in or under a floor cell of the pen (a hopper, a chest, a barrel, water), or water where the mobs'
	 * feet are that runs on out of the pen (a stream under or through its wall that carries the drops off). Water that
	 * stands in the pen alone carries nothing off: a base's pool or a field's water hole with a zombie in it is no farm,
	 * nor is one edged with waterlogged blocks or reaching under a deck or lily pads ({@link #runsOut}).
	 */
	static boolean collector(World w, Flood f) { return collector(w, f, true); }

	/** The same, water where the mobs' feet are only with {@code stream}. */
	static boolean collector(World w, Flood f, boolean stream) {
		for (long c : f.cols()) {
			int x = kx(c), z = kz(c), fy = fl(f.floorOf().get(c) - 1e-6);
			for (int y = fy - 1; y <= fy; y++) if ((w.flags(x, y, z) & (COLLECTOR | WATER)) != 0) return true;
			if (stream && (w.flags(x, fy + 1, z) & WATER) != 0 && runsOut(w, f, x, fy + 1, z)) return true;
		}
		return false;
	}

	/**
	 * Flowing water at this height in a cell beside this column that is no column of the pen: a stream under or
	 * through its wall (the flood holds every cell a mob walks to, so one with water beside the pen is past a wall it
	 * does not pass). Water pushes an item on only into a cell whose water is lower, flowing; a source there stands, and
	 * every waterlogged block holds one (in 26.3 SimpleWaterloggedBlock takes the source alone). Until the third review
	 * of 22 Oct 2026 any water counted: a still pool edged with waterlogged stairs or mangrove roots, one under a deck or
	 * lily pads, was a farm for the gesture.
	 */
	private static boolean runsOut(World w, Flood f, int x, int y, int z) {
		int[][] dirs = { { 0, -1 }, { 1, 0 }, { 0, 1 }, { -1, 0 } };
		for (int[] d : dirs) {
			int nx = x + d[0], nz = z + d[1];
			if (!f.has(nx, nz) && (w.flags(nx, y, nz) & (WATER | FLOWING)) == (WATER | FLOWING)) return true;
		}
		return false;
	}

	// ---------- line of sight ----------

	/** Nothing in the way from a to b: OUTLINE as the crosshair sees, else as a mob sees (collision). */
	static boolean clear(World w, Vec3 a, Vec3 b, boolean outline) { return clearInfo(w, a, b, outline) >= 0; }

	/**
	 * -1 blocked, 0 clear, 1 clear over the top of a fence or wall (a cell whose collision reaches higher than its
	 * outline). A walk over the cells of the segment (Amanatides-Woo), in each its own boxes only, as 26.3's Level.clip
	 * goes (BlockGetter.traverseBlocks, the shape of the cell it visits): what a fence or wall reaches up into the cell
	 * above it, 1.0 to 1.5, stops neither a crosshair nor a mob's sight (LivingEntity.hasLineOfSight, COLLIDER). Blocked
	 * when the nearest hit is closer than the whole way less 0.05, so a point on a block's face counts.
	 */
	static int clearInfo(World w, Vec3 a, Vec3 b, boolean outline) {
		double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1e-9) return 0;
		double limit = len - 0.05;
		int x = fl(a.x), y = fl(a.y), z = fl(a.z), ex = fl(b.x), ey = fl(b.y), ez = fl(b.z);
		int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0, sy = dy > 0 ? 1 : dy < 0 ? -1 : 0, sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
		double tx = sx == 0 ? Double.MAX_VALUE : (sx > 0 ? x + 1 - a.x : a.x - x) / Math.abs(dx);
		double ty = sy == 0 ? Double.MAX_VALUE : (sy > 0 ? y + 1 - a.y : a.y - y) / Math.abs(dy);
		double tz = sz == 0 ? Double.MAX_VALUE : (sz > 0 ? z + 1 - a.z : a.z - z) / Math.abs(dz);
		double ddx = sx == 0 ? Double.MAX_VALUE : 1 / Math.abs(dx), ddy = sy == 0 ? Double.MAX_VALUE : 1 / Math.abs(dy), ddz = sz == 0 ? Double.MAX_VALUE : 1 / Math.abs(dz);
		boolean over = false;
		double tIn = 0;
		for (int guard = 0; guard < 512; guard++) {
			double tOut = Math.min(1, Math.min(tx, Math.min(ty, tz)));
			for (AABB c : outline ? w.outline(x, y, z) : w.collision(x, y, z)) if (hits(c, a, b, limit)) return -1;
			if (outline) {
				// over a fence or wall: the block below reaches into this cell with its collision, its outline does not
				double up = Double.NEGATIVE_INFINITY, out = Double.NEGATIVE_INFINITY;
				for (AABB c : w.collision(x, y - 1, z)) up = Math.max(up, c.maxY);
				for (AABB c : w.outline(x, y - 1, z)) out = Math.max(out, c.maxY);
				if (up > y + 1e-6 && out <= y + 1e-6 && Math.min(a.y + dy * tIn, a.y + dy * tOut) < up) over = true;
			}
			if ((x == ex && y == ey && z == ez) || tOut >= 1) break;
			tIn = tOut;
			if (tx <= ty && tx <= tz) { x += sx; tx += ddx; }
			else if (ty <= tz) { y += sy; ty += ddy; }
			else { z += sz; tz += ddz; }
		}
		return over ? 1 : 0;
	}

	private static boolean hits(AABB c, Vec3 a, Vec3 b, double limit) {
		var hit = c.clip(a, b);
		return hit.isPresent() && hit.get().distanceTo(a) < limit;
	}

	// ---------- where to stand ----------

	private static final double[] XZ = { 0.5, 0.1, 0.9 }, YF = { 0.5, 0.3, 0.7, 0.1, 0.9 };
	private static final double[] PUSH = { 0, 0.15, 0.3 };

	/**
	 * The cells to stand in, time-boxed over ticks: every cell within three of the pen, from three down to as high as a
	 * crouched eye still reaches the top of a mob (three up at least), that a player stands in, not in or over a pen
	 * column, not a door or gate, with what it reaches from a standing and a crouched eye, pushed toward the pen by up to
	 * 0.3. A cell from which a mob in there hits the buddy is left out; on an edge, crouched. The best per side is the ring.
	 */
	static final class Search {
		private final World w;
		private final Flood f;
		private final Mob m;
		private final Evidence ev;
		private final List<Long> order;
		private final List<int[]> cells = new ArrayList<>();
		private final List<Spot> spots = new ArrayList<>();
		private final AABB pen;
		private final Vec3 mid;
		private int next;
		private long nanos;

		Search(World w, Flood f, Mob m, Evidence ev) {
			this.w = w;
			this.f = f;
			this.m = m;
			this.ev = ev;
			this.order = f.list();
			this.pen = new AABB(f.minX(), f.floor(), f.minZ(), f.maxX() + 1, f.floor() + m.h(), f.maxZ() + 1);
			this.mid = f.centre(f.floor());
			int fy = fl(f.floor());
			// the rim of a pit three deep over a chest's or a hopper's floor is fl(F) + 4: up to where a crouched eye
			// still reaches the top of the mob, and 0.4 more for a cell whose feet stand on the block under it (the
			// reach's own test in look() drops what lies too high)
			int top = Math.max(fy + 3, fl(f.floor() + m.h() + REACH - CROUCH_EYE + 0.4));
			for (int x = f.minX() - 3; x <= f.maxX() + 3; x++) for (int z = f.minZ() - 3; z <= f.maxZ() + 3; z++) {
				if (f.has(x, z)) continue;
				for (int y = fy - 3; y <= top; y++) cells.add(new int[] { x, y, z });
			}
		}

		/** Work on for at most this long; true once every cell is looked at. */
		boolean step(long budget) {
			long start = System.nanoTime(), until = start + budget;
			while (next < cells.size()) {
				look(cells.get(next++));
				if (System.nanoTime() > until) break;
			}
			nanos += System.nanoTime() - start;
			return next >= cells.size();
		}

		boolean done() { return next >= cells.size(); }

		long millis() { return nanos / 1_000_000; }

		private void look(int[] q) {
			int qx = q[0], qy = q[1], qz = q[2];
			if ((w.flags(qx, qy, qz) & OPENS) != 0 || (w.flags(qx, qy + 1, qz) & OPENS) != 0) return;
			double feet = w.stand(qx, qy, qz);
			if (Double.isNaN(feet)) return;
			double cx = qx + 0.5, cz = qz + 0.5;
			if (dist(pen, new Vec3(cx, feet + STAND_EYE, cz)) > REACH + 0.6) return;
			double ux = mid.x - cx, uz = mid.z - cz, ul = Math.hypot(ux, uz);
			if (ul > 1e-9) { ux /= ul; uz /= ul; }
			boolean edge = edge(qx, qz, feet);
			Variant best = null;
			for (boolean crouch : new boolean[] { false, true }) {
				if (edge && !crouch) continue;
				for (double push : PUSH) {
					double px = cx + ux * push, pz = cz + uz * push;
					AABB body = new AABB(px - HALF, feet, pz - HALF, px + HALF, feet + (crouch ? CROUCH_H : STAND_H), pz + HALF);
					// never over the pen, and no pushing on where the body would run into something
					if (push > 0 && (overPen(body) || collides(body))) break;
					Variant v = reach(px, pz, feet, crouch, push, body, cx, cz);
					if (better(v, best)) best = v;
				}
			}
			if (best == null || best.reach == 0) return;
			danger(best);
			if (best.melee) return;
			double dx = best.x - mid.x, dz = best.z - mid.z;
			int side = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? 1 : 3) : (dz > 0 ? 2 : 0);
			double angle = Math.atan2(dz, dx);
			spots.add(new Spot(new BlockPos(qx, qy, qz), feet, new Vec3(best.x, feet, best.z), best.crouch, edge, side, best.reach,
				best.samples, best.seen, best.creeper, angle, best.overFence));
		}

		private final class Variant {
			double x, z, feet, push, eye;
			boolean crouch, seen, melee, creeper, overFence;
			long reach;
			int samples;
			AABB body;
			double qx, qz;
		}

		/** Most columns, then standing, then most points, then the smaller push. */
		private boolean better(Variant v, Variant best) {
			if (best == null) return true;
			if (Long.bitCount(v.reach) != Long.bitCount(best.reach)) return Long.bitCount(v.reach) > Long.bitCount(best.reach);
			if (v.crouch != best.crouch) return !v.crouch;
			if (v.samples != best.samples) return v.samples > best.samples;
			return v.push < best.push;
		}

		private Variant reach(double px, double pz, double feet, boolean crouch, double push, AABB body, double qcx, double qcz) {
			Variant v = new Variant();
			v.x = px; v.z = pz; v.feet = feet; v.crouch = crouch; v.push = push; v.body = body; v.qx = qcx; v.qz = qcz;
			v.eye = feet + (crouch ? CROUCH_EYE : STAND_EYE);
			Vec3 eye = new Vec3(px, v.eye, pz);
			for (int i = 0; i < order.size(); i++) {
				long k = order.get(i);
				AABB v0 = column(k), v1 = away(v0, k, qcx, qcz);
				if (dist(v0, eye) > REACH && dist(v1, eye) > REACH) continue;
				int n = count(eye, v0, v) + count(eye, v1, v);
				if (n > 0) { v.reach |= 1L << i; v.samples += n; }
			}
			return v;
		}

		/** The sample points of this box reached through what a crosshair stops at, three at most. */
		private int count(Vec3 eye, AABB box, Variant v) {
			int n = 0;
			for (double fy : YF) for (double fx : XZ) for (double fz : XZ) {
				Vec3 pt = new Vec3(box.minX + fx * (box.maxX - box.minX), box.minY + fy * (box.maxY - box.minY), box.minZ + fz * (box.maxZ - box.minZ));
				if (eye.distanceTo(pt) > REACH) continue;
				int c = clearInfo(w, eye, pt, true);
				if (c < 0) continue;
				if (c == 1) v.overFence = true;
				if (++n >= 3) return n;
			}
			return n;
		}

		/** Whether a mob of the pen sees the buddy there, can land a hit, or would swell as a creeper. */
		private void danger(Variant v) {
			Vec3 eye = new Vec3(v.x, v.eye, v.z);
			for (long k : order) {
				AABB v0 = column(k);
				// the mob in the bowl and pushed to the far side, and one standing on the rim
				for (AABB b : new AABB[] { v0, away(v0, k, v.qx, v.qz), column(k, true) }) {
					double bx = (b.minX + b.maxX) / 2, bz = (b.minZ + b.maxZ) / 2;
					Vec3 mobEye = new Vec3(bx, b.minY + m.eye(), bz);
					if (mobEye.distanceTo(eye) > SIGHT || !clear(w, mobEye, eye, false)) continue;
					v.seen = true;
					if (b.inflate(MELEE, 0, MELEE).intersects(v.body)) v.melee = true;
					double ddx = bx - v.x, ddy = b.minY - v.feet, ddz = bz - v.z;
					if (ddx * ddx + ddy * ddy + ddz * ddz < CREEPER2) v.creeper = true;
				}
			}
		}

		/**
		 * A mob of the pen's size in the middle of this column, on its floor. A column level with the mob's own floor
		 * takes the lower of the two: the merged boxes of a hopper read as a block one high, while a mob stands in its
		 * bowl at 11/16 (as the zombie that was hit did), so the reach is worked out for the lower one.
		 */
		private AABB column(long k) { return column(k, false); }

		private AABB column(long k, boolean high) {
			double x = kx(k) + 0.5, z = kz(k) + 0.5, y = f.floorOf().get(k), hw = m.w() / 2;
			if (!high && Math.abs(y - f.floor()) <= FLOOR_TOL) y = Math.min(y, f.floor());
			return new AABB(x - hw, y, z - hw, x + hw, y + m.h(), z + hw);
		}

		/** The same mob pushed 0.2 to the far side, away from the buddy (knockback and crowding do that), kept in its column. */
		private AABB away(AABB v0, long k, double qcx, double qcz) {
			double cx = (v0.minX + v0.maxX) / 2, cz = (v0.minZ + v0.maxZ) / 2, ux = cx - qcx, uz = cz - qcz, ul = Math.hypot(ux, uz);
			if (ul < 1e-9) return v0;
			double mx = ux / ul * 0.2, mz = uz / ul * 0.2;
			int x = kx(k), z = kz(k);
			mx = Math.max(x - v0.minX, Math.min(x + 1 - v0.maxX, mx));
			mz = Math.max(z - v0.minZ, Math.min(z + 1 - v0.maxZ, mz));
			return v0.move(mx, 0, mz);
		}

		/** A drop beside the cell: a neighbour with no floor within 1.25 under the feet. Then crouch there. */
		private boolean edge(int qx, int qz, double feet) {
			int[][] dirs = { { 0, -1 }, { 1, 0 }, { 0, 1 }, { -1, 0 } };
			for (int[] d : dirs) {
				int nx = qx + d[0], nz = qz + d[1];
				boolean floor = false;
				for (int y = fl(feet - 1.25); y <= fl(feet) && !floor; y++)
					for (AABB c : w.collision(nx, y, nz)) if (c.maxY >= feet - 1.25 - 1e-6 && c.maxY <= feet + STEP + 1e-6) { floor = true; break; }
				// a wall beside the cell is no edge: its blocks reach over the feet
				boolean wall = false;
				for (AABB c : w.collision(nx, fl(feet + 0.01), nz)) if (c.minY <= feet + 0.01 && c.maxY > feet + STEP) { wall = true; break; }
				if (!floor && !wall) return true;
			}
			return false;
		}

		private boolean overPen(AABB body) {
			for (long k : order) {
				int x = kx(k), z = kz(k);
				if (body.maxX > x && body.minX < x + 1 && body.maxZ > z && body.minZ < z + 1) return true;
			}
			return false;
		}

		private boolean collides(AABB body) {
			for (int x = fl(body.minX); x <= fl(body.maxX - 1e-9); x++) for (int z = fl(body.minZ); z <= fl(body.maxZ - 1e-9); z++)
				for (int y = fl(body.minY) - 1; y <= fl(body.maxY - 1e-9); y++)
					for (AABB c : w.collision(x, y, z)) if (c.intersects(body.deflate(1e-4))) return true;
			return false;
		}

		Result result() {
			List<Spot> sorted = new ArrayList<>(spots);
			sorted.sort(Comparator.comparingDouble(Spot::angle));
			Spot[] ring = new Spot[4];
			for (Spot s : sorted) if (ringBetter(s, ring[s.side()])) ring[s.side()] = s;
			boolean pit = false, any = false, fence = false;
			for (Spot s : ring) {
				if (s == null) continue;
				if (!any) { pit = true; any = true; }
				if (s.feet() < f.floor() + 1.5) pit = false;
				if (s.overFence()) fence = true;
			}
			String kind = pit ? "pit" : fence ? "ring" : "cell";
			return new Result(f, ev.shaftTop(), ev.top(), ev.shaft(), ev.collector(), ev.points(), ev.why(), kind, sorted, ring);
		}

		/** Not a creeper's spot, then most columns, most points, standing, nearer to the pen. */
		private boolean ringBetter(Spot s, Spot best) {
			if (best == null) return true;
			if (s.creeper() != best.creeper()) return !s.creeper();
			if (s.columns() != best.columns()) return s.columns() > best.columns();
			if (s.samples() != best.samples()) return s.samples() > best.samples();
			if (s.crouch() != best.crouch()) return !s.crouch();
			return s.at().distanceTo(mid) < best.at().distanceTo(mid);
		}
	}

	/** The distance from a point to a box (0 inside). */
	static double dist(AABB b, Vec3 v) {
		double x = Math.max(b.minX, Math.min(v.x, b.maxX)), y = Math.max(b.minY, Math.min(v.y, b.maxY)), z = Math.max(b.minZ, Math.min(v.z, b.maxZ));
		return v.distanceTo(new Vec3(x, y, z));
	}

	// ---------- the pace of the hits ----------

	/**
	 * The time to the next hit, as Grinder's recipe calibrated on a human player's hand (25 Sep 2026), without its misses and
	 * early hits: just after the weapon's cooldown, log-normal with a memory of the hit before, now and then a longer
	 * wait. Never under the {@link #gate} where the strength is back at STRONG, nor faster than six a second, and bounced
	 * back from that bound rather than clipped at it: a gap under the gate waited for the gate's own tick, which so got
	 * about a fifth of the blows and nothing came before it, the spike at a bound that Grinder's bounce is there against.
	 */
	static final class Pace {
		private double dev;

		long gap(double cooldownMs, Random r) {
			double median = Math.max(MIN_GAP, cooldownMs * (1.02 + r.nextDouble() * 0.24));
			dev = 0.55 * dev + r.nextGaussian() * 0.25;
			double gap = median * Math.exp(dev);
			if (r.nextDouble() < 0.05) gap += 250 + r.nextDouble() * 950;
			double bound = Math.max(MIN_GAP, gate(cooldownMs));
			if (gap < bound) gap = bound + (bound - gap) * (0.5 + r.nextDouble());
			return (long) Math.ceil(gap);
		}

		/**
		 * How long after a blow the strength reads STRONG again, as Pen counts both: a gap runs from the end of the blow's
		 * own tick, where the ticker the blow reset already reads 1, and Pen.hitTick reads it half a tick on
		 * (getAttackStrengthScale(0.5)), so (1.5 + gap / 50) / delay >= STRONG, with the cooldown's delay in ticks.
		 */
		static double gate(double cooldownMs) { return STRONG * cooldownMs - 75; }
	}
}
