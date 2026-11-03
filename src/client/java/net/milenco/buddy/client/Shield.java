package net.milenco.buddy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Holding up the shield (the plan for fighting, step 1: up between two blows, while a skeleton aims and while a
 * creeper hisses). Whoever wants it up asks every tick ({@link #want}); at the start
 * of the next tick, where vanilla uses an item, it goes up the way a right click raises it with a sword in hand: the
 * main hand's use first (which a sword passes), then the off hand's. The use key is held while it is up, so that the
 * game keeps it there; a tick nobody asks takes it down. A shield blocks only from the front and only five ticks after
 * it went up (its {@code blocks_attacks} delay of 0.25 s): the fight looks at whom it fights, the creeper reflex at the
 * creeper.
 */
final class Shield {
	private Shield() {}

	private static boolean want, up;
	private static String why = "";
	private static long toldAt;

	/** Up from the next tick, for this reason; asked again every tick it should stay up. */
	static void want(String reason) { want = true; why = reason; }

	/** Held up now. */
	static boolean up() { return up; }

	/** A shield to block with in the off hand. */
	static boolean ready(LocalPlayer p) {
		return p.getOffhandItem().is(Items.SHIELD) && !Work.nearlyBroken(p.getOffhandItem()) && !p.getCooldowns().isOnCooldown(p.getOffhandItem());
	}

	/** Start of the tick: up, kept up, or down. Before Guard.startTick, so that a blow is never held back by it. */
	static void startTick(Minecraft mc) {
		boolean w = want;
		want = false;
		LocalPlayer p = mc.player;
		if (p == null || mc.gameMode == null) { up = false; return; }
		boolean using = p.isUsingItem() && p.getUseItem().is(Items.SHIELD);
		// not over a bite, not with something in the main hand that has a use of its own (food, a bow, a spear), not behind a screen
		boolean may = w && ready(p) && !AfkHelperClient.biting() && p.containerMenu == p.inventoryMenu
			&& (p.getMainHandItem().isEmpty() || p.getMainHandItem().getUseAnimation() == ItemUseAnimation.NONE);
		if (!may) {
			if (up || using) lower(mc, p);
			return;
		}
		if (using) return;
		if (!p.getMainHandItem().isEmpty()) mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
		mc.gameMode.useItem(p, InteractionHand.OFF_HAND);
		if (!p.isUsingItem()) return;
		HeldKeys.set(mc.options.keyUse, true);
		up = true;
		long now = System.currentTimeMillis();
		if (now - toldAt > 3000) { toldAt = now; Exchange.emit("guard", "action", "shield up", "reason", why, "hp", Math.round(p.getHealth())); }
	}

	private static void lower(Minecraft mc, LocalPlayer p) {
		HeldKeys.set(mc.options.keyUse, false);
		if (p.isUsingItem() && p.getUseItem().is(Items.SHIELD)) mc.gameMode.releaseUsingItem(p);
		up = false;
	}

	/** One that draws a bow or a crossbow: up while it aims (a skeleton, a pillager, a player). */
	static boolean aims(Entity e) {
		return e instanceof LivingEntity le && le.isUsingItem() && (le.getUseItem().is(Items.BOW) || le.getUseItem().is(Items.CROSSBOW));
	}

	/**
	 * A creeper that swells within four blocks of the buddy (the reflex, before any other step): the nearest, or null. Its
	 * blast is blocked by a shield held up towards it; the buddy stands and faces it until it went off or stopped swelling.
	 */
	static Creeper swelling(LocalPlayer p) {
		Creeper best = null;
		double bestD = 4;
		for (Creeper c : p.level().getEntitiesOfClass(Creeper.class, p.getBoundingBox().inflate(4), c -> c.isAlive() && (c.getSwellDir() > 0 || c.getSwelling(1f) > 0.05f))) {
			double d = c.distanceTo(p);
			if (d < bestD) { bestD = d; best = c; }
		}
		return best;
	}

	/** The point to look at so that the shield faces it: its middle. */
	static Vec3 aimAt(Entity e) { return e.getBoundingBox().getCenter(); }
}
