package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Cover from foes in pits, drawn in characters (the third review of 22 Oct 2026). A cell is one character: '#' a full
 * block, '.' air (also everything never set), '|' a thin post (0.2 wide, a block high). The ground is stone with its top
 * at y 10, where he stands; a pit is dug into it. {@code stand} is Follow.floorY's rule for these blocks: a block under
 * the cell, 1.8 of head room.
 */
class CoverShapeTest {
	/** A skeleton: its eye over its feet, its width. */
	private static final double EYE = 1.74, WIDTH = 0.6;

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

		char at(int x, int y, int z) { return cells.getOrDefault(new BlockPos(x, y, z), '.'); }

		@Override public List<AABB> collision(int x, int y, int z) {
			return switch (at(x, y, z)) {
				case '#' -> List.of(new AABB(x, y, z, x + 1, y + 1, z + 1));
				case '|' -> List.of(new AABB(x + 0.4, y, z + 0.4, x + 0.6, y + 1, z + 0.6));
				default -> List.of();
			};
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

	/** Stone from x, z 0 to 14 with its top at 10, and a 1x1 pit two deep at 7, 7 (its floor at 8). */
	private static Chars ground() { return new Chars().fill(0, 5, 0, 14, 9, 14, '#').fill(7, 8, 7, 7, 9, 7, '.'); }

	/** The pit at 7, 7: one column on a floor at 8. */
	private static PenShape.Flood pit() {
		long k = PenShape.key(7, 7);
		return new PenShape.Flood(Set.of(k), Map.of(k, 8.0), 8.0, 7, 7, 7, 7, null);
	}

	/** A skeleton in the middle of the pit, with the pit known (or not). */
	private static CoverShape.Foe skeleton(boolean withPit) {
		return new CoverShape.Foe(new Vec3(7.5, 8 + EYE, 7.5), withPit ? pit() : null, EYE, WIDTH);
	}

	/** A wall north of the pit: x 5 to 9 at z 4, two high over the ground. */
	private static Chars walled() { return ground().fill(5, 10, 4, 9, 11, 4, '#'); }

	private static Map<BlockPos, Integer> walk(Object... cellsAndSteps) {
		Map<BlockPos, Integer> m = new LinkedHashMap<>();
		for (int i = 0; i + 1 < cellsAndSteps.length; i += 2) m.put((BlockPos) cellsAndSteps[i], (Integer) cellsAndSteps[i + 1]);
		return m;
	}

	private static BlockPos c(int x, int z) { return new BlockPos(x, 10, z); }

	private static CoverShape.Cell search(PenShape.World w, Map<BlockPos, Integer> walk, BlockPos from, List<BlockPos> bad,
			java.util.function.Predicate<BlockPos> allowed) {
		CoverShape.Search s = new CoverShape.Search(w, walk, from, 10, List.of(skeleton(true)), bad, allowed);
		while (!s.step(50_000_000L)) { }
		return s.found();
	}

	@Test
	void theFarWallSeesWhatTheMiddleDoesNot() {
		// four blocks south of the pit his eye is under the line over the rim from the middle of the column, not from its
		// far wall, where knockback and crowding put a skeleton
		Chars w = ground();
		Vec3 eye = new Vec3(7.5, 10 + PenShape.STAND_EYE, 11.5);
		assertFalse(CoverShape.sees(w, skeleton(false), eye), "from its eye in the middle");
		assertTrue(CoverShape.sees(w, skeleton(true), eye), "from the far wall of its pit");
	}

	@Test
	void theWholeCellHasToBeOutOfSight() {
		// a thin post hides the middle of the cell behind it, not its corners: where the walker stops in it is anyone's guess
		Chars w = new Chars().fill(0, 5, 0, 14, 9, 14, '#').fill(5, 10, 6, 5, 12, 6, '|');
		CoverShape.Foe f = new CoverShape.Foe(new Vec3(5.5, 10 + PenShape.STAND_EYE, 8.5), null, PenShape.STAND_EYE, WIDTH);
		assertFalse(CoverShape.sees(w, f, new Vec3(5.5, 10 + PenShape.STAND_EYE, 3.5)), "the middle is behind the post");
		assertFalse(CoverShape.hidden(w, List.of(f), c(5, 3), 10), "a corner is not");
	}

	@Test
	void nearestByTheWalkThatNoColumnSees() {
		// behind the wall is out of sight of the whole pit; the cells between the wall and the pit and past its end are not.
		// Two hidden cells: the one fewer steps away
		Chars w = walled();
		Map<BlockPos, Integer> m = walk(c(3, 6), 0, c(6, 5), 2, c(7, 5), 3, c(11, 4), 4, c(7, 3), 6, c(5, 2), 7);
		CoverShape.Cell got = search(w, m, c(3, 6), List.of(), x -> true);
		assertNotNull(got);
		assertEquals(c(7, 3), got.pos());
		assertEquals(6, got.steps());
		assertEquals(10, got.feet(), 1e-9);
	}

	@Test
	void neverTheCellHeStandsIn() {
		Chars w = walled();
		Map<BlockPos, Integer> m = walk(c(7, 3), 0, c(5, 2), 3);
		assertEquals(c(5, 2), search(w, m, c(7, 3), List.of(), x -> true).pos());
		// nor the one over or under it
		m = walk(c(7, 3), 1, c(5, 2), 3);
		assertEquals(c(5, 2), search(w, m, c(7, 3).above(), List.of(), x -> true).pos());
	}

	@Test
	void aCellThatLetHimDownAndItsNeighboursAreLeftOut() {
		Chars w = walled();
		Map<BlockPos, Integer> m = walk(c(3, 6), 0, c(7, 3), 4, c(8, 3), 5, c(5, 2), 7);
		assertEquals(c(5, 2), search(w, m, c(3, 6), List.of(c(7, 3)), x -> true).pos());
	}

	@Test
	void notInAFoesPitNorBesideADrop() {
		Chars w = walled();
		// the pit's own floor, and a cell beside a hole two deep: neither, however hidden
		w.fill(3, 8, 2, 3, 9, 2, '.');
		Map<BlockPos, Integer> m = walk(c(3, 6), 0, new BlockPos(7, 8, 7), 1, c(4, 2), 2, c(5, 2), 3);
		assertEquals(c(5, 2), search(w, m, c(3, 6), List.of(), x -> true).pos());
	}

	@Test
	void inABaseOnlyItsOwnCells() {
		Chars w = walled();
		Map<BlockPos, Integer> m = walk(c(3, 6), 0, c(7, 3), 4, c(5, 2), 7);
		assertEquals(c(5, 2), search(w, m, c(3, 6), List.of(), x -> x.getX() < 6).pos());
		assertNull(search(w, m, c(3, 6), List.of(), x -> false));
	}

	@Test
	void aPitShowsLittleOverItsRim() {
		// from a pit two deep a skeleton sees over the rim some five blocks: open ground farther off is out of its sight
		Chars w = ground();
		Map<BlockPos, Integer> m = walk(c(7, 4), 0, c(7, 3), 1, c(7, 2), 2, c(7, 1), 3);
		CoverShape.Cell got = search(w, m, c(7, 4), List.of(), x -> true);
		assertNotNull(got);
		assertEquals(c(7, 1), got.pos());
	}

	@Test
	void noneOutOfSightIsNone() {
		// a skeleton on open ground sees every cell round it
		Chars w = new Chars().fill(0, 5, 0, 14, 9, 14, '#');
		CoverShape.Foe f = new CoverShape.Foe(new Vec3(7.5, 10 + EYE, 7.5), null, EYE, WIDTH);
		Map<BlockPos, Integer> m = new LinkedHashMap<>();
		int n = 0;
		for (int x = 2; x <= 12; x++) for (int z = 2; z <= 12; z++) if (x != 7 || z != 7) m.put(c(x, z), n++);
		CoverShape.Search s = new CoverShape.Search(w, m, c(2, 2), 10, List.of(f), List.of(), x -> true);
		while (!s.step(50_000_000L)) { }
		assertNull(s.found());
	}

	@Test
	void aLittleEachTickGivesTheSameCell() {
		Chars w = walled();
		// every cell within three of the pit is in its sight; the one behind the wall comes last
		Map<BlockPos, Integer> m = new LinkedHashMap<>();
		m.put(c(3, 6), 0);
		for (int x = 5; x <= 10; x++) for (int z = 5; z <= 10; z++) if (x != 7 || z != 7) m.put(c(x, z), 1 + Math.abs(x - 3) + Math.abs(z - 6));
		m.put(c(7, 3), 40);
		CoverShape.Search s = new CoverShape.Search(w, m, c(3, 6), 20, List.of(skeleton(true)), List.of(), x -> true);
		int ticks = 0;
		while (!s.step(0)) ticks++;
		assertTrue(ticks > 10, "spread over ticks: " + ticks);
		assertEquals(c(7, 3), s.found().pos());
	}

	@Test
	void everyFoeCounts() {
		// a second skeleton in a pit of its own west of the wall's end sees behind the wall
		Chars w = walled().fill(1, 8, 1, 1, 9, 1, '.');
		long k = PenShape.key(1, 1);
		CoverShape.Foe other = new CoverShape.Foe(new Vec3(1.5, 8 + EYE, 1.5), new PenShape.Flood(Set.of(k), Map.of(k, 8.0), 8.0, 1, 1, 1, 1, null), EYE, WIDTH);
		Map<BlockPos, Integer> m = walk(c(3, 6), 0, c(5, 2), 3, c(8, 2), 5);
		List<CoverShape.Foe> foes = new ArrayList<>(List.of(skeleton(true)));
		CoverShape.Search alone = new CoverShape.Search(w, m, c(3, 6), 10, foes, List.of(), x -> true);
		while (!alone.step(50_000_000L)) { }
		assertEquals(c(5, 2), alone.found().pos());
		foes.add(other);
		CoverShape.Search both = new CoverShape.Search(w, m, c(3, 6), 10, foes, List.of(), x -> true);
		while (!both.step(50_000_000L)) { }
		assertNotNull(both.found());
		assertEquals(c(8, 2), both.found().pos());
	}

	@Test
	void aBlowWhileItSearchesIsNoBlowAtACell() {
		// a second and a half of grace from his first arrival at the cell, as Pen.COVER_GRACE; two seconds on, a blow at the
		// cell, or on his way back to it after a push, says that it is seen after all
		long grace = 1500;
		assertTrue(CoverShape.seenAfterAll(true, false, 2000, grace, true, true), "at the cell");
		assertTrue(CoverShape.seenAfterAll(true, false, 2000, grace, false, false), "on his way back after a push");
		// knocked farther off than an arrow's knockback while he holds it: not at it
		assertFalse(CoverShape.seenAfterAll(true, false, 2000, grace, false, true), "out of the cell he holds");
		// on his first walk there, or within the grace: what hit him was in the air before
		assertFalse(CoverShape.seenAfterAll(true, true, 60_000, grace, false, false), "on the first walk there");
		assertFalse(CoverShape.seenAfterAll(true, false, 1000, grace, true, true), "within the grace");
		// the search for the next cell under way, his first arrival at the last one long ago: no cell to blame, and the
		// search goes on (the review of the fourth round of 22 Oct 2026: it began anew as "hit again")
		assertFalse(CoverShape.seenAfterAll(false, false, 60_000, grace, false, false), "while it searches");
	}
}
