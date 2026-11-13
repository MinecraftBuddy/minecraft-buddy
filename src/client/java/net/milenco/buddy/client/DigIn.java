package net.milenco.buddy.client;

/**
 * The rules of the night shelter dug where the buddy stands (the autopilot's shelter_dig_here, a fix of 13 Nov 2026, on), with
 * no world in them: what in the bag goes into the walls and the top, and why a cell is no place to dig in. ObtainSafe asks the
 * world and acts.
 */
final class DigIn {
	private DigIn() {}

	/**
	 * Kinds that are full, still blocks and still kept: what the route builds with or needs for its stations, the portal, food
	 * or a trade. A crafting table, a furnace, a chest or a bed never goes into a wall (the last three have a block entity and
	 * are left out on that already).
	 */
	private static final java.util.Set<String> KEPT = java.util.Set.of("crafting_table", "obsidian", "crying_obsidian", "tnt",
		"pumpkin", "carved_pumpkin", "jack_o_lantern", "melon", "hay_block", "slime_block", "honey_block", "magma_block", "ice",
		"packed_ice", "blue_ice", "bookshelf", "sponge", "wet_sponge", "target", "glowstone", "sea_lantern", "redstone_lamp",
		"note_block", "jukebox", "beehive", "bee_nest");

	/**
	 * A block from the bag that may go into a wall or the top: a full block that stays where it is put (no sand, gravel or
	 * concrete powder, which fall; no leaves; no slab, pane or fence, which are not full), with no block entity (a chest, a
	 * furnace, a bed), and none of the kinds kept for the route: ores, the storage blocks of an ingot or a gem, the stations.
	 * Logs, planks, any stone and dirt are in. {@code fullCube} is the block's collision shape at its default state.
	 */
	static boolean wallBlock(String id, boolean falling, boolean entityBlock, boolean leaves, boolean fullCube) {
		if (id == null || falling || entityBlock || leaves || !fullCube) return false;
		if (KEPT.contains(id) || id.endsWith("_ore") || id.equals("ancient_debris")) return false;
		// storage blocks (iron, gold, diamond, coal, raw iron and the like) are worth more than a wall; moss and snow are not
		if (id.endsWith("_block") && !id.equals("moss_block") && !id.equals("snow_block")) return false;
		return true;
	}

	/**
	 * Why the cell over these two is no place to dig in, null when it is one. {@code first} and {@code second}: the cell under
	 * the feet and the one under it can be dug with what the bag holds (ground the hand or a shovel takes, stone only with a
	 * pickaxe, never sand or gravel); {@code fluid}: lava or water in or beside either, or beside the floor; {@code floor}: the
	 * cell under the second is solid (air there is a cave or a drop); {@code sides}: the four sides of both cells are solid,
	 * so the hole opens onto nothing.
	 */
	static String refusal(boolean first, boolean second, boolean fluid, boolean floor, boolean sides) {
		if (!first || !second) return "no ground to dig with the tools at hand";
		if (fluid) return "water or lava by the hole";
		if (!floor) return "a cave or a drop under the hole";
		if (!sides) return "the hole's side opens onto a cave";
		return null;
	}
	/**
	 * (the autopilot's shelter_dig_clock, a fix of 13 Nov 2026, on) how long the night shelter's dig may go with no block dug
	 * before the buddy shuts in on the spot from the bag. test heel: two shelters dug for five minutes at full hp with 13 and
	 * 36 blocks in the bag, "dug 0", until a zombie and a creeper came.
	 */
	static final long DIG_CLOCK_MS = 15_000;

	/**
	 * The dig has made no progress for {@link #DIG_CLOCK_MS}: counted from the latest of when the dig began at its spot, the
	 * last block dug and the last meal that held the hand (0: none yet).
	 */
	static boolean digStalled(long since, long dugAt, long mealAt, long now) {
		return now - Math.max(since, Math.max(dugAt, mealAt)) > DIG_CLOCK_MS;
	}

	/**
	 * The same spot for the dig's clock: the standing cell's column at most one block off sideways, either way, as a step to a
	 * neighbouring cell and back is (test heel's Finn: five minutes between two cells, the clock started again with each step).
	 */
	static boolean sameSpot(int x, int z, int atX, int atZ) {
		return Math.abs(x - atX) <= 1 && Math.abs(z - atZ) <= 1;
	}

	/**
	 * (meal_hand_hold) a meal that holds the hand: the switch on, a meal under way, and that meal no longer than {@code
	 * maxMs} on (a meal that never ends is left to the food side's own back off rather than hold every step).
	 */
	static boolean mealHolds(boolean on, boolean meal, long mealSince, long now, long maxMs) {
		return on && meal && now - mealSince < maxMs;
	}
}
