package net.milenco.buddy.client;

import java.util.ArrayList;
import java.util.List;

/**
 * What the buddy carries before a step of a kind, the gear gates of AltoClef's speedrun stages (iron before the
 * diamonds, food before the nether), stricter because a hardcore world forgives nothing: underground a table, wood and a
 * sword; deep down armour, a shield, food, torches and a water bucket; in the nether full armour, gold on a foot, blocks
 * and a way to light the portal again; in the End a bow with arrows on top. A gate holds for its own kind and every
 * kind below it, and a need is fetched only by steps of a lower kind, so a gate never waits on itself.
 */
final class ObtainKit {
	private ObtainKit() {}

	enum Danger { SURFACE, UNDERGROUND, DEEP, NETHER, END }

	/** One thing of a kit: any of these items, this many in all (carried or worn). */
	record Need(String name, List<String> any, int count) {}

	/**
	 * The fire resistance brew for the nether: the kit's water bottles and redstone, which Soprano's blaze hunt brews of in
	 * the fortress (it brews only with water bottles in the bag). Off from 0.5.12 (more food was the nether's way then, and
	 * the bottles' sand, the redstone and the wart hunt took time of every order); on again since fire and lava stayed the
	 * nether's first killers with the food in the bag: in 189 to 218 blazes, fire and the lava sea took nine of the
	 * fortress deaths.
	 */
	static final boolean BREW = true;

	static final List<String> FOOD = List.of("cooked_beef", "cooked_porkchop", "cooked_mutton", "cooked_chicken", "cooked_salmon", "cooked_cod",
		"bread", "baked_potato", "golden_carrot", "cooked_rabbit");

	private static List<String> tiers(String piece, String... tiers) {
		List<String> out = new ArrayList<>();
		for (String t : tiers) out.add(t + "_" + piece);
		return out;
	}

	private static final String[] IRON_UP = { "iron", "diamond", "netherite" };
	private static final String[] STONE_UP = { "stone", "copper", "iron", "diamond", "netherite" };

	/**
	 * Wood for tools underground: logs, planks, and the sticks they become (with the table carried, sticks are all the
	 * wood a new pickaxe takes; it climbed 55 blocks to the surface for logs with three sticks in the bag).
	 */
	private static List<String> wood() {
		List<String> l = new ArrayList<>(VanillaData.tag("item", "logs"));
		l.addAll(VanillaData.tag("item", "planks"));
		l.add("stick");
		return l;
	}

	static List<Need> kit(Danger d) {
		List<Need> k = new ArrayList<>();
		// a stone sword as soon as there is stone in the bag, on the surface too: on hard a zombie hits 4.5 and a wooden
		// sword counts as outmatched against one, so it ran from a burning zombie in the morning and was hit to one heart.
		// Asked only with the stone in the bag (skip), so a bare start is not sent for stone first
		k.add(new Need("stone sword", tiers("sword", "stone", "copper", "iron", "diamond", "netherite"), 1));
		// armour as the iron comes, before the deep: a shield from the first ingot, then a piece as soon as there is iron for
		// it, best protection per ingot first. Nearly every death without this was in no armour at all, most of them to
		// skeletons, while it gathered thirty raw iron for the whole deep kit in one go; a player makes the shield first
		if (d.ordinal() < Danger.DEEP.ordinal())
			for (Object[] a : AS_IT_COMES) k.add(new Need(ASIS + a[0], a[0].equals("shield") ? List.of("shield") : tiers((String) a[0], IRON_UP), 1));
		if (d.ordinal() >= Danger.UNDERGROUND.ordinal()) {
			k.add(new Need("crafting table", List.of("crafting_table"), 1));
			// a wooden one does: the first descent comes before the first stone (a fist is a quarter of it). Before the wood:
			// crafted at the table that stands, not after a walk to the trees with the table picked up
			k.add(new Need("sword", tiers("sword", "wooden", "stone", "copper", "iron", "diamond", "netherite"), 1));
			k.add(new Need("wood", wood(), 6));
			// a spare: a stone pickaxe is through in 131 blocks, a staircase down takes three a step (it wore through twice on
			// the way to the iron, with a crafting table to be placed underground each time)
			k.add(new Need("spare pickaxe", tiers("pickaxe", STONE_UP), 2));
		}
		if (d.ordinal() >= Danger.DEEP.ordinal()) {
			k.add(new Need("food", FOOD, 8));
			k.add(new Need("good sword", tiers("sword", IRON_UP), 1));
			// a golden one is a helmet too, as much armour as iron: in the nether the kit's danger is the deep's
			// (Alto.kitDanger), and there it took the golden one off for the iron one in its bag, which turns every piglin on it
			// (it stood still in a blaze fight trying to make an iron helmet, burning to death)
			List<String> helmets = tiers("helmet", IRON_UP);
			helmets.add("golden_helmet");
			k.add(new Need("helmet", helmets, 1));
			k.add(new Need("chestplate", tiers("chestplate", IRON_UP), 1));
			k.add(new Need("leggings", tiers("leggings", IRON_UP), 1));
			k.add(new Need("boots", tiers("boots", IRON_UP), 1));
			k.add(new Need("shield", List.of("shield"), 1));
			k.add(new Need("torches", List.of("torch"), 16));
			k.add(new Need("water bucket", List.of("water_bucket"), 1));
			k.add(new Need("blocks", List.of("cobblestone", "cobbled_deepslate"), 16));
		}
		if (d.ordinal() >= Danger.NETHER.ordinal()) {
			k.add(new Need("more food", FOOD, 16));
			// a golden helmet: it protects as much as an iron one, so the armour stays 15 (golden boots made it 14, and under 15
			// a blaze is a shooter to dig in from, never one to fight)
			k.add(new Need("gold helmet", List.of("golden_helmet"), 1));
			// and a spare in the bag: a golden helmet has 77 points of durability, and a nether order's fights wore one through
			// twice, with every piglin on the buddy from then on; the spare goes on where the worn one breaks (Obtain)
			k.add(new Need("spare gold helmet", List.of("golden_helmet"), 2));
			k.add(new Need("more blocks", List.of("cobblestone", "cobbled_deepslate"), 48));
			k.add(new Need("flint and steel", List.of("flint_and_steel"), 1));
			// a wither skeleton hits for eight through iron: a diamond sword ends it in fewer blows
			k.add(new Need("nether sword", tiers("sword", "diamond", "netherite"), 1));
			// a blaze hangs over a fortress' floor out of a sword's reach and sets the buddy on fire meanwhile
			k.add(new Need("bow", List.of("bow"), 1));
			k.add(new Need("arrows", List.of("arrow"), 32));
			// three water bottles and a redstone dust: Soprano brews fire resistance of them in the fortress (a stand of a rod and
			// three stone, a rod's powder for its fuel, a nether wart and a magma cream found there), and the nether has no water
			// to fill a bottle at. fire was over half the damage taken in the nether, the blazes' fireballs and lava included,
			// and fire resistance takes all of it (the dust makes three minutes eight). BREW turns it off
			if (BREW) {
				k.add(new Need("water bottles", List.of("potion"), 3));
				k.add(new Need("redstone", List.of("redstone"), 1));
			}
		}
		if (d.ordinal() >= Danger.END.ordinal()) {
			k.add(new Need("more arrows", List.of("arrow"), 64));
			k.add(new Need("even more food", FOOD, 24));
		}
		return k;
	}

	/** How dangerous a step is: where it takes the buddy. */
	static Danger of(ObtainPlan.Step s, Nature.Dim here) {
		Nature.Dim dim = here;
		switch (s.act()) {
			case MINE -> {
				Nature.Place p = Nature.block(s.sources().get(0));
				if (p == null) return Danger.SURFACE;
				if (!p.in(here)) dim = p.dim();
				// one in view decides by its own height: obsidian beside it on the surface asked the whole deep kit, logs and cows
				// and all
				Integer y = here == Nature.Dim.OVERWORLD && dim == here ? seenY(s.sources()) : null;
				if (y != null) return y < 0 ? Danger.DEEP : y < 40 ? Danger.UNDERGROUND : Danger.SURFACE;
				if (dim == Nature.Dim.NETHER) return Danger.NETHER;
				if (dim == Nature.Dim.END) return Danger.END;
				if (p.where() == Nature.Where.DEEP || (p.bestY() != Integer.MIN_VALUE && p.bestY() < 0)) return Danger.DEEP;
				if (p.where() == Nature.Where.UNDERGROUND) return Danger.UNDERGROUND;
				return Danger.SURFACE;
			}
			case KILL -> {
				String m = s.sources().get(0);
				Nature.Place p = Nature.mob(m);
				if (p != null && !p.in(here)) dim = p.dim();
				if (dim == Nature.Dim.NETHER) return Danger.NETHER;
				if (dim == Nature.Dim.END) return Danger.END;
				if (List.of("cow", "pig", "sheep", "chicken", "rabbit", "salmon", "cod", "squid").contains(m)) return Danger.SURFACE;
				// the strong ones in iron and with a good sword: an enderman has 40 health and hits for seven on hard (the plan to
				// an elytra sent it after fourteen pearls with a wooden sword as its tenth step)
				return STRONG.contains(m) ? Danger.DEEP : Danger.UNDERGROUND;
			}
			case BARTER -> { return Danger.NETHER; }
			case SPECIAL -> {
				return switch (s.special()) {
					case "nether portal", "through the portal" -> Danger.NETHER;
					case "end portal", "find stronghold", "end ship", "end city" -> Danger.END;
					default -> here == Nature.Dim.NETHER ? Danger.NETHER : here == Nature.Dim.END ? Danger.END : Danger.SURFACE;
				};
			}
			default -> {
				return here == Nature.Dim.NETHER ? Danger.NETHER : here == Nature.Dim.END ? Danger.END : Danger.SURFACE;
			}
		}
	}

	/** The height of the nearest of these blocks the scan trusts, within 24 blocks of the buddy; null when none (or no game). */
	static Integer seenY(List<String> blocks) {
		var mc = AfkHelperClient.mc();
		if (mc == null || mc.player == null) return null;
		net.minecraft.core.BlockPos f = mc.player.blockPosition(), best = null;
		double bd = 24 * 24;
		for (String b : blocks) for (net.minecraft.core.BlockPos q : BlockScan.places(b)) {
			double d = q.distSqr(f);
			if (d < bd) { bd = d; best = q; }
		}
		return best == null ? null : best.getY();
	}

	/** Monsters a wooden sword and no armour do not see through. */
	static final List<String> STRONG = List.of("enderman", "witch", "cave_spider", "phantom", "pillager", "vindicator", "evoker", "ravager",
		"guardian", "elder_guardian", "breeze", "creaking");

	/** Everything that feeds the buddy, raw meat too (no rotten flesh, no spider eyes, no raw chicken). */
	static final List<String> ANY_FOOD = List.of("cooked_beef", "cooked_porkchop", "cooked_mutton", "cooked_chicken", "cooked_salmon", "cooked_cod",
		"bread", "baked_potato", "golden_carrot", "cooked_rabbit", "beef", "porkchop", "mutton", "rabbit", "apple", "carrot", "potato", "melon_slice",
		"sweet_berries", "cod", "salmon");

	/**
	 * Hunger on hard kills: below thirteen with nothing to eat, food comes before the order's next step, of any kind
	 * (AltoClef keeps a buffer of food points before every risky phase; this is the floor under that).
	 */
	static Need hungry(net.minecraft.client.player.LocalPlayer p, ObtainPlan.Have h) {
		// hurt it heals only from a food bar of 18: food then already, before the bar is low
		int bar = p.getFoodData().getFoodLevel();
		// and hurt at all with a bar that does not heal: it sat at 13 health with a bar of 10 and no food for ten minutes,
		// and a skeleton took the rest
		if (bar > 12 && !(bar < 18 && p.getHealth() < p.getMaxHealth() - 4)) return null;
		int have = 0;
		for (String i : ANY_FOOD) have += h.inv(i);
		return have > 0 ? null : new Need("something to eat", ANY_FOOD, 4);
	}

	/** Everything the kit of this danger lacks. */
	/**
	 * Wood from deep under the ground is a stair to the sky and back: not while a usable pickaxe of stone or better is in
	 * the bag (it climbed from y 7 for six logs, found none of the kinds it tried and lost the day).
	 */
	/** The first piece of the armour made as the iron comes that it lacks and has the iron for now, or null. */
	static Need affordableArmour(ObtainPlan.Have h) {
		int iron = h.inv("iron_ingot") + h.inv("raw_iron");
		for (Object[] a : AS_IT_COMES) {
			List<String> any = a[0].equals("shield") ? List.of("shield") : tiers((String) a[0], IRON_UP);
			int have = 0;
			for (String i : any) have += h.inv(i) + h.worn(i);
			if (have == 0) return iron >= (int) a[1] ? new Need(ASIS + a[0], any, 1) : null;
		}
		return null;
	}

	/** A piece of the armour made as the iron comes, and the iron it takes, best protection per ingot first. */
	static final String ASIS = "as the iron comes: ";
	static final Object[][] AS_IT_COMES = { { "shield", 1 }, { "chestplate", 8 }, { "leggings", 7 }, { "helmet", 5 }, { "boots", 4 } };

	/** Soprano carries the order (Alto.on, written there): only it brews. Here, so the kit's rules load without Soprano. */
	static volatile boolean sopranoBrews;

	static boolean skip(Need n, ObtainPlan.Have h) {
		// asked only with the iron for it in the bag (raw or smelted): it is no reason to go and mine
		if (n.name().startsWith(ASIS)) {
			String piece = n.name().substring(ASIS.length());
			for (Object[] a : AS_IT_COMES) if (a[0].equals(piece)) return h.inv("iron_ingot") + h.inv("raw_iron") < (int) a[1];
			return true;
		}
		if (n.name().equals("stone sword"))
			return h.inv("cobblestone") + h.inv("cobbled_deepslate") + h.inv("blackstone") < 2;
		// torches and a water bucket are the overworld's: in the nether no coal, no stem makes charcoal and water boils. out
		// of the overworld they wait for the way back, in the kit and in the order's own check of the kit: an order in the
		// nether without torches gave up as having no safe way
		if ((n.name().equals("torches") || n.name().equals("water bucket")) && h.dim() != Nature.Dim.OVERWORLD) return true;
		// the brew's: filled in the overworld only, and only Soprano brews (the buddy's own steps have no way to a water bottle)
		if ((n.name().equals("water bottles") || n.name().equals("redstone")) && (h.dim() != Nature.Dim.OVERWORLD || !sopranoBrews)) return true;
		if (!n.name().equals("wood")) return false;
		var mc = AfkHelperClient.mc();
		if (mc == null || mc.player == null) return false;
		var p = mc.player;
		net.minecraft.core.BlockPos f = p.blockPosition();
		if (p.level().canSeeSky(f.above())) return false;
		int sky = p.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, f.getX(), f.getZ());
		if (sky - f.getY() < 20) return false;
		// and wood for one more pickaxe in the bag: with the wood not asked the last pickaxes wore through at y 8, and none
		// could be made down there
		int wood = h.inv("stick");
		for (String w : VanillaData.tag("item", "planks")) wood += 2 * h.inv(w);
		for (String w : VanillaData.tag("item", "logs")) wood += 8 * h.inv(w);
		if (wood < 2) return false;
		for (String t : STONE_UP) if (h.inv(t + "_pickaxe") > 0) return true;
		return false;
	}

	static List<Need> missingAll(Danger d, ObtainPlan.Have h) {
		List<Need> out = new ArrayList<>();
		for (Need n : kit(d)) {
			if (skip(n, h)) continue;
			int have = 0;
			for (String i : n.any()) have += h.inv(i) + h.worn(i);
			if (have < enough(n)) out.add(new Need(n.name(), n.any(), n.count() - have));
		}
		return out;
	}

	/**
	 * A stock of eight or more (food, torches, blocks) is enough at three quarters: with seven meals of eight it hunted
	 * four minutes for one beef. Once it is short, the whole count is fetched.
	 */
	static int enough(Need n) { return n.count() >= 8 ? (n.count() * 3 + 3) / 4 : n.count(); }

	/** The first need of the kit for this kind that is not met, or null. */
	static Need missing(Danger d, ObtainPlan.Have h) {
		for (Need n : kit(d)) {
			if (skip(n, h)) continue;
			int have = 0;
			for (String i : n.any()) have += h.inv(i) + h.worn(i);
			// the whole count: the planner takes what the buddy has off it itself
			if (have < enough(n)) return n;
		}
		return null;
	}
}
