package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The overworld's route to the nether: its lines, its reading of the bag (the practice rig's), the phase it starts from,
 * the order of its pieces, forward only, a piece given up after its tries, and off for an order begun in the nether.
 */
class ObtainRouteTest {
	/** A bag: items carried, pieces worn by slot. */
	static final class Kit implements ObtainRoute.Bag {
		final Map<String, Integer> items = new HashMap<>();
		final Map<String, String> worn = new HashMap<>();
		boolean bedNear, rest, slimeNear;

		Kit give(String item, int n) { items.merge(item, n, Integer::sum); return this; }

		Kit give(String item) { return give(item, 1); }

		Kit wear(String slot, String item) { worn.put(slot, item); return this; }

		Kit take(String item) { items.remove(item); return this; }

		public int inv(String item) { return items.getOrDefault(item, 0); }

		public String wornIn(String slot) { return worn.getOrDefault(slot, ""); }

		public int invEnding(String suffix) {
			int n = 0;
			for (var e : items.entrySet()) if (e.getKey().endsWith(suffix)) n += e.getValue();
			return n;
		}

		public boolean bedNear() { return bedNear; }

		public boolean slimeNear() { return slimeNear; }

		public boolean restMissing() { return rest; }
	}

	private final List<String> said = new ArrayList<>();
	private final BiConsumer<Integer, String> say =
		(i, st) -> said.add(ObtainRoute.BEGUN.equals(st) ? ObtainRoute.line(i) : ObtainRoute.shortLine(i));

	/** The rig's kits per ROUTEFROM, each with the ones before it: the bag a run from that phase begins with. */
	static Kit from(String phase) {
		Kit k = new Kit();
		int to = ObtainRoute.PHASES.indexOf(phase);
		if (to >= ObtainRoute.M1_2) k.give("wooden_pickaxe").give("crafting_table").give("oak_log", 16);
		if (to >= ObtainRoute.M1_3) k.give("stone_pickaxe", 2).give("stone_sword").give("furnace").give("cobblestone", 16).give("stick", 4);
		if (to >= ObtainRoute.M2_1) k.give("cooked_beef", 8).give("red_bed").give("sand", 3);
		if (to >= ObtainRoute.M2_2) k.wear("chestplate", "iron_chestplate").wear("leggings", "iron_leggings").wear("boots", "iron_boots").give("shield");
		if (to >= ObtainRoute.M3_1) k.give("iron_pickaxe", 2).give("iron_sword").give("water_bucket");
		if (to >= ObtainRoute.M3_2) k.give("diamond_pickaxe").give("diamond_sword").give("redstone");
		if (to >= ObtainRoute.M3_3) k.give("obsidian", 10);
		if (to >= ObtainRoute.M3_3B) k.give("gold_ingot", 96).wear("helmet", "golden_helmet").give("golden_helmet");
		if (to >= ObtainRoute.M3_4) k.give("slime_ball");
		return k;
	}

	/** The kit an M4START run is given in the overworld: everything for the nether but the trade's gold. */
	static Kit m4start() {
		Kit k = new Kit().wear("helmet", "golden_helmet").wear("chestplate", "iron_chestplate").wear("leggings", "iron_leggings")
			.wear("boots", "iron_boots").give("shield");
		k.give("iron_sword").give("diamond_pickaxe").give("iron_pickaxe").give("obsidian", 10).give("flint_and_steel").give("red_bed")
			.give("oak_boat").give("cooked_beef", 48).give("cobblestone", 64).give("crafting_table").give("oak_planks", 20).give("stick", 12)
			.give("water_bucket").give("torch", 16).give("golden_helmet").give("diamond_sword").give("bow").give("arrow", 32)
			.give("redstone").give("oak_log", 6).give("potion", 3);
		return k;
	}

	/** The route on from this bag, the lines said so far cleared. */
	private void walk(Kit k) {
		ObtainRoute.reset(true);
		ObtainRoute.begin(true, k, say);
		said.clear();
	}

	private String next(Kit k) {
		ObtainRoute.Piece p = ObtainRoute.next(k, say);
		return p == null ? null : p.name();
	}

	private static Set<String> met(Kit k, boolean furnace) {
		Set<String> out = new java.util.TreeSet<>();
		for (int i = 0; i < ObtainRoute.PHASES.size(); i++) if (ObtainRoute.met(i, k, furnace)) out.add(ObtainRoute.PHASES.get(i));
		return out;
	}

	@Test
	void theLinesAreExactAndNotTheNethersRoute() {
		assertEquals("route: phase m1.1 wood", ObtainRoute.line(ObtainRoute.M1_1));
		assertEquals("route: phase m1.2 stone", ObtainRoute.line(ObtainRoute.M1_2));
		assertEquals("route: phase m1.3 surface", ObtainRoute.line(ObtainRoute.M1_3));
		assertEquals("route: phase m2.1 armour", ObtainRoute.line(ObtainRoute.M2_1));
		assertEquals("route: phase m2.2 tools", ObtainRoute.line(ObtainRoute.M2_2));
		assertEquals("route: phase m3.1 diamonds", ObtainRoute.line(ObtainRoute.M3_1));
		assertEquals("route: phase m3.2 obsidian", ObtainRoute.line(ObtainRoute.M3_2));
		assertEquals("route: phase m3.3 gold", ObtainRoute.line(ObtainRoute.M3_3));
		assertEquals("route: phase m3.3b slime", ObtainRoute.line(ObtainRoute.M3_3B));
		assertEquals("route: phase m3.4 portal", ObtainRoute.line(ObtainRoute.M3_4));
		assertEquals("route: phase m2.1 short", ObtainRoute.shortLine(ObtainRoute.M2_1));
		// the rig reads the nether's phases by "phase (\d) ": these never pass for one of them
		Pattern nether = Pattern.compile("phase (\\d) ");
		for (int i = 0; i < ObtainRoute.PHASES.size(); i++) {
			assertFalse(nether.matcher(ObtainRoute.line(i)).find(), ObtainRoute.line(i));
			assertFalse(nether.matcher(ObtainRoute.shortLine(i)).find(), ObtainRoute.shortLine(i));
		}
	}

	@Test
	void metReadsTheBagAsTheRigDoes() {
		assertEquals(Set.of(), met(new Kit(), false));
		assertEquals(Set.of("m1.1"), met(new Kit().give("wooden_pickaxe"), false));
		// a copper pickaxe is no pickaxe to the rig
		assertEquals(Set.of(), met(new Kit().give("copper_pickaxe"), false));
		// a stone pickaxe is m1.2 only with a furnace held at some time
		assertEquals(Set.of("m1.1"), met(new Kit().give("stone_pickaxe"), false));
		assertEquals(Set.of("m1.1", "m1.2"), met(new Kit().give("stone_pickaxe"), true));
		// eight food (raw meat counts) and a bed; seven is not enough
		assertEquals(Set.of("m1.3"), met(new Kit().give("beef", 5).give("bread", 3).give("white_bed"), false));
		assertEquals(Set.of(), met(new Kit().give("beef", 7).give("white_bed"), false));
		assertEquals(Set.of(), met(new Kit().give("cooked_beef", 8), false));
		// three iron-or-better pieces worn and a shield; one in the bag is not worn, a golden one is not iron
		Kit armour = new Kit().wear("helmet", "iron_helmet").wear("chestplate", "diamond_chestplate").wear("boots", "iron_boots").give("shield");
		assertEquals(Set.of("m2.1"), met(armour, false));
		assertEquals(Set.of(), met(new Kit().wear("helmet", "golden_helmet").wear("chestplate", "iron_chestplate").wear("boots", "iron_boots")
			.give("shield"), false));
		assertEquals(Set.of(), met(new Kit().wear("chestplate", "iron_chestplate").wear("boots", "iron_boots").give("iron_leggings").give("shield"), false));
		assertEquals(Set.of(), met(new Kit().wear("helmet", "iron_helmet").wear("chestplate", "iron_chestplate").wear("boots", "iron_boots"), false));
		// two iron-or-better pickaxes, an iron-or-better sword, a water bucket
		assertEquals(Set.of("m1.1", "m1.2", "m2.2"), met(new Kit().give("iron_pickaxe").give("diamond_pickaxe").give("iron_sword").give("water_bucket"), true));
		assertFalse(met(new Kit().give("iron_pickaxe").give("iron_sword").give("water_bucket"), true).contains("m2.2"));
		assertFalse(met(new Kit().give("iron_pickaxe", 2).give("iron_sword").give("bucket"), true).contains("m2.2"));
		// a diamond pickaxe and sword (netherite counts) and redstone
		assertTrue(met(new Kit().give("diamond_pickaxe").give("netherite_sword").give("redstone"), false).contains("m3.1"));
		assertFalse(met(new Kit().give("diamond_pickaxe").give("iron_sword").give("redstone"), false).contains("m3.1"));
		// ten obsidian
		assertEquals(Set.of("m3.2"), met(new Kit().give("obsidian", 10), false));
		assertEquals(Set.of(), met(new Kit().give("obsidian", 9), false));
		// 96 gold, a golden helmet worn and a spare: two in the bag alone is not one worn
		assertEquals(Set.of("m3.3"), met(new Kit().give("gold_ingot", 96).wear("helmet", "golden_helmet").give("golden_helmet"), false));
		assertEquals(Set.of(), met(new Kit().give("gold_ingot", 96).give("golden_helmet", 2), false));
		assertEquals(Set.of(), met(new Kit().give("gold_ingot", 95).wear("helmet", "golden_helmet").give("golden_helmet"), false));
		assertEquals(Set.of(), met(new Kit().give("gold_ingot", 96).wear("helmet", "golden_helmet"), false));
		// that and a slime ball; a ball alone is nothing
		assertEquals(Set.of("m3.3", "m3.3b"), met(new Kit().give("gold_ingot", 96).wear("helmet", "golden_helmet").give("golden_helmet").give("slime_ball"), false));
		assertEquals(Set.of(), met(new Kit().give("slime_ball"), false));
		// the portal's phase is the nether, never the bag
		assertFalse(met(m4start().give("gold_ingot", 64), true).contains("m3.4"));
	}

	@Test
	void doneAddsWhatTheRouteFetchesForTheNextPhase() {
		ObtainRoute.reset(true);
		// sixteen logs in m1.1, planks a quarter
		assertFalse(ObtainRoute.done(ObtainRoute.M1_1, new Kit().give("wooden_pickaxe")));
		assertFalse(ObtainRoute.done(ObtainRoute.M1_1, new Kit().give("wooden_pickaxe").give("oak_log", 15).give("oak_planks", 3)));
		assertTrue(ObtainRoute.done(ObtainRoute.M1_1, new Kit().give("wooden_pickaxe").give("oak_log", 15).give("oak_planks", 4)));
		assertTrue(ObtainRoute.done(ObtainRoute.M1_1, new Kit().give("wooden_pickaxe").give("birch_log", 16)));
		// the bucket full again in m3.2
		assertFalse(ObtainRoute.done(ObtainRoute.M3_2, new Kit().give("obsidian", 10)));
		assertTrue(ObtainRoute.done(ObtainRoute.M3_2, new Kit().give("obsidian", 10).give("water_bucket")));
		// a bed put down for the night is the bed
		Kit night = new Kit().give("cooked_beef", 8);
		night.bedNear = true;
		assertTrue(ObtainRoute.done(ObtainRoute.M1_3, night));
		assertFalse(ObtainRoute.met(ObtainRoute.M1_3, night, false));
	}

	@Test
	void theStartIsThePhaseAfterTheHighestTheBagMeets() {
		assertEquals(ObtainRoute.M1_1, ObtainRoute.start(new Kit()));
		// the M4START kit has no furnace and no gold: everything else, so the gold first
		assertEquals(ObtainRoute.M3_3, ObtainRoute.start(m4start()));
		for (String phase : ObtainRoute.PHASES.subList(1, ObtainRoute.PHASES.size()))
			assertEquals(phase, ObtainRoute.PHASES.get(ObtainRoute.start(from(phase))), "ROUTEFROM=" + phase);
	}

	@Test
	void onlyAnOrderIntoTheNetherBegunInTheOverworldWalksTheRoute() {
		ObtainRoute.reset(false);
		ObtainRoute.begin(true, new Kit(), say);
		assertFalse(ObtainRoute.on());
		assertNull(ObtainRoute.next(new Kit(), say));
		ObtainRoute.reset(true);
		ObtainRoute.begin(false, new Kit(), say);
		assertFalse(ObtainRoute.on());
		assertFalse(ObtainRoute.before(ObtainRoute.M3_4));
		assertEquals(List.of(), said);
		ObtainRoute.reset(true);
		ObtainRoute.begin(true, new Kit(), say);
		assertTrue(ObtainRoute.on());
		// said once, however often the danger is asked
		ObtainRoute.begin(true, new Kit(), say);
		assertEquals(List.of("route: phase m1.1 wood"), said);
		// and a new order starts it all off again
		ObtainRoute.reset(false);
		assertFalse(ObtainRoute.on());
	}

	@Test
	void aBareStartWalksTheWoodAndTheStoneInOrder() {
		Kit k = new Kit();
		walk(k);
		assertEquals("wood", next(k));
		k.give("wooden_pickaxe").give("oak_log", 16);
		assertEquals("stone pickaxes", next(k));
		assertEquals(List.of("route: phase m1.2 stone"), said);
		k.give("stone_pickaxe", 3).give("stick", 4).give("crafting_table");
		assertEquals("stone sword", next(k));
		k.give("stone_sword");
		assertEquals("furnace", next(k));
		// the furnace is held once and put down to smelt: the phase stays met
		k.give("furnace");
		assertEquals("food", next(k));
		k.take("furnace");
		assertTrue(ObtainRoute.done(ObtainRoute.M1_2, k));
		assertEquals(List.of("route: phase m1.2 stone", "route: phase m1.3 surface"), said);
		k.give("cooked_beef", 8);
		assertEquals("bed", next(k));
		k.give("red_bed");
		assertEquals("sand", next(k));
	}

	@Test
	void theFoodRulesFollowThePhase() {
		walk(new Kit());
		assertTrue(ObtainRoute.before(ObtainRoute.M1_3));
		walk(from("m1.3"));
		assertFalse(ObtainRoute.before(ObtainRoute.M1_3));
		assertTrue(ObtainRoute.before(ObtainRoute.M3_4));
		walk(from("m3.4"));
		assertFalse(ObtainRoute.before(ObtainRoute.M3_4));
		ObtainRoute.reset(true);
		assertFalse(ObtainRoute.before(ObtainRoute.M1_3));
	}

	// kit_failed_skip (test heel, 13 Nov 2026): "equip copper_leggings" ran its 20 minutes in a flooded cave and was asked
	// again at once. a piece that failed waits ten minutes while the phase's next piece goes on; it counts one of its tries
	@Test
	void aFailedPieceWaitsTenMinutesWhileTheNextGoesOn() {
		Kit k = from("m2.1").give("shield");
		walk(k);
		long t0 = 1_000_000;
		ObtainRoute.Piece chest = ObtainRoute.next(k, t0, say);
		assertEquals("m2.1/copper_chestplate", chest.key());
		ObtainRoute.tried(chest.key());
		ObtainRoute.failed(chest.key(), t0);
		assertTrue(ObtainRoute.skipped(chest.key(), t0 + 1));
		// the next piece of the phase while the chestplate waits; without a clock it is asked again at once, as before
		assertEquals("m2.1/copper_leggings", ObtainRoute.next(k, t0 + 60_000, say).key());
		assertEquals("m2.1/copper_chestplate", ObtainRoute.next(k, say).key());
		ObtainRoute.tried("m2.1/copper_leggings");
		ObtainRoute.failed("m2.1/copper_leggings", t0 + 60_000);
		assertEquals("m2.1/copper_boots", ObtainRoute.next(k, t0 + 120_000, say).key());
		// ten minutes on the chestplate is asked again, its second try
		assertFalse(ObtainRoute.skipped(chest.key(), t0 + ObtainRoute.SKIP));
		assertEquals("m2.1/copper_chestplate", ObtainRoute.next(k, t0 + ObtainRoute.SKIP, say).key());
		assertEquals(1, ObtainRoute.tries(chest.key()));
		assertTrue(said.isEmpty());
	}

	// when every piece left in the phase waits, the one that failed first is asked: the phase is not left with tries to go
	@Test
	void everyPieceWaitingAsksTheOneThatFailedFirst() {
		Kit k = from("m2.1").give("shield").give("iron_ingot", 8);
		k.wear("chestplate", "iron_chestplate").wear("leggings", "iron_leggings");
		walk(k);
		long t0 = 5_000_000;
		assertEquals("m2.1/iron_boots", ObtainRoute.next(k, t0, say).key());
		ObtainRoute.tried("m2.1/iron_boots");
		ObtainRoute.failed("m2.1/iron_boots", t0);
		assertEquals("m2.1/iron_boots", ObtainRoute.next(k, t0 + 1000, say).key());
		assertTrue(said.isEmpty());
		// a new order forgets the waits
		ObtainRoute.reset(true);
		assertFalse(ObtainRoute.skipped("m2.1/iron_boots", t0 + 1000));
	}

	@Test
	void theShieldComesFirstAndNoHelmetButTheGoldenOne() {
		// with no iron in the bag: the shield, then copper for the empty slots, then the iron
		Kit k = from("m2.1");
		walk(k);
		assertEquals("shield", next(k));
		k.give("shield");
		assertEquals("copper_chestplate", next(k));
		k.wear("chestplate", "copper_chestplate");
		assertEquals("copper_leggings", next(k));
		k.wear("leggings", "copper_leggings").wear("boots", "copper_boots");
		assertEquals("iron_chestplate", next(k));
		// with the iron for a piece in the bag no copper is made for it: seven ingots are the leggings' and the boots', not
		// the chestplate's
		Kit iron = from("m2.1").give("shield").give("iron_ingot", 7);
		walk(iron);
		assertEquals("copper_chestplate", next(iron));
		iron.wear("chestplate", "copper_chestplate");
		assertEquals("iron_chestplate", next(iron));
		iron.wear("chestplate", "iron_chestplate").wear("leggings", "iron_leggings").wear("boots", "iron_boots");
		assertEquals("water bucket", next(iron));
		assertEquals(List.of("route: phase m2.2 tools"), said);
		// no piece of the route is an iron or copper helmet; the golden one is M3.3's
		for (List<ObtainRoute.Piece> phase : ObtainRoute.PIECES)
			for (ObtainRoute.Piece p : phase) assertFalse(p.name().contains("helmet") && !p.name().contains("golden"), p.key());
		assertTrue(ObtainRoute.PIECES.get(ObtainRoute.M3_3).stream().anyMatch(p -> p.name().equals("golden helmet")));
	}

	@Test
	void aPieceThatDoesNotComeIsGivenUpAfterThreeTriesAndThePhaseIsShort() {
		Kit k = from("m3.2");
		walk(k);
		ObtainRoute.Piece obsidian = ObtainRoute.next(k, say);
		assertEquals("m3.2/obsidian", obsidian.key());
		ObtainRoute.tried(obsidian.key());
		ObtainRoute.tried(obsidian.key());
		assertEquals("obsidian", next(k));
		ObtainRoute.tried(obsidian.key());
		// the bucket is in the bag, nothing else of m3.2 to ask: short, and on to the golden helmet (the gold's first piece)
		assertEquals("golden helmet", next(k));
		assertEquals(List.of("route: phase m3.2 short", "route: phase m3.3 gold"), said);
		// the sand is one try, and a phase met without it is no short one
		Kit s = from("m1.3");
		walk(s);
		s.give("cooked_beef", 8).give("red_bed");
		ObtainRoute.Piece sand = ObtainRoute.next(s, say);
		assertEquals("m1.3/sand", sand.key());
		ObtainRoute.tried(sand.key());
		assertEquals("shield", next(s));
		assertEquals(List.of("route: phase m2.1 armour"), said);
	}

	// gold smoke 2 run 2: the helmet's three tries spent with no gold found; once the bag holds the gold for one, it is asked again
	@Test
	void theGoldenHelmetComesBackOnceTheGoldIsThere() {
		Kit k = from("m3.3");
		walk(k);
		ObtainRoute.Piece helmet = ObtainRoute.next(k, say);
		assertEquals("m3.3/golden helmet", helmet.key());
		for (int i = 0; i < ObtainRoute.TRIES; i++) ObtainRoute.tried(helmet.key());
		assertEquals("gold", next(k));
		k.give("gold_ingot", 4);
		assertEquals("gold", next(k));
		k.give("gold_ingot", 1);
		assertEquals("golden helmet again", next(k));
		k.wear("helmet", "golden_helmet");
		assertEquals("gold", next(k));
	}

	// gold and slime in one stretch (12 Nov 2026): a slime met in the gold phase is taken at once, and only while none is in the bag
	@Test
	void aSlimeMetInTheGoldPhaseIsTakenAlong() {
		Kit k = from("m3.3");
		walk(k);
		assertEquals("golden helmet", next(k));
		k.slimeNear = true;
		assertEquals("slime ball", next(k));
		assertEquals("m3.3/slime ball", ObtainRoute.next(k, say).key());
		k.give("slime_ball");
		assertEquals("golden helmet", next(k));
		k.take("slime_ball");
		k.slimeNear = false;
		assertEquals("golden helmet", next(k));
	}

	@Test
	void theRouteNeverGoesBack() {
		Kit k = from("m2.2");
		walk(k);
		assertEquals(ObtainRoute.M2_2, ObtainRoute.phase());
		// everything lost (a chest of the rig's emptied, a death): the phase under way asks its pieces, no earlier one
		Kit bare = new Kit();
		assertEquals("water bucket", next(bare));
		assertEquals(ObtainRoute.M2_2, ObtainRoute.phase());
		assertEquals(List.of(), said);
	}

	@Test
	void theSlimeBallIsAPhaseBeforeThePortal() {
		Kit k = from("m3.3b");
		walk(k);
		assertEquals(ObtainRoute.M3_3B, ObtainRoute.phase());
		assertEquals("slime ball", next(k));
		k.give("slime_ball");
		assertEquals("flint and steel", next(k));
		assertEquals(ObtainRoute.M3_4, ObtainRoute.phase());
		assertEquals(List.of("route: phase m3.4 portal"), said);
		// a ball alone is no progress: a bare start carrying one begins at the wood
		assertEquals(ObtainRoute.M1_1, ObtainRoute.start(new Kit().give("slime_ball")));
	}

	@Test
	void thePortalPhaseEndsTheRouteAndTheNetherLeavesIt() {
		Kit k = from("m3.4");
		walk(k);
		assertEquals("flint and steel", next(k));
		k.give("flint_and_steel");
		k.rest = true;
		assertEquals("rest", next(k));
		k.rest = false;
		assertEquals("water bottles", next(k));
		k.give("potion", 3);
		// nothing left: the whole order builds the portal; the last phase says nothing more
		assertNull(next(k));
		assertEquals(ObtainRoute.M3_4, ObtainRoute.phase());
		assertEquals(List.of(), said);
		// out of the overworld the route is behind the buddy: on, but walking no more
		ObtainRoute.out();
		assertTrue(ObtainRoute.on());
		assertFalse(ObtainRoute.walking());
		assertNull(next(new Kit()));
	}

	@Test
	void theGoldCoversTheHelmetsStillToMake() {
		assertEquals(106, ObtainRoute.goldNeed(new Kit()));
		assertEquals(101, ObtainRoute.goldNeed(new Kit().wear("helmet", "golden_helmet")));
		assertEquals(96, ObtainRoute.goldNeed(new Kit().wear("helmet", "golden_helmet").give("golden_helmet")));
		// the M4START kit has both helmets: 96 to mine
		walk(m4start());
		assertEquals(ObtainRoute.M3_3, ObtainRoute.phase());
		assertEquals("gold", next(m4start()));
	}

	/** Every piece has its task in the kit (Alto.routePiece): a new one fails here until it is mapped there too. */
	@Test
	void thePiecesAreTheOnesTheKitKnows() {
		List<String> names = new ArrayList<>();
		for (List<ObtainRoute.Piece> phase : ObtainRoute.PIECES) for (ObtainRoute.Piece p : phase) names.add(p.key());
		assertEquals(List.of("m1.1/wood", "m1.2/stone pickaxes", "m1.2/stone sword", "m1.2/furnace", "m1.3/food", "m1.3/bed", "m1.3/sand",
			"m2.1/shield", "m2.1/copper_chestplate", "m2.1/copper_leggings", "m2.1/copper_boots", "m2.1/iron_chestplate",
			"m2.1/iron_leggings", "m2.1/iron_boots", "m2.2/water bucket", "m2.2/iron pickaxes", "m2.2/iron sword", "m3.1/diamonds",
			"m3.1/redstone", "m3.2/obsidian", "m3.2/water bucket", "m3.3/slime ball", "m3.3/golden helmet", "m3.3/golden helmet again", "m3.3/gold", "m3.3/spare golden helmet", "m3.3b/slime ball",
			"m3.4/flint and steel", "m3.4/rest", "m3.4/water bottles"), names);
		assertEquals(ObtainRoute.PHASES.size(), ObtainRoute.PIECES.size());
	}
}
