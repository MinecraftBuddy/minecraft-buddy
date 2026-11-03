package net.milenco.buddy.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Where a buddy who stays takes cover from foes the fight leaves be in a pit or a farm's cell ({@link Pen}'s cover; the
 * third review of 22 Oct 2026). Pure: it sees the world only through {@link PenShape.World}, so that
 * {@code CoverShapeTest} can hold it against pits drawn in characters.
 * <ul>
 * <li>{@link #sees}: whether a mob of a pit sees an eye, from its eye now and from every column of its pit at the mob's
 * eye height over that column's floor, in its middle and pushed to its far walls, where knockback and crowding put the
 * mobs (as the pen's own retreat search goes). Hidden from one eye of one tick, a cell was seen by the same skeleton
 * from the far wall a second later, or by another one of its pit.</li>
 * <li>{@link Search}: the nearest cell by the walk there that no foe sees, anywhere in the cell; never the cell the buddy
 * stands in, a column of a foe's pit, a cell beside a drop, or one next to a cell that let the buddy down already; a little
 * each tick.</li>
 * <li>{@link #seenAfterAll}: when a blow on the buddy says that the cell of the cover is seen after all.</li>
 * </ul>
 */
final class CoverShape {
	private CoverShape() {}

	/**
	 * One that may shoot the buddy: its eye now, and the pit it stands in (its columns with their floors; null: none known),
	 * with its eye height over its feet and its width there.
	 */
	record Foe(Vec3 eye, PenShape.Flood pit, double eyeHeight, double width) {}

	/** A cell to take cover in: where, the feet there, and the steps of the walk there. */
	record Cell(BlockPos pos, double feet, int steps) {}

	/**
	 * Where in a cell the buddy's eye may be when standing in it: the middle and the corners of a square 0.8 wide round it. The
	 * walker stops anywhere in a goal's cell, at its edge as often as not; hidden at its middle alone, the cell nearest by
	 * the walk is the one at the edge of a shadow, and its near half in sight.
	 */
	private static final double[][] AT = { { 0.5, 0.5 }, { 0.1, 0.1 }, { 0.9, 0.1 }, { 0.1, 0.9 }, { 0.9, 0.9 } };

	/** Whether this foe sees that eye: from its own eye, or from a column of its pit (middle and far walls). */
	static boolean sees(PenShape.World w, Foe f, Vec3 eye) {
		if (f.eye() != null && PenShape.clear(w, f.eye(), eye, false)) return true;
		if (f.pit() == null) return false;
		double half = Math.min(0.5, f.width() / 2);
		for (long k : f.pit().cols()) {
			Double floor = f.pit().floorOf().get(k);
			if (floor == null) continue;
			int x = PenShape.kx(k), z = PenShape.kz(k);
			double y = floor + f.eyeHeight();
			double fx = eye.x < x + 0.5 ? x + 1 - half : x + half, fz = eye.z < z + 0.5 ? z + 1 - half : z + half;
			double[][] at = { { x + 0.5, z + 0.5 }, { fx, z + 0.5 }, { x + 0.5, fz }, { fx, fz } };
			for (double[] a : at) if (PenShape.clear(w, new Vec3(a[0], y, a[1]), eye, false)) return true;
		}
		return false;
	}

	/**
	 * Whether a new blow on the buddy says that the cell of the cover is seen after all, so that it is given up for the
	 * next: there is a cell ({@code cell}), it was reached once ({@code firstWalk} false: a blow on the first walk there
	 * came from before), over {@code grace} ago ({@code sinceFirst}: what was in the air at the arrival lands within it),
	 * and the buddy is at it ({@code there}: within an arrow's knockback of its middle) or on the way back to it after a
	 * push ({@code arrived} false: as often as not that blow's own knockback). While a search runs there is no cell, and a blow
	 * then is no reason to begin it again: until the review of the fourth round of 22 Oct 2026 it began anew as "hit
	 * again", spent no try, and a search that then found nothing said so.
	 */
	static boolean seenAfterAll(boolean cell, boolean firstWalk, long sinceFirst, long grace, boolean there, boolean arrived) {
		return cell && !firstWalk && sinceFirst > grace && (there || !arrived);
	}

	/** No foe sees the standing eye anywhere in this cell, with the feet there. */
	static boolean hidden(PenShape.World w, List<Foe> foes, BlockPos c, double feet) {
		for (double[] a : AT) {
			Vec3 eye = new Vec3(c.getX() + a[0], feet + PenShape.STAND_EYE, c.getZ() + a[1]);
			for (Foe f : foes) if (sees(w, f, eye)) return false;
		}
		return true;
	}

	/**
	 * Beside the cell, at the feet and two below, nothing to stand on (Grinder.dropAhead on each side): an arrow's
	 * knockback there is a fall, and the rim of the very pit the buddy hides from is such a cell.
	 */
	static boolean dropBeside(PenShape.World w, BlockPos c) {
		int[][] dirs = { { 0, -1 }, { 1, 0 }, { 0, 1 }, { -1, 0 } };
		for (int[] d : dirs) {
			int x = c.getX() + d[0], z = c.getZ() + d[1];
			boolean open = true;
			for (int dy = 0; dy >= -2 && open; dy--) if (!w.collision(x, c.getY() + dy, z).isEmpty()) open = false;
			if (open) return true;
		}
		return false;
	}

	/**
	 * The cover, time-boxed over ticks: the cells the buddy walks to (Pen.walkable: the steps of the walk there, the
	 * buddy's own cell 0), nearest by the walk first, the first that holds. A cell within {@code range} of where the buddy
	 * stands (flat), not that cell itself, no column of a foe's pit, not beside one that let the buddy down this bout
	 * ({@code bad}: not reached in its time, or seen
	 * there after all), {@code allowed} (in a base, its own cells), a cell to stand in with no drop beside it, and hidden
	 * from every foe.
	 */
	static final class Search {
		private final PenShape.World w;
		private final List<Map.Entry<BlockPos, Integer>> cells;
		private final BlockPos from;
		private final int range;
		private final List<Foe> foes;
		private final Set<Long> pits = new HashSet<>();
		private final List<BlockPos> bad;
		private final Predicate<BlockPos> allowed;
		private int next;
		private Cell found;
		private boolean done;

		Search(PenShape.World w, Map<BlockPos, Integer> walk, BlockPos from, int range, List<Foe> foes, Collection<BlockPos> bad,
				Predicate<BlockPos> allowed) {
			this.w = w;
			this.cells = new ArrayList<>(walk.entrySet());
			// the order of the walk there: a stable sort keeps the breadth-first order within one count of steps
			this.cells.sort(Comparator.comparingInt(Map.Entry::getValue));
			this.from = from;
			this.range = range;
			this.foes = List.copyOf(foes);
			for (Foe f : foes) if (f.pit() != null) pits.addAll(f.pit().cols());
			this.bad = List.copyOf(bad);
			this.allowed = allowed;
		}

		/** A little more of it, within this many nanoseconds: true once it is done (found, or none). */
		boolean step(long budget) {
			if (done) return true;
			long until = System.nanoTime() + budget;
			while (next < cells.size()) {
				Map.Entry<BlockPos, Integer> e = cells.get(next++);
				Cell c = consider(e.getKey(), e.getValue());
				if (c != null) { found = c; break; }
				if (System.nanoTime() > until && next < cells.size()) return false;
			}
			done = true;
			return true;
		}

		/** The cell found, or null: none (or not done yet). */
		Cell found() { return found; }

		private Cell consider(BlockPos q, int steps) {
			// never the cell stood in: by straight line the nearest hidden cell was that one as often as not, a moment
			// after the shooter stepped behind the near rim
			if (steps <= 0 || (q.getX() == from.getX() && q.getZ() == from.getZ() && Math.abs(q.getY() - from.getY()) <= 1)) return null;
			if (Math.hypot(q.getX() - from.getX(), q.getZ() - from.getZ()) > range) return null;
			if (pits.contains(PenShape.key(q.getX(), q.getZ()))) return null;
			for (BlockPos b : bad)
				if (Math.abs(b.getX() - q.getX()) <= 1 && Math.abs(b.getZ() - q.getZ()) <= 1 && Math.abs(b.getY() - q.getY()) <= 1) return null;
			if (!allowed.test(q)) return null;
			double feet = w.stand(q.getX(), q.getY(), q.getZ());
			if (Double.isNaN(feet) || dropBeside(w, q)) return null;
			return hidden(w, foes, q, feet) ? new Cell(q.immutable(), feet, steps) : null;
		}
	}
}
