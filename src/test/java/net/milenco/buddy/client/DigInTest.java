package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The night shelter dug where the buddy stands (shelter_dig_here): which blocks count for the walls and the top, which cells are refused. */
class DigInTest {
	/** A plain full block: no falling, no block entity, no leaves. */
	private static boolean full(String id) { return DigIn.wallBlock(id, false, false, false, true); }

	@Test
	void logsPlanksStoneAndDirtCount() {
		assertTrue(full("oak_log"));
		assertTrue(full("spruce_planks"));
		assertTrue(full("stone"));
		assertTrue(full("cobblestone"));
		assertTrue(full("deepslate"));
		assertTrue(full("dirt"));
		assertTrue(full("moss_block"));
	}

	@Test
	void whatFallsOrIsNoFullBlockDoesNot() {
		assertFalse(DigIn.wallBlock("sand", true, false, false, true));
		assertFalse(DigIn.wallBlock("gravel", true, false, false, true));
		assertFalse(DigIn.wallBlock("oak_leaves", false, false, true, true));
		assertFalse(DigIn.wallBlock("glass_pane", false, false, false, false));
		assertFalse(DigIn.wallBlock("oak_slab", false, false, false, false));
	}

	@Test
	void stationsOresAndStorageBlocksAreKept() {
		assertFalse(full("crafting_table"));
		assertFalse(DigIn.wallBlock("furnace", false, true, false, true));
		assertFalse(DigIn.wallBlock("chest", false, true, false, false));
		assertFalse(DigIn.wallBlock("red_bed", false, true, false, false));
		assertFalse(full("obsidian"));
		assertFalse(full("iron_ore"));
		assertFalse(full("deepslate_diamond_ore"));
		assertFalse(full("iron_block"));
		assertFalse(full("raw_gold_block"));
		assertFalse(full("pumpkin"));
		assertFalse(DigIn.wallBlock(null, false, false, false, true));
	}

	@Test
	void aCellWithGroundAFloorAndDrySidesIsTaken() {
		assertNull(DigIn.refusal(true, true, false, true, true));
	}

	@Test
	void eachRefusalSaysWhy() {
		assertEquals("no ground to dig with the tools at hand", DigIn.refusal(false, true, false, true, true));
		assertEquals("no ground to dig with the tools at hand", DigIn.refusal(true, false, false, true, true));
		assertEquals("water or lava by the hole", DigIn.refusal(true, true, true, true, true));
		assertEquals("a cave or a drop under the hole", DigIn.refusal(true, true, false, false, true));
		assertEquals("the hole's side opens onto a cave", DigIn.refusal(true, true, false, true, false));
	}
	/** (shelter_dig_clock) 15 s with no block dug from the latest of the dig's start, the last block and the last meal. */
	@Test
	void theDigGivesUpAfterFifteenSecondsWithNoProgress() {
		assertFalse(DigIn.digStalled(1000, 0, 0, 1000 + DigIn.DIG_CLOCK_MS));
		assertTrue(DigIn.digStalled(1000, 0, 0, 1001 + DigIn.DIG_CLOCK_MS));
		// a block dug starts the 15 s again
		assertFalse(DigIn.digStalled(1000, 10_000, 0, 20_000));
		assertTrue(DigIn.digStalled(1000, 10_000, 0, 25_001));
		// so does a meal that held the hand
		assertFalse(DigIn.digStalled(1000, 0, 12_000, 20_000));
	}

	/** (shelter_dig_clock) a step to a neighbouring cell and back keeps the spot; two cells off is another one. */
	@Test
	void aStepAsideIsTheSameSpot() {
		assertTrue(DigIn.sameSpot(10, 20, 10, 20));
		assertTrue(DigIn.sameSpot(11, 20, 10, 20));
		assertTrue(DigIn.sameSpot(9, 21, 10, 20));
		assertFalse(DigIn.sameSpot(12, 20, 10, 20));
		assertFalse(DigIn.sameSpot(10, 18, 10, 20));
	}

	/** (meal_hand_hold) a meal holds the hand while the switch is on, for at most the given time. */
	@Test
	void aMealHoldsTheHandForAWhile() {
		assertTrue(DigIn.mealHolds(true, true, 1000, 2000, 8000));
		assertFalse(DigIn.mealHolds(false, true, 1000, 2000, 8000));
		assertFalse(DigIn.mealHolds(true, false, 1000, 2000, 8000));
		assertFalse(DigIn.mealHolds(true, true, 1000, 9000, 8000));
	}

	/** (shelter_refused_walls) a dig that stopped short on open ground shuts in on the spot; not off, not ducking, not under a top. */
	@Test
	void aDigStoppedShortOnOpenGroundShutsInOnTheSpot() {
		assertTrue(DigIn.shutOnSpot(true, true, false, true));
		assertFalse(DigIn.shutOnSpot(false, true, false, true));
		assertFalse(DigIn.shutOnSpot(true, false, false, true));
		assertFalse(DigIn.shutOnSpot(true, true, true, true));
		assertFalse(DigIn.shutOnSpot(true, true, false, false));
	}

	/**
	 * (shelter_place_face) down after all only where the dig runs: a furnace beside the cell over the head made the ground not
	 * open, the dig never ran, and the shelter went round between the two.
	 */
	@Test
	void downAfterAllOnlyWhereTheDigRuns() {
		// the old rule: the top refused, nothing dug, ground to dig
		assertTrue(DigIn.downAfterAll(false, false, false, true, 0, true, false));
		// with the face rule, only where the dig below runs
		assertFalse(DigIn.downAfterAll(true, false, false, true, 0, true, false));
		assertTrue(DigIn.downAfterAll(true, false, false, true, 0, true, true));
		// never for a wall, after a dig, ducking, after the dig's clock, or with no ground to dig
		assertFalse(DigIn.downAfterAll(true, false, false, false, 0, true, true));
		assertFalse(DigIn.downAfterAll(true, false, false, true, 1, true, true));
		assertFalse(DigIn.downAfterAll(true, true, false, true, 0, true, true));
		assertFalse(DigIn.downAfterAll(true, false, true, true, 0, true, true));
		assertFalse(DigIn.downAfterAll(true, false, false, true, 0, false, true));
	}
}
