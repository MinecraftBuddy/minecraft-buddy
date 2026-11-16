package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The junk rule in the nether (nether_keep_rack): 96 of netherrack, blackstone and basalt stay, netherrack counted first, and
 * the barter's junk goes first. An M4 run threw 113 netherrack away in the piglin trade by the rule of 96 blocks of stone and
 * the like, and the brew's pillbox starved with 9 blocks for 15.
 */
class NetherKeepTest {
	@Test
	void theTradesTwoStacksOfNetherrackStay() {
		assertArrayEquals(new boolean[] {true, true, false, false},
			ObtainSafe.netherKept(new String[] {"netherrack", "netherrack", "cobblestone", "basalt"}, new int[] {64, 49, 12, 25}));
		// a third stack is past the keep
		assertArrayEquals(new boolean[] {true, true, false},
			ObtainSafe.netherKept(new String[] {"netherrack", "netherrack", "netherrack"}, new int[] {64, 64, 10}));
	}

	@Test
	void netherrackIsCountedFirstThenBlackstoneThenBasalt() {
		assertArrayEquals(new boolean[] {false, true, true, false},
			ObtainSafe.netherKept(new String[] {"basalt", "blackstone", "netherrack", ""}, new int[] {40, 40, 64, 0}));
		assertArrayEquals(new boolean[] {true, true},
			ObtainSafe.netherKept(new String[] {"basalt", "netherrack"}, new int[] {25, 20}));
		assertEquals(96, ObtainSafe.NETHER_KEEP);
	}

	@Test
	void theBartersJunkIsKnown() {
		assertTrue(ObtainSafe.BARTER_JUNK.contains("soul_sand"));
		assertTrue(ObtainSafe.BARTER_JUNK.contains("gravel"));
		assertTrue(ObtainSafe.BARTER_JUNK.contains("nether_brick"));
		assertEquals(-1, ObtainSafe.netherKeepOrder("nether_bricks"));
		assertEquals(0, ObtainSafe.netherKeepOrder("netherrack"));
	}
}
