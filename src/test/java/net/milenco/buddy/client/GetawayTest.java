package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The getaway from swelling creepers in rooms drawn in characters (the review of the fourth round, 22 Oct 2026). A cell is
 * one character: '#' a full block, '.' air (also everything never set). Stone fills y 5 to 9 under every room, so the
 * buddy's feet are at y 10; walls stand from y 10 to 12, and the rooms are carved into them at y 10 and 11. The walk is
 * Pen.walkable's rule on flat ground: the four cells beside one to stand in, within the range each way, and with the
 * getaway's rule only into a cell it does not refuse (Getaway.refuses).
 */
class GetawayTest {
	/** A creeper's eye over its feet (0.85 of its height of 1.7), and how far its blast hurts, uncharged and charged. */
	private static final double EYE = 1.445, BLAST = 6, CHARGED = 12;

	private static final class Chars implements PenShape.World {
		private final Map<BlockPos, Character> cells = new HashMap<>();

		Chars fill(int x1, int y1, int z1, int x2, int y2, int z2, char c) {
			for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
				for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
					for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
						if (c == '.') cells.remove(new BlockPos(x, y, z));
						else cells.put(new BlockPos(x, y, z), c);
					}
			return this;
		}

		/** A room or corridor carved into the walls: air at the feet and the head. */
		Chars carve(int x1, int z1, int x2, int z2) { return fill(x1, 10, z1, x2, 11, z2, '.'); }

		char at(int x, int y, int z) { return cells.getOrDefault(new BlockPos(x, y, z), '.'); }

		@Override public List<AABB> collision(int x, int y, int z) {
			return at(x, y, z) == '#' ? List.of(new AABB(x, y, z, x + 1, y + 1, z + 1)) : List.of();
		}

		@Override public List<AABB> outline(int x, int y, int z) { return collision(x, y, z); }

		@Override public int flags(int x, int y, int z) { return 0; }

		@Override public double stand(int x, int y, int z) {
			if (!collision(x, y, z).isEmpty() || at(x, y - 1, z) != '#') return Double.NaN;
			for (int yy = y; yy <= y + 1; yy++) if (!collision(x, yy, z).isEmpty()) return Double.NaN;
			return y;
		}

		@Override public boolean loaded(int x, int z) { return true; }
	}

	/** Stone under all, walls everywhere over it from x -2 to 14 and z -2 to 14: rooms are carved into them. */
	private static Chars rock() { return new Chars().fill(-2, 5, -2, 14, 9, 14, '#').fill(-2, 10, -2, 14, 12, 14, '#'); }

	private static BlockPos c(int x, int z) { return new BlockPos(x, 10, z); }

	private static Vec3 feet(double x, double z) { return new Vec3(x, 10, z); }

	/** A creeper whose feet are there, swelling at the buddy standing at {@code me}. */
	private static Getaway.Foe creeper(Vec3 at, Vec3 me) { return Getaway.Foe.of(at, at.add(0, EYE, 0), BLAST, me); }

	/** The cells the buddy walks to from {@code from}, nearest by the walk first (as Pen.walkable), only into those {@code enter} lets in. */
	private static Map<BlockPos, Integer> walk(Chars w, BlockPos from, int range, Predicate<BlockPos> enter) {
		Map<BlockPos, Integer> steps = new LinkedHashMap<>();
		ArrayDeque<BlockPos> open = new ArrayDeque<>();
		steps.put(from, 0);
		open.add(from);
		while (!open.isEmpty()) {
			BlockPos cur = open.poll();
			for (BlockPos n : new BlockPos[] { cur.north(), cur.east(), cur.south(), cur.west() }) {
				if (Math.abs(n.getX() - from.getX()) > range || Math.abs(n.getZ() - from.getZ()) > range) continue;
				if (steps.containsKey(n) || Double.isNaN(w.stand(n.getX(), n.getY(), n.getZ())) || !enter.test(n)) continue;
				steps.put(n, steps.get(cur) + 1);
				open.add(n);
			}
		}
		return steps;
	}

	private static Map<BlockPos, Integer> getaway(Chars w, BlockPos from, List<Getaway.Foe> foes) {
		return walk(w, from, 8, q -> !Getaway.refuses(foes, q.getX(), q.getY(), q.getZ()));
	}

	private static Map<BlockPos, Integer> anywhere(Chars w, BlockPos from) { return walk(w, from, 8, q -> true); }

	/** A corridor one wide along x at z 5, from a dead end at x 0 to x 12. */
	private static Chars corridor() { return rock().carve(0, 5, 12, 5); }

	@Test
	void inADeadEndHeStepsIntoItNeverPastTheCreeper() {
		// the buddy stands a cell from the dead end, the creeper swells two and a half blocks up the corridor, which it sees all of
		Chars w = corridor();
		Vec3 me = feet(1.5, 5.5);
		List<Getaway.Foe> foes = List.of(creeper(feet(4, 5.5), me));
		// the walk anywhere ran past it, through its own cell, to the cell farthest from it
		Getaway.Cell old = Getaway.pick(w, anywhere(w, c(1, 5)), foes, me, Set.of(), 8);
		assertNotNull(old);
		assertTrue(old.pos().getX() > 4, "the old way went past the creeper: " + old.pos());
		Map<BlockPos, Integer> walk = getaway(w, c(1, 5), foes);
		assertEquals(Set.of(c(1, 5), c(0, 5)), walk.keySet(), "no cell toward it is entered");
		Getaway.Cell got = Getaway.pick(w, walk, foes, me, Set.of(), 8);
		assertNotNull(got);
		assertEquals(c(0, 5), got.pos());
		assertTrue(got.fallback(), "it sees the dead end: a fallback");
		assertEquals(3.5 - BLAST, got.score(), 1e-9);
	}

	@Test
	void aCorridorBentPastTheCreeperIsNoWayOut() {
		// past the creeper the corridor turns north round a corner, out of its sight: the walk there goes by it
		Chars w = corridor().carve(6, 6, 6, 9);
		Vec3 me = feet(1.5, 5.5);
		List<Getaway.Foe> foes = List.of(creeper(feet(4, 5.5), me));
		Getaway.Cell old = Getaway.pick(w, anywhere(w, c(1, 5)), foes, me, Set.of(), 8);
		assertNotNull(old);
		assertFalse(old.fallback());
		assertTrue(old.pos().getX() == 6 && old.pos().getZ() > 5, "the old way: round the corner, past it: " + old.pos());
		Getaway.Cell got = Getaway.pick(w, getaway(w, c(1, 5), foes), foes, me, Set.of(), 8);
		assertNotNull(got);
		assertEquals(c(0, 5), got.pos());
	}

	@Test
	void twoCreepersAreBothKeptOff() {
		// a corridor along z; one creeper two north of the buddy, one four south. Nearer to neither: no cell at all
		Chars w = rock().carve(5, 0, 5, 12);
		Vec3 me = feet(5.5, 5.5);
		List<Getaway.Foe> foes = List.of(creeper(feet(5.5, 3.5), me), creeper(feet(5.5, 9.5), me));
		Map<BlockPos, Integer> walk = getaway(w, c(5, 5), foes);
		assertEquals(Set.of(c(5, 5)), walk.keySet());
		assertNull(Getaway.pick(w, walk, foes, me, Set.of(), 8), "the buddy stands");
		// a side passage east where the buddy stands: into it, round the corner from both
		w.carve(6, 5, 8, 5);
		Getaway.Cell got = Getaway.pick(w, getaway(w, c(5, 5), foes), foes, me, Set.of(), 8);
		assertNotNull(got);
		assertEquals(c(6, 5), got.pos());
		assertFalse(got.fallback());
	}

	@Test
	void aCellOutOfSightBeyondTheSecondCreeperIsNotTaken() {
		// the nearest creeper two north, the other four south; past the second the corridor turns east, out of both
		// their sights. On the buddy's side of the nearest one, as the old side test went, that corner was the getaway
		Chars w = rock().carve(5, 0, 5, 12).carve(6, 12, 8, 12);
		Vec3 me = feet(5.5, 5.5);
		List<Getaway.Foe> foes = List.of(creeper(feet(5.5, 3.5), me), creeper(feet(5.5, 9.5), me));
		Getaway.Cell old = Getaway.pick(w, anywhere(w, c(5, 5)), foes, me, Set.of(), 8);
		assertNotNull(old);
		assertTrue(old.pos().getZ() > 9, "past the second one: " + old.pos());
		assertNull(Getaway.pick(w, getaway(w, c(5, 5), foes), foes, me, Set.of(), 8));
	}

	@Test
	void outOfSightNearestByTheWalkBeforeAnyFallback() {
		// an open room, the creeper three east of the buddy; behind a pillar of wall two west a cell it cannot see
		Chars w = rock().carve(0, 0, 12, 12).fill(3, 10, 4, 3, 12, 6, '#');
		Vec3 me = feet(5.5, 5.5);
		List<Getaway.Foe> foes = List.of(creeper(feet(8.5, 5.5), me));
		Getaway.Cell got = Getaway.pick(w, getaway(w, c(5, 5), foes), foes, me, Set.of(), 8);
		assertNotNull(got);
		assertFalse(got.fallback());
		assertTrue(got.pos().getX() < 3, "behind the pillar: " + got.pos());
		assertFalse(Getaway.seen(w, foes, got.pos()));
		assertTrue(Getaway.seen(w, foes, c(4, 5)), "in front of the pillar it sees the buddy");
	}

	@Test
	void neverHisOwnCellNorOneGivenUp() {
		Chars w = corridor();
		Vec3 me = feet(1.5, 5.5);
		List<Getaway.Foe> foes = List.of(creeper(feet(4, 5.5), me));
		Map<BlockPos, Integer> walk = getaway(w, c(1, 5), foes);
		assertNull(Getaway.pick(w, Map.of(c(1, 5), 0), foes, me, Set.of(), 8), "the buddy's own cell");
		assertNull(Getaway.pick(w, walk, foes, me, Set.of(c(0, 5)), 8), "the dead end given up");
	}

	@Test
	void aFallbackHasToBeatWhereHeStands() {
		// an open room it sees all of, the creeper four east of the buddy and a little north: a step south takes a quarter of
		// a block farther from it, no gain worth the walk; a step west a whole block
		Chars w = rock().carve(0, 0, 12, 12);
		Vec3 me = feet(5.5, 5.5);
		List<Getaway.Foe> foes = List.of(creeper(feet(9.5, 4.9), me));
		assertNull(Getaway.pick(w, Map.of(c(5, 5), 0, c(5, 6), 1), foes, me, Set.of(), 8));
		Getaway.Cell got = Getaway.pick(w, Map.of(c(5, 5), 0, c(5, 6), 1, c(4, 5), 1), foes, me, Set.of(), 8);
		assertNotNull(got);
		assertEquals(c(4, 5), got.pos());
		assertTrue(got.fallback());
	}

	@Test
	void aFallbackIsGivenUpOnlyForCoverOrHalfABlockMore() {
		Getaway.Cell seenFarther = new Getaway.Cell(c(0, 0), 10, 3, true, -2.0);
		Getaway.Cell hidden = new Getaway.Cell(c(1, 0), 10, 5, false, -4.0);
		assertFalse(Getaway.better(null, -2.0));
		assertFalse(Getaway.better(seenFarther, -2.3), "a fallback 0.3 better");
		assertTrue(Getaway.better(seenFarther, -2.5), "a fallback half a block better");
		assertTrue(Getaway.better(hidden, -2.0), "out of sight, whatever its score");
	}

	@Test
	void aChargedCreeperCountsDouble() {
		Vec3 me = feet(0.5, 0.5);
		Getaway.Foe near = creeper(feet(4.5, 0.5), me);
		Vec3 at = feet(9.5, 0.5);
		Getaway.Foe charged = new Getaway.Foe(at, at.add(0, EYE, 0), CHARGED, at.distanceTo(me) - Getaway.SLACK);
		// four from the uncharged (4 - 6 = -2), nine from the charged one (9 - 12 = -3): the charged one decides
		assertEquals(-3, Getaway.score(List.of(near, charged), me), 1e-9);
		assertEquals(-2, Getaway.score(List.of(near), me), 1e-9);
	}

	@Test
	void inTheAirHeIsMeasuredFromTheGroundUnderHim() {
		// a pit two deep at 5, 5 in open ground, a creeper on its floor; the buddy stands a cell east of it at the rim,
		// knocked a block up by a blow or in a jump (the check of the review of the fourth round): from there every cell
		// beside the buddy's own lies nearer to the creeper than the buddy does, the walk holds that one cell alone and the
		// buddy stands
		Chars w = new Chars().fill(-2, 5, -2, 14, 9, 14, '#').fill(5, 8, 5, 5, 9, 5, '.');
		Vec3 at = new Vec3(5.5, 8, 5.5), air = new Vec3(6.5, 11.15, 5.5);
		List<Getaway.Foe> high = List.of(creeper(at, air));
		assertEquals(Set.of(c(6, 5)), getaway(w, c(6, 5), high).keySet(), "measured from the air: the buddy's own cell alone");
		// from the ground underneath (Getaway.standing): the step east, out of its sight over the pit's rim
		Vec3 me = Getaway.standing(air, 10);
		assertEquals(feet(6.5, 5.5), me);
		List<Getaway.Foe> foes = List.of(creeper(at, me));
		Getaway.Cell got = Getaway.pick(w, getaway(w, c(6, 5), foes), foes, me, Set.of(), 8);
		assertNotNull(got);
		assertEquals(c(7, 5), got.pos());
		assertFalse(got.fallback());
	}

	@Test
	void aCellNearerOnceTheCreeperHasLandedIsTaken() {
		// a pit two deep (x 5..6, z 5..6) in open ground; a creeper swells at the buddy while it falls past into it (the
		// test of 22 Oct 2026): picked against it in the air, the cell lies far out on the platform, where none sees the buddy
		Chars w = new Chars().fill(-2, 5, -2, 14, 9, 14, '#').fill(5, 8, 5, 6, 9, 6, '.');
		Vec3 me = feet(5.5, 4.5);
		List<Getaway.Foe> high = List.of(creeper(new Vec3(5.5, 10.3, 5.5), me));
		Getaway.Cell first = Getaway.pick(w, getaway(w, c(5, 4), high), high, me, Set.of(), 8);
		assertNotNull(first);
		assertFalse(first.fallback(), "a block and a half past its blast: it does not see the buddy there");
		assertTrue(first.steps() >= 5, "far out: " + first);
		// a step on the way it has landed: out of its sight a step back from the rim
		Vec3 on = feet(5.5, 3.5);
		List<Getaway.Foe> low = List.of(creeper(new Vec3(5.5, 8, 5.5), on));
		Map<BlockPos, Integer> walk = getaway(w, c(5, 3), low);
		Getaway.Cell next = Getaway.pick(w, walk, low, on, Set.of(), 8);
		assertNotNull(next);
		assertFalse(next.fallback());
		assertTrue(next.steps() <= 2, "a step or two back: " + next);
		assertTrue(Getaway.nearer(next, walk.getOrDefault(first.pos(), -1), first.pos()));
		assertTrue(Getaway.nearer(next, -1, first.pos()), "the cell walked to is on no such walk any more");
		// the same cell, a fallback, or one not nearer by two steps: no change
		assertFalse(Getaway.nearer(next, walk.getOrDefault(next.pos(), -1), next.pos()));
		assertFalse(Getaway.nearer(new Getaway.Cell(next.pos(), 10, next.steps(), true, 0), 9, first.pos()));
		assertFalse(Getaway.nearer(next, next.steps() + 1, first.pos()));
	}

	@Test
	void atTheCellIsMeasuredFromItsFloor() {
		// a bottom slab's own cell, and a fence's top: the feet half a block over the cell's bottom (the review of 22 Oct
		// 2026: measured to the bottom, 0.5 at the least, such a cell was never reached)
		BlockPos slab = c(3, 5);
		assertTrue(Getaway.arrived(new Vec3(3.8, 10.5, 5.7), slab, 10.5, false), "within the walker's 0.35 on the slab");
		assertFalse(Getaway.arrived(new Vec3(3.9, 10.5, 5.8), slab, 10.5, false), "half a block off its middle: not yet");
		assertTrue(Getaway.arrived(new Vec3(3.9, 10.5, 5.8), slab, 10.5, true), "but kept once there");
		assertFalse(Getaway.arrived(new Vec3(4.8, 10.5, 5.5), slab, 10.5, true), "knocked 1.3 off it");
		// on stone, the feet at the cell's bottom
		assertTrue(Getaway.arrived(new Vec3(3.8, 10, 5.7), slab, 10, false));
	}

	@Test
	void theCellsKeptOutOfAreThoseNearerThanHeStands() {
		// the creeper two and a half up the corridor: the cells on the way to it and round it, not the buddy's own nor beyond
		Vec3 me = feet(1.5, 5.5);
		List<Getaway.Foe> foes = List.of(creeper(feet(4, 5.5), me));
		Set<BlockPos> out = Getaway.refused(foes, c(1, 5), 10);
		assertTrue(out.contains(c(2, 5)));
		assertTrue(out.contains(c(4, 5)), "its own cell");
		assertTrue(out.contains(c(5, 5)), "past it");
		assertFalse(out.contains(c(1, 5)), "the buddy's own cell");
		assertFalse(out.contains(c(0, 5)), "the dead end behind the buddy");
		// the buddy's own cell even where its middle lies nearer than the buddy may come
		me = feet(1.05, 5.5);
		foes = List.of(creeper(feet(4, 5.5), me));
		assertTrue(Getaway.refuses(foes, 1, 10, 5));
		assertFalse(Getaway.refused(foes, c(1, 5), 10).contains(c(1, 5)));
	}
}
