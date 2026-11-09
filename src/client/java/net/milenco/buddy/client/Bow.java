package net.milenco.buddy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The bow (the plan for fighting, step 2: for phantoms, and for skeletons on pillars). For
 * a foe the buddy cannot or should not reach with a blow: one that flies (a phantom, a ghast, a blaze, a vex), a creeper
 * (shot before it is close enough to swell), one that stands two and a half blocks or more above the buddy, or one gone
 * for four seconds without a blow (on a pillar, across water). The buddy stands, draws a full second (a bow's full power, 20
 * ticks: BowItem.getPowerForTime), aims where the arrow will meet the foe and lets go once the aim is right:
 * <p>
 * The arrow's way is worked out tick by tick as the game moves it (AbstractArrow.tick: on by its speed, then times 0.99
 * and 0.05 down), from the eye less a tenth, at three blocks a tick at full power; the pitch is the one whose way passes
 * closest over the foe's spot when the arrow gets there, and that spot is where the foe will be by then at its present
 * speed (worked out twice). No shot goes out when a block stands in the way or a player is within a block of it: an
 * arrow hits the first one in its way, and that could be the followed player.
 */
final class Bow {
	private Bow() {}

	private static final double SPEED = 3.0, DRAG = 0.99, GRAVITY = 0.05, MIN = 3.5, MAX = 40;
	private static final int FULL = 20;

	private static boolean draw, release, drawing;
	private static int drawnAt;
	private static long toldAt, usedAt;

	/** A shot or a draw within the last second and a half: Guard.arm leaves the bow in hand. */
	static boolean active() { return System.currentTimeMillis() - usedAt < 1500; }

	/** A bow and arrows in the inventory. */
	static boolean armed(LocalPlayer p) {
		boolean bow = false, arrows = false;
		var inv = p.getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack st = inv.getItem(i);
			if (st.is(Items.BOW) && !Work.nearlyBroken(st)) bow = true;
			if (st.is(ItemTags.ARROWS)) arrows = true;
		}
		return bow && (arrows || p.getOffhandItem().is(ItemTags.ARROWS));
	}

	/**
	 * Whether this foe is one for the bow now: armed, out of a blow's reach and within forty blocks, in sight, and one the
	 * buddy cannot or should not hit (see the class). Not at the pen, which farms by hand.
	 */
	static boolean wanted(LocalPlayer p, Entity e, double reach, long noBlowFor) {
		if (!armed(p) || Pen.busy() || reach < MIN || reach > MAX || !p.hasLineOfSight(e)) return false;
		if (e instanceof Phantom || e instanceof Ghast || e instanceof Blaze || e instanceof Vex || e instanceof Creeper) return true;
		if (e.getY() - p.getY() >= 2.5) return true;
		return reach > 2.7 && noBlowFor > 4000;
	}

	/** Holding the bow: drawn while this is asked every tick, let go when {@link #shoot} says the aim is right. */
	static boolean drawing() { return drawing; }

	/**
	 * One tick of shooting at this foe: the bow to hand, the aim, the draw; true while it holds the tick. Called from the
	 * fight, which has stopped walking.
	 */
	static boolean tick(Minecraft mc, LocalPlayer p, Entity e) {
		usedAt = System.currentTimeMillis();
		var inv = p.getInventory();
		if (!p.getMainHandItem().is(Items.BOW)) {
			int slot = -1;
			for (int i = 0; i < 9; i++) if (inv.getItem(i).is(Items.BOW) && !Work.nearlyBroken(inv.getItem(i))) { slot = i; break; }
			if (slot >= 0) { if (!AfkHelperClient.isEating()) inv.setSelectedSlot(slot); }
			else AfkHelperClient.toHotbar(p, mc, st -> st.is(Items.BOW) && !Work.nearlyBroken(st), java.util.Comparator.comparingInt(st -> st.isEnchanted() ? 1 : 0), "bow");
			return true;
		}
		float[] aim = aim(p, e);
		if (aim == null) { draw = false; return false; }
		MouseTurn.toward(p, p.getYRot() + Mth.clamp(Mth.wrapDegrees(aim[0] - p.getYRot()), -20f, 20f), p.getXRot() + Mth.clamp(aim[1] - p.getXRot(), -15f, 15f));
		draw = true;
		boolean onAim = Math.abs(Mth.wrapDegrees(aim[0] - p.getYRot())) < 1.2f && Math.abs(aim[1] - p.getXRot()) < 1.2f;
		if (drawing && Follow.ticks() - drawnAt >= FULL && onAim && clear(p, e, aim)) release = true;
		long now = System.currentTimeMillis();
		if (now - toldAt > 5000) { toldAt = now; Exchange.emit("guard", "action", "shoots", "goal", Guard.nameOf(e), "distance", Math.round(Follow.reach(p, e))); }
		return true;
	}

	/** Start of the tick, where vanilla uses and lets go of an item: the draw, held, and the shot. */
	static void startTick(Minecraft mc) {
		boolean d = draw, r = release;
		draw = false;
		release = false;
		LocalPlayer p = mc.player;
		if (p == null || mc.gameMode == null) { drawing = false; return; }
		boolean using = p.isUsingItem() && p.getUseItem().is(Items.BOW);
		if (!d || !p.getMainHandItem().is(Items.BOW) || AfkHelperClient.biting() || p.containerMenu != p.inventoryMenu) {
			// while Soprano has the order a draw the buddy did not begin is Soprano's (the forced drills' bow): left alone, as
			// Shield leaves Soprano's shield. this let go of it at the start of every tick, one tick into the draw, so no
			// arrow ever left (9 Nov 2026)
			if (drawing || (using && !Alto.on())) {
				HeldKeys.set(mc.options.keyUse, false);
				if (using) mc.gameMode.releaseUsingItem(p);
			}
			drawing = false;
			return;
		}
		if (r && using) {
			HeldKeys.set(mc.options.keyUse, false);
			mc.gameMode.releaseUsingItem(p);
			drawing = false;
			Guard.noteShot();
			return;
		}
		if (using) return;
		mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
		if (p.isUsingItem()) { HeldKeys.set(mc.options.keyUse, true); drawing = true; drawnAt = Follow.ticks(); }
	}

	/** Where to look: {yaw, pitch}, or null when no pitch reaches it. */
	static float[] aim(LocalPlayer p, Entity e) {
		Vec3 eye = p.getEyePosition().subtract(0, 0.1, 0);
		Vec3 speed = new Vec3(e.getX() - e.xo, e.getY() - e.yo, e.getZ() - e.zo);
		Vec3 at = e.getBoundingBox().getCenter();
		float[] best = null;
		for (int round = 0; round < 2; round++) {
			best = solve(eye, at);
			if (best == null) return null;
			at = e.getBoundingBox().getCenter().add(speed.scale(best[2]));
		}
		return new float[] { best[0], best[1] };
	}

	/** {yaw, pitch, ticks} that bring an arrow from the eye to this point, by the flattest way; null if none does. */
	static float[] solve(Vec3 eye, Vec3 at) {
		double dx = at.x - eye.x, dz = at.z - eye.z, flat = Math.hypot(dx, dz);
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float bestPitch = Float.NaN, bestTicks = 0;
		double bestMiss = Double.MAX_VALUE;
		for (float pitch = -60f; pitch <= 45f; pitch += 0.25f) {
			double rad = Math.toRadians(-pitch);
			double vh = SPEED * Math.cos(rad), vy = SPEED * Math.sin(rad), h = 0, y = eye.y;
			for (int t = 1; t <= 100; t++) {
				double nh = h + vh, ny = y + vy;
				if (nh >= flat) {
					double f = (flat - h) / (nh - h), yy = y + (ny - y) * f, miss = Math.abs(yy - at.y);
					if (miss < bestMiss) { bestMiss = miss; bestPitch = pitch; bestTicks = t; }
					break;
				}
				h = nh; y = ny; vh *= DRAG; vy = vy * DRAG - GRAVITY;
			}
		}
		if (Float.isNaN(bestPitch) || bestMiss > 0.5) return null;
		return new float[] { yaw, bestPitch, bestTicks };
	}

	/**
	 * The arrow's way at the present pitch and yaw is free up to the foe: no block, no player within a block of it (the
	 * followed player above all); the foe itself and others of its kind may be in it.
	 */
	private static boolean clear(LocalPlayer p, Entity e, float[] aim) {
		Vec3 pos = p.getEyePosition().subtract(0, 0.1, 0);
		Vec3 v = Vec3.directionFromRotation(p.getXRot(), p.getYRot()).scale(SPEED);
		double far = Follow.reach(p, e) + 1;
		for (int t = 0; t < 100 && pos.distanceTo(p.getEyePosition()) < far; t++) {
			Vec3 next = pos.add(v);
			if (p.level().clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getType() != HitResult.Type.MISS) return false;
			AABB seg = new AABB(pos, next).inflate(1.0);
			for (Player o : p.level().getEntitiesOfClass(Player.class, seg, o -> o != p && o != e)) return false;
			if (e.getBoundingBox().inflate(0.3).clip(pos, next).isPresent()) return true;
			pos = next;
			v = new Vec3(v.x * DRAG, v.y * DRAG - GRAVITY, v.z * DRAG);
		}
		return true;
	}
}
