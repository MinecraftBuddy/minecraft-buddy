package net.milenco.buddy.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The way to an order, worked out without the game: what the buddy has, what the base holds, what it sees around it,
 * and the game's recipes and drops ({@link Recipes}, {@link Drops}) with where things are found ({@link Nature}). It
 * gives the whole list of steps in the order they have to be done, and the order's next step, which is all the buddy
 * acts on: after every step it asks again, so a step that brought more or less than planned, a chest found or a way
 * that failed is taken into account (AltoClef re-evaluates its task tree every tick in the same spirit).
 *
 * <p>The list is made by playing the order out on a ledger: what the buddy carries is used first, then the base's
 * chests, and for what is still missing the cheapest way is chosen, its ingredients, tool and station planned before
 * it, and what a craft makes beyond the need kept for the next part. Costs are guesses in seconds, the same for every
 * way, with what the buddy sees near (a tree in view) made cheap: so a birch forest leads to birch planks and not to an
 * oak somewhere.
 */
final class ObtainPlan {
	private ObtainPlan() {}

	/** What the planner may know of the world: filled from the game, or by a test. */
	interface Have {
		/** In the buddy's inventory. */
		int inv(String item);

		/** Worn (armour), not in the inventory: for what a kit asks, never for an order. */
		default int worn(String item) { return 0; }

		/** In the chests of the base it may take from for this order. */
		default int base(String item) { return 0; }

		/** A crafting table, furnace, stonecutter or smithing table stands where the buddy works (no need to carry one). */
		default boolean station(String block) { return false; }

		/** The dimension it is in. */
		default Nature.Dim dim() { return Nature.Dim.OVERWORLD; }

		/** Blocks away to the nearest of these blocks it knows (seen and trusted), or -1. */
		default double near(String block) { return -1; }

		/** Blocks away to the nearest mob of this kind it sees, or -1. */
		default double nearMob(String mob) { return -1; }

		/** A way that failed lately ("mine:oak_log", "kill:cow", "craft:furnace"): not tried again for a while. */
		default boolean blocked(String way) { return false; }

		/** It carries a tool with silk touch. */
		default boolean silk() { return false; }

		/** A portal to this dimension is known where it is now (it went through one, or built one). */
		default boolean portal(Nature.Dim to) { return false; }

		/** A place of ours is found already ("@stronghold": its portal room seen). */
		default boolean known(String place) { return false; }
	}

	enum Act {
		/** Out of a chest of the base. */ TAKE,
		/** In the 2x2 grid or at a table. */ CRAFT,
		/** In a furnace. */ SMELT,
		/** At a stonecutter or a smithing table. */ STATION,
		/** Break blocks of these kinds and pick up what they drop. */ MINE,
		/** Kill mobs of this kind and pick up what they drop. */ KILL,
		/** Gold ingots thrown to piglins. */ BARTER,
		/** Something of our own (a bucket filled, a portal, the End). */ SPECIAL
	}

	/**
	 * One step: what to do ({@code act}), what it is for ({@code item}, {@code count}: what the step makes), and how:
	 * the recipe and the number of crafts for CRAFT, SMELT and STATION, the blocks or the mob for MINE and KILL (any of
	 * them), the special's name for SPECIAL.
	 */
	record Step(Act act, String item, int count, Recipes.Recipe recipe, int times, List<String> sources, String special) {
		String way() {
			return switch (act) {
				case TAKE -> "take:" + item;
				case CRAFT, SMELT, STATION -> act.name().toLowerCase() + ":" + recipe.id();
				case MINE -> "mine:" + String.join(",", sources);
				case KILL -> "kill:" + String.join(",", sources);
				case BARTER -> "barter:" + item;
				case SPECIAL -> "special:" + special;
			};
		}

		@Override public String toString() {
			return switch (act) {
				case TAKE -> "take " + count + " " + item;
				case CRAFT, SMELT, STATION -> act.name().toLowerCase() + " " + recipe.id() + " x" + times + " -> " + count + " " + item;
				case MINE -> "mine " + sources + " for " + count + " " + item;
				case KILL -> "kill " + sources + " for " + count + " " + item;
				case BARTER -> "barter for " + count + " " + item;
				case SPECIAL -> special + " -> " + count + " " + item;
			};
		}
	}

	/** The whole plan, or what kept it from one. */
	record Plan(List<Step> steps, String missing) {
		boolean ok() { return missing == null; }

		/**
		 * The step to do now. A gathering step is done for everything the plan gathers that way, so the buddy does not walk
		 * to the same trees three times for the planks, the sticks and the table.
		 */
		Step next() {
			if (steps.isEmpty()) return null;
			Step s = steps.get(0);
			if (s.act() != Act.MINE && s.act() != Act.KILL && s.act() != Act.TAKE) return s;
			int n = 0;
			for (Step o : steps) if (o.act() == s.act() && o.way().equals(s.way()) && o.item().equals(s.item())) n += o.count();
			return new Step(s.act(), s.item(), n, s.recipe(), s.times(), s.sources(), s.special());
		}
	}

	/** One item, one way to it from scratch, with what that costs. */
	static final double NEVER = 1e12;
	private static final int DEPTH = 40;

	// ---------- facts ----------

	/** Blocks that a pickaxe breaks but that drop without one. */
	private static final Set<String> NO_TOOL = Set.of("glowstone", "ice", "packed_ice", "blue_ice", "glass", "sea_lantern", "redstone_lamp", "beacon");

	/** The tiers of the tools, cheapest first. */
	private static final String[] TIERS = { "wooden", "stone", "iron", "diamond", "netherite" };

	/** What a block or mob gives on average per one broken or killed, where that is more than one. */
	private static final Map<String, Double> YIELD = Map.ofEntries(
		Map.entry("redstone", 4.5), Map.entry("lapis_lazuli", 6.0), Map.entry("glowstone_dust", 3.0), Map.entry("clay_ball", 4.0),
		Map.entry("melon_slice", 5.0), Map.entry("snowball", 4.0), Map.entry("raw_copper", 3.5), Map.entry("nether_wart", 3.0),
		Map.entry("string", 1.0), Map.entry("bone", 1.0), Map.entry("gunpowder", 1.0), Map.entry("leather", 1.0),
		Map.entry("beef", 2.0), Map.entry("porkchop", 2.0), Map.entry("mutton", 1.5), Map.entry("chicken", 1.0),
		Map.entry("feather", 1.0), Map.entry("rotten_flesh", 1.0), Map.entry("ender_pearl", 0.5), Map.entry("blaze_rod", 0.5),
		Map.entry("gold_nugget", 3.0), Map.entry("quartz", 1.0), Map.entry("flint", 0.1), Map.entry("wheat_seeds", 0.2),
		Map.entry("stick", 0.1), Map.entry("apple", 0.01));

	/** What burns, in items smelted per item burnt. */
	static final Map<String, Double> FUEL = Map.ofEntries(
		Map.entry("coal", 8.0), Map.entry("charcoal", 8.0), Map.entry("coal_block", 80.0), Map.entry("blaze_rod", 12.0),
		Map.entry("dried_kelp_block", 20.0), Map.entry("lava_bucket", 100.0));

	static double fuelValue(String item) {
		Double v = FUEL.get(item);
		if (v != null) return v;
		if (VanillaData.tag("item", "logs").contains(item) || VanillaData.tag("item", "planks").contains(item)) return 1.5;
		if (item.equals("stick")) return 0.5;
		return 0;
	}

	/**
	 * What the buddy burns: what burns, but never blaze rods or a bucket. The rods are the brew's and the eyes', a lava
	 * bucket is the obsidian's (and it leaves its bucket in the furnace's fuel slot).
	 */
	static boolean burns(String item) {
		return fuelValue(item) > 0 && !item.equals("blaze_rod") && !item.endsWith("bucket");
	}

	/**
	 * The fuel for {@code times} smelts out of the bag's slots ({@code ids} and {@code counts}, null for an empty slot,
	 * {@code taken} of each gone in as the ingredient already): how many of each slot go into the fuel slot, all of one
	 * kind, for the slot holds one. A kind that burns the lot, one that is not the ingredient first; the ingredient itself
	 * only beyond what went in (logs burnt for charcoal: the planner counts them as its fuel, and every log was passed
	 * over as the ingredient, so nothing lit). No kind enough on its own: the one that gets furthest.
	 */
	static int[] fuelPlan(String[] ids, int[] counts, int[] taken, java.util.function.Predicate<String> ingredient, int times) {
		Map<String, Double> worth = new LinkedHashMap<>();
		for (int i = 0; i < ids.length; i++)
			if (ids[i] != null && burns(ids[i]) && counts[i] - taken[i] > 0) worth.merge(ids[i], (counts[i] - taken[i]) * fuelValue(ids[i]), Double::sum);
		String kind = null;
		boolean kindEnough = false, kindOwn = false;
		double kindWorth = 0;
		for (Map.Entry<String, Double> e : worth.entrySet()) {
			boolean enough = e.getValue() >= times, own = !ingredient.test(e.getKey());
			boolean better = kind == null || (enough != kindEnough ? enough : enough ? own && !kindOwn : e.getValue() > kindWorth);
			if (!better) continue;
			kind = e.getKey();
			kindEnough = enough;
			kindOwn = own;
			kindWorth = e.getValue();
		}
		int[] out = new int[ids.length];
		if (kind == null) return out;
		double v = fuelValue(kind), left = times;
		for (int i = 0; i < ids.length && left > 0; i++) {
			if (!kind.equals(ids[i])) continue;
			int k = (int) Math.min(counts[i] - taken[i], Math.ceil(left / v));
			if (k <= 0) continue;
			out[i] = k;
			left -= k * v;
		}
		return out;
	}

	/**
	 * The ingredient for {@code times} smelts out of the bag's slots, as {@link #fuelPlan}: how many of each slot go into
	 * the input slot, all of one kind, for a click with another kind onto it swaps the two. The kind already in that slot
	 * ({@code inSlot}, null when it is empty), else the kind the bag holds most of.
	 */
	static int[] inputPlan(String[] ids, int[] counts, java.util.function.Predicate<String> ingredient, String inSlot, int times) {
		String kind = inSlot;
		if (kind == null) {
			Map<String, Integer> have = new LinkedHashMap<>();
			for (int i = 0; i < ids.length; i++) if (ids[i] != null && ingredient.test(ids[i])) have.merge(ids[i], counts[i], Integer::sum);
			for (Map.Entry<String, Integer> e : have.entrySet()) if (kind == null || e.getValue() > have.get(kind)) kind = e.getKey();
		}
		int[] out = new int[ids.length];
		if (kind == null || !ingredient.test(kind)) return out;
		int left = times;
		for (int i = 0; i < ids.length && left > 0; i++) {
			if (!kind.equals(ids[i])) continue;
			out[i] = Math.min(left, counts[i]);
			left -= out[i];
		}
		return out;
	}

	/**
	 * Our own ways to items no recipe or drop gives: the name of the special and what it takes for one. A name with a
	 * {@code +} before it is a tool the way keeps (a water bucket to cast obsidian with): wanted once, whatever the count.
	 */
	record Special(String name, Map<String, Integer> takes, double cost) {}

	private static final Map<String, List<Special>> SPECIALS = new HashMap<>();

	private static void special(String item, String name, double cost, Object... takes) {
		Map<String, Integer> m = new LinkedHashMap<>();
		for (int i = 0; i + 1 < takes.length; i += 2) m.put((String) takes[i], (Integer) takes[i + 1]);
		SPECIALS.computeIfAbsent(item, k -> new ArrayList<>()).add(new Special(name, m, cost));
	}

	static {
		special("water_bucket", "fill water", 15, "bucket", 1);
		special("lava_bucket", "fill lava", 60, "bucket", 1);
		special("milk_bucket", "milk a cow", 40, "bucket", 1);
		special("powder_snow_bucket", "fill powder snow", 300, "bucket", 1);
		// AltoClef's obsidian: water poured over a lava pool turns its sources into obsidian, mined with a diamond pickaxe
		// (9.4 s a block). Dearer per block than the few that lie about, cheaper than a search for them. The lava comes in a
		// bucket of its own, filled for each block (ObtainSteps.Cast): without an empty one beside the water's it failed
		// "no bucket"
		special("obsidian", "cast obsidian", 45, "+water_bucket", 1, "+bucket", 1, "+diamond_pickaxe", 1);
		// a log in the world clicked with an axe, then chopped: every stripped log and stem (AltoClef strips the same way).
		// Dear on purpose: a stripped log fits wherever a log does (#logs), and planks must never be planned by way of an
		// axe made of planks
		for (String w : List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak"))
			special("stripped_" + w + "_log", "strip a log", 40, "+tool:axe:0", 1);
		for (String w : List.of("crimson", "warped")) special("stripped_" + w + "_stem", "strip a log", 40, "+tool:axe:0", 1);
		// the elytra hangs in an End ship's item frame: the End's outer islands, after the dragon
		special("elytra", "end ship", 3600, "@end", 1);
		special("dragon_head", "end ship", 3600, "@end", 1);
		special("shulker_shell", "end city", 1800, "@end", 1);
		// a dimension as an item: what it takes to get there
		special("@nether", "nether portal", 300, "obsidian", 10, "flint_and_steel", 1);
		special("@end", "end portal", 1800, "ender_eye", 12, "@stronghold", 1);
		special("@stronghold", "find stronghold", 900, "ender_eye", 2);
		special("@overworld", "back to the overworld", 120);
		// crops are grown: a field of its own, sown and harvested when ripe (the seeds from grass)
		special("wheat", "grow crops", 900, "wheat_seeds", 2);
		special("carrot", "grow crops", 900, "carrot", 1);
		special("potato", "grow crops", 900, "potato", 1);
		special("beetroot", "grow crops", 900, "beetroot_seeds", 2);
	}

	// ---------- the planner ----------

	/** The plan for count of this item with what there is. */
	static Plan plan(String item, int count, Have have) {
		return new Planner(have).run(VanillaData.plain(item), count);
	}

	/**
	 * Several needs in one plan (all a kit lacks): one ledger, so that the gathering of one step is done for all of them at
	 * once (the iron for the helmet, the chestplate, the leggings and the boots in one mine, where a need at a time made six
	 * trips). A need without a way is left out; null when none has one.
	 */
	static Plan planAll(List<List<String>> anys, List<Integer> counts, Have have) {
		Planner pl = new Planner(have);
		boolean any = false;
		for (int i = 0; i < anys.size(); i++) {
			int before = pl.steps.size();
			Map<String, Integer> inv = new HashMap<>(pl.inv);
			if (pl.needAny(anys.get(i).stream().map(VanillaData::plain).toList(), counts.get(i), 0, new ArrayDeque<>())) any = true;
			else { while (pl.steps.size() > before) pl.steps.remove(pl.steps.size() - 1); pl.inv.clear(); pl.inv.putAll(inv); }
		}
		return any ? new Plan(List.copyOf(pl.steps), null) : null;
	}

	/** The plan for count of any of these items together (a kit's food, any sword of a tier). */
	static Plan planAny(List<String> items, int count, Have have) {
		Planner pl = new Planner(have);
		boolean ok = pl.needAny(items.stream().map(VanillaData::plain).toList(), count, 0, new ArrayDeque<>());
		return new Plan(List.copyOf(pl.steps), ok ? null : pl.missing == null ? "no way to any of " + items : pl.missing);
	}

	/**
	 * What one of each of these items costs to come back for later, with none of its blocks or mobs in sight: the cheapest
	 * way from scratch, the place's guess for finding it and the tool's share included. The value of taking one along on
	 * the way (ObtainWish). NEVER for an item with no way.
	 */
	static Map<String, Double> unseenCosts(List<String> items, Have have) {
		Have blind = new Have() {
			public int inv(String item) { return have.inv(item); }
			public int worn(String item) { return have.worn(item); }
			public int base(String item) { return have.base(item); }
			public boolean station(String block) { return have.station(block); }
			public Nature.Dim dim() { return have.dim(); }
			public boolean blocked(String way) { return have.blocked(way); }
			public boolean silk() { return have.silk(); }
			public boolean portal(Nature.Dim to) { return have.portal(to); }
			public boolean known(String place) { return have.known(place); }
		};
		Map<String, Double> out = new java.util.LinkedHashMap<>();
		// a planner each: a cost worked out inside another item's question can be kept too high (a cycle cut short), and
		// the next item would read it from the shared ledger (raw iron came out at a billion after a pickaxe)
		for (String item : items) out.put(item, new Planner(blind).cost(VanillaData.plain(item)));
		return out;
	}

	/** What an order of this item can be: it has a recipe, a drop, or a way of ours. */
	/**
	 * Every item of the game that can be planned from an empty bag in the overworld, sorted: the panel's list of what can
	 * be ordered. Pure, from the game's data alone; a few seconds, so once and not on the game's thread.
	 */
	static List<String> obtainable() {
		Set<String> all = new java.util.TreeSet<>();
		for (List<Recipes.Recipe> rs : Recipes.all().values()) for (Recipes.Recipe r : rs) all.add(r.result());
		all.addAll(Drops.items());
		all.addAll(SPECIALS.keySet());
		Have none = new Have() { public int inv(String x) { return 0; } };
		List<String> out = new ArrayList<>();
		for (String it : all) {
			if (it.startsWith("@")) continue;   // a dimension or a place: a step on the way, not an item
			try { if (plan(it, 1, none).ok()) out.add(it); } catch (RuntimeException ignored) {}
		}
		return out;
	}

	static boolean known(String item) {
		item = VanillaData.plain(item);
		return !Recipes.making(item).isEmpty() || !Drops.of(item).isEmpty() || SPECIALS.containsKey(item);
	}

	private static final class Planner {
		final Have have;
		final Map<String, Integer> inv = new HashMap<>(), base = new HashMap<>();
		final List<Step> steps = new ArrayList<>();
		Map<String, Double> costs = new HashMap<>();
		final Deque<String> costStack = new ArrayDeque<>();
		/** How often a cost ran into an item still being worked out: such a cost is not kept, it may be too high. */
		int cycles;
		final Set<String> placed = new LinkedHashSet<>();
		Nature.Dim dim;
		String missing;

		Planner(Have have) { this.have = have; this.dim = have.dim(); }

		Plan run(String item, int count) {
			boolean ok = need(item, count, 0, new ArrayDeque<>());
			return new Plan(ok ? List.copyOf(steps) : List.copyOf(steps), ok ? null : missing == null ? "no way to " + item : missing);
		}

		int invLeft(String item) { return inv.computeIfAbsent(item, have::inv); }

		int baseLeft(String item) { return base.computeIfAbsent(item, have::base); }

		void add(String item, int n) { inv.merge(item, n, Integer::sum); invLeft(item); }

		/** Takes up to n from what the buddy carries; how many. */
		int use(String item, int n) {
			int left = invLeft(item), u = Math.min(left, n);
			inv.put(item, left - u);
			return u;
		}

		/** n of this item planned for (used up by what asks for it). */
		boolean need(String item, int n, int depth, Deque<String> stack) {
			if (n <= 0) return true;
			n -= use(item, n);
			if (n <= 0) return true;
			if (item.startsWith("@")) return dimension(item, depth, stack);
			int fromBase = Math.min(n, baseLeft(item));
			if (fromBase > 0) {
				base.put(item, baseLeft(item) - fromBase);
				steps.add(new Step(Act.TAKE, item, fromBase, null, 0, List.of(), null));
				n -= fromBase;
				if (n <= 0) return true;
			}
			if (depth > DEPTH || stack.contains(item)) { missing = "too deep at " + item; return false; }
			stack.push(item);
			try {
				Way best = best(item, n, stack);
				if (best == null) { missing = missing != null ? missing : "no way to " + item; return false; }
				return best.plan(this, item, n, depth, stack);
			} finally {
				stack.pop();
			}
		}

		/**
		 * One of these items, the alternatives of an ingredient: what the buddy carries of any of them first, then the
		 * cheapest.
		 */
		boolean needAny(List<String> items, int n, int depth, Deque<String> stack) {
			for (String it : items) {
				if (n <= 0) return true;
				n -= use(it, n);
			}
			if (n <= 0) return true;
			for (String it : items) {
				if (n <= 0) return true;
				int b = Math.min(n, baseLeft(it));
				if (b > 0) {
					base.put(it, baseLeft(it) - b);
					steps.add(new Step(Act.TAKE, it, b, null, 0, List.of(), null));
					n -= b;
				}
			}
			if (n <= 0) return true;
			// one furnace away from what it carries (five raw chicken and three porkchops are eight meals): those first, before
			// the cheapest kind for the whole count (it went to kill eight cows with eight raw meats in the bag)
			for (String it : items) {
				if (n <= 0) return true;
				for (Recipes.Recipe r : Recipes.making(it)) {
					if (n <= 0 || r.kind() != Recipes.Kind.SMELT || r.parts().size() != 1) continue;
					int avail = 0;
					for (String raw : r.parts().get(0).items()) avail += invLeft(raw);
					int k = Math.min(n, avail * r.count());
					if (k > 0 && need(it, k, depth + 1, stack)) n -= k;
				}
			}
			if (n <= 0) return true;
			String cheapest = inContext(stack, () -> {
				String ch = null;
				double c = NEVER;
				for (String it : items) {
					double x = cost(it);
					if (x < c) { c = x; ch = it; }
				}
				return ch;
			});
			if (cheapest == null) { missing = "no way to any of " + items.subList(0, Math.min(4, items.size())); return false; }
			return need(cheapest, n, depth + 1, stack);
		}

		/** None of these is had or made here without going to another dimension first (a guess from the cheapest way). */
		boolean elsewhere(List<String> items, Deque<String> stack) {
			for (String i : items) if (invLeft(i) > 0 || baseLeft(i) > 0) return false;
			return inContext(stack, () -> {
				for (String i : items) {
					Way w = null;
					double c = NEVER;
					for (Way x : ways(i)) { double y = x.cost(this); if (y < c) { c = y; w = x; } }
					if (w instanceof Mine m) { Nature.Place p = Nature.block(m.block()); if (p == null || p.in(dim)) return false; }
					else if (w instanceof Kill k) { Nature.Place p = Nature.mob(k.mob()); if (p == null || p.in(dim)) return false; }
					else if (w != null && !(w instanceof Barter)) return false;
				}
				return true;
			});
		}

		/** A tool, a station or an item kept: one there, not used up. */
		boolean keep(String item, int depth, Deque<String> stack) {
			if (invLeft(item) > 0) return true;
			if (!need(item, 1, depth + 1, stack)) return false;
			add(item, 1);   // made or taken, and kept
			return true;
		}

		/** "+tool:axe:0": nothing when one of that kind and tier or better is there, else the cheapest to make. */
		double toolCost(String key) {
			String[] kt = key.split(":");
			int tier = Integer.parseInt(kt[2]);
			for (int t = tier; t < TIERS.length; t++) if (invLeft(TIERS[t] + "_" + kt[1]) > 0) return 0;
			// the lowest tier asked only: the costs of the better ones run through diamonds and back to iron, and a cost asked
			// from inside another made every iron plan come out as "no way to diamond"
			return cost(TIERS[tier] + "_" + kt[1]);
		}

		/** A pickaxe (or axe, shovel, hoe) of at least this tier, kept. */
		boolean keepTool(String kind, int tier, int depth, Deque<String> stack) {
			for (int t = tier; t < TIERS.length; t++) if (invLeft(TIERS[t] + "_" + kind) > 0) return true;
			// the cheapest tier as seen from here: a stone pickaxe to mine the stone it is made of is no way (a stone pickaxe
			// came out a little cheaper than a wooden one in a forest with stone in view, and the plan went round in a circle
			// and gave up as "too deep at stone_pickaxe")
			String best = inContext(stack, () -> {
				String b = null;
				double c = NEVER;
				for (int t = tier; t < TIERS.length; t++) {
					double x = cost(TIERS[t] + "_" + kind) + (baseLeft(TIERS[t] + "_" + kind) > 0 ? -1e6 : 0);
					if (x < c) { c = x; b = TIERS[t] + "_" + kind; }
				}
				return b;
			});
			if (best == null) { missing = "no " + TIERS[tier] + " " + kind; return false; }
			return keep(best, depth, stack);
		}

		/** A station within reach: one standing there, or one carried to place. */
		boolean station(String block, int depth, Deque<String> stack) {
			if (have.station(block) || placed.contains(block)) return true;
			if (!keep(block, depth, stack)) return false;
			placed.add(block);
			return true;
		}

		boolean dimension(String d, int depth, Deque<String> stack) {
			Nature.Dim to = switch (d) { case "@nether" -> Nature.Dim.NETHER; case "@end" -> Nature.Dim.END; case "@overworld" -> Nature.Dim.OVERWORLD; default -> null; };
			if (to != null && dim == to) return true;
			if (to == null && have.known(d)) return true;
			if (to == Nature.Dim.NETHER && have.portal(to)) {
				steps.add(new Step(Act.SPECIAL, d, 1, null, 0, List.of(), "through the portal"));
				dim = to;
				return true;
			}
			List<Special> ways = SPECIALS.get(d);
			if (ways == null) { missing = "no way to " + d; return false; }
			Special sp = ways.get(0);
			// what it takes first (the eyes made in the nether, where the blazes are), then to the overworld, from which the
			// End's portal and the nether's go
			for (Map.Entry<String, Integer> t : sp.takes().entrySet()) if (!need(t.getKey(), t.getValue(), depth + 1, stack)) return false;
			if ((to == Nature.Dim.END || to == Nature.Dim.NETHER) && dim != Nature.Dim.OVERWORLD && !need("@overworld", 1, depth + 1, stack)) return false;
			steps.add(new Step(Act.SPECIAL, d, 1, null, 0, List.of(), sp.name()));
			if (to != null) dim = to;
			return true;
		}

		/** Its dimension, or the overworld when it does not say. */
		Nature.Dim dimOf(Nature.Place p) { return p == null ? Nature.Dim.OVERWORLD : p.dim(); }

		/** Getting into the dimension a way needs, planned before the way; where it is found here too, here. */
		boolean into(Nature.Place p, int depth, Deque<String> stack) {
			if (p == null || p.in(dim)) return true;
			return into(p.dim(), depth, stack);
		}

		boolean into(Nature.Dim d, int depth, Deque<String> stack) {
			if (d == dim) return true;
			return need(switch (d) { case NETHER -> "@nether"; case END -> "@end"; default -> "@overworld"; }, 1, depth + 1, stack);
		}

		// ---------- the ways and their costs ----------

		Way best(String item, int n, Deque<String> stack) {
			return inContext(stack, () -> {
				Way best = null;
				double c = NEVER;
				for (Way w : ways(item)) {
					if (have.blocked(w.key())) continue;
					double x = w.costWith(this);
					if (x < c) { c = x; best = w; }
				}
				return best;
			});
		}

		/**
		 * Costs worked out as seen from where the planning is: what is being planned on the way here cannot be a way to
		 * itself (iron ingots from an iron block, when the block is made of ingots; charcoal fuelled by charcoal), so those
		 * items count as out of reach, and costs worked out that way are kept only for this question.
		 */
		<T> T inContext(Deque<String> stack, java.util.function.Supplier<T> f) {
			Map<String, Double> saved = costs;
			costs = new HashMap<>();
			int pushed = 0;
			for (String s : stack) { costStack.push(s); pushed++; }
			try {
				return f.get();
			} finally {
				for (int i = 0; i < pushed; i++) costStack.pop();
				costs = saved;
			}
		}

		List<Way> ways(String item) {
			List<Way> out = new ArrayList<>();
			for (Recipes.Recipe r : Recipes.making(item)) {
				if (r.parts().stream().anyMatch(i -> i.test(item))) continue;   // a recipe that takes what it makes
				out.add(new Craft(r));
			}
			Set<String> blocks = new LinkedHashSet<>(), mobs = new LinkedHashSet<>();
			boolean barter = false;
			for (Drops.Drop d : Drops.of(item)) {
				if (d.silk() == Boolean.TRUE && !have.silk()) continue;
				if (!d.state().isEmpty()) continue;   // a ripe crop: not in the wild, a field's to give
				if (d.from() == Drops.From.BLOCK && Nature.block(d.source()) != null) {
					// the block's own item from the block, also without silk touch where the drop is the block itself
					blocks.add(d.source());
				} else if (d.from() == Drops.From.ENTITY && Nature.mob(d.source()) != null) mobs.add(d.source());
				else if (d.from() == Drops.From.BARTER) barter = true;
			}
			for (String b : blocks) out.add(new Mine(item, b, odds(item, Drops.From.BLOCK, b)));
			// a mob's rare drop (a zombie's iron ingot, carrot or potato) is no way: hunting for it is slow and in the dark
			for (String m : mobs) { int o = odds(item, Drops.From.ENTITY, m); if (o < 2) out.add(new Kill(item, m, o)); }
			if (barter) out.add(new Barter(item));
			for (Special s : SPECIALS.getOrDefault(item, List.of())) out.add(new Spec(item, s));
			return out;
		}

		/** 2 when the drop is rare, 1 when it may fail, 0 when it always comes. */
		static int odds(String item, Drops.From from, String source) {
			int o = 2;
			for (Drops.Drop d : Drops.of(item)) if (d.from() == from && d.source().equals(source)) o = Math.min(o, d.rare() ? 2 : d.chance() ? 1 : 0);
			return o;
		}

		/** What one of this item costs from scratch, the cheapest way; NEVER when there is none. */
		double cost(String item) {
			Double have = costs.get(item);
			if (have != null) return have;
			if (costStack.contains(item) || costStack.size() > DEPTH) { cycles++; return NEVER; }
			costStack.push(item);
			int before = cycles;
			double c = NEVER;
			try {
				for (Way w : ways(item)) {
					if (this.have.blocked(w.key())) continue;
					c = Math.min(c, w.cost(this));
				}
			} finally {
				costStack.pop();
			}
			// worked out without running into one of its own callers: right for good; otherwise only for this question
			if (cycles == before || c < NEVER) costs.put(item, c);
			return c;
		}

		/** What n of these cost, what the buddy has counted free. */
		double costOf(String item, double n) {
			double short_ = Math.max(0, n - invLeft(item) - baseLeft(item) * 0.95);
			return short_ <= 0 ? 0 : short_ * cost(item);
		}

		double costAny(List<String> items, double n) {
			double have = 0;
			for (String i : items) have += invLeft(i) + baseLeft(i);
			if (have >= n) return 0;
			double c = NEVER;
			for (String i : items) c = Math.min(c, cost(i));
			return (n - have) * c;
		}

		/** What a tool of a tier costs from scratch, nothing when the buddy has one at least as good. */
		double toolCost(String kind, int tier) {
			for (int t = tier; t < TIERS.length; t++) if (invLeft(TIERS[t] + "_" + kind) > 0 || baseLeft(TIERS[t] + "_" + kind) > 0) return 0;
			double c = NEVER;
			for (int t = tier; t < TIERS.length; t++) c = Math.min(c, cost(TIERS[t] + "_" + kind));
			return c;
		}

		double stationCost(String block) { return have.station(block) || placed.contains(block) ? 0 : costOf(block, 1); }

		/** Walking to the nearest one the buddy knows; the place's own guess when it knows none. */
		double findCost(Nature.Place p, double near) {
			// one in view never costs more than the guess for one still to be found, which pays for the search on top
			double base = near >= 0 ? Math.min(2 + near / 4.0, p.cost()) : p.cost() + 6;
			// what lives in water is had swimming, with the drowned about: an animal of the fields still to be found comes first
			// (fish in view took it to sea twice, and a drowned killed it there)
			if (p.where() == Nature.Where.WATER) base += 40;
			if (!p.in(dim)) base += p.dim() == Nature.Dim.END ? 3000 : 600;
			return base;
		}
	}

	// ---------- ways ----------

	private interface Way {
		String key();
		/** One item from scratch. */
		double cost(Planner pl);
		/** This way now, with what there is. */
		double costWith(Planner pl);
		boolean plan(Planner pl, String item, int n, int depth, Deque<String> stack);
	}

	/** Which tool a block takes to drop anything, by the game's tags: kind and tier, or null when a hand does. */
	static String[] toolFor(String block) {
		boolean pick = VanillaData.tag("block", "mineable/pickaxe").contains(block) && !NO_TOOL.contains(block);
		if (block.equals("snow") || block.equals("snow_block")) return new String[] { "shovel", "0" };
		if (!pick) return null;
		int tier = VanillaData.tag("block", "needs_diamond_tool").contains(block) ? 3
			: VanillaData.tag("block", "needs_iron_tool").contains(block) ? 2
			: VanillaData.tag("block", "needs_stone_tool").contains(block) ? 1 : 0;
		return new String[] { "pickaxe", String.valueOf(tier) };
	}

	private record Craft(Recipes.Recipe r) implements Way {
		public String key() { return (r.kind() == Recipes.Kind.SMELT ? "smelt:" : r.kind() == Recipes.Kind.CRAFT ? "craft:" : "station:") + r.id(); }

		String station() {
			return switch (r.kind()) {
				case CRAFT -> r.small() ? null : "crafting_table";
				case SMELT -> "furnace";
				case CAMPFIRE -> "campfire";
				case STONECUT -> "stonecutter";
				case SMITH -> "smithing_table";
			};
		}

		public double cost(Planner pl) {
			double c = 1;
			for (Map.Entry<Recipes.Ing, Integer> e : r.perCraft().entrySet()) {
				double m = NEVER;
				for (String i : e.getKey().items()) m = Math.min(m, pl.cost(i));
				if (m >= NEVER) return NEVER;
				c += m * e.getValue();
			}
			if (r.kind() == Recipes.Kind.SMELT) c += 10 + pl.cost("coal") / 8;
			if (r.kind() == Recipes.Kind.CAMPFIRE) c += 30;
			if (r.kind() == Recipes.Kind.STONECUT || r.kind() == Recipes.Kind.SMITH) c += 5;
			return c / r.count();
		}

		public double costWith(Planner pl) {
			double c = 1;
			for (Map.Entry<Recipes.Ing, Integer> e : r.perCraft().entrySet()) {
				double x = pl.costAny(e.getKey().items(), e.getValue());
				if (x >= NEVER) return NEVER;
				c += x;
			}
			String st = station();
			if (st != null) c += Math.min(pl.stationCost(st), NEVER);
			if (r.kind() == Recipes.Kind.SMELT) c += 10;
			return c / r.count();
		}

		public boolean plan(Planner pl, String item, int n, int depth, Deque<String> stack) {
			int times = (n + r.count() - 1) / r.count();
			// smelting runs one at a time and needs fuel for each; crafting makes count per craft
			List<Map.Entry<Recipes.Ing, Integer>> parts = new ArrayList<>(r.perCraft().entrySet());
			// what can be had in the dimension the buddy is in first, so that it does not go through a portal and back for the
			// other
			parts.sort(java.util.Comparator.comparingInt(e -> pl.elsewhere(e.getKey().items(), stack) ? 1 : 0));
			for (Map.Entry<Recipes.Ing, Integer> e : parts)
				if (!pl.needAny(e.getKey().items(), e.getValue() * times, depth, stack)) return false;
			if (r.kind() == Recipes.Kind.SMELT && !fuel(pl, times, depth, stack)) return false;
			String st = station();
			if (st != null && !pl.station(st, depth, stack)) return false;
			Act act = r.kind() == Recipes.Kind.SMELT ? Act.SMELT : r.kind() == Recipes.Kind.CRAFT ? Act.CRAFT : Act.STATION;
			pl.steps.add(new Step(act, item, times * r.count(), r, times, List.of(), null));
			pl.add(item, times * r.count() - n);   // what it made beyond the need, kept
			return true;
		}

		/** Fuel for n smelts: what burns that the buddy carries, else the cheapest to get. */
		static boolean fuel(Planner pl, int n, int depth, Deque<String> stack) {
			double left = n;
			for (Map.Entry<String, Integer> e : new ArrayList<>(pl.inv.entrySet())) {
				double v = burns(e.getKey()) ? fuelValue(e.getKey()) : 0;
				if (v <= 0 || e.getValue() <= 0 || left <= 0) continue;
				int use = (int) Math.min(e.getValue(), Math.ceil(left / v));
				pl.use(e.getKey(), use);
				left -= use * v;
			}
			for (String f : List.of("coal", "charcoal", "coal_block")) {
				if (left <= 0) break;
				int u = pl.use(f, (int) Math.ceil(left / fuelValue(f)));
				left -= u * fuelValue(f);
			}
			if (left <= 0) return true;
			// the cheapest per item smelted: coal against logs or planks (one log smelts one and a half)
			double coal = pl.cost("coal") / 8, log = NEVER;
			String logItem = null;
			for (String l : VanillaData.tag("item", "logs_that_burn")) { double c = pl.cost(l) / 1.5; if (c < log) { log = c; logItem = l; } }
			if (logItem != null && log < coal) return pl.need(logItem, (int) Math.ceil(left / 1.5), depth + 1, stack);
			return pl.need("coal", (int) Math.ceil(left / 8), depth + 1, stack);
		}
	}

	private record Mine(String item, String block, int odds) implements Way {
		public String key() { return "mine:" + block; }

		double perBlock() {
			Double y = YIELD.get(item);
			if (y != null) return y;
			return odds == 2 ? 0.05 : odds == 1 ? 0.4 : 1;
		}

		public double cost(Planner pl) {
			Nature.Place p = Nature.block(block);
			String[] t = toolFor(block);
			double c = pl.findCost(p, pl.have.near(block)) / perBlock();
			if (t != null) c += Math.min(pl.toolCost(t[0], Integer.parseInt(t[1])), NEVER) / 30;
			return c;
		}

		public double costWith(Planner pl) {
			Nature.Place p = Nature.block(block);
			String[] t = toolFor(block);
			double c = pl.findCost(p, pl.have.near(block)) / perBlock();
			if (t != null) {
				double tc = pl.toolCost(t[0], Integer.parseInt(t[1]));
				if (tc >= NEVER) return NEVER;
				c += tc;
			}
			return c;
		}

		public boolean plan(Planner pl, String item, int n, int depth, Deque<String> stack) {
			Nature.Place p = Nature.block(block);
			if (!pl.into(p, depth, stack)) return false;
			String[] t = toolFor(block);
			if (t != null && !pl.keepTool(t[0], Integer.parseInt(t[1]), depth, stack)) return false;
			int blocks = (int) Math.ceil(n / perBlock());
			pl.steps.add(new Step(Act.MINE, item, n, null, blocks, List.of(block), null));
			return true;
		}
	}

	private record Kill(String item, String mob, int odds) implements Way {
		public String key() { return "kill:" + mob; }

		double perMob() {
			if (odds == 2) return 0.03;   // a zombie's iron ingot, carrot or potato: never the way to them
			Double y = YIELD.get(item);
			return y != null ? y : 0.6;
		}

		public double cost(Planner pl) { return pl.findCost(Nature.mob(mob), pl.have.nearMob(mob)) / perMob(); }

		public double costWith(Planner pl) { return pl.findCost(Nature.mob(mob), pl.have.nearMob(mob)) / perMob(); }

		public boolean plan(Planner pl, String item, int n, int depth, Deque<String> stack) {
			if (!pl.into(Nature.mob(mob), depth, stack)) return false;
			pl.steps.add(new Step(Act.KILL, item, n, null, (int) Math.ceil(n / perMob()), List.of(mob), null));
			return true;
		}
	}

	private record Barter(String item) implements Way {
		public String key() { return "barter:" + item; }

		/** Gold ingots per item, from the bartering table's weights (ender pearls about 1 in 23 for 2 to 4). */
		double gold() { return item.equals("ender_pearl") ? 8 : item.equals("obsidian") ? 12 : 30; }

		public double cost(Planner pl) { return pl.cost("gold_ingot") * gold() + 600; }

		public double costWith(Planner pl) { return pl.costOf("gold_ingot", gold()) + (pl.dim == Nature.Dim.NETHER ? 20 : 600); }

		public boolean plan(Planner pl, String item, int n, int depth, Deque<String> stack) {
			if (!pl.need("gold_ingot", (int) Math.ceil(n * gold()), depth + 1, stack)) return false;
			if (!pl.into(Nature.Dim.NETHER, depth, stack)) return false;
			pl.steps.add(new Step(Act.BARTER, item, n, null, (int) Math.ceil(n * gold()), List.of("piglin"), null));
			return true;
		}
	}

	private record Spec(String item, Special s) implements Way {
		public String key() { return "special:" + s.name(); }

		/** A tool it keeps counts a tenth of its cost for one item: it serves many. */
		public double cost(Planner pl) {
			double c = s.cost();
			for (Map.Entry<String, Integer> t : s.takes().entrySet()) {
				String k = t.getKey();
				if (k.startsWith("@")) continue;
				if (k.startsWith("+tool:")) c += pl.toolCost(k) / 10;
				else if (k.startsWith("+")) c += pl.invLeft(k.substring(1)) > 0 ? 0 : pl.cost(k.substring(1)) / 10;
				else c += pl.cost(k) * t.getValue();
			}
			return c;
		}

		public double costWith(Planner pl) {
			double c = s.cost();
			for (Map.Entry<String, Integer> t : s.takes().entrySet()) {
				String k = t.getKey();
				if (k.startsWith("@")) continue;
				if (k.startsWith("+tool:")) c += pl.toolCost(k) / 10;
				else if (k.startsWith("+")) c += pl.invLeft(k.substring(1)) > 0 ? 0 : pl.cost(k.substring(1)) / 10;
				else c += pl.costOf(k, t.getValue());
			}
			return c;
		}

		public boolean plan(Planner pl, String item, int n, int depth, Deque<String> stack) {
			for (Map.Entry<String, Integer> t : s.takes().entrySet()) {
				String k = t.getKey();
				if (k.startsWith("+tool:")) {
					String[] kt = k.split(":");
					if (!pl.keepTool(kt[1], Integer.parseInt(kt[2]), depth + 1, stack)) return false;
				} else if (k.startsWith("+") && !pl.keep(k.substring(1), depth + 1, stack)) return false;
			}
			for (int i = 0; i < n; i++)
				for (Map.Entry<String, Integer> t : s.takes().entrySet())
					if (!t.getKey().startsWith("+") && !pl.need(t.getKey(), t.getValue(), depth + 1, stack)) return false;
			pl.steps.add(new Step(Act.SPECIAL, item, n, null, n, List.of(), s.name()));
			return true;
		}
	}
}
