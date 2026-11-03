package net.milenco.buddy.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.milenco.buddy.version.Game;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * The chests in the player's base: filled and emptied with sense (emptied for food and tools, for instance), and the
 * best armour at hand is worn.
 * <p>
 * Which chests are the player's the buddy learns by watching: a chest or barrel the player looks at while its lid is
 * open is one the player opened. Those are kept in {@code chests.json} in the exchange folder, with what lay in each the
 * last time the buddy looked inside. While both are within reach of such chests and the buddy has nothing else to do,
 * the buddy fetches what is lacking (food when fewer than eight are left, a pickaxe, axe or shovel when there is none or
 * only one about to break, a weapon, better armour than is worn; unenchanted before enchanted, so the player's best gear
 * stays where it is), puts what is carried in a chest that already holds that item, and only when the inventory is
 * nearly full the rest in the chest with the most room. Kept on the buddy: the best tool of each kind, the weapon, two
 * stacks' worth of food, saplings, torches and the like. Better armour that is carried goes on, at a look every second.
 */
final class Stash {
	private Stash() {}

	static boolean on = true;

	private static final class Chest {
		String server, dim, owner;   // owner: the player whose chest it is, in small letters
		BlockPos pos;
		final Map<String, Integer> items = new HashMap<>();
		int size, free = -1;      // free -1: never looked inside
		long seen;
		boolean full;
		/**
		 * What has lain in it, ever, less what the buddy put in only because it had room. It decided where a thing went
		 * when no chest held it any more (21 Oct 2026); since the copper golem's way (22 Oct 2026) it is kept for the
		 * record only.
		 */
		final Set<String> had = new HashSet<>();
		/** What the buddy put in it only because it had room: never counted as its own. */
		final Set<String> strays = new HashSet<>();
		/** Tools and armour in it with more than a tenth of their durability left (not {@link Work#lowWear}). */
		final Set<String> fresh = new HashSet<>();
	}

	private static final List<Chest> chests = new ArrayList<>();
	/**
	 * A visit that gets nowhere for this long ends: no step nearer the chest, no click sent, no stack moved. No limit on
	 * the whole visit: the walk to a chest may be long, and a whole chest has to be filled or emptied. On 22 Oct
	 * the buddy stood 17 minutes in the cellar in front of one chest, a click
	 * that never went out and no clock on that step.
	 */
	static final long STUCK = 10_000;
	/**
	 * What was gone from a chest where it lay at the last look, and since when: no look every hour, only when a thing is
	 * no longer where it lay, and then the known chests first and new ones after. Before such a thing goes into a chest
	 * it never lay in, every chest looked into before then is looked
	 * into again. Found again (a chest holds it) it is no longer missing.
	 */
	private static final Map<String, Long> missing = new HashMap<>();
	private static boolean loaded;

	/** The known chests on this server in this dimension, for the panel's map. */
	static List<BlockPos> known(String dim) {
		List<BlockPos> out = new ArrayList<>();
		for (Chest c : chests) if (server().equals(c.server) && dim.equals(c.dim)) out.add(c.pos);
		return out;
	}

	private static final int IDLE = 0, WALK = 1, OPEN = 2, MENU = 3;
	private static int step = IDLE;
	private static Chest visit;
	private static String why;
	private static boolean fallback, depositDone, read;
	private static long stepSince, nextClick, lastRun, nextPlan, nextWear, progressAt, lastTickAt;
	/** The eyes' nearest distance to the chest in this visit: only a new best is a step forward (a walk to and fro is none). */
	private static double bestDist;
	/**
	 * The nearest distance to the spot walked to, from where the buddy was when it was chosen: a way round a wall gets
	 * nearer the spot before it gets nearer the chest. A new spot starts at the distance of that moment, so choosing one
	 * is no step forward.
	 */
	private static double bestStand;
	private static BlockPos bestStandFor;
	/** The walker's count of route cells reached (Follow.cellsWalked) at the last look: a new cell is progress. */
	private static int cellsSeen;
	private static BlockPos clickAt, stand;
	private static int clickedAt = -1, clicks, moved, took;
	private static final Set<String> taken = new HashSet<>();
	private static final Map<BlockPos, Long> skip = new HashMap<>();

	static boolean busy() { return step != IDLE; }

	/** The carrier is home: everything it carries goes into a chest, where none holds the same into the roomiest. */
	private static boolean forcing;

	static void forceStore(boolean on) { forcing = on; }

	static boolean inMenu(LocalPlayer p) { return step == MENU || (step == OPEN && p.containerMenu != p.inventoryMenu); }

	// ---------- learning ----------

	/** The player looks at a chest or barrel that stands open: opened by the player, so one of the player's. */
	static void watch(LocalPlayer p, Player t, BlockPos look) {
		if (!on || look == null || t.distanceTo(p) > 32) return;
		Level level = p.level();
		BlockState st = level.getBlockState(look);
		boolean open = level.getBlockEntity(look) instanceof ChestBlockEntity c ? c.getOpenNess(1f) > 0.05f
			: st.getBlock() instanceof BarrelBlock && st.getValue(BarrelBlock.OPEN);
		if (!open || (visit != null && step != IDLE && (visit.pos.equals(look) || look.equals(otherHalf(level, visit.pos))))) return;
		learn(level, look, "seen", t.getGameProfile().name());
	}

	/** The player the alt works for now, in small letters: that player's chests are the ones used. */
	private static String owner() {
		String t = Follow.boss();
		return t == null ? "" : t.toLowerCase(java.util.Locale.ROOT);
	}

	private static boolean mine(Chest c) { return c.server.equals(server()) && c.owner.equals(owner()); }

	/** Chests of a base the alt has not opened yet: one that does not open for the alt is forgotten again (Base). */
	private static final Set<BlockPos> trial = new HashSet<>();

	/** A chest in a base: tried as one of the owner's, if not known yet. True when it is new. */
	static boolean tryBase(Level level, BlockPos pos, String player) {
		load();
		String server = server(), dim = Work.dimOf(level), who = player.toLowerCase(java.util.Locale.ROOT);
		BlockPos other = otherHalf(level, pos);
		for (Chest c : chests) if (c.server.equals(server) && c.owner.equals(who) && c.dim.equals(dim) && (c.pos.equals(pos) || c.pos.equals(other))) return false;
		trial.add(pos.immutable());
		learn(level, pos, "base", player);
		return true;
	}

	// ---------- for Chores: what the chests hold ----------

	/** The buddy's known chests in this dimension that held this item at the last look, nearest first (Obtain's take). */
	static List<BlockPos> chestsWith(String id, String dim, BlockPos from) {
		load();
		List<Chest> l = new ArrayList<>();
		for (Chest c : chests) if (mine(c) && dim.equals(c.dim) && c.items.getOrDefault(id, 0) > 0) l.add(c);
		l.sort(java.util.Comparator.comparingDouble(c -> c.pos.distSqr(from)));
		List<BlockPos> out = new ArrayList<>();
		for (Chest c : l) out.add(c.pos);
		return out;
	}

	/** Obtain took this many out of the chest there: its record follows, until the next look inside. */
	static void took(BlockPos pos, String id, int n) {
		load();
		for (Chest c : chests) if (mine(c) && c.pos.equals(pos)) { c.items.merge(id, -n, Integer::sum); if (c.items.getOrDefault(id, 0) <= 0) c.items.remove(id); }
	}

	/** How many of this item the owner's known chests hold, on this server. */
	static int inChests(String id) {
		load();
		int n = 0;
		for (Chest c : chests) if (mine(c)) n += c.items.getOrDefault(id, 0);
		return n;
	}

	/** Room in the owner's known chests: {the least free slots of one that holds this item (99 if none), all free slots, chests looked into}. */
	static int[] room(String id) {
		load();
		int least = 99, total = 0, seen = 0;
		for (Chest c : chests) {
			if (!mine(c) || c.free < 0) continue;
			seen++;
			total += c.free;
			if (c.items.containsKey(id)) least = Math.min(least, c.free);
		}
		return new int[] { least, total, seen };
	}

	/** A tool of this kind lies in one of the owner's chests (shears by their own name). */
	static boolean chestHasTool(String kind) {
		load();
		for (Chest c : chests) if (mine(c)) for (String id : c.items.keySet()) if ((id.equals(kind) || id.endsWith("_" + kind)) && allowed(id)) return true;
		return false;
	}

	/** A tool of this kind with more than a tenth of its durability left lies in one of the owner's chests. */
	static boolean chestHasFreshTool(String kind) {
		load();
		for (Chest c : chests) if (mine(c)) for (String id : c.fresh) if ((id.equals(kind) || id.endsWith("_" + kind)) && allowed(id)) return true;
		return false;
	}

	/**
	 * Renewing a tool of this kind is on: {@code baseRenew}, and every one of it carried is {@link Work#lowWear} (at
	 * least one is carried).
	 */
	static boolean renewing(LocalPlayer p, String kind) {
		if (!AfkConfig.get().baseRenew) return false;
		TagKey<Item> t = tag(kind);
		boolean any = false;
		for (int i = 0; i < 36; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (!st.is(t)) continue;
			if (!Work.lowWear(st)) return false;
			any = true;
		}
		return any;
	}

	/**
	 * Renewing the armour of this slot is on: {@code baseRenew}, what is worn there is {@link Work#lowWear}, and nothing
	 * carried for it is as good and fresh.
	 */
	static boolean renewingArmour(LocalPlayer p, EquipmentSlot slot) {
		if (!AfkConfig.get().baseRenew) return false;
		ItemStack on = p.getItemBySlot(slot);
		if (on.isEmpty() || !Work.lowWear(on)) return false;
		double a = armour(on, slot);
		for (int i = 0; i < 36; i++) {
			ItemStack st = p.getInventory().getItem(i);
			Equippable e = st.get(DataComponents.EQUIPPABLE);
			if (e != null && e.slot() == slot && armour(st, slot) >= a && !Work.lowWear(st)) return false;
		}
		return true;
	}

	/** A piece for this slot in one of the owner's chests that may be taken, at least this good and fresh. */
	static boolean chestHasFreshArmour(EquipmentSlot slot, double atLeast) {
		load();
		for (Chest c : chests) if (mine(c)) for (String id : c.fresh) {
			if (!allowed(id)) continue;
			ItemStack st = stackOf(id);
			Equippable e = st.get(DataComponents.EQUIPPABLE);
			if (e != null && e.slot() == slot && armour(st, slot) >= atLeast) return true;
		}
		return false;
	}

	/**
	 * Gear the buddy may take from a chest unasked: food always, armour and tools of iron at most. Diamond and netherite
	 * stay where they are; what the buddy is
	 * given or picks up is kept and worn.
	 */
	static boolean allowed(String id) { return !id.startsWith("diamond_") && !id.startsWith("netherite_"); }

	private static boolean allowed(ItemStack st) { return allowed(AfkHelperClient.itemId(st)); }

	/** A piece for this slot in one of the owner's chests that may be taken, at least this good. */
	static boolean chestHasArmour(EquipmentSlot slot, double atLeast) {
		load();
		for (Chest c : chests) if (mine(c)) for (String id : c.items.keySet()) {
			if (!allowed(id)) continue;
			ItemStack st = stackOf(id);
			Equippable e = st.get(DataComponents.EQUIPPABLE);
			if (e != null && e.slot() == slot && armour(st, slot) >= atLeast) return true;
		}
		return false;
	}

	/**
	 * A piece of gold where piglins would otherwise attack: on an order in the nether, or on its way there (Obtain), one
	 * golden piece is worth more than the iron it replaces, the cheapest slot for it the helmet's, where gold protects as
	 * much as iron. Only while no other slot has gold on.
	 */
	private static double gold(LocalPlayer p, ItemStack st, EquipmentSlot slot) {
		if (st.isEmpty() || !Obtain.wantsGold(p) || !AfkHelperClient.itemId(st).startsWith("golden_")) return 0;
		for (EquipmentSlot o : new EquipmentSlot[] { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET })
			if (o != slot && AfkHelperClient.itemId(p.getItemBySlot(o)).startsWith("golden_")) return 0;
		return 6;
	}

	/** The best worn or carried for that slot. */
	static double bestArmourFor(LocalPlayer p, EquipmentSlot slot) { return bestArmour(p, slot); }

	/** One of this kind is carried that is not about to break. */
	static boolean hasUsable(LocalPlayer p, String kind) { return usable(p, tag(kind)); }

	/** A chest the player taps (Tap): one of the player's. */
	static void learnTapped(Level level, BlockPos pos, String player) { learn(level, pos, "tapped", player); }

	private static void learn(Level level, BlockPos pos, String how, String player) {
		load();
		String server = server(), dim = Work.dimOf(level), who = player.toLowerCase(java.util.Locale.ROOT);
		BlockPos other = otherHalf(level, pos);
		for (Chest c : chests) if (c.server.equals(server) && c.owner.equals(who) && c.dim.equals(dim) && (c.pos.equals(pos) || c.pos.equals(other))) {
			// the player opened a chest that is known: what lies in it may have changed, so a look inside soon (chests that were
			// moved or sorted anew are learnt again)
			if (how.equals("seen") && c.free >= 0) { c.free = -1; save(); }
			return;
		}
		Chest c = new Chest();
		c.server = server;
		c.owner = who;
		c.dim = dim;
		c.pos = pos.immutable();
		chests.add(c);
		save();
		Exchange.emit("helper", "action", "chest learnt", "how", how, "for", player, "x", pos.getX(), "y", pos.getY(), "z", pos.getZ(), "chests", count());
	}

	private static BlockPos otherHalf(Level level, BlockPos pos) {
		BlockState st = level.getBlockState(pos);
		if (st.getBlock() instanceof ChestBlock && st.getValue(ChestBlock.TYPE) != ChestType.SINGLE) return pos.relative(ChestBlock.getConnectedDirection(st));
		return null;
	}

	// ---------- what is known ----------

	static int count() {
		load();
		int n = 0;
		for (Chest c : chests) if (mine(c)) n++;
		return n;
	}

	/** A chest of the owner's on this server with an item whose id matches. */
	static boolean has(Predicate<String> id) {
		load();
		for (Chest c : chests) if (mine(c)) for (String i : c.items.keySet()) if (id.test(i)) return true;
		return false;
	}

	/** One of the owner's chests within this distance, in this world. */
	static boolean nearChest(Level level, BlockPos pos, double r) {
		load();
		String dim = Work.dimOf(level);
		for (Chest c : chests) if (mine(c) && c.dim.equals(dim) && c.pos.distSqr(pos) <= r * r) return true;
		return false;
	}

	static boolean hasFood() { return has(id -> AfkHelperClient.eatable(stackOf(id))); }

	/** A chest of the owner's that holds something carried and has room, or has room at all. */
	static boolean hasRoom(LocalPlayer p) {
		load();
		for (Chest c : chests) if (mine(c) && c.free > 0 && !c.full) return true;
		return false;
	}

	private static ItemStack stackOf(String id) {
		Item it = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(id));
		return it == null ? ItemStack.EMPTY : new ItemStack(it);
	}

	// ---------- the commands ----------

	/** {@code {"cmd":"chests"}}: how many and which; "forget" forgets them; "learn":[x,y,z] learns one by hand. */
	static void command(JsonObject o) {
		load();
		LocalPlayer p = AfkHelperClient.mc().player;
		if (o.has("forget") && o.get("forget").getAsBoolean()) {
			chests.removeIf(Stash::mine);
			save();
			stop("forgotten");
		}
		if (o.has("learn") && p != null) {
			if (Follow.target() == null) { Exchange.emit("error", "msg", Needs.named("chests: {me} follows nobody, so the chest has no owner")); return; }
			JsonArray a = o.getAsJsonArray("learn");
			BlockPos pos = new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
			if (p.level().getBlockEntity(pos) instanceof Container) learn(p.level(), pos, "asked", Follow.target());
			else { Exchange.emit("error", "msg", "no chest there (or it is not loaded)"); return; }
		}
		List<String> list = new ArrayList<>();
		for (Chest c : chests) {
			if (!mine(c)) continue;
			list.add(c.dim.replace("minecraft:", "") + " " + c.pos.toShortString() + (c.free < 0 ? " (not looked into yet)" : " (" + c.items.size() + " kinds, " + c.free + " free)"));
		}
		Exchange.emit("chests", "for", Follow.target() == null ? "" : Follow.target(), "count", list.size(), "list", String.join("; ", list),
			"on", on, "busy", why == null ? "" : why);
	}

	// ---------- every tick ----------

	/** When the alt has nothing else to do: a visit to one of the owner's chests, if there is a reason for one. */
	static boolean tick(Minecraft mc, LocalPlayer p, Player t) {
		long now = System.currentTimeMillis();
		skip.values().removeIf(until -> until < now);
		if (!on || t == null) { if (step != IDLE) stop("off"); return false; }
		if (step == IDLE) {
			if (now < nextPlan) return false;
			nextPlan = now + 3000;
			if (now - lastRun < (forcing ? 2000 : 20_000)) return false;
			return plan(p, t, now);
		}
		Level level = p.level();
		if (t.distanceTo(p) > 28) { stop("you leave"); return false; }
		// another dimension, or a chunk not loaded (after a teleport, a /home): no block entity to read, and no sign the
		// chest is gone; the visit ends and the chest is kept (the review of 22 Oct 2026: it was forgotten)
		if (!visit.dim.equals(Work.dimOf(level)) || !level.isLoaded(visit.pos)) { stop("chest not loaded"); return false; }
		if (!(level.getBlockEntity(visit.pos) instanceof Container)) {
			chests.remove(visit);
			save();
			stop("chest gone");
			return false;
		}
		paused(now);
		Vec3 eye = p.getEyePosition();
		double d = eye.distanceTo(Vec3.atCenterOf(visit.pos));
		if (d < bestDist - 0.5) { bestDist = d; progressAt = now; }
		if (Follow.cellsWalked() != cellsSeen) { cellsSeen = Follow.cellsWalked(); progressAt = now; }
		if (stand != null) {
			double ds = p.position().distanceTo(Vec3.atBottomCenterOf(stand));
			if (!stand.equals(bestStandFor)) { bestStandFor = stand; bestStand = ds; }
			else if (ds < bestStand - 0.5) { bestStand = ds; progressAt = now; }
		}
		if (now - progressAt > STUCK) {
			Exchange.emit("helper", "action", "chest stuck", "step", stepName(), "distance", Math.round(d * 10) / 10.0, "sees", Work.sight(mc),
				"x", visit.pos.getX(), "y", visit.pos.getY(), "z", visit.pos.getZ());
			skip.put(visit.pos, now + 300_000);
			stop("stuck");
			return false;
		}
		Vec3 point = aim(level, p, eye);
		switch (step) {
			case WALK -> {
				if (point != null) { Follow.stand(); toStep(OPEN, now); clicks = 0; clickedAt = -1; return true; }
				if (stand == null) {
					stand = Work.standCell(level, p, visit.pos, t, f -> true);
					// a double chest opens from either half: the other one when this one has no room in front of it
					BlockPos other = otherHalf(level, visit.pos);
					if (stand == null && other != null) stand = Work.standCell(level, p, other, t, f -> true);
					if (stand == null) { skip.put(visit.pos, now + 300_000); stop("no spot to stand"); return false; }
				}
				if (!Work.approach(mc, p, stand, Vec3.atBottomCenterOf(stand), now)) {
					stand = Work.standCell(level, p, visit.pos, t, f -> true);
					if (stand == null) { skip.put(visit.pos, now + 300_000); stop("unreachable"); return false; }
				}
				return true;
			}
			case OPEN -> {
				// A click sent and the screen not here yet: wait for it. Back to WALK here, the chest screen came in after
				// all, Follow took it for a stranger's menu and stood still behind it for 27 minutes (19 Oct 2026, 20:10).
				if (point == null && clickedAt >= 0 && Follow.ticks() - clickedAt <= 30) return true;
				if (point == null) { toStep(WALK, now); stand = null; return true; }
				Follow.stand();
				Follow.aimExact(p, point);
				if (clickedAt >= 0 && Follow.ticks() - clickedAt > 30) {
					clickedAt = -1;
					if (++clicks >= 3) {
						skip.put(visit.pos, now + 600_000);
						BlockPos up = visit.pos.above();
						Exchange.emit("helper", "action", "chest refuses", "x", visit.pos.getX(), "y", visit.pos.getY(), "z", visit.pos.getZ(),
							"above", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(up).getBlock()).getPath(), "sees", Work.sight(mc));
						// a chest of the base the buddy only tried: not one of both of them, so not one of the base
						if (trial.remove(visit.pos)) { chests.remove(visit); save(); Exchange.emit("base", "action", "chest without access", "x", visit.pos.getX(), "y", visit.pos.getY(), "z", visit.pos.getZ()); }
						stop("does not open");
						return false;
					}
				}
				if (clickedAt < 0) clickAt = visit.pos;
				return true;
			}
			default -> { return true; }
		}
	}

	private static Vec3 aim(Level level, LocalPlayer p, Vec3 eye) {
		Vec3 pt = Work.aimPoint(level, p, eye, visit.pos);
		BlockPos other = otherHalf(level, visit.pos);
		if (pt == null && other != null) pt = Work.aimPoint(level, p, eye, other);
		// the reach the spot was chosen with (Work.standCell): with 4.0 here the buddy could stand on the spot, the chest 4.1 off,
		// and never click (22 Oct 2026)
		return pt != null && eye.distanceTo(pt) <= Work.REACH ? pt : null;
	}

	/**
	 * Time in which the visit did not get its tick (a fight, a monster, the bed, a job above it in the chain) is no time
	 * stuck: the clock of {@link #STUCK} moves on by the gap.
	 */
	private static void paused(long now) {
		if (lastTickAt > 0 && now - lastTickAt > 1000) progressAt += now - lastTickAt;
		lastTickAt = now;
	}

	private static String stepName() {
		return switch (step) { case WALK -> "walking"; case OPEN -> "clicking open"; case MENU -> "in the chest"; default -> "nothing"; };
	}

	/** To another step, told once per change, so that a visit can be followed in the events. */
	private static void toStep(int s, long now) {
		if (s == step) return;
		step = s;
		stepSince = now;
		Exchange.emit("helper", "action", "chest step", "step", stepName(), "x", visit.pos.getX(), "y", visit.pos.getY(), "z", visit.pos.getZ());
	}

	/** Start of the tick: the click on the chest, if the crosshair is on it. */
	static void startTick(Minecraft mc) {
		BlockPos want = clickAt;
		clickAt = null;
		LocalPlayer p = mc.player;
		if (want == null || p == null || mc.gameMode == null || mc.level == null || p.isShiftKeyDown() || step != OPEN) return;
		if (!(mc.hitResult instanceof BlockHitResult bh) || bh.getType() != HitResult.Type.BLOCK) return;
		BlockPos hit = bh.getBlockPos();
		if (!hit.equals(want) && !hit.equals(otherHalf(mc.level, want))) return;
		InteractionResult r = AfkHelperClient.useOn(p, InteractionHand.MAIN_HAND, bh);
		if (r.consumesAction()) Game.swingUse(p, InteractionHand.MAIN_HAND);
		clickedAt = Follow.ticks();
		progressAt = System.currentTimeMillis();
		Exchange.emit("helper", "action", "chest step", "step", "click sent", "x", want.getX(), "y", want.getY(), "z", want.getZ());
	}

	/** The chest is open: look what lies in it, take what is lacking, put away what belongs there, close it. */
	/** The land plugin refused the chest being opened (the profile's geenKistToegang): a trial chest is forgotten. */
	static void chat(String line) {
		if (visit == null || step != OPEN || !Profiles.has("noChestAccess", line)) return;
		if (trial.remove(visit.pos)) {
			chests.remove(visit);
			save();
			Exchange.emit("base", "action", "chest without access", "x", visit.pos.getX(), "y", visit.pos.getY(), "z", visit.pos.getZ());
		}
		skip.put(visit.pos, System.currentTimeMillis() + 600_000);
		stop("no access");
	}

	static boolean menuTick(Minecraft mc, LocalPlayer p) {
		long now = System.currentTimeMillis();
		AbstractContainerMenu m = p.containerMenu;
		if (step == OPEN) {
			if (m == p.inventoryMenu) return true;
			toStep(MENU, now);
			progressAt = now;
			read = false;
			depositDone = false;
			moved = took = 0;
			taken.clear();
			nextClick = now + ThreadLocalRandom.current().nextLong(250, 500);
			return true;
		}
		if (m == p.inventoryMenu) { finish("closed"); return false; }
		paused(now);
		int n = m.slots.size() - 36;
		// no limit on the time in the chest: every stack moved is a step forward, so a whole chest fills or empties
		if (n <= 0 || now - progressAt > STUCK) { p.closeContainer(); finish(n <= 0 ? "no chest" : "took too long"); return false; }
		if (now < nextClick) return true;
		if (!read) { readInto(visit, m, n, true); read = true; progressAt = now; nextClick = now + 250; return true; }
		int s = collects(p.level(), visit.pos) ? -1 : takeSlot(p, m, n);
		if (s >= 0) {
			Game.quickMove(mc.gameMode, m.containerId, s, p);
			took++;
			progressAt = now;
			nextClick = now + ThreadLocalRandom.current().nextLong(150, 350);
			return true;
		}
		s = depositDone ? -1 : depositSlot(p, m, n);
		if (s >= 0) {
			ItemStack before = m.slots.get(s).getItem().copy();
			Game.quickMove(mc.gameMode, m.containerId, s, p);
			if (ItemStack.matches(before, m.slots.get(s).getItem())) { depositDone = true; visit.full = true; }
			else moved++;
			progressAt = now;
			nextClick = now + ThreadLocalRandom.current().nextLong(150, 350);
			return true;
		}
		readInto(visit, m, n, false);
		p.closeContainer();
		finish("done");
		return false;
	}

	/**
	 * What the open chest holds. {@code first}: the look on opening it, before anything is taken or put; what lay in it at
	 * the last look and is gone now has been moved (or used up), and becomes {@link #missing}. The look on closing it
	 * would count what the buddy took.
	 */
	private static void readInto(Chest c, AbstractContainerMenu m, int n, boolean first) {
		Set<String> before = first && c.seen > 0 ? new HashSet<>(c.items.keySet()) : Set.of();
		c.items.clear();
		c.fresh.clear();
		int free = 0;
		for (int i = 0; i < n; i++) {
			ItemStack st = m.slots.get(i).getItem();
			if (st.isEmpty()) free++;
			// a tool or a piece of armour about to break counts as nothing: none such was taken from a chest while one lay in
			// it was what the chest had to offer, so the buddy walked to it every few seconds and made no new one (the review of
			// 22 Oct 2026)
			else if (worn(st)) continue;
			else {
				c.items.merge(AfkHelperClient.itemId(st), st.getCount(), Integer::sum);
				if (st.isDamageableItem() && !Work.lowWear(st)) c.fresh.add(AfkHelperClient.itemId(st));
			}
		}
		long now = System.currentTimeMillis();
		List<String> gone = new ArrayList<>();
		for (String id : before) if (!c.items.containsKey(id) && !c.strays.contains(id)) { missing.putIfAbsent(id, now); gone.add(id); }
		if (!gone.isEmpty()) Exchange.emit("helper", "action", "gone from chest", "things", String.join(", ", gone),
			"x", c.pos.getX(), "y", c.pos.getY(), "z", c.pos.getZ());
		for (String id : c.items.keySet()) missing.remove(id);
		for (String id : c.items.keySet()) if (!c.strays.contains(id)) c.had.add(id);
		c.size = n;
		c.free = free;
		c.full = free == 0;
		c.seen = System.currentTimeMillis();
		save();
	}

	private static void finish(String how) {
		if (visit != null) Exchange.emit("helper", "action", "chest", "reason", why, "how", how, "x", visit.pos.getX(), "y", visit.pos.getY(), "z", visit.pos.getZ(),
			"stored", moved, "taken", took, "free", visit.free);
		// a tool taken solves the need of the job: without this it stayed for its ten minutes
		if (took > 0) for (String k : taken) Needs.solved(k);
		step = IDLE;
		visit = null;
		why = null;
		stand = null;
		clickAt = null;
		lastRun = System.currentTimeMillis() - 16_000;   // the next chest of the same round in four seconds, if there is one
	}

	static void stop(String reason) {
		if (step == IDLE) return;
		LocalPlayer p = AfkHelperClient.mc().player;
		if (p != null && step == MENU && p.containerMenu != p.inventoryMenu) p.closeContainer();
		if (visit != null) {
			Exchange.emit("helper", "action", "chest stopped", "reason", reason, "x", visit.pos.getX(), "y", visit.pos.getY(), "z", visit.pos.getZ());
			// not reached: tried again later each time it happens (Failures: ten minutes, thirty, ninety, at most six hours)
			if (reason.startsWith("unreachable") || reason.equals("no spot to stand") || reason.equals("stuck"))
				Failures.failed("chest " + visit.pos.toShortString(), reason, visit.pos.getX(), visit.pos.getY(), visit.pos.getZ());
		} else Exchange.emit("helper", "action", "chest stopped", "reason", reason);
		step = IDLE;
		visit = null;
		why = null;
		stand = null;
		clickAt = null;
		lastRun = System.currentTimeMillis();
	}

	/**
	 * A chest the game will not open: a solid block on top of it, or a cat sitting on it (vanilla's
	 * ChestBlock.isChestBlockedAt). A barrel and a shulker box have their own rules and are never taken for blocked here.
	 */
	/** A chest (or its other half) with a hopper above it: what a farm collects. */
	static boolean collects(Level level, BlockPos pos) {
		if (level.getBlockEntity(pos.above()) instanceof net.minecraft.world.level.block.entity.HopperBlockEntity) return true;
		BlockPos other = otherHalf(level, pos);
		return other != null && level.getBlockEntity(other.above()) instanceof net.minecraft.world.level.block.entity.HopperBlockEntity;
	}

	/** A chest (or its other half) with a hopper under it. */
	static boolean feeds(Level level, BlockPos pos) {
		if (level.getBlockEntity(pos.below()) instanceof net.minecraft.world.level.block.entity.HopperBlockEntity) return true;
		BlockPos other = otherHalf(level, pos);
		return other != null && level.getBlockEntity(other.below()) instanceof net.minecraft.world.level.block.entity.HopperBlockEntity;
	}

	static boolean blocked(Level level, BlockPos pos) {
		if (!(level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.ChestBlock)) return false;
		BlockPos up = pos.above();
		if (level.getBlockState(up).isRedstoneConductor(level, up)) return true;
		return !level.getEntitiesOfClass(net.minecraft.world.entity.animal.feline.Cat.class, new net.minecraft.world.phys.AABB(up)).isEmpty();
	}

	// ---------- what to do ----------

	/** Is there a reason to go to a chest, and which one: what is lacking first, then what is carried, then a look. */
	private static boolean plan(LocalPlayer p, Player t, long now) {
		load();
		Level level = p.level();
		String dim = Work.dimOf(level);
		// a known chest of the owner's that is gone from where it stood (moved, broken): forgotten, its contents with it
		boolean gone = false;
		for (var it = chests.iterator(); it.hasNext(); ) {
			Chest c = it.next();
			if (!mine(c) || !c.dim.equals(dim) || !level.isLoaded(c.pos) || level.getBlockEntity(c.pos) instanceof Container) continue;
			it.remove();
			gone = true;
			Exchange.emit("helper", "action", "chest gone", "x", c.pos.getX(), "y", c.pos.getY(), "z", c.pos.getZ());
		}
		// both halves of one double chest known as two chests (placed one at a time, each learnt while single): one
		// is kept, the half looked into, with what both had (21 Oct 2026: the back halves of the cellar's double
		// chests stand against the wall, "no spot to stand" for each while the front half opens the same chest)
		for (var it = chests.iterator(); it.hasNext(); ) {
			Chest c = it.next();
			if (!mine(c) || !c.dim.equals(dim) || !level.isLoaded(c.pos)) continue;
			BlockPos other = otherHalf(level, c.pos);
			if (other == null) continue;
			Chest twin = null;
			for (Chest d : chests) if (d != c && mine(d) && d.dim.equals(dim) && d.pos.equals(other)) { twin = d; break; }
			if (twin == null || (c.free >= 0 && twin.free < 0)) continue;   // keep the one that was looked into
			twin.had.addAll(c.had);
			twin.strays.addAll(c.strays);
			if (twin.free < 0 && c.free >= 0) { twin.items.putAll(c.items); twin.free = c.free; twin.size = c.size; twin.seen = c.seen; }
			it.remove();
			gone = true;
		}
		if (gone) save();
		List<Chest> near = new ArrayList<>();
		var home = t == p ? Tap.baseAt(dim, p.position(), Base.RADIUS) : null;
		for (Chest c : chests) {
			if (!mine(c) || !c.dim.equals(dim) || skip.containsKey(c.pos)) continue;
			if (blocked(level, c.pos)) {
				// the game itself keeps such a chest shut: no walk and three clicks for nothing every few seconds
				skip.put(c.pos, now + 300_000);
				Exchange.emit("helper", "action", "chest blocked", "x", c.pos.getX(), "y", c.pos.getY(), "z", c.pos.getZ(),
					"above", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(c.pos.above()).getBlock()).getPath());
				continue;
			}
			// a chest that empties into a hopper (on a composter, a furnace, a sorter) is no store: what goes in is gone
			// (the buddy put wheat in the chest over a composter while another chest was set up for wheat; it had some wheat, so
			// it was the nearest chest that "holds some")
			if (feeds(level, c.pos)) continue;
			if (Failures.blocked("chest " + c.pos.toShortString())) continue;
			Vec3 at = Vec3.atCenterOf(c.pos);
			// keeping the base (the buddy is the anchor): every chest of the base, not only those near the buddy
			if (home != null ? at.distanceTo(Vec3.atCenterOf(home.bed())) <= Base.RADIUS : at.distanceTo(p.position()) <= 20 && at.distanceTo(t.position()) <= 24) near.add(c);
		}
		if (near.isEmpty()) return false;
		near.sort(Comparator.comparingDouble(c -> Vec3.atCenterOf(c.pos).distanceToSqr(p.position())));
		for (Chest c : near) {
			// a chest a hopper fills (under a composter: its bone meal) is the farm's: only added to, never taken from
			if (collects(level, c.pos)) continue;
			String w = lacks(p, c);
			if (w != null) return go(c, w, false, now);
		}
		boolean[] keep = keep(p);
		int storable = 0;
		for (int i = 0; i < 36; i++) if (!keep[i] && !p.getInventory().getItem(i).isEmpty()) storable++;
		int free = Work.freeSlots(p);
		// Storing as a copper golem does, with a memory: a chest that holds it; else first a look in every chest that may
		// hold it; and only then the
		// rest in an empty chest. The buddy had put "the rest" in the emptiest of the 7 chests looked into, with 39 of the
		// base's 46 never opened. No longer: the chest where it once lay, or the roomiest when no chest is empty.
		if ((forcing && storable > 0) || storable >= 4 || (storable > 0 && free <= 5)) {
			String w = forcing ? "carrying" : "storing";
			for (Chest c : near) if (c.free >= 0 && !c.full && holdsSome(p, keep, c)) return go(c, w, false, now);
			Chest look = toLookIn(near, p, keep);
			if (look != null) return go(look, w + (look.seen > 0 ? ", look again" : ", look into a new chest"), false, now);
			if (forcing || free <= 4) {
				// an empty chest, the nearest, so that the rest does not end up among other things; none: the rest is kept, and
				// a full inventory is told (Needs "vol")
				for (Chest c : near) if (c.free > 0 && c.items.isEmpty() && !collects(level, c.pos)) return go(c, w + ", de rest", true, now);
			}
		}
		// no look every hour any more: a chest gets one when what is in it is not known (never opened, or opened by
		// the player since), or when something being stored is gone from where it lay (toLookIn)
		for (Chest c : near) if (c.free < 0) return go(c, "looking what is in it", false, now);
		lastRun = now - 10_000;   // nothing to do: look again in ten seconds
		return false;
	}

	/**
	 * Before what is carried goes where it never lay: the chest to look into first, or null when all have been had. A
	 * chest whose contents are not known (never opened, or opened by the player since the buddy's look); and when a thing
	 * carried is {@link #missing}, every chest looked into before it went. Known chests first, then new ones, each the
	 * nearest first ({@code near} is sorted by distance).
	 */
	private static Chest toLookIn(List<Chest> near, LocalPlayer p, boolean[] keep) {
		long since = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (keep[i] || st.isEmpty()) continue;
			since = Math.max(since, missing.getOrDefault(AfkHelperClient.itemId(st), 0L));
		}
		Chest fresh = null;
		for (Chest c : near) {
			if (c.free >= 0 && c.seen >= since) continue;
			if (c.seen > 0) return c;
			if (fresh == null) fresh = c;
		}
		return fresh;
	}

	private static boolean go(Chest c, String reason, boolean any, long now) {
		visit = c;
		why = reason;
		fallback = any;
		step = WALK;
		stepSince = now;
		progressAt = lastTickAt = now;
		bestDist = Double.MAX_VALUE;
		cellsSeen = Follow.cellsWalked();
		bestStandFor = null;
		stand = null;
		Exchange.emit("helper", "action", "to chest", "reason", reason, "x", c.pos.getX(), "y", c.pos.getY(), "z", c.pos.getZ());
		return true;
	}

	/** What this chest has that is lacking, as far as the last look inside it says. */
	private static String lacks(LocalPlayer p, Chest c) {
		if (AfkHelperClient.countFood(p, 0, 36) < 8) for (String id : c.items.keySet()) if (AfkHelperClient.eatable(stackOf(id))) return "food";
		for (String kind : new String[] { "pickaxe", "axe", "shovel" }) {
			if (!usable(p, tag(kind))) for (String id : c.items.keySet()) if (id.endsWith("_" + kind) && allowed(id)) return kind;
			if (renewing(p, kind)) for (String id : c.fresh) if (id.endsWith("_" + kind) && allowed(id)) return kind;
		}
		if (Guard.on() && !armed(p)) for (String id : c.items.keySet()) if (id.endsWith("_sword") && allowed(id)) return "weapon";
		// for the fight (24 Oct 2026): a shield while guarding, golden apples, and better food than beetroot
		if (Guard.on() && !Chores.hasShield(p) && c.items.containsKey("shield")) return "shield";
		if (Chores.count(p, "golden_apple") < 2 && c.items.containsKey("golden_apple")) return "golden apples";
		if (Guard.on() && !Chores.hasBow(p) && c.items.containsKey("bow")) return "bow";
		if (Guard.on() && Chores.hasBow(p) && arrowCount(p) < 16 && (c.items.containsKey("arrow") || c.items.containsKey("spectral_arrow") || c.items.containsKey("tipped_arrow"))) return "arrows";
		if (poorFood(p)) for (String id : c.items.keySet()) if (nutrition(stackOf(id)) >= GOOD_FOOD) return "better food";
		// what the buddy's furnaces smelt or burn (Ovens)
		for (String id : c.items.keySet()) if (Ovens.wants(p, stackOf(id))) return "for the furnaces";
		// torches while digging along (Torches)
		if (Torches.wants(p) && c.items.containsKey("torch")) return "torches";
		// what a chore of the base needs (Chores)
		for (String id : c.items.keySet()) if (Chores.wants(p, stackOf(id))) return "for a chore";
		for (String id : c.items.keySet()) {
			ItemStack st = stackOf(id);
			Equippable e = st.get(DataComponents.EQUIPPABLE);
			if (e != null && e.slot().getType() == EquipmentSlot.Type.HUMANOID_ARMOR && allowed(id) && armour(st, e.slot()) > bestArmour(p, e.slot())) return "harness";
			if (e != null && e.slot().getType() == EquipmentSlot.Type.HUMANOID_ARMOR && allowed(id) && c.fresh.contains(id) && renewingArmour(p, e.slot())
				&& armour(st, e.slot()) >= armour(p.getItemBySlot(e.slot()), e.slot())) return "harness";
		}
		return null;
	}

	/** Something carried that lay in this chest before, and that no chest of the owner's holds now. */

	private static boolean holdsSome(LocalPlayer p, boolean[] keep, Chest c) {
		for (int i = 0; i < 36; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (!keep[i] && !st.isEmpty() && c.items.containsKey(AfkHelperClient.itemId(st))) return true;
		}
		return false;
	}

	/** In the open chest: the slot of something lacking, one of each kind a visit. */
	private static int takeSlot(LocalPlayer p, AbstractContainerMenu m, int n) {
		int best = -1;
		String kind = null;
		double score = 0;
		boolean hungry = AfkHelperClient.countFood(p, 0, 36) < 8 && !taken.contains("food");
		boolean[] want = new boolean[3];
		String[] kinds = { "pickaxe", "axe", "shovel" };
		boolean[] renew = new boolean[3];
		for (int k = 0; k < 3; k++) { renew[k] = renewing(p, kinds[k]); want[k] = !taken.contains(kinds[k]) && (!usable(p, tag(kinds[k])) || renew[k]); }
		boolean weapon = Guard.on() && !armed(p) && !taken.contains("weapon");
		boolean shield = Guard.on() && !Chores.hasShield(p) && !taken.contains("shield");
		boolean apples = Chores.count(p, "golden_apple") < 2 && !taken.contains("golden apples");
		boolean better = poorFood(p) && !taken.contains("better food");
		boolean bowWanted = Guard.on() && !Chores.hasBow(p) && !taken.contains("bow");
		boolean arrowsWanted = Guard.on() && Chores.hasBow(p) && arrowCount(p) < 16 && !taken.contains("arrows");
		for (int i = 0; i < n; i++) {
			ItemStack st = m.slots.get(i).getItem();
			if (st.isEmpty()) continue;
			// unenchanted first: the player's own good gear stays in the chest as long as there is something else
			double plain = st.isEnchanted() ? 0 : 1000;
			if (hungry && AfkHelperClient.eatable(st)) {
				var f = st.get(DataComponents.FOOD);
				double s = 2000 + (f == null ? 0 : f.nutrition()) * 10 + st.getCount() * 0.01;
				if (s > score) { score = s; best = i; kind = "food"; }
			}
			for (int k = 0; k < 3; k++) {
				// one about to break is no use (usable() would want another at once), one nearly worn out none for renewing
				if (!want[k] || !st.is(tag(kinds[k])) || worn(st) || (renew[k] && Work.lowWear(st)) || !allowed(st)) continue;
				double s = plain + st.getDestroySpeed(sample(kinds[k]));
				if (s > score) { score = s; best = i; kind = kinds[k]; }
			}
			if (st.is(Items.TORCH) && !taken.contains("torches") && Torches.wants(p)) {
				double s = 1400 + st.getCount() * 0.01;
				if (s > score) { score = s; best = i; kind = "torches"; }
			}
			String chore = "chore_" + AfkHelperClient.itemId(st);
			if (!taken.contains(chore) && Chores.wants(p, st)) {
				double s = 1300 + st.getCount() * 0.01;
				if (s > score) { score = s; best = i; kind = chore; }
			}
			String oven = "oven_" + AfkHelperClient.itemId(st);
			if (!taken.contains(oven) && Ovens.wants(p, st)) {
				double s = 1500 + st.getCount() * 0.01;
				if (s > score) { score = s; best = i; kind = oven; }
			}
			if (shield && st.is(Items.SHIELD) && !Work.nearlyBroken(st) && 1900 > score) { score = 1900; best = i; kind = "shield"; }
			if (bowWanted && st.is(Items.BOW) && !Work.nearlyBroken(st) && 1890 + (st.isEnchanted() ? 1 : 0) > score) { score = 1890 + (st.isEnchanted() ? 1 : 0); best = i; kind = "bow"; }
			if (arrowsWanted && st.is(ItemTags.ARROWS) && 1880 + st.getCount() * 0.01 > score) { score = 1880 + st.getCount() * 0.01; best = i; kind = "arrows"; }
			if (apples && st.is(Items.GOLDEN_APPLE) && 1950 + st.getCount() * 0.01 > score) { score = 1950 + st.getCount() * 0.01; best = i; kind = "golden apples"; }
			if (better && AfkHelperClient.eatable(st) && nutrition(st) >= GOOD_FOOD) {
				double s = 1800 + nutrition(st) * 10 + st.getCount() * 0.01;
				if (s > score) { score = s; best = i; kind = "better food"; }
			}
			if (weapon && st.is(ItemTags.SWORDS) && !Work.nearlyBroken(st) && allowed(st)) {
				double s = plain + Guard.dps(st);
				if (s > score) { score = s; best = i; kind = "weapon"; }
			}
			Equippable e = st.get(DataComponents.EQUIPPABLE);
			if (e != null && e.slot().getType() == EquipmentSlot.Type.HUMANOID_ARMOR && allowed(st) && !taken.contains("armor_" + e.slot().getName())) {
				double a = armour(st, e.slot());
				boolean renewal = !Work.lowWear(st) && renewingArmour(p, e.slot()) && a >= armour(p.getItemBySlot(e.slot()), e.slot());
				if (a > bestArmour(p, e.slot()) || renewal) {
					double s = plain + a;
					if (s > score) { score = s; best = i; kind = "armor_" + e.slot().getName(); }
				}
			}
		}
		if (best >= 0) taken.add(kind);
		return best;
	}

	/** In the open chest: the slot of the buddy's own inventory with something that belongs in this chest. */
	private static int depositSlot(LocalPlayer p, AbstractContainerMenu m, int n) {
		boolean[] keep = keep(p);
		Set<Item> here = new HashSet<>();
		for (int i = 0; i < n; i++) if (!m.slots.get(i).getItem().isEmpty()) here.add(m.slots.get(i).getItem().getItem());
		boolean nearlyFull = Work.freeSlots(p) <= 4;
		for (int s = n; s < m.slots.size(); s++) {
			Slot sl = m.slots.get(s);
			if (sl.container != p.getInventory()) continue;
			int idx = sl.getContainerSlot();
			ItemStack st = sl.getItem();
			if (idx >= 36 || st.isEmpty() || keep[idx]) continue;
			String id = AfkHelperClient.itemId(st);
			if (here.contains(st.getItem())) return s;
			if (fallback && (nearlyFull || forcing)) { if (visit != null && !visit.had.contains(id)) visit.strays.add(id); return s; }
		}
		return -1;
	}

	// ---------- what the buddy keeps ----------

	/**
	 * Which of the 36 slots stay with the buddy: the two best of each kind of tool and sword (by speed or damage,
	 * enchantments and what durability is left; one about to break counts last), the weapon to fight with, for each armour
	 * slot the best piece not worn (one on, one spare), up to two stacks each of food, torches and rockets, two totems,
	 * saplings up to 16 and a stack of each kind of seed, and the few things always carried (a shield, a compass, a
	 * clock, a map, an elytra, shears). What one of the buddy's furnaces needs and what the corpse guard holds for its owner stay
	 * too. The rest goes to the chests.
	 */
	static boolean[] keep(LocalPlayer p) {
		var inv = p.getInventory();
		boolean[] keep = new boolean[36];
		String[] kinds = { "pickaxe", "axe", "shovel", "how", "sword" };
		for (String kind : kinds) {
			List<Integer> of = new ArrayList<>();
			for (int i = 0; i < 36; i++) if (inv.getItem(i).is(tag(kind))) of.add(i);
			of.sort(Comparator.comparingDouble((Integer i) -> -toolScore(inv.getItem(i), kind)));
			for (int k = 0; k < of.size() && k < 2; k++) keep[of.get(k)] = true;
		}
		int weapon = -1;
		double dps = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack st = inv.getItem(i);
			if (!Guard.isWeapon(st) || Work.nearlyBroken(st)) continue;
			double d = Guard.dps(st);
			if (d > dps) { dps = d; weapon = i; }
		}
		if (weapon >= 0) keep[weapon] = true;
		// up to two stacks of: food (the most filling first), torches, rockets; two totems
		keepUpTo(inv, keep, AfkHelperClient::eatable, Comparator.comparingInt((Integer i) -> { var f = inv.getItem(i).get(DataComponents.FOOD); return f == null ? 0 : -f.nutrition(); }), 128);
		keepUpTo(inv, keep, st -> st.is(Items.TORCH), null, 128);
		keepUpTo(inv, keep, st -> st.is(Items.FIREWORK_ROCKET), null, 128);
		keepUpTo(inv, keep, st -> st.is(Items.TOTEM_OF_UNDYING), null, 2);
		// golden apples for the fight (24 Oct 2026): eaten only to heal in one (Heal), never for hunger
		keepUpTo(inv, keep, st -> st.is(Items.GOLDEN_APPLE), null, 8);
		keepUpTo(inv, keep, st -> st.is(Items.ENCHANTED_GOLDEN_APPLE), null, 4);
		// the bow and its arrows (step 2): the best bow, two stacks of arrows
		keepUpTo(inv, keep, st -> st.is(Items.BOW) && !Work.nearlyBroken(st), Comparator.comparingInt((Integer i) -> inv.getItem(i).isEnchanted() ? 0 : 1), 1);
		keepUpTo(inv, keep, st -> st.is(ItemTags.ARROWS), null, 128);
		// wheat, to lure an animal that got out back into its pen (Herd): the smallest stack, so a full one goes to the chests
		keepUpTo(inv, keep, st -> st.is(Items.WHEAT), Comparator.comparingInt((Integer i) -> inv.getItem(i).getCount()), 1);
		// the best spare piece for each armour slot
		for (EquipmentSlot slot : new EquipmentSlot[] { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
			int best = -1;
			double bestA = -1;
			for (int i = 0; i < 36; i++) {
				ItemStack st = inv.getItem(i);
				Equippable e = st.get(DataComponents.EQUIPPABLE);
				if (e == null || e.slot() != slot || st.is(Items.ELYTRA)) continue;
				double a = armour(st, slot) + (st.isEnchanted() ? 0.5 : 0) + durability(st);
				if (a > bestA) { bestA = a; best = i; }
			}
			if (best >= 0) keep[best] = true;
		}
		int saplings = 0;
		Map<String, Integer> seeds = new HashMap<>();
		for (int i = 0; i < 36; i++) {
			ItemStack st = inv.getItem(i);
			if (st.is(ItemTags.SAPLINGS) && saplings < 16) { keep[i] = true; saplings += st.getCount(); }
			// the seeds of the buddy's fields, to plant again: a stack of each kind
			String id = AfkHelperClient.itemId(st);
			if (SEEDS.contains(id) && seeds.merge(id, st.getCount(), Integer::sum) - st.getCount() < 64) keep[i] = true;
			if (st.is(Items.SHIELD) || st.is(Items.COMPASS) || st.is(Items.RECOVERY_COMPASS)
				|| st.is(Items.CLOCK) || st.is(Items.FILLED_MAP) || st.is(Items.ELYTRA) || st.is(Items.SHEARS)) keep[i] = true;
		}
		// what the corpse guard fetched goes back to its owner's hands, not into a chest; what a furnace needs stays
		for (int i = 0; i < 36; i++) if (Dump.heldBack(i) || Ovens.keeps(inv.getItem(i)) || Chores.keeps(p, inv.getItem(i))) keep[i] = true;
		// what was thrown at the buddy to take home goes home, whatever the rules above (Carrier)
		for (int i = 0; i < 36; i++) if (Carrier.carried(i)) keep[i] = false;
		return keep;
	}

	/** Keep slots of what matches, the first by the order given (the fullest stacks otherwise), up to this many. */
	private static void keepUpTo(net.minecraft.world.entity.player.Inventory inv, boolean[] keep, java.util.function.Predicate<ItemStack> what,
			Comparator<Integer> order, int most) {
		List<Integer> of = new ArrayList<>();
		for (int i = 0; i < 36; i++) if (!inv.getItem(i).isEmpty() && what.test(inv.getItem(i))) of.add(i);
		of.sort(order != null ? order : Comparator.comparingInt((Integer i) -> -inv.getItem(i).getCount()));
		int n = 0;
		for (int i : of) { if (n >= most) break; keep[i] = true; n += inv.getItem(i).getCount(); }
	}

	/** How good a tool or sword is: its speed or damage, a little for its enchantments and what durability it has left. */
	private static double toolScore(ItemStack st, String kind) {
		double base = kind.equals("sword") ? Guard.dps(st) : st.getDestroySpeed(sample(kind));
		if (worn(st)) return -100 + base;
		return base + (st.isEnchanted() ? 2 : 0) + durability(st) * 2;
	}

	/** The share of its durability an item has left, 1 for one that does not wear. */
	private static double durability(ItemStack st) {
		return st.isDamageableItem() ? (st.getMaxDamage() - st.getDamageValue()) / (double) st.getMaxDamage() : 1;
	}

	private static final Set<String> SEEDS = Set.of("wheat_seeds", "beetroot_seeds", "carrot", "potato", "nether_wart");

	private static boolean worn(ItemStack st) { return st.isDamageableItem() && st.getMaxDamage() - st.getDamageValue() <= 10; }

	/** A tool of this kind that is not about to break. */
	private static boolean usable(LocalPlayer p, TagKey<Item> kind) {
		for (int i = 0; i < 36; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (st.is(kind) && !worn(st)) return true;
		}
		return false;
	}

	private static int arrowCount(LocalPlayer p) {
		int n = 0;
		for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).is(ItemTags.ARROWS)) n += p.getInventory().getItem(i).getCount();
		return n;
	}

	/** Food at least this filling is worth a trip to the chests when all that is carried is poor (beetroot, a carrot). */
	static final int GOOD_FOOD = 5;

	private static int nutrition(ItemStack st) {
		var f = st.get(DataComponents.FOOD);
		return f == null ? 0 : f.nutrition();
	}

	/**
	 * All the food carried fills little (two or less a bite): on 24 Oct 2026 the buddy carried 133 beetroot and nothing else,
	 * one point a bite, while a fight heals on a full belly. A chest with better food is then worth a visit.
	 */
	private static boolean poorFood(LocalPlayer p) {
		int best = 0;
		for (int i = 0; i < 36; i++) if (AfkHelperClient.eatable(p.getInventory().getItem(i))) best = Math.max(best, nutrition(p.getInventory().getItem(i)));
		return best > 0 && best <= 2;
	}

	private static boolean armed(LocalPlayer p) {
		for (int i = 0; i < 36; i++) if (Guard.isWeapon(p.getInventory().getItem(i))) return true;
		return false;
	}

	private static TagKey<Item> tag(String kind) {
		return switch (kind) {
			case "pickaxe" -> ItemTags.PICKAXES;
			case "axe" -> ItemTags.AXES;
			case "shovel" -> ItemTags.SHOVELS;
			case "sword" -> ItemTags.SWORDS;
			default -> ItemTags.HOES;
		};
	}

	/** A block to compare tools of a kind on. */
	private static BlockState sample(String kind) {
		return switch (kind) {
			case "pickaxe" -> Blocks.STONE.defaultBlockState();
			case "axe" -> Blocks.OAK_LOG.defaultBlockState();
			case "shovel" -> Blocks.DIRT.defaultBlockState();
			default -> Blocks.HAY_BLOCK.defaultBlockState();
		};
	}

	// ---------- armour ----------

	/** How much this piece protects in that slot: its armour points, toughness at half. */
	static double armour(ItemStack st, EquipmentSlot slot) {
		if (st.isEmpty()) return 0;
		// the elytra stays on: the buddy flies after the followed player
		if (slot == EquipmentSlot.CHEST && LivingEntity.canGlideUsing(st, slot)) return 100;
		double a = 0;
		for (ItemAttributeModifiers.Entry e : st.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY).modifiers()) {
			if (!e.slot().test(slot)) continue;
			if (e.attribute().value() == Attributes.ARMOR.value()) a += e.modifier().amount();
			else if (e.attribute().value() == Attributes.ARMOR_TOUGHNESS.value()) a += e.modifier().amount() / 2;
		}
		return a;
	}

	/** The best worn or carried for that slot. */
	private static double bestArmour(LocalPlayer p, EquipmentSlot slot) {
		double best = armour(p.getItemBySlot(slot), slot);
		for (int i = 0; i < 36; i++) {
			ItemStack st = p.getInventory().getItem(i);
			Equippable e = st.get(DataComponents.EQUIPPABLE);
			if (e != null && e.slot() == slot) best = Math.max(best, armour(st, slot));
		}
		return best;
	}

	/**
	 * Better armour than is worn, in the inventory: on, one click a second, without a screen (the inventory's own menu
	 * takes clicks without one). A worse piece that is worn comes off first, into the inventory.
	 */
	static void wearBest(Minecraft mc, LocalPlayer p) {
		// not while carrying a dead player's things: one of them would go on
		if (Corpse.holding()) return;
		long now = System.currentTimeMillis();
		if (now < nextWear || mc.gameMode == null || p.containerMenu != p.inventoryMenu || AfkHelperClient.isEating()) return;
		// a chest or a crafting table clicked a moment ago may open any tick: a click on the armour now would be lost
		if (now - Recorder.lastUseAt < 1500) return;
		// a person takes a moment between two pieces, never the same moment (21 Oct 2026, after SoulFire's random min-max delays)
		nextWear = now + java.util.concurrent.ThreadLocalRandom.current().nextLong(700, 1600);
		var inv = p.getInventory();
		if (totem(mc, p)) return;
		int best = -1;
		EquipmentSlot bestSlot = null;
		double gain = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack st = inv.getItem(i);
			Equippable e = st.get(DataComponents.EQUIPPABLE);
			if (e == null || e.slot().getType() != EquipmentSlot.Type.HUMANOID_ARMOR || Work.nearlyBroken(st)) continue;
			double g = armour(st, e.slot()) + gold(p, st, e.slot()) - armour(p.getItemBySlot(e.slot()), e.slot()) - gold(p, p.getItemBySlot(e.slot()), e.slot());
			// as good, and fresh where the one worn is nearly worn out (a new piece the buddy made: baseRenew)
			if (g == 0 && Work.lowWear(p.getItemBySlot(e.slot())) && !Work.lowWear(st) && !p.getItemBySlot(e.slot()).isEmpty()) g = 0.01;
			// a nearly worn-out piece loses a tie to a fresh one: with the slot empty the first one found went on, came
			// off a second later for the fresh one, and went on again, for a minute (the scenarios of 23 Oct 2026)
			if (Work.lowWear(st)) g -= 0.001;
			if (g > gain) { gain = g; best = i; bestSlot = e.slot(); }
		}
		if (best < 0) return;
		int armourSlot = 8 - bestSlot.getIndex();   // 5 head, 6 chest, 7 legs, 8 feet in the inventory's menu
		if (!p.getItemBySlot(bestSlot).isEmpty()) {
			if (Work.freeSlots(p) == 0) return;
			Game.quickMove(mc.gameMode, p.inventoryMenu.containerId, armourSlot, p);
			Exchange.emit("helper", "action", "harness off", "piece", AfkHelperClient.itemId(p.getItemBySlot(bestSlot)));
			nextWear = now + java.util.concurrent.ThreadLocalRandom.current().nextLong(300, 700);
			return;
		}
		String id = AfkHelperClient.itemId(inv.getItem(best));
		Game.quickMove(mc.gameMode, p.inventoryMenu.containerId, best < 9 ? 36 + best : best, p);
		Exchange.emit("helper", "action", "harness on", "piece", id, "better", Math.round(gain * 10) / 10.0);
	}

	/**
	 * The off hand. A totem whenever there is one (21 Oct 2026, after SoulFire's AutoTotem: the buddy kept two totems and
	 * never held one; and since 1 Nov 2026 before a shield). A shield otherwise while guarding (the plan for fighting: Shield
	 * raises it). Only over an empty hand or a shield about to break: seeds or bone meal a farm holds there stay.
	 * One click, the game's own swap with the off hand.
	 */
	private static boolean totem(Minecraft mc, LocalPlayer p) {
		ItemStack off = p.getOffhandItem();
		var inv = p.getInventory();
		int shield = -1, totem = -1;
		for (int i = 0; i < 36; i++) {
			ItemStack st = inv.getItem(i);
			if (shield < 0 && st.is(Items.SHIELD) && !Work.nearlyBroken(st)) shield = i;
			if (totem < 0 && st.is(Items.TOTEM_OF_UNDYING)) totem = i;
		}
		boolean shieldOn = off.is(Items.SHIELD) && !Work.nearlyBroken(off);
		boolean canShield = Guard.on() && (shieldOn || shield >= 0);
		// a totem goes before a shield, always: the shield only while there is no totem
		boolean wantTotem = totem >= 0 || off.is(Items.TOTEM_OF_UNDYING);
		if (wantTotem) {
			if (off.is(Items.TOTEM_OF_UNDYING) || totem < 0) return false;
			Game.swapWithHotbar(mc.gameMode, p.inventoryMenu.containerId, totem < 9 ? 36 + totem : totem, 40, p);
			Exchange.emit("helper", "action", "totem in hand", "hp", Math.round(p.getHealth()));
			return true;
		}
		if (!canShield || shieldOn || shield < 0) return false;
		boolean free = off.isEmpty() || (off.is(Items.SHIELD) && Work.nearlyBroken(off));
		if (!free) return false;
		Game.swapWithHotbar(mc.gameMode, p.inventoryMenu.containerId, shield < 9 ? 36 + shield : shield, 40, p);
		Exchange.emit("helper", "action", "shield in hand", "hp", Math.round(p.getHealth()));
		return true;
	}

	// ---------- the file ----------

	/** The server's profile (see Profiles): a chest is known for the server it stands on. */
	private static String server() { return Profiles.key(); }

	/** Forget every chest of this player on this server; how many. */
	static int forget(String player) {
		load();
		String server = server(), who = player.toLowerCase(java.util.Locale.ROOT);
		int before = chests.size();
		chests.removeIf(c -> c.server.equals(server) && c.owner.equals(who));
		save();
		stop("forgotten");
		return before - chests.size();
	}

	private static Path file() { return Paths.get(AfkConfig.get().exchangeDir).resolve("chests.json"); }

	private static void load() {
		if (loaded) return;
		loaded = true;
		try {
			Path f = file();
			if (!Files.exists(f)) return;
			JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
			JsonElement list = root.get("chests");
			if (list == null) return;
			for (JsonElement el : list.getAsJsonArray()) {
				JsonObject o = el.getAsJsonObject();
				Chest c = new Chest();
				c.server = Profiles.migrate(o.get("server").getAsString());
				JsonElement owner = o.get("owner");
				c.owner = owner != null ? owner.getAsString() : Follow.boss() == null ? "" : Follow.boss().toLowerCase(java.util.Locale.ROOT);
				c.dim = o.get("dim").getAsString();
				c.pos = new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
				c.size = o.has("size") ? o.get("size").getAsInt() : 0;
				c.free = o.has("free") ? o.get("free").getAsInt() : -1;
				c.seen = o.has("seen") ? o.get("seen").getAsLong() : 0;
				if (o.has("items")) for (var e : o.getAsJsonObject("items").entrySet()) c.items.put(e.getKey(), e.getValue().getAsInt());
				JsonElement had = o.get("had"), strays = o.get("strays"), fresh = o.get("fresh");
				if (had != null) for (JsonElement h : had.getAsJsonArray()) c.had.add(h.getAsString());
				if (strays != null) for (JsonElement h : strays.getAsJsonArray()) c.strays.add(h.getAsString());
				// written before 0.4.96: every tool or piece in it taken for good until the next look
				if (fresh != null) for (JsonElement h : fresh.getAsJsonArray()) c.fresh.add(h.getAsString());
				else for (String id : c.items.keySet()) if (stackOf(id).isDamageableItem()) c.fresh.add(id);
				c.had.addAll(c.items.keySet());
				chests.add(c);
			}
		} catch (Exception e) {
			Exchange.emit("error", "msg", "chests not read: " + e.getMessage());
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static void save() {
		JsonArray a = new JsonArray();
		for (Chest c : chests) {
			JsonObject o = new JsonObject();
			o.addProperty("server", c.server);
			o.addProperty("owner", c.owner);
			o.addProperty("dim", c.dim);
			o.addProperty("x", c.pos.getX());
			o.addProperty("y", c.pos.getY());
			o.addProperty("z", c.pos.getZ());
			o.addProperty("size", c.size);
			o.addProperty("free", c.free);
			o.addProperty("seen", c.seen);
			JsonObject items = new JsonObject();
			for (var e : c.items.entrySet()) items.addProperty(e.getKey(), e.getValue());
			o.add("items", items);
			JsonArray had = new JsonArray();
			for (String h : new java.util.TreeSet<>(c.had)) had.add(h);
			o.add("had", had);
			JsonArray strays = new JsonArray();
			for (String h : new java.util.TreeSet<>(c.strays)) strays.add(h);
			o.add("strays", strays);
			JsonArray fresh = new JsonArray();
			for (String h : new java.util.TreeSet<>(c.fresh)) fresh.add(h);
			o.add("fresh", fresh);
			a.add(o);
		}
		JsonObject root = new JsonObject();
		root.add("chests", a);
		try {
			Path f = file(), tmp = f.resolveSibling("chests.json.tmp");
			Files.writeString(tmp, GSON.toJson(root));
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			Exchange.emit("error", "msg", "chests not saved: " + e.getMessage());
		}
	}
}
