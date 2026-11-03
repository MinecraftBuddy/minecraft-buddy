package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Whether a jump may go wrong. The first two tests are a cave of a real world in which the buddy once died, read from
 * its region file and moved to made-up coordinates: a slice three blocks wide (x -1000 to -998) around the line they
 * jumped along, every row a height, every character a block along z ({@code #} a block, {@code L} lava, {@code W}
 * water, {@code .} nothing to stand on).
 */
class MissTest {
	private static final String[][] CAVE = {
		// x -1000, y 10 down to -18, z -1000 to -979
		{
			"#####################.",
			"####################..",
			"####################..",
			"###################...",
			"##################....",
			"##################....",
			"#######...............",
			"######...............#",
			"####................##",
			"##..................##",
			".............##L#.####",
			"...............#######",
			".................#####",
			"...................###",
			".....................#",
			"......................",
			"......................",
			"......................",
			"......................",
			"......................",
			"......................",
			"......................",
			"......................",
			"#.....................",
			"#.....................",
			"####..................",
			"######................",
			"########..............",
			"##########............",
		},
		// x -999, y 10 down to -18, z -1000 to -979
		{
			"######################",
			"######################",
			"#####################.",
			"#####################.",
			"####################..",
			"###################...",
			"#########.......##....",
			"######................",
			"###..................#",
			"##.............L....##",
			"........######LL######",
			".............#########",
			"................######",
			"...#..............####",
			"###.................##",
			"##...................#",
			"#.....................",
			"......................",
			"......................",
			"......................",
			"......................",
			"#.....................",
			"......................",
			"......................",
			"#.....................",
			"###...................",
			"#####.................",
			"#######...............",
			"##########............",
		},
		// x -998, y 10 down to -18, z -1000 to -979
		{
			"######################",
			"######################",
			"######################",
			"######################",
			"#####################.",
			"####################..",
			"############..####....",
			"##########......##....",
			"######..##.....L.....W",
			"###.....####...L####WW",
			"#.....##############W#",
			".....#################",
			"...#####......########",
			".#####...........#####",
			"#####..............###",
			"####................##",
			"###...................",
			"##....................",
			"#.....................",
			"#.....................",
			"##....................",
			"......................",
			"......................",
			"......................",
			"..#...................",
			"###...................",
			"###...................",
			"######................",
			"#########.............",
		},
	};

	private static Miss.Block cave(int x, int y, int z) {
		int i = x + 1000, row = 10 - y, col = z + 1000;
		if (row > 28 && i >= 0 && i <= 2 && col >= 0 && col <= 21) return Miss.solid(1);   // the deepslate goes on down
		if (i < 0 || i > 2 || row < 0 || row > 28 || col < 0 || col > 21)
			throw new AssertionError("outside the slice: " + x + " " + y + " " + z);
		return block(CAVE[i][row].charAt(col));
	}

	private static Miss.Block block(char c) {
		return switch (c) {
			case '#' -> Miss.solid(1);
			case 'L' -> Miss.HAZARD_BLOCK;
			case 'W' -> Miss.WATER_BLOCK;
			default -> Miss.AIR_BLOCK;
		};
	}

	@Test
	void notOverThePoolOfLava() {
		// from the floor at z -983 over the lava at z -986 and -985 to the floor at z -987, all at feet height 1:
		// the buddy came down in the lava (20:04:33)
		assertFalse(Miss.safe(MissTest::cave, -999, -983, 1, -999, -987, 1, Miss.maxFall(20), false));
	}

	@Test
	void notOntoTheBlockAboveTheCave() {
		// from the floor at z -991, feet 1, to the lone block at -999 -3 -997, feet -2, five on and three down over a
		// cave whose floor is at -15: the buddy missed it and fell sixteen blocks (20:04:37)
		assertFalse(Miss.safe(MissTest::cave, -999, -991, 1, -999, -997, -2, Miss.maxFall(20), false));
	}

	/** A floor of stone at feet height 0 from z 0 to 1 and from z 4 on, and under the gap {@code under} at that y. */
	private static Miss.Probe gap(int gapY, Miss.Block under) {
		return (x, y, z) -> {
			if (y == -1 && (z <= 1 || z >= 4)) return Miss.solid(1);
			if (y == gapY && z >= 2 && z <= 3) return under;
			return Miss.AIR_BLOCK;
		};
	}

	@Test
	void overAShallowGap() {
		// a floor two below the gap: a miss is a drop
		assertTrue(Miss.safe(gap(-3, Miss.solid(1)), 0, 1, 0, 0, 4, 0, Miss.maxFall(20), false));
	}

	@Test
	void notOverLavaAtTheBottom() {
		assertFalse(Miss.safe(gap(-3, Miss.HAZARD_BLOCK), 0, 1, 0, 0, 4, 0, Miss.maxFall(20), false));
	}

	@Test
	void overWaterHoweverDeep() {
		assertTrue(Miss.safe(gap(-30, Miss.WATER_BLOCK), 0, 1, 0, 0, 4, 0, Miss.maxFall(20), false));
	}

	@Test
	void theDepthGoesByHealth() {
		// the floor's top five below the feet: from the top of the jump 6.25, which full health takes (7) and nine
		// hearts' worth does not (6)
		Miss.Probe five = gap(-6, Miss.solid(1));
		assertTrue(Miss.safe(five, 0, 1, 0, 0, 4, 0, Miss.maxFall(20), false));
		assertFalse(Miss.safe(five, 0, 1, 0, 0, 4, 0, Miss.maxFall(9), false));
		// ten below is too deep at any health
		assertFalse(Miss.safe(gap(-11, Miss.solid(1)), 0, 1, 0, 0, 4, 0, Miss.maxFall(20), false));
	}

	@Test
	void theVoidOnlyOnTheCourse() {
		Miss.Probe none = gap(-200, Miss.AIR_BLOCK);
		assertFalse(Miss.safe(none, 0, 1, 0, 0, 4, 0, Miss.maxFall(20), false));
		assertTrue(Miss.safe(none, 0, 1, 0, 0, 4, 0, Miss.maxFall(20), true));
		// lava beside the course is never all right
		Miss.Probe lavaBeside = (x, y, z) -> x == 1 && y == 0 && z == 3 ? Miss.HAZARD_BLOCK : none.at(x, y, z);
		assertFalse(Miss.safe(lavaBeside, 0, 1, 0, 0, 4, 0, Miss.maxFall(20), true));
	}

	private static boolean has(List<int[]> cols, int x, int z) {
		for (int[] c : cols) if (c[0] == x && c[1] == z) return true;
		return false;
	}

	@Test
	void theColumnsALineCoversAndOneBeyond() {
		// straight on along z from cell 0 0 to 0 4: the line and a block beyond the landing, beside it at the landing,
		// and of the take-off only that cell: a cliff behind it or beside the spot jumped from rules nothing out
		List<int[]> cols = Miss.columns(0, 0, 0, 4);
		for (int z = 0; z <= 6; z++) assertTrue(has(cols, 0, z), "z " + z);
		assertTrue(has(cols, -1, 4) && has(cols, 1, 4) && has(cols, -1, 5) && has(cols, 1, 5));
		assertFalse(has(cols, 0, -1));
		assertFalse(has(cols, -1, 0) || has(cols, 1, 0));
		for (int[] c : cols) assertTrue(c[0] >= -1 && c[0] <= 1 && c[1] >= 0 && c[1] <= 6, c[0] + " " + c[1]);
	}

	@Test
	void healthToFall() {
		assertEquals(7, Miss.maxFall(20));
		assertEquals(7, Miss.maxFall(10));
		assertEquals(6, Miss.maxFall(9));
		assertEquals(3, Miss.maxFall(5));
	}
}
