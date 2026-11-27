package net.milenco.buddy.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * The overworld's way to the nether in a fixed order of phases, for an order whose way goes into the nether and that began
 * in the overworld. Three milestones: M1 everything ready for the first iron, M2 iron tools and armour, M3 ready for the
 * nether (a diamond pickaxe, ten obsidian, flint and steel, 96 gold, a golden helmet, a slime ball). The kit (Alto.kit) asks the pieces
 * of the phase under way in their order and goes on to the next phase once nothing of it is left to ask; the whole order
 * (the autopilot's own way in: the frame from the bag's obsidian, lit, and through) comes only after the last phase. Each
 * phase says a line when it begins ("route: phase m2.1 armour") and one when it is left unmet ("route: phase m2.1 short"),
 * so a run reads by phase as the nether's route does.
 *
 * <p>A phase is met when the bag holds what it is for ({@link #met}), the same reading as the practice rig's, so the rig
 * and the route agree on where a run is. Forward only: a phase once left is not asked again, so the route never goes back
 * up for wood with the diamonds in sight. A piece that does not come is asked again, three tries in all, then given up,
 * and the other pieces go on; with kit_failed_skip it first waits ten minutes while the phase's other pieces go on. No types of the autopilot here: the kit names its tasks, this class only the bag, so the
 * order is tested without the game.
 */
final class ObtainRoute {
	private ObtainRoute() {}

	/** The phases in their order, and the word each one's line says. */
	// (m3.3b: the slime ball, a phase of its own between the gold and the portal; the cream of the nether's brew is made
	// from it. Added within M3 so that m3.4 stays the portal)
	static final List<String> PHASES = List.of("m1.1", "m1.2", "m1.3", "m2.1", "m2.2", "m3.1", "m3.2", "m3.3", "m3.3b", "m3.4");
	static final List<String> WORDS = List.of("wood", "stone", "surface", "armour", "tools", "diamonds", "obsidian", "gold", "slime", "portal");
	static final int M1_1 = 0, M1_2 = 1, M1_3 = 2, M2_1 = 3, M2_2 = 4, M3_1 = 5, M3_2 = 6, M3_3 = 7, M3_3B = 8, M3_4 = 9;

	/** A phase's line says it began, or that it was left unmet. */
	static final String BEGUN = "begun", SHORT = "short";

	/** How often a piece is asked before it is given up. */
	static final int TRIES = 3;
	/** The slime phase's tries (steering 204): one search, then the portal without the ball. */
	static final int SLIME_TRIES = 1;

	/** The wood M1.1 fetches, in planks: sixteen logs. */
	static final int WOOD = 16 * 4;

	/** Gold kept for the piglins' trade: what M3 is ready with, besides a golden helmet worn and a spare (96 since 12 Nov
	 * 2026: 64 proved too few for a potion; 128 that evening; 96 again since 14 Nov 2026 by the maintainer's word, what the
	 * phase records count: 96 and 5 more for a golden helmet). */
	static final int GOLD = 96;

	/**
	 * The trade's gold the M3 kit carries: {@link #GOLD}, or none with nether_gold on (steering 200 of 19 Nov 2026, the
	 * maintainer's word: the trade's gold from nether gold ore, in the overworld only the golden boots' four (the two golden helmets' ten before steering 202); the
	 * overworld mine took 10 to 20 gold a game hour, and 2 of 18 whole runs past the obsidian reached 96).
	 */
	static int tradeGold(Bag b) { return b.netherGold() ? 0 : GOLD; }

	/** The golden boots' gold (steering 202 of 19 Nov 2026: the piglins' piece is one pair of golden boots). */
	static final int BOOTS_GOLD = 4;

	/** What the route reads of the buddy. */
	interface Bag {
		/** Carried: the inventory and the off hand. */
		int inv(String item);

		/** The item worn in this armour slot ("helmet", "chestplate", "leggings", "boots"); "" for none. */
		String wornIn(String slot);

		/** Carried of every item whose id ends with this ("_bed", "_log"). */
		int invEnding(String suffix);

		/** Stone and copper pickaxes carried that are not worn out. */
		default int cheapPicks() { return inv("stone_pickaxe") + inv("copper_pickaxe"); }

		/** A bed stands within six blocks: the buddy's own, put down for the night. */
		default boolean bedNear() { return false; }

		/** Deep under the ground, where no bed is fetched. */
		default boolean deepUnder() { return false; }

		/** A slime in sight or a slime ball on the ground near: one met on the way, taken along. */
		default boolean slimeNear() { return false; }

		/** The nether kit's rest has something to fetch (a good sword, torches, blocks). */
		default boolean restMissing() { return false; }

		/** The autopilot's spare_shield_m2 switch: a second shield at M2.2's table visit, from the bag alone. */
		default boolean spareShieldOn() { return false; }

		/** A shield's makings carried: one iron, raw or smelted, and wood worth six planks (ten with no crafting table). */
		default boolean shieldMakings() { return false; }

		/** The autopilot's cook_raw_m2 switch: the raw meat carried is cooked at the end of M2.2, before the deep. */
		default boolean cookRawOn() { return false; }

		/** The raw meat the bag's furnace and fuel would cook now (whole kinds, at most twenty); 0 with no furnace in the bag. */
		default int cookableMeat() { return 0; }

		/** The autopilot's m34_kit_again switch: before the portal a crafting table, and the M3 kit asked again. */
		default boolean kitAgain() { return false; }

		/**
		 * The autopilot's nether_sticks: the sticks the bag takes through the portal for the stone pickaxes of the nether's gold
		 * step (0: none asked).
		 */
		default int netherSticks() { return 0; }

		/**
		 * The obsidian standing in the buddy's own portal frames nearby (the autopilot's count, with its frame_obsidian_counts
		 * switch on; 0 without): it counts for M3.4's obsidian as the bag's does.
		 */
		default int obsidianInFrames() { return 0; }

		/**
		 * The bag's food points as the nether's food rule counts them, with the autopilot's m34_food_points switch on; -1
		 * without, and the portal phase's food piece asks the count of food alone.
		 */
		default int foodPoints() { return -1; }

		/** The autopilot's gold_clock_fix switch: M3.4's gold on M3.3's clock and budget, and not mined again once over. */
		default boolean goldClockFix() { return false; }

		/** The autopilot's gold_raw_smelt switch: M3.4 smelts the bag's raw gold once no more gold is mined. */
		default boolean rawGoldSmelt() { return false; }

		/** The autopilot's nether_gold switch: the trade's gold is dug in the nether, the overworld gets only the golden boots'. */
		default boolean netherGold() { return false; }
	}

	/** A piece of a phase: its name (the kit's step), how often it is asked, and whether the bag still lacks it. */
	record Piece(int phase, String name, int tries, Predicate<Bag> wanted) {
		/** The piece's own name across the route: a water bucket in M2.2 and again in M3.2 are two pieces. */
		String key() { return PHASES.get(phase) + "/" + name; }
	}

	/** The order under way walks the route; its dimension at the start; out of the overworld since (the route behind it). */
	private static boolean on, overworld, through;
	/** The phase under way. */
	private static int phase;
	/** A furnace was in the bag at some time this order (it is put down to smelt, so the bag alone forgets it). */
	private static boolean furnaceHeld;

	/** A furnace was in the bag at some time this order (put down to smelt, it is still at hand). */
	static boolean furnaceHeld() { return furnaceHeld; }
	/** The pieces' tries, by key. */
	private static final Map<String, Integer> TRIED = new HashMap<>();
	/** The pieces that failed, by key, and when (the order's game clock, ms): passed over for {@link #SKIP} (kit_failed_skip). */
	private static final Map<String, Long> FAILED = new HashMap<>();

	/** How long a piece that failed waits while the phase's other pieces go on: ten game minutes, as the old kit's. */
	static final long SKIP = 600_000;

	private static final String[] SLOTS = { "helmet", "chestplate", "leggings", "boots" };
	private static final List<String> IRON_UP = List.of("iron", "diamond", "netherite");

	/** (m34_food_points) under this many food points the portal phase gathers food for the nether (a food run gathers 160). */
	static final int M34_FOOD_POINTS = 120;

	/** (cook_raw_m2) fewer raw pieces than this are not worth putting the furnace down for at the end of M2.2. */
	static final int COOK_MIN = 4;

	/** What the rig counts as food in the bag (raw meat too). */
	static final Set<String> FOOD = Set.of("cooked_beef", "cooked_porkchop", "cooked_mutton", "cooked_chicken", "cooked_cod",
		"cooked_salmon", "cooked_rabbit", "bread", "baked_potato", "apple", "golden_apple", "carrot", "potato", "beef", "porkchop",
		"mutton", "chicken", "cod", "salmon", "rabbit", "sweet_berries", "glow_berries", "melon_slice", "cookie", "pumpkin_pie",
		"mushroom_stew", "beetroot", "beetroot_soup", "dried_kelp", "rabbit_stew", "golden_carrot");

	/** Each phase's pieces, in the order they are asked. */
	static final List<List<Piece>> PIECES = List.of(
		// a table, a wooden pickaxe and sixteen logs: the wood of the stone tools, their sticks and the table after them
		List.of(piece(M1_1, "wood", b -> !done(M1_1, b))),
		// the stone pickaxes the rock wears out (a spare kept for the ore), a stone sword, a furnace for the iron
		List.of(piece(M1_2, "stone pickaxes", b -> b.cheapPicks() < 3 || b.inv("crafting_table") < 1 || b.inv("stick") < 4),
			piece(M1_2, "stone sword", b -> !anyOf(b, "stone_sword", "copper_sword", "iron_sword", "diamond_sword", "netherite_sword")),
			piece(M1_2, "furnace", b -> !furnaceHeld && b.inv("furnace") < 1)),
		// food and a bed before the first night; the sand of the brew's bottles on the way, one try (a gain, no gate)
		List.of(piece(M1_3, "food", b -> food(b) < 8),
			piece(M1_3, "bed", b -> b.invEnding("_bed") < 1 && !b.bedNear() && !b.deepUnder()),
			new Piece(M1_3, "sand", 1, b -> b.inv("sand") < 3)),
		// the first iron as armour: the shield first (one ingot), then copper for an empty slot whose iron is not in the bag
		// yet, then the iron itself, most protection per ingot first, the helmet last (steering 202: the golden boots replace the iron ones in M3.3)
		List.of(piece(M2_1, "shield", b -> b.inv("shield") < 1),
			piece(M2_1, "copper_chestplate", b -> copperWanted(b, "chestplate")),
			piece(M2_1, "copper_leggings", b -> copperWanted(b, "leggings")),
			piece(M2_1, "copper_boots", b -> copperWanted(b, "boots")),
			piece(M2_1, "iron_chestplate", b -> !ironUp(b.wornIn("chestplate"))),
			piece(M2_1, "iron_leggings", b -> !ironUp(b.wornIn("leggings"))),
			piece(M2_1, "iron_boots", b -> !ironUp(b.wornIn("boots")) && !goldBoots(b)),
			// (steering 202 of 19 Nov 2026, his word: golden boots for the piglins and an iron helmet, so M2's armour has the
			// helmet too; the golden boots that replace the iron ones in M3.3 count as boots here)
			piece(M2_1, "iron_helmet", b -> !ironUp(b.wornIn("helmet")))),
		// a water bucket (a fire put out, a fall caught), two iron pickaxes, an iron sword
		List.of(piece(M2_2, "water bucket", b -> b.inv("water_bucket") < 1),
			piece(M2_2, "iron pickaxes", b -> picksUp(b) < 2),
			piece(M2_2, "iron sword", b -> !anyOf(b, "iron_sword", "diamond_sword", "netherite_sword")),
			// (spare_shield_m2, off: a kit change that waits for his word, 24 Nov 2026) a second shield at the tools' table visit,
			// from the bag alone: of 53 overworld deaths after M2.1 whose shield could be read, 17 had none (a creeper's blast
			// costs a shield 25-44 uses of 336), and the shield again needs its makings in the bag, which those runs lacked. One
			// try; with no iron or wood for it the piece is passed over, never a trip for them
			new Piece(M2_2, "spare shield", 1, b -> b.spareShieldOn() && b.inv("shield") < 2 && b.shieldMakings()),
			// (cook_raw_m2, 25 Nov 2026) the raw meat carried, cooked in the bag's furnace before the deep as far as the bag's fuel
			// goes: 9 of 29 heel runs went down with 9 or more raw pieces, and 5 of 16 obsidian deaths had run out of food. One
			// try, never a trip for fuel; fewer than four raw pieces the fuel covers is no piece
			new Piece(M2_2, "cook raw meat", 1, b -> b.cookRawOn() && b.cookableMeat() >= COOK_MIN)),
		// down to the diamonds for the pickaxe and the sword; the brew's redstone down there
		List.of(piece(M3_1, "diamonds", b -> !anyOf(b, "diamond_pickaxe", "netherite_pickaxe") || !anyOf(b, "diamond_sword", "netherite_sword")),
			piece(M3_1, "redstone", b -> b.inv("redstone") < 1)),
		// ten obsidian for the frame, and the bucket full again after the lava
		List.of(piece(M3_2, "obsidian", b -> b.inv("obsidian") < 10),
			piece(M3_2, "water bucket", b -> b.inv("water_bucket") < 1)),
		// the golden helmet first (worn, 15 armour with the iron: the mine then takes the cave gold it sees, not only its
		// tunnel's walls; steering 170), then the gold for the trade and the spare, and the spare in the bag
		// (12 Nov 2026: the first piece spends its three tries while no gold is found, and was never asked again: a run that
		// later had 48 ingots in the bag never wore one, gold smoke 2 run 2. "golden helmet again" asks it once the bag holds
		// the gold for it, or a helmet)
		// (12 Nov 2026: the gold and the slime ball are fetched in one stretch under the ground; a slime met in the gold mine
		// is taken at once, its ball the next phase's, rather than looked for again in a dig of its own afterwards)
		List.of(piece(M3_3, "slime ball", b -> b.inv("slime_ball") < 1 && b.slimeNear()),
			// (steering 202 of 19 Nov 2026, his word: one pair of golden boots, 4 gold, for the piglins; no golden helmet, no spare)
			piece(M3_3, "golden boots", b -> !goldBoots(b)),
			piece(M3_3, "golden boots again", b -> !goldBoots(b) && (b.inv("golden_boots") > 0 || b.inv("gold_ingot") >= BOOTS_GOLD)),
			piece(M3_3, "gold", b -> b.inv("gold_ingot") < goldNeed(b))),
		// a slime ball for the brew's cream: slimes deep under the ground in some chunks, the dig at the gold mine's height.
		// one try of forty minutes, then on to the portal without the ball (steering 204 of 21 Nov 2026, his word: up to three
		// tries stalled 33 of 136 runs in 24 h on blind digging for slimes)
		// (m33b_boots_again, a fix of 22 Nov 2026) the golden boots on again first when they are in the bag: a stick craft's cursor
		// steps put the iron boots back on, and the phase ended short with the ball in the bag (mc-test1 t7, heel-170s)
		List.of(piece(M3_3B, "golden boots again", b -> !goldBoots(b) && b.inv("golden_boots") > 0),
			new Piece(M3_3B, "slime ball", SLIME_TRIES, b -> b.inv("slime_ball") < 1)),
		// up again: flint and steel, the rest of the nether's kit, the bottles filled; then the whole order builds the portal
		// (m34_kit_again, a fix of 14 Nov 2026: M4 begins with the whole kit, his word. A run lost its table, its wood, the
		// bucket, the bed and every pickaxe above wood on the way through M3, and the bottles, which need a table, stalled the
		// portal's phase; nothing after M1.1 asked for a table or wood again. So a crafting table while none is in the bag and
		// less than four planks of wood, then each piece of the M3 kit the bag lacks, asked again with its three tries, then
		// the bottles)
		List.of(piece(M3_4, "flint and steel", b -> b.inv("flint_and_steel") < 1),
			piece(M3_4, "rest", Bag::restMissing),
			piece(M3_4, "crafting table", b -> b.kitAgain() && b.inv("crafting_table") < 1 && wood(b) < 4),
			// (nether_sticks, a fix of 21 Nov 2026) sticks for the nether's stone pickaxes: the gold step's pickaxes of iron or
			// better go down to their reserve on the tunnels to the ore, and a stone pickaxe from the bag's cobblestone takes
			// over; with the two sticks the bag held, one stone pickaxe was made and the step ended with 60 cobblestone left
			// (heel-136-mc1 r2), and 58 of 102 gold steps ended on the pickaxes kept, at a median 48 of the 96 gold
			piece(M3_4, "sticks", b -> b.netherSticks() > 0 && b.inv("stick") < b.netherSticks()),
			// (m34_food_points, a fix of 20 Nov 2026) and by points: the nether sends the buddy home under 36 points, and eight
			// carrots or berries met the count, so two runs turned back at the portal for food (read of 20 Nov 01:10)
			piece(M3_4, "food", b -> b.kitAgain() && (food(b) < 8 || b.foodPoints() >= 0 && b.foodPoints() < M34_FOOD_POINTS)),
			piece(M3_4, "bed", b -> b.kitAgain() && b.invEnding("_bed") < 1 && !b.bedNear() && !b.deepUnder()),
			piece(M3_4, "water bucket", b -> b.kitAgain() && b.inv("water_bucket") < 1),
			// (the iron pickaxes before the diamonds: diamond ore wants one)
			piece(M3_4, "iron pickaxes", b -> b.kitAgain() && picksUp(b) < 2),
			piece(M3_4, "diamonds", b -> b.kitAgain()
				&& (!anyOf(b, "diamond_pickaxe", "netherite_pickaxe") || !anyOf(b, "diamond_sword", "netherite_sword"))),
			piece(M3_4, "redstone", b -> b.kitAgain() && b.inv("redstone") < 1),
			// (frame_obsidian_counts, a fix of 20 Nov 2026) the obsidian in the frame begun counts: after a food run the piece saw
			// 2 in the bag, mined the 8 of the buddy's own frame back out, and the new site's search then looped for 43 minutes
			// (look 154)
			piece(M3_4, "obsidian", b -> b.kitAgain() && b.inv("obsidian") + b.obsidianInFrames() < 10),
			// (gold_raw_smelt, a fix of 19 Nov 2026) once no more gold is mined, the raw gold in the bag smelted: a piglin trades
			// only ingots, and goudsmoke3's run 4 went through the portal with 27 ingots and 12 raw gold from the slime dig
			piece(M3_4, "raw gold", b -> b.kitAgain() && b.rawGoldSmelt() && b.inv("raw_gold") > 0
				&& b.inv("gold_ingot") < goldNeed(b) && goldOver(b) != null),
			// (the golden boots worn from the bag's gold first, as M3.3's "golden boots again"; then the gold; no spare since
			// steering 202) (gold_clock_fix: once the gold is over, goldOver, no more gold is mined)
			piece(M3_4, "golden boots", b -> b.kitAgain() && !goldBoots(b)
				&& (b.inv("golden_boots") > 0 || b.inv("gold_ingot") >= BOOTS_GOLD)),
			piece(M3_4, "gold", b -> b.kitAgain() && b.inv("gold_ingot") < goldNeed(b) && goldOver(b) == null),
			piece(M3_4, "water bottles", b -> b.inv("potion") < 3),
			// (nether_gold, 19 Nov 2026) the trade's gold is folded from nuggets at a table in the nether: a table (or wood for
			// one) once more, last before the portal. A whole run crafted its bed at the table after the first ask, the table's
			// break was cut, and it went through with neither: no gold, no trade, the rods with no potion
			piece(M3_4, "crafting table last", b -> b.kitAgain() && b.netherGold() && b.inv("crafting_table") < 1 && wood(b) < 4)));

	private static Piece piece(int phase, String name, Predicate<Bag> wanted) { return new Piece(phase, name, TRIES, wanted); }

	/** A new order: the route off until the order's danger is known, and where it began. */
	static void reset(boolean inOverworld) {
		on = through = furnaceHeld = false;
		overworld = inOverworld;
		phase = M1_1;
		TRIED.clear();
		FAILED.clear();
		goldSpent = 0;
		slimeSpent = 0;
		goldStopped = goldPassSaid = goldPicksWorn = false;
		goldOrder++;
		topUpDone = topUpSaid = false;
	}

	/** (food_top_deep) a food run ended on the deep stretch's first two phases this order: the top-up is spent. */
	private static boolean topUpDone;

	/** (food_top_deep) the top-up's line said once a run of it (Alto.wantsFood); set back when a food run ends or an order begins. */
	static boolean topUpSaid;

	/** (Tuning food_top_deep, off: test voorraad of 27 Nov 2026) the points the bag is topped up to before the deep's obsidian. */
	static final int FOOD_TOP = 85;

	/**
	 * (food_top_deep) Whether one food run is due before the obsidian: walking the route at the diamonds or the obsidian
	 * (M3.1, M3.2), no obsidian in the bag yet, no food run ended there this order, and under FOOD_TOP points. Heel's runs
	 * that began the obsidian under 40 points died there 31%, at 40 to 79 23%, at 80 or more 18% (498 starts, 24-27 Nov
	 * 2026), and a trip up for food in the middle of the phase took 17% of its time and was its main way to the surface.
	 */
	static boolean topUpDue(int score, int obsidian) {
		return topUpDue(walking(), phase, topUpDone, obsidian, score);
	}

	static boolean topUpDue(boolean walking, int phase, boolean done, int obsidian, int score) {
		return walking && (phase == M3_1 || phase == M3_2) && !done && obsidian == 0 && score < FOOD_TOP;
	}

	/** (food_top_deep) a food run ended: on the deep stretch's first two phases the top-up is spent, whatever came of it. */
	static void foodRunEnded() {
		topUpSaid = false;
		if (walking() && (phase == M3_1 || phase == M3_2)) topUpDone = true;
	}

	/** The order walks the route (its way goes into the nether, and it began in the overworld). */
	static boolean on() { return on; }

	/** On and still in the overworld's phases. */
	static boolean walking() { return on && !through; }

	/** Walking and not yet at that phase. */
	static boolean before(int p) { return walking() && phase < p; }

	static int phase() { return phase; }

	/** (slime_on_way, off) at most this many slimes taken along on the way in an order (Obtain). */
	static final int SLIME_WAYS = 3;

	/** (slime_on_way) a slime met may cut in: the diamonds or the obsidian (M3.1, M3.2; the gold has its own piece), its time come, fewer than SLIME_WAYS so far, no ball, no menu open. */
	static boolean slimeWayDue(int phase, long now, long next, int ways, int balls, boolean menu) {
		return (phase == M3_1 || phase == M3_2) && now >= next && ways < SLIME_WAYS && balls < 1 && !menu;
	}

	/**
	 * (Tuning night_route_m34, on since 27 Nov 2026) The route's last stretch to the nether, where the night's rules hold whatever the armour: the
	 * switch on, an order under way that walks the route (the trip home from the nether is no part of it), the slime's phase or
	 * the portal's (M3.3b, M3.4), in the overworld, no bed for tonight, and no slime search under way (its one search of forty
	 * minutes runs on, steering 204). Every night rule ends at armour 15, and the kit with golden boots counts 15 since 20 Nov
	 * 2026: 14 of 24 deaths between M3.3 and the nether came on the surface at night, 8 of them after the slime search gave up,
	 * at M3.4's food, bed, table, bottles and the climb to the portal (the read heel-slime-search, 26 Nov 2026).
	 */
	static boolean nightStretch(boolean on, boolean order, boolean walking, int phase, boolean overworld, boolean bedTonight,
			boolean slimeSearch) {
		return on && order && walking && phase >= M3_3B && phase <= M3_4 && overworld && !bedTonight && !slimeSearch;
	}

	/** (night_route_m34) the name a wait gives the whole order's walk to the portal's site, after the last piece. */
	static final String PORTAL_WALK = "the walk to the portal";

	/**
	 * (night_route_m34) Whether this piece, come up in the dark on the night's stretch, waits for the morning: a piece of the
	 * portal's phase done on the surface. The tables (wood), the food and the bed (animals), the bottles (sand and water), and
	 * the sticks when the bag's wood does not make them (a log fetched). The food goes on when the buddy is hurt with nothing to
	 * eat, as the night's food rule lets it. The pieces below the ground (the diamonds, the obsidian, the gold) go on at night.
	 */
	static boolean waitsForMorning(Piece pc, Bag b, boolean hurtAndNoFood) {
		if (pc.phase() != M3_4) return false;
		return switch (pc.name()) {
			case "crafting table", "crafting table last", "bed", "water bottles" -> true;
			case "food" -> !hurtAndNoFood;
			case "sticks" -> wood(b) < sticksWood(b);
			default -> false;
		};
	}

	/** (night_route_m34) the planks the sticks still missing take: two planks make four sticks. */
	static int sticksWood(Bag b) {
		int more = Math.max(0, b.netherSticks() - b.inv("stick"));
		return 2 * ((more + 3) / 4);
	}

	/** A time of day as a clock shows it ("18:31"): the day's tick 0 is 06:00, a game hour is 1000 ticks. */
	static String clock(long dayTime) {
		long t = Math.floorMod(dayTime, 24000L);
		long h = (t / 1000 + 6) % 24, m = t % 1000 * 60 / 1000;
		return (h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m;
	}

	/** Out of the overworld: the route is behind the buddy, the rest of the order is the old kit's. */
	static void out() { if (on) through = true; }

	/**
	 * The order's danger is known (Obtain.deepOrder): the route on for an order into the nether begun in the overworld,
	 * from the phase after the highest the bag meets already; that phase's line said.
	 */
	static void begin(boolean nether, Bag b, BiConsumer<Integer, String> say) {
		if (on || !nether || !overworld) return;
		on = true;
		furnaceHeld = b.inv("furnace") > 0;
		phase = start(b);
		say.accept(phase, BEGUN);
	}

	/** The phase after the highest this bag meets (the first with none); never past the portal's. */
	static int start(Bag b) {
		boolean furnace = b.inv("furnace") > 0;
		for (int i = M3_3B; i >= M1_1; i--) if (met(i, b, furnace)) return i + 1;
		return M1_1;
	}

	/** A try of this piece has ended, done or failed. */
	static void tried(String key) { TRIED.merge(key, 1, Integer::sum); }

	/** How often the piece of this key was tried this order (for the log). */
	static int triedCount(String key) { return TRIED.getOrDefault(key, 0); }

	static int tries(String key) { return TRIED.getOrDefault(key, 0); }

	/** A try of this piece ran out of its time or failed (kit_failed_skip): passed over for ten minutes from now. */
	static void failed(String key, long now) { FAILED.put(key, now); }

	/** Whether the piece failed less than ten minutes before now. */
	static boolean skipped(String key, long now) {
		Long at = FAILED.get(key);
		return at != null && now >= at && now - at < SKIP;
	}

	/** The piece of this key, or null. */
	static Piece piece(String key) {
		for (List<Piece> ps : PIECES) for (Piece pc : ps) if (pc.key().equals(key)) return pc;
		return null;
	}

	/** The next piece to ask with no piece's ten minutes counted (as {@link #next(Bag, long, BiConsumer)} with none failed). */
	static Piece next(Bag b, BiConsumer<Integer, String> say) { return next(b, Long.MIN_VALUE, say); }

	/**
	 * The next piece to ask, or null once the portal's phase has nothing left to ask (the whole order then). A phase with
	 * nothing left is passed: said short when the bag does not meet it, and the next one begun. A piece that failed less
	 * than ten minutes before now waits while the phase's other pieces go on; when every piece left in the phase waits, the
	 * one that failed first is asked, so the phase is never left with tries to go and no gap opens between two pieces.
	 * Long.MIN_VALUE for now: no piece waits.
	 */
	static Piece next(Bag b, long now, BiConsumer<Integer, String> say) {
		if (!walking()) return null;
		if (b.inv("furnace") > 0) furnaceHeld = true;
		while (true) {
			Piece waiting = null;
			long waitingAt = Long.MAX_VALUE;
			for (Piece pc : PIECES.get(phase)) {
				if (tries(pc.key()) >= pc.tries() || !pc.wanted().test(b)) continue;
				if (now == Long.MIN_VALUE || !skipped(pc.key(), now)) return pc;
				long at = FAILED.get(pc.key());
				if (at < waitingAt) { waiting = pc; waitingAt = at; }
			}
			if (waiting != null) return waiting;
			if (phase == M3_4) return null;
			if (!done(phase, b)) say.accept(phase, SHORT);
			phase++;
			say.accept(phase, BEGUN);
		}
	}

	/** The line a phase says when it begins: "route: phase m2.1 armour". */
	static String line(int i) { return "route: phase " + PHASES.get(i) + " " + WORDS.get(i); }

	/** The line a phase says when it is left unmet: "route: phase m2.1 short". */
	static String shortLine(int i) { return "route: phase " + PHASES.get(i) + " " + SHORT; }

	/**
	 * Whether the bag meets the phase, as the practice rig reads it: a pickaxe; a stone one or better and a furnace held at
	 * some time; eight food and a bed; three iron (or better) pieces worn and a shield; two iron pickaxes, an iron sword and
	 * a water bucket; a diamond pickaxe and sword and redstone; ten obsidian; 96 gold with a golden helmet worn and a spare;
	 * that gold and a slime ball (a ball alone is no progress: a bare start may carry one).
	 * The portal's phase is the nether itself, never the bag.
	 */
	static boolean met(int i, Bag b, boolean furnaceHeld) {
		return switch (i) {
			case M1_1 -> anyOf(b, "wooden_pickaxe", "stone_pickaxe", "iron_pickaxe", "golden_pickaxe", "diamond_pickaxe", "netherite_pickaxe");
			case M1_2 -> anyOf(b, "stone_pickaxe", "iron_pickaxe", "diamond_pickaxe", "netherite_pickaxe") && furnaceHeld;
			case M1_3 -> food(b) >= 8 && b.invEnding("_bed") > 0;
			case M2_1 -> ironUpWorn(b) + (goldBoots(b) ? 1 : 0) >= 4 && anyOf(b, "shield");
			case M2_2 -> picksUp(b) >= 2 && anyOf(b, "iron_sword", "diamond_sword", "netherite_sword") && anyOf(b, "water_bucket");
			case M3_1 -> anyOf(b, "diamond_pickaxe", "netherite_pickaxe") && anyOf(b, "diamond_sword", "netherite_sword") && anyOf(b, "redstone");
			case M3_2 -> all(b, "obsidian") >= 10;
			case M3_3 -> all(b, "gold_ingot") >= tradeGold(b) && goldBoots(b);
			case M3_3B -> met(M3_3, b, furnaceHeld) && all(b, "slime_ball") >= 1;
			default -> false;
		};
	}

	/**
	 * Whether the route has the phase: met, with the furnace this order held, and what the route fetches for the next
	 * phase besides: sixteen logs in M1.1 (planks a quarter) and the water bucket in M3.2; a bed put down for the night
	 * near by counts as the bed.
	 */
	static boolean done(int i, Bag b) {
		boolean m = met(i, b, furnaceHeld || b.inv("furnace") > 0);
		return switch (i) {
			case M1_1 -> m && wood(b) >= WOOD;
			case M1_3 -> m || food(b) >= 8 && b.bedNear();
			case M3_2 -> m && b.inv("water_bucket") > 0;
			default -> m;
		};
	}

	/**
	 * (Tuning gold_runs_on, a fix of 14 Nov 2026, on) the gold piece of M3.3 runs on past its 40 minutes while the gold rises: the
	 * mine takes 15 to 18 gold ores a game hour, so 96 and the helmets' ten take about six hours, and the route gave the piece three
	 * tries of 40 minutes; 5 of 22 runs of the test heel gave the gold up while it still rose, one at 90 of 101. A try past its
	 * time ends once GOLD_NO_RISE passes with no rise; all the piece's tries together end at GOLD_ALL.
	 */
	static final long GOLD_NO_RISE = 20 * 60_000L, GOLD_ALL = 6 * 3600_000L;

	/** The gold piece's key (only M3.3's: the slime ball and the helmets keep their own time). */
	static final String GOLD_KEY = PHASES.get(M3_3) + "/gold";

	/** M3.4's gold piece: the M3 kit's gold asked again before the portal (m34_kit_again). */
	static final String GOLD_KEY_34 = PHASES.get(M3_4) + "/gold";

	/**
	 * A piece on the gold clock: M3.3's gold, and with gold_clock_fix M3.4's as well, on the same budget. (a fix of 16 Nov 2026,
	 * on: in 164 of 166 runs of the test heel that began M3.4, M3.3's gold was given up as M3.4 began, and M3.4 asked the gold
	 * again as three plain 40-minute tries; 31 of the 65 runs lost in M3.4 were lost in that second gold mine)
	 */
	static boolean goldPiece(String key, boolean fix) { return GOLD_KEY.equals(key) || fix && GOLD_KEY_34.equals(key); }

	/** The game ms the gold pieces' tries of this order ran, all of them together (gold_runs_on's six hours). */
	private static long goldSpent;
	/** (gold_clock_fix) the last gold try that ended did so with no rise in the gold; M3.4's message about it said. */
	private static boolean goldStopped, goldPassSaid;
	/** (gold_keep_picks) a gold try ended for worn pickaxes: no gold piece is asked again this order. */
	private static boolean goldPicksWorn;
	/** The order the gold's clock counts for: a try begun in an earlier order banks nothing into this one's. */
	private static int goldOrder;

	static long goldSpent() { return goldSpent; }

	static int goldOrder() { return goldOrder; }

	/** A try of a gold piece ran this long (game ms): into the budget. */
	static void goldBank(long ms) { if (ms > 0) goldSpent += ms; }

	/**
	 * (slime_clock_fix) the game ms the slime ball's search ran in tries of this order that were stopped from outside (food, a
	 * shield, the night's hole): the search's forty minutes are one search over all of them (steering 204). Without it each
	 * stop began the forty minutes again: 69 of 179 searches given up in heel-254..257 ran past 41 minutes, up to 84, and
	 * two runs stalled in the search when the rig's 45 minutes without progress came first (the read heel-slime-search,
	 * 26 Nov 2026).
	 */
	private static long slimeSpent;

	static long slimeSpent() { return slimeSpent; }

	/** A stopped try of the slime search ran this long (game ms): into the search's forty minutes. */
	static void slimeBank(long ms) { if (ms > 0) slimeSpent += ms; }

	/**
	 * (gold_clock_fix) a try of a gold piece ended (done or failed, not stopped from outside): with no rise in the gold, it has
	 * stopped rising; a try in which it rose and that ended otherwise says it has not.
	 */
	static void goldTryEnded(boolean noRise, boolean rose) {
		if (noRise) goldStopped = true;
		else if (rose) goldStopped = false;
	}

	/** (gold_keep_picks) a gold try ended for worn pickaxes (BranchMineTask.picksWorn): the kit's are kept from here on. */
	static void goldPicksWorn() { goldPicksWorn = true; }

	/** Why the gold is over after {@link #goldPicksWorn}. */
	static final String PICKS_WORN = "pickaxes worn";

	/**
	 * (gold_clock_fix) why the gold is over for this order, so that M3.4 does not mine it again and goes on with what the bag
	 * holds; null while it is not, or without the fix. Its six game hours spent, or its last try ended with no rise; and
	 * (gold_keep_picks, set only with that switch) a try ended for worn pickaxes.
	 */
	static String goldOver(Bag b) {
		if (goldPicksWorn) return PICKS_WORN;
		if (!b.goldClockFix()) return null;
		if (goldSpent >= GOLD_ALL) return "the gold's " + GOLD_ALL / 3_600_000 + " game hours spent";
		if (goldStopped) return "the gold stopped rising";
		return null;
	}

	/**
	 * (gold_clock_fix) once an order: why M3.4 passes its gold over while the bag is short of it, for the line; else null.
	 */
	static String goldPassedOver(Bag b) {
		if (goldPassSaid || !walking() || phase != M3_4 || !b.kitAgain() || b.inv("gold_ingot") >= goldNeed(b)) return null;
		String why = goldOver(b);
		if (why == null || tries(GOLD_KEY_34) >= TRIES) return null;
		goldPassSaid = true;
		return why;
	}

	/** Where a try of the gold piece stands. */
	enum GoldClock { ON, EXTENDED, NO_RISE, ALL_TIME }

	/**
	 * A try that has run {@code ran} ms of its {@code limit}, the gold last rising {@code sinceRise} ms ago (the try's start counts
	 * as a rise), after {@code before} ms of the piece's earlier tries: on within its time, on past it while the gold rose within
	 * GOLD_NO_RISE, over with no rise in that long, and over for all tries at GOLD_ALL.
	 */
	static GoldClock goldClock(long ran, long sinceRise, long before, long limit) {
		if (before + ran >= GOLD_ALL) return GoldClock.ALL_TIME;
		if (ran <= limit) return GoldClock.ON;
		return sinceRise < GOLD_NO_RISE ? GoldClock.EXTENDED : GoldClock.NO_RISE;
	}

	/** The gold the gold piece counts: ingots, raw gold and gold ore, a block as nine, four golden boots worn or carried. */
	static int goldCount(Bag b) {
		return b.inv("gold_ingot") + b.inv("raw_gold") + b.inv("gold_ore") + b.inv("deepslate_gold_ore")
			+ 9 * (b.inv("gold_block") + b.inv("raw_gold_block"))
			+ BOOTS_GOLD * (b.inv("golden_boots") + (goldBoots(b) ? 1 : 0));
	}

	/** The try under way of this piece is its last (gold_runs_on's six hours): its end, counted by {@link #tried}, gives it up. */
	static void lastTry(String key) {
		Piece pc = piece(key);
		int tries = pc == null ? TRIES : pc.tries();
		TRIED.put(key, Math.max(tries(key), tries - 1));
	}

	/** The gold M3.3 wants in the bag: the trade's ({@link #tradeGold}), and four for the golden boots while none are made. */
	static int goldNeed(Bag b) {
		return tradeGold(b) + (goldBoots(b) || b.inv("golden_boots") > 0 ? 0 : BOOTS_GOLD);
	}


	/** Golden boots worn: the piglins leave the buddy be. */
	static boolean goldBoots(Bag b) { return "golden_boots".equals(b.wornIn("boots")); }

	// carried and worn together, as the rig's bag counts the armour slots too
	private static int all(Bag b, String item) { return b.inv(item) + worn(b, item); }

	private static int worn(Bag b, String item) {
		int n = 0;
		for (String s : SLOTS) if (item.equals(b.wornIn(s))) n++;
		return n;
	}

	private static boolean anyOf(Bag b, String... items) {
		for (String i : items) if (all(b, i) > 0) return true;
		return false;
	}

	private static int food(Bag b) {
		int n = 0;
		for (String f : FOOD) n += all(b, f);
		return n;
	}

	// iron, diamond and netherite pickaxes
	private static int picksUp(Bag b) {
		int n = 0;
		for (String t : IRON_UP) n += all(b, t + "_pickaxe");
		return n;
	}

	private static boolean ironUp(String id) { return id != null && IRON_UP.contains(id.split("_")[0]); }

	private static int ironUpWorn(Bag b) {
		int n = 0;
		for (String s : SLOTS) if (ironUp(b.wornIn(s))) n++;
		return n;
	}

	/** Planks in the bag, a log counted as the four it makes. */
	static int wood(Bag b) { return b.invEnding("_planks") + 4 * (b.invEnding("_log") + b.invEnding("_stem")); }

	/** The ingots an iron piece of this slot takes. */
	private static int ingots(String slot) {
		return switch (slot) { case "chestplate" -> 8; case "leggings" -> 7; case "helmet" -> 5; case "boots" -> 4; default -> 1; };
	}

	/**
	 * Copper for a slot while its iron is not to be had yet: no iron piece worn there, the slot empty, the iron for it
	 * not in the bag (raw or smelted, or the piece itself), and no copper piece of it in the bag.
	 */
	private static boolean copperWanted(Bag b, String slot) {
		boolean ironFor = b.inv("iron_ingot") + b.inv("raw_iron") >= ingots(slot) || b.inv("iron_" + slot) > 0;
		return !ironUp(b.wornIn(slot)) && b.wornIn(slot).isEmpty() && !ironFor && b.inv("copper_" + slot) < 1;
	}
}
