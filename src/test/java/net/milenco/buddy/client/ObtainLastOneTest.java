package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** With the whole order in the bag no home run takes over from the way out (box 3 heel-232-mc3 r3: nine rods, 11 points). */
class ObtainLastOneTest {
	@Test
	void theWholeOrderInTheBagHoldsAtAnyFood() {
		assertTrue(Obtain.lastOneFirst(9, 9, 11));
		assertTrue(Obtain.lastOneFirst(9, 9, 0));
		assertTrue(Obtain.lastOneFirst(9, 10, 3));
	}

	@Test
	void oneShortHoldsOnlyWithTwelvePoints() {
		assertTrue(Obtain.lastOneFirst(9, 8, 12));
		assertFalse(Obtain.lastOneFirst(9, 8, 11));
	}

	@Test
	void smallOrdersAndTwoShortGoHome() {
		assertFalse(Obtain.lastOneFirst(3, 3, 0));
		assertFalse(Obtain.lastOneFirst(9, 7, 20));
	}
}
