package net.milenco.buddy.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The corpse guard: when the followed player dies near the buddy, the buddy fetches what fell and gives it back once
 * that player is close again: only when the buddy was near, and never where fetching would kill the buddy: not in lava
 * or the void, nor among too many mobs. It runs by itself while following; nothing starts it.
 *
 * <p>What is the player's: the items that appear around the spot in the two seconds after the death, and no other; what lay
 * there before stays. Nothing is fetched from lava or below the world, nor while more than {@link #MOBS} hostile mobs or
 * a creeper are near the spot, nor while the buddy's own health is low: then the buddy waits at a distance, as long as
 * the items last (five minutes on the ground). For a player killed by another player nothing is fetched (that is the
 * winner's). With a full inventory the dearest things go first. Back with the player, {@link Dump} throws them at the
 * player's feet, three tries.
 */
final class Corpse {
	private Corpse() {}

	static final double NEAR = 32, SPOT = 6, MOB_RADIUS = 8, CREEPER_RADIUS = 10, KEEP_AWAY = 14;
	static final int MOBS = 2;
	static final float LOW = 10f;
	static final long DROP_WINDOW = 2000, LAST = 5 * 60_000L;

	private static final int IDLE = 0, DROPS = 1, FETCH = 2, BACK = 3;
	private static int step = IDLE;
	private static Vec3 spot;
	private static String dim, owner;
	private static long diedAt, waitNoted;
	private static final Set<Integer> before = new HashSet<>();
	/** The items that are the dead player's, with where each lay last. */
	private static final Map<Integer, Vec3> drops = new LinkedHashMap<>();
	private static final Set<Integer> gaveUp = new HashSet<>();
	/** The stack each of those items held when last seen, so that what the alt took is known exactly. */
	private static final Map<Integer, ItemStack> held = new HashMap<>();
	/** What the alt took off the ground this trip: given back as those very items, not as whatever slot changed. */
	private static final List<ItemStack> taken = new ArrayList<>();
	private static int trips;

	static boolean busy() { return step == DROPS || step == FETCH; }

	/** Carrying the dead player's things: none of them is put on (the buddy put on a fetched chestplate, 20 Oct 2026). */
	static boolean holding() { return step == FETCH || step == BACK; }

	static String state() {
		return switch (step) {
			case DROPS -> "watching what drops";
			case FETCH -> "fetching your things (" + drops.size() + ")";
			case BACK -> "has your things";
			default -> "";
		};
	}

	/**
	 * Every tick while following, before anything else: the moment the player dies, what drops around the body then, and
	 * the items gone from where they lay.
	 */
	static void watch(LocalPlayer p, Player t) {
		long now = System.currentTimeMillis();
		Level level = p.level();
		String who = deadName;
		if (who != null) {
			deadName = null;
			String target = Follow.target();
			if (target != null && who.equalsIgnoreCase(target) && now - deadAt < 2000) died(p, who, deadPos, deadKiller, now);
		}
		if (step == DROPS) {
			if (!level.dimension().identifier().toString().equals(dim)) { reset("another dimension"); return; }
			for (ItemEntity it : level.getEntitiesOfClass(ItemEntity.class, new AABB(spot, spot).inflate(SPOT)))
				if (!before.contains(it.getId()) && !drops.containsKey(it.getId())) { drops.put(it.getId(), it.position()); held.put(it.getId(), it.getItem().copy()); }
			if (now - diedAt > DROP_WINDOW) {
				if (drops.isEmpty()) { reset("nothing dropped"); return; }
				step = FETCH;
				taken.clear();
				trips = 0;
				Exchange.emit("corpse guard", "action", "dropped", "for", owner, "pieces", drops.size());
			}
		}
	}

	// the death event, from the packet's handling (DeathEventMixin), taken up by the next tick
	private static volatile String deadName;
	private static volatile Vec3 deadPos;
	private static volatile Entity deadKiller;
	private static volatile long deadAt;

	static void deathEvent(String name, Vec3 at, Entity killer) {
		deadPos = at;
		deadKiller = killer;
		deadAt = System.currentTimeMillis();
		deadName = name;
	}

	/**
	 * The player died: if the alt was near and in the same world, note what lay there already. The player's things fell
	 * in the same server tick just before the event, so an item there that is only a few ticks old is the player's, an
	 * older one is not.
	 */
	private static void died(LocalPlayer p, String name, Vec3 at, Entity killer, long now) {
		if (step != IDLE) return;
		double far = at.distanceTo(p.position());
		if (far > NEAR) { Exchange.emit("corpse guard", "action", "too far", "for", name, "distance", Math.round(far)); return; }
		// killed by another player: what the dead player dropped is the winner's
		if (killer instanceof Player other && !other.getGameProfile().name().equalsIgnoreCase(name)) {
			Exchange.emit("corpse guard", "action", "by a player", "for", name, "by", other.getGameProfile().name());
			return;
		}
		spot = at;
		dim = p.level().dimension().identifier().toString();
		owner = name;
		diedAt = now;
		before.clear();
		drops.clear();
		gaveUp.clear();
		held.clear();
		for (ItemEntity it : p.level().getEntitiesOfClass(ItemEntity.class, new AABB(spot, spot).inflate(SPOT))) {
			if (it.tickCount > 10) before.add(it.getId());
			else { drops.put(it.getId(), it.position()); held.put(it.getId(), it.getItem().copy()); }
		}
		step = DROPS;
		Exchange.emit("corpse guard", "action", "death seen", "for", owner, "x", (int) Math.floor(spot.x), "y", (int) Math.floor(spot.y), "z", (int) Math.floor(spot.z));
	}

	/** Fetching: true while it has the keys. */
	static boolean tick(Minecraft mc, LocalPlayer p) {
		if (step == BACK) { giveBack(p); return false; }
		if (step != FETCH) return false;
		long now = System.currentTimeMillis();
		Level level = p.level();
		if (!level.dimension().identifier().toString().equals(dim)) { done(p, "another dimension", false); return false; }
		if (now - diedAt > LAST) { done(p, "gone", false); return false; }
		// what is gone: picked up by the alt, by someone else, burnt or despawned; part of a stack taken by the alt too
		AABB mine = p.getBoundingBox().inflate(1.5, 1, 1.5);
		var it = drops.entrySet().iterator();
		while (it.hasNext()) {
			var e = it.next();
			Entity ent = level.getEntity(e.getKey());
			ItemStack was = held.get(e.getKey());
			if (ent instanceof ItemEntity item && item.isAlive()) {
				ItemStack now2 = item.getItem();
				if (was != null && now2.getCount() < was.getCount() && mine.contains(item.position())) taken.add(was.copyWithCount(was.getCount() - now2.getCount()));
				held.put(e.getKey(), now2.copy());
				e.setValue(item.position());
				continue;
			}
			if (was != null && mine.contains(e.getValue())) taken.add(was.copy());
			held.remove(e.getKey());
			it.remove();
		}
		// what cannot be fetched without dying: in lava or fire, below the world
		for (var e : drops.entrySet()) {
			if (gaveUp.contains(e.getKey())) continue;
			Vec3 at = e.getValue();
			BlockPos b = BlockPos.containing(at);
			if (at.y < level.getMinY() + 1 || level.getFluidState(b).is(FluidTags.LAVA) || level.getFluidState(b.below()).is(FluidTags.LAVA)
					|| level.getBlockState(b).is(net.minecraft.tags.BlockTags.FIRE)) gaveUp.add(e.getKey());
		}
		List<Integer> left = new ArrayList<>();
		for (Integer id : drops.keySet()) if (!gaveUp.contains(id)) left.add(id);
		if (left.isEmpty()) { done(p, drops.isEmpty() ? "all fetched" : "rest out of reach", false); return false; }
		// too dangerous now: wait at a distance while the items last
		String danger = danger(level, p);
		if (danger != null) {
			if (now - waitNoted > 15_000) { waitNoted = now; Exchange.emit("corpse guard", "action", "waits", "reason", danger, "lying", left.size()); }
			if (p.position().distanceTo(spot) < KEEP_AWAY) {
				Vec3 away = p.position().subtract(spot);
				away = away.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : away.normalize();
				Work.approach(mc, p, "corpse-guard-away", spot.add(away.scale(KEEP_AWAY + 2)), now);
				return true;
			}
			Follow.stand();
			return true;
		}
		// full: only what goes on a stack the buddy has, or the dearest when a slot is free
		boolean full = p.getInventory().getFreeSlot() < 0;
		Integer best = null;
		double bestScore = -Double.MAX_VALUE;
		for (Integer id : left) {
			Entity ent = level.getEntity(id);
			if (!(ent instanceof ItemEntity item)) continue;
			ItemStack st = item.getItem();
			if (full && !fits(p, st)) continue;
			double score = worth(st) * 100 - item.position().distanceTo(p.position());
			if (score > bestScore) { bestScore = score; best = id; }
		}
		// full: give back what the buddy has, and come for the rest after that
		if (best == null) { done(p, full ? "inventory full" : "not visible", full); return false; }
		Vec3 goal = drops.get(best);
		if (!Work.approach(mc, p, "corpse-guard-" + best, goal, now)) gaveUp.add(best);
		return true;
	}

	/** Why it is not safe to go now, or null. */
	private static String danger(Level level, LocalPlayer p) {
		if (p.getHealth() < LOW) return "own health low";
		if (p.isOnFire()) return "burning";
		AABB near = new AABB(spot, spot).inflate(MOB_RADIUS);
		int mobs = 0;
		for (Entity e : level.getEntities(p, near)) if (e instanceof Enemy && e.isAlive()) mobs++;
		if (mobs > MOBS) return mobs + " mobs";
		for (Creeper c : level.getEntitiesOfClass(Creeper.class, new AABB(spot, spot).inflate(CREEPER_RADIUS))) if (c.isAlive()) return "creeper";
		return null;
	}

	/** How dear a stack is, so that a full inventory takes the right things: 0 for nothing much, up to about 3. */
	static double worth(ItemStack st) {
		double w = 0;
		if (st.is(Items.TOTEM_OF_UNDYING) || st.is(Items.ELYTRA) || st.is(Items.NETHER_STAR) || st.is(Items.ENCHANTED_GOLDEN_APPLE)) w += 3;
		if (st.isEnchanted() || st.has(DataComponents.STORED_ENCHANTMENTS)) w += 1.5;
		if (st.has(DataComponents.CONTAINER)) w += 2;   // a shulker box with what is in it
		String id = AfkHelperClient.itemId(st);
		if (id.contains("netherite")) w += 2;
		else if (id.contains("diamond")) w += 1.5;
		if (st.isDamageableItem()) w += 0.8;
		if (st.has(DataComponents.EQUIPPABLE)) w += 0.5;
		return w + Math.min(st.getCount(), 64) / 640.0;
	}

	/** Goes onto a stack of the same that has room. */
	private static boolean fits(LocalPlayer p, ItemStack st) {
		for (int i = 0; i < 36; i++) {
			ItemStack have = p.getInventory().getItem(i);
			if (ItemStack.isSameItemSameComponents(have, st) && have.getCount() < have.getMaxStackSize()) return true;
		}
		return false;
	}

	/**
	 * A trip is over: give back what was taken. With things still on the ground (the buddy's inventory was full), another
	 * trip once given; a trip that took nothing ends it.
	 */
	private static void done(LocalPlayer p, String why, boolean more) {
		Set<Integer> slots = slotsOf(p, taken);
		int pieces = 0;
		for (ItemStack st : taken) pieces += st.getCount();
		Exchange.emit("corpse guard", "action", "fetched", "reason", why, "pieces", pieces, "slots", slots.size(), "lying", drops.size(), "trip", trips + 1);
		trips++;
		if (!more || taken.isEmpty()) drops.clear();
		taken.clear();
		if (slots.isEmpty()) { reset(why); return; }
		step = BACK;
		Dump.giveBack(slots, owner);
	}

	/** The slots that hold what the buddy took: the same items, as many as were taken (a stack of the buddy's own of it
	 *  may go along). */
	private static Set<Integer> slotsOf(LocalPlayer p, List<ItemStack> took) {
		Set<Integer> out = new HashSet<>();
		for (ItemStack want : took) {
			int need = want.getCount();
			for (int i = 0; i < 36 && need > 0; i++) {
				ItemStack have = p.getInventory().getItem(i);
				if (out.contains(i) || !ItemStack.isSameItemSameComponents(have, want)) continue;
				out.add(i);
				need -= have.getCount();
			}
		}
		return out;
	}

	/** Dump has it now; once it gave, or gave up, another trip for what is left, or done. */
	private static void giveBack(LocalPlayer p) {
		if (Dump.givingBack()) return;
		if (!drops.isEmpty() && System.currentTimeMillis() - diedAt < LAST) {
			step = FETCH;
			Exchange.emit("corpse guard", "action", "another trip", "lying", drops.size());
			return;
		}
		reset("given back");
	}

	private static void reset(String why) {
		if (step != IDLE) Exchange.emit("corpse guard", "action", "done", "reason", why);
		step = IDLE;
		drops.clear();
		before.clear();
		gaveUp.clear();
		held.clear();
		taken.clear();
	}

	static void stop(String why) { if (step != IDLE) reset(why); }
}
