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

	/**
	 * (the autopilot's shelter_refused_walls, a fix of 13 Nov 2026, on) the dig stopped short of its depth (the ground under the
	 * feet no ground to dig, or a block that would not break) where the top has nothing to go against: the buddy shuts in on the
	 * spot from the bag, walls first and the top last, as after the dig's clock. Not when ducking from shooters, whose own order
	 * puts the side they shoot from first. test heel's night on a snowy slope: "no ground to dig with the tools at hand" (snow, a
	 * wooden pickaxe, 24 blocks in the bag), the top tried first and refused, three tries elsewhere and then a wait in an open
	 * hole until a zombie reached in.
	 */
	static boolean shutOnSpot(boolean on, boolean stoppedShort, boolean duck, boolean open) {
		return on && stoppedShort && !duck && open;
	}

	/**
	 * The top refused on open ground with nothing dug: down after all where three blocks of ground can be dug. With {@code
	 * faceRule} (the autopilot's shelter_place_face, a fix of 13 Nov 2026, on) only where the dig will run ({@code digRuns}: open
	 * ground, or dug in where the buddy stands); else the cell counts as one that will not shut, and 20 s later the buddy tries
	 * elsewhere. test heel: a furnace beside the cell over the head was something to shut against for the shelter and no face for
	 * the placer, and "down after all" with no dig to run went round for five silent minutes.
	 */
	static boolean downAfterAll(boolean faceRule, boolean duck, boolean noDig, boolean top, int dug, boolean diggable, boolean digRuns) {
		return !duck && !noDig && top && dug == 0 && diggable && (!faceRule || digRuns);
	}

	/**
	 * (the autopilot's shelter_blocks_first, a fix of 13 Nov 2026, on) with fewer blocks than this in the bag the night shelter
	 * first breaks cells of ground for blocks, when it cannot dig its hole where it stands or near.
	 */
	static final int BLOCKS_FIRST_UNDER = 5;
	/** At most this many cells broken, within this many blocks of the standing cell, in this long. */
	static final int GATHER_CELLS = 6, GATHER_RANGE = 3;
	static final long GATHER_MS = 40_000;
	/** Blocks enough to stop early: the eight walls of open ground, the top and a block on a wall for it. */
	static final int GATHER_ENOUGH = 10;

	/** Ground the hand breaks quickly and that drops a block for a wall: dirt (grass, podzol, mycelium) or coarse dirt. */
	private static final java.util.Set<String> HAND_GROUND = java.util.Set.of("grass_block", "dirt", "coarse_dirt", "podzol", "mycelium");

	static boolean handGround(String id) { return id != null && HAND_GROUND.contains(id); }

	/**
	 * (shelter_blocks_first) blocks first: the switch on, fewer than {@link #BLOCKS_FIRST_UNDER} blocks to build with, no
	 * monster about ({@code foes}), and no hole to dig where the buddy stands or near ({@code digs}: three down, two down
	 * here or a step off, or a site of three to walk to; each of those digs its own blocks). test heel's night start with an
	 * empty bag on grass over stone: "no hole here", "cannot shut, blocks 0", the walls shut with 1 and then 0 blocks while
	 * skeletons shot 20 to 5.
	 */
	static boolean blocksFirst(boolean on, int blocks, boolean foes, boolean digs) {
		return on && blocks < BLOCKS_FIRST_UNDER && !foes && !digs;
	}

	/**
	 * A cell {@code dx}, {@code dz} off the standing cell the blocks-first step may break: within {@link #GATHER_RANGE}, not the
	 * standing cell nor one of its four sides (the ground the walls stand on), and beside no cell broken already ({@code
	 * broken}, offsets as dx, dz), so that each hole's own sides stay ground for a buddy who ends up standing in it.
	 */
	static boolean gatherCell(int dx, int dz, java.util.List<int[]> broken) {
		if (Math.abs(dx) > GATHER_RANGE || Math.abs(dz) > GATHER_RANGE || Math.abs(dx) + Math.abs(dz) <= 1) return false;
		for (int[] b : broken) if (Math.abs(b[0] - dx) + Math.abs(b[1] - dz) == 1) return false;
		return true;
	}

	/** The blocks-first step is over: its cells broken, blocks enough in the bag, or its time up. */
	static boolean gatherDone(int broken, int blocks, long since, long now) {
		return broken >= GATHER_CELLS || blocks >= GATHER_ENOUGH || now - since > GATHER_MS;
	}

	/**
	 * (the autopilot's shelter_hand_dig, a fix of 13 Nov 2026, on) the cell will not shut and the bag is empty: rather than a
	 * wait half shut (or another try at the same kind of spot), a cell within 16 where two of ground come out by hand, and the
	 * buddy digs in there, the top shut with the dug block, as the autopilot's own dug-in cover did that night. Three such walks
	 * at most; {@code digHere} is the dig-in's own switch (shelter_dig_here), which does the digging.
	 */
	static boolean handDig(boolean on, boolean digHere, boolean duck, int blocks, int tries) {
		return handDig(on, digHere, duck, blocks, tries, false);
	}

	/**
	 * The same, and (the autopilot's shelter_stuck_way_out, a fix of 14 Nov 2026, on) {@code spent}: the three tries elsewhere
	 * are spent, so the dig-in by hand is the way out with blocks in the bag too. A live look at the test heel: a cell that
	 * would not shut, three tries elsewhere that left the feet where they were, 12 logs in the bag and only an axe, then a
	 * wait half shut for good until a stray shot in through the open side.
	 */
	static boolean handDig(boolean on, boolean digHere, boolean duck, int blocks, int tries, boolean spent) {
		return on && digHere && !duck && (blocks == 0 || spent) && tries < 3;
	}

	/** (shelter_stuck_way_out) the tries elsewhere the night shelter takes before it waits where it is. */
	static final int ELSEWHERE_TRIES = 3;

	/** (shelter_stuck_way_out) the tries elsewhere are spent: the switch on and {@link #ELSEWHERE_TRIES} of them taken. */
	static boolean triesSpent(boolean on, int elsewhere) {
		return on && elsewhere >= ELSEWHERE_TRIES;
	}

	/**
	 * (shelter_stuck_way_out) a try elsewhere that found no walk to make keeps the shut-in on the spot ({@code noDig}): the
	 * dig there ran out already, and dropping it switched off shelter_refused_walls' pass over the cell that will not shut, so
	 * the other walls and the top never went up. Not for a walk to a dig-in by hand ({@code hand}), which starts afresh.
	 */
	static boolean keepNoDig(boolean on, boolean noDig, boolean hand) {
		return on && noDig && !hand;
	}

	/**
	 * (shelter_stuck_way_out) a kept shut-in on the spot holds while the feet stay in the column it was kept at; a walk to
	 * another cell starts that cell's own dig.
	 */
	static boolean feetStayed(int x, int z, int atX, int atZ) {
		return x == atX && z == atZ;
	}
}
