package net.milenco.buddy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.milenco.buddy.version.Game;

/**
 * The alt's water bucket (alt only): it puts out a fire on the buddy and breaks a fall that would hurt, and the water goes
 * back into the bucket afterwards (made after the alt burned to death in a cave).
 *
 * Burning on the ground (out of lava, not in water, no fire resistance) the buddy looks straight down and pours into the
 * cell stood in, which puts the fire out at once; falling so that the landing would cost two hearts or more, or leave three or
 * fewer, the buddy pours onto the landing block as soon as it is within reach, and so lands in water. Each is one
 * tick, where vanilla clicks: the bucket into the hand (from the backpack by a swap packet when it is not in the
 * hotbar), the look, the use; the server takes the look from the use packet. Then the buddy waits in the water (the
 * following pauses, {@link #busy()}), scoops it up and puts back what was in hand. A pour the server refuses (a claim, the
 * spawn) shows as no water two ticks on: then no bucket for half a minute, and the following walks the buddy to water close
 * by instead ({@code Follow}). Not where water evaporates (the nether), not onto a block that would take the water
 * itself (a slab, stairs: waterlogged underfoot is no water around the body), not into lava.
 */
final class Water {
	private Water() {}

	/** What a fall may cost before the bucket is worth it: two hearts. */
	static final int SAVE_DAMAGE = 4;
	/** Or what it may leave the buddy at most: three hearts. */
	static final float SAVE_LEFT = 6f;
	/** After a refused pour: so long no bucket (ms). */
	private static final long REFUSED_MS = 30_000;
	/** This much obsidian round the buddy makes a frame (a portal's has ten, a lone cast from lava one or two). */
	static final int FRAME_OBSIDIAN = 4;

	private static BlockPos poured;         // where the water went, until it is scooped up again
	private static String why;              // "brand" or "val"
	private static int ticks, pouredAt, tries, lastTry;
	private static int bucketSlot = -1;     // the hotbar slot the bucket is in during the pour
	private static int restoreSlot = -1;    // what was selected before
	private static int swappedFrom = -1;    // the backpack slot the bucket came from, to go back to
	private static float hpAtPour;
	private static double fallAtPour;
	private static long refusedUntil;

	/** Between the pour and the scoop: the following waits, the buddy stands in the water. */
	static boolean busy() { return poured != null; }

	static void stop(String reason) {
		if (poured == null) return;
		Exchange.emit("water", "action", "left standing", "reason", reason, "x", poured.getX(), "y", poured.getY(), "z", poured.getZ());
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.gameMode != null) putBack(mc, mc.player);
		poured = null;
	}

	static boolean tick(Minecraft mc, LocalPlayer p, AfkConfig c) { return false; }

	/** The start of the tick, where vanilla clicks: pour, or scoop up what was poured. */
	static void startTick(Minecraft mc) {
		ticks++;
		LocalPlayer p = mc.player;
		if (p == null || mc.gameMode == null || mc.level == null) { poured = null; return; }
		if (!AfkHelperClient.active() || !p.isAlive()) { if (poured != null) stop("gone"); return; }
		if (poured != null) { scoopTick(mc, p); return; }
		// a menu open: not now (the chat and the pause screen are no menu: a game without a window always has the latter)
		var screen = Game.openScreen(mc);
		boolean menu = screen != null && !(screen instanceof net.minecraft.client.gui.screens.ChatScreen)
			&& !(screen instanceof net.minecraft.client.gui.screens.PauseScreen);
		if (menu || p.containerMenu != p.inventoryMenu || p.isPassenger() || p.isFallFlying()
			|| p.isSpectator() || p.getAbilities().invulnerable) return;
		if (fallTick(mc, p)) return;
		douseTick(mc, p);
	}

	// ---------- the fire ----------

	/** On fire where water would help: not in lava (out first), not in water, no fire resistance. */
	static boolean burning(LocalPlayer p) {
		return p.isOnFire() && !p.isInLava() && !p.isInWater() && !p.hasEffect(MobEffects.FIRE_RESISTANCE);
	}

	/** The buddy can put a fire out alone, here and now: a bucket, not refused, on the ground on a block water goes on. */
	static boolean canDouse(LocalPlayer p) {
		return System.currentTimeMillis() >= refusedUntil && bucket(p) >= 0 && p.onGround() && douseCell(p) != null;
	}

	private static void douseTick(Minecraft mc, LocalPlayer p) {
		if (!burning(p) || AfkHelperClient.isEating() || !canDouse(p) || byFrame(p)) return;
		BlockPos cell = douseCell(p);
		// straight down: the ray from the eye meets the block stood on, and the water goes into the buddy's own cell
		if (pour(mc, p, p.getYRot(), 90f, cell, "fire")) hpAtPour = p.getHealth();
	}

	/**
	 * Next to an obsidian frame: a nether portal being built, and the fire is most likely the lighting that made no portal.
	 * Water poured there floods the frame, and the build then spent two game hours clearing water (attempt 218, 4 Nov
	 * 2026). Soprano punches that fire out; the burn that is left is short.
	 */
	static boolean byFrame(LocalPlayer p) {
		Level level = p.level();
		BlockPos feet = p.blockPosition();
		int obsidian = 0;
		for (BlockPos cell : BlockPos.betweenClosed(feet.offset(-2, -1, -2), feet.offset(2, 3, 2))) {
			if (level.getBlockState(cell).is(net.minecraft.world.level.block.Blocks.OBSIDIAN) && ++obsidian >= FRAME_OBSIDIAN) return true;
		}
		return false;
	}

	/**
	 * Where the water goes when poured straight down, as the bucket's own ray finds it: on top of what the ray meets
	 * first, which can be the fire the buddy stands in (it has a thin outline at the bottom) or a plant, and then the water
	 * goes one higher. Good when that cell touches the buddy.
	 */
	private static BlockPos douseCell(LocalPlayer p) {
		Level level = p.level();
		Vec3 eye = p.getEyePosition();
		BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(0, -p.blockInteractionRange(), 0), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
		if (hit.getType() != HitResult.Type.BLOCK) return null;
		BlockPos cell = hit.getBlockPos().relative(hit.getDirection());
		if (!p.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(cell))) return null;
		return takes(level, hit.getBlockPos(), cell) ? cell : null;
	}

	// ---------- the fall ----------

	/** What a fall of this many blocks costs, as 26.3 counts it (three blocks free, no feather falling counted). */
	static int damage(double fall) { return Math.max(0, Mth.floor(fall - 3)); }

	/** Worth the bucket: two hearts or more, or so much that three hearts or fewer are left. */
	static boolean worth(int damage, float health) { return damage >= SAVE_DAMAGE || (damage > 0 && health - damage <= SAVE_LEFT); }

	private static boolean fallTick(Minecraft mc, LocalPlayer p) {
		if (p.onGround() || p.isInWater() || p.onClimbable() || p.getDeltaMovement().y > -0.2
			|| p.hasEffect(MobEffects.SLOW_FALLING) || p.hasEffect(MobEffects.LEVITATION) || bucket(p) < 0) return false;
		Level level = p.level();
		BlockHitResult land = landing(p);
		if (land == null) return false;
		BlockPos block = land.getBlockPos();
		FluidState f = level.getFluidState(block);
		if (!f.isEmpty()) return false;   // into water (no harm) or lava (no help)
		double top = land.getLocation().y;
		double fall = p.fallDistance + (p.getY() - top);
		int dmg = damage(fall);
		if (!worth(dmg, p.getHealth())) return false;
		// aim at the middle of the landing block's top, as far as the eye reaches; not yet in reach: next tick
		Vec3 eye = p.getEyePosition();
		Vec3 aim = new Vec3(block.getX() + 0.5, top, block.getZ() + 0.5);
		double reach = p.blockInteractionRange() - 0.1;
		if (eye.distanceTo(aim) > reach) return false;
		Vec3 d = aim.subtract(eye);
		float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
		float pitch = (float) (-(Mth.atan2(d.y, Math.hypot(d.x, d.z)) * Mth.RAD_TO_DEG));
		// the bucket's own ray has to come down on that top, not on the side of something in between
		Vec3 view = Vec3.directionFromRotation(pitch, yaw);
		BlockHitResult seen = level.clip(new ClipContext(eye, eye.add(view.scale(p.blockInteractionRange())), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
		if (seen.getType() != HitResult.Type.BLOCK) return false;
		// where the bucket puts it: on what the ray meets, so on the landing block or on a plant on top of it; either
		// way in the column of the landing, the first or second cell above the block
		BlockPos cell = seen.getBlockPos().relative(seen.getDirection());
		if (cell.getX() != block.getX() || cell.getZ() != block.getZ() || cell.getY() < block.getY() + 1 || cell.getY() > block.getY() + 2) return false;
		if (!takes(level, seen.getBlockPos(), cell)) return false;
		if (AfkHelperClient.isEating()) mc.gameMode.releaseUsingItem(p);
		if (!pour(mc, p, yaw, pitch, cell, "fall")) return false;
		hpAtPour = p.getHealth();
		fallAtPour = fall;
		return true;
	}

	/**
	 * Where the buddy comes down: the path tick by tick as the game moves a falling player (gravity 0.08, then 0.98 of it,
	 * 0.91 across), with a ray from the middle and the four corners, the first block whose top it meets.
	 */
	private static BlockHitResult landing(LocalPlayer p) {
		Level level = p.level();
		Vec3 pos = p.position(), v = p.getDeltaMovement();
		double[][] corners = { { 0, 0 }, { -0.3, -0.3 }, { -0.3, 0.3 }, { 0.3, -0.3 }, { 0.3, 0.3 } };
		for (int t = 0; t < 80; t++) {
			Vec3 next = pos.add(v);
			BlockHitResult first = null;
			double best = Double.MAX_VALUE;
			for (double[] c : corners) {
				Vec3 a = pos.add(c[0], 0, c[1]), b = next.add(c[0], 0, c[1]);
				BlockHitResult h = level.clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p));
				if (h.getType() != HitResult.Type.BLOCK) continue;
				double dist = h.getLocation().distanceToSqr(a);
				if (dist < best) { best = dist; first = h; }
			}
			if (first != null) return first.getDirection() == Direction.UP || !level.getFluidState(first.getBlockPos()).isEmpty() ? first : null;
			if (next.y < level.getMinY() - 2) return null;
			pos = next;
			v = new Vec3(v.x * 0.91, (v.y - 0.08) * 0.98, v.z * 0.91);
		}
		return null;
	}

	// ---------- pour and scoop ----------

	/** The block takes water on its top into the cell above, and the water there is not lost: no nether, no slab below. */
	private static boolean takes(Level level, BlockPos block, BlockPos cell) {
		if (level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, cell)) return false;
		if (level.getBlockState(block).getBlock() instanceof LiquidBlockContainer) return false;
		BlockState in = level.getBlockState(cell);
		return in.isAir() || (in.canBeReplaced(Fluids.WATER) && in.getFluidState().isEmpty());
	}

	/** A slot with a water bucket: the hotbar 0-8 first, then the backpack 9-35; -1 for none. */
	static int bucket(LocalPlayer p) {
		var inv = p.getInventory();
		for (int i = 0; i < 36; i++) if (inv.getItem(i).is(Items.WATER_BUCKET)) return i;
		return -1;
	}

	private static boolean pour(Minecraft mc, LocalPlayer p, float yaw, float pitch, BlockPos cell, String reason) {
		int slot = bucket(p);
		if (slot < 0) return false;
		var inv = p.getInventory();
		restoreSlot = inv.getSelectedSlot();
		swappedFrom = -1;
		if (slot < 9) bucketSlot = slot;
		else {
			// into the hand at once: the swap goes as a packet before the use, so the server has it there too
			Game.swapWithHotbar(mc.gameMode, p.inventoryMenu.containerId, slot, restoreSlot, p);
			bucketSlot = restoreSlot;
			swappedFrom = slot;
		}
		if (inv.getSelectedSlot() != bucketSlot) inv.setSelectedSlot(bucketSlot);
		if (!p.getMainHandItem().is(Items.WATER_BUCKET)) { putBack(mc, p); return false; }
		p.setYRot(yaw);
		p.setXRot(pitch);
		InteractionResult r = mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
		if (!r.consumesAction()) { putBack(mc, p); return false; }
		Game.swingUse(p, InteractionHand.MAIN_HAND);
		poured = cell.immutable();
		why = reason;
		pouredAt = ticks;
		tries = 0;
		Exchange.emit("water", "action", "poured", "reason", reason, "hp", Math.round(p.getHealth() * 10) / 10.0,
			"x", cell.getX(), "y", cell.getY(), "z", cell.getZ());
		return true;
	}

	/** Wait in the water until it did what it was for, then the water back into the bucket and the hand as it was. */
	private static void scoopTick(Minecraft mc, LocalPlayer p) {
		Level level = p.level();
		int since = ticks - pouredAt;
		FluidState f = level.getFluidState(poured);
		boolean water = !f.isEmpty() && f.is(net.minecraft.tags.FluidTags.WATER);
		if (!water) {
			// two ticks and no water: the server would not have it here
			if (since >= 3) {
				refusedUntil = System.currentTimeMillis() + REFUSED_MS;
				Exchange.emit("water", "action", "refused", "reason", why, "x", poured.getX(), "y", poured.getY(), "z", poured.getZ());
				putBack(mc, p);
				poured = null;
			}
			return;
		}
		if ("fire".equals(why)) {
			if (p.isOnFire() && since < 15) return;
			if (since < 3) return;
		} else {
			// in the water and still: the fall is over
			if (!(p.isInWater() || p.onGround()) && since < 60) return;
			if (p.getDeltaMovement().y < -0.1 && since < 60) return;
			if (since < 4) return;
		}
		if (tries == 0) {
			String done = "fire".equals(why) ? (p.isOnFire() ? "still burning" : "put out") : (p.getHealth() < hpAtPour - 0.5f ? "fall not broken" : "fall broken");
			Exchange.emit("water", "action", done, "hp", Math.round(p.getHealth() * 10) / 10.0, "fall", Math.round(fallAtPour * 10) / 10.0);
		}
		if (tries >= 3 || since > 100) { stop("cannot scoop up"); return; }
		if (ticks - lastTry < 4 && tries > 0) return;
		lastTry = ticks;
		tries++;
		if (!f.isSource()) { stop("no source"); return; }
		var inv = p.getInventory();
		if (!inv.getItem(bucketSlot).is(Items.BUCKET)) {
			int s = -1;
			for (int i = 0; i < 9; i++) if (inv.getItem(i).is(Items.BUCKET)) { s = i; break; }
			if (s < 0) { stop("no empty bucket"); return; }
			bucketSlot = s;
		}
		if (inv.getSelectedSlot() != bucketSlot) inv.setSelectedSlot(bucketSlot);
		Vec3 eye = p.getEyePosition();
		Vec3 aim = Vec3.atCenterOf(poured);
		Vec3 d = aim.subtract(eye);
		float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
		float pitch = (float) (-(Mth.atan2(d.y, Math.hypot(d.x, d.z)) * Mth.RAD_TO_DEG));
		Vec3 view = Vec3.directionFromRotation(pitch, yaw);
		BlockHitResult seen = level.clip(new ClipContext(eye, eye.add(view.scale(p.blockInteractionRange())), ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, p));
		if (seen.getType() != HitResult.Type.BLOCK || !seen.getBlockPos().equals(poured)) return;
		p.setYRot(yaw);
		p.setXRot(pitch);
		InteractionResult r = mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
		if (!r.consumesAction() || !p.getMainHandItem().is(Items.WATER_BUCKET)) return;
		Game.swingUse(p, InteractionHand.MAIN_HAND);
		Exchange.emit("water", "action", "scooped up", "x", poured.getX(), "y", poured.getY(), "z", poured.getZ());
		putBack(mc, p);
		poured = null;
	}

	/** The bucket back where it came from, and the slot that was selected. */
	private static void putBack(Minecraft mc, LocalPlayer p) {
		var inv = p.getInventory();
		if (swappedFrom >= 0 && bucketSlot >= 0) Game.swapWithHotbar(mc.gameMode, p.inventoryMenu.containerId, swappedFrom, bucketSlot, p);
		if (restoreSlot >= 0 && inv.getSelectedSlot() != restoreSlot) inv.setSelectedSlot(restoreSlot);
		swappedFrom = -1;
		bucketSlot = -1;
		restoreSlot = -1;
	}
}
