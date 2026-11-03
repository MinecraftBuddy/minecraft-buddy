package net.milenco.buddy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ConcretePowderBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.milenco.buddy.mixin.client.BlockItemAccessor;
import net.milenco.buddy.version.Game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Builds the Litematica placement in the real world, the way a player does it: walk to a spot, take the block in
 * hand, look at the face to click on and right-click. Before every click the mod asks vanilla's own placement logic
 * what would come of it (a torch against a wall, the top half of a slab, stairs facing away from you) and only clicks
 * when that is exactly the block of the schematic. So there is no Easy Place and no placement packet the server would
 * not also get from a hand: Grim sees the crosshair on the clicked face, within reach, at a human pace.
 *
 * What is in the way goes first: wrong blocks, and whatever stands where the schematic wants air, broken top down with
 * the fastest tool that still has durability to spare. Where the ground does not reach, it builds scaffold of cheap
 * blocks like a player: a bridge (crouched, backwards to the edge, a block against the side) or a pillar (jump, block
 * under you). That also gives the spots from where stairs and furnaces face the right way. The scaffold is written
 * down and broken again at the end.
 *
 * Material comes from the chests, barrels and shulker boxes within 3 blocks and from those you mark further away; the
 * builder remembers them in build.json and fetches what it lacks. What is built is simply what stands in the world, so after a stop it carries on from there.
 */
public class Builder {
	// ---------- state ----------
	private static boolean isOn = false, paused = false, cleanupAnnounced = false;
	private static long pausedUntil = 0, lastPlayer = 0, startedAt = 0, nextBreak = 0, lastSave = 0, nextStepAfter = 0, lastClickAt = 0, lastPickup = 0;
	private static String stopReason = null, dim = "";
	private static final ArrayDeque<Step> steps = new ArrayDeque<>();
	private static Step current = null;
	private static Schematic.Placement placement = null;
	/** Where you stood when building began: the way in, which the build works back toward (BuildOrder rule 3). */
	private static BlockPos entry = null;
	/** The order rules for the current plan. */
	private static BuildOrder.Ctx order = null;
	/** The scaffolding tower you stand on, if any: placing goes on from up there until nothing is left in reach. */
	private static Tower tower = null;
	private static Scan scan = null;
	private static Scanner scanner = null;
	private static int placed = 0, broken = 0, lastPlacedSaved = 0;
	/** Blocks that went wrong, with until when we leave them alone; after that they simply join in again. */
	private static final Map<BlockPos, Long> skipped = new HashMap<>();
	private static final Map<BlockPos, Integer> failures = new HashMap<>();
	/** Items no chest had, with the moment: we look again after a few minutes (or when you add a chest). */
	private static final Map<Item, Long> noMaterial = new HashMap<>();
	/**
	 * Per chest the items already looked for in this restock round, so the next trip for them goes to another one.
	 * Per item: a chest opened for scaffold dirt was otherwise skipped when it was the one with the wool.
	 */
	private static final Map<BlockPos, Set<Item>> visitedThisRound = new HashMap<>();
	/** Scaffold blocks under a block that would otherwise float, or beside one that needs another face to click on. */
	private static final Map<BlockPos, BlockState> supports = new HashMap<>();
	private static final Map<BlockPos, Integer> supportsFor = new HashMap<>();
	/** Which block each support was for; once that block stands, its supports have done their job. */
	private static final Map<BlockPos, BlockPos> supportOf = new HashMap<>();
	/** Supports whose block now stands: broken right away instead of at the end, where they stood in the way. */
	private static final Set<BlockPos> retire = new HashSet<>();
	/** Finishing touches still to do (live), and entities of the schematic not right yet. */
	private static final Map<BlockPos, Finisher.Fix> finishing = new HashMap<>();
	private static List<Finisher.EntityJob> entityJobs = new ArrayList<>();
	/** Things logged once per position (a floating sand block, TNT that could be powered). */
	private static final Set<String> noted = new HashSet<>();
	/** An entity to click at the start of the next tick. */
	private static Entity pendingEntity = null;
	/** A water source to scoop with the empty bucket at the start of the next tick. */
	private static BlockPos pendingScoop = null;
	/** Refilling buckets: off for this run once a source did not come back, and a pause after finding none in reach. */
	private static boolean refillOff = false, toldNoSource = false;
	private static long noRefillUntil = 0, sourcesAt = 0;
	private static List<BlockPos> waterSources = new ArrayList<>();
	private static final Map<BlockPos, Long> badSources = new HashMap<>();
	/** The source the last bucket came from and that came back: always among the ones tried next time. */
	private static BlockPos goodSource = null;

	/** Water (or waterlogged blocks) that would run out of the build now, so it waits; for the HUD and the events. */
	private static final Set<BlockPos> waterWaits = new HashSet<>(), waterLogged = new HashSet<>();
	/** Our scaffold in this world, as in build.json; refreshed every plan. */
	private static Set<BlockPos> scaffold = new HashSet<>();
	/** Every block the builder placed, with when: for two minutes the watchdog knows it was us. */
	private static final Map<BlockPos, Long> placedByUs = new HashMap<>();
	private static long lastUnreachableTell = 0, lastFillerTell = 0, lastSignAt = 0, signCloseAt = 0, lastWaterAt = 0, lastBreakAt = 0;
	private static final AfkHelperClient.Look look = new AfkHelperClient.Look();
	private static final ThreadLocalRandom R = ThreadLocalRandom.current();
	private static final int BUSY = 0, DONE = 1, FAILED = 2;

	// ---------- clicking at the start of the tick ----------
	/** A right-click for the start of the next tick; checked again there with the crosshair of that moment. */
	private record Pending(BlockPos target, BlockState want, BlockPos open, boolean crouch, boolean scaffold) {}
	private enum ClickResult { NONE, PLACED, REFUSED, MISSED, UNSTEADY, OPENED }
	private static Pending pending = null;
	private static ClickResult lastClick = ClickResult.NONE;
	private static float startYaw, startPitch;
	private static double startX, startY, startZ;
	/** What the last bucket click was aimed at, for the event when the water does not arrive. */
	private static String lastPour = "";
	private static int jumpTicks = 0;
	private static boolean crouching = false;
	/** The block being broken: Dig sets it every tick the crosshair is on it, like holding the attack button. */
	private static BlockPos digTarget = null;
	private static boolean wasDigging = false;

	/**
	 * Like vanilla, clicking and breaking happen at the start of the tick (Grim reports "post" actions otherwise).
	 * The view direction we set at the end of a tick only goes to the server after the next click, so we only
	 * right-click when it has not changed since the previous start: then the server judges the click with the
	 * direction we see now.
	 */
	public static void startTick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.gameMode == null) return;
		boolean steady = p.getYRot() == startYaw && p.getXRot() == startPitch;
		startYaw = p.getYRot(); startPitch = p.getXRot();
		// a bucket is aimed by the server from where it has you: only while standing still is that where you are
		boolean stood = p.getX() == startX && p.getY() == startY && p.getZ() == startZ;
		startX = p.getX(); startY = p.getY(); startZ = p.getZ();
		boolean blocked = !isOn || AfkHelperClient.isEating() || Game.openScreen(mc) instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>;
		BlockHitResult hit = mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK ? b : null;

		if (!blocked && digTarget != null && hit != null && hit.getBlockPos().equals(digTarget)) {
			if (mc.gameMode.continueDestroyBlock(digTarget, hit.getDirection())) Game.swingAttack(p);
			wasDigging = true;
		} else if (wasDigging) { mc.gameMode.stopDestroyBlock(); wasDigging = false; }
		digTarget = null;

		Entity ent = pendingEntity;
		pendingEntity = null;
		if (ent != null && isOn) {
			if (blocked) { lastClick = ClickResult.MISSED; return; }
			if (!steady) { lastClick = ClickResult.UNSTEADY; return; }
			if (mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult eh && eh.getEntity() == ent) {
				var r = Game.interactEntity(mc.gameMode, p, ent, eh, InteractionHand.MAIN_HAND);
				if (r.consumesAction()) Game.swingUse(p, InteractionHand.MAIN_HAND);
				lastClick = ClickResult.OPENED;
			} else lastClick = ClickResult.MISSED;
			return;
		}
		BlockPos scoop = pendingScoop;
		pendingScoop = null;
		if (scoop != null && isOn) {
			if (blocked) { lastClick = ClickResult.MISSED; return; }
			if (!steady) { lastClick = ClickResult.UNSTEADY; return; }
			// the same check the bucket itself makes: the first water source along the view is the one we chose
			if (!stood) { lastClick = ClickResult.UNSTEADY; return; }
			BlockHitResult water = fluidAhead(p, p.getEyePosition(), p.getViewVector(1f));
			if (water != null && !bucketAimHolds(p, p.getEyePosition(), p.getEyePosition().add(p.getViewVector(1f)), scoop, null, ClipContext.Fluid.SOURCE_ONLY)) water = null;
			if (!p.getMainHandItem().is(Items.BUCKET) || water == null || !water.getBlockPos().equals(scoop)
				|| (hit != null && SafeLook.interactable(p.level().getBlockState(hit.getBlockPos())))) { lastClick = ClickResult.MISSED; return; }
			// as vanilla clicks: on the block under the crosshair first (which does nothing with a bucket), then the item
			var r = hit != null ? mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit) : null;
			if (r == null || !r.consumesAction()) r = mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
			if (r.consumesAction()) { Game.swingUse(p, InteractionHand.MAIN_HAND); lastClick = ClickResult.OPENED; lastClickAt = System.currentTimeMillis(); lastWaterAt = lastClickAt; }
			else lastClick = ClickResult.REFUSED;
			return;
		}
		Pending want = pending;
		pending = null;
		if (want == null || !isOn) return;
		if (blocked) { lastClick = ClickResult.MISSED; return; }
		if (!steady) { lastClick = ClickResult.UNSTEADY; return; }
		if (hit == null) { lastClick = ClickResult.MISSED; return; }
		if (want.open() != null) {
			// crouched, a right-click with a block in hand puts that block against the chest instead of opening it
			if (!hit.getBlockPos().equals(want.open()) || p.isShiftKeyDown()) { lastClick = ClickResult.MISSED; return; }
			var r = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
			if (r.consumesAction()) Game.swingUse(p, InteractionHand.MAIN_HAND);
			lastClick = ClickResult.OPENED;
			return;
		}
		// against a chest or a door you only place with shift held; otherwise it opens
		if (want.crouch() && !p.isShiftKeyDown()) { lastClick = ClickResult.MISSED; return; }
		Placing sim = simulate(p, p.getMainHandItem(), hit, p.getYRot(), p.getXRot());
		if (sim == null || !sim.pos().equals(want.target()) || !acceptable(want.want(), sim.state())) { lastClick = ClickResult.MISSED; return; }
		if (p.getMainHandItem().is(Items.WATER_BUCKET)) {
			if (!stood) { lastClick = ClickResult.UNSTEADY; return; }
			Vec3 eye = p.getEyePosition();
			if (!bucketAimHolds(p, eye, eye.add(p.getViewVector(1f)), hit.getBlockPos(), hit.getDirection(), ClipContext.Fluid.NONE)) { lastClick = ClickResult.MISSED; return; }
			lastPour = hit.getBlockPos().toShortString() + " " + hit.getDirection() + ", eye " + String.format(java.util.Locale.ROOT, "%.2f %.2f %.2f", eye.x, eye.y, eye.z)
				+ ", look " + Math.round(p.getYRot()) + "/" + Math.round(p.getXRot()) + (p.isShiftKeyDown() ? ", crouched" : "");
		}
		var r = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
		// a bucket does nothing on the block itself: like vanilla, the click then goes on as using the item
		if (!r.consumesAction() && p.getMainHandItem().is(Items.WATER_BUCKET)) r = mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
		if (r.consumesAction()) {
			Game.swingUse(p, InteractionHand.MAIN_HAND);
			lastClick = ClickResult.PLACED; lastClickAt = System.currentTimeMillis();
			placedByUs.values().removeIf(t -> lastClickAt - t > 120_000);
			placedByUs.put(want.target(), lastClickAt);
			if (want.want().getBlock() instanceof SignBlock) lastSignAt = lastClickAt;
			if (want.want().hasProperty(BlockStateProperties.ROTATION_16))
				Exchange.emit("build", "action", "placed rotated", "block", want.target().toShortString(), "what", Lumberjack.blockId(want.want().getBlock()).replace("minecraft:", ""),
					"wants", orientation(want.want()), "got", orientation(sim.state()), "from", String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", p.getX(), p.getY(), p.getZ()), "yaw", Math.round(p.getYRot()));
			if (p.getMainHandItem().is(Items.BUCKET) || p.getMainHandItem().is(Items.WATER_BUCKET)) lastWaterAt = lastClickAt;
			if (want.scaffold()) { BuildStore.addScaffold(want.target(), dim); scaffold.add(want.target()); }
		} else lastClick = ClickResult.REFUSED;
	}

	// ---------- on/off ----------
	public static boolean isOn() { return isOn; }

	/** Water around you that the builder poured or set running itself in the last two minutes, near the build: not a test. */
	public static boolean ownWater(LocalPlayer p) {
		// poured by us, or set running by a block we broke: in or near the build, within two minutes of that
		long last = Math.max(lastWaterAt, lastBreakAt);
		return isOn && placement != null && System.currentTimeMillis() - last < 120_000 && placement.contains(p.blockPosition(), 8);
	}
	/** Busy with something during which the rest of the mod (wandering, glances, crouching) must keep off the keys. */
	public static boolean isBusy() { return isOn && (paused || !(current instanceof Wait)); }

	public static void start() {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null || isOn) return;
		if (!Schematic.available()) {  return; }
		Schematic.Placement pl = pickPlacement(p);
		if (pl == null) {
			Exchange.emit("build", "action", "no placement", "litematica", Schematic.describe());
			return;
		}
		int far = (int) pl.distance(p.blockPosition());
		if (far > NAV_RANGE) {  return; }
		Jobs.switchedOn(Jobs.Job.BUILD, "build on");
		Lumberjack.stop("build on");
		Wander.abort("build on");
		BuildStore.Data d = BuildStore.get();
		// The same file put down somewhere else is another build. The third wheat floor carried the name of the
		// second, so it kept that floor's count and its shulker boxes, and one of those (known to hold leaves) won
		// from the new ones he stood beside.
		String corner = pl.min().toShortString();
		// no corner yet: a file from before it was kept, so a build that is under way goes on with its chests
		boolean samePlacement = pl.name().equals(d.placement) && (d.corner.isEmpty() || corner.equals(d.corner));
		boolean resumed = samePlacement && (d.placed > 0 || d.done > 0);
		if (!samePlacement) { d.placement = pl.name(); d.placed = 0; d.done = 0; d.total = 0; }
		d.corner = corner;
		placement = pl; dim = dimension(p); entry = p.blockPosition(); order = null; tower = null;
		isOn = true; paused = false; stopReason = null; cleanupAnnounced = false;
		steps.clear(); current = null; pending = null; scan = null; scanner = null; digTarget = null;
		skipped.clear(); failures.clear(); noMaterial.clear(); visitedThisRound.clear(); supports.clear(); supportsFor.clear(); supportOf.clear(); retire.clear(); clearedFor.clear(); helpedFor.clear(); waterWaits.clear(); waterLogged.clear();
		finishing.clear(); entityJobs = new ArrayList<>(); noted.clear(); pendingEntity = null;
		rewet.clear(); pourFails = 0; neededNow.clear();
		pendingScoop = null; refillOff = false; toldNoSource = false; noRefillUntil = 0; sourcesAt = 0; waterSources = new ArrayList<>(); badSources.clear();
		scaffold = BuildStore.scaffold(dim);
		// Found chests start afresh from where you stand now (the ones you marked stay), but not when the same
		// placement goes on: after a restart in the middle of a field the shulker boxes were out of sight of the scan
		// and the builder had no material.
		if (!samePlacement && BuildStore.dropFound(dim, ch -> true) > 0) BuildStore.save();
		scanChests(p);
		placed = 0; broken = 0; lastPlacedSaved = 0;
		startedAt = System.currentTimeMillis(); lastSave = startedAt; nextStepAfter = 0; lastPickup = startedAt;
		AfkConfig c = AfkConfig.get();
		nextBreak = startedAt + (long) (R.nextDouble(0.6, 1.4) * Math.max(1, c.buildPauseEveryMin) * 60_000);
		d.running = true; d.wanted = true; d.stopReason = "";
		BuildStore.save();
		// no "carrying on where it stopped": the world is the truth and what stands right simply stays; the count
		// from last time said 68 of 69 while the build had been torn down in between
		Exchange.emit("build", "action", "start", "placement", pl.name(), "from", pl.min().toShortString(), "until", pl.max().toShortString(),
			"chests", chestsHere(p).size(), "scaffold", scaffold.size(), "resumed", resumed);
	}

	/** Stops that are not the player's choice: the build is still wanted, and the alt takes it up again when it is back. */
	private static final Set<String> COMES_BACK = Set.of("teleport", "new connection", "another world");
	private static boolean resumePlanned = false;

	/** Switched on and not switched off since, also while a teleport or a new connection has it stopped. */

	/**
	 * Back in the world after a restart or a kick: the alt carries on the build it was on, like the wheat
	 * field does. It stood still until someone turned building on again, and the alt's own /home pulled it away.
	 */
	public static void resumeLater() {
		if (resumePlanned || isOn || !BuildStore.get().wanted) return;
		resumePlanned = true;
		AfkHelperClient.later(R.nextDouble(25, 45), () -> {
			resumePlanned = false;
			BuildStore.Data d = BuildStore.get();
			if (isOn || !d.wanted || Minecraft.getInstance().player == null) return;
			// Only the build he was on. After a restart his placement was gone and another one was nearest: he
			// "resumed" on that, a floor that was finished long ago.
			if (!d.corner.isEmpty() && !placementThere(d.placement, d.corner)) {
				Exchange.emit("build", "action", "not resumed", "reason", "placement gone", "placement", d.placement, "corner", d.corner, "litematica", Schematic.describe());
				return;
			}
			Exchange.emit("build", "action", "resume");
			start();
		});
	}

	public static void stop(String why) {
		BuildStore.Data kept = BuildStore.get();
		if (!COMES_BACK.contains(why) && kept.wanted) { kept.wanted = false; BuildStore.save(); }
		if (!isOn) return;
		isOn = false; paused = false; stopReason = why;
		releaseKeys(); steps.clear(); current = null; pending = null; scanner = null;
		LocalPlayer p = Minecraft.getInstance().player;
		if (p != null && p.containerMenu != p.inventoryMenu) p.closeContainer();
		BuildStore.Data d = BuildStore.get();
		d.running = false; d.stopReason = why;
		remember(p, d);
		BuildStore.save();
		long duration = startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt;
		Exchange.emit("build", "action", "stop", "why", why, "minutes", duration / 60_000, "placed", placed, "broken", broken,
			"done", d.done, "total", d.total, "wrong", scan == null ? 0 : scan.wrong, "scaffold", d.scaffold.size());
	}

	/** Hold still for a moment, for example because someone is typing. */
	public static void pause(long ms, String why) {
		if (!isOn) return;
		pausedUntil = Math.max(pausedUntil, System.currentTimeMillis() + ms);
		if (!paused) {
			paused = true; releaseKeys();
			if (current != null) { steps.addFirst(current); current = null; }
			Exchange.emit("build", "action", "pause", "why", why);
		}
	}

	private static void releaseKeys() {
		releaseMovement();
		crouch(false);
	}

	/** Everything but shift: between placements at an edge you stay crouched. */
	private static void releaseMovement() {
		pending = null; digTarget = null;
		var o = Minecraft.getInstance().options;
		HeldKeys.set(o.keyUp, false); HeldKeys.set(o.keyDown, false); HeldKeys.set(o.keyLeft, false); HeldKeys.set(o.keyRight, false);
		HeldKeys.set(o.keySprint, false); HeldKeys.set(o.keyJump, false);
		jumpTicks = 0;
	}

	/**
	 * Only lets go of shift if we pressed it: the AFK crouch and eating have their own. Pressing is repeated while
	 * the key is up: the alt's own crouch stops in the tick the builder starts, and let go of the key the builder had
	 * just pressed. The builder still thought it held the key and waited three seconds for a crouch that never came,
	 * twenty blocks in a row.
	 */
	private static void crouch(boolean on) {
		var shift = Minecraft.getInstance().options.keyShift;
		if (on == crouching && (!on || shift.isDown())) return;
		crouching = on;
		HeldKeys.set(shift, on);
	}
	public static boolean crouching() { return isOn && crouching; }

	private static void jump(Minecraft mc) { HeldKeys.set(mc.options.keyJump, true); jumpTicks = 3; }

	/**
	 * Which placement: the one with a block nearest to you. Its box says little, since a large box may be mostly air
	 * around the spot you stand at, so the schematic's own blocks decide.
	 */
	private static Schematic.Placement pickPlacement(LocalPlayer p) {
		BlockPos me = p.blockPosition();
		List<Schematic.Placement> all = new ArrayList<>(Schematic.placements());
		all.sort(Comparator.comparingDouble(pl -> pl.distance(me)));
		Schematic.Placement best = null; double bestDist = Double.MAX_VALUE;
		for (var pl : all) {
			if (pl.distance(me) >= bestDist) break;   // its box is already farther than the best block
			double d = Schematic.blockDistance(pl, me, 0);
			if (d < bestDist) { best = pl; bestDist = d; }
		}
		return best;
	}

	/**
	 * The same placement again, with its current box (you may have moved it in Litematica). Two placements of one
	 * file carry the same name: then the one at our corner, not the first on the list, or the builder went on with
	 * the other one after its first comparison.
	 */
	private static Schematic.Placement refreshPlacement() {
		Schematic.Placement byName = null;
		for (var pl : Schematic.placements()) {
			if (!pl.name().equals(placement.name())) continue;
			if (pl.min().equals(placement.min())) return pl;
			if (byName == null) byName = pl;
		}
		return byName;
	}

	private static boolean placementThere(String name, String corner) {
		for (var pl : Schematic.placements()) if (pl.name().equals(name) && pl.min().toShortString().equals(corner)) return true;
		return false;
	}

	private static String dimension(LocalPlayer p) { return p.level().dimension().identifier().toString(); }

	private static void remember(LocalPlayer p, BuildStore.Data d) {
		if (scan != null) { d.done = scan.done; d.total = scan.total; if (scan.lowestY != Integer.MAX_VALUE) d.layer = scan.lowestY; }
		d.placed += placed - lastPlacedSaved; lastPlacedSaved = placed;
		if (p != null) { d.spotX = p.getBlockX(); d.spotY = p.getBlockY(); d.spotZ = p.getBlockZ(); }
	}

	// ---------- material chests ----------

	/** Is this block here ours: the one the schematic wants, or our scaffold? Then the builder put it there, not a visitor. */
	static boolean fromSchematic(BlockPos pos, BlockState have) {
		if (!isOn) return false;
		if (scaffold.contains(pos)) return true;
		// what we put there ourselves lately, also outside the build: restoring the dirt we broke set off the watchdog
		Long at = placedByUs.get(pos);
		if (at != null && System.currentTimeMillis() - at < 120_000) return true;
		Level schem = Schematic.world();
		return schem != null && placement != null && placement.contains(pos, 0) && matches(schem.getBlockState(pos), have);
	}

	static boolean isContainer(BlockState st) {
		Block b = st.getBlock();
		return b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof ShulkerBoxBlock;
	}

	/** Around you, the builder finds chests, barrels and shulker boxes by itself. */
	private static final int CHEST_SCAN_RADIUS = 3;

	/**
	 * Adds every container within {@link #CHEST_SCAN_RADIUS} to the chest list, so you need not mark each one.
	 * Skipped: what the schematic places itself (that is the build, not a store). A found chest that has gone again
	 * is dropped; one you marked yourself stays until you unmark it.
	 */
	private static void scanChests(LocalPlayer p) {
		Level level = p.level();
		String d = dimension(p);
		BlockPos me = p.blockPosition();
		Level schem = Schematic.world();
		int r = CHEST_SCAN_RADIUS;
		List<BlockPos> found = new ArrayList<>();
		for (BlockPos q : BlockPos.betweenClosed(me.offset(-r, -r, -r), me.offset(r, r, r))) {   // three blocks in every direction: eight took in half the base
			BlockPos c = materialContainer(level, schem, q);
			if (c != null && !found.contains(c)) found.add(c);
		}
		// Nothing that close and none known: the alt stood a few blocks from the shulker boxes laid out for it, found
		// no chest at all and walked off to hoe a far corner. Then the shulker boxes nearby, else the nearest chests.
		if (found.isEmpty() && chestsHere(p).isEmpty()) {
			found = nearbyContainers(level, schem, me);
			if (!found.isEmpty()) {
				BlockPos first = found.get(0);
				Exchange.emit("build", "action", "chests further off", "count", found.size(), "nearest", first.toShortString());
			}
		}
		int added = 0;
		for (BlockPos c : found) if (BuildStore.chest(c, d) == null) { BuildStore.addFound(c, d); added++; }
		int gone = BuildStore.dropFound(d, c -> c.pos().distSqr(me) <= r * r && !isContainer(level.getBlockState(c.pos())));
		if (added == 0 && gone == 0) return;
		BuildStore.save();
		Exchange.emit("build", "action", "chests found", "added", added, "gone", gone, "chests", chestsHere(p).size());
		// a new chest may hold what was missing: look again
		if (added > 0) noMaterial.clear();
	}

	/** This cell as a container to take material from, or null: not one the schematic itself places. */
	private static BlockPos materialContainer(Level level, Level schem, BlockPos q) {
		if (!isContainer(level.getBlockState(q))) return null;
		BlockPos c = Chests.canonical(q.immutable());
		if (schem != null && placement != null && placement.contains(c, 0) && isContainer(schem.getBlockState(c))) return null;
		return c;
	}

	/** How far to look when nothing stands within {@link #CHEST_SCAN_RADIUS}. */
	private static final int CHEST_WIDE_RADIUS = 16;

	/**
	 * The containers to use when none is right beside you, nearest first: every shulker box within sixteen blocks
	 * (a shulker box put down in the world is material laid out on purpose), or without any the nearest chest or
	 * barrel and the ones within three blocks of it. Not everything in that range: that would be half a base.
	 */
	private static List<BlockPos> nearbyContainers(Level level, Level schem, BlockPos me) {
		int r = CHEST_WIDE_RADIUS;
		List<BlockPos> all = new ArrayList<>();
		for (BlockPos q : BlockPos.betweenClosed(me.offset(-r, -r, -r), me.offset(r, r, r))) {
			BlockPos c = materialContainer(level, schem, q);
			if (c != null && !all.contains(c)) all.add(c);
		}
		all.sort(Comparator.comparingDouble(c -> c.distSqr(me)));
		List<BlockPos> boxes = new ArrayList<>();
		for (BlockPos c : all) if (level.getBlockState(c).getBlock() instanceof ShulkerBoxBlock) boxes.add(c);
		if (!boxes.isEmpty() || all.isEmpty()) return boxes;
		BlockPos nearest = all.get(0);
		List<BlockPos> group = new ArrayList<>();
		for (BlockPos c : all)
			if (Math.abs(c.getX() - nearest.getX()) <= 3 && Math.abs(c.getY() - nearest.getY()) <= 3 && Math.abs(c.getZ() - nearest.getZ()) <= 3) group.add(c);
		return group;
	}

	/**
	 * Can this container open at all? A shulker box whose lid side is covered, or a chest under a solid block,
	 * does not: he kept walking to one and clicking. Unloaded counts as openable, it is checked again there.
	 */
	static boolean openable(Level level, BlockPos pos) {
		if (!level.isLoaded(pos)) return true;
		BlockState st = level.getBlockState(pos);
		if (st.getBlock() instanceof ShulkerBoxBlock) {
			BlockPos lid = pos.relative(st.getValue(ShulkerBoxBlock.FACING));
			return level.getBlockState(lid).getCollisionShape(level, lid).isEmpty();
		}
		if (st.getBlock() instanceof ChestBlock) return !level.getBlockState(pos.above()).isRedstoneConductor(level, pos.above());
		return true;
	}

	/** The chests in this world within 64 blocks: marked or found. */
	private static List<BuildStore.Chest> chestsHere(LocalPlayer p) {
		String d = dimension(p);
		List<BuildStore.Chest> out = new ArrayList<>();
		for (var c : BuildStore.get().chests)
			if (c.dim.equals(d) && c.pos().closerThan(p.blockPosition(), NAV_RANGE) && openable(p.level(), c.pos())) out.add(c);
		return out;
	}

	// ---------- scaffold material ----------
	/** Scaffold blocks from the list that you carry and the build itself does not need. */
	private static int fillerCount(LocalPlayer p, AfkConfig c) {
		int n = 0;
		for (Item it : fillerItems(c)) n += Math.max(0, Lumberjack.countInInventory(p, it) - (scan == null ? 0 : scan.need.getOrDefault(it, 0)));
		return n;
	}

	/** The scaffold block to use now, or null. */
	private static Item filler(LocalPlayer p, AfkConfig c) {
		for (Item it : fillerItems(c))
			if (Lumberjack.countInInventory(p, it) - (scan == null ? 0 : scan.need.getOrDefault(it, 0)) > 0) return it;
		return null;
	}

	private static List<Item> fillerItems(AfkConfig c) {
		List<Item> out = new ArrayList<>();
		for (String id : c.buildScaffold) {
			try {
				Item it = Lumberjack.itemOf(id.trim().toLowerCase(java.util.Locale.ROOT));
				if (it instanceof BlockItem bi && bi.getBlock().defaultBlockState().isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) out.add(it);
			} catch (RuntimeException ignored) { }
		}
		return out;
	}

	/** Put the scaffold block in hand. False if there is none; the plan then fetches some. */
	private static boolean holdFiller(LocalPlayer p, Minecraft mc, AfkConfig c) {
		Item f = filler(p, c);
		if (f == null) return false;
		int slot = Lumberjack.hotbarSlot(p, f);
		if (slot < 0) { toHotbar(p, mc, sourceSlot(p, f)); return true; }
		if (p.getInventory().getSelectedSlot() != slot) p.getInventory().setSelectedSlot(slot);
		return true;
	}

	/** May a scaffold block go here: empty or a plant, not a block the schematic wants, not in water or lava. */
	private static boolean canScaffold(Level level, BlockPos pos) {
		BlockState st = level.getBlockState(pos);
		if (!(st.isAir() || st.canBeReplaced()) || !st.getFluidState().isEmpty()) return false;
		Level schem = Schematic.world();
		return schem == null || !placement.contains(pos, 0) || schem.getBlockState(pos).isAir();
	}

	static boolean solid(Level level, BlockPos pos) { return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty(); }

	// ---------- tick ----------
	/** Every tick. True if the mod itself is moving or turning. */
	public static boolean tick(LocalPlayer p, AfkConfig c) {
		if (!isOn) return false;
		Minecraft mc = Minecraft.getInstance();
		long now = System.currentTimeMillis();
		if (jumpTicks > 0 && --jumpTicks == 0) HeldKeys.set(mc.options.keyJump, false);
		if (p.isDeadOrDying()) { stop("dead"); return false; }
		if (!dimension(p).equals(dim)) { stop("another world"); return false; }
		if (placement.distance(p.blockPosition()) > NAV_RANGE + 32) { stop("too far from the build"); return false; }
		if (!advanceScan(p, c, now)) return false;
		if (now - lastSave > 30_000) { lastSave = now; remember(p, BuildStore.get()); BuildStore.save(); }

		// eating: stand still, swap nothing; the eating logic needs the slot and the button
		if (AfkHelperClient.isEating()) {
			var o = mc.options; HeldKeys.set(o.keyUp, false); HeldKeys.set(o.keyDown, false); HeldKeys.set(o.keySprint, false);
			pending = null; digTarget = null;
			return false;
		}
		// Freecam: the crosshair would be the camera's, and placing what the camera sees is exactly reach to an anti-cheat
		if (mc.getCameraEntity() != null && mc.getCameraEntity() != p) { pause(500, "freecam"); return false; }
		var screen = Game.openScreen(mc);
		// the editor vanilla opens after we placed a sign: close it after a moment, as Esc would; the sign stays blank
		if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen sign && now - lastSignAt < 5000) {
			if (signCloseAt == 0) signCloseAt = now + R.nextLong(500, 1300);
			if (now >= signCloseAt) { signCloseAt = 0; lastSignAt = 0; sign.onClose(); }
			return false;
		}
		signCloseAt = 0;
		// a container screen the server sees (not the chat or the Esc menu): wait, unless it is our own chest visit
		if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> && !(current instanceof ChestVisit)) {
			pause(300, "scherm open");
			return false;
		}
		Player other = Lumberjack.playerNearby(p, c.buildPauseRadius);
		if (other != null) {
			if (!paused) Exchange.emit("build", "action", "player nearby", "name", other.getGameProfile().name());
			lastPlayer = now; pause(1000, "player nearby");
		}
		if (paused) {
			if (now < pausedUntil || now - lastPlayer < c.buildWaitAfterPlayerSec * 1000L) {
				// stock still: glancing about during a pause (and the rest of the mod taking over) looked like someone there
				return false;
			}
			paused = false;
			Exchange.emit("build", "action", "on");
		}
		if (current == null) {
			// between two actions a breather now and then: nobody builds for hours without looking up
			if (c.buildPauseEveryMin > 0 && now >= nextBreak && steps.isEmpty() && p.onGround()) {
				long duration = R.nextLong(20_000, 60_000);
				nextBreak = now + (long) (R.nextDouble(0.6, 1.4) * c.buildPauseEveryMin * 60_000);
				Exchange.emit("build", "action", "breather", "seconds", duration / 1000);
				pause(duration, "breather");
				return false;
			}
			if (now < nextStepAfter) return look.tick(p);
			current = steps.pollFirst();
			if (current == null) {
				plan(p, c, now);
				if (!isOn) return false;
				current = steps.pollFirst();
				if (current == null) { nextStepAfter = now + 250; return false; }
			}
			current.begin(p);
		}
		Step s = current;
		int r;
		try { r = s.tick(p, mc, c); }
		catch (Exception e) { Exchange.emit("error", "where", "build:" + s.name(), "notice", String.valueOf(e)); r = FAILED; }
		if (r != BUSY) {
			boolean selfScheduled = current == null;
			current = null;
			// between two blocks at an edge you stay crouched; flicking shift per block is what nobody does
			if (!selfScheduled) { if (s instanceof Place || s instanceof Walk || s instanceof Dig) releaseMovement(); else releaseKeys(); }
			// a moment between two blocks, as a hand needs it; longer after walking or a chest. Hoeing counts as blocks:
			// with the longer wait a field took a third longer than it had to.
			nextStepAfter = now + (s instanceof Place || s instanceof Dig || s instanceof Finish ? R.nextLong(40, 180) : R.nextLong(150, 500));
			if (r == FAILED) { Exchange.emit("build", "action", "step failed", "step", s.name()); s.failed(p, c); }
		}
		return true;
	}

	// ---------- comparing schematic and world ----------
	/** One comparison of schematic and world over the whole placement. */
	static final class Scan {
		final Map<BlockPos, BlockState> missing = new HashMap<>();   // position -> the state that belongs there
		final Map<BlockPos, Boolean> dig = new HashMap<>();          // position -> true for a wrong block, false for "should be air"
		final Map<Item, Integer> need = new HashMap<>();              // items for everything still missing
		final Map<BlockPos, String> fixes = new HashMap<>();          // right block, still to finish (Finisher)
		final Set<BlockPos> spill = new HashSet<>();                  // water sources where the schematic wants air
		int total = 0, done = 0, wrong = 0, unloaded = 0, lowestY = Integer.MAX_VALUE;
		long finishedAt = 0;
		private Set<BlockPos> bodies;
		/** The missing blocks your body would be in the way of: the ones with a shape. A crop, a torch or a sign goes in fine where you stand. */
		Set<BlockPos> inTheWay(Level level) {
			if (bodies == null) {
				bodies = new HashSet<>();
				for (var e : missing.entrySet()) if (!e.getValue().getCollisionShape(level, e.getKey()).isEmpty()) bodies.add(e.getKey());
			}
			return bodies;
		}
	}

	/** Walks through the box a slice per tick, so a big build does not freeze the game. */
	static final class Scanner {
		final Level world, schem; final Schematic.Placement pl; final Schematic.Layers layers; final boolean clearAir; final Set<BlockPos> ours;
		/** The items whose blocks are left out of this placement (the panel's material list), as items. */
		final Set<Item> ignored = new HashSet<>();
		final Scan s = new Scan();
		final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		int x, y, z;
		Scanner(Level world, Level schem, Schematic.Placement pl, Schematic.Layers layers, boolean clearAir, Set<BlockPos> ours) {
			this.world = world; this.schem = schem; this.pl = pl; this.layers = layers; this.clearAir = clearAir; this.ours = ours;
			for (String id : BuildStore.ignored(pl.name())) {
				var key = net.minecraft.resources.Identifier.tryParse(id);
				if (key != null) net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(key).ifPresent(ignored::add);
			}
			x = pl.min().getX(); y = pl.min().getY(); z = pl.min().getZ();
		}
		/** Up to 'budget' positions; true when the whole box has been done. Layer by layer from the bottom. */
		boolean step(int budget) {
			while (budget-- > 0 && y <= pl.max().getY()) {
				visit();
				if (++x > pl.max().getX()) { x = pl.min().getX(); if (++z > pl.max().getZ()) { z = pl.min().getZ(); y++; } }
			}
			return y > pl.max().getY();
		}
		private void visit() {
			m.set(x, y, z);
			if (!layers.contains(x, y, z)) return;
			// Litematica only loads the schematic around you. A part that is not loaded reads as air, and "air wanted"
			// would have the builder break the real blocks there: it waits until that part is loaded.
			if (!schem.isLoaded(m)) { s.unloaded++; return; }
			BlockState want = schem.getBlockState(m);
			if (want.isAir()) {
				// what stands where the schematic wants air: gone, unless it is our own scaffold or water/lava
				if (!clearAir || !world.isLoaded(m)) return;
				BlockState have = world.getBlockState(m);
				if (have.isAir() || ours.contains(m) || Finisher.harmlessInAir(have)) return;
				if (plainSource(have)) { s.spill.add(m.immutable()); return; }   // taken up again with an empty bucket
				if (breakable(world, m, have)) s.dig.put(m.immutable(), false);
				return;
			}
			// a block whose item is on the placement's ignore list: neither placed nor counted, and what stands there stays
			if (!ignored.isEmpty() && ignored.contains(itemFor(want))) return;
			BlockState base = Finisher.base(want);
			if (notOurs(want) && base == null) return;
			if (!world.isLoaded(m)) { s.unloaded++; return; }
			BlockState have = world.getBlockState(m);
			s.total++;
			// the right block (or its base, or what the world turned it into by itself): only finishing touches left
			if (matches(want, have) || Finisher.sameEnough(want, have) || Finisher.readyFor(want, have)) {
				Finisher.Fix f = Finisher.fix(world, schem, m, want, have);
				if (f == null) { s.done++; return; }
				s.fixes.put(m.immutable(), f.what());
				// a tool counts once, however many blocks it works on (a field asked for 1600 hoes)
				if (f.item() != null) { if (isTool(f.item())) s.need.put(f.item(), 1); else s.need.merge(f.item(), 1, Integer::sum); }
				return;
			}
			if (base != null) want = base;   // a cauldron before its water, a pot before its plant
			// kelp and seagrass grow in water: the water first
			if (Finisher.growsInWater(want) && !(have.getFluidState().isSource() && have.getFluidState().is(net.minecraft.tags.FluidTags.WATER)))
				want = Blocks.WATER.defaultBlockState();
			if (!fillable(want, have)) {
				if (!breakable(world, m, have)) { s.wrong++; return; }
				s.dig.put(m.immutable(), true);   // first away, then the right one
			}
			if (dryVersionOf(want, have) && waterFirst(m) && breakable(world, m, have)) s.dig.put(m.immutable(), true);
			s.missing.put(m.immutable(), want);
			if (!dryVersionOf(want, have)) s.need.merge(itemFor(want), 1, Integer::sum);
			if (wantsWaterlogged(want)) s.need.merge(Items.WATER_BUCKET, 1, Integer::sum);
			s.lowestY = Math.min(s.lowestY, y);
		}
	}

	/** Keep a comparison going; a fresh one at most every two seconds. False if building cannot go on. */
	private static boolean advanceScan(LocalPlayer p, AfkConfig c, long now) {
		if (scanner == null) {
			if (scan != null && now - scan.finishedAt < 2000) return true;
			Schematic.Placement fresh = refreshPlacement();
			if (fresh == null) { stop("placement gone"); return false; }
			placement = fresh;
			Level schem = Schematic.world();
			if (schem == null) { stop("litematica gone"); return false; }
			scanner = new Scanner(p.level(), schem, placement, Schematic.layers(), c.buildClearAir, new HashSet<>(scaffold));
		}
		if (scanner.step(60_000)) {
			boolean first = scan == null;
			scan = scanner.s; scan.finishedAt = now; scanner = null;
			if (first) Exchange.emit("build", "action", "compared", "total", scan.total, "done", scan.done, "to_do", scan.missing.size(),
				"chop away", scan.dig.size(), "wrong", scan.wrong, "not_loaded", scan.unloaded, "low", scan.lowestY == Integer.MAX_VALUE ? null : scan.lowestY);
		}
		return true;
	}

	/**
	 * May the builder break this? Never what holds things (chests, furnaces, signs, beds, spawners: someone's stuff),
	 * never bedrock or water and lava.
	 */
	static boolean breakable(Level level, BlockPos pos, BlockState st) {
		if (st.isAir() || st.hasBlockEntity() || st.getBlock() instanceof LiquidBlock) return false;
		return st.getDestroySpeed(level, pos) >= 0;
	}

	/**
	 * Would breaking this let water or lava run? Always when it holds water itself (a waterlogged block: the water
	 * stays behind as a source). From a neighbour on top or beside it (it does not flow up) only through a face the
	 * neighbour's shape leaves open, as vanilla decides it: beside waterlogged leaves is fine, beside a waterlogged
	 * slab or plain water it is not.
	 */
	static boolean holdsBackFluid(Level level, BlockPos pos) {
		if (!level.getFluidState(pos).isEmpty()) return true;
		for (Direction d : Direction.values()) {
			if (d == Direction.DOWN) continue;
			BlockPos n = pos.relative(d);
			BlockState ns = level.getBlockState(n);
			if (ns.getFluidState().isEmpty()) continue;
			if (!net.minecraft.world.phys.shapes.Shapes.mergedFaceOccludes(ns.getCollisionShape(level, n), net.minecraft.world.phys.shapes.Shapes.empty(), d.getOpposite())) return true;
		}
		return false;
	}

	/** Lava next to it: breaking would let it run into the build. */
	private static boolean lavaNext(Level level, BlockPos pos) {
		for (Direction d : Direction.values()) if (level.getFluidState(pos.relative(d)).is(Fluids.LAVA) || level.getFluidState(pos.relative(d)).is(Fluids.FLOWING_LAVA)) return true;
		return false;
	}

	/** Properties the placement itself decides and that stay put afterwards; the rest (connections, water, power) follows from the neighbours. */
	private static final Set<String> ORIENTATION = Set.of("facing", "axis", "half", "face", "rotation", "hinge", "orientation", "vertical_direction", "attachment", "hanging");
	/** Properties that grow with every click on the same block. */
	private static final Set<String> COUNTS = Set.of("candles", "pickles", "layers", "eggs");

	static boolean matches(BlockState want, BlockState have) {
		if (want.getBlock() != have.getBlock()) return false;
		if (want.getBlock() == Blocks.WATER) return have.getFluidState().isSource();   // flowing water is not the source it wants
		if (wantsWaterlogged(want) && !(have.hasProperty(BlockStateProperties.WATERLOGGED) && have.getValue(BlockStateProperties.WATERLOGGED))) return false;
		for (Property<?> prop : want.getProperties()) {
			String n = prop.getName();
			boolean relevant = ORIENTATION.contains(n) || COUNTS.contains(n) || (n.equals("type") && want.getBlock() instanceof SlabBlock);
			if (relevant && (!have.hasProperty(prop) || !want.getValue(prop).equals(have.getValue(prop)))) return false;
		}
		return true;
	}

	/** Candles, pickles, snow layers: how many are in this block, or -1 if it is not that kind of block. */
	private static int countOf(BlockState st) {
		for (Property<?> prop : st.getProperties())
			if (COUNTS.contains(prop.getName()) && st.getValue(prop) instanceof Integer n) return n;
		return -1;
	}

	/** Can the target still be made by placing: empty, grass or water there, or the same block one click short. */
	static boolean fillable(BlockState want, BlockState have) {
		if (want.getBlock() == Blocks.WATER) return have.isAir() || have.canBeReplaced();   // flowing water too
		if (dryVersionOf(want, have)) return true;   // its water still goes in
		if (have.getBlock() != want.getBlock()) return have.isAir() || have.canBeReplaced();
		if (want.getBlock() instanceof SlabBlock) return want.getValue(SlabBlock.TYPE) == SlabType.DOUBLE && have.getValue(SlabBlock.TYPE) != SlabType.DOUBLE;
		int w = countOf(want), h = countOf(have);
		return w > 0 && h >= 0 && h < w;
	}

	/** Is this placement a step toward the target: the block itself, the first half of a double slab, one candle of three? */
	static boolean acceptable(BlockState want, BlockState got) {
		if (matches(want, got)) return true;
		if (wantsWaterlogged(want) && dryVersionOf(want, got)) return true;   // first the block, the water after
		if (got.getBlock() != want.getBlock()) return false;
		if (want.getBlock() instanceof SlabBlock) return want.getValue(SlabBlock.TYPE) == SlabType.DOUBLE;
		int w = countOf(want), g = countOf(got);
		return w > 0 && g > 0 && g <= w;
	}

	/**
	 * Blocks the builder leaves alone: the upper half of a door or tall plant and the head of a bed come along with
	 * the other half, and whatever has no item of its own (fire, portals, flowing water) cannot be placed. A water
	 * source comes from a water bucket.
	 */
	static boolean notOurs(BlockState want) {
		Block b = want.getBlock();
		if (b == Blocks.WATER) return !want.getFluidState().isSource();
		if (want.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && want.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return true;
		if (want.hasProperty(BlockStateProperties.BED_PART) && want.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) return true;
		return !(b.asItem() instanceof BlockItem);
	}

	/** The item that places this block: its own, or a water bucket for water. */
	static Item itemFor(BlockState want) {
		return want.getBlock() == Blocks.WATER ? Items.WATER_BUCKET : want.getBlock().asItem();
	}

	/** The item for this job now: a water bucket once the block of a waterlogged one stands dry. */
	static Item itemFor(Job j) {
		Level level = Minecraft.getInstance().level;
		if (level != null && wantsWaterlogged(j.want()) && dryVersionOf(j.want(), level.getBlockState(j.pos()))) return Items.WATER_BUCKET;
		return itemFor(j.want());
	}

	static boolean wantsWaterlogged(BlockState want) {
		return want.hasProperty(BlockStateProperties.WATERLOGGED) && want.getValue(BlockStateProperties.WATERLOGGED);
	}

	/** The wanted block standing there right, only without its water yet. */
	private static boolean dryVersionOf(BlockState want, BlockState have) {
		return have.getBlock() == want.getBlock() && have.hasProperty(BlockStateProperties.WATERLOGGED) && !have.getValue(BlockStateProperties.WATERLOGGED)
			&& matches(want.setValue(BlockStateProperties.WATERLOGGED, false), have);
	}

	/** Does placing water here pour it (or a waterlogged block) now, rather than a block? */
	private static boolean poursWater(BlockState want, BlockState have) {
		return want.getBlock() == Blocks.WATER || (wantsWaterlogged(want) && dryVersionOf(want, have));
	}

	/**
	 * Would water here stay where the schematic wants water? It runs down and up to seven sideways into every open
	 * cell; each cell it reaches must be water (or waterlogged) in the schematic, otherwise it waits until the blocks
	 * that hold it are there. Pouring it in first ran water out of the build and set off the watchdog.
	 * As vanilla decides it (FlowingFluid.canPassThroughWall): water only leaves a cell through a face that the
	 * two collision shapes together do not cover. So waterlogged leaves (a full shape) never let water out, while a
	 * slab or stairs let it out through the open half of a side.
	 */
	private static boolean waterContained(Level level, BlockPos pos) {
		Level schem = Schematic.world();
		if (schem == null) return false;
		ArrayDeque<BlockPos> q = new ArrayDeque<>();
		Set<BlockPos> seen = new HashSet<>();
		q.add(pos); seen.add(pos);
		while (!q.isEmpty()) {
			BlockPos cur = q.poll();
			VoxelShape from = level.getBlockState(cur).getCollisionShape(level, cur);
			for (Direction d : new Direction[] { Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST }) {
				BlockPos n = cur.relative(d);
				if (seen.contains(n)) continue;
				BlockState have = level.getBlockState(n);
				if (net.minecraft.world.phys.shapes.Shapes.mergedFaceOccludes(from, have.getCollisionShape(level, n), d)) continue;
				seen.add(n);
				if (!have.getFluidState().isEmpty()) continue;   // water already: nothing new
				if (!have.isAir() && !have.canBeReplaced()) continue;   // holds the water
				if (!placement.contains(n, 0) || !schem.getBlockState(n).getFluidState().is(net.minecraft.tags.FluidTags.WATER)) return false;
				for (Direction e : Direction.values()) if (level.getBlockState(n.relative(e)).getBlock() instanceof ConcretePowderBlock) return false;
				if (Math.abs(n.getX() - pos.getX()) + Math.abs(n.getZ() - pos.getZ()) < 8) q.add(n);
			}
		}
		return true;
	}

	// ---------- simulating a click ----------
	record Placing(BlockPos pos, BlockState state) {}

	/**
	 * What would come of a right-click with this stack on this hit, looking in this direction. The view direction
	 * is set on the player for a moment and put back right away: it never leaves the client, it only feeds vanilla's
	 * placement logic the direction the real click will have.
	 */
	static Placing simulate(LocalPlayer p, ItemStack stack, BlockHitResult hit, float yaw, float pitch) {
		if (stack.is(Items.WATER_BUCKET)) return pourWater(p.level(), hit);
		if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) return null;
		float y0 = p.getYRot(), x0 = p.getXRot();
		p.setYRot(yaw); p.setXRot(pitch);
		try {
			BlockPlaceContext ctx = item.updatePlacementContext(new BlockPlaceContext(p, InteractionHand.MAIN_HAND, stack, hit));
			if (ctx == null || !ctx.canPlace()) return null;
			BlockState st = ((BlockItemAccessor) item).buddy$placementState(ctx);
			return st == null ? null : new Placing(ctx.getClickedPos(), st);
		} catch (RuntimeException e) {
			return null;
		} finally {
			p.setYRot(y0); p.setXRot(x0);
		}
	}

	/**
	 * Where a water bucket empties, as vanilla's bucket decides it: into the clicked block if it can hold water
	 * (waterlogging it), otherwise into the cell in front of the clicked face, waterlogging what stands there.
	 */
	private static Placing pourWater(Level level, BlockHitResult hit) {
		var wl = BlockStateProperties.WATERLOGGED;
		BlockState clicked = level.getBlockState(hit.getBlockPos());
		if (clicked.hasProperty(wl) && !clicked.getValue(wl)) return new Placing(hit.getBlockPos(), clicked.setValue(wl, true));
		BlockPos at = hit.getBlockPos().relative(hit.getDirection());
		BlockState there = level.getBlockState(at);
		if (there.hasProperty(wl)) return there.getValue(wl) ? null : new Placing(at, there.setValue(wl, true));
		if (!(there.isAir() || there.canBeReplaced())) return null;
		return new Placing(at, Blocks.WATER.defaultBlockState());
	}

	/** Where to look, what the crosshair must then hit, and whether shift must be held (clicking against a chest or door). */
	record Aim(BlockHitResult hit, Vec3 point, boolean crouch) {}

	/**
	 * A spot to click from this eye position so exactly the wanted block lands in the target, or null. Tries the
	 * faces of the neighbours that touch the target (and the target itself for a second slab or a replaceable plant),
	 * several points per face for top/bottom halves and door hinges; each must be visible in a straight line, the
	 * way the crosshair would hit it. Of what works, the one that needs the smallest turn; clicking against a chest
	 * or door (with shift) only when nothing else works.
	 */
	static Aim findAim(LocalPlayer p, Vec3 eye, BlockPos target, BlockState want, ItemStack stack) {
		Level level = p.level();
		double range = p.blockInteractionRange() - 0.1;
		if (eye.distanceTo(Vec3.atCenterOf(target)) > range + 1.8) return null;
		Aim best = null; double bestTurn = Double.MAX_VALUE;
		for (Direction d : Direction.values()) {
			BlockPos n = target.relative(d);
			BlockState ns = level.getBlockState(n);
			if (ns.isAir() || ns.canBeReplaced()) continue;
			VoxelShape shape = ns.getShape(level, n);
			if (shape.isEmpty()) continue;
			boolean crouch = SafeLook.interactable(ns);
			for (Aim a : aimsOnFace(p, eye, range, n, d.getOpposite(), shape.bounds(), target, want, stack, crouch)) {
				double turn = turn(p, eye, a.point()) + (crouch ? 90 : 0);
				if (turn < bestTurn) { bestTurn = turn; best = a; }
			}
		}
		BlockState here = level.getBlockState(target);
		if (!here.isAir() && (here.canBeReplaced() || here.getBlock() == want.getBlock())) {
			VoxelShape shape = here.getShape(level, target);
			if (!shape.isEmpty()) for (Direction face : Direction.values())
				for (Aim a : aimsOnFace(p, eye, range, target, face, shape.bounds(), target, want, stack, false)) {
					double turn = turn(p, eye, a.point());
					if (turn < bestTurn) { bestTurn = turn; best = a; }
				}
		}
		return best;
	}

	private static final double[][] FACE_POINTS = { {0.5, 0.5}, {0.25, 0.5}, {0.75, 0.5}, {0.5, 0.25}, {0.5, 0.75}, {0.25, 0.25}, {0.75, 0.75}, {0.25, 0.75}, {0.75, 0.25} };

	/** Points on one face of the clicked block that work: visible from the eye, within reach, and placing the right state. */
	/** Points a Place step already found blocked; left out of findAim while it looks again. */
	private static Set<Vec3> aimSkip = Set.of();

	private static List<Aim> aimsOnFace(LocalPlayer p, Vec3 eye, double range, BlockPos clicked, Direction face, AABB box, BlockPos target, BlockState want, ItemStack stack, boolean crouch) {
		List<Aim> out = new ArrayList<>();
		for (Vec3 point : facePoints(eye, clicked, face, box)) {
			if (aimSkip.contains(point)) continue;
			if (eye.distanceTo(point) > range || !visible(p, eye, point, clicked, face)) continue;
			if (stack.is(Items.WATER_BUCKET) && !bucketAimHolds(p, eye, point, clicked, face, ClipContext.Fluid.NONE)) continue;
			float[] rot = rotation(eye, point);
			BlockHitResult hit = new BlockHitResult(point, face, clicked, false);
			Placing sim = simulate(p, stack, hit, rot[0], rot[1]);
			if (sim == null || !sim.pos().equals(target) || !acceptable(want, sim.state())) continue;
			out.add(new Aim(hit, point, crouch));
		}
		return out;
	}

	/**
	 * Points on a face of a block (within its shape), or none if the face looks away from the eye: clicking or
	 * breaking a face on the far side is what Grim calls impossible.
	 */
	private static List<Vec3> facePoints(Vec3 eye, BlockPos pos, Direction face, AABB box) {
		List<Vec3> out = new ArrayList<>();
		Direction.Axis axis = face.getAxis();
		double plane = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? box.max(axis) : box.min(axis);
		double eyeAlong = axis == Direction.Axis.X ? eye.x - pos.getX() : axis == Direction.Axis.Y ? eye.y - pos.getY() : eye.z - pos.getZ();
		if ((eyeAlong - plane) * face.getAxisDirection().getStep() <= 0) return out;
		Direction.Axis u = axis == Direction.Axis.X ? Direction.Axis.Y : Direction.Axis.X;
		Direction.Axis v = axis == Direction.Axis.Z ? Direction.Axis.Y : Direction.Axis.Z;
		for (double[] f : FACE_POINTS) {
			double[] c = new double[3];
			c[axis.ordinal()] = plane;
			c[u.ordinal()] = box.min(u) + (box.max(u) - box.min(u)) * f[0];
			c[v.ordinal()] = box.min(v) + (box.max(v) - box.min(v)) * f[1];
			out.add(new Vec3(pos.getX() + c[0], pos.getY() + c[1], pos.getZ() + c[2]));
		}
		return out;
	}

	/** Would the crosshair from this eye, aimed at this point, land on exactly this face of this block? */
	private static boolean visible(LocalPlayer p, Vec3 eye, Vec3 point, BlockPos pos, Direction face) {
		Vec3 dir = point.subtract(eye).normalize();
		BlockHitResult ray = p.level().clip(new ClipContext(eye, point.add(dir.scale(0.02)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
		return ray.getType() == HitResult.Type.BLOCK && ray.getBlockPos().equals(pos) && ray.getDirection() == face;
	}

	/** A point to break this block from this eye: on a face you see, within reach. Null if there is none. */
	static Vec3 breakPoint(LocalPlayer p, Vec3 eye, BlockPos pos) {
		Level level = p.level();
		VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
		if (shape.isEmpty()) return null;
		double range = p.blockInteractionRange() - 0.05;
		Vec3 best = null; double bestTurn = Double.MAX_VALUE;
		for (Direction face : Direction.values())
			for (Vec3 point : facePoints(eye, pos, face, shape.bounds())) {
				if (eye.distanceTo(point) > range || !visible(p, eye, point, pos, face)) continue;
				double turn = turn(p, eye, point);
				if (turn < bestTurn) { bestTurn = turn; best = point; }
				break;   // one point per face is enough to know the face works
			}
		return best;
	}

	private static float[] rotation(Vec3 eye, Vec3 point) {
		double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
		return new float[] { (float) Math.toDegrees(Math.atan2(-dx, dz)), (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))) };
	}

	private static double turn(LocalPlayer p, Vec3 eye, Vec3 point) {
		float[] rot = rotation(eye, point);
		return Math.abs(Mth.wrapDegrees(rot[0] - p.getYRot())) + Math.abs(rot[1] - p.getXRot());
	}

	// ---------- planning ----------
	/** One block to place: from the schematic, or a scaffold block (a support, or a bridge/pillar block). */
	record Job(BlockPos pos, BlockState want, boolean scaffold) {}

	private static void plan(LocalPlayer p, AfkConfig c, long now) {
		if (scan == null) { steps.add(new Wait(300, "comparing with the schematic")); return; }
		Level level = p.level();
		skipped.values().removeIf(t -> t < now);
		noMaterial.values().removeIf(t -> now - t > 5 * 60_000L);
		// what you now carry is not missing any more (the HUD itself only shows what the build still lacks; scaffolding
		// and dirt are never in the build's needs, and dropping them here sent him back to an empty chest every plan)
		noMaterial.keySet().removeIf(it -> Lumberjack.countInInventory(p, it) > 0);
		refreshScaffold(level);

		// away: wrong blocks, and (if set) whatever stands where the schematic wants air; checked against the world of now
		List<BlockPos> digs = new ArrayList<>();
		boolean digLeft = false;
		for (var e : scan.dig.entrySet()) {
			if (!stillToDig(level, e.getKey(), e.getValue())) continue;
			digLeft = true;
			if (!skipped.containsKey(e.getKey()) && !holdsBackFluid(level, e.getKey())) digs.add(e.getKey());
		}
		// supports whose block stands: away now, top down, not the one under your feet
		retire.removeIf(pos -> !scaffold.contains(pos) || !solid(level, pos));
		for (BlockPos pos : retire) if (!pos.equals(p.blockPosition().below()) && !skipped.containsKey(pos) && !holdsBackFluid(level, pos)) { digs.add(pos); digLeft = true; }
		// in: the build itself and the supports for blocks that would float
		List<Job> jobs = new ArrayList<>(), crops = new ArrayList<>();
		Set<BlockPos> remaining = new HashSet<>();
		waterWaits.clear();
		wetGround = null;
		boolean buildLeft = false;
		for (var e : scan.missing.entrySet()) {
			BlockState have = level.getBlockState(e.getKey());
			BlockState want = e.getValue();
			if (matches(want, have)) { rewet.remove(e.getKey()); continue; }
			buildLeft = true;
			remaining.add(e.getKey());
			// water first where a bucket on the block does not work: the dry block waits to be broken (it is on the
			// list of things to break), and an empty cell gets its water before the block
			if (wantsWaterlogged(want) && waterFirst(e.getKey())) {
				if (dryVersionOf(want, have)) continue;
				if (!plainSource(have) && (have.isAir() || have.canBeReplaced())) want = Blocks.WATER.defaultBlockState();
			}
			if (!fillable(want, have) || skipped.containsKey(e.getKey())) continue;
			if (!placeableNow(level, e.getKey(), want)) continue;
			if (poursWater(want, have) && !waterContained(level, e.getKey())) {   // waits for what holds it
				waterWaits.add(e.getKey());
				if (waterLogged.add(e.getKey()))
					Exchange.emit("build", "action", "water waits", "block", e.getKey().toShortString(), "what", Lumberjack.blockId(want.getBlock()).replace("minecraft:", ""));
				continue;
			}
			(Finisher.needsFarmland(want) ? crops : jobs).add(new Job(e.getKey(), want, false));
		}
		// A field goes in passes, the way a player does it: first all the ground and the water, then hoe all of it,
		// then sow all of it. Hoeing and sowing each bit at once left dry farmland that turned back to dirt before
		// its water came, and kept switching between dirt, hoe and seeds.
		boolean groundLeft = false;
		for (Job j : jobs) {
			Item it = itemFor(j);
			if (Lumberjack.countInInventory(p, it) > 0 || !noMaterial.containsKey(it)) { groundLeft = true; break; }
		}
		boolean tillLeft = false;
		if (hasHoe(p)) for (var e : scan.fixes.entrySet())
			if (e.getValue().equals("till") && !skipped.containsKey(e.getKey()) && Finisher.tillable(level.getBlockState(e.getKey()).getBlock())) { tillLeft = true; break; }
		holdHoe = groundLeft;
		// farmland that no water reaches is sown at once all the same: bare and dry it turns back to dirt
		for (Job j : crops) if ((!groundLeft && !tillLeft) || !wetNear(level, j.pos().below())) jobs.add(j);
		supports.keySet().removeIf(pos -> solid(level, pos) || !canScaffold(level, pos));
		for (var e : supports.entrySet()) if (!skipped.containsKey(e.getKey())) jobs.add(new Job(e.getKey(), e.getValue(), true));
		remaining.addAll(supports.keySet());
		order = new BuildOrder.Ctx(level, placement, remaining, entry == null ? p.blockPosition() : entry, footing(p), p.blockInteractionRange());

		// finishing touches (Finisher): settings, items on blocks, entities of the schematic
		Level schemW = Schematic.world();
		finishing.clear();
		if (schemW != null) {
			for (BlockPos pos : scan.fixes.keySet()) {
				if (!level.isLoaded(pos)) continue;
				Finisher.Fix f = Finisher.fix(level, schemW, pos, schemW.getBlockState(pos), level.getBlockState(pos));
				if (f != null && !skipped.containsKey(pos)) finishing.put(pos, f);
			}
			entityJobs = Finisher.entityJobs(level, schemW, new AABB(Vec3.atLowerCornerOf(placement.min()), Vec3.atLowerCornerOf(placement.max()).add(1, 1, 1)));
			entityJobs.removeIf(e -> skipped.containsKey(e.want().blockPosition()));
		}
		boolean finishLeft = !finishing.isEmpty() || !entityJobs.isEmpty();
		toolsLacking.clear();
		Set<String> kinds = new HashSet<>();
		for (Finisher.Fix f : finishing.values())
			if (f.item() != null && kinds.add(f.what()) && isTool(f.item()) && !canHold(p, f)) toolsLacking.add(f);
		neededNow.clear();
		for (Job j : jobs) neededNow.add(itemFor(j));
		for (Finisher.Fix f : finishing.values()) if (f.item() != null) neededNow.add(f.item());

		// all done: break the scaffold again, then finished
		boolean cleanup = false;
		if (!buildLeft && !digLeft && supports.isEmpty() && !finishLeft) {
			Set<BlockPos> ours = new HashSet<>();
			for (BlockPos pos : scaffold) if (pos.closerThan(p.blockPosition(), 64)) ours.add(pos);
			if (ours.isEmpty() || !c.buildRemoveScaffold) {
				if (scan.unloaded > 0) { steps.add(new Wait(2000, "waiting for chunks that are not loaded")); return; }   // the rest lies in chunks you have not loaded
				stop("done");
				return;
			}
			if (!cleanupAnnounced) { cleanupAnnounced = true;  Exchange.emit("build", "action", "clearing the scaffold", "blocks", ours.size()); }
			cleanup = true;
			digs.clear();
			// scaffold with water against it stays: breaking it could let the water run. Trying it again every
			// twenty seconds kept the build from ever finishing.
			int held = 0;
			for (BlockPos pos : ours) {
				if (holdsBackFluid(level, pos)) held++;
				else if (!skipped.containsKey(pos)) digs.add(pos);
			}
			if (digs.isEmpty() && held > 0 && held == ours.size()) {
				Exchange.emit("build", "action", "scaffold stays", "blocks", held, "reason", "holds water back");
				stop("done");
				return;
			}
			if (digs.isEmpty()) { steps.add(new Wait(5000, "only skipped blocks left, trying again soon")); return; }
		}

		// 1. break what is within reach, top down
		BlockPos d = bestDigInReach(p, digs, cleanup);
		if (d != null) { steps.add(new Dig(d)); return; }

		// water where the schematic wants air (a bucket that went on top of a block instead of into it): take it up
		// again before it runs over the build and washes the crops out
		if (!cleanup && tower == null && !scan.spill.isEmpty() && Lumberjack.countInInventory(p, Items.BUCKET) > 0 && planSpill(p, now)) return;

		// 2. drops lying around the build, now and then
		if (tower == null && c.buildPickup && now - lastPickup > 45_000 && Lumberjack.freeSlots(p) >= 2 && !looseItems(p).isEmpty()) {
			lastPickup = now; steps.add(new Pickup()); return;
		}

		// what we broke outside the build goes back first, as soon as its block is placed (steps run before a new plan)
		List<Job> restore = new ArrayList<>();
		for (Job r : restoreJobs(p)) if (Lumberjack.countInInventory(p, itemFor(r)) > 0) restore.add(r);
		if (!restore.isEmpty() && tower == null) {
			Job r = bestPlaceInReach(p, restore, false);
			if (r != null) { steps.add(new Place(r)); return; }
			if (planWalkToPlace(p, c, restore, now, false)) return;
		}

		// A tool a finishing touch waits for, in a chest we know of: fetch it before the next pass. With his hoe worn
		// down he went on to sow and would only have looked for another hoe once every seed was in.
		if (!cleanup && tower == null && !toolsLacking.isEmpty() && Lumberjack.freeSlots(p) > 1) {
			Set<Item> tools = new HashSet<>();
			for (Finisher.Fix f : toolsLacking) {
				Item it = toolInChest(p, f);
				if (it != null && !noMaterial.containsKey(it)) tools.add(it);
			}
			if (!tools.isEmpty() && planRestock(p, c, tools, now)) return;
		}

		// 3. place what is within reach, split by material: what you carry, what a chest may have
		Set<Item> lacking = new HashSet<>();
		List<Job> carried = new ArrayList<>();
		if (!cleanup) for (Job j : jobs) {
			Item it = itemFor(j);
			if (Lumberjack.countInInventory(p, it) > 0) carried.add(j); else if (!noMaterial.containsKey(it)) lacking.add(it);
		}
		// Water: one full bucket per block from the chests filled the bag with buckets and then stalled. With a
		// source nearby that refills itself, an empty bucket is filled there; an empty one from a chest will do too.
		int waterJobs = 0;
		if (!cleanup) {
			for (Job j : jobs) if (itemFor(j) == Items.WATER_BUCKET) waterJobs++;
			for (Finisher.Fix f : finishing.values()) if (f.item() == Items.WATER_BUCKET) waterJobs++;
		}
		boolean dry = waterJobs > 0 && Lumberjack.countInInventory(p, Items.WATER_BUCKET) == 0;
		if (dry && refillPossible(p, now)) {
			if (Lumberjack.countInInventory(p, Items.BUCKET) > 0) lacking.remove(Items.WATER_BUCKET);
			else if (!noMaterial.containsKey(Items.BUCKET)) lacking.add(Items.BUCKET);
		}
		// hoeing before other placing within reach (once the ground pass is done: holdHoe keeps it back until then)
		boolean restDone = jobs.isEmpty() && digs.isEmpty();
		Step till = finishInReach(p, restDone, true);
		if (till != null) { steps.add(till); return; }
		Job t = bestPlaceInReach(p, carried, true);
		if (t != null) { steps.add(new Place(t)); return; }
		// finishing touches within reach; what gives power only once everything else is done
		Step fin = finishInReach(p, restDone, false);
		if (fin != null) { steps.add(fin); return; }
		if (tower != null) {
			// nothing left in reach from the tower: down, break its foot (the rest falls) and pick it up
			steps.add(new Descend(tower)); steps.add(new Dig(tower.base())); steps.add(new Pickup());
			return;
		}
		// out of water, with an empty bucket: fill it at a source that refills itself
		if (dry && Lumberjack.countInInventory(p, Items.BUCKET) > 0 && planRefill(p, waterJobs, now)) return;

		// 4. the lowest layer waits for material you do not carry: fetch it first
		if (!cleanup) {
			int low = Integer.MAX_VALUE;
			for (Job j : jobs) {
				Item it = itemFor(j);
				if (Lumberjack.countInInventory(p, it) > 0 || lacking.contains(it)) low = Math.min(low, j.pos().getY());
			}
			boolean lowNeedsFetch = false;
			for (Job j : jobs) if (j.pos().getY() == low && lacking.contains(itemFor(j))) { lowNeedsFetch = true; break; }
			// Not with a full bag while there is work for what you carry: he walked back to the shulker boxes for
			// leaves with the bag full of dirt, could take nothing, and reported the leaves as gone.
			boolean room = carried.isEmpty() || Lumberjack.freeSlots(p) > 1;
			if (!lacking.isEmpty() && (lowNeedsFetch || carried.isEmpty()) && digs.isEmpty() && room) {
				if (planRestock(p, c, lacking, now)) return;
			}
		}

		// 5. walk: first to what has to go, then to what has to come
		if (!digs.isEmpty() && planWalkToDig(p, c, digs, cleanup, now)) return;
		if (cleanup) {
			// what is left of the scaffold cannot be reached from the ground: say so, the build itself is done
			Exchange.emit("build", "action", "scaffold stays", "blocks", digs.size());
			stop("done");
			return;
		}
		if (!carried.isEmpty() && planWalkToPlace(p, c, carried, now, true)) return;
		// nothing to place from here: fetch what the finishing touches need, and walk to them
		if (finishLeft && planFinish(p, c, now, restDone)) return;

		// scaffold would get us there, but there is nothing to build it with: fetch some
		if (c.buildUseScaffold && fillerCount(p, c) == 0 && (!carried.isEmpty() || !digs.isEmpty())
			&& !(c.buildUseScaffolding && Lumberjack.countInInventory(p, Items.SCAFFOLDING) > 0)) {
			Set<Item> fillers = new HashSet<>();
			for (Item it : fillerItems(c)) if (!noMaterial.containsKey(it)) fillers.add(it);
			if (!fillers.isEmpty() && planRestock(p, c, fillers, now)) return;
			if (now - lastFillerTell > 10 * 60_000L) { lastFillerTell = now;  }
		}

		// every block left would shut another one out: then the least bad one rather than standing still
		if (!carried.isEmpty()) {
			Job any = bestPlaceInReach(p, carried, false);
			if (any != null) { Exchange.emit("build", "action", "enclosing unavoidable", "block", any.pos().toShortString()); steps.add(new Place(any)); return; }
			if (planWalkToPlace(p, c, carried, now, false)) return;
		}
		if (carried.isEmpty() && digs.isEmpty()) { steps.add(new Wait(5000, jobs.isEmpty() ? "only skipped blocks left, trying again soon" : "waiting for material")); return; }
		// say where: without a position you could not help him get there
		List<BlockPos> stuck = new ArrayList<>();
		for (Job j : carried) stuck.add(j.pos());
		stuck.addAll(digs);
		stuck.sort(Comparator.comparingDouble(q -> q.distSqr(p.blockPosition())));
		BlockPos first = stuck.get(0);
		if (now - lastUnreachableTell > 5 * 60_000L) {
			lastUnreachableTell = now;
		}
		Exchange.emit("build", "action", "cannot reach it", "to_do", jobs.size(), "chop away", digs.size(), "spot", p.blockPosition().toShortString(), "first", first.toShortString());
		steps.add(new Wait(8000, "cannot reach the rest"));
	}

	/** Forget scaffold blocks that are gone (you broke them, or a creeper did). */
	private static void refreshScaffold(Level level) {
		for (BlockPos pos : new ArrayList<>(scaffold))
			if (level.isLoaded(pos) && !solid(level, pos)) { scaffold.remove(pos); BuildStore.removeScaffold(pos, dim); }
	}

	/** Must this still go: gone already, or (for a wrong block) is it right by now? */
	private static boolean stillToDig(Level level, BlockPos pos, boolean wrong) {
		BlockState st = level.getBlockState(pos);
		if (!breakable(level, pos, st) || scaffold.contains(pos)) return false;
		if (!wrong) return true;
		Level schem = Schematic.world();
		if (schem == null) return false;
		BlockState want = schem.getBlockState(pos);
		if (dryVersionOf(want, st) && waterFirst(pos)) return true;   // away, so the water can go in first
		return !matches(want, st) && !fillable(want, st);
	}

	/** After breaking the block under your feet, where do you land: fine if within three blocks. */
	private static boolean safeDrop(Level level, BlockPos floor) {
		for (int k = 1; k <= 3; k++) if (solid(level, floor.below(k))) return !Grinder.hazard(level, floor.below(k - 1)) && !Grinder.hazard(level, floor.below(k));
		return false;
	}

	/** The block to break next from here: top first. Your own floor only when it is scaffold and the drop is small. */
	private static BlockPos bestDigInReach(LocalPlayer p, List<BlockPos> digs, boolean cleanup) {
		Level level = p.level();
		Vec3 eye = p.getEyePosition();
		double range = p.blockInteractionRange() + 0.9;
		BlockPos floor = p.blockPosition().below();
		List<BlockPos> near = new ArrayList<>();
		for (BlockPos pos : digs) if (eye.distanceToSqr(Vec3.atCenterOf(pos)) <= range * range) near.add(pos);
		near.sort(Comparator.<BlockPos>comparingInt(pos -> -pos.getY()).thenComparingDouble(pos -> turn(p, eye, Vec3.atCenterOf(pos))));
		int tries = 0;
		for (BlockPos pos : near) {
			if (tries++ > 48) break;
			if (pos.equals(floor) && !(cleanup && scaffold.contains(pos) && safeDrop(level, pos))) continue;
			// clearing up a bridge while standing on it would cut the way back
			if (cleanup && !pos.equals(floor) && scaffold.contains(floor)) continue;
			if (lavaNext(level, pos)) { skipped.put(pos, System.currentTimeMillis() + 10 * 60_000L); continue; }
			if (breakPoint(p, eye, pos) != null) return pos;
		}
		return null;
	}

	/** Eye position at this cell as you will stand there: crouched at an edge, upright otherwise. */
	private static Vec3 eyeAt(LocalPlayer p, Level level, BlockPos cell) {
		return new Vec3(cell.getX() + 0.5, feetAt(level, cell) + eyeHeight(p, level, cell), cell.getZ() + 0.5);
	}

	/**
	 * The block to place next from here: low layers first, then by the order rules (BuildOrder), then the one
	 * closest to where you look. With {@code strict}, never one that shuts another block out.
	 */
	private static Job bestPlaceInReach(LocalPlayer p, List<Job> carried, boolean strict) {
		Vec3 eye = new Vec3(p.getX(), p.getY() + eyeHeight(p, p.level(), p.blockPosition()), p.getZ());
		double range = p.blockInteractionRange() + 0.9;
		List<Job> near = new ArrayList<>();
		for (Job j : carried) if (eye.distanceToSqr(Vec3.atCenterOf(j.pos())) <= range * range) near.add(j);
		// Strictly layer by layer had him swing round for every block of the lowest layer that lay behind him; a block
		// one layer up may now go first when it saves more than LAYER_TURN degrees of turning.
		int low = near.stream().mapToInt(j -> j.pos().getY()).min().orElse(0);
		Map<Job, Double> key = new HashMap<>();
		// within reach the turn counts most: at full weight the order rules had him swing between both sides
		for (Job j : near) key.put(j, (j.pos().getY() - low) * LAYER_TURN + turn(p, eye, Vec3.atCenterOf(j.pos())) + priority(j) * IN_REACH_ORDER);
		near.sort(Comparator.comparingDouble(key::get));
		int tries = 0, checks = 0;
		for (Job j : near) {
			if (tries++ > 48) break;
			if (standsInTheWay(p, j.pos())) continue;
			if (findAim(p, eye, j.pos(), j.want(), new ItemStack(itemFor(j))) == null) continue;
			if (strict && order != null && shuts(p.level(), j) && checks++ < MAX_CLOSE_CHECKS && BuildOrder.closesOff(order, j.pos())) continue;
			return j;
		}
		return null;
	}

	/** Spots tried per block before it counts as having none. */
	private static final int SPOTS_TRIED = 64;

	/**
	 * The spots taken in turn from each side the block is looked at from (four directions, level or steeply down),
	 * each side nearest first. In plain walking order the first spots all lay on one side, and a stair, gate or
	 * observer that needs the other side was skipped with the wrong facing.
	 */
	private static List<BlockPos> bySide(LocalPlayer p, Level level, List<BlockPos> cells, Vec3 target) {
		Map<Integer, ArrayDeque<BlockPos>> sides = new java.util.TreeMap<>();
		for (BlockPos cell : cells) {
			Vec3 eye = eyeAt(p, level, cell);
			double dx = target.x - eye.x, dz = target.z - eye.z, dy = target.y - eye.y;
			int dir = Direction.getApproximateNearest(dx, 0, dz).get2DDataValue();
			int steep = -dy > Math.hypot(dx, dz) ? 1 : 0;
			sides.computeIfAbsent(dir * 2 + steep, k -> new ArrayDeque<>()).add(cell);
		}
		List<BlockPos> out = new ArrayList<>(cells.size());
		while (out.size() < cells.size())
			for (ArrayDeque<BlockPos> q : sides.values()) if (!q.isEmpty()) out.add(q.poll());
		return out;
	}

	/** Walking cost (a step is 10) of standing where a block of the build still has to come. */
	private static final int FUTURE_COST = 40;

	private static boolean reachesFrom(LocalPlayer p, Level level, BlockPos here, BlockPos cell, Job j, Vec3 bc, double range) {
		return !cell.equals(here) && eyeAt(p, level, cell).distanceTo(bc) <= range + 0.8 && !new AABB(j.pos()).intersects(bodyAt(cell));
	}

	/** The properties that the click decides (facing, half, ...), for the events. */
	private static String orientation(BlockState st) {
		List<String> out = new ArrayList<>();
		for (Property<?> prop : st.getProperties())
			if (ORIENTATION.contains(prop.getName()) || (prop.getName().equals("type") && st.getBlock() instanceof SlabBlock))
				out.add(prop.getName() + "=" + st.getValue(prop));
		return String.join(",", out);
	}

	/**
	 * Why no spot worked, for the event: no face to click on, none in sight, the click lands elsewhere, or it gives
	 * another facing (and which). Looks at the first few spots, the way the search did.
	 */
	private static String whyNoAim(LocalPlayer p, Level level, List<BlockPos> cells, Job j) {
		BlockPos target = j.pos();
		ItemStack stack = new ItemStack(itemFor(j));
		double range = p.blockInteractionRange() - 0.1;
		int faces = 0, seen = 0, elsewhere = 0, wrong = 0; String got = "";
		for (Direction d : Direction.values()) {
			BlockState ns = level.getBlockState(target.relative(d));
			if (!ns.isAir() && !ns.canBeReplaced() && !ns.getShape(level, target.relative(d)).isEmpty()) faces++;
		}
		if (faces == 0) return "no face to click against";
		if (cells.isEmpty()) return "no place to stand within reach";
		int tried = 0;
		for (BlockPos cell : cells) {
			if (tried++ >= 12) break;
			Vec3 eye = eyeAt(p, level, cell);
			for (Direction d : Direction.values()) {
				BlockPos n = target.relative(d);
				BlockState ns = level.getBlockState(n);
				if (ns.isAir() || ns.canBeReplaced()) continue;
				VoxelShape shape = ns.getShape(level, n);
				if (shape.isEmpty()) continue;
				for (Vec3 point : facePoints(eye, n, d.getOpposite(), shape.bounds())) {
					if (eye.distanceTo(point) > range || !visible(p, eye, point, n, d.getOpposite())) continue;
					seen++;
					float[] rot = rotation(eye, point);
					Placing sim = simulate(p, stack, new BlockHitResult(point, d.getOpposite(), n, false), rot[0], rot[1]);
					if (sim == null || !sim.pos().equals(target)) elsewhere++;
					else if (!acceptable(j.want(), sim.state())) { wrong++; got = orientation(sim.state()); }
				}
			}
		}
		if (seen == 0) return "no face in sight";
		if (wrong > 0) return "wrong state (got " + got + ")";
		return "lands in another place";
	}

	/** Walking cost (a step is 10) of every block that a standing spot's eye is below the block to place. */
	private static final int BELOW_COST = 25;

	/** Degrees of the order rules that one block of walking is worth when choosing where to go next. */
	private static final double WALK_DEGREES = 8;

	/** How much the order rules count within reach, next to the turn. */
	private static final double IN_REACH_ORDER = 0.4;

	/** Degrees of turning that one layer higher is worth when choosing the next block within reach. */
	private static final double LAYER_TURN = 90;

	/** Checking whether a block shuts others out takes a search of the walkable area; this many per choice at most. */
	private static final int MAX_CLOSE_CHECKS = 10;

	/**
	 * How far the pathfinder goes, and the cells it may take for that. It stopped at about 48 blocks, so on a field
	 * of 64 wide the builder found no way back to its chest from the far side.
	 */
	static final int NAV_RANGE = 128, FAR_NODES = 60_000;

	/** Only a block you bump into can shut anything off; crops, torches and signs need no check. */
	private static boolean shuts(Level level, Job j) { return !j.want().getCollisionShape(level, j.pos()).isEmpty(); }

	private static double priority(Job j) { return order == null ? 0 : BuildOrder.priority(order, j.pos(), j.want()); }

	/** Would the block end up where your own body is? */
	private static boolean standsInTheWay(LocalPlayer p, BlockPos pos) { return p.getBoundingBox().intersects(new AABB(pos)); }

	private static AABB bodyAt(BlockPos cell) { return new AABB(cell.getX() + 0.2, cell.getY(), cell.getZ() + 0.2, cell.getX() + 0.8, cell.getY() + 1.8, cell.getZ() + 0.8); }

	/**
	 * Walk to the nearest spot from where one of the lowest remaining blocks can really be placed. We check that
	 * with the same click simulation, from the eye height of that spot: otherwise he walks to a place from where the
	 * stairs would face the wrong way. The spots include the ones only scaffold reaches. Where no spot works, the
	 * block gets a support to click on.
	 */
	private static boolean planWalkToPlace(LocalPlayer p, AfkConfig c, List<Job> carried, long now, boolean strict) {
		Level level = p.level();
		// with scaffolding at hand, height comes from a tower; dirt pillars and bridges were left all over the build
		boolean towers = c.buildUseScaffolding && Lumberjack.countInInventory(p, Items.SCAFFOLDING) > 0;
		Nav nav = Nav.explore(p, 6000, c.buildUseScaffold && !towers ? fillerCount(p, c) : 0);
		double range = p.blockInteractionRange() - 0.2;
		int low = carried.stream().mapToInt(j -> j.pos().getY()).min().orElse(0);
		List<Job> layer = new ArrayList<>();
		for (Job j : carried) if (j.pos().getY() <= low + 1) layer.add(j);
		// the order rules first, walking distance second (a block is worth WALK_DEGREES per block you walk)
		Map<Job, Double> key = new HashMap<>();
		for (Job j : layer) key.put(j, priority(j) + Math.sqrt(j.pos().distSqr(p.blockPosition())) * WALK_DEGREES);
		layer.sort(Comparator.comparingDouble(key::get));
		int checks = 0;
		List<Job> picked = new ArrayList<>(), waiting = new ArrayList<>();
		for (Job j : layer) {
			if (strict && order != null && shuts(level, j) && checks < MAX_CLOSE_CHECKS) { checks++; if (BuildOrder.closesOff(order, j.pos())) continue; }
			// Nothing to click on yet, but a neighbour of the build still comes: that one first, as a player would.
			// Giving it a support right away grew pillars of dirt inside the build.
			if (!hasFace(level, j) && hasComingNeighbour(j)) { waiting.add(j); continue; }
			picked.add(j);
			if (picked.size() >= 6) break;
		}
		// nearby first: almost always enough, and cheap
		for (Job j : picked) {
			BlockPos spot = findSpot(p, level, nav, j, range, null, new ArrayList<>());
			if (spot != null) { steps.add(new Walk(spot, nav.route(spot))); return true; }
		}
		// nothing nearby: look as far as the pathfinder goes before a block is given up
		Nav far = picked.isEmpty() ? nav : Nav.far(p, c.buildUseScaffold && !towers ? fillerCount(p, c) : 0);
		for (Job j : picked) {
			List<BlockPos> cells = new ArrayList<>();
			BlockPos best = findSpot(p, level, far, j, range, null, cells);
			boolean near = !cells.isEmpty();
			if (best != null) { steps.add(new Walk(best, far.route(best))); return true; }
			// no spot from the ground: a scaffolding tower to stand on
			if (planTower(p, c, j, far, now)) return true;
			// a block beside it first (its own from the build, or a helper) that gives the face it needs
			if (planHelper(p, c, j, far, range, now)) return true;
			// nor that: stand where it works, breaking what is in the way there for now
			if (planClearing(p, c, j, far, now)) return true;
			// close enough but no face works: give it something to click on (a support under a support too, within reason)
			if (near && c.buildUseScaffold && supports.size() < 12 && addSupport(p, c, j)) return true;
			// nowhere to place this one from: leave it for two minutes, the rest goes on
			skipped.put(j.pos(), now + 2 * 60_000L);
			Exchange.emit("build", "action", "no spot to place", "block", j.pos().toShortString(), "what", Lumberjack.blockId(j.want().getBlock()).replace("minecraft:", ""),
				"wants", orientation(j.want()), "reason", whyNoAim(p, level, cells, j));
		}
		// only when nothing else can go: a support for the first block that waits for a neighbour
		if (!strict && c.buildUseScaffold && supports.size() < 12)
			for (Job j : waiting) if (addSupport(p, c, j)) return true;
		return false;
	}

	/**
	 * The best spot to walk to from where this block can really be placed, or null; {@code cells} gets every spot
	 * in reach. Not a spot whose body would take the cell {@code avoid}.
	 */
	private static BlockPos findSpot(LocalPlayer p, Level level, Nav nav, Job j, double range, BlockPos avoid, List<BlockPos> cells) {
		ItemStack stack = new ItemStack(itemFor(j));
		Vec3 bc = Vec3.atCenterOf(j.pos());
		// Walking distance alone put the first thirty spots all on the ground below a high block, from where no
		// face shows; the spots up the stairs never got a turn and he said he could not reach it. So first the
		// spots whose eye is at the height of the block, then walking distance.
		// Spots where a block still comes count too, at a price: stairs, gates and observers take their facing
		// from where you stand, and their one right side was often such a spot, so they were never placed.
		List<BlockPos> found = new ArrayList<>();
		for (BlockPos cell : nav.order) if (reachesFrom(p, level, nav.start, cell, j, bc, range)) found.add(cell);
		for (BlockPos cell : nav.future) if (reachesFrom(p, level, nav.start, cell, j, bc, range)) found.add(cell);
		if (avoid != null) found.removeIf(cell -> new AABB(avoid).intersects(bodyAt(cell)));
		found.sort(Comparator.comparingInt(cell -> nav.cost.get(cell) + (nav.future.contains(cell) ? FUTURE_COST : 0)
			+ BELOW_COST * Math.max(0, j.pos().getY() - cell.getY() - 1)));
		cells.addAll(bySide(p, level, found, bc));
		int sims = 0, hits = 0, bestScore = 0; BlockPos best = null;
		for (BlockPos cell : cells) {
			if (sims++ > SPOTS_TRIED) break;
			if (findAim(p, eyeAt(p, level, cell), j.pos(), j.want(), stack) == null) continue;
			if (nav.route(cell) == null) continue;
			// from above (BuildOrder rule 4): of the first few spots that work, one on top of the build wins
			int score = nav.cost.get(cell) + (nav.future.contains(cell) ? FUTURE_COST : 0) - BuildOrder.standBonus(cell, j.pos());
			if (best == null || score < bestScore) { best = cell; bestScore = score; }
			if (++hits >= 4) break;
		}
		return best;
	}

	/** Blocks we already looked for a helper beside, with when. */
	private static final Map<BlockPos, Long> helpedFor = new HashMap<>();

	/**
	 * A block that no spot can place right, because the face it needs is not there: a top stair wants the upper half
	 * of a side or the underside of the block above, a gate or observer a face on its one right side. For each empty
	 * cell beside it we try, on the client only and undone at once, what a block there would change. If that makes a
	 * spot work, the block of the build that belongs there goes first, or else a helper block that goes again after.
	 */
	private static boolean planHelper(LocalPlayer p, AfkConfig c, Job j, Nav nav, double range, long now) {
		if (order == null) return false;
		Long last = helpedFor.get(j.pos());
		if (last != null && now - last < 5 * 60_000L) return false;
		Level level = p.level();
		Level schem = Schematic.world();
		Item f = filler(p, c);
		for (Direction d : Direction.values()) {
			BlockPos n = j.pos().relative(d);
			BlockState have = level.getBlockState(n);
			if (!(have.isAir() || have.canBeReplaced()) || standsInTheWay(p, n) || supports.containsKey(n)) continue;
			boolean fromBuild = order.remaining.contains(n) && schem != null;
			BlockState trial;
			if (fromBuild) {
				trial = schem.getBlockState(n);
				if (trial.hasBlockEntity() || trial.getCollisionShape(level, n).isEmpty() || !trial.getFluidState().isEmpty()) continue;
			} else if (f != null && canScaffold(level, n)) trial = ((BlockItem) f).getBlock().defaultBlockState();
			else continue;
			BlockPos spot = tryWith(level, n, trial, () -> findSpot(p, level, nav, j, range, n, new ArrayList<>()));
			if (spot == null) continue;
			helpedFor.put(j.pos(), now);
			if (fromBuild) {
				Job first = new Job(n, trial, false);
				if (Lumberjack.countInInventory(p, itemFor(first)) == 0) continue;
				Exchange.emit("build", "action", "the neighbour first", "for", j.pos().toShortString(), "neighbour", n.toShortString());
				Job here = bestPlaceInReach(p, List.of(first), false);
				if (here != null) { steps.add(new Place(here)); return true; }
				BlockPos s2 = findSpot(p, level, nav, first, range, null, new ArrayList<>());
				if (s2 != null) { steps.add(new Walk(s2, nav.route(s2))); return true; }
				continue;
			}
			supports.put(n, trial);
			supportsFor.merge(j.pos(), 1, Integer::sum);
			supportOf.put(n, j.pos());
			Exchange.emit("build", "action", "helper block", "for", j.pos().toShortString(), "up", n.toShortString());
			return true;
		}
		return false;
	}

	/**
	 * Run a check as if this block stood here: set on the client only, without updates or redraw, and put back
	 * right after. Nothing of it reaches the server; the raycasts and vanilla's placement logic simply see it.
	 */
	private static <T> T tryWith(Level level, BlockPos pos, BlockState st, java.util.function.Supplier<T> check) {
		BlockState old = level.getBlockState(pos);
		int flags = Block.UPDATE_INVISIBLE | Block.UPDATE_KNOWN_SHAPE;
		level.setBlock(pos, st, flags);
		try { return check.get(); } finally { level.setBlock(pos, old, flags); }
	}

	/** The supports that were for this block, and the ones under those: the block stands now, they can go. */
	private static void retireSupportsOf(BlockPos block) {
		ArrayDeque<BlockPos> todo = new ArrayDeque<>();
		todo.add(block);
		while (!todo.isEmpty()) {
			BlockPos b = todo.poll();
			for (var e : supportOf.entrySet()) if (e.getValue().equals(b) && retire.add(e.getKey())) todo.add(e.getKey());
		}
	}

	// ---------- breaking outside the build for a moment ----------
	private static final int CLEAR_MAX = 4;
	/** Blocks we already broke something for, with when. */
	private static final Map<BlockPos, Long> clearedFor = new HashMap<>();

	/**
	 * The cells along the line from the eye to a point on the face of {@code pos} that are in the way, not counting
	 * {@code air}. Null if one of them may not be broken, or if there are more than {@code max}.
	 */
	private static List<BlockPos> blockers(Level level, Vec3 eye, Vec3 point, BlockPos pos, Set<BlockPos> air, int max) {
		List<BlockPos> out = new ArrayList<>();
		Vec3 d = point.subtract(eye);
		double len = d.length();
		Vec3 step = d.normalize();
		for (double t = 0; t < len - 0.03; t += 0.05) {
			BlockPos cell = BlockPos.containing(eye.add(step.scale(t)));
			if (cell.equals(pos) || air.contains(cell) || out.contains(cell)) continue;
			VoxelShape shape = level.getBlockState(cell).getShape(level, cell);
			if (shape.isEmpty() || shape.clip(eye, point, cell) == null) continue;
			if (out.size() >= max || !mayClear(level, cell)) return null;
			out.add(cell);
		}
		return out;
	}

	/**
	 * May we break this for a moment? Only outside the build, nothing that holds things or cannot be broken,
	 * nothing beside lava or water (it would run in), and nothing that sand or gravel rests on.
	 */
	private static boolean mayClear(Level level, BlockPos cell) {
		BlockState st = level.getBlockState(cell);
		if (!breakable(level, cell, st) || lavaNext(level, cell)) return false;
		// inside the build only a plain finished block (no facing): it simply comes back as missing and is rebuilt
		if (placement.contains(cell, 0) && !plainBuildBlock(level, cell, st)) return false;
		if (holdsBackFluid(level, cell)) return false;
		return !(level.getBlockState(cell.above()).getBlock() instanceof net.minecraft.world.level.block.FallingBlock);
	}

	private static boolean plainBuildBlock(Level level, BlockPos cell, BlockState st) {
		Level schem = Schematic.world();
		return schem != null && matches(schem.getBlockState(cell), st) && orientation(st).isEmpty()
			&& st.isCollisionShapeFullBlock(level, cell) && !st.hasBlockEntity();
	}

	/** What goes back where we broke something: what you get from breaking it (grass gives dirt, stone cobblestone). */
	private static BlockState restoreState(String id) {
		Block b = Lumberjack.blockOf(id);
		if (b == null) return null;
		if (b == Blocks.GRASS_BLOCK || b == Blocks.PODZOL || b == Blocks.MYCELIUM || b == Blocks.DIRT_PATH || b == Blocks.FARMLAND) b = Blocks.DIRT;
		else if (b == Blocks.STONE) b = Blocks.COBBLESTONE;
		else if (b == Blocks.DEEPSLATE) b = Blocks.COBBLED_DEEPSLATE;
		return b.defaultBlockState();
	}

	/**
	 * A spot outside the build from where the block can be placed once a few blocks are out of the way: the spot
	 * itself, the room for your head, and what stands in the line of sight. Stairs and observers that need their
	 * one right side, where terrain or a wall of something else stands, got skipped otherwise. What is broken is
	 * written down (build.json) and put back after.
	 */
	private static boolean planClearing(LocalPlayer p, AfkConfig c, Job j, Nav nav, long now) {
		Level level = p.level();
		// broke, could not place after all, put back, and again twenty seconds later: once per ten minutes is enough
		Long lastTry = clearedFor.get(j.pos());
		if (lastTry != null && now - lastTry < 10 * 60_000L) return false;
		BlockPos t = j.pos();
		double range = p.blockInteractionRange() - 0.1;
		ItemStack stack = new ItemStack(itemFor(j));
		double eyeHeight = p.getEyeHeight(net.minecraft.world.entity.Pose.STANDING);
		double crouchEye = p.getEyeHeight(net.minecraft.world.entity.Pose.CROUCHING);
		List<BlockPos> bestClear = null; BlockPos bestSpot = null, bestFrom = null; int bestCost = Integer.MAX_VALUE;
		for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) for (int dy = -2; dy <= 1; dy++) {
			BlockPos s = t.offset(dx, dy, dz);
			if (!solid(level, s.below()) || new AABB(t).intersects(bodyAt(s))) continue;
			Vec3 eye = new Vec3(s.getX() + 0.5, s.getY() + eyeHeight, s.getZ() + 0.5);
			if (eye.distanceTo(Vec3.atCenterOf(t)) > range + 0.8) continue;
			// the way in: a spot you can walk to right beside it, at the same height
			BlockPos from = null;
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos a = s.relative(d);
				if (nav.returnable.contains(a) && nav.cost.containsKey(a) && (from == null || nav.cost.get(a) < nav.cost.get(from))) from = a;
			}
			if (from == null) continue;
			List<BlockPos> clear = new ArrayList<>();
			boolean ok = true;
			for (BlockPos body : List.of(s, s.above())) {
				if (passable(level, body, false)) continue;
				if (!mayClear(level, body)) { ok = false; break; }
				clear.add(body);
			}
			if (!ok) continue;
			// from where you will really stand: at an edge crouched, and that eye is lower; it has to work for both
			List<BlockPos> sight = sightThrough(p, level, eye, j, stack, range, new HashSet<>(clear));
			if (sight == null) continue;
			clear.addAll(sight);
			Vec3 low = new Vec3(eye.x, s.getY() + crouchEye, eye.z);
			List<BlockPos> sightLow = sightThrough(p, level, low, j, stack, range, new HashSet<>(clear));
			if (sightLow == null) continue;
			for (BlockPos b : sightLow) if (!clear.contains(b)) clear.add(b);
			if (clear.isEmpty() || clear.size() > CLEAR_MAX) continue;   // nothing to break: the normal search had it
			int cost = nav.cost.get(from) + 40 * clear.size();
			if (cost < bestCost) { bestCost = cost; bestClear = clear; bestSpot = s; bestFrom = from; }
		}
		if (bestClear == null) return false;
		clearedFor.put(j.pos(), now);
		String d = dimension(p);
		// outside the build it is written down to put back; inside, the schematic itself brings it back
		for (BlockPos cell : bestClear)
			if (!placement.contains(cell, 0)) BuildStore.get().temp.add(new BuildStore.Temp(cell, d, Lumberjack.blockId(level.getBlockState(cell).getBlock())));
		BuildStore.save();
		Exchange.emit("build", "action", "gone for now", "for", t.toShortString(), "spot", bestSpot.toShortString(), "blocks", bestClear.stream().map(BlockPos::toShortString).toList());
		if (!bestFrom.equals(nav.start)) steps.add(new Walk(bestFrom, nav.route(bestFrom)));
		// first the spot and the room for your head, top down, from beside it; then in, and the rest from there
		for (BlockPos cell : List.of(bestSpot.above(), bestSpot)) if (bestClear.contains(cell)) steps.add(new Dig(cell));
		steps.add(new Walk(bestSpot, null));
		for (BlockPos cell : bestClear) if (!cell.equals(bestSpot) && !cell.equals(bestSpot.above())) steps.add(new Dig(cell));
		steps.add(new Place(j));
		steps.add(new Pickup());
		return true;
	}

	/** The fewest blocks to break so a click from this eye places the block right, or null if none will do. */
	private static List<BlockPos> sightThrough(LocalPlayer p, Level level, Vec3 eye, Job j, ItemStack stack, double range, Set<BlockPos> air) {
		BlockPos t = j.pos();
		List<BlockPos> best = null;
		for (Direction dir : Direction.values()) {
			BlockPos n = t.relative(dir);
			BlockState ns = level.getBlockState(n);
			if (ns.isAir() || ns.canBeReplaced() || air.contains(n)) continue;
			VoxelShape shape = ns.getShape(level, n);
			if (shape.isEmpty()) continue;
			Direction face = dir.getOpposite();
			for (Vec3 point : facePoints(eye, n, face, shape.bounds())) {
				if (eye.distanceTo(point) > range) continue;
				float[] rot = rotation(eye, point);
				Placing sim = simulate(p, stack, new BlockHitResult(point, face, n, false), rot[0], rot[1]);
				if (sim == null || !sim.pos().equals(t) || !acceptable(j.want(), sim.state())) continue;
				List<BlockPos> in = blockers(level, eye, point, n, air, CLEAR_MAX);
				if (in != null && (best == null || in.size() < best.size())) best = in;
				if (best != null && best.isEmpty()) return best;
			}
		}
		return best;
	}

	/** Blocks we broke outside the build that can go back now: not where you stand, and only once nothing waits on them. */
	private static List<Job> restoreJobs(LocalPlayer p) {
		List<Job> out = new ArrayList<>();
		Level level = p.level();
		String d = dimension(p);
		boolean changed = BuildStore.get().temp.removeIf(tp -> {
			if (!tp.dim.equals(d) || !level.isLoaded(tp.pos())) return false;
			BlockState want = restoreState(tp.block);
			BlockState have = level.getBlockState(tp.pos());
			return want == null || !(have.isAir() || have.canBeReplaced()) || matches(want, have);
		});
		if (changed) BuildStore.save();
		for (var tp : BuildStore.get().temp) {
			if (!tp.dim.equals(d)) continue;
			BlockState want = restoreState(tp.block);
			if (want != null && !standsInTheWay(p, tp.pos())) out.add(new Job(tp.pos(), want, false));
		}
		return out;
	}

	// ---------- refilling buckets ----------
	/** How far from the build a water source may be, and how many buckets to carry when there is one. */
	private static final int REFILL_RANGE = 16, BUCKETS_CARRIED = 3;

	/**
	 * A water source that comes back after you take it, by vanilla's own rule: two source blocks beside it, and under
	 * it a solid block or another source. Only plain water, never a waterlogged block, never the build's own water.
	 * Taking any other source would remove water for good (somebody's farm, a single block of water).
	 */
	private static boolean refills(Level level, BlockPos pos) {
		if (!plainSource(level.getBlockState(pos)) || placement.contains(pos, 0)) return false;
		int beside = 0;
		for (Direction d : Direction.Plane.HORIZONTAL) if (plainSource(level.getBlockState(pos.relative(d)))) beside++;
		if (beside < 2) return false;
		BlockState below = level.getBlockState(pos.below());
		return plainSource(below) || below.isCollisionShapeFullBlock(level, pos.below());
	}

	private static boolean plainSource(BlockState st) { return st.getBlock() == Blocks.WATER && st.getFluidState().isSource(); }

	/** The refilling sources around the build (or, for a very large build, around you), looked up at most every fifteen seconds. */
	private static List<BlockPos> refillSources(LocalPlayer p, long now) {
		if (now - sourcesAt < 15_000) return waterSources;
		sourcesAt = now;
		Level level = p.level();
		BlockPos lo = placement.min().offset(-REFILL_RANGE, -6, -REFILL_RANGE), hi = placement.max().offset(REFILL_RANGE, 6, REFILL_RANGE);
		long volume = (long) (hi.getX() - lo.getX() + 1) * (hi.getY() - lo.getY() + 1) * (hi.getZ() - lo.getZ() + 1);
		if (volume > 300_000) { lo = p.blockPosition().offset(-REFILL_RANGE, -6, -REFILL_RANGE); hi = p.blockPosition().offset(REFILL_RANGE, 6, REFILL_RANGE); }
		List<BlockPos> out = new ArrayList<>();
		for (BlockPos q : BlockPos.betweenClosed(lo, hi)) if (level.isLoaded(q) && refills(level, q)) out.add(q.immutable());
		waterSources = out;
		return out;
	}

	/** May buckets be refilled now: not switched off for this run, not just found out of reach, and a source exists. */
	private static boolean refillPossible(LocalPlayer p, long now) {
		return !refillOff && now >= noRefillUntil && !refillSources(p, now).isEmpty();
	}

	private static final double[][] RAY_SHIFTS = { {0, 0, 0}, {0.12, 0, 0}, {-0.12, 0, 0}, {0, 0, 0.12}, {0, 0, -0.12}, {0, 0.1, 0}, {0, -0.1, 0} };

	/**
	 * A bucket does not click the block the client points at: the server finds it again with a ray of its own, from
	 * where it has you. A hair's difference then lands on another block (a wall behind a leaf seen at a grazing
	 * angle), and the water goes in front of that one. So an aim for a bucket only counts when the same block (and
	 * face, if given) is hit from an eye shifted a little to every side.
	 */
	private static boolean bucketAimHolds(LocalPlayer p, Vec3 eye, Vec3 point, BlockPos expect, Direction face, ClipContext.Fluid fluid) {
		Vec3 dir = point.subtract(eye).normalize();
		double range = p.blockInteractionRange();
		for (double[] o : RAY_SHIFTS) {
			Vec3 from = eye.add(o[0], o[1], o[2]);
			BlockHitResult r = p.level().clip(new ClipContext(from, from.add(dir.scale(range)), ClipContext.Block.OUTLINE, fluid, p));
			if (r.getType() != HitResult.Type.BLOCK || !r.getBlockPos().equals(expect) || (face != null && r.getDirection() != face)) return false;
		}
		return true;
	}

	/** The first water source (or block) along this line of sight within reach, the way a bucket finds it. */
	private static BlockHitResult fluidAhead(LocalPlayer p, Vec3 eye, Vec3 dir) {
		BlockHitResult r = p.level().clip(new ClipContext(eye, eye.add(dir.scale(p.blockInteractionRange())), ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, p));
		return r.getType() == HitResult.Type.BLOCK ? r : null;
	}

	private static final double[][] SCOOP_POINTS = { {0.5, 0.5}, {0.3, 0.3}, {0.7, 0.7}, {0.3, 0.7}, {0.7, 0.3} };

	/** A point on the surface of this source to look at so the bucket takes exactly this one, or null from this eye. */
	private static Vec3 scoopPoint(LocalPlayer p, Vec3 eye, BlockPos source) {
		Level level = p.level();
		double top = source.getY() + level.getFluidState(source).getHeight(level, source) - 0.03;
		Vec3 best = null; double bestTurn = Double.MAX_VALUE;
		for (double[] xz : SCOOP_POINTS) {
			Vec3 point = new Vec3(source.getX() + xz[0], top, source.getZ() + xz[1]);
			if (eye.distanceTo(point) > p.blockInteractionRange() - 0.4) continue;
			BlockHitResult hit = fluidAhead(p, eye, point.subtract(eye).normalize());
			if (hit == null || !hit.getBlockPos().equals(source)) continue;
			if (!bucketAimHolds(p, eye, point, source, null, ClipContext.Fluid.SOURCE_ONLY)) continue;
			double turn = turn(p, eye, point);
			if (turn < bestTurn) { bestTurn = turn; best = point; }
		}
		return best;
	}

	/**
	 * Out of water with an empty bucket: go to the nearest refilling source you can stand beside and fill up there.
	 * False (and a minute's rest, the chests then count again) if there is none or none can be reached.
	 */
	private static boolean planRefill(LocalPlayer p, int wanted, long now) {
		if (!refillPossible(p, now)) {
			if (!refillOff && now >= noRefillUntil && !toldNoSource) {
				toldNoSource = true;
				Exchange.emit("build", "action", "no water source", "inside", REFILL_RANGE);
			}
			return false;
		}
		Level level = p.level();
		badSources.values().removeIf(t -> t < now);
		List<BlockPos> sources = new ArrayList<>(refillSources(p, now));
		sources.removeIf(badSources::containsKey);
		sources.sort(Comparator.comparingDouble(q -> q.distSqr(p.blockPosition())));
		int target = Math.min(wanted, Lumberjack.countInInventory(p, Items.BUCKET));
		Vec3 here = p.getEyePosition();
		for (int i = 0; i < Math.min(24, sources.size()); i++)
			if (scoopPoint(p, here, sources.get(i)) != null) { steps.add(new Refill(sources.get(i), target)); return true; }
		// The candidates spread out: with thousands of source blocks around (water above or beside the build) the
		// twelve nearest were twelve cells of one body of water out of reach, and the pool laid out for it was never
		// tried from afar; every miss then cost a round of the chests. The source that worked before is always one
		// of them, but no longer the first: with a new pool on his own floor he kept climbing the stairs to the old
		// one a floor up. The shortest walk wins, not the shortest line: that old pool was right above his head.
		List<BlockPos> tryFirst = new ArrayList<>();
		for (BlockPos src : sources) {
			if (tryFirst.size() >= 12) break;
			boolean apart = true;
			for (BlockPos chosen : tryFirst) if (chosen.distSqr(src) < 36) { apart = false; break; }
			if (apart) tryFirst.add(src);
		}
		if (goodSource != null && !tryFirst.contains(goodSource) && !badSources.containsKey(goodSource) && level.isLoaded(goodSource) && refills(level, goodSource)) tryFirst.add(goodSource);
		double eyeH = p.getEyeHeight(net.minecraft.world.entity.Pose.STANDING);
		for (int look = 0; look < 2; look++) {
			Nav nav = look == 0 ? Nav.explore(p, 8000, 0) : Nav.far(p, 0);
			BlockPos bestSrc = null, bestCell = null; int bestCost = Integer.MAX_VALUE;
			for (BlockPos src : tryFirst) {
				for (BlockPos cell : nav.all) {
					if (nav.cost.get(cell) >= bestCost) break;   // by cost, nearest first: nothing cheaper follows
					if (cell.distSqr(src) > 30) continue;
					Vec3 eye = new Vec3(cell.getX() + 0.5, feetAt(level, cell) + eyeH, cell.getZ() + 0.5);
					if (scoopPoint(p, eye, src) == null) continue;
					bestSrc = src; bestCell = cell; bestCost = nav.cost.get(cell);
					break;
				}
			}
			if (bestSrc == null) continue;
			steps.add(new Walk(bestCell, nav.route(bestCell)));
			steps.add(new Refill(bestSrc, target));
			return true;
		}
		noRefillUntil = now + 60_000;
		Exchange.emit("build", "action", "water source unreachable", "sources", sources.size());
		return false;
	}

	/** Walk to spilled water and scoop it up; false if none of it is within reach of a spot you can get to. */
	private static boolean planSpill(LocalPlayer p, long now) {
		Level level = p.level();
		List<BlockPos> spill = new ArrayList<>();
		for (BlockPos q : scan.spill) if (plainSource(level.getBlockState(q)) && !skipped.containsKey(q)) spill.add(q);
		if (spill.isEmpty()) return false;
		spill.sort(Comparator.comparingDouble(q -> q.distSqr(p.blockPosition())));
		if (spill.size() > 6) spill = spill.subList(0, 6);
		Vec3 here = p.getEyePosition();
		for (BlockPos q : spill) if (scoopPoint(p, here, q) != null) { steps.add(new Refill(q, 1, true)); return true; }
		double eyeH = p.getEyeHeight(net.minecraft.world.entity.Pose.STANDING);
		for (int look = 0; look < 2; look++) {
			Nav nav = look == 0 ? Nav.explore(p, 6000, 0) : Nav.far(p, 0);
			for (BlockPos q : spill) {
				for (BlockPos cell : nav.all) {
					if (cell.distSqr(q) > 30) continue;
					Vec3 eye = new Vec3(cell.getX() + 0.5, feetAt(level, cell) + eyeH, cell.getZ() + 0.5);
					if (scoopPoint(p, eye, q) == null) continue;
					Route route = nav.route(cell);
					if (route == null) continue;
					steps.add(new Walk(cell, route));
					steps.add(new Refill(q, 1, true));
					Exchange.emit("build", "action", "spilled water", "block", q.toShortString(), "count", scan.spill.size());
					return true;
				}
			}
		}
		for (BlockPos q : spill) skipped.put(q, now + 2 * 60_000L);
		return false;
	}

	/**
	 * Fill empty buckets at a source that refills itself: look at its surface, use the bucket, wait until the source
	 * is back, and again until there is enough. If the source does not come back (the server may have that switched
	 * off), the water goes back where it came from and refilling is off for this run.
	 */
	static class Refill extends Step {
		final BlockPos source; final int target;
		/** Spilled water: taken up for good, so no waiting for it to come back and no check that it would. */
		final boolean spill;
		Vec3 point = null; boolean looked = false, clicked = false; int wait = 0, misses = 0, scoops = 0, before = 0, moving = 0; long scoopedAt = 0;
		Refill(BlockPos source, int target) { this(source, target, false); }
		Refill(BlockPos source, int target, boolean spill) { this.source = source; this.target = Math.max(1, target); this.spill = spill; }
		int tick(LocalPlayer p, Minecraft mc, AfkConfig c) {
			if (wait > 0) { wait--; return BUSY; }
			Level level = p.level();
			long now = System.currentTimeMillis();
			int water = Lumberjack.countInInventory(p, Items.WATER_BUCKET), empty = Lumberjack.countInInventory(p, Items.BUCKET);
			if (scoopedAt > 0) {
				if (plainSource(level.getBlockState(source))) { goodSource = source; scoopedAt = 0; point = null; looked = false; wait = R.nextInt(4, 9); return BUSY; }
				if (now - scoopedAt < 6000) return BUSY;   // half a second normally; a slow server gets time
				// it does not come back here: return the water and stop scooping, so nothing is taken for good
				refillOff = true;
				Exchange.emit("build", "action", "source does not refill", "source", source.toShortString());
				steps.addFirst(new Place(new Job(source, Blocks.WATER.defaultBlockState(), false)));
				return DONE;
			}
			if (clicked) {
				clicked = false; lastClick = ClickResult.NONE;
				if (water > before) {
					scoops++;
					if (spill) { Exchange.emit("build", "action", "water scooped up", "block", source.toShortString()); return DONE; }
					scoopedAt = now; wait = 3;
					return BUSY;
				}
				if (++misses > 8) return FAILED;
				point = null; looked = false; wait = 3;
				return BUSY;
			}
			if (scoops >= target || empty == 0) {
				Exchange.emit("build", "action", "topped up", "source", source.toShortString(), "buckets", scoops);
				return DONE;
			}
			if (spill) { if (!plainSource(level.getBlockState(source))) return DONE; }
			else if (!refills(level, source)) return FAILED;
			int slot = Lumberjack.hotbarSlot(p, Items.BUCKET);
			if (slot < 0) {
				if (++moving > 3) return FAILED;
				toHotbar(p, mc, sourceSlot(p, Items.BUCKET)); wait = 3; return BUSY;
			}
			if (p.getInventory().getSelectedSlot() != slot) { p.getInventory().setSelectedSlot(slot); wait = 2; return BUSY; }
			// from a stack of empty buckets the full one goes to a free slot; with none it would drop on the ground
			if (p.getMainHandItem().getCount() > 1 && Lumberjack.freeSlots(p) == 0) return FAILED;
			crouch(false);
			if (point == null) {
				point = scoopPoint(p, p.getEyePosition(), source);
				if (point == null) return FAILED;
				looked = false;
			}
			if (!looked) { looked = true; look.startByAngle(p, point, 0.1f); }
			if (look.busy()) { look.tick(p); return BUSY; }
			BlockHitResult ahead = fluidAhead(p, p.getEyePosition(), p.getViewVector(1f));
			if (ahead != null && ahead.getBlockPos().equals(source)) {
				if (now - lastClickAt < 350 + R.nextLong(0, 250)) return BUSY;
				before = water; pendingScoop = source; clicked = true; lastClick = ClickResult.NONE;
				return BUSY;
			}
			if (++misses > 60) return FAILED;
			Lumberjack.aim(p, point);
			return elapsed() > 40_000 ? FAILED : BUSY;
		}
		void failed(LocalPlayer p, AfkConfig c) {
			if (spill) { skipped.put(source, System.currentTimeMillis() + 60_000L); return; }
			badSources.put(source, System.currentTimeMillis() + 5 * 60_000L);
			Exchange.emit("build", "action", "topping up failed", "source", source.toShortString());
		}
		String name() { return "refill at " + source.toShortString(); }
		String label() { return String.format(java.util.Locale.ROOT, "filling a bucket at %s", source.toShortString()); }
	}

	// ---------- scaffolding towers ----------
	/** A scaffolding tower: its foot, how many blocks high, and the spot beside it you build it from. */
	record Tower(BlockPos base, int height, BlockPos beside) {
		BlockPos top() { return base.above(height); }
	}

	private static final int TOWER_MAX = 6;
	private static final BlockState SCAFFOLDING = Blocks.SCAFFOLDING.defaultBlockState();

	/**
	 * For a block no spot on the ground reaches: the lowest scaffolding tower beside a spot you can walk to, from
	 * whose top the block can really be placed. Fetches scaffolding first if you carry too little.
	 */
	private static boolean planTower(LocalPlayer p, AfkConfig c, Job j, Nav nav, long now) {
		if (!c.buildUseScaffolding || order == null) return false;
		Level level = p.level();
		ItemStack stack = new ItemStack(itemFor(j));
		double eyeHeight = p.getEyeHeight(net.minecraft.world.entity.Pose.STANDING);
		Tower best = null; int bestCost = Integer.MAX_VALUE;
		for (BlockPos a : nav.order) {
			int ca = nav.cost.get(a);
			if (ca + 15 >= bestCost) break;
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos b = a.relative(d);
				if (b.getY() != a.getY() || !placement.contains(b, 3) || b.distSqr(j.pos()) > 49 || floorY(level, b) != b.getY() || order.remaining.contains(b)) continue;
				for (int h = 1; h <= TOWER_MAX; h++) {
					BlockPos top = b.above(h);
					if (!passable(level, top, false) || !passable(level, top.above(), false) || order.remaining.contains(top) || order.remaining.contains(top.above())) break;
					Vec3 eye = new Vec3(b.getX() + 0.5, top.getY() + eyeHeight, b.getZ() + 0.5);
					if (findAim(p, eye, j.pos(), j.want(), stack) == null) continue;
					int cost = ca + h * 15;
					if (cost < bestCost) { best = new Tower(b, h, a); bestCost = cost; }
					break;
				}
			}
		}
		if (best == null) return false;
		if (Lumberjack.countInInventory(p, Items.SCAFFOLDING) < best.height()) {
			if (noMaterial.containsKey(Items.SCAFFOLDING)) return false;
			return planRestock(p, c, new HashSet<>(Set.of(Items.SCAFFOLDING)), now);
		}
		if (!best.beside().equals(nav.start)) {
			Route route = nav.route(best.beside());
			if (route == null) return false;
			steps.add(new Walk(best.beside(), route));
		}
		steps.add(new BuildTower(best));
		steps.add(new Climb(best));
		Exchange.emit("build", "action", "tower", "foot", best.base().toShortString(), "high", best.height(), "for", j.pos().toShortString());
		return true;
	}

	/** Turn toward a point and walk while roughly facing it. */
	private static void steerTo(LocalPlayer p, Minecraft mc, Vec3 m) {
		double dx = m.x - p.getX(), dz = m.z - p.getZ();
		float delta = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz)) - p.getYRot());
		MouseTurn.toward(p, p.getYRot() + Mth.clamp(delta, -12f, 12f) + (float) R.nextDouble(-0.4, 0.4), p.getXRot() + Mth.clamp(10f - p.getXRot(), -6f, 6f) * 0.3f);
		HeldKeys.set(mc.options.keyUp, Math.abs(delta) < 50);
	}

	/** Hold scaffolding in hand, from the bag if it is not in the hotbar. False while it is still being moved. */
	private static boolean holdScaffolding(LocalPlayer p, Minecraft mc) {
		int slot = Lumberjack.hotbarSlot(p, Items.SCAFFOLDING);
		if (slot < 0) { toHotbar(p, mc, sourceSlot(p, Items.SCAFFOLDING)); return false; }
		if (p.getInventory().getSelectedSlot() != slot) { p.getInventory().setSelectedSlot(slot); return false; }
		return true;
	}

	/**
	 * Put up the tower from beside it: the first scaffolding on the floor, every next one with a click on the side
	 * of the foot, which vanilla puts on top of the column.
	 */
	static class BuildTower extends Step {
		final Tower t; Aim aim = null; boolean looked = false, clicked = false; int wait = 0, misses = 0, attempts = 0;
		BuildTower(Tower t) { this.t = t; }
		int tick(LocalPlayer p, Minecraft mc, AfkConfig c) {
			if (wait > 0) { wait--; return BUSY; }
			Level level = p.level();
			int built = 0;
			while (built < t.height() && level.getBlockState(t.base().above(built)).is(Blocks.SCAFFOLDING)) built++;
			if (built >= t.height()) return DONE;
			BlockPos target = t.base().above(built);
			if (clicked) {
				clicked = false; lastClick = ClickResult.NONE; aim = null; wait = 3;
				if (++attempts > t.height() + 4) return FAILED;
				return BUSY;
			}
			if (!holdScaffolding(p, mc)) { wait = 3; return elapsed() > 8000 ? FAILED : BUSY; }
			crouch(false);
			ItemStack stack = p.getMainHandItem();
			if (aim == null) {
				Vec3 eye = p.getEyePosition();
				if (built == 0) aim = findAim(p, eye, target, SCAFFOLDING, stack);
				else {
					AABB box = new AABB(0, 0, 0, 1, 1, 1);
					for (Direction face : Direction.Plane.HORIZONTAL) {
						List<Aim> on = aimsOnFace(p, eye, p.blockInteractionRange() - 0.1, t.base(), face, box, target, SCAFFOLDING, stack, false);
						if (!on.isEmpty()) { aim = on.get(0); break; }
					}
				}
				if (aim == null) return FAILED;
				looked = false;
			}
			if (!looked) { looked = true; look.startByAngle(p, aim.point(), 0.1f); }
			if (look.busy()) { look.tick(p); return BUSY; }
			if (mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK
				&& b.getBlockPos().equals(aim.hit().getBlockPos()) && b.getDirection() == aim.hit().getDirection()) {
				Placing sim = simulate(p, stack, b, p.getYRot(), p.getXRot());
				if (sim != null && sim.pos().equals(target)) {
					if (System.currentTimeMillis() - lastClickAt < 250 + R.nextLong(0, 150)) return BUSY;
					pending = new Pending(target, sim.state(), null, false, true);
					clicked = true; lastClick = ClickResult.NONE;
					return BUSY;
				}
			}
			if (++misses > 60) return FAILED;
			Lumberjack.aim(p, aim.point());
			return elapsed() > 20_000 ? FAILED : BUSY;
		}
		void failed(LocalPlayer p, AfkConfig c) { steps.clear(); }
		String name() { return "tower at " + t.base().toShortString(); }
		String label() { return String.format(java.util.Locale.ROOT, "building scaffolding at %s", t.base().toShortString()); }
	}

	/**
	 * Walk into the foot of the tower, and only once in the middle hold jump, without walking on, until you are on
	 * top. Jumping while still walking in had him jump beside the tower, and walking on at the top off it.
	 */
	static class Climb extends Step {
		final Tower t; boolean climbing = false, released = false;
		Climb(Tower t) { this.t = t; }
		int tick(LocalPlayer p, Minecraft mc, AfkConfig c) {
			var o = mc.options;
			if (elapsed() > 15_000) { HeldKeys.set(o.keyJump, false); HeldKeys.set(o.keyUp, false); return FAILED; }
			crouch(false);
			Vec3 m = new Vec3(t.base().getX() + 0.5, p.getY(), t.base().getZ() + 0.5);
			double d = Math.hypot(m.x - p.getX(), m.z - p.getZ());
			if (!climbing) {
				if (d > 0.15) { steerTo(p, mc, m); return BUSY; }
				HeldKeys.set(o.keyUp, false);
				climbing = true;
			}
			// feet level with the top: let go at once, and stand still until you land on it
			if (released || p.getY() >= t.top().getY() - 0.05) {
				HeldKeys.set(o.keyJump, false);
				released = true;
				if (p.onGround() && p.getY() >= t.top().getY() - 0.05) { tower = t; return DONE; }
				return BUSY;
			}
			HeldKeys.set(o.keyJump, true);
			return BUSY;
		}
		void failed(LocalPlayer p, AfkConfig c) { steps.clear(); }
		String name() { return "climb at " + t.base().toShortString(); }
		String label() { return "climbing the scaffolding"; }
	}

	/** Crouch down through the tower to the floor, then step out beside it. */
	static class Descend extends Step {
		final Tower t; boolean down = false, centred = false;
		Descend(Tower t) { this.t = t; }
		int tick(LocalPlayer p, Minecraft mc, AfkConfig c) {
			var o = mc.options;
			if (elapsed() > 20_000) { crouch(false); HeldKeys.set(o.keyUp, false); tower = null; return FAILED; }
			if (!centred) {
				// off the middle, crouching lands you on whatever else you half stand on, and you stay up there
				Vec3 m = new Vec3(t.base().getX() + 0.5, p.getY(), t.base().getZ() + 0.5);
				if (Math.hypot(m.x - p.getX(), m.z - p.getZ()) > 0.2 && elapsed() < 3000) { steerTo(p, mc, m); return BUSY; }
				HeldKeys.set(o.keyUp, false);
				centred = true;
			}
			if (!down) {
				crouch(true);
				if (p.onGround() && p.getY() < t.base().getY() + 0.1) { crouch(false); down = true; }
				return BUSY;
			}
			Vec3 m = new Vec3(t.beside().getX() + 0.5, p.getY(), t.beside().getZ() + 0.5);
			double d = Math.hypot(m.x - p.getX(), m.z - p.getZ());
			if (d > 0.3) { steerTo(p, mc, m); return BUSY; }
			HeldKeys.set(o.keyUp, false);
			tower = null;
			return DONE;
		}
		String name() { return "descend at " + t.base().toShortString(); }
		String label() { return "climbing down the scaffolding"; }
	}

	/**
	 * How long a block that failed waits: briefly the first times (often only a block was in the way for a moment),
	 * longer when it keeps failing. It used to be a minute at once, up to ten.
	 */
	private static long skipFor(int failures) {
		return failures <= 1 ? 20_000L : failures == 2 ? 60_000L : Math.min(10, failures) * 60_000L;
	}

	private static void noteOnce(String key, Object... kv) {
		if (noted.add(key)) Exchange.emit("build", kv);
	}

	/**
	 * Rules of the world a block has to wait for: sand and gravel fall without something under them, concrete
	 * powder hardens beside water, TNT goes off when power can reach it.
	 */
	private static boolean placeableNow(Level level, BlockPos pos, BlockState want) {
		Block b = want.getBlock();
		// seeds wait for the hoe instead of counting as "no spot to place" and waiting minutes after it
		if (Finisher.needsFarmland(want) && !level.getBlockState(pos.below()).is(Blocks.FARMLAND)) return false;
		if (b instanceof FallingBlock && !solid(level, pos.below())) {
			Level schem = Schematic.world();
			if (schem != null && schem.getBlockState(pos.below()).isAir())
				noteOnce("floats " + pos, "action", "floats", "block", pos.toShortString(), "what", Lumberjack.blockId(b).replace("minecraft:", ""));
			return false;
		}
		if (b instanceof ConcretePowderBlock) {
			for (Direction d : Direction.values())
				if (level.getFluidState(pos.relative(d)).is(net.minecraft.tags.FluidTags.WATER)) {
					noteOnce("hard " + pos, "action", "would harden", "block", pos.toShortString());
					return false;
				}
		}
		if (b == Blocks.TNT && !Finisher.safeForTnt(level, pos)) {
			noteOnce("tnt " + pos, "action", "tnt waits", "block", pos.toShortString());
			return false;
		}
		return true;
	}

	/**
	 * Where a bucket on the block does not get the water in (on this server the water of a bucket on some leaves
	 * landed on top of them, or nowhere), the other way round always works: the water first, and the block placed
	 * into it comes out waterlogged. {@code rewet} holds the dry blocks where a pour failed; after two failures
	 * every dry one goes that way. A dry block that stands is broken first.
	 */
	private static final Set<BlockPos> rewet = new HashSet<>();
	private static int pourFails = 0;

	private static boolean waterFirst(BlockPos pos) { return pourFails >= 2 || rewet.contains(pos); }

	/** What this pass can use, as items: the chest gives nothing else, and takes back what is for a later pass. */
	private static final Set<Item> neededNow = new HashSet<>();

	/** Hoeing waits for the ground pass (all the dirt and the water) to be done. */
	private static boolean holdHoe = false;
	/** Farmland cells that water in the world keeps wet, worked out once per plan when asked for. */
	private static Set<BlockPos> wetGround = null;

	private static boolean hasHoe(LocalPlayer p) {
		for (int i = 0; i < 36; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.is(net.minecraft.tags.ItemTags.HOES) && !wornOut(s)) return true;
		}
		return false;
	}

	/** The finishing touches that wait for a tool you do not carry (a hoe, a shovel, flint and steel), one of each kind. */
	private static final List<Finisher.Fix> toolsLacking = new ArrayList<>();

	private static final Map<Item, Boolean> toolKinds = new HashMap<>();
	private static boolean isTool(Item it) { return toolKinds.computeIfAbsent(it, i -> new ItemStack(i).isDamageableItem()); }

	/**
	 * A tool for this fix that a chest of ours is known to hold, of any kind that fits. The fix names one item (an
	 * iron hoe) and only that was looked for, so the diamond hoe in the shulker box stayed there while the build
	 * waited for a hoe.
	 */
	private static Item toolInChest(LocalPlayer p, Finisher.Fix f) {
		for (BuildStore.Chest ch : chestsHere(p))
			for (var e : ch.contents.entrySet()) {
				if (e.getValue() <= 0) continue;
				Item it = Lumberjack.itemOf(e.getKey());
				if (it != null && it != Items.AIR && f.fits().test(new ItemStack(it))) return it;
			}
		return null;
	}

	/** Is this stack in a chest a tool that a waiting finishing touch can use, while you carry none for it? */
	private static boolean toolWanted(LocalPlayer p, ItemStack s) {
		if (s.isEmpty()) return false;
		for (Finisher.Fix f : toolsLacking) if (fitsFix(f, s) && !canHold(p, f)) return true;
		return false;
	}

	/** A tool at the durability we never go below (the same line as for breaking). */
	private static boolean wornOut(ItemStack s) {
		return s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() <= AfkConfig.get().buildToolMin;
	}

	/**
	 * Does this stack do for the fix? Not a tool that is nearly gone: a field of four thousand blocks would have
	 * hoed the hoe to pieces, where breaking already kept its tools.
	 */
	private static boolean fitsFix(Finisher.Fix f, ItemStack s) { return f.fits().test(s) && !wornOut(s); }

	/**
	 * Does water in the world reach this farmland (four blocks around, level with it or one above)? Then it stays
	 * farmland; bare and dry it turns back to dirt.
	 */
	static boolean wetNear(Level level, BlockPos pos) {
		if (placement == null || !placement.contains(pos, 0)) return wetDirect(level, pos);
		if (wetGround == null) {
			Set<BlockPos> wet = new HashSet<>();
			BlockPos lo = placement.min().offset(-4, 0, -4), hi = placement.max().offset(4, 1, 4);
			long volume = (long) (hi.getX() - lo.getX() + 1) * (hi.getY() - lo.getY() + 1) * (hi.getZ() - lo.getZ() + 1);
			if (volume > 400_000) return wetDirect(level, pos);   // a huge build: per cell instead of one sweep
			for (BlockPos q : BlockPos.betweenClosed(lo, hi)) {
				if (!level.getFluidState(q).is(net.minecraft.tags.FluidTags.WATER)) continue;
				for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
					wet.add(new BlockPos(q.getX() + dx, q.getY(), q.getZ() + dz));
					wet.add(new BlockPos(q.getX() + dx, q.getY() - 1, q.getZ() + dz));
				}
			}
			wetGround = wet;
		}
		return wetGround.contains(pos);
	}

	private static boolean wetDirect(Level level, BlockPos pos) {
		for (BlockPos q : BlockPos.betweenClosed(pos.offset(-4, 0, -4), pos.offset(4, 1, 4)))
			if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.WATER)) return true;
		return false;
	}

	/**
	 * May this ground be hoed now? Not before the ground pass is done; and only where the farmland will hold: wet
	 * from water nearby, or with the seed for it at hand to go in right after (or nothing to grow on it at all).
	 */
	private static boolean hoeNow(LocalPlayer p, BlockPos pos) {
		if (holdHoe) return false;
		Level level = p.level();
		if (wetNear(level, pos)) return true;
		Level schem = Schematic.world();
		if (schem == null) return true;
		BlockState above = schem.getBlockState(pos.above());
		return !Finisher.needsFarmland(above) || Lumberjack.countInInventory(p, itemFor(above)) > 0;
	}

	/** Can we hold what this fix needs right now (in the hotbar or the bag, or a free hand)? */
	private static boolean canHold(LocalPlayer p, Finisher.Fix f) {
		var inv = p.getInventory();
		if (f.emptyHand() || f.item() == null) return true;
		for (int i = 0; i < 36; i++) if (fitsFix(f, inv.getItem(i))) return true;
		return false;
	}

	/** A point on the block to click for this fix, visible from the eye and where the rule wants it. */
	private static Aim fixAim(LocalPlayer p, Vec3 eye, BlockPos pos, Finisher.Rule rule) {
		Level level = p.level();
		BlockState st = level.getBlockState(pos);
		VoxelShape shape = st.getShape(level, pos);
		if (shape.isEmpty()) return null;
		double range = p.blockInteractionRange() - 0.1;
		Aim best = null; double bestTurn = Double.MAX_VALUE;
		for (Direction face : Direction.values())
			for (Vec3 point : facePoints(eye, pos, face, shape.bounds())) {
				if (eye.distanceTo(point) > range || !visible(p, eye, point, pos, face)) continue;
				BlockHitResult hit = new BlockHitResult(point, face, pos, false);
				if (!rule.ok(level, pos, hit, eye)) continue;
				double turn = turn(p, eye, point);
				if (turn < bestTurn) { bestTurn = turn; best = new Aim(hit, point, false); }
			}
		return best;
	}

	/** The middle of an entity, if you can click it from this eye (entity reach, nothing in between). */
	private static Vec3 entityAim(LocalPlayer p, Vec3 eye, Entity e) {
		Vec3 point = e.getBoundingBox().getCenter();
		if (eye.distanceTo(point) > p.entityInteractionRange() - 0.2) return null;
		BlockHitResult ray = p.level().clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
		return ray.getType() == HitResult.Type.MISS || ray.getLocation().distanceTo(eye) >= eye.distanceTo(point) - 0.05 ? point : null;
	}

	/** What an entity job needs: where to click and what to hold; null when it is done. */
	private static Finisher.Fix entityFix(Level level, Finisher.EntityJob job) {
		Entity want = job.want();
		Entity have = Finisher.twin(level, want);
		if (have == null) {
			Item it = Finisher.placeItem(want);
			if (want instanceof net.minecraft.world.entity.decoration.HangingEntity h) {
				Direction dir = h.getDirection();
				return Finisher.Fix.use("place " + Lumberjack.itemId(it), it, Finisher.face(dir));
			}
			float yaw = Mth.wrapDegrees(want.getYRot());
			return Finisher.Fix.use("place standing", it, (l, pos, hit, eye) -> hit.getDirection() == Direction.UP
				&& Math.abs(Mth.wrapDegrees(Finisher.standYaw(rotation(eye, hit.getLocation())[0]) - yaw)) < 1f);
		}
		if (want instanceof net.minecraft.world.entity.decoration.ItemFrame wf && have instanceof net.minecraft.world.entity.decoration.ItemFrame hf) {
			ItemStack wi = wf.getItem();
			if (!wi.isEmpty() && hf.getItem().isEmpty()) return new Finisher.Fix("fill frame", wi.getItem(), s -> ItemStack.isSameItem(s, wi), false, false, Finisher.ANY);
			if (!wi.isEmpty() && ItemStack.isSameItem(wi, hf.getItem()) && wf.getRotation() != hf.getRotation()) return Finisher.Fix.click("turn frame", false);
		}
		return null;
	}

	/** Where a click places or works on this entity job: the supporting block, or the entity itself (null). */
	private static BlockPos entityClickPos(Level level, Finisher.EntityJob job) {
		Entity want = job.want();
		if (Finisher.twin(level, want) != null) return null;
		if (want instanceof net.minecraft.world.entity.decoration.HangingEntity h) return want.blockPosition().relative(h.getDirection().getOpposite());
		return want.blockPosition().below();
	}

	/** A finishing touch that can be done from here, as a step; null if none. */
	private static Step finishInReach(LocalPlayer p, boolean restDone, boolean groundOnly) {
		Vec3 eye = p.getEyePosition();
		List<BlockPos> near = new ArrayList<>();
		for (var e : finishing.entrySet()) {
			Finisher.Fix f = e.getValue();
			if ((f.last() && !restDone) || !canHold(p, f)) continue;
			if (groundOnly && !f.what().equals("till")) continue;
			if (f.what().equals("till") && !hoeNow(p, e.getKey())) continue;
			if (e.getKey().distSqr(p.blockPosition()) > 64) continue;   // thousands of fixes on a field: only the near ones are worth aiming at
			near.add(e.getKey());
		}
		// The one that takes the least turning first. In the order of the map he swung round between every two
		// blocks of a field, which looked odd and took most of the time.
		near.sort(Comparator.comparingDouble(pos -> turn(p, eye, new Vec3(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5))));
		for (BlockPos pos : near) if (fixAim(p, eye, pos, finishing.get(pos).rule()) != null) return new Finish(pos, null);
		if (groundOnly) return null;
		Level level = p.level();
		for (Finisher.EntityJob job : entityJobs) {
			Finisher.Fix f = entityFix(level, job);
			if (f == null || !canHold(p, f)) continue;
			BlockPos at = entityClickPos(level, job);
			if (at != null ? fixAim(p, eye, at, f.rule()) != null : entityAim(p, eye, Finisher.twin(level, job.want())) != null)
				return new Finish(at, job);
		}
		return null;
	}

	/**
	 * Nothing left to place from here: fetch what the finishing touches need, then walk to where one can be done.
	 */
	private static boolean planFinish(LocalPlayer p, AfkConfig c, long now, boolean restDone) {
		Level level = p.level();
		Set<Item> fetch = new HashSet<>();
		for (Finisher.Fix f : toolsLacking) {
			Item known = toolInChest(p, f);
			Item it = known != null ? known : f.item();
			if (!noMaterial.containsKey(it)) fetch.add(it);
		}
		for (Finisher.Fix f : finishing.values()) if (f.item() != null && !isTool(f.item()) && !canHold(p, f) && !noMaterial.containsKey(f.item())) fetch.add(f.item());
		for (Finisher.EntityJob job : entityJobs) {
			Finisher.Fix f = entityFix(level, job);
			if (f != null && f.item() != null && !canHold(p, f) && !noMaterial.containsKey(f.item())) fetch.add(f.item());
		}
		if (!fetch.isEmpty() && planRestock(p, c, fetch, now)) return true;
		// the nearest ones first; a look nearby, and only when that finds no spot as far as the pathfinder goes
		record Touch(BlockPos where, BlockPos click, Finisher.Fix fix, Finisher.EntityJob job) {}
		List<Touch> todo = new ArrayList<>();
		for (var e : finishing.entrySet()) {
			Finisher.Fix f = e.getValue();
			if ((f.last() && !restDone) || !canHold(p, f)) continue;
			if (f.what().equals("till") && !hoeNow(p, e.getKey())) continue;
			todo.add(new Touch(e.getKey(), e.getKey(), f, null));
		}
		for (Finisher.EntityJob job : entityJobs) {
			Finisher.Fix f = entityFix(level, job);
			if (f == null || !canHold(p, f)) continue;
			todo.add(new Touch(job.want().blockPosition(), entityClickPos(level, job), f, job));
		}
		if (todo.isEmpty()) return false;
		todo.sort(Comparator.comparingDouble(t -> t.where().distSqr(p.blockPosition())));
		if (todo.size() > 8) todo = todo.subList(0, 8);
		double eyeH = p.getEyeHeight(net.minecraft.world.entity.Pose.STANDING);
		for (int look = 0; look < 2; look++) {
			Nav nav = look == 0 ? Nav.explore(p, 6000, 0) : Nav.far(p, 0);
			for (Touch t : todo) {
				int tried = 0;
				for (BlockPos cell : nav.order) {
					if (cell.equals(nav.start) || cell.distSqr(t.where()) > 36) continue;
					if (++tried > 60) break;
					Vec3 eye = new Vec3(cell.getX() + 0.5, feetAt(level, cell) + eyeH, cell.getZ() + 0.5);
					boolean ok = t.click() != null ? fixAim(p, eye, t.click(), t.fix().rule()) != null
						: entityAim(p, eye, Finisher.twin(level, t.job().want())) != null;
					if (!ok) continue;
					Route route = nav.route(cell);
					if (route == null) continue;
					steps.add(new Walk(cell, route));
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Hold what a fix needs: the item (from the bag into the hotbar if needed), a free hand, or for a plain click
	 * something harmless (a free hand, else a block; never a tool, which strips, scrapes or tills). True once held.
	 */
	private static boolean holdFor(LocalPlayer p, Minecraft mc, Finisher.Fix f) {
		var inv = p.getInventory();
		java.util.function.Predicate<ItemStack> ok;
		if (f.emptyHand()) ok = ItemStack::isEmpty;
		else if (f.item() == null) ok = s -> s.isEmpty() || s.getItem() instanceof BlockItem;
		else ok = s -> fitsFix(f, s);
		if (ok.test(p.getMainHandItem()) && (f.item() != null || f.emptyHand() || p.getMainHandItem().isEmpty() || !hasEmptyHotbar(p))) return true;
		for (int i = 0; i < 9; i++) if (ok.test(inv.getItem(i)) && (f.item() != null || f.emptyHand() || inv.getItem(i).isEmpty() || !hasEmptyHotbar(p))) {
			inv.setSelectedSlot(i); return false;
		}
		if (f.item() != null) {
			for (int i = 9; i < 36; i++) if (fitsFix(f, inv.getItem(i))) { toHotbar(p, mc, i); return false; }
		}
		return false;
	}

	private static boolean hasEmptyHotbar(LocalPlayer p) {
		for (int i = 0; i < 9; i++) if (p.getInventory().getItem(i).isEmpty()) return true;
		return false;
	}

	/**
	 * One finishing touch: hold the right thing, look at the right spot, click, and again until the block (or the
	 * entity) is as the schematic has it. A note block takes up to 24 clicks, a repeater three.
	 */
	static class Finish extends Step {
		final BlockPos pos; final Finisher.EntityJob job;
		Aim aim = null; Vec3 entityPoint = null; boolean looked = false, clicked = false; int wait = 0, clicks = 0, misses = 0, holding = 0;
		String what = "";
		Finish(BlockPos pos, Finisher.EntityJob job) { this.pos = pos; this.job = job; }
		private Finisher.Fix live(Level level) {
			if (job != null) return entityFix(level, job);
			Level schem = Schematic.world();
			return schem == null ? null : Finisher.fix(level, schem, pos, schem.getBlockState(pos), level.getBlockState(pos));
		}
		int tick(LocalPlayer p, Minecraft mc, AfkConfig c) {
			if (wait > 0) { wait--; return BUSY; }
			Level level = p.level();
			Finisher.Fix f = live(level);
			if (f == null) {
				if (clicks > 0) Exchange.emit("build", "action", "finished", "block", (pos != null ? pos : job.want().blockPosition()).toShortString(), "what", what, "clicks", clicks);
				return DONE;
			}
			what = f.what();
			if (clicked) {
				clicked = false; lastClick = ClickResult.NONE; wait = 5;
				if (++clicks > 30) return FAILED;
				// the entity is there now (a frame hung up): its next job aims at the entity itself
				if (job != null && pos != null && Finisher.twin(level, job.want()) != null) { aim = null; entityPoint = null; looked = false; }
				return BUSY;
			}
			crouch(false);
			if (!holdFor(p, mc, f)) { wait = 3; return ++holding > 6 ? FAILED : BUSY; }
			Entity target = job != null && entityClickPos(level, job) == null ? Finisher.twin(level, job.want()) : null;
			BlockPos at = job != null ? entityClickPos(level, job) : pos;
			if (aim == null && entityPoint == null) {
				Vec3 eye = p.getEyePosition();
				if (target != null) entityPoint = entityAim(p, eye, target);
				else if (at != null) aim = fixAim(p, eye, at, f.rule());
				if (aim == null && entityPoint == null) return FAILED;
				looked = false;
			}
			Vec3 point = entityPoint != null ? entityPoint : aim.point();
			if (!looked) { looked = true; look.startByAngle(p, point, 0.1f); }
			if (look.busy()) { look.tick(p); return BUSY; }
			if (System.currentTimeMillis() - lastClickAt < 300 + R.nextLong(0, 200)) return BUSY;
			if (target != null) {
				if (mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult eh && eh.getEntity() == target) {
					pendingEntity = target; clicked = true; lastClick = ClickResult.NONE; lastClickAt = System.currentTimeMillis();
					return BUSY;
				}
			} else if (mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK && b.getBlockPos().equals(at)
				&& f.rule().ok(level, at, b, p.getEyePosition())) {
				pending = new Pending(null, null, at, false, false);
				clicked = true; lastClick = ClickResult.NONE; lastClickAt = System.currentTimeMillis();
				return BUSY;
			}
			if (++misses > 60) return FAILED;
			Lumberjack.aim(p, point);
			return elapsed() > 20_000 ? FAILED : BUSY;
		}
		void failed(LocalPlayer p, AfkConfig c) {
			BlockPos key = pos != null ? pos : job.want().blockPosition();
			int n = failures.merge(key, 1, Integer::sum);
			skipped.merge(key, System.currentTimeMillis() + skipFor(n), Math::max);
			Exchange.emit("build", "action", "finishing failed", "block", key.toShortString(), "what", what);
		}
		String name() { return "finish " + what + " at " + (pos != null ? pos : job.want().blockPosition()).toShortString(); }
		String label() {
			BlockPos key = pos != null ? pos : job.want().blockPosition();
			return String.format(java.util.Locale.ROOT, "finishing at %s", key.toShortString());
		}
	}

	/** Is there a face to click on now: a solid neighbour (for a top half, not the floor below)? */
	private static boolean hasFace(Level level, Job j) {
		boolean topHalf = wantsTopHalf(j.want());
		for (Direction d : Direction.values())
			if (!(d == Direction.DOWN && topHalf) && solid(level, j.pos().relative(d))) return true;
		return false;
	}

	/** Does a block of the build still come right next to this one? */
	private static boolean hasComingNeighbour(Job j) {
		if (order == null) return false;
		for (Direction d : Direction.values()) if (order.remaining.contains(j.pos().relative(d))) return true;
		return false;
	}

	/**
	 * A scaffold block for a block that has nothing to click on: one that floats gets one under it, one that wants its
	 * top half (a top slab or upside-down stairs) with only a floor below gets one beside it. At most two per block.
	 */
	private static boolean addSupport(LocalPlayer p, AfkConfig c, Job j) {
		Item f = filler(p, c);
		if (f == null || supportsFor.getOrDefault(j.pos(), 0) >= 2) return false;
		Level level = p.level();
		// A block that already has a face to click on did get supports too, when reach or facing was the trouble;
		// dirt solves neither, so the build filled up with dirt. Now only a block with no usable face gets one.
		boolean topHalf = wantsTopHalf(j.want());
		for (Direction d : Direction.values())
			if (!(d == Direction.DOWN && topHalf) && solid(level, j.pos().relative(d))) return false;
		List<BlockPos> options = new ArrayList<>();
		options.add(j.pos().below());
		// a support itself only gets one under it, a column like a pillar; sideways ones grew into a heap
		if (!j.scaffold()) {
			List<BlockPos> sides = new ArrayList<>();
			for (Direction d : Direction.Plane.HORIZONTAL) sides.add(j.pos().relative(d));
			sides.sort(Comparator.comparingDouble(pos -> pos.distSqr(p.blockPosition())));
			if (topHalf) options.addAll(0, sides); else options.addAll(sides);
		}
		for (BlockPos pos : options) {
			if (solid(level, pos) || !canScaffold(level, pos) || supports.containsKey(pos)) continue;
			if (p.getBoundingBox().intersects(new AABB(pos))) continue;   // not where you stand: that walled him in
			supports.put(pos, ((BlockItem) f).getBlock().defaultBlockState());
			supportsFor.merge(j.pos(), 1, Integer::sum);
			supportOf.put(pos, j.pos());
			Exchange.emit("build", "action", "support block", "for", j.pos().toShortString(), "up", pos.toShortString());
			return true;
		}
		return false;
	}

	/** A top slab or upside-down stairs: only a click on the upper half of a side, or on the underside of the block above, places it. */
	private static boolean wantsTopHalf(BlockState want) {
		if (want.getBlock() instanceof SlabBlock) return want.getValue(SlabBlock.TYPE) == SlabType.TOP;
		return want.hasProperty(BlockStateProperties.HALF) && want.getValue(BlockStateProperties.HALF) == net.minecraft.world.level.block.state.properties.Half.TOP;
	}

	/** Walk to a spot from where the highest block that has to go can be broken. */
	private static boolean planWalkToDig(LocalPlayer p, AfkConfig c, List<BlockPos> digs, boolean cleanup, long now) {
		Level level = p.level();
		double range = p.blockInteractionRange() - 0.2;
		List<BlockPos> order = new ArrayList<>(digs);
		order.sort(Comparator.<BlockPos>comparingInt(pos -> -pos.getY()).thenComparingDouble(pos -> pos.distSqr(p.blockPosition())));
		int budget = c.buildUseScaffold && !cleanup ? fillerCount(p, c) : 0;
		// nearby first; only when that finds no spot for any of them, as far as the pathfinder goes
		for (int look = 0; look < 2; look++) {
		Nav nav = look == 0 ? Nav.explore(p, 6000, budget) : Nav.far(p, budget);
		int targets = 0;
		for (BlockPos b : order) {
			if (targets++ >= 6) break;
			if (lavaNext(level, b)) { skipped.put(b, now + 10 * 60_000L); continue; }
			Vec3 bc = Vec3.atCenterOf(b);
			int checks = 0;
			for (BlockPos cell : nav.all) {
				if (cell.equals(nav.start)) continue;
				Vec3 eye = eyeAt(p, level, cell);
				if (eye.distanceTo(bc) > range + 0.8) continue;
				// standing on it: only for scaffold on the way down, handled when you are there
				if (cell.below().equals(b) && !(cleanup && safeDrop(level, b))) continue;
				if (cleanup && scaffold.contains(cell.below()) && !cell.below().equals(b)) continue;
				if (checks++ > 40) break;
				if (breakPoint(p, eye, b) == null) continue;
				Route route = nav.route(cell);
				if (route == null) continue;
				steps.add(new Walk(cell, route));
				return true;
			}
			if (look == 0) continue;
			skipped.put(b, now + 2 * 60_000L);
			Exchange.emit("build", "action", "cannot reach to break", "block", b.toShortString());
		}
		}
		return false;
	}

	/**
	 * Go to a chest for what the lowest layer lacks: first one that had it last time, then one we have not looked
	 * into yet. When every chest has been tried and it is still missing, those blocks wait and you hear it once.
	 */
	/** Chests the last restock could not get to, for one message instead of one per chest. */
	private static final List<BlockPos> unreachableChests = new ArrayList<>();
	private static long lastChestTell = 0;

	private static Nav restockFar = null;

	private static boolean planRestock(LocalPlayer p, AfkConfig c, Set<Item> lacking, long now) {
		unreachableChests.clear();
		restockFar = null;
		boolean planned = restockFrom(p, c, lacking, now);
		if (!unreachableChests.isEmpty()) {
			Exchange.emit("build", "action", "chests unreachable", "chests", unreachableChests.stream().map(BlockPos::toShortString).toList());
			// quietly and once: a sound per chest it could not reach was only noise
			if (now - lastChestTell > 5 * 60_000L) {
				lastChestTell = now;
				List<String> shown = new ArrayList<>();
				for (int i = 0; i < Math.min(3, unreachableChests.size()); i++) shown.add(unreachableChests.get(i).toShortString());
				String list = String.join(" | ", shown) + (unreachableChests.size() > 3 ? " " + String.format(java.util.Locale.ROOT, "+%s more", unreachableChests.size() - 3) : "");
			}
		}
		return planned;
	}

	private static boolean restockFrom(LocalPlayer p, AfkConfig c, Set<Item> lacking, long now) {
		scanChests(p);
		List<BuildStore.Chest> chests = chestsHere(p);
		if (chests.isEmpty()) {
			for (Item it : lacking) noMaterial.put(it, now);
			return false;
		}
		BuildStore.Chest best = null; int bestScore = 0; double bestDist = Double.MAX_VALUE;
		for (var ch : chests) {
			Set<Item> tried = visitedThisRound.get(ch.pos());
			if (tried != null && tried.containsAll(lacking)) continue;
			int score = 0;
			for (Item it : lacking) if (ch.contents.getOrDefault(Lumberjack.itemId(it), 0) > 0) score = 3;
			// unknown or a while ago: maybe (four minutes, so a chest the player refilled is found again soon). Seen
			// lately without any of it: not worth the walk (he toured all five shulker boxes for water buckets none of
			// them held, every few minutes)
			if (score == 0 && (ch.seen == 0 || now - ch.seen > 4 * 60_000L)) score = 2;
			if (score == 0) continue;
			double dist = ch.pos().distSqr(p.blockPosition());
			if (score > bestScore || (score == bestScore && dist < bestDist)) { best = ch; bestScore = score; bestDist = dist; }
		}
		if (best == null) {
			for (Item it : lacking) noMaterial.put(it, now);
			visitedThisRound.clear();
			Exchange.emit("build", "action", "out of material", "items", lacking.stream().map(Lumberjack::itemId).toList());
			return false;
		}
		visitedThisRound.computeIfAbsent(best.pos(), k -> new HashSet<>()).addAll(lacking);
		Nav nav = Nav.explore(p, 8000, 0);
		BlockPos stand = standFor(p, nav, best.pos());
		if (stand == null) {   // not nearby: one far look for this whole restock
			if (restockFar == null) restockFar = Nav.far(p, 0);
			nav = restockFar;
			stand = standFor(p, nav, best.pos());
		}
		if (stand == null) {
			unreachableChests.add(best.pos());
			return restockFrom(p, c, lacking, now);
		}
		if (!stand.equals(nav.start)) {
			Route route = nav.route(stand);
			if (route == null) { unreachableChests.add(best.pos()); return restockFrom(p, c, lacking, now); }
			steps.add(new Walk(stand, route));
		}
		steps.add(new ChestVisit(best.pos()));
		return true;
	}

	/** A walkable spot from where this chest can be opened; your own spot if it is already within reach. */
	private static BlockPos standFor(LocalPlayer p, Nav nav, BlockPos chest) {
		if (Lumberjack.withinReach(p, chest) && breakPoint(p, p.getEyePosition(), chest) != null) return nav.start;
		double eyeHeight = p.getEyeHeight(net.minecraft.world.entity.Pose.STANDING), range = p.blockInteractionRange() - 0.3;
		for (BlockPos cell : nav.all) {
			Vec3 eye = new Vec3(cell.getX() + 0.5, cell.getY() + eyeHeight, cell.getZ() + 0.5);
			Vec3 near = new Vec3(Mth.clamp(eye.x, chest.getX(), chest.getX() + 1), Mth.clamp(eye.y, chest.getY(), chest.getY() + 1), Mth.clamp(eye.z, chest.getZ(), chest.getZ() + 1));
			// within reach is not enough: another shulker box or block in front of it takes the click
			if (eye.distanceTo(near) <= range && breakPoint(p, eye, chest) != null) return cell;
		}
		return null;
	}

	private static String names(Set<Item> items) {
		List<String> out = new ArrayList<>();
		for (Item it : items) out.add(new ItemStack(it).getHoverName().getString());
		return String.join(", ", out);
	}

	/** Items still short for all blocks to do, minus what you carry. */
	private static Map<Item, Integer> shortage(LocalPlayer p) {
		Map<Item, Integer> out = new LinkedHashMap<>();
		if (scan == null) return out;
		for (var e : scan.need.entrySet()) {
			// a tool is short only while a finishing touch waits for one: with a diamond hoe in hand no iron one is missing
			if (isTool(e.getKey())) {
				for (Finisher.Fix f : toolsLacking) if (f.item() == e.getKey()) out.put(e.getKey(), 1);
				continue;
			}
			int s = e.getValue() - Lumberjack.countInInventory(p, e.getKey());
			if (s > 0) out.put(e.getKey(), s);
		}
		return out;
	}

	/** Loose items in and around the build within twelve blocks: mostly what we broke. */
	private static List<ItemEntity> looseItems(LocalPlayer p) {
		AABB box = new AABB(Vec3.atLowerCornerOf(placement.min()), Vec3.atLowerCornerOf(placement.max()).add(1, 1, 1)).inflate(4);
		return p.level().getEntitiesOfClass(ItemEntity.class, box, e -> e.distanceTo(p) < 12 && e.onGround());
	}

	// ---------- walking ----------
	/** Standing room: a solid top to stand on, two free blocks above it, no water, lava or fire. */
	static boolean standable(Level level, BlockPos f) { return !Double.isNaN(floorY(level, f)); }

	/**
	 * The height your feet are at when you stand in this cell, or NaN if you cannot stand there. Not only on a full
	 * block (feet at the bottom of the cell): also on a low block in the cell itself (a bottom slab, carpet, snow:
	 * feet halfway up) and on a tall one below (a fence or wall: feet half a block into the cell). The pathfinder
	 * knew only whole blocks, so it never walked a slab floor or over a fence and gave up on the last blocks.
	 */
	static double floorY(Level level, BlockPos f) {
		BlockState here = level.getBlockState(f);
		// stepping on it sets it off
		if (here.getBlock() instanceof net.minecraft.world.level.block.BasePressurePlateBlock || here.getBlock() instanceof net.minecraft.world.level.block.TripWireBlock) return Double.NaN;
		VoxelShape own = here.getCollisionShape(level, f);
		double feet;
		BlockPos under;
		if (!own.isEmpty()) {
			double top = own.max(Direction.Axis.Y);
			if (top > 0.6 || own.min(Direction.Axis.Y) > 0.01) return Double.NaN;   // a full block, or something hanging
			feet = f.getY() + top; under = f;
		} else {
			if (!here.getFluidState().isEmpty()) return Double.NaN;
			BlockPos b = f.below();
			VoxelShape floor = level.getBlockState(b).getCollisionShape(level, b);
			if (floor.isEmpty()) return Double.NaN;
			double top = floor.max(Direction.Axis.Y);
			if (top < 0.9) return Double.NaN;   // a low block below: you stand in that cell, not in this one
			feet = b.getY() + top; under = b;
		}
		if (!level.getBlockState(under).getFluidState().isEmpty() && under != f) return Double.NaN;
		// room for the body (1.8 high) from the feet up
		for (int y = f.getY() + 1; y < feet + 1.8; y++) if (!passable(level, new BlockPos(f.getX(), y, f.getZ()), false)) return Double.NaN;
		if (Grinder.hazard(level, f) || Grinder.hazard(level, f.below())) return Double.NaN;
		return feet;
	}

	/**
	 * The pathfinder's cell for where you are. On farmland or a dirt path (15/16 high) your feet are still inside the
	 * block's own cell, and in a jump you are above yours; neither is a cell that anything walks back into.
	 */
	static BlockPos footing(LocalPlayer p) {
		Level level = p.level();
		BlockPos at = p.blockPosition();
		if (standable(level, at)) return at;
		if (standable(level, at.above()) && floorY(level, at.above()) - p.getY() < 0.2) return at.above();
		if (!p.onGround()) for (int k = 1; k <= 2; k++) if (standable(level, at.below(k))) return at.below(k);
		return at;
	}

	/** The feet height in this cell, or its bottom if you cannot stand there (for cells on a route). */
	static double feetAt(Level level, BlockPos f) {
		double y = floorY(level, f);
		return Double.isNaN(y) ? f.getY() : y;
	}

	/** Free to walk through; at foot height a carpet or pressure plate may lie there. */
	static boolean passable(Level level, BlockPos q, boolean feet) {
		BlockState st = level.getBlockState(q);
		if (!st.getFluidState().isEmpty()) return false;
		VoxelShape s = st.getCollisionShape(level, q);
		return s.isEmpty() || (feet && s.max(Direction.Axis.Y) <= 0.2);
	}

	/**
	 * Standing in this cell means standing on farmland. Walking over it is fine; landing on it after a jump or a
	 * drop tramples it back to dirt (and the crop on it with it), which had the builder hoe and sow again and again.
	 */
	static boolean onFarmland(Level level, BlockPos cell) {
		return level.getBlockState(cell.below()).is(Blocks.FARMLAND);
	}

	/** Head room for a jump from this cell up by {@code rise}: a ceiling only has to leave that much above your head. */
	private static boolean roomToJump(Level level, BlockPos cur, double feet, double rise) {
		for (int y = cur.getY() + 1; y < feet + 1.8 + rise + 0.05; y++) if (!passable(level, new BlockPos(cur.getX(), y, cur.getZ()), false)) return false;
		return true;
	}

	/**
	 * A drop right beside this cell. There we crouch while placing, like a player does at the edge of a wall or
	 * a floor: then turning and a nudge from the click never walk you off.
	 */
	static boolean edge(Level level, BlockPos cell) {
		// on top of our tower every side is a drop, but crouching there would take you down through the scaffolding
		if (tower != null && cell.equals(tower.top())) return false;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos h = cell.relative(d);
			if (!standable(level, h) && passable(level, h, true) && passable(level, h.above(), false) && passable(level, h.below(), false)) return true;
		}
		return false;
	}

	/** Eye height at this cell as you will stand there: crouched at an edge, upright otherwise. */
	private static double eyeHeight(LocalPlayer p, Level level, BlockPos cell) {
		return p.getEyeHeight(edge(level, cell) ? net.minecraft.world.entity.Pose.CROUCHING : net.minecraft.world.entity.Pose.STANDING);
	}

	enum Move { WALK, UP, DROP, BRIDGE, PILLAR, DESCEND }
	record Route(BlockPos start, List<BlockPos> cells, List<Move> moves) {}

	/** Scaffold costs like several steps of walking around: ground first, scaffold where it really helps. */
	private static final int SCAFFOLD_COST = 45;

	/**
	 * The cells you can get to, with the cheapest way to each: level, one up with a jump, up to three down, and (with
	 * scaffold blocks in the bag) a bridge block over a gap or a pillar block under you. Scaffold only near the build
	 * and never where the schematic wants a block.
	 */
	static final class Nav {
		final Map<BlockPos, Integer> cost = new HashMap<>(), used = new HashMap<>();
		final Map<BlockPos, BlockPos> from = new HashMap<>();
		final Map<BlockPos, Move> move = new HashMap<>();
		final List<BlockPos> order = new ArrayList<>();   // by cost, nearest first; only cells fit to stand on while working
		/**
		 * Every cell you can get to and back from, by cost, nearest first: also those where a block of the build still
		 * comes. For what you do without putting a block down (breaking, a chest, scooping water) your body is in
		 * nobody's way. In an empty room where farmland still came on every floor cell none of them was in
		 * {@link #order}: he ran to the stairs outside the field to break a torch, and from there called the shulker
		 * boxes he had stood beside unreachable.
		 */
		final List<BlockPos> all = new ArrayList<>();
		/** Cells where a block of the build still comes: only for a block that has no other spot to be placed from. */
		final Set<BlockPos> future = new HashSet<>();
		/** Cells from which there is a way back to where you started. */
		final Set<BlockPos> returnable = new HashSet<>();
		/** For every cell the cells you can step to it from; only kept while exploring. */
		private final Map<BlockPos, List<BlockPos>> stepsInto = new HashMap<>();
		/** The cells you step to straight from the start. */
		private final List<BlockPos> firstSteps = new ArrayList<>(8);
		BlockPos start;

		/** As far as {@link #NAV_RANGE}: only when a look nearby found nothing, it costs many times more. */
		static Nav far(LocalPlayer p, int scaffoldBudget) { return explore(p, FAR_NODES, scaffoldBudget); }

		static Nav explore(LocalPlayer p, int maxNodes, int scaffoldBudget) {
			Level level = p.level();
			Nav nav = new Nav();
			BlockPos start = footing(p);
			nav.start = start;
			// Only where your body would be in the way of the block that comes. Every cell of a field is one where wheat
			// still comes, so none of them counted as a place to work from: he hoed what he could reach from the top
			// of a leaf and then stood still with 3800 blocks of the field left to hoe.
			Set<BlockPos> notToStand = scan == null ? Set.of() : scan.inTheWay(level);
			PriorityQueue<BlockPos> q = new PriorityQueue<>(Comparator.comparingInt(b -> nav.cost.getOrDefault(b, Integer.MAX_VALUE)));
			nav.cost.put(start, 0); nav.used.put(start, 0); nav.from.put(start, start); q.add(start);
			Set<BlockPos> closed = new HashSet<>();
			int top = placement.max().getY() + 1;
			while (!q.isEmpty() && closed.size() < maxNodes) {
				BlockPos cur = q.poll();
				if (!closed.add(cur)) continue;
				nav.all.add(cur);
				if (!notToStand.contains(cur) && !notToStand.contains(cur.above())) nav.order.add(cur);
				else nav.future.add(cur);
				int base = nav.cost.get(cur), spent = nav.used.get(cur);
				boolean canScaffold = spent < scaffoldBudget;
				for (Direction d : Direction.Plane.HORIZONTAL) {
					BlockPos h = cur.relative(d);
					if (Math.abs(h.getX() - start.getX()) > NAV_RANGE || Math.abs(h.getZ() - start.getZ()) > NAV_RANGE) continue;
					// by the real heights: a step up to 0.6 walks (onto a slab), up to 1.25 needs a jump (onto a block,
					// from a slab onto a fence), lower is a drop
					double fc = feetAt(level, cur);
					boolean moved = false;
					for (BlockPos cand : new BlockPos[] { h, h.above() }) {
						double fh = floorY(level, cand);
						if (Double.isNaN(fh)) continue;
						double dy = fh - fc;
						if (Math.abs(dy) <= 0.6) { nav.relax(q, closed, cur, cand, base + 10, spent, Move.WALK); moved = true; break; }
						// Farmland is trampled by a fall of more than half a block: a drop onto it, or a low hop. A jump up a
						// whole block lands after a fall of about a third and is fine; without it he could not get onto the
						// field at all once everything around him was hoed.
						if (onFarmland(level, cand) && dy < 0.8) { moved = true; break; }
						if (dy > 0.6 && dy <= 1.25 && roomToJump(level, cur, fc, dy)) { nav.relax(q, closed, cur, cand, base + 18, spent, Move.UP); moved = true; break; }
						if (dy < -0.6 && dy >= -3.2) { nav.relax(q, closed, cur, cand, base + 12 + (int) (-dy * 3), spent, Move.DROP); moved = true; break; }
					}
					if (moved) continue;
					if (!passable(level, h, true) || !passable(level, h.above(), false)) continue;
					boolean dropped = false;
					for (int k = 1; k <= 3; k++) {
						BlockPos down = h.below(k);
						if (standable(level, down)) {
							if (!onFarmland(level, down)) nav.relax(q, closed, cur, down, base + 12 + 3 * k, spent, Move.DROP);
							dropped = true; break;
						}
						if (!passable(level, down, false)) break;
					}
					// a bridge block: from the edge, against the side of the block you stand on
					if (!dropped && canScaffold && placement.contains(h, 4) && level.getBlockState(cur.below()).isCollisionShapeFullBlock(level, cur.below())
						&& canScaffold(level, h.below()) && !Grinder.hazard(level, h))
						nav.relax(q, closed, cur, h, base + 10 + SCAFFOLD_COST, spent + 1, Move.BRIDGE);
				}
				// down our own pillar: break the scaffold block under you, you drop one block onto the next
				if (scaffold.contains(cur.below()) && solid(level, cur.below(2)) && !Grinder.hazard(level, cur.below(2)))
					nav.relax(q, closed, cur, cur.below(), base + 25, spent, Move.DESCEND);
				// a pillar block: jump and put it where you stood
				if (canScaffold && cur.getY() < top && placement.contains(cur, 4) && canScaffold(level, cur)
					&& passable(level, cur.above(2), false) && passable(level, cur.above(3), false))
					nav.relax(q, closed, cur, cur.above(), base + 18 + SCAFFOLD_COST, spent + 1, Move.PILLAR);
			}
			// Only spots with a way back count as places to go. A drop of two or three is one way; the builder dropped
			// into the stair hole to get in reach of a chest and stood at the bottom with no way out.
			ArrayDeque<BlockPos> todo = new ArrayDeque<>();
			todo.add(start); nav.returnable.add(start);
			// Where you are is not always a cell that anything walks into: standing on farmland he took the farmland
			// block itself as his cell, found no way back to it from anywhere, dropped every standing spot and said
			// he could not reach dirt three blocks away. The start is now the cell he really stands in; where even
			// that is no cell (in water, on a chest), the cells he steps to from there count as back.
			if (!nav.stepsInto.containsKey(start)) for (BlockPos c : nav.firstSteps) if (nav.returnable.add(c)) todo.add(c);
			while (!todo.isEmpty()) {
				List<BlockPos> into = nav.stepsInto.get(todo.poll());
				if (into != null) for (BlockPos c : into) if (nav.returnable.add(c)) todo.add(c);
			}
			nav.stepsInto.clear();
			nav.order.removeIf(c -> !nav.returnable.contains(c));
			nav.all.removeIf(c -> !nav.returnable.contains(c));
			nav.future.removeIf(c -> !nav.returnable.contains(c));
			return nav;
		}

		private void relax(PriorityQueue<BlockPos> q, Set<BlockPos> closed, BlockPos cur, BlockPos next, int k, int spent, Move m) {
			stepsInto.computeIfAbsent(next, x -> new ArrayList<>(2)).add(cur);
			if (cur.equals(start)) firstSteps.add(next);
			if (closed.contains(next) || k >= cost.getOrDefault(next, Integer.MAX_VALUE)) return;
			cost.put(next, k); used.put(next, spent); from.put(next, cur); move.put(next, m); q.add(next);
		}

		/** The way from the start to this cell, or null if it was not reached. */
		Route route(BlockPos to) {
			if (!from.containsKey(to)) return null;
			List<BlockPos> cells = new ArrayList<>();
			for (BlockPos at = to; !from.get(at).equals(at); at = from.get(at)) cells.add(at);
			java.util.Collections.reverse(cells);
			List<Move> moves = new ArrayList<>();
			for (BlockPos c : cells) moves.add(move.get(c));
			return new Route(start, cells, moves);
		}
	}

	// ---------- steps ----------
	abstract static class Step {
		long startedAt;
		void begin(LocalPlayer p) { startedAt = System.currentTimeMillis(); }
		abstract int tick(LocalPlayer p, Minecraft mc, AfkConfig c);
		void failed(LocalPlayer p, AfkConfig c) {}
		/** Name for the events: the watcher matches on it. */
		abstract String name();
		/** What the player sees in the HUD status line, in their own language. */
		abstract String label();
		long elapsed() { return System.currentTimeMillis() - startedAt; }
	}

	/** Do nothing for a moment, with the reason for the HUD. */
	static class Wait extends Step {
		final long ms; final String label;
		Wait(long ms, String label) { this.ms = ms; this.label = label; }
		int tick(LocalPlayer p, Minecraft mc, AfkConfig c) { look.tick(p); return elapsed() >= ms ? DONE : BUSY; }
		String name() { return "waits"; }
		String label() { return label; }
	}

	/**
	 * Walk a route with the real keys: turning toward the next cell, a jump where it goes one up. Where the route
	 * needs scaffold, a bridge or pillar step goes first and then the walk carries on.
	 */
	static class Walk extends Step {
		final BlockPos goal; Route route; int idx = 0, still = 0, replans = 0, jumpsHere = 0; long idxSince = 0; Vec3 previous;
		Walk(BlockPos goal, Route route) { this.goal = goal; this.route = route; }
		int tick(LocalPlayer p, Minecraft mc, AfkConfig c) {
			Level level = p.level();
			if (route == null) {
				route = Nav.explore(p, 8000, c.buildUseScaffold ? fillerCount(p, c) : 0).route(goal);
				if (route == null) route = Nav.far(p, c.buildUseScaffold ? fillerCount(p, c) : 0).route(goal);
				idx = 0;
				if (route == null) return FAILED;
			}
			List<BlockPos> cells = route.cells();
			if (idx >= cells.size()) {
				Vec3 m = new Vec3(goal.getX() + 0.5, p.getY(), goal.getZ() + 0.5);
				double d = Math.hypot(m.x - p.getX(), m.z - p.getZ());
				if (d < 0.25 && p.onGround()) { stopKeys(mc); return DONE; }
				crouch(edge(level, goal));
				steer(p, mc, m, d);
				return elapsed() > 30_000 ? FAILED : BUSY;
			}
			BlockPos wp = cells.get(idx);
			Move mv = route.moves().get(idx);
			BlockPos prev = idx == 0 ? route.start() : cells.get(idx - 1);
			// down a pillar: break the scaffold block under you and drop onto the next one
			if (mv == Move.DESCEND && solid(level, wp)) {
				stopKeys(mc);
				steps.addFirst(this);
				steps.addFirst(new Dig(wp));
				current = null;
				return DONE;
			}
			// scaffold first: stand on the cell before it, then bridge or pillar
			if ((mv == Move.BRIDGE || mv == Move.PILLAR) && !standable(level, wp)) {
				Vec3 at = new Vec3(prev.getX() + 0.5, p.getY(), prev.getZ() + 0.5);
				double d = Math.hypot(at.x - p.getX(), at.z - p.getZ());
				if (d > 0.4 || !p.onGround()) { steer(p, mc, at, d); return elapsed() > 30_000 ? FAILED : BUSY; }
				stopKeys(mc);
				steps.addFirst(this);
				steps.addFirst(mv == Move.BRIDGE ? new Bridge(prev, directionTo(prev, wp)) : new Pillar(prev));
				current = null;
				return DONE;
			}
			// cut corners over plain walking at the same height, as long as the straight line stays on standing room
			for (int j = Math.min(cells.size() - 1, idx + 8); j > idx; j--) {
				BlockPos w = cells.get(j);
				if (plainLevel(route, idx, j) && clearLine(level, p.position(), Vec3.atBottomCenterOf(w), w.getY())) { idx = j; wp = w; idxSince = 0; break; }
			}
			Vec3 m = new Vec3(wp.getX() + 0.5, p.getY(), wp.getZ() + 0.5);
			double d = Math.hypot(m.x - p.getX(), m.z - p.getZ());
			double wf = feetAt(level, wp);
			boolean up = wf > p.getY() + 0.6;
			boolean last = idx == cells.size() - 1;
			// crouched you cannot step down on purpose, so only on a level stretch: along a bridge, or the last bit to a spot at an edge
			boolean bridging = mv == Move.BRIDGE || (idx + 1 < cells.size() && route.moves().get(idx + 1) == Move.BRIDGE);
			crouch(Math.abs(wf - p.getY()) < 0.1 && (bridging || (last && d < 1.8 && edge(level, wp))));
			if (idxSince == 0) idxSince = System.currentTimeMillis();
			// A step up he could not make (something over his head, or two high) had him jump and circle round the
			// spot for a minute: jumping moved him, so standing still never showed. Now a few jumps or a few seconds
			// without reaching the next cell count as stuck.
			if (jumpsHere > 3 || System.currentTimeMillis() - idxSince > 6000) {
				stopKeys(mc); crouch(false);
				jumpsHere = 0; idxSince = 0;
				if (++replans > 2) return FAILED;
				route = null;
				return BUSY;
			}
			// In water a tap on the jump key lifts you a few centimetres. Hold it, the way you swim to the edge and
			// climb out: pushed into a leaf hole he had just filled, he tapped at its wall until the walk gave up.
			if (p.isInWater()) { crouch(false); HeldKeys.set(mc.options.keyJump, true); jumpTicks = 3; }
			if (up && d < 1.5 && p.onGround() && jumpTicks == 0) { crouch(false); jump(mc); jumpsHere++; }
			if (d < (last ? 0.3 : 0.45) && (!up || p.getY() >= wf - 0.05)) { idx++; jumpsHere = 0; idxSince = 0; return BUSY; }
			steer(p, mc, m, d);
			if (previous != null && p.position().distanceToSqr(previous) < 0.0004) {
				if (++still > 30) {
					stopKeys(mc); still = 0;
					if (++replans > 3) return FAILED;
					route = null;
				}
			} else still = 0;
			previous = p.position();
			if (elapsed() > 20_000 + (route == null ? 0 : route.cells().size() * 3000L)) return FAILED;
			return BUSY;
		}
		private static boolean plainLevel(Route r, int from, int to) {
			int y = r.cells().get(from).getY();
			for (int i = from; i <= to; i++) if (r.cells().get(i).getY() != y || r.moves().get(i) != Move.WALK) return false;
			return true;
		}
		/** Turn toward the point and walk as soon as you roughly look at it; sprint only on a long straight bit. */
		private void steer(LocalPlayer p, Minecraft mc, Vec3 m, double distance) {
			double dx = m.x - p.getX(), dz = m.z - p.getZ();
			float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
			float delta = Mth.wrapDegrees(yaw - p.getYRot());
			float pitch = p.getXRot() + Mth.clamp(10f - p.getXRot(), -6f, 6f) * 0.3f;   // slowly toward a normal walking posture
			float step = distance > 2 ? 16f : 10f;
			MouseTurn.toward(p, p.getYRot() + Mth.clamp(delta, -step, step) + (float) R.nextDouble(-0.4, 0.4), pitch);
			boolean walk = Math.abs(delta) < 60;
			HeldKeys.set(mc.options.keyUp, walk);
			HeldKeys.set(mc.options.keySprint, walk && distance > 6 && Math.abs(delta) < 15 && p.getFoodData().getFoodLevel() > 6);
		}
		private static void stopKeys(Minecraft mc) { HeldKeys.set(mc.options.keyUp, false); HeldKeys.set(mc.options.keySprint, false); }
		void failed(LocalPlayer p, AfkConfig c) { stopKeys(Minecraft.getInstance()); steps.clear(); }
		String name() { return "walk to " + goal.toShortString(); }
		String label() { return String.format(java.util.Locale.ROOT, "walking to %s", goal.toShortString()); }
	}

	private static Direction directionTo(BlockPos from, BlockPos to) {
		return Direction.getApproximateNearest(to.getX() - from.getX(), 0, to.getZ() - from.getZ());
	}

	/** Is the straight line from a to b over standing room at height y, also for the width of the player? */
	static boolean clearLine(Level level, Vec3 a, Vec3 b, int y) {
		double d = Math.hypot(b.x - a.x, b.z - a.z); int n = Math.max(1, (int) Math.ceil(d / 0.25));
		for (int i = 0; i <= n; i++) {
			double t = (double) i / n, x = a.x + (b.x - a.x) * t, z = a.z + (b.z - a.z) * t;
			for (double ox : new double[] { -0.32, 0.32 }) for (double oz : new double[] { -0.32, 0.32 })
				if (!standable(level, new BlockPos(Mth.floor(x + ox), y, Mth.floor(z + oz)))) return false;
		}
		return true;
	}

	/**
	 * A bridge block, the way players bridge: crouched, facing back, step backwards until you hang over the edge
	 * (crouching keeps you on it), then look down at the side of the block you stand on and place against it. The
	 * block lands under the next cell. From over the edge that face looks at you, so the click is a normal one.
	 */
	static class Bridge extends Step {
		final BlockPos from; final Direction dir; final BlockPos target;
		boolean clicked = false, atEdge = false; int wait = 0, attempts = 0, settle = 0, still = 0; Vec3 previous;
		Bridge(BlockPos from, Direction dir) { this.from = from; this.dir = dir; this.target = from.relative(dir).below(); }
		int tick(LocalPlayer p, Minecraft mc, AfkConfig c) {
			if (wait > 0) { wait--; return BUSY; }
			Level level = p.level();
			if (clicked) {
				ClickResult r = lastClick; lastClick = ClickResult.NONE;
				if (r == ClickResult.PLACED || solid(level, target)) { if (solid(level, target)) { back(mc, false); return DONE; } if (++settle > 10) return FAILED; lastClick = ClickResult.PLACED; return BUSY; }
				clicked = false;
				if (r == ClickResult.REFUSED && ++attempts > 3) return FAILED;
			}
			if (solid(level, target)) { back(mc, false); return DONE; }
			if (!holdFiller(p, mc, c)) return FAILED;
			crouch(true);
			if (!p.isCrouching()) return elapsed() > 3000 ? FAILED : BUSY;
			// how far past the middle of the cell toward the gap: the side face is at 0.5, crouching stops you near 0.8
			double along = (p.getX() - (from.getX() + 0.5)) * dir.getStepX() + (p.getZ() - (from.getZ() + 0.5)) * dir.getStepZ();
			Vec3 face = new Vec3(from.getX() + 0.5 + dir.getStepX() * 0.5, from.getY() - 0.5, from.getZ() + 0.5 + dir.getStepZ() * 0.5);
			if (along >= 0.6) atEdge = true;
			if (!atEdge) {
				// facing back, pitched down, and backwards: that keeps the eye over the new spot, like a player bridging
				float yaw = (float) Math.toDegrees(Math.atan2(dir.getStepX(), -dir.getStepZ()));
				float delta = Mth.wrapDegrees(yaw - p.getYRot());
				MouseTurn.toward(p, p.getYRot() + Mth.clamp(delta, -14f, 14f), p.getXRot() + Mth.clamp(76f - p.getXRot(), -10f, 10f));
				back(mc, Math.abs(delta) < 30);
				boolean stopped = previous != null && p.position().distanceToSqr(previous) < 0.00005 && Math.abs(delta) < 30;
				previous = p.position();
				still = stopped ? still + 1 : 0;
				if (still > 15 && along > 0.52) atEdge = true;   // the edge holds you a bit earlier: the face is in view already
				else if (still > 60) return FAILED;
				else return elapsed() > 12_000 ? FAILED : BUSY;
			}
			back(mc, false);
			if (mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK && b.getBlockPos().equals(from.below()) && b.getDirection() == dir) {
				Placing sim = simulate(p, p.getMainHandItem(), b, p.getYRot(), p.getXRot());
				if (sim != null && sim.pos().equals(target)) {
					pending = new Pending(target, sim.state(), null, false, true);
					clicked = true; lastClick = ClickResult.NONE;
					return BUSY;
				}
			}
			Lumberjack.aim(p, face);
			return elapsed() > 12_000 ? FAILED : BUSY;
		}
		private static void back(Minecraft mc, boolean on) { HeldKeys.set(mc.options.keyDown, on); }
		void failed(LocalPlayer p, AfkConfig c) { back(Minecraft.getInstance(), false); steps.clear(); }
		String name() { return "bridge to " + from.relative(dir).toShortString(); }
		String label() { return String.format(java.util.Locale.ROOT, "bridging to %s", from.relative(dir).toShortString()); }
	}

	/** A pillar block: look straight down, jump, and at the top put a block where you stood. You land on it. */
	static class Pillar extends Step {
		final BlockPos cell; boolean clicked = false; int jumps = 0, wait = 0;
		Pillar(BlockPos cell) { this.cell = cell; }
		int tick(LocalPlayer p, Minecraft mc, AfkConfig c) {
			if (wait > 0) { wait--; return BUSY; }
			Level level = p.level();
			if (clicked) {
				lastClick = ClickResult.NONE;
				clicked = false;
			}
			if (solid(level, cell)) return p.onGround() && p.getY() >= cell.getY() + 0.9 ? DONE : (elapsed() > 10_000 ? FAILED : BUSY);
			if (!holdFiller(p, mc, c)) return FAILED;
			crouch(false);
			if (Math.abs(p.getXRot() - 88f) > 1.5f) { MouseTurn.toward(p, p.getYRot(), p.getXRot() + Mth.clamp(88f - p.getXRot(), -12f, 12f)); return BUSY; }
			if (p.onGround() && p.getY() < cell.getY() + 0.5) {
				if (jumps++ > 4) return FAILED;
				jump(mc);
				return BUSY;
			}
			// high enough that the new block does not touch you, and the crosshair on the floor you jumped from
			if (!p.onGround() && p.getY() > cell.getY() + 1.02 && mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK
				&& b.getBlockPos().equals(cell.below()) && b.getDirection() == Direction.UP) {
				Placing sim = simulate(p, p.getMainHandItem(), b, p.getYRot(), p.getXRot());
				if (sim != null && sim.pos().equals(cell)) {
					pending = new Pending(cell, sim.state(), null, false, true);
					clicked = true; lastClick = ClickResult.NONE;
				}
			}
			return elapsed() > 10_000 ? FAILED : BUSY;
		}
		void failed(LocalPlayer p, AfkConfig c) { steps.clear(); }
		String name() { return "pillar at " + cell.toShortString(); }
		String label() { return String.format(java.util.Locale.ROOT, "pillaring up at %s", cell.toShortString()); }
	}

	/** Place one block: right item in hand, look at the chosen face, and click once the result is exactly right. */
	static class Place extends Step {
		final Job job;
		Aim aim = null; boolean looked = false, clicked = false, swapped = false, stickyCrouch = false; int wait = 0, misses = 0, attempts = 0, settle = 0, wrongResult = 0, blocked = 0;
		/** Clicks in a row that did not go out, or ticks a bucket's aim did not hold. */
		int notOut = 0;
		/** Points where another block took the crosshair: the next aim goes elsewhere. */
		final Set<Vec3> tried = new HashSet<>();
		boolean centring = false; long centreFrom = 0; boolean centred = false;
		/** The last click emptied a water bucket. */
		boolean poured = false;
		/** Why it failed, for the event. */
		String why = "";
		Place(Job job) { this.job = job; }
		private int fail(String reason) { why = reason; return FAILED; }
		int tick(LocalPlayer p, Minecraft mc, AfkConfig c) {
			if (wait > 0) { wait--; return BUSY; }
			Level level = p.level();
			BlockPos target = job.pos(); BlockState want = job.want();
			BlockState have = level.getBlockState(target);
			if (clicked) {
				ClickResult r = lastClick; lastClick = ClickResult.NONE;
				switch (r) {
					case PLACED -> {
						// the client puts the block there right away; if the server does not agree it takes it back.
						// Water into a block is the exception: the client only shows it once the server says so. A dry
						// leaf counted as "right so far", the step was done, and the next plan poured a second bucket
						// on it: by then the water was in, and vanilla puts the second one on top.
						boolean there = poured ? matches(want, have) : acceptable(want, have);
						if (there) {
							notOut = 0;
							placed++; failures.remove(target);
							if (job.scaffold()) supports.remove(target); else retireSupportsOf(target);
							return DONE;
						}
						if (++settle > (poured ? 60 : 10)) return fail(poured ? "water did not arrive" : "server took the block back");
						lastClick = ClickResult.PLACED;
						return BUSY;
					}
					case UNSTEADY, MISSED, NONE -> {
						clicked = false; misses++;
						// The click did not go out. A bucket is checked once more at the click itself (standing still,
						// and rays from a shifted eye along the view as it really is). A view a fraction off a sliver
						// of a face inside a 1x1 hole failed that every time, while from here all still looked right:
						// he asked for the same click again, for minutes and without a word, until someone pushed him.
						if (++notOut > 5) {
							notOut = 0;
							if (aim != null) tried.add(aim.point());
							aim = null;
							if (tried.size() > 8) return fail("click does not get through");
						}
						if (elapsed() > 12_000) return fail("busy too long");
					}
					default -> { clicked = false; if (++attempts > 3) return fail("click refused"); wait = 4; return BUSY; }
				}
			}
			if (matches(want, have)) return DONE;
			if (!fillable(want, have)) return fail("spot taken");
			if (centring && !centred) {
				Vec3 mid = new Vec3(p.blockPosition().getX() + 0.5, p.getY(), p.blockPosition().getZ() + 0.5);
				if (Math.hypot(mid.x - p.getX(), mid.z - p.getZ()) > 0.1 && System.currentTimeMillis() - centreFrom < 1500) { steerTo(p, mc, mid); return BUSY; }
				HeldKeys.set(mc.options.keyUp, false);
				centred = true; aim = null; wait = 2;
				return BUSY;
			}
			Item it = itemFor(job);
			int slot = Lumberjack.hotbarSlot(p, it);
			if (slot < 0) {
				if (Lumberjack.countInInventory(p, it) == 0 || swapped) return fail("not in the bag");
				swapped = true;
				toHotbar(p, mc, sourceSlot(p, it));
				wait = 3;
				return BUSY;
			}
			if (p.getInventory().getSelectedSlot() != slot) { p.getInventory().setSelectedSlot(slot); wait = 2; return BUSY; }
			// on the tower never: crouching there takes you down through the scaffolding
			boolean wantCrouch = tower == null && (stickyCrouch || edge(level, footing(p)));
			if (wantCrouch != crouching) { crouch(wantCrouch); aim = null; wait = 3; return BUSY; }   // the eye drops 35 cm: aim again after that
			if (wantCrouch && !p.isCrouching()) { crouch(true); return elapsed() > 3000 ? fail("crouching failed") : BUSY; }
			ItemStack stack = p.getMainHandItem();
			if (aim == null) {
				if (standsInTheWay(p, target)) return fail("stands in their own way");
				aimSkip = tried;
				try { aim = findAim(p, p.getEyePosition(), target, want, stack); } finally { aimSkip = Set.of(); }
				if (aim == null && !centring && tower == null) {
					// the plan counted on the middle of the spot; a few tenths off, a narrow line of sight is gone
					Vec3 mid = new Vec3(p.blockPosition().getX() + 0.5, p.getY(), p.blockPosition().getZ() + 0.5);
					if (Math.hypot(mid.x - p.getX(), mid.z - p.getZ()) > 0.12) { centring = true; centreFrom = System.currentTimeMillis(); return BUSY; }
				}
				if (aim == null) return fail(tried.isEmpty() ? "no point to click from here" : "another block in the way");
				if (aim.crouch() && !crouching) { stickyCrouch = true; crouch(true); aim = null; wait = 3; return BUSY; }
				looked = false;
			}
			if (!looked) { looked = true; look.startByAngle(p, aim.point(), 0.1f); }
			if (look.busy()) { look.tick(p); return BUSY; }
			if (mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK
				&& b.getBlockPos().equals(aim.hit().getBlockPos()) && b.getDirection() == aim.hit().getDirection()) {
				Placing sim = simulate(p, stack, b, p.getYRot(), p.getXRot());
				if (sim != null && sim.pos().equals(target) && acceptable(want, sim.state())) {
					// the check the click itself makes for a bucket: while it does not hold, aim finer (below) instead
					// of asking for a click that will not go out; a point that never holds makes way for another
					Vec3 from = p.getEyePosition();
					if (!stack.is(Items.WATER_BUCKET) || bucketAimHolds(p, from, from.add(p.getViewVector(1f)), b.getBlockPos(), b.getDirection(), ClipContext.Fluid.NONE)) {
						// the pace: never faster than the setting, with some spread like a hand
						long gap = (long) (1000.0 / Math.max(1, c.buildMaxPerSec)) + R.nextLong(0, 120);
						if (System.currentTimeMillis() - lastClickAt < gap) return BUSY;
						pending = new Pending(target, want, null, aim.crouch(), job.scaffold());   // hold still: the click goes at the start of the next tick
						poured = stack.is(Items.WATER_BUCKET);
						clicked = true; lastClick = ClickResult.NONE;
						return BUSY;
					}
					if (++notOut > 20) {
						notOut = 0; tried.add(aim.point()); aim = null;
						return tried.size() > 8 ? fail("click does not get through") : BUSY;
					}
				}
				// On the right face, yet the click would give the wrong block or facing: nudging the crosshair about
				// for seconds does not change that and looked like searching, so give up on this block for now.
				else if (++wrongResult > 6) return fail("click gives the wrong state");
			} else if (mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK && !b.getBlockPos().equals(aim.hit().getBlockPos())
				&& ++blocked > 5) {
				// another block takes the crosshair: straight on to another point or face instead of nudging for seconds
				tried.add(aim.point()); aim = null; blocked = 0; misses = 0;
				if (tried.size() > 8) return fail("another block in the way");
				return BUSY;
			}
			if (++misses > 40) {
				// the view is blocked now or the spot does not work any more: think again from here
				misses = 0; aim = null;
				if (++attempts > 3) return fail("crosshair does not reach the face");
				return BUSY;
			}
			Lumberjack.aim(p, aim.point());
			return elapsed() > 12_000 ? fail("busy too long") : BUSY;
		}
		void failed(LocalPlayer p, AfkConfig c) {
			int n = failures.merge(job.pos(), 1, Integer::sum);
			skipped.put(job.pos(), System.currentTimeMillis() + skipFor(n));
			boolean noWater = why.equals("water did not arrive");
			// the water did not get into the block: here it goes the other way round, water first (see rewet)
			if (noWater && wantsWaterlogged(job.want())) { rewet.add(job.pos()); pourFails++; }
			Exchange.emit("build", "action", "block skipped", "block", job.pos().toShortString(), "what", Lumberjack.blockId(job.want().getBlock()).replace("minecraft:", ""), "times", n,
				"stood", Lumberjack.blockId(p.level().getBlockState(job.pos()).getBlock()).replace("minecraft:", ""), "us", scaffold.contains(job.pos()), "reason", why,
				"click", noWater ? lastPour : "", "above", noWater ? Lumberjack.blockId(p.level().getBlockState(job.pos().above()).getBlock()).replace("minecraft:", "") : "");
		}
		String name() { return (job.scaffold() ? "support " : "place ") + Lumberjack.blockId(job.want().getBlock()).replace("minecraft:", "") + " at " + job.pos().toShortString(); }
		String label() { return String.format(java.util.Locale.ROOT, "placing %s at %s", new ItemStack(itemFor(job)).getHoverName().getString(), job.pos().toShortString()); }
	}

	/**
	 * Break one block with the fastest tool that still has durability to spare: look at a face you see and hold on
	 * as long as the crosshair is on it. Nothing that needs a tool you do not have: that would take minutes.
	 */
	static class Dig extends Step {
		final BlockPos pos; boolean looked = false, swapped = false; int misses = 0, wait = 0; long limit = 0; Vec3 point = null;
		Dig(BlockPos pos) { this.pos = pos; }
		int tick(LocalPlayer p, Minecraft mc, AfkConfig c) {
			if (wait > 0) { wait--; return BUSY; }
			Level level = p.level();
			BlockState st = level.getBlockState(pos);
			if (st.isAir() || st.getShape(level, pos).isEmpty()) {
				broken++; lastBreakAt = System.currentTimeMillis();
				if (scaffold.remove(pos)) BuildStore.removeScaffold(pos, dim);
				return DONE;
			}
			// water or lava against it would run the moment it is gone: over the build, over you, and the watchdog
			// took that for someone testing you
			if (holdsBackFluid(level, pos)) {
				skipped.put(pos, System.currentTimeMillis() + 10 * 60_000L);
				Exchange.emit("build", "action", "do not break", "block", pos.toShortString(), "reason", "holds water back");
				return FAILED;
			}
			if (!Lumberjack.withinReach(p, pos)) return FAILED;
			// the tool: select it, or first fetch it from the bag into slots 5-9
			int tool = toolFor(p, st, c);
			if (tool >= 9) {
				if (swapped) return FAILED;
				swapped = true; toHotbar(p, mc, tool); wait = 3; return BUSY;
			}
			int slot = tool >= 0 ? tool : handSlot(p);
			if (p.getInventory().getSelectedSlot() != slot) { p.getInventory().setSelectedSlot(slot); wait = 2; return BUSY; }
			if (limit == 0) {
				float progress = st.getDestroyProgress(p, level, pos);
				if (progress <= 0 || 1 / progress > 400) {   // over 20 seconds: not without the right tool
					Exchange.emit("build", "action", "too hard without a tool", "block", pos.toShortString(), "what", Lumberjack.blockId(st.getBlock()).replace("minecraft:", ""));
					return FAILED;
				}
				limit = (long) (1 / progress * 50 * 2.5) + 4000;
			}
			if (point == null) { point = breakPoint(p, p.getEyePosition(), pos); if (point == null) return FAILED; }
			if (!looked) { looked = true; look.startByAngle(p, point, 0.15f); }
			boolean turning = look.busy(); if (turning) look.tick(p);
			if (pos.equals(Lumberjack.crosshairBlock(mc))) { digTarget = pos; misses = 0; }   // also mid-turn: hit as soon as it is on
			else if (!turning) {
				Lumberjack.aim(p, point);
				if (++misses > 50) { misses = 0; point = null; looked = false; if (elapsed() > 6000) return FAILED; }
			}
			return elapsed() > limit ? FAILED : BUSY;
		}
		void failed(LocalPlayer p, AfkConfig c) {
			int n = failures.merge(pos, 1, Integer::sum);
			// never shorten a longer wait set on the way (water behind it): that brought him back every twenty seconds
			skipped.merge(pos, System.currentTimeMillis() + skipFor(n), Math::max);
		}
		String name() { return "breek " + pos.toShortString(); }
		String label() { return String.format(java.util.Locale.ROOT, "breaking %s", pos.toShortString()); }
	}

	/**
	 * Inventory slot (0-35) of the fastest tool for this block that is not nearly worn out, or -1 if the hand does
	 * as well. On a tie the hotbar wins, so nothing moves around for nothing.
	 */
	private static int toolFor(LocalPlayer p, BlockState st, AfkConfig c) {
		var inv = p.getInventory();
		float best = 1f; int bestSlot = -1;
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) continue;
			float speed = s.getDestroySpeed(st);
			if (speed <= 1f) continue;
			if (s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() <= c.buildToolMin) continue;
			if (speed > best || (speed == best && i < 9 && bestSlot >= 9)) { best = speed; bestSlot = i; }
		}
		return bestSlot;
	}

	/** A hotbar slot to break with by hand: the current one unless it holds a tool that would wear for nothing. */
	private static int handSlot(LocalPlayer p) {
		var inv = p.getInventory();
		int sel = inv.getSelectedSlot();
		if (!inv.getItem(sel).isDamageableItem()) return sel;
		for (int i = 4; i < 9; i++) if (!inv.getItem(i).isDamageableItem()) return i;
		return sel;
	}

	private static int sourceSlot(LocalPlayer p, Item it) {
		for (int i = 9; i < 36; i++) if (p.getInventory().getItem(i).is(it)) return i;
		return -1;
	}

	/**
	 * Move an inventory slot into slots 5-9 without opening a screen (the click goes as a packet, like with an open
	 * inventory). Slots 1-4 are the player's tools; tools and food elsewhere in the hotbar stay too.
	 */
	private static void toHotbar(LocalPlayer p, Minecraft mc, int source) {
		if (source < 9 || source >= 36) return;
		var inv = p.getInventory();
		Map<Item, Integer> still = scan == null ? Map.of() : scan.need;
		int target = -1, leastNeeded = Integer.MAX_VALUE;
		for (int i = 4; i < 9; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) { target = i; break; }
			if (s.isDamageableItem() || s.get(DataComponents.FOOD) != null) continue;
			int needed = still.getOrDefault(s.getItem(), 0);
			if (needed < leastNeeded) { leastNeeded = needed; target = i; }
		}
		if (target < 0) { Exchange.emit("build", "action", "hotbar full", "item", Lumberjack.itemId(inv.getItem(source).getItem())); return; }
		Game.swapWithHotbar(mc.gameMode, p.inventoryMenu.containerId, source, target, p);
	}

	/** Pick up what lies around the build: keep walking to the nearest item. */
	static class Pickup extends Step {
		int picked = 0; Walk walk = null;
		int tick(LocalPlayer p, Minecraft mc, AfkConfig c) {
			if (walk != null) {
				int r = walk.tick(p, mc, c);
				if (r == BUSY) return BUSY;
				walk = null;
				HeldKeys.set(mc.options.keyUp, false); HeldKeys.set(mc.options.keySprint, false);
			}
			if (Lumberjack.freeSlots(p) == 0 || elapsed() > 60_000 || picked > 20) return DONE;
			List<ItemEntity> loose = looseItems(p);
			loose.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
			Nav nav = null;
			for (ItemEntity e : loose) {
				if (e.distanceTo(p) < 1.2) continue;   // being picked up already
				BlockPos cell = BlockPos.containing(e.getX(), e.getY() + 0.1, e.getZ());
				if (!standable(p.level(), cell)) continue;
				if (nav == null) nav = Nav.explore(p, 3000, 0);
				Route route = nav.route(cell);
				// only plain walking: a descent would slip a step in between while this step holds the reins
				if (route == null || route.moves().stream().anyMatch(m -> m == Move.DESCEND || m == Move.BRIDGE || m == Move.PILLAR)) continue;
				walk = new Walk(cell, route); walk.begin(p); picked++;
				return BUSY;
			}
			return DONE;
		}
		String name() { return "pick up"; }
		String label() { return "picking up"; }
	}

	/** Chest: open, write down what is in it, shift-click out what the build still needs, close. */
	static class ChestVisit extends Step {
		final BlockPos pos; int phase = 0, wait = 0, clicks = 0, fillerRoom = 0; boolean looked = false, chatWasOpen = false; AbstractContainerMenu menu = null;
		final Set<Integer> stuck = new HashSet<>(); int lastSlot = -1; ItemStack lastStack = ItemStack.EMPTY;
		Map<Item, Integer> wanted = null; Set<Item> urgent = null, fillers = null; boolean urgentOnly = true, roomMade = false; Vec3 aimAt = null; int putBack = 0, swapped = -1;
		ChestVisit(BlockPos pos) { this.pos = pos; }
		int tick(LocalPlayer p, Minecraft mc, AfkConfig c) {
			if (wait > 0) { wait--; return BUSY; }
			if (phase == 0) {   // open
				if (!Lumberjack.withinReach(p, pos) || !isContainer(p.level().getBlockState(pos))) return FAILED;
				// crouched, a right-click with a block in hand puts that block against the chest instead of opening it
				crouch(false);
				if (p.isCrouching()) return elapsed() > 3000 ? FAILED : BUSY;
				// a point on a face you see: the middle of the box could lie behind the shulker box next to it
				if (!looked) {
					aimAt = breakPoint(p, p.getEyePosition(), pos);
					if (aimAt == null || !openable(p.level(), pos)) return FAILED;
					looked = true; look.startByAngle(p, aimAt, 0.15f);
				}
				Vec3 target = aimAt;
				if (look.busy()) { look.tick(p); return BUSY; }
				if (pos.equals(Lumberjack.crosshairBlock(mc))) {
					chatWasOpen = Game.openScreen(mc) instanceof net.minecraft.client.gui.screens.ChatScreen;
					pending = new Pending(null, null, pos, false, false);
					lastClick = ClickResult.NONE;
					phase = 1; wait = 1; return BUSY;
				}
				Lumberjack.aim(p, target);
				return elapsed() > 8000 ? FAILED : BUSY;
			}
			if (phase == 1) {   // wait until the menu is there
				if (lastClick == ClickResult.MISSED || lastClick == ClickResult.UNSTEADY) { lastClick = ClickResult.NONE; phase = 0; looked = false; return BUSY; }
				if (p.containerMenu instanceof ChestMenu || p.containerMenu instanceof ShulkerBoxMenu) {
					menu = p.containerMenu; phase = 2; wait = R.nextInt(3, 7);
					snapshot(p);
					wanted = shortage(p);
					// Only what this pass can use. With the dirt gone he filled the bag with seeds for the sowing still
					// to come, had no room when the dirt was back, and reported the dirt as gone.
					if (!neededNow.isEmpty()) wanted.keySet().removeIf(it -> !neededNow.contains(it));
					// a tool goes by what fits (see toolWanted), one at a time, never by count
					wanted.keySet().removeIf(Builder::isTool);
					// with a source that refills nearby a few buckets will do, full or empty: the rest comes from there
					if (wanted.containsKey(Items.WATER_BUCKET) && refillPossible(p, System.currentTimeMillis())) {
						int room = Math.max(0, BUCKETS_CARRIED - Lumberjack.countInInventory(p, Items.WATER_BUCKET) - Lumberjack.countInInventory(p, Items.BUCKET));
						BuildStore.Chest here = BuildStore.chest(pos, dimension(p));
						boolean full = here != null && here.contents.getOrDefault(Lumberjack.itemId(Items.WATER_BUCKET), 0) > 0;
						wanted.put(Items.WATER_BUCKET, Math.min(wanted.get(Items.WATER_BUCKET), room));
						if (!full && room > 0) wanted.put(Items.BUCKET, room);
					}
					urgent = new HashSet<>();
					for (Item it : wanted.keySet()) if (Lumberjack.countInInventory(p, it) == 0) urgent.add(it);
					// some scaffold blocks along, if the bag holds few
					fillers = new HashSet<>(fillerItems(c));
					int spare = fillerCount(p, c);
					fillerRoom = c.buildUseScaffold ? Math.max(0, 64 - spare) : 0;
					if (spare == 0) urgent.addAll(fillers);
					// scaffolding for towers, up to a stack
					if (c.buildUseScaffolding) {
						int have = Lumberjack.countInInventory(p, Items.SCAFFOLDING);
						if (have < 64) wanted.merge(Items.SCAFFOLDING, 64 - have, Math::max);
						if (have == 0) urgent.add(Items.SCAFFOLDING);
					}
					return BUSY;
				}
				return elapsed() > 8000 ? FAILED : BUSY;
			}
			if (p.containerMenu != menu) { Exchange.emit("build", "action", "chest closed", "pos", pos.toShortString()); return FAILED; }
			int n = menu.slots.size() - 36;
			if (phase == 2) {
				boolean later = false, hasWanted = false;
				for (int i = n; i < n + 36 && !later; i++) later = forLater(menu.getSlot(i).getItem());
				for (int i = 0; i < n && !hasWanted; i++) {
					ItemStack s = menu.getSlot(i).getItem();
					hasWanted = !s.isEmpty() && wanted.getOrDefault(s.getItem(), 0) > 0;
				}
				// first put back what is for a later pass, as long as this chest has what is wanted now and takes it
				if (!roomMade) {
					if (hasWanted) for (int i = n; i < n + 36; i++) {
						ItemStack s = menu.getSlot(i).getItem();
						if (s.isEmpty() || stuck.contains(i) || !forLater(s)) continue;
						if (stayedPut(i)) break;   // the chest is full: no use trying the other stacks
						lastSlot = i; lastStack = s.copy();
						Game.quickMove(mc.gameMode, menu.containerId, i, p);
						putBack++; wait = R.nextInt(2, 5);
						return BUSY;
					}
					roomMade = true; lastSlot = -1;
				}
				// take out what is short, one stack per click, until the bag is nearly full (with stacks still to
				// go back, the last slot is used too: the next one going back frees it again)
				int keep = later ? 0 : 1;
				for (int i = 0; i < n; i++) {
					ItemStack s = menu.getSlot(i).getItem();
					if (s.isEmpty() || stuck.contains(i) || stayedPut(i) || Lumberjack.freeSlots(p) <= keep) continue;
					boolean tool = toolWanted(p, s);
					if (urgentOnly && !urgent.contains(s.getItem()) && !tool) continue;
					int want = wanted.getOrDefault(s.getItem(), 0);
					boolean filler = want <= 0 && fillers.contains(s.getItem()) && fillerRoom > 0;
					if (want <= 0 && !filler && !tool) continue;
					lastSlot = i; lastStack = s.copy();
					if (filler) fillerRoom -= s.getCount(); else if (!tool) wanted.put(s.getItem(), want - s.getCount());
					Game.quickMove(mc.gameMode, menu.containerId, i, p);
					clicks++; wait = R.nextInt(2, 5);
					return BUSY;
				}
				// A full chest and a full bag swap stack by stack: what was taken left a slot in the chest for the
				// next stack to go back. On as long as that moves something.
				if (later && hasWanted && putBack + clicks > swapped) { swapped = putBack + clicks; roomMade = false; stuck.clear(); lastSlot = -1; return BUSY; }
				// first round only what you had nothing of at all, so a full bag never leaves out what the layer waits for
				if (urgentOnly) { urgentOnly = false; return BUSY; }
				phase = 3; wait = R.nextInt(2, 5); return BUSY;
			}
			snapshot(p);
			close(p, mc);
			Exchange.emit("build", "action", "chest", "pos", pos.toShortString(), "clicks", clicks, "back", putBack, "free", Lumberjack.freeSlots(p));
			// Only a visit that brought something starts a new round. Clearing the round on every plan sent him back
			// to the same chest each second for scaffold dirt it did not have, and he never said he lacked it.
			if (clicks > 0) visitedThisRound.clear();
			return DONE;
		}
		/** Material of the build that this pass has no use for (seeds while the ground is still being laid): back into the chest. */
		private boolean forLater(ItemStack s) {
			if (s.isEmpty()) return false;
			Item it = s.getItem();
			if (scan == null || neededNow.isEmpty() || neededNow.contains(it) || !scan.need.containsKey(it)) return false;
			if (it == Items.BUCKET || it == Items.WATER_BUCKET || it == Items.SCAFFOLDING || fillers.contains(it)) return false;
			return !s.isDamageableItem() && s.get(DataComponents.FOOD) == null;
		}
		/** A click that changed nothing: that slot will not move (inventory full of something else). */
		private boolean stayedPut(int slot) {
			if (slot != lastSlot) return false;
			ItemStack now = menu.getSlot(slot).getItem();
			boolean same = !now.isEmpty() && now.getItem() == lastStack.getItem() && now.getCount() == lastStack.getCount();
			if (same) stuck.add(slot);
			return same;
		}
		/** Write down what is in the chest now, so the next trip goes to the right one. */
		private void snapshot(LocalPlayer p) {
			BuildStore.Chest ch = BuildStore.chest(pos, dimension(p));
			if (ch == null || menu == null) return;
			Map<String, Integer> contents = new LinkedHashMap<>();
			int n = menu.slots.size() - 36;
			// a tool that is nearly gone does not count as stock: he would come back for it every few minutes
			for (int i = 0; i < n; i++) { ItemStack s = menu.getSlot(i).getItem(); if (!s.isEmpty() && !wornOut(s)) contents.merge(Lumberjack.itemId(s.getItem()), s.getCount(), Integer::sum); }
			ch.contents = contents; ch.seen = System.currentTimeMillis();
			BuildStore.save();
		}
		/** Close the chest. If the chat was open, it comes back: without a screen and without focus vanilla otherwise opens its pause menu. */
		private void close(LocalPlayer p, Minecraft mc) {
			p.closeContainer();
			if (chatWasOpen) mc.setScreenAndShow(new net.minecraft.client.gui.screens.ChatScreen("", false));
		}
		void failed(LocalPlayer p, AfkConfig c) { if (p.containerMenu != p.inventoryMenu) close(p, Minecraft.getInstance()); }
		String name() { return "chest " + pos.toShortString(); }
		String label() { return String.format(java.util.Locale.ROOT, "fetching from chest %s", pos.toShortString()); }
	}

	// ---------- screen ----------
	public static String status() {
		if (!isOn) return "off" + (stopReason != null ? " (" + stopReason + ")" : "");
		String what = paused ? "paused" : current != null ? current.label() : scan == null ? "comparing with the schematic" : "planning";
		return what + (scan != null ? ", " + scan.done + "/" + scan.total : "");
	}

	public static String placementName() { return placement == null ? "" : placement.name(); }

	/**
	 * For /buddy build status and the Telegram bridge: the line it always gave, and while building what has
	 * been done, what is left in each pass, what is missing and what it is doing right now.
	 */
	public static String summary() {
		BuildStore.Data d = BuildStore.get();
		String name = isOn ? placementName() : d.placement;
		int done = scan != null ? scan.done : d.done, total = scan != null ? scan.total : d.total;
		String first = String.format(java.util.Locale.ROOT, "Building: %s | placement %s | %s of %s right, %s to place, %s to break, %s wrong | %s chests, %s scaffold", status(), name.isEmpty() ? "-" : name, done, total, scan == null ? "?" : scan.missing.size(),
			scan == null ? "?" : scan.dig.size(), scan == null ? "?" : scan.wrong, d.chests.size(), d.scaffold.size());
		if (!isOn) return first;
		List<String> lines = new ArrayList<>();
		lines.add(first);
		lines.add("Placed" + " " + placed + " · " + "Broken" + " " + broken + " · " + time(System.currentTimeMillis() - startedAt));
		List<String> left = new ArrayList<>();
		if (scan != null) left.add("To do" + " " + scan.missing.size());
		int toFinish = finishing.size() + entityJobs.size();
		if (toFinish > 0) left.add("To finish" + " " + toFinish);
		if (!skipped.isEmpty()) left.add("Skipped" + " " + skipped.size());
		if (!waterWaits.isEmpty()) left.add("Water waits" + " " + waterWaits.size());
		if (!left.isEmpty()) lines.add(String.join(" · ", left));
		if (!noMaterial.isEmpty()) lines.add("Missing" + ": " + names(noMaterial.keySet()));
		if (current != null) lines.add("▶ " + current.label());
		return String.join("\n", lines);
	}

	private static String time(long ms) {
		long min = ms / 60_000;
		return min >= 60 ? (min / 60) + "h" + String.format("%02d", min % 60) : min + "m";
	}
}
