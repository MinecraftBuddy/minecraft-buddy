package net.milenco.buddy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.milenco.buddy.version.Game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Wood cutting on the tree farm: chop the trees that have grown, put back a sapling of the same kind,
 * pick up the drops, and only when the field is nearly empty do a bonemeal round. Everything through the
 * real keys and the real crosshair (breaking only the block that is under the crosshair, never through
 * leaves or out of reach), so the server sees exactly what a player would do too.
 *
 * The field is flat, with glass at +10 above every sapling: then nothing grows above +7 and everything can
 * be chopped from the floor (0 and +1 from the path, then up the trunk column).
 */
public class Lumberjack {
	// ---------- state ----------
	private static boolean isOn = false;
	private static boolean paused = false;           // player nearby, or typing for a moment
	private static long pausedUntil = 0, lastPlayer = 0;
	private static final ArrayDeque<Step> steps = new ArrayDeque<>();
	private static Step current = null;
	private static long nextStepAfter = 0;          // short breather between steps
	private static long lastPickup = 0, lastPlan = 0, lastChest = 0, lastBonemealRound = 0;
	/** Chest visited and there were no saplings: do not walk there again every round. */
	private static boolean chestWithoutSaplings = false, shortForGroup = false;
	/** Finishing: complete this module, plant, pick up, empty into the chest and then stop. */
	private static boolean finishing = false;
	public static boolean finishing() { return finishing; }
	public static void setFinishing(boolean on) {
		if (finishing == on) return;
		finishing = on;
		Exchange.emit("wood", "action", on ? "finishing on" : "finishing off");
	}

	/** Is the buddy carrying something that belongs in the drop-off chest (wood, apples, sticks)? */
	private static boolean somethingForTheChest(LocalPlayer p) {
		for (int i = 0; i < 36; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (!st.isEmpty() && treeStuff(st) && !st.is(ItemTags.SAPLINGS) && !st.is(Items.BONE_MEAL)) return true;
		}
		return false;
	}
	/** How often a plot could not be planted in a row: twice is enough to leave it alone for a while. */
	private static final Map<BlockPos, Integer> plantFailed = new HashMap<>();
	/** Kinds of which we found nothing at the last chest visit, with the moment: do not fetch again for ten minutes. */
	private static final Map<String, Long> noSaplingsInChest = new HashMap<>();

	/** According to the last chest visit, is this kind not in the chest after all? */
	private static boolean chestLacks(String kind, long now) {
		Long t = noSaplingsInChest.get(kind);
		return t != null && now - t < 10 * 60_000L;
	}
	private static long chestWithoutSaplingsSince = 0;
	private static int lastRow = Integer.MIN_VALUE, treesThisRound = 0, modules = 0;   // module = one wood kind worked through completely
	private static boolean rowReversed = false;
	private static final Map<BlockPos, String> plantLater = new HashMap<>();   // plot -> sapling item
	private static final Map<BlockPos, Integer> bonemealAttempts = new HashMap<>();
	private static final Map<BlockPos, Integer> breakFailed = new HashMap<>();
	private static final Map<Item, Integer> harvest = new HashMap<>(), previousCount = new HashMap<>();
	private static boolean rebaseline = true;   // recalibrate after a chest: what you take there is not yield
	private static long startedAt = 0, nextBreak = 0;   // per plot: how often breaking went wrong there
	private static final Set<BlockPos> fullChests = new HashSet<>();
	/** Role per chest, learned when opening it: if there is a sapling in it, it is the sapling chest and nothing else goes in there. The code is what the events carry. */
	enum ChestRole {
		UNKNOWN("unknown"), SAPLINGS("saplings"), DROPOFF("dropoff");
		final String code; ChestRole(String code) { this.code = code; }
	}
	private static final Map<BlockPos, ChestRole> chestRole = new HashMap<>();
	enum ChestPurpose {
		SAPLINGS("saplings"), DROPOFF("dropoff");
		final String code; ChestPurpose(String code) { this.code = code; }
	}
	private static boolean chestWithoutBonemeal = false; private static long chestWithoutBonemealSince = 0;
	private static final Map<BlockPos, Long> chopped = new HashMap<>();   // plot -> when the tree came down (for the wait on saplings)
	private static final Map<BlockPos, Long> skipped = new HashMap<>();   // plot -> until when we leave it alone
	private static int axeSlot = -1;
	private static String stopReason = null;
	private static boolean stockAtStart = false;
	private static boolean chopFirst = false;
	private static int chopFirstRow = Integer.MIN_VALUE;   // which row that was: that row belongs to this kind's round again
	private static String activeKind = null;                       // the kind we are working on now, until all its trunks are gone
	private static final Map<String, Long> kindFinishedAt = new HashMap<>();   // when a kind was last finished: that one goes to the back
	private static final Set<Integer> rowsToDo = new HashSet<>();        // the rows of the active kind that are still due this round
	private static Vec3 noPathFrom = null; private static int noPathCount = 0;
	/** After a teleport (tpa, AFK world, reconnect) with AFK on: until this moment, back on by itself as soon as you stand still in the field. */
	private static long resumeUntil = 0, resumeStillSince = 0; private static Vec3 resumeStillAt = null;   // "no path" from the same spot: then the problem is where you stand, not the tree   // while planting a tree grew on the path: chop first, then continue planting   // when switching on, first pass the chest for all the saplings this field is going to ask for
	private static final AfkHelperClient.Look look = new AfkHelperClient.Look();
	private static final ThreadLocalRandom R = ThreadLocalRandom.current();

	private static final int BUSY = 0, DONE = 1, FAILED = 2;

	/**
	 * We break and right-click ourselves at the start of the tick, exactly as vanilla does it in
	 * handleKeybinds, but without the requirement that the window holds the mouse: with the attack key
	 * nothing happened as soon as the owner looked at another window.
	 */
	private static BlockPos hitTarget = null, useTarget = null; private static boolean wasHitting = false;
	private static BlockPos freeTarget = null;   // block you are stuck in: we chop that one loose regardless of the crosshair
	private static PlantOnTheWay plantingStep = null;   // the step that is placing saplings now, so startTick can place right away
	/** Between two logs the button stays down: whatever comes under the crosshair then (leaf, log) breaks too, like with a real player. */
	private static boolean holdAttack = false;
	public static void startTick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.gameMode == null) return;
		// with a chest or inventory open, or while eating, a player does not hit or click: then neither do we
		if (AfkHelperClient.isEating() || Game.openScreen(mc) instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) {
			if (wasHitting) { mc.gameMode.stopDestroyBlock(); wasHitting = false; }
			hitTarget = null; useTarget = null;
			return;
		}
		// Stuck in a block: you cannot point at that block with the crosshair (the ray starts inside it), so we chop it
		// away directly, with the face that points at you. That is how a player gets free too when a tree grows around that player.
		if (freeTarget != null) {
			BlockState st = p.level().getBlockState(freeTarget);
			if (st.isAir() || st.getCollisionShape(p.level(), freeTarget).isEmpty()) { mc.gameMode.stopDestroyBlock(); wasHitting = false; freeTarget = null; }
			else {
				if (axeSlot >= 0 && p.getInventory().getSelectedSlot() != axeSlot) { p.getInventory().setSelectedSlot(axeSlot); return; }
				BlockPos feet = p.blockPosition();
				Direction side = freeTarget.getY() > feet.getY() ? Direction.DOWN : freeTarget.getY() < feet.getY() ? Direction.UP
					: Direction.getApproximateNearest(feet.getX() - freeTarget.getX(), 0, feet.getZ() - freeTarget.getZ());
				if (mc.gameMode.continueDestroyBlock(freeTarget, side)) Game.swingAttack(p);
				wasHitting = true;
				return;
			}
		}
		BlockPos under = mc.hitResult instanceof BlockHitResult b0 && b0.getType() == HitResult.Type.BLOCK ? b0.getBlockPos() : null;
		boolean hit = under != null && (under.equals(hitTarget)
			|| (holdAttack && hitTarget == null && (p.level().getBlockState(under).is(BlockTags.LOGS) || p.level().getBlockState(under).is(BlockTags.LEAVES)) && withinReach(p, under)));
		if (hit && !p.level().getBlockState(under).isAir()) {
			// safety net: normally the axe is already in hand from the start of the chop step
			if (axeSlot >= 0 && p.getInventory().getSelectedSlot() != axeSlot) { p.getInventory().setSelectedSlot(axeSlot); if (wasHitting) { mc.gameMode.stopDestroyBlock(); wasHitting = false; } return; }
			if (mc.gameMode.continueDestroyBlock(under, ((BlockHitResult) mc.hitResult).getDirection())) Game.swingAttack(p);
			wasHitting = true;
		} else if (wasHitting) { mc.gameMode.stopDestroyBlock(); wasHitting = false; }
		hitTarget = null;   // BreakBlock sets it again every tick as long as the crosshair is right
		// Planting on the way: decide and place in the same tick, with the crosshair as it is now. A click that was only
		// executed a tick later fell through because the crosshair had already moved on - hence the misses.
		if (plantingStep != null && useTarget == null && mc.hitResult instanceof BlockHitResult pb
			&& pb.getType() == HitResult.Type.BLOCK && pb.getDirection() == Direction.UP) {
			BlockPos plot = pb.getBlockPos().above();
			if (plantingStep.allowedHere(p, plot)) {
				var r = mc.gameMode.useItemOn(p, net.minecraft.world.InteractionHand.MAIN_HAND, pb);
				if (r.consumesAction()) { Game.swingUse(p, net.minecraft.world.InteractionHand.MAIN_HAND); plantingStep.placed(); }
			}
		}
		if (useTarget != null) {
			if (mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK && b.getBlockPos().equals(useTarget)) {
				var r = mc.gameMode.useItemOn(p, net.minecraft.world.InteractionHand.MAIN_HAND, b);
				if (r.consumesAction()) Game.swingUse(p, net.minecraft.world.InteractionHand.MAIN_HAND);
			}
			useTarget = null;
		}
	}
	/** Right-click this block, once, on the next tick. */
	private static void clickOn(BlockPos pos) { useTarget = pos; }
	/** How high up a column above the sapling we chop at most: with glass at +10 nothing gets above +7. */
	private static final int MAX_HEIGHT = 9;
	private static final double BRANCH_RADIUS = 5;

	// ---------- on/off ----------
	public static boolean isOn() { return isOn; }
	/** Busy with something during which the rest of the mod (wandering, looking around, sneaking) must keep off the keys. */
	public static boolean isBusy() { return isOn && !paused && !(current instanceof Wait); }

	public static void start() {
		AfkConfig c = AfkConfig.get();
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null) return;
		// only look at x/z: the floor height comes from the scan below, so on a first run it is still 0
		if (!inPlane(p.blockPosition(), 6)) {  return; }
		// switching on is one action: read the field as it is now (only trunks and saplings, no old list), then get going
		String scanned = scan(p);
		if (c.woodPlots.isEmpty()) {  return; }
		Jobs.switchedOn(Jobs.Job.WOOD, "chopping on");
		Wander.abort("chopping on");
		isOn = true; paused = false; stopReason = null; finishing = false;
		steps.clear(); current = null;
		lastPickup = System.currentTimeMillis(); lastPlan = 0; lastRow = Integer.MIN_VALUE; treesThisRound = 0; modules = 0;
		plantLater.clear(); bonemealAttempts.clear(); breakFailed.clear();
		harvest.clear(); previousCount.clear(); rebaseline = true; startedAt = System.currentTimeMillis();
		nextBreak = startedAt + (long) (R.nextDouble(0.6, 1.4) * Math.max(1, c.woodPauseEveryMin) * 60_000); fullChests.clear(); chestWithoutBonemeal = false; chopped.clear();
		skipped.clear(); chopFirst = false; chopFirstRow = Integer.MIN_VALUE; activeKind = null; kindFinishedAt.clear(); chestRole.clear(); rowsToDo.clear();   // a plot that went wrong last time may simply join in again now
		stockAtStart = !c.woodChests.isEmpty();
		   // the scan results are in events.jsonl, not in the chat
		Exchange.emit("wood", "action", "start", "plots", c.woodPlots.size(), "chests", c.woodChests.size());
	}

	public static void stop(String why) {
		if (!isOn) return;
		boolean involuntary = why.equals("teleport") || why.equals("outside the field") || why.equals("another world");
		resumeUntil = involuntary && AfkConfig.get().afk ? System.currentTimeMillis() + 3 * 60 * 60_000L : 0;
		isOn = false; paused = false; stopReason = why; finishing = false;
		releaseKeys(); steps.clear(); current = null;
		LocalPlayer p = Minecraft.getInstance().player;
		if (p != null && p.containerMenu != p.inventoryMenu) p.closeContainer();
		long duration = startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt;
		Exchange.emit("wood", "action", "stop", "why", why, "minutes", duration / 60_000, "modules", modules, "harvest", harvestToMap());
	}

	/** Once a second from the client: after an involuntary stop, back on as soon as you stand still in the field for five seconds with AFK on. */
	public static void resumeTick(LocalPlayer p, AfkConfig c) {
		if (isOn || resumeUntil == 0) return;
		long now = System.currentTimeMillis();
		if (now > resumeUntil || !c.afk) { resumeUntil = 0; return; }
		if (!inField(p.blockPosition(), 2) || !AfkHelperClient.active()) { resumeStillAt = null; return; }
		if (resumeStillAt == null || p.position().distanceTo(resumeStillAt) > 0.5) { resumeStillAt = p.position(); resumeStillSince = now; return; }
		if (now - resumeStillSince < 5000) return;
		resumeUntil = 0;
		Exchange.emit("wood", "action", "resumed");
		start();
	}

	/** Hold still for a moment, for example because someone is typing. */
	public static void pause(long ms, String why) {
		if (!isOn) return;
		pausedUntil = Math.max(pausedUntil, System.currentTimeMillis() + ms);
		if (!paused) { paused = true; releaseKeys(); if (current != null) { steps.addFirst(current); current = null; } Exchange.emit("wood", "action", "pause", "why", why); }
	}

	private static void releaseKeys() {
		hitTarget = null; useTarget = null; holdAttack = false; plantingStep = null; freeTarget = null;
		if (wasHitting) { var gm = Minecraft.getInstance().gameMode; if (gm != null) gm.stopDestroyBlock(); wasHitting = false; }
		var o = Minecraft.getInstance().options;
		HeldKeys.set(o.keyUp, false); HeldKeys.set(o.keyDown, false); HeldKeys.set(o.keyLeft, false); HeldKeys.set(o.keyRight, false);
		HeldKeys.set(o.keyAttack, false); HeldKeys.set(o.keyShift, false); HeldKeys.set(o.keySprint, false);   // keyUse belongs to the eating and drinking logic
	}

	/** Whatever tree stuff is added to your inventory is yield; what you take out of a chest is not. */
	private static void countHarvest(LocalPlayer p) {
		if (p.containerMenu != p.inventoryMenu) { rebaseline = true; return; }
		Map<Item, Integer> now = new HashMap<>();
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (!s.isEmpty() && treeStuff(s)) now.merge(s.getItem(), s.getCount(), Integer::sum);
		}
		if (!rebaseline) for (var e : now.entrySet()) {
			int added = e.getValue() - previousCount.getOrDefault(e.getKey(), 0);
			if (added > 0) harvest.merge(e.getKey(), added, Integer::sum);
		}
		previousCount.clear(); previousCount.putAll(now); rebaseline = false;
	}

	/** Logs chopped since the start, all kinds together (for the yield log). */
	static int logs() {
		int n = 0;
		for (var e : harvest.entrySet()) if (new ItemStack(e.getKey()).is(ItemTags.LOGS)) n += e.getValue();
		return n;
	}

	public static int modules() { return modules; }
	public static String currentKind() { return activeKind == null ? "" : activeKind.replace("minecraft:", "").replace("_sapling", ""); }

	private static Map<String, Integer> harvestToMap() {
		Map<String, Integer> out = new HashMap<>();
		for (var e : harvest.entrySet()) out.put(itemId(e.getKey()).replace("minecraft:", ""), e.getValue());
		return out;
	}

	private static String time(long ms) {
		long m = ms / 60_000;
		return m >= 60 ? String.format(java.util.Locale.ROOT, "%sh %sm", m / 60, m % 60) : String.format(java.util.Locale.ROOT, "%sm", m);
	}

	// ---------- scan ----------
	/**
	 * Records the plots (ground with a sapling or trunk on it) and the chests in the field, as the field is
	 * right now: no old list added. The floor is where you stand, unless a floor height is already known and
	 * you are close to it (then you may also stand on the cobble or an edge).
	 */
	public static String scan(LocalPlayer p) {
		AfkConfig c = AfkConfig.get();
		Level level = p.level();
		int py = p.blockPosition().getY();
		String dim = level.dimension().identifier().toString();
		if (c.woodY == 0 || Math.abs(py - c.woodY) > 3 || !dim.equals(c.woodDim)) c.woodY = py;
		c.woodDim = dim;
		int x1 = Math.min(c.woodX1, c.woodX2), x2 = Math.max(c.woodX1, c.woodX2), z1 = Math.min(c.woodZ1, c.woodZ2), z2 = Math.max(c.woodZ1, c.woodZ2);
		List<AfkConfig.WoodPlot> plots = new ArrayList<>(); List<AfkConfig.ChestPos> chests = new ArrayList<>();
		Set<BlockPos> seen = new HashSet<>();
		for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) for (int y = c.woodY - 2; y <= c.woodY + 1; y++) {
			BlockPos pos = new BlockPos(x, y, z);
			BlockState st = level.getBlockState(pos);
			if (st.getBlock() instanceof ChestBlock || st.getBlock() instanceof BarrelBlock) {
				// a double chest is one chest: both halves give the same menu, so record it once
				BlockPos k = Chests.canonical(pos);
				if (chests.stream().noneMatch(q -> q.pos().equals(k))) chests.add(new AfkConfig.ChestPos(k.getX(), k.getY(), k.getZ()));
				continue;
			}
			// the same ground the game allows a sapling on
			boolean ground = Game.plantable(level.getBlockState(pos.below()));
			if (!ground || seen.contains(pos)) continue;
			String sapling = null;
			if (st.getBlock() instanceof SaplingBlock) sapling = itemId(st.getBlock().asItem());
			else if (st.is(BlockTags.LOGS)) sapling = saplingForLog(st.getBlock());
			if (sapling != null) { plots.add(new AfkConfig.WoodPlot(x, y, z, sapling)); seen.add(pos); }
		}
		/*
		 * Keep the empty plots from the previous list. The scan only sees trunks and saplings, so a plot that
		 * was just chopped and not yet planted dropped out of the list: after switching off and on it never
		 * planted anything there again (26 Sep). The ground must still be there and the plot must
		 * fall inside the field and on the floor height, otherwise you drag old plots of a rebuilt field along.
		 */
		Set<BlockPos> fresh = new HashSet<>();
		for (var pl : plots) fresh.add(pl.pos());
		int kept = 0;
		for (var old : c.woodPlots) {
			BlockPos pos = old.pos();
			if (fresh.contains(pos) || pos.getY() != c.woodY) continue;
			if (pos.getX() < x1 || pos.getX() > x2 || pos.getZ() < z1 || pos.getZ() > z2) continue;
			if (!level.getBlockState(pos).isAir() || !Game.plantable(level.getBlockState(pos.below()))) continue;
			plots.add(old); kept++;
		}
		if (kept > 0) Exchange.emit("wood", "action", "empty spots kept", "count", kept);
		c.woodPlots = plots; c.woodChests = chests;
		AfkConfig.save();
		Map<String, Integer> perKind = new HashMap<>();
		for (var pl : plots) perKind.merge(pl.kind.replace("minecraft:", "").replace("_sapling", "").replace("_propagule", ""), 1, Integer::sum);
		Exchange.emit("wood", "action", "scan", "plots", plots.size(), "chests", chests.size(), "kinds", perKind);
		return String.format(java.util.Locale.ROOT, "%s plots (%s), %s chests", plots.size(), perKind, chests.size());
	}

	static String itemId(Item it) { return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it).toString(); }
	static String blockId(Block b) { return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).toString(); }

	/** Which sapling belongs to this log. Mangrove has a propagule, azalea grows like oak. */
	static String saplingForLog(Block log) {
		String id = blockId(log).replace("stripped_", "");
		if (id.contains("mangrove")) return "minecraft:mangrove_propagule";
		if (!id.endsWith("_log") && !id.endsWith("_wood")) return null;
		return id.replaceAll("_(log|wood)$", "_sapling");
	}

	/** Inside the x/z box of the field (without looking at the height). */
	private static boolean inPlane(BlockPos pos, int margin) {
		AfkConfig c = AfkConfig.get();
		int x1 = Math.min(c.woodX1, c.woodX2) - margin, x2 = Math.max(c.woodX1, c.woodX2) + margin, z1 = Math.min(c.woodZ1, c.woodZ2) - margin, z2 = Math.max(c.woodZ1, c.woodZ2) + margin;
		return pos.getX() >= x1 && pos.getX() <= x2 && pos.getZ() >= z1 && pos.getZ() <= z2;
	}

	private static boolean inField(BlockPos pos, int margin) {
		return inPlane(pos, margin) && Math.abs(pos.getY() - AfkConfig.get().woodY) <= 3;
	}

	// ---------- tick ----------
	/** Every tick. True if the mod itself is moving or turning. */
	public static boolean tick(LocalPlayer p, AfkConfig c) {
		if (!isOn) return false;
		Minecraft mc = Minecraft.getInstance();
		long now = System.currentTimeMillis();
		if (now - lastCount > 500) { lastCount = now; countHarvest(p); }
		if (p.isDeadOrDying()) { stop("dead"); return false; }
		if (!AfkHelperClient.active()) { stop("another server"); return false; }
		if (!c.woodDim.isEmpty() && !p.level().dimension().identifier().toString().equals(c.woodDim)) { stop("another world"); return false; }
		if (!inField(p.blockPosition(), 8)) { stop("outside the field"); return false; }

		// eating: stand still for a moment, swap nothing, hit nothing; the mod's eating logic needs the slot and the button
		if (AfkHelperClient.isEating()) {
			var o = mc.options; HeldKeys.set(o.keyUp, false); HeldKeys.set(o.keyDown, false); HeldKeys.set(o.keySprint, false);
			hitTarget = null; useTarget = null;
			return false;
		}
		// Freecam (or any camera other than your player): the keys then steer the camera and the crosshair is the camera's,
		// not your body's. Chopping what the camera sees is exactly what an anti-cheat flags as reach. So wait.
		if (mc.getCameraEntity() != null && mc.getCameraEntity() != p) {
			if (!paused) pause(500, "freecam");
			pausedUntil = Math.max(pausedUntil, now + 500);
			return false;
		}
		// The server does not see chat and the Esc menu, so it simply carries on through those (like the Grinder). A chest
		// or other container screen the server does see: there it waits, unless it is the chest of the ChestStep.
		var screen = Game.openScreen(mc);
		boolean container = screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>;
		if (container && !(current instanceof ChestStep)) {
			if (!paused) pause(300, "screen open");
			pausedUntil = Math.max(pausedUntil, now + 300);
			return false;
		}
		// player nearby: hold still, and only continue a minute after that player has left
		Player other = playerNearby(p, c.woodPauseRadius);
		if (other != null) { lastPlayer = now; if (!paused) pause(1000, "player " + other.getGameProfile().name()); }
		if (paused) {
			if (now < pausedUntil || now - lastPlayer < c.woodWaitAfterPlayerSec * 1000L) {
				// look around now and then as if you are waiting
				if (!look.busy() && R.nextInt(120) == 0) { look.start(p, p.getEyePosition().add(rndDir(6)), 1.5f); }
				return look.tick(p);
			}
			paused = false; Exchange.emit("wood", "action", "on");
		}
		// A tree has grown around you: chop yourself free instead of getting stuck. This comes before everything.
		BlockPos stuck = stuckBlock(p, c);
		if (stuck != null) {
			if (!stuck.equals(freeTarget)) {
				freeTarget = stuck;
				Exchange.emit("wood", "action", "stuck, chop me free", "block", stuck.toShortString(), "what", blockId(p.level().getBlockState(stuck).getBlock()).replace("minecraft:", ""));
				var o = mc.options; HeldKeys.set(o.keyUp, false); HeldKeys.set(o.keyDown, false); HeldKeys.set(o.keySprint, false);
			}
			look.tick(p);
			if (!look.busy()) look.startByAngle(p, center(stuck), 0.2f);
			return true;
		}
		if (freeTarget != null) { freeTarget = null; lastFreeing = now; }
		if (current == null) {
			// between two actions a breather now and then: a player does not chop for hours on end
			if (c.woodPauseEveryMin > 0 && now >= nextBreak && steps.isEmpty()) {
				long duration = R.nextLong(20_000, 60_000);
				nextBreak = now + (long) (R.nextDouble(0.6, 1.4) * c.woodPauseEveryMin * 60_000);
				Exchange.emit("wood", "action", "breather", "seconds", duration / 1000);
				pause(duration, "breather");
				return false;
			}
			if (now < nextStepAfter) return look.tick(p);
			current = steps.pollFirst();
			if (current == null) { plan(p, c, now); current = steps.pollFirst(); if (current == null) { nextStepAfter = now + 200; return false; } }
			current.begin(p);
			holdAttack = current.chop;
			// grab the axe as soon as a chop step begins (that is the walk to the tree), not just before the first
			// hit: a swap and a hit in the same tick stands out
			if (current.chop && axeSlot >= 0 && p.getInventory().getSelectedSlot() != axeSlot) p.getInventory().setSelectedSlot(axeSlot);
		}
		// hold on to the step beforehand: a step that puts itself back in the queue (leaf detour, branches) clears current itself
		Step s = current;
		int r;
		try { r = s.tick(p, mc, c); }
		catch (Exception e) { Exchange.emit("error", "where", "wood:" + s.name(), "notice", String.valueOf(e)); r = FAILED; }
		if (r != BUSY) {
			boolean selfScheduled = current == null;   // the step has already put itself (with an intermediate step in front) back in the queue
			current = null;
			Step next = steps.peekFirst();
			boolean keepChopping = r == DONE && s.chop && next != null && next.chop;
			if (keepChopping) { hitTarget = null; holdAttack = true; nextStepAfter = 0; }   // button stays down, straight on
			else if (selfScheduled) { nextStepAfter = 0; }                                // intermediate step: just carry on
			else { releaseKeys(); nextStepAfter = 0; }   // just carry on: the only pauses are those of AFK mode itself
			if (r == FAILED) {
				Exchange.emit("wood", "action", "step failed", "step", s.name());
				s.failed(p, c);
				// the rest of this row may have been thrown away: put the row back in the round, so it finishes it instead
				// of jumping to the next kind. The plot that went wrong is in 'skipped' and no longer takes part.
				if (s.chop && lastRow != Integer.MIN_VALUE) rowsToDo.add(lastRow);
			}
		}
		return true;
	}

	private static long lastCount = 0, lastFreeing = 0, lastBonemealExplanation = 0;

	/**
	 * Is there a trunk or leaf where you stand? Then a tree has grown around you. Also when you have ended up on
	 * top of it (above floor height) you chop away the block below you, so you get back on the floor.
	 */
	private static boolean treeBlock(BlockState st) { return st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES); }

	private static BlockPos stuckBlock(LocalPlayer p, AfkConfig c) {
		Level level = p.level();
		BlockPos feet = p.blockPosition();
		BlockState fs = level.getBlockState(feet), hs = level.getBlockState(feet.above());
		boolean inTree = (treeBlock(fs) && !fs.getCollisionShape(level, feet).isEmpty())
			|| (treeBlock(hs) && !hs.getCollisionShape(level, feet.above()).isEmpty());
		boolean belowFloor = p.getY() < c.woodY - 0.1;
		boolean onATree = p.getY() > c.woodY + 0.5 && p.onGround() && treeBlock(level.getBlockState(feet.below()));
		if (!inTree && !belowFloor && !onATree) return null;
		// Only remove trunks and leaves: the floor stays, even when you are pressed halfway into it.
		// First what is at head height, then above that, then your feet, then what stands around you.
		List<BlockPos> order = new ArrayList<>();
		order.add(feet.above()); order.add(feet.above(2)); order.add(feet);
		for (Direction d : Direction.Plane.HORIZONTAL) { order.add(feet.above().relative(d)); order.add(feet.relative(d)); }
		if (onATree) order.add(feet.below());
		for (BlockPos q : order) {
			BlockState st = level.getBlockState(q);
			if (treeBlock(st) && !st.getCollisionShape(level, q).isEmpty()) return q;
		}
		return null;   // nothing of a tree in the way: then a step or the server sorts it out itself
	}

	private static Vec3 rndDir(double len) {
		double yaw = Math.toRadians(R.nextDouble(-180, 180)), pitch = Math.toRadians(R.nextDouble(-20, 15));
		return new Vec3(-Math.sin(yaw) * Math.cos(pitch) * len, -Math.sin(pitch) * len, Math.cos(yaw) * Math.cos(pitch) * len);
	}

	/** Someone nearby who is not trusted. Trusted players may simply watch. */
	static Player playerNearby(LocalPlayer me, int radius) {
		if (me.level() == null || radius <= 0) return null;
		var trusted = AfkConfig.get().trusted;
		for (Player o : me.level().players()) {
			if (o == me || o.distanceTo(me) >= radius) continue;
			// Freecam puts its own camera player in the world (FreeCamera extends LocalPlayer, with your name): that is you as well
			if (o instanceof LocalPlayer || o.getUUID().equals(me.getUUID())) continue;
			String name = o.getGameProfile().name();
			if (trusted.stream().anyMatch(t -> t.equalsIgnoreCase(name))) continue;
			return o;
		}
		return null;
	}

	// ---------- planning ----------
	private static void plan(LocalPlayer p, AfkConfig c, long now) {
		Level level = p.level();
		lastPlan = now;
		// axe
		axeSlot = bestAxe(p, c);
		// is there a faster axe in the backpack (the kit gives stone, the diamond one is at the back)? bring that forward first
		if (axeSlot >= 0) {
			BlockState log = net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState();
			float speedNow = p.getInventory().getItem(axeSlot).getDestroySpeed(log);
			if (AfkHelperClient.toHotbar(p, Minecraft.getInstance(),
					st -> st.is(ItemTags.AXES) && st.getDestroySpeed(log) > speedNow + 0.01f
						&& (!st.isDamageableItem() || st.getMaxDamage() - st.getDamageValue() > c.woodAxeMin),
					java.util.Comparator.comparingDouble(st -> st.getDestroySpeed(log)), "better axe")) return;
		}
		if (axeSlot < 0) {
			// alt with a full hotbar: the axe is then in the backpack. Fetch it first, plan again next round.
			if (AfkHelperClient.toHotbar(p, Minecraft.getInstance(),
					st -> st.is(ItemTags.AXES) && (!st.isDamageableItem() || st.getMaxDamage() - st.getDamageValue() > c.woodAxeMin),
					java.util.Comparator.comparingDouble(st -> st.getDestroySpeed(net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState())),
					"axe")) return;
			stop(stopReason == null ? "no axe with more than " + c.woodAxeMin + " durability in the hotbar" : stopReason); return;
		}
		// at the start: count what the whole field is going to ask in saplings (trunks at floor height and empty plots) and
		// fetch that from the chest first, then you need not go back and forth between the rows
		if (stockAtStart) {
			stockAtStart = false;
			Map<String, Integer> needed = new HashMap<>();
			for (var pl : c.woodPlots) if (!isSapling(level, pl.pos())) needed.merge(pl.kind, 1, Integer::sum);
			boolean shortage = false;
			for (var e : needed.entrySet()) if (countInInventory(p, itemOf(e.getKey())) < e.getValue()) shortage = true;
			Exchange.emit("wood", "action", "stock at the start", "needed", needed, "short", shortage);
			if (shortage && !c.woodChests.isEmpty()) { steps.add(new Stock(needed)); return; }
		}
		/*
		 * Full? Then to a chest first. This check used to sit below the pickup and the planting, and with a full
		 * field it never got there: on 26 Sep it reported "inventory vol" three times without ever going to a
		 * chest, and then it picks up nothing any more and the plant steps fail.
		 */
		if (freeSlots(p) <= 2 && chestNeeded(p, c, now) && planChest(p, c)) return;
		// pick up: only when it is time (items only vanish after 5 minutes, so at most once per 3-4 minutes), never more often
		List<ItemEntity> loose = looseItems(p, c);
		if (!loose.isEmpty() && pickupTime(c, now)) { planPickup(loose); return; }
		// while planting a trunk appeared on the path: that one first, only then the open plots
		// While planting a trunk appeared on the path, or the buddy got stuck. That row belongs to this kind's round again,
		// otherwise the round moves on to the next kind and half the row stays without saplings.
		if (chopFirst) {
			chopFirst = false;
			if (chopFirstRow != Integer.MIN_VALUE) { rowsToDo.add(chopFirstRow); chopFirstRow = Integer.MIN_VALUE; }
			AfkConfig.WoodPlot b = nextTree(p, c);
			if (b != null) { planRow(p, c, b); return; }
		}
		// first fill the open plots we have saplings for
		/*
		 * Always look at open plots, not only when there is still something on the list. If that list ran empty
		 * while the saplings were gone, the gaps stayed until that kind was fully due again: that is how the owner
		 * found half a 2x2 dark oak that was never completed (26 Sep).
		 */
		if (planPlantLater(p, c, now)) return;
		// inventory nearly full or stock gone: to a chest
		if (chestNeeded(p, c, now) && planChest(p, c)) return;
		// Bonemeal before choosing the next row. When this sat behind "is there still a tree", it never happened
		// with several kinds: somewhere there is always one trunk standing.
		if (chestWithoutSaplings && now - chestWithoutSaplingsSince > 10 * 60_000L) chestWithoutSaplings = false;   // maybe it has been restocked
		if (chestWithoutBonemeal && now - chestWithoutBonemealSince > 10 * 60_000L) chestWithoutBonemeal = false;   // maybe it has been restocked
		if (bonemealRoundNeeded(p, c, now)) {
			if (countInInventory(p, Items.BONE_MEAL) > 0) { planBonemeal(p, c); return; }
			if (!chestWithoutBonemeal && planChest(p, c, ChestPurpose.SAPLINGS)) return;
		}
		/*
		 * Finishing: complete this module, then start nothing new. First plant the open plots, then pick up
		 * what is lying around, then everything into the chest, and then stop. Just like "finish" at the
		 * raid farm, so you can let it run out without leaving half rows and full pockets behind.
		 */
		if (finishing) {
			if (planPlantLater(p, c, now)) return;
			if (!loose.isEmpty()) { planPickup(loose); return; }
			if (!c.woodChests.isEmpty() && somethingForTheChest(p) && planChest(p, c, ChestPurpose.DROPOFF)) return;
			stop("rounded off");
			return;
		}
		// the next grown tree
		AfkConfig.WoodPlot tree = nextTree(p, c);
		if (tree != null) { planRow(p, c, tree); return; }
		// nothing left to chop: pick up when it is time, then the open plots
		if (!loose.isEmpty() && pickupTime(c, now)) { planPickup(loose); return; }
		/*
		 * Always look at open plots, not only when there is still something on the list. If that list ran empty
		 * while the saplings were gone, the gaps stayed until that kind was fully due again: that is how the owner
		 * found half a 2x2 dark oak that was never completed (26 Sep).
		 */
		if (planPlantLater(p, c, now)) return;
		// Nothing to chop (usually: bonemeal gone). Do not stand waiting: leaves still drop saplings, so pick up
		// regularly and fill open plots. We only stop when the axe is worn out or the chests are full.
		if (!loose.isEmpty() && now - lastPickup > 60_000) { planPickup(loose); return; }
		steps.add(new Wait(R.nextLong(20_000, 40_000)));
		if (treesThisRound > 0) { treesThisRound = 0; rowReversed = !rowReversed; }
	}

	private static boolean isGrown(Level level, AfkConfig.WoodPlot pl) { return level.getBlockState(pl.pos()).is(BlockTags.LOGS); }
	private static boolean isSapling(Level level, BlockPos pos) { return level.getBlockState(pos).getBlock() instanceof SaplingBlock || level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.MANGROVE_PROPAGULE); }

	/**
	 * The way a player does it: pick a row, start at the nearest end and work through the trunks on your
	 * path until the row is done. Rows run along x (same z) or along z (same x).
	 */
	private static AfkConfig.WoodPlot nextTree(LocalPlayer p, AfkConfig c) {
		Level level = p.level();
		List<AfkConfig.WoodPlot> ready = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (var pl : c.woodPlots) if (isGrown(level, pl) && skipped.getOrDefault(pl.pos(), 0L) < now) ready.add(pl);
		if (ready.isEmpty()) { if (activeKind != null) { moduleDone(now); rowsToDo.clear(); } return null; }
		// one round per kind: when choosing a kind, record the rows that have a trunk now, do each of those once,
		// and then on to the kind that was due longest ago. What grows back meanwhile is for the next round;
		// otherwise you get stuck in a kind that grows as fast as you chop.
		if (activeKind != null) ready.removeIf(a -> !a.kind.equals(activeKind) || !rowsToDo.contains(rowOf(c, a)));
		if (activeKind == null || ready.isEmpty()) {
			if (activeKind != null) moduleDone(now);
			ready.clear();
			for (var pl : c.woodPlots) if (isGrown(level, pl) && skipped.getOrDefault(pl.pos(), 0L) < now) ready.add(pl);
			Map<String, Integer> perKind = new HashMap<>();
			for (var a : ready) perKind.merge(a.kind, 1, Integer::sum);
			activeKind = perKind.keySet().stream().min(Comparator.comparingLong((String so) -> kindFinishedAt.getOrDefault(so, 0L)).thenComparing(so -> -perKind.get(so))).get();
			ready.removeIf(a -> !a.kind.equals(activeKind));
			rowsToDo.clear(); for (var a : ready) rowsToDo.add(rowOf(c, a));
			Exchange.emit("wood", "action", "kind", "kind", activeKind.replace("minecraft:", ""), "logs", perKind.get(activeKind), "rows", rowsToDo.size());
		}
		Comparator<AfkConfig.WoodPlot> nearest = Comparator.comparingDouble(a -> a.pos().distSqr(p.blockPosition()));
		// the row you are working on if there is still something there, otherwise the nearest row; and always
		// start at the end of that row that is nearest, never in the middle
		List<AfkConfig.WoodPlot> inRow = new ArrayList<>();
		for (var pl : ready) if (rowOf(c, pl) == lastRow) inRow.add(pl);
		AfkConfig.WoodPlot closest = (inRow.isEmpty() ? ready : inRow).stream().min(nearest).get();
		int row = rowOf(c, closest);
		List<AfkConfig.WoodPlot> newRow = new ArrayList<>();
		for (var pl : ready) if (rowOf(c, pl) == row) newRow.add(pl);
		boolean alongX = rowAlongX(c, closest);
		AfkConfig.WoodPlot a = newRow.stream().min(Comparator.comparingInt(q -> alongX ? q.x : q.z)).get();
		AfkConfig.WoodPlot b = newRow.stream().max(Comparator.comparingInt(q -> alongX ? q.x : q.z)).get();
		return a.pos().distSqr(p.blockPosition()) <= b.pos().distSqr(p.blockPosition()) ? a : b;
	}

	/** A kind is worked through: all trunks gone and the saplings back. We call that a module. */
	private static void moduleDone(long now) {
		kindFinishedAt.put(activeKind, now);
		modules++;
		Exchange.emit("wood", "action", "module done", "kind", activeKind.replace("minecraft:", "").replace("_sapling", ""), "modules", modules);
		activeKind = null;
	}

	/**
	 * Does the row of this plot run along x or along z? Neighbours right next to it, or two blocks away with one
	 * block in between (1x1 plots with an edge around them), belong to the same row. A lone plot counts as along x.
	 */
	private static boolean rowAlongX(AfkConfig c, AfkConfig.WoodPlot pl) {
		boolean x = false, z = false;
		for (var q : c.woodPlots) {
			if (q == pl) continue;
			if (q.z == pl.z && Math.abs(q.x - pl.x) <= 2) x = true;
			if (q.x == pl.x && Math.abs(q.z - pl.z) <= 2) z = true;
		}
		return x || !z;
	}
	/** Row number: the fixed coordinate of the row (z for a row along x, x for a row along z), with an offset so x and z rows do not collide. */
	/**
	 * A cell on the row itself, in front of or behind the plot. Walking along the row is straight and calm; standing
	 * beside the row costs a detour and often puts the buddy's nose in the leaves of the neighbouring tree.
	 */
	private static BlockPos onTheRow(LocalPlayer p, AfkConfig c, BlockPos plot) {
		AfkConfig.WoodPlot pl = null;
		for (var q : c.woodPlots) if (q.x == plot.getX() && q.z == plot.getZ()) { pl = q; break; }
		if (pl == null) return null;
		boolean alongX = rowAlongX(c, pl);
		BlockPos a = new BlockPos(plot.getX() + (alongX ? 1 : 0), c.woodY, plot.getZ() + (alongX ? 0 : 1));
		BlockPos b = new BlockPos(plot.getX() - (alongX ? 1 : 0), c.woodY, plot.getZ() - (alongX ? 0 : 1));
		boolean aOk = walkable(p.level(), a) && withinReach(p, plot.below()) == withinReach(p, plot.below());
		if (aOk && a.distSqr(p.blockPosition()) <= b.distSqr(p.blockPosition())) return a;
		if (walkable(p.level(), b)) return b;
		return aOk ? a : null;
	}

	/** Dark oak and jungle grow as 2x2: four corners or nothing. */
	private static boolean twoByTwo(String kind) {
		String n = kind.replace("minecraft:", "");
		return n.startsWith("dark_oak") || n.startsWith("jungle");
	}

	/**
	 * Enough saplings for this plot? With a 2x2 the whole group counts: planting with two saplings yields no
	 * tree and throws them away. That is how the owner found half a dark oak that then stayed there (26 Sep).
	 */
	private static boolean enoughForGroup(LocalPlayer p, AfkConfig c, BlockPos plot, String kind) {
		if (!twoByTwo(kind)) return countInInventory(p, itemOf(kind)) > 0;
		int empty = 0;
		boolean trunkInGroup = false;
		for (var q : c.woodPlots) {
			if (!q.kind.equals(kind) || Math.abs(q.x - plot.getX()) > 1 || Math.abs(q.z - plot.getZ()) > 1) continue;
			BlockPos pos = q.pos();
			if (p.level().getBlockState(pos).is(BlockTags.LOGS)) { trunkInGroup = true; continue; }
			if (!isSapling(p.level(), pos)) empty++;
		}
		// if a trunk is still standing in the group, that 2x2 will not grow anyway: chop first, otherwise you throw saplings away
		if (trunkInGroup) return false;
		boolean enough = countInInventory(p, itemOf(kind)) >= Math.max(1, empty);
		if (!enough) shortForGroup = true;
		return enough;
	}

	private static int rowOf(AfkConfig c, AfkConfig.WoodPlot pl) { return rowAlongX(c, pl) ? pl.z : 100_000 + pl.x; }

	/**
	 * A whole row the way a player does it, as a tunnel: you stand on the plot you just chopped clear, chop the
	 * next trunk straight ahead at 0 and +1, step into it, chop upwards, and so on. Only forward and up. On the
	 * way back you walk backwards through the same strip and put the sapling on the plot you just left. With
	 * 1x1 plots with edge material in between you stand on that edge block.
	 */
	private static void planRow(LocalPlayer p, AfkConfig c, AfkConfig.WoodPlot first) {
		Level level = p.level();
		long now = System.currentTimeMillis();
		int row = rowOf(c, first); boolean alongX = rowAlongX(c, first);
		lastRow = row;
		rowsToDo.remove(row);   // this row has had its turn for this kind; what grows back is for the next round
		List<AfkConfig.WoodPlot> trees = new ArrayList<>(), others = new ArrayList<>();
		for (var pl : c.woodPlots) {
			if (rowOf(c, pl) != row || !isGrown(level, pl) || skipped.getOrDefault(pl.pos(), 0L) >= now) continue;
			if (activeKind == null || pl.kind.equals(activeKind)) trees.add(pl); else others.add(pl);
		}
		// If a trunk of another kind happens to stand in between, take it along: you walk past it anyway, and
		// otherwise it stays until that kind is due again. Only a few - with more it is not a straggler but a
		// row of another kind, and that waits for its own round. The kind we are working on does not change
		// here, so the round simply continues.
		if (!trees.isEmpty() && !others.isEmpty() && others.size() <= Math.max(0, c.woodTakeAlongMax)) {
			Map<String, Integer> along = new HashMap<>();
			for (var pl : others) along.merge(pl.kind.replace("minecraft:", "").replace("_sapling", ""), 1, Integer::sum);
			trees.addAll(others);
			Exchange.emit("wood", "action", "took another kind", "row", row, "at", currentKind(), "taken along", along);
		}
		if (trees.isEmpty()) return;
		// from the nearest end of the row to the other, never start in the middle
		trees.sort(Comparator.comparingInt(q -> alongX ? q.x : q.z));
		if (trees.get(0).pos().distSqr(p.blockPosition()) > trees.get(trees.size() - 1).pos().distSqr(p.blockPosition())) java.util.Collections.reverse(trees);
		AfkConfig.WoodPlot a = trees.get(0), b = trees.get(trees.size() - 1);
		int dx = trees.size() > 1 ? Integer.signum(b.x - a.x) : (alongX ? 1 : 0), dz = trees.size() > 1 ? Integer.signum(b.z - a.z) : (alongX ? 0 : 1);
		// first get enough saplings for this row in stock, then you can carry straight on after chopping
		Map<String, Integer> needed = new HashMap<>();
		for (var pl : trees) needed.merge(pl.kind, 1, Integer::sum);
		boolean shortage = false;
		for (var e : needed.entrySet()) if (countInInventory(p, itemOf(e.getKey())) < e.getValue()) shortage = true;
		if (shortage && !c.woodChests.isEmpty() && now - lastChest > 20_000) {
			AfkConfig.ChestPos best = pickChest(p, c, ChestPurpose.SAPLINGS);
			BlockPos besideChest = best == null ? null : nearestSpotBeside(p, best.pos());
			if (besideChest != null) { steps.add(new WalkTo(besideChest)); steps.add(new ChestStep(best.pos(), ChestPurpose.SAPLINGS)); lastChest = now; Exchange.emit("wood", "action", "fetch saplings first", "needed", needed); }
		}
		ChopPlan plan = chopPlan(p, c, trees, alongX, dx, dz, now);
		steps.addAll(plan.steps);
		List<AfkConfig.WoodPlot> done = new ArrayList<>(plan.done); List<BlockPos> front = new ArrayList<>(plan.front);
		// 2x2 (dark oak, jungle): the plots of the same kind lying at right angles to this row are the other half
		// of the same tree. Those right after it, in the opposite direction, so all four columns come down in one go.
		Set<BlockPos> inRow = new HashSet<>(); for (var g : trees) inRow.add(g.pos());
		List<AfkConfig.WoodPlot> secondHalf = new ArrayList<>();
		for (var pl : c.woodPlots) {
			if (!pl.kind.equals(first.kind) || inRow.contains(pl.pos()) || !isGrown(level, pl) || skipped.getOrDefault(pl.pos(), 0L) >= now) continue;
			for (var g : trees) if ((alongX && pl.x == g.x && Math.abs(pl.z - g.z) == 1) || (!alongX && pl.z == g.z && Math.abs(pl.x - g.x) == 1)) { secondHalf.add(pl); break; }
		}
		Set<BlockPos> separate = new HashSet<>();   // plots not on the planting line: ordinary plant step
		if (!secondHalf.isEmpty()) {
			secondHalf.sort(Comparator.comparingInt(q -> alongX ? -dx * q.x : -dz * q.z));   // back in the other direction
			java.util.Set<BlockPos> alreadyFree = new HashSet<>();
			for (var g : plan.done) alreadyFree.add(new BlockPos(g.x, c.woodY, g.z));
			ChopPlan plan2 = chopPlan(p, c, secondHalf, alongX, -dx, -dz, now, alreadyFree);
			steps.addAll(plan2.steps);
			done.addAll(plan2.done); front.addAll(plan2.front);
			for (var g : trees) separate.add(g.pos()); for (var g : plan2.done) separate.add(g.pos());
			Exchange.emit("wood", "action", "2x2", "spots", done.size());
		}
		// the far branches (acacia, cherry) and what is still hanging above in a 2x2 only once the whole row is down:
		// otherwise you walk out into the field between two trunks
		for (var g : done) steps.add(new Branches(g.pos(), BRANCH_RADIUS).chop());
		treesThisRound += done.size();
		Exchange.emit("wood", "action", "row", "row", row, "trees", done.size(), "kind", done.isEmpty() ? "" : done.get(0).kind.replace("minecraft:", ""));
		// way back: backwards through the strip with the sapling in hand, clicking as soon as the crosshair passes over an empty dirt.
		// Only for plots whose standing spot lies on the line of the row; a plot that went sideways via the lane gets the ordinary plant step.
		List<AfkConfig.WoodPlot> line = new ArrayList<>(); List<BlockPos> lineFront = new ArrayList<>();
		List<AfkConfig.WoodPlot> loose = new ArrayList<>();
		for (int i = done.size() - 1; i >= 0; i--) {
			BlockPos v = front.get(i); AfkConfig.WoodPlot g = done.get(i);
			boolean onLine = (alongX ? v.getZ() == g.z : v.getX() == g.x) && !separate.contains(g.pos());
			if (onLine) { line.add(g); lineFront.add(v); } else loose.add(g);
		}
		if (!loose.isEmpty()) {
			// 2x2: from the spot where you stand after the last trunk all four dirt tops are within reach. Farthest first,
			// otherwise a freshly planted sapling is in the line of sight to the next one.
			BlockPos stand = front.get(front.size() - 1);
			loose.sort(Comparator.comparingDouble((AfkConfig.WoodPlot g) -> -g.pos().distSqr(stand)));
			steps.add(new WalkTo(stand));
			for (var g : loose) steps.add(new Plant(g.pos(), g.kind, true));
		}
		if (!line.isEmpty()) steps.add(new ReadyToPlant(line, lineFront, row, alongX, dx, dz));
	}

	record ChopPlan(List<Step> steps, List<AfkConfig.WoodPlot> done, List<BlockPos> front) {}

	/** The chop steps for these trees, in this order, as a tunnel: stand in front of the trunk, 0 and +1, into it, up, branches. */
	private static ChopPlan chopPlan(LocalPlayer p, AfkConfig c, List<AfkConfig.WoodPlot> trees, boolean alongX, int dx, int dz, long now) {
		return chopPlan(p, c, trees, alongX, dx, dz, now, java.util.Set.of());
	}

	/** 'alreadyFree': plots that come down earlier in the same plan (the first half of a 2x2). You can stand there later. */
	private static ChopPlan chopPlan(LocalPlayer p, AfkConfig c, List<AfkConfig.WoodPlot> trees, boolean alongX, int dx, int dz, long now, java.util.Set<BlockPos> alreadyFree) {
		Level level = p.level();
		List<Step> out = new ArrayList<>(); List<AfkConfig.WoodPlot> done = new ArrayList<>(); List<BlockPos> front = new ArrayList<>();
		BlockPos previous = null;   // where you stand when you have the next trunk in front of you
		for (var pl : trees) {
			BlockPos plot = pl.pos();
			BlockPos stand = null;
			BlockPos v = new BlockPos(plot.getX() - dx, c.woodY, plot.getZ() - dz);   // straight in front of it, in the strip
			if (freeLater(level, c, v, alreadyFree, done)) stand = v;
			if (stand == null) { int side = alongX ? Integer.signum(p.blockPosition().getZ() - plot.getZ()) : Integer.signum(p.blockPosition().getX() - plot.getX()); if (side == 0) side = 1;
				stand = laneCell(level, c, plot, alongX, side); if (stand == null) stand = laneCell(level, c, plot, alongX, -side); if (stand == null) stand = nearestSpotBeside(p, plot); }
			// last chance: a neighbouring cell that comes down in this plan (the other columns of a 2x2)
			if (stand == null) for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos q = new BlockPos(plot.getX() + d.getStepX(), c.woodY, plot.getZ() + d.getStepZ());
				if (freeLater(level, c, q, alreadyFree, done)) { stand = q; break; }
			}
			if (stand == null) { skipped.put(plot, now + 5 * 60_000L); Exchange.emit("wood", "action", "no spot beside", "spot", plot.toShortString()); continue; }
			out.add(new WalkTo(stand) { void failed(LocalPlayer p2, AfkConfig c2) { super.failed(p2, c2); if (!(noPath && noPathCount >= 2)) skipped.put(plot, System.currentTimeMillis() + 5 * 60_000L); } }.chop());
			out.add(new BreakBlock(plot).chop());
			out.add(new BreakBlock(plot.above()).chop());
			out.add(new WalkTo(plot).chop());
			for (int dy = 2; dy <= MAX_HEIGHT; dy++) { if (!level.getBlockState(plot.above(dy)).is(BlockTags.LOGS)) break; out.add(new BreakBlock(plot.above(dy)).chop()); }
			out.add(new Branches(plot, 1).chop());   // only what hangs within 3x3 around the trunk: you get that from the column
			done.add(pl); front.add(stand);
			chopped.put(plot, now);
			previous = plot;
		}
		return new ChopPlan(out, done, front);
	}

	/** Can you stand there later: walkable already, or it comes down earlier in this plan. */
	private static boolean freeLater(Level level, AfkConfig c, BlockPos cell, java.util.Set<BlockPos> alreadyFree, List<AfkConfig.WoodPlot> done) {
		if (walkable(level, cell)) return true;
		if (alreadyFree.contains(new BlockPos(cell.getX(), c.woodY, cell.getZ()))) return true;
		for (var g : done) if (g.x == cell.getX() && g.z == cell.getZ()) return true;
		return false;
	}

	/** The cell beside a plot, at right angles to the row on this side, if you can stand there. */
	private static BlockPos laneCell(Level level, AfkConfig c, BlockPos plot, boolean alongX, int side) {
		BlockPos q = alongX ? new BlockPos(plot.getX(), c.woodY, plot.getZ() + side) : new BlockPos(plot.getX() + side, c.woodY, plot.getZ());
		return walkable(level, q) ? q : null;
	}

	/** All plots without sapling and without trunk, from the world itself: not only what we could not plant ourselves. */
	private static Map<BlockPos, String> openPlots(LocalPlayer p, AfkConfig c) {
		Map<BlockPos, String> open = new HashMap<>();
		for (var pl : c.woodPlots) { BlockPos plot = pl.pos(); if (!isSapling(p.level(), plot) && !p.level().getBlockState(plot).is(BlockTags.LOGS) && p.level().getBlockState(plot).getCollisionShape(p.level(), plot).isEmpty()) open.put(plot, pl.kind); }
		open.putAll(plantLater);
		return open;
	}

	/** Plant open plots: per row backwards through the strip (quick), separate plots one by one. */
	private static boolean planPlantLater(LocalPlayer p, AfkConfig c, long now) {
		Level level = p.level();
		shortForGroup = false;
		Map<BlockPos, String> open = openPlots(p, c);
		open.keySet().removeIf(q -> isSapling(level, q) || level.getBlockState(q).is(BlockTags.LOGS));
		// dark oak and jungle only grow when all four corners are there: planting half is throwing saplings away
		open.entrySet().removeIf(e -> !enoughForGroup(p, c, e.getKey(), e.getValue()));
		Map<String, Integer> without = new HashMap<>();
		for (var e : open.entrySet()) if (countInInventory(p, itemOf(e.getValue())) == 0) without.merge(e.getValue(), 1, Integer::sum);
		open.entrySet().removeIf(e -> countInInventory(p, itemOf(e.getValue())) == 0);
		if (open.isEmpty()) {
			if (without.isEmpty() && !shortForGroup) return false;
			/*
			 * Pick up first: what lies on the field is often exactly sapling, and that saves a walk to a chest
			 * where it is not anyway. We skip the chest when we saw nothing of that kind there at the previous
			 * visit: we know what is in the chest.
			 */
			List<ItemEntity> loose2 = looseItems(p, c);
			if (!loose2.isEmpty() && now - lastPickup > 60_000) {
				Exchange.emit("wood", "action", "pick up for saplings first", "items", loose2.size(), "needed", without);
				planPickup(loose2);
				return true;
			}
			if (c.woodChests.isEmpty() || chestWithoutSaplings || now - lastChest < 30_000) return false;
			boolean chestCanHelp = without.keySet().stream().anyMatch(kind -> !chestLacks(kind, now)) || (shortForGroup && without.isEmpty());
			if (!chestCanHelp) { Exchange.emit("wood", "action", "chest lacks these saplings", "needed", without); return false; }
			Exchange.emit("wood", "action", "fetching saplings for open spots", "needed", without, "for_2x2", shortForGroup);
			if (planChest(p, c, ChestPurpose.SAPLINGS)) return true;
			chestWithoutSaplings = true; chestWithoutSaplingsSince = now;
			return false;
		}
		// group per row
		Map<Integer, List<AfkConfig.WoodPlot>> perRow = new HashMap<>();
		for (var pl : c.woodPlots) if (open.containsKey(pl.pos())) perRow.computeIfAbsent(rowOf(c, pl), k -> new ArrayList<>()).add(pl);
		// nearest row first
		List<Integer> rows = new ArrayList<>(perRow.keySet());
		rows.sort(Comparator.comparingDouble(r -> perRow.get(r).stream().mapToDouble(pl -> pl.pos().distSqr(p.blockPosition())).min().orElse(1e9)));
		int row = rows.get(0); List<AfkConfig.WoodPlot> plots = perRow.get(row);
		boolean alongX = rowAlongX(c, plots.get(0));
		plots.sort(Comparator.comparingInt(q -> alongX ? q.x : q.z));
		/*
		 * Start at the end that is nearest. At first the buddy walked to the far end first to walk back planting, but
		 * this step is rebuilt every time (after every plant or hiccup), and then the step sent the buddy to that far
		 * point again each time: there, back, there (26 Sep: distance swung between 4.5 and 6.3 blocks
		 * while the route ran from 4 to 2 points).
		 */
		if (plots.get(0).pos().distSqr(p.blockPosition()) > plots.get(plots.size() - 1).pos().distSqr(p.blockPosition())) java.util.Collections.reverse(plots);
		AfkConfig.WoodPlot a = plots.get(0), b = plots.get(plots.size() - 1);
		int dx = plots.size() > 1 ? Integer.signum(b.x - a.x) : (alongX ? 1 : 0), dz = plots.size() > 1 ? Integer.signum(b.z - a.z) : (alongX ? 0 : 1);
		// route: for plot i you stand on the plot after it in walking direction (that is where you step to while you have plot i in front of you)
		List<AfkConfig.WoodPlot> line = new ArrayList<>(); List<BlockPos> route = new ArrayList<>();
		for (int i = 0; i < plots.size(); i++) {
			AfkConfig.WoodPlot pl = plots.get(i);
			BlockPos stand = new BlockPos(pl.x + dx, c.woodY, pl.z + dz);
			// if a trunk or something else is in the way there, then the cell on the other side on the row: walking
			// along the row is calm and straight. Standing beside the row costs a detour and often ends in the leaves.
			if (!walkable(level, stand)) stand = new BlockPos(pl.x - dx, c.woodY, pl.z - dz);
			if (!walkable(level, stand)) { BlockPos beside = nearestSpotBeside(p, pl.pos()); if (beside != null) { steps.add(new WalkTo(beside)); steps.add(new Plant(pl.pos(), pl.kind)); } continue; }
			line.add(pl); route.add(stand);
		}
		if (!line.isEmpty()) { steps.add(new WalkTo(route.get(0))); steps.add(new PlantOnTheWay(line, route)); }
		for (var pl : plots) plantLater.remove(pl.pos());
		return true;
	}

	/** May we pick up? At least three minutes after the last time, and otherwise the configured time. */
	private static boolean pickupTime(AfkConfig c, long now) { return now - lastPickup > Math.max(180, c.woodPickupEverySec) * 1000L; }

	private static void planPickup(List<ItemEntity> loose) {
		steps.add(new Pickup());
		lastPickup = System.currentTimeMillis(); treesThisRound = 0;
	}

	/**
	 * A bonemeal round is needed when fewer plots have a tree than the configured threshold, there are saplings
	 * to fertilise, and the previous round was at least a minute ago. Why it skips goes to events.jsonl, at most
	 * once per two minutes, because that could not be seen.
	 */
	private static boolean bonemealRoundNeeded(LocalPlayer p, AfkConfig c, long now) {
		int total = c.woodPlots.size(), grown = 0, saplings = 0, toFertilise = 0;
		for (var pl : c.woodPlots) {
			if (isGrown(p.level(), pl)) grown++;
			else if (isSapling(p.level(), pl.pos())) { saplings++; if (bonemealAttempts.getOrDefault(pl.pos(), 0) < c.woodBonemealMax) toFertilise++; }
		}
		boolean onTime = now - lastBonemealRound >= 60_000;
		// at most this percentage grown; with the threshold at 0 that means: only when not a single tree is standing any more
		boolean empty = total > 0 && grown * 100 <= total * c.woodBonemealThreshold;
		boolean needed = c.woodBonemeal && onTime && empty && toFertilise > 0;
		if (!needed && now - lastBonemealExplanation > 120_000) {
			lastBonemealExplanation = now;
			Exchange.emit("wood", "action", "no bonemeal round", "grown", grown, "from", total, "threshold", c.woodBonemealThreshold + "%",
				"saplings_in_field", saplings, "to_bonemeal", toFertilise, "in_bag", countInInventory(p, Items.BONE_MEAL),
				"reason", !c.woodBonemeal ? "bonemeal is off" : !onTime ? "just been" : !empty ? "too many trees" : "nothing to bonemeal");
		}
		return needed;
	}

	private static void planBonemeal(LocalPlayer p, AfkConfig c) {
		lastBonemealRound = System.currentTimeMillis();
		List<AfkConfig.WoodPlot> list = new ArrayList<>();
		for (var pl : c.woodPlots) if (isSapling(p.level(), pl.pos()) && bonemealAttempts.getOrDefault(pl.pos(), 0) < c.woodBonemealMax) list.add(pl);
		list.sort(Comparator.comparingInt((AfkConfig.WoodPlot a) -> rowReversed ? -a.z : a.z).thenComparingInt(a -> rowReversed ? -a.x : a.x));
		int n = 0;
		for (var pl : list) {
			BlockPos beside = nearestSpotBeside(p, pl.pos());
			if (beside == null) continue;
			steps.add(new WalkTo(beside)); steps.add(new Bonemeal(pl.pos()));
			if (++n >= 12) break;   // in chunks, so a grown tree or a player can come in between
		}
		Exchange.emit("wood", "action", "bonemeal round", "saplings", n);
	}

	private static boolean chestNeeded(LocalPlayer p, AfkConfig c, long now) {
		if (c.woodChests.isEmpty() || now - lastChest < 30_000) return false;
		if (freeSlots(p) <= 2) return true;
		// saplings missing for open plots: to the chest as soon as there is nothing left to chop and nothing left to pick up.
		// Leaves still decaying will drop more saplings later; the next pickup round takes those along.
		boolean woodLeft = nextTree(p, c) != null;
		if (!woodLeft && (looseItems(p, c).isEmpty() || !pickupTime(c, now))) {
			for (var e : openPlots(p, c).entrySet()) if (countInInventory(p, itemOf(e.getValue())) == 0) return true;
		}
		return false;
	}

	private static boolean planChest(LocalPlayer p, AfkConfig c) { return planChest(p, c, freeSlots(p) <= 2 ? ChestPurpose.DROPOFF : ChestPurpose.SAPLINGS); }

	/** The nearest chest that fits this purpose: known role first, otherwise a chest whose role we do not know yet. */
	private static AfkConfig.ChestPos pickChest(LocalPlayer p, AfkConfig c, ChestPurpose purpose) {
		AfkConfig.ChestPos best = null; double bd = 1e9; boolean bestKnown = false;
		for (var k : c.woodChests) {
			if (fullChests.contains(k.pos())) continue;
			ChestRole role = chestRole.getOrDefault(k.pos(), ChestRole.UNKNOWN);
			boolean fits = role == ChestRole.UNKNOWN || (purpose == ChestPurpose.SAPLINGS ? role == ChestRole.SAPLINGS : role == ChestRole.DROPOFF);
			if (!fits) continue;
			boolean known = role != ChestRole.UNKNOWN;
			double d = k.pos().distSqr(p.blockPosition());
			if (best == null || (known && !bestKnown) || (known == bestKnown && d < bd)) { bd = d; best = k; bestKnown = known; }
		}
		return best;
	}

	private static boolean planChest(LocalPlayer p, AfkConfig c, ChestPurpose purpose) {
		AfkConfig.ChestPos best = pickChest(p, c, purpose);
		if (best == null) { if (purpose == ChestPurpose.DROPOFF) stop("all output chests full"); return false; }
		BlockPos beside = nearestSpotBeside(p, best.pos());
		if (beside == null) { fullChests.add(best.pos()); Exchange.emit("wood", "action", "chest unreachable", "pos", best.pos().toShortString()); return false; }
		steps.add(new WalkTo(beside)); steps.add(new ChestStep(best.pos(), purpose));
		lastChest = System.currentTimeMillis();
		return true;
	}

	// ---------- helpers ----------
	/** Best axe in the hotbar with enough durability, or -1. Slots 1-4 are the owner's but reading is allowed. */
	private static int bestAxe(LocalPlayer p, AfkConfig c) {
		int best = -1; float fastest = 0; boolean wornAxe = false;
		BlockState log = net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState();
		for (int i = 0; i < 9; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.isEmpty() || !s.is(ItemTags.AXES)) continue;
			if (s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() <= c.woodAxeMin) { wornAxe = true; continue; }
			float v = s.getDestroySpeed(log);
			if (v > fastest) { fastest = v; best = i; }
		}
		if (best < 0 && wornAxe) stopReason = "axe nearly worn out (under " + c.woodAxeMin + " durability)";
		return best;
	}

	static net.minecraft.world.level.block.Block blockOf(String id) {
		try { return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse(id)); } catch (RuntimeException e) { return null; }
	}
	static Item itemOf(String id) { return net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(id)); }

	static int countInInventory(LocalPlayer p, Item it) {
		int n = 0;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) { ItemStack s = p.getInventory().getItem(i); if (s.is(it)) n += s.getCount(); }
		return n;
	}

	static int freeSlots(LocalPlayer p) { int n = 0; for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) n++; return n; }

	/** Things that come from trees: those may go into the chest, the rest of the inventory is left alone. */
	static boolean treeStuff(ItemStack s) {
		return s.is(ItemTags.LOGS) || s.is(ItemTags.SAPLINGS) || s.is(ItemTags.LEAVES) || s.is(Items.STICK) || s.is(Items.APPLE) || s.is(Items.MANGROVE_PROPAGULE);
	}

	private static List<ItemEntity> looseItems(LocalPlayer p, AfkConfig c) {
		int x1 = Math.min(c.woodX1, c.woodX2) - 2, x2 = Math.max(c.woodX1, c.woodX2) + 2, z1 = Math.min(c.woodZ1, c.woodZ2) - 2, z2 = Math.max(c.woodZ1, c.woodZ2) + 2;
		var box = new net.minecraft.world.phys.AABB(x1, c.woodY - 2, z1, x2 + 1, c.woodY + 4, z2 + 1);
		return p.level().getEntitiesOfClass(ItemEntity.class, box, e -> treeStuff(e.getItem()) || e.getItem().is(Items.BONE_MEAL));
	}

	/**
	 * Walkable: solid floor below, nothing in the way at foot and head height, no hazard. Saplings do not
	 * count as an obstacle, and neither do leaves: WalkTo hits those away on the way. Spruce hangs its leaves
	 * down to +1 beside the trunk; without this not a single cell beside a spruce was reachable.
	 */
	static boolean walkable(Level level, BlockPos f) {
		BlockState below = level.getBlockState(f.below()), feet = level.getBlockState(f), head = level.getBlockState(f.above());
		if (below.getCollisionShape(level, f.below()).isEmpty()) return false;
		if (below.getBlock() instanceof ChestBlock || below.getBlock() instanceof BarrelBlock) return false;
		if (!below.getFluidState().isEmpty() || !feet.getFluidState().isEmpty()) return false;
		if (!freeOrLeaves(level, f) || !freeOrLeaves(level, f.above())) return false;
		return !Grinder.hazard(level, f) && !Grinder.hazard(level, f.below());
	}
	static boolean freeOrLeaves(Level level, BlockPos q) { BlockState st = level.getBlockState(q); return st.getCollisionShape(level, q).isEmpty() || st.is(BlockTags.LEAVES); }
	/** The leaf block that is in the way at this spot (feet or head), or null. */
	static BlockPos leavesInTheWay(Level level, BlockPos f) {
		if (level.getBlockState(f).is(BlockTags.LEAVES)) return f;
		if (level.getBlockState(f.above()).is(BlockTags.LEAVES)) return f.above();
		return null;
	}

	/** Nearest walkable spot directly beside this block (at floor height). */
	private static BlockPos nearestSpotBeside(LocalPlayer p, BlockPos target) {
		AfkConfig c = AfkConfig.get();
		BlockPos best = null; double bd = 1e9;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos q = new BlockPos(target.getX() + d.getStepX(), c.woodY, target.getZ() + d.getStepZ());
			if (!walkable(p.level(), q)) continue;
			double dist = q.distSqr(p.blockPosition());
			if (dist < bd) { bd = dist; best = q; }
		}
		return best;
	}

	/** Path over the floor of the field (4 directions, breadth-first). Null if there is none. */
	static List<BlockPos> path(Level level, BlockPos from, BlockPos to) {
		AfkConfig c = AfkConfig.get();
		int x1 = Math.min(c.woodX1, c.woodX2) - 4, x2 = Math.max(c.woodX1, c.woodX2) + 4, z1 = Math.min(c.woodZ1, c.woodZ2) - 4, z2 = Math.max(c.woodZ1, c.woodZ2) + 4;
		BlockPos start = new BlockPos(from.getX(), c.woodY, from.getZ()), target = new BlockPos(to.getX(), c.woodY, to.getZ());
		if (start.equals(target)) return List.of();
		// Dijkstra: a step costs 10 and every turn 3 extra (at equal length it otherwise chose an L shape, and that looks
		// like walking around). Leaves in the strip (on a plot cell) cost 15: break a couple of leaves straight ahead and
		// carry on. Leaves in the lane between the rows cost 120: there you would rather walk a few blocks around.
		Set<BlockPos> plotCells = new HashSet<>();
		for (var pl : c.woodPlots) plotCells.add(new BlockPos(pl.x, c.woodY, pl.z));
		Map<BlockPos, BlockPos> cameFrom = new HashMap<>(); Map<BlockPos, Integer> cost = new HashMap<>(); Map<BlockPos, Direction> heading = new HashMap<>();
		java.util.PriorityQueue<BlockPos> q = new java.util.PriorityQueue<>(Comparator.comparingInt(b -> cost.getOrDefault(b, Integer.MAX_VALUE)));
		q.add(start); cameFrom.put(start, start); cost.put(start, 0);
		Set<BlockPos> closed = new HashSet<>();
		int stepsMax = 6000;
		while (!q.isEmpty() && stepsMax-- > 0) {
			BlockPos cur = q.poll();
			if (!closed.add(cur)) continue;
			if (cur.equals(target)) break;
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos n = cur.relative(d);
				if (n.getX() < x1 || n.getX() > x2 || n.getZ() < z1 || n.getZ() > z2 || closed.contains(n)) continue;
				if (!walkable(level, n)) continue;
				// walking around beats hitting: a leaf in the strip costs like 2.5 steps, so up to two steps of detour wins.
				// In the lane between the rows it costs like eleven steps: there walking around nearly always wins.
				int leaf = leavesInTheWay(level, n) == null ? 0 : plotCells.contains(n) ? 25 : 110;
				int k = cost.get(cur) + 10 + leaf + (heading.get(cur) != null && heading.get(cur) != d ? 3 : 0);
				if (k < cost.getOrDefault(n, Integer.MAX_VALUE)) { cost.put(n, k); cameFrom.put(n, cur); heading.put(n, d); q.add(n); }
			}
		}
		if (!cameFrom.containsKey(target)) return null;
		List<BlockPos> out = new ArrayList<>();
		for (BlockPos at = target; !at.equals(start); at = cameFrom.get(at)) out.add(at);
		java.util.Collections.reverse(out);
		// detour? then record what stood in the way on the straight line, so we need not guess
		int straight = start.distManhattan(target);
		if (out.size() > straight + 1) {
			List<String> blockade = new ArrayList<>();
			int n = Math.max(Math.abs(target.getX() - start.getX()), Math.abs(target.getZ() - start.getZ()));
			for (int i = 1; i <= n; i++) {
				BlockPos cell = new BlockPos(start.getX() + Math.round((target.getX() - start.getX()) * (float) i / n), c.woodY, start.getZ() + Math.round((target.getZ() - start.getZ()) * (float) i / n));
				if (!walkable(level, cell)) blockade.add(cell.toShortString() + "=" + blockId(level.getBlockState(cell).getBlock()).replace("minecraft:", "") + "/" + blockId(level.getBlockState(cell.above()).getBlock()).replace("minecraft:", "") + "/onder:" + blockId(level.getBlockState(cell.below()).getBlock()).replace("minecraft:", ""));
			}
			Exchange.emit("wood", "action", "detour", "from", start.toShortString(), "to", target.toShortString(), "steps", out.size(), "straight", straight, "in_the_way", blockade);
		}
		return out;
	}

	static Vec3 center(BlockPos pos) { return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5); }

	/**
	 * Where you aim: a point on the face of the block that points at you and is not covered, a quarter block
	 * from the edges. The centre of the block, aimed from the lane beside log 0, goes through log +1 (the ray
	 * hits the block above first), and then log 0 stays standing. Straight above your head has no direction:
	 * then slightly to the side you are already looking, otherwise the first turn is to yaw 0.
	 */
	/**
	 * Aiming point to place a sapling: the top face of the dirt under the plot, on the half that lies away from
	 * you. The aim was 3 cm below the surface, and from a neighbouring cell that ray then comes in from the side:
	 * you hit the side or the block in front, and planting only works on the top face. The owner saw the crosshair
	 * just beside it and only had to nudge it (screenshot, 26 Sep).
	 */
	static Vec3 aimAtGround(LocalPlayer p, BlockPos plot) {
		double dx = plot.getX() + 0.5 - p.getX(), dz = plot.getZ() + 0.5 - p.getZ();
		double len = Math.max(0.001, Math.hypot(dx, dz));
		return new Vec3(plot.getX() + 0.5 + dx / len * 0.28, plot.getY() - 0.005, plot.getZ() + 0.5 + dz / len * 0.28);
	}

	static Vec3 aimPoint(LocalPlayer p, BlockPos pos) {
		Vec3 eye = p.getEyePosition(); Level level = p.level();
		double[] min = { pos.getX(), pos.getY(), pos.getZ() }, max = { pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1 }, e = { eye.x, eye.y, eye.z };
		int axis = -1, side = 0; double best = -1;
		for (int a = 0; a < 3; a++) {
			int k = e[a] < min[a] ? -1 : e[a] > max[a] ? 1 : 0;
			if (k == 0) continue;
			double gap = k < 0 ? min[a] - e[a] : e[a] - max[a];
			BlockPos neighbour = pos.offset(a == 0 ? k : 0, a == 1 ? k : 0, a == 2 ? k : 0);
			if (!level.getBlockState(neighbour).getShape(level, neighbour).isEmpty()) continue;   // that face is behind another block
			if (gap > best) { best = gap; axis = a; side = k; }
		}
		if (axis < 0) return center(pos);
		double[] t = new double[3];
		for (int a = 0; a < 3; a++) t[a] = Mth.clamp(e[a], min[a] + 0.25, max[a] - 0.25);
		t[axis] = side < 0 ? min[axis] + 0.02 : max[axis] - 0.02;
		if (Math.hypot(t[0] - eye.x, t[2] - eye.z) < 0.15) {
			double yaw = Math.toRadians(p.getYRot());
			t[0] = Mth.clamp(t[0] - Math.sin(yaw) * 0.2, min[0] + 0.1, max[0] - 0.1);
			t[2] = Mth.clamp(t[2] + Math.cos(yaw) * 0.2, min[2] + 0.1, max[2] - 0.1);
		}
		return new Vec3(t[0], t[1], t[2]);
	}

	static boolean withinReach(LocalPlayer p, BlockPos pos) {
		Vec3 eye = p.getEyePosition();
		double cx = Mth.clamp(eye.x, pos.getX(), pos.getX() + 1), cy = Mth.clamp(eye.y, pos.getY(), pos.getY() + 1), cz = Mth.clamp(eye.z, pos.getZ(), pos.getZ() + 1);
		// no margin: vanilla's view ray is exactly as long as the reach, so what the crosshair hits can be hit.
		// With a 0.15 margin the log at +6 (4.38 blocks) fell just outside.
		return eye.distanceTo(new Vec3(cx, cy, cz)) <= p.blockInteractionRange();
	}

	/** Look straight at a point, in mouse pixels; true as soon as the crosshair is (nearly) on it. */
	static boolean aim(LocalPlayer p, Vec3 target) {
		Vec3 eye = p.getEyePosition();
		double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z, dh = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)), pitch = (float) -Math.toDegrees(Math.atan2(dy, dh));
		float dyaw = Mth.wrapDegrees(yaw - p.getYRot()), dpitch = pitch - p.getXRot();
		MouseTurn.toward(p, p.getYRot() + Mth.clamp(dyaw, -14f, 14f), p.getXRot() + Mth.clamp(dpitch, -14f, 14f));
		return Math.abs(dyaw) < 1.5f && Math.abs(dpitch) < 1.5f;
	}

	static BlockPos crosshairBlock(Minecraft mc) {
		return mc.hitResult instanceof BlockHitResult b && mc.hitResult.getType() == HitResult.Type.BLOCK ? b.getBlockPos() : null;
	}

	/** Put the axe in hand (only select, never move). */
	private static void grabAxe(LocalPlayer p) { if (axeSlot >= 0 && p.getInventory().getSelectedSlot() != axeSlot) p.getInventory().setSelectedSlot(axeSlot); }

	/** Hotbar slot with this item, or -1. If it is in 1-4 (picked up into a free tool slot), we simply select that slot; we only move things to 5-9. */
	static int hotbarSlot(LocalPlayer p, Item it) { for (int i = 4; i < 9; i++) if (p.getInventory().getItem(i).is(it)) return i; for (int i = 0; i < 4; i++) if (p.getInventory().getItem(i).is(it)) return i; return -1; }

	// ---------- steps ----------
	static abstract class Step {
		long startedAt;
		boolean chop = false;   // part of chopping a tree: the attack button stays down, no thinking pause in between
		Step chop() { chop = true; return this; }
		void begin(LocalPlayer p) { startedAt = System.currentTimeMillis(); }
		abstract int tick(LocalPlayer p, Minecraft mc, AfkConfig c);
		void failed(LocalPlayer p, AfkConfig c) {}
		/** Name for the events: the watcher matches on it. */
		abstract String name();
		/** What the player sees in the HUD status line, in the player's own language. */
		abstract String label();
		long elapsed() { return System.currentTimeMillis() - startedAt; }
	}

	/** Do nothing for a moment. */
	static class Wait extends Step {
		final long ms; Wait(long ms) { this.ms = ms; }
		int tick(LocalPlayer p, Minecraft mc, AfkConfig c) { look.tick(p); return elapsed() >= ms ? DONE : BUSY; }
		String name() { return "waits"; }
		String label() { return "waiting"; }
	}

	/** Is the straight line from a to b (at floor height y) over free floor, also for the width of the player? */
	static boolean clearLine(Level level, Vec3 a, Vec3 b, int y) {
		double d = Math.hypot(b.x - a.x, b.z - a.z); int n = Math.max(1, (int) Math.ceil(d / 0.25));
		for (int i = 0; i <= n; i++) {
			double t = (double) i / n, x = a.x + (b.x - a.x) * t, z = a.z + (b.z - a.z) * t;
			for (double ox : new double[] { -0.32, 0.32 }) for (double oz : new double[] { -0.32, 0.32 }) {
				BlockPos cell = new BlockPos(Mth.floor(x + ox), y, Mth.floor(z + oz));
				if (!walkable(level, cell) || leavesInTheWay(level, cell) != null) return false;   // leaves are hit away per cell of the route, not on a shortcut line
			}
		}
		return true;
	}

	/**
	 * Walk to a floor spot via a path, with the real walk key and correcting the view direction. The path is
	 * a staircase of straight bits; the walk cuts corners as long as the straight line is clear, keeps going while
	 * turning and sprints on long stretches. Otherwise you see a zigzag with stops.
	 */
	static class WalkTo extends Step {
		final BlockPos target; List<BlockPos> route; int idx = 0; Vec3 previous; int still = 0; boolean noPath = false;
		WalkTo(BlockPos target) { this.target = target; }
		private void releaseKeys(Minecraft mc) { HeldKeys.set(mc.options.keyUp, false); HeldKeys.set(mc.options.keyDown, false); HeldKeys.set(mc.options.keySprint, false); }
		int tick(LocalPlayer p, Minecraft mc, AfkConfig c) {
			if (route == null) {
				route = path(p.level(), p.blockPosition(), target);
				if (route == null) {
					// details for the log: where are you, and are your own cell and the four beside it walkable?
					BlockPos here = new BlockPos(p.blockPosition().getX(), c.woodY, p.blockPosition().getZ());
					List<String> neighbours = new ArrayList<>();
					for (Direction d : Direction.Plane.HORIZONTAL) { BlockPos q = here.relative(d); neighbours.add(d.getName() + ":" + (walkable(p.level(), q) ? "ok" : blockId(p.level().getBlockState(q).getBlock()).replace("minecraft:", "") + "/" + blockId(p.level().getBlockState(q.above()).getBlock()).replace("minecraft:", ""))); }
					Exchange.emit("wood", "action", "no path", "to", target.toShortString(), "from", p.blockPosition().toShortString(), "floor_y", c.woodY, "own_slot", walkable(p.level(), here), "neighbours", neighbours, "goal_walkable", walkable(p.level(), target));
					noPath = true;
					return FAILED;
				}
			}
			noPathCount = 0;
			if (idx >= route.size()) {
				BlockPos leaf = leavesInTheWay(p.level(), target);
				if (leaf != null && withinReach(p, leaf)) { releaseKeys(mc); BreakBlock b = new BreakBlock(leaf); b.chop = chop; steps.addFirst(this); steps.addFirst(b); current = null; return DONE; }
				// stand exactly in the middle, otherwise you have a toe in the neighbouring block
				Vec3 m = new Vec3(target.getX() + 0.5, p.getY(), target.getZ() + 0.5);
				if (route.isEmpty() || p.position().distanceTo(m) < 0.3) { releaseKeys(mc); return DONE; }
				steer(p, mc, m, 0.3); return BUSY;
			}
			// cut corners: the farthest point on the route that can be reached in a straight line
			for (int j = Math.min(route.size() - 1, idx + 12); j > idx; j--) {
				BlockPos w = route.get(j);
				if (clearLine(p.level(), p.position(), new Vec3(w.getX() + 0.5, p.getY(), w.getZ() + 0.5), target.getY())) { idx = j; break; }
			}
			BlockPos wp = route.get(idx);
			BlockPos leaf = leavesInTheWay(p.level(), wp);
			if (leaf != null) {
				if (!withinReach(p, leaf)) { releaseKeys(mc); route = null; return elapsed() > 20_000 ? FAILED : BUSY; }
				releaseKeys(mc);
				BreakBlock b = new BreakBlock(leaf); b.chop = chop;
				steps.addFirst(this); steps.addFirst(b); current = null; return DONE;
			}
			Vec3 m = new Vec3(wp.getX() + 0.5, p.getY(), wp.getZ() + 0.5);
			double d = Math.hypot(m.x - p.getX(), m.z - p.getZ());
			if (d < (idx == route.size() - 1 ? 0.3 : 0.45)) { idx++; return BUSY; }
			steer(p, mc, m, d);
			// stuck?
			if (previous != null && p.position().distanceToSqr(previous) < 0.0004) { if (++still > 30) { releaseKeys(mc); route = null; idx = 0; still = 0; if (elapsed() > 20_000) return FAILED; } }
			else still = 0;
			previous = p.position();
			if (elapsed() > 15_000 + route.size() * 1200L) return FAILED;
			return BUSY;
		}
		/** Turn towards the point and walk as soon as you roughly look at it; sprint on a long stretch. */
		private void steer(LocalPlayer p, Minecraft mc, Vec3 m, double distance) {
			double dx = m.x - p.getX(), dz = m.z - p.getZ();
			float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
			float delta = Mth.wrapDegrees(yaw - p.getYRot());
			float pitch = p.getXRot() + Mth.clamp(8f - p.getXRot(), -6f, 6f) * 0.3f;   // slowly towards a normal walking posture
			// a bit back (planting on the way back): step backwards without turning around, like a player does
			if (distance <= 2.2 && Math.abs(delta) > 140) {
				MouseTurn.toward(p, p.getYRot() + Mth.clamp(Mth.wrapDegrees(delta - 180f), -6f, 6f), pitch);
				HeldKeys.set(mc.options.keyUp, false); HeldKeys.set(mc.options.keySprint, false); HeldKeys.set(mc.options.keyDown, true);
				return;
			}
			HeldKeys.set(mc.options.keyDown, false);
			float step = distance > 2 ? 16f : 10f;   // far away: turn briskly; close by: fine correction
			MouseTurn.toward(p, p.getYRot() + Mth.clamp(delta, -step, step) + (float) R.nextDouble(-0.4, 0.4), pitch);
			boolean walk = Math.abs(delta) < 60;
			HeldKeys.set(mc.options.keyUp, walk);
			HeldKeys.set(mc.options.keySprint, walk && distance > 5 && Math.abs(delta) < 20 && p.getFoodData().getFoodLevel() > 6);
		}
		void failed(LocalPlayer p, AfkConfig c) {
			releaseKeys(Minecraft.getInstance()); steps.clear();
			if (noPath) {
				// twice no path from the same spot: then you are standing somewhere with no way out. Do not strike the
				// trees off, but report, wait a moment and look again (maybe you walk a bit).
				if (noPathFrom != null && p.position().distanceTo(noPathFrom) < 1.5) noPathCount++; else { noPathCount = 1; noPathFrom = p.position(); }
				if (noPathCount >= 2) {
					steps.add(new Wait(10_000));
					return;
				}
			}
			skipped.put(new BlockPos(target.getX(), c.woodY, target.getZ()), System.currentTimeMillis() + 5 * 60_000L);
		}
		String name() { return "walk to " + target.toShortString(); }
		String label() { return String.format(java.util.Locale.ROOT, "walking to %s", target.toShortString()); }
	}

	/** Break one block: look at it, and hold the attack key as long as the crosshair is on it. If leaves are in between, those first. */
	static class BreakBlock extends Step {
		final BlockPos pos; int misses = 0, detours = 0; boolean looked = false, closer = false;
		BreakBlock(BlockPos pos) { this.pos = pos; }
		void begin(LocalPlayer p) { super.begin(p); grabAxe(p); }
		int tick(LocalPlayer p, Minecraft mc, AfkConfig c) {
			BlockState st = p.level().getBlockState(pos);
			if (st.isAir() || (!st.is(BlockTags.LOGS) && !st.is(BlockTags.LEAVES))) { hitTarget = null; breakFailed.remove(new BlockPos(pos.getX(), c.woodY, pos.getZ())); return DONE; }
			if (!withinReach(p, pos)) { Exchange.emit("wood", "action", "out of reach", "block", pos.toShortString()); return FAILED; }
			if (!looked) { looked = true; look.startByAngle(p, aimPoint(p, pos), 0.3f); }
			boolean turning = look.busy(); if (turning) look.tick(p);
			BlockPos ch = crosshairBlock(mc);
			if (pos.equals(ch)) { hitTarget = pos; misses = 0; }   // also mid-turn: hit as soon as it is on target
			else if (turning) { hitTarget = null; }
			else {
				hitTarget = null;
				BlockState chSt = ch == null ? null : p.level().getBlockState(ch);
				if (ch != null && (chSt.is(BlockTags.LEAVES) || chSt.is(BlockTags.LOGS)) && inField(ch, 2) && withinReach(p, ch) && detours++ < 16) {
					// leaves or a trunk of the neighbouring tree in the line of sight: that away first, then this block again
					BreakBlock b = new BreakBlock(ch); b.chop = chop;
					steps.addFirst(this); steps.addFirst(b); looked = false;
					current = null; return DONE;
				}
				/*
				 * Crosshair hits nothing while the block is just within reach: at a steep angle (a dark oak six
				 * high) the view ray just misses it. Trying five times from the same spot did not help and left
				 * the block standing (26 Sep). One step closer did.
				 */
				if (ch == null && !closer && misses > 12) {
					closer = true;
					BlockPos beside = nearestSpotBeside(p, new BlockPos(pos.getX(), c.woodY, pos.getZ()));
					if (beside != null && !beside.equals(p.blockPosition())) {
						Exchange.emit("wood", "action", "step closer to break", "block", pos.toShortString(), "to", beside.toShortString());
						steps.addFirst(this); steps.addFirst(new WalkTo(beside));
						looked = false; misses = 0; current = null; return DONE;
					}
				}
				aim(p, aimPoint(p, pos));
				if (++misses > 60) return FAILED;
			}
			if (elapsed() > 12_000) return FAILED;
			return BUSY;
		}
		/**
		 * If breaking fails, only leave that plot alone after two times, and only when it concerns the trunk of a
		 * known plot. A leaf hit away on the way may never strike a tree off.
		 */
		void failed(LocalPlayer p, AfkConfig c) {
			hitTarget = null;
			BlockState st = p.level().getBlockState(pos);
			BlockPos plot = new BlockPos(pos.getX(), c.woodY, pos.getZ());
			boolean ownTrunk = st.is(BlockTags.LOGS) && c.woodPlots.stream().anyMatch(q -> q.x == pos.getX() && q.z == pos.getZ());
			BlockPos ch = crosshairBlock(Minecraft.getInstance());
			Exchange.emit("wood", "action", "cannot break", "block", pos.toShortString(), "what", blockId(st.getBlock()).replace("minecraft:", ""),
				"crosshair", ch == null ? "nothing" : ch.toShortString() + "=" + blockId(p.level().getBlockState(ch).getBlock()).replace("minecraft:", ""),
				"reach", withinReach(p, pos), "own_trunk", ownTrunk);
			if (!ownTrunk) return;
			int n = breakFailed.merge(plot, 1, Integer::sum);
			if (n >= 2) { skipped.put(plot, System.currentTimeMillis() + 2 * 60_000L); breakFailed.remove(plot); }
		}
		String name() { return "breek " + pos.toShortString(); }
		String label() { return String.format(java.util.Locale.ROOT, "breaking %s", pos.toShortString()); }
	}

	/** Loose branch logs around a trunk (cherry, acacia, big oak): stand under them and chop them away. */
	static class Branches extends Step {
		final BlockPos trunk; final double radius; int done = 0;
		BlockPos last = null;   // where you chopped last: continue from there, otherwise you jump from storey to storey on a cherry
		final Set<BlockPos> tried = new HashSet<>();   // do not try the same branch endlessly
		Branches(BlockPos trunk, double radius) { this.trunk = trunk; this.radius = radius; }
		int tick(LocalPlayer p, Minecraft mc, AfkConfig c) {
			Level level = p.level();
			// trunks of neighbouring trees still standing belong to that tree; above a sapling or empty plot a branch is just a branch
			Set<BlockPos> columns = new HashSet<>();
			for (var pl : c.woodPlots) if (isGrown(level, pl)) columns.add(new BlockPos(pl.x, 0, pl.z));
			// order: what you can take without walking first, and within that what lies closest to the previous block
			BlockPos ref = last != null ? last : trunk;
			BlockPos best = null; double bd = 1e9; boolean bestInReach = false;
			int r = (int) radius;
			for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
				if (dx * dx + dz * dz > r * r) continue;
				if (columns.contains(new BlockPos(trunk.getX() + dx, 0, trunk.getZ() + dz)) && !(dx == 0 && dz == 0)) continue;   // trunk of another plot
				for (int dy = 0; dy <= MAX_HEIGHT; dy++) {
					BlockPos q = trunk.offset(dx, dy, dz);
					if (!level.getBlockState(q).is(BlockTags.LOGS) || tried.contains(q)) continue;
					double d = q.distSqr(ref);
					boolean inReach = withinReach(p, q);
					if (best == null || (inReach && !bestInReach) || (inReach == bestInReach && d < bd)) { bd = d; best = q; bestInReach = inReach; }
				}
			}
			if (best == null || done >= 40) return DONE;
			done++; tried.add(best); last = best;
			BreakBlock b = new BreakBlock(best); b.chop = chop;
			// within reach: chop from where you stand; otherwise stand under it, beside it if need be
			if (withinReach(p, best)) { steps.addFirst(this); steps.addFirst(b); current = null; return DONE; }
			BlockPos under = new BlockPos(best.getX(), c.woodY, best.getZ());
			BlockPos stand = walkable(level, under) ? under : nearestSpotBeside(p, under);
			if (stand == null) { Exchange.emit("wood", "action", "branch unreachable", "block", best.toShortString()); return DONE; }
			WalkTo w = new WalkTo(stand); w.chop = chop;
			steps.addFirst(this); steps.addFirst(b); steps.addFirst(w);
			current = null; return DONE;
		}
		String name() { return "branches at " + trunk.toShortString(); }
		String label() { return String.format(java.util.Locale.ROOT, "branches at %s", trunk.toShortString()); }
	}

	/** Put a sapling back on the dirt under the old trunk. */
	static class Plant extends Step {
		final BlockPos plot; final String kind; final boolean quick; int attempts = 0, wait = 0; boolean looked = false, hotbarTried = false, walked = false;
		Plant(BlockPos plot, String kind) { this(plot, kind, false); }
		/** quick: from where you stand, with a flick of the eyes; not within reach, then walk there after all. */
		Plant(BlockPos plot, String kind, boolean quick) { this.plot = plot; this.kind = kind; this.quick = quick; }
		int tick(LocalPlayer p, Minecraft mc, AfkConfig c) {
			Level level = p.level();
			if (isSapling(level, plot) || level.getBlockState(plot).is(BlockTags.LOGS)) return DONE;
			if (wait > 0) { wait--; return BUSY; }
			Item it = itemOf(kind);
			int slot = hotbarSlot(p, it);
			if (slot < 0) {
				if (countInInventory(p, it) == 0 || hotbarTried) { plantLater.put(plot, kind); Exchange.emit("wood", "action", "no sapling", "kind", kind.replace("minecraft:", ""), "spot", plot.toShortString(), "inHotbar", hotbarTried); return DONE; }
				hotbarTried = true;
				steps.addFirst(this); steps.addFirst(new ToHotbar(it)); current = null; return DONE;
			}
			if (p.getInventory().getSelectedSlot() != slot) { p.getInventory().setSelectedSlot(slot); wait = 3; return BUSY; }
			if (!level.getBlockState(plot).isAir()) { Exchange.emit("wood", "action", "spot not empty", "spot", plot.toShortString(), "block", blockId(level.getBlockState(plot).getBlock())); return FAILED; }
			if (!enoughForGroup(p, c, plot, kind)) {   // 2x2: four corners or nothing
				plantLater.put(plot, kind);
				skipped.put(new BlockPos(plot.getX(), c.woodY, plot.getZ()), System.currentTimeMillis() + 60_000L);
				Exchange.emit("wood", "action", "too few for 2x2", "spot", plot.toShortString(), "kind", kind.replace("minecraft:", ""), "in_bag", countInInventory(p, itemOf(kind)));
				return DONE;
			}
			BlockPos dirt = plot.below();
			if (!withinReach(p, dirt)) {
				if (walked) return FAILED;
				walked = true;
				BlockPos beside = onTheRow(p, c, plot);
				if (beside == null) beside = nearestSpotBeside(p, plot);
				if (beside == null) for (Direction d : Direction.Plane.HORIZONTAL) {   // 2x2: the columns beside it are empty now
					BlockPos q = new BlockPos(plot.getX() + d.getStepX(), c.woodY, plot.getZ() + d.getStepZ());
					if (walkable(p.level(), q)) { beside = q; break; }
				}
				if (beside == null) return FAILED;
				steps.addFirst(this); steps.addFirst(new WalkTo(beside)); current = null; return DONE;
			}
			Vec3 target = aimAtGround(p, plot);
			if (!looked) { looked = true; look.startByAngle(p, target, 0.2f); }
			boolean turning = look.busy(); if (turning) look.tick(p);
			if (mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK && b.getBlockPos().equals(dirt) && b.getDirection() == Direction.UP) {
				clickOn(dirt); wait = 3; attempts++;
				if (attempts > 4) return FAILED;
				return BUSY;
			}
			if (!turning) aim(p, target);
			if (elapsed() > 8000) return FAILED;
			return BUSY;
		}
		/*
		 * When the plant step failed, the plot went straight back on the list and the planning built it up again:
		 * "planten onderweg" and "plant" alternated while the buddy stood still (26 Sep). After two failures we
		 * leave that plot alone for three minutes; by then the leaves have decayed or it looks different.
		 */
		void failed(LocalPlayer p, AfkConfig c) {
			int n = plantFailed.merge(plot, 1, Integer::sum);
			if (n < 2) { plantLater.put(plot, kind); return; }
			plantFailed.remove(plot);
			plantLater.remove(plot);
			skipped.put(new BlockPos(plot.getX(), c.woodY, plot.getZ()), System.currentTimeMillis() + 3 * 60_000L);
			Exchange.emit("wood", "action", "skip the spot for now", "spot", plot.toShortString(), "kind", kind.replace("minecraft:", ""));
		}
		String name() { return "plant " + kind.replace("minecraft:", "") + " at " + plot.toShortString(); }
		String label() { return String.format(java.util.Locale.ROOT, "planting %s at %s", kind.replace("minecraft:", ""), plot.toShortString()); }
	}

	/**
	 * At the start: pass the chests until there are enough saplings for what the field is going to ask, or until
	 * all chests have been looked at. Taking one chest and hoping it is the sapling chest went wrong.
	 */
	static class Stock extends Step {
		final Map<String, Integer> needed; final Set<BlockPos> visited = new HashSet<>();
		Stock(Map<String, Integer> needed) { this.needed = needed; }
		int tick(LocalPlayer p, Minecraft mc, AfkConfig c) {
			boolean shortage = false;
			for (var e : needed.entrySet()) if (countInInventory(p, itemOf(e.getKey())) < e.getValue()) shortage = true;
			if (!shortage) return DONE;
			// next chest: a known sapling chest first, otherwise a chest we have not seen yet
			AfkConfig.ChestPos best = null; double bd = 1e9; boolean bestKnown = false;
			for (var k : c.woodChests) {
				if (visited.contains(k.pos()) || fullChests.contains(k.pos())) continue;
				ChestRole role = chestRole.getOrDefault(k.pos(), ChestRole.UNKNOWN);
				if (role == ChestRole.DROPOFF) continue;
				boolean known = role == ChestRole.SAPLINGS; double d = k.pos().distSqr(p.blockPosition());
				if (best == null || (known && !bestKnown) || (known == bestKnown && d < bd)) { bd = d; best = k; bestKnown = known; }
			}
			if (best == null) { Exchange.emit("wood", "action", "stock: all chests looked at", "short", true); return DONE; }
			BlockPos beside = nearestSpotBeside(p, best.pos());
			visited.add(best.pos());
			if (beside == null) return BUSY;
			steps.addFirst(this); steps.addFirst(new ChestStep(best.pos(), ChestPurpose.SAPLINGS)); steps.addFirst(new WalkTo(beside));
			lastChest = System.currentTimeMillis();
			current = null; return DONE;
		}
		String name() { return "fetch stock"; }
		String label() { return "fetching stock"; }
	}

	/**
	 * Before replanting: do we have enough saplings for these plots? If not, first pick up what is already lying
	 * there, then the chest, and only then backwards through the strip. Otherwise you walk the whole route empty-handed.
	 */
	static class ReadyToPlant extends Step {
		final List<AfkConfig.WoodPlot> plots; final List<BlockPos> route; boolean pickedUp = false, chestDone = false;
		final int row; final boolean alongX; final int dx, dz; int replanned = 0;
		ReadyToPlant(List<AfkConfig.WoodPlot> plots, List<BlockPos> route, int row, boolean alongX, int dx, int dz) {
			this.plots = plots; this.route = route; this.row = row; this.alongX = alongX; this.dx = dx; this.dz = dz;
		}
		/** What is missing per kind to fill all the plots. */
		private Map<String, Integer> shortage(LocalPlayer p) {
			Map<String, Integer> needed = new HashMap<>();
			for (var pl : plots) if (!isSapling(p.level(), pl.pos())) needed.merge(pl.kind, 1, Integer::sum);
			Map<String, Integer> missing = new HashMap<>();
			for (var e : needed.entrySet()) { int have = countInInventory(p, itemOf(e.getKey())); if (have < e.getValue()) missing.put(e.getKey(), e.getValue() - have); }
			return missing;
		}
		int tick(LocalPlayer p, Minecraft mc, AfkConfig c) {
			// has a tree grown in this row meanwhile? That one first, otherwise you plant around a trunk (and walk
			// backwards into it). Replanning twice is enough; after that it is for the next round.
			if (replanned < 2) {
				List<AfkConfig.WoodPlot> fresh = new ArrayList<>(); int trunks = 0;
				for (var pl : c.woodPlots) if (rowOf(c, pl) == row && isGrown(p.level(), pl)) { trunks++; if (!plots.contains(pl)) fresh.add(pl); }
				Exchange.emit("wood", "action", "check before planting", "row", row, "logs_in_row", trunks, "new", fresh.size(), "to_plant", plots.size());
				if (!fresh.isEmpty()) {
					replanned++;
					fresh.sort(Comparator.comparingDouble(q -> q.pos().distSqr(p.blockPosition())));
					ChopPlan extra = chopPlan(p, c, fresh, alongX, dx, dz, System.currentTimeMillis());
					// the new plots at the front of the planting route (you leave them last)
					for (int i = extra.done.size() - 1; i >= 0; i--) { plots.add(0, extra.done.get(i)); route.add(0, extra.front.get(i)); }
					Exchange.emit("wood", "action", "grown in row", "row", row, "trees", extra.done.size());
					steps.addFirst(this);
					for (var g : extra.done) steps.addFirst(new Branches(g.pos(), BRANCH_RADIUS).chop());
					for (int i = extra.steps.size() - 1; i >= 0; i--) steps.addFirst(extra.steps.get(i));
					current = null; return DONE;
				}
			}
			Map<String, Integer> missing = shortage(p);
			// too few, and there is still a row standing: chop that first. By the time it is down the leaves here have
			// decayed and the saplings lie ready; this row then comes up as "open plots".
			if (!missing.isEmpty() && nextTree(p, c) != null) {
				Exchange.emit("wood", "action", "planting put off", "short", missing);
				for (var pl : plots) if (!isSapling(p.level(), pl.pos())) plantLater.put(pl.pos(), pl.kind);
				return DONE;
			}
			if (!missing.isEmpty() && !pickedUp && pickupTime(c, System.currentTimeMillis()) && !looseItems(p, c).isEmpty()) {
				pickedUp = true; steps.addFirst(this); steps.addFirst(new Pickup()); current = null; return DONE;
			}
			if (!missing.isEmpty() && !chestDone && !c.woodChests.isEmpty()) {
				chestDone = true;
				Exchange.emit("wood", "action", "short of saplings", "short", missing);
				steps.addFirst(this);
				if (planChestFirst(p, c)) { current = null; return DONE; }
				steps.removeFirst();
			}
			// now plant with what we have; the rest comes later, when the leaves have dropped their saplings
			steps.addFirst(new PlantOnTheWay(plots, route));
			current = null; return DONE;
		}
		String name() { return "count saplings"; }
		String label() { return "counting saplings"; }
	}

	/** Put chest steps at the front of the queue (for a quick side trip). */
	private static boolean planChestFirst(LocalPlayer p, AfkConfig c) {
		AfkConfig.ChestPos best = pickChest(p, c, ChestPurpose.SAPLINGS);
		if (best == null) return false;
		BlockPos beside = nearestSpotBeside(p, best.pos());
		if (beside == null) return false;
		steps.addFirst(new ChestStep(best.pos(), ChestPurpose.SAPLINGS)); steps.addFirst(new WalkTo(beside));
		lastChest = System.currentTimeMillis();
		return true;
	}

	/**
	 * Walk backwards through the strip with the sapling in hand and click as soon as the crosshair passes over
	 * the empty dirt of a plot, like a player does: no stopping, no waiting. The gaze is angled down at about a
	 * block in front of you, so on the plot you just left. What is missed goes on "later".
	 */
	static class PlantOnTheWay extends Step {
		final List<AfkConfig.WoodPlot> plots; final List<BlockPos> route;   // plots in walking order, and where you walk to each time
		final Set<BlockPos> toDo = new HashSet<>(); int idx = 0, sinceClick = 99, still = 0; Vec3 previous;
		/*
		 * The plot aimed at now. Without this the plot with the smallest angle was picked anew every tick;
		 * if two were about equally close, that choice flipped back and forth, the buddy kept turning back and walked
		 * jerkily forwards and backwards until the step failed after tens of seconds.
		 */
		BlockPos aimTarget = null;
		/*
		 * Walking backwards (looking into the row just planted)? Without a threshold this flipped
		 * as soon as the distance passed 2.2: the buddy walked forwards, switched to backwards, thereby got farther than
		 * 2.2 again and flipped back. That is the spinning the owner saw (26 Sep).
		 */
		boolean besideLatched = false;
		long lastStuckLog = 0;
		/* No progress towards your point: usually leaves of a spruce or cherry right in front of your nose. */
		double bestD = 99; long bestDSince = 0; int skippedPoints = 0;
		/* Stay at a point for a moment until that one plot is planted: walking past and hoping the crosshair is
		 * right leaves too many behind, and then you need several rounds for one row. */
		int waitIdx = -1; long waitSince = 0;
		/* Noise that drifts slowly: a new value every tick is not a human hand but jitter. */
		float noise = 0; int noiseTicks = 0;
		/** Interrupted: the rest goes on later, and this row back into this kind's round. */
		private void interrupt() {
			for (BlockPos q : toDo) { AfkConfig.WoodPlot pl = plotAt(q); if (pl != null) plantLater.put(q, pl.kind); }
			if (!plots.isEmpty()) chopFirstRow = rowOf(AfkConfig.get(), plots.get(0));
			chopFirst = true;
		}
		PlantOnTheWay(List<AfkConfig.WoodPlot> plots, List<BlockPos> route) {
			this.plots = plots; this.route = route;
			for (var pl : plots) toDo.add(pl.pos());
		}
		private AfkConfig.WoodPlot plotAt(BlockPos pos) { for (var pl : plots) if (pl.pos().equals(pos)) return pl; return null; }
		boolean hotbarReady = false;
		void begin(LocalPlayer p) { super.begin(p); plantingStep = this; }
		/** May a sapling go here? Used by startTick with the crosshair of that moment. */
		boolean allowedHere(LocalPlayer p, BlockPos plot) {
			if (sinceClick < 3 || !toDo.contains(plot) || !p.level().getBlockState(plot).isAir()) return false;
			AfkConfig.WoodPlot pl = plotAt(plot);
			if (pl == null || !p.getInventory().getSelectedItem().is(itemOf(pl.kind))) return false;
			// with dark oak and jungle only plant when the whole 2x2 can be filled
			return enoughForGroup(p, AfkConfig.get(), plot, pl.kind);
		}
		void placed() { sinceClick = 0; }
		int tick(LocalPlayer p, Minecraft mc, AfkConfig c) {
			Level level = p.level();
			sinceClick++;
			toDo.removeIf(q -> isSapling(level, q) || level.getBlockState(q).is(BlockTags.LOGS));
			if (toDo.isEmpty()) { HeldKeys.set(mc.options.keyDown, false); HeldKeys.set(mc.options.keyUp, false); plantingStep = null; return DONE; }
			if (!hotbarReady) {
				// every kind we need that is in the inventory but not in the hotbar: to the hotbar first
				hotbarReady = true;
				for (var pl : plots) { Item it = itemOf(pl.kind); if (toDo.contains(pl.pos()) && hotbarSlot(p, it) < 0 && countInInventory(p, it) > 0) { steps.addFirst(this); steps.addFirst(new ToHotbar(it)); current = null; return DONE; } }
			}
			// the sapling in hand before you start walking, not only once you stand above it
			for (BlockPos q : toDo) {
				AfkConfig.WoodPlot pl = plotAt(q);
				if (pl == null) continue;
				int slot = hotbarSlot(p, itemOf(pl.kind));
				if (slot >= 0 && p.getInventory().getSelectedSlot() != slot) p.getInventory().setSelectedSlot(slot);
				break;
			}
			if (idx >= route.size()) {
				// at the end: stand still a moment for the last plot (it is right in front of you now), then the rest on later
				HeldKeys.set(mc.options.keyDown, false); HeldKeys.set(mc.options.keyUp, false);
				if (arrived == 0) arrived = System.currentTimeMillis();
				if (System.currentTimeMillis() - arrived > 500) { for (BlockPos q : toDo) { AfkConfig.WoodPlot pl = plotAt(q); if (pl != null) plantLater.put(q, pl.kind); } plantingStep = null; return DONE; }
				BlockPos rest = toDo.stream().min(Comparator.comparingDouble(q -> q.distSqr(p.blockPosition()))).get();
				aim(p, aimAtGround(p, rest));
				clickIfHit(p, mc, level);
				return BUSY;
			}
			// walking: backwards to the next point, with the gaze the other way (at the plots you are leaving)
			BlockPos wp = route.get(idx);
			// leaves on the path (cherry hangs low): hit them away and carry on, just like when walking
			BlockPos leaf = leavesInTheWay(level, wp);
			if (leaf != null && withinReach(p, leaf)) { HeldKeys.set(mc.options.keyDown, false); steps.addFirst(this); steps.addFirst(new BreakBlock(leaf)); current = null; return DONE; }
			// a trunk on the path (just grown): do not wait until you get stuck, hand over to chopping right away
			if (level.getBlockState(wp).is(BlockTags.LOGS) || level.getBlockState(wp.above()).is(BlockTags.LOGS)) {
				HeldKeys.set(mc.options.keyDown, false);
				Exchange.emit("wood", "action", "trunk on the planting path", "block", wp.toShortString(), "left", toDo.size());
				interrupt();
				return DONE;
			}
			Vec3 m = new Vec3(wp.getX() + 0.5, p.getY(), wp.getZ() + 0.5);
			double dx = m.x - p.getX(), dz = m.z - p.getZ(), d = Math.hypot(dx, dz);
			// 0.35 block was too strict: close to the point the buddy walks backwards and slides just past the
			// middle, and then the whole row was given up ("kom niet bij mijn punt" at 0.87 block, 26 Sep).
			if (d < 0.9) {
				// finish the plot at this point first; only when it is there (or after 2 s) on to the next
				AfkConfig.WoodPlot here = idx < plots.size() ? plots.get(idx) : null;
				if (here != null && toDo.contains(here.pos())) {
					if (waitIdx != idx) { waitIdx = idx; waitSince = System.currentTimeMillis(); }
					if (System.currentTimeMillis() - waitSince < 2500) {
						aimTarget = here.pos();
						aim(p, aimAtGround(p, here.pos()));
						if (withinReach(p, here.pos().below())) {
							HeldKeys.set(mc.options.keyUp, false); HeldKeys.set(mc.options.keyDown, false);
						} else {
							// just walked past: step back while you keep looking at it, like you do yourself
							double tx = here.x + 0.5 - p.getX(), tz = here.z + 0.5 - p.getZ();
							float toward = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-tx, tz)) - p.getYRot());
							HeldKeys.set(mc.options.keyUp, Math.abs(toward) < 50);
							HeldKeys.set(mc.options.keyDown, Math.abs(toward) > 130);
						}
						clickIfHit(p, mc, level);
						return BUSY;
					}
					HeldKeys.set(mc.options.keyUp, false); HeldKeys.set(mc.options.keyDown, false);
					Exchange.emit("wood", "action", "no spot at the point", "spot", here.pos().toShortString());
				}
				idx++; bestD = 99; bestDSince = 0; return BUSY;
			}
			float yawAway = (float) Math.toDegrees(Math.atan2(-dx, dz));            // direction you are walking in
			// walking backwards you only do to the block next to you; a longer stretch you simply walk forwards, that looks
			// normal and planting is not possible there anyway (the crosshair is then not on the floor in front of you)
			boolean beside = besideLatched ? d < 3.2 : d <= 2.2;
			besideLatched = beside;
			// Aim at the top face of the dirt of the plot that is up now. With the fixed view angle of 52° the
			// crosshair landed just on the dirt in front at one plot and on the sapling already there at the next;
			// then the hitbox is wrong, no click goes out and the plot stays empty (screenshot,
			// 25 Sep 2026). The plot then only comes up again much later.
			BlockPos aimAt = null;
			if (beside) {
				// do not keep aiming at a plot farther away: that is standing and turning instead of planting
				boolean stillGood = aimTarget != null && toDo.contains(aimTarget) && withinReach(p, aimTarget.below())
					&& horizontal(p, aimTarget) > 0.8 && aimTarget.distToCenterSqr(p.getX(), p.getY(), p.getZ()) < 2.5 * 2.5;
				if (!stillGood) aimTarget = toDo.stream()
					.filter(q -> withinReach(p, q.below()) && horizontal(p, q) > 0.8 && horizontal(p, q) < 2.5)
					.min(Comparator.comparingDouble(q -> horizontal(p, q))).orElse(null);
				aimAt = aimTarget;
			} else aimTarget = null;
			float yawLook, pitch;
			if (aimAt != null) {
				Vec3 eye = p.getEyePosition();
				Vec3 mp = aimAtGround(p, aimAt);
				double mx = mp.x - eye.x, my = mp.y - eye.y, mz = mp.z - eye.z;
				yawLook = (float) Math.toDegrees(Math.atan2(-mx, mz));
				pitch = (float) -Math.toDegrees(Math.atan2(my, Math.hypot(mx, mz)));
			} else if (beside) {
				// no plot within reach for a moment: keep looking where you look and at the ground, do not flip
				yawLook = p.getYRot();
				pitch = 52f + (float) Math.sin(System.nanoTime() / 1.2e9) * 2f;
			} else {
				yawLook = yawAway;
				pitch = 8f;
			}
			/*
			 * Which key brings the buddy to the point? Backwards is relative to the view direction, and that points
			 * at the plot being planted. If the point lay to the side, the walk went away diagonally, got outside
			 * the boundary, turned back and it started again: the fast back and forth the owner saw (26 Sep,
			 * distance that kept swinging between 2 and 3.3). Now the angle to the point counts: if it
			 * is behind, then backwards, in front then forwards, and if it is to the side the buddy simply turns
			 * towards it and walks forwards.
			 */
			float toWp = Mth.wrapDegrees(yawAway - p.getYRot());
			boolean backwards = beside && Math.abs(toWp) > 140;
			boolean forwardsToWp = !backwards && Math.abs(toWp) < 60;
			if (beside && !backwards && !forwardsToWp) { yawLook = yawAway; pitch = 8f; }   // to the side: turn to your point first
			float delta = Mth.wrapDegrees(yawLook - p.getYRot());
			// turn calmly close to a plot: with 14 degrees per tick plus switching targets the picture shook
			float maxTurn = beside ? 7f : 14f;
			if (--noiseTicks <= 0) { noiseTicks = R.nextInt(20, 45); noise = (float) R.nextDouble(-0.6, 0.6); }
			MouseTurn.toward(p, p.getYRot() + Mth.clamp(delta, -maxTurn, maxTurn) + noise, p.getXRot() + Mth.clamp(pitch - p.getXRot(), -6f, 6f));
			HeldKeys.set(mc.options.keyDown, backwards && Math.abs(delta) < 30);
			HeldKeys.set(mc.options.keyUp, !backwards && Math.abs(toWp) < 45);
			/*
			 * If the buddy gets no closer to the point, something is in the way: with spruce and cherry the leaves hang
			 * down to eye level. On 26 Sep the buddy kept swinging around one spot because of that, with the crosshair in
			 * the leaves. We hit those leaves away, just like when walking normally; if that fails the row goes on later.
			 */
			// Standing still to plant is not being stuck: then this check does not count.
			if (d < bestD - 0.1 || sinceClick < 40) { bestD = Math.min(bestD, d); bestDSince = System.currentTimeMillis(); }
			else if (bestDSince == 0) bestDSince = System.currentTimeMillis();
			else if (System.currentTimeMillis() - bestDSince > 2500) {
				bestD = 99; bestDSince = 0;
				// a trunk that has grown between the buddy and the point: chop that first, do not fiddle around it
				BlockPos between = p.blockPosition().offset(Integer.signum(wp.getX() - p.blockPosition().getX()), 0, Integer.signum(wp.getZ() - p.blockPosition().getZ()));
				for (BlockPos q : new BlockPos[] { between, between.above(), p.blockPosition().above() }) {
					if (level.getBlockState(q).is(BlockTags.LOGS)) {
						HeldKeys.set(mc.options.keyDown, false); HeldKeys.set(mc.options.keyUp, false);
						Exchange.emit("wood", "action", "trunk in the way of planting", "block", q.toShortString(), "left", toDo.size());
						interrupt();
						plantingStep = null;
						return DONE;
					}
				}
				// only leaves that are really on the walking path: the leaves under the crosshair usually hang to the side,
				// and hitting those away helped nothing and looked like jitter (26 Sep)
				BlockPos away = leavesInTheWay(level, wp);
				if (away == null) away = leavesInTheWay(level, p.blockPosition());
				if (away != null && withinReach(p, away)) {
					HeldKeys.set(mc.options.keyDown, false); HeldKeys.set(mc.options.keyUp, false);
					Exchange.emit("wood", "action", "leaf in the way of planting", "leaf", away.toShortString(), "wp", wp.toShortString());
					BreakBlock b = new BreakBlock(away); b.chop = chop;
					steps.addFirst(this); steps.addFirst(b); current = null; return DONE;
				}
				// not reaching this point does not mean the row is worthless: skip it and carry on.
				// Only when three points in a row fail is something really wrong with this row.
				skippedPoints++;
				Exchange.emit("wood", "action", "point skipped", "wp", wp.toShortString(), "d", Math.round(d * 100) / 100.0,
					"left", toDo.size(), "skipped", skippedPoints);
				HeldKeys.set(mc.options.keyDown, false); HeldKeys.set(mc.options.keyUp, false);
				if (skippedPoints >= 3) { Exchange.emit("wood", "action", "cannot reach my point", "wp", wp.toShortString(), "left", toDo.size()); return FAILED; }
				idx++;
				return BUSY;
			}
			// measure instead of guess: if the buddy is jittering without planting, log what is in sight at that moment
			if (sinceClick > 40 && System.currentTimeMillis() - lastStuckLog > 1000) {
				lastStuckLog = System.currentTimeMillis();
				String crosshair = "nothing";
				if (mc.hitResult instanceof BlockHitResult hb && hb.getType() == HitResult.Type.BLOCK)
					crosshair = hb.getBlockPos().toShortString() + "=" + blockId(level.getBlockState(hb.getBlockPos()).getBlock()).replace("minecraft:", "") + "/" + hb.getDirection().getName();
				Exchange.emit("wood", "action", "plant stuck", "wp", wp.toShortString(), "idx", idx, "route", route.size(),
					"d", Math.round(d * 100) / 100.0, "beside", beside, "aim", aimAt == null ? "-" : aimAt.toShortString(),
					"delta", Math.round(delta), "yaw", Math.round(p.getYRot()), "pitch", Math.round(p.getXRot()),
					"cross", crosshair, "hand", itemId(p.getInventory().getSelectedItem().getItem()), "left", toDo.size());
			}
			// stuck (leaves, item, something in the way): try a step sideways first, only then give up
			// stuck (a tree coming up beside you pushes you sideways or under): do not break off to another kind, but resume the same row
			if (previous != null && p.position().distanceToSqr(previous) < 0.0004) {
				still++;
				if (still > 15 && still <= 30) {   // three quarters of a second still: shuffle sideways, maybe you are against a trunk or an item
					HeldKeys.set(mc.options.keyDown, false);
					HeldKeys.set(mc.options.keyLeft, still % 20 < 10);
					HeldKeys.set(mc.options.keyRight, still % 20 >= 10);
					return BUSY;
				}
				HeldKeys.set(mc.options.keyLeft, false); HeldKeys.set(mc.options.keyRight, false);
				if (still > 60) { HeldKeys.set(mc.options.keyDown, false); Exchange.emit("wood", "action", "stuck while planting", "left", toDo.size()); return FAILED; }   // failed() does interrupt()
			} else { still = 0; HeldKeys.set(mc.options.keyLeft, false); HeldKeys.set(mc.options.keyRight, false); }
			previous = p.position();
			clickIfHit(p, mc, level);
			if (elapsed() > 10_000 + route.size() * 1500L) { HeldKeys.set(mc.options.keyDown, false); return FAILED; }
			return BUSY;
		}
		long arrived = 0;
		/** Horizontal distance from the player to the centre of a plot. */
		private static double horizontal(LocalPlayer p, BlockPos q) { return Math.hypot(q.getX() + 0.5 - p.getX(), q.getZ() + 0.5 - p.getZ()); }

		/** Make sure the right sapling is in hand; the placing itself is done by startTick with the crosshair of that moment. */
		private void clickIfHit(LocalPlayer p, Minecraft mc, Level level) {
			if (!(mc.hitResult instanceof BlockHitResult b) || b.getType() != HitResult.Type.BLOCK || b.getDirection() != Direction.UP) return;
			BlockPos plot = b.getBlockPos().above();
			AfkConfig.WoodPlot pl = plotAt(plot);
			if (pl == null || !toDo.contains(plot)) return;
			int slot = hotbarSlot(p, itemOf(pl.kind));
			if (slot < 0) { toDo.remove(plot); plantLater.put(plot, pl.kind); return; }
			if (p.getInventory().getSelectedSlot() != slot) p.getInventory().setSelectedSlot(slot);
		}
		void failed(LocalPlayer p, AfkConfig c) { var o = Minecraft.getInstance().options; HeldKeys.set(o.keyDown, false); HeldKeys.set(o.keyUp, false); plantingStep = null; interrupt(); }
		String name() { return "plant on the way (" + toDo.size() + ")"; }
		String label() { return String.format(java.util.Locale.ROOT, "planting on the way (%s)", toDo.size()); }
	}

	/** Bonemeal on a sapling until it grows, with human pauses in between. */
	static class Bonemeal extends Step {
		final BlockPos plot; int times = 0, wait = 0; boolean looked = false, hotbarTried = false;
		Bonemeal(BlockPos plot) { this.plot = plot; }
		int tick(LocalPlayer p, Minecraft mc, AfkConfig c) {
			Level level = p.level();
			if (level.getBlockState(plot).is(BlockTags.LOGS)) { bonemealAttempts.remove(plot); Exchange.emit("wood", "action", "grown", "spot", plot.toShortString(), "bonemeal", times); return DONE; }
			if (!isSapling(level, plot)) return DONE;
			if (wait > 0) { wait--; return BUSY; }
			int slot = hotbarSlot(p, Items.BONE_MEAL);
			if (slot < 0) {
				if (countInInventory(p, Items.BONE_MEAL) == 0 || hotbarTried) return DONE;
				hotbarTried = true;
				steps.addFirst(this); steps.addFirst(new ToHotbar(Items.BONE_MEAL)); current = null; return DONE;
			}
			if (p.getInventory().getSelectedSlot() != slot) { p.getInventory().setSelectedSlot(slot); wait = 3; return BUSY; }
			int total = bonemealAttempts.merge(plot, 0, Integer::sum);
			if (total >= c.woodBonemealMax) { Exchange.emit("wood", "action", "does not grow", "spot", plot.toShortString(), "bonemeal", total);  return DONE; }
			Vec3 target = new Vec3(plot.getX() + 0.5, plot.getY() + 0.3, plot.getZ() + 0.5);
			if (!looked) { looked = true; look.startByAngle(p, target, 0.2f); }
			boolean turning = look.busy(); if (turning) look.tick(p);
			if (plot.equals(crosshairBlock(mc))) {
				clickOn(plot); times++; bonemealAttempts.merge(plot, 1, Integer::sum);
				wait = R.nextInt(6, 17);   // 0.3-0.8 s, sometimes longer
				if (R.nextInt(6) == 0) wait += R.nextInt(10, 30);
				return BUSY;
			}
			if (!turning) aim(p, target);
			if (elapsed() > 30_000) return FAILED;
			return BUSY;
		}
		String name() { return "bonemeal " + plot.toShortString(); }
		String label() { return String.format(java.util.Locale.ROOT, "bonemeal %s", plot.toShortString()); }
	}

	/** Fetch an item from the inventory to a free hotbar slot (5-9): inventory open, one click, closed. */
	static class ToHotbar extends Step {
		final Item it; int phase = 0, wait = 0;
		ToHotbar(Item it) { this.it = it; }
		int tick(LocalPlayer p, Minecraft mc, AfkConfig c) {
			if (wait > 0) { wait--; return BUSY; }
			if (phase == 0) {
				if (hotbarSlot(p, it) >= 0) return DONE;
				// open no screen: the click goes to the server as a packet, exactly as with an open inventory. Opening
				// and closing a screen while the chat was open made vanilla bring up its pause menu.
				phase = 1; wait = 2; return BUSY;
			}
			if (phase == 1) {
				int source = -1;
				for (int i = 9; i < 36; i++) if (p.getInventory().getItem(i).is(it)) { source = i; break; }
				int target = -1;
				for (int i = 4; i < 9; i++) if (p.getInventory().getItem(i).isEmpty()) { target = i; break; }
				if (target < 0) for (int i = 4; i < 9; i++) { ItemStack s = p.getInventory().getItem(i); if (!s.is(ItemTags.AXES) && !s.is(ItemTags.SAPLINGS) && !s.is(Items.BONE_MEAL) && s.get(net.minecraft.core.component.DataComponents.FOOD) == null) { target = i; break; } }
				// full of useful stuff: then swap out another sapling kind (it comes back when it is needed)
				if (target < 0) for (int i = 4; i < 9; i++) { ItemStack s = p.getInventory().getItem(i); if (s.is(ItemTags.SAPLINGS) && !s.is(it)) { target = i; break; } }
				if (source < 0 || target < 0) { phase = 2; wait = 4; Exchange.emit("wood", "action", "hotbar full or item gone", "item", itemId(it)); return BUSY; }
				// slot 9-35 of the inventory is also slot 9-35 in the inventory menu; SWAP with button = hotbar index
				Game.swapWithHotbar(mc.gameMode, p.inventoryMenu.containerId, source, target, p);
				phase = 2; wait = 3; return BUSY;
			}
			return hotbarSlot(p, it) >= 0 ? DONE : FAILED;
		}
		String name() { return "to hotbar " + itemId(it); }
		String label() { return String.format(java.util.Locale.ROOT, "to hotbar %s", itemId(it)); }
	}

	/** Pick up everything lying around: keep walking to the nearest item. */
	static class Pickup extends Step {
		int pickedUp = 0; WalkTo walk = null; long lastNew = 0;
		int tick(LocalPlayer p, Minecraft mc, AfkConfig c) {
			if (walk != null) {
				int r = walk.tick(p, mc, c);
				if (r == BUSY) return BUSY;
				walk = null; HeldKeys.set(mc.options.keyUp, false);
				if (r == FAILED) return elapsed() > 60_000 ? DONE : BUSY;
			}
			if (freeSlots(p) == 0) { Exchange.emit("wood", "action", "inventory full"); return DONE; }
			List<ItemEntity> loose = looseItems(p, c);
			if (loose.isEmpty() || elapsed() > 120_000 || pickedUp > 60) { Exchange.emit("wood", "action", "picked up", "items", pickedUp); return DONE; }
			loose.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
			for (ItemEntity e : loose) {
				BlockPos cell = new BlockPos(Mth.floor(e.getX()), c.woodY, Mth.floor(e.getZ()));
				if (!walkable(p.level(), cell)) continue;
				walk = new WalkTo(cell); walk.begin(p); pickedUp++;
				return BUSY;
			}
			return DONE;   // what is lying there is not reachable
		}
		String name() { return "pick up"; }
		String label() { return "picking up"; }
	}

	/** Chest: open, tree stuff in, bonemeal and saplings out up to the reserve, close. */
	static class ChestStep extends Step {
		final BlockPos pos; final ChestPurpose purpose; ChestRole role = ChestRole.UNKNOWN;
		int phase = 0, wait = 0, clicks = 0; boolean looked = false, chatWasOpen = false; ChestMenu menu = null;
		final Set<Integer> stuck = new HashSet<>();   // slots where a click did nothing
		/** Close the chest. If the chat was open when the chest opened, it comes back: without a screen and without focus vanilla otherwise opens its pause menu. */
		private void close(LocalPlayer p, Minecraft mc) {
			p.closeContainer();
			if (chatWasOpen) mc.setScreenAndShow(new net.minecraft.client.gui.screens.ChatScreen("", false));
		}
		int lastSlot = -1; ItemStack lastStack = ItemStack.EMPTY;
		ChestStep(BlockPos pos, ChestPurpose purpose) { this.pos = pos; this.purpose = purpose; }
		/** Click, and remember what was there: if it is still exactly the same next time, the chest (or your inventory) is full. */
		private void click(Minecraft mc, LocalPlayer p, int slot) {
			lastSlot = slot; lastStack = menu.getSlot(slot).getItem().copy();
			Game.quickMove(mc.gameMode, menu.containerId, slot, p);
			clicks++; wait = R.nextInt(2, 5);
		}
		private boolean stayedPut(int slot) {
			if (slot != lastSlot) return false;
			ItemStack now = menu.getSlot(slot).getItem();
			boolean same = !now.isEmpty() && now.getItem() == lastStack.getItem() && now.getCount() == lastStack.getCount();
			if (same) stuck.add(slot);
			return same;
		}
		int tick(LocalPlayer p, Minecraft mc, AfkConfig c) {
			if (wait > 0) { wait--; return BUSY; }
			if (phase == 0) {   // open
				if (!withinReach(p, pos)) return FAILED;
				Vec3 target = new Vec3(pos.getX() + 0.5, pos.getY() + 0.9, pos.getZ() + 0.5);
				if (!looked) { looked = true; look.startByAngle(p, target, 0.2f); }
				boolean turning = look.busy(); if (turning) look.tick(p);
				if (pos.equals(crosshairBlock(mc))) {
					chatWasOpen = Game.openScreen(mc) instanceof net.minecraft.client.gui.screens.ChatScreen;
					int slot = p.getInventory().getSelectedSlot();
					for (int i = 0; i < 9; i++) { ItemStack s = p.getInventory().getItem(i); if (s.isEmpty() || s.is(ItemTags.AXES)) { slot = i; break; } }   // nothing in hand that you place by accident
					if (p.getInventory().getSelectedSlot() != slot) { p.getInventory().setSelectedSlot(slot); wait = 3; return BUSY; }
					clickOn(pos);
					phase = 1; wait = 3; return BUSY;
				}
				if (!turning) aim(p, target);
				if (elapsed() > 8000) return FAILED;
				return BUSY;
			}
			if (phase == 1) {   // wait until the menu is there, then read off the role of the chest
				if (p.containerMenu instanceof ChestMenu cm) {
					menu = cm; phase = 2; wait = 4;
					int n = cm.getRowCount() * 9; boolean saplings = false;
					for (int i = 0; i < n; i++) { ItemStack st = cm.getSlot(i).getItem(); if (st.is(ItemTags.SAPLINGS) || st.is(Items.MANGROVE_PROPAGULE)) saplings = true; }
					role = saplings ? ChestRole.SAPLINGS : ChestRole.DROPOFF;
					chestRole.put(pos, role);
					Exchange.emit("wood", "action", "chest role", "pos", pos.toShortString(), "role", role.code, "goal", purpose.code);
					return BUSY;
				}
				if (elapsed() > 6000) return FAILED;
				return BUSY;
			}
			if (p.containerMenu != menu) { Exchange.emit("wood", "action", "chest closed"); return FAILED; }
			int chestSlots = menu.getRowCount() * 9;
			if (phase == 2) {   // in: into the sapling chest only saplings (above the reserve), into a drop-off chest everything except saplings
				Map<Item, Integer> keep = new HashMap<>();
				for (int i = chestSlots; i < chestSlots + 36; i++) {
					ItemStack s = menu.getSlot(i).getItem();
					if (s.isEmpty() || !treeStuff(s)) continue;
					boolean sapling = s.is(ItemTags.SAPLINGS) || s.is(Items.MANGROVE_PROPAGULE);
					if (role == ChestRole.SAPLINGS && !sapling) continue;
					if (role == ChestRole.DROPOFF && sapling) continue;
					int reserve = sapling ? c.woodKeepSaplings : 0;
					int already = keep.getOrDefault(s.getItem(), 0);
					if (already < reserve) { keep.put(s.getItem(), already + s.getCount()); continue; }
					if (stuck.contains(i) || stayedPut(i)) continue;
					click(mc, p, i); return BUSY;
				}
				// after emptying, is there still stuff left that belonged in this chest? then the chest is full
				int remaining = 0; Map<Item, Integer> keep2 = new HashMap<>();
				for (int i = chestSlots; i < chestSlots + 36; i++) { ItemStack s = menu.getSlot(i).getItem(); if (s.isEmpty() || !treeStuff(s)) continue; boolean sapling = s.is(ItemTags.SAPLINGS) || s.is(Items.MANGROVE_PROPAGULE); if ((role == ChestRole.SAPLINGS) != sapling) continue; int reserve = sapling ? c.woodKeepSaplings : 0; int already = keep2.getOrDefault(s.getItem(), 0); if (already < reserve) { keep2.put(s.getItem(), already + s.getCount()); continue; } remaining += s.getCount(); }
				if (remaining > 0 && !stuck.isEmpty()) { fullChests.add(pos);  Exchange.emit("wood", "action", "chest full", "pos", pos.toShortString()); }
				phase = 3; wait = 3; return BUSY;
			}
			if (phase == 3) {   // out: bonemeal and saplings up to the reserve
				int bonemeal = countInInventory(p, Items.BONE_MEAL);
				boolean seen = false;
				for (int i = 0; i < chestSlots; i++) {
					ItemStack s = menu.getSlot(i).getItem();
					if (s.isEmpty()) continue;
					boolean want = false;
					if (s.is(Items.BONE_MEAL)) { seen = true; want = bonemeal < c.woodKeepBonemeal; }
					else if (s.is(ItemTags.SAPLINGS) || s.is(Items.MANGROVE_PROPAGULE)) want = countInInventory(p, s.getItem()) < Math.max(c.woodKeepSaplings, plotsOfKind(c, itemId(s.getItem()))) && kindNeeded(c, itemId(s.getItem()));
					if (!want || freeSlots(p) == 0 || stuck.contains(i) || stayedPut(i)) continue;
					click(mc, p, i); return BUSY;
				}
				chestWithoutBonemeal = !seen && countInInventory(p, Items.BONE_MEAL) == 0; chestWithoutBonemealSince = System.currentTimeMillis();
				// remember which saplings were in here: otherwise the walk to an empty chest comes anew every round
				Set<String> seenSaplings = new HashSet<>();
				for (int i = 0; i < chestSlots; i++) {
					ItemStack s2 = menu.getSlot(i).getItem();
					if (!s2.isEmpty() && (s2.is(ItemTags.SAPLINGS) || s2.is(Items.MANGROVE_PROPAGULE))) seenSaplings.add("minecraft:" + itemId(s2.getItem()));
				}
				long now2 = System.currentTimeMillis();
				for (var q : c.woodPlots) if (!seenSaplings.contains(q.kind)) noSaplingsInChest.put(q.kind, now2);
				for (String kind : seenSaplings) noSaplingsInChest.remove(kind);
				Exchange.emit("wood", "action", "chest saplings", "seen", seenSaplings, "empty_for", noSaplingsInChest.keySet());
				phase = 4; wait = 3; return BUSY;
			}
			close(p, mc);
			Exchange.emit("wood", "action", "chest", "pos", pos.toShortString(), "clicks", clicks, "bonemeal", countInInventory(p, Items.BONE_MEAL), "free", freeSlots(p));
			return DONE;
		}
		void failed(LocalPlayer p, AfkConfig c) { if (p.containerMenu != p.inventoryMenu) close(p, Minecraft.getInstance()); }
		String name() { return "chest " + pos.toShortString(); }
		String label() { return String.format(java.util.Locale.ROOT, "chest %s", pos.toShortString()); }
	}

	private static boolean kindNeeded(AfkConfig c, String saplingId) { for (var pl : c.woodPlots) if (pl.kind.equals(saplingId)) return true; return false; }
	private static int plotsOfKind(AfkConfig c, String saplingId) { int n = 0; for (var pl : c.woodPlots) if (pl.kind.equals(saplingId)) n++; return n; }
}
