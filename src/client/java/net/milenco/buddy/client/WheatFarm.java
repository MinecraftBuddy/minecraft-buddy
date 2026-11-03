package net.milenco.buddy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.milenco.buddy.version.Game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Works a whole field of crops on foot, for the harvest count rather than the yield: goes to the home beside the
 * field, tidies the inventory into the chests there, and then walks the rows one by one, looking down at the
 * ground in front of the feet. A ripe crop under the crosshair is broken and the bare farmland under it gets a
 * seed; a crop that is still growing is walked past. Rows that need nothing are skipped, and of a row only the
 * stretch with work is walked. When the whole field is growing the buddy stands and looks again every two seconds.
 *
 * One crop is planted ({@code tarweGewas}: wheat seeds, carrots, potatoes, beetroot seeds) and whatever stands ripe is
 * broken, so a field of another crop turns into that one cell by cell as fast as there is something to plant: from a
 * few carrots handed to the buddy, every ripe carrot gives more than the one that goes back in.
 *
 * With a full inventory the buddy walks back to the chests, crafts the wheat into hay bales at the crafting table and
 * stores them; seeds of no use top up a chest that already holds seeds, and what does not fit goes down the
 * stair hole. Chests follow one rule: a chest with
 * a single kind of item only gets that kind, a mixed or empty chest takes anything. When every chest is full the
 * harvest just carries on.
 *
 * The switch survives a restart ({@code tarweAan}): after a kick, a crash or a server restart the buddy goes back to
 * the home and picks the rows up again.
 *
 * With a hoe ({@code tarweSchoffel}) the buddy breaks with the hoe in hand, so its Fortune counts for what a crop drops,
 * and plants from the off hand, as a player does: no change of slot between the break and the seed. The hoe with the
 * most Fortune is the one; a worn one goes back into a chest at the home and another is taken there.
 *
 * Someone else at work on the same floor makes the buddy take another floor ({@link #giveWay}).
 *
 * Every click is one a player makes: the crosshair is really on the block (the view ray is computed here, at
 * the start of the tick, the way vanilla does before a click), a break at most every third tick and a seed at
 * most every fourth, which is what holding the right mouse button gives.
 */
public final class WheatFarm {
	private WheatFarm() {}

	private enum Phase { OFF, HOME, SETTLE, TIDY, GOTO, CLIMB, LANE, IDLE }
	private enum After { TIDY, LANE }
	private enum Need { NONE, HARVEST, PLANT, TILL }
	private enum Tidy { PLAN, REACH, AIM, OPENING, LOADING, CRAFT, DUMP, FETCH, CLOSING, SEEDS_GO, SEEDS_AIM, SEEDS_DROP }
	private enum Purpose { CRAFT, SURVEY, DUMP, FETCH }

	private static final int BUSY = 0, DONE = 1, FAILED = -1;
	/** How far ahead of your feet the crosshair lands while walking a row (a pitch of about 66 degrees). */
	private static final double LANDING = 0.72;
	/** Top of farmland above the block's own y. */
	private static final double FARMLAND_TOP = 0.9375;

	private static Phase phase = Phase.OFF;
	private static long resumeAt = 0, nextResumeCheck = 0;
	private static long pausedUntil = 0, nextBreather = 0, nextNearCheck = 0;
	private static String pauseWhy = "";
	private static long startedAt = 0;
	private static int harvested = 0, planted = 0, tilled = 0, hayStored = 0, laps = 0;
	private static final AfkHelperClient.Look look = new AfkHelperClient.Look();

	// the field, as found on arrival
	private static int x1, x2, z1, z2, fy;
	private static String dim = "";
	private static boolean fromZ2 = false;
	private static BlockPos tidySpot = null;
	private static Vec3 homeSpot = null;

	// the floors of the field (the height of their farmland), the top one first, and the row each one's round is at
	private static final List<Integer> floors = new ArrayList<>();
	private static int floor = 0, homeFloor = 0, resumeFloor = 0, climbTo = 0;
	private static int[] laneAt = new int[0];
	private static Climb climb = null;
	private static After climbAfter = After.LANE;
	// Since when the rows of this floor are being worked, and which floor that is. The clock used to start again at every
	// step onto a floor, and the chests and the seed dump lie on other floors: on 3 Oct the round over the top floor
	// ended 49 s after such a trip, so it was too soon to go down and the buddy began the same floor again.
	private static long floorSince = 0, nextFloorTry = 0;
	private static int rowFloor = -1;
	// under the lowest floor: where to stand and where to aim to throw spare seeds down
	private static BlockPos deepStand = null;
	private static Vec3 deepAim = null;
	private static long noDeepUntil = 0;
	private static boolean fromDump = false;

	// going home
	private static long homeSentAt = 0, settleUntil = 0;
	private static Vec3 homeFrom = null;
	private static int homeAttempts = 0;

	// walking somewhere
	private static Walk walk = null;
	private static After after = After.LANE;
	private static int walkFailures = 0;

	// the rows
	private static int lane = 0, dir = 1, laneZ = 0, endX = 0, frontier = 0;
	private static int waitCell = Integer.MIN_VALUE, waitTicks = 0;
	private static long idleSince = 0, idleCheckAt = 0;
	private static final Map<BlockPos, Long> skipUntil = new HashMap<>();   // cells that would not take the click: leave them for a while
	private static long laneStartedAt = 0, lapStartedAt = 0, nextTripAt = 0, nextInventoryCheck = 0;
	private static boolean haveSeeds = false, haveHoe = false;
	private static boolean lastBreakOwn = false;   // the crop broken last drops what is planted
	// the hoe harvested with this tick (null: by hand), and the Fortune of the one last seen in hand
	private static ItemStack farmHoe = null;
	private static int hoeTold = Integer.MIN_VALUE;
	private static long lastOffhandSwap = 0, nextHoeTripAt = 0;
	// others at work on the field: per player where that player was last seen to move, and when
	private static final Map<java.util.UUID, double[]> seen = new HashMap<>();
	private static long crowdedSince = 0, nextCrowdCheck = 0, noYieldUntil = 0;
	// farmland that does not carry the buddy's crop yet, for the panel: counted every few seconds
	private static int openCount = 0;
	private static long nextOpenCount = 0;
	private static Vec3 lanePrev = null;
	private static int laneStill = 0;
	private static boolean resumeLane = false;
	private static int resumeX = 0;

	// clicks at the start of the tick
	private static long tickNo = 0, lastBreak = -100, lastPlace = -100, slotChanged = -100, lastHotbarSwap = 0;
	private static int breakGap = 3, placeGap = 4;
	private static final ArrayDeque<int[]> clicks = new ArrayDeque<>();   // {0, slot} = click, {1, slot, hotbar} = swap
	private static long clickAt = 0, screenSince = 0;


	// at the chests
	private static Tidy tidy = Tidy.PLAN;
	private static Purpose purpose = Purpose.SURVEY;
	private static BlockPos target = null, table = null, hole = null;
	private static BlockHitResult useHit = null;
	private static AbstractContainerMenu menu = null;
	private static long stepAt = 0, tidyStartedAt = 0, surveyedAt = 0, resultWait = 0;
	private static int tries = 0, tidySteps = 0, movedThisTidy = 0;
	private static boolean approached = false, armorDone = false, craftDone = false, seedsDone = false, foodDone = false, hoeDone = false;
	private static boolean fetchHoe = false, hoeClicked = false;
	private static int hoesBefore = 0;
	private static long nextFoodTripAt = 0;
	private static int fetchLast = -1, foodBefore = 0;
	/** Below this much food more is fetched from the chests, up to twice as much (the two stacks that are kept). */
	private static final int FOOD_LOW = 64;
	private static boolean craftWhole = false, spreadDone = false, takeJunk = false, wheatMayGo = false, storageFull = false, storageFullTold = false, chestsDenied = false, noTableTold = false, hayFull = false, hayFullTold = false;
	private static int dropped = 0;
	private static Item dumpItem = null;
	private static boolean[] keep = new boolean[36];
	private static final List<BlockPos> chests = new ArrayList<>();
	private static final Map<BlockPos, Info> infos = new HashMap<>();
	private static final Set<BlockPos> unreachable = new HashSet<>();
	private static final Set<Item> stuck = new HashSet<>();

	/** What a chest held when it was last open. */
	private static final class Info {
		final Set<Item> types = new HashSet<>();
		final Set<Item> fullFor = new HashSet<>();
		int empty = 0;
		boolean food = false;
		int hoe = -1;   // the most Fortune on a hoe in here that still has durability to spare, -1 without one
		final Set<Item> roomFor = new HashSet<>();   // kinds with a stack that is not full yet
	}

	// ---------- the switch ----------

	public static boolean isOn() { return phase != Phase.OFF; }
	/** Switched on, also while it waits to carry on after a teleport or a new connection. */
	public static boolean wanted() { return AfkConfig.get().wheatOn; }

	private static boolean fieldSet(AfkConfig c) { return c.wheatX1 != c.wheatX2 && c.wheatZ1 != c.wheatZ2; }

	public static void start() {
		AfkConfig c = AfkConfig.get();
		if (isOn() || AfkHelperClient.mc().player == null) return;
		if (!fieldSet(c)) {  return; }
		if (crop() == null) {  return; }
		Jobs.switchedOn(Jobs.Job.WHEAT, "wheat on");
		if (!c.wheatOn) { c.wheatOn = true; AfkConfig.save(); }
		harvested = planted = tilled = hayStored = laps = 0;
		startedAt = System.currentTimeMillis();
		homeAttempts = 0;
		Exchange.emit("wheat", "action", "start", "home", c.wheatHome, "crop", cropId());
		begin();
	}

	public static void stop(String why) {
		AfkConfig c = AfkConfig.get();
		if (!isOn() && !c.wheatOn) return;
		halt();
		if (c.wheatOn) { c.wheatOn = false; AfkConfig.save(); }
		// switched off while it waited to carry on after a restart: it never ran in this game
		long sec = startedAt == 0 ? 0 : Math.max(1, (System.currentTimeMillis() - startedAt) / 1000);
		Exchange.emit("wheat", "action", "done", "reason", why, "harvested", harvested, "planted", planted, "hay", hayStored, "rounds", laps,
			"sec", sec);
		// what was stored only when there was any: a field of carrots makes no hay bales
		String more = hayStored > 0 ? String.format(java.util.Locale.ROOT, ", %1$s hay bales stored", number(hayStored)) : "";
	}


	/** Stand still for a while and then carry on where it was (the watchdog, a player close by, a breather). */
	public static void pause(long ms, String why) {
		if (!isOn()) return;
		long until = System.currentTimeMillis() + ms;
		if (until > pausedUntil) pausedUntil = until;
		pauseWhy = why;
	}

	/** Out of the rows, but still switched on: it starts over from the home after this long. */
	private static void suspend(long ms, String why) {
		boolean was = isOn();
		halt();
		resumeAt = System.currentTimeMillis() + ms;
		if (!was) return;
		Exchange.emit("wheat", "action", "interrupted", "reason", why, "on_in_s", ms / 1000);
	}

	private static void halt() {
		Minecraft mc = AfkHelperClient.mc();
		releaseKeys(mc);
		LocalPlayer p = mc.player;
		if (p != null && menu != null && p.containerMenu == menu) p.closeContainer();
		menu = null; useHit = null; walk = null; climb = null;
		clicks.clear();
		look.stop();
		phase = Phase.OFF;
		pausedUntil = 0;
	}

	private static void releaseKeys(Minecraft mc) {
		HeldKeys.set(mc.options.keyUp, false);
		HeldKeys.set(mc.options.keyDown, false);
		HeldKeys.set(mc.options.keySprint, false);
		HeldKeys.set(mc.options.keyJump, false);
	}

	/** A new connection: the server put the buddy wherever the logout was. Start over from the home shortly. */
	static void newConnection() {
		if (!isOn() && !wanted()) return;
		suspend(rnd(25_000, 45_000), "new connection");
	}

	/** The server announces a teleport (tpa, a warp, a home typed by hand): out of the rows until it is over. */
	static void teleport() {
		if (!isOn() || phase == Phase.HOME || phase == Phase.SETTLE) return;   // our own click on the home
		suspend(rnd(20_000, 40_000), "teleport");
	}


	/**
	 * The field lies on someone's land and this account is not trusted there. On 3 Oct the buddy tried all eight
	 * chests on another player's land four times each and then walked the rows breaking crops the server put straight
	 * back, with a refusal in chat every few seconds. One refusal is enough: no chests means carry on without
	 * storing, no breaking means there is nothing to do here at all.
	 */
	static void chat(String line) {
		if (!isOn()) return;
		// the land plugin's refusals, at the start of a server line (the profile's geenKistToegang, geenBouwToegang)
		var m = Profiles.match("noChestAccess", line, null);
		if (m.find() && phase == Phase.TIDY) {
			unreachable.addAll(chests);
			useHit = null; walk = null;
			if (tidy == Tidy.REACH || tidy == Tidy.AIM || tidy == Tidy.OPENING) tidy = Tidy.PLAN;
			if (!chestsDenied) {
				chestsDenied = true;
				Exchange.emit("wheat", "action", "no permission", "what", "chests", "owner", m.group(1));
			}
			return;
		}
		m = Profiles.match("noBuildAccess", line, null);
		if (m.find()) {
			Exchange.emit("wheat", "action", "no permission", "what", "build", "owner", m.group(1));
			stop("no permission");
		}
	}

	private static void begin() {
		phase = Phase.HOME;
		homeSentAt = 0;
		pausedUntil = 0;
		nextBreather = 0;
		rowFloor = -1;   // the clock of the floor starts with the first row
		crowdedSince = 0;
	}

	// ---------- the numbers (Yield and the Telegram bridge read them) ----------

	static int harvested() { return harvested; }
	static int planted() { return planted; }
	static int hay() { return hayStored; }

	/** Where it is, for the status in Telegram: protocol words, the bridge puts them into English. */
	static Map<String, Object> statusFields() {
		Map<String, Object> f = new java.util.LinkedHashMap<>();
		long now = System.currentTimeMillis();
		// walking between the floors is walking, to the bridge that reads this
		f.put("phase", (phase == Phase.CLIMB ? Phase.GOTO : phase).name().toLowerCase(java.util.Locale.ROOT));
		f.put("floor", floor + 1);
		f.put("floors", floors.size());
		f.put("row", lane + 1);
		f.put("rows", phase == Phase.OFF && x2 == x1 ? 0 : lanes());
		f.put("pause", now < pausedUntil ? pauseWhy : "");
		f.put("on_in_s", phase == Phase.OFF && wanted() ? Math.max(0, (resumeAt - now) / 1000) : 0);
		f.put("rounds", laps);
		f.put("crop", cropId());
		return f;
	}

	/** What the buddy is doing right now, in the player's language. */
	static String state() {
		long now = System.currentTimeMillis();
		if (phase == Phase.OFF) return "waiting to carry on";
		if (now < pausedUntil) return "paused (" + pauseWhy + ")";
		return switch (phase) {
			case HOME, SETTLE -> "to the home";
			case TIDY -> "at the chests";
			case GOTO, CLIMB -> "walking";
			case IDLE -> "waiting for the crop";
			default -> String.format(java.util.Locale.ROOT, "row %1$s of %2$s", lane + 1, lanes());
		};
	}

	// ---------- the panel ----------

	private static String number(long v) { return java.text.NumberFormat.getIntegerInstance(java.util.Locale.GERMANY).format(v); }

	// ---------- end of the tick: where to go and where to look ----------

	static boolean tick(Minecraft mc, LocalPlayer p, AfkConfig c) {
		long now = System.currentTimeMillis();
		if (phase == Phase.OFF) {
			if (c.wheatOn && now >= resumeAt && now >= nextResumeCheck) {
				nextResumeCheck = now + 1000;
				if (p.isAlive() && AfkHelperClient.active() && fieldSet(c) && !othersOn()) {
					Exchange.emit("wheat", "action", "resumed");
					if (startedAt == 0) startedAt = now;
					begin();
				}
			}
			return false;
		}
		if (othersOn()) { stop("farm on"); return false; }
		// the setting was changed to something that does not grow: every ripe crop would be broken and nothing planted back
		if (crop() == null) { stop("unknown crop"); return false; }
		if (!AfkHelperClient.active()) { suspend(60_000, "another server"); return false; }
		if (p.isDeadOrDying()) { suspend(30_000, "dead"); return false; }
		if (AfkHelperClient.isEating()) { releaseKeys(mc); return true; }   // the hand is busy with food: no walking into the next crop

		switch (phase) {
			case HOME -> homeTick(mc, p, c, now);
			case SETTLE -> {
				if (now < settleUntil) break;
				String problem = locate(p, c);
				if (problem != null) { stop(problem); break; }
				Exchange.emit("wheat", "action", "arrived", "x", p.getBlockX(), "y", p.getBlockY(), "z", p.getBlockZ(), "farmland_y", fy, "floors", new ArrayList<>(floors));
				startTidy(true);
			}
			case TIDY -> tidyTick(mc, p, c, now);
			case GOTO, CLIMB, LANE, IDLE -> {
				if (displaced(p)) { Exchange.emit("wheat", "action", "moved", "x", p.getBlockX(), "y", p.getBlockY(), "z", p.getBlockZ()); suspend(rnd(30_000, 60_000), "teleported"); break; }
				if (handsOff(mc, p, now)) { releaseKeys(mc); break; }
				watchPlayers(p, c, now);
				if (now >= nextOpenCount) { nextOpenCount = now + 5000; openCount = openCells(p.level())[1]; }
				if (now < pausedUntil) { releaseKeys(mc); laneStartedAt += 50; break; }   // a pause does not count against the row
				if ((phase == Phase.LANE || phase == Phase.IDLE || phase == Phase.GOTO && after == After.LANE) && giveWay(mc, p, now)) break;
				if (phase == Phase.GOTO) gotoTick(mc, p, now);
				else if (phase == Phase.CLIMB) climbTick(mc, p, now);
				else if (phase == Phase.LANE) laneTick(mc, p, now);
				else { releaseKeys(mc); if (now >= idleCheckAt) startLane(p); }
			}
			default -> { }
		}
		return true;
	}

	private static boolean othersOn() { return Jobs.othersOn(Jobs.Job.WHEAT); }

	/** Somewhere else all of a sudden, without the server saying so in chat. */
	private static boolean displaced(LocalPlayer p) {
		if (!p.level().dimension().identifier().toString().equals(dim)) return true;
		int x = p.getBlockX(), z = p.getBlockZ();
		int lo = floors.isEmpty() ? fy : floors.get(floors.size() - 1), hi = floors.isEmpty() ? fy : floors.get(0);
		return x < x1 - 12 || x > x2 + 12 || z < z1 - 12 || z > z2 + 12 || p.getY() < lo - 3 || p.getY() > hi + 5;
	}

	/** A screen that is not ours is open: wait, and close a menu the server pushed at you after a few seconds. */
	private static boolean handsOff(Minecraft mc, LocalPlayer p, long now) {
		var s = Game.openScreen(mc);
		boolean harmless = s == null || s instanceof net.minecraft.client.gui.screens.ChatScreen || s instanceof net.minecraft.client.gui.screens.PauseScreen;
		if (p.containerMenu != p.inventoryMenu) {
			if (screenSince == 0) screenSince = now;
			if (now - screenSince > 3000) { p.closeContainer(); screenSince = 0; }
			return true;
		}
		screenSince = 0;
		return !harmless;
	}

	/** A stranger close by: stand still until the stranger is gone, like the wood farm does. And a breather now and then. */
	private static void watchPlayers(LocalPlayer p, AfkConfig c, long now) {
		if (now >= nextNearCheck) {
			nextNearCheck = now + 500;
			Player near = Lumberjack.playerNearby(p, c.wheatPauseRadius);
			if (near != null) {
				if (now >= pausedUntil) Exchange.emit("wheat", "action", "pause", "reason", "player nearby", "player", near.getGameProfile().name());
				pause(Math.max(5, c.wheatWaitAfterPlayerSec) * 1000L + rnd(0, 4000), "player nearby");
			}
		}
		if (c.wheatPauseEveryMin <= 0) return;
		if (nextBreather == 0) nextBreather = now + (long) (c.wheatPauseEveryMin * 60_000L * ThreadLocalRandom.current().nextDouble(0.5, 1.5));
		if (now >= nextBreather && now >= pausedUntil) {
			nextBreather = 0;
			long ms = rnd(20_000, 60_000);
			Exchange.emit("wheat", "action", "pause", "reason", "breather", "sec", ms / 1000);
			pause(ms, "breather");
		}
	}

	// ---------- to the home ----------

	private static void homeTick(Minecraft mc, LocalPlayer p, AfkConfig c, long now) {
		if (homeSentAt == 0) {
			if (homeSpot != null && p.level().dimension().identifier().toString().equals(dim) && p.position().distanceTo(homeSpot) < 2.5) { arrived(now, 300); return; }
			if (p.containerMenu != p.inventoryMenu) p.closeContainer();   // MenuPick would take an open chest for the homes menu
			homeFrom = p.position(); homeSentAt = now; homeAttempts++;
			AfkHelperClient.goHome(c.wheatHome);
			return;
		}
		if (p.position().distanceTo(homeFrom) > 3) { arrived(now, rnd(1500, 3000)); return; }
		if (now - homeSentAt < 12_000) return;
		if (p.containerMenu != p.inventoryMenu) p.closeContainer();
		// counted across the retries: a home that is not there must not be asked for every minute for ever
		if (homeAttempts >= 5) { stop("no home"); return; }
		suspend(rnd(30_000, 60_000), "no home");
	}

	private static void arrived(long now, long settle) {
		phase = Phase.SETTLE;
		settleUntil = now + settle;
	}

	/** Find the field around the spot the home put the buddy on. Returns the reason when this is not it. */
	private static String locate(LocalPlayer p, AfkConfig c) {
		x1 = Math.min(c.wheatX1, c.wheatX2); x2 = Math.max(c.wheatX1, c.wheatX2);
		z1 = Math.min(c.wheatZ1, c.wheatZ2); z2 = Math.max(c.wheatZ1, c.wheatZ2);
		Level l = p.level();
		int px = p.getBlockX(), pz = p.getBlockZ();
		// nothing to plant: the whole field would be broken and left bare
		if (crop() == null) return "unknown crop";
		if (px < x1 - 8 || px > x2 + 8 || pz < z1 - 8 || pz > z2 + 8) return "home not by the field";
		// the farmland is the floor you stand on or next to
		int base = Mth.floor(p.getY() + 0.1), best = 0, bestY = 0;
		for (int y = base - 3; y <= base + 1; y++) {
			int n = farmlandAround(l, px, pz, y);
			if (n > best) { best = n; bestY = y; }
		}
		if (best == 0) return "no farmland";
		fy = bestY;
		// Every level with farmland like this one is a floor of the field. Only the floor of the home was known, so
		// the floor built under it was never harvested.
		List<Integer> found = new ArrayList<>();
		for (int y = fy + 24; y >= fy - 24; y--)
			if (y == fy || farmlandAround(l, px, pz, y) >= Math.max(20, best / 3)) found.add(y);
		if (!found.equals(floors)) { floors.clear(); floors.addAll(found); laneAt = new int[floors.size()]; rowFloor = -1; }
		else laneAt[floor] = lane;
		floor = homeFloor = floors.indexOf(fy);
		lane = laneAt[floor];
		dim = l.dimension().identifier().toString();
		tidySpot = new BlockPos(px, fy, pz);
		homeSpot = p.position();
		homeAttempts = 0;
		fromZ2 = Math.abs(pz - z2) <= Math.abs(pz - z1);
		return null;
	}

	private static int farmlandAround(Level l, int px, int pz, int y) {
		int n = 0;
		for (int x = Math.max(x1, px - 12); x <= Math.min(x2, px + 12); x++) for (int z = Math.max(z1, pz - 12); z <= Math.min(z2, pz + 12); z++)
			if (l.getBlockState(new BlockPos(x, y, z)).is(Blocks.FARMLAND)) n++;
		return n;
	}

	// ---------- the field ----------

	private static int lanes() { return z2 - z1 + 1; }
	private static int zOf(int lane) { return fromZ2 ? z2 - lane : z1 + lane; }
	private static boolean inBox(int x, int z) { return x >= x1 && x <= x2 && z >= z1 && z <= z2; }
	private static boolean loaded(Level l, int x, int z) { return l.hasChunk(x >> 4, z >> 4); }

	/** A cell at floor height you can stand on and walk through. */
	private static boolean walkable(Level l, int x, int z) {
		if (!loaded(l, x, z)) return false;
		BlockPos f = new BlockPos(x, fy, z);
		BlockState floor = l.getBlockState(f);
		var shape = floor.getCollisionShape(l, f);
		// open water in the floor has no shape: you would sink into it. A waterlogged slab has one, and carries you.
		if (shape.isEmpty() || shape.max(Direction.Axis.Y) < 0.45) return false;
		for (int dy = 1; dy <= 2; dy++) {
			BlockPos q = f.above(dy);
			BlockState st = l.getBlockState(q);
			if (!st.getCollisionShape(l, q).isEmpty() || Grinder.hazard(l, q)) return false;
		}
		return true;
	}

	private static boolean tillable(BlockState st) { return st.is(Blocks.DIRT) || st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.DIRT_PATH) || st.is(Blocks.COARSE_DIRT); }

	/**
	 * Dirt in the field that was farmland before someone jumped on it: farmland on at least three sides. The floor
	 * around the chests is dirt too, and that must stay a floor.
	 */
	private static boolean trampled(Level l, BlockPos f) {
		int n = 0;
		for (Direction d : Direction.Plane.HORIZONTAL) if (l.getBlockState(f.relative(d)).is(Blocks.FARMLAND)) n++;
		return n >= 3;
	}

	private static Need need(Level l, int x, int z) {
		if (!loaded(l, x, z)) return Need.NONE;
		BlockPos f = new BlockPos(x, fy, z);
		Long skip = skipUntil.get(f);
		if (skip != null) { if (System.currentTimeMillis() < skip) return Need.NONE; skipUntil.remove(f); }
		BlockState above = l.getBlockState(f.above());
		if (above.getBlock() instanceof CropBlock crop) return crop.isMaxAge(above) ? Need.HARVEST : Need.NONE;
		if (!above.isAir()) return Need.NONE;
		BlockState floor = l.getBlockState(f);
		if (floor.is(Blocks.FARMLAND)) return haveSeeds ? Need.PLANT : Need.NONE;
		if (haveHoe && tillable(floor) && trampled(l, f)) return Need.TILL;
		return Need.NONE;
	}

	private static boolean bare(Level l, int x, int z) {
		BlockPos f = new BlockPos(x, fy, z);
		return loaded(l, x, z) && l.getBlockState(f).is(Blocks.FARMLAND) && l.getBlockState(f.above()).isAir();
	}

	static boolean seed(ItemStack st) { return st.getItem() instanceof BlockItem b && b.getBlock() instanceof CropBlock; }
	private static boolean hoe(ItemStack st) { return st.is(ItemTags.HOES); }

	// ---------- the hoe ----------

	private static int fortune(ItemStack st) {
		for (var e : st.getEnchantments().entrySet()) if (e.getKey().is(Enchantments.FORTUNE)) return e.getIntValue();
		return 0;
	}

	private static int left(ItemStack st) { return st.isDamageableItem() ? st.getMaxDamage() - st.getDamageValue() : 9999; }

	/** A hoe with durability to spare: a worn one goes back into a chest instead of breaking in hand. */
	private static boolean usableHoe(ItemStack st) { return hoe(st) && left(st) > AfkConfig.get().wheatHoeMin; }

	/** Better to harvest with: more Fortune, then more durability left. */
	private static boolean betterHoe(ItemStack a, ItemStack b) {
		int fa = fortune(a), fb = fortune(b);
		return fa != fb ? fa > fb : left(a) > left(b);
	}

	/** Inventory slot of the hoe to harvest with; -1 without a usable one, or with the setting off. */
	private static int hoeSlot(LocalPlayer p) {
		if (!AfkConfig.get().wheatHoe) return -1;
		int best = -1;
		for (int i = 0; i < 36; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (usableHoe(st) && (best < 0 || betterHoe(st, p.getInventory().getItem(best)))) best = i;
		}
		return best;
	}

	private static int hoes(LocalPlayer p) { int n = 0; for (int i = 0; i < 36; i++) if (hoe(p.getInventory().getItem(i))) n++; return n; }

	private static ItemStack hoeOf(LocalPlayer p) { int i = hoeSlot(p); return i < 0 ? null : p.getInventory().getItem(i); }

	/** That very hoe, not any hoe: with a plain one in a lower slot the Fortune hoe would stay in the backpack. */
	private static boolean theHoe(ItemStack st) { return farmHoe != null && st == farmHoe; }

	/** The hoe for trampled dirt: the one to harvest with, or with that setting off any hoe, as before. */
	private static boolean tillHoe(ItemStack st) { return AfkConfig.get().wheatHoe ? theHoe(st) : hoe(st); }

	/** What is planted, in the inventory or in the off hand. */
	private static boolean carries(LocalPlayer p) { return has(p, WheatFarm::plants) || plants(p.getOffhandItem()); }

	// ---------- the crop ----------

	private static String cropSetting = null;
	private static Item cropItem = null;

	/**
	 * What is planted ({@code tarweGewas}), by the name of the item or of the crop block: "carrot" and "carrots",
	 * "wheat_seeds" and "wheat". Null when the setting names nothing that grows on farmland.
	 */
	private static Item crop() {
		String setting = AfkConfig.get().wheatCrop;
		if (java.util.Objects.equals(setting, cropSetting)) return cropItem;
		cropSetting = setting;
		return cropItem = cropNamed(setting);
	}

	static Item cropNamed(String setting) {
		String name = setting == null ? "" : setting.trim().toLowerCase(java.util.Locale.ROOT).replace("minecraft:", "");
		for (Item item : BuiltInRegistries.ITEM) {
			if (!(item instanceof BlockItem b) || !(b.getBlock() instanceof CropBlock)) continue;
			String block = BuiltInRegistries.BLOCK.getKey(b.getBlock()).getPath();
			if (name.equals(BuiltInRegistries.ITEM.getKey(item).getPath()) || name.equals(block) || (name + "s").equals(block)) return item;
		}
		return null;
	}

	private static String cropId() { Item c = crop(); return c == null ? "" : BuiltInRegistries.ITEM.getKey(c).getPath(); }

	/**
	 * The one thing put on farmland. Any seed used to do: with carrots to plant and the wheat seeds of the crop
	 * being replaced in the same inventory, the wheat would have gone straight back in.
	 */
	private static boolean plants(ItemStack st) { return !st.isEmpty() && st.getItem() == crop(); }

	/** The buddy's own crop stands here, at any age. */
	private static boolean own(BlockState st) { return crop() instanceof BlockItem b && st.is(b.getBlock()); }

	/**
	 * Farmland on every floor, as far as the field is loaded: {bare, still to plant}. Still to plant is bare farmland
	 * and farmland under another crop, which gets the buddy's own once that is ripe and broken: that is what the ones
	 * carried are for.
	 */
	private static int[] openCells(Level l) {
		int bare = 0, open = 0;
		for (int y : floors) for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) {
			if (!loaded(l, x, z)) continue;
			BlockPos f = new BlockPos(x, y, z);
			if (!l.getBlockState(f).is(Blocks.FARMLAND)) continue;
			BlockState above = l.getBlockState(f.above());
			if (above.isAir()) { bare++; open++; }
			else if (above.getBlock() instanceof CropBlock && !own(above)) open++;
		}
		return new int[] { bare, open };
	}

	private static boolean has(LocalPlayer p, Predicate<ItemStack> what) {
		for (int i = 0; i < 36; i++) if (what.test(p.getInventory().getItem(i))) return true;
		return false;
	}

	// ---------- walking the rows ----------

	private static boolean inLane(int x) { return (endX - x) * dir >= 0; }

	/**
	 * The stretch of a row that needs something, as {first x, last x}; null when the row can be left alone. What is
	 * not loaded yet may hold anything, so that counts: it has to be gone to and looked at.
	 */
	private static int[] work(Level l, int z, int from, int to) {
		int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
		for (int x = Math.max(from, x1); x <= Math.min(to, x2); x++) {
			if (loaded(l, x, z) && need(l, x, z) == Need.NONE) continue;
			lo = Math.min(lo, x); hi = Math.max(hi, x);
		}
		return lo > hi ? null : new int[] { lo, hi };
	}

	/**
	 * Go to the next row that has work, starting with the current one, and in it to the nearest end of the stretch
	 * that needs something. Every row used to be walked from wall to wall: with a field that was mostly bare and no
	 * seed in the inventory the buddy walked row after row of nothing while the ripe wheat stood on the far side (3 Oct).
	 * Rows without work are skipped now; the order stays the same, so every row still comes round as often.
	 */
	private static void startLane(LocalPlayer p) {
		Level l = p.level();
		long now = System.currentTimeMillis();
		farmHoe = hoeOf(p);
		haveSeeds = carries(p);
		haveHoe = has(p, WheatFarm::tillHoe);
		if (floor != rowFloor) { rowFloor = floor; floorSince = now; }
		// nothing to do for minutes: walk a row anyway, so the server does not take the buddy for someone who left
		boolean patrol = idleSince > 0 && now - idleSince > 240_000;
		boolean asked = false;
		for (int k = 0; k < lanes(); k++) {
			int idx = (lane + k) % lanes(), z = zOf(idx);
			// the round over this floor is done: the next floor first, this one again after that
			if (k > 0 && idx == 0 && !patrol) { asked = true; if (toOtherFloor(p, l, now, false)) return; }
			int[] w = patrol ? new int[] { x1, x2 } : work(l, z, x1, x2);
			if (w == null) continue;
			int near = Math.abs(p.getX() - w[0]) <= Math.abs(p.getX() - w[1]) ? w[0] : w[1];
			int d = near == w[0] ? 1 : -1, far = d > 0 ? w[1] : w[0];
			// the corner with the chests and the stair hole is no floor: start at the first cell that is
			int start = near;
			while ((far - start) * d >= 0 && !walkable(l, start, z)) start += d;
			if ((far - start) * d < 0) continue;
			if (idx < lane) lapDone(l, now);
			lane = idx; laneZ = z; dir = d; endX = far; frontier = start;
			resumeLane = false;
			idleSince = 0;
			goTo(start, laneZ, After.LANE);
			return;
		}
		if (!asked && !patrol && toOtherFloor(p, l, now, true)) return;
		// nothing ripe and nothing to plant, anywhere: stand and look again in a moment
		releaseKeys(AfkHelperClient.mc());
		if (idleSince == 0) { idleSince = now; Exchange.emit("wheat", "action", "nothing to do", "seed", haveSeeds); }
		phase = Phase.IDLE;
		idleCheckAt = now + 2000;
	}

	/** Round the rows once: back at the first one. */
	private static void lapDone(Level l, long now) {
		laps++;
		Exchange.emit("wheat", "action", "round", "round", laps, "floor", floor + 1, "harvested", harvested, "planted", planted, "bare", bareCells(l),
			"sec", lapStartedAt == 0 ? 0 : (now - lapStartedAt) / 1000);
		lapStartedAt = now;
	}

	private static void nextLane(LocalPlayer p) {
		if (++lane >= lanes()) {
			long now = System.currentTimeMillis();
			lane = 0; lapDone(p.level(), now);
			if (toOtherFloor(p, p.level(), now, true)) return;
		}
		startLane(p);
	}

	// ---------- the floors ----------

	/** Is there a row with work on this floor? */
	private static boolean workOn(Level l, int which) {
		int keep = fy;
		fy = floors.get(which);
		try {
			for (int k = 0; k < lanes(); k++) if (work(l, zOf(k), x1, x2) != null) return true;
			return false;
		} finally { fy = keep; }
	}

	/**
	 * The round over this floor is done, or it has nothing left: on to the next floor that has work. Not within five
	 * minutes of the first row here while this floor still has work (a minute and a half when it has none), or the buddy
	 * would walk the stairs for a few crops each time.
	 */
	private static boolean toOtherFloor(LocalPlayer p, Level l, long now, boolean counted) {
		if (floors.size() < 2 || now < nextFloorTry) return false;
		if (now - floorSince < (workOn(l, floor) ? 300_000 : 90_000)) return false;
		for (int s = 1; s < floors.size(); s++) {
			int to = (floor + s) % floors.size();
			if (!workOn(l, to) || !workersOn(p, floors.get(to), now).isEmpty()) continue;
			if (!counted) lapDone(l, now);
			lane = 0;
			resumeLane = false;
			startClimb(to, After.LANE);
			return true;
		}
		return false;
	}

	// ---------- others on the field ----------

	/** Remember where everyone in sight was last seen to move: someone standing still is not at work. */
	private static void trackOthers(LocalPlayer p, long now) {
		for (Player o : p.level().players()) {
			if (o == p || o instanceof LocalPlayer || o.getUUID().equals(p.getUUID())) continue;   // freecam puts a second you in the world
			double[] at = seen.get(o.getUUID());
			if (at == null) { seen.put(o.getUUID(), new double[] { o.getX(), o.getZ(), 0 }); continue; }
			if (Math.hypot(o.getX() - at[0], o.getZ() - at[1]) > 1.0) { at[0] = o.getX(); at[1] = o.getZ(); at[2] = now; }
		}
	}

	/** Who else works this floor, as far as can be seen: inside the field, at its height, on the move in the last twenty seconds. */
	private static List<Player> workersOn(LocalPlayer p, int floorY, long now) {
		List<Player> out = new ArrayList<>();
		for (Player o : p.level().players()) {
			if (o == p || o instanceof LocalPlayer || o.getUUID().equals(p.getUUID()) || o.isSpectator()) continue;
			if (!inBox(Mth.floor(o.getX()), Mth.floor(o.getZ())) || Math.abs(o.getY() - (floorY + FARMLAND_TOP)) > 1.5) continue;
			double[] at = seen.get(o.getUUID());
			if (at != null && now - (long) at[2] < 20_000) out.add(o);
		}
		return out;
	}

	/**
	 * Someone else is at work on this floor: take another one. The buddy ran the rows right behind another player, who had
	 * harvested every cell just before the buddy got there (4 Oct). Two players with this mod would both walk off and meet
	 * again on the next floor, so the name decides: the one whose name comes later goes after a few seconds, the
	 * other waits a minute and a half for that one to leave, and goes after all when nobody does (someone farming by hand).
	 * A floor without anyone on it and with work first, else any floor without anyone.
	 */
	private static boolean giveWay(Minecraft mc, LocalPlayer p, long now) {
		if (now < nextCrowdCheck) return false;
		nextCrowdCheck = now + 500;
		trackOthers(p, now);
		if (floors.size() < 2) return false;
		List<Player> others = workersOn(p, fy, now);
		if (others.isEmpty()) { crowdedSince = 0; return false; }
		if (crowdedSince == 0) crowdedSince = now;
		String mine = p.getGameProfile().name(), first = others.get(0).getGameProfile().name();
		for (Player o : others) if (o.getGameProfile().name().compareToIgnoreCase(first) < 0) first = o.getGameProfile().name();
		long wait = mine.compareToIgnoreCase(first) > 0 ? 8_000 : 90_000;
		if (now - crowdedSince < wait || now < noYieldUntil || now < nextFloorTry) return false;
		crowdedSince = 0;
		Level l = p.level();
		int to = -1;
		for (int s = 1; s < floors.size() && to < 0; s++) { int f = (floor + s) % floors.size(); if (workersOn(p, floors.get(f), now).isEmpty() && workOn(l, f)) to = f; }
		for (int s = 1; s < floors.size() && to < 0; s++) { int f = (floor + s) % floors.size(); if (workersOn(p, floors.get(f), now).isEmpty()) to = f; }
		if (to < 0) { noYieldUntil = now + 120_000; return false; }   // someone on every floor: stay
		noYieldUntil = now + 60_000;
		Exchange.emit("wheat", "action", "give way", "for", first, "floor", to + 1, "from", floor + 1, "work", workOn(l, to));
		releaseKeys(mc);
		walk = null; lane = 0; resumeLane = false;
		startClimb(to, After.LANE);
		return true;
	}

	private static void setFloor(int which) {
		laneAt[floor] = lane;
		floor = which;
		fy = floors.get(which);
		lane = laneAt[which];
	}

	private static void startClimb(int to, After then) {
		Exchange.emit("wheat", "action", "to the floor", "floor", to + 1, "from", floor + 1, "farmland_y", floors.get(to), "what_for", then == After.TIDY ? "chests" : "rows");
		climbTo = to; climbAfter = then;
		climb = new Climb(floors.get(to), then == After.TIDY ? tidySpot : null);
		phase = Phase.CLIMB;
	}

	private static void climbTick(Minecraft mc, LocalPlayer p, long now) {
		int r = climb.tick(mc, p);
		if (r == BUSY) return;
		Climb done = climb;
		climb = null;
		releaseKeys(mc);
		if (r == DONE) {
			fromDump = false;
			setFloor(climbTo);
			Exchange.emit("wheat", "action", "floor", "floor", floor + 1, "farmland_y", fy, "x", p.getBlockX(), "z", p.getBlockZ(), "steps", done.walked);
			if (climbAfter == After.TIDY) goTo(tidySpot.getX(), tidySpot.getZ(), After.TIDY);
			else if (resumeLane) goTo(resumeX, laneZ, After.LANE);
			else startLane(p);
			return;
		}
		// say how far the search came: without that nobody can tell what is in the way between the floors
		Exchange.emit("wheat", "action", "no path to the floor", "floor", climbTo + 1, "x", p.getBlockX(), "y", p.getBlockY(), "z", p.getBlockZ(),
			"reached", done.reached, "lowest", done.lowest, "highest", done.highest, "walked", done.walked);
		// on the way to the chests: the home takes the buddy there
		if (climbAfter == After.TIDY) { phase = Phase.HOME; homeSentAt = 0; homeAttempts = 0; return; }
		nextFloorTry = now + 600_000;
		// no way back from where the seeds went down: not there again for half an hour
		if (fromDump) { fromDump = false; deepStand = null; noDeepUntil = now + 30 * 60_000L; }
		resumeLane = false;
		BlockPos on = standingOn(p);
		int at = floors.indexOf(on.getY());
		// somewhere between the floors, or beside the field: from the home again
		if (at < 0 || !walkableOn(p.level(), on)) { phase = Phase.HOME; homeSentAt = 0; homeAttempts = 0; return; }
		setFloor(at);
		startLane(p);
	}

	private static boolean dumpPlace() { return hole != null || deepStand != null; }

	/**
	 * A place under the lowest floor to throw things down: the nearest cell to walk or drop to that lies two
	 * blocks or more under that floor's own ground, with the last cell at the floor's height before it to stand on.
	 * What went down the stair hole of the home landed in the stairwell the buddy walks through on the way to the
	 * floor below, and was all picked up again.
	 */
	private static void findDeep(LocalPlayer p) {
		deepStand = null; deepAim = null;
		if (floors.size() < 2) return;   // one floor: the stair hole of the home, as before
		if (System.currentTimeMillis() < noDeepUntil) return;
		Level l = p.level();
		int lowest = floors.get(floors.size() - 1);
		BlockPos start = standingOn(p), deep = null;
		Map<BlockPos, BlockPos> from = new HashMap<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start); from.put(start, start);
		while (!queue.isEmpty() && from.size() < 60_000) {
			BlockPos cur = queue.poll();
			if (cur.getY() <= lowest - 3) { deep = cur; break; }
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos n = step(l, cur, d);
				if (n == null || from.containsKey(n) || n.getY() < lowest - 12 || n.getY() > floors.get(0) + 6) continue;
				if (n.getX() < x1 - 12 || n.getX() > x2 + 12 || n.getZ() < z1 - 12 || n.getZ() > z2 + 12) continue;
				from.put(n, cur);
				queue.add(n);
			}
		}
		if (deep == null) return;
		List<BlockPos> way = new ArrayList<>();
		for (BlockPos at = deep; !at.equals(start); at = from.get(at)) way.add(at);
		way.add(start);
		Collections.reverse(way);
		int last = -1;
		for (int i = 0; i < way.size(); i++) if (way.get(i).getY() >= lowest - 1) last = i;
		if (last < 0) return;
		BlockPos stand = way.get(last);
		for (int i = last + 1; i < way.size(); i++) {
			BlockPos c = way.get(i);
			if (top(l, c) > top(l, stand) - 1.5) continue;
			if (Math.hypot(c.getX() - stand.getX(), c.getZ() - stand.getZ()) > 3.5) return;   // too far to throw
			deepStand = stand;
			// flat and far, in the direction of the drop: aimed down at the cell itself a stack can catch the edge stood on
			double dx = c.getX() - stand.getX(), dz = c.getZ() - stand.getZ(), h = Math.max(1, Math.hypot(dx, dz));
			deepAim = new Vec3(stand.getX() + 0.5 + dx / h * 3.5, top(l, stand) + 0.9, stand.getZ() + 0.5 + dz / h * 3.5);
			return;
		}
	}

	/**
	 * The block you stand on. At an edge your middle can hang over the drop while the block beside it carries you:
	 * the walk back from the seed dump was then planned from the air over the first stair, sent the buddy off the edge
	 * onto that stair, and there the buddy jumped against the wall for three and a half minutes.
	 */
	private static BlockPos standingOn(LocalPlayer p) {
		Level l = p.level();
		BlockPos under = BlockPos.containing(p.getX(), p.getY() - 0.2, p.getZ());
		if (!free(l, under)) return under;
		for (double dx : new double[] { -0.3, 0.3 }) for (double dz : new double[] { -0.3, 0.3 }) {
			BlockPos c = BlockPos.containing(p.getX() + dx, p.getY() - 0.2, p.getZ() + dz);
			if (!free(l, c) && Math.abs(top(l, c) - p.getY()) < 0.2) return c;
		}
		return under;
	}

	private static boolean walkableOn(Level l, BlockPos floorBlock) {
		int keep = fy;
		fy = floorBlock.getY();
		try { return walkable(l, floorBlock.getX(), floorBlock.getZ()); } finally { fy = keep; }
	}

	/** Nothing here that a body bumps into (a carpet or a layer of snow does not count). */
	private static boolean free(Level l, BlockPos q) {
		var shape = l.getBlockState(q).getCollisionShape(l, q);
		return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.2;
	}

	/** A block to stand on, with room for a body over it and nothing there that hurts or drowns. */
	private static boolean stands(Level l, BlockPos b) {
		var shape = l.getBlockState(b).getCollisionShape(l, b);
		if (shape.isEmpty() || shape.max(Direction.Axis.Y) < 0.45) return false;
		for (int dy = 1; dy <= 2; dy++) {
			BlockPos q = b.above(dy);
			if (!free(l, q) || Grinder.hazard(l, q) || !l.getFluidState(q).isEmpty()) return false;
		}
		return true;
	}

	private static double top(Level l, BlockPos b) {
		var shape = l.getBlockState(b).getCollisionShape(l, b);
		return b.getY() + (shape.isEmpty() ? 1 : shape.max(Direction.Axis.Y));
	}

	/**
	 * The block you stand on after one step from {@code a} to this side, or null when there is no way: level, one
	 * block up with a jump (with the room over your head for it), or up to three down. Never a drop onto farmland,
	 * which tramples it; a jump up onto it from a whole block lower is fine.
	 */
	private static BlockPos step(Level l, BlockPos a, Direction d) {
		int nx = a.getX() + d.getStepX(), nz = a.getZ() + d.getStepZ();
		if (!loaded(l, nx, nz)) return null;
		double from = top(l, a);
		for (int y = a.getY() + 1; y >= a.getY() - 3; y--) {
			BlockPos b = new BlockPos(nx, y, nz);
			if (free(l, b)) continue;                // nothing to stand on at this height: look lower
			if (!stands(l, b)) return null;          // a wall, or no room over it
			double rise = top(l, b) - from;
			if (rise > 1.25 || rise < -3.2) return null;
			boolean farmland = l.getBlockState(b).is(Blocks.FARMLAND);
			if (rise > 0.6) return free(l, a.above(3)) && (!farmland || rise >= 0.8) ? b : null;
			if (rise < -0.6) return !farmland && free(l, new BlockPos(nx, a.getY() + 2, nz)) ? b : null;
			return b;
		}
		return null;
	}

	/**
	 * Walk from one floor of the field to another over whatever joins them (stairs, ledges, a hole with steps), by the
	 * moves of {@link #step}. Ends on a cell of the target floor: farmland of the field, or for the chests a cell
	 * beside the spot of the home.
	 */
	private static final class Climb {
		final int toY;
		final BlockPos near, exact;
		List<BlockPos> route = null;
		int idx = 0, replans = 0, still = 0, ticks = 0, walked = 0, reached = 0, lowest = 0, highest = 0, onThis = 0;
		Vec3 previous = null;

		Climb(int toY, BlockPos near) { this.toY = toY; this.near = near; this.exact = null; }
		/** To this very cell (the block to stand on). */
		Climb(BlockPos exact) { this.toY = exact.getY(); this.near = null; this.exact = exact; }

		int tick(Minecraft mc, LocalPlayer p) {
			Level l = p.level();
			if (++ticks > 3600) return FAILED;
			if (route == null) {
				if (!p.onGround()) return BUSY;   // falling or jumping: plan from the landing
				route = path(l, standingOn(p));
				idx = 0;
				if (route == null) return FAILED;
			}
			if (idx >= route.size()) return DONE;
			// cut corners over a level stretch of the floor walked on, as the walk over the field does
			if (standingOn(p).getY() == fy) for (int j = Math.min(route.size() - 1, idx + 10); j > idx; j--) {
				boolean level = true;
				for (int i = idx; i <= j && level; i++) level = route.get(i).getY() == fy;
				BlockPos w = route.get(j);
				if (level && clearLine(l, p.position(), w.getX() + 0.5, w.getZ() + 0.5)) { walked += j - idx; idx = j; break; }
			}
			BlockPos wp = route.get(idx);
			double top = top(l, wp);
			Vec3 m = new Vec3(wp.getX() + 0.5, p.getY(), wp.getZ() + 0.5);
			double d = Math.hypot(m.x - p.getX(), m.z - p.getZ());
			if (d < 0.45 && p.onGround() && Math.abs(p.getY() - top) < 0.7) {
				idx++; walked++; still = 0; onThis = 0;
				if (idx < route.size()) return BUSY;
				// there: let go at once, one more tick of walking carried the buddy over the middle to the edge
				HeldKeys.set(mc.options.keyUp, false); HeldKeys.set(mc.options.keyJump, false);
				return DONE;
			}
			steerTo(mc, p, m, d);
			// the last bit to the spot to stand on is coasted: with a beacon the buddy slid on past the middle of the cell
			if (idx == route.size() - 1 && d < 0.55 && Math.abs(p.getY() - top) < 0.7) HeldKeys.set(mc.options.keyUp, false);
			HeldKeys.set(mc.options.keyJump, top - p.getY() > 0.6 && d < 1.4 && p.onGround());
			// Not getting anywhere: plan again from here. Standing still shows it, and so does a waypoint that
			// is not reached in five seconds: jumping against a wall moves the buddy, so there was never any standing still.
			boolean stuck = ++onThis > 100;
			if (previous != null && p.position().distanceToSqr(previous) < 0.0001) { if (++still > 40) stuck = true; } else still = 0;
			previous = p.position();
			if (stuck) {
				still = 0; onThis = 0; route = null;
				HeldKeys.set(mc.options.keyJump, false);
				if (++replans > 10) return FAILED;
			}
			return BUSY;
		}

		/** Breadth-first over the cells you can step to: both floors and what lies between them. */
		private List<BlockPos> path(Level l, BlockPos start) {
			Map<BlockPos, BlockPos> from = new HashMap<>();
			ArrayDeque<BlockPos> queue = new ArrayDeque<>();
			queue.add(start); from.put(start, start);
			lowest = highest = start.getY();
			int lo = floors.get(floors.size() - 1) - 4, hi = floors.get(0) + 6;
			BlockPos goal = null;
			while (!queue.isEmpty() && from.size() < 60_000) {
				BlockPos cur = queue.poll();
				if (exact != null ? cur.equals(exact) : cur.getY() == toY && (near != null ? Math.abs(cur.getX() - near.getX()) <= 2 && Math.abs(cur.getZ() - near.getZ()) <= 2
					: inBox(cur.getX(), cur.getZ()) && l.getBlockState(cur).is(Blocks.FARMLAND))) { goal = cur; break; }
				for (Direction d : Direction.Plane.HORIZONTAL) {
					BlockPos n = step(l, cur, d);
					if (n == null || from.containsKey(n) || n.getY() < lo || n.getY() > hi) continue;
					if (n.getX() < x1 - 12 || n.getX() > x2 + 12 || n.getZ() < z1 - 12 || n.getZ() > z2 + 12) continue;
					from.put(n, cur);
					queue.add(n);
					lowest = Math.min(lowest, n.getY()); highest = Math.max(highest, n.getY());
				}
			}
			reached = from.size();
			if (goal == null) return null;
			List<BlockPos> out = new ArrayList<>();
			for (BlockPos at = goal; !at.equals(start); at = from.get(at)) out.add(at);
			Collections.reverse(out);
			return out;
		}
	}

	/** Farmland with nothing on it, in the whole field; -1 when part of the field is not loaded. */
	private static int bareCells(Level l) {
		int n = 0;
		for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) {
			if (!loaded(l, x, z)) return -1;
			if (bare(l, x, z)) n++;
		}
		return n;
	}

	private static void enterLane(long now) {
		phase = Phase.LANE;
		laneStartedAt = now; lanePrev = null; laneStill = 0;
		if (lapStartedAt == 0) lapStartedAt = now;
	}

	private static void laneTick(Minecraft mc, LocalPlayer p, long now) {
		Level l = p.level();
		double px = p.getX(), pz = p.getZ();
		// With no seed left, the crop broken next drops some, and they are in the inventory half a second later: wait
		// for those, or that cell stays bare for a whole round. Only after the buddy's own crop: another one drops nothing
		// to plant, and the buddy would stand a second and a half at each.
		farmHoe = hoeOf(p);
		haveSeeds = carries(p) || lastBreakOwn && tickNo - lastBreak <= 30 && Lumberjack.freeSlots(p) > 0;
		haveHoe = has(p, WheatFarm::tillHoe);
		int hoeFortune = farmHoe == null ? -1 : fortune(farmHoe);
		if (hoeFortune != hoeTold) {
			hoeTold = hoeFortune;
			Exchange.emit("wheat", "action", "hoe", "on", farmHoe != null, "fortune", Math.max(0, hoeFortune), "left", farmHoe == null ? 0 : left(farmHoe));
		}

		// full of wheat and seeds: to the chests, and back to this cell afterwards
		if (now >= nextInventoryCheck) {
			nextInventoryCheck = now + 1000;
			boolean hungry = now >= nextFoodTripAt && foodCount(p) < FOOD_LOW;
			// no usable hoe any more (worn, or none yet): the chests at the home may hold one
			boolean hoeless = AfkConfig.get().wheatHoe && farmHoe == null && !chests.isEmpty() && now >= nextHoeTripAt;
			boolean full = Lumberjack.freeSlots(p) == 0;
			// Not between breaking a crop and planting its cell: the trip would leave the cell bare for a round. Look again
			// in two ticks. Only with something to plant there: breaking a field of another crop with nothing to put back,
			// the trip would never start.
			if (full && !hungry && haveSeeds && lastBreak > lastPlace && tickNo - lastBreak < 40) nextInventoryCheck = now + 100;
			if (hungry || hoeless || now >= nextTripAt && full) {
				releaseKeys(mc);
				resumeLane = true; resumeX = Mth.floor(px);
				Exchange.emit("wheat", "action", "to the chests", "row", lane + 1, "floor", floor + 1, "reason", hungry ? "food" : hoeless ? "hoe" : "full");
				// the chests stand on the floor of the home: there first
				resumeFloor = floor;
				if (floor != homeFloor) startClimb(homeFloor, After.TIDY);
				else goTo(tidySpot.getX(), tidySpot.getZ(), After.TIDY);
				return;
			}
		}
		if (now - laneStartedAt > 240_000) { Exchange.emit("wheat", "action", "row took too long", "row", lane + 1); releaseKeys(mc); nextLane(p); return; }

		// past the cells that need nothing, and past the ones you are already beyond
		while (inLane(frontier)) {
			double d = (frontier + 0.5 - px) * dir;
			if (d > 6) break;
			Need n = need(l, frontier, laneZ);
			if (n != Need.NONE && d >= -0.25) break;
			frontier += dir;
		}
		if (!inLane(frontier)) {
			// the stretch is done; with the seeds picked up on the way the bare farmland further along is work now
			int[] more = dir > 0 ? work(l, laneZ, frontier, x2) : work(l, laneZ, x1, frontier);
			if (more == null) { releaseKeys(mc); nextLane(p); return; }
			endX = dir > 0 ? more[1] : more[0];
			return;
		}

		int cur = Mth.floor(px);
		double dT = (frontier + 0.5 - px) * dir;
		boolean pending = dT <= 6;
		// A cell that will not take the click (the server put the crop back, no seed can reach the hotbar): standing
		// in front of it for good helps nobody. After a couple of seconds on to the next one; the next round tries again.
		if (pending && dT < 0.75) {
			if (frontier != waitCell) { waitCell = frontier; waitTicks = 0; }
			else if (++waitTicks > 50) {
				Exchange.emit("wheat", "action", "slot skipped", "x", frontier, "z", laneZ, "needed", need(l, frontier, laneZ).name());
				skipUntil.put(new BlockPos(frontier, fy, laneZ), now + 300_000);
				frontier += dir;
				return;
			}
		}
		boolean last = !inLane(cur + dir);
		boolean blocked = !last && !walkable(l, cur + dir, laneZ);
		if (blocked && (frontier - cur) * dir >= 1) {
			// no floor ahead (open water, the stair hole, a chest): around it to the next cell of this row, if there is one
			int nx = cur + dir;
			while (inLane(nx) && !walkable(l, nx, laneZ)) nx += dir;
			releaseKeys(mc);
			if (!inLane(nx)) { nextLane(p); return; }
			if ((nx - frontier) * dir > 0) frontier = nx;
			resumeLane = true; resumeX = nx;
			goTo(nx, laneZ, After.LANE);
			return;
		}

		// Look at the ground just ahead. Close to a cell that needs something, keep the crosshair inside that cell,
		// on its far half: the crop in the cell before it stands in the way of the near half.
		double aim = LANDING;
		boolean hold = true;
		if (pending) {
			if (dT <= LANDING) aim = Math.max(0.15, Math.min(LANDING, dT + 0.35));
			if (dT < 0.5) hold = false;   // you are on it: stand until it is done, the next step would carry the crosshair past
		}
		if ((last || blocked) && (cur + 0.5 - px) * dir < 0.05) hold = false;
		float yaw = (float) Math.toDegrees(Math.atan2(-(dir * 3.0), laneZ + 0.5 - pz));
		float pitch = (float) Math.toDegrees(Math.atan2(p.getEyeY() - (fy + FARMLAND_TOP), aim));
		float dyaw = Mth.wrapDegrees(yaw - p.getYRot()), dpitch = pitch - p.getXRot();
		MouseTurn.toward(p, p.getYRot() + Mth.clamp(dyaw, -14f, 14f), p.getXRot() + Mth.clamp(dpitch, -9f, 9f));
		boolean walking = hold && Math.abs(dyaw) < 35;
		HeldKeys.set(mc.options.keyUp, walking);
		HeldKeys.set(mc.options.keyDown, false);
		HeldKeys.set(mc.options.keySprint, false);

		// walking against something: leave this row
		if (walking && lanePrev != null && p.position().distanceToSqr(lanePrev) < 0.0001) {
			if (++laneStill > 60) { Exchange.emit("wheat", "action", "stuck", "row", lane + 1, "x", cur, "z", laneZ); releaseKeys(mc); nextLane(p); return; }
		} else laneStill = 0;
		lanePrev = p.position();
	}

	// ---------- start of the tick: the clicks ----------

	public static void startTick(Minecraft mc) {
		tickNo++;
		if (phase == Phase.OFF) return;
		LocalPlayer p = mc.player;
		if (p == null || mc.gameMode == null || mc.level == null) return;
		long now = System.currentTimeMillis();
		farmHoe = hoeOf(p);

		// clicks in your own inventory (armour, a seed to the hotbar): no chest may be open for those
		if (!clicks.isEmpty()) {
			if (p.containerMenu == p.inventoryMenu && now >= clickAt) {
				int[] k = clicks.poll();
				if (k[0] == 0) Game.click(mc.gameMode, p.inventoryMenu.containerId, k[1], p);
				else Game.swapWithHotbar(mc.gameMode, p.inventoryMenu.containerId, k[1], k[2], p);
				clickAt = now + rnd(140, 320);
			}
			return;
		}
		if (phase == Phase.TIDY) { tidyClicks(mc, p, now); return; }
		if (phase != Phase.LANE || now < pausedUntil || AfkHelperClient.isEating() || p.containerMenu != p.inventoryMenu) return;
		var screen = Game.openScreen(mc);
		if (screen != null && !(screen instanceof net.minecraft.client.gui.screens.ChatScreen) && !(screen instanceof net.minecraft.client.gui.screens.PauseScreen)) return;

		BlockHitResult hit = pickBlock(p);
		if (hit == null) return;
		Level l = p.level();
		BlockPos pos = hit.getBlockPos();
		if (!inBox(pos.getX(), pos.getZ())) return;
		BlockState st = l.getBlockState(pos);

		if (pos.getY() == fy + 1 && st.getBlock() instanceof CropBlock crop) {
			if (!crop.isMaxAge(st) || tickNo - lastBreak < breakGap) return;
			// with the hoe in hand: its Fortune counts for what the crop drops
			if (farmHoe != null && !inHand(p, WheatFarm::theHoe, now)) return;
			lastBreakOwn = own(st);
			mc.gameMode.startDestroyBlock(pos, hit.getDirection());
			Game.swingAttack(p);
			// the break is predicted locally, so the block tells right away whether it went
			if (!(l.getBlockState(pos).getBlock() instanceof CropBlock)) harvested++;
			lastBreak = tickNo;
			breakGap = 3 + (ThreadLocalRandom.current().nextInt(6) == 0 ? 1 : 0);
			return;
		}

		// only the top of the floor: a right-click on anything else opens chests and flips levers
		if (pos.getY() != fy || hit.getDirection() != Direction.UP || !l.getBlockState(pos.above()).isAir()) return;
		boolean plant = st.is(Blocks.FARMLAND);
		if (!plant && !(tillable(st) && trampled(l, pos))) return;
		if (plant ? !readyToPlant(p, now) : !inHand(p, WheatFarm::tillHoe, now)) return;
		if (tickNo - lastPlace < placeGap) return;
		InteractionHand used = rightClick(mc, p, hit);
		if (used != null) {
			Game.swingUse(p, used);
			if (plant) planted++; else tilled++;
		}
		lastPlace = tickNo;
		// four ticks is what holding the button gives; now and then a little longer, like a finger that slips
		placeGap = 4 + (ThreadLocalRandom.current().nextInt(8) == 0 ? ThreadLocalRandom.current().nextInt(1, 4) : 0);
	}

	/**
	 * Ready to put the crop on farmland. With a hoe the buddy keeps that in hand and plants from the off hand, as a
	 * player does who harvests with a Fortune hoe: no change of slot between the break and the seed, and what is
	 * picked up tops the off hand up by itself. Without a hoe the crop itself is in hand.
	 */
	private static boolean readyToPlant(LocalPlayer p, long now) {
		if (farmHoe == null) return inHand(p, WheatFarm::plants, now);
		if (!inHand(p, WheatFarm::theHoe, now)) return false;
		if (plants(p.getOffhandItem())) return true;
		toOffhand(p, now);
		return false;
	}

	/** The largest stack of what is planted goes into the off hand; what was there comes back in its place. */
	private static void toOffhand(LocalPlayer p, long now) {
		if (now - lastOffhandSwap < 1500) return;
		int source = -1;
		for (int i = 0; i < 36; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (plants(st) && (source < 0 || st.getCount() > p.getInventory().getItem(source).getCount())) source = i;
		}
		if (source < 0) return;
		lastOffhandSwap = now;
		clicks.add(new int[] { 1, source < 9 ? 36 + source : source, 40 });   // the hotbar sits at 36-44 in the inventory menu, button 40 is the off hand
	}

	/**
	 * A right-click on a block the way the game itself does it: the main hand first, and when that does nothing with
	 * the block the off hand. Returns the hand that did something. Through useOn(): a carrot is food, and in AFK mode
	 * a click with food in hand never reaches the block.
	 */
	private static InteractionHand rightClick(Minecraft mc, LocalPlayer p, BlockHitResult hit) {
		InteractionResult r = AfkHelperClient.useOn(p, InteractionHand.MAIN_HAND, hit);
		if (r.consumesAction()) return InteractionHand.MAIN_HAND;
		ItemStack main = p.getMainHandItem();
		// only past a hoe or an empty hand: anything else the game would go on to use on the air (a pearl, a bucket)
		if (r instanceof InteractionResult.Fail || !(main.isEmpty() || hoe(main)) || p.getOffhandItem().isEmpty()) return null;
		if (!main.isEmpty()) mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
		return AfkHelperClient.useOn(p, InteractionHand.OFF_HAND, hit).consumesAction() ? InteractionHand.OFF_HAND : null;
	}

	/** The block the crosshair is on right now, the way vanilla works it out before a click. */
	private static BlockHitResult pickBlock(LocalPlayer p) {
		HitResult hr = p.pick(p.blockInteractionRange(), 1f, false);
		return hr instanceof BlockHitResult b && hr.getType() == HitResult.Type.BLOCK ? b : null;
	}

	/**
	 * Is this in your hand, ready to click with? Selects its hotbar slot (the click then waits a few ticks: the
	 * server has to hear about the slot first) and fetches it from the backpack when the hotbar has none.
	 */
	private static boolean inHand(LocalPlayer p, Predicate<ItemStack> what, long now) {
		if (what.test(p.getMainHandItem())) return tickNo - slotChanged >= 5;
		for (int i = 0; i < 9; i++) if (what.test(p.getInventory().getItem(i))) {
			p.getInventory().setSelectedSlot(i);
			slotChanged = tickNo;
			return false;
		}
		toHotbar(p, what, now);
		return false;
	}

	private static void toHotbar(LocalPlayer p, Predicate<ItemStack> what, long now) {
		if (now - lastHotbarSwap < 3000) return;
		int source = -1;
		for (int i = 9; i < 36; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (what.test(st) && (source < 0 || st.getCount() > p.getInventory().getItem(source).getCount())) source = i;
		}
		// only in the off hand, from the time with a hoe: back to the hotbar
		boolean fromOffhand = source < 0 && what.test(p.getOffhandItem());
		if (source < 0 && !fromOffhand) return;
		int slot = -1;
		for (int i = 8; i >= 0 && slot < 0; i--) if (p.getInventory().getItem(i).isEmpty()) slot = i;
		// not the slot with the buddy's food while another will do: the buddy eats from the hotbar, and what is handed over
		// to plant lands in the backpack when the hotbar is full of wheat and seeds
		// Only a slot that holds what is being fetched is no place for it. Every slot with what is planted used to be
		// refused, for the hoe as well: with a hotbar of carrots, an axe and a shovel the hoe that was picked up stayed in
		// the backpack, and the buddy walked the rows past every ripe crop (5 Oct).
		for (int i = 8; i >= 0 && slot < 0; i--) { ItemStack st = p.getInventory().getItem(i); if (!st.isDamageableItem() && !what.test(st) && (plants(st) || st.get(DataComponents.FOOD) == null)) slot = i; }
		for (int i = 8; i >= 0 && slot < 0; i--) { ItemStack st = p.getInventory().getItem(i); if (!st.isDamageableItem() && !what.test(st)) slot = i; }
		// a hotbar of nothing but tools: one of those makes room
		for (int i = 8; i >= 0 && slot < 0; i--) if (!what.test(p.getInventory().getItem(i))) slot = i;
		if (slot < 0) return;
		lastHotbarSwap = now;
		clicks.add(fromOffhand ? new int[] { 1, 36 + slot, 40 } : new int[] { 1, source, slot });   // backpack slots carry the same number in the inventory menu
	}

	// ---------- walking to a cell ----------

	private static void goTo(int x, int z, After then) {
		walk = new Walk(x, z);
		after = then;
		phase = Phase.GOTO;
	}

	private static void gotoTick(Minecraft mc, LocalPlayer p, long now) {
		int r = walk.tick(mc, p);
		if (r == BUSY) return;
		walk = null;
		releaseKeys(mc);
		if (after == After.TIDY) {
			// close enough to the chests is enough; if not, the home takes the buddy there
			if (r == DONE || Math.hypot(tidySpot.getX() + 0.5 - p.getX(), tidySpot.getZ() + 0.5 - p.getZ()) <= 3) startTidy(false);
			else { Exchange.emit("wheat", "action", "no path to the chests"); phase = Phase.HOME; homeSentAt = 0; homeAttempts = 0; }
			return;
		}
		if (r == DONE) { walkFailures = 0; enterLane(now); return; }
		Exchange.emit("wheat", "action", "no path", "row", lane + 1, "x", p.getBlockX(), "z", p.getBlockZ());
		if (++walkFailures >= 6) { stop("no path"); return; }
		nextLane(p);
	}

	/**
	 * Walk over the floor of the field to a cell: a path around what has no floor, followed with the forward key
	 * and the view turned where you go. The path is planned as far as the world is loaded and planned again from
	 * there, so the far side of the field needs no long view distance.
	 */
	private static final class Walk {
		final int tx, tz;
		List<BlockPos> route = null;
		int idx = 0, replans = 0, still = 0, ticks = 0;
		Vec3 previous = null;

		Walk(int tx, int tz) { this.tx = tx; this.tz = tz; }

		int tick(Minecraft mc, LocalPlayer p) {
			Level l = p.level();
			if (++ticks > 3000) return FAILED;   // counted in ticks walked, so a pause on the way does not end it
			if (route == null) {
				route = plan(l, p.getBlockX(), p.getBlockZ(), tx, tz);
				idx = 0;
				if (route == null) return FAILED;
			}
			if (idx >= route.size()) {
				if (p.getBlockX() != tx || p.getBlockZ() != tz) {
					// end of what could be planned from back there: plan on from here
					if (++replans > 16) return FAILED;
					route = null;
					return BUSY;
				}
				Vec3 m = new Vec3(tx + 0.5, p.getY(), tz + 0.5);
				double d = Math.hypot(m.x - p.getX(), m.z - p.getZ());
				if (d < 0.3) return DONE;
				steer(mc, p, m, d);
				return BUSY;
			}
			// cut corners: the farthest point of the path that lies in a straight free line
			for (int j = Math.min(route.size() - 1, idx + 10); j > idx; j--) {
				BlockPos w = route.get(j);
				if (clearLine(l, p.position(), w.getX() + 0.5, w.getZ() + 0.5)) { idx = j; break; }
			}
			BlockPos wp = route.get(idx);
			Vec3 m = new Vec3(wp.getX() + 0.5, p.getY(), wp.getZ() + 0.5);
			double d = Math.hypot(m.x - p.getX(), m.z - p.getZ());
			if (d < 0.45) { idx++; return BUSY; }
			steer(mc, p, m, d);
			if (previous != null && p.position().distanceToSqr(previous) < 0.0001) {
				if (++still > 40) { still = 0; route = null; if (++replans > 16) return FAILED; }
			} else still = 0;
			previous = p.position();
			return BUSY;
		}

		private void steer(Minecraft mc, LocalPlayer p, Vec3 m, double distance) { steerTo(mc, p, m, distance); }
	}

	/** Turn toward a point and walk there: the view goes where you go, no sprinting. */
	private static void steerTo(Minecraft mc, LocalPlayer p, Vec3 m, double distance) {
		float yaw = (float) Math.toDegrees(Math.atan2(-(m.x - p.getX()), m.z - p.getZ()));
		float delta = Mth.wrapDegrees(yaw - p.getYRot());
		float pitch = p.getXRot() + Mth.clamp(25f - p.getXRot(), -6f, 6f) * 0.4f;   // slowly to a normal walking posture
		float step = distance > 2 ? 16f : 10f;
		MouseTurn.toward(p, p.getYRot() + Mth.clamp(delta, -step, step), pitch);
		HeldKeys.set(mc.options.keyUp, Math.abs(delta) < 55);
		HeldKeys.set(mc.options.keyDown, false);
		HeldKeys.set(mc.options.keySprint, false);
	}

	private static boolean clearLine(Level l, Vec3 a, double bx, double bz) {
		double d = Math.hypot(bx - a.x, bz - a.z);
		int n = Math.max(1, (int) Math.ceil(d / 0.25));
		for (int i = 1; i <= n; i++) {
			double t = (double) i / n, x = a.x + (bx - a.x) * t, z = a.z + (bz - a.z) * t;
			for (double ox : new double[] { -0.32, 0.32 }) for (double oz : new double[] { -0.32, 0.32 })
				if (!walkable(l, Mth.floor(x + ox), Mth.floor(z + oz))) return false;
		}
		return true;
	}

	/**
	 * Breadth-first over the walkable cells within forty blocks. Leads to the target, or else to the cell nearest
	 * to it that can be reached (the rest of the way is not loaded yet, or is closed off). Null when that is the
	 * cell you are already on.
	 */
	private static List<BlockPos> plan(Level l, int sx, int sz, int tx, int tz) {
		BlockPos start = new BlockPos(sx, fy, sz), goal = new BlockPos(tx, fy, tz);
		if (start.equals(goal)) return List.of();
		Map<BlockPos, BlockPos> from = new HashMap<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(start); from.put(start, start);
		BlockPos best = start;
		double bestD = start.distSqr(goal);
		while (!queue.isEmpty() && from.size() < 7000) {
			BlockPos cur = queue.poll();
			if (cur.equals(goal)) { best = cur; break; }
			double d = cur.distSqr(goal);
			if (d < bestD) { bestD = d; best = cur; }
			for (Direction dr : Direction.Plane.HORIZONTAL) {
				BlockPos n = cur.relative(dr);
				if (from.containsKey(n) || Math.abs(n.getX() - sx) > 40 || Math.abs(n.getZ() - sz) > 40) continue;
				if (n.getX() < x1 - 10 || n.getX() > x2 + 10 || n.getZ() < z1 - 10 || n.getZ() > z2 + 10) continue;
				if (!walkable(l, n.getX(), n.getZ())) continue;
				from.put(n, cur);
				queue.add(n);
			}
		}
		if (best.equals(start)) return null;
		List<BlockPos> out = new ArrayList<>();
		for (BlockPos at = best; !at.equals(start); at = from.get(at)) out.add(at);
		Collections.reverse(out);
		return out;
	}

	// ---------- at the chests ----------

	private static void startTidy(boolean first) {
		Minecraft mc = AfkHelperClient.mc();
		LocalPlayer p = mc.player;
		releaseKeys(mc);
		phase = Phase.TIDY;
		tidy = Tidy.PLAN;
		long now = System.currentTimeMillis();
		tidyStartedAt = now; stepAt = now + rnd(300, 700);
		tidySteps = 0; movedThisTidy = 0; dropped = 0;
		armorDone = false; craftDone = false; seedsDone = false; foodDone = false; hoeDone = false;
		stuck.clear(); unreachable.clear();
		if (first) chestsDenied = false;
		menu = null; useHit = null; walk = null;
		// what a chest held half an hour ago says little: the owner empties them in between
		if (first || storageFull || now - surveyedAt > 30 * 60_000L) { infos.clear(); surveyedAt = now; }
		if (p != null) { scan(p.level()); if (first || deepStand == null) findDeep(p); }
		if (first) Exchange.emit("wheat", "action", "surroundings", "chests", chests.size(), "crafting table", table == null ? "" : table.toShortString(),
			"gap", hole == null ? "" : hole.toShortString(), "stand", tidySpot.toShortString(), "drop spot", deepStand == null ? "" : deepStand.toShortString());
	}

	/** The chests, the crafting table and the stair hole around the spot the home put the buddy on. */
	private static void scan(Level l) {
		chests.clear();
		table = null; hole = null;
		Set<BlockPos> seen = new LinkedHashSet<>();
		double tableD = 1e9, holeD = 1e9;
		for (int dx = -10; dx <= 10; dx++) for (int dz = -10; dz <= 10; dz++) for (int y = fy - 1; y <= fy + 5; y++) {
			BlockPos b = new BlockPos(tidySpot.getX() + dx, y, tidySpot.getZ() + dz);
			BlockState st = l.getBlockState(b);
			double d = dx * dx + dz * dz;
			boolean close = Math.abs(dx) <= 6 && Math.abs(dz) <= 6 && y >= fy && y <= fy + 4;
			if (close && (st.getBlock() instanceof ChestBlock || st.getBlock() instanceof BarrelBlock)) seen.add(canonical(l, b));
			else if (st.is(Blocks.CRAFTING_TABLE) && d < tableD) { tableD = d; table = b; }   // the table may stand a little further off
		}
		chests.addAll(seen);
		chests.sort((a, b) -> Double.compare(a.distSqr(tidySpot), b.distSqr(tidySpot)));
		infos.keySet().retainAll(seen);
		// the hole: a cell with no floor where the field has one, with a cell beside it to stand on
		for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) {
			int x = tidySpot.getX() + dx, z = tidySpot.getZ() + dz;
			if (x < x1 - 1 || x > x2 + 1 || z < z1 - 1 || z > z2 + 1) continue;
			BlockPos f = new BlockPos(x, fy, z);
			if (!l.getBlockState(f).isAir() || !l.getBlockState(f.above()).isAir() || !l.getBlockState(f.above(2)).isAir()) continue;
			if (standBeside(l, f) == null) continue;
			double d = dx * dx + dz * dz;
			if (d < holeD) { holeD = d; hole = f; }
		}
	}

	/** Both halves of a double chest are one chest: the half with the lowest coordinates stands for it. */
	private static BlockPos canonical(Level l, BlockPos pos) {
		BlockPos other = otherHalf(l, pos);
		if (other == null) return pos;
		return pos.compareTo(other) <= 0 ? pos : other;
	}

	private static BlockPos otherHalf(Level l, BlockPos pos) {
		BlockState st = l.getBlockState(pos);
		if (!(st.getBlock() instanceof ChestBlock) || !st.hasProperty(ChestBlock.TYPE) || st.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return null;
		Direction facing = st.getValue(ChestBlock.FACING);
		return pos.relative(st.getValue(ChestBlock.TYPE) == ChestType.LEFT ? facing.getClockWise() : facing.getCounterClockWise());
	}

	/**
	 * A point on this chest (either half) or table that a view ray from this eye position reaches first, within reach;
	 * the nearest one. On 3 Oct the buddy aimed at the side of a chest with a second chest on top of it, and the ray hit the
	 * upper one every time.
	 */
	private static Vec3 sightPoint(LocalPlayer p, Vec3 eye, BlockPos target) {
		Level l = p.level();
		BlockPos other = otherHalf(l, target);
		double reach = p.blockInteractionRange();
		Vec3 best = null; double bestD = 1e9;
		for (BlockPos b : other == null ? List.of(target) : List.of(target, other)) for (Direction face : Direction.values()) {
			for (double u : new double[] { 0.5, 0.3, 0.7 }) for (double v : new double[] { 0.5, 0.3, 0.7 }) {
				Vec3 pt = facePoint(b, face, u, v);
				double d = eye.distanceTo(pt);
				if (d > reach - 0.1 || d >= bestD) continue;
				// on into the block a little: a chest is smaller than the block around it
				Vec3 end = pt.add(pt.subtract(eye).normalize().scale(0.4));
				BlockHitResult hr = l.clip(new net.minecraft.world.level.ClipContext(eye, end, net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
				if (hr.getType() != HitResult.Type.BLOCK || !canonical(l, hr.getBlockPos()).equals(target) || eye.distanceTo(hr.getLocation()) > reach) continue;
				best = pt; bestD = d;
			}
		}
		return best;
	}

	private static Vec3 facePoint(BlockPos b, Direction face, double u, double v) {
		double[] c = { b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5 };
		int a = face.getAxis().ordinal(), i = (a + 1) % 3, j = (a + 2) % 3;
		c[a] += face.getAxisDirection().getStep() * 0.5;
		c[i] += u - 0.5; c[j] += v - 0.5;
		return new Vec3(c[0], c[1], c[2]);
	}

	/** The nearest cell, not the one you stand on, from where some point of it can be seen and reached. */
	private static BlockPos standWithSight(LocalPlayer p, BlockPos target) {
		Level l = p.level();
		BlockPos here = new BlockPos(p.getBlockX(), fy, p.getBlockZ()), best = null;
		double bd = 1e9;
		for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
			int x = target.getX() + dx, z = target.getZ() + dz;
			BlockPos cell = new BlockPos(x, fy, z);
			if (cell.equals(here) || !walkable(l, x, z)) continue;
			double d = cell.distSqr(here);
			if (d >= bd) continue;
			double floorTop = fy + l.getBlockState(cell).getCollisionShape(l, cell).max(Direction.Axis.Y);
			if (sightPoint(p, new Vec3(x + 0.5, floorTop + p.getEyeHeight(), z + 0.5), target) == null) continue;
			best = cell; bd = d;
		}
		return best;
	}

	/** A walkable cell right beside this block. */
	private static BlockPos standBeside(Level l, BlockPos b) {
		BlockPos best = null; double bd = 1e9;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos q = new BlockPos(b.getX() + d.getStepX(), fy, b.getZ() + d.getStepZ());
			if (!walkable(l, q.getX(), q.getZ())) continue;
			double dist = q.distSqr(tidySpot);
			if (dist < bd) { bd = dist; best = q; }
		}
		return best;
	}

	private static void tidyTick(Minecraft mc, LocalPlayer p, AfkConfig c, long now) {
		if (now - tidyStartedAt > 300_000 || tidySteps > 400) { Exchange.emit("wheat", "action", "chests took too long"); finishTidy(p, now); return; }
		if (look.busy()) { look.tick(p); return; }
		if (now < stepAt || !clicks.isEmpty()) return;
		Level l = p.level();
		switch (tidy) {
			case PLAN -> planTidy(p, now);
			case REACH -> {
				if (walk != null) {
					int r = walk.tick(mc, p);
					if (r == BUSY) return;
					walk = null; releaseKeys(mc);
					if (r == FAILED) { giveUp(p, "no path there"); tidy = Tidy.PLAN; return; }
				}
				// a point of the chest that the view ray really reaches from here; a chest on top of it or beside it hides part of it
				Vec3 point = sightPoint(p, p.getEyePosition(), target);
				if (point != null) { look.startByAngle(p, point, 0.04f); tidy = Tidy.AIM; return; }
				BlockPos stand = approached ? null : standWithSight(p, target);
				if (stand == null) { giveUp(p, approached ? "not in sight after a step" : "nowhere in sight or reach"); tidy = Tidy.PLAN; return; }
				approached = true;
				walk = new Walk(stand.getX(), stand.getZ());
			}
			case AIM -> {
				BlockHitResult hit = pickBlock(p);
				if (hit != null && canonical(l, hit.getBlockPos()).equals(target)) {
					useHit = hit;   // the click itself goes at the start of the next tick
					tidy = Tidy.OPENING;
					stepAt = now; tidySteps++;
					return;
				}
				if (++tries >= 3) { giveUp(p, "not in sight: " + (hit == null ? "nothing" : Lumberjack.blockId(l.getBlockState(hit.getBlockPos()).getBlock()) + " at " + hit.getBlockPos().toShortString())); tidy = Tidy.PLAN; return; }
				tidy = Tidy.REACH;
			}
			case OPENING -> {
				if (useHit != null) return;
				if (p.containerMenu != p.inventoryMenu) {
					menu = p.containerMenu;
					tidy = Tidy.LOADING;
					stepAt = now + rnd(250, 450);   // the items arrive a tick after the menu
				} else if (now - stepAt > 2500) {
					if (++tries >= 4) { giveUp(p, "does not open"); tidy = Tidy.PLAN; } else tidy = Tidy.REACH;
				}
			}
			case LOADING -> opened(p, now);
			case CLOSING -> {
				if (p.containerMenu != p.inventoryMenu) p.closeContainer();
				menu = null;
				tidy = Tidy.PLAN;
				stepAt = now + rnd(250, 500);
			}
			case SEEDS_GO -> {
				if (climb != null) {
					int r = climb.tick(mc, p);
					if (r == BUSY) return;
					Climb done = climb;
					climb = null; releaseKeys(mc);
					if (r == FAILED) {
						// no way there after all: look for one afresh next time, and use the hole of the home now if there is one
						Exchange.emit("wheat", "action", "no path to the drop spot", "x", p.getBlockX(), "y", p.getBlockY(), "z", p.getBlockZ(), "reached", done.reached);
						deepStand = null; tidy = Tidy.PLAN;
						return;
					}
					setFloor(floors.size() - 1);   // standing at the lowest floor now
					fromDump = true;
					look.startByAngle(p, deepAim, 0.1f);
					tidy = Tidy.SEEDS_AIM;
					return;
				}
				if (walk != null) {
					int r = walk.tick(mc, p);
					if (r == BUSY) return;
					walk = null; releaseKeys(mc);
					if (r == FAILED) { hole = null; tidy = Tidy.PLAN; return; }
				}
				look.startByAngle(p, new Vec3(hole.getX() + 0.5, fy + 0.2, hole.getZ() + 0.5), 0.1f);
				tidy = Tidy.SEEDS_AIM;
			}
			case SEEDS_AIM -> { tidy = Tidy.SEEDS_DROP; stepAt = now + rnd(200, 500); }
			default -> { }   // CRAFT, DUMP and SEEDS_DROP are clicks: start of the tick
		}
	}

	/** What is next at the chests: armour on, wheat into bales, everything that may go into a chest, spare seeds down the hole. */
	private static void planTidy(LocalPlayer p, long now) {
		tidySteps++;
		if (p.containerMenu != p.inventoryMenu) { p.closeContainer(); stepAt = now + 300; return; }
		if (!armorDone) { armorDone = true; if (wearBest(p)) return; }
		keep = keepFlags(p);
		boolean canCraft = table != null && !unreachable.contains(table);
		// Whether the hay still fits decides whether crafting is worth it. When no chest takes hay any more, the wheat
		// goes down the stair hole as it is, like the spare seeds, and the work carries on.
		if (dumpPlace() && (count(p, Items.WHEAT) >= 9 || count(p, Items.HAY_BLOCK) > 0))
			for (BlockPos chest : chests) if (!infos.containsKey(chest) && !unreachable.contains(chest)) { open(chest, Purpose.SURVEY); return; }
		hayFull = dumpPlace() && choose(Items.HAY_BLOCK) == null;
		if (hayFull && !hayFullTold) { hayFullTold = true;  }
		if (!hayFull) hayFullTold = false;
		/*
		 * Wheat only goes into a chest as bales. Without a crafting table it used to go in as it was, "rather than
		 * filling the inventory for good": on 3 Oct the table could not be used and ten stacks of loose wheat ended up
		 * in the chests, which is exactly what the owner had asked not to happen. Now it stays in the inventory and the buddy says so.
		 */
		wheatMayGo = false;
		if (!canCraft && !hayFull && count(p, Items.WHEAT) >= 9 && !noTableTold) {
			noTableTold = true;
			Exchange.emit("wheat", "action", "no crafting table", "crafting table", table == null ? "" : table.toShortString());
		}
		if (canCraft) noTableTold = false;
		if (!craftDone && canCraft && !hayFull && count(p, Items.WHEAT) >= 9) { open(table, Purpose.CRAFT); return; }

		// hay first: it gets the chest of its own, the rest takes what is left
		List<Item> items = new ArrayList<>();
		for (int pass = 0; pass < 2; pass++) for (int i = 0; i < 36; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (st.isEmpty() || keep[i] || items.contains(st.getItem()) || stuck.contains(st.getItem())) continue;
			if (seed(st) && dumpPlace() && !seedChest(st.getItem())) continue;   // no chest keeps seeds: down the hole
			if (st.is(Items.WHEAT) && !wheatMayGo) continue;
			if (hayFull && st.is(Items.HAY_BLOCK)) continue;                        // no room for hay: down the hole
			if ((pass == 0) == st.is(Items.HAY_BLOCK)) items.add(st.getItem());
		}
		for (Item item : items) {
			for (BlockPos chest : chests) if (!infos.containsKey(chest) && !unreachable.contains(chest)) { open(chest, Purpose.SURVEY); return; }
			BlockPos chest = choose(item);
			if (chest == null) {
				// seeds that no chest takes any more go down the hole; that is no full storage
				if (!(seed(new ItemStack(item)) && dumpPlace())) stuck.add(item);
				continue;
			}
			dumpItem = item;
			open(chest, Purpose.DUMP);
			return;
		}
		// under a stack of food: more from the chest that holds some (the owner keeps bread there)
		if (!foodDone && foodCount(p) < FOOD_LOW) {
			for (BlockPos chest : chests) if (!infos.containsKey(chest) && !unreachable.contains(chest)) { open(chest, Purpose.SURVEY); return; }
			foodDone = true;
			for (BlockPos chest : chests) {
				Info info = infos.get(chest);
				if (info == null || !info.food || unreachable.contains(chest)) continue;
				fetchLast = -1; foodBefore = foodCount(p); fetchHoe = false;
				open(chest, Purpose.FETCH);
				return;
			}
			Exchange.emit("wheat", "action", "no food in the chests", "food", foodCount(p));
		}
		// A hoe to harvest with: no usable one carried, or one in a chest with more Fortune than the buddy's. The one
		// carried before goes into a chest on the next pass, as it is no longer kept. Nothing beats Fortune III, so then no look.
		if (AfkConfig.get().wheatHoe && !hoeDone) {
			ItemStack mine = hoeOf(p);
			int have = mine == null ? -1 : fortune(mine);
			if (have < 3 && Lumberjack.freeSlots(p) > 0) {
				for (BlockPos chest : chests) if (!infos.containsKey(chest) && !unreachable.contains(chest)) { open(chest, Purpose.SURVEY); return; }
				BlockPos best = null;
				int bestFortune = have;
				for (BlockPos chest : chests) {
					Info info = infos.get(chest);
					if (info != null && !unreachable.contains(chest) && info.hoe > bestFortune) { bestFortune = info.hoe; best = chest; }
				}
				hoeDone = true;
				if (best != null) { fetchHoe = true; hoeClicked = false; open(best, Purpose.FETCH); return; }
				if (mine == null) Exchange.emit("wheat", "action", "no hoe in the chests", "chests", chests.size());
			} else hoeDone = true;
		}
		if (!seedsDone && dropSlot(p) >= 0) {
			if (deepStand != null) {
				Exchange.emit("wheat", "action", "to the drop spot", "stand", deepStand.toShortString());
				climb = new Climb(deepStand);
				tidy = Tidy.SEEDS_GO;
				return;
			}
			BlockPos stand = hole == null ? null : standBeside(p.level(), hole);
			if (stand != null) { walk = new Walk(stand.getX(), stand.getZ()); tidy = Tidy.SEEDS_GO; return; }
		}
		finishTidy(p, now);
	}

	private static void finishTidy(LocalPlayer p, long now) {
		if (p.containerMenu != p.inventoryMenu) p.closeContainer();
		menu = null; walk = null;
		boolean full = !stuck.isEmpty();
		storageFull = full || hayFull;   // look into the chests afresh next time: the owner may have emptied them
		int free = Lumberjack.freeSlots(p);
		Exchange.emit("wheat", "action", "chests", "dumped", movedThisTidy, "hay", hayStored, "full", full, "hay_full", hayFull, "in_gap", dropped,
			"free", free, "chests", chests.size(), "unreachable", unreachable.size());
		if (full && !storageFullTold && !chestsDenied) { storageFullTold = true;  }
		if (!full) storageFullTold = false;
		// nothing came free: walking back every minute helps nobody. Look again in ten minutes, the chests may be emptied by then.
		nextTripAt = now + (full || free == 0 ? 600_000 : 20_000);
		// still short of food: the chests had none. Not back every minute for that; half an hour gives the owner time to put some in.
		nextFoodTripAt = foodCount(p) < FOOD_LOW ? now + 30 * 60_000L : 0;
		// and the same for a hoe: the chests had no usable one
		nextHoeTripAt = !AfkConfig.get().wheatHoe || hoeSlot(p) >= 0 ? 0 : now + 30 * 60_000L;
		boolean seedAtHand = hoeSlot(p) >= 0;   // with a hoe, planting is from the off hand
		for (int i = 0; i < 9; i++) if (plants(p.getInventory().getItem(i))) seedAtHand = true;
		if (!seedAtHand) { lastHotbarSwap = 0; toHotbar(p, WheatFarm::plants, now); }
		// Back to the row that was left, on the floor it was left on. From another floor, or from the place where the seeds
		// went down (beside the field, a block lower than the farmland), the walk between the floors leads there.
		climb = null;
		if (resumeLane && resumeFloor >= floors.size()) resumeLane = false;
		int to = resumeLane ? resumeFloor : floor;
		BlockPos on = standingOn(p);
		boolean onField = on.getY() == fy && walkable(p.level(), on.getX(), on.getZ());
		// not again within ten minutes of a walk that found no way: that ended at the home, and would do so for ever
		if (floors.size() > 1 && now >= nextFloorTry && (to != floor || !onField)) startClimb(to, After.LANE);
		else if (to != floor) { resumeLane = false; startLane(p); }
		else if (resumeLane) goTo(resumeX, laneZ, After.LANE);
		else startLane(p);
	}

	/** This chest or table cannot be used from here: leave it for this visit, and say why, so it need not be guessed afterwards. */
	private static void giveUp(LocalPlayer p, String why) {
		unreachable.add(target);
		Exchange.emit("wheat", "action", "unreachable", "block", Lumberjack.blockId(p.level().getBlockState(target).getBlock()), "pos", target.toShortString(),
			"reason", why, "goal", purpose.name().toLowerCase(java.util.Locale.ROOT), "stand", p.blockPosition().toShortString());
	}

	private static void open(BlockPos pos, Purpose what) {
		target = pos; purpose = what;
		tries = 0; approached = false;
		tidy = Tidy.REACH;
	}

	/** The menu is open and filled. */
	private static void opened(LocalPlayer p, long now) {
		if (p.containerMenu != menu) { tidy = Tidy.PLAN; return; }
		if (purpose == Purpose.CRAFT) {
			if (!(menu instanceof CraftingMenu)) { giveUp(p, "no crafting table menu: " + menu.getClass().getSimpleName() + " with " + menu.slots.size() + " slots"); tidy = Tidy.CLOSING; return; }
			craftWhole = false; spreadDone = false; resultWait = 0;
			tidy = Tidy.CRAFT;
			return;
		}
		if (menu.slots.size() <= 36) { giveUp(p, "no chest menu: " + menu.slots.size() + " slots"); tidy = Tidy.CLOSING; return; }
		Info info = read(menu, infos.get(target));
		infos.put(target, info);
		if (purpose == Purpose.SURVEY) { tidy = Tidy.CLOSING; stepAt = now + rnd(150, 400); return; }
		if (purpose == Purpose.FETCH) { tidy = Tidy.FETCH; return; }
		if (!accepts(info, dumpItem)) { tidy = Tidy.CLOSING; return; }   // someone changed it since the last look
		// a mixed chest and an empty one take anything; hay stays on its own
		takeJunk = dumpItem != Items.HAY_BLOCK && info.types.size() != 1;
		tidy = Tidy.DUMP;
	}

	private static Info read(AbstractContainerMenu m, Info before) {
		Info info = new Info();
		int n = m.slots.size() - 36;
		for (int i = 0; i < n; i++) {
			ItemStack st = m.slots.get(i).getItem();
			if (st.isEmpty()) info.empty++; else info.types.add(st.getItem());
			if (edible(st)) info.food = true;
			if (usableHoe(st)) info.hoe = Math.max(info.hoe, fortune(st));
			if (!st.isEmpty() && st.getCount() < st.getMaxStackSize()) info.roomFor.add(st.getItem());
		}
		// full for an item stays full as long as no slot came free
		if (before != null && info.empty == 0) info.fullFor.addAll(before.fullFor);
		return info;
	}

	/** One kind in the chest: only that kind. Several kinds, or nothing: anything. */
	private static boolean accepts(Info info, Item item) {
		return (info.types.size() != 1 || info.types.contains(item)) && !info.fullFor.contains(item)
			&& (info.empty > 0 || info.roomFor.contains(item));
	}

	/**
	 * Spare seeds fill up a chest that already holds seeds; only what does not fit goes down the stair hole.
	 * While a chest has not been looked into yet, it might be that chest.
	 */
	private static boolean seedChest(Item item) {
		for (BlockPos chest : chests) {
			if (unreachable.contains(chest)) continue;
			Info info = infos.get(chest);
			if (info == null || info.types.contains(item) && accepts(info, item)) return true;
		}
		return false;
	}

	/** The chest for this item: its own kind first; then, for hay, an empty one before a mixed one, and for the rest the other way round. */
	private static BlockPos choose(Item item) {
		boolean hay = item == Items.HAY_BLOCK;
		boolean seedsToHole = dumpPlace() && seed(new ItemStack(item));
		BlockPos best = null; int bestRank = 9;
		for (BlockPos chest : chests) {
			Info info = infos.get(chest);
			if (info == null || unreachable.contains(chest) || !accepts(info, item)) continue;
			if (seedsToHole && !info.types.contains(item)) continue;   // seeds only top up a chest that has seeds
			int rank = info.types.size() == 1 ? 0 : info.types.isEmpty() == hay ? 1 : 2;
			if (rank < bestRank) { bestRank = rank; best = chest; }
		}
		return best;
	}

	/**
	 * Food to eat: what the eat list names, or anything edible when that list is empty. Never what is planted: carrots
	 * and potatoes are food too, and a field of them is not a stock of food to keep or fetch.
	 */
	private static boolean edible(ItemStack st) {
		if (st.isEmpty() || st.get(DataComponents.FOOD) == null || plants(st)) return false;
		var allowed = AfkConfig.get().eatItems;
		if (allowed.isEmpty()) return true;
		String id = Lumberjack.itemId(st.getItem()).replace("minecraft:", "");   // the list is written with and without the namespace
		for (String a : allowed) if (a.replace("minecraft:", "").equals(id)) return true;
		return false;
	}

	private static int foodCount(LocalPlayer p) {
		int n = 0;
		for (int i = 0; i < 36; i++) { ItemStack st = p.getInventory().getItem(i); if (edible(st)) n += st.getCount(); }
		return n;
	}

	private static int count(LocalPlayer p, Item item) {
		int n = 0;
		for (int i = 0; i < 36; i++) { ItemStack st = p.getInventory().getItem(i); if (st.is(item)) n += st.getCount(); }
		return n;
	}

	/**
	 * Inventory slot of what goes down the stair hole: a seed stack that is not kept, and wheat and bales when no chest
	 * takes hay.
	 */
	private static int dropSlot(LocalPlayer p) {
		for (int i = 0; i < 36; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (keep[i] || st.isEmpty()) continue;
			if (seed(st) || hayFull && (st.is(Items.WHEAT) || st.is(Items.HAY_BLOCK))) return i;
		}
		return -1;
	}

	// ----- what stays in the inventory -----

	/**
	 * Kept: what the buddy lives and works on, the best pickaxe, axe and shovel, the hoe to harvest with, two stacks of food,
	 * two stacks of scaffolding, and what the field still needs of the crop planted (a stack more than it has cells without it).
	 * The rest goes, the seeds of another crop too.
	 */
	private static boolean[] keepFlags(LocalPlayer p) {
		boolean[] k = new boolean[36];
		keepBest(p, k, ItemTags.PICKAXES, Blocks.STONE.defaultBlockState());
		keepBest(p, k, ItemTags.AXES, Blocks.OAK_LOG.defaultBlockState());
		keepBest(p, k, ItemTags.SHOVELS, Blocks.DIRT.defaultBlockState());
		// the hoe to harvest with; a worn one, or one with less Fortune, goes back into a chest
		if (AfkConfig.get().wheatHoe) { int h = hoeSlot(p); if (h >= 0) k[h] = true; }
		else keepBest(p, k, ItemTags.HOES, Blocks.HAY_BLOCK.defaultBlockState());
		// the food actually eaten first; anything else edible only fills up what is left of the two stacks
		keepStacks(p, k, WheatFarm::edible, 2);
		int foodKept = 0;
		for (int i = 0; i < 36; i++) if (k[i]) { ItemStack st = p.getInventory().getItem(i); if (st.get(DataComponents.FOOD) != null) foodKept++; }
		keepStacks(p, k, st -> st.get(DataComponents.FOOD) != null && !plants(st), Math.max(0, 2 - foodKept));
		keepStacks(p, k, st -> st.is(Items.SCAFFOLDING), 2);
		keepStacks(p, k, WheatFarm::plants, Math.min(36, (openCells(p.level())[1] + 127) / 64));
		return k;
	}

	private static void keepBest(LocalPlayer p, boolean[] k, TagKey<Item> kind, BlockState on) {
		int best = -1; double bestScore = -1;
		for (int i = 0; i < 36; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (!st.is(kind)) continue;
			double left = st.isDamageableItem() ? 1.0 - (double) st.getDamageValue() / st.getMaxDamage() : 1.0;
			double score = st.getDestroySpeed(on) * 100 + Math.min(8, st.getEnchantments().size()) * 3 + left;
			if (score > bestScore) { bestScore = score; best = i; }
		}
		if (best >= 0) k[best] = true;
	}

	private static void keepStacks(LocalPlayer p, boolean[] k, Predicate<ItemStack> what, int max) {
		for (int n = 0; n < max; n++) {
			int best = -1;
			for (int i = 0; i < 36; i++) {
				ItemStack st = p.getInventory().getItem(i);
				if (k[i] || st.isEmpty() || !what.test(st)) continue;
				if (best < 0 || st.getCount() > p.getInventory().getItem(best).getCount()) best = i;
			}
			if (best < 0) return;
			k[best] = true;
		}
	}

	// ----- armour -----

	private static final EquipmentSlot[] ARMOR = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };

	private static double armorScore(ItemStack st) {
		if (st.isEmpty()) return -1;
		double v = 0;
		for (var e : st.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY).modifiers()) {
			if (e.attribute().value() == Attributes.ARMOR.value()) v += e.modifier().amount() * 10;
			else if (e.attribute().value() == Attributes.ARMOR_TOUGHNESS.value()) v += e.modifier().amount() * 4;
		}
		return v <= 0 ? 0 : v + Math.min(8, st.getEnchantments().size()) * 0.5;
	}

	/** Put on the best armour carried: pick it up, click the armour slot, put what was worn back in its place. */
	private static boolean wearBest(LocalPlayer p) {
		boolean any = false;
		for (int a = 0; a < ARMOR.length; a++) {
			double bestScore = Math.max(0, armorScore(p.getItemBySlot(ARMOR[a])));
			int best = -1;
			for (int i = 0; i < 36; i++) {
				ItemStack st = p.getInventory().getItem(i);
				var eq = st.get(DataComponents.EQUIPPABLE);
				if (eq == null || eq.slot() != ARMOR[a]) continue;
				double score = armorScore(st);
				if (score > bestScore) { bestScore = score; best = i; }
			}
			if (best < 0) continue;
			int slot = best < 9 ? 36 + best : best;   // the hotbar sits at 36-44 in the inventory menu
			clicks.add(new int[] { 0, slot });
			clicks.add(new int[] { 0, 5 + a });
			clicks.add(new int[] { 0, slot });
			any = true;
		}
		if (any) Exchange.emit("wheat", "action", "harness on");
		return any;
	}

	// ----- clicks in a chest or at the crafting table -----

	private static void tidyClicks(Minecraft mc, LocalPlayer p, long now) {
		if (useHit != null) {
			// coming from the rows with what is planted in hand: with carrots no chest would open
			AfkHelperClient.useOn(p, InteractionHand.MAIN_HAND, useHit);
			useHit = null;
			return;
		}
		if (now < stepAt) return;
		switch (tidy) {
			case CRAFT -> craftStep(mc, p, now);
			case DUMP -> dumpStep(mc, p, now);
			case FETCH -> fetchStep(mc, p, now);
			case SEEDS_DROP -> {
				int i = dropSlot(p);
				if (i < 0 || p.containerMenu != p.inventoryMenu) { seedsDone = true; tidy = Tidy.PLAN; stepAt = now + rnd(200, 400); return; }
				Game.drop(mc.gameMode, p.inventoryMenu.containerId, i < 9 ? 36 + i : i, p);
				dropped++;
				tidySteps++;
				stepAt = now + rnd(150, 350);
			}
			default -> { }
		}
	}

	/** Shift-click food out of the chest, a stack at a time, as long as it stays within the two stacks that are kept. */
	private static void fetchStep(Minecraft mc, LocalPlayer p, long now) {
		if (p.containerMenu != menu) { tidy = Tidy.PLAN; return; }
		if (fetchHoe) { fetchHoeStep(mc, p, now); return; }
		int have = foodCount(p), n = menu.slots.size() - 36;
		// the last click moved nothing (no room): stop, do not click the same stack for ever
		if (have != fetchLast) for (int i = 0; i < n; i++) {
			ItemStack st = menu.slots.get(i).getItem();
			if (!edible(st) || have + st.getCount() > 2 * FOOD_LOW) continue;
			fetchLast = have;
			Game.quickMove(mc.gameMode, menu.containerId, i, p);
			tidySteps++;
			stepAt = now + rnd(120, 260);
			return;
		}
		Exchange.emit("wheat", "action", "food taken", "added", have - foodBefore, "food", have);
		infos.put(target, read(menu, infos.get(target)));
		tidy = Tidy.CLOSING;
		stepAt = now + rnd(150, 350);
	}

	/** Shift-click the best hoe out of the chest, once: the most Fortune, then the most durability left. */
	private static void fetchHoeStep(Minecraft mc, LocalPlayer p, long now) {
		ItemStack mine = hoeOf(p);
		if (!hoeClicked) {
			int n = menu.slots.size() - 36, best = -1;
			for (int i = 0; i < n; i++) {
				ItemStack st = menu.slots.get(i).getItem();
				if (usableHoe(st) && (best < 0 || betterHoe(st, menu.slots.get(best).getItem()))) best = i;
			}
			if (best >= 0 && (mine == null || fortune(menu.slots.get(best).getItem()) > fortune(mine))) {
				hoeClicked = true;
				hoesBefore = hoes(p);
				Game.quickMove(mc.gameMode, menu.containerId, best, p);
				tidySteps++;
				stepAt = now + rnd(250, 450);
				return;
			}
		}
		// a full inventory takes nothing: say which of the two it was
		Exchange.emit("wheat", "action", hoeClicked && hoes(p) > hoesBefore ? "hoe taken" : "no hoe taken",
			"fortune", mine == null ? 0 : fortune(mine), "left", mine == null ? 0 : left(mine));
		infos.put(target, read(menu, infos.get(target)));
		tidy = Tidy.CLOSING;
		stepAt = now + rnd(150, 350);
	}

	private static void dumpStep(Minecraft mc, LocalPlayer p, long now) {
		if (p.containerMenu != menu) { tidy = Tidy.PLAN; return; }
		Info info = infos.get(target);
		int base = menu.slots.size() - 36;
		for (int i = base; i < menu.slots.size(); i++) {
			Slot slot = menu.slots.get(i);
			ItemStack st = slot.getItem();
			if (st.isEmpty() || slot.container != p.getInventory() || slot.getContainerSlot() >= 36 || keep[slot.getContainerSlot()]) continue;
			Item item = st.getItem();
			boolean wanted = item == dumpItem
				|| takeJunk && item != Items.HAY_BLOCK && !(seed(st) && dumpPlace()) && !(item == Items.WHEAT && !wheatMayGo);
			if (!wanted || info.fullFor.contains(item)) continue;
			int had = st.getCount();
			Game.quickMove(mc.gameMode, menu.containerId, i, p);
			int left = slot.getItem().is(item) ? slot.getItem().getCount() : 0;
			if (left > 0) info.fullFor.add(item);   // it did not all fit: full for this kind
			if (left < had) { movedThisTidy++; if (item == Items.HAY_BLOCK) hayStored += had - left; }
			tidySteps++;
			stepAt = now + rnd(90, 220);
			return;
		}
		Info fresh = read(menu, null);
		fresh.fullFor.addAll(info.fullFor);
		infos.put(target, fresh);
		tidy = Tidy.CLOSING;
		stepAt = now + rnd(150, 350);
	}

	/**
	 * Wheat into hay bales, nine to one. Nine whole stacks go in as they are, a stack each. What is left is spread
	 * over the nine cells by dragging, stack after stack, and what does not divide by nine stays in the inventory.
	 */
	private static void craftStep(Minecraft mc, LocalPlayer p, long now) {
		if (p.containerMenu != menu) { tidy = Tidy.PLAN; return; }
		tidySteps++;
		stepAt = now + rnd(90, 200);
		int base = menu.slots.size() - 36;
		int empties = 0, min = 64;
		for (int g = 1; g <= 9; g++) {
			ItemStack st = menu.slots.get(g).getItem();
			if (st.isEmpty()) { empties++; min = 0; }
			else if (!st.is(Items.WHEAT)) { craftDone = true; tidy = Tidy.CLOSING; return; }
			else min = Math.min(min, st.getCount());
		}
		int full = 0, firstFull = -1, largest = -1, smallest = -1, room = -1, free = -1, stacks = 0, total = 0;
		for (int i = base; i < menu.slots.size(); i++) {
			ItemStack st = menu.slots.get(i).getItem();
			if (st.isEmpty()) { if (free < 0) free = i; continue; }
			if (!st.is(Items.WHEAT)) continue;
			stacks++; total += st.getCount();
			if (st.getCount() >= 64) { full++; if (firstFull < 0) firstFull = i; } else if (room < 0) room = i;
			if (largest < 0 || st.getCount() > menu.slots.get(largest).getItem().getCount()) largest = i;
			if (smallest < 0 || st.getCount() < menu.slots.get(smallest).getItem().getCount()) smallest = i;
		}
		boolean spreadable = largest >= 0 && menu.slots.get(largest).getItem().getCount() >= 9;

		ItemStack carried = menu.getCarried();
		if (!carried.isEmpty()) {
			if (carried.is(Items.WHEAT) && craftWhole && empties > 0) {
				for (int g = 1; g <= 9; g++) if (menu.slots.get(g).getItem().isEmpty()) { Game.click(mc.gameMode, menu.containerId, g, p); return; }
			}
			if (carried.is(Items.WHEAT) && !craftWhole && !spreadDone && carried.getCount() >= 9 && (empties == 0 || empties == 9) && min < 64) {
				Game.spread(mc.gameMode, menu.containerId, new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 }, p);
				spreadDone = true;
				return;
			}
			// what is left on the cursor goes back: onto wheat that has room, else into an empty slot
			int down = carried.is(Items.WHEAT) && room >= 0 ? room : free;
			if (down < 0) { craftDone = true; tidy = Tidy.CLOSING; return; }
			Game.click(mc.gameMode, menu.containerId, down, p);
			return;
		}

		if (empties == 0 && (craftWhole || min >= 57 || !spreadable)) {
			ItemStack result = menu.slots.get(0).getItem();
			if (!result.is(Items.HAY_BLOCK)) {
				// the server works the recipe out; it is here a moment after the last cell was filled
				if (resultWait == 0) resultWait = now + 2000;
				else if (now > resultWait) { craftDone = true; tidy = Tidy.CLOSING; }
				return;
			}
			resultWait = 0;
			Game.quickMove(mc.gameMode, menu.containerId, 0, p);
			craftWhole = false;
			stepAt = now + rnd(400, 650);   // the server takes all the bales out at once and then sends what is left
			return;
		}
		if (empties != 0 && empties != 9 && !craftWhole) { craftDone = true; tidy = Tidy.CLOSING; return; }
		int pick;
		if (empties == 9 && full >= 9) craftWhole = true;
		if (craftWhole) pick = firstFull;
		else if (spreadable) pick = largest;
		else if (empties == 9 && stacks >= 2 && total >= 9) { pick = smallest; spreadDone = true; }   // loose bits: put them together first
		else pick = -1;
		if (pick < 0) { craftDone = true; tidy = Tidy.CLOSING; return; }
		if (pick != smallest || spreadable || craftWhole) spreadDone = false;
		Game.click(mc.gameMode, menu.containerId, pick, p);
	}

	private static long rnd(long a, long b) { return a >= b ? a : ThreadLocalRandom.current().nextLong(a, b); }
}
