package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The game's recipes and loot tables as {@link Recipes} and {@link Drops} read them from the Minecraft jar. */
class RecipesTest {
	@Test
	void dataIsThere() {
		assertTrue(VanillaData.present(), "the Minecraft jar's data/minecraft on the test classpath");
		assertTrue(Recipes.all().size() > 500, "recipes: " + Recipes.all().size());
	}

	@Test
	void ironPickaxe() {
		List<Recipes.Recipe> rs = Recipes.making("iron_pickaxe");
		Recipes.Recipe r = rs.stream().filter(x -> x.kind() == Recipes.Kind.CRAFT).findFirst().orElseThrow();
		assertEquals(3, r.width());
		assertEquals(3, r.height());
		assertFalse(r.small());
		assertEquals(5, r.parts().size());
		assertTrue(r.grid()[0].test("iron_ingot"));
		assertTrue(r.grid()[4].test("stick"));
		assertEquals(null, r.grid()[3]);
	}

	@Test
	void planksSticksAndSmelting() {
		Recipes.Recipe planks = Recipes.making("oak_planks").get(0);
		assertEquals(4, planks.count());
		assertTrue(planks.small());
		assertTrue(planks.parts().get(0).test("oak_log"));
		assertTrue(planks.parts().get(0).test("stripped_oak_wood"));
		Recipes.Recipe sticks = Recipes.making("stick").stream().filter(x -> x.parts().get(0).test("oak_planks")).findFirst().orElseThrow();
		assertEquals(4, sticks.count());
		assertTrue(sticks.small());
		Recipes.Recipe ingot = Recipes.making("iron_ingot").stream().filter(x -> x.kind() == Recipes.Kind.SMELT && x.parts().get(0).test("raw_iron")).findFirst().orElseThrow();
		assertEquals(200, ingot.cookTime());
		assertNotNull(Recipes.making("netherite_pickaxe").stream().filter(x -> x.kind() == Recipes.Kind.SMITH).findFirst().orElse(null));
		assertTrue(Recipes.making("crafting_table").get(0).small());
		assertFalse(Recipes.making("furnace").get(0).small());
	}

	@Test
	void drops() {
		Drops.Drop raw = Drops.of("raw_iron").stream().filter(d -> d.source().equals("iron_ore")).findFirst().orElseThrow();
		assertEquals(Boolean.FALSE, raw.silk());
		assertFalse(raw.chance());
		Drops.Drop ore = Drops.of("iron_ore").stream().filter(d -> d.source().equals("iron_ore")).findFirst().orElseThrow();
		assertEquals(Boolean.TRUE, ore.silk());
		Drops.Drop flint = Drops.of("flint").stream().filter(d -> d.source().equals("gravel")).findFirst().orElseThrow();
		assertTrue(flint.chance());
		Drops.Drop rod = Drops.of("blaze_rod").stream().filter(d -> d.from() == Drops.From.ENTITY).findFirst().orElseThrow();
		assertEquals("blaze", rod.source());
		assertTrue(rod.byPlayer());
		assertTrue(Drops.of("ender_pearl").stream().anyMatch(d -> d.from() == Drops.From.BARTER));
		assertTrue(Drops.of("ender_pearl").stream().anyMatch(d -> d.source().equals("enderman")));
		Drops.Drop wheat = Drops.of("wheat").stream().filter(d -> d.source().equals("wheat")).findFirst().orElseThrow();
		assertEquals("7", wheat.state().get("age"));
		assertTrue(Drops.of("white_wool").stream().anyMatch(d -> d.source().equals("sheep")));
		assertTrue(Drops.of("cobblestone").stream().anyMatch(d -> d.source().equals("stone") && d.silk() == Boolean.FALSE));
		assertTrue(Drops.of("diamond").stream().anyMatch(d -> d.source().equals("deepslate_diamond_ore")));
	}

	@Test
	void tags() {
		assertTrue(VanillaData.tag("block", "mineable/pickaxe").contains("iron_ore"));
		assertTrue(VanillaData.tag("block", "needs_stone_tool").contains("iron_ore"));
		assertTrue(VanillaData.tag("block", "needs_iron_tool").contains("diamond_ore"));
		assertTrue(VanillaData.tag("block", "needs_diamond_tool").contains("obsidian"));
		assertTrue(VanillaData.tag("item", "planks").contains("oak_planks"));
		assertTrue(VanillaData.tag("item", "logs").contains("oak_log"));
	}

	/** The cells of a shaped craft, each with its ingredient. */
	private static List<Recipes.Ing> cells(String item) {
		Recipes.Recipe r = Recipes.making(item).stream().filter(x -> x.kind() == Recipes.Kind.CRAFT).findFirst().orElseThrow();
		List<Recipes.Ing> out = new java.util.ArrayList<>();
		for (Recipes.Ing i : r.grid()) if (i != null) out.add(i);
		return out;
	}

	/** A grid filled from mixed kinds of one tag, as the planner counts them: each cell one kind, none used beyond what is there. */
	@Test
	void craftCellsFromMixedKinds() {
		List<Recipes.Ing> furnace = cells("furnace");
		assertEquals(8, furnace.size());
		String[] kinds = new String[furnace.size()];
		assertEquals(1, ObtainSteps.Craft.cellKinds(furnace, java.util.Map.of("cobblestone", 5, "cobbled_deepslate", 5), 1, kinds));
		assertTrue(java.util.Arrays.stream(kinds).filter("cobblestone"::equals).count() <= 5, java.util.Arrays.toString(kinds));
		assertTrue(java.util.Arrays.stream(kinds).filter("cobbled_deepslate"::equals).count() <= 5, java.util.Arrays.toString(kinds));
		assertEquals(0, ObtainSteps.Craft.cellKinds(furnace, java.util.Map.of("cobblestone", 4, "cobbled_deepslate", 3), 1, kinds));
		List<Recipes.Ing> table = cells("crafting_table");
		String[] t = new String[table.size()];
		assertEquals(1, ObtainSteps.Craft.cellKinds(table, java.util.Map.of("oak_planks", 2, "birch_planks", 2), 4, t));
		// two tables: two cells of two oak and two of two birch
		assertEquals(2, ObtainSteps.Craft.cellKinds(table, java.util.Map.of("oak_planks", 4, "birch_planks", 4), 4, t));
		assertEquals(2, java.util.Arrays.stream(t).filter("oak_planks"::equals).count());
	}
}
