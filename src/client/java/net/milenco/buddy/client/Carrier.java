package net.milenco.buddy.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The carrier: what the player he follows throws at him goes home to the base's chests. Thrown is an item that
 * appears at the height of that player's eyes, where a throw starts, and that the alt then picks up; what the rules of
 * {@link Stash#keep} would keep is still taken home.
 *
 * <p>Once nothing more came for six seconds he goes: in the base already, he stores it; otherwise by the profile's
 * plain home command (his own home, set at the bed by a tap), or on foot when the base is near and the server has none.
 * In the base Stash puts it all away, in a chest that holds the same or else in the roomiest one. Then the following
 * takes over again (a /tpa where the server has one). With no base he keeps it, and the chests near the player get it
 * as ever.
 */
final class Carrier {
	private Carrier() {}

	static final long QUIET = 6000, HOME_WAIT = 15_000, STORE_MAX = 60_000, RETRY = 120_000;
	static final double WALK_MAX = 160;

	private static final int IDLE = 0, HOME = 1, WALK = 2, STORE = 3;
	private static int step = IDLE;
	/** Items that appeared at his eyes, with the stack each held, while they lie there. */
	private static final Map<Integer, ItemStack> flying = new HashMap<>();
	private static final Map<Integer, Vec3> flyingAt = new HashMap<>();
	private static final Set<Integer> seen = new HashSet<>();
	/** What the alt caught: to take home. */
	private static final List<ItemStack> carried = new ArrayList<>();
	private static long lastCatch, stepSince, notBefore;
	private static String owner;

	static boolean busy() { return step != IDLE; }

	/** This inventory slot holds something he carries home: no rule keeps it. */
	static boolean carried(int slot) { return !carried.isEmpty() && slotsCache.contains(slot); }

	private static Set<Integer> slotsCache = new HashSet<>();

	/** Every tick while he follows and sees him: what he throws, and what of it the alt caught. */
	static void watch(LocalPlayer p, Player t) {
		Level level = p.level();
		if (t != null && t.isAlive() && !Dump.busy()) {
			double eye = t.getEyeY();
			for (ItemEntity it : level.getEntitiesOfClass(ItemEntity.class, t.getBoundingBox().inflate(2.5))) {
				if (it.tickCount > 4 || !seen.add(it.getId())) continue;
				if (Math.abs(it.getY() - (eye - 0.3)) > 0.6) continue;   // a throw starts just under the eyes
				// loot of a farm whose kill cell is at his eye height is no throw (Pen)
				if (Pen.on() && Pen.nearXZ(it.getX(), it.getZ(), 2)) continue;
				flying.put(it.getId(), it.getItem().copy());
				flyingAt.put(it.getId(), it.position());
			}
		}
		if (seen.size() > 500) seen.clear();
		AABB mine = p.getBoundingBox().inflate(1.5, 1, 1.5);
		for (var it = flying.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			Entity ent = level.getEntity(e.getKey());
			if (ent instanceof ItemEntity item && item.isAlive()) { e.setValue(item.getItem().copy()); flyingAt.put(e.getKey(), item.position()); continue; }
			Vec3 last = flyingAt.remove(e.getKey());
			if (last != null && mine.contains(last)) {
				carried.add(e.getValue());
				lastCatch = System.currentTimeMillis();
				owner = t != null ? t.getGameProfile().name() : owner;
				Exchange.emit("carrier", "action", "caught", "piece", AfkHelperClient.itemId(e.getValue()), "count", e.getValue().getCount(), "total", carried.size());
			}
			it.remove();
		}
		slotsCache = carried.isEmpty() ? new HashSet<>() : slotsOf(p, carried);
		if (!carried.isEmpty() && slotsCache.isEmpty()) { carried.clear(); if (step != IDLE) done("nothing left to carry"); }
	}

	/** True while it has the keys: on its way home, or storing there. */
	static boolean tick(Minecraft mc, LocalPlayer p) {
		long now = System.currentTimeMillis();
		if (carried.isEmpty()) { if (step != IDLE) done("nothing left to carry"); return false; }
		Level level = p.level();
		BlockPos bed = owner == null ? null : Tap.base(owner, Work.dimOf(level));
		switch (step) {
			case IDLE -> {
				if (now - lastCatch < QUIET || now < notBefore || bed == null) return false;
				double far = p.position().distanceTo(Vec3.atCenterOf(bed));
				if (far <= Base.RADIUS) { start(STORE, now, "in the base"); return true; }
				String home = Profiles.cmd("home", null);
				if (home != null) {
					Follow.stand();
					AfkHelperClient.sendTyped(home, null);
					start(HOME, now, "home");
					return true;
				}
				if (far <= WALK_MAX) { start(WALK, now, "walks home"); return true; }
				return false;
			}
			case HOME -> {
				Follow.stand();
				if (bed != null && p.position().distanceTo(Vec3.atCenterOf(bed)) <= Base.RADIUS) { start(STORE, now, "at home"); return true; }
				if (now - stepSince > HOME_WAIT) { giveUp("home failed", now); return false; }
				return true;
			}
			case WALK -> {
				if (bed == null) { giveUp("no base", now); return false; }
				if (p.position().distanceTo(Vec3.atCenterOf(bed)) <= Base.RADIUS / 2) { start(STORE, now, "at home"); return true; }
				if (!Work.approach(mc, p, "carrier-bed", Vec3.atBottomCenterOf(bed), now) || now - stepSince > 120_000) { giveUp("cannot get there", now); return false; }
				return true;
			}
			case STORE -> {
				Stash.forceStore(true);
				if (Stash.tick(mc, p, p)) return true;
				if (now - stepSince > STORE_MAX) { done("storing took too long"); return false; }
				// Stash found no chest for it this round: once more in a moment, then done with what is left
				if (now - stepSince > 20_000 && !Stash.busy()) { done("no chest with room"); return false; }
				return true;
			}
			default -> { step = IDLE; return false; }
		}
	}

	private static void start(int s, long now, String why) {
		step = s;
		stepSince = now;
		Exchange.emit("carrier", "action", why, "pieces", carried.size());
	}

	private static void giveUp(String why, long now) {
		Exchange.emit("carrier", "action", "fails", "reason", why);
		step = IDLE;
		notBefore = now + RETRY;
		Stash.forceStore(false);
	}

	private static void done(String why) {
		LocalPlayer p = AfkHelperClient.mc().player;
		int left = p == null ? 0 : slotsOf(p, carried).size();
		Exchange.emit("carrier", "action", "done", "reason", why, "still carried", left);
		step = IDLE;
		Stash.forceStore(false);
		carried.clear();
		slotsCache = new HashSet<>();
	}

	/** The slots that hold what he caught: the same items, as many as he caught. */
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

	static void stop(String why) { if (step != IDLE) { step = IDLE; Stash.forceStore(false); Exchange.emit("carrier", "action", "stopped", "reason", why); } }
}
