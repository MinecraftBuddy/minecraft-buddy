package net.milenco.buddy.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.Set;
import java.util.HashSet;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.AABB;
import net.milenco.buddy.version.Game;

/**
 * The chores of a base, for a buddy in one with nothing else to do: chests fill up, so blocks are crafted to make
 * room (iron ingots into blocks, wheat into hay bales), surplus seeds go to a composter, sheep are shorn, and new
 * tools are made from the storage while some ingots stay over.
 *
 * <ul>
 * <li>Compacting: when the base's chests run full (one that holds the item has two free slots or fewer, or all of them
 * together six), what goes nine to a block (ingots, raw ore, diamonds, wheat to hay bales and the like) is made into
 * blocks at a crafting table of the base, from what the buddy carries and fetches from those chests for it.</li>
 * <li>Seeds beyond a stack of a kind go to a composter of the base: into a hopper on it, or a chest on that hopper, and
 * otherwise in by hand; ripe bone meal is taken out.</li>
 * <li>Sheep of the base with wool are shorn (shears from a chest, or made from two iron ingots), the wool picked up.</li>
 * <li>A tool kind the buddy has none of, with none in the chests, is made at the crafting table: of iron while nine
 * iron ingots stay over, else of copper while nine copper ingots stay over, else of stone; never of diamonds, which
 * were asked about as ingots. Sticks from planks and planks from logs in the inventory's own grid. A pickaxe, an axe
 * and a shovel always, a sword while guarding, a hoe while the fields are on.</li>
 * </ul>
 */
final class Chores {
	private Chores() {}

	static final double REACH = 20;
	static final long PLAN_EVERY = 5000, SCAN_EVERY = 30_000;
	/** The iron left over when something is made of it (keepIron, set on the panel; 9 until 24 Oct 2026). */
	static int ingotsLeft() { return Math.max(0, AfkConfig.get().keepIron); }

	/** The gold left over when golden apples are made (keepGold, set on the panel). */
	static int goldLeft() { return Math.max(0, AfkConfig.get().keepGold); }

	/** Nine of these make the block after it. */
	static final Map<String, String> NINE = new HashMap<>();
	static {
		String[][] pairs = { { "iron_ingot", "iron_block" }, { "gold_ingot", "gold_block" }, { "copper_ingot", "copper_block" },
			{ "diamond", "diamond_block" }, { "emerald", "emerald_block" }, { "lapis_lazuli", "lapis_block" }, { "redstone", "redstone_block" },
			{ "coal", "coal_block" }, { "wheat", "hay_block" }, { "raw_iron", "raw_iron_block" }, { "raw_gold", "raw_gold_block" },
			{ "raw_copper", "raw_copper_block" }, { "bone_meal", "bone_block" }, { "dried_kelp", "dried_kelp_block" },
			{ "slime_ball", "slime_block" }, { "iron_nugget", "iron_ingot" }, { "gold_nugget", "gold_ingot" } };
		for (String[] p : pairs) NINE.put(p[0], p[1]);
	}
	static final List<String> SEEDS = List.of("wheat_seeds", "beetroot_seeds", "melon_seeds", "pumpkin_seeds");

	// the base's things, found around the alt
	private static BlockPos table, composter;
	private static long nextScan, nextPlan;
	private static final Map<Integer, Long> skipSheep = new HashMap<>();
	/** A table, composter or inlet the buddy could not get to: left alone for two minutes, so the next chore gets its turn. */
	private static final Map<BlockPos, Long> skipBlock = new HashMap<>();
	static final long SKIP_FOR = 120_000;

	private enum Kind { COMPACT, TOOL, STICKS, PLANKS, SHEARS_MAKE, UNPACK, UNPACK_GOLD, ARMOUR, SHIELD, GAPPLE, BOW, ARROWS, COMPOST_IN, COMPOST_HAND, SHEAR, HERD }
	private static final int IDLE = 0, WALK = 1, OPEN = 2, MENU = 3, ACT = 4;
	private static int step = IDLE;
	private static Kind kind;
	private static String what;        // the item to compact, the tool kind, ...
	private static String material;    // the tool's material
	private static BlockPos at;        // the table, the composter or its inlet
	private static Sheep sheep;
	/** Where to stand to shear it, and where the sheep was when that was chosen (a sheep walks on). */
	private static BlockPos sheepStand;
	private static Vec3 sheepAt = Vec3.ZERO;
	static final long SHEEP_FOR = 30_000;
	// the shearing round: shorn so far, the wool phase, the piece gone for and where to stand for it, and what was left
	private static int shorn;
	private static boolean collecting;
	private static ItemEntity wool;
	private static BlockPos woolStand;
	private static long woolNearSince;
	private static final Set<Integer> gaveUpWool = new HashSet<>();
	/** How far round the buddy the wool of a round is looked for: the pen by the base is three by seven. */
	static final double WOOL_NEAR = 12;
	private static BlockPos stand, clickBlock;
	private static boolean clickEntity;
	private static long stepSince, nextClick;
	private static int clickedAt = -1, clicks;
	private static final Deque<Runnable> queue = new ArrayDeque<>();

	static boolean busy() { return step != IDLE; }

	/** The table or the inlet the buddy opened is open: its clicks run through {@link #menuTick} until they are done. */
	static boolean inMenu(LocalPlayer p) { return (step == OPEN || step == MENU) && p.containerMenu != p.inventoryMenu; }

	private static String owner() { return Follow.boss(); }

	/** In a base of the player the buddy works for: the chores there are the buddy's. */
	private static boolean inBase(LocalPlayer p) { return inBase(p.level(), p.position()); }

	/** This place lies in the base the buddy keeps. */
	private static boolean inBase(Level level, Vec3 at) {
		String boss = owner();
		var e = Tap.baseAt(Work.dimOf(level), at, Base.RADIUS);
		return boss != null && e != null && e.owner().equalsIgnoreCase(boss);
	}

	/**
	 * What may go into blocks: all of it, less what the other jobs count on (21 Oct 2026, after Player2's material
	 * reservation): a stack of what a furnace smelts or burns, and of coal and charcoal half a stack for torches.
	 */
	static int spare(LocalPlayer p, String id) {
		int keep = Ovens.uses(id) ? 64 : 0;
		if (id.equals("coal") || id.equals("charcoal")) keep = Math.max(keep, 32);
		if (id.equals("wheat")) keep = Math.max(keep, 1);   // the wheat to lure with (Herd)
		return count(p, id) + Stash.inChests(id) - keep;
	}

	// ---------- for Stash: what to fetch, what to keep ----------

	/** Something a chore needs and the buddy has too little of: Stash takes it from a chest. */
	static boolean wants(LocalPlayer p, ItemStack st) {
		if (st.isEmpty() || !inBase(p)) return false;
		ensureScan(p);
		String id = AfkHelperClient.itemId(st);
		if (NINE.containsKey(id) && tight(p, id) && count(p, id) < 64 && Stash.inChests(id) >= 18) return true;
		ToolPlan plan = toolPlan(p);
		if (plan != null) {
			if (id.equals(plan.material) && count(p, id) < plan.cost) return true;
			if (st.is(Items.STICK) && count(p, "stick") < plan.sticks) return true;
			// no sticks and nothing to make them of: planks, or a log
			if (count(p, "stick") < plan.sticks && countMatching(p, x -> x.is(ItemTags.PLANKS)) < 2 && countMatching(p, x -> x.is(ItemTags.LOGS)) == 0
					&& (st.is(ItemTags.PLANKS) || st.is(ItemTags.LOGS))) return true;
		}
		if (st.is(Items.SHEARS) && count(p, "shears") == 0 && woolly(p) != null) return true;
		// wheat in the inventory always, to lure an animal back into its pen (Herd; Stash keeps a stack of it)
		if (st.is(Items.WHEAT) && count(p, "wheat") == 0 && herds() && Herd.hasPens()) return true;
		// iron ingots for what is made of iron (shears, a tool, armour); when no chest has enough loose ingots, a block,
		// made into nine (21 Oct 2026: 91 sheep by the base, 54 iron blocks in its chests and not one ingot, so
		// not one sheep was shorn)
		int need = ironNeed(p);
		if (need > 0 && st.is(Items.IRON_INGOT) && count(p, "iron_ingot") < need) return true;
		if (need > 0 && st.is(Items.IRON_BLOCK) && count(p, "iron_ingot") + Stash.inChests("iron_ingot") < need && count(p, "iron_block") == 0) return true;
		// planks or a log for a shield; gold and an apple for golden apples (the fight, 24 Oct 2026)
		if (shieldToMake(p) && planks(p) < 6 && logs(p) == 0 && (st.is(ItemTags.PLANKS) || st.is(ItemTags.LOGS))) return true;
		// string for a bow; flint and feathers for arrows; sticks for both, or what makes them (step 2, 24 Oct 2026)
		boolean bow = bowToMake(p), arrows = arrowsToMake(p);
		if (bow && st.is(Items.STRING) && count(p, "string") < 3) return true;
		if (arrows && st.is(Items.FLINT) && count(p, "flint") < 8) return true;
		if (arrows && st.is(Items.FEATHER) && count(p, "feather") < 8) return true;
		if ((bow || arrows) && count(p, "stick") < 3 && planks(p) < 2 && logs(p) == 0
			&& (st.is(Items.STICK) || st.is(ItemTags.PLANKS) || st.is(ItemTags.LOGS))) return true;
		if (applesToMake(p)) {
			if (st.is(Items.GOLD_INGOT) && count(p, "gold_ingot") < 8) return true;
			if (st.is(Items.GOLD_BLOCK) && count(p, "gold_ingot") + Stash.inChests("gold_ingot") < 8 && count(p, "gold_block") == 0) return true;
			if (st.is(Items.APPLE) && count(p, "apple") == 0) return true;
		}
		return false;
	}

	/** Sheep with wool near, no shears carried or in a chest, and iron for a pair with nine ingots over. */
	private static boolean shearsToMake(LocalPlayer p) {
		return AfkConfig.get().baseShear && count(p, "shears") == 0 && !Stash.chestHasTool("shears") && woolly(p) != null
			&& iron(p) >= 2 + ingotsLeft();
	}

	/** All the buddy's iron in ingots: loose, and nine to a block, carried and in the chests. */
	static int iron(LocalPlayer p) {
		return count(p, "iron_ingot") + Stash.inChests("iron_ingot") + 9 * (count(p, "iron_block") + Stash.inChests("iron_block"));
	}

	/** The iron ingots the next thing made of iron takes: armour or a tool first, then shears; 0 for none. */
	private static int ironNeed(LocalPlayer p) {
		ToolPlan t = toolPlan(p);
		if (t != null && t.material.equals("iron_ingot")) return t.cost;
		ArmourPlan a = armourPlan(p);
		if (a != null) return a.cost;
		if (shieldToMake(p)) return 1;
		return shearsToMake(p) ? 2 : 0;
	}

	/** Something a chore will use: no chest takes it meanwhile. */
	static boolean keeps(LocalPlayer p, ItemStack st) {
		if (st.isEmpty() || !inBase(p)) return false;
		ensureScan(p);
		String id = AfkHelperClient.itemId(st);
		if (NINE.containsKey(id) && table != null && tight(p, id)) return true;
		if (SEEDS.contains(id) && composter != null) return true;
		if ((st.is(Items.IRON_INGOT) || st.is(Items.IRON_BLOCK)) && ironNeed(p) > 0) return true;
		if ((st.is(ItemTags.PLANKS) || st.is(ItemTags.LOGS)) && shieldToMake(p)) return true;
		if (st.is(Items.STRING) && bowToMake(p)) return true;
		if ((st.is(Items.FLINT) || st.is(Items.FEATHER)) && arrowsToMake(p)) return true;
		if ((st.is(Items.STICK) || st.is(ItemTags.PLANKS) || st.is(ItemTags.LOGS)) && (bowToMake(p) || arrowsToMake(p))) return true;
		if ((st.is(Items.GOLD_INGOT) || st.is(Items.GOLD_BLOCK) || st.is(Items.APPLE)) && applesToMake(p)) return true;
		ToolPlan plan = toolPlan(p);
		return plan != null && (id.equals(plan.material) || st.is(Items.STICK) || st.is(ItemTags.PLANKS) || st.is(ItemTags.LOGS));
	}

	/** The chests of the base run full for this item. */
	private static boolean tight(LocalPlayer p, String id) {
		int[] f = Stash.room(id);
		return f[2] > 0 && (f[0] <= 2 || f[1] <= 6);
	}

	static int count(LocalPlayer p, String id) {
		int n = 0;
		for (int i = 0; i < 36; i++) if (AfkHelperClient.itemId(p.getInventory().getItem(i)).equals(id)) n += p.getInventory().getItem(i).getCount();
		return n;
	}

	// ---------- tools ----------

	private record ToolPlan(String kind, String material, int cost, int sticks) {}

	private static final Map<String, int[]> COST = Map.of("pickaxe", new int[] { 3, 2 }, "axe", new int[] { 3, 2 },
		"shovel", new int[] { 1, 2 }, "sword", new int[] { 2, 1 }, "how", new int[] { 2, 2 });

	/** The tool to make, if any: a kind the buddy lacks and no chest holds, of the best material that leaves enough over. */
	private static ToolPlan toolPlan(LocalPlayer p) {
		if (table == null) return null;
		List<String> kinds = new ArrayList<>(List.of("pickaxe", "axe", "shovel"));
		if (Guard.on()) kinds.add("sword");
		// a hoe while the fields are on, along with the player or in the base, and one to mend a trampled field with (Work)
		if (Work.farm || AfkConfig.get().baseHarvest || Work.trampled()) kinds.add("how");
		for (String k : kinds) {
			// all the buddy carries of it nearly worn out (baseRenew): a new one of iron, unless a chest has a good one to fetch
			boolean renew = Stash.renewing(p, k);
			if (renew ? Stash.chestHasFreshTool(k) : (Stash.hasUsable(p, k) || Stash.chestHasTool(k))) continue;
			int[] c = COST.get(k);
			String mat = null;
			if (iron(p) >= c[0] + ingotsLeft()) mat = "iron_ingot";
			else if (renew) continue;   // early only of iron: the worn one is used up first ("zolang er voldoende iron beschikbaar is")
			else if (count(p, "copper_ingot") + Stash.inChests("copper_ingot") >= c[0] + ingotsLeft()) mat = "copper_ingot";
			if (mat == null) for (String s : List.of("cobblestone", "cobbled_deepslate", "blackstone"))
				if (count(p, s) + Stash.inChests(s) >= c[0]) { mat = s; break; }
			if (mat != null) return new ToolPlan(k, mat, c[0], c[1]);
		}
		return null;
	}

	/**
	 * The tool kind a trip home renews ({@link Renew}): every one of it carried nearly worn out ({@code baseRenew}), and a
	 * fresh one in a chest of the base to fetch, or iron enough for a new one with {@code keepIron} left. The kinds
	 * carried while following; the table is looked for at home.
	 */
	static String renewKind(LocalPlayer p) {
		List<String> kinds = new ArrayList<>(List.of("pickaxe", "axe", "shovel"));
		if (Guard.on()) kinds.add("sword");
		for (String k : kinds)
			if (Stash.renewing(p, k) && (Stash.chestHasFreshTool(k) || iron(p) >= COST.get(k)[0] + ingotsLeft())) return k;
		return null;
	}

	private static String toolId(String kind, String mat) {
		String m = switch (mat) { case "iron_ingot" -> "iron"; case "copper_ingot" -> "copper"; default -> "stone"; };
		return m + "_" + kind;
	}

	/** Sticks short for the tool to make, and no planks or logs carried or in the chests: wood to chop. */
	static boolean woodWanted(LocalPlayer p) {
		if (busy() || !inBase(p)) return false;
		ensureScan(p);
		ToolPlan plan = toolPlan(p);
		boolean forTool = plan != null && count(p, "stick") < plan.sticks && planks(p) < 2 && logs(p) == 0;
		boolean forShield = shieldToMake(p) && planks(p) < 6 && logs(p) == 0;
		boolean forBow = (bowToMake(p) || arrowsToMake(p)) && count(p, "stick") < 3 && planks(p) < 2 && logs(p) == 0;
		if (!forTool && !forShield && !forBow) return false;
		return !Stash.has(id -> ((forTool || forBow) && id.equals("stick")) || id.endsWith("_planks") || id.endsWith("_log") || id.endsWith("_wood") || id.endsWith("_stem") || id.endsWith("_hyphae"));
	}

	// ---------- armour ----------

	private record ArmourPlan(EquipmentSlot slot, String id, int cost, String[] pattern) {}

	private static final EquipmentSlot[] SLOTS = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };

	/**
	 * A piece of iron armour to make from the base's iron: for a slot
	 * where what the buddy wears or carries is worse than iron and no chest holds a piece of iron or better to take,
	 * while nine ingots stay over. An elytra counts as better: it stays on.
	 */
	private static ArmourPlan armourPlan(LocalPlayer p) {
		if (table == null) return null;
		for (EquipmentSlot slot : SLOTS) {
			String id = switch (slot) { case HEAD -> "iron_helmet"; case CHEST -> "iron_chestplate"; case LEGS -> "iron_leggings"; default -> "iron_boots"; };
			double iron = Stash.armour(new ItemStack(switch (slot) { case HEAD -> Items.IRON_HELMET; case CHEST -> Items.IRON_CHESTPLATE; case LEGS -> Items.IRON_LEGGINGS; default -> Items.IRON_BOOTS; }), slot);
			// the piece in that slot nearly worn out, iron or worse (baseRenew): a new iron piece, unless a chest has a fresh one
			boolean renew = Stash.renewingArmour(p, slot) && Stash.armour(p.getItemBySlot(slot), slot) <= iron;
			if (renew ? Stash.chestHasFreshArmour(slot, iron) : (Stash.bestArmourFor(p, slot) >= iron || Stash.chestHasArmour(slot, iron))) continue;
			int cost = switch (slot) { case HEAD -> 5; case CHEST -> 8; case LEGS -> 7; default -> 4; };
			if (iron(p) < cost + ingotsLeft()) return null;
			String[] pattern = switch (slot) {
				case HEAD -> new String[] { "M", "M", "M", "M", "", "M", "", "", "" };
				case CHEST -> new String[] { "M", "", "M", "M", "M", "M", "M", "M", "M" };
				case LEGS -> new String[] { "M", "M", "M", "M", "", "M", "M", "", "M" };
				default -> new String[] { "M", "", "M", "M", "", "M", "", "", "" };
			};
			return new ArmourPlan(slot, id, cost, pattern);
		}
		return null;
	}

	// ---------- for the fight: a shield and golden apples ----------

	/** Golden apples wanted, carried or in the chests (the plan for fighting of 24 Oct 2026, step 1). */
	static final int APPLES_WANTED = 4;

	/** All the buddy's gold in ingots: loose, and nine to a block, carried and in the chests. */
	static int gold(LocalPlayer p) {
		return count(p, "gold_ingot") + Stash.inChests("gold_ingot") + 9 * (count(p, "gold_block") + Stash.inChests("gold_block"));
	}

	private static int planks(LocalPlayer p) { return countMatching(p, x -> x.is(ItemTags.PLANKS)); }

	private static int logs(LocalPlayer p) { return countMatching(p, x -> x.is(ItemTags.LOGS)); }

	/** A shield to block with, in the off hand or the inventory: not about to break. */
	static boolean hasShield(LocalPlayer p) {
		if (p.getOffhandItem().is(Items.SHIELD) && !Work.nearlyBroken(p.getOffhandItem())) return true;
		for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).is(Items.SHIELD) && !Work.nearlyBroken(p.getInventory().getItem(i))) return true;
		return false;
	}

	/**
	 * A shield to make (the plan for fighting: six planks and one iron ingot): while guarding, no usable one carried
	 * or in a chest, one iron ingot while keepIron stays over, at the
	 * base's crafting table. The planks come from what the buddy carries, the chests, or a log made into four.
	 */
	static boolean shieldToMake(LocalPlayer p) {
		return table != null && Guard.on() && !hasShield(p) && !Stash.chestHasTool("shield") && iron(p) >= 1 + ingotsLeft();
	}

	/**
	 * Golden apples to make, leaving a settable amount of gold: fewer than
	 * {@link #APPLES_WANTED} carried and in the chests, an apple, and eight gold ingots while keepGold stays over.
	 */
	static boolean applesToMake(LocalPlayer p) {
		return table != null && count(p, "golden_apple") + Stash.inChests("golden_apple") < APPLES_WANTED
			&& count(p, "apple") + Stash.inChests("apple") >= 1 && gold(p) >= 8 + goldLeft();
	}

	/** Arrows wanted, carried and in the chests, made four to a flint, a stick and a feather. */
	static final int ARROWS_WANTED = 32;

	/** A bow to shoot with, carried. */
	static boolean hasBow(LocalPlayer p) {
		for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).is(Items.BOW) && !Work.nearlyBroken(p.getInventory().getItem(i))) return true;
		return false;
	}

	private static int arrows(LocalPlayer p) {
		return countMatching(p, x -> x.is(ItemTags.ARROWS)) + Stash.inChests("arrow") + Stash.inChests("spectral_arrow") + Stash.inChests("tipped_arrow");
	}

	/**
	 * A bow to make (the plan for fighting, step 2: a bow against phantoms and skeletons on posts): while
	 * guarding, none carried or in a chest, three string carried and in the chests, and three sticks or the wood for them.
	 */
	static boolean bowToMake(LocalPlayer p) {
		return table != null && Guard.on() && !hasBow(p) && !Stash.chestHasTool("bow")
			&& count(p, "string") + Stash.inChests("string") >= 3;
	}

	/** Arrows to make: while guarding, with a bow at hand or to be made, fewer than {@link #ARROWS_WANTED}, a flint and a feather. */
	static boolean arrowsToMake(LocalPlayer p) {
		return table != null && Guard.on() && (hasBow(p) || Stash.chestHasTool("bow") || bowToMake(p)) && arrows(p) < ARROWS_WANTED
			&& count(p, "flint") + Stash.inChests("flint") >= 1 && count(p, "feather") + Stash.inChests("feather") >= 1;
	}

	// ---------- every tick ----------

	/** When nothing else is going on in a base: one chore. True while busy. */
	static boolean tick(Minecraft mc, LocalPlayer p, Player t) {
		long now = System.currentTimeMillis();
		Level level = p.level();
		skipSheep.values().removeIf(u -> u < now);
		skipBlock.values().removeIf(u -> u < now);
		if (step == IDLE) {
			if (now < nextPlan || !inBase(p) || p.containerMenu != p.inventoryMenu) return false;
			nextPlan = now + PLAN_EVERY;
			ensureScan(p);
			return plan(mc, p, now);
		}
		// out of the base (a /tpahere, a teleport home): the chore stays there. A shearing round went on with whatever sheep
		// stood by the buddy at the other end (the review of 22 Oct 2026)
		if (!inBase(p)) { done("no longer in the base"); return false; }
		// a switch turned off on the panel ends its round at once
		if ((kind == Kind.SHEAR && !AfkConfig.get().baseShear) || (kind == Kind.HERD && !herds())) { done("switched off"); return false; }
		if (kind == Kind.SHEAR) return shearTick(mc, p, now);
		if (kind == Kind.HERD) {
			if (Herd.tick(mc, p, now)) return true;
			done(Herd.why);
			return false;
		}
		if (now - stepSince > 45_000) { done("took too long"); return false; }
		// the table or the inlet was open and is shut before its clicks were done (menuTick runs while it is open)
		if (step == MENU) { done("closed"); return false; }
		if (step == ACT) return act(mc, p, now);
		// to the table or the composter's inlet
		Vec3 eye = p.getEyePosition();
		Vec3 point = Work.aimPoint(level, p, eye, at);
		if (point != null && eye.distanceTo(point) > Work.REACH) point = null;   // the reach the spot was chosen with (Work.standCell)
		switch (step) {
			case WALK -> {
				if (point != null) { Follow.stand(); step = OPEN; stepSince = now; clicks = 0; clickedAt = -1; return true; }
				if (stand == null) stand = Work.standCell(level, p, at, t, f -> true);
				if (stand == null || !Work.approach(mc, p, "chore-" + at, Vec3.atBottomCenterOf(stand), now)) { skipBlock.put(at, now + SKIP_FOR); done("cannot reach it"); return false; }
				return true;
			}
			case OPEN -> {
				if (point == null) { step = WALK; stand = null; return true; }
				Follow.stand();
				Follow.aimExact(p, point);
				if (kind == Kind.COMPOST_HAND) { step = ACT; stepSince = now; return true; }
				if (clickedAt >= 0 && Follow.ticks() - clickedAt > 30) { clickedAt = -1; if (++clicks >= 3) { done("does not open"); return false; } }
				if (clickedAt < 0) clickBlock = at;
				return true;
			}
			default -> { return true; }
		}
	}

	/**
	 * Herding strays back in (Herd): its switch, and only through the pens' gates (basePenGates). With the gates closed to
	 * the buddy nothing gets out that way, and the pens' animals are left be.
	 */
	private static boolean herds() { return AfkConfig.get().baseHerd && AfkConfig.get().basePenGates; }

	/** What to do now, if anything, and the start of it. */
	private static boolean plan(Minecraft mc, LocalPlayer p, long now) {
		// an animal out of its pen first: it walks off further all the time (Herd)
		if (herds() && Herd.find(p, now)) return start(Kind.HERD, Herd.what(), null, now);
		// seeds over a stack: the composter; a full one emptied (its bone meal goes into the chests)
		if (composter != null) {
			var cs = p.level().getBlockState(composter);
			boolean reach = !skipBlock.containsKey(composter);
			if (reach && cs.is(Blocks.COMPOSTER) && cs.getValue(ComposterBlock.LEVEL) == 8) return start(Kind.COMPOST_HAND, "bone_meal", composter, now);
			for (String s : SEEDS) if (count(p, s) > 64) {
				BlockPos inlet = inlet(p.level(), composter);
				if (inlet != null && !skipBlock.containsKey(inlet)) return start(Kind.COMPOST_IN, s, inlet, now);
				if (reach) return start(Kind.COMPOST_HAND, s, composter, now);
			}
		}
		// a tool first, so that compacting does not turn the ingots for it into blocks: sticks and planks in the inventory's own grid, then the table
		boolean tableOk = table != null && !skipBlock.containsKey(table);
		// iron that is in blocks: one made into nine ingots in that grid
		int need = ironNeed(p);
		if (need > 0 && count(p, "iron_ingot") < need && count(p, "iron_block") >= 1) return startGrid(Kind.UNPACK, now);
		ToolPlan plan = toolPlan(p);
		if (plan != null && count(p, plan.material) >= plan.cost) {
			if (count(p, "stick") < plan.sticks) {
				if (countMatching(p, st -> st.is(ItemTags.PLANKS)) >= 2) return startGrid(Kind.STICKS, now);
				if (countMatching(p, st -> st.is(ItemTags.LOGS)) >= 1) return startGrid(Kind.PLANKS, now);
			} else if (tableOk) {
				material = plan.material;
				return start(Kind.TOOL, plan.kind, table, now);
			}
		}
		ArmourPlan ap = armourPlan(p);
		if (ap != null && tableOk && count(p, "iron_ingot") >= ap.cost) {
			material = "iron_ingot";
			return start(Kind.ARMOUR, ap.id, table, now);
		}
		// for the fight: a shield of six planks and an iron ingot, golden apples of eight gold ingots and an apple
		if (shieldToMake(p)) {
			if (planks(p) < 6) { if (logs(p) >= 1) return startGrid(Kind.PLANKS, now); }
			else if (count(p, "iron_ingot") >= 1 && tableOk) return start(Kind.SHIELD, "shield", table, now);
		}
		// a bow (three sticks and three string) and arrows (a flint, a stick and a feather make four)
		boolean bow = bowToMake(p), arrows = arrowsToMake(p);
		if (bow || arrows) {
			int sticks = bow ? 3 : 1;
			if (count(p, "stick") < sticks) {
				if (planks(p) >= 2) return startGrid(Kind.STICKS, now);
				if (logs(p) >= 1) return startGrid(Kind.PLANKS, now);
			} else if (tableOk) {
				if (bow && count(p, "string") >= 3) return start(Kind.BOW, "bow", table, now);
				if (!bow && count(p, "flint") >= 1 && count(p, "feather") >= 1) return start(Kind.ARROWS, "arrow", table, now);
			}
		}
		if (applesToMake(p)) {
			if (count(p, "gold_ingot") < 8 && count(p, "gold_block") >= 1) return startGrid(Kind.UNPACK_GOLD, now);
			if (count(p, "gold_ingot") >= 8 && count(p, "apple") >= 1 && tableOk) return start(Kind.GAPPLE, "golden_apple", table, now);
		}
		// full chests: what the buddy carries into blocks
		if (tableOk) for (var e : NINE.entrySet()) if (count(p, e.getKey()) >= 9 && tight(p, e.getKey()) && spare(p, e.getKey()) >= 9) return start(Kind.COMPACT, e.getKey(), table, now);
		// sheep with wool: shears first, made from two iron ingots if there are none and enough iron stays over
		Sheep s = AfkConfig.get().baseShear ? woolly(p) : null;
		if (s != null) {
			if (count(p, "shears") == 0) {
				if (shearsToMake(p) && count(p, "iron_ingot") >= 2) return startGrid(Kind.SHEARS_MAKE, now);
				return false;
			}
			sheep = s;
			return start(Kind.SHEAR, "sheep", null, now);
		}
		return false;
	}

	private static boolean start(Kind k, String w, BlockPos target, long now) {
		kind = k; what = w; at = target; step = WALK; stepSince = now; stand = null; clickedAt = -1; clicks = 0;
		shorn = 0; collecting = false; wool = null; woolStand = null; gaveUpWool.clear();
		queue.clear();
		// with what there is of the iron or gold it uses, carried and in the chests: what keepIron and keepGold are checked against
		LocalPlayer me = AfkHelperClient.mc().player;
		int stock = me == null ? -1 : k == Kind.GAPPLE ? gold(me) : k == Kind.SHIELD || k == Kind.ARMOUR || (k == Kind.TOOL && "iron_ingot".equals(material)) ? iron(me) : -1;
		Exchange.emit("chore", "action", "starts", "what", k.name().toLowerCase(), "item", w, "material", k == Kind.TOOL || k == Kind.ARMOUR ? material : "", "stock", stock);
		return true;
	}

	/** A recipe in the inventory's own 2x2 grid: no table and no screen, real clicks on the inventory menu. */
	private static boolean startGrid(Kind k, long now) {
		kind = k; what = k.name().toLowerCase(); step = ACT; stepSince = now; nextClick = now;
		queue.clear();
		LocalPlayer p = AfkHelperClient.mc().player;
		Minecraft mc = AfkHelperClient.mc();
		int menu = p.inventoryMenu.containerId;
		java.util.function.IntUnaryOperator inv = i -> i < 9 ? 36 + i : i;
		switch (k) {
			case STICKS -> { int s = slotOf(p, st -> st.is(ItemTags.PLANKS)); placeOne(mc, p, menu, inv.applyAsInt(s), 1); placeOne(mc, p, menu, inv.applyAsInt(s), 3); }
			case PLANKS -> { int s = slotOf(p, st -> st.is(ItemTags.LOGS)); placeOne(mc, p, menu, inv.applyAsInt(s), 1); }
			case UNPACK -> { int s = slotOf(p, st -> st.is(Items.IRON_BLOCK)); placeOne(mc, p, menu, inv.applyAsInt(s), 1); }
			case UNPACK_GOLD -> { int s = slotOf(p, st -> st.is(Items.GOLD_BLOCK)); placeOne(mc, p, menu, inv.applyAsInt(s), 1); }
			default -> { int s = slotOf(p, st -> st.is(Items.IRON_INGOT)); placeOne(mc, p, menu, inv.applyAsInt(s), 2); placeOne(mc, p, menu, inv.applyAsInt(s), 3); }
		}
		queue.add(() -> Game.quickMove(mc.gameMode, menu, 0, p));
		queue.add(() -> { for (int g = 1; g <= 4; g++) if (!p.inventoryMenu.slots.get(g).getItem().isEmpty()) Game.quickMove(mc.gameMode, menu, g, p); });
		Exchange.emit("chore", "action", "starts", "what", what);
		return true;
	}

	/** One of the stack in that slot into a grid cell: pick it up, one down with the right button, the rest back. */
	private static void placeOne(Minecraft mc, LocalPlayer p, int menu, int from, int cell) {
		queue.add(() -> Game.click(mc.gameMode, menu, from, 0, false, p));
		queue.add(() -> Game.click(mc.gameMode, menu, cell, 1, false, p));
		queue.add(() -> Game.click(mc.gameMode, menu, from, 0, false, p));
	}

	/** One item into each of these cells, from as many stacks of it as it takes. */
	private static void spread(Minecraft mc, LocalPlayer p, int menu, java.util.function.IntUnaryOperator inv, Predicate<ItemStack> what, int[] cells) {
		int c = 0;
		for (int i = 0; i < 36 && c < cells.length; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (st.isEmpty() || !what.test(st)) continue;
			for (int n = st.getCount(); n > 0 && c < cells.length; n--) placeOne(mc, p, menu, inv.applyAsInt(i), cells[c++]);
		}
	}

	/** The clicks one after the other, a few ticks apart; the composter by hand; the wool after shearing. */
	private static boolean act(Minecraft mc, LocalPlayer p, long now) {
		if (now < nextClick) return true;
		nextClick = now + 150;
		if (kind == Kind.COMPOST_HAND) return compostByHand(mc, p, now);
		if (queue.isEmpty()) {
			if (p.containerMenu != p.inventoryMenu) p.closeContainer();
			done("done");
			return false;
		}
		queue.poll().run();
		return true;
	}

	/** The table or the inlet is open: the clicks for the recipe, or the seeds moved in. */
	static boolean menuTick(Minecraft mc, LocalPlayer p) {
		long now = System.currentTimeMillis();
		AbstractContainerMenu m = p.containerMenu;
		if (step == OPEN) {
			if (m == p.inventoryMenu) return true;
			step = MENU;
			stepSince = now;
			nextClick = now + 300;
			fill(mc, p, m);
			return true;
		}
		if (now - stepSince > 45_000) { done("took too long"); return false; }
		return act(mc, p, now);
	}

	/** The clicks for what the open menu is for. */
	private static void fill(Minecraft mc, LocalPlayer p, AbstractContainerMenu m) {
		queue.clear();
		int menu = m.containerId, n = m.slots.size() - 36;
		java.util.function.IntUnaryOperator inv = i -> n + (i < 9 ? 27 + i : i - 9);
		if (kind == Kind.COMPOST_IN) {
			// the seeds over a stack into the hopper or chest on the composter
			int over = count(p, what) - 64;
			List<Integer> slots = new ArrayList<>();
			for (int i = 0; i < 36; i++) if (AfkHelperClient.itemId(p.getInventory().getItem(i)).equals(what)) slots.add(i);
			slots.sort(Comparator.comparingInt(i -> p.getInventory().getItem(i).getCount()));
			for (int i : slots) {
				if (over <= 0) break;
				int slot = inv.applyAsInt(i);
				over -= p.getInventory().getItem(i).getCount();
				queue.add(() -> Game.quickMove(mc.gameMode, menu, slot, p));
			}
			return;
		}
		if (!(m instanceof CraftingMenu)) return;
		if (kind == Kind.COMPACT) {
			// a stack spread over the nine cells, the rest back, the blocks out; as often as there are stacks of nine
			for (int i = 0; i < 36; i++) {
				ItemStack st = p.getInventory().getItem(i);
				if (!AfkHelperClient.itemId(st).equals(what) || st.getCount() < 9) continue;
				int slot = inv.applyAsInt(i);
				queue.add(() -> Game.click(mc.gameMode, menu, slot, 0, false, p));
				queue.add(() -> Game.spread(mc.gameMode, menu, new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 }, p));
				queue.add(() -> Game.click(mc.gameMode, menu, slot, 0, false, p));
				queue.add(() -> Game.quickMove(mc.gameMode, menu, 0, p));
			}
		} else if (kind == Kind.TOOL) {
			String[] pattern = switch (what) {
				case "pickaxe" -> new String[] { "M", "M", "M", "", "S", "", "", "S", "" };
				case "axe" -> new String[] { "M", "M", "", "M", "S", "", "", "S", "" };
				case "shovel" -> new String[] { "", "M", "", "", "S", "", "", "S", "" };
				case "sword" -> new String[] { "", "M", "", "", "M", "", "", "S", "" };
				default -> new String[] { "M", "M", "", "", "S", "", "", "S", "" };
			};
			int mat = slotOf(p, st -> AfkHelperClient.itemId(st).equals(material)), stick = slotOf(p, st -> st.is(Items.STICK));
			if (mat < 0 || stick < 0) return;
			for (int c = 0; c < 9; c++) {
				if (pattern[c].isEmpty()) continue;
				placeOne(mc, p, menu, inv.applyAsInt(pattern[c].equals("M") ? mat : stick), 1 + c);
			}
			queue.add(() -> Game.quickMove(mc.gameMode, menu, 0, p));
		} else if (kind == Kind.ARMOUR) {
			ArmourPlan ap = armourPlan(p);
			int mat = slotOf(p, st -> st.is(Items.IRON_INGOT));
			if (ap == null || !ap.id.equals(what) || mat < 0) return;
			for (int c = 0; c < 9; c++) if (!ap.pattern[c].isEmpty()) placeOne(mc, p, menu, inv.applyAsInt(mat), 1 + c);
			queue.add(() -> Game.quickMove(mc.gameMode, menu, 0, p));
		} else if (kind == Kind.SHIELD) {
			// planks, an iron ingot, planks / three planks / a plank in the middle; the planks of any kind, from several stacks
			int iron = slotOf(p, st -> st.is(Items.IRON_INGOT));
			if (iron < 0 || planks(p) < 6) return;
			spread(mc, p, menu, inv, st -> st.is(ItemTags.PLANKS), new int[] { 1, 3, 4, 5, 6, 8 });
			placeOne(mc, p, menu, inv.applyAsInt(iron), 2);
			queue.add(() -> Game.quickMove(mc.gameMode, menu, 0, p));
		} else if (kind == Kind.BOW) {
			// sticks on the left of the bow's shape, string down the right
			int stick = slotOf(p, st -> st.is(Items.STICK)), string = slotOf(p, st -> st.is(Items.STRING));
			if (stick < 0 || string < 0 || count(p, "stick") < 3 || count(p, "string") < 3) return;
			spread(mc, p, menu, inv, st -> st.is(Items.STICK), new int[] { 2, 4, 8 });
			spread(mc, p, menu, inv, st -> st.is(Items.STRING), new int[] { 3, 6, 9 });
			queue.add(() -> Game.quickMove(mc.gameMode, menu, 0, p));
		} else if (kind == Kind.ARROWS) {
			// flint over a stick over a feather, as many at once as make the arrows lacking (eight at most)
			int sets = Math.min(8, Math.min(count(p, "flint"), Math.min(count(p, "feather"), count(p, "stick"))));
			sets = Math.min(sets, Math.max(1, (ARROWS_WANTED - arrows(p) + 3) / 4));
			if (sets < 1) return;
			int[] col = { 2, 5, 8 };
			String[] ids = { "flint", "stick", "feather" };
			for (int k = 0; k < 3; k++) {
				final String id = ids[k];
				int[] cells = new int[sets];
				java.util.Arrays.fill(cells, col[k]);
				spread(mc, p, menu, inv, st -> AfkHelperClient.itemId(st).equals(id), cells);
			}
			queue.add(() -> Game.quickMove(mc.gameMode, menu, 0, p));
		} else if (kind == Kind.GAPPLE) {
			// eight gold ingots around an apple
			int apple = slotOf(p, st -> st.is(Items.APPLE));
			if (apple < 0 || count(p, "gold_ingot") < 8) return;
			spread(mc, p, menu, inv, st -> st.is(Items.GOLD_INGOT), new int[] { 1, 2, 3, 4, 6, 7, 8, 9 });
			placeOne(mc, p, menu, inv.applyAsInt(apple), 5);
			queue.add(() -> Game.quickMove(mc.gameMode, menu, 0, p));
		}
		// whatever stayed in the grid back into the inventory
		queue.add(() -> { for (int g = 1; g <= 9; g++) if (!m.slots.get(g).getItem().isEmpty()) Game.quickMove(mc.gameMode, menu, g, p); });
	}

	/** The composter without an inlet: seeds in by hand, one a click; ripe bone meal out with a click. */
	private static boolean compostByHand(Minecraft mc, LocalPlayer p, long now) {
		var st = p.level().getBlockState(composter);
		if (!st.is(Blocks.COMPOSTER)) { done("composter gone"); return false; }
		int lvl = st.getValue(ComposterBlock.LEVEL);
		if (count(p, what) <= 64 && lvl < 8) { done("done"); return false; }
		if (lvl == 7) return true;   // it turns to bone meal by itself in a moment
		// the seed there is too much of in hand, and only that: with wheat seeds in hand those were composted whatever was over,
		// also below the stack the fields need (the review of 22 Oct 2026)
		if (lvl < 8 && !AfkHelperClient.itemId(p.getMainHandItem()).equals(what)) {
			AfkHelperClient.toHotbar(p, mc, s -> AfkHelperClient.itemId(s).equals(what), Comparator.comparingInt(ItemStack::getCount), "seeds");
			for (int i = 0; i < 9; i++) if (AfkHelperClient.itemId(p.getInventory().getItem(i)).equals(what)) { p.getInventory().setSelectedSlot(i); break; }
			if (!AfkHelperClient.itemId(p.getMainHandItem()).equals(what)) { Follow.stand(); return true; }
		}
		Vec3 point = Work.aimPoint(p.level(), p, p.getEyePosition(), composter);
		if (point == null) { step = WALK; stand = null; return true; }
		Follow.stand();
		Follow.aimExact(p, point);
		clickBlock = composter;
		return true;
	}

	// ---------- shearing ----------

	/**
	 * A round in the pen: every sheep with wool that can be got to is shorn first, one after the other, and then all the wool
	 * is picked up (until then the buddy sheared one, went for its wool, gave up, and carried it to a chest before the next). The round is
	 * one chore, so
	 * no chest visit comes in between. The clock of the chore runs per sheep and per piece of wool.
	 */
	private static boolean shearTick(Minecraft mc, LocalPlayer p, long now) {
		if (collecting) return collectWool(mc, p, now);
		if (sheep != null && sheep.isAlive() && sheep.isSheared()) {
			if (clickedAt >= 0) shorn++;
			nextSheep(p, now);
			return true;
		}
		if (sheep == null || !sheep.isAlive() || count(p, "shears") == 0) { nextSheep(p, now); return true; }
		// a sheep that takes half a minute is left for five, and the round goes on (on a real server the whole round ended after
		// 45 s by the pen, 22 Oct 2026)
		if (now - stepSince > SHEEP_FOR) { skipSheep.put(sheep.getId(), now + 300_000); nextSheep(p, now); return true; }
		Vec3 aimAt = sheep.position().add(0, sheep.getBbHeight() * 0.6, 0);
		if (p.getEyePosition().distanceTo(aimAt) <= 2.8 && sheepClear(p.level(), p.getEyePosition(), aimAt)) {
			Follow.stand();
			Follow.aim(p, aimAt);
			if (step != OPEN) { step = OPEN; stepSince = now; clickedAt = -1; clicks = 0; }
			clickEntity = true;
			// a click that did not shear it in half a second (the shears not in hand yet, the crosshair on the fence): again,
			// three times, then the sheep is left for a while
			if (clickedAt >= 0 && Follow.ticks() - clickedAt > 10) {
				clickedAt = -1;
				if (++clicks >= 3) { skipSheep.put(sheep.getId(), now + 300_000); nextSheep(p, now); }
			}
			return true;
		}
		// to a cell from where it is in reach, not to where it stands: the walk took the post of the pen's fence beside it for
		// a place to stand, and the buddy stood 45 s at the edge of the pit by the pen (22 Oct 2026)
		if (sheepStand == null || sheep.position().distanceTo(sheepAt) > 1.0) { sheepStand = shearCell(p.level(), p, sheep); sheepAt = sheep.position(); }
		if (sheepStand == null) { skipSheep.put(sheep.getId(), now + 300_000); nextSheep(p, now); return true; }
		if (!Work.approach(mc, p, sheepStand, Vec3.atBottomCenterOf(sheepStand), now)) { Work.badStand(sheepStand); sheepStand = null; }
		return true;
	}

	/** Nothing but air between the buddy's eye and the sheep: the click hits the sheep, not the fence between. */
	private static boolean sheepClear(Level level, Vec3 eye, Vec3 at) {
		return level.clip(new net.minecraft.world.level.ClipContext(eye, at, net.minecraft.world.level.ClipContext.Block.OUTLINE,
			net.minecraft.world.level.ClipContext.Fluid.NONE, AfkHelperClient.mc().player)).getType() == HitResult.Type.MISS;
	}

	/**
	 * A cell to shear this sheep from: the sheep within reach of the eye there and nothing in between, never on a fence,
	 * wall or gate; the nearest to the buddy. Outside the pen over its fence, or inside through its gate: the walk decides.
	 */
	private static BlockPos shearCell(Level level, LocalPlayer p, Sheep s) {
		Vec3 aim = s.position().add(0, s.getBbHeight() * 0.6, 0);
		BlockPos at = s.blockPosition(), best = null;
		double bd = Double.MAX_VALUE;
		for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -1; dy <= 1; dy++) {
			BlockPos c = at.offset(dx, dy, dz);
			if (Work.badStandAt(c)) continue;
			double feet = Follow.floor(level, c);
			if (Double.isNaN(feet) || onPost(level, c, feet)) continue;
			Vec3 eye = new Vec3(c.getX() + 0.5, feet + p.getEyeHeight(), c.getZ() + 0.5);
			if (eye.distanceTo(aim) > 2.5 || !sheepClear(level, eye, aim)) continue;
			double d = p.position().distanceToSqr(Vec3.atBottomCenterOf(c));
			if (d < bd) { bd = d; best = c.immutable(); }
		}
		return best;
	}

	/**
	 * Standing in this cell is standing on a fence, a wall or a gate, or in a gate or a doorway ({@link Work#inDoorway}): no
	 * place to walk to, and a jump does not get onto a post. The post is the block under the cell: it stands half a block into the cell above it, so the feet
	 * are in the cell (the first version looked at the block at the feet, the air, and the buddy jumped at the pen's posts four
	 * times on the test server).
	 */
	private static boolean onPost(Level level, BlockPos c, double feet) {
		if (Work.inDoorway(level, c)) return true;
		for (BlockPos q : new BlockPos[] { c, c.below() }) {
			var st = level.getBlockState(q);
			if (st.is(BlockTags.FENCES) || st.is(BlockTags.WALLS) || st.is(BlockTags.FENCE_GATES)) return true;
		}
		return false;
	}

	/** The next sheep with wool, or, when there is none to get to, the wool. */
	private static void nextSheep(LocalPlayer p, long now) {
		sheep = count(p, "shears") > 0 ? woolly(p) : null;
		sheepStand = null;
		stepSince = now;
		clickedAt = -1;
		clicks = 0;
		clickEntity = false;
		if (sheep != null) { step = WALK; return; }
		collecting = true;
		step = ACT;
		Exchange.emit("chore", "action", "picking up wool", "sheep", shorn);
	}

	/** After the shearing: each piece of wool, from a cell where the game hands it over. */
	private static boolean collectWool(Minecraft mc, LocalPlayer p, long now) {
		Level level = p.level();
		if (wool != null && (!wool.isAlive() || now - stepSince > 20_000)) {
			if (wool.isAlive()) gaveUpWool.add(wool.getId());
			wool = null;
		}
		if (wool == null) {
			for (ItemEntity it : level.getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(WOOL_NEAR, 4, WOOL_NEAR))) {
				if (!it.getItem().is(ItemTags.WOOL) || it.tickCount > 6000 || gaveUpWool.contains(it.getId())) continue;
				if (wool == null || it.distanceToSqr(p) < wool.distanceToSqr(p)) wool = it;
			}
			if (wool == null) {
				Exchange.emit("chore", "action", "shorn", "sheep", shorn, "wool", countMatching(p, st -> st.is(ItemTags.WOOL)));
				done("done");
				return false;
			}
			stepSince = now;
			woolNearSince = 0;
			woolStand = null;
		}
		// On the ground the buddy walks into it, as into a drop of the dig-along; on a post, to the cell beside it nearest to it.
		// Not standing still at the edge of the game's reach: there the client and the server disagree and a sheep pushes
		// the buddy out of it, which left a wait for no reason.
		BlockPos lies = wool.blockPosition();
		boolean ground = !onPost(level, lies, 0) && !Double.isNaN(Follow.floor(level, lies));
		if (!ground && woolStand == null) {
			woolStand = pickupCell(level, p, wool);
			if (woolStand == null) { gaveUpWool.add(wool.getId()); wool = null; return true; }
		}
		Vec3 goal = ground ? wool.position() : Vec3.atBottomCenterOf(woolStand);
		// within a step and a half and still not picked up in three seconds (a fence between, full, or the server sees it
		// elsewhere): the next piece, where the buddy would stand "close enough" until the chore's clock ran out
		if (Math.hypot(goal.x - p.getX(), goal.z - p.getZ()) < 1.6) {
			if (woolNearSince == 0) woolNearSince = now;
			else if (now - woolNearSince > 3000) { gaveUpWool.add(wool.getId()); wool = null; return true; }
		} else woolNearSince = 0;
		if (!Work.approach(mc, p, ground ? (Object) ("wool-" + wool.getId()) : woolStand, goal, now)) {
			if (ground) { gaveUpWool.add(wool.getId()); wool = null; return true; }
			// that cell gets nowhere: another one beside the wool, else the wool is left
			Work.badStand(woolStand);
			woolStand = pickupCell(level, p, wool);
			if (woolStand == null) { gaveUpWool.add(wool.getId()); wool = null; }
		}
		return true;
	}

	/**
	 * A cell to stand in where the game picks this item up (its box within the buddy's, one block wider all round and half a
	 * block higher and lower), the one nearest to the item, then to the buddy, so that it lies well within reach and not at
	 * its edge; never on top of a fence, wall or gate. Wool that lay on the posts of
	 * the pen was walked to as it lay, and the jump onto a post went beside it into the pit by the pen, a heart of fall
	 * damage (22 Oct 2026).
	 */
	private static BlockPos pickupCell(Level level, LocalPlayer p, ItemEntity it) {
		BlockPos at = it.blockPosition(), best = null;
		double bd = Double.MAX_VALUE;
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -2; dy <= 0; dy++) {
			BlockPos c = at.offset(dx, dy, dz);
			if (Work.badStandAt(c)) continue;
			double feet = Follow.floor(level, c);
			if (Double.isNaN(feet) || onPost(level, c, feet)) continue;
			AABB reach = new AABB(c.getX() + 0.2, feet, c.getZ() + 0.2, c.getX() + 0.8, feet + 1.8, c.getZ() + 0.8).inflate(1.0, 0.5, 1.0);
			if (!reach.intersects(it.getBoundingBox())) continue;
			Vec3 mid = Vec3.atBottomCenterOf(c);
			double d = Math.hypot(mid.x - it.getX(), mid.z - it.getZ()) * 100 + p.position().distanceToSqr(mid);
			if (d < bd) { bd = d; best = c.immutable(); }
		}
		return best;
	}

	/** Start of the tick: the click on the table, the inlet, the composter or the sheep, if the crosshair is on it. */
	static void startTick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.gameMode == null || step == IDLE || p.isShiftKeyDown()) return;
		// a bite comes first: the shears taken into the hand took the food out of it, and starving at food 0 by the pen the buddy
		// tried twice and ate nothing for forty seconds (the scenarios of 23 Oct 2026)
		if (AfkHelperClient.isEating()) { clickEntity = false; clickBlock = null; return; }
		if (clickEntity && kind == Kind.SHEAR && sheep != null) {
			clickEntity = false;
			if (clickedAt >= 0) return;
			if (!p.getMainHandItem().is(Items.SHEARS)) {
				// the shears in the hotbar first: fetched from the backpack each time something else was held, six pairs filled
				// the hotbar and no food could come into it (the scenarios of 23 Oct 2026)
				int at = -1;
				for (int i = 0; i < 9 && at < 0; i++) if (p.getInventory().getItem(i).is(Items.SHEARS)) at = i;
				if (at >= 0) p.getInventory().setSelectedSlot(at);
				else AfkHelperClient.toHotbar(p, mc, s -> s.is(Items.SHEARS), Comparator.comparingInt(ItemStack::getDamageValue), "shears");
				return;
			}
			if (mc.hitResult instanceof EntityHitResult eh && eh.getEntity() == sheep) {
				InteractionResult r = Game.interactEntity(mc.gameMode, p, sheep, eh, InteractionHand.MAIN_HAND);
				if (r.consumesAction()) Game.swingUse(p, InteractionHand.MAIN_HAND);
				clickedAt = Follow.ticks();
			}
			return;
		}
		BlockPos want = clickBlock;
		clickBlock = null;
		if (want == null || !(mc.hitResult instanceof BlockHitResult bh) || bh.getType() != HitResult.Type.BLOCK || !bh.getBlockPos().equals(want)) return;
		InteractionResult r = AfkHelperClient.useOn(p, InteractionHand.MAIN_HAND, bh);
		if (r.consumesAction()) Game.swingUse(p, InteractionHand.MAIN_HAND);
		if (kind != Kind.COMPOST_HAND) clickedAt = Follow.ticks();
	}

	// ---------- the base's things ----------

	/**
	 * The table and the composter, looked for again every 30 s. Stash comes before the chores in Work's chain and asks
	 * what to keep the moment the buddy is in the base, so they have to be known then: seeds stored before the first look
	 * never reached the composter.
	 */
	private static void ensureScan(LocalPlayer p) {
		long now = System.currentTimeMillis();
		if (now < nextScan) return;
		nextScan = now + SCAN_EVERY;
		scan(p.level(), p);
	}

	/** The crafting table and the composter nearest to the buddy, within reach and in the base. */
	private static void scan(Level level, LocalPlayer p) {
		BlockPos me = p.blockPosition();
		BlockPos t = null, c = null;
		double dt = Double.MAX_VALUE, dc = Double.MAX_VALUE;
		int r = (int) REACH;
		for (BlockPos q : BlockPos.betweenClosed(me.offset(-r, -5, -r), me.offset(r, 5, r))) {
			var st = level.getBlockState(q);
			if (st.is(Blocks.CRAFTING_TABLE)) { double d = q.distSqr(me); if (d < dt) { dt = d; t = q.immutable(); } }
			else if (st.is(Blocks.COMPOSTER)) { double d = q.distSqr(me); if (d < dc) { dc = d; c = q.immutable(); } }
		}
		if ((t == null) != (table == null) || (c == null) != (composter == null))
			Exchange.emit("chore", "action", "found", "crafting table", t == null ? "" : t.toShortString(), "composter", c == null ? "" : c.toShortString());
		table = t;
		composter = c;
	}

	/** A hopper on the composter, or a chest on that hopper: where seeds go in without clicking each one. */
	private static BlockPos inlet(Level level, BlockPos comp) {
		BlockPos h = comp.above();
		if (!(level.getBlockEntity(h) instanceof HopperBlockEntity)) return null;
		BlockPos above = h.above();
		return level.getBlockEntity(above) instanceof Container && !(level.getBlockEntity(above) instanceof HopperBlockEntity) ? above : h;
	}

	/** A sheep of the base with wool, grown, within reach: the nearest, not one that could not be got to lately. */
	/**
	 * The nearest sheep with wool in the base. In the whole base, not within {@link #REACH}: on a real server the buddy stands by
	 * the bed, 21 blocks from the sheep pen, and in a quarter of an hour with shearing on and seventeen sheep in wool not
	 * one was shorn (23 Oct 2026); on the test server the scripts had set the buddy down by the pen.
	 */
	private static Sheep woolly(LocalPlayer p) {
		Sheep best = null;
		double bd = Double.MAX_VALUE;
		for (Sheep s : p.level().getEntitiesOfClass(Sheep.class, p.getBoundingBox().inflate(Base.RADIUS, 8, Base.RADIUS))) {
			if (!s.isAlive() || s.isSheared() || s.isBaby() || skipSheep.containsKey(s.getId()) || !inBase(p.level(), s.position())) continue;
			double d = s.distanceToSqr(p);
			if (d < bd) { bd = d; best = s; }
		}
		return best;
	}

	private static int slotOf(LocalPlayer p, Predicate<ItemStack> what) {
		int best = -1;
		for (int i = 0; i < 36; i++) if (what.test(p.getInventory().getItem(i)) && (best < 0 || p.getInventory().getItem(i).getCount() > p.getInventory().getItem(best).getCount())) best = i;
		return best;
	}

	private static int countMatching(LocalPlayer p, Predicate<ItemStack> what) {
		int n = 0;
		for (int i = 0; i < 36; i++) if (what.test(p.getInventory().getItem(i))) n += p.getInventory().getItem(i).getCount();
		return n;
	}

	private static void done(String why) {
		LocalPlayer p = AfkHelperClient.mc().player;
		if (kind == Kind.HERD) Herd.reset(p);
		if (p != null && step == MENU && p.containerMenu != p.inventoryMenu) p.closeContainer();
		Exchange.emit("chore", "action", "done", "what", kind == null ? "" : kind.name().toLowerCase(), "item", what == null ? "" : what, "reason", why);
		step = IDLE;
		queue.clear();
		sheep = null;
		collecting = false;
		wool = null;
		woolStand = null;
		clickBlock = null;
		clickEntity = false;
		nextPlan = System.currentTimeMillis() + 1000;
	}

	static void stop(String why) { if (step != IDLE) done(why); }

	/** No base kept any more (a teleport, a /tpahere): a chore under way is left, unless the buddy is still in the base. */
	static void leftBase(LocalPlayer p) { if (step != IDLE && !inBase(p)) done("no longer in the base"); }
}
