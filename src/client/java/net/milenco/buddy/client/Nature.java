package net.milenco.buddy.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the blocks and mobs a buddy can gather are found in a world nobody built in: the dimension, the kind of place,
 * the height that is best, and a guess of what one costs in seconds of searching, walking and breaking (the planner
 * weighs ways with it, so the numbers only have to be right against each other). Only what stands here is a source to
 * break or kill: a crafting table drops itself, but nobody looks for one in the wild.
 */
final class Nature {
	private Nature() {}

	enum Dim { OVERWORLD, NETHER, END }

	/**
	 * The kind of place, which is also the way of searching: on the surface in view, underground in caves or a mine at a
	 * height, in water, in a structure that has to be found first.
	 */
	enum Where { SURFACE, UNDERGROUND, DEEP, WATER, STRUCTURE, ANY }

	/** {@code bestY}: the height to mine at (Integer.MIN_VALUE when it does not matter); {@code hint}: a biome or structure. */
	record Place(String id, Dim dim, Where where, double cost, int bestY, int minY, int maxY, String hint, java.util.Set<Dim> also) {
		/** Found in this dimension too. */
		boolean in(Dim d) { return dim == d || also.contains(d); }
	}

	private static final Map<String, Place> BLOCKS = new HashMap<>();
	private static final Map<String, Place> MOBS = new HashMap<>();
	private static final int ANY_Y = Integer.MIN_VALUE;

	private static void block(String id, Dim d, Where w, double cost) { BLOCKS.put(id, new Place(id, d, w, cost, ANY_Y, -64, 320, "", java.util.Set.of())); }

	private static void block(String id, Dim d, Where w, double cost, String hint) { BLOCKS.put(id, new Place(id, d, w, cost, ANY_Y, -64, 320, hint, java.util.Set.of())); }

	private static void ore(String id, Dim d, Where w, double cost, int best, int min, int max) { BLOCKS.put(id, new Place(id, d, w, cost, best, min, max, "", java.util.Set.of())); }

	private static void mob(String id, Dim d, Where w, double cost, String hint) { MOBS.put(id, new Place(id, d, w, cost, ANY_Y, -64, 320, hint, java.util.Set.of())); }

	static {
		Dim O = Dim.OVERWORLD, N = Dim.NETHER, E = Dim.END;
		// trees: every log of the overworld, the nether's stems
		for (String w : List.of("oak", "birch", "spruce", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak"))
			block(w + "_log", O, Where.SURFACE, w.equals("oak") || w.equals("birch") || w.equals("spruce") ? 8 : 25, w);
		block("crimson_stem", N, Where.SURFACE, 30, "crimson_forest");
		block("warped_stem", N, Where.SURFACE, 30, "warped_forest");
		// the ground
		block("grass_block", O, Where.SURFACE, 3);
		block("dirt", O, Where.SURFACE, 3);
		block("sand", O, Where.SURFACE, 8, "desert, beach, river");
		block("red_sand", O, Where.SURFACE, 40, "badlands");
		block("gravel", O, Where.SURFACE, 10, "rivers, beaches, caves");
		block("clay", O, Where.WATER, 25, "rivers, swamps");
		block("snow_block", O, Where.SURFACE, 40, "snowy slopes");
		block("ice", O, Where.SURFACE, 40, "frozen");
		block("mud", O, Where.SURFACE, 40, "mangrove swamp");
		block("moss_block", O, Where.UNDERGROUND, 60, "lush caves");
		block("cactus", O, Where.SURFACE, 25, "desert");
		block("sugar_cane", O, Where.SURFACE, 20, "beside water");
		block("bamboo", O, Where.SURFACE, 40, "jungle");
		block("pumpkin", O, Where.SURFACE, 40, "plains, taiga");
		// jungles only (and village farms): a long way off anywhere else, the food a search went for over the cows
		block("melon", O, Where.SURFACE, 150, "jungle");
		block("kelp_plant", O, Where.WATER, 20, "ocean");
		block("vine", O, Where.SURFACE, 30, "jungle, swamp");
		block("lily_pad", O, Where.WATER, 40, "swamp");
		block("sweet_berry_bush", O, Where.SURFACE, 40, "taiga");
		block("hay_block", O, Where.STRUCTURE, 300, "village");
		block("brown_mushroom", O, Where.SURFACE, 30, "dark places");
		block("red_mushroom", O, Where.SURFACE, 30, "dark places");
		// flowers, each with the biome it is likely in
		block("dandelion", O, Where.SURFACE, 10, "plains, forest");
		block("poppy", O, Where.SURFACE, 10, "plains, forest");
		block("cornflower", O, Where.SURFACE, 25, "plains, flower forest");
		block("oxeye_daisy", O, Where.SURFACE, 25, "plains, flower forest");
		block("azure_bluet", O, Where.SURFACE, 25, "plains, flower forest");
		block("allium", O, Where.SURFACE, 40, "flower forest");
		block("blue_orchid", O, Where.SURFACE, 50, "swamp");
		block("red_tulip", O, Where.SURFACE, 30, "plains, flower forest");
		block("orange_tulip", O, Where.SURFACE, 30, "plains, flower forest");
		block("white_tulip", O, Where.SURFACE, 30, "plains, flower forest");
		block("pink_tulip", O, Where.SURFACE, 30, "plains, flower forest");
		block("lily_of_the_valley", O, Where.SURFACE, 40, "flower forest");
		block("sunflower", O, Where.SURFACE, 40, "sunflower plains");
		block("lilac", O, Where.SURFACE, 40, "forest");
		block("rose_bush", O, Where.SURFACE, 40, "forest");
		block("peony", O, Where.SURFACE, 40, "forest");
		block("short_grass", O, Where.SURFACE, 4);
		block("tall_grass", O, Where.SURFACE, 6);
		block("fern", O, Where.SURFACE, 10, "taiga, jungle");
		block("dead_bush", O, Where.SURFACE, 25, "desert, badlands");
		block("torchflower", O, Where.SURFACE, 200, "sniffer");
		block("pink_petals", O, Where.SURFACE, 40, "cherry grove");
		block("oak_leaves", O, Where.SURFACE, 5, "oak");
		block("birch_leaves", O, Where.SURFACE, 5, "birch");
		block("spruce_leaves", O, Where.SURFACE, 5, "spruce");
		block("jungle_leaves", O, Where.SURFACE, 25, "jungle");
		block("dark_oak_leaves", O, Where.SURFACE, 25, "dark forest");
		block("acacia_leaves", O, Where.SURFACE, 25, "savanna");
		block("cherry_leaves", O, Where.SURFACE, 30, "cherry grove");
		block("mangrove_leaves", O, Where.SURFACE, 30, "mangrove swamp");
		// stone and what lies in it
		block("stone", O, Where.UNDERGROUND, 3);
		block("deepslate", O, Where.DEEP, 5);
		block("granite", O, Where.UNDERGROUND, 8);
		block("diorite", O, Where.UNDERGROUND, 8);
		block("andesite", O, Where.UNDERGROUND, 8);
		block("tuff", O, Where.DEEP, 10);
		block("calcite", O, Where.UNDERGROUND, 60, "amethyst geode");
		block("amethyst_cluster", O, Where.UNDERGROUND, 80, "amethyst geode");
		block("dripstone_block", O, Where.UNDERGROUND, 40, "dripstone caves");
		block("pointed_dripstone", O, Where.UNDERGROUND, 40, "dripstone caves");
		block("sandstone", O, Where.SURFACE, 10, "desert");
		block("obsidian", O, Where.DEEP, 90, "lava lakes under water, ruined portals");
		ore("coal_ore", O, Where.UNDERGROUND, 15, 96, 0, 256);
		ore("deepslate_coal_ore", O, Where.DEEP, 40, 0, -8, 0);
		ore("copper_ore", O, Where.UNDERGROUND, 15, 48, -16, 112);
		ore("deepslate_copper_ore", O, Where.DEEP, 40, -8, -16, 0);
		ore("iron_ore", O, Where.UNDERGROUND, 25, 16, -24, 72);
		ore("deepslate_iron_ore", O, Where.DEEP, 30, -8, -64, 0);
		ore("gold_ore", O, Where.UNDERGROUND, 80, -16, -64, 32);
		ore("deepslate_gold_ore", O, Where.DEEP, 70, -16, -64, 32);
		ore("redstone_ore", O, Where.DEEP, 50, -58, -64, 16);
		ore("deepslate_redstone_ore", O, Where.DEEP, 45, -58, -64, 16);
		ore("lapis_ore", O, Where.UNDERGROUND, 80, 0, -64, 64);
		ore("deepslate_lapis_ore", O, Where.DEEP, 70, -8, -64, 64);
		ore("diamond_ore", O, Where.DEEP, 250, -58, -64, 16);
		ore("deepslate_diamond_ore", O, Where.DEEP, 220, -58, -64, 16);
		ore("emerald_ore", O, Where.UNDERGROUND, 400, 100, -16, 320);
		block("water", O, Where.ANY, 5);
		block("lava", O, Where.DEEP, 30, "deep caves, the surface's lava pools");
		// the nether
		block("netherrack", N, Where.ANY, 2);
		block("soul_sand", N, Where.ANY, 15, "soul sand valley");
		block("soul_soil", N, Where.ANY, 15, "soul sand valley");
		block("basalt", N, Where.ANY, 20, "basalt deltas");
		block("blackstone", N, Where.ANY, 15, "basalt deltas, bastions");
		block("magma_block", N, Where.ANY, 15);
		block("glowstone", N, Where.ANY, 40, "the ceiling");
		block("nether_quartz_ore", N, Where.ANY, 15);
		block("nether_gold_ore", N, Where.ANY, 20);
		ore("ancient_debris", N, Where.ANY, 900, 15, 8, 22);
		block("nether_wart", N, Where.STRUCTURE, 120, "fortress");
		block("nether_bricks", N, Where.STRUCTURE, 60, "fortress");
		block("crimson_nylium", N, Where.SURFACE, 30, "crimson_forest");
		block("warped_nylium", N, Where.SURFACE, 30, "warped_forest");
		block("shroomlight", N, Where.SURFACE, 60, "nether forests");
		// the End
		block("end_stone", E, Where.ANY, 5);
		block("chorus_flower", E, Where.SURFACE, 60, "outer islands");
		block("chorus_plant", E, Where.SURFACE, 40, "outer islands");
		block("purpur_block", E, Where.STRUCTURE, 60, "end city");
		block("end_rod", E, Where.STRUCTURE, 60, "end city");

		// mobs
		mob("cow", O, Where.SURFACE, 30, "grass");
		mob("pig", O, Where.SURFACE, 30, "grass");
		mob("sheep", O, Where.SURFACE, 30, "grass");
		mob("chicken", O, Where.SURFACE, 30, "grass");
		mob("rabbit", O, Where.SURFACE, 60, "desert, flower forest, taiga");
		mob("salmon", O, Where.WATER, 60, "rivers");
		mob("cod", O, Where.WATER, 60, "oceans");
		mob("squid", O, Where.WATER, 60, "water");
		mob("zombie", O, Where.ANY, 50, "night, dark");
		mob("skeleton", O, Where.ANY, 60, "night, dark");
		mob("spider", O, Where.ANY, 50, "night, dark");
		mob("creeper", O, Where.ANY, 90, "night, dark");
		mob("enderman", O, Where.ANY, 150, "night; the End; warped forest");
		MOBS.put("enderman", new Place("enderman", O, Where.ANY, 150, ANY_Y, -64, 320, "night; the End; warped forest", java.util.Set.of(N, E)));
		mob("witch", O, Where.SURFACE, 300, "swamp huts, night");
		mob("slime", O, Where.UNDERGROUND, 300, "slime chunks, swamps");
		mob("blaze", N, Where.STRUCTURE, 60, "fortress");
		mob("wither_skeleton", N, Where.STRUCTURE, 200, "fortress");
		mob("ghast", N, Where.ANY, 200, "open nether");
		mob("magma_cube", N, Where.ANY, 120, "basalt deltas");
		mob("piglin", N, Where.ANY, 60, "crimson forest, wastes");
		mob("zombified_piglin", N, Where.ANY, 60, "everywhere");
		mob("hoglin", N, Where.SURFACE, 90, "crimson forest");
		mob("shulker", E, Where.STRUCTURE, 200, "end city");
	}

	/** Where a block is to be had, or null when nobody looks for it in the wild. */
	static Place block(String id) { return BLOCKS.get(id); }

	static Place mob(String id) { return MOBS.get(id); }

	static Map<String, Place> blocks() { return BLOCKS; }
}
