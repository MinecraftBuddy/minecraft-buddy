package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Whether the spare shield is made only from the bag's makings (spare_shield_deep): outside the overworld always, on the
 * route's deep stretch with the switch. The bill otherwise climbed to the surface for wood in M3.1 and M3.2.
 */
class SpareShieldDeepTest {
	@Test
	void outsideTheOverworldAlwaysFromTheBag() {
		assertTrue(ObtainSafe.spareShieldBagOnly(false, false, false));
		assertTrue(ObtainSafe.spareShieldBagOnly(true, false, false));
	}

	@Test
	void theSwitchOffLetsTheDeepFetchWood() {
		assertFalse(ObtainSafe.spareShieldBagOnly(false, true, true));
	}

	@Test
	void onTheDeepStretchFromTheBag() {
		assertTrue(ObtainSafe.spareShieldBagOnly(true, true, true));
		// before the diamonds, or off the route: as before
		assertFalse(ObtainSafe.spareShieldBagOnly(true, true, false));
	}
}
