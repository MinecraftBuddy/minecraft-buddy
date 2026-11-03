package net.milenco.buddy.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Where the buddy gets away to from swelling creepers ({@link Pen}'s getaway; the review of the fourth round, 22 Oct 2026).
 * Pure: the world only through {@link PenShape.World}, so that {@code GetawayTest} can hold it against rooms drawn in
 * characters.
 * <ul>
 * <li>{@link #refuses}: a cell nearer to one of them than the buddy stands now, less {@link #SLACK} (the middle of the
 * buddy's own cell and the step off it pass). The walk Pen.walkable searches for a getaway never enters such a cell, and
 * the walker plans round them and cuts no corner across them (Follow.shun): so a getaway never goes past a swelling
 * creeper, nor toward
 * another one. The cell farthest from the nearest creeper alone, the fallback until then, lay past it in a dead end
 * with the way there through its own cell, or beside a second one.</li>
 * <li>{@link #pick}: of the cells the buddy walks to that way, the nearest by the walk out of the sight of each or a block
 * and a half past its blast; none: the one farthest out of all their blasts (its {@link #score}), if it beats the place
 * stood on now by half a block. That one is marked as a fallback: they see it by design, and being seen gave it up
 * every five ticks for one a little farther on, with the keys let go each time.</li>
 * </ul>
 */
final class Getaway {
	private Getaway() {}

	/** How much nearer to a swelling creeper than the buddy stands a cell may lie: the current cell's middle, the step off it. */
	static final double SLACK = 0.3;
	/** A cell a creeper sees will do this far past its blast; a fallback has to beat the place stood on now by BETTER. */
	static final double FAR = 1.5, BETTER = 0.5;

	/** A swelling creeper: its feet, its eye, how far its blast hurts, and the nearest the buddy may come to it
	 *  ({@link #refuses}). */
	record Foe(Vec3 feet, Vec3 eye, double blast, double limit) {
		/** As near as the buddy stands now ({@code me}: the feet on the ground underneath, {@link #standing}), less SLACK. */
		static Foe of(Vec3 feet, Vec3 eye, double blast, Vec3 me) { return new Foe(feet, eye, blast, feet.distanceTo(me) - SLACK); }
	}

	/**
	 * Where the buddy stands, to measure from: the feet at {@code at}, on the ground underneath while a knock or a jump
	 * has the buddy in the air. Measured from the place in the air, a block up at a pit's rim, every cell beside the
	 * buddy's own lay nearer a creeper down in the pit than the buddy did, the walk held that one cell alone, and the
	 * buddy stood there for half a second (the
	 * check of the review of the fourth round, 22 Oct 2026).
	 */
	static Vec3 standing(Vec3 at, double ground) { return new Vec3(at.x, ground, at.z); }

	/** A cell to get away to: where, the feet there, the steps of the walk there, whether it is a fallback, its score. */
	record Cell(BlockPos pos, double feet, int steps, boolean fallback, double score) {}

	/** Where a cell's nearness is measured: its middle at its own height, the feet on a block under it. */
	static Vec3 point(int x, int y, int z) { return new Vec3(x + 0.5, y, z + 0.5); }

	/** A cell nearer to one of them than the buddy may come: never entered on the way to a getaway's cell. */
	static boolean refuses(List<Foe> foes, int x, int y, int z) {
		Vec3 c = point(x, y, z);
		for (Foe f : foes) if (f.feet().distanceTo(c) < f.limit()) return true;
		return false;
	}

	/**
	 * How far out of their blasts a point is: the least, over them, of its distance less that one's blast, so that a
	 * charged one (twice the blast) counts double. Higher is safer.
	 */
	static double score(List<Foe> foes, Vec3 at) {
		double s = Double.POSITIVE_INFINITY;
		for (Foe f : foes) s = Math.min(s, f.feet().distanceTo(at) - f.blast());
		return s;
	}

	/** One of them sees a player whose feet are there (COLLIDER from its eye to the player's standing eye), within its blast
	 *  and FAR. */
	static boolean sees(PenShape.World w, List<Foe> foes, Vec3 feet) {
		Vec3 eye = feet.add(0, PenShape.STAND_EYE, 0);
		for (Foe f : foes) if (f.feet().distanceTo(feet) <= f.blast() + FAR && PenShape.clear(w, f.eye(), eye, false)) return true;
		return false;
	}

	/** One of them sees a player standing in that cell, or it is no cell to stand in any more. */
	static boolean seen(PenShape.World w, List<Foe> foes, BlockPos cell) {
		double feet = w.stand(cell.getX(), cell.getY(), cell.getZ());
		return Double.isNaN(feet) || sees(w, foes, new Vec3(cell.getX() + 0.5, feet, cell.getZ() + 0.5));
	}

	/**
	 * The cell to get away to: of the cells the buddy walks to ({@code walk}, with the steps of the walk there;
	 * Pen.walkable kept out of the cells {@link #refuses} refuses), within {@code range} flat, never the buddy's own (its
	 * middle hidden while the creeper saw the buddy at its edge kept the buddy standing in its sight), never one of
	 * {@code not} (given up in this getting away), with no drop beside it: the nearest by the walk that none of them sees
	 * ({@link #sees}); none such, the one with the best {@link #score} if it beats the buddy's own by BETTER, as a
	 * fallback; null for none, and the buddy stands. Nearest by the walk: by straight line it was a cell behind the rock
	 * beside the buddy's tunnel, or the floor of a hole there was no climbing out of (the third review of 22 Oct 2026).
	 */
	static Cell pick(PenShape.World w, Map<BlockPos, Integer> walk, List<Foe> foes, Vec3 me, Set<BlockPos> not, int range) {
		List<Map.Entry<BlockPos, Integer>> cells = new ArrayList<>(walk.entrySet());
		// the order of the walk there: a stable sort keeps the breadth-first order within one count of steps
		cells.sort(Comparator.comparingInt(Map.Entry::getValue));
		double mine = score(foes, me);
		Cell far = null;
		for (Map.Entry<BlockPos, Integer> e : cells) {
			BlockPos q = e.getKey();
			if (e.getValue() <= 0 || not.contains(q) || Math.hypot(q.getX() + 0.5 - me.x, q.getZ() + 0.5 - me.z) > range) continue;
			double feet = w.stand(q.getX(), q.getY(), q.getZ());
			if (Double.isNaN(feet) || CoverShape.dropBeside(w, q)) continue;
			Vec3 c = new Vec3(q.getX() + 0.5, feet, q.getZ() + 0.5);
			double s = score(foes, c);
			if (!sees(w, foes, c)) return new Cell(q.immutable(), feet, e.getValue(), false, s);
			if (s > mine + BETTER && (far == null || s > far.score())) far = new Cell(q.immutable(), feet, e.getValue(), true, s);
		}
		return far;
	}

	/**
	 * Whether a new pick is worth giving up a fallback for, whose score from where it stands is {@code now}: one they do
	 * not see, or a fallback better by BETTER. Not for being seen, as the fallback is by design.
	 */
	static boolean better(Cell next, double now) {
		return next != null && (!next.fallback() || next.score() >= now + BETTER);
	}

	/** At the getaway's cell: this near the middle of its floor (the walker's last cell is reached within 0.35), and once there this near. */
	static final double ARRIVED = 0.45, KEPT = 1.2;

	/**
	 * Whether the buddy is at the getaway's cell: the feet within ARRIVED of the middle of its floor ({@code feet}, where
	 * they stand in it: a bottom slab or a fence's top half a block over the cell's bottom), or within KEPT once there
	 * ({@code was}: a knock off it by less). Measured to the cell's bottom, a cell on a slab was never reached (the
	 * review of 22 Oct 2026), and the danger, the walk anew after a knock and the blow at the cell all waited on it.
	 */
	static boolean arrived(Vec3 me, BlockPos cell, double feet, boolean was) {
		double d = me.distanceTo(new Vec3(cell.getX() + 0.5, feet, cell.getZ() + 0.5));
		return d < ARRIVED || (was && d < KEPT);
	}

	/** Steps of the walk a cell out of their sight has to lie nearer by than the one walked to now, to be worth the change. */
	static final int NEARER = 2;

	/**
	 * Whether a new pick is worth giving up a cell out of their sight for, the one walked to now ({@code now},
	 * {@code left}: its steps on the walk of that pick, -1 for none): out of their sight too, another one, and nearer by
	 * the walk from where the buddy stands by NEARER, or that cell is on no such walk any more. A creeper that swelled at
	 * the buddy while it fell past into the pit had the buddy walk to a cell picked against where it was in the air, five
	 * blocks along the rim in its sight, where a cell out of it lay a step back once it had landed, and it went off (the
	 * test of 22 Oct 2026).
	 */
	static boolean nearer(Cell next, int left, BlockPos now) {
		return next != null && !next.fallback() && !next.pos().equals(now) && (left < 0 || next.steps() + NEARER <= left);
	}

	/**
	 * The cells {@link #refuses} refuses round each of them, within {@code reach} of the buddy's cell {@code mine} flat,
	 * never that cell nor the one over or under it: for the walker to plan round and cut no corner across (Follow.shun),
	 * so that its own way to the cell does not go past the creeper the cell was picked to keep the buddy from.
	 */
	static Set<BlockPos> refused(List<Foe> foes, BlockPos mine, int reach) {
		Set<BlockPos> out = new HashSet<>();
		for (Foe f : foes) {
			double r = f.limit();
			if (r <= 0) continue;
			int x0 = (int) Math.floor(f.feet().x - r - 0.5), x1 = (int) Math.ceil(f.feet().x + r - 0.5);
			int z0 = (int) Math.floor(f.feet().z - r - 0.5), z1 = (int) Math.ceil(f.feet().z + r - 0.5);
			int y0 = (int) Math.floor(f.feet().y - r), y1 = (int) Math.ceil(f.feet().y + r);
			for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
				if (Math.abs(x - mine.getX()) > reach || Math.abs(z - mine.getZ()) > reach) continue;
				for (int y = y0; y <= y1; y++) {
					if (x == mine.getX() && z == mine.getZ() && Math.abs(y - mine.getY()) <= 1) continue;
					if (refuses(foes, x, y, z)) out.add(new BlockPos(x, y, z));
				}
			}
		}
		return out;
	}
}
