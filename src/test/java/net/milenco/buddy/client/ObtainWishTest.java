package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * What is worth taking along on the way: the kit's needs and the order in tiers, and the planner's prices for one raw
 * item. Soprano turns the items into raw items itself (its bill), so these hold the items and counts handed over.
 */
class ObtainWishTest {
	private static ObtainWish.Wishes deep(ObtainPlan.Have w) {
		return ObtainWish.of(ObtainKit.missingAll(ObtainKit.Danger.DEEP, w), "diamond", 3, null, 0, false, List.of(), w);
	}

	@Test
	void theDeepKitsGearIsNowItsStocksLater() {
		ObtainPlanTest.World w = new ObtainPlanTest.World();
		ObtainWish.Wishes x = deep(w);
		for (String piece : List.of("iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots", "shield", "iron_sword"))
			assertEquals(1, x.now().get(piece), piece);
		assertEquals(16, x.later().get("torch"));
		assertEquals(1, x.later().get("water_bucket"));
		assertEquals(2, x.later().get("stone_pickaxe"));
		// the order is later, by its bag count
		assertEquals(3, x.later().get("diamond"));
		// food stays with food on the way, blocks are no raw item of ours
		assertFalse(x.now().containsKey("cobblestone") || x.later().containsKey("cobblestone"));
		for (String food : ObtainKit.FOOD) assertFalse(x.now().containsKey(food) || x.later().containsKey(food), food);
	}

	@Test
	void theNetherKitsDiamondSwordIsANeedOfItsGate() {
		ObtainPlanTest.World w = new ObtainPlanTest.World();
		ObtainWish.Wishes x = ObtainWish.of(ObtainKit.missingAll(ObtainKit.Danger.NETHER, w), "blaze_rod", 7, null, 0, false, List.of(), w);
		assertEquals(1, x.now().get("diamond_sword"));
		assertEquals(1, x.now().get("golden_helmet"));
		// the spare helmet is a stock: the bag count of two, taken as the larger of the two tiers by Soprano
		assertEquals(2, x.later().get("golden_helmet"));
		assertEquals(1, x.later().get("flint_and_steel"));
	}

	@Test
	void armourAlreadyWornIsNoWish() {
		ObtainPlan.Have w = new ObtainPlan.Have() {
			public int inv(String item) { return 0; }
			public int worn(String item) { return item.equals("iron_chestplate") ? 1 : 0; }
		};
		ObtainWish.Wishes x = deep(w);
		assertNull(x.now().get("iron_chestplate"));
		assertEquals(1, x.now().get("iron_helmet"));
	}

	@Test
	void stocksCountWhatTheBagHolds() {
		ObtainPlanTest.World w = new ObtainPlanTest.World();
		w.add("torch", 5);
		// the kit's torches are 16; at 12 it is enough, at 5 it fetches the eleven missing on top of the bag's five
		assertEquals(16, deep(w).later().get("torch"));
	}

	@Test
	void theTableAndPlanksAndTheBuddysOwnStepAreNow() {
		ObtainPlanTest.World w = new ObtainPlanTest.World();
		w.add("raw_iron", 2);
		ObtainWish.Wishes x = ObtainWish.of(List.of(), "iron_pickaxe", 1, "raw_iron", 3, true, List.of("copper_chestplate"), w);
		assertEquals(1, x.now().get("crafting_table"));
		assertEquals(20, x.now().get("planks"));
		// copper for an empty slot whose iron is not in the bag yet, as the kit makes it
		assertEquals(1, x.now().get("copper_chestplate"));
		// the step makes three more on top of the two in the bag
		assertEquals(5, x.now().get("raw_iron"));
		assertEquals(1, x.later().get("iron_pickaxe"));
		// a place is no item
		assertTrue(ObtainWish.of(List.of(), null, 0, "@nether", 1, false, List.of(), w).now().isEmpty());
	}

	@Test
	void aRawItemIsWorthWhatComingBackForItCosts() {
		ObtainPlanTest.World w = new ObtainPlanTest.World();
		Map<String, Double> v = ObtainWish.values(w);
		// minutes at the most, never the planner's "no way" (one ledger for all of them once priced raw iron at a billion)
		for (String raw : List.of("coal", "raw_iron", "raw_gold", "diamond", "redstone", "flint", "log"))
			assertTrue(v.containsKey(raw) && v.get(raw) > 0 && v.get(raw) < 1000, raw + " " + v);
		// the deeper and the rarer, the more it is worth; a flint from gravel is worth ten gravel
		assertTrue(v.get("diamond") > v.get("raw_gold"), v.toString());
		assertTrue(v.get("raw_gold") > v.get("raw_iron"), v.toString());
		assertTrue(v.get("raw_iron") > v.get("coal"), v.toString());
		assertTrue(v.get("flint") > v.get("coal"), v.toString());
	}

	@Test
	void aSeenOreDoesNotMakeItCheaper() {
		ObtainPlanTest.World w = new ObtainPlanTest.World();
		double before = ObtainWish.values(w).get("raw_iron");
		w.near.put("iron_ore", 3.0);
		w.near.put("deepslate_iron_ore", 3.0);
		assertEquals(before, ObtainWish.values(w).get("raw_iron"));
	}

	@Test
	void aPickInTheBagTakesTheToolsShareOff() {
		ObtainPlanTest.World bare = new ObtainPlanTest.World(), picked = new ObtainPlanTest.World();
		picked.add("iron_pickaxe", 1);
		assertTrue(ObtainWish.values(picked).get("diamond") < ObtainWish.values(bare).get("diamond"));
	}
}
