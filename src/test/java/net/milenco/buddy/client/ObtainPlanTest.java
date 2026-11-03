package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Orders played out against a pretend world: the planner gives a step, the world does it the way the buddy would (a
 * craft takes its ingredients, a mine needs the right pickaxe), and the next step is asked until the order is there. What
 * the tests hold is that it gets there, from nothing as from a base, and that it does not walk a roundabout way.
 */
class ObtainPlanTest {
	/** A world that does what a step says, strictly. */
	static final class World implements ObtainPlan.Have {
		final Map<String, Integer> inv = new HashMap<>(), base = new HashMap<>();
		final Set<String> stations = new HashSet<>();
		final Map<String, Double> near = new HashMap<>();
		Nature.Dim dim = Nature.Dim.OVERWORLD;
		final List<String> log = new ArrayList<>();
		int mined, killed;

		public int inv(String item) { return inv.getOrDefault(item, 0); }
		public int base(String item) { return base.getOrDefault(item, 0); }
		public boolean station(String block) { return stations.contains(block); }
		public Nature.Dim dim() { return dim; }
		public double near(String block) { return near.getOrDefault(block, -1.0); }
		final Set<Nature.Dim> portals = new HashSet<>();
		public boolean portal(Nature.Dim to) { return portals.contains(to); }

		void add(String item, int n) { inv.merge(item, n, Integer::sum); }

		boolean take(List<String> any, int n) {
			for (String i : any) {
				int u = Math.min(n, inv(i));
				if (u > 0) { inv.put(i, inv(i) - u); n -= u; }
			}
			return n == 0;
		}

		boolean hasTool(String kind, int tier) {
			String[] tiers = { "wooden", "stone", "iron", "diamond", "netherite" };
			for (int t = tier; t < tiers.length; t++) if (inv(tiers[t] + "_" + kind) > 0) return true;
			return false;
		}

		void apply(ObtainPlan.Step s) {
			log.add(s.toString());
			switch (s.act()) {
				case TAKE -> {
					int u = Math.min(s.count(), base(s.item()));
					assertTrue(u > 0, "nothing to take: " + s);
					base.put(s.item(), base(s.item()) - u);
					add(s.item(), u);
				}
				case CRAFT, SMELT, STATION -> {
					Recipes.Recipe r = s.recipe();
					String st = switch (r.kind()) { case CRAFT -> r.small() ? null : "crafting_table"; case SMELT -> "furnace"; case STONECUT -> "stonecutter"; case SMITH -> "smithing_table"; case CAMPFIRE -> "campfire"; };
					if (st != null && !stations.contains(st)) {
						if (inv(st) < 1) fail("no " + st + " for " + s + "\n" + String.join("\n", log));
						stations.add(st);   // placed where it works, and picked up again afterwards: it stays the buddy's
					}
					for (Map.Entry<Recipes.Ing, Integer> e : r.perCraft().entrySet())
						if (!take(e.getKey().items(), e.getValue() * s.times())) fail("missing " + e.getKey().items().subList(0, Math.min(3, e.getKey().items().size())) + " for " + s + " in " + inv + "\n" + String.join("\n", log));
					if (r.kind() == Recipes.Kind.SMELT) {
						double left = s.times();
						for (Map.Entry<String, Integer> e : new ArrayList<>(inv.entrySet())) {
							double v = ObtainPlan.fuelValue(e.getKey());
							if (v <= 0 || left <= 0 || e.getValue() <= 0) continue;
							int use = (int) Math.min(e.getValue(), Math.ceil(left / v));
							inv.put(e.getKey(), e.getValue() - use);
							left -= use * v;
						}
						if (left > 0) fail("no fuel for " + s + " in " + inv + "\n" + String.join("\n", log));
					}
					add(r.result(), r.count() * s.times());
				}
				case MINE -> {
					String block = s.sources().get(0);
					String[] t = ObtainPlan.toolFor(block);
					if (t != null && !hasTool(t[0], Integer.parseInt(t[1]))) fail("no " + t[0] + " tier " + t[1] + " for " + block + " in " + inv + "\n" + String.join("\n", log));
					mined += s.times();
					add(s.item(), s.count());
				}
				case KILL -> { killed += s.times(); add(s.item(), s.count()); }
				case BARTER -> { assertTrue(take(List.of("gold_ingot"), s.times())); add(s.item(), s.count()); }
				case SPECIAL -> {
					switch (s.special()) {
						case "fill water" -> { assertTrue(take(List.of("bucket"), 1)); add("water_bucket", 1); }
						case "fill lava" -> { assertTrue(take(List.of("bucket"), 1)); add("lava_bucket", 1); }
						case "nether portal" -> { assertTrue(inv("obsidian") >= 10 && inv("flint_and_steel") >= 1); dim = Nature.Dim.NETHER; take(List.of("obsidian"), 10); portals.add(Nature.Dim.NETHER); }
						case "through the portal" -> dim = s.item().equals("@nether") ? Nature.Dim.NETHER : Nature.Dim.OVERWORLD;
						case "grow crops" -> add(s.item(), s.count());
						case "strip a log" -> {
							assertTrue(hasTool("axe", 0), "strip without an axe: " + inv);
							add(s.item(), s.count());
						}
						case "cast obsidian" -> {
							assertTrue(inv("water_bucket") >= 1 && inv("diamond_pickaxe") >= 1, "cast without its tools: " + inv);
							add("obsidian", s.count());
						}
						case "back to the overworld" -> dim = Nature.Dim.OVERWORLD;
						case "find stronghold" -> { assertTrue(take(List.of("ender_eye"), 2)); add("@stronghold", 1); }
						case "end portal" -> { assertTrue(take(List.of("ender_eye"), 12)); dim = Nature.Dim.END; }
						case "end ship" -> { assertEquals(Nature.Dim.END, dim); add(s.item(), s.count()); }
						default -> fail("special " + s.special());
					}
				}
			}
		}
	}

	/** Steps until the order is there; the steps it took. */
	static List<String> run(World w, String item, int count) {
		for (int i = 0; i < 400; i++) {
			if (w.inv(item) >= count) return w.log;
			ObtainPlan.Plan p = ObtainPlan.plan(item, count, w);
			if (!p.ok()) fail("no plan for " + item + ": " + p.missing() + "\n" + String.join("\n", w.log));
			ObtainPlan.Step s = p.next();
			if (s == null) fail("an empty plan before the order is there: " + w.inv);
			w.apply(s);
		}
		fail("not there in 400 steps:\n" + String.join("\n", w.log));
		return null;
	}

	@Test
	void woodenPickaxeFromNothing() {
		World w = new World();
		List<String> log = run(w, "wooden_pickaxe", 1);
		// the logs in one go: one walk to the trees for the planks, the sticks and the table
		assertTrue(log.get(0).startsWith("mine ") && log.get(0).contains("_log"), log.get(0));
		assertEquals(1, log.stream().filter(l -> l.startsWith("mine ")).count(), String.join("\n", log));
	}

	@Test
	void ironPickaxeFromNothing() {
		World w = new World();
		List<String> log = run(w, "iron_pickaxe", 1);
		System.out.println("iron pickaxe from nothing:\n  " + String.join("\n  ", log));
		assertTrue(log.stream().anyMatch(l -> l.contains("raw_iron")));
		assertTrue(w.inv("iron_pickaxe") >= 1);
	}

	@Test
	void birchForestMakesBirchPlanks() {
		World w = new World();
		w.near.put("birch_log", 6.0);
		List<String> log = run(w, "crafting_table", 1);
		assertTrue(log.get(0).contains("birch_log"), log.get(0));
	}

	@Test
	void fromTheBase() {
		World w = new World();
		w.base.put("iron_ingot", 64);
		w.base.put("oak_planks", 20);
		w.stations.add("crafting_table");
		List<String> log = run(w, "iron_pickaxe", 1);
		assertTrue(log.stream().noneMatch(l -> l.startsWith("mine ")), String.join("\n", log));
	}

	@Test
	void manyThings() {
		for (String item : List.of("torch", "furnace", "bucket", "water_bucket", "shield", "iron_block", "diamond_pickaxe",
				"flint_and_steel", "white_bed", "cooked_beef", "bread", "glass", "stone_bricks", "chest", "ladder", "bow",
				"arrow", "iron_chestplate", "golden_apple", "obsidian", "diamond_block", "dandelion", "poppy", "sand",
				"cobblestone", "smooth_stone", "brick", "paper", "book", "compass", "clock", "shears", "rail")) {
			World w = new World();
			try {
				run(w, item, item.equals("torch") ? 16 : 1);
			} catch (AssertionError e) {
				fail(item + ": " + e.getMessage());
			}
		}
	}

	@Test
	void eyesOfEnderGoThroughTheNether() {
		World w = new World();
		List<String> log = run(w, "ender_eye", 2);
		System.out.println("two eyes of ender from nothing:\n  " + String.join("\n  ", log));
		assertTrue(log.stream().anyMatch(l -> l.contains("nether portal")));
		assertTrue(log.stream().anyMatch(l -> l.contains("blaze")));
	}

	@Test
	void elytra() {
		World w = new World();
		List<String> log = run(w, "elytra", 1);
		System.out.println("elytra from nothing (" + log.size() + " steps):\n  " + String.join("\n  ", log));
		assertEquals(1, w.inv("elytra"));
	}

	/** Every item of the game that has a way: how many can be planned from nothing, and how fast (the panel's list). */
	@Test
	void everyItemFromNothing() {
		long t0 = System.nanoTime();
		List<String> ok = ObtainPlan.obtainable();
		long ms = (System.nanoTime() - t0) / 1_000_000;
		System.out.println("obtainable from nothing: " + ok.size() + " in " + ms + " ms");
		for (String must : List.of("oak_log", "iron_pickaxe", "diamond_block", "obsidian", "torch", "bread", "white_wool", "ender_eye", "elytra", "iron_block"))
			assertTrue(ok.contains(must), must);
		assertTrue(ok.size() > 600, "only " + ok.size());
		assertTrue(ms < 20_000, ms + " ms");
	}

	/** Obsidian is cast with a water bucket kept, not one bucket a block; from nothing too, by way of diamonds. */
	@Test
	void obsidianCast() {
		World w = new World();
		w.add("water_bucket", 1);
		w.add("diamond_pickaxe", 1);
		List<String> log = run(w, "obsidian", 14);
		assertTrue(log.stream().anyMatch(l -> l.startsWith("cast obsidian")), String.join("\n", log));
		assertEquals(1, w.inv("water_bucket"));
		World bare = new World();
		List<String> from = run(bare, "obsidian", 10);
		System.out.println("obsidian from nothing: " + from.size() + " steps\n  " + String.join("\n  ", from));
		assertTrue(bare.inv("diamond_pickaxe") >= 1 && bare.inv("water_bucket") >= 1);
	}

	/** The order with the kit gates as Obtain.tick puts them before a step: what the kit lacks first, by safer steps. */
	static List<String> runWithKit(World w, String item, int count) {
		for (int i = 0; i < 600; i++) {
			if (w.inv(item) >= count) return w.log;
			ObtainPlan.Plan p = ObtainPlan.plan(item, count, w);
			if (!p.ok()) fail("no plan for " + item + ": " + p.missing() + "\n" + String.join("\n", w.log));
			ObtainPlan.Step s = p.next();
			if (s == null) fail("an empty plan before the order is there: " + w.inv);
			{
				ObtainKit.Danger d = ObtainKit.of(s, w.dim());
				List<ObtainKit.Need> all = ObtainKit.missingAll(d, w);
				if (all.size() > 1) {
					ObtainPlan.Plan kp = ObtainPlan.planAll(all.stream().map(ObtainKit.Need::any).toList(), all.stream().map(ObtainKit.Need::count).toList(), w);
					ObtainPlan.Step k = kp == null ? null : kp.next();
					if (k != null && ObtainKit.of(k, w.dim()).ordinal() < d.ordinal()) s = k;
				}
			}
			for (int k = 0; k < 6; k++) {
				ObtainKit.Danger d = ObtainKit.of(s, w.dim());
				ObtainKit.Need need = ObtainKit.missing(d, w);
				if (need == null) break;
				ObtainPlan.Plan kp = ObtainPlan.planAny(need.any(), need.count(), w);
				ObtainPlan.Step ks = kp.ok() ? kp.next() : null;
				int kd = ks == null ? 0 : ObtainKit.of(ks, w.dim()).ordinal();
				if (ks == null || kd > d.ordinal() || kd == d.ordinal() && d != ObtainKit.Danger.SURFACE) break;
				s = ks;
			}
			w.apply(s);
		}
		fail("not there in 600 steps:\n" + String.join("\n", w.log));
		return null;
	}

	/**
	 * Armour as the iron comes: a shield from the first iron, before the next ore, and the chestplate once there are eight
	 * (without it nearly every death came in no armour, most of them to skeletons, while it gathered all the deep kit's
	 * iron in one go).
	 */
	@Test
	void shieldFromTheFirstIron() {
		World w = new World();
		w.add("iron_pickaxe", 1);
		w.add("stone_sword", 1);
		w.add("crafting_table", 1);
		w.add("furnace", 1);
		w.add("oak_planks", 16);
		w.add("coal", 8);
		w.add("raw_iron", 1);
		List<String> log = runWithKit(w, "raw_iron", 12);
		System.out.println("raw iron with a shield on the way: " + log.size() + " steps\n  " + String.join("\n  ", log));
		int shield = -1, mines = 0, mineAtShield = -1;
		for (int i = 0; i < log.size(); i++) {
			if (log.get(i).contains("iron_ore")) mines++;
			if (shield < 0 && log.get(i).contains("shield")) { shield = i; mineAtShield = mines; }
		}
		assertTrue(shield >= 0 && mineAtShield == 0, "the shield before any more ore: " + String.join("\n", log));
		// with the shield and eight raw iron: the chestplate before the next ore
		World w2 = new World();
		for (String[] a : new String[][] { { "iron_pickaxe", "1" }, { "stone_pickaxe", "1" }, { "stone_sword", "1" }, { "crafting_table", "1" },
				{ "furnace", "1" }, { "oak_planks", "16" }, { "coal", "8" }, { "shield", "1" }, { "raw_iron", "8" } })
			w2.add(a[0], Integer.parseInt(a[1]));
		List<String> log2 = runWithKit(w2, "raw_iron", 9);
		int chest = -1, mineAtChest = -1;
		mines = 0;
		for (int i = 0; i < log2.size(); i++) {
			if (log2.get(i).contains("iron_ore")) mines++;
			if (chest < 0 && log2.get(i).contains("iron_chestplate")) { chest = i; mineAtChest = mines; }
		}
		assertTrue(chest >= 0 && mineAtChest == 0, "the chestplate before the next ore: " + String.join("\n", log2));
	}

	/** A diamond pickaxe from nothing with the kits: the deep kit (iron armour, a shield, food...) comes before the diamonds. */
	@Test
	void diamondPickaxeWithTheKits() {
		World w = new World();
		List<String> log = runWithKit(w, "diamond_pickaxe", 1);
		System.out.println("diamond pickaxe with the kits: " + log.size() + " steps\n  " + String.join("\n  ", log));
		int diamonds = -1, chestplate = -1;
		for (int i = 0; i < log.size(); i++) {
			if (diamonds < 0 && log.get(i).contains("diamond_ore")) diamonds = i;
			if (chestplate < 0 && log.get(i).contains("iron_chestplate")) chestplate = i;
		}
		assertTrue(chestplate >= 0 && chestplate < diamonds, "armour before the deep: chestplate " + chestplate + ", diamonds " + diamonds);
	}

	/**
	 * Stone in view and no tree seen: a stone pickaxe can come out cheaper than a wooden one, and the pickaxe to mine the
	 * stone is still the wooden one (the plan went in a circle and gave up in the game).
	 */
	@Test
	void stoneInViewNoTree() {
		World w = new World();
		w.near.put("stone", 1.0);
		w.near.put("cobblestone", 1.0);
		List<String> log = run(w, "stone_pickaxe", 1);
		assertTrue(log.stream().anyMatch(l -> l.contains("wooden_pickaxe")), String.join("\n", log));
	}

	/** An elytra from nothing with the kits: every danger's kit is in before its first step, and it gets there. */
	@Test
	void elytraWithTheKits() {
		World w = new World();
		List<String> log = runWithKit(w, "elytra", 1);
		System.out.println("elytra with the kits: " + log.size() + " steps\n  " + String.join("\n  ", log));
		int portal = -1, shield = -1, eyes = -1, ship = -1;
		for (int i = 0; i < log.size(); i++) {
			String l = log.get(i);
			if (portal < 0 && l.contains("nether portal")) portal = i;
			if (shield < 0 && l.contains("-> 1 shield")) shield = i;
			if (eyes < 0 && l.contains("end portal")) eyes = i;
			if (ship < 0 && l.contains("end ship")) ship = i;
		}
		assertTrue(shield >= 0 && shield < portal, "the shield before the nether: shield " + shield + ", portal " + portal);
		assertTrue(portal < eyes && eyes < ship, "portal " + portal + ", end " + eyes + ", ship " + ship);
	}

	/** A stripped log from nothing: an axe made, kept, and the log stripped; with an iron axe there it is used. */
	@Test
	void strippedLogs() {
		World w = new World();
		List<String> log = run(w, "stripped_birch_log", 4);
		assertTrue(log.stream().anyMatch(l -> l.startsWith("strip a log")), String.join("\n", log));
		World iron = new World();
		iron.add("iron_axe", 1);
		List<String> l2 = run(iron, "stripped_oak_log", 2);
		assertEquals(1, l2.size(), String.join("\n", l2));
	}


	/** Food for a kit from what it carries raw: five chickens and three porkchops cooked, no cows killed. */
	@Test
	void foodFromWhatItCarries() {
		World w = new World();
		w.add("chicken", 5);
		w.add("porkchop", 3);
		w.add("coal", 2);
		w.add("furnace", 1);
		ObtainPlan.Plan p = ObtainPlan.planAny(ObtainKit.FOOD, 8, w);
		assertTrue(p.ok(), p.missing());
		assertTrue(p.steps().stream().noneMatch(st -> st.act() == ObtainPlan.Act.KILL), p.steps().toString());
	}
}
