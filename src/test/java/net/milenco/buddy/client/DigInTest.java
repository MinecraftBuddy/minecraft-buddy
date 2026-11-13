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
}
