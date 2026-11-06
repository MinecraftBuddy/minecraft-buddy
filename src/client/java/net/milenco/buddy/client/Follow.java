package net.milenco.buddy.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.animal.nautilus.AbstractNautilus;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.milenco.buddy.version.Game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Follow mode, for the buddy only: walks after one player with the real keys and keeps finding a way to that player at
 * every move. The way goes as a player goes: level or a step onto a slab, one block up with a jump, up to three down
 * (deeper only into water), up and down ladders and vines, through wooden doors and fence gates (opened with a click
 * and, when the buddy opened them, closed behind), and through water in all directions, under the surface too:
 * there swimming like a player, sprinting where it is deep, and up for air before the air runs out. It is planned
 * again as soon as the player has moved on. Out of sight (a teleport, a portal, out of range) the buddy walks to the
 * spot where the player was last seen and waits there until the player shows up again: through a portal that is how the
 * buddy comes along, because standing in it takes you across. When the player sits down on a cushion, the buddy goes and
 * sits on a free one nearby, and gets up when the player does. Over a gap the buddy jumps, as far as the game lets a
 * player jump (the arithmetic of the server's parkour course): from a standstill at the edge, or with a run-up from a
 * platform of three, onto blocks, wall posts, iron bars and into ladders. Instead of a player the buddy can go to a row
 * of spots, one after the other (the checkpoints of that course). Whom to follow changes only by a command
 * ({@code !follow <name>} or {@code !follow off} in Telegram), and is kept in
 * {@code follow.json} over a restart. The followed player says stay or follow again by looking at the buddy and
 * crouching twice; with a weapon in hand a trusted player's sign switches the protection ({@link Guard}) on or off,
 * and held up to the sky, the fighting against players. The buddy fights whoever hits the followed player, with
 * critical hits. When the player teleports away the buddy asks /tpa, and accepts only that player's /tpahere; when the
 * player sleeps the buddy takes a free bed nearby, and when the player gets into a boat or a minecart the buddy gets in
 * too, or into another one. Digging goes only as far as {@link Work} allows (the player's tunnel, trees, crops); no building.
 * Self-contained on purpose: the builder must stay removable, so none of its walking code is used here.
 */
final class Follow {
	/** A Java name, or a Bedrock name with Floodgate's dot in front. */
	private static final Pattern NAME = Pattern.compile("\\.?[A-Za-z0-9_]{2,16}");
	/** Stand still once this close, and set off again only beyond FAR: no stop and go at every step the player takes. */
	private static final double NEAR = 2.0, FAR = 3.5;
	/** How many cells one plan may look at, and how far around you: about as far as the server shows players. */
	private static final int MAX_NODES = 12_000, RANGE = 140;
	/** RANGE for a {@code ga}, or {@code "reach"} from the command: a shorter one makes a route end mid-course, to test planOn. */
	private static int gotoRange = RANGE;

	private static int range() { return spots.isEmpty() ? RANGE : gotoRange; }
	/** Plan again when the player has moved this far from the cell the plan led to. */
	private static final double REPLAN_MOVED = 2.5;
	/** Under water: up for air below this (of 300; one breath lasts fifteen seconds). */
	private static final int AIR_LOW = 150;
	private static final double[] BODY = { -0.3, 0.3 };

	private static String target;           // whom; null when off
	private static Vec3 lastSeen;           // where the player was last seen, in lastDim
	private static String lastDim;
	private static boolean lost = false, walking = false, noPathTold = false, breath = false, wet = false, hoverSink = false;
	private static long pauseUntil = 0;

	private static List<BlockPos> route;    // cells still to walk, nearest first
	private static BlockPos routeGoal;      // the cell the plan led to
	private static boolean exactGoal;       // to a spot (where the player vanished), not just near the player
	private static boolean airRoute;        // the route leads up to air, not to the player
	private static int idx, still, ticks, nextPlan, jumpTicks, backTicks;
	private static long idxSince;
	private static double idxDist;          // how far the cell was on setting off for it
	private static Vec3 previous;
	/** Feet heights worked out during one plan: every cell is looked at from up to eight sides. */
	private static final Map<BlockPos, Double> feetMemo = new HashMap<>();

	// doors and gates: the one to click at the start of the next tick, and what a click did
	private static BlockPos clickDoor;      // lower half of the door the crosshair should be on
	private static BlockPos opening;        // clicked to open, waiting for it to show open
	private static int openingSince;
	private static boolean openingSeen;     // it showed open since the click
	private static BlockPos toClose;        // opened by the buddy: shut it again once through
	private static boolean toCloseEntered;  // the buddy has been in the doorway since
	private static int closeTries;
	private static int closeBlocked;        // ticks an animal or a player stood between the buddy and toClose
	private static int shutClickAt = -1;    // the tick of the click to shut toClose, -1 for none
	/** A closed door or gate waited at because animals stand right behind it, and since when (tick). */
	private static BlockPos doorWait;
	private static int doorWaitSince;
	/**
	 * How long an animal in front of a door to shut, or behind one to open, is waited for: on the test
	 * server's copy of the base a shearing round gave the pen's gate up after three tries of two seconds with sheep in
	 * the line, and it stood open 80 seconds; walking in with two sheep just inside the buddy stuck in the doorway (23 Oct 2026).
	 */
	private static final int ANIMAL_WAIT = 300;
	/**
	 * Ticks a click on a door waits for the server's answer. The client shows a door open or shut the moment it is clicked,
	 * its own guess; a claim or a region that refuses it (GriefPrevention's LockFenceGates) puts it back a moment
	 * later. Taken for open at once, a refused door was never "deur blijft dicht" and was clicked every few ticks (the
	 * review of 22 Oct 2026).
	 */
	private static final int DOOR_ANSWER = 6;
	/**
	 * Every door and gate the buddy opened that may still be open, since when (doors not shut behind let the
	 * cows and sheep mix and sheep walk free): one not
	 * gone through, one walked far from, and the first of two in a row were forgotten and stayed open.
	 */
	private static final Map<BlockPos, Long> opened = new HashMap<>();
	/** Walks to a door left open that came to nothing: three and it is told and left. */
	private static final Map<BlockPos, Integer> closeFails = new HashMap<>();
	/**
	 * A gate held open for a while (Herd: the animal being led follows into its pen a few steps behind the buddy, and with
	 * the gate shut once the buddy was through, it stayed outside). Null for none. Neither {@link #closeBehind} nor {@link #closeLeftOpen} shuts it.
	 */
	private static BlockPos heldOpen;

	/** Hold this gate open, or none (null): shut as always again once the buddy is out of it. */
	static void holdOpen(BlockPos gate) { heldOpen = gate; }

	/** Shut this gate now, from where the buddy stands (Herd, from inside the pen), whoever opened it. */
	static void shut(BlockPos gate) {
		if (gate.equals(toClose)) return;
		toClose = gate; toCloseEntered = true; closeTries = 0; closeBlocked = 0; shutClickAt = -1;
		opened.put(gate, System.currentTimeMillis());
	}
	/** Doors a click did not open (a claim without trust, a redstone lock): a wall for a minute. */
	private static final Map<BlockPos, Long> refused = new HashMap<>();
	/**
	 * Cells the buddy got stuck on the way to (a bamboo stalk that stands off-centre in the next cell, a corner the plan did
	 * not see): left out of the plans for twenty seconds, so the next plan goes another way instead of the same.
	 */
	private static final Map<BlockPos, Long> avoid = new HashMap<>();

	// cushions: the free one gone for while the player sits, the one to click next tick, and how it went
	private static Entity seat;             // the cushion gone for
	private static Entity clickSeat;        // to right-click at the start of the next tick
	private static int seatClickedAt = -1;  // tick of that click; -1 when none waits
	private static long seatSince;          // when the walk to this cushion began
	private static boolean seated;          // sat down of the buddy's own accord, so up again the same way
	private static int standTicks;          // shift held this many ticks more: getting up
	// a walk to a spot begun seated (rideTo): the ride, the spot, the least still to go and when, the way over water
	private static Entity rideOn;
	private static BlockPos rideSpot;
	private static double rideBest;
	private static int rideBestTick, boatIdx;
	private static List<BlockPos> boatWay;
	private static boolean rideTold, rideWaits, rideLanding;
	/** This near the spot (horizontally) the buddy gets off and walks the rest. */
	private static final double RIDE_NEAR = 2.5;
	/** Cushions a click did not seat the buddy on (a region at spawn that does not allow it, taken just then): skipped a minute. */
	private static final Map<Integer, Long> seatRefused = new HashMap<>();
	/** Sit within this distance of the player, or not at all. */
	private static final double SEAT_RANGE = 8;
	private static final double GHAST_REACH_UP = 3.5;   // a ghast's underside at most this far above the buddy's feet: within a click
	// The player's happy ghast while the player is off it: the alt stays on and flies it low alongside until the player
	// mounts it again, taking over the steering when the player gets off. The first one on steers (HappyGhast.getControllingPassenger),
	// so when the player gets off the alt has the steering, and when the player gets on again behind the alt, the alt hands it back: off and on again.
	private static final double GHAST_SIDE = 3.6;    // its middle this far beside the player: its side within the player's reach, not overhead
	private static final double GHAST_UNDER = 2.4;   // its underside this far above the player's feet: overhead, within reach
	private static boolean chauffeur;       // steering the player's ghast while the player is off it
	private static boolean driving;         // the ghast under way to its spot beside the player
	private static double ghastBest;        // nearest it came to that spot since ghastBestTick
	private static int ghastBestTick;
	private static Vec3 ghastParkedAt;      // got no nearer (a tunnel, a roof): waits until the player has moved on from here
	private static long handoverUntil;      // got off to hand the steering back: getting on again, however long a click takes
	// Tied up with a lead it may be left: down to the ground first, off with a crouch (the game sets the alt on top), and off its top at the edge
	// with the shortest drop.
	private static boolean landing;         // leashed: on the way down to get off
	private static boolean steppingOff;     // on top of the player's ghast, on the way to its edge

	// jumps: how each cell of the route is reached from the one before, and the jump under way
	static final byte WALK = 0, JUMP = 1, RUN = 2;
	/** A jump around a pillar between two blocks ({@link Round}), past the one side or the other. */
	static final byte ROUND_A = 3, ROUND_B = 4;
	static final byte DIG = PathShape.DIG, PILLAR = PathShape.PILLAR, BRIDGE = PathShape.BRIDGE;
	private static List<Byte> routeMoves = new ArrayList<>();
	private static Leap leap;
	private static final Map<BlockPos, Integer> leapFails = new HashMap<>();
	private static Vec3 lastPos;            // last tick's position: moved more than four blocks is a teleport
	private static boolean hurry;           // to a spot: sprint on a straight bit
	private static boolean canSprint;       // food for sprinting when the plan was made: a run-up takes it
	/**
	 * When the plan was made: whether to jump over a gap at all (not burning, not in lava, more than four hearts),
	 * how far a jump that goes wrong may let the buddy fall ({@link Miss}), and whether the void under a jump is all right
	 * (the parkour course, where a miss puts the buddy back at the checkpoint).
	 */
	private static boolean leapsOk;
	private static double missFall;
	private static boolean voidOk;
	/** At this health or lower no jump over a gap: a miss with the fire or a fight on top of it killed the buddy (18 Oct 2026). */
	private static final float LEAP_HP = 8f;
	/**
	 * How much lower a jump may land. A fall counts from the top of the arc, 1.25 above the take-off, and from four
	 * blocks it hurts: three lower cost half a heart at every landing (18 Oct 2026), often while following.
	 * Not in the parkour dimension, which takes no fall damage.
	 */
	static final double JUMP_DROP = 2.7;
	/** What a jump has to leave over, from a standstill and with a run-up: the server's parkour course holds its jumps to it. */
	private static final double SPARE = 0.15, SPARE_RUN = 0.35, SPARE_CARRIED = 0.05;
	/** At how many edges one plan looks for jumps: each tries some six hundred landings. */
	private static final int JUMP_LOOKS = 1500;
	private static int jumpLooks;
	/** How each cell was reached, while a plan is made. */
	private static final Map<BlockPos, Byte> planHow = new HashMap<>();
	/** Where the buddy stood when the last {@code ga} came: the start of the route its time counts for (Routes). */
	private static BlockPos spotsFrom;
	private static final Map<BlockPos, BlockPos> planFrom = new HashMap<>();
	private static final double[] ARC = { -0.25, 0.25 };

	// spots to stand on one after the other (the checkpoints and the finish of the parkour course), instead of a player
	private static final List<BlockPos> spots = new ArrayList<>();
	private static String spotsDim;
	private static long spotsSince;
	private static boolean spotsSeen;
	private static Vec3 spotsLast;
	/** The followed player said stay: the buddy waits on the spot until told to follow again. */
	private static boolean staying;
	// the sign: a player who looks at the buddy and crouches twice within 0.8 s; the answer, a nod (stay) or a hop (follow)
	private static final Map<String, Boolean> crouchedBefore = new HashMap<>();
	private static final Map<String, Long> crouchedAt = new HashMap<>();
	private static int nodTicks, hopTicks;
	// the answer to a sign with a weapon: protection on or off, fighting players on or off
	static final int ANS_GUARD_ON = 1, ANS_GUARD_OFF = 2, ANS_PVP_ON = 3, ANS_PVP_OFF = 4;
	// a shake of the head and one crouch: "are you sure?"; a shake alone: "no, that cannot be"
	static final int ANS_ASK = 5, ANS_NO = 6, ANS_ONCE = 7;
	// the answers to a tap: yes (a nod), off (one crouch), no (a shake)
	static final int YES = 0, OFF = 1, NO = 2;

	/** Answer a tap the way a player would: a nod, one crouch, a shake of the head. */
	static void respond(int what) {
		switch (what) {
			case YES -> nodTicks = 12;
			case OFF -> answer(ANS_ONCE);
			default -> answer(ANS_NO);
		}
	}
	private static int answerKind, answerTicks;
	private static float answerYaw;
	// The crouches of a sign come in a burst, each within 0.8 s of the one before; what it means is read once the burst
	// is over: two the sign of old, five or more forgetting. Per player: how many, the last one, and how that player stood then.
	private static final Map<String, Integer> burst = new HashMap<>();
	private static final Map<String, boolean[]> burstHow = new HashMap<>();
	private static final int HOW_LOOKS = 0, HOW_ARMED = 1, HOW_UP = 2, HOW_EMPTY = 3, HOW_NEAR = 4, HOW_CLOSE = 5;
	/** Five crouches asked the buddy to forget this server: until then a sign of two confirms it. */
	private static long forgetAskedUntil;

	// teleported away: ask to come along
	private static long lostAt, lastTpa;
	private static boolean tpaOpen;         // the buddy's /tpa waits for an answer
	private static String acceptFrom;       // a /tpahere to accept once standing still is possible
	private static long acceptUntil;

	// To the AFK zone when the player does not answer the /tpa or goes offline, and wait there; when that fails because
	// the buddy moves, find the cause and solve it. 0 no, 1 on the way, 2 waiting there.
	private static int afk;
	private static long afkTypedAt, afkNextTry, offlineSince, combatUntil;
	private static String afkWhy, afkFail;
	private static int afkTries;
	private static boolean wasOnline;
	private static BlockPos dry, aside;

	// a bed near the player while the player sleeps
	private static BlockPos bed, clickBed;
	private static int bedClickedAt = -1;
	private static long bedSince, wokeAt;
	// a menu open that no job of the alt opened: when it came, and how long it may stay
	private static long strayMenuSince;
	private static final long STRAY_MENU_MS = 60_000;
	private static final Map<BlockPos, Long> bedRefused = new HashMap<>();

	private Follow() {}

	static boolean isOn() { return target != null || !spots.isEmpty(); }

	/** The tick the buddy last stood at a closed door on the way, to open it. */
	private static long doorTick = -100;

	/** Whether a door held the buddy up within the last second: no progress then is no failure to get somewhere. */
	static boolean atDoor() { return ticks - doorTick <= 20 || opening != null; }

	/** The player followed and worked for, or null. */
	static String target() { return target; }

	static boolean staying() { return staying; }

	/** Whom the alt works for: the followed player, or the owner of the base kept (Base). */
	static String boss() { return target != null ? target : Base.keeper(); }

	/**
	 * On the way to the player right now, in water, or busy with a cushion: then the alt's own crouch and jump keys hold off.
	 * Crouched in water you sink, crouched you click no cushion (it takes that as stepping past it), and a crouch
	 * gets you off it again at once.
	 */
	static boolean walking() {
		LocalPlayer p = AfkHelperClient.mc().player;
		return (isOn() && (walking || wet || seat != null || seated || standTicks > 0 || leap != null || hopTicks > 0
			|| answerTicks > 0 || Guard.enemy() != null || bed != null || !spots.isEmpty()
			|| (p != null && (p.isPassenger() || p.isSleeping())))) || Work.busy() || Stash.busy() || Dump.busy() || Unloader.isBusy() || Corpse.busy() || Ovens.busy() || Carrier.busy() || Renew.busy() || Torches.busy() || Chores.busy() || Pen.busy();
	}

	// ---------- switching ----------

	/** {@code {"cmd":"follow","player":"Name"}} starts, {@code "on":false} stops, without either it answers how it stands. */
	static void command(JsonObject o) {
		if (o.has("on") && !o.get("on").getAsBoolean()) {
			remember(null);
			if (target == null) Exchange.emit("follow", "action", "stop", "on", false, "reason", "was off already");
			else stop("asked");
			return;
		}
		if (o.has("stay")) {
			if (target == null) { Exchange.emit("error", "msg", Needs.named("follow: {me} follows nobody")); return; }
			setStaying(o.get("stay").getAsBoolean(), "asked");
			return;
		}
		String name = o.has("player") ? o.get("player").getAsString().trim() : "";
		if (name.isEmpty()) { status(); return; }
		if (!NAME.matcher(name).matches()) { Exchange.emit("error", "msg", "follow: no player name: " + name); return; }
		start(name);
		if (name.equalsIgnoreCase(target)) remember(name);
	}

	/**
	 * Whom to follow lasts until the next command, also over a restart of the game: kept in {@code follow.json} in the exchange folder.
	 */
	private static void remember(String name) {
		try {
			java.nio.file.Path f = followFile();
			if (name == null) java.nio.file.Files.deleteIfExists(f);
			// told to stay lasts over a restart too: else the buddy came back following, sent a /tpa nobody answered and
			// went to the AFK zone, out of the base the buddy was left in (21 Oct 2026)
			else java.nio.file.Files.writeString(f, "{\"player\":\"" + name + "\"" + (staying ? ",\"stay\":true" : "") + "}");
		} catch (Exception e) {
			Exchange.emit("error", "msg", "who to follow not saved: " + e.getMessage());
		}
	}

	private static java.nio.file.Path followFile() { return java.nio.file.Paths.get(AfkConfig.get().exchangeDir).resolve("follow.json"); }

	/** follow.json, read. */
	private static com.google.gson.JsonObject followRead() throws java.io.IOException {
		java.nio.file.Path f = followFile();
		if (!java.nio.file.Files.exists(f)) return null;
		return com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(f)).getAsJsonObject();
	}

	private static boolean rememberedStaying() {
		try {
			var o = followRead();
			return o != null && o.has("stay") && o.get("stay").getAsBoolean();
		} catch (Exception e) {
			return false;
		}
	}

	private static String remembered() {
		try {
			var o = followRead();
			if (o == null || !o.has("player")) return null;
			String name = o.get("player").getAsString();
			return NAME.matcher(name).matches() ? name : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static void start(String name) {
		LocalPlayer p = AfkHelperClient.mc().player;
		if (p != null && p.getGameProfile().name().equalsIgnoreCase(name)) { Exchange.emit("error", "msg", Needs.named("follow: that is {me}'s own name")); return; }
		if (othersOn()) { Exchange.emit("error", "msg", "follow: switch the farm off first"); return; }
		Wander.abort("follow");
		release();
		target = name;
		// a new !follow is the newest word to follow, whether staying or not: a step back of the pen under way ends at
		// its next tick, and the stay a too-dangerous one was to give lapses with it (Pen.stayLifted). Until the review of
		// the fourth round of 22 Oct 2026 only while staying, and the buddy follows during a too-dangerous step back, so typed
		// then it still ended in that stay
		Pen.stayLifted();
		staying = false; spots.clear(); leap = null; leapFails.clear();
		lastSeen = null; lastDim = null; lost = false; walking = false; noPathTold = false; breath = false; pauseUntil = 0;
		refused.clear(); avoid.clear(); backTicks = 0; toClose = null; opening = null; clickDoor = null;
		seatRefused.clear(); seat = null; clickSeat = null; seatClickedAt = -1; standTicks = 0; seated = false;
		chauffeur = false; driving = false; ghastParkedAt = null; handoverUntil = 0; landing = false; steppingOff = false;
		clearRoute();
		Player t = p == null ? null : find(p);
		Exchange.emit("follow", "action", "start", "on", true, "player", name, "visible", t != null,
			"distance", t == null ? -1 : round(t.distanceTo(p)));
	}

	static void stop(String why) {
		// called every tick while the mod is off: when nothing is on, it must not touch a key
		if (!isOn()) return;
		Pen.stop(why);
		Work.reset(why);
		if (!spots.isEmpty()) { spots.clear(); Exchange.emit("goto", "action", "stop", "reason", why); }
		leap = null;
		douse = null;
		if (target == null) { release(); clearRoute(); walking = false; return; }
		staying = false;
		String was = target;
		release();
		target = null;
		walking = false; breath = false;
		toClose = null; opening = null; clickDoor = null;
		seat = null; clickSeat = null; seatClickedAt = -1; standTicks = 0; seated = false;
		chauffeur = false; driving = false; ghastParkedAt = null; handoverUntil = 0; landing = false; steppingOff = false;
		clearRoute();
		Exchange.emit("follow", "action", "stop", "on", false, "player", was, "reason", why);
	}

	private static void status() {
		LocalPlayer p = AfkHelperClient.mc().player;
		Player t = p == null ? null : find(p);
		Exchange.emit("follow", "action", "status", "on", target != null, "player", target == null ? "" : target,
			"visible", t != null, "distance", t == null ? -1 : round(t.distanceTo(p)), "walks", walking, "stays", staying,
			"points", spots.size());
	}

	/**
	 * Stay put, or follow again: by the sign, or by {@code "stay"} in the command. Following again ends a step
	 * back of the pen under way at its next tick (Pen.stayLifted; before the third review of 22 Oct 2026 the buddy walked on to
	 * its cell first, up to fifteen seconds), and one that would end in a stay (too dangerous) gives none. A new
	 * {@code !follow} (start) does the same, whether staying or not.
	 */
	private static void setStaying(boolean stay, String by) {
		if (stay) Pen.stop("stays");
		else Pen.stayLifted();
		staying = stay;
		if (target != null) remember(target);
		release();
		clearRoute();
		leap = null;
		walking = false;
		Exchange.emit("follow", "action", stay ? "stays" : "follows again", "player", target, "by", by);
	}

	/** The pen ended at the player's word or for safety: wait on the spot now (Pen). */
	static void stayHere(String by) { if (target != null && !staying) setStaying(true, by); }

	/** Staying is no good there (fallen into a pen, shot at with no cover to be had): follow again (Pen). */
	static void unstay(String by) { if (target != null && staying) setStaying(false, by); }

	/**
	 * {@code {"cmd":"goto","points":[[x,y,z],...]}}, or one spot as x, y and z: stand on each spot in turn, in the
	 * dimension the buddy is in now, and say so at each; {@code "on":false} stops. Following a player waits meanwhile.
	 */
	static void gotoCommand(JsonObject o) {
		if (o.has("on") && !o.get("on").getAsBoolean()) {
			boolean was = !spots.isEmpty();
			spots.clear(); leap = null; release(); clearRoute(); walking = false;
			Exchange.emit("goto", "action", "stop", "reason", was ? "asked" : "was off already");
			return;
		}
		trace = o.has("trace") && o.get("trace").getAsBoolean();
		EARLY = !o.has("early") || o.get("early").getAsBoolean();
		if (o.has("margin")) EARLY_SPARE = o.get("margin").getAsDouble();
		CHAIN = !o.has("chain") || o.get("chain").getAsBoolean();
		RUN_ON = !o.has("run_on") || o.get("run_on").getAsBoolean();
		STRAFE_FIX = !o.has("strafe") || o.get("strafe").getAsBoolean();
		POSTS = !o.has("posts") || o.get("posts").getAsBoolean();
		NARROW = !o.has("narrow") || o.get("narrow").getAsBoolean();
		gotoRange = o.has("reach") ? Math.max(20, Math.min(RANGE, o.get("reach").getAsInt())) : RANGE;
		LINE = !o.has("line") || o.get("line").getAsBoolean();
		WALK_ON = !o.has("platform") || o.get("platform").getAsBoolean();
		List<BlockPos> list = new ArrayList<>();
		try {
			if (o.has("points")) for (JsonElement e : o.getAsJsonArray("points")) {
				JsonArray a = e.getAsJsonArray();
				list.add(new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()));
			}
			else if (o.has("x") && o.has("y") && o.has("z")) list.add(new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()));
		} catch (RuntimeException e) { Exchange.emit("error", "msg", "goto: points as [[x,y,z],...]"); return; }
		if (list.isEmpty()) {
			BlockPos f = spots.isEmpty() ? null : spots.get(0);
			Exchange.emit("goto", "action", "status", "points", spots.size(), "x", f == null ? 0 : f.getX(), "y", f == null ? 0 : f.getY(), "z", f == null ? 0 : f.getZ());
			return;
		}
		if (othersOn()) { Exchange.emit("error", "msg", "goto: switch the farm off first"); return; }
		LocalPlayer p = AfkHelperClient.mc().player;
		Wander.abort("goto");
		release(); clearRoute();
		leap = null; leapFails.clear(); avoid.clear(); refused.clear(); backTicks = 0; walking = false; noPathTold = false;
		rideOn = null;
		spots.clear();
		spots.addAll(list);
		Pen.stop("goto");
		// the dimension the spots are in: given (the parkour course is in events:arenas, and !parkour may come before the
		// buddy joined the game), or the current one
		spotsDim = o.has("dim") ? o.get("dim").getAsString() : p == null ? null : p.level().dimension().identifier().toString();
		spotsSince = System.currentTimeMillis();
		spotsFrom = p == null ? null : p.blockPosition();
		spotsSeen = false;
		spotsLast = null;
		BlockPos f = list.get(0);
		Exchange.emit("goto", "action", "start", "points", list.size(), "x", f.getX(), "y", f.getY(), "z", f.getZ());
	}

	/**
	 * The buddy's own teleport counts down (a /tpa accepted, /home): every step cancels it, so stand still for it.
	 * Following goes on from wherever the teleport lands.
	 */
	static void teleport() {
		if (!isOn()) return;
		leap = null;
		pauseUntil = System.currentTimeMillis() + 6000;
		release();
		clearRoute();
		toClose = null; opening = null;
	}

	/** A new connection is another world: what was seen before means nothing there. Following itself stays on. */
	static void newConnection() {
		Work.reset("new connection");
		Pen.newConnection();
		// whom the buddy followed before a restart of the game, followed again once the world is there
		if (target == null) {
			String name = remembered();
			boolean stay = rememberedStaying();
			if (name != null) AfkHelperClient.later(5.0, () -> {
				if (target == null && AfkHelperClient.active() && AfkHelperClient.mc().player != null) {
					start(name);
					if (stay && name.equals(target)) { staying = true; remember(name); Exchange.emit("follow", "action", "stays", "player", name, "by", "restart"); }
				}
			});
		}
		if (!isOn()) return;
		leap = null; lastPos = null;
		release();
		clearRoute();
		lastSeen = null; lastDim = null; lost = false; walking = false; breath = false;
		toClose = null; opening = null; clickDoor = null; refused.clear(); avoid.clear(); backTicks = 0;
		opened.clear(); closeFails.clear();
		seatRefused.clear(); seat = null; clickSeat = null; seatClickedAt = -1; standTicks = 0; seated = false;
		chauffeur = false; driving = false; ghastParkedAt = null; handoverUntil = 0; landing = false; steppingOff = false;
	}

	// ---------- every tick ----------

	/**
	 * Start of the tick, where vanilla clicks too: open or close the door the crosshair is on, if that is the one
	 * meant. Never a click on anything else: with a block in hand a click beside the door would place it.
	 */
	static void startTick(Minecraft mc) {
		Shield.startTick(mc);
		Bow.startTick(mc);
		Guard.startTick(mc);
		Work.startTick(mc);
		PathShape.startTick(mc);
		Stash.startTick(mc);
		Ovens.startTick(mc);
		Torches.startTick(mc);
		Chores.startTick(mc);
		BlockPos lie = clickBed;
		clickBed = null;
		if ((isOn() || Base.keeper() != null) && lie != null && mc.player != null && mc.gameMode != null && mc.level != null && !mc.player.isShiftKeyDown()
				&& mc.hitResult instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK
				&& bedHalves(mc.level, lie).contains(bh.getBlockPos()) && mc.level.getBlockState(bh.getBlockPos()).getBlock() instanceof AbstractBedBlock) {
			InteractionResult r = AfkHelperClient.useOn(mc.player, InteractionHand.MAIN_HAND, bh);
			if (r.consumesAction()) Game.swingUse(mc.player, InteractionHand.MAIN_HAND);
			bedClickedAt = ticks;
		}
		Entity sit = clickSeat;
		clickSeat = null;
		if (isOn() && sit != null) {
			LocalPlayer p = mc.player;
			// only the seat meant, under the crosshair, with room, and not crouched (then a cushion lets you pass, and a
			// happy ghast takes the click as a use and not a ride). With room, not empty: the player's own boat or ghast has the player in it
			if (p != null && mc.gameMode != null && !p.isShiftKeyDown() && !p.isPassenger() && hasRoom(sit)
					&& !(sit instanceof HappyGhast && riskyInHand(p.getMainHandItem()))
					&& mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == sit) {
				Game.interactEntity(mc.gameMode, p, sit, hit, InteractionHand.MAIN_HAND);
				seatClickedAt = ticks;
			}
		}
		BlockPos want = clickDoor;
		clickDoor = null;
		// keeping a base with nobody to follow too: the click never went then, so no door of the base opened or shut for the buddy
		if (!(isOn() || Base.keeper() != null) || want == null) return;
		LocalPlayer p = mc.player;
		if (p == null || mc.gameMode == null || mc.level == null || p.isShiftKeyDown()) return;
		if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
		if (!doorBase(mc.level, hit.getBlockPos()).equals(want) || !openable(mc.level.getBlockState(want))) return;
		boolean wasOpen = isOpen(mc.level.getBlockState(want));
		InteractionResult r = AfkHelperClient.useOn(p, InteractionHand.MAIN_HAND, hit);
		if (r.consumesAction()) Game.swingUse(p, InteractionHand.MAIN_HAND);
		if (!wasOpen) { opening = want; openingSince = ticks; openingSeen = false; }
		else if (want.equals(toClose)) shutClickAt = ticks;   // shut once the server's answer is in (closeBehind)
	}

	/** Every tick. True while follow mode turns or walks the buddy. */
	static boolean tick(Minecraft mc, LocalPlayer p, AfkConfig c) {
		if (AfkHelperClient.active() && !c.afk) sign(p);
		boolean keep = Base.wants(p, target != null, staying);
		if (!isOn() && !Work.testing() && !keep) { lastPos = null; return false; }
		ticks++;
		// how long since a bed, against phantoms (Rest)
		Rest.tick(p);
		// the corpse guard watches every tick: the moment the buddy dies, and what falls then
		if (target != null) { Player seen = find(p); Corpse.watch(p, seen); Carrier.watch(p, seen); Pen.watch(mc, p, seen); }
		// The player lies in bed: the alt goes to sleep at once, a round to the chests can wait (also right after a tpahere)
		if (Stash.busy()) { Player sleeper = find(p); if (sleeper != null && sleeper.isSleeping()) Stash.stop("the player sleeps"); }
		// hit while a screen is open (a chest, a furnace, the crafting grid): shut it and hit back first; behind a screen
		// nobody fights (the review of 22 Oct 2026)
		if (p.containerMenu != p.inventoryMenu && p.isAlive() && Guard.underAttack(p) != null) {
			p.closeContainer();
			Stash.stop("attacked");
			Ovens.stop("attacked");
			Chores.stop("attacked");
			Exchange.emit("guard", "action", "menu closed", "reason", "attacked");
			return true;
		}
		// a chest the alt opened, to put things in or take them out: the chest screen is the alt's own doing
		if (Stash.inMenu(p)) return Arbiter.at("chest", Stash.menuTick(mc, p));
		if (Ovens.inMenu(p)) return Arbiter.at("furnace", Ovens.menuTick(mc, p));
		if (Chores.inMenu(p)) return Arbiter.at("chore", Chores.menuTick(mc, p));
		if (jumpTicks > 0 && --jumpTicks == 0) HeldKeys.set(mc.options.keyJump, false);
		var screen = Game.openScreen(mc);
		boolean menu = screen != null && !(screen instanceof net.minecraft.client.gui.screens.ChatScreen)
			&& !(screen instanceof net.minecraft.client.gui.screens.PauseScreen);
		// A menu that is nobody's job any more: shut after a minute. Nothing else does, and following waits behind any
		// menu, so one left open stops the buddy for good (19 Oct 2026: a chest of the buddy's own, 27 minutes). A session that clicks
		// in a menu through menuklik keeps it open for a minute after each click.
		long seen = System.currentTimeMillis();
		if (!menu || !p.isAlive() || p.containerMenu == p.inventoryMenu) strayMenuSince = 0;   // a container only; the death screen is the respawn's
		else if (strayMenuSince == 0) strayMenuSince = seen;
		else if (seen - Math.max(strayMenuSince, MenuLog.lastMenuCommand) > STRAY_MENU_MS) {
			String title = screen.getTitle().getString();
			p.closeContainer();
			strayMenuSince = 0;
			Exchange.emit("follow", "action", "menu closed", "title", title);
		}
		// Dead, a menu open (nobody walks behind a chest screen), AFK mode on (the respawn watcher switches it on for
		// a moment after a death), a farm at work, a teleport counting down: wait, and carry on afterwards.
		if (!p.isAlive() || menu || c.afk || othersOn() || System.currentTimeMillis() < pauseUntil || !AfkHelperClient.active()) {
			if (walking) { release(); clearRoute(); walking = false; }
			breath = false;
			leap = null;
			return false;
		}
		Level level = p.level();
		long now = System.currentTimeMillis();
		wet = p.isInWater();
		// teleported (back to a checkpoint of the course after a fall, a /tp): the plan and a jump were of the place before
		if (lastPos != null && p.position().distanceToSqr(lastPos) > 16) { release(); clearRoute(); leap = null; }
		lastPos = p.position();
		if (Work.testing()) return Arbiter.at("test", Work.testTick(mc, p));
		if (nodTicks > 0 || hopTicks > 0 || answerTicks > 0) return Arbiter.at("answer", answer(mc, p));
		// guarding the player, the weapon is in hand already, as a player who walks with someone keeps a sword out
		// once a second at most, never while eating
		if (target != null && spots.isEmpty() && Guard.on() && !Work.busy()) Guard.arm(mc, p, false);
		// the best armour on, whatever the buddy does
		Stash.wearBest(mc, p);
		if (Arbiter.at("tpahere", acceptTpahere(mc, p, now))) return true;
		refused.values().removeIf(until -> until < now);
		avoid.values().removeIf(until -> until < now);
		fence.values().removeIf(until -> until < now);
		// stuck: a step back first, out of the corner, and then a new plan; by a pen crouched, so that the step back, which
		// no plan checks, never takes the buddy over its edge, and its walk round it keeps the crouch it had
		if (backTicks > 0) {
			HeldKeys.set(mc.options.keyUp, false);
			HeldKeys.set(mc.options.keySprint, false);
			HeldKeys.set(mc.options.keyDown, --backTicks > 0);
			if (p.isInWater()) holdJump(mc);
			else if (Pen.crouchWalk(p) || Pen.fenceBeside(p.blockPosition())) HeldKeys.set(mc.options.keyShift, true);
			return true;
		}
		// A click that did not open the door within a second, or that the server put back shut: not to be opened here. Plan
		// around it. Open counts once the server has had time to answer ({@link #DOOR_ANSWER}).
		if (opening != null) {
			boolean open = isOpen(level.getBlockState(opening));
			if (open) openingSeen = true;
			if (open && ticks - openingSince >= DOOR_ANSWER) {
				toClose = opening; toCloseEntered = false; closeTries = 0; closeBlocked = 0; shutClickAt = -1;
				opened.put(opening, now);
				Exchange.emit("follow", "action", "door opened", "x", opening.getX(), "y", opening.getY(), "z", opening.getZ());
				opening = null;
			}
			else if ((!open && openingSeen) || ticks - openingSince > 20) {
				refused.put(opening, now + 60_000);
				Exchange.emit("follow", "action", "door stays shut", "x", opening.getX(), "y", opening.getY(), "z", opening.getZ());
				opening = null;
				clearRoute();
			}
		}
		// Under water with little air left: up first. A player with a turtle helmet or a conduit is followed nowhere.
		if (p.isUnderWater() && p.getAirSupply() < AIR_LOW && !breath) {
			breath = true;
			clearRoute();
			Exchange.emit("follow", "action", "to air", "air", p.getAirSupply());
		}
		if (breath) { walking = true; return Arbiter.at("air", breathe(mc, p)); }
		// burning, with no bucket to put it out here (Water does that itself): into water close by, when there is
		if (Arbiter.at("put out fire", douseWalk(mc, p, now))) return true;
		// a creeper swelling within four blocks: facing it with the shield up, which blocks its blast from the front (the
		// plan for fighting of 24 Oct 2026). Not at the pen, which has its own way with creepers (Pen.creeper), nor on a ghast
		if (!Pen.busy() && !(p.getVehicle() instanceof HappyGhast) && Shield.ready(p)) {
			Creeper boom = Shield.swelling(p);
			if (boom != null) {
				release();
				face(p, Shield.aimAt(boom), 30f, 20f);
				Shield.want("creeper");
				walking = true;
				return Arbiter.at("creeper", true);
			}
		}
		// hit by a monster: back at it before getting loose, a door or a walk to a spot, which came first and left the buddy to be
		// hit on (the review of 22 Oct 2026). The fight below as always, and only this one taken early.
		if (Guard.underAttack(p) != null && !(p.getVehicle() instanceof HappyGhast) && !Pen.busy()) {
			Entity early = Guard.watch(p, find(p));
			if (early != null && !(keep && (target == null || staying) && !c.baseFight)) return Arbiter.at("fight", fight(mc, p, early));
		}
		if (Arbiter.at("get loose", Unstuck.tick(mc, p, !p.isSleeping() && routing() && (walking || Work.busy() || Stash.busy() || Carrier.busy() || Renew.busy() || Corpse.busy()) && !Pen.near(p, Pen.NO_UNSTUCK)))) return true;
		if (Arbiter.at("open door", closeBehind(mc, p))) return true;
		if (Arbiter.at("close door", closeLeftOpen(mc, p))) return true;
		if (!spots.isEmpty()) return Arbiter.at("goto", toSpots(mc, p));
		hurry = false;
		Entity foe = Guard.watch(p, find(p));
		// on a ghast the alt does not get off for a fight: that would be a fall
		// keeping a base whose monsters are to be left alone (Modes)
		if (foe != null && keep && (target == null || staying) && !c.baseFight) foe = null;
		// a creeper swelling at the buddy while the pen holds the hands: away from it, or the blow that kills it, before the
		// fight, the unloader and the corpse guard, which hold the farm step back (Pen.creeper; the third review of 22 Oct
		// 2026: a fight with a zombie at the farming spot left a creeper swelling alongside). Of those only the fight may take the
		// tick from a getaway, as the step below always takes it (the review of the fourth round: the unloader and the
		// corpse guard got one tick of walking in ten and were walked back to the cell past a block, for two minutes)
		if (Pen.busy()) {
			boolean fight = foe != null && !(p.getVehicle() instanceof HappyGhast);
			boolean held = fight || Unloader.isBusy() || (c.followCarry && Corpse.busy());
			if (Arbiter.at("creeper", Pen.creeper(mc, p, held, fight))) return true;
		}
		if (foe != null && !(p.getVehicle() instanceof HappyGhast)) return Arbiter.at("fight", fight(mc, p, foe));
		// !dump: the unloader walks the buddy to the chests by these paths; meanwhile no following and no looking at the player
		if (Unloader.isBusy()) return Arbiter.at("unload", Unloader.walkTick(mc, p, find(p)));
		// the buddy's things after a death: fetched before following on (Corpse)
		if (c.followCarry && Arbiter.at("corpse guard", Corpse.tick(mc, p))) return true;
		// the player's taps on blocks: a job on or off, the walk to a new home (Tap)
		if (Arbiter.at("tap", Tap.tick(mc, p))) return true;
		// the mob farm the player showed with a bare-hand punch: the pen (Pen)
		if (Arbiter.at("farm mobs", Pen.tick(mc, p, c, find(p)))) return true;
		// staying, and shot at by a foe the fight leaves be in its pit: out of its sight, and held there while the cover is
		// fresh, so that the carrier and the base wait (Pen.cover); looking at the player there, as the stay does
		if (staying && target != null && Arbiter.at("take cover", Pen.cover(mc, p))) {
			if (Pen.holdsCover()) { Player t = find(p); if (t != null) lookAt(p, t); }
			return true;
		}
		// what the player threw at the alt: home with it (Carrier)
		if (target != null && c.followCarry && Arbiter.at("carrier", Carrier.tick(mc, p))) return true;
		// every tool of a kind nearly worn out and iron at home: home to make a new one, then back (Renew)
		if (target != null && !staying && Arbiter.at("renew", Renew.tick(mc, p))) return true;
		// left in the base, not following or told to stay: the buddy keeps it (Base)
		if (keep && spots.isEmpty() && (target == null || staying)) {
			if (Arbiter.at("base", Base.tick(mc, p))) return true;
			if (target == null) { halt(); hover(mc, p, p.getY() + 1); return false; }
		}
		if (staying) {
			halt();
			hover(mc, p, p.getY() + 1);
			Player t = find(p);
			if (t != null) lookAt(p, t);
			return Arbiter.at("stay", t != null);
		}
		if (Arbiter.at("sleep", sleep(mc, p))) return true;
		if (Arbiter.at("ride", ride(mc, p))) return true;

		String dim = level.dimension().identifier().toString();
		Player t = find(p);
		if (t != null) {
			if (lost) { lost = false; Exchange.emit("follow", "action", "found", "player", target, "distance", round(t.distanceTo(p))); }
			tpaOpen = false;
			offlineSince = 0;
			if (afk != 0) { afk = 0; Exchange.emit("follow", "action", "back from afk"); }
			lastSeen = t.position(); lastDim = dim;
			// what the player works at (a tunnel dug, trees chopped, the chests): help with it, as long as the player does not walk off
			if (spots.isEmpty()) {
				Work.watch(p, t);
				if (Arbiter.at("work", Work.tick(mc, p, t))) return true;
			}
			double dh = Math.hypot(t.getX() - p.getX(), t.getZ() - p.getZ());
			if (Math.abs(t.getY() - p.getY()) < 2.5 && dh <= (walking ? NEAR : FAR)) {
				// in the water beside the player, who stands on land: out onto the bank, not bobbing there (18 Oct 2026)
				if (p.isInWater() && !t.isInWater()) {
					BlockPos land = landBeside(level, p, t);
					if (land != null) {
						walking = true;
						walkTo(mc, p, Vec3.atBottomCenterOf(land), true, t);
						return true;
					}
				}
				// a monster within twelve blocks of the player (step 3 of the plan for fighting, 24 Oct 2026): beside the player
				// on its side, 1.7 blocks off, facing it, so that what comes at the player meets the alt first
				Entity near = !p.isInWater() ? Guard.threat(p, t, 12, false) : null;
				if (near != null) {
					Vec3 side = guardSpot(level, t, near);
					if (side != null && Math.hypot(side.x - p.getX(), side.z - p.getZ()) > 1.0) {
						walking = true;
						walkTo(mc, p, side, true, t);
						return Arbiter.at("with player", true);
					}
					halt();
					face(p, near.getEyePosition(), 10f, 6f);
					return Arbiter.at("with player", true);
				}
				halt();
				hover(mc, p, t.getY());
				lookAt(p, t);
				return Arbiter.at("with player", true);
			}
			walking = true;
			walkTo(mc, p, t.position(), false, t);
			return Arbiter.at("follow", true);
		}
		if (!lost) {
			lost = true;
			lostAt = now;
			Exchange.emit("follow", "action", "lost", "player", target, "dim", lastDim == null ? "" : lastDim,
				"x", lastSeen == null ? 0 : Math.round(lastSeen.x), "y", lastSeen == null ? 0 : Math.round(lastSeen.y), "z", lastSeen == null ? 0 : Math.round(lastSeen.z));
		}
		if (afk != 0) return Arbiter.at("afk", afkTick(mc, p, now));
		// offline for ten seconds, or the /tpa unanswered: to the AFK zone, and wait there
		if (!online(mc)) {
			if (offlineSince == 0) offlineSince = now;
			else if (now - offlineSince > 10_000) { startAfk(mc, "offline"); return false; }
		} else offlineSince = 0;
		if (tpaOpen && now - lastTpa > 70_000) { startAfk(mc, "no answer"); return false; }
		// Gone while still online, and not through a portal (into which the buddy walks after the player) or out of sight by walking off:
		// teleported. Ask to come along, once (for instance with /tpa Steve).
		boolean here = lastSeen != null && dim.equals(lastDim);
		boolean portal = here && portalAt(level, BlockPos.containing(lastSeen));
		boolean walkedOff = here && Math.hypot(lastSeen.x - p.getX(), lastSeen.z - p.getZ()) > 90;
		// a server without /tpa in its profile: the buddy goes to where the player was seen last, and waits there
		if (!portal && !walkedOff && !tpaOpen && online(mc) && now - lostAt > 2500 && now - lastTpa > 60_000 && Profiles.cmd("tpa", target) != null) {
			// ready to stand still first: an accepted /tpa starts a wait of three seconds
			if (fixStill(mc, p, now, null)) return true;
			halt();
			lastTpa = now;
			tpaOpen = true;
			lastSeen = null;
			AfkHelperClient.sendTyped(Profiles.cmd("tpa", target), null);
			Exchange.emit("follow", "action", "tpa", "player", target);
		}
		// gone: to the spot where the player was last seen, in this dimension; there stand and wait (in a portal that takes you along)
		if (lastSeen == null || !dim.equals(lastDim)) { halt(); hover(mc, p, p.getY() + 1); return false; }
		if (Math.hypot(lastSeen.x - p.getX(), lastSeen.z - p.getZ()) < 0.6 && Math.abs(lastSeen.y - p.getY()) < 1.5) {
			lastSeen = null;
			halt();
			return false;
		}
		walking = true;
		walkTo(mc, p, lastSeen, true, null);
		return Arbiter.at("search", true);
	}

	/** The crouches of the players near the buddy, read as a sign once a burst of them is over. */
	private static void sign(LocalPlayer p) {
		long now = System.currentTimeMillis();
		for (Player o : p.level().players()) {
			if (o == p) continue;
			String name = o.getGameProfile().name();
			boolean down = o.isShiftKeyDown();
			Boolean before = crouchedBefore.put(name, down);
			if (!down || before == null || before) continue;   // only the moment of going down
			Long prev = crouchedAt.put(name, now);
			burst.put(name, prev != null && now - prev <= 800 ? burst.getOrDefault(name, 1) + 1 : 1);
			burstHow.put(name, new boolean[] { looksAt(o, p), Guard.isSignWeapon(o.getMainHandItem()), o.getXRot() < -55f,
				o.getMainHandItem().isEmpty() && o.getOffhandItem().isEmpty(), o.distanceTo(p) <= 10, o.distanceTo(p) <= Pen.GESTURE_NEAR });
		}
		// a burst is over once 0.85 s went by without a crouch
		for (var it = burst.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			Long at = crouchedAt.get(e.getKey());
			if (at == null || now - at < 850) continue;
			it.remove();
			crouchedAt.remove(e.getKey());
			boolean[] how = burstHow.remove(e.getKey());
			if (how != null && how[HOW_NEAR]) signed(e.getKey(), e.getValue(), how, now);
		}
	}

	/**
	 * The sign: a player looks at the buddy from within ten blocks and crouches twice within 0.8 s. Without a weapon only
	 * the followed player counts: stay, or follow again. With a weapon (a sword, spear, mace or trident: Guard.isSignWeapon;
	 * a tool is none) only a trusted player counts (the protection). A sign
	 * never makes the buddy follow someone else: that is a command. Five crouches or more, looking at the buddy with nothing in the
	 * hands, ask to forget what the buddy knows of this server (a wipe of the memory): a head shake and one crouch answer it, and a sign of two within the next seconds confirms it.
	 */
	private static void signed(String name, int count, boolean[] how, long now) {
		AfkConfig c = AfkConfig.get();
		boolean his = name.equalsIgnoreCase(target);
		if (count >= 5) {
			if (his && how[HOW_LOOKS] && how[HOW_EMPTY]) {
				forgetAskedUntil = now + 7000;
				answer(ANS_ASK);
				Exchange.emit("follow", "action", "forgetting asked", "player", name);
			}
			return;
		}
		if (count != 2) return;
		if (his && forgetAskedUntil > now && how[HOW_LOOKS] && how[HOW_EMPTY]) {
			forgetAskedUntil = 0;
			Profiles.forget("all", target, "gesture");
			nodTicks = 12;
			return;
		}
		// While the buddy farms a pen, two crouches of the followed player within six blocks end it, no look needed: then
		// the buddy waits there, and in a base keeps it. A weapon held up to the sky stays the sign for fighting players.
		if (his && spots.isEmpty() && Pen.on() && how[HOW_CLOSE] && !(how[HOW_ARMED] && how[HOW_UP])) {
			Pen.stop("sign", true);
			setStaying(true, "sign");
			answer(ANS_ONCE);
			return;
		}
		if (!(c.isTrusted(name) || his)) return;
		boolean armed = how[HOW_ARMED];
		// protection only by a trusted player, staying and following again only by the followed player: whom to follow
		// changes by !follow alone
		if (armed && !c.isTrusted(name)) return;
		if (!armed && !his) return;
		// a weapon held up to the sky: fighting players on or off
		if (armed && how[HOW_UP]) {
			answer(Guard.togglePvp("sign") ? ANS_PVP_ON : ANS_PVP_OFF);
			return;
		}
		if (!how[HOW_LOOKS]) return;
		// a weapon in hand, looking at the buddy: protection on or off
		if (armed) {
			answer(Guard.toggle("sign") ? ANS_GUARD_ON : ANS_GUARD_OFF);
			return;
		}
		if (spots.isEmpty()) {
			setStaying(!staying, "sign");
			if (staying) nodTicks = 12; else hopTicks = 4;
		}
	}

	/** Does that player look at the buddy: that player's line of sight through the buddy's body, a little widened. */
	private static boolean looksAt(Player o, LocalPlayer p) {
		Vec3 eye = o.getEyePosition();
		return p.getBoundingBox().inflate(0.4).clip(eye, eye.add(o.getViewVector(1f).scale(12))).isPresent();
	}

	/** The answer to the sign: a nod (the head down and up) for staying, a hop for following. */
	private static void answer(int kind) {
		answerKind = kind;
		answerTicks = kind == ANS_ASK || kind == ANS_GUARD_OFF ? 22 : 14;
		LocalPlayer p = AfkHelperClient.mc().player;
		if (p != null) answerYaw = p.getYRot();
	}

	private static boolean answer(Minecraft mc, LocalPlayer p) {
		if (answerTicks > 0) {
			answerTicks--;
			float pitch = p.getXRot();
			switch (answerKind) {
				case ANS_GUARD_ON -> {
					// the weapon in hand, and one swing with it
					if (answerTicks == 13) Guard.arm(mc, p, true);
					if (answerTicks == 6) Guard.queueSwing();
					pitch = 0f;
				}
				case ANS_PVP_ON -> {
					// up to the sky as well, and two swings
					if (answerTicks == 13) Guard.arm(mc, p, true);
					if (answerTicks == 7 || answerTicks == 3) Guard.queueSwing();
					pitch = -60f;
				}
				default -> pitch = answerTicks >= 7 ? 45f : 0f;   // fighting players off: a nod, deep
			}
			if (answerKind == ANS_ONCE) {
				HeldKeys.set(mc.options.keyShift, answerTicks <= 10 && answerTicks >= 6);
				return true;
			}
			if (answerKind == ANS_ASK || answerKind == ANS_NO || answerKind == ANS_GUARD_OFF) {
				// a shake: the head left and right twice, level; for the question one crouch after it, for the protection
				// off the weapon away first (until 24 Oct 2026 a nod, which looked like the nod of staying)
				int t = answerTicks;
				if (answerKind == ANS_GUARD_OFF && t == 21) Guard.putAway(p);
				float off = t >= 10 ? ((t / 3) % 2 == 0 ? 25f : -25f) : 0f;
				MouseTurn.toward(p, p.getYRot() + Mth.clamp(answerYaw + off - p.getYRot(), -20f, 20f), p.getXRot() + Mth.clamp(-p.getXRot(), -15f, 15f));
				if (answerKind == ANS_ASK) HeldKeys.set(mc.options.keyShift, t <= 7 && t >= 4);
				return true;
			}
			MouseTurn.toward(p, p.getYRot(), p.getXRot() + Mth.clamp(pitch - p.getXRot(), -15f, 15f));
			return true;
		}
		if (hopTicks > 0) {
			if (hopTicks == 4 && p.onGround()) { HeldKeys.set(mc.options.keyJump, true); jumpTicks = 2; }
			hopTicks--;
			return true;
		}
		nodTicks--;
		float want = nodTicks >= 6 ? 35f : 0f;
		MouseTurn.toward(p, p.getYRot(), p.getXRot() + Mth.clamp(want - p.getXRot(), -12f, 12f));
		return true;
	}

	/** To the spots one after the other: a spot counts once stood on, then the next. */
	private static boolean toSpots(Minecraft mc, LocalPlayer p) {
		String dim = p.level().dimension().identifier().toString();
		if (spotsDim != null && !dim.equals(spotsDim)) {
			// Not there (yet): sent before the buddy joined the game, the wait lasts until the buddy is in it, so that a
			// !parkour sent early starts as soon as it can. Out of it again, or never there in ten minutes: the game
			// is over or did not go ahead, and following resumes.
			if (spotsSeen || System.currentTimeMillis() - spotsSince > 10 * 60_000L) {
				spots.clear(); leap = null; release(); clearRoute();
				Exchange.emit("goto", "action", "stop", "reason", spotsSeen ? "out of the dimension" : "never in the dimension");
			} else halt();
			return false;
		}
		spotsSeen = true;
		// Moved more than four blocks in a tick: a teleport (the game putting the buddy on its start, out of the waiting room,
		// where there was no way to the spot). Plan from here at once: waiting for the next plan cost up to two seconds.
		if (spotsLast != null && spotsLast.distanceToSqr(p.position()) > 16) {
			release(); clearRoute(); leap = null;
			nextPlan = ticks;
			Exchange.emit("goto", "action", "moved", "x", p.getBlockX(), "y", p.getBlockY(), "z", p.getBlockZ());
		}
		spotsLast = p.position();
		BlockPos s = spots.get(0);
		// seated: the ride goes on while it gets the buddy nearer, since the walk keys move the seat and not the buddy (6 Nov
		// 2026: a walk sent from the panel went nowhere while the buddy sat on a cushion). In bed: up first
		if (standTicks > 0) { HeldKeys.set(mc.options.keyShift, --standTicks > 0); return true; }
		if (p.isSleeping()) { p.connection.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.STOP_SLEEPING)); return true; }
		if (p.isPassenger()) return rideTo(mc, p, s);
		rideOn = null;
		// off a happy ghast the buddy stands on top of it: down over the edge with the shortest drop first
		if (stepOffGhast(mc, p, null)) return true;
		if (p.onGround() && Math.abs(p.getX() - (s.getX() + 0.5)) < 0.55 && Math.abs(p.getZ() - (s.getZ() + 0.5)) < 0.55 && Math.abs(p.getY() - s.getY()) < 0.9) {
			spots.remove(0);
			Exchange.emit("goto", "action", "point", "x", s.getX(), "y", s.getY(), "z", s.getZ(), "left", spots.size());
			clearRoute();
			leap = null;
			nextPlan = ticks;   // plan at once: the buddy may be running, and waiting for a plan would stop the run dead
			if (spots.isEmpty()) { halt(); release(); Routes.arrived(spotsDim, spotsFrom, s, System.currentTimeMillis() - spotsSince); return false; }
			s = spots.get(0);
		}
		walking = true;
		hurry = true;
		walkTo(mc, p, Vec3.atBottomCenterOf(s), true, null);
		// a trace while walking to spots, every four ticks, to read back where the time goes (no screen on the alt)
		if (trace && ticks % 4 == 0) {
			BlockPos wp = route != null && idx < route.size() ? route.get(idx) : null;
			Exchange.emit("goto", "action", "trace", "tick", ticks, "x", round3(p.getX()), "y", round3(p.getY()), "z", round3(p.getZ()),
				"v", round3(Math.hypot(p.getDeltaMovement().x, p.getDeltaMovement().z)), "vy", round3(p.getDeltaMovement().y),
				"cell", wp == null ? "" : wp.getX() + "," + wp.getY() + "," + wp.getZ(), "place", wp == null ? -1 : move(idx),
				"climbs", p.onClimbable(), "ground", p.onGround(), "sprint", p.isSprinting(), "jump", leap != null,
				"up", mc.options.keyUp.isDown(), "jump", mc.options.keyJump.isDown());
		}
		return true;
	}

	/** {@code "trace": true} in a {@code goto} command: the trace above. */
	static boolean trace;

	/** From the buddy's eye to the nearest point of its box: within 2.7 the fight hits, beyond it walks. */
	static double reach(LocalPlayer p, Entity e) {
		AABB b = e.getBoundingBox();
		Vec3 eye = p.getEyePosition();
		return eye.distanceTo(new Vec3(Mth.clamp(eye.x, b.minX, b.maxX), Mth.clamp(eye.y, b.minY, b.maxY), Mth.clamp(eye.z, b.minZ, b.maxZ)));
	}

	/**
	 * Why the fight leaves a foe be, or null: out of the buddy's reach in a closed space that is a drop below or a farm, so
	 * that the way to it is a fall into it (Pen.enclosedWhy; 0.4.80, the pen). Guard asks it before it picks a foe, every
	 * time, so one that comes out and attacks is fought at once.
	 */
	static String pitFoe(LocalPlayer p, Entity e) {
		return reach(p, e) > 2.7 ? Pen.enclosedWhy(p, e) : null;
	}

	/**
	 * Fighting: to the enemy the way of following, and in reach the best weapon, the crosshair on the enemy and a blow at
	 * full strength, as a critical hit where it can be: a jump timed so that the blow comes while falling, not sprinting,
	 * when the strength is back. In water, on a ladder or under a low ceiling the plain blow.
	 */
	private static boolean fight(Minecraft mc, LocalPlayer p, Entity e) {
		var o = mc.options;
		// a foe out of reach in a pit or a farm's cell is not walked to (pitFoe); Guard picks none, so this only meets
		// one that came to lie there within the tick. Before the flag of walking, which would pull the buddy to NEAR next tick
		String pit = pitFoe(p, e);
		if (pit != null) { Guard.stop(pit); return false; }
		walking = true;
		if (p.isPassenger() || p.isSleeping()) {
			// up first: off a cushion or out of a boat with a crouch, out of bed as its button does
			if (p.isSleeping()) p.connection.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.STOP_SLEEPING));
			else { standTicks = 4; HeldKeys.set(o.keyShift, true); }
			seated = false;
			return true;
		}
		// at four hearts or less with a golden apple or food: away from it and a bite first (heal)
		if (heal(mc, p, e)) return true;
		double dist = reach(p, e);
		// one the buddy cannot or should not hit: the bow, standing (Bow; the plan for fighting of 24 Oct 2026, step 2)
		if (Bow.wanted(p, e, dist, System.currentTimeMillis() - Guard.lastBlowAt(e))) {
			release();
			clearRoute();
			if (Bow.tick(mc, p, e)) return true;
		}
		Guard.arm(mc, p, false);
		AABB b = e.getBoundingBox();
		Vec3 eye = p.getEyePosition();
		boolean onIt = mc.hitResult instanceof EntityHitResult h && h.getEntity() == e;
		// one that aims a bow or a crossbow at the buddy: the shield up on the way to it too (Shield)
		if (Shield.aims(e)) Shield.want("archer");
		// a player is hit from 2.9 (PvP point 3 of 24 Oct 2026; the game's reach is 3): the buddy's blow lands before the other's
		boolean player = e instanceof Player;
		double hitAt = player ? PLAYER_REACH : 2.7;
		long now0 = System.currentTimeMillis();
		// the opener is due: a player, the strength back, none in the last three seconds (PvP point 2)
		boolean openDue = player && now0 - openedAt > 3000 && p.getAttackStrengthScale(0.5f) >= 0.9f && !p.isUsingItem();
		if (dist > hitAt) {
			// not hit for twenty seconds while gone for: out of reach (on a pillar, over water), left alone
			if (now0 - Guard.lastBlowAt(e) > Guard.NO_BLOW) { Guard.outOfReach(e); release(); clearRoute(); return false; }
			// the run-in for the opener: within six blocks, in sight, on the ground and nothing unsafe ahead, straight at the
			// foe at a run, looking at the foe (a route lets go of the sprint over its last cells, so the buddy came in walking, 24 Oct 2026)
			if (openDue && dist < 6 && p.hasLineOfSight(e) && p.onGround() && !p.isInWater() && p.getFoodData().getFoodLevel() > 6
				&& !Pen.unsafeAhead(p) && Math.abs(e.getY() - p.getY()) < 1.1) {
				clearRoute();
				leap = null;
				Vec3 c = e.getBoundingBox().getCenter(), ey = p.getEyePosition();
				float y0 = (float) Math.toDegrees(Math.atan2(-(c.x - ey.x), c.z - ey.z)), x0 = (float) -Math.toDegrees(Math.atan2(c.y - ey.y, Math.hypot(c.x - ey.x, c.z - ey.z)));
				MouseTurn.toward(p, p.getYRot() + Mth.clamp(Mth.wrapDegrees(y0 - p.getYRot()), -25f, 25f), p.getXRot() + Mth.clamp(x0 - p.getXRot(), -15f, 15f));
				HeldKeys.set(o.keyUp, Math.abs(Mth.wrapDegrees(y0 - p.getYRot())) < 30f);
				HeldKeys.set(o.keySprint, true);
				HeldKeys.set(o.keyShift, false);
				HeldKeys.set(o.keyDown, false);
				HeldKeys.set(o.keyLeft, false);
				HeldKeys.set(o.keyRight, false);
				runInAt = now0;
				return true;
			}
			hurry = true;
			walkTo(mc, p, e.position(), false, null);
			return true;
		}
		clearRoute();
		leap = null;
		Vec3 mid = b.getCenter();
		double dx = mid.x - eye.x, dy = mid.y - eye.y, dz = mid.z - eye.z;
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)), pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
		MouseTurn.toward(p, p.getYRot() + Mth.clamp(Mth.wrapDegrees(yaw - p.getYRot()), -25f, 25f), p.getXRot() + Mth.clamp(pitch - p.getXRot(), -15f, 15f));
		boolean sprinting = p.isSprinting();
		long now = System.currentTimeMillis();
		float strength0 = p.getAttackStrengthScale(0.5f);
		// the opener against a player (PvP point 2): come in at a run, the first blow still sprinting, which knocks the foe
		// back (no critical hit then), so that the foe's own first blow falls short; once in three seconds at most
		if (openDue && sprinting && strength0 >= 0.9f) {
			HeldKeys.set(o.keyUp, true);
			HeldKeys.set(o.keySprint, true);
			if (onIt) {
				Guard.queueHit(e);
				openedAt = now;
				Exchange.emit("guard", "action", "opens", "goal", Guard.nameOf(e));
				return true;
			}
			// the crosshair a tick or two behind the turn: still running, for a quarter of a second after the run-in
			if (now - runInAt < 250 && dist > 1.2) return true;
		}
		// the blow for the foe (step 2 of the plan for fighting, 24 Oct 2026): a creeper a sprinting blow, which knocks it
		// back, and then away from it; a crowd round the foe a plain blow on the ground, which with Sweeping Edge sweeps the
		// others; everything else the critical hit
		boolean creeper = e instanceof Creeper, sweep = !creeper && sweeps(p, e);
		boolean away = now < backOffUntil && safeBehind(p);
		// keeping a blow's length off a monster that hits in reach (a zombie): a step back while it is closer than 1.3, where
		// it is safe behind, so that its blow falls short and the buddy's does not
		boolean tooClose = !creeper && (player ? dist < 2.0 : dist < 1.3) && safeBehind(p);
		boolean knock = creeper && dist <= 2.7 && p.getFoodData().getFoodLevel() > 6 && !away;
		// a step closer, never sprinting but for the knock: a sprinting blow is a knock back and no critical hit
		HeldKeys.set(o.keyUp, !away && !tooClose && (knock || (dist > (player ? 2.75 : 2.3) && !sprinting)) && !Pen.unsafeAhead(p));
		HeldKeys.set(o.keySprint, knock);
		HeldKeys.set(o.keyShift, false);
		HeldKeys.set(o.keyDown, away || tooClose);
		float strength = p.getAttackStrengthScale(0.5f), delay = Math.max(6f, p.getCurrentItemAttackStrengthDelay());
		// strafing against a player (PvP point 2): sideways while the strength comes back, one way for 0.6 to 1.2 s, the
		// other way where that side is not safe; harder to hit than a fighter who stands still between blows
		boolean strafe = player && strength < 0.9f && !away && !tooClose;
		if (strafe) {
			if (now > strafeUntil) {
				strafeLeft = java.util.concurrent.ThreadLocalRandom.current().nextBoolean();
				strafeUntil = now + java.util.concurrent.ThreadLocalRandom.current().nextLong(600, 1200);
				if (now - strafeToldAt > 5000) { strafeToldAt = now; Exchange.emit("guard", "action", "strafes", "goal", Guard.nameOf(e)); }
			}
			if (!safeSide(p, strafeLeft)) strafeLeft = !strafeLeft;
			if (!safeSide(p, strafeLeft)) strafe = false;
		}
		HeldKeys.set(o.keyLeft, strafe && strafeLeft);
		HeldKeys.set(o.keyRight, strafe && !strafeLeft);
		// the shield between two blows (the plan for fighting of 24 Oct 2026): up while the strength comes back, down from
		// seven tenths, a few ticks before the blow, which no client can strike with a shield up. Not at a creeper, whose
		// blast the reflex above blocks, and it walks no closer: a raised shield slows the buddy to a fifth
		if (!(e instanceof Creeper) && strength < 0.7f && dist <= 2.7) Shield.want("between blows");
		boolean jumpable = !p.isInWater() && !p.onClimbable() && passable(p.level(), p.blockPosition().above(2), false)
			&& !Pen.near(p, Pen.NO_JUMP) && !Pen.enclosed(p, e) && !sweep && !creeper;
		// a player behind a raised shield (step 4 of the plan for fighting, 24 Oct 2026): an axe in hand for a blow, which
		// takes the shield away for five seconds whatever its strength (Player.blockUsingItem); the sword back after it
		if (e instanceof Player pl && pl.isBlocking() && onIt && !p.isUsingItem()) {
			int axe = -1;
			for (int i = 0; i < 9; i++) if (p.getInventory().getItem(i).is(net.minecraft.tags.ItemTags.AXES) && !Work.nearlyBroken(p.getInventory().getItem(i))) { axe = i; break; }
			if (axe >= 0) {
				if (p.getInventory().getSelectedSlot() != axe) { p.getInventory().setSelectedSlot(axe); axeUntil = now + 1200; return true; }
				if (strength >= 0.5f) { Guard.queueHit(e); axeUntil = now + 400; }
				return true;
			}
		}
		if (knock) {
			// the knock: the blow once running, then a second and a half away from it
			if (sprinting && strength >= 0.95f && onIt) { Guard.queueHit(e); backOffUntil = now + 1500; }
			return true;
		}
		if (p.onGround()) {
			// up in time to fall when the strength is back: a jump takes about six ticks to start falling
			if (jumpable && !sprinting && strength >= 1f - 6f / delay && jumpTicks == 0) { HeldKeys.set(o.keyJump, true); jumpTicks = 2; }
			else if (!jumpable && strength >= 0.95f && onIt) Guard.queueHit(e);
		} else if (p.getDeltaMovement().y < 0 && p.fallDistance > 0 && strength >= 0.92f && onIt) Guard.queueHit(e);
		else if (!jumpable && strength >= 0.95f && onIt) Guard.queueHit(e);
		return true;
	}

	private static long backOffUntil, openedAt, runInAt, strafeUntil, strafeToldAt;
	private static boolean strafeLeft;
	/** The reach a player is hit from: the game's is 3, from the eye to the box. */
	static final double PLAYER_REACH = 2.9;
	/** An axe held for a shield until this moment: Guard.arm leaves it in hand. */
	static long axeUntil;

	/**
	 * A plain blow sweeps (Player.attack): a sword, with Sweeping Edge, since without it the others get one point, and
	 * two monsters or more within a block of the foe beside it.
	 */
	private static boolean sweeps(LocalPlayer p, Entity e) {
		ItemStack hand = p.getMainHandItem();
		if (!hand.is(net.minecraft.tags.ItemTags.SWORDS)) return false;
		boolean edge = false;
		for (var h : hand.getEnchantments().keySet()) if (h.is(net.minecraft.world.item.enchantment.Enchantments.SWEEPING_EDGE)) edge = true;
		if (!edge) return false;
		return p.level().getEntitiesOfClass(LivingEntity.class, e.getBoundingBox().inflate(1.0, 0.25, 1.0),
			o -> o != e && o != p && o.isAlive() && o instanceof net.minecraft.world.entity.monster.Enemy && !Pen.claims(o)).size() >= 2;
	}

	/**
	 * A step back is safe: the cell behind (away from the look) has room for feet and head, a floor at most
	 * a block down, and nothing that burns or hurts.
	 */
	private static boolean safeBehind(LocalPlayer p) {
		double yaw = Math.toRadians(p.getYRot());
		return safeAt(p, Math.sin(yaw) * 0.8, -Math.cos(yaw) * 0.8);
	}

	/** A step to the left or the right is safe, as {@link #safeBehind}: left is (cos yaw, sin yaw) in the game's turning. */
	private static boolean safeSide(LocalPlayer p, boolean left) {
		double yaw = Math.toRadians(p.getYRot()), s = left ? 0.8 : -0.8;
		return safeAt(p, Math.cos(yaw) * s, Math.sin(yaw) * s);
	}

	private static boolean safeAt(LocalPlayer p, double ox, double oz) {
		BlockPos c = BlockPos.containing(p.getX() + ox, p.getY() + 0.01, p.getZ() + oz);
		Level level = p.level();
		if (!level.getBlockState(c).getCollisionShape(level, c).isEmpty() || !level.getBlockState(c.above()).getCollisionShape(level, c.above()).isEmpty()) return false;
		if (Grinder.dropAhead(level, c)) return false;
		for (BlockPos q : new BlockPos[] { c, c.below(), c.below(2) }) {
			var st = level.getBlockState(q);
			if (st.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) || st.is(net.minecraft.tags.BlockTags.FIRE) || st.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
				|| st.is(net.minecraft.world.level.block.Blocks.CACTUS) || st.is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH) || st.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)
				|| st.is(net.minecraft.tags.BlockTags.CAMPFIRES)) return false;
			if (!st.getCollisionShape(level, q).isEmpty()) break;
		}
		return !Follow.fenced(c);
	}

	private static long healFrom, healNext;
	private static boolean healEating;

	/**
	 * Healing in a fight (the plan for fighting, step 1: below 8 hp the buddy walks away from the foe, toward the player or
	 * the base, eats a golden apple or good food, and only then goes back): at four hearts or
	 * less, with a golden apple, or food while the belly is not full (hearts come back from food only on a full one), a
	 * walk away from the foe until it is five blocks off or three seconds have passed, sprinting where the way allows, and
	 * a bite: a golden apple first (an enchanted one under two hearts or when there is no plain one), else the best food. The
	 * fight goes on after the bite. Not from a creeper (the shield is for that), and not again within ten seconds of a bite,
	 * so that a foe that keeps up does not make the buddy run for ever; what does not get going within six seconds is given up.
	 */
	private static boolean heal(Minecraft mc, LocalPlayer p, Entity e) {
		long now = System.currentTimeMillis();
		if (healEating) {
			if (AfkHelperClient.biting()) { release(); return true; }
			healEating = false;
			healNext = now + 10_000;
			healFrom = 0;
			Exchange.emit("guard", "action", "has eaten", "hp", Math.round(p.getHealth()));
			return false;
		}
		// begun at four hearts, it goes on while healing on the way out (a full belly heals half a heart a second), until seven
		if ((healFrom == 0 ? p.getHealth() > 8 : p.getHealth() > 14) || now < healNext || e instanceof Creeper) { healFrom = 0; return false; }
		var inv = p.getInventory();
		boolean apple = false, gold = false, food = false;
		for (int i = 0; i < 36; i++) {
			ItemStack st = inv.getItem(i);
			if (st.is(Items.GOLDEN_APPLE)) apple = true;
			if (st.is(Items.ENCHANTED_GOLDEN_APPLE)) gold = true;
			if (AfkHelperClient.eatable(st)) food = true;
		}
		boolean canFood = food && p.getFoodData().getFoodLevel() < 20;
		if (!apple && !gold && !canFood) return false;
		if (healFrom == 0) { healFrom = now; Exchange.emit("guard", "action", e instanceof Player ? "eats at once" : "backs off", "hp", Math.round(p.getHealth()), "goal", Guard.nameOf(e)); }
		if (now - healFrom > 6000) { healNext = now + 10_000; healFrom = 0; return false; }
		// from a player no walk away first (PvP point 4): a sprinting player catches up and hits the buddy in the back; the bite
		// at once, with the totem in the off hand under four hearts (Stash.totem) as the net
		if (!(e instanceof Player) && reach(p, e) < 5 && now - healFrom < 3000 && !p.isInWater()) {
			Vec3 away = p.position().subtract(e.position());
			away = new Vec3(away.x, 0, away.z);
			if (away.lengthSqr() < 1e-4) away = new Vec3(1, 0, 0);
			hurry = true;
			walkTo(mc, p, p.position().add(away.normalize().scale(8)), false, null);
			return true;
		}
		release();
		clearRoute();
		java.util.function.Predicate<ItemStack> pick = gold && (!apple || p.getHealth() <= 4) ? st -> st.is(Items.ENCHANTED_GOLDEN_APPLE)
			: apple ? st -> st.is(Items.GOLDEN_APPLE) : AfkHelperClient::eatable;
		int slot = -1, best = -1;
		for (int i = 0; i < 9; i++) {
			ItemStack st = inv.getItem(i);
			if (!pick.test(st)) continue;
			var f = st.get(net.minecraft.core.component.DataComponents.FOOD);
			int val = f == null ? 0 : f.nutrition();
			if (val > best) { best = val; slot = i; }
		}
		if (slot < 0) {
			AfkHelperClient.toHotbar(p, mc, pick, java.util.Comparator.comparingInt(st -> { var f = st.get(net.minecraft.core.component.DataComponents.FOOD); return f == null ? 0 : f.nutrition(); }), "healing");
			return true;
		}
		if (AfkHelperClient.eatNow(p, slot, "fight")) healEating = true;
		return true;
	}

	/**
	 * Where to stand between the player and a monster: 1.7 blocks from the player towards it, on the cell there or one up
	 * or down, with room for feet and head and a floor; null when there is none (then the buddy stays put and faces it).
	 */
	private static Vec3 guardSpot(Level level, Player t, Entity mob) {
		Vec3 dir = new Vec3(mob.getX() - t.getX(), 0, mob.getZ() - t.getZ());
		if (dir.lengthSqr() < 1e-4) return null;
		// 1.7 and not two: within NEAR of the player, or the follow walk would pull the buddy back to the player's side every other tick
		Vec3 at = t.position().add(dir.normalize().scale(1.7));
		for (int dy : new int[] { 0, 1, -1 }) {
			BlockPos c = BlockPos.containing(at.x, t.getY() + 0.01 + dy, at.z);
			if (!level.getBlockState(c).getCollisionShape(level, c).isEmpty() || !level.getBlockState(c.above()).getCollisionShape(level, c.above()).isEmpty()) continue;
			if (level.getBlockState(c.below()).getCollisionShape(level, c.below()).isEmpty() || fenced(c)) continue;
			var st = level.getBlockState(c.below());
			if (st.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK) || st.is(net.minecraft.world.level.block.Blocks.CACTUS) || st.is(net.minecraft.tags.BlockTags.CAMPFIRES)) continue;
			return Vec3.atBottomCenterOf(c);
		}
		return null;
	}

	/** Turn toward a point, at most this much a tick, as the fight aims. */
	private static void face(LocalPlayer p, Vec3 at, float maxYaw, float maxPitch) {
		Vec3 eye = p.getEyePosition();
		double dx = at.x - eye.x, dy = at.y - eye.y, dz = at.z - eye.z;
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)), pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
		MouseTurn.toward(p, p.getYRot() + Mth.clamp(Mth.wrapDegrees(yaw - p.getYRot()), -maxYaw, maxYaw), p.getXRot() + Mth.clamp(pitch - p.getXRot(), -maxPitch, maxPitch));
	}

	/**
	 * Sleeping: while the player sleeps, a free bed near the player (within eight blocks), walked to and right-clicked; up
	 * again as soon as the player is, the way the button in bed does it. A bed that does not take the buddy within a second
	 * (monsters near, not yet night for the server) or is not reached in fifteen is skipped for a minute. Lying down in a
	 * bed also sets the buddy's own spawn point there, as for any player.
	 */
	private static boolean sleep(Minecraft mc, LocalPlayer p) {
		long now = System.currentTimeMillis();
		bedRefused.values().removeIf(until -> until < now);
		Player t = find(p);
		boolean heSleeps = t != null && t.isSleeping();
		BlockPos baseBed = baseNight(p, t);
		if (p.isSleeping()) {
			bed = null;
			bedClickedAt = -1;
			if (heSleeps || baseBed != null) { release(); return true; }
			if (now - wokeAt > 1000) {
				wokeAt = now;
				p.connection.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.STOP_SLEEPING));
				Exchange.emit("follow", "action", "out of bed");
			}
			return true;
		}
		if (heSleeps) return toBed(mc, p, t.blockPosition());
		if (baseBed != null) return toBed(mc, p, baseBed);
		bed = null; bedClickedAt = -1; return false;
	}

	private static BlockPos nightBed;
	private static long nightCheckedAt;

	/**
	 * The base's bed when it is night and the followed player is in that base too (until then only the player's
	 * own sleep sent the buddy to bed while following). Up again when that player leaves the base, or in the morning. Looked up once a second: the base is
	 * a file.
	 */
	private static BlockPos baseNight(LocalPlayer p, Player t) {
		long now = System.currentTimeMillis();
		if (now - nightCheckedAt < 1000) return nightBed;
		nightCheckedAt = now;
		nightBed = null;
		if (t == null || !bedtime(p.level()) || !Sleepers.allows(p)) return null;
		var home = Tap.baseAt(Work.dimOf(p.level()), p.position(), Base.RADIUS);
		if (home != null && Vec3.atCenterOf(home.bed()).distanceTo(t.position()) <= Base.RADIUS) nightBed = home.bed();
		return nightBed;
	}

	/**
	 * The night in the buddy's base (Base), also when alone there: when it is dark, to a free bed near the base's bed (that bed itself
	 * first) the same way as with the player, and lying there until the morning wakes the buddy. A bed the server refuses (monsters
	 * near, not dark enough yet for it) is tried again after a minute; meanwhile the base's work goes on.
	 */
	static boolean sleepInBase(Minecraft mc, LocalPlayer p, BlockPos baseBed) {
		long now = System.currentTimeMillis();
		bedRefused.values().removeIf(until -> until < now);
		if (p.isSleeping()) {
			if (bed != null) Exchange.emit("base", "action", "sleeps", "x", p.blockPosition().getX(), "y", p.blockPosition().getY(), "z", p.blockPosition().getZ());
			bed = null;
			bedClickedAt = -1;
			release();
			// with the option on, up again when nobody else has slept for a while (Sleepers)
			if (!Sleepers.allows(p) && now - wokeAt > 1000) {
				wokeAt = now;
				p.connection.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.STOP_SLEEPING));
				Exchange.emit("base", "action", "out of bed", "reason", "nobody else sleeps");
			}
			return true;
		}
		if (!bedtime(p.level())) { Sleepers.day(); if (bed != null) { bed = null; bedClickedAt = -1; } return false; }
		if (!Sleepers.allows(p)) { if (bed != null) { bed = null; bedClickedAt = -1; } return false; }
		return toBed(mc, p, baseBed, true);
	}

	/**
	 * Time for bed: from 18:45 on the game's clock (tick 12750) until the morning, or in a thunderstorm. The game's
	 * own "dark outside" came some 700 ticks before the server lets anyone sleep, so the bed refused the buddy at dusk.
	 * Once someone else sleeps, from the tick the server lets anyone sleep ({@link Profiles#sleepFrom}, the game's own
	 * unless the server's profile says later): two players lay down
	 * at that tick, the night went five seconds later, and the buddy stood by the bed waiting for 12750 (23 Oct 2026).
	 */
	static boolean bedtime(Level level) {
		long t = Math.floorMod(level.getOverworldClockTime(), 24000L);
		if (level.isThundering()) return true;
		if (t >= 12750 && t < 23400) return true;
		LocalPlayer p = AfkHelperClient.mc().player;
		return t >= Profiles.sleepFrom() && t < 23400 && p != null && Sleepers.now(p);
	}


	/**
	 * The server lays a player in a bed only with the feet within 3 blocks across and 2 up or down of the bottom of one of
	 * its halves (Player.isReachableBedBlock), here with a margin. The click went only within 3.4 blocks of its middle from
	 * the eye, while the spot by the bed was chosen with the reach for blocks: on a real server the buddy stood 23 s by the bed, 3.8 off, while
	 * three others slept, and lay down only when another bed came free nearer (22 Oct 2026).
	 */
	private static boolean bedReach(Level level, Vec3 feet, BlockPos bed) {
		for (BlockPos half : bedHalves(level, bed)) {
			Vec3 b = Vec3.atBottomCenterOf(half);
			if (Math.abs(feet.x - b.x) <= 2.8 && Math.abs(feet.y - b.y) <= 1.8 && Math.abs(feet.z - b.z) <= 2.8) return true;
		}
		return false;
	}

	/** Both halves of a bed, the given one first. */
	private static List<BlockPos> bedHalves(Level level, BlockPos bed) {
		BlockState st = level.getBlockState(bed);
		if (!st.hasProperty(BlockStateProperties.BED_PART) || !st.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return List.of(bed);
		Direction facing = st.getValue(BlockStateProperties.HORIZONTAL_FACING);
		BlockPos other = st.getValue(BlockStateProperties.BED_PART) == net.minecraft.world.level.block.state.properties.BedPart.FOOT
			? bed.relative(facing) : bed.relative(facing.getOpposite());
		return List.of(bed, other);
	}

	/** A point of the bed to click from this eye, on either half, in reach and in sight; null when none is. */
	private static Vec3 bedPoint(Level level, LocalPlayer p, Vec3 eye, BlockPos bed) {
		for (BlockPos half : bedHalves(level, bed)) {
			Vec3 pt = Work.aimPoint(level, p, eye, half);
			if (pt != null) return pt;
		}
		return null;
	}

	/** A cell by the bed from where lying down goes and a point of it is in sight: the nearest to the buddy. */
	private static BlockPos bedStand(Level level, LocalPlayer p, BlockPos bed) {
		BlockPos best = null;
		double bd = Double.MAX_VALUE;
		java.util.Set<BlockPos> seen = new java.util.HashSet<>();
		for (BlockPos half : bedHalves(level, bed)) for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -1; dy <= 1; dy++) {
			BlockPos c = half.offset(dx, dy, dz);
			if (!seen.add(c)) continue;
			double feet = floorY(level, c, false);
			if (Double.isNaN(feet)) continue;
			Vec3 at = new Vec3(c.getX() + 0.5, feet, c.getZ() + 0.5);
			if (!bedReach(level, at, bed) || bedPoint(level, p, at.add(0, p.getEyeHeight(), 0), bed) == null) continue;
			double d = p.position().distanceToSqr(at);
			if (d < bd) { bd = d; best = c.immutable(); }
		}
		return best;
	}

	/** Whether the followed player is in sight within 32 blocks: in the base then the buddy is not alone. */
	static boolean sees(LocalPlayer p) {
		Player t = find(p);
		return t != null && t.distanceTo(p) <= 32;
	}

	/** To a free bed near this spot, and into it: the walk, the click, a refusal skipped for a minute. */
	private static boolean toBed(Minecraft mc, LocalPlayer p, BlockPos near) { return toBed(mc, p, near, false); }

	/**
	 * {@code base}: from the base's work, where the follow walk does not run, so the walk goes the way the jobs' walks do, to
	 * a cell beside the bed (21 Oct 2026: with {@code baseSleepOthers} the buddy was at a field when the others lay down, and chose a
	 * new bed every fifteen seconds without a step).
	 */
	private static boolean toBed(Minecraft mc, LocalPlayer p, BlockPos near, boolean base) {
		long now = System.currentTimeMillis();
		Level level = p.level();
		if (bedClickedAt >= 0) {
			// two seconds: the server took 1.2 to lay the buddy down, and one second called a good bed a refusal (17 Oct 2026)
			if (ticks - bedClickedAt <= 40) { HeldKeys.set(mc.options.keyUp, false); HeldKeys.set(mc.options.keySprint, false); return true; }
			if (bed != null) {
				refuseBed(level, bed, now);
				Exchange.emit("follow", "action", "bed refuses", "x", bed.getX(), "y", bed.getY(), "z", bed.getZ());
			}
			bed = null;
			bedClickedAt = -1;
		}
		long walkFor = base ? 40_000 : 15_000;   // in the base the walk may come from the far side of it
		if (bed != null && (!freeBed(level, bed) || now - bedSince > walkFor)) {
			if (now - bedSince > walkFor) refuseBed(level, bed, now);
			bed = null;
		}
		if (bed == null) {
			bed = findBed(level, near);
			if (bed == null) return false;   // no free bed near the player: stand by the player as always
			bedSince = now;
			clearRoute();
			Exchange.emit("follow", "action", "goes to sleep", "x", bed.getX(), "y", bed.getY(), "z", bed.getZ());
		}
		// within the server's reach for a bed and a point of it in sight: click it
		Vec3 pt = p.onGround() && bedReach(level, p.position(), bed) ? bedPoint(level, p, p.getEyePosition(), bed) : null;
		if (pt != null) {
			halt();
			HeldKeys.set(mc.options.keyShift, false);
			aimExact(p, pt);
			// this bed's own halves: within a block of it took the bed beside it, which the turn sweeps over (an occupied one
			// gave "bed refuses" on the buddy's own free bed a moment later; the review of 22 Oct 2026)
			if (mc.hitResult instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK && bedHalves(level, bed).contains(bh.getBlockPos())
					&& level.getBlockState(bh.getBlockPos()).getBlock() instanceof AbstractBedBlock) clickBed = bed;
			return true;
		}
		if (base) {
			if (bedStandFor == null || !bedStandFor.equals(bed)) { bedStandFor = bed; bedStandCell = bedStand(level, p, bed); }
			Vec3 to = bedStandCell == null ? Vec3.atBottomCenterOf(bed) : Vec3.atBottomCenterOf(bedStandCell);
			if (!Work.approach(mc, p, "bed-" + bed.toShortString(), to, now)) { refuseBed(level, bed, now); bed = null; }
			return true;
		}
		walking = true;
		walkTo(mc, p, Vec3.atBottomCenterOf(bed), false, null);
		return true;
	}

	private static BlockPos bedStandFor, bedStandCell;

	/**
	 * A bed shunned for a minute, both halves: with one, the search took the other half of the same bed straight away, and
	 * every refusal cost twice the wait and two "bed refuses" (the review of 22 Oct 2026).
	 */
	private static void refuseBed(Level level, BlockPos bed, long now) {
		for (BlockPos half : bedHalves(level, bed)) bedRefused.put(half.immutable(), now + 60_000);
	}

	private static boolean freeBed(Level level, BlockPos pos) {
		BlockState st = level.getBlockState(pos);
		return st.getBlock() instanceof AbstractBedBlock && !st.getValue(AbstractBedBlock.OCCUPIED);
	}

	/** The free bed nearest to this spot, within eight blocks and three up or down. */
	private static BlockPos findBed(Level level, BlockPos at) {
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos q : BlockPos.betweenClosed(at.offset(-8, -3, -8), at.offset(8, 3, 8))) {
			if (bedRefused.containsKey(q) || !freeBed(level, q)) continue;
			double d = q.distSqr(at);
			if (d < bestD) { bestD = d; best = q.immutable(); }
		}
		return best;
	}

	/**
	 * A line of chat: a /tpahere of the followed player, to accept (of a trusted one while following nobody); the answer to
	 * the buddy's /tpa; how the buddy's /afk went; the start and end of a fight's tag (PvPManager).
	 */
	static void chat(String line) {
		if (!AfkHelperClient.active()) return;
		Work.chat(line);
		Stash.chat(line);
		long now = System.currentTimeMillis();
		if (Profiles.has("fightOff", line)) combatUntil = 0;
		else if (Profiles.has("fightOn", line)) combatUntil = now + 16_000;
		if (target != null && Profiles.has("declined", line, target)) { if (afk == 0 && isOn()) startAfk(AfkHelperClient.mc(), "refused"); return; }
		if (Profiles.has("expired", line)) { if (afk == 0 && tpaOpen) startAfk(AfkHelperClient.mc(), "expired"); return; }
		if (target != null && Profiles.has("accepted", line, target)) { tpaOpen = false; return; }
		if (afk == 1) {
			if (Profiles.has("afkAlready", line) || (afkTypedAt > 0 && now - afkTypedAt < 12_000 && Profiles.has("teleported", line))) {
				afk = 2;
				afkTypedAt = 0;
				wasOnline = online(AfkHelperClient.mc());
				Exchange.emit("follow", "action", "in afk", "reason", afkWhy);
				return;
			}
			if (Profiles.has("cancelledMoved", line)) afkFail = "moved";
			else if (Profiles.has("cancelledDamage", line)) afkFail = "damage";
			else if (Profiles.has("afkInFight", line)) afkFail = "fight";
			else if (Profiles.has("afkInEvent", line)) afkFail = "event";
		}
		Matcher m = Profiles.match("tpahere", line, null);
		if (!m.find()) return;
		String from = m.group(1);
		if (target != null ? !from.equalsIgnoreCase(target) : !AfkConfig.get().isTrusted(from)) return;
		// accepted once standing still for the three seconds is possible: in water a bob cancels the teleport (17 Oct 2026)
		acceptFrom = from;
		acceptUntil = now + 50_000;
		Exchange.emit("follow", "action", "tpahere", "from", from);
	}

	/**
	 * A request of the followed player that the server's own button in chat accepts, on a server whose profile has
	 * no line for it: accepted the same way as a /tpahere.
	 */
	static void requestByClick(String from) {
		if (!AfkHelperClient.active() || from == null || !from.equalsIgnoreCase(target)) return;
		acceptFrom = from;
		acceptUntil = System.currentTimeMillis() + 50_000;
		Exchange.emit("follow", "action", "request by click", "from", from);
	}

	/** Every tick, also when following no one: a /tpahere to accept, once the buddy can stand still for it. */
	private static boolean acceptTpahere(Minecraft mc, LocalPlayer p, long now) {
		if (acceptFrom == null) return false;
		if (now > acceptUntil) { acceptFrom = null; return false; }
		if (fixStill(mc, p, now, null)) return true;
		halt();
		String click = Profiles.clickAccept(acceptFrom), accept = click != null ? click : Profiles.cmd("tpaccept", acceptFrom);
		if (accept == null) { acceptFrom = null; return false; }
		AfkHelperClient.sendTyped(accept, null);
		Exchange.emit("follow", "action", "tpahere accepted", "from", acceptFrom);
		acceptFrom = null;
		return true;
	}

	// ---------- to the AFK zone ----------

	private static void startAfk(Minecraft mc, String why) {
		// a server without an AFK zone in its profile: the buddy waits on the spot, as in the zone
		afk = Profiles.cmd("afk", null) == null ? 2 : 1;
		afkWhy = why;
		afkTries = 0;
		afkTypedAt = 0;
		afkNextTry = 0;
		afkFail = null;
		tpaOpen = false;
		dry = null;
		aside = null;
		release();
		clearRoute();
		Exchange.emit("follow", "action", "goes afk", "reason", why);
	}

	/**
	 * On the way to the AFK zone, or waiting there. On the way: first whatever keeps the buddy from standing still three
	 * seconds put right (out of bed, off a seat, the attacker dead, out of an event, a fight's tag waited
	 * out, out of water or lava onto the nearest dry block, a step aside from whatever pushes), then /afk, standing
	 * still; a /afk that fails says why in chat, and that is put right before the next try. Waiting there: when the player
	 * comes back online, one /tpa, so that the player can take the alt along; seen again, the alt follows as ever.
	 */
	private static boolean afkTick(Minecraft mc, LocalPlayer p, long now) {
		var o = mc.options;
		if (afk == 2) {
			boolean on = online(mc);
			String tpa = Profiles.cmd("tpa", target);
			if (on && !wasOnline && now - lastTpa > 30_000 && tpa != null) {
				lastTpa = now;
				tpaOpen = true;
				AfkHelperClient.sendTyped(tpa, null);
				Exchange.emit("follow", "action", "tpa", "player", target, "reason", "online again");
			}
			wasOnline = on;
			if (tpaOpen && now - lastTpa > 70_000) tpaOpen = false;   // unanswered again: wait on
			halt();
			hover(mc, p, p.getY() + 1);
			return false;
		}
		if (now < afkNextTry) { halt(); hover(mc, p, p.getY() + 1); return false; }
		// a /afk just typed: stand still and wait for its outcome
		if (afkTypedAt > 0) {
			if (afkFail == null && now - afkTypedAt < 7000) { release(); return false; }
			if (afkFail == null) afkFail = "no answer";
			afkTypedAt = 0;
			afkTries++;
			Exchange.emit("follow", "action", "afk failed", "reason", afkFail, "times", afkTries);
			if (afkTries % 5 == 0) { afkNextTry = now + 30_000; return false; }
		}
		// what keeps the buddy from standing still, put right first
		String leave = Profiles.cmd("eventLeave", null);
		if (leave != null && Profiles.inEvent(p.level().dimension().identifier().toString())) {
			AfkHelperClient.sendTyped(leave, null);
			Exchange.emit("follow", "action", "out of the event", "reason", "to afk");
			afkNextTry = now + 3000;
			return false;
		}
		if (fixStill(mc, p, now, afkFail)) return true;
		halt();
		afkFail = null;
		afkTypedAt = now;
		AfkHelperClient.sendTyped(Profiles.cmd("afk", null), null);
		Exchange.emit("follow", "action", "to afk", "reason", afkWhy, "attempt", afkTries + 1);
		return false;
	}

	/**
	 * Whatever keeps the buddy from standing still three seconds (the wait of a teleport, of a /tpa accepted, of /afk), put
	 * right: out of bed, off a seat, the attacker dead, a fight's tag waited out (no teleport goes in a fight),
	 * out of water or lava onto the nearest dry block (a bob in water cancels a teleport), a step aside from what pushes,
	 * the speed run off. True while it is at it, false once standing still is possible.
	 */
	private static boolean fixStill(Minecraft mc, LocalPlayer p, long now, String fail) {
		var o = mc.options;
		if (p.isSleeping()) { p.connection.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.STOP_SLEEPING)); return true; }
		// on a ghast: not off it (in the air that is a fall), only still; a teleport takes the buddy off it by itself
		if (p.getVehicle() instanceof HappyGhast g) { release(); return g.getDeltaMovement().length() > 0.02; }
		if (p.isPassenger()) { HeldKeys.set(o.keyShift, !o.keyShift.isDown()); return true; }
		HeldKeys.set(o.keyShift, false);
		Entity foe = Guard.watch(p, null);
		if (foe != null) { fight(mc, p, foe); return true; }
		if (now < combatUntil) { halt(); return true; }
		if (p.isInWater() || p.isInLava()) { toDry(mc, p); return true; }
		if (p.onGround()) dry = null;   // not at a bob out of the water: that chose the dry block anew each time
		if ("moved".equals(fail) && pushed(p) && stepAside(mc, p)) return true;
		aside = null;
		Vec3 v = p.getDeltaMovement();
		if (!p.onGround() || Math.hypot(v.x, v.z) > 0.01) { halt(); return true; }
		return false;
	}

	/** In water or lava: to the nearest dry block to stand on, within twelve, swimming up when there is none. */
	private static boolean toDry(Minecraft mc, LocalPlayer p) {
		Level level = p.level();
		if (dry == null || level.getBlockState(dry).getFluidState().isEmpty() == false) {
			dry = null;
			BlockPos at = p.blockPosition();
			double best = Double.MAX_VALUE;
			for (int dx = -12; dx <= 12; dx++) for (int dz = -12; dz <= 12; dz++) for (int dy = -4; dy <= 4; dy++) {
				BlockPos c = at.offset(dx, dy, dz);
				if (Double.isNaN(floorY(level, c, false)) || waterCell(level, c) || !level.getBlockState(c.below()).getFluidState().isEmpty()) continue;
				double d = c.distSqr(at);
				if (d < best) { best = d; dry = c.immutable(); }
			}
			if (dry != null) Exchange.emit("follow", "action", "out of the water", "x", dry.getX(), "y", dry.getY(), "z", dry.getZ());
		}
		walking = true;
		if (dry == null) { halt(); hover(mc, p, p.getY() + 3); return true; }
		walkTo(mc, p, Vec3.atBottomCenterOf(dry), true, null);
		return true;
	}

	/** Something living close enough to push the buddy while standing still. */
	private static boolean pushed(LocalPlayer p) {
		return !p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(0.6), e -> e != p).isEmpty();
	}

	/** A few blocks aside, away from what pushes, onto ground to stand on. */
	private static boolean stepAside(Minecraft mc, LocalPlayer p) {
		Level level = p.level();
		if (aside == null) {
			List<LivingEntity> near = level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(0.6), e -> e != p);
			Vec3 away = near.isEmpty() ? new Vec3(1, 0, 0) : p.position().subtract(near.get(0).position()).multiply(1, 0, 1).normalize();
			for (int k = 4; k >= 2 && aside == null; k--) {
				BlockPos c = BlockPos.containing(p.position().add(away.scale(k)));
				for (int dy = 1; dy >= -2 && aside == null; dy--) if (!Double.isNaN(floorY(level, c.above(dy), false))) aside = c.above(dy).immutable();
			}
			if (aside == null) { afkFail = null; return false; }
			Exchange.emit("follow", "action", "steps aside", "x", aside.getX(), "y", aside.getY(), "z", aside.getZ());
		}
		if (Math.hypot(aside.getX() + 0.5 - p.getX(), aside.getZ() + 0.5 - p.getZ()) < 0.4) { aside = null; afkFail = null; halt(); return true; }
		walking = true;
		walkTo(mc, p, Vec3.atBottomCenterOf(aside), true, null);
		return true;
	}

	/** Is the followed player online on this server or the one linked to it, as far as the player list says? */
	private static boolean online(Minecraft mc) {
		return target != null && mc.getConnection() != null && mc.getConnection().getPlayerInfo(target) != null;
	}

	private static boolean portalAt(Level level, BlockPos pos) {
		for (BlockPos q : new BlockPos[] { pos, pos.above(), pos.below() }) {
			BlockState st = level.getBlockState(q);
			if (st.is(Blocks.NETHER_PORTAL) || st.is(Blocks.END_PORTAL) || st.is(Blocks.END_GATEWAY)) return true;
		}
		return false;
	}

	private static Player find(LocalPlayer p) {
		if (target == null) return null;
		for (Player o : p.level().players()) if (o != p && o.getGameProfile().name().equalsIgnoreCase(target)) return o;
		return null;
	}

	private static boolean othersOn() { return Jobs.othersOn(Jobs.Job.FOLLOW); }

	/** Up to air along the water, then afloat at the surface until the bubbles are full again. */
	private static boolean breathe(Minecraft mc, LocalPlayer p) {
		if (!p.isUnderWater()) {
			// Only full bubbles end it. Bobbing at the surface with jump held lifts you clean out of the water for a tick,
			// and that used to count as done: at about half the air the buddy dived again, was out of it within a second and went
			// back up, over and over at the same spot (17 Oct 2026).
			if (p.getAirSupply() >= p.getMaxAirSupply() - 4) { breath = false; clearRoute(); return true; }
			HeldKeys.set(mc.options.keyUp, false);
			HeldKeys.set(mc.options.keySprint, false);
			HeldKeys.set(mc.options.keyShift, false);
			if (p.isInWater()) holdJump(mc);
			return true;
		}
		if (route == null || !airRoute || idx >= route.size()) {
			clearRoute();
			route = planToAir(p.level(), startCell(p));
			airRoute = true;
			if (route == null) {
				// no air within reach of the search: straight up and hope, the way a player would
				HeldKeys.set(mc.options.keyUp, false);
				holdJump(mc);
				MouseTurn.toward(p, p.getYRot(), Math.max(-60f, p.getXRot() - 6f));
				return true;
			}
		}
		walk(mc, p, null);
		return true;
	}

	/**
	 * A door the buddy opened, out of the doorway: turn round and shut it, as it was found. Through it and a step past it;
	 * or not through it, once the way no longer leads through it (a sheep was sheared from the gate, or the sheep gone
	 * for walked off, and the gate stayed open). Whoever else stands in the doorway, a player or an animal, it is shut all
	 * the same; only the buddy has to be out of it, because animals that are waited for get out (until then there was a
	 * wait for players). Too far to click, or no crosshair on it in two seconds: the round in the base fetches it
	 * ({@link #closeLeftOpen}).
	 */
	private static boolean closeBehind(Minecraft mc, LocalPlayer p) {
		if (toClose == null) return false;
		Level level = p.level();
		// far off (a teleport, a respawn) the chunk is not there and the door reads as air: kept in `opened` for the round in
		// the base, not forgotten as shut (the review of 22 Oct 2026)
		if (!level.isLoaded(toClose)) { toClose = null; return false; }
		if (toClose.equals(heldOpen)) return false;
		// after the click to shut it: the server's answer first ({@link #DOOR_ANSWER})
		if (shutClickAt >= 0 && ticks - shutClickAt < DOOR_ANSWER) return true;
		BlockState st = level.getBlockState(toClose);
		Vec3 mid = Vec3.atCenterOf(toClose);
		AABB doorway = new AABB(toClose).expandTowards(0, 1, 0);
		if (!openable(st) || !isOpen(st) || powered(st)) {
			if (shutClickAt >= 0 && openable(st) && !isOpen(st))
				Exchange.emit("follow", "action", "door shut", "x", toClose.getX(), "y", toClose.getY(), "z", toClose.getZ());
			opened.remove(toClose);
			closeFails.remove(toClose);
			toClose = null;
			shutClickAt = -1;
			return false;
		}
		shutClickAt = -1;   // still open after the click: the server put it back, or it was never shut; click again
		if (closeTries > 40) {
			// no crosshair on it in two seconds: a strike, as a walk to it that came to nothing. Without it the round in the
			// base took the door up again at once, with a fresh count, for ever (the review of 22 Oct 2026). A gate with
			// animals by it is never given up: the round in the base keeps coming back to it
			if (closeFails.merge(toClose, 1, Integer::sum) >= 3 && !animalsBy(level, toClose)) {
				opened.remove(toClose);
				closeFails.remove(toClose);
				Exchange.emit("follow", "action", "door left open", "x", toClose.getX(), "y", toClose.getY(), "z", toClose.getZ());
			}
			toClose = null;
			return false;
		}
		if (p.getEyePosition().distanceTo(mid) > 4.2) { toClose = null; return false; }
		if (p.getBoundingBox().intersects(doorway)) { toCloseEntered = true; return false; }
		// out of the doorway is enough: 0.9 from its middle was wanted as well, while a walk to the cell beside it counts as
		// there 0.65 off, and there the buddy stood (the review of 22 Oct 2026)
		double away = Math.hypot(mid.x - p.getX(), mid.z - p.getZ());
		if (!toCloseEntered && (away < 1.5 || onRoute(toClose))) return false;
		walking = true;
		HeldKeys.set(mc.options.keyUp, false);
		HeldKeys.set(mc.options.keySprint, false);
		HeldKeys.set(mc.options.keyShift, false);
		Vec3 pt = doorPoint(level, p, toClose);
		// every point of it behind an animal or a player: wait for one to move, which is no failed try; only a long wait is
		if (pt == null) {
			if (++closeBlocked > ANIMAL_WAIT) { closeBlocked = 0; closeTries = 41; }
			return true;
		}
		closeBlocked = 0;
		closeTries++;
		aimExact(p, pt);
		if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && doorBase(level, hit.getBlockPos()).equals(toClose)) clickDoor = toClose;
		return true;
	}

	/**
	 * Where to click a door or gate: a point on its outline, the lower half first, with nothing in front of it, a block, an
	 * animal or a player ({@link Work#aimPoint}). Null when there is none. It aimed at the middle of the cell first, which
	 * is on a gate but beside an open door, whose outline is a panel along one side: the crosshair never came on a door the buddy
	 * had gone through, and only gates and doors on a pressure plate were shut (the review of 22 Oct 2026). The sheep crowd
	 * at the pen's gate, and a crosshair on the gate's middle took a sheep while three walked out (22 Oct 2026).
	 */
	private static Vec3 doorPoint(Level level, LocalPlayer p, BlockPos door) {
		Vec3 eye = p.getEyePosition();
		Vec3 pt = Work.aimPoint(level, p, eye, door, true);
		if (pt == null && level.getBlockState(door.above()).getBlock() instanceof DoorBlock) pt = Work.aimPoint(level, p, eye, door.above(), true);
		return pt;
	}

	/** The way still leads through this cell (a door's lower half, or the cell above it). */
	private static boolean onRoute(BlockPos c) {
		if (route == null) return false;
		for (int i = idx; i < route.size(); i++) if (route.get(i).equals(c) || route.get(i).equals(c.above())) return true;
		return false;
	}

	/**
	 * Keeping a base: a door or gate the buddy opened and that is still open, within 16 blocks, is walked to and shut from
	 * the buddy's side of it, unless the way leads through it now. Three walks to it that come to nothing: told (`deur blijft open`) and left.
	 */
	private static boolean closeLeftOpen(Minecraft mc, LocalPlayer p) {
		if (opened.isEmpty() || toClose != null || Base.keeper() == null) return false;
		Level level = p.level();
		long now = System.currentTimeMillis();
		BlockPos best = null;
		double bd = 16 * 16;
		for (var it = opened.keySet().iterator(); it.hasNext(); ) {
			BlockPos g = it.next();
			if (!level.isLoaded(g)) continue;
			BlockState st = level.getBlockState(g);
			if (!openable(st) || !isOpen(st) || powered(st)) { it.remove(); closeFails.remove(g); continue; }
			if (g.equals(heldOpen)) continue;
			double d = Vec3.atCenterOf(g).distanceToSqr(p.position());
			if (d < bd && !onRoute(g)) { bd = d; best = g; }
		}
		if (best == null) return false;
		Vec3 mid = Vec3.atCenterOf(best);
		AABB doorway = new AABB(best).expandTowards(0, 1, 0);
		if (p.getEyePosition().distanceTo(mid) <= 3.5 && !p.getBoundingBox().intersects(doorway)) {
			toClose = best; toCloseEntered = true; closeTries = 0; closeBlocked = 0; shutClickAt = -1;
			return closeBehind(mc, p);
		}
		BlockPos stand = beside(level, p, best);
		// the far part of that cell, 1.3 from the door's middle: a walk counts as there within 0.35, and from its middle the
		// buddy could stop with the box still in the doorway, where the hand-over never came (the review of 22 Oct 2026)
		Vec3 goal = stand == null ? null : Vec3.atBottomCenterOf(stand).add((stand.getX() - best.getX()) * 0.3, 0, (stand.getZ() - best.getZ()) * 0.3);
		if (stand == null || !Work.approach(mc, p, "door-" + best.toShortString(), goal, now)) {
			if (closeFails.merge(best, 1, Integer::sum) >= 3 && !animalsBy(level, best)) {
				opened.remove(best);
				closeFails.remove(best);
				Exchange.emit("follow", "action", "door left open", "x", best.getX(), "y", best.getY(), "z", best.getZ());
			}
			Work.walkFresh();
			return false;
		}
		return true;
	}

	/**
	 * A door held open by redstone: a pressure plate stood on, a button, a lever. The redstone shuts it, a click of the
	 * buddy's does not (the house door with a plate inside: on the plate to shut it, the buddy opened it again and called it left open,
	 * 22 Oct 2026, on the test server).
	 */
	private static boolean powered(BlockState st) {
		return st.hasProperty(BlockStateProperties.POWERED) && st.getValue(BlockStateProperties.POWERED);
	}

	/**
	 * A cell in front of or behind this door or gate to stand in, the one nearest to the buddy; null when neither will do. Not on
	 * a pressure plate, which would open it again.
	 */
	private static BlockPos beside(Level level, LocalPlayer p, BlockPos g) {
		BlockPos best = null;
		double bd = Double.MAX_VALUE;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos c = g.relative(d);
			if (Double.isNaN(floorY(level, c, false)) || openable(level.getBlockState(c))
					|| level.getBlockState(c).getBlock() instanceof net.minecraft.world.level.block.BasePressurePlateBlock) continue;
			double dist = p.position().distanceToSqr(Vec3.atBottomCenterOf(c));
			if (dist < bd) { bd = dist; best = c.immutable(); }
		}
		return best;
	}

	// ---------- riding: cushions, boats, minecarts, happy ghasts ----------

	/**
	 * Riding along: while the player sits on a cushion, in a boat or in a minecart, the alt does the same. In the player's
	 * boat when it has room (a boat two seats, a chest boat one), otherwise the free one of the same kind nearest to the
	 * player within eight blocks, walked to and right-clicked as a player does; in a boat of the alt's own, steered after the
	 * player. Out again with a crouch as soon as the player gets out, or is gone. True while this holds the alt's keys. On anything else
	 * the alt rides (a horse, say) it is left alone.
	 * A happy ghast (0.4.40) only when it is the player's, never a
	 * free one: on a ghast of the alt's own the alt would be the one who steers, sit still while the player flies off, and
	 * get off in the air. It is tried only while its underside is within reach of the ground the alt stands on; flying high,
	 * the alt follows below as always. The click goes with an empty hand where it can, because a lead, a name tag, a
	 * harness or a spawn egg in hand is used on the ghast instead of riding it.
	 */
	private static boolean ride(Minecraft mc, LocalPlayer p) {
		long now = System.currentTimeMillis();
		seatRefused.values().removeIf(until -> until < now);
		if (standTicks > 0) {
			HeldKeys.set(mc.options.keyShift, --standTicks > 0);
			return true;
		}
		Player t = find(p);
		Entity his = t == null ? null : t.getVehicle();
		String kind = rideKind(his);
		if (p.isPassenger()) {
			Entity mine = p.getVehicle();
			String myKind = rideKind(mine);
			if (myKind == null) { seat = null; return false; }
			if (mine instanceof HappyGhast g) return onGhast(mc, p, g, t);
			if (kind != null && t.distanceTo(p) <= SEAT_RANGE + 8) {
				if (!seated) {
					seated = true;
					Exchange.emit("follow", "action", "sits", "in", myKind, "x", Math.round(mine.getX()), "y", Math.round(mine.getY()), "z", Math.round(mine.getZ()));
				}
				seat = null;
				seatClickedAt = -1;
				if (mine instanceof AbstractBoat && mine != his && mine.getControllingPassenger() == p) steerBoat(mc, p, mine, t);
				else { release(); lookAt(p, t); }
				return true;
			}
			// the player got out or went away: out as well, the way a player gets off, with a crouch
			seated = false;
			release();
			standTicks = 4;
			HeldKeys.set(mc.options.keyShift, true);
			Exchange.emit("follow", "action", "stands up", "off", myKind);
			return true;
		}
		seated = false;
		if (stepOffGhast(mc, p, t)) return true;
		if (kind == null) { seat = null; seatClickedAt = -1; return false; }
		// the player's ghast up in the air: nothing to reach, follow below as always (and let go of one being walked to)
		if ("ghast".equals(kind) && his.getBoundingBox().minY - p.getY() > GHAST_REACH_UP) {
			if (seat == his) { seat = null; seatClickedAt = -1; }
			return false;
		}
		// a click went out: it seats the buddy within a second, or this one will not take a rider
		if (seatClickedAt >= 0) {
			if (ticks - seatClickedAt <= 20) { HeldKeys.set(mc.options.keyUp, false); HeldKeys.set(mc.options.keySprint, false); return true; }
			// handing the steering back the buddy stands on top of the ghast, which keeps it still meanwhile: click again, not refuse
			if (seat == his && seat instanceof HappyGhast && now < handoverUntil) { seatClickedAt = -1; return true; }
			if (seat != null) {
				seatRefused.put(seat.getId(), now + 60_000);
				Exchange.emit("follow", "action", "cushion refuses", "in", rideKind(seat), "x", Math.round(seat.getX()), "y", Math.round(seat.getY()), "z", Math.round(seat.getZ()));
			}
			seat = null;
			seatClickedAt = -1;
		}
		// the one gone for taken, gone, too far from the player, or not reached in fifteen seconds: another
		if (seat != null && (seat.isRemoved() || !hasRoom(seat) || (seat != his && seat.distanceTo(t) > SEAT_RANGE) || now - seatSince > 15_000)) {
			if (now - seatSince > 15_000 && hasRoom(seat)) seatRefused.put(seat.getId(), now + 60_000);
			seat = null;
		}
		if (seat == null) {
			seat = !"cushion".equals(kind) && hasRoom(his) && !seatRefused.containsKey(his.getId()) ? his
				: "ghast".equals(kind) ? null : freeVehicle(p, t, kind);
			if (seat == null) return false;   // none free: stand by the player as always
			seatSince = now;
			clearRoute();
			Exchange.emit("follow", "action", "sits down", "in", kind, "by_him", seat == his, "x", Math.round(seat.getX()), "y", Math.round(seat.getY()), "z", Math.round(seat.getZ()));
		}
		boolean ghast = seat instanceof HappyGhast;
		// a ghast is four blocks across: measured to its side, aimed a little inside the side nearest
		Vec3 mid = ghast ? nearestInside(seat.getBoundingBox(), p.getEyePosition()) : seat.getBoundingBox().getCenter();
		if (p.getEyePosition().distanceTo(mid) <= 2.6 && (p.onGround() || p.isInWater())) {
			halt();
			HeldKeys.set(mc.options.keyShift, false);
			if (ghast && riskyInHand(p.getMainHandItem())) emptyHand(p);
			aimAt(p, mid);
			if (mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == seat) clickSeat = seat;
			return true;
		}
		walking = true;
		walkTo(mc, p, seat.position(), false, null);
		return true;
	}

	/**
	 * On the player's happy ghast. With the player on it too: seated and looking at the player, and when the alt is the
	 * first one on (the player got off and on again) off it with a crouch, which sets the alt on top, and on again behind
	 * the player, who then steers. Without the player: the alt stays on and flies it low alongside until the player gets on
	 * (see GHAST_SIDE).
	 */
	private static boolean onGhast(Minecraft mc, LocalPlayer p, HappyGhast g, Player t) {
		seat = null;
		seatClickedAt = -1;
		if (t != null && t.getVehicle() == g) {
			if (chauffeur) {
				chauffeur = false;
				release();
				Exchange.emit("follow", "action", "no longer steers", "reason", "the player gets on");
			}
			if (g.getFirstPassenger() == p) {
				release();
				handoverUntil = System.currentTimeMillis() + 10_000;
				seated = false;
				standTicks = 4;
				HeldKeys.set(mc.options.keyShift, true);
				Exchange.emit("follow", "action", "hands over the steering", "x", Math.round(g.getX()), "y", Math.round(g.getY()), "z", Math.round(g.getZ()));
				return true;
			}
			handoverUntil = 0;
			if (!seated) {
				seated = true;
				Exchange.emit("follow", "action", "sits", "in", "ghast", "x", Math.round(g.getX()), "y", Math.round(g.getY()), "z", Math.round(g.getZ()));
			}
			release();
			lookAt(p, t);
			return true;
		}
		if (g.isLeashed()) { landAndLeave(mc, p, g); return true; }
		landing = false;
		if (!chauffeur) {
			chauffeur = true;
			driving = false;
			ghastParkedAt = null;
			ghastBest = Double.MAX_VALUE;
			ghastBestTick = ticks;
			Exchange.emit("follow", "action", "steers the ghast", "visible", t != null, "x", Math.round(g.getX()), "y", Math.round(g.getY()), "z", Math.round(g.getZ()));
		}
		seated = true;
		driveGhast(mc, p, g, t);
		return true;
	}

	/**
	 * Fly the player's ghast to a spot beside the player: its middle GHAST_SIDE away on the side it is on, its underside
	 * GHAST_UNDER above the player's feet. A ghast goes where its rider looks while forward is held, up and down too
	 * (HappyGhast.getRiddenInput), and turns after the rider slowly, so forward only while it roughly faces the spot.
	 * Against a wall or a hill it climbs; when it gets no nearer for three seconds (the player is in a tunnel or under a
	 * roof) it stays where it is until the player has moved on four blocks. Out of sight it hovers where it is.
	 */
	private static void driveGhast(Minecraft mc, LocalPlayer p, HappyGhast g, Player t) {
		var o = mc.options;
		HeldKeys.set(o.keyShift, false);
		HeldKeys.set(o.keyJump, false);
		HeldKeys.set(o.keySprint, false);
		HeldKeys.set(o.keyLeft, false);
		HeldKeys.set(o.keyRight, false);
		HeldKeys.set(o.keyDown, false);
		if (t == null) { HeldKeys.set(o.keyUp, false); driving = false; return; }
		if (ghastParkedAt != null) {
			if (t.position().distanceTo(ghastParkedAt) < 4) { HeldKeys.set(o.keyUp, false); lookAt(p, t); return; }
			ghastParkedAt = null;
			ghastBest = Double.MAX_VALUE;
			ghastBestTick = ticks;
		}
		double ox = g.getX() - t.getX(), oz = g.getZ() - t.getZ(), oh = Math.hypot(ox, oz);
		if (oh < 0.5) { ox = p.getX() - t.getX(); oz = p.getZ() - t.getZ(); oh = Math.hypot(ox, oz); }
		if (oh < 0.5) { ox = 1; oz = 0; oh = 1; }
		Vec3 goal = new Vec3(t.getX() + ox / oh * GHAST_SIDE, t.getY() + GHAST_UNDER, t.getZ() + oz / oh * GHAST_SIDE);
		Vec3 d = goal.subtract(g.position());
		double dist = d.length();
		// there: let go, and set off again once it is a block and a half off
		boolean go = dist > (driving ? 0.6 : 1.5);
		if (go && !driving) { ghastBest = dist; ghastBestTick = ticks; }
		driving = go;
		if (!go) { HeldKeys.set(o.keyUp, false); lookAt(p, t); return; }
		if (dist < ghastBest - 0.3) { ghastBest = dist; ghastBestTick = ticks; }
		else if (ticks - ghastBestTick > 60) {
			ghastParkedAt = t.position();
			driving = false;
			HeldKeys.set(o.keyUp, false);
			Exchange.emit("follow", "action", "ghast waits", "distance", round(dist), "x", Math.round(g.getX()), "y", Math.round(g.getY()), "z", Math.round(g.getZ()));
			return;
		}
		steerGhast(mc, p, g, d);
	}

	/** A ghast the buddy steers, toward a point d away from it: turned after it, up over a wall or a hill in the way. */
	private static void steerGhast(Minecraft mc, LocalPlayer p, HappyGhast g, Vec3 d) {
		var o = mc.options;
		double dh = Math.hypot(d.x, d.z);
		float yaw = dh > 0.3 ? (float) Math.toDegrees(Math.atan2(-d.x, d.z)) : p.getYRot();
		float pitch = (float) -Math.toDegrees(Math.atan2(d.y, dh));
		if (g.horizontalCollision) pitch = Math.min(pitch, -45f);   // a wall or a hill in the way: up over it
		MouseTurn.toward(p, p.getYRot() + Mth.clamp(Mth.wrapDegrees(yaw - p.getYRot()), -12f, 12f),
			p.getXRot() + Mth.clamp(pitch - p.getXRot(), -10f, 10f));
		// straight up or down the way it faces does not matter; sideways it has to face the spot first
		boolean faces = dh < 1.0 || Math.abs(Mth.wrapDegrees(yaw - g.getYRot())) < 35f;
		HeldKeys.set(o.keyUp, faces && Math.abs(pitch - p.getXRot()) < 25f);
	}

	/**
	 * Tied up with a lead while the player is off it: fly it straight down (looking down with forward held is down,
	 * HappyGhast.getRiddenInput) until its underside is just above what is under it, or it gets no lower for two
	 * seconds, then off with a crouch.
	 */
	private static void landAndLeave(Minecraft mc, LocalPlayer p, HappyGhast g) {
		var o = mc.options;
		double ground = groundUnder(p.level(), g.getBoundingBox(), 40);
		double h = g.getY() - ground;
		if (!landing) {
			landing = true;
			chauffeur = false;
			ghastBest = h;
			ghastBestTick = ticks;
			Exchange.emit("follow", "action", "ghast tied up", "height", round(h), "x", Math.round(g.getX()), "y", Math.round(g.getY()), "z", Math.round(g.getZ()));
		}
		if (h < ghastBest - 0.2) { ghastBest = h; ghastBestTick = ticks; }
		if (h > 0.35 && ticks - ghastBestTick <= 40) {
			HeldKeys.set(o.keyShift, false);
			HeldKeys.set(o.keyJump, false);
			HeldKeys.set(o.keySprint, false);
			MouseTurn.toward(p, p.getYRot(), p.getXRot() + Mth.clamp(90f - p.getXRot(), -12f, 12f));
			HeldKeys.set(o.keyUp, p.getXRot() > 70f);
			return;
		}
		release();
		landing = false;
		seated = false;
		standTicks = 4;
		HeldKeys.set(o.keyShift, true);
		Exchange.emit("follow", "action", "gets off", "reason", "lead", "height", round(h), "x", Math.round(g.getX()), "y", Math.round(g.getY()), "z", Math.round(g.getZ()));
	}

	/**
	 * Standing on top of a happy ghast the player does not ride (got off it because it was tied up): walk off the edge
	 * with the shortest drop, toward the player when two are alike. True while the buddy is on it.
	 */
	private static boolean stepOffGhast(Minecraft mc, LocalPlayer p, Player t) {
		if (System.currentTimeMillis() < handoverUntil) return false;
		HappyGhast under = null;
		for (Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(0.4, 0.6, 0.4), e -> e instanceof HappyGhast)) {
			AABB b = e.getBoundingBox();
			if (Math.abs(b.maxY - p.getY()) < 0.6 && p.getX() > b.minX - 0.3 && p.getX() < b.maxX + 0.3 && p.getZ() > b.minZ - 0.3 && p.getZ() < b.maxZ + 0.3) under = (HappyGhast) e;
		}
		if (under == null || (t != null && t.getVehicle() == under)) {
			steppingOff = false;
			return false;
		}
		AABB b = under.getBoundingBox();
		Level level = p.level();
		Vec3 best = null;
		double bestDrop = Double.MAX_VALUE, bestToHim = Double.MAX_VALUE;
		Vec3[] edges = {
			new Vec3(b.minX - 0.8, p.getY(), p.getZ()), new Vec3(b.maxX + 0.8, p.getY(), p.getZ()),
			new Vec3(p.getX(), p.getY(), b.minZ - 0.8), new Vec3(p.getX(), p.getY(), b.maxZ + 0.8) };
		for (Vec3 e : edges) {
			double drop = p.getY() - groundUnder(level, new AABB(e.x - 0.3, e.y, e.z - 0.3, e.x + 0.3, e.y + 1.8, e.z + 0.3), 30);
			double toHim = t == null ? 0 : e.distanceTo(t.position());
			if (drop < bestDrop - 0.5 || (Math.abs(drop - bestDrop) <= 0.5 && toHim < bestToHim)) { best = e; bestDrop = drop; bestToHim = toHim; }
		}
		if (!steppingOff) {
			steppingOff = true;
			Exchange.emit("follow", "action", "off the ghast", "fall", round(bestDrop));
		}
		var o = mc.options;
		HeldKeys.set(o.keyShift, false);
		HeldKeys.set(o.keySprint, false);
		HeldKeys.set(o.keyJump, false);
		float yaw = (float) Math.toDegrees(Math.atan2(-(best.x - p.getX()), best.z - p.getZ()));
		float delta = Mth.wrapDegrees(yaw - p.getYRot());
		MouseTurn.toward(p, p.getYRot() + Mth.clamp(delta, -20f, 20f), p.getXRot() + Mth.clamp(20f - p.getXRot(), -8f, 8f));
		HeldKeys.set(o.keyUp, Math.abs(delta) < 30f);
		return true;
	}

	/** The top of the highest thing under a box (a block that stops a fall, or water), within this many blocks; far below when none. */
	private static double groundUnder(Level level, AABB box, int depth) {
		double best = box.minY - depth;
		double[] xs = { box.minX + 0.3, (box.minX + box.maxX) / 2, box.maxX - 0.3 };
		double[] zs = { box.minZ + 0.3, (box.minZ + box.maxZ) / 2, box.maxZ - 0.3 };
		for (double x : xs) for (double z : zs) {
			for (int y = Mth.floor(box.minY); y > box.minY - depth; y--) {
				BlockPos pos = BlockPos.containing(x, y, z);
				var st = level.getBlockState(pos);
				var shape = st.getCollisionShape(level, pos);
				double top = !shape.isEmpty() ? y + shape.max(net.minecraft.core.Direction.Axis.Y) : !st.getFluidState().isEmpty() ? y + 0.9 : Double.NaN;
				if (!Double.isNaN(top) && top <= box.minY + 0.01) { if (top > best) best = top; break; }
			}
		}
		return best;
	}

	/** In a boat of the buddy's own: turn the boat toward the player with left and right, forward while it roughly faces the player, not near yet. */
	private static void steerBoat(Minecraft mc, LocalPlayer p, Entity boat, Player t) {
		steerBoat(mc, p, boat, t.position(), 4);
	}

	/** A boat the buddy steers, toward a point: turned with left and right, forward while it roughly faces it and is farther than {@code near}. */
	private static void steerBoat(Minecraft mc, LocalPlayer p, Entity boat, Vec3 at, double near) {
		var o = mc.options;
		double dx = at.x - boat.getX(), dz = at.z - boat.getZ(), d = Math.hypot(dx, dz);
		float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float diff = Mth.wrapDegrees(want - boat.getYRot());
		HeldKeys.set(o.keyLeft, diff < -6f);
		HeldKeys.set(o.keyRight, diff > 6f);
		HeldKeys.set(o.keyUp, d > near && Math.abs(diff) < 70f);
		HeldKeys.set(o.keySprint, false);
		MouseTurn.toward(p, p.getYRot() + Mth.clamp(Mth.wrapDegrees(want - p.getYRot()), -10f, 10f), p.getXRot() + Mth.clamp(10f - p.getXRot(), -5f, 5f));
	}

	/**
	 * A walk to a spot while seated: the ride goes on as long as it gets the buddy nearer, steered where the buddy can
	 * steer it, and is left with a crouch once it no longer helps; the rest on foot. A cushion never helps, nor a ride
	 * that stands still and that the buddy does not steer. A boat the buddy steers takes the way over water to the water
	 * nearest the spot and is left there, at the shore. A saddled horse, donkey, mule, camel or nautilus, a pig or a
	 * strider with its stick in hand (taken from the hotbar), and a happy ghast in a harness are steered straight at the
	 * spot, and left before a drop or lava. A minecart goes where its rails go, given a push toward the spot while it
	 * stands. Never off high above the ground: a ghast the buddy steers goes down first, a ride someone else steers is
	 * waited out. True while seated.
	 */
	private static boolean rideTo(Minecraft mc, LocalPlayer p, BlockPos s) {
		var o = mc.options;
		Level level = p.level();
		Entity v = p.getVehicle();
		if (v != rideOn || !s.equals(rideSpot)) {
			rideOn = v; rideSpot = s; boatWay = null; boatIdx = 0; rideTold = false; rideWaits = false; rideLanding = false;
			rideBest = Double.MAX_VALUE; rideBestTick = ticks;
		}
		walking = false;
		seated = true;
		for (var k : new net.minecraft.client.KeyMapping[] { o.keyShift, o.keyJump, o.keySprint, o.keyDown, o.keyLeft, o.keyRight })
			HeldKeys.set(k, false);
		Vec3 goal = Vec3.atBottomCenterOf(s);
		double hd = Math.hypot(goal.x - v.getX(), goal.z - v.getZ());
		boolean steers = steersRide(p, v);
		boolean boat = v instanceof AbstractBoat, cart = v instanceof AbstractMinecart, ghast = v instanceof HappyGhast;
		Vec3 aim = ghast ? goal.add(0, 1, 0) : goal;
		double left = ghast ? aim.distanceTo(v.position()) : hd;
		String why = null;
		if (rideLanding) why = "landing";
		else if (isCushion(v)) why = "cushion";
		else if (hd < RIDE_NEAR) why = "there";
		else if (!steers && !cart && v.getDeltaMovement().horizontalDistance() < 0.02) why = "cannot steer";
		else if (boat && steers) {
			if (boatWay == null || v.position().distanceTo(Vec3.atBottomCenterOf(boatWay.get(boatIdx))) > 3) {
				boatWay = boatWay(level, v, goal);
				boatIdx = 0;
				rideBest = Double.MAX_VALUE; rideBestTick = ticks;
			}
			if (boatWay == null) why = "no water that way";
			else {
				for (int i = boatIdx + 1, n = Math.min(boatWay.size(), boatIdx + 12); i < n; i++)
					if (flatDist(v, boatWay.get(i)) < flatDist(v, boatWay.get(boatIdx))) boatIdx = i;
				left = boatWay.size() - 1 - boatIdx;
				if (left <= 1) why = "shore";
				else aim = Vec3.atBottomCenterOf(boatWay.get(Math.min(boatIdx + 3, boatWay.size() - 1)));
			}
		}
		if (why == null) {
			if (left < rideBest - 1) { rideBest = left; rideBestTick = ticks; }
			else if (ticks - rideBestTick > (boat ? 40 : 60)) why = "no nearer";
		}
		if (why == null && steers && !boat && !ghast && dropAhead(level, v)) why = "drop ahead";
		if (why == null) {
			if (!rideTold) {
				rideTold = true;
				Exchange.emit("goto", "action", "rides", "in", vehicleKind(v), "steers", steers, "distance", round(hd));
			}
			if (boat && steers) steerBoat(mc, p, v, aim, 0);
			else if (ghast && steers) steerGhast(mc, p, (HappyGhast) v, aim.subtract(v.position()));
			else if (steers) steerMount(mc, p, v, aim);
			else {
				// a ride someone else steers, or a minecart: a push toward the spot while it stands still
				// (NewMinecartBehavior.calculatePlayerInputSpeed takes the rider's forward only then)
				float yaw = (float) Math.toDegrees(Math.atan2(-(aim.x - v.getX()), aim.z - v.getZ()));
				MouseTurn.toward(p, p.getYRot() + Mth.clamp(Mth.wrapDegrees(yaw - p.getYRot()), -10f, 10f), p.getXRot());
				HeldKeys.set(o.keyUp, cart && v.getDeltaMovement().horizontalDistance() < 0.1);
			}
			return true;
		}
		HeldKeys.set(o.keyUp, false);
		double high = v.getY() - groundUnder(level, v.getBoundingBox(), 40);
		if (ghast && steers) {
			// down first, looking down with forward held (HappyGhast.getRiddenInput), until just above the ground or no lower
			// for two seconds: getting off puts the rider on top of it
			if (!rideLanding) {
				rideLanding = true; rideBest = high; rideBestTick = ticks;
				Exchange.emit("goto", "action", "lands", "reason", why, "height", round(high));
			}
			if (high < rideBest - 0.2) { rideBest = high; rideBestTick = ticks; }
			if (high > 0.5 && ticks - rideBestTick <= 40) {
				MouseTurn.toward(p, p.getYRot(), p.getXRot() + Mth.clamp(90f - p.getXRot(), -12f, 12f));
				HeldKeys.set(o.keyUp, p.getXRot() > 70f);
				return true;
			}
		} else if (high > 3) {
			// high up on a ride someone else steers: off would be a fall, so wait
			if (!rideWaits) { rideWaits = true; Exchange.emit("goto", "action", "waits", "reason", "high up", "in", vehicleKind(v), "height", round(high)); }
			return true;
		}
		release();
		clearRoute();
		seated = false;
		standTicks = 4;
		HeldKeys.set(o.keyShift, true);
		Exchange.emit("goto", "action", "gets off", "off", vehicleKind(v), "reason", why, "distance", round(hd),
			"x", Math.round(v.getX()), "y", Math.round(v.getY()), "z", Math.round(v.getZ()));
		rideOn = null;
		return true;
	}

	/**
	 * Whether the buddy steers this ride: the first one in a boat, on a saddled horse, donkey, mule, camel or nautilus,
	 * or on a happy ghast in a harness; on a pig or a strider with its stick in hand, taken from the hotbar when there.
	 */
	private static boolean steersRide(LocalPlayer p, Entity v) {
		if (v.getControllingPassenger() == p) return true;
		if ((v instanceof Pig || v instanceof Strider) && v.getFirstPassenger() == p) {
			var stick = v instanceof Pig ? Items.CARROT_ON_A_STICK : Items.WARPED_FUNGUS_ON_A_STICK;
			var inv = p.getInventory();
			for (int i = 0; i < 9; i++) if (inv.getItem(i).is(stick)) {
				if (inv.getSelectedSlot() != i) inv.setSelectedSlot(i);
				return true;
			}
		}
		return false;
	}

	/** A mount the buddy steers, toward a point: turned after it with forward held; a pig or a strider goes where its rider looks by itself, a nautilus up and down too. */
	private static void steerMount(Minecraft mc, LocalPlayer p, Entity v, Vec3 at) {
		double dx = at.x - v.getX(), dz = at.z - v.getZ(), dh = Math.hypot(dx, dz);
		float diff = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz)) - p.getYRot());
		float pitch = v instanceof AbstractNautilus && v.isInWater() ? (float) -Math.toDegrees(Math.atan2(at.y + 0.5 - v.getY(), dh)) : 10f;
		MouseTurn.toward(p, p.getYRot() + Mth.clamp(diff, -15f, 15f), p.getXRot() + Mth.clamp(pitch - p.getXRot(), -8f, 8f));
		HeldKeys.set(mc.options.keyUp, Math.abs(diff) < 40f && !(v instanceof Pig || v instanceof Strider));
	}

	/** A drop of more than three blocks, or lava or fire, just ahead of a mount the buddy steers (a strider walks on lava). */
	private static boolean dropAhead(Level level, Entity v) {
		if (v.isInWater()) return false;
		double far = Math.max(1.5, v.getDeltaMovement().horizontalDistance() * 6), yaw = Math.toRadians(v.getYRot());
		AABB b = v.getBoundingBox().move(-Math.sin(yaw) * far, 0, Math.cos(yaw) * far);
		if (v.getY() - groundUnder(level, b, 8) > 3) return true;
		BlockPos c = BlockPos.containing(b.getCenter().x, b.minY, b.getCenter().z);
		for (BlockPos q : new BlockPos[] { c, c.below() }) {
			BlockState st = level.getBlockState(q);
			if (st.is(BlockTags.FIRE) || (!(v instanceof Strider) && st.getFluidState().is(FluidTags.LAVA))) return true;
		}
		return false;
	}

	/**
	 * The way a boat goes from where it is, over water open to the air or over ice, to the cell of it nearest the spot
	 * (within 96 blocks, 6000 cells): the cells from the boat's own on, or null when that cell is not three blocks nearer
	 * the spot than the boat is.
	 */
	private static List<BlockPos> boatWay(Level level, Entity boat, Vec3 goal) {
		BlockPos at = BlockPos.containing(boat.position());
		BlockPos start = boatCell(level, at) ? at : boatCell(level, at.below()) ? at.below() : boatCell(level, at.above()) ? at.above() : null;
		if (start == null) return null;
		Map<BlockPos, BlockPos> from = new HashMap<>();
		ArrayDeque<BlockPos> todo = new ArrayDeque<>();
		from.put(start, start);
		todo.add(start);
		BlockPos best = start;
		double bestD = Double.MAX_VALUE;
		while (!todo.isEmpty() && from.size() < 6000) {
			BlockPos c = todo.poll();
			double d = Math.hypot(goal.x - c.getX() - 0.5, goal.z - c.getZ() - 0.5) + Math.abs(goal.y - c.getY()) * 0.5;
			if (d < bestD) { bestD = d; best = c; }
			for (Direction dir : Direction.Plane.HORIZONTAL) {
				BlockPos n = c.relative(dir);
				if (Math.abs(n.getX() - start.getX()) > 96 || Math.abs(n.getZ() - start.getZ()) > 96) continue;
				// along the water, or down a step where it flows down
				if (!boatCell(level, n)) {
					if (!level.getBlockState(n).getCollisionShape(level, n).isEmpty() || !boatCell(level, n.below())) continue;
					n = n.below();
				}
				if (from.putIfAbsent(n, c) == null) todo.add(n);
			}
		}
		Vec3 b = boat.position();
		if (Math.hypot(goal.x - b.x, goal.z - b.z) - Math.hypot(goal.x - best.getX() - 0.5, goal.z - best.getZ() - 0.5) < 3) return null;
		List<BlockPos> way = new ArrayList<>();
		for (BlockPos c = best; ; c = from.get(c)) {
			way.add(c);
			if (c.equals(start)) break;
		}
		Collections.reverse(way);
		return way;
	}

	/** A cell a boat goes through: water with open air above it (a lily pad breaks), or the air over ice. */
	private static boolean boatCell(Level level, BlockPos c) {
		BlockState here = level.getBlockState(c);
		if (!here.getCollisionShape(level, c).isEmpty()) return false;
		if (here.getFluidState().is(FluidTags.WATER)) {
			BlockPos up = c.above();
			BlockState above = level.getBlockState(up);
			return above.getFluidState().isEmpty() && (above.getCollisionShape(level, up).isEmpty() || above.is(Blocks.LILY_PAD));
		}
		return here.getFluidState().isEmpty() && level.getBlockState(c.below()).is(BlockTags.ICE);
	}

	/** How far a cell's middle is from an entity, across. */
	private static double flatDist(Entity e, BlockPos c) {
		return Math.hypot(c.getX() + 0.5 - e.getX(), c.getZ() + 0.5 - e.getZ());
	}

	/** What a ride is called in the events: as rideKind, else its entity type (a horse, a pig). */
	private static String vehicleKind(Entity v) {
		String k = rideKind(v);
		return k != null ? k : EntityType.getKey(v.getType()).getPath();
	}

	/**
	 * What the buddy can ride along on: a cushion, a boat (a raft too), a minecart that seats one, a grown happy ghast in a
	 * harness (without one a click does not seat anyone); null for anything else.
	 */
	private static String rideKind(Entity e) {
		if (e == null) return null;
		if (isCushion(e)) return "cushion";
		if (e instanceof AbstractBoat) return "boat";
		if (e instanceof Minecart) return "minecart";
		if (e instanceof HappyGhast g && !g.isBaby() && g.isWearingBodyArmor()) return "ghast";
		return null;
	}

	/** Room for one more: a happy ghast seats four, a boat two, a chest boat, a cushion and a minecart one. */
	private static boolean hasRoom(Entity e) {
		if (e == null || e.isRemoved()) return false;
		int seats = e instanceof HappyGhast ? 4 : e instanceof AbstractBoat && !(e instanceof AbstractChestBoat) ? 2 : 1;
		return e.getPassengers().size() < seats;
	}

	/** The point of a box nearest to the eye, kept 0.3 inside it, so that a ray to it hits the box. */
	private static Vec3 nearestInside(AABB box, Vec3 eye) {
		AABB in = box.deflate(0.3);
		return new Vec3(Mth.clamp(eye.x, in.minX, in.maxX), Mth.clamp(eye.y, in.minY, in.maxY), Mth.clamp(eye.z, in.minZ, in.maxZ));
	}

	/** What a click on a happy ghast uses on it instead of riding it (HappyGhast.mobInteract and Mob.interact). */
	static boolean riskyInHand(ItemStack s) {
		return !s.isEmpty() && (s.is(Items.LEAD) || s.is(Items.NAME_TAG) || s.is(ItemTags.HARNESSES) || s.is(Items.SHEARS)
			|| s.getItem() instanceof SpawnEggItem);
	}

	/** An empty hotbar slot in hand, else one with nothing risky in it; stays as it is when every slot is risky. */
	private static void emptyHand(LocalPlayer p) {
		var inv = p.getInventory();
		int pick = -1;
		for (int i = 0; i < 9 && pick < 0; i++) if (inv.getItem(i).isEmpty()) pick = i;
		for (int i = 0; i < 9 && pick < 0; i++) if (!riskyInHand(inv.getItem(i))) pick = i;
		if (pick >= 0 && inv.getSelectedSlot() != pick) inv.setSelectedSlot(pick);
	}

	/** The free one of this kind nearest to the player, within eight blocks of the player. */
	private static Entity freeVehicle(LocalPlayer p, Player t, String kind) {
		Entity best = null;
		double bestD = Double.MAX_VALUE;
		for (Entity e : p.level().getEntities((Entity) null, t.getBoundingBox().inflate(SEAT_RANGE), e -> kind.equals(rideKind(e)))) {
			if (!e.getPassengers().isEmpty() || e.isRemoved() || seatRefused.containsKey(e.getId())) continue;
			double d = e.distanceTo(t);
			if (d <= SEAT_RANGE && d < bestD) { bestD = d; best = e; }
		}
		return best;
	}

	/** A cushion of 26.3, known by its entity type: the class exists in no earlier version. */
	private static boolean isCushion(Entity e) {
		return e != null && "cushion".equals(EntityType.getKey(e.getType()).getPath());
	}

	// ---------- walking ----------

	private static void walkTo(Minecraft mc, LocalPlayer p, Vec3 goal, boolean exact, Player t) {
		Level level = p.level();
		BlockPos goalCell = goalCell(level, BlockPos.containing(goal), exact);
		boolean due = route == null || airRoute || idx >= route.size() || exact != exactGoal
			|| Math.sqrt(routeGoal.distSqr(goalCell)) > REPLAN_MOVED;
		if (due && ticks >= nextPlan) {
			nextPlan = ticks + 10;
			clearRoute();
			canSprint = p.getFoodData().getFoodLevel() > 6;
			leapsOk = mayLeap(p);
			// keeping a base there is no hurry: a jump that can go wrong only where a miss costs nothing (the buddy fell and took
			// fall damage on the way to some wool); following, up to two hearts
			missFall = Base.keeper() != null ? 3 : Miss.maxFall(p.getHealth());
			voidOk = Profiles.voidAllowed(level.dimension().identifier().toString());
			PathShape.prepare(p);
			route = plan(level, startCell(p), goalCell, exact);
			routeGoal = goalCell; exactGoal = exact;
			if (route == null) {
				// nothing to walk to from here (walled in, or the only way is to dig): wait and look again
				nextPlan = ticks + 40;
				if (!noPathTold) { noPathTold = true; Exchange.emit("follow", "action", "no path", "player", target == null ? "" : target, "x", p.getBlockX(), "y", p.getBlockY(), "z", p.getBlockZ(), "gx", goalCell.getX(), "gy", goalCell.getY(), "gz", goalCell.getZ(), "cells", lastNodes, "jumps", lastLooks, "ms", lastMs,
					"from", startCell(p).toShortString(), "foot", round3(feet(level, startCell(p), goalCell, exact)), "doing", Arbiter.doing()); }
			} else noPathTold = false;
		}
		if (route == null || airRoute || idx >= route.size()) {
			halt();
			hover(mc, p, p.getY() + 1);
			// waiting for a plan on the pen's walk round it: still crouched, as on the walk there (Pen.crouchWalk)
			if (!p.isInWater() && Pen.crouchWalk(p)) HeldKeys.set(mc.options.keyShift, true);
			return;
		}
		walk(mc, p, t);
	}

	/**
	 * Follow the planned cells: on land with the forward key and a jump where it goes one up; on a ladder with jump
	 * held to climb and nothing held to slide down; in water with jump to rise and crouch to sink, sprinting (which is
	 * swimming) under the surface on a longer stretch. A closed door on the way is opened first.
	 */
	private static void walk(Minecraft mc, LocalPlayer p, Player t) {
		Level level = p.level();
		byte shape = move(idx);
		if (shape == DIG || shape == PILLAR || shape == BRIDGE) {
			BlockPos from = idx > 0 ? route.get(idx - 1) : startCell(p);
			if (PathShape.tick(mc, p, route.get(idx), from, shape)) { walking = true; return; }
			if (idx < routeMoves.size()) routeMoves.set(idx, WALK);
		}
		if (move(idx) != WALK) {
			// burning, in lava or low since the plan was made: no new jump (one under way is finished), plan again
			if ((leap == null || !leap.to.equals(route.get(idx))) && !mayLeap(p)) {
				Exchange.emit("follow", "action", "no jump", "reason", p.isOnFire() ? "burning" : p.isInLava() ? "lava" : "low hp",
					"hp", round(p.getHealth()), "x", p.getBlockX(), "y", p.getBlockY(), "z", p.getBlockZ());
				leap = null;
				release();
				clearRoute();
				return;
			}
			leapTick(mc, p, route.get(idx), move(idx));
			return;
		}
		leap = null;
		// cut corners over plain ground at one height, as long as the straight line stays on it (no jump on the way)
		for (int j = Math.min(route.size() - 1, idx + 8); j > idx; j--) {
			if (!walksOnly(idx, j)) continue;
			if (plainLevel(level, idx, j) && clearLine(level, p.position(), route.get(j))) { idx = j; idxSince = 0; cellsWalked++; break; }
			if (allWater(level, idx, j) && clearWater(level, p.position(), route.get(j))) { idx = j; idxSince = 0; cellsWalked++; break; }
		}
		BlockPos wp = route.get(idx);
		double wf = feet(level, wp, routeGoal, exactGoal);
		if (Double.isNaN(wf)) { clearRoute(); return; }   // the world changed under the plan
		long now = System.currentTimeMillis();
		if (idxSince == 0) { idxSince = now; idxDist = p.position().distanceTo(Vec3.atBottomCenterOf(wp)); }
		// a cell out of reach (something above the head, a step too high) set the buddy jumping and circling: another way. The
		// time it may take goes by how far it was, slower in water.
		long limit = 2500 + (long) (idxDist * (p.isInWater() ? 2000 : 1000));
		if (now - idxSince > limit) { stuck(wp, "too long"); return; }

		if (door(mc, p, wp)) return;

		Vec3 m = new Vec3(wp.getX() + 0.5, p.getY(), wp.getZ() + 0.5);
		double d = Math.hypot(m.x - p.getX(), m.z - p.getZ());
		double rise = wf - p.getY();
		boolean last = idx == route.size() - 1;
		boolean wpWater = waterCell(level, wp), wpLadder = ladderCell(level, wp);
		boolean inWater = p.isInWater(), climbing = p.onClimbable();
		boolean deep = wpWater && !surface(level, wp);
		// Down over an edge: off it at a walk and kept over the cell in the air, and the cell reached only on its floor.
		// At walking speed the buddy came down a block and a half past the edge, over the next step of a cave, and fell five and
		// nine blocks in all (18 Oct 2026).
		boolean drop = !inWater && !climbing && !wpWater && !wpLadder && rise < -0.6;

		// Under the surface always swim (sprint in water): lying down the body is 0.6 high and goes through a gap of one,
		// the way a player goes through an underwater cave. Upright it is 1.8 high, and a passage of two only takes it
		// with the feet at its bottom: floating half a block higher the buddy hit its edge and stuck there (17 Oct 2026).
		boolean food = p.getFoodData().getFoodLevel() > 6;
		boolean swim = inWater && p.isUnderWater() && deep && food;
		// up and down
		boolean sneak = false;
		if (inWater) {
			if (deep) {
				if (swim) {
					// swimming goes where you look; jump and crouch only help on a steep bit
					if (rise > 0.6) holdJump(mc);
					else if (rise < -0.6) sneak = true;
				} else {
					// upright: keep the feet at the bottom of the cell, so the head clears a low passage
					if (p.getY() < wf - 0.05) holdJump(mc);
					else if (p.getY() > wf + 0.15) sneak = true;
				}
			} else holdJump(mc);   // to the surface, or out onto the bank
		} else if (climbing || wpLadder) {
			if (rise > 0.3) holdJump(mc);   // jump held on a ladder climbs; letting go slides down
		} else if (rise > 0.6 && d < 1.6 && p.onGround() && jumpTicks == 0) {
			HeldKeys.set(mc.options.keyJump, true); jumpTicks = 3;
		}
		// beside a pen being farmed (or farmed a moment ago) crouched: the edge keeps a crouched player on the block; on the
		// pen's own walk round it (Pen.crouchWalk) the whole way, also through a corner beside none of its columns, where
		// the buddy let go of crouch at every corner of a small pen (the third review of 22 Oct 2026); never for a drop, in
		// water or on a ladder, nor while getting away from a creeper (Pen.gettingAway: at a sneak's pace a creeper in the
		// pit kept the buddy in its sight and went off, the test of 22 Oct 2026)
		if (!inWater && !drop && !Pen.gettingAway() && (Pen.fenceBeside(wp) || Pen.fenceBeside(p.blockPosition())
				|| (!climbing && !wpLadder && Pen.crouchWalk(p)))) sneak = true;
		HeldKeys.set(mc.options.keyShift, sneak);

		// Past this cell already, on the way to the next: on. Swimming carries you on, and the buddy used to turn round for a
		// cell shot past by half a block (17 Oct 2026); after a new plan the first cell can lie just behind.
		// Only where the straight way to the next cell is free: from half a block off the middle a turn round a corner
		// cut across the cell beside it, and a bamboo stalk standing off-centre there caught the buddy (17 Oct 2026).
		BlockPos next = last || move(idx + 1) != WALK ? null : route.get(idx + 1);
		boolean straight = next != null && (wpWater ? clearWater(level, p.position(), next) : clearLine(level, p.position(), next));
		if (straight && !wpLadder && !climbing && !drop && (wpWater || rise <= 0.6)) {
			Vec3 a = Vec3.atBottomCenterOf(wp), b = Vec3.atBottomCenterOf(next), me = p.position();
			Vec3 ab = b.subtract(a);
			if (me.distanceTo(a) < 1.8 && me.subtract(a).dot(ab) > 0.25 * ab.lengthSqr()) { idx++; idxSince = 0; cellsWalked++; return; }
		}
		// the cell a jump starts from: in it is enough, the run toward the landing starts from wherever the buddy is (the
		// middle of a checkpoint's edge cell was circled for a second before the jump, 17 Oct 2026)
		boolean beforeJump = !last && move(idx + 1) != WALK && !wpWater && !wpLadder && !climbing && p.onGround() && Math.abs(rise) < 0.3;
		boolean arrived = wpWater ? d < (straight || last ? 0.6 : 0.3) && Math.abs(rise) < 0.9
			: (wpLadder || climbing) ? d < 0.5 && Math.abs(rise) < 0.45
			: beforeJump ? d < 0.75
			: !drop && d < (last ? 0.35 : straight ? 0.5 : 0.3) && (rise <= 0.6 || p.getY() >= wf - 0.1);
		if (arrived) { idx++; idxSince = 0; cellsWalked++; return; }

		// sprinting: on land only to catch up on a straight bit; under water it is swimming
		// to a spot (the parkour course) also on a short straight bit, when the cell after this one lies on along the way:
		// walking the two cells of a platform between jumps lost the run every time (17 Oct 2026)
		boolean onward = false;
		if ((hurry || (t != null && t.distanceTo(p) > 7)) && !last && idx + 1 < route.size()) {
			BlockPos n1 = route.get(idx + 1);
			double ax = wp.getX() + 0.5 - p.getX(), az = wp.getZ() + 0.5 - p.getZ(), bx = n1.getX() - wp.getX(), bz = n1.getZ() - wp.getZ();
			double la = Math.hypot(ax, az), lb = Math.hypot(bx, bz);
			onward = la > 0.05 && lb > 0 && (ax * bx + az * bz) / (la * lb) > 0.7;
		}
		boolean sprint = swim || ((hurry || (t != null && t.distanceTo(p) > 7)) && (d > 2.5 || onward) && !inWater && !climbing && !drop && food
			&& !Pen.near(p, Pen.NO_SPRINT));
		float pitch;
		if (swim) {
			// swimming goes where you look: up or down toward the cell, like a player diving after you
			Vec3 body = p.position().add(0, 0.3, 0), to = new Vec3(wp.getX() + 0.5, wp.getY() + 0.3, wp.getZ() + 0.5);
			pitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(to.y - body.y, Math.hypot(to.x - body.x, to.z - body.z))), -70f, 70f);
		} else pitch = 10f;
		// on a ladder straight above or below there is nowhere to walk to: only keep in the cell
		boolean push = !(climbing && d < 0.25 && Math.abs(rise) > 0.3);
		// Going up a ladder: pushed against the wall it hangs on, which climbs as fast and keeps the climber in it. Left
		// alone the speed of coming in carried the buddy off its side twice in one climb (17 Oct 2026).
		if (climbing && rise > 0.3) {
			BlockState ls = level.getBlockState(p.blockPosition());
			if (ls.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
				Direction back = ls.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
				m = new Vec3(p.getBlockX() + 0.5 + back.getStepX() * 0.45, p.getY(), p.getBlockZ() + 0.5 + back.getStepZ() * 0.45);
				d = Math.hypot(m.x - p.getX(), m.z - p.getZ());
				push = true;
			}
		}
		// to the edge of a drop no faster than about half a walk: a tick without the key now and then
		if (drop && p.onGround() && d < 1.3 && Math.hypot(p.getX() - p.xo, p.getZ() - p.zo) > 0.13) push = false;
		strafeOk = !drop && !wpLadder && !wpWater;
		steer(mc, p, m, d, sprint, pitch, push);
		strafeOk = true;
		boolean back = false;
		if (drop && !p.onGround()) {
			// in the air over it: no more speed once over the middle, and back toward it when past
			float want = (float) Math.toDegrees(Math.atan2(-(m.x - p.getX()), m.z - p.getZ()));
			float off = Math.abs(Mth.wrapDegrees(want - p.getYRot()));
			if (d < 0.35 || off > 60) HeldKeys.set(mc.options.keyUp, false);
			back = d > 0.15 && off > 120;
		}
		HeldKeys.set(mc.options.keyDown, back);
		// only with the walk key down: turning round on the spot at a sharp corner is no being stuck
		if (previous != null && mc.options.keyUp.isDown() && p.position().distanceToSqr(previous) < 0.0004) {
			if (++still > 20) { still = 0; stuck(wp, "standing still"); return; }
		} else still = 0;
		previous = p.position();
	}

	/** This cell is not to be reached: back off a step, leave it out of the plans for a while and plan anew. */
	private static void stuck(BlockPos wp, String why) {
		avoid.put(wp.immutable(), System.currentTimeMillis() + 20_000);
		release();
		// on the pen's walk round it the crouch stays down through the step back (the tick of it too)
		LocalPlayer p = AfkHelperClient.mc().player;
		if (p != null && !p.isInWater() && Pen.crouchWalk(p)) HeldKeys.set(AfkHelperClient.mc().options.keyShift, true);
		backTicks = 6;
		clearRoute();
		Exchange.emit("follow", "action", "stuck", "reason", why, "x", wp.getX(), "y", wp.getY(), "z", wp.getZ());
	}

	/**
	 * A closed door or gate in the next cell: walk up to it, stop, get the crosshair on it and click it open at the
	 * start of the next tick. True while the door holds the buddy up.
	 */
	private static boolean door(Minecraft mc, LocalPlayer p, BlockPos wp) {
		Level level = p.level();
		BlockPos base = null;
		for (BlockPos c : new BlockPos[] { wp, wp.above() }) {
			BlockState st = level.getBlockState(c);
			if (openable(st) && !isOpen(st)) { base = doorBase(level, c); break; }
		}
		if (base == null) return false;
		doorTick = ticks;
		if (base.equals(opening)) { HeldKeys.set(mc.options.keyUp, false); HeldKeys.set(mc.options.keySprint, false); return true; }
		Vec3 mid = Vec3.atCenterOf(base);
		double flat = Math.hypot(mid.x - p.getX(), mid.z - p.getZ());
		if (flat > 2.2) return false;   // on the way there: walk on, the door is not in reach yet
		HeldKeys.set(mc.options.keyUp, false);
		HeldKeys.set(mc.options.keySprint, false);
		HeldKeys.set(mc.options.keyShift, false);   // crouched, a click with a block in hand places it against the door
		aimAt(p, mid.add(0, 0.1, 0));
		// animals right behind it (the sheep of a pen): not opened until they have moved, or they walk out past the buddy while
		// pushing through; after a while the route is dropped and planned anew, the door still shut, and a chore gives up its
		// goal by its own clock. No wall in `refused`: the first version made it one for twenty seconds, and a herding round
		// that came within them could not get its stray in, and failed again at once the next time (23 Oct 2026). Not while
		// leading an animal into its pen, which waits behind the gate on purpose.
		if (!Herd.active() && animalsBehind(level, p, base)) {
			if (!base.equals(doorWait)) {
				doorWait = base; doorWaitSince = ticks;
				Exchange.emit("follow", "action", "door waits for animals", "x", base.getX(), "y", base.getY(), "z", base.getZ());
			}
			if (ticks - doorWaitSince > ANIMAL_WAIT) {
				Exchange.emit("follow", "action", "door not opened", "reason", "animals behind it", "x", base.getX(), "y", base.getY(), "z", base.getZ());
				doorWait = null;
				clearRoute();
				return true;   // the route is gone: walk() must not go on with it this tick
			}
			return true;
		}
		doorWait = null;
		if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && doorBase(level, hit.getBlockPos()).equals(base)) clickDoor = base;
		return true;
	}

	/**
	 * An animal within a block of this closed door or gate on its far side from the buddy: the cell behind it along the way
	 * through, and the half of the doorway on that side.
	 */
	private static boolean animalsBehind(Level level, LocalPlayer p, BlockPos door) {
		BlockState st = level.getBlockState(door);
		if (!st.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return false;
		Direction f = st.getValue(BlockStateProperties.HORIZONTAL_FACING);
		Direction.Axis axis = f.getAxis();   // a door and a gate are both walked through along the way they face
		Direction away = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
		Vec3 mid = Vec3.atCenterOf(door);
		double side = axis == Direction.Axis.X ? p.getX() - mid.x : p.getZ() - mid.z;
		if (side > 0) away = away.getOpposite();
		BlockPos behind = door.relative(away);
		AABB box = new AABB(behind).minmax(new AABB(door)).expandTowards(0, 1, 0).inflate(0.3, 0, 0.3);
		if (level.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, box, a -> a.isAlive()).isEmpty()) return false;
		// only into a closed space (a pen): out of one, onto open ground, the animals by its gate outside held the buddy in the
		// sheep pen on the test server, both its gates (23 Oct 2026)
		return Herd.closedSide(level, behind);
	}

	/** Animals within four blocks of a door or gate: a pen's gate, which must not be left open. */
	private static boolean animalsBy(Level level, BlockPos door) {
		return !level.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, new AABB(door).inflate(4, 2, 4), a -> a.isAlive()).isEmpty();
	}

	/** Turn toward the point and walk once you roughly look at it. */
	private static void steer(Minecraft mc, LocalPlayer p, Vec3 m, double distance, boolean sprint, float wantPitch, boolean push) {
		float yaw = (float) Math.toDegrees(Math.atan2(-(m.x - p.getX()), m.z - p.getZ()));
		float delta = Mth.wrapDegrees(yaw - p.getYRot());
		float pitch = p.getXRot() + Mth.clamp(wantPitch - p.getXRot(), -8f, 8f) * 0.5f;
		// as fast as a player turns with the mouse: at ten degrees a tick a corner of ninety stood the buddy still for nine ticks
		float step = distance > 2 ? 30f : 24f;
		if (distance > 0.15) MouseTurn.toward(p, p.getYRot() + Mth.clamp(delta, -step, step), pitch);
		else MouseTurn.toward(p, p.getYRot(), pitch);
		HeldKeys.set(mc.options.keyUp, push && Math.abs(delta) < 60);
		// Walking on while the head still turns (21 Oct 2026, after SoulFire's MovementAction, which picks the keys from
		// the angle to the target): on firm ground a cell off to the side gets the strafe key with or without forward,
		// so a sharp turn no longer means standing still while turning. Not on a ladder, in water or by a drop (walk's own
		// handling of those keys), and never near the cell, where a strafe overshoots it.
		boolean side = push && strafeOk && p.onGround() && !p.isInWater() && !p.onClimbable() && distance > 0.6;
		HeldKeys.set(mc.options.keyRight, side && delta > 25 && delta < 115);
		HeldKeys.set(mc.options.keyLeft, side && delta < -25 && delta > -115);
		if (side && Math.abs(delta) >= 60 && Math.abs(delta) < 115) HeldKeys.set(mc.options.keyUp, false);
		// under water sprint is swimming, kept through the turns of a cave; on land only on a straight bit
		HeldKeys.set(mc.options.keySprint, sprint && push && (Math.abs(delta) < 20 || p.isInWater()));
	}

	/** Strafing allowed in the next steer: off by a drop and in the jumps' own handling. */
	private static boolean strafeOk = true;

	/** Turn toward a point within a few ticks, as with the mouse. */
	private static void aimAt(LocalPlayer p, Vec3 at) {
		Vec3 eye = p.getEyePosition();
		double dx = at.x - eye.x, dy = at.y - eye.y, dz = at.z - eye.z;
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)), pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
		MouseTurn.toward(p, p.getYRot() + Mth.clamp(Mth.wrapDegrees(yaw - p.getYRot()), -14f, 14f), p.getXRot() + Mth.clamp(pitch - p.getXRot(), -10f, 10f));
	}

	/** Standing beside the player: turn toward the player's face now and then, the way a player who waits does. */
	private static void lookAt(LocalPlayer p, Player t) {
		Vec3 eye = p.getEyePosition(), to = t.getEyePosition();
		double dx = to.x - eye.x, dy = to.y - eye.y, dz = to.z - eye.z;
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)), pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
		float dYaw = Mth.wrapDegrees(yaw - p.getYRot()), dPitch = pitch - p.getXRot();
		if (Math.abs(dYaw) < 10 && Math.abs(dPitch) < 10) return;
		MouseTurn.toward(p, p.getYRot() + Mth.clamp(dYaw, -7f, 7f), p.getXRot() + Mth.clamp(dPitch, -5f, 5f));
	}

	/**
	 * Waiting in water: afloat at the surface, or under it at about the player's height, instead of sinking to the bottom.
	 * On land, nothing.
	 */
	private static void hover(Minecraft mc, LocalPlayer p, double y) {
		boolean sink = false;
		if (p.isInWater()) {
			boolean up = !p.isUnderWater() || p.getY() < y - 0.3;
			if (up) holdJump(mc);
			sink = !up && p.getY() > y + 1.0;
		}
		// only when it changes: out of the water the crouch key is the alt's own again
		if (sink != hoverSink) { HeldKeys.set(mc.options.keyShift, sink); hoverSink = sink; }
	}

	private static void holdJump(Minecraft mc) { HeldKeys.set(mc.options.keyJump, true); jumpTicks = 3; }

	// ---------- for the work along with the player ----------

	static int ticks() { return ticks; }

	/**
	 * Cells of a planned route reached, ever: a walk round a wall or down a stair gets on along its route while it gets no
	 * nearer in a straight line, which is progress all the same (22 Oct 2026, the cellar stairs; Work.approach, Stash).
	 */
	static int cellsWalked() { return cellsWalked; }

	private static int cellsWalked;

	/** Walk to that cell exactly, on the same plans and jumps as when following. */
	static void goTo(Minecraft mc, LocalPlayer p, Vec3 goal) { walking = true; walkTo(mc, p, goal, true, null); }

	/**
	 * The next walk planned at once, not at the next due plan (up to half a second off, two seconds after a plan that
	 * found no way): getting away from a creeper (Pen) walked on to the spot meanwhile.
	 */
	static void planNow() { nextPlan = ticks; }

	/** Stand still where the buddy is. */
	static void stand() { halt(); }

	static void aim(LocalPlayer p, Vec3 at) { aimAt(p, at); }

	/** At a point to click that may be narrow (beside an item frame): to a pixel. */
	static void aimExact(LocalPlayer p, Vec3 at) {
		Vec3 eye = p.getEyePosition();
		double dx = at.x - eye.x, dy = at.y - eye.y, dz = at.z - eye.z;
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)), pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
		MouseTurn.toward(p, p.getYRot() + Mth.clamp(Mth.wrapDegrees(yaw - p.getYRot()), -14f, 14f), p.getXRot() + Mth.clamp(pitch - p.getXRot(), -10f, 10f), 0.1f);
	}

	/** Where the feet are standing in this cell, NaN if there is no standing there. */
	static double floor(Level level, BlockPos f) { return floorY(level, f, false); }

	/** Stop walking but stay on. */
	private static void halt() {
		if (walking) release();
		walking = false;
		clearRoute();
	}

	static void release() {
		Minecraft mc = AfkHelperClient.mc();
		HeldKeys.set(mc.options.keyUp, false);
		HeldKeys.set(mc.options.keySprint, false);
		HeldKeys.set(mc.options.keyJump, false);
		HeldKeys.set(mc.options.keyShift, false);
		HeldKeys.set(mc.options.keyDown, false);
		HeldKeys.set(mc.options.keyLeft, false);
		HeldKeys.set(mc.options.keyRight, false);
		jumpTicks = 0;
		hoverSink = false;
		leap = null;
	}

	/**
	 * Cells no plan may enter, until a moment (0.4.80): the columns of the pen being farmed, from under its floor to over its
	 * shaft, and its doors and gates. Not the avoid map, which a new plan, a command and getting loose clear and fill.
	 */
	private static final Map<BlockPos, Long> fence = new HashMap<>();

	/** Fence these cells until then (refreshed while the pen runs, half a minute more after it). */
	static void fence(java.util.Collection<BlockPos> cells, long until) {
		for (BlockPos c : cells) fence.merge(c.immutable(), until, Math::max);
	}

	static boolean fenced(BlockPos c) {
		if (fence.isEmpty()) return false;
		Long until = fence.get(c);
		return until != null && until > System.currentTimeMillis();
	}

	/** Every fence down at once: the buddy fell into the pen, and may dig or pillar out of it. */
	static void unfence() { fence.clear(); }

	/**
	 * Cells the walk away from a swelling creeper keeps out of (Pen's getaway, Getaway.refused): nearer to one of them
	 * than the buddy stood when its cell was picked. No plan enters them and no corner is cut across them, so that the planner's
	 * own way to that cell keeps off the creeper as the search that found the cell did: its shortest way to a cell the
	 * search reached round the creeper went straight past it (the review of the fourth round, 22 Oct 2026). Pen sets them
	 * every tick of that walk, until a moment, and clears them when it ends. Not the fence: beside a fenced cell the
	 * walker crouches.
	 */
	private static Set<BlockPos> shun = Set.of();
	private static long shunUntil;

	/** Keep the walk out of these cells until then (replacing the last set). */
	static void shun(Set<BlockPos> cells, long until) { shun = cells; shunUntil = until; }

	/** No cells kept out of any more: the getaway's walk is over, or something else steers the walk. */
	static void unshun() { shun = Set.of(); shunUntil = 0; }

	private static boolean shunned(BlockPos c) {
		return !shun.isEmpty() && shunUntil > System.currentTimeMillis() && shun.contains(c);
	}

	/** Is the followed player online, as far as the player list says (Pen: the player away). */
	static boolean ownerOnline() { return online(AfkHelperClient.mc()); }

	/**
	 * Whether the sleep step would send the buddy to bed now: the followed player asleep within sixteen blocks, or the
	 * base's bed at night with that player in the base (Pen pauses for the night and lets it).
	 */
	static boolean bedWanted(LocalPlayer p) {
		Player t = find(p);
		if (t != null && t.isSleeping() && t.distanceTo(p) <= 16) return true;
		return baseNight(p, t) != null;
	}

	/** Unstuck shuffled the buddy loose: these cells out of the plans for a minute, and plan anew. */
	static void unstuckDone(java.util.Collection<BlockPos> cells) {
		long until = System.currentTimeMillis() + 60_000;
		for (BlockPos c : cells) avoid.put(c.immutable(), until);
		release();
		clearRoute();
	}

	/** A step of the plan (a dig, a pillar, a bridge) that does not come off: as if stuck there. */
	static void stuckAt(BlockPos wp, String why) { stuck(wp, why); }

	/** A way is planned and to be walked. */
	static boolean routing() { return route != null && idx < route.size(); }

	private static void clearRoute() {
		route = null; routeGoal = null; airRoute = false; idx = 0; still = 0; idxSince = 0; previous = null;
		routeMoves = new ArrayList<>();
	}

	/** Plain ground at one height, where a straight line may cut a corner: no water, ladder or door on it. */
	private static boolean plainLevel(Level level, int from, int to) {
		int y = route.get(from).getY();
		for (int i = from; i <= to; i++) {
			BlockPos c = route.get(i);
			if (c.getY() != y || waterCell(level, c) || ladderCell(level, c) || openable(level.getBlockState(c)) || openable(level.getBlockState(c.above()))) return false;
		}
		return true;
	}

	private static boolean allWater(Level level, int from, int to) {
		for (int i = from; i <= to; i++) if (!waterCell(level, route.get(i))) return false;
		return true;
	}

	/** Does the whole body stay on ground of this cell's height along the straight line there? */
	private static boolean clearLine(Level level, Vec3 a, BlockPos to) {
		double fy = floorY(level, to, false);
		if (Double.isNaN(fy)) return false;
		double bx = to.getX() + 0.5, bz = to.getZ() + 0.5;
		int n = Math.max(1, (int) Math.ceil(Math.hypot(bx - a.x, bz - a.z) / 0.25));
		for (int i = 1; i <= n; i++) {
			double s = (double) i / n, x = a.x + (bx - a.x) * s, z = a.z + (bz - a.z) * s;
			for (double ox : BODY) for (double oz : BODY) {
				BlockPos c = new BlockPos(Mth.floor(x + ox), to.getY(), Mth.floor(z + oz));
				// nor across a cell a getaway keeps out of (shun): a corner cut round a creeper went past it
				if (openable(level.getBlockState(c)) || ladderCell(level, c) || shunned(c)) return false;
				double f = floorY(level, c, false);
				if (Double.isNaN(f) || Math.abs(f - fy) > 0.01) return false;
			}
		}
		return true;
	}

	/** In water: is the straight line from here to that cell all water you can swim through? */
	private static boolean clearWater(Level level, Vec3 a, BlockPos to) {
		Vec3 from = a.add(0, 0.3, 0), b = new Vec3(to.getX() + 0.5, to.getY() + 0.3, to.getZ() + 0.5);
		int n = Math.max(1, (int) Math.ceil(from.distanceTo(b) / 0.25));
		for (int i = 1; i <= n; i++) {
			double s = (double) i / n;
			double x = from.x + (b.x - from.x) * s, y = from.y + (b.y - from.y) * s, z = from.z + (b.z - from.z) * s;
			for (double ox : BODY) for (double oz : BODY)
				if (!waterCell(level, new BlockPos(Mth.floor(x + ox), Mth.floor(y), Mth.floor(z + oz)))) return false;
		}
		return true;
	}

	// ---------- jumps ----------

	private static byte move(int i) { return i >= 0 && i < routeMoves.size() ? routeMoves.get(i) : WALK; }

	private static boolean isRound(byte how) { return how == ROUND_A || how == ROUND_B; }

	/** In a jump, or one of the next six cells of the route is one: not the moment to eat. */
	static boolean inJumps() {
		if (leap != null) return true;
		if (route == null) return false;
		for (int i = idx; i < Math.min(route.size(), idx + 6); i++) if (move(i) != WALK) return true;
		return false;
	}

	private static boolean walksOnly(int from, int to) {
		for (int i = from; i <= to; i++) if (move(i) != WALK) return false;
		return true;
	}

	/** A cell at the edge of a gap or a drop: beside it air with nothing to stand on within a block down. */
	private static boolean edge(Level level, BlockPos cur, BlockPos goal, boolean exact) {
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos h = cur.relative(d);
			if (!passable(level, h, true) || !passable(level, h.above(), false)) continue;
			if (Double.isNaN(feet(level, h, goal, exact)) && Double.isNaN(feet(level, h.below(), goal, exact))) return true;
		}
		return false;
	}

	/**
	 * The jumps from this cell: every landing up to five blocks away, a block higher to three lower, that a player
	 * reaches by the parkour course's arithmetic with travel to spare, with room for the arc. From a standstill at the edge, or
	 * with a run-up where two cells of the same height lie behind (and there is food to sprint).
	 */
	private static void leaps(Level level, BlockPos cur, double fc, BlockPos start, BlockPos goal, boolean exact, int base,
			PriorityQueue<Node> open, Set<BlockPos> closed, Map<BlockPos, Integer> cost, Map<BlockPos, BlockPos> from) {
		AABB here = support(level, cur);
		if (here == null) return;
		for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) {
			int flat2 = dx * dx + dz * dz;
			if (flat2 < 4 || flat2 > 29) continue;   // right beside is a step; beyond five no jump reaches
			if (Math.abs(cur.getX() + dx - start.getX()) > range() || Math.abs(cur.getZ() + dz - start.getZ()) > range()) continue;
			for (int dy = 1; dy >= -3; dy--) {
				BlockPos c = cur.offset(dx, dy, dz);
				double fh = feet(level, c, goal, exact);
				if (Double.isNaN(fh) || waterCell(level, c) || avoid.containsKey(c) || fenced(c) || Pen.over(cur, c)) continue;
				// a jump that lands on farmland tramples it; walking onto it is safe
				if (onFarmland(level, c)) continue;
				AABB there = landing(level, c);
				if (there == null) continue;
				double need = need(here, there), rise = fh - fc;
				if (need < 0.05) continue;   // the tops touch: that is a step, no jump
				if (!voidOk && rise < -JUMP_DROP) continue;   // lands so low that the fall hurts
				// the arithmetic is for a sprinting jump: without food for sprinting only the smallest gaps. A run-up is two
				// cells of one height behind, or the speed of a jump just made the same way (21 Oct 2026: over iron bars the
				// buddy may skip one: from a bar the landing is a running one, with no cell behind)
				boolean carried = carried(cur, dx, dz);
				byte how = need <= (canSprint ? reach(rise, false) - SPARE : 0.4) ? JUMP
					: canSprint && ((need <= reach(rise, true) - SPARE_RUN && runway(level, cur, fc, dx, dz) >= 2)
						|| (carried && need <= reach(rise, true) - SPARE_CARRIED)) ? RUN : WALK;
				int dist = (int) Math.ceil(Math.sqrt(flat2));
				if (how != WALK && clearArc(level, cur, fc, c, fh)) {
					if (!missSafe(level, cur, fc, c, fh)) continue;   // a miss would land in lava or too deep
					// a run that carries the speed on is cheaper than stopping for two short jumps (25 against 2 x 20 + 10 a block)
					relax(open, closed, cost, from, cur, c, base + 10 * dist + (how == RUN ? (carried ? 25 : 40) : 20), goal, how);
					break;
				}
				// straight on through a pillar: round its side through the air, two or three blocks along one line at one
				// height, from a standstill at the corner (the parkour course's wall of obsidian)
				if (canSprint && (dx == 0) != (dz == 0) && Math.abs(dx + dz) <= 3 && Math.abs(fh - fc) < 0.05) {
					int side = Round.possible(level, cur, c, 1, here, there) ? 1 : Round.possible(level, cur, c, -1, here, there) ? -1 : 0;
					if (side != 0 && missSafe(level, cur, fc, c, fh)) {
						relax(open, closed, cost, from, cur, c, base + 10 * dist + 40, goal, side > 0 ? ROUND_A : ROUND_B);
						break;
					}
				}
			}
		}
	}

	/** What the feet rest on in this cell, in the world: a slab in it, or the top of the block below (a post's narrow top). */
	private static AABB support(Level level, BlockPos f) {
		BlockState here = level.getBlockState(f);
		if (!opensBy(level, f) && !climbable(here)) {
			VoxelShape own = here.getCollisionShape(level, f);
			if (!own.isEmpty() && own.max(Direction.Axis.Y) <= 0.6) return own.bounds().move(f);
		}
		BlockPos b = f.below();
		VoxelShape floor = level.getBlockState(b).getCollisionShape(level, b);
		return floor.isEmpty() ? null : floor.bounds().move(b);
	}

	/** Where a jump into this cell lands: its support, or for a ladder the block itself (the jumper's middle has to get into it). */
	private static AABB landing(Level level, BlockPos c) {
		if (ladderCell(level, c) && Double.isNaN(floorY(level, c, false)))
			return new AABB(c.getX() + 0.3, c.getY(), c.getZ() + 0.3, c.getX() + 0.7, c.getY() + 1, c.getZ() + 0.7);
		return support(level, c);
	}

	/** How far the jumper's middle has to travel: from 0.3 over one edge to 0.3 short of the other, as the parkour course's arithmetic counts it. */
	private static double need(AABB a, AABB b) {
		double gx = Math.max(0, Math.max(b.minX - a.maxX, a.minX - b.maxX));
		double gz = Math.max(0, Math.max(b.minZ - a.maxZ, a.minZ - b.maxZ));
		return Math.hypot(Math.max(0, gx - 0.6), Math.max(0, gz - 0.6));
	}

	/**
	 * How far the middle of a jumping player travels before the tick of landing {@code rise} blocks above the take-off
	 * (below, when negative); 0 when the jump does not get that high. The parkour course's reach(), in its own numbers: a
	 * sprinting jump from a standstill moves 0.3274 in the first tick (0.4806 with a run-up), then 0.546 of it plus
	 * 0.02548 and after that 0.91 of it plus 0.02548 a tick; the jump leaves the ground at 0.42 a tick, which becomes 0.98 of
	 * it less 0.08 each tick.
	 */
	static double reach(double rise, boolean run) {
		double speed = run ? 0.4806 : 0.3274, climb = 0.42, height = 0, travelled = 0;
		for (int tick = 1; tick < 60; tick++) {
			double before = height;
			height += climb;
			// the top of the arc under the landing first: no jump gets there. The other order returned the distance at the
			// top for a landing 1.5 up, the top of a fence, and the buddy jumped at the pen's posts again and again, the last time
			// into the pit beside them (22 Oct 2026)
			if (height < before && before < rise) return 0;
			if (height < before && height < rise) return travelled;
			travelled += speed;
			speed = speed * (tick == 1 ? 0.546 : 0.91) + 0.02548;
			climb = (climb - 0.08) * 0.98;
		}
		return 0;
	}

	/** Into this cell by a jump or a run in about the same direction as the next one: a running landing. */
	private static boolean carried(BlockPos cur, int dx, int dz) {
		Byte how = planHow.get(cur);
		BlockPos prev = planFrom.get(cur);
		if (how == null || prev == null || (how != JUMP && how != RUN)) return false;
		int px = cur.getX() - prev.getX(), pz = cur.getZ() - prev.getZ();
		double dot = (px * dx + pz * dz) / (Math.hypot(px, pz) * Math.hypot(dx, dz) + 1e-9);
		return dot >= 0.85;
	}

	/** Cells of the same height behind this one, away from the jump: room for a run-up. */
	private static int runway(Level level, BlockPos cur, double fc, int dx, int dz) {
		int sx = Math.abs(dx) >= Math.abs(dz) ? -Integer.signum(dx) : 0, sz = Math.abs(dx) >= Math.abs(dz) ? 0 : -Integer.signum(dz);
		int n = 0;
		for (int k = 1; k <= 3; k++) {
			double f = floorY(level, cur.offset(sx * k, 0, sz * k), false);
			if (Double.isNaN(f) || Math.abs(f - fc) > 0.1) break;
			n++;
		}
		return n;
	}

	/** Room for the body along the jump: the line between the two cells, its middle about 1.25 above the straight line. */
	private static boolean clearArc(Level level, BlockPos cur, double fc, BlockPos c, double fh) {
		double x0 = cur.getX() + 0.5, z0 = cur.getZ() + 0.5, x1 = c.getX() + 0.5, z1 = c.getZ() + 0.5;
		int n = Math.max(3, (int) Math.ceil(Math.hypot(x1 - x0, z1 - z0) / 0.3));
		for (int i = 1; i < n; i++) {
			double s = (double) i / n, x = x0 + (x1 - x0) * s, z = z0 + (z1 - z0) * s;
			double y = fc + (fh - fc) * s + 5.0 * s * (1 - s);
			for (double ox : ARC) for (double oz : ARC)
			{
				for (int yy = Mth.floor(y + 0.05); yy <= Mth.floor(y + 1.75); yy++)
					if (!passable(level, new BlockPos(Mth.floor(x + ox), yy, Mth.floor(z + oz)), false)) return false;
				// the block under the feet at this point of the arc: a fence or a wall is 1.5 high and reaches into the
				// cell above it, where the cells alone see air (21 Oct 2026: jumps over a pen's fence,
				// "jump failed" each time)
				BlockPos under = new BlockPos(Mth.floor(x + ox), Mth.floor(y + 0.05) - 1, Mth.floor(z + oz));
				VoxelShape sh = level.getBlockState(under).getCollisionShape(level, under);
				if (!sh.isEmpty() && under.getY() + sh.max(Direction.Axis.Y) > y + 0.02) return false;
			}
		}
		return true;
	}

	/** How far to walk to water while burning: so many steps, and no further: only when it is close enough. */
	static final int DOUSE_STEPS = 8;
	private static BlockPos douse;          // the water being walked into
	private static long douseUntil, douseLookAt;

	/**
	 * On fire (out of lava, not in water) and no bucket that can put it out here: the nearest water within
	 * {@link #DOUSE_STEPS} steps, looked for once a second, walked into by the usual paths; given up after six
	 * seconds. Events {@code volg}: naar water, geblust (in the water), brandt niet meer (out by itself on the way),
	 * water niet gehaald.
	 */
	private static boolean douseWalk(Minecraft mc, LocalPlayer p, long now) {
		boolean burning = Water.burning(p);
		if (douse != null) {
			if (!burning || p.isInWater() || now > douseUntil) {
				Exchange.emit("follow", "action", p.isInWater() ? "put out" : burning ? "water not reached" : "no longer burning", "hp", round(p.getHealth()));
				douse = null;
				halt();
				return false;
			}
			walking = true;
			walkTo(mc, p, Vec3.atBottomCenterOf(douse), true, null);
			return true;
		}
		if (!burning || Water.canDouse(p) || now < douseLookAt) return false;
		douseLookAt = now + 1000;
		BlockPos w = nearWater(p.level(), startCell(p), DOUSE_STEPS);
		if (w == null) return false;
		douse = w;
		douseUntil = now + 6000;
		clearRoute();
		leap = null;
		Exchange.emit("follow", "action", "to water", "hp", round(p.getHealth()), "x", w.getX(), "y", w.getY(), "z", w.getZ(),
			"distance", round(Math.sqrt(w.distSqr(p.blockPosition()))));
		walking = true;
		walkTo(mc, p, Vec3.atBottomCenterOf(w), true, null);
		return true;
	}

	/**
	 * The nearest water within this many steps, by walking only: a step on level ground, one up, a drop of up to three
	 * (no jumps over gaps: the buddy is burning). Null when there is none that close.
	 */
	private static BlockPos nearWater(Level level, BlockPos start, int steps) {
		feetMemo.clear();
		Map<BlockPos, Integer> dist = new HashMap<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		dist.put(start, 0);
		queue.add(start);
		while (!queue.isEmpty()) {
			BlockPos cur = queue.poll();
			int d = dist.get(cur);
			if (!cur.equals(start) && waterCell(level, cur)) return cur;
			if (d >= steps) continue;
			double fc = feet(level, cur, null, false);
			if (Double.isNaN(fc)) fc = cur.getY();
			for (Direction dir : Direction.Plane.HORIZONTAL) {
				BlockPos h = cur.relative(dir);
				for (BlockPos cand : new BlockPos[] { h.above(), h, h.below(), h.below(2), h.below(3) }) {
					if (dist.containsKey(cand) || fenced(cand)) continue;
					double fh = feet(level, cand, null, false);
					if (Double.isNaN(fh)) continue;
					double dy = fh - fc;
					if (dy > 1.25 || dy < -3.2) continue;
					if (dy > 0.6 && !waterCell(level, cur) && !roomToJump(level, cur, fc, dy)) continue;
					// a drop: the cells fallen past have to be open
					if (dy < -0.6 && (!passable(level, h, true) || !passable(level, h.above(), false))) continue;
					dist.put(cand.immutable(), d + 1);
					queue.add(cand.immutable());
					break;
				}
			}
		}
		return null;
	}

	/** Not burning, not in lava, more than {@link #LEAP_HP}: only then a jump over a gap. */
	private static boolean mayLeap(LocalPlayer p) {
		return !p.isOnFire() && !p.isInLava() && p.getHealth() > LEAP_HP;
	}

	/** The dimension of the server's parkour course and the other games: the void under a jump is no death there. */

	/** A jump from this cell to that one that goes wrong lands on something the buddy lives through ({@link Miss}). */
	private static boolean missSafe(Level level, BlockPos cur, double fc, BlockPos c, double fh) {
		return Miss.safe((x, y, z) -> fallBlock(level, x, y, z), cur.getX(), cur.getZ(), fc, c.getX(), c.getZ(), fh, missFall, voidOk);
	}

	/** What a falling player meets in this block: lava, fire and the like, water, a floor, or nothing. */
	private static Miss.Block fallBlock(Level level, int x, int y, int z) {
		BlockPos q = new BlockPos(x, y, z);
		BlockState st = level.getBlockState(q);
		if (st.is(Blocks.LAVA)) return Miss.HAZARD_BLOCK;
		if (!st.getFluidState().isEmpty()) return Miss.WATER_BLOCK;
		if (Grinder.hazard(level, q)) return Miss.HAZARD_BLOCK;
		VoxelShape s = st.getCollisionShape(level, q);
		return s.isEmpty() ? Miss.AIR_BLOCK : Miss.solid(s.max(Direction.Axis.Y));
	}

	/** A jump under way, in phases: to the edge, the jump, the air, the landing; for a run-up first back and the run. */
	private static final class Leap {
		final BlockPos to;
		final boolean run, ladder;
		final AABB box;
		final double top, foot;
		int phase, phaseTicks, still, retries;
		boolean left;
		Vec3 back, prev;
		// round a pillar: the side (0 for a plain jump), the frame of the jump, the blocks around it, the plan in use
		final int round;
		Round.Frame frame;
		List<AABB> obs;
		Round.Plan plan;
		/** Before a jump round a pillar: land near the corner it starts from, not in the middle. */
		Vec3 corner;
		/** A running jump from a cell without a run-up behind it: only the speed of the landing before carries it. */
		boolean carried;
		/** Round a pillar: the running take-off from the landing was given up, the corner it is. */
		boolean noRun;
		/** The way of the jump, from the cell it starts in to its landing: along this the far side of the landing lies. */
		final double ux, uz;

		Leap(Level level, BlockPos to, boolean run, int round, BlockPos from) {
			this.to = to.immutable();
			this.run = run;
			this.round = round;
			double ddx = to.getX() - from.getX(), ddz = to.getZ() - from.getZ(), dl = Math.hypot(ddx, ddz);
			ux = dl < 1e-6 ? 0 : ddx / dl;
			uz = dl < 1e-6 ? 0 : ddz / dl;
			ladder = ladderCell(level, to) && Double.isNaN(floorY(level, to, false));
			AABB s = landing(level, to);
			box = s != null ? s : new AABB(to.getX() + 0.3, to.getY() - 1, to.getZ() + 0.3, to.getX() + 0.7, to.getY(), to.getZ() + 0.7);
			top = ladder ? to.getY() : box.maxY;
			// the lowest block of the ladder below the cell it was planned into: down to there it still catches the buddy
			int f = to.getY();
			while (ladder && f > to.getY() - 6 && ladderCell(level, new BlockPos(to.getX(), f - 1, to.getZ()))) f--;
			foot = f;
			// Running is the first try, into a ladder too, to be as fast as a good player in parkour:
			// the pillar behind the ladder stops the buddy, so a running jump cannot go too far, where the careful way from the
			// edge cost two and a half seconds a ladder.
			phase = 8;
			AABB fromBox = round == 0 ? null : support(level, from);
			if (fromBox != null) {
				frame = new Round.Frame(fromBox, from, to, round);
				obs = Round.obstacles(level, from, to, fromBox.maxY);
				phase = 20;
			}
		}

		/** The middle of the landing: where the jump is aimed, the way a player aims at the middle of the next block. */
		Vec3 aim() { return corner != null ? corner : new Vec3((box.minX + box.maxX) / 2, top, (box.minZ + box.maxZ) / 2); }

		void next(int ph) { phase = ph; phaseTicks = 0; still = 0; prev = null; }
	}

	/**
	 * A jump into the last cell of a route that ends short of the spot (a plan stops at RANGE, at MAX_NODES or after
	 * 30 ms): the way on from that cell is planned now and added, so that the landing there runs on into the next jump, as
	 * anywhere else on the course. At the route's end the buddy braked to the middle of the landing, stopped there crouched and
	 * only then planned on: in one run of the parkour, the first plan ended on the block before the
	 * long jump at 1630, and the stop cost 0.6 s (33.60 where the rig gives 32.8). On the rig the plan happened to end on
	 * a checkpoint, where a route that ends on a walk is planned on without a stop. Only on a {@code ga}: following plans
	 * anew every half second anyway.
	 */
	private static void planOn(Level level) {
		if (spots.isEmpty() || route == null || routeGoal == null || route.size() < 2 || idx != route.size() - 1) return;
		BlockPos end = route.get(idx);
		if (exactGoal ? end.equals(routeGoal)
				: Math.abs(end.getX() - routeGoal.getX()) <= 1 && Math.abs(end.getZ() - routeGoal.getZ()) <= 1 && Math.abs(end.getY() - routeGoal.getY()) <= 1) return;
		List<BlockPos> cells = route;
		List<Byte> moves = routeMoves;
		List<BlockPos> more = plan(level, end, routeGoal, exactGoal, route.get(idx - 1), move(idx));
		List<Byte> moreMoves = routeMoves;
		route = new ArrayList<>(cells);
		routeMoves = new ArrayList<>(moves);
		if (more == null) return;
		route.addAll(more);
		routeMoves.addAll(moreMoves);
		if (trace) Exchange.emit("goto", "action", "planned on", "x", end.getX(), "y", end.getY(), "z", end.getZ(), "cells", more.size(), "ms", lastMs);
	}

	/**
	 * One tick of the jump into the next cell of the route. From a standstill: crouched to the edge (a crouched player
	 * stops there with the middle 0.3 over it, where the course's arithmetic starts), up for a tick, then forward,
	 * sprint and jump in one tick, sprinting on through the air toward the middle of the landing, and crouched again the
	 * moment of landing, so the speed left does not carry the buddy off a narrow top. With a run-up: first back to the far end
	 * of the platform, then run and jump in the last tick on the edge.
	 */
	private static void leapTick(Minecraft mc, LocalPlayer p, BlockPos wp, byte how) {
		Level level = p.level();
		var o = mc.options;
		boolean run = how == RUN;
		int round = how == ROUND_A ? 1 : how == ROUND_B ? -1 : 0;
		if (leap == null || !leap.to.equals(wp)) {
			planOn(level);
			BlockPos fromCell = idx > 0 ? route.get(idx - 1) : p.blockPosition();
			leap = new Leap(level, wp, run, round, fromCell);
			if (run) {
				double ff = floorY(level, fromCell, false);
				leap.carried = Double.isNaN(ff) || runway(level, fromCell, ff, wp.getX() - fromCell.getX(), wp.getZ() - fromCell.getZ()) < 2;
			}
			byte after = move(idx + 1);
			if (round == 0 && !leap.ladder && isRound(after) && idx + 1 < route.size()) {
				Round.Frame f = new Round.Frame(leap.box, wp, route.get(idx + 1), after == ROUND_A ? 1 : -1);
				leap.corner = new Vec3(f.x(Round.LAND_U, Round.LAND_W), leap.top, f.z(Round.LAND_U, Round.LAND_W));
			}
		}
		Leap l = leap;
		l.phaseTicks++;
		Vec3 aim = l.aim();
		Vec3 now = p.position();
		// a trace of every jump, a line at each phase, to read back what a jump did (the alt has no screen)
		if (l.phaseTicks == 1)
			Exchange.emit("jump", "phase", l.phase, "tick", ticks, "x", round3(p.getX()), "y", round3(p.getY()), "z", round3(p.getZ()),
				"vx", round3(p.getDeltaMovement().x), "vy", round3(p.getDeltaMovement().y), "vz", round3(p.getDeltaMovement().z),
				"sprint", p.isSprinting(), "crouch", p.isShiftKeyDown(), "ground", p.onGround(), "yaw", round3(p.getYRot()),
				"goal", l.to.getX() + "," + l.to.getY() + "," + l.to.getZ());
		boolean resting = l.prev != null && now.distanceToSqr(l.prev) < 0.00002;
		l.prev = now;
		if (l.round != 0 && l.frame == null) { failLeap(p, "round the wall: no take-off"); return; }
		if (l.phase >= 20) { roundTick(mc, p, l, wp, resting); return; }
		switch (l.phase) {
			case 0 -> {
				if (l.run) { l.next(5); return; }
				HeldKeys.set(o.keySprint, false);
				HeldKeys.set(o.keyShift, true);
				boolean facing = face(p, aim, 20f);
				HeldKeys.set(o.keyUp, facing && p.onGround());
				l.still = facing && p.onGround() && resting ? l.still + 1 : 0;
				if (l.still >= 3) l.next(1);
				else if (l.phaseTicks > 100) failLeap(p, "rand");
			}
			case 1 -> {
				// Still crouched, the walk key let go until the speed is gone: the edge stops the walking, not the speed,
				// and getting up with 0.036 a tick left carried the buddy off the edge before the jump (17 Oct 2026).
				HeldKeys.set(o.keyShift, true);
				HeldKeys.set(o.keyUp, false);
				face(p, aim, 20f);
				Vec3 v = p.getDeltaMovement();
				if (Math.hypot(v.x, v.z) < 0.002 || l.phaseTicks > 12) l.next(l.run ? 5 : 7);
			}
			case 7 -> {
				// a crouched player cannot sprint: up, and still for a tick
				HeldKeys.set(o.keyShift, false);
				HeldKeys.set(o.keyUp, false);
				face(p, aim, 20f);
				if (!p.onGround()) { failLeap(p, "off the edge"); return; }
				if (l.phaseTicks >= 2) l.next(2);
			}
			case 2 -> {
				face(p, aim, 180f);
				HeldKeys.set(o.keyUp, true);
				HeldKeys.set(o.keySprint, true);
				HeldKeys.set(o.keyJump, true);
				jumpTicks = 2;
				l.left = false;
				l.next(3);
			}
			case 3 -> {
				// A sprinting jump always carries about 2.6 blocks, and an iron bar 1.8 off was overshot (17 Oct 2026): in
				// the air the buddy runs on, lets go or brakes, whichever comes down nearest the middle of the landing.
				// with a running jump after this one that has no run-up, the speed is what carries it: land as far on as the
				// landing allows instead of braking to its middle (21 Oct 2026: from the first iron bar to the third)
				// the racing line: keys and turn for the rest of the flight that come down where the next jump goes at once
				Line line = l.left && !p.onGround() ? racePlan(level, p, l) : null;
				int air, side, land = ticksToLand(p, l.top);
				if (line != null) {
					air = line.fwd;
					side = line.side;
					if (line.turn) face(p, line.next, LINE_TURN);
				} else {
					AABB on = walkOn(level, l);
					air = l.ladder ? 1 : airControl(p, l.top, carriedNext(level, l) || narrowChain(l) ? farAim(p, l, aim)
						: on != null ? onFar(l, aim, on) : chainOn(level, l) ? onAim(p, l, aim) : aim, l.ux, l.uz);
					// sideways as well: the speed across the line, from a jump that came in at an angle, is steered out with the
					// strafe keys, the way a player corrects in the air (17 Oct 2026: the only brake while looking straight)
					side = l.ladder || !l.left ? 0 : airStrafe(p, aim, land, l.box, air > 0);
				}
				HeldKeys.set(o.keyUp, air > 0);
				HeldKeys.set(o.keySprint, air > 0);
				HeldKeys.set(o.keyDown, air < 0);
				HeldKeys.set(o.keyShift, false);
				// every tick of the flight, to set the take-off arithmetic against what the game did (24 Oct 2026)
				if (trace) { Vec3 fv = p.getDeltaMovement();
					Exchange.emit("jump", "flight", l.phaseTicks, "tick", ticks, "x", round3(p.getX()), "y", round3(p.getY()), "z", round3(p.getZ()),
						"vx", round3(fv.x), "vy", round3(fv.y), "vz", round3(fv.z), "ground", p.onGround(), "sprint", p.isSprinting(),
						"air", air, "sideways", side, "yaw", round3(p.getYRot()), "goal", l.to.getX() + "," + l.to.getY() + "," + l.to.getZ(),
						"line", line == null ? -1 : line.tier, "rotate", line != null && line.turn); }
				HeldKeys.set(o.keyLeft, side > 0);
				HeldKeys.set(o.keyRight, side < 0);
				// the last two ticks before the landing: turned toward the next jump already, so that the take-off from
				// a narrow top goes at once (four careful jumps of the 17th came from turning on the block itself)
				BlockPos after = l.left && land <= 2 && move(idx + 1) != WALK && route != null && idx + 1 < route.size() ? route.get(idx + 1) : null;
				AABB nextBox = after == null ? null : landing(level, after);
				if (line == null) {
					if (nextBox != null) face(p, new Vec3((nextBox.minX + nextBox.maxX) / 2, nextBox.maxY, (nextBox.minZ + nextBox.maxZ) / 2), 25f);
					else face(p, aim, 6f);
				}
				if (!p.onGround()) l.left = true;
				if (l.ladder && l.left && p.onClimbable() && p.getBlockX() == wp.getX() && p.getBlockZ() == wp.getZ()) { doneLeap(mc, false); return; }
				// before a jump round a pillar a stop: that jump starts crouched at the corner
				if (l.left && p.onGround() && !p.isInWater() && ((move(idx + 1) != WALK && !isRound(move(idx + 1))) || runOn(level, p))
						&& (onLanding(p, l) || onWalkOn(p, l, walkOn(level, l)))) {
					// another jump straight after: no stop, run on and jump again from the run (the next Leap)
					boolean carriedOn = carriedNext(level, l), narrow = narrowChain(l);
					int onTo = walkedOn(level, p, l);
					doneLeap(mc, true);
					// down further on, in a cell of the walk after the landing: the route on from there, and in the cell the next
					// jump goes from that jump at once (two ticks of walking on its plan first left the buddy at cp3's edge, too late for
					// the running take-off, and the careful jump from a standstill fell short, 24 Oct 2026)
					if (onTo > 0) idx = onTo;
					// Down on an iron bar with another jump to come: on, at once, when a jump from where one tick of running
					// takes the buddy reaches the next landing; else crouched, and the bar's edge holds for the careful jump.
					// The buddy stopped on the bar before every time, half a second (24 Oct 2026).
					if (narrow && !carriedOn) {
						if (runsOffNarrow(level, p)) { HeldKeys.set(o.keyUp, true); HeldKeys.set(o.keySprint, true); HeldKeys.set(o.keyDown, false); HeldKeys.set(o.keyShift, false); }
						else { HeldKeys.set(o.keyUp, false); HeldKeys.set(o.keySprint, false); HeldKeys.set(o.keyShift, true); }
						return;
					}
					// On a whole block the run goes on from the landing tick: the keys as the air left them (often braking
					// toward the middle) cost the sprint and a tick of push back on the ground before the take-off (24 Oct
					// 2026). On a post or a bar the speed would carry the buddy off; there the next jump stops as before, but for a
					// running jump on the speed of the landing: that one goes from the tick after, and needs all of it.
					if (RUN_ON && (whole(l.box) || carriedOn) && !l.ladder) { HeldKeys.set(o.keyUp, true); HeldKeys.set(o.keySprint, true); HeldKeys.set(o.keyDown, false); }
					// and that jump's first tick now, in the landing tick: its take-off is worked out and the key goes down for
					// the next tick, so one tick is spent on the ground, as by a player who keeps the jump key down. It began a tick
					// later, two on the ground a jump, about two seconds over the parkour course (against a player's record, 23 Oct 2026)
					if (EARLY && route != null && idx < route.size() && plainHop(level, l, route.get(idx)) && mayLeap(p)) {
						landingTick = true;
						try { leapTick(mc, p, route.get(idx), move(idx)); } finally { landingTick = false; }
					}
					return;
				}
				if (l.left && (p.onGround() || p.isInWater())) {
					HeldKeys.set(o.keyUp, false);
					HeldKeys.set(o.keySprint, false);
					HeldKeys.set(o.keyDown, false);
					HeldKeys.set(o.keyLeft, false);
					HeldKeys.set(o.keyRight, false);
					HeldKeys.set(o.keyShift, true);
					// the jump round a pillar starts crouched from wherever the buddy stands on it: no waiting for the speed to go
					// not crouched yet: the jump round may go straight from this landing, running, and a crouch takes the sprint
					// (Round's running take-off); if not, the corner way crouches by itself
					if (isRound(move(idx + 1)) && !p.isInWater() && onLanding(p, l)) { doneLeap(mc, false); HeldKeys.set(o.keyShift, false); return; }
					l.next(4);
				} else if (p.getY() < l.top - 4 || l.phaseTicks > 60) failLeap(p, "dropped");
			}
			case 4 -> {
				// crouched until the speed of the jump is gone, as at the edge: up with speed left, the buddy slid off the top
				HeldKeys.set(o.keyShift, true);
				HeldKeys.set(o.keyUp, false);
				Vec3 v = p.getDeltaMovement();
				if (l.phaseTicks < 3 || (Math.hypot(v.x, v.z) > 0.002 && l.phaseTicks < 15)) return;
				if (onLanding(p, l)) doneLeap(mc, false);
				else failLeap(p, "beside it");
			}
			case 5 -> {
				if (l.back == null) l.back = runStart(level, p, aim);
				HeldKeys.set(o.keySprint, false);
				HeldKeys.set(o.keyShift, true);
				boolean facing = face(p, l.back, 20f);
				HeldKeys.set(o.keyUp, facing);
				l.still = facing && resting ? l.still + 1 : 0;
				if (Math.hypot(l.back.x - p.getX(), l.back.z - p.getZ()) < 0.2 || l.still >= 3) { HeldKeys.set(o.keyUp, false); l.next(6); }
				else if (l.phaseTicks > 100) failLeap(p, "run-up");
			}
			case 6 -> {
				HeldKeys.set(o.keyShift, false);
				boolean facing = face(p, aim, 20f);
				// turned round first, standing; then run
				if (l.phaseTicks < 3 || !facing && l.phaseTicks < 30) { HeldKeys.set(o.keyUp, false); HeldKeys.set(o.keySprint, false); return; }
				HeldKeys.set(o.keyUp, true);
				HeldKeys.set(o.keySprint, true);
				Vec3 dir = new Vec3(aim.x - p.getX(), 0, aim.z - p.getZ()).normalize();
				if (p.onGround() && !supported(level, p, dir.scale(0.32))) {
					HeldKeys.set(o.keyJump, true);
					jumpTicks = 2;
					l.left = false;
					l.next(3);
				} else if (l.phaseTicks > 60) failLeap(p, "run-up");
			}
			case 8 -> {
				// Running: the way a player strings jumps together, a checkpoint much faster by jumping one after the
				// other and running on. Sprint on toward the landing and jump in the
				// first tick in which a jump from here, at this speed, comes down on it; would the next tick take the buddy off the
				// edge before that, brake crouched (the edge holds) and jump the careful way from there.
				HeldKeys.set(o.keyShift, false);
				HeldKeys.set(o.keyDown, false);
				if (!p.onGround()) {
					// off something without a jump (a step down): steer in the air all the same
					if (l.phaseTicks > 1) { l.left = true; l.next(3); }
					return;
				}
				face(p, aim, 35f);
				double yawErr = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-(aim.x - p.getX()), aim.z - p.getZ())) - p.getYRot());
				boolean facing = Math.abs(yawErr) < 15;
				// Turned away (just after a checkpoint, a new plan while still running the other way): turn round crouched
				// before running, or the run goes off the side; the buddy ran off a checkpoint that way (17 Oct 2026).
				float off = Math.abs(Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-(aim.x - p.getX()), aim.z - p.getZ())) - p.getYRot()));
				if (off > 45f) {
					if (trace) Exchange.emit("jump", "take-off", "no", "tick", ticks, "landing", landingTick, "x", round3(p.getX()), "z", round3(p.getZ()),
						"yaw_error", Math.round(yawErr), "looks", false, "reason", "turns " + Math.round(off), "goal", l.to.getX() + "," + l.to.getY() + "," + l.to.getZ());
					HeldKeys.set(o.keyUp, false);
					HeldKeys.set(o.keySprint, false);
					HeldKeys.set(o.keyShift, true);
					if (l.phaseTicks > 60) l.next(1);
					return;
				}
				HeldKeys.set(o.keyUp, true);
				HeldKeys.set(o.keySprint, true);
				Vec3 v = p.getDeltaMovement();
				Vec3 dir = new Vec3(aim.x - p.getX(), 0, aim.z - p.getZ()).normalize();
				boolean lastTick = !supported(level, p, new Vec3(v.x + dir.x * 0.13, 0, v.z + dir.z * 0.13));
				boolean can = l.ladder ? ladderTakeOff(p, l, aim, lastTick) : canTakeOff(p, l, aim, yawErr);
				// a tick on the ground without the take-off, and why: what stands in the way of a jump every landing tick
				if (trace && !(facing && can))
					Exchange.emit("jump", "take-off", "no", "tick", ticks, "landing", landingTick, "x", round3(p.getX()), "z", round3(p.getZ()),
						"v", round3(Math.hypot(v.x, v.z)), "sprint", p.isSprinting(), "yaw_error", Math.round(yawErr), "looks", facing,
						"reason", takeOffWhy, "goal", l.to.getX() + "," + l.to.getY() + "," + l.to.getZ());
				if (facing && can) {
					if (trace) Exchange.emit("jump", "take-off", landingTick ? "landing" : "running", "tick", ticks, "x", round3(p.getX()),
						"v", round3(Math.hypot(v.x, v.z)), "sprint", p.isSprinting(), "ground", p.onGround(), "yaw_error", Math.round(yawErr),
						"reason", takeOffWhy, "goal", l.to.getX() + "," + l.to.getY() + "," + l.to.getZ(),
						"z", round3(p.getZ()), "in", round3(predIn0), "out_of", round3(predIn1), "near", round3(predNear), "far", round3(predFar),
						"vx", round3(v.x), "vz", round3(v.z));
					HeldKeys.set(o.keyJump, true);
					jumpTicks = 2;
					l.left = false;
					l.next(3);
				} else if ((lastTick || l.phaseTicks > 60) && l.carried && !l.ladder) {
					// a run on the speed of the landing that does not come off: no careful jump from a standstill, which this
					// gap never allows (the buddy fell short into the water every time), but stop on the edge and plan without it
					Exchange.emit("jump", "brake", true, "tick", ticks, "goal", l.to.getX() + "," + l.to.getY() + "," + l.to.getZ(),
						"v", round3(Math.hypot(v.x, v.z)), "reason", "no speed: " + takeOffWhy);
					avoid.put(l.to, System.currentTimeMillis() + 60_000);
					leap = null;
					clearRoute();
					HeldKeys.set(o.keyUp, false);
					HeldKeys.set(o.keySprint, false);
					HeldKeys.set(o.keyShift, true);
					return;
				} else if (lastTick || l.phaseTicks > 60) {
					// why the run did not come off, to read back (the alt has no screen)
					Exchange.emit("jump", "brake", true, "tick", ticks, "goal", l.to.getX() + "," + l.to.getY() + "," + l.to.getZ(),
						"looks", facing, "askew", Math.round(off), "v", round3(Math.hypot(v.x, v.z)), "long", l.phaseTicks > 60,
						"reason", takeOffWhy);
					HeldKeys.set(o.keyUp, false);
					HeldKeys.set(o.keySprint, false);
					HeldKeys.set(o.keyShift, true);
					l.next(1);
				}
			}
			default -> l.next(0);
		}
	}

	/**
	 * One tick of the jump round a pillar ({@link Round}): crouched to the corner of the block, against its edge on the
	 * side passed, 0.15 back from its middle; still; up; then the jump at the angle that leaves most over, and in the
	 * air every tick the direction to look in worked out anew from the buddy's place (sprinting on, the facing direction
	 * is where the air pushes). Landed, as any jump.
	 */
	/**
	 * The jump round the pillar straight from the run the buddy landed with: the take-off angles within reach of a turn
	 * this tick, worked out with the present speed; turned, and worked out again for the yaw actually reached. True when
	 * the jump went.
	 */
	private static boolean runningRound(LocalPlayer p, Leap l, Round.Frame fr, Vec3 v, String kind) {
		double cur = fr.rel(p.getYRot());
		Round.Plan want = Round.best(l.obs, fr, p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z, Round.FIRSTS_M, Round.AIRS, Round.KS, Round.AIRS_M, l.box, l.top);
		if (trace) Exchange.emit(kind, "action", "round", "how", "running plan", "tick", ticks, "looks", round3(cur), "margin", want == null ? -1 : round3(want.score),
			"corner", want == null ? 0 : round3(want.first));
		if (want == null || want.score < Round.GO) { l.noRun = true; return false; }
		var o = AfkHelperClient.mc().options;
		MouseTurn.toward(p, fr.yaw(want.first), 25f);
		if (Math.abs(fr.rel(p.getYRot()) - want.first) > 3) {
			// a turn this large does not come through within the tick of the jump (the jump went straight on and came
			// down on the same block): turn first, standing (no crouch, that takes the sprint), and look again next tick
			MouseTurn.toward(p, fr.yaw(want.first), 25f);
			HeldKeys.set(o.keyShift, false);
			HeldKeys.set(o.keyUp, false);
			HeldKeys.set(o.keySprint, false);
			HeldKeys.set(o.keyDown, false);
			HeldKeys.set(o.keyLeft, false);
			HeldKeys.set(o.keyRight, false);
			return true;
		}
		MouseTurn.toward(p, fr.yaw(want.first), 25f);
		double th = fr.rel(p.getYRot());
		Round.Plan now = Round.best(l.obs, fr, p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z, new double[] { th }, Round.AIRS, Round.KS, Round.AIRS, l.box, l.top);
		if (now == null || now.score < Round.GO) return false;
		Exchange.emit(kind, "action", "round", "how", "running take-off", "tick", ticks, "u", round3(fr.u(p.getX(), p.getZ())), "w", round3(fr.w(p.getX(), p.getZ())),
			"corner", round3(th), "v", round3(Math.hypot(v.x, v.z)), "then", round3(now.a1) + "x" + now.k + " " + round3(now.a2), "margin", round3(now.score));
		l.plan = now;
		HeldKeys.set(o.keyShift, false);
		HeldKeys.set(o.keyDown, false);
		HeldKeys.set(o.keyLeft, false);
		HeldKeys.set(o.keyRight, false);
		HeldKeys.set(o.keyUp, true);
		HeldKeys.set(o.keySprint, true);
		HeldKeys.set(o.keyJump, true);
		jumpTicks = 2;
		l.left = false;
		l.next(25);
		return true;
	}

	private static void roundTick(Minecraft mc, LocalPlayer p, Leap l, BlockPos wp, boolean resting) {
		var o = mc.options;
		Level level = p.level();
		Round.Frame fr = l.frame;
		Vec3 v = p.getDeltaMovement();
		double u = fr.u(p.getX(), p.getZ()), w = fr.w(p.getX(), p.getZ());
		String kind = spots.isEmpty() ? "follow" : "goto";
		switch (l.phase) {
			case 20 -> {
				// Running on: in the first ticks on the block, still sprinting from the jump onto it, a jump from right here
				// at this speed may come off already, with the same room to spare as from the corner (21 Oct 2026: the buddy
				// waited needlessly at the obsidian jump). Then no crouching to the corner and no standing up.
				if (l.phaseTicks <= 2 && trace) Exchange.emit(kind, "action", "round", "how", "running?", "tick", ticks, "ground", p.onGround(),
					"crouching", p.isCrouching(), "sprint", p.isSprinting(), "v", round3(Math.hypot(v.x, v.z)), "u", round3(u), "w", round3(w), "looks", round3(fr.rel(p.getYRot())));
				if (l.phaseTicks <= 3 && !l.noRun && p.onGround() && !p.isCrouching() && p.getFoodData().getFoodLevel() > 6 && runningRound(p, l, fr, v, kind)) return;
				// crouched (the edge holds), looking straight on, the side key toward the outside and the walk keys to
				// 0.15 back from the middle
				HeldKeys.set(o.keySprint, false);
				HeldKeys.set(o.keyShift, true);
				float turn = Mth.wrapDegrees(fr.yaw(0) - p.getYRot());
				MouseTurn.toward(p, p.getYRot() + Mth.clamp(turn, -20f, 20f), 25f);
				boolean facing = Math.abs(Mth.wrapDegrees(fr.yaw(0) - p.getYRot())) < 8f;
				double ue = Round.START_U - u;
				HeldKeys.set(o.keyUp, facing && ue > 0.04);
				HeldKeys.set(o.keyDown, facing && ue < -0.04);
				// outward only until near the edge: the speed toward it stays in the body while the crouch holds it there, and
				// standing up for the jump would carry it off with it
				boolean outLeft = fr.sideIsLeft(), out = facing && w < 0.7;
				HeldKeys.set(o.keyLeft, out && outLeft);
				HeldKeys.set(o.keyRight, out && !outLeft);
				// Off as soon as a jump from where the buddy will stand, standing up, comes off with room to spare: up first (no
				// edge holds then, so the speed that is left still moves the buddy), and the jump two ticks later, once standing
				// (a crouched player cannot sprint). The speed toward the edge stays in the body while the crouch holds: let it go.
				double k2 = 1 + 0.546, nx = p.getX() + v.x * k2, nz = p.getZ() + v.z * k2;
				if (p.onGround() && l.phaseTicks > 1 && Math.hypot(v.x, v.z) < 0.08
						&& Math.abs(fr.u(nx, nz)) < 0.7999 && Math.abs(fr.w(nx, nz)) < 0.7999) {
					long t0 = System.nanoTime();
					Round.Plan q = Round.best(l.obs, fr, nx, p.getY(), nz, v.x * 0.298, 0, v.z * 0.298, Round.FIRSTS_M, Round.AIRS, Round.KS, Round.AIRS_M, l.box, l.top);
					if (trace) Exchange.emit(kind, "action", "round", "how", "can do", "tick", ticks, "u", round3(fr.u(nx, nz)), "w", round3(fr.w(nx, nz)),
						"margin", q == null ? -1 : round3(q.score), "ms", (System.nanoTime() - t0) / 1_000_000);
					if (q != null && q.score >= Round.GO) {
						HeldKeys.set(o.keyShift, false);
						HeldKeys.set(o.keyUp, false);
						HeldKeys.set(o.keyDown, false);
						HeldKeys.set(o.keyLeft, false);
						HeldKeys.set(o.keyRight, false);
						l.plan = q;
						l.next(23);
						return;
					}
				}
				l.still = facing && p.onGround() && resting && Math.abs(ue) < 0.06 && w > 0.7 ? l.still + 1 : 0;
				if (l.still >= 3) l.next(21);
				else if (l.phaseTicks > 120) failLeap(p, "round the wall: corner");
			}
			case 21 -> {
				// the keys let go, still crouched, until the last speed is gone
				HeldKeys.set(o.keyShift, true);
				HeldKeys.set(o.keyUp, false);
				HeldKeys.set(o.keyDown, false);
				HeldKeys.set(o.keyLeft, false);
				HeldKeys.set(o.keyRight, false);
				if (Math.hypot(v.x, v.z) < 0.002 || l.phaseTicks > 12) l.next(22);
			}
			case 22 -> {
				// a crouched player cannot sprint: up, and still for two ticks
				HeldKeys.set(o.keyShift, false);
				if (!p.onGround()) { failLeap(p, "round the wall: off the edge"); return; }
				if (l.phaseTicks >= 2) l.next(23);
			}
			case 23 -> {
				// the angle that leaves most over from where the buddy stands; turned to it, and the jump worked out again for
				// the yaw actually reached (the mouse turns in whole pixels). Up first: a crouched player cannot sprint.
				HeldKeys.set(o.keyShift, false);
				if (p.isCrouching() || !p.onGround()) {
					if (l.phaseTicks > 4) failLeap(p, "round the wall: not upright");
					return;
				}
				if (l.plan == null) {
					l.plan = Round.best(l.obs, fr, p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z, Round.FIRSTS, Round.AIRS, Round.KS, Round.AIRS, l.box, l.top);
					if (l.plan == null || l.plan.score < 0.02) {
						Exchange.emit(kind, "action", "round", "how", "no way", "u", round3(u), "w", round3(w),
							"margin", l.plan == null ? -1 : round3(l.plan.score));
						failLeap(p, "round the wall: no way");
						return;
					}
				}
				MouseTurn.toward(p, fr.yaw(l.plan.first), 25f);
				double th = fr.rel(p.getYRot());
				Round.Plan now = Round.best(l.obs, fr, p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z, new double[] { th }, Round.AIRS, Round.KS, Round.AIRS, l.box, l.top);
				if (now == null || now.score < 0.01) {
					// from here it does not come off after all: crouched to the corner again
					Exchange.emit(kind, "action", "round", "how", "back to the corner", "u", round3(u), "w", round3(w));
					l.plan = null;
					if (++l.retries > 3) failLeap(p, "round the wall: corner wrong");
					else l.next(20);
					return;
				}
				Exchange.emit(kind, "action", "round", "how", "take-off", "tick", ticks, "u", round3(u), "w", round3(w), "corner", round3(th),
					"then", round3(now.a1) + "x" + now.k + " " + round3(now.a2), "margin", round3(now.score));
				l.plan = now;
				HeldKeys.set(o.keyShift, false);
				HeldKeys.set(o.keyUp, true);
				HeldKeys.set(o.keySprint, true);
				HeldKeys.set(o.keyJump, true);
				jumpTicks = 2;
				l.left = false;
				l.next(25);
			}
			case 25 -> {
				if (!p.onGround()) l.left = true;
				if (l.left && (p.onGround() || p.isInWater())) {
					HeldKeys.set(o.keyLeft, false);
					HeldKeys.set(o.keyRight, false);
					HeldKeys.set(o.keyDown, false);
					if (!onLanding(p, l)) { failLeap(p, "round the wall: beside it"); return; }
					Exchange.emit(kind, "action", "round", "how", "landed", "tick", ticks, "x", round3(p.getX()), "z", round3(p.getZ()));
					byte after = move(idx + 1);
					if (!p.isInWater() && ((after != WALK && !isRound(after)) || runOn(level, p))) { doneLeap(mc, true); return; }
					HeldKeys.set(o.keyUp, false);
					HeldKeys.set(o.keySprint, false);
					HeldKeys.set(o.keyShift, true);
					l.next(4);
					return;
				}
				if (p.getY() < l.top - 4 || l.phaseTicks > 50) { failLeap(p, "round the wall: fell"); return; }
				if (!l.left) {
					if (l.phaseTicks > 3) failLeap(p, "round the wall: did not jump");
					return;
				}
				Round.Plan pl = Round.best(l.obs, fr, p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z, null, Round.AIRS, Round.KS, Round.AIRS, l.box, l.top);
				double a;
				if (pl != null) a = pl.a1;
				else {
					// nothing works out any more: at least toward the middle of the landing
					Vec3 aim = l.aim();
					a = Math.toDegrees(Math.atan2(fr.w(aim.x, aim.z) - w, fr.u(aim.x, aim.z) - u));
				}
				if (trace) Exchange.emit(kind, "action", "round", "how", "air", "tick", ticks, "u", round3(u), "w", round3(w),
					"y", round3(p.getY()), "expected", l.plan == null ? "" : round3(fr.u(l.plan.nx, l.plan.nz)) + "," + round3(fr.w(l.plan.nx, l.plan.nz)),
					"look", round3(a), "margin", pl == null ? -1 : round3(pl.score));
				l.plan = pl;
				MouseTurn.toward(p, fr.yaw(a), 25f);
				HeldKeys.set(o.keyShift, false);
				HeldKeys.set(o.keyDown, false);
				HeldKeys.set(o.keyLeft, false);
				HeldKeys.set(o.keyRight, false);
				HeldKeys.set(o.keyUp, true);
				HeldKeys.set(o.keySprint, true);
			}
			default -> l.next(20);
		}
	}

	/** Ticks until sinking back to this height, falling as the game has it. */
	private static int ticksToLand(LocalPlayer p, double top) {
		double y = p.getY(), vy = p.getDeltaMovement().y;
		int n = 0;
		while (n < 40) {
			y += vy;
			vy = (vy - 0.08) * 0.98;
			n++;
			if (vy < 0 && y <= top) break;
		}
		return n;
	}

	/**
	 * In the air: 1 to strafe left, -1 right, 0 neither, by where coasting would come down beside the middle of the
	 * landing. Left of the look is (cos yaw, sin yaw), as the game turns the strafe key into movement.
	 */
	private static int airStrafe(LocalPlayer p, Vec3 aim, int n, AABB box, boolean pushing) {
		Vec3 v = p.getDeltaMovement();
		double k = 0, f = 1;
		for (int i = 0; i < n; i++) { k += f; f *= 0.91; }
		double qx = p.getX() + v.x * k, qz = p.getZ() + v.z * k;
		double ex = aim.x - qx, ez = aim.z - qz;
		double yaw = Math.toRadians(p.getYRot()), lx = Math.cos(yaw), lz = Math.sin(yaw);
		double left = ex * lx + ez * lz;
		if (Math.abs(left) < 0.06) return 0;
		// With the run key down a strafe key costs reach: the game spreads the air's push over both keys, 0.018 a tick
		// forward where it gave 0.0255. That took the last few hundredths off two jumps at the parkour that only just
		// reached the next block (24 Oct 2026). So while pushing on the buddy strafes only when coasting sideways would
		// take the buddy off the landing: the middle more than 0.15 past its side.
		if (pushing && box != null && STRAFE_FIX) {
			double cx = (box.minX + box.maxX) / 2, cz = (box.minZ + box.maxZ) / 2;
			double half = 0.5 * (Math.abs(box.getXsize() * lx) + Math.abs(box.getZsize() * lz));
			if (Math.abs((cx - qx) * lx + (cz - qz) * lz) < half + 0.15) return 0;
		}
		return left > 0 ? 1 : -1;
	}

	/**
	 * What strafing costs a jump in reach along the line, for a sideways correction of {@code need} in {@code n} ticks of
	 * air: each tick of strafe with the run key down gives 0.0071 less push forward, and what is lost then is lost for
	 * every tick after (0.91 kept). The whole flight strafed costs the most; a correction of part of what strafing can do
	 * costs that part.
	 */
	private static double strafeLoss(double need, double n) {
		if (need <= 0 || !STRAFE_FIX) return 0;
		double full = 0.0071 / 0.09 * Math.max(0, n - (1 - Math.pow(0.91, n)) / 0.09);
		double cap = 2 * strafeCap(n);
		return cap <= 0 ? full : full * Math.min(1, need / cap);
	}

	/**
	 * How far the strafe keys move the buddy sideways in this many ticks of air, taken at half: diagonal input gives 0.018 a
	 * tick to the side, kept 0.91 a tick.
	 */
	private static double strafeCap(double n) {
		double a = 0.018;
		return 0.5 * a / 0.09 * Math.max(0, n - (1 - Math.pow(0.91, n)) / 0.09);
	}

	/** Does the buddy stand on the landing: the middle within 0.3 of its top, at its height? */
	private static boolean onLanding(LocalPlayer p, Leap l) {
		return p.getX() > l.box.minX - 0.3 && p.getX() < l.box.maxX + 0.3 && p.getZ() > l.box.minZ - 0.3
			&& p.getZ() < l.box.maxZ + 0.3 && Math.abs(p.getY() - l.top) < 0.6;
	}

	/**
	 * Would a jump taken in the next tick, at the present speed, come down on the landing? Along the line to its middle:
	 * the first tick carries the speed there is, 0.1274 of sprinting on the ground and the jump's 0.2, after that the air
	 * with the key down at the most and braking at the least, and that span has to meet the stretch of the line where the
	 * buddy's middle may come down and still stand (the landing widened by 0.3, less a margin). The speed across the line
	 * must not drift the buddy off it on the way.
	 */
	private static String takeOffWhy = "";
	/**
	 * The take-off is weighed in the landing tick: the jump then has to come down {@link #EARLY_SPARE} further onto the
	 * landing than the nearest place it may, or a tick of running comes first, as before. Without that spare the buddy took off on the
	 * landing tick for jumps that only just reached the next block's edge, and fell four laps of five (23 Oct 2026).
	 */
	private static boolean landingTick;
	private static double EARLY_SPARE = 0.15;
	/**
	 * On since 24 Oct 2026: off while the take-off arithmetic was too hopeful (23 Oct 2026), on once the flights were
	 * measured tick by tick and the reach that strafing costs was counted. {@code "early": false} in a goto turns it off.
	 */
	private static boolean EARLY = true;
	/** What canTakeOff worked out for the jump it allowed: the span of the landing, and the nearest and farthest flight. */
	private static double predIn0, predIn1, predNear, predFar;

	/**
	 * A jump that may go in the landing tick: a plain one, from a whole block onto a whole block, no running jump on
	 * carried speed (the iron bars: with the early take-off the buddy came down on the bar in between and found no way on)
	 * and no ladder, nor a jump after it that lives on the speed carried. Everything else keeps its tick of running first.
	 */
	private static boolean plainHop(Level level, Leap from, BlockPos to) {
		if (move(idx) != JUMP || from.ladder || !wide(from.box)) return false;
		AABB box = landing(level, to);
		if (box == null || !wide(box) || ladderCell(level, to)) return false;
		return !(idx + 1 < route.size() && move(idx + 1) == RUN);
	}

	/** On something narrower than a wall post (an iron bar), with a jump after it: see the landing in leapTick. */
	private static boolean narrowChain(Leap l) {
		if (!NARROW || !POSTS || l.ladder || wide(l.box) || route == null || idx + 1 >= route.size()) return false;
		byte after = move(idx + 1);
		return after != WALK && !isRound(after);
	}
	private static boolean NARROW = true;

	/**
	 * In the landing tick on a narrow top: would a running jump, one tick of running from here, reach the next landing
	 * (route at idx, the jump after this one)? The tick worked out as the game does it: the speed there is and 0.1274 of sprinting
	 * toward the landing, 0.546 of it kept; then canTakeOff's arithmetic from there, with a spare of 0.15.
	 */
	private static boolean runsOffNarrow(Level level, LocalPlayer p) {
		if (route == null || idx >= route.size()) return false;
		BlockPos n = route.get(idx);
		AABB box = landing(level, n);
		if (box == null || ladderCell(level, n)) return false;
		Vec3 aim = new Vec3((box.minX + box.maxX) / 2, box.maxY, (box.minZ + box.maxZ) / 2);
		Vec3 v = p.getDeltaMovement();
		double ux = aim.x - p.getX(), uz = aim.z - p.getZ(), r = Math.hypot(ux, uz);
		if (r < 0.3) return false;
		ux /= r; uz /= r;
		// the run's push goes along the look; the next tick turns 35 degrees more before the take-off, which has to
		// be within 15 of the landing (phase 8)
		double yaw = Math.toRadians(p.getYRot()), fx = -Math.sin(yaw), fz = Math.cos(yaw);
		double off = Math.toDegrees(Math.atan2(fx * uz - fz * ux, fx * ux + fz * uz));
		if (Math.abs(off) > 45) { takeOffWhy = "looks " + Math.round(off); return false; }
		double ax = v.x + 0.1274 * fx, az = v.z + 0.1274 * fz;
		double left = Math.signum(off) * Math.max(0, Math.abs(off) - 35);
		boolean ok = takeOff(p.getX() + ax, p.getY(), p.getZ() + az, ax * 0.546, az * 0.546, true, left, box, box.maxY, aim, 0.15);
		if (trace) Exchange.emit("jump", "narrow", ok, "tick", ticks, "x", round3(p.getX()), "v", round3(Math.hypot(v.x, v.z)),
			"goal", n.getX() + "," + n.getY() + "," + n.getZ(), "reason", takeOffWhy);
		return ok;
	}

	private static boolean whole(AABB b) { return b.getXsize() >= 0.9 && b.getZsize() >= 0.9; }

	/**
	 * Wide enough to run on from and jump again at once: a whole block, or a wall post (half a block). Not an iron bar
	 * (an eighth): there the early take-off put the buddy on the bar in between, with no way on (23 Oct 2026). With
	 * {@code "posts": false} in a goto, whole blocks only, as before 24 Oct 2026.
	 */
	private static boolean wide(AABB b) { return POSTS ? b.getXsize() >= 0.4 && b.getZsize() >= 0.4 : whole(b); }
	private static boolean POSTS = true;

	private static boolean canTakeOff(LocalPlayer p, Leap l, Vec3 aim, double yawErr) {
		Vec3 v = p.getDeltaMovement();
		return takeOff(p.getX(), p.getY(), p.getZ(), v.x, v.z, p.isSprinting(), yawErr, l.box, l.top, aim, landingTick ? EARLY_SPARE : 0);
	}

	/** canTakeOff from a place and speed of one's own: where the buddy stands, or where a tick of running will take the buddy. */
	private static boolean takeOff(double px, double py, double pz, double vx, double vz, boolean sprinting, double yawErr,
			AABB lbox, double ltop, Vec3 aim, double spare) {
		double ux = aim.x - px, uz = aim.z - pz, r = Math.hypot(ux, uz);
		takeOffWhy = "too close";
		if (r < 0.3) return false;
		ux /= r;
		uz /= r;
		double along = vx * ux + vz * uz, across = -vx * uz + vz * ux;
		double err = Math.toRadians(yawErr);
		double first = along + (sprinting ? 0.1274 + 0.2 * Math.cos(err) : 0.098);
		across = Math.abs(across) + (sprinting ? 0.2 * Math.abs(Math.sin(err)) : 0);
		double rise = ltop - py;
		double[] far = flight(first, rise, 0.02548), near = flight(first, rise, -0.0196);
		takeOffWhy = "too high";
		if (far == null || near == null) return false;
		double[] in = through(px, pz, ux, uz, lbox, 0.22);
		takeOffWhy = "line misses";
		if (in == null) return false;
		double drift = across * (1 - Math.pow(0.91, far[1])) / 0.09;
		double bare = Math.min(lbox.getXsize(), lbox.getZsize()) / 2 + 0.15;
		// drift past what the landing can take unsteered is steered out in the air, which costs reach (airStrafe)
		double reach = far[0] - strafeLoss(drift - bare, far[1]);
		takeOffWhy = String.format(java.util.Locale.ROOT, "in %.2f-%.2f near %.2f far %.2f reach %.2f", in[0], in[1], near[0], far[0], reach);
		if (Math.max(in[0], near[0]) + spare > Math.min(in[1], reach)) return false;
		predIn0 = in[0]; predIn1 = in[1]; predNear = near[0]; predFar = reach;
		double half = Math.min(lbox.getXsize(), lbox.getZsize()) / 2 + 0.22 + strafeCap(far[1]);
		takeOffWhy = String.format(java.util.Locale.ROOT, "drift %.2f of %.2f", drift, half);
		return drift < half;
	}

	/**
	 * Into a ladder: the pillar behind it stops the buddy, so too far is never a danger, and too short is. A running jump
	 * from here, pushing on through the air, has to get the buddy's middle 0.4 into the ladder's block before sinking to
	 * a block above its foot. As late as it goes, so that the ladder is caught high: when the jump from here gets there
	 * before sinking below the take-off, or when this is the last tick on the edge.
	 */
	private static boolean ladderTakeOff(LocalPlayer p, Leap l, Vec3 aim, boolean lastTick) {
		Vec3 v = p.getDeltaMovement();
		double ux = aim.x - p.getX(), uz = aim.z - p.getZ(), r = Math.hypot(ux, uz);
		takeOffWhy = "too close";
		if (r < 0.3) return false;
		ux /= r;
		uz /= r;
		double along = v.x * ux + v.z * uz, across = -v.x * uz + v.z * ux;
		double first = along + (p.isSprinting() ? 0.1274 + 0.2 : 0.098);
		double[] in = through(p.getX(), p.getZ(), ux, uz, l.box, 0.0);
		takeOffWhy = "line misses";
		if (in == null) return false;
		double need = in[0] + 0.4;
		double[] level = flight(first, 0.0, 0.02548), low = flight(first, l.foot + 1.0 - p.getY(), 0.02548);
		takeOffWhy = String.format(java.util.Locale.ROOT, "ladder needs %.2f level %.2f low %.2f", need,
			level == null ? 0 : level[0], low == null ? 0 : low[0]);
		boolean high = level != null && level[0] >= need, reaches = low != null && low[0] >= need;
		if (!(high || (lastTick && reaches))) return false;
		double drift = Math.abs(across) * (1 - Math.pow(0.91, (high ? level : low)[1])) / 0.09;
		return drift < 0.4;
	}

	/**
	 * Landed, with one walk on to come: run on, without the stop, when that cell lies straight ahead along the jump at
	 * the same height (a block of two, a checkpoint), so the speed goes into what follows.
	 */
	private static boolean runOn(Level level, LocalPlayer p) {
		if (route == null || idx + 1 >= route.size() || move(idx + 1) != WALK) return false;
		BlockPos a = route.get(idx), b = route.get(idx + 1);
		double fa = floorY(level, a, false), fb = floorY(level, b, false);
		if (Double.isNaN(fa) || Double.isNaN(fb) || Math.abs(fa - fb) > 0.1) return false;
		Vec3 v = p.getDeltaMovement();
		double vx = v.x, vz = v.z, sp = Math.hypot(vx, vz);
		if (sp < 0.05) return true;
		double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ(), dl = Math.hypot(dx, dz);
		if ((vx * dx + vz * dz) / (sp * dl) > 0.7) return true;
		// another way, but the floor goes on a block further where the speed carries: no edge to slide off
		BlockPos ahead = BlockPos.containing(p.getX() + vx / sp * 1.2, p.getY() + 0.5, p.getZ() + vz / sp * 1.2);
		double fh = floorY(level, ahead, false);
		return !Double.isNaN(fh) && Math.abs(fh - fa) < 0.1;
	}

	/**
	 * How far the buddy's middle travels before the tick of coming down {@code rise} above the take-off, with this
	 * first tick and then {@code accel} a tick in the air, and in how many ticks; null when the jump does not get that high.
	 * reach() with a first tick of one's own.
	 */
	private static double[] flight(double first, double rise, double accel) {
		double speed = first, climb = 0.42, height = 0, travelled = 0;
		for (int tick = 1; tick < 60; tick++) {
			double before = height;
			height += climb;
			if (height < before && height < rise) return new double[] { travelled, tick };
			if (height < before && before < rise) return null;
			travelled += speed;
			speed = speed * (tick == 1 ? 0.546 : 0.91) + accel;
			climb = (climb - 0.08) * 0.98;
		}
		return null;
	}

	/** Where a line from (x, z) along (ux, uz) runs through the box widened by e: {enter, leave} from (x, z), or null. */
	private static double[] through(double x, double z, double ux, double uz, AABB box, double e) {
		double lo = 0, hi = Double.MAX_VALUE;
		double[][] axes = { { x, ux, box.minX - e, box.maxX + e }, { z, uz, box.minZ - e, box.maxZ + e } };
		for (double[] a : axes) {
			if (Math.abs(a[1]) < 1e-9) {
				if (a[0] < a[2] || a[0] > a[3]) return null;
				continue;
			}
			double t1 = (a[2] - a[0]) / a[1], t2 = (a[3] - a[0]) / a[1];
			lo = Math.max(lo, Math.min(t1, t2));
			hi = Math.min(hi, Math.max(t1, t2));
		}
		return lo <= hi ? new double[] { lo, hi } : null;
	}

	/** After the jump under way comes a running one that has no run-up behind it. */
	private static boolean carriedNext(Level level, Leap l) {
		if (route == null || idx + 1 >= route.size() || move(idx + 1) != RUN) return false;
		BlockPos n = route.get(idx + 1);
		double f = floorY(level, l.to, false);
		return Double.isNaN(f) || runway(level, l.to, f, n.getX() - l.to.getX(), n.getZ() - l.to.getZ()) < 2;
	}

	/**
	 * Another jump straight after this one, the same way or near it, off a whole block: then the middle of the landing is
	 * no place to aim for. Braking toward it took the key off every other tick, and with the key the sprint, so the buddy came
	 * down at 0.19 a tick and without the sprint where the take-off had 0.26 (24 Oct 2026, the parkour's first jump); the next
	 * take-off then had to make up for it on the ground.
	 */
	private static boolean chainOn(Level level, Leap l) {
		if (!CHAIN || route == null || idx + 1 >= route.size() || l.ladder || !wide(l.box)) return false;
		byte after = move(idx + 1);
		if (after == WALK || isRound(after)) return false;
		BlockPos n = route.get(idx + 1);
		double dx = n.getX() - l.to.getX(), dz = n.getZ() - l.to.getZ(), d = Math.hypot(dx, dz);
		return d > 0 && (dx * l.ux + dz * l.uz) / d > 0.7;
	}

	/**
	 * The landing and the walk after it as one floor (24 Oct 2026, from a fast player's runs). Onto a
	 * checkpoint's platform the jump lands on its middle row, the farthest it reaches, and the next jump goes from its far
	 * row: the buddy braked in the air toward the middle of the landing and ran across at 0.15 a tick, where a player keeps the
	 * run key down and crosses at 0.28 (4 to 9 ticks on each platform; the fast player is ahead there). The cells the route walks
	 * after the landing, along the jump's own way and at the landing's height, up to the cell the next jump goes from:
	 * one box with the landing, aimed along as far as that cell's middle ({@link #onFar}), and a landing anywhere on it is no
	 * miss (the buddy shot past the middle row at cp7 and stopped crouched for "ernaast", 8 ticks). Only on a {@code ga}, as the racing line; null
	 * when the route goes on another way.
	 */
	private static AABB walkOn(Level level, Leap l) {
		if (!WALK_ON || spots.isEmpty() || route == null || l.ladder || l.round != 0 || !wide(l.box) || (l.ux == 0 && l.uz == 0)) return null;
		AABB box = l.box;
		BlockPos prev = l.to;
		int cells = 0;
		for (int i = idx + 1; i < route.size() && move(i) == WALK && cells < 3; i++) {
			BlockPos c = route.get(i);
			double dx = c.getX() - prev.getX(), dz = c.getZ() - prev.getZ(), d = Math.hypot(dx, dz);
			if (d == 0 || (dx * l.ux + dz * l.uz) / d < 0.7) break;
			double f = floorY(level, c, false);
			AABB s = support(level, c);
			if (Double.isNaN(f) || Math.abs(f - l.top) > 0.05 || s == null || !whole(s)) break;
			box = box.minmax(s);
			prev = c;
			cells++;
		}
		return cells == 0 ? null : box;
	}

	/**
	 * Where the route goes on from after coming down in a cell of the walk after the landing ({@link #walkOn}): that
	 * cell's index, or for the cell the next jump goes from, the index of that jump's landing, so that it goes in the
	 * landing tick. 0 when not on the walk, or still on the landing itself.
	 */
	private static int walkedOn(Level level, LocalPlayer p, Leap l) {
		if (walkOn(level, l) == null) return 0;
		int bx = p.getBlockX(), bz = p.getBlockZ();
		BlockPos prev = l.to;
		for (int i = idx + 1; i < route.size() && move(i) == WALK && i <= idx + 3; i++) {
			BlockPos c = route.get(i);
			double dx = c.getX() - prev.getX(), dz = c.getZ() - prev.getZ(), d = Math.hypot(dx, dz);
			if (d == 0 || (dx * l.ux + dz * l.uz) / d < 0.7 || Math.abs(floorY(level, c, false) - l.top) > 0.05) break;
			if (c.getX() == bx && c.getZ() == bz) return i + 1 < route.size() && move(i + 1) != WALK && !isRound(move(i + 1)) ? i + 1 : i;
			prev = c;
		}
		return 0;
	}

	/** Down on the landing and the walk after it ({@link #walkOn}), as onLanding is for the landing alone. */
	private static boolean onWalkOn(LocalPlayer p, Leap l, AABB on) {
		return on != null && p.getX() > on.minX - 0.3 && p.getX() < on.maxX + 0.3 && p.getZ() > on.minZ - 0.3
			&& p.getZ() < on.maxZ + 0.3 && Math.abs(p.getY() - l.top) < 0.6;
	}

	/**
	 * Along the jump's way through the landing's middle, as far on as the middle of the cell the walk after it ends in,
	 * where the next jump goes from. Not its far side: at cp3 the buddy came down on the platform's very edge, stopped there
	 * crouched with no room left to run, and the jump to 1571 fell short in four laps of five (24 Oct 2026).
	 */
	private static Vec3 onFar(Leap l, Vec3 aim, AABB on) {
		double far = (l.ux > 0 ? on.maxX - aim.x : l.ux < 0 ? aim.x - on.minX : 0) * Math.abs(l.ux)
			+ (l.uz > 0 ? on.maxZ - aim.z : l.uz < 0 ? aim.z - on.minZ : 0) * Math.abs(l.uz);
		return aim.add(l.ux * (far - 0.5), 0, l.uz * (far - 0.5));
	}

	/** Switch for walkOn, to measure with and without: {@code "platform": false} in a goto. */
	private static boolean WALK_ON = true;

	/** Switch for chainOn, to measure with and without: {@code "chain": false} in a goto. */
	private static boolean CHAIN = true;
	/** Switch for running on from the landing tick: {@code "run_on": false} in a goto. */
	private static boolean RUN_ON = true;
	/** Switch for strafing only when needed and counting its cost: {@code "strafe": false} in a goto. */
	private static boolean STRAFE_FIX = true;

	/** Near the far side of the landing along the jump, 0.15 inside its edge: the buddy lands at full speed and still on it. */
	private static Vec3 onAim(LocalPlayer p, Leap l, Vec3 aim) {
		double half = 0.5 * (Math.abs((l.box.maxX - l.box.minX) * l.ux) + Math.abs((l.box.maxZ - l.box.minZ) * l.uz));
		return aim.add(l.ux * (half - 0.15), 0, l.uz * (half - 0.15));
	}

	/** The far side of the landing along the way, as far on as the buddy still stands on it (the middle 0.25 past its edge). */
	private static Vec3 farAim(LocalPlayer p, Leap l, Vec3 aim) {
		// along the jump's own way: the line from the buddy's place turned round once over the middle, and near an iron
		// bar a tenth beside its middle made it a slant, which sent the buddy on past the bar (24 Oct 2026)
		double ux = l.ux, uz = l.uz;
		if (ux == 0 && uz == 0) {
			ux = aim.x - p.getX(); uz = aim.z - p.getZ();
			double r = Math.hypot(ux, uz);
			if (r < 0.05) return aim;
			ux /= r; uz /= r;
		}
		double half = 0.5 * (Math.abs((l.box.maxX - l.box.minX) * ux) + Math.abs((l.box.maxZ - l.box.minZ) * uz));
		return aim.add(ux * (half + 0.25), 0, uz * (half + 0.25));
	}

	/**
	 * What racePlan chose for the rest of a flight: the run key (1), let go (0) or brake (-1); strafe left (1), right
	 * (-1) or not; whether to turn toward the next landing; and whether the next jump then goes in the landing tick (0)
	 * or after a tick of running (1).
	 */
	private record Line(int fwd, int side, boolean turn, int tier, Vec3 next) {}

	/** The racing line on or off, to measure with and without: {@code "line": false} in a goto. */
	private static boolean LINE = true;
	/** How far to turn toward the next landing a tick in the air on the racing line, in degrees. */
	private static final float LINE_TURN = 20f;

	/**
	 * The racing line (24 Oct 2026: a fast player skips the obsidian jump; how can the buddy get faster?).
	 * Aiming at the middle of each landing, or near its far side along the jump, the buddy came down where the next jump
	 * did not reach yet: 32 ticks more on the ground in a lap than one a jump, 21 of them outside the wall, which is what
	 * the fast player is ahead there. Of the refused take-offs in a landing tick, nearly all said that the reach fell short
	 * of the landing ("reach" under "in"). So each tick of the flight racePlan tries every way of holding the keys for the
	 * rest of it (the run key, let go or brake; strafe left, right or not; turning toward the next landing or not), works
	 * out with the game's numbers where and how fast each comes down, and takes the one from which the next jump goes in
	 * the landing tick (takeOff with the early spare), the fastest along it with some room to spare; failing that, the
	 * one from which it goes after a tick of running. None of either: the old way. Only on a {@code ga}, between plain
	 * jumps off and onto blocks or wall posts: no ladder, no run-up, no jump round a pillar, no iron bar.
	 */
	private static Line racePlan(Level level, LocalPlayer p, Leap l) {
		if (!LINE || spots.isEmpty() || route == null || idx + 1 >= route.size() || l.ladder || l.round != 0 || l.corner != null || !wide(l.box)) return null;
		if (move(idx) != JUMP || move(idx + 1) != JUMP) return null;
		BlockPos nc = route.get(idx + 1);
		AABB nb = landing(level, nc);
		if (nb == null || !wide(nb) || ladderCell(level, nc)) return null;
		Vec3 next = new Vec3((nb.minX + nb.maxX) / 2, nb.maxY, (nb.minZ + nb.maxZ) / 2);
		Vec3 v = p.getDeltaMovement();
		Line best = null;
		double bestScore = -1e9;
		for (int fwd = 1; fwd >= -1; fwd--) for (int side = -1; side <= 1; side++) for (int t = 0; t < 2; t++) {
			boolean turn = t == 1;
			double x = p.getX(), y = p.getY(), z = p.getZ(), vx = v.x, vy = v.y, vz = v.z;
			float yaw = p.getYRot();
			// letting go of the run key ends the sprint, and with it the air's 0.026 and the next jump's push
			boolean sprint = p.isSprinting() && fwd > 0, down = false;
			// the game's flight: the keys' push (0.98 of the input, kept to a length of one) along the look, the move, then
			// 0.91 of the speed kept; down in the tick of sinking to the landing's top, as ticksToLand
			for (int i = 0; i < 40 && !down; i++) {
				if (turn) yaw += Mth.clamp(Mth.wrapDegrees(yawTo(x, z, next) - yaw), -LINE_TURN, LINE_TURN);
				double len = Math.hypot(fwd, side);
				if (len > 0) {
					double m = 0.98 * len, k = (m > 1 ? 1 / m : 1) * 0.98 * (sprint ? 0.026 : 0.02), r = Math.toRadians(yaw);
					vx += -Math.sin(r) * fwd * k + Math.cos(r) * side * k;
					vz += Math.cos(r) * fwd * k + Math.sin(r) * side * k;
				}
				x += vx;
				z += vz;
				y += vy;
				vy = (vy - 0.08) * 0.98;
				down = vy < 0 && y <= l.top;
				vx *= 0.91;
				vz *= 0.91;
			}
			// down on it, with a tenth to spare of the 0.3 the buddy's middle may be past its edge
			if (!down || x < l.box.minX - 0.2 || x > l.box.maxX + 0.2 || z < l.box.minZ - 0.2 || z > l.box.maxZ + 0.2) continue;
			// in the landing tick the buddy turns up to 35 degrees toward the next landing (phase 8), and jumps within 15 of it
			double err = Math.max(0, Math.abs(Mth.wrapDegrees(yawTo(x, z, next) - yaw)) - 35);
			double ux = next.x - x, uz = next.z - z, ul = Math.hypot(ux, uz);
			if (err >= 15 || ul < 0.3) continue;
			ux /= ul;
			uz /= ul;
			int tier;
			double slack;
			if (takeOff(x, l.top, z, vx, vz, sprint, err, nb, nb.maxY, next, EARLY_SPARE)) {
				tier = 0;
				slack = Math.min(predIn1, predFar) - Math.max(predIn0, predNear) - EARLY_SPARE;
			} else {
				// a tick of running first: 0.1274 of sprinting toward the landing, then the ground's 0.546 (canTakeOff's numbers)
				double ax = vx + 0.1274 * ux, az = vz + 0.1274 * uz, gx = x + ax, gz = z + az;
				if (gx < l.box.minX - 0.25 || gx > l.box.maxX + 0.25 || gz < l.box.minZ - 0.25 || gz > l.box.maxZ + 0.25) continue;
				if (!takeOff(gx, l.top, gz, ax * 0.546, az * 0.546, true, 0, nb, nb.maxY, next, 0)) continue;
				tier = 1;
				slack = Math.min(predIn1, predFar) - Math.max(predIn0, predNear);
			}
			double score = -10 * tier + vx * ux + vz * uz + 0.25 * Math.min(slack, 0.3);
			if (score > bestScore) { bestScore = score; best = new Line(fwd, side, turn, tier, next); }
		}
		return best;
	}

	private static float yawTo(double x, double z, Vec3 at) { return (float) Math.toDegrees(Math.atan2(-(at.x - x), at.z - z)); }

	/**
	 * In the air: 1 to run on, 0 to let go, -1 to brake (back), by where each comes down along the line to the middle of
	 * the landing. Worked out with the game's numbers: falling as it does until sinking to the landing's top, 0.91 of
	 * the speed kept each tick, and 0.02548 a tick more or less with the key down.
	 */
	private static int airControl(LocalPlayer p, double top, Vec3 aim, double wx, double wz) {
		Vec3 v = p.getDeltaMovement();
		double y = p.getY(), vy = v.y;
		int n = 0;
		while (n < 40) {
			y += vy;
			vy = (vy - 0.08) * 0.98;
			n++;
			if (vy < 0 && y <= top) break;
		}
		double ux = aim.x - p.getX(), uz = aim.z - p.getZ(), r = Math.hypot(ux, uz);
		if (r < 0.05) return -1;   // above the middle already: brake
		// Measured along the jump's own way when it has one: the line from the buddy's place turns slant near a narrow
		// landing (a tenth beside an iron bar's middle) and round once over it, and along that line the speed looked like
		// little, so the buddy ran on past the bar (24 Oct 2026). Along the way, the distance to the aim is signed.
		if (wx != 0 || wz != 0) { r = ux * wx + uz * wz; ux = wx; uz = wz; }
		else { ux /= r; uz /= r; }
		double coast = 0, fwd = 0, back = 0, vc = v.x * ux + v.z * uz, vf = vc, vb = vc;
		for (int i = 0; i < n; i++) {
			coast += vc; vc *= 0.91;
			vf += 0.02548; fwd += vf; vf *= 0.91;
			vb -= 0.02; back += vb; vb *= 0.91;
		}
		if (fwd <= r) return 1;
		double ef = fwd - r, ec = Math.abs(coast - r), eb = Math.abs(back - r);
		if (ef <= ec && ef <= eb) return 1;
		return ec <= eb ? 0 : -1;
	}

	/** Turn toward the point, at most maxStep degrees this tick, looking a little down. True once roughly facing it. */
	private static boolean face(LocalPlayer p, Vec3 at, float maxStep) {
		float yaw = (float) Math.toDegrees(Math.atan2(-(at.x - p.getX()), at.z - p.getZ()));
		float delta = Mth.wrapDegrees(yaw - p.getYRot());
		float pitch = p.getXRot() + Mth.clamp(25f - p.getXRot(), -6f, 6f);
		MouseTurn.toward(p, p.getYRot() + Mth.clamp(delta, -maxStep, maxStep), pitch);
		return Math.abs(Mth.wrapDegrees(yaw - p.getYRot())) < 8f;
	}

	/** The far end of the platform, behind the buddy, away from the jump: where a run-up starts. Up to three cells back. */
	private static Vec3 runStart(Level level, LocalPlayer p, Vec3 aim) {
		double dx = aim.x - p.getX(), dz = aim.z - p.getZ();
		boolean alongX = Math.abs(dx) >= Math.abs(dz);
		int sx = alongX ? -(int) Math.signum(dx) : 0, sz = alongX ? 0 : -(int) Math.signum(dz);
		BlockPos at = p.blockPosition(), far = at;
		for (int k = 1; k <= 3; k++) {
			BlockPos c = at.offset(sx * k, 0, sz * k);
			double f = floorY(level, c, false);
			if (Double.isNaN(f) || Math.abs(f - p.getY()) > 0.1) break;
			far = c;
		}
		return Vec3.atBottomCenterOf(far);
	}

	/** Would the buddy still stand on something this much further on? */
	private static boolean supported(Level level, LocalPlayer p, Vec3 ahead) {
		AABB box = p.getBoundingBox().move(ahead.x, 0, ahead.z);
		return !level.noCollision(p, new AABB(box.minX, box.minY - 0.1, box.minZ, box.maxX, box.minY, box.maxZ));
	}

	/**
	 * Landed: the next cell. Running on into another jump, the keys stay down; after a careful landing the crouch stays
	 * (the next jump starts as it likes, and a walk lets go of the crouch itself).
	 */
	private static void doneLeap(Minecraft mc, boolean running) {
		leap = null;
		idx++;
		idxSince = 0;
		cellsWalked++;
		previous = null;
		HeldKeys.set(mc.options.keyDown, false);
		HeldKeys.set(mc.options.keyLeft, false);
		HeldKeys.set(mc.options.keyRight, false);
		if (running) return;
		HeldKeys.set(mc.options.keyUp, false);
		HeldKeys.set(mc.options.keySprint, false);
	}

	/** The jump did not come off: say so, plan anew from wherever the buddy is; a landing missed three times is left out a minute. */
	private static void failLeap(LocalPlayer p, String why) {
		BlockPos to = leap.to;
		int n = leapFails.merge(to, 1, Integer::sum);
		Exchange.emit(spots.isEmpty() ? "follow" : "goto", "action", "jump failed", "reason", why, "times", n,
			"x", to.getX(), "y", to.getY(), "z", to.getZ());
		// three times missed: a minute another way, then another try
		if (n >= 3) { avoid.put(to, System.currentTimeMillis() + 60_000); leapFails.remove(to); }
		release();
		clearRoute();
	}

	// ---------- planning ----------

	private record Node(BlockPos pos, int f) {}

	/** The last plan's search, for the event {@code no path}: cells looked at, milliseconds, jumps looked for. */
	private static int lastNodes, lastLooks;
	private static long lastMs;

	/**
	 * A* over the cells you can be in. Leads to the goal, or else to the cell nearest to it that can be reached (the rest
	 * is not loaded yet, or closed off): from there it plans on. Null when that is the cell you are already in.
	 */
	private static List<BlockPos> plan(Level level, BlockPos start, BlockPos goal, boolean exact) {
		return plan(level, start, goal, exact, null, WALK);
	}

	/** As above, from a cell entered from {@code cameFrom} by {@code cameHow}: a jump from it may go on the speed brought along (carried). */
	private static List<BlockPos> plan(Level level, BlockPos start, BlockPos goal, boolean exact, BlockPos cameFrom, byte cameHow) {
		feetMemo.clear();
		planHow.clear();
		planFrom.clear();
		if (cameFrom != null) { planFrom.put(start, cameFrom); planHow.put(start, cameHow); }
		jumpLooks = JUMP_LOOKS;
		Map<BlockPos, Integer> cost = new HashMap<>();
		Map<BlockPos, BlockPos> from = new HashMap<>();
		PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingInt(Node::f));
		Set<BlockPos> closed = new HashSet<>();
		cost.put(start, 0); from.put(start, start);
		open.add(new Node(start, guess(start, goal)));
		BlockPos best = start;
		int bestGuess = guess(start, goal);
		// beside a player on land the plan ends on land, not afloat beside the bank (18 Oct 2026): land
		// always goes before water
		boolean landGoal = !waterCell(level, goal), reached = false;
		BlockPos bestWet = null;
		int wetGuess = Integer.MAX_VALUE;
		long started = System.nanoTime();
		int expanded = 0;
		while (!open.isEmpty() && closed.size() < MAX_NODES) {
			// a time limit as well as the cells: a plan never holds the game up for more than 30 ms
			if ((++expanded & 255) == 0 && System.nanoTime() - started > 30_000_000L) break;
			BlockPos cur = open.poll().pos();
			if (!closed.add(cur)) continue;
			int g = guess(cur, goal);
			boolean wetEnd = landGoal && waterCell(level, cur);
			if (g < bestGuess && !wetEnd) { bestGuess = g; best = cur; }
			if (g < wetGuess && wetEnd) { wetGuess = g; bestWet = cur; }
			if (exact ? cur.equals(goal) : !wetEnd && Math.abs(cur.getX() - goal.getX()) <= 1 && Math.abs(cur.getZ() - goal.getZ()) <= 1 && Math.abs(cur.getY() - goal.getY()) <= 1) { best = cur; reached = true; break; }
			int base = cost.get(cur);
			double fc = feet(level, cur, goal, exact);
			if (Double.isNaN(fc)) fc = cur.getY();   // where you start may be no cell (in a jump, on a chest)
			boolean wet = waterCell(level, cur), ladder = ladderCell(level, cur);
		// on a block the buddy is to put down, or on a full block: from there the buddy can dig, climb or bridge
			byte came = planHow.getOrDefault(cur, WALK);
			boolean placed = came == PILLAR || came == BRIDGE;
			if (placed) fc = cur.getY();
			boolean firm = !wet && !ladder && Math.abs(fc - cur.getY()) < 0.05 && (placed || PathShape.sturdy(level, cur.below()));
			if (firm && PathShape.placeOk && passable(level, cur.above(), false) && passable(level, cur.above(2), false))
				relax(open, closed, cost, from, cur, cur.above(), base + 45, goal, PILLAR);
			// straight up and down: swimming, or on a ladder or vine
			if (wet || ladder) {
				BlockPos up = cur.above(), down = cur.below();
				if (waterCell(level, up) || ladderCell(level, up)) relax(open, closed, cost, from, cur, up, base + 14, goal);
				if (waterCell(level, down) || ladderCell(level, down)) relax(open, closed, cost, from, cur, down, base + 12, goal);
			} else if (ladderCell(level, cur.below())) relax(open, closed, cost, from, cur, cur.below(), base + 12, goal);   // onto a ladder from the top
			// over a gap: a jump from the edge, or with a run-up
			if (leapsOk && !wet && !ladder && jumpLooks > 0 && edge(level, cur, goal, exact)) {
				jumpLooks--;
				leaps(level, cur, fc, start, goal, exact, base, open, closed, cost, from);
			}
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos h = cur.relative(d);
				if (Math.abs(h.getX() - start.getX()) > range() || Math.abs(h.getZ() - start.getZ()) > range()) continue;
				boolean moved = false;
				// by the real heights: a step up to 0.6 walks (onto a slab), up to 1.25 needs a jump, lower is a drop
				for (BlockPos cand : new BlockPos[] { h, h.above() }) {
					double fh = feet(level, cand, goal, exact);
					if (Double.isNaN(fh)) continue;
					double dy = fh - fc;
					// swimming a block costs as much as walking two: land first where both go (18 Oct 2026), and not so
					// much more than the moves up and down that the search spreads through the whole sea
					int extra = (wet || waterCell(level, cand) ? 10 : 0) + (opensBy(level, cand) ? 15 : 0);
					if (Math.abs(dy) <= 0.6) { relax(open, closed, cost, from, cur, cand, base + 10 + extra, goal); moved = true; break; }
					// farmland is trampled by a fall of more than half a block: a drop onto it, or a low hop
					if (onFarmland(level, cand) && dy < 0.8) { moved = true; break; }
					// up a block: a jump, or out of the water, or off the top of a ladder
					if (dy > 0.6 && dy <= 1.25 && (wet || ladder || roomToJump(level, cur, fc, dy))) { relax(open, closed, cost, from, cur, cand, base + 18 + extra, goal); moved = true; break; }
					if (dy < -0.6 && dy >= -3.2) { relax(open, closed, cost, from, cur, cand, base + 12 + (int) (-dy * 3) + extra + overshoot(level, cand, d, -dy, goal, exact), goal); moved = true; break; }
				}
				if (moved) continue;
				if (!passable(level, h, true) || !passable(level, h.above(), false)) {
					int dig = firm ? PathShape.digCost(level, h) : -1;
					if (dig >= 0) relax(open, closed, cost, from, cur, h, base + 10 + dig, goal, DIG);
					continue;
				}
				if (firm && PathShape.placeOk && !waterCell(level, h) && passable(level, h.below(), false)
						&& level.getBlockState(h.below()).getFluidState().isEmpty())
					relax(open, closed, cost, from, cur, h, base + 40, goal, BRIDGE);
				// over an edge: up to three down onto the ground or a ladder, deeper only into water, which takes the fall
				for (int k = 1; k <= 12; k++) {
					BlockPos down = h.below(k);
					boolean water = waterCell(level, down);
					if (water || (k <= 3 && !Double.isNaN(feet(level, down, goal, exact)))) {
						if (!onFarmland(level, down)) relax(open, closed, cost, from, cur, down, base + 12 + 3 * k + (water ? 0 : overshoot(level, down, d, k, goal, exact)), goal);
						break;
					}
					if (!passable(level, down, false)) break;
				}
			}
		}
		// not there within the search: on toward the goal through the water when land gets no nearer (an island across the sea)
		if (!reached && bestWet != null && wetGuess + 30 < bestGuess) best = bestWet;
		lastNodes = closed.size(); lastLooks = JUMP_LOOKS - jumpLooks; lastMs = (System.nanoTime() - started) / 1_000_000;
		if (best.equals(start)) return null;
		List<BlockPos> cells = path(from, start, best);
		routeMoves = new ArrayList<>(cells.size());
		for (BlockPos c : cells) routeMoves.add(planHow.getOrDefault(c, WALK));
		return cells;
	}

	/** The way up to air from under water: the nearest cell at the surface, through water only. */
	private static List<BlockPos> planToAir(Level level, BlockPos start) {
		List<BlockPos> way = planToAir(level, start, true);
		return way != null ? way : planToAir(level, start, false);
	}

	/** Around the cells the buddy just got stuck at, or (when that leaves no way) through them all the same. */
	private static List<BlockPos> planToAir(Level level, BlockPos start, boolean shun) {
		feetMemo.clear();
		Map<BlockPos, BlockPos> from = new HashMap<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		from.put(start, start);
		queue.add(start);
		while (!queue.isEmpty() && from.size() < 6000) {
			BlockPos cur = queue.poll();
			if (surface(level, cur) && !cur.equals(start)) {
				List<BlockPos> cells = path(from, start, cur);
				routeMoves = new ArrayList<>(Collections.nCopies(cells.size(), WALK));
				return cells;
			}
			// up first: the shortest way to air is mostly straight up
			for (BlockPos n : new BlockPos[] { cur.above(), cur.north(), cur.south(), cur.east(), cur.west(), cur.below() }) {
				if (from.containsKey(n) || !waterCell(level, n) || (shun && avoid.containsKey(n))) continue;
				from.put(n, cur);
				queue.add(n);
			}
		}
		return null;
	}

	private static List<BlockPos> path(Map<BlockPos, BlockPos> from, BlockPos start, BlockPos end) {
		List<BlockPos> cells = new ArrayList<>();
		for (BlockPos at = end; !at.equals(start); at = from.get(at)) cells.add(at);
		Collections.reverse(cells);
		return cells;
	}

	private static void relax(PriorityQueue<Node> open, Set<BlockPos> closed, Map<BlockPos, Integer> cost, Map<BlockPos, BlockPos> from,
			BlockPos cur, BlockPos next, int k, BlockPos goal) {
		relax(open, closed, cost, from, cur, next, k, goal, WALK);
	}

	private static void relax(PriorityQueue<Node> open, Set<BlockPos> closed, Map<BlockPos, Integer> cost, Map<BlockPos, BlockPos> from,
			BlockPos cur, BlockPos next, int k, BlockPos goal, byte how) {
		if (closed.contains(next) || avoid.containsKey(next) || fenced(next) || shunned(next)) return;
		k += PathShape.danger(AfkHelperClient.mc().level, next);
		if (k >= cost.getOrDefault(next, Integer.MAX_VALUE)) return;
		cost.put(next, k);
		from.put(next, cur);
		planFrom.put(next, cur);
		planHow.put(next, how);
		// a little greedy: a path a few steps longer than the shortest, found in far fewer cells
		open.add(new Node(next, k + guess(next, goal) * 6 / 5));
	}

	/** What it costs at least to get there: ten a block, level or not. */
	private static int guess(BlockPos a, BlockPos b) {
		int flat = Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ());
		return 10 * Math.max(flat, Math.abs(a.getY() - b.getY()));
	}

	/** The cell to walk to for a spot: where you would be there, or the ground under it. */
	/**
	 * A drop where coming down a little long goes on down: the cell beyond the landing, in the direction of travel,
	 * open to the body and with no floor within the fall that is left (three blocks in all). Such a drop is taken
	 * slowly, and the plan takes another way where there is one: in a cave the buddy came down a step long and fell five and
	 * nine blocks (18 Oct 2026).
	 */
	private static int overshoot(Level level, BlockPos landing, Direction d, double fallen, BlockPos goal, boolean exact) {
		if (fallen < 1.5) return 0;
		BlockPos b = landing.relative(d);
		if (!passable(level, b, true) || !passable(level, b.above(), false)) return 0;   // a wall: the buddy stops at it
		for (int k = 0; k <= Math.max(0, (int) Math.floor(3.2 - fallen)); k++) {
			BlockPos q = b.below(k);
			if (waterCell(level, q) || !Double.isNaN(feet(level, q, goal, exact))) return 0;
			if (k > 0 && !passable(level, q, false)) return 0;
		}
		return 40;
	}

	/**
	 * A cell on land beside the player: within three blocks of the player, two up or down, not in water and not in the
	 * player's own cell; the nearest to the alt. Null where there is none.
	 */
	private static BlockPos landBeside(Level level, LocalPlayer p, Player t) {
		BlockPos at = t.blockPosition(), best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos q : BlockPos.betweenClosed(at.offset(-3, -2, -3), at.offset(3, 2, 3))) {
			double dh = Math.hypot(q.getX() + 0.5 - t.getX(), q.getZ() + 0.5 - t.getZ());
			if (dh < 1.0 || dh > 3.0) continue;
			if (waterCell(level, q) || waterCell(level, q.above()) || Double.isNaN(floorY(level, q, false))) continue;
			double d = Vec3.atBottomCenterOf(q).distanceToSqr(p.position());
			if (d < bestD) { bestD = d; best = q.immutable(); }
		}
		return best;
	}

	private static BlockPos goalCell(Level level, BlockPos at, boolean exact) {
		if (!Double.isNaN(floorY(level, at, exact)) || waterCell(level, at) || ladderCell(level, at)) return at;
		if (!Double.isNaN(floorY(level, at.above(), exact)) || waterCell(level, at.above())) return at.above();
		for (int k = 1; k <= 3; k++) {
			BlockPos b = at.below(k);
			if (!Double.isNaN(floorY(level, b, exact)) || waterCell(level, b) || ladderCell(level, b)) return b;
		}
		return at;
	}

	/** The cell you are in now: in water or on a ladder the one you are in; in a jump the ground below you. */
	private static BlockPos startCell(LocalPlayer p) {
		Level level = p.level();
		BlockPos at = p.blockPosition();
		if (waterCell(level, at) || ladderCell(level, at)) return at;
		if (p.isInWater()) {
			// head in a gap only half a block high, or floating on the surface with the feet in the cell below
			if (waterCell(level, at.above())) return at.above();
			if (waterCell(level, at.below())) return at.below();
		}
		if (!Double.isNaN(floorY(level, at, false))) return at;
		if (!Double.isNaN(floorY(level, at.above(), false)) && floorY(level, at.above(), false) - p.getY() < 0.2) return at.above();
		if (!p.onGround()) for (int k = 1; k <= 2; k++) if (!Double.isNaN(floorY(level, at.below(k), false))) return at.below(k);
		// on the edge of a cell the buddy cannot be in (the middle over a fence post, the feet on a chest beside it): a cell
		// the body also stands in, whose floor is where the feet are (21 Oct 2026, wedged by the sheep pen, "no path"
		// with one cell looked at, for good)
		for (double dx : new double[] { -0.3, 0.3 }) for (double dz : new double[] { -0.3, 0.3 }) {
			BlockPos c = BlockPos.containing(p.getX() + dx, p.getY() + 0.01, p.getZ() + dz);
			for (BlockPos q : new BlockPos[] { c, c.above() }) {
				double fy = floorY(level, q, false);
				if (!Double.isNaN(fy) && Math.abs(fy - p.getY()) < 0.3) return q;
			}
		}
		return at;
	}

	// ---------- the ground ----------

	/** Where your feet are in this cell (standing, swimming or on a ladder), NaN if you cannot be there. */
	private static double feet(Level level, BlockPos f, BlockPos goal, boolean exact) {
		Double known = feetMemo.get(f);
		if (known != null) return known;
		double y = floorY(level, f, exact && f.equals(goal));
		if (Double.isNaN(y) && (waterCell(level, f) || ladderCell(level, f))) y = f.getY();
		feetMemo.put(f.immutable(), y);
		return y;
	}

	/**
	 * The height your feet are at when you stand in this cell, or NaN if you cannot stand there: on a full block
	 * below, on a low block in the cell itself (a slab, carpet, snow) or on a tall one below (a fence or wall). A door
	 * or gate the buddy can open, and a ladder at the bottom, are no obstacle. Never in a portal, unless it is the spot
	 * the player vanished at: walking through every portal on the way would send you somewhere else.
	 */
	private static double floorY(Level level, BlockPos f, boolean portalOk) {
		BlockState here = level.getBlockState(f);
		// a pressure plate is no obstacle (the checkpoints and the finish of the parkour course are plates); a tripwire is
		if (here.getBlock() instanceof net.minecraft.world.level.block.TripWireBlock) return Double.NaN;
		if (here.is(Blocks.COBWEB)) return Double.NaN;
		// a bed is no floor to stand or walk on (21 Oct 2026: the buddy chose the top of a bed in the bedroom as a place to
		// stand, walked onto the beds and could not get off them or to the bed beside, "cannot get there" and "no path" by
		// the beds every night); the plan goes round them, as a player does, and a bed is clicked from beside it
		if (here.getBlock() instanceof AbstractBedBlock) return Double.NaN;
		if (!portalOk && (here.is(Blocks.NETHER_PORTAL) || here.is(Blocks.END_PORTAL) || here.is(Blocks.END_GATEWAY))) return Double.NaN;
		boolean through = opensBy(level, f) || climbable(here);
		VoxelShape own = through ? net.minecraft.world.phys.shapes.Shapes.empty() : here.getCollisionShape(level, f);
		double feet;
		BlockPos under;
		if (!own.isEmpty()) {
			double top = own.max(Direction.Axis.Y);
			if (top > 0.6 || own.min(Direction.Axis.Y) > 0.01) return Double.NaN;   // a full block, or something hanging
			feet = f.getY() + top; under = f;
		} else {
			if (!here.getFluidState().isEmpty()) return Double.NaN;
			BlockPos b = f.below();
			BlockState below = level.getBlockState(b);
			// the edge of a ladder is no floor (you fall past it), nor is an open door or a gate
			if (climbable(below) || openable(below)) return Double.NaN;
			VoxelShape floor = below.getCollisionShape(level, b);
			if (floor.isEmpty()) return Double.NaN;
			double top = floor.max(Direction.Axis.Y);
			// a low block below (a slab, up to 0.6): you stand in that cell, not in this one. Anything higher is floor for
			// this cell: a chest or soul sand is 0.875 high, and with 0.9 as the line the buddy stood on a chest sunk into the
			// floor of the field and found no way off it (21 Oct 2026)
			if (top <= 0.6) return Double.NaN;
			feet = b.getY() + top; under = b;
		}
		if (!level.getBlockState(under).getFluidState().isEmpty() && under != f) return Double.NaN;
		for (int y = f.getY() + 1; y < feet + 1.8; y++) if (!passable(level, new BlockPos(f.getX(), y, f.getZ()), false)) return Double.NaN;
		if (Grinder.hazard(level, f) || Grinder.hazard(level, f.below()) || besideLava(level, f)) return Double.NaN;
		return feet;
	}

	/**
	 * Water you can swim in: the cell itself water, room for the head above it (water or air), no lava next to it.
	 * At the surface or under it.
	 */
	private static boolean waterCell(Level level, BlockPos f) {
		BlockState st = level.getBlockState(f);
		if (st.getFluidState().isEmpty() || st.is(Blocks.LAVA) || st.is(Blocks.BUBBLE_COLUMN) || !st.getCollisionShape(level, f).isEmpty()) return false;
		BlockState above = level.getBlockState(f.above());
		if (above.is(Blocks.LAVA) || above.is(Blocks.BUBBLE_COLUMN) || !above.getCollisionShape(level, f.above()).isEmpty()) return false;
		return !besideLava(level, f);
	}

	/** Water with air above it: where you breathe. */
	private static boolean surface(Level level, BlockPos f) {
		return waterCell(level, f) && level.getBlockState(f.above()).getFluidState().isEmpty();
	}

	/** A ladder or vine you can hang in, with room for the head (air, or more of the ladder). */
	private static boolean ladderCell(Level level, BlockPos f) {
		BlockState st = level.getBlockState(f);
		if (!climbable(st) || besideLava(level, f)) return false;
		return passable(level, f.above(), false);
	}

	private static boolean climbable(BlockState st) {
		return st.is(Blocks.LADDER) || st.is(Blocks.VINE) || st.is(Blocks.TWISTING_VINES) || st.is(Blocks.TWISTING_VINES_PLANT)
			|| st.is(Blocks.WEEPING_VINES) || st.is(Blocks.WEEPING_VINES_PLANT) || st.is(Blocks.CAVE_VINES) || st.is(Blocks.CAVE_VINES_PLANT);
	}

	/** A door a hand opens (wood, copper; not iron) or a fence gate. */
	private static boolean openable(BlockState st) {
		if (st.getBlock() instanceof DoorBlock d) return d.type().canOpenByHand();
		return st.getBlock() instanceof FenceGateBlock;
	}

	/**
	 * A door or gate the buddy can get through here: openable, not one a click failed to open a moment ago, and with
	 * {@code basePenGates} off not a pen's ({@link Herd#penGate}): that is a wall, and its fence a way over only where
	 * carpets lie on it.
	 */
	private static boolean opensBy(Level level, BlockPos pos) {
		BlockState st = level.getBlockState(pos);
		if (!openable(st)) return false;
		BlockPos base = doorBase(level, pos);
		return !refused.containsKey(base) && (AfkConfig.get().basePenGates || !Herd.penGate(level, base));
	}

	private static boolean isOpen(BlockState st) {
		return st.hasProperty(BlockStateProperties.OPEN) && st.getValue(BlockStateProperties.OPEN);
	}

	/** The lower half of a door; any other block is itself. */
	private static BlockPos doorBase(Level level, BlockPos pos) {
		BlockState st = level.getBlockState(pos);
		if (st.getBlock() instanceof DoorBlock && st.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return pos.below();
		return pos.immutable();
	}

	private static boolean besideLava(Level level, BlockPos f) {
		for (Direction d : Direction.Plane.HORIZONTAL) if (level.getBlockState(f.relative(d)).is(Blocks.LAVA)) return true;
		return false;
	}

	/** Free to walk through; at foot height a carpet or pressure plate may lie there. Doors the buddy opens and ladders too. */
	static boolean passable(Level level, BlockPos q, boolean feet) {
		BlockState st = level.getBlockState(q);
		if (st.is(Blocks.COBWEB) || st.is(Blocks.LAVA)) return false;
		if (climbable(st) || opensBy(level, q)) return true;
		if (!st.getFluidState().isEmpty()) return false;
		VoxelShape s = st.getCollisionShape(level, q);
		return s.isEmpty() || (feet && s.max(Direction.Axis.Y) <= 0.2);
	}

	/** Landing on farmland after a jump or a drop tramples it back to dirt, and the crop on it with it. */
	private static boolean onFarmland(Level level, BlockPos cell) {
		return level.getBlockState(cell.below()).is(Blocks.FARMLAND);
	}

	/** Head room for a jump from this cell up by {@code rise}. */
	private static boolean roomToJump(Level level, BlockPos cur, double feet, double rise) {
		for (int y = cur.getY() + 1; y < feet + 1.8 + rise + 0.05; y++) if (!passable(level, new BlockPos(cur.getX(), y, cur.getZ()), false)) return false;
		return true;
	}

	private static double round(double v) { return Math.round(v * 10) / 10.0; }

	private static double round3(double v) { return Math.round(v * 1000) / 1000.0; }
}
