package net.milenco.buddy.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * What drops what, from the game's loot tables ({@link VanillaData}): blocks broken, mobs killed, piglins bartered with,
 * structure chests. Read as far as a step needs it: the item, where it comes from, and the conditions that decide
 * whether a buddy gets it (silk touch or not, shears, chance, a kill by a player, a block state such as a ripe crop).
 */
final class Drops {
	private Drops() {}

	enum From { BLOCK, ENTITY, BARTER, CHEST }

	/**
	 * One way to an item. {@code silk}: only with silk touch (TRUE), only without (FALSE), either (null). {@code chance}:
	 * not every time (a random chance, a table bonus, a count that can be zero). {@code state}: the block state it needs
	 * ("age" "7" for ripe wheat), empty when any.
	 */
	record Drop(String item, From from, String source, Boolean silk, boolean shears, boolean chance, boolean rare, boolean byPlayer, Map<String, String> state) {
		@Override public String toString() {
			return item + " from " + from.name().toLowerCase() + " " + source + (silk == Boolean.TRUE ? " (silk)" : "")
				+ (shears ? " (shears)" : "") + (rare ? " (rare)" : chance ? " (chance)" : "") + (byPlayer ? " (player kill)" : "") + (state.isEmpty() ? "" : " " + state);
		}
	}

	private static Map<String, List<Drop>> byItem;
	private static Map<String, List<Drop>> bySource;

	static synchronized Map<String, List<Drop>> all() {
		if (byItem != null) return byItem;
		Map<String, List<Drop>> items = new HashMap<>(), sources = new HashMap<>();
		Map<String, JsonObject> tables = VanillaData.folder("loot_table");
		for (Map.Entry<String, JsonObject> e : tables.entrySet()) {
			String name = e.getKey();
			From from; String source;
			if (name.startsWith("blocks/")) { from = From.BLOCK; source = name.substring(7); }
			else if (name.startsWith("entities/")) { from = From.ENTITY; String s = name.substring(9); source = s.contains("/") ? s.substring(0, s.indexOf('/')) : s; }
			else if (name.equals("gameplay/piglin_bartering")) { from = From.BARTER; source = "piglin"; }
			else if (name.startsWith("chests/")) { from = From.CHEST; source = name.substring(7); }
			else continue;
			List<Drop> out = new ArrayList<>();
			try { table(e.getValue(), from, source, out, tables); } catch (RuntimeException ex) { continue; }
			for (Drop d : out) {
				items.computeIfAbsent(d.item(), k -> new ArrayList<>()).add(d);
				sources.computeIfAbsent(from.name() + ":" + source, k -> new ArrayList<>()).add(d);
			}
		}
		items.replaceAll((k, v) -> List.copyOf(v));
		sources.replaceAll((k, v) -> List.copyOf(v));
		byItem = Collections.unmodifiableMap(items);
		bySource = Collections.unmodifiableMap(sources);
		return byItem;
	}

	private static void table(JsonObject t, From from, String source, List<Drop> out, Map<String, JsonObject> tables) {
			Ctx top = new Ctx();
			cond(top, t.get("condition"));
			cond(top, t.get("conditions"));
			if (t.has("pools"))
				for (JsonElement pool : t.getAsJsonArray("pools")) {
					JsonObject po = pool.getAsJsonObject();
					Ctx c = top.copy();
					cond(c, po.get("condition"));
					cond(c, po.get("conditions"));
					if (po.has("entries")) entries(po.getAsJsonArray("entries"), c, from, source, out, tables, new HashSet<>());
				}
	}

	/** Every way to this item. */
	/** Every item something drops. */
	static java.util.Set<String> items() { return all().keySet(); }

	static List<Drop> of(String item) { return all().getOrDefault(VanillaData.plain(item), List.of()); }

	/** What a block drops (with every condition), what a mob does: "block:iron_ore", "entity:zombie". */
	static List<Drop> from(From from, String source) { all(); return bySource.getOrDefault(from.name() + ":" + VanillaData.plain(source), List.of()); }

	/** The conditions met so far on the way down a table. */
	private static final class Ctx {
		Boolean silk;
		boolean shears, chance, rare, byPlayer;
		Map<String, String> state = Map.of();

		Ctx copy() {
			Ctx c = new Ctx();
			c.silk = silk; c.shears = shears; c.chance = chance; c.rare = rare; c.byPlayer = byPlayer; c.state = state;
			return c;
		}
	}

	private static void entries(JsonArray entries, Ctx ctx, From from, String source, List<Drop> out, Map<String, JsonObject> tables, Set<String> seen) {
		for (JsonElement el : entries) {
			if (!el.isJsonObject()) continue;
			JsonObject en = el.getAsJsonObject();
			String type = en.has("type") ? VanillaData.plain(en.get("type").getAsString()) : "item";
			Ctx c = ctx.copy();
			cond(c, en.get("condition"));
			cond(c, en.get("conditions"));
			if (counts0(en.get("modifier")) || counts0(en.get("functions"))) c.chance = true;
			switch (type) {
				case "item" -> { if (en.has("name")) out.add(new Drop(VanillaData.plain(en.get("name").getAsString()), from, source, c.silk, c.shears, c.chance, c.rare, c.byPlayer, c.state)); }
				case "tag" -> {
					if (!en.has("name")) break;
					for (String id : VanillaData.tag("item", en.get("name").getAsString()))
						out.add(new Drop(id, from, source, c.silk, c.shears, c.chance, c.rare, c.byPlayer, c.state));
				}
				case "loot_table" -> {
					if (!en.has("value") && !en.has("name")) break;
					JsonElement v = en.has("value") ? en.get("value") : en.get("name");
					if (!v.isJsonPrimitive()) break;
					String t = VanillaData.plain(v.getAsString());
					JsonObject sub = tables.get(t);
					if (sub == null || !seen.add(t) || !sub.has("pools")) break;
					for (JsonElement pool : sub.getAsJsonArray("pools")) {
						Ctx pc = c.copy();
						cond(pc, pool.getAsJsonObject().get("condition"));
						cond(pc, pool.getAsJsonObject().get("conditions"));
						if (pool.getAsJsonObject().has("entries")) entries(pool.getAsJsonObject().getAsJsonArray("entries"), pc, from, source, out, tables, seen);
					}
				}
				case "alternatives", "group", "sequence" -> {
					if (!en.has("children")) break;
					// alternatives: the first child that matches wins, so a child after one that asks for silk touch is had
					// without it (the ore after the silk touch branch)
					boolean afterSilk = false;
					for (JsonElement ch : en.getAsJsonArray("children")) {
						Ctx cc = c.copy();
						if (type.equals("alternatives") && afterSilk && cc.silk == null) cc.silk = Boolean.FALSE;
						JsonArray one = new JsonArray();
						one.add(ch);
						entries(one, cc, from, source, out, tables, seen);
						if (type.equals("alternatives") && ch.isJsonObject() && silkCond(ch.getAsJsonObject().get("condition"))) afterSilk = true;
					}
				}
				default -> {}
			}
		}
	}

	private static boolean silkCond(JsonElement e) {
		return e != null && e.isJsonPrimitive() && VanillaData.plain(e.getAsString()).equals("tool/can_silk_touch");
	}

	/** A condition (a string naming a predicate, an object, a list of them) applied to what is known. */
	private static void cond(Ctx c, JsonElement e) {
		if (e == null || e.isJsonNull()) return;
		if (e.isJsonArray()) { for (JsonElement x : e.getAsJsonArray()) cond(c, x); return; }
		if (e.isJsonPrimitive()) {
			String s = VanillaData.plain(e.getAsString());
			if (s.equals("tool/can_silk_touch")) c.silk = Boolean.TRUE;
			else if (s.equals("tool/can_shear")) c.shears = true;
			return;
		}
		JsonObject o = e.getAsJsonObject();
		String type = o.has("type") ? VanillaData.plain(o.get("type").getAsString()) : o.has("condition") ? VanillaData.plain(o.get("condition").getAsString()) : "";
		switch (type) {
			case "inverted" -> {
				JsonElement t = o.get("term");
				if (silkCond(t) || (t != null && t.isJsonObject() && t.getAsJsonObject().has("predicate") && t.toString().contains("silk_touch"))) c.silk = Boolean.FALSE;
			}
			// a chance of its own on the drop: a rare one (a zombie's iron ingot, gravel's flint), not a count that may be nought
			case "random_chance", "random_chance_with_enchanted_bonus", "table_bonus" -> { c.chance = true; c.rare = true; }
			case "killed_by_player" -> c.byPlayer = true;
			case "match_block", "block_state_property" -> {
				if (o.has("state") && o.get("state").isJsonObject()) {
					Map<String, String> m = new HashMap<>(c.state);
					for (Map.Entry<String, JsonElement> s : o.getAsJsonObject("state").entrySet())
						if (s.getValue().isJsonPrimitive()) m.put(s.getKey(), s.getValue().getAsString());
					c.state = Map.copyOf(m);
				}
			}
			case "match_tool" -> { if (o.toString().contains("silk_touch")) c.silk = Boolean.TRUE; if (o.toString().contains("shears")) c.shears = true; }
			case "any_of", "all_of" -> {
				// any_of: one of them is enough; a buddy can always pick the easiest, so only what all of them share counts.
				// all_of: every one of them
				if (o.has("terms")) {
					if (type.equals("all_of")) cond(c, o.get("terms"));
					else c.chance = c.chance || false;
				}
			}
			default -> {}
		}
	}

	/** A set_count that can come out at zero: the drop is a chance, not a certainty. */
	private static boolean counts0(JsonElement mods) {
		if (mods == null) return false;
		List<JsonElement> list = new ArrayList<>();
		if (mods.isJsonArray()) mods.getAsJsonArray().forEach(list::add);
		else list.add(mods);
		for (JsonElement m : list) {
			if (!m.isJsonObject()) continue;
			JsonObject o = m.getAsJsonObject();
			String t = o.has("type") ? VanillaData.plain(o.get("type").getAsString()) : o.has("function") ? VanillaData.plain(o.get("function").getAsString()) : "";
			if (!t.equals("set_count") || !o.has("count")) continue;
			JsonElement n = o.get("count");
			if (n.isJsonObject() && n.getAsJsonObject().has("min") && n.getAsJsonObject().get("min").getAsDouble() <= 0) return true;
			if (n.isJsonPrimitive() && n.getAsDouble() <= 0) return true;
		}
		return false;
	}
}
