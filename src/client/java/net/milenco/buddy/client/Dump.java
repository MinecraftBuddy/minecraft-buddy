package net.milenco.buddy.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.milenco.buddy.version.Game;

/**
 * What he carries and does not keep goes to the player he follows when none of that player's chests is near, so that
 * no stock of it stays in the buddy's inventory, and the buddy makes sure the player picked it up.
 * After a job of {@link Work} (chopping that stopped, a harvest, a tunnel), with no chest of his within 32 blocks: up
 * to him, two blocks off, the crosshair at his feet, and the stacks thrown. Then watched: an item gone while he stood
 * by it went to him. What still lies there after eight seconds the alt picks up again and throws once more; three
 * tries, and then five minutes before the next. What it keeps is {@link Stash#keep}.
 */
final class Dump {
	private Dump() {}

	private static final int IDLE = 0, WALK = 1, THROW = 2, WATCH = 3, PICKUP = 4;
	private static int step = IDLE;
	/** A job ended at this moment: give what he carries. */
	private static long due;
	private static long stepSince, restUntil, lastThrow;
	private static int tries, given, back;
	private static final List<Integer> slots = new ArrayList<>();
	private static final Set<Integer> before = new HashSet<>();
	/** The thrown items, with where each lay last. */
	private static final Map<Integer, Vec3> thrown = new HashMap<>();

	static final double NEAR_CHEST = 32, STAND = 2.0;
	static final long WAIT = 8000, REST = 5 * 60_000L;

	static boolean on = true;
	/**
	 * Giving back what the corpse guard fetched ({@link Corpse}): only these inventory slots, to this player, whatever
	 * {@link #on} says and with his chests near or not. Null when it gives what it does not keep.
	 */
	private static Set<Integer> only;
	private static String onlyFor;

	static void giveBack(Set<Integer> invSlots, String player) {
		only = new HashSet<>(invSlots);
		onlyFor = player;
		due = System.currentTimeMillis();
		restUntil = 0;
		tries = 0;
	}

	static boolean givingBack() { return only != null; }

	/** The slots kept for giving back: no chest takes them meanwhile. */
	static boolean heldBack(int invSlot) { return only != null && only.contains(invSlot); }

	static boolean busy() { return step != IDLE; }

	/** A job of Work is over: what it brought in goes to him, now or once he is near. */
	static void jobDone() {
		if (due == 0) due = System.currentTimeMillis();
	}

	static void reset() {
		step = IDLE;
		thrown.clear();
		slots.clear();
		before.clear();
	}

	static String state() {
		return switch (step) {
			case WALK -> "to you";
			case THROW -> "throws";
			case WATCH -> "watching whether you take it";
			case PICKUP -> "picks it up again";
			default -> now() < restUntil ? "waits (" + (restUntil - now()) / 1000 + " s)" : due != 0 ? "wants to give" : "";
		};
	}

	private static long now() { return System.currentTimeMillis(); }

	/** What he does not keep, in the inventory's own menu numbers (hotbar 36 to 44). */
	private static List<Integer> giveSlots(LocalPlayer p) {
		List<Integer> out = new ArrayList<>();
		if (only != null) {
			for (int i : only) if (i >= 0 && i < 36 && !p.getInventory().getItem(i).isEmpty()) out.add(i < 9 ? 36 + i : i);
			return out;
		}
		boolean[] keep = Stash.keep(p);
		for (int i = 0; i < 36; i++) if (!keep[i] && !p.getInventory().getItem(i).isEmpty()) out.add(i < 9 ? 36 + i : i);
		return out;
	}

	/** Once a tick while following, when nothing else of Work is going on. True while busy. */
	static boolean tick(Minecraft mc, LocalPlayer p, Player t) {
		long now = now();
		Level level = p.level();
		if ((!on && only == null) || t == null || t == p) { if (step != IDLE) stop("you are gone"); return false; }
		if (only != null && !t.getGameProfile().name().equalsIgnoreCase(onlyFor)) return false;
		switch (step) {
			case IDLE -> {
				if (due == 0 || now < restUntil || Follow.ticks() % 20 != 0) return false;
				if (now - due > 30 * 60_000L) { due = 0; return false; }
				if (t.distanceTo(p) > 24 || p.containerMenu != p.inventoryMenu) return false;
				if (giveSlots(p).isEmpty()) { due = 0; tries = 0; only = null; return false; }
				// his chests near: Stash puts it there (not what the corpse guard fetched: that is his, back in his hands)
				if (only == null && Stash.nearChest(level, p.blockPosition(), NEAR_CHEST)) { due = 0; return false; }
				step = WALK;
				stepSince = now;
				Exchange.emit("helper", "action", "give", "how", "starts", "attempt", tries + 1, "stacks", giveSlots(p).size());
				return true;
			}
			case WALK -> {
				Vec3 at = t.position(), me = p.position();
				double flat = Math.hypot(at.x - me.x, at.z - me.z);
				if (flat > 1.4 && flat < 2.8 && Math.abs(at.y - me.y) < 1.2 && p.onGround()) {
					Follow.stand();
					step = THROW;
					stepSince = now;
					slots.clear();
					slots.addAll(giveSlots(p));
					before.clear();
					for (ItemEntity it : level.getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(8))) before.add(it.getId());
					return true;
				}
				if (now - stepSince > 20_000) { pause("cannot reach you"); return false; }
				// two blocks from him, on the side the alt comes from
				Vec3 dir = flat < 0.1 ? new Vec3(1, 0, 0) : new Vec3(me.x - at.x, 0, me.z - at.z).normalize();
				Vec3 goal = at.add(dir.scale(STAND));
				if (!Work.approach(mc, p, "give", goal, now)) { pause("cannot reach you"); return false; }
				return true;
			}
			case THROW -> {
				Follow.stand();
				// the crosshair half a block in front of his feet: what is thrown lands at them
				Vec3 at = t.position(), me = p.position();
				Vec3 toward = new Vec3(at.x - me.x, 0, at.z - me.z);
				Vec3 target = toward.lengthSqr() < 0.01 ? at : at.subtract(toward.normalize().scale(0.5));
				Follow.aim(p, target);
				Vec3 eye = p.getEyePosition();
				float yaw = (float) Math.toDegrees(Math.atan2(-(target.x - eye.x), target.z - eye.z));
				boolean facing = Math.abs(Mth.wrapDegrees(yaw - p.getYRot())) < 12 && p.getXRot() > 20;
				if (!facing && now - stepSince < 3000) return true;
				if (mc.gameMode == null || p.containerMenu != p.inventoryMenu || AfkHelperClient.isEating()) return true;
				if (!slots.isEmpty()) {
					// two stacks a tick: twenty stacks in half a second, as a player who empties his inventory with Q
					for (int k = 0; k < 2 && !slots.isEmpty(); k++) Game.drop(mc.gameMode, p.inventoryMenu.containerId, slots.remove(0), p);
					lastThrow = now;
					return true;
				}
				// the items appear a tick or two later: the new ones near him are his
				for (ItemEntity it : level.getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(6)))
					if (!before.contains(it.getId())) thrown.put(it.getId(), it.position());
				if (now - lastThrow < 500) return true;
				step = WATCH;
				stepSince = now;
				given = back = 0;
				Exchange.emit("helper", "action", "give", "how", "thrown", "attempt", tries + 1, "pieces", thrown.size());
				if (thrown.isEmpty()) finish();
				return true;
			}
			case WATCH -> {
				Follow.stand();
				lookAt(p, t);
				watchThrown(level, p, t);
				if (thrown.isEmpty()) { finish(); return false; }
				// he picks them up when he stands on them: what still lies there after a while, back into the alt's hands
				if (now - stepSince > WAIT) {
					tries++;
					step = PICKUP;
					stepSince = now;
					Exchange.emit("helper", "action", "give", "how", "not picked up", "attempt", tries, "lying", thrown.size());
				}
				return true;
			}
			case PICKUP -> {
				watchThrown(level, p, t);
				if (thrown.isEmpty() || now - stepSince > 20_000) {
					thrown.clear();
					if (tries >= 3) { pause("not picked up three times"); return false; }
					step = WALK;
					stepSince = now;
					return true;
				}
				// the nearest one
				Integer id = null;
				double best = Double.MAX_VALUE;
				for (var e : thrown.entrySet()) {
					double d = e.getValue().distanceToSqr(p.position());
					if (d < best) { best = d; id = e.getKey(); }
				}
				if (!Work.approach(mc, p, "give-" + id, thrown.get(id), now)) thrown.remove(id);
				return true;
			}
			default -> { step = IDLE; return false; }
		}
	}

	/** What of the thrown items is gone: near him, it went to him; near the alt, back to the alt. */
	private static void watchThrown(Level level, LocalPlayer p, Player t) {
		var it = thrown.entrySet().iterator();
		while (it.hasNext()) {
			var e = it.next();
			Entity ent = level.getEntity(e.getKey());
			if (ent instanceof ItemEntity item && item.isAlive()) { e.setValue(item.position()); continue; }
			Vec3 last = e.getValue();
			if (t.getBoundingBox().inflate(1.5, 1, 1.5).contains(last)) given++;
			else if (p.getBoundingBox().inflate(1.5, 1, 1.5).contains(last)) back++;
			it.remove();
		}
	}

	private static void lookAt(LocalPlayer p, Player t) {
		Vec3 eye = p.getEyePosition(), to = t.getEyePosition();
		float yaw = (float) Math.toDegrees(Math.atan2(-(to.x - eye.x), to.z - eye.z));
		if (Math.abs(Mth.wrapDegrees(yaw - p.getYRot())) > 15) Follow.aim(p, to);
	}

	private static void finish() {
		Exchange.emit("helper", "action", "give", "how", "done", "given", given, "taken back", back, "attempt", tries + 1);
		step = IDLE;
		due = 0;
		tries = 0;
		thrown.clear();
		only = null;
	}

	/** It did not come off: five minutes before the next try (three tries), or after a short while (he walked off). */
	private static void pause(String why) {
		boolean done = tries >= 3;
		restUntil = now() + (done ? REST : 60_000);
		if (done) { tries = 0; due = restUntil; only = null; }
		Exchange.emit("helper", "action", "give", "how", "stopped", "reason", why, "again_in_s", (restUntil - now()) / 1000);
		step = IDLE;
		thrown.clear();
	}

	static void stop(String why) {
		if (step == IDLE) return;
		Exchange.emit("helper", "action", "give", "how", "broken off", "reason", why);
		step = IDLE;
		thrown.clear();
	}
}
