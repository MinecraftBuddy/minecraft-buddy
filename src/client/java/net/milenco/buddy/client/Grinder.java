package net.milenco.buddy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.milenco.buddy.version.Game;

import java.util.Comparator;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Farm mode: hits hostile mobs (blazes and the like) under the crosshair or within reach, at a random pace,
 * and now and then takes a small step there and back plus a small head movement. Never players, pets,
 * villagers, armor stands or named mobs.
 */
public class Grinder {
	private static boolean active = false;
	private static Vec3 start, anchor, lastPos;
	private static boolean approaching = false, sneaking = false;
	private static int stuckTicks = 0;
	private static long nextApproach = 0, crouchUntil = 0, lastBlockChange = 0;
	private static net.minecraft.core.BlockPos lastBlock = null;
	private static boolean bigStep = false;
	private static long nextBigStep = 0;   // earliest next big step, even if you are still on the same block
	private static int stepTicks = 0;      // ticks the outward leg of a nudge has been running
	private static long nextWalk = 0;
	private static long nextAttack = 0, nextNudge = 0, nextJitter = 0;
	private static long deferredMs = 0, deferredUntil = 0; private static Entity deferredWho = null; private static String deferredWhy = "";
	private static long returnSince = 0, returnDone = 0; private static double returnBest = 0; private static boolean returning = false;
	private static int nudgeTicks = 0, nudgePhase = 0;   // 1 = out, 2 = back
	private static net.minecraft.client.KeyMapping nudgeKey, backKey;
	private static int hits = 0, killed = 0;
	/** Mobs we just hit, with the moment: to count how many of them die. */
	private static final java.util.Map<Integer, Long> hitMobs = new java.util.HashMap<>();
	private static float lastHitYaw, lastHitPitch;   // direction of the last hit that landed = where the farm is
	private static long lastTargetAt = 0;
	private static Entity seenTarget = null;   // mob under the crosshair, since seenSince: reaction time at the raid farm
	private static long seenSince = 0, reaction = 0;
	private static final AfkHelperClient.Look look = new AfkHelperClient.Look();
	private static final ThreadLocalRandom R = ThreadLocalRandom.current();
	/** Upper bound for a natural click pace (server: no unnaturally fast hitting). */
	static final double MAX_CPS = 6.0;
	/** Within this many blocks raiders and vexes count as a threat: then the farm does not pause. */
	private static final double THREAT_RADIUS = 16;
	private static long nextBreak = 0, lastMobSeen = 0;
	private static long visitorUntil = 0, nextSession = 0;
	private static long nextSideSwitch = 0;
	private static boolean sideSwitching = false;
	/** Waypoints around the landing spot and, as the last one, the far side itself. */
	private static final java.util.ArrayDeque<Vec3> switchRoute = new java.util.ArrayDeque<>();
	private static Vec3 switchTarget = null, switchFailed = null;
	private static boolean returnBlocked = false;
	private static boolean visitorQuiet = false;
	private static double pace = 0;           // current click pace; drifts slowly within your bounds
	private static long paceUntil = 0;        // until when this pace holds
	private static double noise = 0.2, stallChance = 0.07, missChance = 0.35, whiffChance = 0.05, earlyChance = 0.12;   // how messy your clicking is; drifts along
	private static boolean earlyHit = false;    // this hit may go before full attack strength
	private static float earlyThreshold = 0.95f;   // and then at which attack strength; different per hit, otherwise you get a spike there
	private static long styleUntil = 0;
	private static double prevDeviation = 0;
	private static Entity pendingTarget = null;
	private static boolean pendingMiss = false;
	/**
	 * Stopped involuntarily while you were AFK: where the farm was running, so it turns back on
	 * as soon as you stand there again. Needed after a night in which the farm stopped at 04:13
	 * ("too far from the farm spot") and the buddy did nothing until the morning: coming back to the spot did not
	 * turn the farm on again.
	 */
	private static Vec3 resumeSpot = null;
	private static long resumeBefore = 0, resumeNotBefore = 0, stillSince = 0, startedAt = 0;
	private static Vec3 stillAt = null;
	private static int resumeAttempts = 0;
	/**
	 * How far the return spot may drift from your start spot. Without this bound the anchor was set
	 * to your current spot on every failed approach (wall, edge, mob gone): half a metre each time,
	 * and after an hour you stand metres away until the farm gives up entirely.
	 */
	private static final double ANCHOR_MAX = 2.0;

	/**
	 * New return spot, but never further than ANCHOR_MAX from where the farm started. With a landing spot
	 * it may lie anywhere around that spot: the side switch puts you on the far side, and then your start
	 * is four metres or so away.
	 */
	private static void setAnchor(Vec3 next) {
		if (start == null) { anchor = next; return; }
		LocalPlayer p = Minecraft.getInstance().player;
		if (p != null && areaOn(p) && !raidOn(p)) {
			double cx = Mth.clamp(next.x, aMinX(), aMaxX()), cz = Mth.clamp(next.z, aMinZ(), aMaxZ());
			if (Math.hypot(next.x - cx, next.z - cz) <= ANCHOR_MAX) { anchor = next; return; }
		}
		double dx = next.x - start.x, dz = next.z - start.z, d = Math.hypot(dx, dz);
		if (d <= ANCHOR_MAX) { anchor = next; return; }
		double f = ANCHOR_MAX / d;
		anchor = new Vec3(start.x + dx * f, next.y, start.z + dz * f);
	}

	/** Stops you did not cause yourself: only after those do we try to resume. */
	private static boolean involuntary(String why) {
		return why.equals("too far from the farm spot") || teleportedAway(why);
	}

	/** You were put somewhere else: a tpa, an event. */
	private static boolean teleportedAway(String why) {
		return why.equals("teleported") || why.equals("teleport");
	}

	/**
	 * Teleported away while farming: wait a bit (you do not come straight back like a bot)
	 * and then go to the configured home. There you stand on the farm spot again and resumeTick picks it up.
	 */
	private static void backToHome() {
		AfkConfig c = AfkConfig.get();
		String home = c.farmHomeBack == null ? "" : c.farmHomeBack.trim();
		if (home.isEmpty()) return;
		double over = R.nextDouble(10, 40);
		Exchange.emit("farm", "action", "back home", "home", home, "left_s", Math.round(over));
		AfkHelperClient.later(over, () -> {
			// anything may have happened in the meantime: only go if the farm is still waiting
			if (active || resumeSpot == null || !AfkConfig.get().afk || !AfkHelperClient.active()) {
				Exchange.emit("farm", "action", "back home called off");
				return;
			}
			String cmd = Profiles.cmd("home", null);
			if (cmd != null) { AfkHelperClient.homeAsked(home); AfkHelperClient.sendChat(cmd + " " + home); }
		});
	}

	/**
	 * Start of the client tick: this is where vanilla also handles mouse clicks (before the movement of that tick).
	 * Hitting at the end of the tick gives anti-cheat reports ("Post").
	 */
	private static long nextWeaponCheck = 0;

	/**
	 * Hit with the right weapon. The farm hit with whatever happened to be in hand; on the alt that sword
	 * sometimes sits in the backpack and the buddy kept whacking with an axe or bread.
	 */
	private static void ensureWeapon(LocalPlayer p, Minecraft mc, long now) {
		if (now < nextWeaponCheck) return;
		nextWeaponCheck = now + 2000;
		int best = -1; double strongest = -1;
		for (int i = 0; i < 9; i++) {
			net.minecraft.world.item.ItemStack st = p.getInventory().getItem(i);
			if (st.isEmpty() || !st.is(net.minecraft.tags.ItemTags.SWORDS)) continue;
			double strength = st.getDestroySpeed(net.minecraft.world.level.block.Blocks.COBWEB.defaultBlockState());
			if (strength > strongest) { strongest = strength; best = i; }
		}
		var web = net.minecraft.world.level.block.Blocks.COBWEB.defaultBlockState();
		// no sword in the hotbar, or a better one in the backpack (the kit gives stone, the netherite sits in the back)
		double threshold = best < 0 ? 0 : strongest + 0.01;
		if (AfkHelperClient.toHotbar(p, mc, st -> st.is(net.minecraft.tags.ItemTags.SWORDS) && st.getDestroySpeed(web) > threshold,
				java.util.Comparator.comparingDouble(st -> st.getDestroySpeed(web)), "sword")) return;
		if (best < 0) return;
		if (p.getInventory().getSelectedSlot() != best && !AfkHelperClient.isEating()) p.getInventory().setSelectedSlot(best);
	}

	public static void startTick(Minecraft mc) {
		Entity t = pendingTarget; boolean miss = pendingMiss;
		pendingTarget = null; pendingMiss = false;
		LocalPlayer p = mc.player;
		if (!active || p == null || mc.gameMode == null || paused) return;
		if (t != null) {
			if (rayTarget(p) != t) return;   // view direction or mob changed: do not hit
			mc.gameMode.attack(p, t);
			Game.swingAttack(p);
			ClickLog.swing(true);
			hits++;
			hitMobs.put(t.getId(), System.currentTimeMillis());
			lastHitYaw = p.getYRot(); lastHitPitch = p.getXRot();
		} else if (miss) {
			p.resetAttackStrengthTicker();
			Game.swingAttack(p);
			ClickLog.swing(false);
		}
	}

	/**
	 * A human does not click every hit at a different pace, but keeps roughly the same rhythm for
	 * minutes and then gradually slows down or speeds up. Drawing a fresh value per hit gives a
	 * distribution that is too flat, and that is exactly what click detectors look at.
	 */
	private static double clickPace(AfkConfig c, long now) {
		double lo = Math.min(MAX_CPS, Math.max(0.5, c.farmMinCps)), hi = Math.min(MAX_CPS, Math.max(lo + 0.1, c.farmMaxCps));
		if (pace <= 0 || now > paceUntil) {
			double goal = R.nextDouble(lo, hi);
			pace = pace <= 0 ? goal : pace + (goal - pace) * R.nextDouble(0.3, 0.8);   // shift gradually
			paceUntil = now + R.nextLong(25_000, 90_000);
		}
		return Mth.clamp(pace + R.nextGaussian() * 0.15, lo, hi);
	}

	/**
	 * Time until the next hit. A hand does not click symmetrically around an average: most hits lie
	 * close together with the occasional much longer one, and fast and slow hits come in clusters.
	 * Hence log-normal (skewed to the right, never negative) with a bit of memory of the previous
	 * hit, instead of gaussian with a hard lower bound. That old recipe gave a neat symmetric hill
	 * with one truncated side and a stall chance that was exactly 1 in 14 hour after hour: over
	 * thousands of hits that is a signature in itself.
	 */
	private static long hitGap(AfkConfig c, long now, double cooldownMs) {
		if (now > styleUntil) {   // one session you click tighter than another, and that changes along the way
			noise = R.nextDouble(0.15, 0.45);
			stallChance = R.nextDouble(0.08, 0.30);
			missChance = R.nextDouble(0.20, 0.50);
			whiffChance = R.nextDouble(0.02, 0.09);
			earlyChance = R.nextDouble(0.08, 0.22);
			styleUntil = now + R.nextLong(60_000, 300_000);
		}
		double median = 1000.0 / clickPace(c, now);
		// If you wait for full attack strength, the cooldown sets the pace and not your hand. Without this
		// correction 82% of the hits lay within eight milliseconds of 600 ms (measured 25 Sep 2026) - a
		// metronome, while a human player sat at a median of 649 ms with a quarter of the hits below it.
		// So: aim for just after the cooldown with a reaction time that differs per hit, and click too
		// early now and then (that becomes a weaker hit, just like with a human).
		if (cooldownMs > 0) {
			// not at the raid farm: a weaker hit leaves the raider standing and costs a whole extra cooldown
			earlyHit = !raid() && R.nextDouble() < earlyChance;
			earlyThreshold = earlyHit ? (float) R.nextDouble(0.62, 0.90) : 0.95f;
			median = Math.max(1000.0 / MAX_CPS, cooldownMs * (earlyHit ? earlyThreshold + R.nextDouble(0, 0.08) : R.nextDouble(1.02, 1.26)));
		} else { earlyHit = false; earlyThreshold = 0.95f; }
		double dev = 0.55 * prevDeviation + R.nextGaussian() * noise;   // clusters of fast and slow hits
		prevDeviation = dev;
		double gap = median * Math.exp(dev);
		// a moment of inattention: usually short, sometimes a few seconds waiting until something floats by again
		if (R.nextDouble() < stallChance) gap += Math.exp(R.nextGaussian() * 0.7) * R.nextLong(250, 1400);
		double min = 1000.0 / MAX_CPS;                                   // server rule: never unnaturally fast
		if (gap < min) gap = min + (min - gap) * R.nextDouble(0.5, 1.5); // bounce back, do not clip: otherwise
		return (long) gap;                                               // there is a spike exactly at the bound
	}

	/** How long your weapon takes to get back to full strength, in milliseconds (0 = we do not wait). */
	private static double cooldownMs(LocalPlayer p, AfkConfig c) {
		return c.farmWaitCooldown ? p.getCurrentItemAttackStrengthDelay() * 50.0 : 0;
	}

	/** How often you swing at nothing when the mob has just left; that is not a fixed 35% either. */
	private static double missChance() { return missChance; }

	public static boolean active() { return active; }

	/** Is the farm itself sneaking right now? Then eating does not need to add sneaking on top. */
	public static boolean isSneaking() { return sneaking; }

	/**
	 * Walks back to the spot where you stood, without changing your view direction: the movement keys
	 * work relative to your head, so we convert the direction into forward/backward and left/right.
	 * If it does not get closer, it gives up by itself.
	 */
	private static boolean returnToSpot(LocalPlayer p, Minecraft mc, AfkConfig c, long now) {
		double dx = anchor.x - p.getX(), dz = anchor.z - p.getZ();
		double d = Math.hypot(dx, dz);
		var o = mc.options;
		if (d < (returning ? 0.25 : 0.80)) {   // only go back when you really stand beside it, not at every nudge
			if (returning) {
				HeldKeys.set(o.keyUp, false); HeldKeys.set(o.keyDown, false); HeldKeys.set(o.keyLeft, false); HeldKeys.set(o.keyRight, false);
				returning = false; returnDone = now;
				// look at the farm direction again; on the raid or landing spot at its centre
				if (areaOn(p)) look.start(p, new Vec3((aMinX() + aMaxX()) / 2, p.getY() + (raidOn(p) ? c.raidLookY / 100.0 : 0.65), (aMinZ() + aMaxZ()) / 2), 0.8f);
				else { double yr = Math.toRadians(lastHitYaw), pr = Math.toRadians(lastHitPitch);
					look.start(p, p.getEyePosition().add(-Math.sin(yr) * Math.cos(pr) * 5, -Math.sin(pr) * 5, Math.cos(yr) * Math.cos(pr) * 5), 0.8f); }
				nextAttack = Math.max(nextAttack, now + R.nextLong(200, 600));
				Exchange.emit("farm", "action", "back at the spot");
			}
			return false;
		}
		if (!returning) { returning = true; returnSince = now; returnBest = d; Exchange.emit("farm", "action", "back to the spot", "distance", Math.round(d * 100) / 100.0); }
		if (d < returnBest - 0.05) { returnBest = d; returnSince = now; }
		if (now - returnSince > 2500) {   // not getting closer (wall, edge): never mind
			HeldKeys.set(o.keyUp, false); HeldKeys.set(o.keyDown, false); HeldKeys.set(o.keyLeft, false); HeldKeys.set(o.keyRight, false);
			returning = false; returnBlocked = true; setAnchor(p.position());
			Exchange.emit("farm", "action", "way back blocked");
			return false;
		}
		double yaw = Math.toRadians(p.getYRot());
		double forward = -Math.sin(yaw) * dx + Math.cos(yaw) * dz;   // forward vector = (-sin, cos)
		double right = -Math.cos(yaw) * dx - Math.sin(yaw) * dz;     // right is perpendicular to that
		HeldKeys.set(o.keyUp, forward > 0.1); HeldKeys.set(o.keyDown, forward < -0.1);
		HeldKeys.set(o.keyRight, right > 0.1); HeldKeys.set(o.keyLeft, right < -0.1);
		if (c.farmCrouch && !sneaking) { HeldKeys.set(o.keyShift, true); sneaking = true; }   // back while crouched, not off the edge
		return true;
	}

	// interruption (typing, reading, looking at a player): pause until pausedUntil, then turn back to the farm direction
	private static long pausedUntil = 0;
	private static boolean paused = false, chatOpen = false;
	private static float savedYaw, savedPitch;
	private static Entity pauseLookAt = null;

	public static void setChatOpen(boolean open) {
		if (!active) { chatOpen = open; return; }
		if (open && !chatOpen) interrupt(0, null, "chat open");
		if (!open && chatOpen) pausedUntil = Math.max(pausedUntil, System.currentTimeMillis() + R.nextLong(400, 1200));
		chatOpen = open;
	}

	/**
	 * Raiders that kill you within a few seconds if you look away: an evoker spawns vexes,
	 * a witch throws poison. As long as those are around the farm does not pause and you keep hitting.
	 */
	static LivingEntity threat(LocalPlayer p, double radius) {
		if (p == null || p.level() == null) return null;
		LivingEntity nearest = null; double best = radius * radius;
		for (Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(radius))) {
			if (!(e instanceof LivingEntity le) || !le.isAlive()) continue;
			boolean dangerous = le instanceof net.minecraft.world.entity.raid.Raider    // evoker, witch, vindicator, pillager, ravager
				|| le instanceof net.minecraft.world.entity.monster.Vex;
			if (!dangerous) continue;
			double d = p.distanceToSqr(le);
			if (d < best) { best = d; nearest = le; }
		}
		return nearest;
	}

	/** Can you stand here: solid floor, two blocks free and nothing dangerous. */
	private static boolean standable(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
		if (hazard(level, pos) || hazard(level, pos.below())) return false;
		if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) return false;
		if (!level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()) return false;
		return !level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty();
	}

	/**
	 * A spot on another side of the landing spot. The server does not count nudges back and forth on the
	 * same square metre as movement: on 25 Sep the account went to the AFK world in the middle of farming,
	 * exactly five minutes after the last real change of spot. Walking to the far side is a
	 * real move, and from every side you can hit the mobs on the spot equally well.
	 */
	private static Vec3 otherSide(LocalPlayer p) {
		double cx = (aMinX() + aMaxX()) / 2, cz = (aMinZ() + aMaxZ()) / 2;
		double halfX = (aMaxX() - aMinX()) / 2, halfZ = (aMaxZ() - aMinZ()) / 2;
		java.util.List<Vec3> candidates = new java.util.ArrayList<>();
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			double x = cx + d.getStepX() * (halfX + 1.3), z = cz + d.getStepZ() * (halfZ + 1.3);
			Vec3 spot = new Vec3(Math.floor(x) + 0.5, p.getY(), Math.floor(z) + 0.5);
			if (spot.distanceTo(p.position()) < 2.0) continue;   // too close: that is no real move
			if (!standable(p.level(), net.minecraft.core.BlockPos.containing(spot))) continue;
			candidates.add(spot);
		}
		// do not immediately retry the side the buddy just failed to reach, if there is another one
		if (switchFailed != null && candidates.size() > 1) candidates.removeIf(k -> k.distanceTo(switchFailed) < 0.5);
		return candidates.isEmpty() ? null : candidates.get(R.nextInt(candidates.size()));
	}

	/**
	 * Does this segment (with half your body width) cross the landing spot? The first bit does not count:
	 * after approaching you stand within half a metre of the edge, and back then this rejected every route,
	 * so that on 26 Sep from 03:45 to 03:51 the buddy did not switch at all and went to the AFK world.
	 */
	private static boolean crossesArea(Vec3 a, Vec3 b) {
		double m = 0.3, len = Math.hypot(b.x - a.x, b.z - a.z);
		for (int i = 0; i <= 40; i++) {
			if (len * i / 40 < 0.7) continue;
			double x = a.x + (b.x - a.x) * i / 40, z = a.z + (b.z - a.z) * i / 40;
			if (x > aMinX() - m && x < aMaxX() + m && z > aMinZ() - m && z < aMaxZ() + m) return true;
		}
		return false;
	}

	/**
	 * The way to the far side, around the landing spot: straight ahead the buddy walked right across it or tight
	 * along the edge, got stuck and never arrived (on 26 Sep five of the seven switches). Corners lie,
	 * like the sides, one and a half blocks from the edge, in the middle of a block.
	 */
	private static java.util.List<Vec3> routeAround(LocalPlayer p, Vec3 target) {
		Vec3 from = p.position();
		java.util.List<Vec3> corners = new java.util.ArrayList<>();
		for (double x : new double[]{aMinX() - 1.5, aMaxX() + 1.5})
			for (double z : new double[]{aMinZ() - 1.5, aMaxZ() + 1.5}) {
				Vec3 h = new Vec3(x, target.y, z);
				if (standable(p.level(), net.minecraft.core.BlockPos.containing(h))) corners.add(h);
			}
		java.util.List<Vec3> best = crossesArea(from, target) ? null : java.util.List.of(target);
		double bestLen = best == null ? Double.MAX_VALUE : from.distanceTo(target);
		for (Vec3 h : corners) {
			if (!crossesArea(from, h) && !crossesArea(h, target)) {
				double len = from.distanceTo(h) + h.distanceTo(target);
				if (len < bestLen) { bestLen = len; best = java.util.List.of(h, target); }
			}
			for (Vec3 h2 : corners) {
				if (h2 == h || crossesArea(from, h) || crossesArea(h, h2) || crossesArea(h2, target)) continue;
				double len = from.distanceTo(h) + h.distanceTo(h2) + h2.distanceTo(target);
				if (len < bestLen) { bestLen = len; best = java.util.List.of(h, h2, target); }
			}
		}
		return best;
	}

	/** The nearest non-trusted player within the visitor radius, or null. */
	private static net.minecraft.world.entity.player.Player visitor(LocalPlayer p, AfkConfig c) {
		if (c.farmVisitorRadius <= 0) return null;
		net.minecraft.world.entity.player.Player best = null;
		double bestD = (double) c.farmVisitorRadius * c.farmVisitorRadius;
		for (net.minecraft.world.entity.player.Player o : p.level().players()) {
			if (o == p) continue;
			String n = o.getGameProfile().name();
			if (c.trusted.stream().anyMatch(x -> x.equalsIgnoreCase(n))) continue;
			double d = o.distanceToSqr(p);
			if (d < bestD) { bestD = d; best = o; }
		}
		return best;
	}

	/** Interrupt the farm for a while, possibly looking at a player. */
	public static void interrupt(long ms, Entity lookAt, String why) {
		if (!active) return;
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null) return;
		if (visitorQuiet) lookAt = null;   // if someone is standing there watching, you look at nobody
		// only at the raid farm: that is where the mobs stand that kill you in seconds. At the blaze farm
		// they cannot reach you, so there a simple pause as always is fine.
		if (ms > 0 && raidOn(p)) {
			LivingEntity danger = threat(p, THREAT_RADIUS);
			if (danger != null) {
				// not now, but later: between two raids the farm is idle anyway
				deferredMs = Math.max(deferredMs, ms);
				deferredWho = lookAt; deferredWhy = why;
				deferredUntil = System.currentTimeMillis() + 5 * 60_000L;
				Exchange.emit("farm", "action", "pause put off", "why", why, "mob", danger.getType().getDescription().getString());
				return;
			}
		}
		if (!paused) { savedYaw = p.getYRot(); savedPitch = p.getXRot(); paused = true; releaseKeys(); ClickLog.interruption(); Exchange.emit("farm", "action", "pause", "why", why); }
		pausedUntil = Math.max(pausedUntil, System.currentTimeMillis() + ms);
		if (lookAt != null) { pauseLookAt = lookAt; look.start(p, lookAt.getEyePosition(), 2f); }
	}
	public static int hits() { return hits; }
	public static int killed() { return killed; }
	/** How long the farm has been running, as "12m" or "1h 04m". */
	public static String runtime() {
		if (!active || startedAt == 0) return "";
		long m = (System.currentTimeMillis() - startedAt) / 60_000;
		return m < 60 ? String.format(java.util.Locale.ROOT, "%sm", m) : String.format(java.util.Locale.ROOT, "%sh %sm", m / 60, String.format("%02d", m % 60));
	}

	/**
	 * Mobs we hit that died afterwards. The server does not send "you killed X", so we check whether
	 * a mob we hit disappears or is dead; after half a minute without a blow we forget it.
	 */
	private static void countKills(Minecraft mc) {
		if (mc.level == null || hitMobs.isEmpty()) return;
		long now = System.currentTimeMillis();
		var it = hitMobs.entrySet().iterator();
		while (it.hasNext()) {
			var e = it.next();
			var ent = mc.level.getEntity(e.getKey());
			if (ent == null) { killed++; it.remove(); continue; }
			if (ent instanceof LivingEntity le && (le.isDeadOrDying() || !le.isAlive())) { killed++; it.remove(); continue; }
			if (now - e.getValue() > 30_000) it.remove();
		}
	}

	public static void start() {
		Jobs.switchedOn(Jobs.Job.MOB_FARM, "mob farm on");
		visitorUntil = 0; nextSession = 0; visitorQuiet = false; nextSideSwitch = 0; sideSwitching = false; switchRoute.clear(); switchFailed = null;
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null) return;
		nextBreak = 0; lastMobSeen = 0;
		if (raidOn(p)) RaidState.newSession();
		active = true; startedAt = System.currentTimeMillis(); start = p.position(); anchor = start; lastPos = start; hits = 0; killed = 0; hitMobs.clear(); approaching = false; stuckTicks = 0; nextApproach = 0;
		lastHitYaw = p.getYRot(); lastHitPitch = p.getXRot(); lastTargetAt = System.currentTimeMillis();
		lastBlock = p.blockPosition(); lastBlockChange = System.currentTimeMillis(); bigStep = false;
		nextWalk = System.currentTimeMillis() + (long) (R.nextDouble(0.6, 1.4) * Math.max(30, AfkConfig.get().farmWalkEverySec) * 1000);
		long now = System.currentTimeMillis();
		nextAttack = now + 300; nextNudge = now + nudgeDelay(); nextJitter = now + R.nextLong(2000, 6000);
		Exchange.emit("farm", "action", "start");
	}

	/** Is the farm waiting for you to get back on the farm spot? Then do not wander around. */
	public static boolean waitingToResume() {
		return resumeSpot != null && !active;
	}

	/**
	 * Once per second: if the farm is off after an involuntary stop and you stand still on the
	 * spot again, it turns back on. Standing still is the condition, otherwise it starts in the
	 * middle of a teleport or while you walk away yourself.
	 */
	public static void resumeTick(LocalPlayer p, AfkConfig c) {
		if (active || resumeSpot == null) return;
		long now = System.currentTimeMillis();
		if (!c.afk || now > resumeBefore) { resumeSpot = null; return; }
		if (stillAt == null || p.position().distanceTo(stillAt) > 0.5) { stillAt = p.position(); stillSince = now; }
		// also next to the landing spot itself: your home is not necessarily within 2.5 blocks of where the
		// farm started, and then the farm simply stayed off after the AFK world and /home
		boolean atSpot = p.position().distanceTo(resumeSpot) <= 2.5 || (areaOn(p) && !raidOn(p) && distToArea(p) <= 3.0);
		if (!atSpot || now - stillSince < 5_000 || now < resumeNotBefore) return;
		if (++resumeAttempts > 5) {   // if it keeps going wrong, something else is the matter
			resumeSpot = null;
			Exchange.emit("farm", "action", "resume given up");
			return;
		}
		resumeNotBefore = now + 60_000;
		Exchange.emit("farm", "action", "resumed", "attempt", resumeAttempts);
		Vec3 spot = resumeSpot; resumeSpot = null;
		start();
		if (!(areaOn(p) && !raidOn(p))) { start = spot; anchor = spot; }   // keep the old spot, not where you happened to land
	}

	public static void stop(String why) {
		if (!active) return;
		long now = System.currentTimeMillis();
		if (involuntary(why) && AfkConfig.get().afk && start != null) {
			// a round that simply ran counts as a success: then the counter may go back to zero.
			// Otherwise every resume would acquit itself and the farm keeps restarting forever.
			if (now - startedAt > 10 * 60_000L) resumeAttempts = 0;
			resumeSpot = start; resumeBefore = now + 3 * 60 * 60_000L; resumeNotBefore = 0;
		} else { resumeSpot = null; resumeAttempts = 0; }
		if (resumeSpot != null && teleportedAway(why)) backToHome();
		String ran = runtime();   // before active goes false: runtime() is empty once the farm is off
		countKills(Minecraft.getInstance());   // mobs hit in the last seconds still count
		active = false; returning = false; pace = 0; deferredMs = 0; deferredWho = null; releaseKeys(); pendingTarget = null; pendingMiss = false;
		ClickLog.interruption(); ClickLog.flush();
		Exchange.emit("farm", "action", "stop", "why", why, "hits", hits, "killed", killed, "runtime", ran);
	}

	private static void releaseKeys() {
		var o = Minecraft.getInstance().options;
		if (returning) { HeldKeys.set(o.keyUp, false); HeldKeys.set(o.keyDown, false); HeldKeys.set(o.keyLeft, false); HeldKeys.set(o.keyRight, false); returning = false; }
		if (approaching) { HeldKeys.set(Minecraft.getInstance().options.keyUp, false); approaching = false; }
		if (sneaking) { HeldKeys.set(Minecraft.getInstance().options.keyShift, false); sneaking = false; }
		if (nudgeKey != null) HeldKeys.set(nudgeKey, false);
		if (backKey != null) HeldKeys.set(backKey, false);
		nudgeTicks = 0; nudgePhase = 0;
	}

	private static long nudgeDelay() { int s = Math.max(3, AfkConfig.get().farmNudgeEverySec); return (long) (R.nextDouble(s * 0.5, s * 1.5) * 1000); }

	/** Water, lava or a block that hurts. */
	static boolean hazard(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
		var st = level.getBlockState(pos);
		if (!st.getFluidState().isEmpty()) return true;
		return st.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK) || st.is(net.minecraft.world.level.block.Blocks.CAMPFIRE) || st.is(net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE)
			|| st.is(net.minecraft.world.level.block.Blocks.FIRE) || st.is(net.minecraft.world.level.block.Blocks.SOUL_FIRE) || st.is(net.minecraft.world.level.block.Blocks.CACTUS)
			|| st.is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH) || st.is(net.minecraft.world.level.block.Blocks.WITHER_ROSE) || st.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW);
	}

	/** Would you fall down there? (no collision shape at foot, below and two below) */
	static boolean dropAhead(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
		for (int dy = 0; dy >= -2; dy--) {
			var q = pos.offset(0, dy, 0);
			if (!level.getBlockState(q).getCollisionShape(level, q).isEmpty()) return false;
		}
		return true;
	}

	/** Kind of mob we may hit (without reach check). */
	static boolean candidate(Entity e) {
		if (!(e instanceof LivingEntity le) || !le.isAlive() || le.isDeadOrDying()) return false;
		if (e instanceof Player || e instanceof TamableAnimal || e instanceof AbstractVillager || e instanceof ArmorStand) return false;
		return !e.hasCustomName() && e instanceof Enemy;
	}

	private static Vec3 prevTickPos;
	private static long prevTickAt = 0;
	private static double lastMoveSq(LocalPlayer p) { double d = prevTickPos == null ? 1 : p.position().distanceToSqr(prevTickPos); return d; }

	/** Landing spot configured and in the same world? */
	private static double distance(LocalPlayer p, double x1, double x2, double z1, double z2) {
		double cx = Mth.clamp(p.getX(), x1, x2), cz = Mth.clamp(p.getZ(), z1, z2);
		return Math.hypot(p.getX() - cx, p.getZ() - cz);
	}

	/** Raid spot configured, in this world, and closer than the landing spot? */
	static boolean raidOn(LocalPlayer p) {
		AfkConfig c = AfkConfig.get();
		if (!c.raidMode || c.raidDim == null || !c.raidDim.equals(p.level().dimension().identifier().toString())) return false;
		double dRaid = distance(p, c.raidX, c.raidX + 1, c.raidZ, c.raidZ + 1);
		if (dRaid > 24) return false;
		boolean blaze = c.farmUseArea && c.farmAreaDim != null && c.farmAreaDim.equals(p.level().dimension().identifier().toString());
		if (!blaze) return true;
		double dBlaze = distance(p, Math.min(c.farmAreaX1, c.farmAreaX2), Math.max(c.farmAreaX1, c.farmAreaX2) + 1,
			Math.min(c.farmAreaZ1, c.farmAreaZ2), Math.max(c.farmAreaZ1, c.farmAreaZ2) + 1);
		return dRaid <= dBlaze;   // you are at the raid farm
	}

	/** Landing spot or raid spot active (which of the two is decided by raidOn). */
	static boolean areaOn(LocalPlayer p) {
		AfkConfig c = AfkConfig.get();
		if (raidOn(p)) return true;
		if (!(c.farmUseArea && c.farmAreaDim != null && c.farmAreaDim.equals(p.level().dimension().identifier().toString()))) return false;
		return distance(p, Math.min(c.farmAreaX1, c.farmAreaX2), Math.max(c.farmAreaX1, c.farmAreaX2) + 1,
			Math.min(c.farmAreaZ1, c.farmAreaZ2), Math.max(c.farmAreaZ1, c.farmAreaZ2) + 1) <= 24;
	}
	private static boolean raid() { LocalPlayer p = Minecraft.getInstance().player; return p != null && raidOn(p); }
	static double aMinX() { AfkConfig c = AfkConfig.get(); return raid() ? c.raidX : Math.min(c.farmAreaX1, c.farmAreaX2); }
	static double aMaxX() { AfkConfig c = AfkConfig.get(); return raid() ? c.raidX + 1 : Math.max(c.farmAreaX1, c.farmAreaX2) + 1; }
	static double aMinZ() { AfkConfig c = AfkConfig.get(); return raid() ? c.raidZ : Math.min(c.farmAreaZ1, c.farmAreaZ2); }
	static double aMaxZ() { AfkConfig c = AfkConfig.get(); return raid() ? c.raidZ + 1 : Math.max(c.farmAreaZ1, c.farmAreaZ2) + 1; }

	/** Is the mob (with its hitbox) above the landing spot? */
	static boolean inArea(Entity e, LocalPlayer p) {
		if (!areaOn(p)) return true;
		AABB b = e.getBoundingBox();
		return b.maxX > aMinX() && b.minX < aMaxX() && b.maxZ > aMinZ() && b.minZ < aMaxZ() && b.minY < p.getEyeY() + 3 && b.maxY > p.getY() - 4;
	}

	/** Horizontal distance from your centre to the edge of the landing spot (0 = above it). */
	static double distToArea(LocalPlayer p) {
		double cx = Mth.clamp(p.getX(), aMinX(), aMaxX()), cz = Mth.clamp(p.getZ(), aMinZ(), aMaxZ());
		return Math.hypot(p.getX() - cx, p.getZ() - cz);
	}

	/** A point on the hitbox you can really see from your eyes (through the half-slab gap), or null. */
	static Vec3 aimPoint(Entity e, LocalPlayer p) {
		return aimPoint(e.getBoundingBox(), p.getEyePosition(), p.level(), p, raidOn(p), 8, null);
	}

	/**
	 * The same for any box: its points in this order, within reach of the eye and seen past what a crosshair stops at.
	 * Without {@code view} the first such point; with it the one nearest to where the buddy looks (the pen, 0.4.80, which turns
	 * as little as it can between two mobs).
	 */
	static Vec3 aimPoint(AABB b, Vec3 eye, net.minecraft.world.level.Level level, Entity viewer, boolean low, double reach, Vec3 view) {
		double[] ys = low ? new double[]{0.45, 0.3, 0.6, 0.2, 0.75, 0.1, 0.9} : new double[]{0.5, 0.3, 0.7, 0.15, 0.85, 0.05, 0.95};
		double[] os = {0, 0.2, -0.2};
		Vec3 best = null;
		double bestDot = -2;
		for (double fy : ys) for (double ox : os) for (double oz : os) {
			Vec3 pt = new Vec3((b.minX + b.maxX) / 2 + ox * (b.maxX - b.minX), b.minY + fy * (b.maxY - b.minY), (b.minZ + b.maxZ) / 2 + oz * (b.maxZ - b.minZ));
			if (eye.distanceTo(pt) > reach) continue;
			var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, pt, net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, viewer));
			if (hit.getType() != HitResult.Type.MISS && hit.getLocation().distanceTo(eye) < eye.distanceTo(pt) - 0.05) continue;
			if (view == null) return pt;
			double dot = pt.subtract(eye).normalize().dot(view);
			if (dot > bestDot) { bestDot = dot; best = pt; }
		}
		return best;
	}

	/** What your crosshair hits right now within attack reach (first mob along your view direction, not behind a block). */
	static Entity rayTarget(LocalPlayer p) {
		Vec3 eye = p.getEyePosition(), view = p.getViewVector(1f);
		double reach = p.entityInteractionRange();
		Vec3 end = eye.add(view.scale(reach));
		var bh = p.level().clip(new net.minecraft.world.level.ClipContext(eye, end, net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
		double blockDist = bh.getType() == HitResult.Type.MISS ? reach : bh.getLocation().distanceTo(eye);
		Entity best = null; double bestDist = blockDist;
		for (LivingEntity e : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().expandTowards(view.scale(reach)).inflate(1), x -> valid(x, p))) {
			var hit = e.getBoundingBox().clip(eye, end);
			if (hit.isPresent()) { double d = hit.get().distanceTo(eye); if (d < bestDist) { bestDist = d; best = e; } }
		}
		return best;
	}

	/** Visible: a bit of the mob can be seen, and it floats roughly on the side of the farm. */
	static boolean visible(Entity e, LocalPlayer p) {
		if (aimPoint(e, p) == null) return false;
		if (areaOn(p)) return inArea(e, p);
		double dx = e.getX() - p.getX(), dz = e.getZ() - p.getZ();
		float yawTo = (float) Math.toDegrees(Math.atan2(-dx, dz));
		return Math.abs(Mth.wrapDegrees(yawTo - lastHitYaw)) < 110;   // roughly towards the farm, height does not matter
	}

	/** May this entity be hit now? */
	static boolean valid(Entity e, LocalPlayer p) {
		if (!candidate(e) || !inArea(e, p)) return false;
		return p.getEyePosition().distanceTo(closest(e.getBoundingBox(), p.getEyePosition())) <= p.entityInteractionRange() - 0.1;
	}

	private static Vec3 closest(AABB b, Vec3 v) {
		return new Vec3(Mth.clamp(v.x, b.minX, b.maxX), Mth.clamp(v.y, b.minY, b.maxY), Mth.clamp(v.z, b.minZ, b.maxZ));
	}


	/**
	 * Nudges there and back. With antiAfkOnly it only takes a real step when you have stood on the same
	 * block so long that the server is about to consider you AFK - that is what still has to happen during
	 * a pause, without hitting and without changing your view direction. A running nudge is always
	 * finished, otherwise a movement key stays pressed.
	 */
	private static boolean nudges(LocalPlayer p, Minecraft mc, AfkConfig c, long now, boolean antiAfkOnly) {
		if (!c.farmNudge) return false;
		boolean self = false;
		// nextBigStep: if a big step failed (wall or edge behind you), the AFK limit stayed exceeded and the
		// next one started right away; so the buddy shot back and forth on the same block about five times a second
		boolean afkLimit = now - lastBlockChange > Math.max(20, c.farmBlockMoveSec) * 1000L && now >= nextBigStep;
		boolean needBlock = afkLimit || now >= nextWalk;
		boolean may = antiAfkOnly ? afkLimit : (now >= nextNudge || needBlock);
		if (nudgePhase == 0 && may && !approaching) {
			var o = mc.options;
			net.minecraft.client.KeyMapping[][] pairs = areaOn(p) ? new net.minecraft.client.KeyMapping[][]{{o.keyDown, o.keyUp}}
				: c.farmApproach ? new net.minecraft.client.KeyMapping[][]{{o.keyLeft, o.keyRight}, {o.keyRight, o.keyLeft}} : new net.minecraft.client.KeyMapping[][]{{o.keyLeft, o.keyRight}, {o.keyRight, o.keyLeft}, {o.keyDown, o.keyUp}};
			bigStep = needBlock || antiAfkOnly;
			if (areaOn(p) && c.farmCrouch && !bigStep && !sneaking) { HeldKeys.set(o.keyShift, true); sneaking = true; }   // controlled, not sliding onto the slabs
			if (bigStep && sneaking) { HeldKeys.set(o.keyShift, false); sneaking = false; }
			var pair = pairs[R.nextInt(pairs.length)];
			nudgeKey = pair[0]; backKey = pair[1];
			if (bigStep) nextWalk = now + (long) (R.nextDouble(0.6, 1.4) * Math.max(30, c.farmWalkEverySec) * 1000);
			nudgeTicks = bigStep ? R.nextInt(9, 15) : R.nextInt(2, 5);   // big step = well over a block, counts as movement for the server
			nudgePhase = 1; stepTicks = 0; HeldKeys.set(nudgeKey, true);
			crouchUntil = now + R.nextLong(Math.max(1, c.farmCrouchMinSec) * 1000L, Math.max(c.farmCrouchMinSec + 1, c.farmCrouchMaxSec) * 1000L);
		} else if (nudgePhase == 1 && (--nudgeTicks <= 0 || (bigStep && ++stepTicks > 3 && lastMoveSq(p) < 0.0004))) {   // let it get going first
			HeldKeys.set(nudgeKey, false); HeldKeys.set(backKey, true); nudgePhase = 2; nudgeTicks = bigStep ? R.nextInt(9, 16) : R.nextInt(2, 5);
			if (bigStep && areaOn(p) && c.farmCrouch) { HeldKeys.set(mc.options.keyShift, true); sneaking = true; }   // back to the edge: controlled again
		} else if (nudgePhase == 2 && (--nudgeTicks <= 0 || p.position().distanceTo(anchor) < 0.15 || (areaOn(p) && distToArea(p) < 0.45))) {
			if (bigStep) nextBigStep = now + nudgeDelay();
			HeldKeys.set(backKey, false); nudgePhase = 0; nextNudge = now + nudgeDelay(); bigStep = false;
		}
		if (nudgePhase != 0) self = true;
		// stay crouched for a while after the nudge (more natural than flashing for half a second)
		if (sneaking && !approaching && nudgePhase == 0 && now >= crouchUntil) { HeldKeys.set(mc.options.keyShift, false); sneaking = false; }
		else if (sneaking) self = true;
		return self;
	}

	/**
	 * A move that did not come from you: knockback from someone checking whether you react. Runs every tick,
	 * also during a pause, and compares only with the previous tick: this check used to sit at the bottom of
	 * tick() and was skipped during a pause, so that after a six-second breather it saw the whole displacement
	 * of those six seconds as one push (false alarm of 2.3 blocks on 25 Sep 09:28).
	 * Above two blocks it is not a push but a teleport or a position correction from the server.
	 */
	private static void pushCheck(LocalPlayer p, Minecraft mc, long now) {
		if (prevTickPos != null && now - prevTickAt <= 120 && !raidOn(p) && nudgePhase == 0 && !approaching
				&& !returning && !p.isPassenger() && p.onGround()) {
			var o = mc.options;
			boolean keysDown = o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown() || o.keyRight.isDown() || o.keyJump.isDown();
			double dxz = Math.hypot(p.getX() - prevTickPos.x, p.getZ() - prevTickPos.z);
			if (!keysDown && dxz > 0.45 && dxz < 2.0) Watchdog.pushed(p, dxz);
		}
		prevTickPos = p.position();
		prevTickAt = now;
	}

	/**
	 * Every few minutes go and stand on the other side of the landing spot: that is the only movement
	 * the server's AFK counter does see (nudges on your own block do not count). True as long as
	 * it is walking. Also during a pause: on 26 Sep at 05:47 a long pause started right after a switch,
	 * in it the buddy only did nudges on one block, and at 05:49 stood in the AFK world.
	 */
	private static boolean sideSwitch(LocalPlayer p, Minecraft mc, AfkConfig c, long now, boolean may) {
		if (may && c.farmSideSwitchSec > 0 && areaOn(p) && !raidOn(p) && !sideSwitching && now >= nextSideSwitch) {
			nextSideSwitch = now + (long) (R.nextDouble(0.7, 1.3) * Math.max(30, c.farmSideSwitchSec) * 1000);
			Vec3 next = otherSide(p);
			java.util.List<Vec3> route = next == null ? null : routeAround(p, next);
			if (route == null) {   // do not fail silently: then you only notice at the AFK world that something is wrong
				Exchange.emit("farm", "action", "other side: no way", "to", next == null ? "no spot" : Math.round(next.x) + "," + Math.round(next.z));
				switchFailed = next;
				nextSideSwitch = now + R.nextLong(15_000, 40_000);
			} else {
				// set the anchor directly, not via setAnchor: that bounded the far side to 2 blocks from your
				// start, so the buddy walked to a point halfway and got stuck there
				switchRoute.clear(); switchRoute.addAll(route); anchor = switchRoute.poll(); switchTarget = next;
				returnBlocked = false; returning = false;
				sideSwitching = true;
				nextApproach = now + 4000;   // no approaching for a moment: that pulls you straight back to the old edge
				Exchange.emit("farm", "action", "other side", "to", Math.round(next.x) + "," + Math.round(next.z));
			}
		}
		if (sideSwitching) {
			if (returnToSpot(p, mc, c, now)) return true;
			if (!returnBlocked && !switchRoute.isEmpty()) {   // waypoint reached: on to the next
				anchor = switchRoute.poll(); returning = true; returnSince = now; returnBest = p.position().distanceTo(anchor);
				return true;
			}
			if (returnBlocked) {
				// a failed switch does not count as movement for the server; waiting for the next round
				// left you standing on one spot for seven minutes, and then you went to the AFK world
				switchFailed = switchTarget;
				nextSideSwitch = now + R.nextLong(15_000, 40_000);
			} else switchFailed = null;
			switchRoute.clear(); returnBlocked = false;
			sideSwitching = false;
			nextApproach = Math.max(nextApproach, now + R.nextLong(500, 1500));
		}
		return false;
	}

	/** Every tick. True if the mod itself moves/turns (does not count as presence). */
	public static boolean tick(LocalPlayer p, AfkConfig c) {
		if (!active) return false;
		Minecraft mc = Minecraft.getInstance();
		if (p.isDeadOrDying()) { stop("dead"); return false; }
		if (lastPos != null && p.position().distanceTo(lastPos) > 2.5) { stop("teleported"); return false; }
		lastPos = p.position();
		if (p.position().distanceTo(start) > c.farmApproachRange + 4) { stop("too far from the farm spot"); return false; }
		if (!AfkHelperClient.active()) { stop("another server"); return false; }
		boolean self = look.tick(p);
		if (p.containerMenu != p.inventoryMenu || p.isUsingItem()) { releaseKeys(); return self; }   // menu open or eating: pause
		long now = System.currentTimeMillis();
		countKills(mc);
		ensureWeapon(p, mc, now);
		pushCheck(p, mc, now);
		if (lastBlock == null || !p.blockPosition().equals(lastBlock)) { lastBlock = p.blockPosition(); lastBlockChange = now; }
		if (chatOpen && !paused) { savedYaw = p.getYRot(); savedPitch = p.getXRot(); paused = true; releaseKeys(); }   // menu or chat open: idle
		// As long as someone stands next to you, you do nothing. Pausing three seconds and then hammering on
		// while that person watches is exactly what a helper waits for; a human stops until the visitor is gone.
		net.minecraft.world.entity.player.Player watcher = visitor(p, c);
		if (watcher != null) {
			visitorUntil = now + Math.max(1, c.farmVisitorAfterSec) * 1000L + R.nextLong(0, 8000);
			if (!paused) {
				visitorQuiet = true;
				interrupt(0, null, "visitor " + watcher.getGameProfile().name());
			}
			visitorQuiet = true;
			pauseLookAt = null;            // do not look at the visitor: looking up without saying anything back is what stands out
			look.stop();                   // abort a turn that was still running right away
			pausedUntil = Math.max(pausedUntil, now + 2000);
		} else if (paused && now < visitorUntil) {
			pausedUntil = Math.max(pausedUntil, visitorUntil);   // just left: do not start again right away
		}
		if (paused && raidOn(p) && threat(p, THREAT_RADIUS) != null) {
			pausedUntil = 0;   // a threat is coming while the buddy stands still: continue right away
			Exchange.emit("farm", "action", "pause broken off", "why", "danger");
		}
		if (paused) {
			if (chatOpen || now < pausedUntil) {
				// With a visitor nothing at all: no looking, no turning. Looking up and then saying nothing back is a
				// giveaway; standing still is what a human does who is away for a bit.
				if (!visitorQuiet && pauseLookAt != null && pauseLookAt.isAlive() && !look.busy() && p.distanceTo(pauseLookAt) < 32 && R.nextInt(8) == 0)
					look.start(p, pauseLookAt.getEyePosition(), 1.5f);
				// but do change block in time: otherwise the server itself puts you on AFK after a minute or so
				// and in the middle of a long pause you suddenly stand in the AFK world.
				if ((chatOpen || visitorQuiet) && sideSwitching) {   // in the middle of a switch: let go, otherwise the buddy keeps walking
					releaseKeys(); sideSwitching = false; switchRoute.clear(); nextSideSwitch = now + R.nextLong(15_000, 40_000);
				}
				if (!chatOpen && !visitorQuiet && nudgePhase == 0 && sideSwitch(p, mc, c, now, true)) return true;
				if (!sideSwitching && nudges(p, mc, c, now, true)) return true;
				return true;
			}
			visitorQuiet = false;
			// pause over: back to the farm direction and after a short run-up carry on
			paused = false; pauseLookAt = null;
			double yr = Math.toRadians(lastHitYaw), pr = Math.toRadians(lastHitPitch);
			look.start(p, p.getEyePosition().add(-Math.sin(yr) * Math.cos(pr) * 5, -Math.sin(pr) * 5, Math.cos(yr) * Math.cos(pr) * 5), 1f);
			nextAttack = now + R.nextLong(300, 900);
			nextApproach = now + R.nextLong(800, 2000);
			Exchange.emit("farm", "action", "on");
			return true;
		}

		// catch up on the deferred pause as soon as it is quiet: no raid in progress and no dangerous mobs left
		if (deferredMs > 0 && !paused && nudgePhase == 0 && !approaching) {
			if (now > deferredUntil) { deferredMs = 0; deferredWho = null; }
			else if (!RaidState.inProgress() && threat(p, THREAT_RADIUS) == null) {
				long ms = deferredMs; Entity who = deferredWho; String why = deferredWhy;
				deferredMs = 0; deferredWho = null;
				boolean near = who != null && who.isAlive() && p.distanceTo(who) < 32;
				interrupt(ms, near ? who : null, why + " (caught up)");
				return true;
			}
		}

		if (sideSwitch(p, mc, c, now, !paused && nudgePhase == 0 && !approaching)) { prevTickPos = p.position(); prevTickAt = now; return true; }

		// approaching: with a landing spot up to just before the edge (never onto it, slabs can be stepped on); otherwise to the nearest mob
		if (c.farmApproach && !raidOn(p) && nudgePhase == 0 && !sideSwitching) {
			boolean area = areaOn(p);
			double gx, gz, dh; boolean have;
			if (area) {
				gx = Mth.clamp(p.getX(), aMinX(), aMaxX()); gz = Mth.clamp(p.getZ(), aMinZ(), aMaxZ());
				dh = Math.hypot(p.getX() - gx, p.getZ() - gz);
				have = dh > 0.45;
			} else {
				LivingEntity mob = p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(c.farmApproachRange), e -> candidate(e) && Math.abs(e.getY() - p.getY()) < 4 && visible(e, p)).stream()
					.min(Comparator.comparingDouble(e -> e.distanceToSqr(p))).orElse(null);
				gx = mob == null ? 0 : mob.getX(); gz = mob == null ? 0 : mob.getZ();
				dh = mob == null ? 0 : Math.hypot(gx - p.getX(), gz - p.getZ());
				have = mob != null && dh > 0.9;
			}
			boolean want = have && now >= nextApproach && p.onGround();
			if (want) {
				double yawDeg = Math.toDegrees(Math.atan2(-(gx - p.getX()), gz - p.getZ()));
				double rad = Math.toRadians(yawDeg);
				net.minecraft.core.BlockPos ahead = net.minecraft.core.BlockPos.containing(p.getX() - Math.sin(rad) * 0.8, p.getY() + 0.01, p.getZ() + Math.cos(rad) * 0.8);
				boolean hazard = hazard(p.level(), ahead) || hazard(p.level(), ahead.below());
				boolean edge = !hazard && dropAhead(p.level(), ahead);
				if (hazard) { want = false; stuckTicks = 0; setAnchor(p.position()); nextApproach = now + R.nextLong(3000, 6000); }
				else {
					// sneak near an edge, and at the landing spot on the last metre (stop precisely, do not slide on)
					boolean slow = edge || (area && dh < 1.3);
					if (slow && c.farmCrouch && !sneaking) { HeldKeys.set(mc.options.keyShift, true); sneaking = true; }
					float delta = Mth.wrapDegrees((float) yawDeg - p.getYRot());
					MouseTurn.toward(p, p.getYRot() + Mth.clamp(delta, -9f, 9f) + (float) R.nextDouble(-0.5, 0.5), p.getXRot());
					if (Math.abs(delta) < 35) { HeldKeys.set(mc.options.keyUp, true); approaching = true; }
					if (approaching && lastMoveSq(p) < 0.0004) { if (++stuckTicks > 6) { HeldKeys.set(mc.options.keyUp, false); approaching = false; stuckTicks = 0; setAnchor(p.position()); nextApproach = now + R.nextLong(2000, 5000);
						if (sneaking) { HeldKeys.set(mc.options.keyShift, false); sneaking = false; } } }
					else stuckTicks = 0;
					self = true;
				}
			}
			if (!want && approaching) {
				HeldKeys.set(mc.options.keyUp, false); approaching = false; stuckTicks = 0;
				if (!have) { setAnchor(p.position()); nextApproach = now + R.nextLong(2000, 5000); }
			}
			if (!approaching && sneaking && stuckTicks == 0 && nudgePhase == 0 && now >= crouchUntil) { HeldKeys.set(mc.options.keyShift, false); sneaking = false; }
		}

		// target: crosshair, otherwise the nearest valid mob within reach (then look towards it smoothly)
		Entity target = rayTarget(p);   // own crosshair ray: also works with the Esc menu open, takes blocks into account
		boolean searching = false, raidSpot = raidOn(p);
		// At the raid farm the raider comes out from behind a block. Hitting on the very first tick it shows
		// went in while the block was still in the way on the server, and the hit was lost. Like a human, react
		// first: the mob has to stay under the crosshair for a moment before the first blow.
		boolean settled = true;
		if (target == null) seenTarget = null;
		else if (raidSpot) {
			if (target != seenTarget) { seenTarget = target; seenSince = now; reaction = R.nextLong(150, 320); }
			settled = now - seenSince >= reaction;
		}
		if (target != null) lastTargetAt = now;
		else if (!look.busy()) {
			double r = p.entityInteractionRange() + 1;
			LivingEntity aim = p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(r), e -> valid(e, p) && aimPoint(e, p) != null).stream()
				.min(Comparator.comparingDouble(e -> e.distanceToSqr(p))).orElse(null);
			if (aim == null)   // nothing within attack reach: look at the nearest visible mob in the farm, even if it floats high or low
				aim = p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(c.farmApproachRange), e -> candidate(e) && visible(e, p)).stream()
					.min(Comparator.comparingDouble(e -> e.distanceToSqr(p))).orElse(null);
			Vec3 pt = aim == null ? null : aimPoint(aim, p);
			if (pt != null) {
				look.start(p, pt, 0.6f);   // a little spread: the visible bit is only half a block
				searching = true;
			} else if (hits == 0 && areaOn(p) && now - lastTargetAt > R.nextLong(1200, 2500)) {
				// never hit anything yet: look at the centre of the spot. Blazes float in the gap above the slab,
				// raiders stand a block lower, so there you look slightly down.
				double height = raidOn(p) ? c.raidLookY / 100.0 : 0.65;
				look.start(p, new Vec3((aMinX() + aMaxX()) / 2, p.getY() + height, (aMinZ() + aMaxZ()) / 2), 1f);
				lastTargetAt = now; searching = true;
			} else if (now - lastTargetAt > R.nextLong(1200, 2500)) {
				// nothing for a while: back to the direction where the last hit landed
				double yr = Math.toRadians(lastHitYaw + R.nextDouble(-3, 3)), pr = Math.toRadians(Mth.clamp(lastHitPitch + R.nextDouble(-2, 2), -89, 89));
				look.start(p, p.getEyePosition().add(-Math.sin(yr) * Math.cos(pr) * 5, -Math.sin(pr) * 5, Math.cos(yr) * Math.cos(pr) * 5), 0.5f);
				lastTargetAt = now; searching = true;
			}
		}

		boolean foodInHand = p.getMainHandItem().get(net.minecraft.core.component.DataComponents.FOOD) != null;   // AutoEat just switched to food
		if (target != null) lastMobSeen = now;
		// Not at the raid farm while a raider is still within reach: then it has not floated away but is only
		// behind the block for a moment, and the swing at nothing reset the attack strength just before it came
		// out (93 of the 171 misses on 28 Sep were followed within 1.5 s by a real hit).
		boolean hidden = raidSpot && target == null && !p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(p.entityInteractionRange() + 1), e -> valid(e, p)).isEmpty();
		if (target == null && !hidden && !foodInHand && now >= nextAttack && now - lastMobSeen < 700 && R.nextDouble() < missChance()) {
			// click where the blaze just was: vanilla then only sends the swing and resets your attack strength
			pendingMiss = true;   // execute at the start of the next tick, like a real click
			nextAttack = now + hitGap(c, now, cooldownMs(p, c));
		}
		// A human does not farm eight hours straight: now and then you really go away for a bit. Without those
		// long gaps a session of half a day is the clearest sign that nobody is behind it.
		if (c.farmSessionMin > 0 && !raidOn(p)) {
			long between = (long) (R.nextDouble(0.7, 1.3) * Math.max(5, c.farmSessionMin) * 60_000);
			if (nextSession == 0) nextSession = now + between;
			else if (now >= nextSession && !approaching && nudgePhase == 0) {
				nextSession = now + between;
				long ms = R.nextLong(120_000, 480_000);
				interrupt(ms, null, "long break");
				return true;
			}
		}
		// now and then nothing at all: a breather, sometimes with a glance to the side
		if (nextBreak == 0) nextBreak = now + R.nextLong(90_000, 360_000);
		if (now >= nextBreak && !approaching && nudgePhase == 0 && !raidOn(p)) {
			nextBreak = now + R.nextLong(90_000, 360_000);
			long ms = R.nextInt(4) == 0 ? R.nextLong(8_000, 20_000) : R.nextLong(2_000, 7_000);
			interrupt(ms, null, "breather");
			if (R.nextInt(3) == 0) {
				double yr = Math.toRadians(p.getYRot() + R.nextDouble(-120, 120)), pr = Math.toRadians(Mth.clamp(p.getXRot() + R.nextDouble(-20, 20), -60, 60));
				look.start(p, p.getEyePosition().add(-Math.sin(yr) * Math.cos(pr) * 5, -Math.sin(pr) * 5, Math.cos(yr) * Math.cos(pr) * 5), 2f);
			}
			return true;
		}
		if (target != null && settled && !foodInHand && now >= nextAttack && (!c.farmWaitCooldown || p.getAttackStrengthScale(0.5f) >= earlyThreshold)) {
			// Now and then just beside it, even when the mob is there. Every swing landing is a signal in itself:
			// a hand sometimes clicks too early or just as the blaze drifts away. Only swing, no attack.
			// Not at the raid farm: a raider stands still on one block, and there you simply hit it the first time.
			if (!raidSpot && R.nextDouble() < whiffChance) pendingMiss = true;
			else pendingTarget = target;   // execute at the start of the next tick, like a real click
			nextAttack = now + hitGap(c, now, cooldownMs(p, c));
		}

		// small head movement now and then
		if (target == null && !searching && now >= nextJitter && !look.busy()) {
			nextJitter = now + R.nextLong(2500, 8000);
			double yaw = Math.toRadians(lastHitYaw + R.nextDouble(-6, 6)), pitch = Math.toRadians(Mth.clamp(lastHitPitch + R.nextDouble(-4, 4), -80, 80));
			look.start(p, p.getEyePosition().add(-Math.sin(yaw) * Math.cos(pitch) * 5, -Math.sin(pitch) * 5, Math.cos(yaw) * Math.cos(pitch) * 5), 0.5f);
		}

		// Back to your spot. The return leg of a nudge ends on a counter, so a remainder is left every
		// time; on the raid spot (one block) you lose your view after a while because of that.
		// Only on the raid spot (one block) and not too often: at the blaze farm the approaching already
		// steers, and then it became twenty nudges a minute back and forth, which looks odd to anyone watching.
		if (c.farmRecenter && raidOn(p) && nudgePhase == 0 && !approaching && anchor != null
				&& (returning || now - returnDone > 15_000) && returnToSpot(p, mc, c, now)) self = true;

		// nudge there and back
		if (nudges(p, mc, c, now, false)) self = true;
		if (raidOn(p) && c.raidDrink) AfkHelperClient.drinkOminous(p);
		return self || look.busy();
	}
}
