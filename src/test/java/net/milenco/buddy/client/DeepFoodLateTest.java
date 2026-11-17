package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The deep stretch's daytime floor for a food trip to the surface (deep_food_late): 16 points in M3.2 with the switch, 40
 * otherwise. Whole runs went up for food in the middle of the obsidian, 10 to 17 game minutes into the night, and built the cast
 * again where the trip ended: 17 of 28 logged M3.2 losses had such a trip.
 */
class DeepFoodLateTest {
	@Test
	void theSwitchOffKeepsForty() {
		assertEquals(40, ObtainSafe.deepFoodFloor(false, ObtainRoute.M3_2));
		assertEquals(Alto.DEEP_FOOD_FLOOR, ObtainSafe.deepFoodFloor(false, ObtainRoute.M3_1));
	}

	@Test
	void inTheObsidianItIsSixteen() {
		assertEquals(16, ObtainSafe.deepFoodFloor(true, ObtainRoute.M3_2));
		assertEquals(Alto.DEEP_FOOD_LATE, ObtainSafe.deepFoodFloor(true, ObtainRoute.M3_2));
	}

	@Test
	void theOtherDeepPhasesKeepForty() {
		// the diamonds before, the gold and the slime ball after
		assertEquals(40, ObtainSafe.deepFoodFloor(true, ObtainRoute.M3_1));
		assertEquals(40, ObtainSafe.deepFoodFloor(true, ObtainRoute.M3_3));
		assertEquals(40, ObtainSafe.deepFoodFloor(true, ObtainRoute.M3_3B));
	}
}
