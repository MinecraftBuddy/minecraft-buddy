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

	// ---- (shelter_blocks_first) blocks from the ground first, (shelter_hand_dig) a dig-in by hand elsewhere

	@Test
	void grassAndDirtAreTheHandsGround() {
		assertTrue(DigIn.handGround("grass_block"));
		assertTrue(DigIn.handGround("dirt"));
		assertTrue(DigIn.handGround("coarse_dirt"));
		assertTrue(DigIn.handGround("podzol"));
		assertTrue(DigIn.handGround("mycelium"));
		// stone drops nothing by hand; sand and gravel fall; a path or a farm is a player's
		assertFalse(DigIn.handGround("stone"));
		assertFalse(DigIn.handGround("sand"));
		assertFalse(DigIn.handGround("gravel"));
		assertFalse(DigIn.handGround("farmland"));
		assertFalse(DigIn.handGround("dirt_path"));
		assertFalse(DigIn.handGround(null));
	}

	@Test
	void blocksFirstWithFewBlocksAndNoHoleToDig() {
		// test heel's night start: an empty bag, grass over stone
		assertTrue(DigIn.blocksFirst(true, 0, false, false));
		assertTrue(DigIn.blocksFirst(true, DigIn.BLOCKS_FIRST_UNDER - 1, false, false));
		assertFalse(DigIn.blocksFirst(true, DigIn.BLOCKS_FIRST_UNDER, false, false));
		// a hole to dig here or near digs its own blocks
		assertFalse(DigIn.blocksFirst(true, 0, false, true));
		// a monster about: the shelter at once
		assertFalse(DigIn.blocksFirst(true, 0, true, false));
		assertFalse(DigIn.blocksFirst(false, 0, false, false));
	}

	@Test
	void theCellsBrokenForBlocksKeepTheWallsGround() {
		java.util.List<int[]> none = java.util.List.of();
		// not the standing cell nor its four sides
		assertFalse(DigIn.gatherCell(0, 0, none));
		assertFalse(DigIn.gatherCell(1, 0, none));
		assertFalse(DigIn.gatherCell(0, -1, none));
		// a corner, and up to three off
		assertTrue(DigIn.gatherCell(1, 1, none));
		assertTrue(DigIn.gatherCell(-3, 3, none));
		assertFalse(DigIn.gatherCell(4, 0, none));
		// never beside a cell broken already, a corner of one is fine
		java.util.List<int[]> one = java.util.List.of(new int[] {2, 1});
		assertFalse(DigIn.gatherCell(2, 0, one));
		assertFalse(DigIn.gatherCell(3, 1, one));
		assertTrue(DigIn.gatherCell(3, 2, one));
	}

	@Test
	void blocksFirstEndsOnItsCellsItsBlocksOrItsTime() {
		assertFalse(DigIn.gatherDone(0, 0, 0, 1000));
		assertTrue(DigIn.gatherDone(DigIn.GATHER_CELLS, 0, 0, 1000));
		assertTrue(DigIn.gatherDone(2, DigIn.GATHER_ENOUGH, 0, 1000));
		assertTrue(DigIn.gatherDone(2, 3, 0, DigIn.GATHER_MS + 1));
		assertFalse(DigIn.gatherDone(DigIn.GATHER_CELLS - 1, DigIn.GATHER_ENOUGH - 1, 0, DigIn.GATHER_MS));
	}

	@Test
	void aDigInByHandElsewhereOnlyWithAnEmptyBag() {
		assertTrue(DigIn.handDig(true, true, false, 0, 0));
		assertTrue(DigIn.handDig(true, true, false, 0, 2));
		assertFalse(DigIn.handDig(true, true, false, 1, 0));
		assertFalse(DigIn.handDig(true, true, false, 0, 3));
		// from shooters the shelter ducks where it stands
		assertFalse(DigIn.handDig(true, true, true, 0, 0));
		// the dig-in's own switch does the digging
		assertFalse(DigIn.handDig(true, false, false, 0, 0));
		assertFalse(DigIn.handDig(false, true, false, 0, 0));
	}

	// (shelter_stuck_way_out) a live look at the test heel: three tries elsewhere spent, 12 logs in the bag, a wait half shut
	@Test
	void onceTheTriesElsewhereAreSpentTheHandDigWithBlocksToo() {
		assertFalse(DigIn.handDig(true, true, false, 12, 0, DigIn.triesSpent(true, 2)));
		assertTrue(DigIn.handDig(true, true, false, 12, 0, DigIn.triesSpent(true, 3)));
		assertTrue(DigIn.handDig(true, true, false, 12, 2, DigIn.triesSpent(true, 3)));
		assertFalse(DigIn.handDig(true, true, false, 12, 3, DigIn.triesSpent(true, 3)));
		// switched off it is the empty bag only, as before
		assertFalse(DigIn.handDig(true, true, false, 12, 0, DigIn.triesSpent(false, 3)));
		assertTrue(DigIn.handDig(true, true, false, 0, 0, DigIn.triesSpent(false, 3)));
		// from shooters still none
		assertFalse(DigIn.handDig(true, true, true, 12, 0, DigIn.triesSpent(true, 3)));
	}

	@Test
	void aTryElsewhereKeepsTheShutInOnTheSpotWhileTheFeetStay() {
		assertTrue(DigIn.keepNoDig(true, true, false));
		// a walk to a dig-in by hand starts afresh
		assertFalse(DigIn.keepNoDig(true, true, true));
		assertFalse(DigIn.keepNoDig(true, false, false));
		assertFalse(DigIn.keepNoDig(false, true, false));
		assertTrue(DigIn.feetStayed(10, -4, 10, -4));
		assertFalse(DigIn.feetStayed(11, -4, 10, -4));
		assertFalse(DigIn.feetStayed(10, -3, 10, -4));
	}

	// (shelter_down_from_tree) a live look at the test heel: a bare start at night on oak leaves at y 75, the floor at 66 to 70
	@Test
	void fromTheCanopyDownFirst() {
		assertTrue(DigIn.downFromTree(true, false, false, 0, false, true, 75, 68));
		// on a log too, and with no ground found under the column
		assertTrue(DigIn.downFromTree(true, false, false, 0, false, true, 70, null));
		// more than four over the ground on anything, not on four
		assertTrue(DigIn.downFromTree(true, false, false, 0, false, false, 75, 70));
		assertFalse(DigIn.downFromTree(true, false, false, 0, false, false, 74, 70));
		// on the ground, or on a bush of one block, stays
		assertFalse(DigIn.downFromTree(true, false, false, 0, false, false, 68, 68));
		assertFalse(DigIn.downFromTree(true, false, false, 0, false, true, 69, 68));
		assertTrue(DigIn.downFromTree(true, false, false, 0, false, true, 70, 68));
		// under an overhang the column's ground lies over the head
		assertFalse(DigIn.downFromTree(true, false, false, 0, false, false, 60, 80));
	}

	@Test
	void noWalkDownOnceTheShelterIsUnderWay() {
		assertFalse(DigIn.downFromTree(true, true, false, 0, false, true, 75, 68));
		// ducking from shooters: where it stands
		assertFalse(DigIn.downFromTree(true, false, true, 0, false, true, 75, 68));
		assertFalse(DigIn.downFromTree(true, false, false, 1, false, true, 75, 68));
		assertFalse(DigIn.downFromTree(true, false, false, 0, true, true, 75, 68));
		assertFalse(DigIn.downFromTree(false, false, false, 0, false, true, 75, 68));
	}

	@Test
	void sitesAreMeasuredFromTheGroundUnderTheFeet() {
		// the canopy: from the floor
		assertEquals(68, DigIn.siteBase(true, 75, 68));
		// the ground: the feet; an overhang over the head, or none found: the feet
		assertEquals(68, DigIn.siteBase(true, 68, 68));
		assertEquals(60, DigIn.siteBase(true, 60, 80));
		assertEquals(75, DigIn.siteBase(true, 75, null));
		// switched off: the feet, as before
		assertEquals(75, DigIn.siteBase(false, 75, 68));
	}

	@Test
	void noShutInOnTheSpotWithNoBlockAndNothingToDig() {
		assertTrue(DigIn.shutOnSpotPointless(true, 0, false));
		assertFalse(DigIn.shutOnSpotPointless(true, 1, false));
		assertFalse(DigIn.shutOnSpotPointless(true, 0, true));
		assertFalse(DigIn.shutOnSpotPointless(false, 0, false));
	}

	// (shelter_logs_first) a live look at the test heel: a night start with an empty bag on bare stone, logs 10 to 13 below
	@Test
	void logsWhenTheGroundGaveTooFew() {
		assertTrue(DigIn.logsAfterGather(true, 0, false, 0));
		assertTrue(DigIn.logsAfterGather(true, 4, false, 1));
		// blocks enough, a monster cut it, no step left, or switched off
		assertFalse(DigIn.logsAfterGather(true, 5, false, 0));
		assertFalse(DigIn.logsAfterGather(true, 0, true, 0));
		assertFalse(DigIn.logsAfterGather(true, 0, false, DigIn.LOG_RUNS));
		assertFalse(DigIn.logsAfterGather(false, 0, false, 0));
	}

	@Test
	void logsInPlaceOfTheWaitInTheOpen() {
		assertTrue(DigIn.logsInsteadOfWait(true, 0, false, true, true, false, 0));
		// the tries not spent, a walk by hand still to be had, a monster about, ducking, blocks enough: not yet or not at all
		assertFalse(DigIn.logsInsteadOfWait(true, 0, false, false, true, false, 0));
		assertFalse(DigIn.logsInsteadOfWait(true, 0, false, true, false, false, 0));
		assertFalse(DigIn.logsInsteadOfWait(true, 0, false, true, true, true, 0));
		assertFalse(DigIn.logsInsteadOfWait(true, 0, true, true, true, false, 0));
		assertFalse(DigIn.logsInsteadOfWait(true, 9, false, true, true, false, 0));
		assertFalse(DigIn.logsInsteadOfWait(true, 0, false, true, true, false, DigIn.LOG_RUNS));
		assertFalse(DigIn.logsInsteadOfWait(false, 0, false, true, true, false, 0));
	}

	@Test
	void threeLogsAreTwelvePlanks() {
		assertFalse(DigIn.logsDone(2, 0, 0, 1000));
		assertTrue(DigIn.logsDone(3, 0, 0, 1000));
		assertTrue(DigIn.logsDone(0, DigIn.GATHER_ENOUGH, 0, 1000));
		assertTrue(DigIn.logsDone(0, 0, 0, DigIn.LOG_MS + 1));
		assertEquals(3, DigIn.plankCrafts(7));
		assertEquals(2, DigIn.plankCrafts(2));
		assertEquals(0, DigIn.plankCrafts(0));
		assertEquals("oak_planks", DigIn.planksOf("oak_log"));
		assertEquals("dark_oak_planks", DigIn.planksOf("stripped_dark_oak_wood"));
		assertEquals("pale_oak_planks", DigIn.planksOf("pale_oak_log"));
		assertEquals("crimson_planks", DigIn.planksOf("crimson_stem"));
		assertEquals("warped_planks", DigIn.planksOf("stripped_warped_hyphae"));
		assertNull(DigIn.planksOf("dirt"));
		assertNull(DigIn.planksOf(null));
	}

	@Test
	void theNearLogLowInItsTrunkFirst() {
		// the foot of a trunk 5 off before its third log, and before a log 5 off and 12 below
		assertTrue(DigIn.logScore(5, 0, 0, 0) < DigIn.logScore(5, 2, 0, 2));
		assertTrue(DigIn.logScore(5, 0, 0, 0) < DigIn.logScore(5, -12, 0, 0));
		// with nothing on the level, the logs below are taken
		assertTrue(DigIn.logScore(20, -12, 0, 0) < Double.MAX_VALUE);
	}

	// (shelter_skip_tries) the tries elsewhere at the same feet, three times 20 s, with no site and no blocks
	@Test
	void noTriesElsewhereWithNothingToWalkTo() {
		assertTrue(DigIn.skipTries(true, 0, true));
		assertTrue(DigIn.skipTries(true, 4, true));
		assertFalse(DigIn.skipTries(true, 0, false));
		assertFalse(DigIn.skipTries(true, 13, true));
		assertFalse(DigIn.skipTries(false, 0, true));
	}

	// (shelter_flat_walk) the second night on a snowy slope: a wall cell over a drop would not shut, 13 blocks in the bag
	@Test
	void flatGroundOnceTheTriesAreSpent() {
		assertTrue(DigIn.flatWalk(true, 13, true));
		assertFalse(DigIn.flatWalk(true, 13, false));
		assertFalse(DigIn.flatWalk(true, DigIn.FLAT_BLOCKS - 1, true));
		assertFalse(DigIn.flatWalk(false, 13, true));
		// a wall over the ground, a wall already there, and the slope's wall cell over a drop
		assertTrue(DigIn.wallStands(false, true, true, false, true));
		assertTrue(DigIn.wallStands(true, false, false, true, false));
		assertFalse(DigIn.wallStands(false, true, false, false, true));
		// at the head something that is no block and takes none
		assertFalse(DigIn.wallStands(true, false, false, false, false));
	}
}
