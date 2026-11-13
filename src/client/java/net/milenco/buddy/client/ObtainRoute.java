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
 * nether (a diamond pickaxe, ten obsidian, flint and steel, 128 gold, a golden helmet, a slime ball). The kit (Alto.kit) asks the pieces
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

	/** The wood M1.1 fetches, in planks: sixteen logs. */
	static final int WOOD = 16 * 4;

	/** Gold kept for the piglins' trade: what M3 is ready with, besides a golden helmet worn and a spare (96 since 12 Nov
	 * 2026: 64 proved too few for a potion; 128 since that evening: the first potion near certain, 1 in 90 runs without one
	 * against 1 in 30 at 96, and no longer in the nether, as the trade stops at the first potion). */
	static final int GOLD = 128;

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
	/** The pieces' tries, by key. */
	private static final Map<String, Integer> TRIED = new HashMap<>();
	/** The pieces that failed, by key, and when (the order's game clock, ms): passed over for {@link #SKIP} (kit_failed_skip). */
	private static final Map<String, Long> FAILED = new HashMap<>();

	/** How long a piece that failed waits while the phase's other pieces go on: ten game minutes, as the old kit's. */
	static final long SKIP = 600_000;

	private static final String[] SLOTS = { "helmet", "chestplate", "leggings", "boots" };
	private static final List<String> IRON_UP = List.of("iron", "diamond", "netherite");

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
		// yet, then the iron itself, most protection per ingot first. No helmet: the golden one goes there in M3.3
		List.of(piece(M2_1, "shield", b -> b.inv("shield") < 1),
			piece(M2_1, "copper_chestplate", b -> copperWanted(b, "chestplate")),
			piece(M2_1, "copper_leggings", b -> copperWanted(b, "leggings")),
			piece(M2_1, "copper_boots", b -> copperWanted(b, "boots")),
			piece(M2_1, "iron_chestplate", b -> !ironUp(b.wornIn("chestplate"))),
			piece(M2_1, "iron_leggings", b -> !ironUp(b.wornIn("leggings"))),
			piece(M2_1, "iron_boots", b -> !ironUp(b.wornIn("boots")))),
		// a water bucket (a fire put out, a fall caught), two iron pickaxes, an iron sword
		List.of(piece(M2_2, "water bucket", b -> b.inv("water_bucket") < 1),
			piece(M2_2, "iron pickaxes", b -> picksUp(b) < 2),
			piece(M2_2, "iron sword", b -> !anyOf(b, "iron_sword", "diamond_sword", "netherite_sword"))),
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
			piece(M3_3, "golden helmet", b -> !"golden_helmet".equals(b.wornIn("helmet"))),
			piece(M3_3, "golden helmet again", b -> !"golden_helmet".equals(b.wornIn("helmet"))
				&& (b.inv("golden_helmet") > 0 || b.inv("gold_ingot") >= 5)),
			piece(M3_3, "gold", b -> b.inv("gold_ingot") < goldNeed(b)),
			piece(M3_3, "spare golden helmet", b -> b.inv("golden_helmet") < 1)),
		// a slime ball for the brew's cream: slimes deep under the ground in some chunks, the dig at the gold mine's height
		List.of(piece(M3_3B, "slime ball", b -> b.inv("slime_ball") < 1)),
		// up again: flint and steel, the rest of the nether's kit, the bottles filled; then the whole order builds the portal
		List.of(piece(M3_4, "flint and steel", b -> b.inv("flint_and_steel") < 1),
			piece(M3_4, "rest", Bag::restMissing),
			piece(M3_4, "water bottles", b -> b.inv("potion") < 3)));

	private static Piece piece(int phase, String name, Predicate<Bag> wanted) { return new Piece(phase, name, TRIES, wanted); }

	/** A new order: the route off until the order's danger is known, and where it began. */
	static void reset(boolean inOverworld) {
		on = through = furnaceHeld = false;
		overworld = inOverworld;
		phase = M1_1;
		TRIED.clear();
		FAILED.clear();
	}

	/** The order walks the route (its way goes into the nether, and it began in the overworld). */
	static boolean on() { return on; }

	/** On and still in the overworld's phases. */
	static boolean walking() { return on && !through; }

	/** Walking and not yet at that phase. */
	static boolean before(int p) { return walking() && phase < p; }

	static int phase() { return phase; }

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
	 * a water bucket; a diamond pickaxe and sword and redstone; ten obsidian; 128 gold with a golden helmet worn and a spare;
	 * that gold and a slime ball (a ball alone is no progress: a bare start may carry one).
	 * The portal's phase is the nether itself, never the bag.
	 */
	static boolean met(int i, Bag b, boolean furnaceHeld) {
		return switch (i) {
			case M1_1 -> anyOf(b, "wooden_pickaxe", "stone_pickaxe", "iron_pickaxe", "golden_pickaxe", "diamond_pickaxe", "netherite_pickaxe");
			case M1_2 -> anyOf(b, "stone_pickaxe", "iron_pickaxe", "diamond_pickaxe", "netherite_pickaxe") && furnaceHeld;
			case M1_3 -> food(b) >= 8 && b.invEnding("_bed") > 0;
			case M2_1 -> ironUpWorn(b) >= 3 && anyOf(b, "shield");
			case M2_2 -> picksUp(b) >= 2 && anyOf(b, "iron_sword", "diamond_sword", "netherite_sword") && anyOf(b, "water_bucket");
			case M3_1 -> anyOf(b, "diamond_pickaxe", "netherite_pickaxe") && anyOf(b, "diamond_sword", "netherite_sword") && anyOf(b, "redstone");
			case M3_2 -> all(b, "obsidian") >= 10;
			case M3_3 -> all(b, "gold_ingot") >= GOLD && worn(b, "golden_helmet") > 0 && all(b, "golden_helmet") >= 2;
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

	/** The gold M3.3 wants in the bag: the trade's, and five for each golden helmet still to make. */
	static int goldNeed(Bag b) {
		return GOLD + ("golden_helmet".equals(b.wornIn("helmet")) ? 0 : 5) + (b.inv("golden_helmet") > 0 ? 0 : 5);
	}

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
