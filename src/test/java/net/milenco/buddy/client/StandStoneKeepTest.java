package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The full-bag throw past 96 blocks (stand_stone_junk_keep): the brewing stand's stone in all its kinds stays as cobblestone
 * did, and above a stack its smallest stack goes. A stack of cobbled deepslate, the only stand stone in the bag, was thrown
 * in the nether and the brew found 0 of 3.
 */
class StandStoneKeepTest {
	private static final boolean[] ALL = {true, true, true, true};

	@Test
	void deepslateStaysLikeCobblestone() {
		String[] ids = {"cobbled_deepslate", "netherrack", "dirt", ""};
		int[] counts = {53, 64, 30, 0};
		boolean[] eligible = {true, true, true, false};
		// the first stack that is no stand stone goes
		assertEquals(1, ObtainSafe.overflowSlot(ids, counts, eligible, true));
		// with nothing else to throw the deepslate stays
		assertEquals(-1, ObtainSafe.overflowSlot(ids, counts, new boolean[] {true, false, false, false}, true));
		// without the switch it went first, as before
		assertEquals(0, ObtainSafe.overflowSlot(ids, counts, eligible, false));
	}

	@Test
	void aboveAStackTheSmallestGoes() {
		String[] ids = {"cobbled_deepslate", "cobblestone", "blackstone", "cobblestone"};
		int[] counts = {64, 2, 30, 5};
		assertEquals(1, ObtainSafe.overflowSlot(ids, counts, ALL, true));
		// one stack or less of stand stone: nothing goes
		assertEquals(-1, ObtainSafe.overflowSlot(new String[] {"cobblestone", "blackstone", "", ""}, new int[] {40, 20, 0, 0},
			new boolean[] {true, true, false, false}, true));
	}

	@Test
	void withoutTheSwitchTheLastCobblestoneGoesAsBefore() {
		String[] ids = {"cobblestone", "cobblestone", "cobblestone", "blackstone"};
		int[] counts = {64, 10, 64, 3};
		// blackstone is no kit stone without the switch: it goes first
		assertEquals(3, ObtainSafe.overflowSlot(ids, counts, ALL, false));
		assertEquals(2, ObtainSafe.overflowSlot(ids, counts, new boolean[] {true, true, true, false}, false));
	}
}
