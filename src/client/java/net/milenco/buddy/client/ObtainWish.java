package net.milenco.buddy.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What is worth taking along on the way (docs/obtain.md, "Taken along on the way"), for Soprano's take-along chain: the
 * items still wanted per tier and what one raw item is worth. Pure, from the kit's missing needs, the order and the
 * planner's prices; Alto hands the result to the fork, which turns the items into raw items with counts by Soprano's own
 * bill (so what is taken along is what Soprano would otherwise walk for) and puts the reserve on top.
 * <ul>
 * <li><b>now</b>: what the gate ahead lacks (armour, shield, the swords, the golden helmet and the diamond sword of the
 * nether's kit), the table and planks the kit fetches first, the copper pieces it makes for empty slots meanwhile, and the
 * item of the buddy's own step under way;</li>
 * <li><b>later</b>: the kit's stocks (torches, a water bucket, flint and steel, a spare pickaxe and helmet) and the order.</li>
 * </ul>
 * Food stays with food on the way; blocks, a bow and arrows are not taken along.
 */
final class ObtainWish {
	private ObtainWish() {}

	/** The raw items taken along (overworld blocks), as Soprano names them. */
	static final List<String> RAW = List.of("coal", "raw_iron", "raw_gold", "raw_copper", "diamond", "redstone", "flint", "log");

	/** The trees a log is priced by: the cheapest of them. */
	static final List<String> LOGS = List.of("oak_log", "birch_log", "spruce_log", "jungle_log", "acacia_log", "dark_oak_log",
		"mangrove_log", "cherry_log");

	/** The kit's needs that keep the buddy alive at the gate ahead: the now tier. The rest of the kit is later. */
	static final Set<String> GEAR = Set.of("stone sword", "sword", "good sword", "helmet", "chestplate", "leggings", "boots", "shield",
		"gold helmet", "nether sword");

	/** The table and planks the kit fetches before any iron (Alto.kit), as bag counts. */
	static final Map<String, Integer> TABLE_AND_PLANKS = Map.of("crafting_table", 1, "planks", 20);

	/** The wanted items per tier, as bag counts to hold, and the seconds one raw item saves. */
	record Wishes(Map<String, Integer> now, Map<String, Integer> later, Map<String, Double> values) {}

	/**
	 * The wishes. missing = ObtainKit.missingAll for the kit's danger; order and count = the order (a bag count); stepItem
	 * and stepCount = the buddy's own step under way and what it makes, null for none or for Soprano's whole order;
	 * tableAndPlanks = the kit would fetch its table and planks first; copper = the copper pieces the kit would make for
	 * empty slots whose iron is not in the bag ("copper_chestplate"); have = the bag, for the counts and the prices.
	 */
	static Wishes of(List<ObtainKit.Need> missing, String order, int count, String stepItem, int stepCount, boolean tableAndPlanks,
		List<String> copper, ObtainPlan.Have have) {
		Map<String, Integer> now = new LinkedHashMap<>(), later = new LinkedHashMap<>();
		for (ObtainKit.Need n : missing) {
			Map.Entry<String, Integer> it = item(n, have);
			if (it == null) continue;
			(gear(n) ? now : later).merge(it.getKey(), it.getValue(), Math::max);
		}
		if (tableAndPlanks) TABLE_AND_PLANKS.forEach((k, v) -> now.merge(k, v, Math::max));
		for (String piece : copper) now.merge(piece, 1, Math::max);
		if (stepItem != null && !stepItem.startsWith("@") && stepCount > 0) {
			String s = VanillaData.plain(stepItem);
			now.merge(s, have.inv(s) + stepCount, Math::max);
		}
		if (order != null && !order.startsWith("@") && count > 0) later.merge(VanillaData.plain(order), count, Math::max);
		return new Wishes(now, later, values(have));
	}

	/** A need of the gate ahead (GEAR), the "as the iron comes" pieces included. */
	static boolean gear(ObtainKit.Need n) {
		return n.name().startsWith(ObtainKit.ASIS) || GEAR.contains(n.name());
	}

	/**
	 * The catalogue item and the bag count a need of the kit stands for, the cheapest of its kinds (as Alto.kit fetches it);
	 * null for what is not taken along (food, blocks, wood of its own, a bow, arrows, water bottles).
	 */
	static Map.Entry<String, Integer> item(ObtainKit.Need n, ObtainPlan.Have h) {
		String name = n.name();
		if (name.startsWith(ObtainKit.ASIS)) {
			String piece = name.substring(ObtainKit.ASIS.length());
			return Map.entry(piece.equals("shield") ? "shield" : "iron_" + piece, 1);
		}
		return switch (name) {
			case "helmet", "chestplate", "leggings", "boots" -> Map.entry("iron_" + name, 1);
			case "shield" -> Map.entry("shield", 1);
			case "stone sword" -> Map.entry("stone_sword", 1);
			case "sword" -> Map.entry("wooden_sword", 1);
			case "good sword" -> Map.entry("iron_sword", 1);
			// a need of the nether's gate: two diamonds seen are worth a detour on the way there, the kit makes the sword
			// as soon as they are in the bag
			case "nether sword" -> Map.entry("diamond_sword", 1);
			case "gold helmet" -> Map.entry("golden_helmet", 1);
			// (Soprano counts the bag and not what is worn: the bag's count and the ones missing)
			case "spare gold helmet" -> Map.entry("golden_helmet", h.inv("golden_helmet") + n.count());
			case "crafting table" -> Map.entry("crafting_table", 1);
			case "spare pickaxe" -> Map.entry("stone_pickaxe", h.inv("stone_pickaxe") + n.count());
			case "torches" -> Map.entry("torch", h.inv("torch") + n.count());
			case "water bucket" -> Map.entry("water_bucket", 1);
			case "flint and steel" -> Map.entry("flint_and_steel", 1);
			case "redstone" -> Map.entry("redstone", h.inv("redstone") + n.count());
			default -> null;
		};
	}

	/**
	 * What one raw item is worth: what the planner says coming back for it later costs with none of its blocks in sight
	 * (the place's guess for finding one, divided by what a block gives, the tool's share on top). A log is the cheapest
	 * tree's. Rounded to a tenth; an item with no way is left out.
	 */
	static Map<String, Double> values(ObtainPlan.Have have) {
		List<String> ask = new java.util.ArrayList<>(RAW);
		ask.remove("log");
		ask.addAll(LOGS);
		Map<String, Double> cost = ObtainPlan.unseenCosts(ask, have);
		Map<String, Double> out = new LinkedHashMap<>();
		for (String raw : RAW) {
			double c = raw.equals("log") ? LOGS.stream().mapToDouble(l -> cost.getOrDefault(l, ObtainPlan.NEVER)).min().orElse(ObtainPlan.NEVER)
				: cost.getOrDefault(raw, ObtainPlan.NEVER);
			if (c > 0 && c < ObtainPlan.NEVER) out.put(raw, Math.round(c * 10) / 10.0);
		}
		return out;
	}
}
