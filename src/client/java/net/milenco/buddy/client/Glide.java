package net.milenco.buddy.client;

import com.google.gson.JsonObject;

import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import net.milenco.buddy.version.Game;

/**
 * Flying with an elytra in the game: taking off, steering by {@link Flight}'s plan, rockets, landing. It follows the
 * player the buddy follows when that player takes off, without dying of kinetic or fall damage, and flies to a point
 * on {@code {"cmd":"fly","to":[x,y,z]}}. He wears the elytra all the time, so Stash keeps it on.
 * While it flies, following and everything else that steers keeps off the keys (Jobs: GLIDE). Once in the air it never lets
 * go of the keys until he stands on the ground: a stop or a lost target only makes it land.
 */
final class Glide {
	private Glide() {}

	private enum Mode { OFF, TAKEOFF, FLY, LAND }

	private static Mode mode = Mode.OFF;
	/** Fly to this point and land there, or (null) follow Follow's player. */
	private static Vec3 point;
	private static Vec3 landAt;
	private static int ticks, jumpPhase, jumpTries, rocketLeft, rocketCooldown, restoreSlot = -1, fireAt;
	private static boolean fireRocket;
	private static Flight.Plan plan;
	private static Vec3 targetLast, targetVel = Vec3.ZERO;
	private static int targetGroundTicks, targetUnseen;
	private static float hpAtStart;
	private static double predictedX = Double.NaN, predictedZ, predictedY, maxErr;

	static boolean active() { return mode != Mode.OFF; }

	/** In the air or on the way up: no one else touches the keys, and nothing stops it but a landing. */
	static boolean airborne() { return mode != Mode.OFF; }

	// ---------- switching ----------

	/** {@code {"cmd":"fly","to":[x,y,z]}} flies there and lands, {@code "stop":true} lands here, without either it answers. */
	static void command(JsonObject o) {
		LocalPlayer p = Minecraft.getInstance().player;
		if (o.has("stop") && o.get("stop").getAsBoolean()) { stop("asked"); return; }
		if (o.has("to") && p != null) {
			var a = o.getAsJsonArray("to");
			point = new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
			start(p, "to a point");
			return;
		}
		status("status", p);
	}

	/**
	 * Off. In the air that is a landing where he is (never letting go of the keys mid-air: the glide would carry him into
	 * whatever stands ahead); on the ground at once.
	 */
	static void stop(String why) {
		if (mode == Mode.OFF) return;
		LocalPlayer p = Minecraft.getInstance().player;
		if (mode == Mode.FLY && p != null && p.isAlive() && p.isFallFlying()) {
			if (landAt == null) { landAt = spotNear(p.level(), p.position(), 12); Exchange.emit("flight", "action", "landing", "reason", why, "spot", where(landAt)); }
			point = landAt == null ? null : landAt;
			mode = Mode.LAND;
			return;
		}
		end(why);
	}

	private static void end(String why) {
		LocalPlayer p = Minecraft.getInstance().player;
		Exchange.emit("flight", "action", "done", "reason", why, "sec", ticks / 20, "hp", p == null ? 0 : Math.round(p.getHealth()),
			"hp_lost", p == null ? 0 : Math.round(hpAtStart - p.getHealth()));
		mode = Mode.OFF;
		point = null; landAt = null; plan = null; fireRocket = false; rocketLeft = 0;
		targetLast = null; targetVel = Vec3.ZERO; targetGroundTicks = 0; targetUnseen = 0; predictedX = Double.NaN; maxErr = 0;
		Minecraft mc = Minecraft.getInstance();
		HeldKeys.set(mc.options.keyJump, false);
	}

	private static boolean start(LocalPlayer p, String why) {
		String no = cannotFly(p);
		if (no != null) { Exchange.emit("flight", "action", "cannot", "reason", no); return false; }
		mode = p.isFallFlying() ? Mode.FLY : Mode.TAKEOFF;
		ticks = 0; jumpPhase = 0; jumpTries = 0; landAt = null; hpAtStart = p.getHealth();
		Exchange.emit("flight", "action", "start", "reason", why, "rockets", rockets(p), "elytra", elytraLeft(p), "goal", point == null ? Follow.target() : where(point));
		return true;
	}

	/** Why he cannot take off now, or null. */
	private static String cannotFly(LocalPlayer p) {
		if (!LivingEntity.canGlideUsing(p.getItemBySlot(EquipmentSlot.CHEST), EquipmentSlot.CHEST)) return "no elytra on";
		if (elytraLeft(p) < LOW_ELYTRA) return "elytra nearly broken";
		if (p.isInLiquid() || p.isPassenger()) return "in water or riding";
		if (!p.isFallFlying() && p.onGround() && rockets(p) == 0) return "no rockets";
		return null;
	}

	// ---------- the tick ----------

	/** Start of the tick, where vanilla uses an item: a rocket, and the slot back after it. */
	static void startTick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.gameMode == null) return;
		if (restoreSlot >= 0) { p.getInventory().setSelectedSlot(restoreSlot); restoreSlot = -1; }
		if (!fireRocket) return;
		fireRocket = false;
		if (!p.isFallFlying()) return;
		int slot = rocketSlot(p);
		if (slot < 0) return;
		int was = p.getInventory().getSelectedSlot();
		p.getInventory().setSelectedSlot(slot);
		InteractionResult r = mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
		if (r.consumesAction()) {
			Game.swingUse(p, InteractionHand.MAIN_HAND);
			Fireworks f = p.getInventory().getItem(slot).get(DataComponents.FIREWORKS);
			rocketLeft = 10 * Math.max(1, f == null ? 1 : f.flightDuration()) + 5;
			rocketCooldown = 10;
			Exchange.emit("flight", "action", "rocket", "left", rockets(p) - 1, "x", Math.round(p.getX()), "y", Math.round(p.getY()), "z", Math.round(p.getZ()));
		}
		if (slot != was) restoreSlot = was;
	}

	/** The tick, after the game moved him: the next look, a rocket, the keys. True while it steers. */
	static boolean tick(Minecraft mc, LocalPlayer p, AfkConfig c) {
		if (p == null) return false;
		if (mode == Mode.OFF) { maybeFollowUp(p); return false; }
		ticks++;
		if (!p.isAlive()) { end("dead"); return false; }
		if (rocketLeft > 0) rocketLeft--;
		if (rocketCooldown > 0) rocketCooldown--;
		rocketSeen(p);
		trackTarget(p);
		switch (mode) {
			case TAKEOFF -> takeoff(mc, p);
			case FLY, LAND -> fly(mc, p);
			default -> { }
		}
		return true;
	}

	/** Following someone who takes off: up after him. */
	private static void maybeFollowUp(LocalPlayer p) {
		// AFK mode keeps the following still (the respawn after a death used it), so it keeps the flying still too
		if (!Follow.isOn() || Follow.target() == null || !AfkHelperClient.active() || AfkConfig.get().afk) return;
		// not from the pen he farms, and not while told to stay: a buddy told to stay took off after a gliding owner (0.4.80)
		if (Pen.busy() || Follow.staying()) return;
		Player t = target(p);
		if (t == null || !t.isFallFlying()) return;
		if (p.isPassenger()) return;   // on a seat or his ghast: a take-off there is no take-off (on the ghast it is a fall)
		if (cannotFly(p) != null) return;   // no elytra or no rockets: he follows on foot, and asks for a /tpa when he loses him
		point = null;
		start(p, "follows " + t.getGameProfile().name());
	}

	private static void takeoff(Minecraft mc, LocalPlayer p) {
		if (p.isFallFlying()) {
			HeldKeys.set(mc.options.keyJump, false);
			mode = Mode.FLY;
			fireRocket = rockets(p) > 0;   // up: the plan's first look is steep while it is low
			Exchange.emit("flight", "action", "airborne", "y", Math.round(p.getY()));
			return;
		}
		if (ticks > 80 || jumpTries > 4) { Exchange.emit("flight", "action", "take-off fails", "attempts", jumpTries); end("take-off fails"); return; }
		if (!roomAbove(p)) { end("no room above"); return; }
		// look up toward where he goes, then: jump, let go, and jump again in the air (LocalPlayer starts the glide on a
		// jump pressed while not on the ground)
		float yaw = p.getYRot();
		Vec3 g = goalPoint(p);
		if (g != null) yaw = (float) Flight.yawTo(p.getX(), p.getZ(), g.x, g.z);
		turn(p, yaw, -50);
		switch (jumpPhase) {
			case 0 -> { if (p.onGround()) { HeldKeys.set(mc.options.keyJump, true); jumpPhase = 1; jumpTries++; } else jumpPhase = 2; }
			case 1 -> { HeldKeys.set(mc.options.keyJump, false); jumpPhase = 2; }
			case 2 -> { if (!p.onGround() && p.getDeltaMovement().y < 0.3) { HeldKeys.set(mc.options.keyJump, true); jumpPhase = 3; } else if (p.onGround()) jumpPhase = 0; }
			case 3 -> { HeldKeys.set(mc.options.keyJump, false); jumpPhase = p.onGround() ? 0 : 2; }
			default -> jumpPhase = 0;
		}
	}

	private static void fly(Minecraft mc, LocalPlayer p) {
		// in water the server keeps the glide on until he stands on the bottom: in water he is down, and it cost nothing
		if (p.isInLiquid()) { end(mode == Mode.LAND ? "landed in water" : "in water"); return; }
		if (!p.isFallFlying()) {
			// The glide ends as he comes down: on a slope or a block's edge a tick before he stands. Low over the ground
			// that is the landing; pressing jump there started a new glide at no speed, over and over (18 Oct 2026: 45 s on
			// a mountain). Only high in the air is it the server stopping the glide, and worth starting again.
			if (p.onGround() || Flight.heightAbove(world(p.level()), p.getX(), p.getY(), p.getZ()) < 4) {
				HeldKeys.set(mc.options.keyJump, false);
				end(mode == Mode.LAND ? "landed" : "came down");
				return;
			}
			HeldKeys.set(mc.options.keyJump, (ticks & 1) == 0);
			return;
		}
		HeldKeys.set(mc.options.keyJump, false);
		Level level = p.level();
		if (mode == Mode.FLY) {
			String why = landingReason(p);
			if (why != null) {
				Vec3 near = point != null && why.equals("arrived") ? point : why.equals("landed") && targetLast != null ? targetLast : p.position();
				landAt = spotNear(level, near, why.equals("out of rockets") || why.equals("elytra nearly broken") ? 16 : 8);
				mode = Mode.LAND;
				Exchange.emit("flight", "action", "landing", "reason", why, "spot", where(landAt));
			}
		}
		Flight.Goal goal = goal(p);
		if (goal == null) { goal = new Flight.Goal(p.getX(), p.getY(), p.getZ(), 0, 0, 0, true); }
		// how well the plan's tick matched the game's: the largest difference of the last second shows in the status
		if (!Double.isNaN(predictedX)) maxErr = Math.max(maxErr, Math.sqrt(sq(p.getX() - predictedX) + sq(p.getY() - predictedY) + sq(p.getZ() - predictedZ)));
		Flight.State s = state(p);
		if ((ticks & 1) == 0 || plan == null) {
			// no rocket while landing: it only adds the speed the landing has to lose
			plan = Flight.best(world(level), s, goal, rockets(p) > 0 && rocketCooldown == 0 && mode != Mode.LAND, rocketTicks(p));
			if (plan.rocket && rocketLeft == 0 && rocketCooldown == 0) fireRocket = true;
			if (!plan.safe() && ticks % 10 == 0) Exchange.emit("flight", "action", "danger", "left_ticks", plan.result.tick, "what", plan.result.event.name().toLowerCase(java.util.Locale.ROOT),
				"x", Math.round(p.getX()), "y", Math.round(p.getY()), "z", Math.round(p.getZ()));
		}
		turn(p, (float) plan.yaw, (float) plan.pitch);
		// where the plan expects him after the next tick, with the look as the turn leaves it
		Flight.State next = s.copy();
		Flight.step(world(level), next, plan.yaw, plan.pitch);
		predictedX = next.x; predictedY = next.y; predictedZ = next.z;
		if (ticks % 20 == 0) {
			Vec3 v = p.getDeltaMovement();
			Exchange.emit("flight", "action", "status", "mode", mode.name().toLowerCase(java.util.Locale.ROOT), "x", Math.round(p.getX()), "y", Math.round(p.getY()), "z", Math.round(p.getZ()),
				"speed", round2(v.horizontalDistance()), "descends", round2(v.y), "look", Math.round(plan.pitch), "room", plan.result.room, "safe", plan.safe(),
				"wrong", round2(maxErr), "rockets", rockets(p), "elytra", elytraLeft(p), "hp", Math.round(p.getHealth()));
			maxErr = 0;
		}
	}

	/** Why to come down now, or null to fly on. */
	private static String landingReason(LocalPlayer p) {
		if (elytraLeft(p) < LOW_ELYTRA) return "elytra nearly broken";
		if (point != null) return Math.hypot(point.x - p.getX(), point.z - p.getZ()) < 24 ? "arrived" : null;
		if (!Follow.isOn() || Follow.target() == null) return "following off";
		if (targetGroundTicks > 10) return "landed";
		if (targetUnseen > 200) return "lost";
		// out of rockets he can no longer climb or catch up: down while he still has the height to choose where
		if (rockets(p) == 0 && rocketLeft == 0 && (Flight.heightAbove(world(p.level()), p.getX(), p.getY(), p.getZ()) < 25
				|| targetLast != null && targetLast.distanceTo(p.position()) > 80)) return "out of rockets";
		return null;
	}

	// ---------- where to ----------

	private static Player target(LocalPlayer p) {
		String name = Follow.target();
		if (name == null) return null;
		for (Player o : p.level().players()) if (o != p && o.getGameProfile().name().equalsIgnoreCase(name)) return o;
		return null;
	}

	/** The followed player's place and speed, measured here from tick to tick (a remote player's motion is not sent). */
	private static void trackTarget(LocalPlayer p) {
		if (point != null) return;
		Player t = target(p);
		if (t == null) { targetUnseen++; return; }
		targetUnseen = 0;
		Vec3 now = t.position();
		if (targetLast != null) targetVel = targetVel.scale(0.6).add(now.subtract(targetLast).scale(0.4));
		targetLast = now;
		targetGroundTicks = t.isFallFlying() ? 0 : targetGroundTicks + 1;
	}

	private static Vec3 goalPoint(LocalPlayer p) {
		if (point != null) return point;
		return targetLast;
	}

	/** Where the plan flies to: the point, the landing spot, or a place behind and above the player he follows. */
	private static Flight.Goal goal(LocalPlayer p) {
		if (mode == Mode.LAND) {
			Vec3 l = landAt != null ? landAt : p.position();
			return new Flight.Goal(l.x, l.y, l.z, 0, 0, 0, true);
		}
		if (point != null) return new Flight.Goal(point.x, Math.max(point.y, p.getY() - 10) + 6, point.z, 0, 0, 0, false);
		if (targetLast == null) return null;
		Vec3 v = targetUnseen > 0 ? targetVel.scale(Math.max(0, 1 - targetUnseen / 100.0)) : targetVel;
		double hv = v.horizontalDistance();
		double bx = hv > 0.1 ? -v.x / hv * 7 : 0, bz = hv > 0.1 ? -v.z / hv * 7 : 0;
		Vec3 t = targetLast.add(v.scale(targetUnseen));
		return new Flight.Goal(t.x + bx, t.y + 3, t.z + bz, v.x, v.y * 0.5, v.z, false);
	}

	/**
	 * A place to land near this one: open ground, the nearest in a ring of up to {@code radius}. A block he can stand on
	 * that is no leaves, no lava or the like, with the three by three around it free for two blocks up, so that the slow
	 * last metres come down in the open and not into a tree (18 Oct 2026: a landing beside trees hung in a crown for 13 s).
	 * Water counts: coming down in it costs nothing. Null when there is none: then the plan lands where it can.
	 */
	private static Vec3 spotNear(Level level, Vec3 around, int radius) {
		int cx = Mth.floor(around.x), cz = Mth.floor(around.z);
		Vec3 best = null;
		double bestD = Double.MAX_VALUE;
		for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
			int x = cx + dx, z = cz + dz;
			if (!level.hasChunkAt(x, z)) continue;
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);   // the first air above the highest blocking block
			BlockPos under = new BlockPos(x, y - 1, z);
			BlockState st = level.getBlockState(under);
			if (st.getFluidState().is(FluidTags.LAVA) || st.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK) || st.is(net.minecraft.world.level.block.Blocks.CACTUS)
					|| st.is(net.minecraft.world.level.block.Blocks.CAMPFIRE) || st.is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH)) continue;
			if (st.is(net.minecraft.tags.BlockTags.LEAVES)) continue;
			if (!open(level, x, y, z)) continue;
			double d = dx * dx + dz * dz + Math.abs(y - around.y) * 0.5;
			if (d < bestD) { bestD = d; best = new Vec3(x + 0.5, y, z + 0.5); }
		}
		return best;
	}

	/** The three by three around (x, z) free of anything solid from y up two blocks. */
	private static boolean open(Level level, int x, int y, int z) {
		BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = 0; dy <= 2; dy++) {
			q.set(x + dx, y + dy, z + dz);
			if (!level.getBlockState(q).getCollisionShape(level, q).isEmpty()) return false;
		}
		return true;
	}

	// ---------- the plan's view of the world ----------

	private static final Long2BooleanOpenHashMap cache = new Long2BooleanOpenHashMap();
	private static long cacheTick = -1;

	/** Solid, lava, or not loaded (unknown counts as in the way); water is not: coming down in it costs nothing. */
	private static Flight.World world(Level level) {
		long now = level.getGameTime();
		if (now != cacheTick) { cache.clear(); cacheTick = now; }
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		return new Flight.World() {
			@Override
			public boolean solid(int x, int y, int z) {
				long key = BlockPos.asLong(x, y, z);
				if (cache.containsKey(key)) return cache.get(key);
				boolean solid;
				if (y < level.getMinY()) solid = false;   // the void under the world: falling there is another matter, not a wall
				else if (!level.hasChunk(x >> 4, z >> 4)) solid = true;
				else {
					pos.set(x, y, z);
					BlockState st = level.getBlockState(pos);
					solid = st.getFluidState().is(FluidTags.LAVA) || !st.getCollisionShape(level, pos).isEmpty();
				}
				cache.put(key, solid);
				return solid;
			}

			@Override
			public boolean floor(int x, int y, int z) {
				if (solid(x, y, z)) return true;
				pos.set(x, y, z);
				return !level.getBlockState(pos).getFluidState().isEmpty();
			}
		};
	}

	private static Flight.State state(LocalPlayer p) {
		Vec3 v = p.getDeltaMovement();
		return new Flight.State(p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z, p.getYRot(), p.getXRot(), rocketLeft);
	}

	/** Turn toward the plan's look, as fast as the plan says the mouse turns. */
	private static void turn(LocalPlayer p, float yaw, float pitch) {
		float y = p.getYRot() + (float) Math.max(-Flight.YAW_RATE, Math.min(Flight.YAW_RATE, Mth.wrapDegrees(yaw - p.getYRot())));
		float pt = p.getXRot() + (float) Math.max(-Flight.PITCH_RATE, Math.min(Flight.PITCH_RATE, pitch - p.getXRot()));
		MouseTurn.toward(p, y, pt);
	}

	private static boolean roomAbove(LocalPlayer p) {
		Level l = p.level();
		BlockPos b = p.blockPosition();
		for (int i = 2; i <= 4; i++) if (!l.getBlockState(b.above(i)).getCollisionShape(l, b.above(i)).isEmpty()) return false;
		return true;
	}

	/**
	 * Is his rocket still pulling? A rocket's time is drawn on the server (10 to 21 ticks for one of flight 1) and the
	 * client never hears it; it hears the end, because the server takes the rocket away when it bursts. So: a rocket at
	 * him means a pull, none means none, and the guess only fills in how long.
	 */
	private static void rocketSeen(LocalPlayer p) {
		boolean pulling = !p.level().getEntitiesOfClass(net.minecraft.world.entity.projectile.FireworkRocketEntity.class, p.getBoundingBox().inflate(2.0)).isEmpty();
		if (pulling && rocketLeft == 0) rocketLeft = 3;
		else if (!pulling && rocketLeft > 0 && rocketCooldown < 7) rocketLeft = 0;   // three ticks after the shot it should be there
	}

	// ---------- what he carries ----------

	/** Below this the elytra has a minute or less of gliding left: down before it breaks. */
	private static final int LOW_ELYTRA = 40;

	static int elytraLeft(LocalPlayer p) {
		ItemStack st = p.getItemBySlot(EquipmentSlot.CHEST);
		return st.has(DataComponents.GLIDER) ? st.getMaxDamage() - st.getDamageValue() : 0;
	}

	/** A rocket that only pushes: one with stars explodes on the one who flies it. */
	private static boolean plainRocket(ItemStack st) {
		if (!st.is(Items.FIREWORK_ROCKET)) return false;
		Fireworks f = st.get(DataComponents.FIREWORKS);
		return f == null || f.explosions().isEmpty();
	}

	static int rockets(LocalPlayer p) {
		int n = 0;
		for (int i = 0; i < 36; i++) { ItemStack st = p.getInventory().getItem(i); if (plainRocket(st)) n += st.getCount(); }
		return n;
	}

	private static int rocketSlot(LocalPlayer p) {
		for (int i = 0; i < 9; i++) if (plainRocket(p.getInventory().getItem(i))) return i;
		// in the backpack: to the hotbar now, fired at the next chance
		AfkHelperClient.toHotbar(p, Minecraft.getInstance(), Glide::plainRocket, java.util.Comparator.comparingInt(ItemStack::getCount), "rocket");
		return -1;
	}

	private static int rocketTicks(LocalPlayer p) {
		for (int i = 0; i < 36; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (!plainRocket(st)) continue;
			Fireworks f = st.get(DataComponents.FIREWORKS);
			return 10 * Math.max(1, f == null ? 1 : f.flightDuration()) + 5;
		}
		return 15;
	}

	// ---------- the status ----------

	private static void status(String action, LocalPlayer p) {
		Exchange.emit("flight", "action", action, "mode", mode.name().toLowerCase(java.util.Locale.ROOT), "rockets", p == null ? 0 : rockets(p),
			"elytra", p == null ? 0 : elytraLeft(p), "goal", point != null ? where(point) : Follow.target() == null ? "" : Follow.target());
	}

	private static String where(Vec3 v) { return v == null ? "" : Math.round(v.x) + "," + Math.round(v.y) + "," + Math.round(v.z); }

	private static double round2(double v) { return Math.round(v * 100) / 100.0; }

	private static double sq(double v) { return v * v; }
}
