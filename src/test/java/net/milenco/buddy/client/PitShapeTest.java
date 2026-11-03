package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Which blocks of a pit lie between the player's work: the shape dug, not the box around it (0.4.44). */
class PitShapeTest {
	/** A floor plan at one height: '.' open (the player's work), '#' rock, 'F' a block put back. Row is z, column is x. */
	private static PitShape.Cells plan(String... rows) {
		Set<Long> open = new HashSet<>(), fixed = new HashSet<>();
		for (int z = 0; z < rows.length; z++) for (int x = 0; x < rows[z].length(); x++) {
			char ch = rows[z].charAt(x);
			if (ch == '.') open.add(key(x, z));
			if (ch == 'F') fixed.add(key(x, z));
		}
		return new PitShape.Cells() {
			public boolean open(int x, int y, int z) { return open.contains(key(x, z)); }
			public boolean fixed(int x, int y, int z) { return fixed.contains(key(x, z)); }
		};
	}

	private static long key(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }

	@Test
	void aBlockBetweenHisBreaksIsTaken() {
		// the player broke the two ends of a row; the middle is between them
		PitShape.Cells c = plan(
			".##.",
			"....");
		assertTrue(PitShape.between(c, 1, 0, 0, 0, 3, 0, 1));
		assertTrue(PitShape.between(c, 2, 0, 0, 0, 3, 0, 1));
	}

	@Test
	void theWallOfHisRoomIsNeverTaken() {
		// the room the player carved is x 0..3; the box ends there, so x 4 lies outside it and the blocks at its edge have rock
		// behind them: the case of 19 Oct 2026, when the alt broke the whole wall one block past that room
		PitShape.Cells c = plan(
			"....#",
			"....#",
			"....#");
		assertFalse(PitShape.between(c, 4, 0, 1, 0, 3, 0, 2));
	}

	@Test
	void theInnerCornerOfAnLIsLeft() {
		// an L: a bar along z at x 0..1 and a bar along x at z 0..1; the corner x 2..5, z 2..5 is not dug
		PitShape.Cells c = plan(
			"......",
			"......",
			"..####",
			"..####",
			"..####",
			"..####");
		for (int x = 2; x <= 5; x++) for (int z = 2; z <= 5; z++)
			assertFalse(PitShape.between(c, x, 0, z, 0, 5, 0, 5), "corner " + x + "," + z);
	}

	@Test
	void aGapInTheRimHeWidensIsFilled() {
		// the old pit at x 0..1, the new rim at x 4: the two blocks between are taken
		PitShape.Cells c = plan(
			"..##.",
			"..##.",
			".....");
		assertTrue(PitShape.between(c, 2, 0, 0, 0, 4, 0, 2));
		assertTrue(PitShape.between(c, 3, 0, 1, 0, 4, 0, 2));
	}

	@Test
	void aBlockHePutBackStopsTheLook() {
		// the player set a block back at x 2; the block at x 3 has only rock and that block on its left
		PitShape.Cells c = plan(
			"..F#.");
		assertFalse(PitShape.between(c, 3, 0, 0, 0, 4, 0, 0));
	}

	@Test
	void nothingFurtherThanFiveAway() {
		PitShape.Cells c = plan(
			".######.");
		assertFalse(PitShape.between(c, 1, 0, 0, 0, 7, 0, 0));   // six to the right
		PitShape.Cells d = plan(
			".#####.");
		assertTrue(PitShape.between(d, 1, 0, 0, 0, 6, 0, 0));    // five
	}
}
