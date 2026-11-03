package net.milenco.buddy.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The game's recipes by what they make, from {@link VanillaData}: crafting (shaped, shapeless, transmute), the furnace's
 * smelting, the stonecutter and the smithing table's upgrade. Ids are plain ("iron_pickaxe"), an ingredient is the list
 * of items any one of which fills its cell. What the game makes by code (a firework, a banner copied, a repair, a
 * decorated pot) is left out: none of it is a step on the way to anything.
 */
final class Recipes {
	private Recipes() {}

	enum Kind {
		/** A crafting grid: 2x2 in the inventory when it fits, the table's 3x3 otherwise. */
		CRAFT,
		/** A furnace (also the blast furnace's or smoker's own, which have a furnace recipe beside them). */
		SMELT,
		/** A campfire, no fuel. */
		CAMPFIRE,
		STONECUT,
		/** The smithing table: template, base, addition (netherite gear). */
		SMITH
	}

	/** One cell or one part: any of these items. */
	record Ing(List<String> items) {
		boolean test(String id) { return items.contains(id); }
	}

	/**
	 * One recipe. For a shaped one {@code grid} holds width * height cells, row by row, null where a cell stays empty; for a
	 * shapeless one width is 0 and {@code parts} is all. {@code parts} is every ingredient, one entry per item it takes.
	 */
	record Recipe(String id, Kind kind, String result, int count, int width, int height, Ing[] grid, List<Ing> parts, int cookTime) {
		boolean shaped() { return width > 0; }

		/** Fits the 2x2 grid of the inventory. */
		boolean small() { return kind == Kind.CRAFT && (shaped() ? width <= 2 && height <= 2 : parts.size() <= 4); }

		/** How many of each ingredient one craft takes, the alternatives of an ingredient counted together. */
		Map<Ing, Integer> perCraft() {
			Map<Ing, Integer> m = new java.util.LinkedHashMap<>();
			for (Ing i : parts) m.merge(i, 1, Integer::sum);
			return m;
		}

		@Override public String toString() { return id + " (" + kind.name().toLowerCase() + ")"; }
	}

	private static Map<String, List<Recipe>> byResult;
	private static Map<String, Recipe> byId;

	static synchronized Map<String, List<Recipe>> all() {
		if (byResult != null) return byResult;
		Map<String, List<Recipe>> out = new HashMap<>();
		Map<String, Recipe> ids = new HashMap<>();
		for (Map.Entry<String, JsonObject> e : VanillaData.folder("recipe").entrySet()) {
			Recipe r;
			try { r = parse(e.getKey(), e.getValue()); } catch (RuntimeException ex) { r = null; }
			if (r == null) continue;
			out.computeIfAbsent(r.result(), k -> new ArrayList<>()).add(r);
			ids.put(r.id(), r);
		}
		for (Map.Entry<String, List<Recipe>> e : out.entrySet()) e.setValue(List.copyOf(e.getValue()));
		byResult = Collections.unmodifiableMap(out);
		byId = Collections.unmodifiableMap(ids);
		return byResult;
	}

	/** The recipes that make this item, the crafting ones first. */
	static List<Recipe> making(String item) {
		return all().getOrDefault(VanillaData.plain(item), List.of());
	}

	static Recipe byId(String id) { all(); return byId.get(id); }

	private static Recipe parse(String name, JsonObject o) {
		String type = VanillaData.plain(o.get("type").getAsString());
		return switch (type) {
			case "crafting_shaped" -> shaped(name, o);
			case "crafting_shapeless" -> {
				List<Ing> parts = new ArrayList<>();
				for (JsonElement x : o.getAsJsonArray("ingredients")) parts.add(ing(x));
				yield new Recipe(name, Kind.CRAFT, resultId(o.get("result")), resultCount(o.get("result")), 0, 0, null, List.copyOf(parts), 0);
			}
			case "crafting_transmute" -> {
				// the input with the material (a dyed shulker box, a bundle): the material as often as it says
				List<Ing> parts = new ArrayList<>();
				parts.add(ing(o.get("input")));
				int n = o.has("material_count") ? o.get("material_count").getAsInt() : 1;
				Ing m = ing(o.get("material"));
				for (int i = 0; i < n; i++) parts.add(m);
				yield new Recipe(name, Kind.CRAFT, resultId(o.get("result")), resultCount(o.get("result")), 0, 0, null, List.copyOf(parts), 0);
			}
			case "smelting" -> cooking(name, o, Kind.SMELT);
			case "campfire_cooking" -> cooking(name, o, Kind.CAMPFIRE);
			case "stonecutting" -> new Recipe(name, Kind.STONECUT, resultId(o.get("result")), resultCount(o.get("result")), 0, 0, null,
				List.of(ing(o.get("ingredient"))), 0);
			case "smithing_transform" -> new Recipe(name, Kind.SMITH, resultId(o.get("result")), resultCount(o.get("result")), 0, 0, null,
				List.of(ing(o.get("template")), ing(o.get("base")), ing(o.get("addition"))), 0);
			default -> null;   // blasting and smoking have a smelting recipe beside them; the rest is made by code
		};
	}

	private static Recipe shaped(String name, JsonObject o) {
		JsonArray pat = o.getAsJsonArray("pattern");
		Map<Character, Ing> key = new HashMap<>();
		for (Map.Entry<String, JsonElement> k : o.getAsJsonObject("key").entrySet()) key.put(k.getKey().charAt(0), ing(k.getValue()));
		int h = pat.size(), w = 0;
		for (JsonElement row : pat) w = Math.max(w, row.getAsString().length());
		Ing[] grid = new Ing[w * h];
		List<Ing> parts = new ArrayList<>();
		for (int y = 0; y < h; y++) {
			String row = pat.get(y).getAsString();
			for (int x = 0; x < row.length(); x++) {
				Ing i = key.get(row.charAt(x));
				grid[y * w + x] = i;
				if (i != null) parts.add(i);
			}
		}
		return new Recipe(name, Kind.CRAFT, resultId(o.get("result")), resultCount(o.get("result")), w, h, grid, List.copyOf(parts), 0);
	}

	private static Recipe cooking(String name, JsonObject o, Kind kind) {
		int time = o.has("cookingtime") ? o.get("cookingtime").getAsInt() : 200;
		return new Recipe(name, kind, resultId(o.get("result")), resultCount(o.get("result")), 0, 0, null, List.of(ing(o.get("ingredient"))), time);
	}

	/** "minecraft:stick", "#minecraft:planks", or a list of those. */
	private static Ing ing(JsonElement e) {
		Set<String> items = new LinkedHashSet<>();
		List<JsonElement> list = new ArrayList<>();
		if (e.isJsonArray()) e.getAsJsonArray().forEach(list::add);
		else list.add(e);
		for (JsonElement x : list) {
			String s = x.isJsonObject() ? (x.getAsJsonObject().has("item") ? x.getAsJsonObject().get("item").getAsString()
				: "#" + x.getAsJsonObject().get("tag").getAsString()) : x.getAsString();
			if (s.startsWith("#")) items.addAll(VanillaData.tag("item", s));
			else items.add(VanillaData.plain(s));
		}
		return INTERN.computeIfAbsent(List.copyOf(items), Ing::new);
	}

	private static final Map<List<String>, Ing> INTERN = new HashMap<>();

	private static String resultId(JsonElement r) {
		return VanillaData.plain(r.isJsonObject() ? r.getAsJsonObject().get("id").getAsString() : r.getAsString());
	}

	private static int resultCount(JsonElement r) {
		return r.isJsonObject() && r.getAsJsonObject().has("count") ? r.getAsJsonObject().get("count").getAsInt() : 1;
	}
}
