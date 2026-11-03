package net.milenco.buddy.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.milenco.buddy.version.Game;

/**
 * Torches while digging along: only underground (no sky light where the alt stands), only in the tunnel or pit dug
 * along in, only where it is dark (block light under {@link #DARK} at head and feet), on a wall at head height: in
 * a tunnel the right-hand wall seen from where it started (so on the way out the torches are on the left), in a pit the
 * nearest wall. Never on a block that is still to be dug, on a chest or another block with something in it, or on a
 * face a torch does not hold on. Under {@link #MIN} torches the alt takes more from the player's chests (Stash), and
 * makes them from coal or charcoal and sticks in the inventory's own crafting grid; with none at all the alt says so.
 */
final class Torches {
	private Torches() {}

	// a torch gives 14 and a block less each block: dark at 4 or less puts them about ten apart, as agreed; monsters spawn at 0
	static final int MIN = 16, DARK = 5;
	static final long TRY_EVERY = 1500, PLACE_WAIT = 3000;

	private static BlockPos wall, cell;
	private static Direction face;
	private static Vec3 point;
	private static long placingSince, nextTry, nextCraft;
	private static boolean clicked;
	private static final Map<BlockPos, Long> refused = new HashMap<>();
	private static int craftStep;
	private static long craftNext;
	private static int coalSlot, stickSlot;

	static boolean busy() { return wall != null || craftStep > 0; }

	/** Torches to fetch from a chest: digging along with fewer than {@link #MIN}. */
	static boolean wants(LocalPlayer p) { return Work.dig && count(p) < MIN; }

	static int count(LocalPlayer p) {
		int n = 0;
		for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).is(Items.TORCH)) n += p.getInventory().getItem(i).getCount();
		return n;
	}

	/**
	 * Between two blocks of the dig-along: a torch if it is dark here, or a few torches made. {@code forward} is the
	 * tunnel's direction (null in a pit), {@code dug} says which blocks are still to be dug. True while busy.
	 */
	static boolean tick(Minecraft mc, LocalPlayer p, Direction forward, Predicate<BlockPos> dug) {
		long now = System.currentTimeMillis();
		Level level = p.level();
		refused.values().removeIf(until -> until < now);
		if (wall != null) {
			Follow.stand();
			Follow.aim(p, point);
			BlockState at = level.getBlockState(cell);
			if (at.is(Blocks.WALL_TORCH) || at.is(Blocks.TORCH)) {
				Exchange.emit("torch", "action", "placed", "x", cell.getX(), "y", cell.getY(), "z", cell.getZ(), "left", count(p));
				wall = null;
				return true;
			}
			if (now - placingSince > PLACE_WAIT) {
				refused.put(wall, now + 60_000);
				Exchange.emit("torch", "action", "fails", "x", cell.getX(), "y", cell.getY(), "z", cell.getZ(), "clicked", clicked);
				wall = null;
			}
			return true;
		}
		if (craft(mc, p, now)) return true;
		if (now < nextTry || !p.onGround()) return false;
		nextTry = now + TRY_EVERY;
		BlockPos feet = p.blockPosition(), head = feet.above();
		if (level.getBrightness(LightLayer.SKY, head) > 0) return false;
		if (level.getBrightness(LightLayer.BLOCK, head) >= DARK || level.getBrightness(LightLayer.BLOCK, feet) >= DARK) return false;
		if (count(p) == 0) { Needs.want("torches", "{me} has no torches for your tunnel."); return false; }
		List<Direction> dirs = new ArrayList<>();
		if (forward != null) { dirs.add(forward.getClockWise()); dirs.add(forward.getCounterClockWise()); }
		else for (Direction d : Direction.Plane.HORIZONTAL) dirs.add(d);
		Vec3 eye = p.getEyePosition();
		BlockPos bestWall = null, bestCell = null;
		Direction bestDir = null;
		double bestD = Double.MAX_VALUE;
		for (int n = 0; n < dirs.size(); n++) {
			Direction d = dirs.get(n);
			for (int k = 0; k <= 2; k++) {
				BlockPos c = head.relative(d, k), w = c.relative(d);
				if (!level.getBlockState(c).isAir()) break;
				BlockState ws = level.getBlockState(w);
				if (ws.isAir()) continue;
				if (!ws.isFaceSturdy(level, w, d.getOpposite()) || dug.test(w) || level.getBlockEntity(w) != null || refused.containsKey(w)) break;
				Vec3 at = Vec3.atCenterOf(w).subtract(d.getStepX() * 0.5, 0, d.getStepZ() * 0.5);
				// in a tunnel the right-hand wall wins whenever it is there at all
				double cost = eye.distanceTo(at) + (forward != null ? n * 100 : 0);
				if (eye.distanceTo(at) <= 4 && cost < bestD) { bestD = cost; bestWall = w; bestCell = c; bestDir = d; }
				break;
			}
		}
		if (bestWall == null) return false;
		if (!p.getMainHandItem().is(Items.TORCH)) {
			if (AfkHelperClient.toHotbar(p, mc, st -> st.is(Items.TORCH), Comparator.comparingInt(ItemStack::getCount), "torch")) return true;
			for (int i = 0; i < 9; i++) if (p.getInventory().getItem(i).is(Items.TORCH)) { p.getInventory().setSelectedSlot(i); break; }
			if (!p.getMainHandItem().is(Items.TORCH)) return false;
		}
		wall = bestWall;
		cell = bestCell;
		face = bestDir.getOpposite();
		point = Vec3.atCenterOf(bestWall).subtract(bestDir.getStepX() * 0.5, 0, bestDir.getStepZ() * 0.5);
		placingSince = now;
		clicked = false;
		Follow.stand();
		Follow.aim(p, point);
		return true;
	}

	/** Start of the tick: the click with the torch, if the crosshair is on that face of that wall. */
	static void startTick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (wall == null || clicked || p == null || mc.gameMode == null || p.isShiftKeyDown()) return;
		if (!(mc.hitResult instanceof BlockHitResult bh) || !bh.getBlockPos().equals(wall) || bh.getDirection() != face) return;
		if (!p.getMainHandItem().is(Items.TORCH)) return;
		InteractionResult r = AfkHelperClient.useOn(p, InteractionHand.MAIN_HAND, bh);
		if (r.consumesAction()) Game.swingUse(p, InteractionHand.MAIN_HAND);
		clicked = true;
	}

	/**
	 * Torches from coal (or charcoal) and a stick, in the 2x2 grid of the buddy's own inventory, one at a time: coal top left,
	 * stick under it, the result shift-clicked out (four torches). Real clicks, a few ticks apart, as a player does.
	 */
	private static boolean craft(Minecraft mc, LocalPlayer p, long now) {
		if (craftStep == 0) {
			if (!Work.dig || count(p) >= MIN || now < nextCraft || p.containerMenu != p.inventoryMenu || mc.gameMode == null) return false;
			coalSlot = stickSlot = -1;
			for (int i = 0; i < 36; i++) {
				ItemStack st = p.getInventory().getItem(i);
				if (coalSlot < 0 && (st.is(Items.COAL) || st.is(Items.CHARCOAL))) coalSlot = i;
				if (stickSlot < 0 && st.is(Items.STICK)) stickSlot = i;
			}
			if (coalSlot < 0 || stickSlot < 0) { nextCraft = now + 60_000; return false; }
			// no room for four torches: the result stays in the grid and the clicks make nothing, eight every 1.6 s (the
			// review of 22 Oct 2026)
			if (!room(p)) { nextCraft = now + 60_000; return false; }
			craftBefore = count(p);
			craftStep = 1;
			craftNext = now;
		}
		if (now < craftNext) return true;
		craftNext = now + 150;
		int menu = p.inventoryMenu.containerId;
		int coal = coalSlot < 9 ? 36 + coalSlot : coalSlot, stick = stickSlot < 9 ? 36 + stickSlot : stickSlot;
		switch (craftStep++) {
			case 1 -> Game.click(mc.gameMode, menu, coal, 0, false, p);    // the coal on the cursor
			case 2 -> Game.click(mc.gameMode, menu, 1, 1, false, p);       // one in the grid, top left
			case 3 -> Game.click(mc.gameMode, menu, coal, 0, false, p);    // the rest back
			case 4 -> Game.click(mc.gameMode, menu, stick, 0, false, p);
			case 5 -> Game.click(mc.gameMode, menu, 3, 1, false, p);       // one stick under it
			case 6 -> Game.click(mc.gameMode, menu, stick, 0, false, p);
			case 7 -> Game.quickMove(mc.gameMode, menu, 0, p);             // the torches out
			default -> {
				// what may still lie in the grid back into the inventory
				for (int s = 1; s <= 4; s++) if (!p.inventoryMenu.slots.get(s).getItem().isEmpty()) Game.quickMove(mc.gameMode, menu, s, p);
				craftStep = 0;
				// nothing came of it (the server refused, the grid was not empty): a minute before the next try
				if (count(p) <= craftBefore) { nextCraft = now + 60_000; Exchange.emit("torch", "action", "not made", "now", count(p)); return true; }
				nextCraft = now + 400;
				Exchange.emit("torch", "action", "made", "now", count(p));
				if (count(p) >= MIN) Needs.solved("torches");
				return true;
			}
		}
		return true;
	}

	static void stop() { wall = null; craftStep = 0; }

	private static int craftBefore;

	/** Room in the inventory for four torches: an empty slot, or a stack of torches with four places left. */
	private static boolean room(LocalPlayer p) {
		for (int i = 0; i < 36; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (st.isEmpty() || (st.is(Items.TORCH) && st.getCount() <= st.getMaxStackSize() - 4)) return true;
		}
		return false;
	}
}
