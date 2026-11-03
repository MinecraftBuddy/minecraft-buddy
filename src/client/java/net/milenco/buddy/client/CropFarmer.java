package net.milenco.buddy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.milenco.buddy.version.Game;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Plants and harvests the one crop you are looking at, for a nano farm where something else (a dispenser
 * on a clock) does the bonemealing: bare farmland under the crosshair gets the seed you hold, a ripe crop
 * gets broken, and a crop that is still growing is left alone. With bonemeal in your other hand it
 * bonemeals the growing crop itself, as holding the right mouse button would.
 *
 * Holding the left mouse button yourself does not work here: that breaks the crop before it is ripe.
 *
 * It never turns the view and keeps no target: every click goes to whatever the crosshair is on at that
 * moment, so looking at another field simply moves the work there, and looking away pauses it.
 */
public final class CropFarmer {
	private CropFarmer() {}

	private static boolean on = false;
	private static long startedAt = 0, nextAt = 0;
	private static int planted = 0, harvested = 0, mealed = 0;

	public static boolean isOn() { return on; }
	static int harvested() { return harvested; }
	static int planted() { return planted; }

	public static void start() {
		if (on || AfkHelperClient.mc().player == null) return;
		// the farm and the builder turn the view themselves; the crop needs the crosshair to stay where you put it
		Jobs.switchedOn(Jobs.Job.CROP, "harvesting on");
		Lumberjack.stop("harvesting on");
		Builder.stop("harvesting on");
		planted = harvested = mealed = 0;
		startedAt = System.currentTimeMillis();
		nextAt = 0;
		on = true;
		Exchange.emit("harvest", "action", "start");
	}

	public static void stop(String why) {
		if (!on) return;
		on = false;
		Exchange.emit("harvest", "action", "done", "reason", why, "harvested", harvested, "planted", planted, "bonemeal", mealed,
			"sec", (System.currentTimeMillis() - startedAt) / 1000);
	}

	/** Start of the tick: one click at most, on what the crosshair is on right now. */
	public static void startTick(Minecraft mc) {
		if (!on) return;
		LocalPlayer p = mc.player;
		if (p == null || mc.gameMode == null || mc.level == null) return;
		if (Jobs.othersOn(Jobs.Job.CROP)) { stop("farm on"); return; }
		if (handsOff(mc, p)) return;
		long now = System.currentTimeMillis();
		if (now < nextAt) return;
		if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
		BlockPos pos = hit.getBlockPos();
		BlockState st = mc.level.getBlockState(pos);

		if (st.getBlock() instanceof CropBlock crop) {
			if (crop.isMaxAge(st)) {
				mc.gameMode.startDestroyBlock(pos, hit.getDirection());
				Game.swingAttack(p);
				// the break is predicted locally, so the block tells right away whether it went
				if (!(mc.level.getBlockState(pos).getBlock() instanceof CropBlock)) harvested++;
				rest(now);
			} else if (p.getOffhandItem().is(Items.BONE_MEAL)) {
				if (mc.gameMode.useItemOn(p, InteractionHand.OFF_HAND, hit).consumesAction()) { Game.swingUse(p, InteractionHand.OFF_HAND); mealed++; }
				rest(now);
			}
			return;
		}

		// only farmland, and only its top: a right-click on anything else opens chests and flips levers
		if (!st.is(Blocks.FARMLAND) || hit.getDirection() != Direction.UP || !mc.level.getBlockState(pos.above()).isAir()) return;
		if (!seed(p.getMainHandItem())) {
			int slot = seedSlot(p);
			if (slot < 0) { stop("no seed"); return; }
			// the carried item goes to the server on the next tick; the click must come after it
			p.getInventory().setSelectedSlot(slot);
			nextAt = now + 250;
			return;
		}
		if (AfkHelperClient.useOn(p, InteractionHand.MAIN_HAND, hit).consumesAction()) { Game.swingUse(p, InteractionHand.MAIN_HAND); planted++; }
		rest(now);
	}

	/** A click every 150-250 ms, well under the six a second the farm keeps to, and now and then a short hitch. */
	private static void rest(long now) {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		nextAt = now + (r.nextInt(30) == 0 ? r.nextLong(300, 700) : r.nextLong(150, 250));
	}

	private static boolean seed(ItemStack st) { return st.getItem() instanceof BlockItem b && b.getBlock() instanceof CropBlock; }

	private static int seedSlot(LocalPlayer p) {
		for (int i = 0; i < 9; i++) if (seed(p.getInventory().getItem(i))) return i;
		return -1;
	}

	/** A chest, your inventory or a menu is open: no clicks. Chat and the Esc menu the server does not see, as with the farm. */
	private static boolean handsOff(Minecraft mc, LocalPlayer p) {
		var s = Game.openScreen(mc);
		boolean harmless = s == null || s instanceof net.minecraft.client.gui.screens.ChatScreen || s instanceof net.minecraft.client.gui.screens.PauseScreen;
		return !harmless || p.containerMenu != p.inventoryMenu;
	}
}
