package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The armour the rules on 15 count: golden boots, the kit's piece for the piglins worn in place of the iron ones (an iron
 * helmet, chestplate and leggings and golden boots, 14), count as the iron boots they stand in for.
 */
class KitArmourTest {
	@Test
	void goldenBootsCountAsIron() {
		assertEquals(15, ObtainSafe.kitArmour(14, true));
		assertEquals(15, ObtainSafe.kitArmour(15, false));
		assertEquals(14, ObtainSafe.kitArmour(14, false));
		assertEquals(1, ObtainSafe.kitArmour(0, false) + 1);
	}
}
