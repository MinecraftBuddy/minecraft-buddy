package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * (night_route_m34) The route's last stretch to the nether at night: where the night's rules hold whatever the armour, which
 * pieces wait for the morning, and the clock the wait's lines say.
 */
class RouteNightTest {
	private static boolean stretch(int phase) {
		return ObtainRoute.nightStretch(true, true, true, phase, true, false, false);
	}

	@Test
	void stretchIsTheSlimesPhaseAndThePortals() {
		assertTrue(stretch(ObtainRoute.M3_3B));
		assertTrue(stretch(ObtainRoute.M3_4));
		assertFalse(stretch(ObtainRoute.M3_3), "the gold's phase keeps its rules");
		assertFalse(stretch(ObtainRoute.M3_1));
		assertFalse(stretch(ObtainRoute.M1_1));
	}

	@Test
	void stretchNeedsTheSwitchAnOrderTheRouteAndTheOverworld() {
		assertFalse(ObtainRoute.nightStretch(false, true, true, ObtainRoute.M3_4, true, false, false), "switch off");
		assertFalse(ObtainRoute.nightStretch(true, false, true, ObtainRoute.M3_4, true, false, false), "no order under way");
		assertFalse(ObtainRoute.nightStretch(true, true, false, ObtainRoute.M3_4, true, false, false), "the trip home from the nether");
		assertFalse(ObtainRoute.nightStretch(true, true, true, ObtainRoute.M3_4, false, false, false), "out of the overworld");
	}

	@Test
	void stretchLeavesABedNightAndTheSlimeSearch() {
		assertFalse(ObtainRoute.nightStretch(true, true, true, ObtainRoute.M3_4, true, true, false), "a bed tonight");
		assertFalse(ObtainRoute.nightStretch(true, true, true, ObtainRoute.M3_3B, true, false, true), "the slime search runs on");
	}

	private static ObtainRoute.Piece piece(String key) {
		ObtainRoute.Piece pc = ObtainRoute.piece(key);
		assertNotNull(pc, key);
		return pc;
	}

	@Test
	void surfacePiecesOfThePortalsPhaseWait() {
		ObtainRouteTest.Kit b = new ObtainRouteTest.Kit();
		for (String k : new String[] { "m3.4/crafting table", "m3.4/crafting table last", "m3.4/bed", "m3.4/water bottles", "m3.4/food" })
			assertTrue(ObtainRoute.waitsForMorning(piece(k), b, false), k);
	}

	@Test
	void piecesBelowTheGroundGoOn() {
		ObtainRouteTest.Kit b = new ObtainRouteTest.Kit();
		for (String k : new String[] { "m3.4/obsidian", "m3.4/diamonds", "m3.4/gold", "m3.4/iron pickaxes", "m3.4/redstone",
				"m3.4/flint and steel", "m3.4/rest", "m3.4/golden boots", "m3.4/raw gold", "m3.4/water bucket" })
			assertFalse(ObtainRoute.waitsForMorning(piece(k), b, false), k);
	}

	@Test
	void otherPhasesNeverWait() {
		ObtainRouteTest.Kit b = new ObtainRouteTest.Kit();
		assertFalse(ObtainRoute.waitsForMorning(piece("m3.3b/slime ball"), b, false), "the slime search");
		assertFalse(ObtainRoute.waitsForMorning(piece("m3.3b/golden boots again"), b, false));
		assertFalse(ObtainRoute.waitsForMorning(piece("m1.3/food"), b, false), "the first food has its own rules");
		assertFalse(ObtainRoute.waitsForMorning(piece("m1.3/bed"), b, false));
	}

	@Test
	void foodGoesWhenHurtWithNothingToEat() {
		ObtainRouteTest.Kit b = new ObtainRouteTest.Kit();
		assertTrue(ObtainRoute.waitsForMorning(piece("m3.4/food"), b, false));
		assertFalse(ObtainRoute.waitsForMorning(piece("m3.4/food"), b, true));
		assertTrue(ObtainRoute.waitsForMorning(piece("m3.4/bed"), b, true), "only the food");
	}

	@Test
	void sticksWaitOnlyWhenTheBagsWoodIsShort() {
		ObtainRouteTest.Kit b = new ObtainRouteTest.Kit();
		b.netherSticks = 16;
		// sixteen sticks: four crafts of two planks
		assertEquals(8, ObtainRoute.sticksWood(b));
		assertTrue(ObtainRoute.waitsForMorning(piece("m3.4/sticks"), b, false), "no wood: a log fetched");
		b.give("oak_planks", 7);
		assertTrue(ObtainRoute.waitsForMorning(piece("m3.4/sticks"), b, false), "seven planks are short");
		b.give("oak_planks", 1);
		assertFalse(ObtainRoute.waitsForMorning(piece("m3.4/sticks"), b, false), "eight planks: a craft");
		ObtainRouteTest.Kit logs = new ObtainRouteTest.Kit().give("spruce_log", 2);
		logs.netherSticks = 16;
		assertFalse(ObtainRoute.waitsForMorning(piece("m3.4/sticks"), logs, false), "two logs are eight planks");
		ObtainRouteTest.Kit some = new ObtainRouteTest.Kit().give("stick", 13);
		some.netherSticks = 16;
		assertEquals(2, ObtainRoute.sticksWood(some), "three more sticks: one craft");
	}

	@Test
	void clockReadsTheDayAsAClock() {
		assertEquals("06:00", ObtainRoute.clock(0));
		assertEquals("17:30", ObtainRoute.clock(11500));
		assertEquals("18:30", ObtainRoute.clock(12510));
		assertEquals("00:00", ObtainRoute.clock(18000));
		assertEquals("05:59", ObtainRoute.clock(23999));
		assertEquals("07:00", ObtainRoute.clock(24000 * 3 + 1000), "a later day");
		assertEquals("05:00", ObtainRoute.clock(-1000));
	}

	// (food_top_deep) one food run before the obsidian: at the diamonds or the obsidian, none in the bag, under 85, once
	@Test
	void topUpOnlyBeforeTheObsidian() {
		assertTrue(ObtainRoute.topUpDue(true, ObtainRoute.M3_1, false, 0, 64));
		assertTrue(ObtainRoute.topUpDue(true, ObtainRoute.M3_2, false, 0, 84));
		assertFalse(ObtainRoute.topUpDue(true, ObtainRoute.M3_2, false, 0, 85), "enough in the bag");
		assertFalse(ObtainRoute.topUpDue(true, ObtainRoute.M3_2, false, 3, 40), "the obsidian begun");
		assertFalse(ObtainRoute.topUpDue(true, ObtainRoute.M3_2, true, 0, 40), "spent this order");
		assertFalse(ObtainRoute.topUpDue(true, ObtainRoute.M2_2, false, 0, 40), "before the deep");
		assertFalse(ObtainRoute.topUpDue(true, ObtainRoute.M3_3, false, 0, 40), "after the obsidian");
		assertFalse(ObtainRoute.topUpDue(false, ObtainRoute.M3_2, false, 0, 40), "not on the route");
	}
}
