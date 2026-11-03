package net.milenco.buddy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.milenco.buddy.version.Game;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Empty the alt's inventory, on request from Telegram: into the nearest chests or else onto the
 * ground. Only for the alt; a player at the keys tidies up by hand.
 *
 * The lumberjack has a chest routine too, but that one is full of wood rules (keep saplings, chest
 * roles). This one is deliberately bare: open, everything in that may go, close, and on to the next
 * chest if needed.
 */
public final class Unloader {
	private Unloader() {}

	private enum Phase { OFF, SEARCH, WALK, TURN, OPEN, DUMP, CLOSE, THROW }

	private static Phase phase = Phase.OFF;
	private static boolean toGround = false, toolsToo = false;
	private static BlockPos chest = null;
	/** Following someone: where to stand to reach the chest (Work's choice of cell), walked to by Follow's paths. */
	private static BlockPos stand = null;
	private static AbstractContainerMenu menu = null;
	private static final Set<BlockPos> tried = new HashSet<>();
	private static long waitUntil = 0, started = 0, stepStarted = 0;
	private static boolean woodBackOn = false;
	private static int dumped = 0, thrown = 0;
	private static final AfkHelperClient.Look look = new AfkHelperClient.Look();

	public static boolean isBusy() { return phase != Phase.OFF; }

	/** Only these inventory slots go (as numbered in the inventaris event); empty = whatever may go. */
	private static final Set<Integer> onlySlots = new HashSet<>();

	/**
	 * Start unloading. where = "chest" or "ground"; everything also means tools, weapons and food. With slots
	 * only those go, whatever is in them: naming a slot is asking for that item.
	 */
	public static void start(String where, boolean everything, Set<Integer> slots) {
		LocalPlayer p = AfkHelperClient.mc().player;
		if (p == null) return;
		toGround = where.startsWith("ground") || where.startsWith("floor");
		toolsToo = everything;
		onlySlots.clear(); onlySlots.addAll(slots);
		tried.clear();
		dumped = thrown = 0;
		started = stepStarted = System.currentTimeMillis();
		// the lumberjack holds the keys down; set it aside first and turn it back on after unloading
		woodBackOn = Lumberjack.isOn();
		if (woodBackOn) Lumberjack.stop("unload");
		Builder.stop("unload");
		phase = toGround ? Phase.THROW : Phase.SEARCH;
		Exchange.emit("unload", "action", "start", "where", toGround ? "ground" : "chest", "all", everything, "slots", onlySlots.toString());
	}

	public static void stop(String why) {
		if (phase == Phase.OFF) return;
		LocalPlayer p = AfkHelperClient.mc().player;
		if (p != null && menu != null && p.containerMenu == menu) p.closeContainer();
		var mc = AfkHelperClient.mc();
		HeldKeys.set(mc.options.keyUp, false); HeldKeys.set(mc.options.keySprint, false);
		Exchange.emit("unload", "action", "done", "reason", why, "dumped", dumped, "thrown", thrown, "wood_on_again", woodBackOn);
		phase = Phase.OFF; menu = null; chest = null; stand = null;
		if (woodBackOn) { woodBackOn = false; AfkHelperClient.later(2.0, Lumberjack::start); }
	}

	/**
	 * May this go? Tools, weapons, armour, the compass and the food stay, because the alt lives on
	 * those; with "everything" those go too.
	 */
	private static boolean mayGo(net.minecraft.world.inventory.Slot slot, LocalPlayer p) {
		ItemStack st = slot.getItem();
		if (st.isEmpty()) return false;
		if (!onlySlots.isEmpty()) return slot.container == p.getInventory() && onlySlots.contains(slot.getContainerSlot());
		if (toolsToo) return true;
		if (st.isDamageableItem()) return false;
		if (st.getItem() == net.minecraft.world.item.Items.COMPASS) return false;
		return st.get(net.minecraft.core.component.DataComponents.FOOD) == null;
	}

	/** Slots 9-44 of a chest menu are your own inventory; which of those may go. */
	private static int nextSlotIn(AbstractContainerMenu m, int chestSlots, LocalPlayer p) {
		for (int i = chestSlots; i < m.slots.size(); i++) if (mayGo(m.slots.get(i), p)) return i;
		return -1;
	}

	static void tick(Minecraft mc, LocalPlayer p) {
		if (phase == Phase.OFF) return;
		long now = System.currentTimeMillis();
		if (now < waitUntil) return;
		if (now - started > 300_000) { stop("took too long"); return; }
		// following someone and in a fight: that first, then on with the chests
		if (Follow.isOn() && Guard.enemy() != null) { waitUntil = now + 200; return; }
		if (look.busy()) { look.tick(p); waitUntil = now + 50; return; }

		switch (phase) {
			case THROW -> {
				int slot = -1;
				for (int i = 0; i < p.inventoryMenu.slots.size(); i++) {
					if (mayGo(p.inventoryMenu.slots.get(i), p)) { slot = i; break; }
				}
				if (slot < 0 || p.containerMenu != p.inventoryMenu) { stop("nothing left to drop"); return; }
				Game.drop(mc.gameMode, p.inventoryMenu.containerId, slot, p);
				thrown++;
				waitUntil = now + ThreadLocalRandom.current().nextLong(120, 320);   // not everything in one tick, that looks like a macro
			}
			case SEARCH -> {
				chest = nearestChest(p);
				if (chest == null) { stop(dumped > 0 ? "no chest near any more" : "no chest within reach"); return; }
				stepStarted = now;
				stand = null;
				// following someone the walk is walkTick's, by Follow's paths: also to a chest a step up or round a corner
				phase = Follow.isOn() || Math.sqrt(chest.distSqr(p.blockPosition())) > 3.5 ? Phase.WALK : Phase.TURN;
				if (phase == Phase.TURN) look.startByAngle(p, center(chest), 0.3f);
			}
			case WALK -> {
				if (Follow.isOn()) return;
				// the wood field is flat and open, so just walk to it: turn and go forward until you stand next to it
				double d = Math.sqrt(chest.distSqr(p.blockPosition()));
				if (d <= 3.0 || now - stepStarted > 30_000) {
					HeldKeys.set(mc.options.keyUp, false); HeldKeys.set(mc.options.keySprint, false);
					if (d > 4.5) { tried.add(chest); phase = Phase.SEARCH; return; }   // did not get closer: another chest
					look.startByAngle(p, center(chest), 0.3f);
					phase = Phase.TURN;
					return;
				}
				double dx = chest.getX() + 0.5 - p.getX(), dz = chest.getZ() + 0.5 - p.getZ();
				float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
				float delta = net.minecraft.util.Mth.wrapDegrees(yaw - p.getYRot());
				MouseTurn.toward(p, p.getYRot() + net.minecraft.util.Mth.clamp(delta, -12f, 12f), p.getXRot() + net.minecraft.util.Mth.clamp(5f - p.getXRot(), -6f, 6f));
				HeldKeys.set(mc.options.keyUp, Math.abs(delta) < 50);
				HeldKeys.set(mc.options.keySprint, Math.abs(delta) < 20 && d > 8);
				waitUntil = now + 50;
			}
			case TURN -> {
				// only click once the crosshair is really on the chest, otherwise you open a block next to it
				BlockHitResult hit = mc.hitResult instanceof BlockHitResult b ? b : null;
				if (hit == null || !hit.getBlockPos().equals(chest)) {
					Vec3 seen = Work.aimPoint(p.level(), p, p.getEyePosition(), chest);
					look.startByAngle(p, seen != null ? seen : center(chest), 0.15f);
					if (now - stepStarted > 20_000) { tried.add(chest); phase = Phase.SEARCH; }
					waitUntil = now + 100;
					return;
				}
				mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
				phase = Phase.OPEN;
				waitUntil = now + 300;
			}
			case OPEN -> {
				if (p.containerMenu == p.inventoryMenu) {
					if (now - started > 30_000) { tried.add(chest); phase = Phase.SEARCH; }
					waitUntil = now + 200;
					return;
				}
				menu = p.containerMenu;
				phase = Phase.DUMP;
				waitUntil = now + ThreadLocalRandom.current().nextLong(200, 500);
			}
			case DUMP -> {
				if (p.containerMenu != menu) { tried.add(chest); phase = Phase.SEARCH; return; }
				int chestSlots = menu.slots.size() - 36;
				int slot = nextSlotIn(menu, chestSlots, p);
				if (slot < 0) { phase = Phase.CLOSE; return; }
				ItemStack before = menu.slots.get(slot).getItem().copy();
				int inBefore = countIn(menu, chestSlots, before);
				Game.quickMove(mc.gameMode, menu.containerId, slot, p);
				// Into the chest, or nowhere (full): the next chest. A shift-click that only moves it between the hotbar
				// and the backpack, as in a menu that does not take it, went round and round for minutes (17 Oct 2026).
				if (countIn(menu, chestSlots, before) <= inBefore) { tried.add(chest); phase = Phase.CLOSE; }
				else dumped++;
				waitUntil = now + ThreadLocalRandom.current().nextLong(120, 300);
			}
			case CLOSE -> {
				p.closeContainer();
				menu = null;
				tried.add(chest);
				boolean more = false;
				for (int i = 0; i < p.inventoryMenu.slots.size(); i++) if (mayGo(p.inventoryMenu.slots.get(i), p)) more = true;
				if (!more) { stop("all cleared"); return; }
				phase = Phase.SEARCH;
				waitUntil = now + 400;
			}
			default -> { }
		}
	}

	/**
	 * End of the tick, from Follow while the alt follows someone and this is busy. The walk to the chest goes by
	 * Follow's own paths and jumps, to a cell Work picks from which the chest can be seen; for the rest of the
	 * unloading the alt stands still. Before, Follow went on following meanwhile and turned the alt toward the player every
	 * tick, against this class's turn toward the chest, and the straight walk did not take a step up: 44 seconds
	 * before a chest a few blocks off opened (seen at {@code !dump}).
	 */
	static boolean walkTick(Minecraft mc, LocalPlayer p, Player t) {
		if (phase != Phase.WALK || chest == null) { Follow.stand(); return true; }
		long now = System.currentTimeMillis();
		Level level = p.level();
		Vec3 eye = p.getEyePosition();
		Vec3 seen = Work.aimPoint(level, p, eye, chest);
		if (seen != null && eye.distanceTo(seen) <= 4.0) {
			Follow.stand();
			look.startByAngle(p, seen, 0.3f);
			stepStarted = now;
			phase = Phase.TURN;
			return true;
		}
		if (now - stepStarted > 30_000) { tried.add(chest); stand = null; phase = Phase.SEARCH; Follow.stand(); return true; }
		if (stand == null) stand = Work.standCell(level, p, chest, t, f -> true);
		// nowhere to stand, or no step closer for four seconds (Work.approach says so): the next chest
		if (stand == null || !Work.approach(mc, p, stand, Vec3.atBottomCenterOf(stand), now)) {
			tried.add(chest);
			stand = null;
			phase = Phase.SEARCH;
			Follow.stand();
		}
		return true;
	}

	/** How many of this item lie in the chest's own slots. */
	private static int countIn(AbstractContainerMenu m, int chestSlots, ItemStack like) {
		int n = 0;
		for (int i = 0; i < chestSlots; i++) {
			ItemStack st = m.slots.get(i).getItem();
			if (ItemStack.isSameItemSameComponents(st, like)) n += st.getCount();
		}
		return n;
	}

	private static Vec3 center(BlockPos b) { return new Vec3(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5); }

	/**
	 * Nearest chest, barrel or shulker box within 24 blocks that we have not tried yet. Only those: a furnace, a
	 * brewing stand or a crafter is a container too, and on 17 Oct 2026 a furnace was the nearest.
	 */
	private static BlockPos nearestChest(LocalPlayer p) {
		if (p.level() == null) return null;
		BlockPos own = p.blockPosition();
		List<BlockPos> candidates = new ArrayList<>();
		for (int dx = -24; dx <= 24; dx++) for (int dy = -4; dy <= 4; dy++) for (int dz = -24; dz <= 24; dz++) {
			BlockPos b = own.offset(dx, dy, dz);
			if (tried.contains(b)) continue;
			var be = p.level().getBlockEntity(b);
			if (be instanceof net.minecraft.world.level.block.entity.ChestBlockEntity
					|| be instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity
					|| be instanceof net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity) candidates.add(b);
		}
		BlockPos best = null; double nearest = Double.MAX_VALUE;
		for (BlockPos b : candidates) {
			double d = b.distSqr(own);
			if (d < nearest) { nearest = d; best = b; }
		}
		return best;
	}
}
