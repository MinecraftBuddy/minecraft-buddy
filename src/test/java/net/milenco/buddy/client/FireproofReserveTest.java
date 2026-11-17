package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The nether's food reserve while fire-proof in the rods (fireproof_food_reserve): 12 points with the effect on or a fire
 * resistance potion in the bag on an order of blaze rods, 36 otherwise. Whole runs went home for food at 36 points in the middle
 * of the rods under the brewed potions, 10 of 59, and lost 9 of them.
 */
class FireproofReserveTest {
	@Test
	void theSwitchOffKeepsThirtySix() {
		assertEquals(36, ObtainSafe.netherFoodReserve(false, true, true, 3));
		assertEquals(Alto.NETHER_FOOD_RESERVE, ObtainSafe.netherFoodReserve(false, true, false, 0));
	}

	@Test
	void fireProofInTheRodsIsTwelve() {
		// the potion drunk
		assertEquals(12, ObtainSafe.netherFoodReserve(true, true, true, 0));
		// the brewed potions in the bag, the effect off between two of them
		assertEquals(12, ObtainSafe.netherFoodReserve(true, true, false, 2));
		assertEquals(Alto.FIREPROOF_FOOD_RESERVE, ObtainSafe.netherFoodReserve(true, true, true, 3));
	}

	@Test
	void notFireProofOrNotRodsKeepsThirtySix() {
		// no effect and no potion: the reserve as before
		assertEquals(36, ObtainSafe.netherFoodReserve(true, true, false, 0));
		// another order in the nether, fire-proof or not
		assertEquals(36, ObtainSafe.netherFoodReserve(true, false, true, 3));
	}
}
