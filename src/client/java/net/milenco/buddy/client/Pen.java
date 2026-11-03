package net.milenco.buddy.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Farming mobs at a pen of a mob farm (0.4.80, "farm mobs" in the chain of {@link Follow#tick}): the player the buddy
 * follows punches a mob with a bare hand, the buddy then kills the mobs in that pen and waits for new ones when there
 * are none for a while; two crouches end it, and the buddy goes idle (in a base, back to keeping the base).
 * <ul>
 * <li><b>The gesture</b>: the followed player punches a monster with a bare hand, near the buddy. The server's damage
 * event says who hit what and how (DamageEventMixin); a hand that still holds nothing three ticks later is no slot
 * switched in the same server tick. Where the mob stands is a pen when {@link PenShape} finds a closed space of at most
 * 3 by 3 with two points of evidence and a place to hit it from: a nod, and the buddy goes. A closed space that is no farm, or
 * one that cannot be hit from anywhere: a shake. Open ground: nothing, and the punch is an ordinary blow that {@link Guard}
 * helps with.</li>
 * <li><b>The farm</b>: the buddy walks to a spot round the pen (the planner, never over the pen: {@link Follow#fence}), leans
 * in, crouched on an edge, and kills what comes in, at a human pace and only with the crosshair on the mob; with a
 * sword and someone else in the reach of its sweep, an axe of the hotbar takes its place, which does not sweep, or the blow
 * waits. Now and then a walk round, and to another side for a mob out of reach. Empty, the buddy waits and looks.</li>
 * <li><b>The end</b>: two quick crouches of that player within six blocks ({@link Follow}'s sign, without looking at
 * the buddy): a step back out of the sight of the pen's mobs, and a wait there; in a base the base keeps the buddy busy.
 * A tap, a mode, a job, a stop of the following end it as well, and following resumes.</li>
 * <li><b>Danger</b>: low after a blow, a swelling creeper (the pen's struck when the blow lands in time, any other
 * fled), a spear, no food; and during a stay after it, cover from a foe the fight leaves be.</li>
 * </ul>
 * It is no {@link Jobs.Job}: a job that is on pauses the whole chain, and staying alive, the fight, the corpse guard,
 * the taps and the answers have to go on while the buddy stands at a pit.
 */
final class Pen {
	private Pen() {}

	// ---------- numbers ----------

	static final double REACH = PenShape.REACH, GESTURE_NEAR = Tap.NEAR, VICTIM_NEAR = 4.5;
	static final int HAND_CONFIRM = 3, FALL_WAIT = 40;
	static final long DEDUPE = 2000, EVENT_OLD = 1000, IGNORED_GAP = 1000;
	static final long SEARCH_BUDGET = 5_000_000L;
	static final double LEASH = 32, LEASH_NIGHT = 64, TELEPORT = 8;
	static final long RECHECK = 10_000, RECHECK_AGAIN = 3000, UNREACHABLE = 1500, SWITCH_MIN = 6000, SPOT_BAD = 120_000, GO_MIN = 5000;
	static final double ARRIVE_V = 0.6, LEAN = 0.08;
	static final int LEAN_TICKS = 15;
	static final float RETREAT_HP = 10, RESUME_HP = 16;
	static final long RETREAT_MAX = 60_000, RETREAT_WINDOW = 600_000, DAMAGE_RECENT = 5000;
	static final int RETREATS = 3;
	/** Backing off and still hit after this long, or hit again on the way at this health or less: give up and go. */
	static final long RETREAT_HIT = 10_000;
	static final float RETREAT_LOW = 6;
	/**
	 * A creeper swells at its target within three and goes on within seven while it sees the target (SwellGoal), and goes off
	 * when its fuse of 30 ticks is full (Creeper.maxSwell); its blast reaches twice its radius of three, twice that when
	 * charged. A creeper that could not be struck within this of a spot (its swell's three and one more) makes it no spot to
	 * go back to; it was 3.5 until the third review of 22 Oct 2026.
	 */
	static final double CREEPER_SWELL = 4;
	static final int CREEPER_FUSE = Fuse.FUSE;
	/** Ticks of margin, and the most a moment's reaction takes, before the blow at a swelling creeper goes out. */
	static final int CREEPER_MARGIN = 3, REACT_MAX = 5;
	/** A hitch of unknown length under a strike (a mob in the line, nothing of it in reach): counted as this many ticks a look. */
	static final int UNSEEN_HITCH = 2;
	/**
	 * The creepers whose swell is counted (Fuse.Count) round the buddy: sideways, and up a drop of seventeen, which is what a
	 * landing's count needs to be full, over a floor four below.
	 */
	static final double COUNT_SIDE = 16, COUNT_UP = 24, COUNT_DOWN = 8;
	/** Away from a swelling creeper at least this long; waiting there longer, or driven off by one creeper this often within it: too dangerous. */
	static final long CREEPER_BACK = 3000, CREEPER_WAIT = 120_000;
	static final int CREEPER_EVADES = 3;
	/** Where to get away from a swelling creeper: a cell within this, past its blast and a half or out of its sight. */
	static final int CREEPER_RANGE = 8;
	/**
	 * Cover during a stay: a cell out of sight within this (by the walk there); hit there after the grace from the arrival,
	 * it is seen after all; it is held this long from the first arrival there.
	 */
	static final int COVER_RANGE = 10;
	static final long COVER_GRACE = 1500, COVER_FORGET = 30_000;
	/**
	 * Cells tried in one bout of cover before there is none (a walk that did not get there, a cell seen after all);
	 * covers from one foe within COVER_REPEAT that tell that a base keeper is shot at again and again; how far from the
	 * cell a hit still counts as there (an arrow's knockback), and how far off it a push takes the buddy back to it; how far
	 * off other foes the fight leaves be count as ones to hide from.
	 */
	static final int COVER_TRIES = 3, COVER_REPEATS = 3;
	static final long COVER_REPEAT = 300_000;
	static final double COVER_THERE = 1.5, COVER_DRIFT = 0.6, COVER_FOES = 32;
	/** Within this of the pen, flat, its walks to a spot and the lean go crouched the whole way, as a player walks round a pit. */
	static final double CROUCH_NEAR = 4;
	static final double BACKOFF = 2, BACKOFF_RANGE = 6;
	static final long BACKOFF_MAX = 4000, FENCE_TAIL = 30_000;
	static final double NO_DIG_NEAR = 16, NO_UNSTUCK = 8, NO_SPRINT = 3, NO_JUMP = 2;
	static final long WAIT_EVENT = 30_000, WAIT_AGAIN = 600_000, STATUS = 300_000, TRACE_EVERY = 4;
	static final double STRONG = PenShape.STRONG;
	/**
	 * A player or a pet beside the mob: the sweep's reach is widened by this sideways (anything else by SWEEP_MARGIN),
	 * as the client draws them a few ticks late and they are the ones a blow must never push over the pit; up and down
	 * by as much only while one is off the ground (a jump, a step off a ledge, a ladder), and by a hair
	 * (SWEEP_MARGIN_GROUND) while it stands, as a foot on the ground does not sink. With 0.3 for one that stands the buddy took
	 * the axe for the owner on the rim of a pit two deep on hoppers, out of the sweep's reach (the test of 22 Oct 2026).
	 */
	static final double SWEEP_MARGIN_MOVER = 1.0, SWEEP_MARGIN = 0.3, SWEEP_MARGIN_GROUND = 0.05;
	static final double RESUME_AT = 5.5, RESUME_NEAR = 8;
	static final long RESUME_HOLD = 15_000;
	static final long THREAT_FRESH = 2000, KILL_FRESH = 3000;
	/**
	 * A pen whose chain stays in its own pause this long ends (watch): AFK mode, the mod not allowed here, another job,
	 * under which the farm step never comes. A fight, the corpse guard, a menu, a walk of the unloader or the panel only
	 * pause it, and it goes on after them.
	 */
	static final long STALL = 20_000;

	// the pen's states: off, coming back after a restart, walking to a spot, leaning in, farming, walking to another
	// side, backing off from danger, paused for the night, stepping back after the end
	private static final int OFF = 0, RESUMING = 1, GOING = 2, LEAN_IN = 3, FARMING = 4, SWITCHING = 5, RETREAT = 6, NIGHT = 7, BACKOFF_STATE = 8;
	private static final String[] PHASE = { "off", "resuming", "going", "leaning", "farming", "switching", "retreat", "night", "backoff" };

	/** Stops that come of what happens to the buddy, not of what someone says: "geeft op". */
	private static final Set<String> GIVES_UP = Set.of("dead", "teleported", "another dimension", "away from the pen", "fell into the pen",
		"pen gone", "cannot reach it", "too dangerous", "no food", "is seated");

	// ---------- the pen ----------

	/** A recognised pen: whose, where, the mob's size, its columns, its shaft, where to stand. */
	private static final class Hok {
		final String owner, dim, mobId;
		final double w, h, eye;
		final PenShape.Flood flood;
		final List<Long> cols;
		PenShape.Evidence ev;
		PenShape.Result result;
		double top;
		AABB box;            // the columns from F - 1 up to the top of the shaft and one above
		Vec3 centre;         // the middle of the floor
		Set<BlockPos> fence = Set.of();
		long since;

		Hok(String owner, String dim, String mobId, PenShape.Mob m, PenShape.Flood f, PenShape.Evidence ev, PenShape.Result r) {
			this.owner = owner; this.dim = dim; this.mobId = mobId;
			this.w = m.w(); this.h = m.h(); this.eye = m.eye();
			this.flood = f; this.cols = f.list(); this.ev = ev; this.result = r;
			this.top = ev.top();
			this.box = new AABB(f.minX(), f.floor() - 1, f.minZ(), f.maxX() + 1, top + 1, f.maxZ() + 1);
			this.centre = f.centre(f.floor());
		}

		double floor() { return flood.floor(); }

		boolean column(double x, double z) { return flood.has(Mth.floor(x), Mth.floor(z)); }

		/**
		 * A mob of this pen: its middle over a column, its feet from F - 1 up to the top of the shaft, never a kind the
		 * farm does not take (never), and either down in the pen in the farm's own band (F + 1.2, a spider on the wall up
		 * to h + 1, as huntable), on the ground or knocked up, or over it on its way down the shaft: one that cannot fly,
		 * falling. One that stands on the rim with its box over the edge, or a phantom or a vex over the pen in the open
		 * band where the buddy stands, is none: the farm never hits it, so it stays Guard's, and its blows are answered.
		 */
		boolean holds(AABB b, boolean onGround, boolean spider, boolean never, boolean flier, double dy) {
			if (never || !column((b.minX + b.maxX) / 2, (b.minZ + b.maxZ) / 2) || b.minY < floor() - 1 || b.minY > top) return false;
			if (b.minY <= (spider ? floor() + h + 1 : floor() + 1.2)) return true;
			return !onGround && !flier && dy < 0;
		}

		boolean holds(Entity e) {
			return holds(e.getBoundingBox(), e.onGround(), e instanceof Spider, never(e), flies(e), e.getDeltaMovement().y);
		}

		/** Horizontal distance from a point to the box round the columns. */
		double flat(Vec3 v) {
			double x = Mth.clamp(v.x, flood.minX(), flood.maxX() + 1), z = Mth.clamp(v.z, flood.minZ(), flood.maxZ() + 1);
			return Math.hypot(v.x - x, v.z - z);
		}

		String where() { return Math.round(centre.x - 0.5) + " " + Math.round(floor()) + " " + Math.round(centre.z - 0.5); }
	}

	private static Hok hok;
	private static int state = OFF;
	private static long stateSince;
	/** Since when the chain is in its own pause while the pen holds the buddy (held), or 0: watch ends a pen held too long. */
	private static long heldSince;
	private static PenShape.Spot spot;
	private static String goWhy = "to a spot";
	private static long goSince, goLimit;
	/** When eating began on a walk to a spot, 0 when not eating: the walk's time stands still meanwhile. */
	private static long eatFrom;
	private static final Map<BlockPos, Long> badSpot = new HashMap<>();
	private static int leanTicks, leanStill;
	private static Vec3 leanLast;
	/** The tick of the pen's own walk or lean, for crouchWalk. */
	private static int walkTick = -10;
	private static final AfkHelperClient.Look look = new AfkHelperClient.Look();
	private static final Random R = new Random();
	private static final PenShape.Pace pace = new PenShape.Pace();
	private static long nextHit, lastHitSeen;
	// with a sword and someone beside the mob: the axe taken for it (Guard.arm leaves it in hand), when it is taken
	// (a moment's reaction after the sighting), when someone was last seen in the sweep's reach, how long that has to be
	// clear before the sword comes back, and whether the holding off for want of an axe was said
	private static boolean axeHeld, heldOffTold;
	private static long axeAt, bystanderAt, axeBack;
	private static int hits, killed, hitsTotal, killedTotal;
	private static final Map<Integer, Long> hitMobs = new HashMap<>();
	private static final ConcurrentLinkedQueue<Integer> deaths = new ConcurrentLinkedQueue<>();
	private static long started;
	/** Test switches: no hits ({@code "hit": false}), a trace every four ticks ({@code "trace"}), the sweep rule off ({@code "safe": false}). */
	private static boolean dry, trace, unsafe;
	private static long emptySince, nextGlance, waitToldAt;
	private static boolean centreLooked, waitTold;
	private static long unreachableSince, inReachAt, nextRound, lastSwitch;
	private static int threatId = -1;
	private static long threatAt;
	// damage to the buddy (any drop of health) and a blow (one with an attacker, damageTick); the damage event last
	// taken, and the newest one since the health last dropped
	private static long lastDamageAt, lastBlowAt;
	private static float lastHealth = -1;
	private static DamageSource lastSource, freshSource;
	// a swelling creeper: the one of the pen to strike (from when the blow may go out, a moment's reaction after it was
	// seen; the look under way that turns to it), or the one to get away from (the cell, since and until when, whether
	// the waiting for the spot to clear was said), and when which creepers drove the buddy off lately
	private static Creeper urgent;
	private static long urgentAt;
	private static int urgentTurn = -1;
	// the strike begun, kept through a moment it is out of near (kept, strikeOn): the tick its swell was last seen (the
	// server's direction), the tick its blow last went ahead, and whether a walk off cleared the buddy of nothing by then
	private static int urgentSwellTick, urgentPlanTick;
	private static boolean urgentLate;
	private static int evadeId = -1;
	private static BlockPos evadeCell;
	// that cell: the feet there, whether it is a fallback (seen by design: Getaway.pick)
	private static double evadeFeet;
	private static boolean evadeFallback;
	private static long evadeSince, evadeUntil;
	private static boolean evadeWaitTold;
	private static final Map<Integer, ArrayDeque<Long>> evades = new HashMap<>();
	// the swell of the creepers about the buddy as the server counts it, as the most it can be (Fuse.Count), every tick while
	// the pen holds the buddy (watch)
	private static final Map<Integer, Fuse.Count> counts = new HashMap<>();
	// the urgent one struck: whose death is waited for (no second blow at it, no getting away from it for swelling on)
	// and until when; the blow of Guard's last looked at
	private static int struckId = -1;
	private static long struckUntil, creeperHitSeen;
	// a hitch of unknown length under a strike (a line not clear, nothing of it in reach), and whether it began only once
	// a walk out of its blast could no longer get clear
	private static boolean unseenHitch, unseenLate;
	/** The tick creeperTick last ran in (the creeper step of the chain, or Pen.tick's own): once a tick. */
	private static int creeperRanAt = -1000;
	/** The last tick in which a creeper swelled at the buddy, or a strike or a walk away from one was under way. */
	private static int dangerAt = -1000;
	/**
	 * The last tick a creeper swelled within its reach of the buddy, seen or not (anySwelling, the server's swell direction),
	 * or a strike at one was under way.
	 */
	private static int swellAt = -1000;
	// getting away: the creepers it is for (whether each stopped swelling at the buddy since), the cells given up, when the walk
	// to its cell began (Follow.ticks), whether the buddy stood at it, too dangerous once clear of them (the need), and
	// whether one of them was no creeper of the pen
	private static final Map<Integer, Boolean> evadeFor = new HashMap<>();
	private static final Set<BlockPos> evadeTried = new HashSet<>();
	private static int evadePlanTick;
	private static boolean evadeAtCell, evadeOutside;
	private static String evadeDanger;
	// the walk to the cell: when a fallback was last looked past (replan), when this cell was first reached (kept over
	// a knock off it), the cells its walk keeps out of (Follow.shun), and whether the last creeper step gave the tick to
	// a fight (yieldAt: the tick it did)
	private static int evadeLookTick;
	private static long evadeArrivedAt;
	private static Set<BlockPos> evadeShun = Set.of();
	private static boolean evadeYielded;
	private static int yieldAt = -1000;
	private static final ArrayDeque<Long> retreats = new ArrayDeque<>();
	// the cell to back off to, worked out from the pen's start: out of the sight of its columns (hidden), or only the
	// farthest one within ten; when it was reached in a retreat
	private static BlockPos retreatCell;
	private static boolean retreatHidden;
	private static RetreatSearch retreatSearch;
	private static boolean retreatSearched;
	private static String retreatWhy = "";
	private static long retreatSince, retreatUntil, retreatArrived;
	/** A bout began over a gap (overGap): its pick on landing (evadeTick). */
	private static boolean evadePickDue;
	/**
	 * The tick a walk to a spot was last set up in (go, goTo: a switch for a spear too), and the one "terug naar hok" was
	 * last told in (toldBack): endEvade sets none up again in that tick.
	 */
	private static long wentTick = -1, backToldTick = -1;
	// this retreat's own: another cell where a creeper stood by its cell (retreatTo, looked for by retreatAround) or
	// the place reached (retreatStays); a cell away from the pen while no cell is known (retreatAway, looked for once); the
	// goal it last walked to, whose change begins the walk anew
	private static BlockPos retreatTo, retreatAway, retreatGoal;
	private static boolean retreatStays, retreatAwayLooked;
	private static CoverShape.Search retreatAround;
	private static Vec3 lastPos;
	private static int lastTick = -100;
	private static long nextStatus, nextRecheck;
	private static int blockHash, recheckFails;
	private static PenShape.Search research;
	private static long nightSince;
	/** The walk to the retreat cell for the night did not come off: the buddy waits in place. */
	private static boolean nightStays;
	private static BlockPos backoffCell;
	private static long backoffSince, backoffLimit, backoffSeen;
	/** The stay that comes once the buddy is at the cell the step back goes to (too dangerous), or null: none, or set already. */
	private static String backoffStay;
	/**
	 * Where the buddy stood in the last tick of the step back and which tick that was, and the dimension it began in: a teleport
	 * or another dimension ends it (watch), a walk off it does not. The dimension is its own, as the pen of a moment ago
	 * (tail) lapses.
	 */
	private static Vec3 backoffPos;
	private static int backoffPosTick;
	private static String backoffDim;
	// cover during a stay, from foes the fight leaves be: the foe a bout began for, the search under way and why it
	// looks (the reason of no cover when it finds none), the cell and the feet there, since and until when the walk goes
	// there, the arrival there (the grace) and since when it is held (its half minute, from the first arrival there),
	// whether it is held this tick and whether that first arrival is still to come, when cover was last asked for, the
	// damage event it answered last, the cells that failed this bout and how many were tried, the covers per foe lately
	private static int coverFoe = -1;
	private static CoverShape.Search coverSearch;
	private static String coverWhy = "";
	private static BlockPos coverCell;
	private static double coverFeet;
	private static long coverSince, coverLimit, coverArrived, coverQuiet, coverSeen;
	private static boolean coverHolds, coverFirst;
	private static DamageSource coverSource;
	private static final List<BlockPos> coverBad = new ArrayList<>();
	private static int coverTries;
	private static final Map<Integer, ArrayDeque<Long>> coverRepeats = new HashMap<>();
	private static long resumeUntil, nextResumeTry;
	private static String resumeBy = "restart";
	private static long ownerAwaySince;
	private static boolean waitSpotTold;
	// the fall into the pen: its columns, kept until the buddy has been out of them five seconds
	private static Set<Long> fellCols;
	private static long fellOutSince;
	// the pen of a moment ago: its fence and the caution near it stay a while after it stopped
	private static Hok tail;
	private static long tailUntil;

	/** Farming: walking to the pen, at it, or waiting there through the night. */
	static boolean on() { return state >= GOING && state <= NIGHT; }

	/** The pen keeps the buddy busy: also while it comes back after a restart and during the step back after the end. */
	static boolean busy() { return state != OFF; }

	static int hits() { return hitsTotal; }

	static int killed() { return killedTotal; }

	// ---------- the gesture ----------

	/**
	 * One blow on a mob, as the server told it: plain values, copied where the packet is handled. With what Hok.holds
	 * wants of its body: a kind the farm never takes, one that flies, how fast it falls.
	 */
	private record Blow(int id, String type, AABB box, boolean onGround, boolean spider, boolean never, boolean flier, double dy, float health,
			boolean enemy, boolean named, boolean tamed, boolean excluded, double w, double h, double eye, int causeId, int directId,
			boolean playerAttack, String causeName, boolean causeHandEmpty, long at) {}

	private static final ConcurrentLinkedQueue<Blow> blows = new ConcurrentLinkedQueue<>();

	/**
	 * A mob took a blow (DamageEventMixin, through AfkHelperClient.damageEvent). Its health is still the value of before
	 * the blow: the new one comes later as the entity's data. A jockey counts with its mount's size and box.
	 */
	static void damage(LivingEntity e, DamageSource s) {
		try {
			Entity cause = s.getEntity(), direct = s.getDirectEntity();
			Entity body = e.getVehicle() instanceof LivingEntity v ? v : e;
			double[] size = size(body);
			Player hitter = cause instanceof Player pl ? pl : null;
			blows.add(new Blow(e.getId(), BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(), body.getBoundingBox(), body.onGround(),
				body instanceof Spider, never(e), flies(body), body.getDeltaMovement().y, e.getHealth(), e instanceof Enemy, e.hasCustomName(),
				e instanceof TamableAnimal t && t.isTame(), excluded(e), size[0], size[1], size[2], cause == null ? -1 : cause.getId(),
				direct == null ? -1 : direct.getId(), s.is(DamageTypes.PLAYER_ATTACK), hitter == null ? null : hitter.getGameProfile().name(),
				hitter != null && hitter.getMainHandItem().isEmpty(), System.currentTimeMillis()));
			while (blows.size() > 50) blows.poll();
		} catch (RuntimeException ignored) {
			// copy only: whatever goes wrong here must not reach the packet's handling
		}
	}

	/** A mob died (DeathEventMixin): counted as a kill when the buddy hit it within the last three seconds. */
	static void mobDied(int id) {
		deaths.add(id);
		while (deaths.size() > 200) deaths.poll();
	}

	/** Kinds that are no farm's: bosses, the warden, a shulker, a creaking (which a blow does not hurt). */
	private static boolean excluded(Entity e) {
		return e instanceof WitherBoss || e instanceof EnderDragon || e instanceof Warden || e instanceof Shulker || e instanceof Creaking;
	}

	/** Kinds the farm never hits (huntable), so never the pen's to claim: those and a ghast or a phantom. */
	private static boolean never(Entity e) { return excluded(e) || e instanceof Ghast || e instanceof Phantom; }

	/** One that flies or floats: over the pen above its band it is passing by, not dropping down the shaft. */
	private static boolean flies(Entity e) {
		return e instanceof Phantom || e instanceof Ghast || e instanceof Vex || e instanceof Blaze || e.isNoGravity();
	}

	/** Width, height and eye height: the grown size of its kind (a baby counts as grown), a slime's own box. */
	private static double[] size(Entity e) {
		if (e instanceof Slime) {
			AABB b = e.getBoundingBox();
			double h = b.maxY - b.minY;
			return new double[] { b.maxX - b.minX, h, h * 0.625 };
		}
		var d = e.getType().getDimensions();
		return new double[] { d.width(), d.height(), d.eyeHeight() };
	}

	/** A recognition under way: the punch, its pen, and how far the checks got. */
	private static final class Pending {
		Blow blow;
		PenShape.Mob mob;
		PenShape.Flood flood;
		PenShape.Evidence ev;
		PenShape.Search search;
		LevelWorld world;
		String mobId, door = "gesture", owner;
		int eventTick, fallUntil;
		boolean falling, handChecked, handFull, checked;
		/** Only recognise, as the command {@code "probe"} asks: no answer, no start, the outcome in pen.txt. */
		boolean probe;
		/** The command's start: no hand to look at. */
		boolean test;
	}

	private static Pending pending;
	private static final Map<Integer, Long> victims = new HashMap<>();
	private static long lastIgnored;

	/**
	 * Guard leaves a mob of the pen alone: the one just punched while it is recognised, and every mob in the pen being
	 * farmed or dropping down its shaft (Hok.holds); one on the rim is not the pen's. No mob of the pen being farmed once
	 * the buddy stands in one of its columns: knocked in, Guard defends down there in that very tick.
	 */
	static boolean claims(Entity e) {
		if (e == null) return false;
		if (pending != null && !pending.probe && pending.blow != null && e.getId() == pending.blow.id()) return true;
		if (hok == null || state == BACKOFF_STATE || !(e instanceof LivingEntity) || e instanceof Player) return false;
		LocalPlayer p = AfkHelperClient.mc().player;
		if (p != null && Work.dimOf(p.level()).equals(hok.dim) && hok.column(p.getX(), p.getZ())
			&& p.getY() >= hok.floor() - 1 && p.getY() <= hok.top) return false;
		return hok.holds(e);
	}

	/**
	 * The mob punched for the gesture still falls down a shaft: it is held from a new fight (claims), but a fight Guard
	 * is in with it already goes on, for what it lands in is not known yet.
	 */
	static boolean falling(Entity e) {
		return e != null && pending != null && pending.falling && pending.blow != null && e.getId() == pending.blow.id();
	}

	/** A mob of the pen hit the buddy or the followed player: it goes first. */
	static void threat(Entity e) {
		threatId = e.getId();
		threatAt = System.currentTimeMillis();
	}

	/**
	 * Every tick while following (Follow.tick), also while dead and while the chain is held before the farm step
	 * (a fight, AFK mode, another job, a menu): the kills, the buddy's death, the fall into the pen (also the one of a moment
	 * ago) and its fence, a pen held in the chain's own pause for a while, the step back ended by a teleport or another
	 * dimension and given up for cover, the punches, the recognition under way.
	 */
	static void watch(Minecraft mc, LocalPlayer p, Player owner) {
		long now = System.currentTimeMillis();
		int tick = Follow.ticks();
		Integer dead;
		while ((dead = deaths.poll()) != null) {
			Long at = hitMobs.remove(dead);
			if (at != null && now - at <= KILL_FRESH) { killed++; killedTotal++; }
		}
		hitMobs.values().removeIf(at -> now - at > 30_000);
		victims.values().removeIf(at -> now - at > DEDUPE);
		// dead: the pen ends there and is written off, so that it never comes back after a death (the owner's choice).
		// Follow.tick pauses before the farm step while the buddy is dead, so the pen's own guard never saw it
		if (!p.isAlive()) {
			if (state == RESUMING) { setState(OFF, now); notBack("dead"); }   // no pen yet to write off: its entry
			if (busy() || pending != null) stop("dead", false);
			blows.clear();   // punches seen from the death screen are no gesture
			return;
		}
		if (hok != null && Work.dimOf(p.level()).equals(hok.dim)) {
			// every tick, not in the farm step, which a fight holds back: knocked into the pen, the pen stops at once (the fence
			// goes, so that digging or pillaring out is possible, and Guard defends down there), and the fence never lapses
			if (fellInto(hok, p)) {
				fellCols = new HashSet<>(hok.cols);
				stop("fell into the pen", false);
				Needs.want("pen_stuck", "{me} fell into the mob farm and cannot get out without help.");
				fellOutSince = 0;
			} else Follow.fence(hok.fence, now + 2000);
		} else if (hok == null && tail != null && now < tailUntil && Work.dimOf(p.level()).equals(tail.dim) && fellInto(tail, p)) fellInTail(p);
		// the chain held in its own pause (AFK mode, the mod not allowed here, another job): the farm step never comes, and
		// a pen on without its tick would hold AFK mode's own moves off and refuse !farm, so after STALL it ends, as an off
		// with that cause. A fight, the corpse guard, a menu, a walk of the unloader or the panel only pause it: after them
		// it goes on, and back to the spot within the leash (after the fall test, so that a fall in a hold is told as one)
		String why = busy() ? held() : null;
		if (why == null) heldSince = 0;
		else if (heldSince == 0) heldSince = now;
		else if (now - heldSince > STALL) {
			heldSince = 0;
			if (state == RESUMING) { setState(OFF, now); notBack(why); }
			else stop(why, false);
		}
		// stepping back after the end: a teleport (an accepted /tpahere) or another dimension ends it, also under
		// a fight or a creeper's getaway, which only hold it up (backoffWatch)
		if (state == BACKOFF_STATE) backoffWatch(p, tick, now);
		// stepping back after the end, told to stay, and hit by a foe the fight leaves be: the step back ends, and taking
		// cover (cover, the chain's next step during a stay) gets the buddy out of its sight instead
		if (state == BACKOFF_STATE && Follow.staying() && Guard.leftBeHit(now) != null) endBackoff();
		Blow b;
		while ((b = blows.poll()) != null) consider(p, owner, b, now, tick);
		if (pending != null) pendingTick(mc, p, owner, now, tick);
		fellTick(p, now);
		// the swell of the creepers about the buddy as the server counts it, every tick while the pen is busy, also while the
		// chain is held: a landing is seen only as it happens
		if (busy()) countSwell(p, tick);
		else if (!counts.isEmpty()) counts.clear();
	}

	/**
	 * The creepers about the buddy counted as the server counts their swell (Fuse.Count): what a landing adds only the server
	 * counts, and a drop farm's creeper lands at 25 of its 30, where the client's own count began at 0 (the third review
	 * of 22 Oct 2026). Its height as the server last sent it, its standing as the server's packets say.
	 */
	private static void countSwell(LocalPlayer p, int tick) {
		AABB box = new AABB(p.getX() - COUNT_SIDE, p.getY() - COUNT_DOWN, p.getZ() - COUNT_SIDE, p.getX() + COUNT_SIDE, p.getY() + COUNT_UP, p.getZ() + COUNT_SIDE);
		for (Creeper c : p.level().getEntitiesOfClass(Creeper.class, box, Creeper::isAlive)) {
			Fuse.Count n = counts.computeIfAbsent(c.getId(), k -> new Fuse.Count());
			n.seen = tick;
			n.tick(c.onGround(), c.isInWater() || c.onClimbable(), sent(c).y, c.getSwellDir(), clientSwell(c));
		}
		counts.values().removeIf(n -> n.seen != tick);
	}

	/** The client's own count of a creeper's swell (Creeper.getSwelling is it over maxSwell - 2). */
	private static int clientSwell(Creeper c) { return Math.round(c.getSwelling(1f) * (CREEPER_FUSE - 2)); }

	/** The server's count of this creeper's swell as the most it can be: the whole fuse for one not counted yet. */
	private static int count(Creeper c) {
		Fuse.Count n = counts.get(c.getId());
		return n == null ? CREEPER_FUSE : n.est;
	}

	/**
	 * The feet in a column of this pen, down at that column's floor: from a block under it (the band Hok.box and claims
	 * take, F - 1) up to the mob's height over it. Without the lower bound a walk on a floor of the base under the pen's
	 * columns, below its fence, was a fall into the pen: the need, the fence down and the stay lifted, also for the running
	 * pen, which then ended (the third review of 22 Oct 2026). A real fall passes that band: the flood refuses holes, and in
	 * a hopper's bowl the feet are a third of a block under its rim.
	 */
	private static boolean fellInto(Hok h, LocalPlayer p) {
		Double floor = h.flood.floorOf().get(PenShape.key(Mth.floor(p.getX()), Mth.floor(p.getZ())));
		return floor != null && p.getY() >= floor - 1 && p.getY() <= floor + h.h;
	}

	/**
	 * Knocked into the pen of a moment ago (during the step back from it, a wait by it, or a walk off after a stop): as a
	 * fall into the pen being farmed. The step back ends, the fence and its caution go at once (so that digging or
	 * pillaring out is possible, or Unstuck can get the buddy loose), the need is raised, and a stay is lifted, so that the
	 * walker tries a way out rather than staying at the floor of the kill chamber.
	 */
	private static void fellInTail(LocalPlayer p) {
		Hok h = tail;
		if (state == BACKOFF_STATE) endBackoff();
		fellCols = new HashSet<>(h.cols);
		fellOutSince = 0;
		Follow.unfence();
		tail = null;
		tailUntil = 0;
		Needs.want("pen_stuck", "{me} fell into the mob farm and cannot get out without help.");
		Exchange.emit("pen", "action", "fell into the pen", "x", p.getBlockX(), "y", p.getBlockY(), "z", p.getBlockZ());
		Follow.unstay("fell into the pen");
	}

	private static void consider(LocalPlayer p, Player owner, Blow b, long now, int tick) {
		if (now - b.at() > EVENT_OLD) return;
		String target = Follow.target();
		if (target == null || b.causeName() == null || !b.causeName().equalsIgnoreCase(target)) return;
		// the owner's own fist: no arrow, no mace smash, no spear, nothing in the hand
		if (b.causeId() != b.directId() || !b.playerAttack() || !b.causeHandEmpty()) return;
		if (!b.enemy() || b.named() || b.tamed() || b.excluded()) return;
		// a mob of the pen being farmed or looked at: several punches kill a mob, so a punch there is no new gesture (by the
		// test of claims: one on the rim, or one flying over the pen, is a punch like any other)
		if (hok != null && hok.holds(b.box(), b.onGround(), b.spider(), b.never(), b.flier(), b.dy())) return;
		if (pending != null && pending.flood != null && pendingMeets(b.box())) return;
		Long before = victims.put(b.id(), now);
		if (before != null && now - before < DEDUPE) return;
		// where it stands first: open ground is no gesture at all, so nothing is said of it, whatever the buddy is doing, and the
		// punch is a blow like any other that Guard helps with this very tick
		Level level = p.level();
		LevelWorld w = new LevelWorld(level);
		PenShape.Mob m = new PenShape.Mob(b.w(), b.h(), b.eye(), b.box(), b.onGround());
		double F = PenShape.floor(w, m);
		boolean falling = Double.isNaN(F);
		// no floor under it: one dropping down a shaft is waited for; any other (swimming, flying, beside a cliff) is in
		// the open as well
		if (falling && (level.getEntity(b.id()) == null || !PenShape.inShaft(w, m))) return;
		PenShape.Flood f = falling ? null : PenShape.flood(w, m, F);
		if (f != null && !f.closed()) return;
		// Grinder's farm does not hold the gesture back (the start ends it: the owner's newest instruction wins), nor a pour of
		// the water bucket, which ends by itself well within the time a recognition takes
		String held = held(Jobs.Job.MOB_FARM, Jobs.Job.WATER);
		String no = !AfkConfig.get().followMobFarm ? "switched off"
			: held != null ? held
			: Follow.staying() ? "stays"
			: owner == null || owner.distanceTo(p) > GESTURE_NEAR || PenShape.dist(b.box(), owner.getEyePosition()) > VICTIM_NEAR ? "too far"
			: p.isPassenger() ? "is seated" : null;
		// a refusal is told for a closed space; for a mob still in the air it is not yet known where it lands, so nothing
		// is said, and nothing held from Guard either
		if (no != null) { if (!falling) ignored(no, b, now); return; }
		Pending pd = new Pending();
		pd.blow = b; pd.mob = m; pd.world = w; pd.mobId = b.type(); pd.eventTick = tick; pd.owner = target;
		if (falling) {
			// dropping down a shaft: wait for it to land, holding it from Guard meanwhile (a fight would walk the buddy into it)
			pd.falling = true;
			pd.fallUntil = tick + FALL_WAIT;
			replacePending(pd);
			return;
		}
		pd.flood = f;
		replacePending(pd);
	}

	/**
	 * What holds the chain of Follow.tick in its own pause, under which the farm step never comes: AFK mode, the mod not
	 * allowed on this server, another job on (but the following and the ones given). A pen started or kept then would be
	 * on without a tick. The gesture and the panel's on let Grinder's farm and a pour of the water bucket be (the start
	 * ends the one, the other ends by itself); the resume only the pour, as an AFK farm that runs then was switched on
	 * after the connection, the newer instruction.
	 */
	private static String held(Jobs.Job... let) {
		if (AfkConfig.get().afk) return "AFK mode";
		if (!AfkHelperClient.active()) return "mod off";
		Jobs.Job[] except = Arrays.copyOf(let, let.length + 1);
		except[let.length] = Jobs.Job.FOLLOW;
		return Jobs.anyOn(except) ? "another farm" : null;
	}

	private static void replacePending(Pending pd) {
		pending = pd;
	}

	private static boolean pendingMeets(AABB b) {
		PenShape.Flood f = pending.flood;
		for (long c : f.cols()) {
			int x = PenShape.kx(c), z = PenShape.kz(c);
			if (b.maxX > x && b.minX < x + 1 && b.maxZ > z && b.minZ < z + 1 && b.minY < f.floor() + 45 && b.maxY > f.floor() - 1) return true;
		}
		return false;
	}

	private static void ignored(String why, Blow b, long now) {
		if (now - lastIgnored < IGNORED_GAP) return;
		lastIgnored = now;
		Exchange.emit("pen", "action", "gesture ignored", "reason", why, "mob", b.type(),
			"x", Mth.floor((b.box().minX + b.box().maxX) / 2), "y", Mth.floor(b.box().minY), "z", Mth.floor((b.box().minZ + b.box().maxZ) / 2));
	}

	/** The checks of a recognition in their order: the landing, the hand, where they stand, the buddy's own state, the evidence, the spots. */
	private static void pendingTick(Minecraft mc, LocalPlayer p, Player owner, long now, int tick) {
		Pending pd = pending;
		Level level = p.level();
		// the hand still empty three ticks on: the damage event comes at once, a change of what the owner holds with the owner's next
		// tick, so a sword taken out in the server tick of the punch shows only now. Read at that tick also while the mob
		// still falls, and kept: a sword taken out after it is no answer to the punch
		if (!pd.handChecked && !pd.test && !pd.probe && tick >= pd.eventTick + HAND_CONFIRM) {
			pd.handChecked = true;
			pd.handFull = owner == null || !owner.getMainHandItem().isEmpty();
		}
		if (pd.falling) {
			Entity live = level.getEntity(pd.blow.id());
			if (!(live instanceof LivingEntity le) || !le.isAlive()) { pending = null; return; }
			PenShape.Mob m = liveMob(le);
			double F = PenShape.floor(pd.world, m);
			if (Double.isNaN(F) || (!le.onGround() && tick <= pd.fallUntil)) {
				if (tick > pd.fallUntil) { release(pd); return; }
				return;
			}
			pd.world = new LevelWorld(level);
			PenShape.Flood f = PenShape.flood(pd.world, m, F);
			if (!f.closed()) { release(pd); return; }
			pd.mob = m; pd.flood = f; pd.falling = false;
		}
		if (!pd.handChecked && !pd.test && !pd.probe) return;
		// a hand that was not empty: told once it is known to be a closed space (a mob that fell is held from Guard until
		// then, so that the fight does not walk the buddy into the shaft)
		if (pd.handFull) {
			Exchange.emit("pen", "action", "gesture ignored", "reason", "hand not empty", "mob", pd.mobId);
			release(pd);
			return;
		}
		if (!pd.checked) {
			pd.checked = true;
			boolean weakened = pd.blow != null && pd.blow.health() <= 2;
			int[] crowd = { 0 };
			if (weakenedInPen(level, pd.flood, pd.mob, crowd)) weakened = true;
			boolean cart = cart(level, pd.flood);
			pd.ev = PenShape.evidence(pd.world, pd.flood, pd.mob, weakened, crowd[0], cart);
			Vec3 him = owner == null ? null : owner.position();
			String no = PenShape.inside(pd.flood, pd.ev.top(), him, p.position());
			if (no == null && !pd.probe && p.getHealth() < 12) no = "low hp";
			if (no == null && !pd.probe && p.getFoodData().getFoodLevel() <= 6) no = "no food";
			if (no == null && !pd.ev.enough()) no = "does not look like a mob farm";
			if (no != null) { refuse(pd, no, p); return; }
			pd.search = new PenShape.Search(pd.world, pd.flood, pd.mob, pd.ev);
		}
		if (!pd.search.step(SEARCH_BUDGET)) return;
		PenShape.Result r = pd.search.result();
		if (r.spots().isEmpty()) { refuse(pd, "cannot hit them from anywhere", p); return; }
		accept(pd, r, p, now);
	}

	/** A recognition that comes to nothing, silently: the punch is Guard's again. */
	private static void release(Pending pd) {
		pending = null;
		if (pd.probe) return;
		assist(pd);
	}

	/** Guard's help with the punch after all; not while Grinder's farm holds the chain, behind whose pause it would come late. */
	private static void assist(Pending pd) {
		LocalPlayer p = AfkHelperClient.mc().player;
		if (p == null || pd.blow == null || Grinder.active()) return;
		Entity e = p.level().getEntity(pd.blow.id());
		if (e != null) Guard.assist(e);
	}

	/**
	 * A closed space that is no pen the buddy can farm: a shake, and the mob is fought as ever. Not while Grinder's farm holds
	 * the chain (the gesture is not refused for it, as a pen's start ends it): the shake and the help would wait behind
	 * its pause and come long after the punch, so only the line in chat says why.
	 */
	private static void refuse(Pending pd, String why, LocalPlayer p) {
		why = Needs.named(why);
		pending = null;
		PenShape.Flood f = pd.flood;
		Exchange.emit("pen", "action", "no pen here", "reason", why, "size", f == null ? "" : f.size(), "mob", pd.mobId,
			"x", f == null ? 0 : f.minX(), "y", f == null ? 0 : Mth.floor(f.floor()), "z", f == null ? 0 : f.minZ());
		if (pd.probe) { writeTxt(p, pd, null, why); return; }
		if (Grinder.active()) return;
		Follow.respond(Follow.NO);
		assist(pd);
	}

	private static void accept(Pending pd, PenShape.Result r, LocalPlayer p, long now) {
		pending = null;
		PenShape.Flood f = r.flood();
		int sides = 0;
		double depth = 0;
		for (PenShape.Spot s : r.ring()) if (s != null) { sides++; depth += s.feet() - f.floor(); }
		Exchange.emit("pen", "action", "recognised", "size", f.size(), "depth", sides == 0 ? 0 : Math.round(depth / sides * 10) / 10.0,
			"kind", r.kind(), "evidence", r.evidence(), "spots", r.spots().size(), "sides", sides,
			"ms", pd.search.millis(), "mob", pd.mobId, "x", f.minX(), "y", Mth.floor(f.floor()), "z", f.minZ());
		writeTxt(p, pd, r, null);
		if (pd.probe) return;
		Follow.respond(Follow.YES);
		start(new Hok(pd.owner, Work.dimOf(p.level()), pd.mobId, pd.mob, f, pd.ev, r), pd.door, now);
	}

	/** The mobs of the pen at two hearts or less (a farm weakens them), and how many there are, in the pen or its shaft. */
	private static boolean weakenedInPen(Level level, PenShape.Flood f, PenShape.Mob m, int[] crowd) {
		AABB scan = new AABB(f.minX(), f.floor() - 0.5, f.minZ(), f.maxX() + 1, f.floor() + m.h() + 42, f.maxZ() + 1);
		boolean weak = false;
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, scan, e -> Grinder.candidate(e) && !excluded(e))) {
			if (!f.has(Mth.floor(e.getX()), Mth.floor(e.getZ()))) continue;
			crowd[0]++;
			if (e.getHealth() <= 2) weak = true;
		}
		return weak;
	}

	/** A hopper minecart in or under a column of the pen: a collector too. */
	private static boolean cart(Level level, PenShape.Flood f) {
		AABB scan = new AABB(f.minX(), f.floor() - 2.5, f.minZ(), f.maxX() + 1, f.floor() + 1, f.maxZ() + 1);
		for (MinecartHopper c : level.getEntitiesOfClass(MinecartHopper.class, scan))
			if (f.has(Mth.floor(c.getX()), Mth.floor(c.getZ()))) return true;
		return false;
	}

	private static PenShape.Mob liveMob(LivingEntity e) {
		Entity body = e.getVehicle() instanceof LivingEntity v ? v : e;
		double[] s = size(body);
		return new PenShape.Mob(s[0], s[1], s[2], body.getBoundingBox(), body.onGround());
	}

	// ---------- the world as the recogniser sees it ----------

	/** The level's blocks as boxes, each block state looked up once for one recognition. */
	static final class LevelWorld implements PenShape.World {
		private final Level level;
		private final Map<BlockPos, BlockState> states = new HashMap<>();
		private final Map<BlockPos, Double> stand = new HashMap<>();

		LevelWorld(Level level) { this.level = level; }

		private BlockState state(BlockPos pos) { return states.computeIfAbsent(pos, level::getBlockState); }

		private static List<AABB> boxes(VoxelShape s, BlockPos pos) {
			if (s.isEmpty()) return List.of();
			List<AABB> out = new ArrayList<>();
			for (AABB b : s.toAabbs()) out.add(b.move(pos));
			return out;
		}

		@Override public List<AABB> collision(int x, int y, int z) {
			BlockPos pos = new BlockPos(x, y, z);
			return boxes(state(pos).getCollisionShape(level, pos), pos);
		}

		@Override public List<AABB> outline(int x, int y, int z) {
			BlockPos pos = new BlockPos(x, y, z);
			return boxes(state(pos).getShape(level, pos), pos);
		}

		@Override public int flags(int x, int y, int z) {
			BlockPos pos = new BlockPos(x, y, z);
			BlockState st = state(pos);
			Block b = st.getBlock();
			int f = 0;
			if (!st.getFluidState().isEmpty()) f |= PenShape.FLUID;
			if (st.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) f |= PenShape.WATER;
			// water that runs on: a source stands, and every waterlogged block holds one (PenShape.runsOut)
			if (!st.getFluidState().isEmpty() && !st.getFluidState().isSource()) f |= PenShape.FLOWING;
			if (b instanceof HopperBlock || b instanceof ChestBlock || b instanceof BarrelBlock) f |= PenShape.COLLECTOR;
			if (b instanceof DoorBlock || b instanceof FenceGateBlock || b instanceof TrapDoorBlock) f |= PenShape.OPENS;
			// of those what the walker opens, Follow.openable's rule: no iron door, no trapdoor (PenShape.throughDoor)
			if (b instanceof DoorBlock d ? d.type().canOpenByHand() : b instanceof FenceGateBlock) f |= PenShape.OPENABLE;
			if (Grinder.hazard(level, pos)) f |= PenShape.HAZARD;
			return f;
		}

		@Override public double stand(int x, int y, int z) {
			return stand.computeIfAbsent(new BlockPos(x, y, z), pos -> Follow.floor(level, pos));
		}

		@Override public boolean loaded(int x, int z) { return level.hasChunk(x >> 4, z >> 4); }
	}

	// ---------- starting and stopping ----------

	private static void start(Hok h, String door, long now) {
		if (hok != null) stop("another pen", false);
		if (Grinder.active()) Grinder.stop("pen");
		Work.reset("pen");
		hok = h;
		h.since = now;
		h.fence = fenceCells(AfkHelperClient.mc().level, h);
		if (Guard.enemy() != null && claims(Guard.enemy())) Guard.stop("pen");
		hits = 0; killed = 0; started = now;
		badSpot.clear(); retreats.clear(); research = null; recheckFails = 0;
		// where to go when it gets dangerous is worked on from the start, a little each tick: a retreat on the way to the
		// first spot, the night, too dangerous all want it, and without it the buddy only backed off straight from the pen
		retreatCell = null; retreatHidden = false; retreatSearched = false; retreatSearch = null;
		Level level = AfkHelperClient.mc().level;
		if (level != null) { retreatSearch = new RetreatSearch(level, h); retreatSearched = true; }
		emptySince = 0; waitTold = false; waitSpotTold = false; centreLooked = false; unreachableSince = 0; inReachAt = now;
		nextRound = now + roundGap(); lastSwitch = 0; nextStatus = now + STATUS; nextRecheck = now + RECHECK; blockHash = 0;
		lastPos = null; lastTick = -100; lastHealth = -1; ownerAwaySince = 0; threatId = -1;
		axeHeld = false; heldOffTold = false; axeAt = 0; bystanderAt = 0;
		clearCreepers();
		tail = null; tailUntil = 0; fellCols = null;
		Follow.fence(h.fence, now + 2000);
		persist(h, true);
		Exchange.emit("pen", "action", "on", "by", door, "size", h.flood.size(), "x", h.flood.minX(), "y", Mth.floor(h.floor()), "z", h.flood.minZ());
		go("to a spot", now);
	}

	/** Every pen column from three under its floor to one over its shaft, and the doors and gates beside it. */
	private static Set<BlockPos> fenceCells(Level level, Hok h) {
		Set<BlockPos> out = new LinkedHashSet<>();
		int lo = Mth.floor(h.floor()) - 3, hi = Mth.ceil(h.top) + 1;
		for (long c : h.cols) for (int y = lo; y <= hi; y++) out.add(new BlockPos(PenShape.kx(c), y, PenShape.kz(c)));
		if (level != null) {
			int[][] dirs = { { 0, -1 }, { 1, 0 }, { 0, 1 }, { -1, 0 } };
			for (long c : h.cols) for (int[] d : dirs) {
				int x = PenShape.kx(c) + d[0], z = PenShape.kz(c) + d[1];
				if (h.flood.has(x, z)) continue;
				for (int y = Mth.floor(h.floor()) - 1; y <= Mth.floor(h.floor()) + 3; y++) {
					Block b = level.getBlockState(new BlockPos(x, y, z)).getBlock();
					if (b instanceof DoorBlock || b instanceof FenceGateBlock || b instanceof TrapDoorBlock) out.add(new BlockPos(x, y, z));
				}
			}
		}
		return out;
	}

	static void stop(String why) { stop(why, false); }

	/**
	 * Off; with {@code backoff} (the sign, the panel's off, the player away) to the cell the retreat's search found:
	 * the one no column of the pen sees, or with none the farthest it found within ten. Two blocks back the pen's mobs
	 * still see the buddy, and the fight leaves them be there; the plain step back of two blocks only while that search is not
	 * done yet.
	 */
	static void stop(String why, boolean backoff) {
		LocalPlayer p = AfkHelperClient.mc().player;
		stop(why, backoff, backoff && p != null && hok != null ? retreatCell(p, SEARCH_BUDGET * 4) : null);
	}

	/**
	 * Off. With {@code backoff} the buddy first steps back from the pen (the sign, the panel's off, the player away: then a
	 * wait; too dangerous: then a wait once there), to {@code to} when that is given (the cell out of its mobs' sight, as
	 * the walk's own time allows); otherwise the chain takes over and following resumes. The fence stays half a minute
	 * more, so the first steps after it do not go over the pen; after a fall into it at once, so that digging or
	 * pillaring out is possible.
	 */
	private static void stop(String why, boolean backoff, BlockPos to) {
		if (pending != null && !pending.probe) pending = null;
		if (state == OFF) return;
		long now = System.currentTimeMillis();
		Minecraft mc = AfkHelperClient.mc();
		LocalPlayer p = mc.player;
		if (state == BACKOFF_STATE) {
			// the stay that comes with the sign, the panel's off or the player away: a step back first, and the stay
			// holds after it; anything else that stops the buddy now ends the step back
			if (!why.equals("stays")) endBackoff();
			return;
		}
		clearCreepers();
		Hok h = hok;
		HeldKeys.set(mc.options.keyUp, false);
		HeldKeys.set(mc.options.keyDown, false);
		HeldKeys.set(mc.options.keyLeft, false);
		HeldKeys.set(mc.options.keyRight, false);
		// crouched on an edge, the crouch stays until the walker takes the keys again: let go there and the buddy steps off
		boolean onEdge = p != null && spot != null && spot.edge() && spot.at().distanceTo(p.position()) < 0.6;
		if (!onEdge) HeldKeys.set(mc.options.keyShift, false);
		look.stop();
		axeHeld = false;   // the axe taken for someone beside the mob: Guard.arm brings the best weapon back
		if (h != null) {
			long minutes = (now - started) / 60_000;
			if (GIVES_UP.contains(why)) {
				Exchange.emit("pen", "action", "gives up", "reason", why, "killed", killed, "hits", hits, "minutes", minutes,
					"x", p == null ? 0 : p.getBlockX(), "y", p == null ? 0 : p.getBlockY(), "z", p == null ? 0 : p.getBlockZ());
			} else {
				Exchange.emit("pen", "action", "off", "reason", why, "killed", killed, "hits", hits, "minutes", minutes);
			}
			if (!why.equals("new connection")) persist(h, false);
			if (why.equals("fell into the pen")) {
				Follow.unfence();
				tail = null;
				tailUntil = 0;
			} else {
				Follow.fence(h.fence, now + FENCE_TAIL);
				tail = h;
				tailUntil = now + FENCE_TAIL;
			}
		}
		hok = null;
		spot = null;
		research = null;
		if (backoff && p != null && h != null) {
			state = BACKOFF_STATE;
			Work.walkFresh();
			stateSince = now;
			backoffSince = now;
			// a hold from its first tick (a fight ahead of the chain, a creeper's getaway) counts as one too (backoffTick)
			backoffSeen = now;
			heldSince = 0;
			backoffStay = null;
			backoffCell = to != null ? to : backoffCell(p, h);
			// a step back is a few blocks; the way to the cell out of sight up to ten, so that gets a walk's own time
			backoffLimit = to == null ? BACKOFF_MAX : backoffTime(Vec3.atBottomCenterOf(to).distanceTo(p.position()));
			// where it begins, for the teleport test of watch
			backoffPos = p.position();
			backoffPosTick = Follow.ticks();
			backoffDim = Work.dimOf(p.level());
			tail = h;
			tailUntil = now + FENCE_TAIL;
			if (backoffCell == null) endBackoff();
		} else state = OFF;
	}

	private static void endBackoff() {
		state = OFF;
		backoffCell = null;
		backoffStay = null;
		backoffPos = null;
		clearCreepers();
		Follow.stand();
	}

	/**
	 * The stay is lifted (Follow.setStaying(false): the follow sign, {@code volg blijf:false}, an unstay), or a new
	 * {@code !follow} (Follow.start) is the newest word to follow, whether staying or not: a step back under way ends at
	 * its next tick (backoffTick), after a creeper's getaway that is under way, and one that was to end in a stay (too
	 * dangerous) ends without it. Until the third review of 22 Oct 2026 the buddy walked on to the cell out of the pen's sight
	 * first, up to fifteen seconds away from the player; until the review of the fourth round that day a {@code !follow}
	 * did nothing here unless staying, and during a too-dangerous step back the buddy follows, so that one still ended in its
	 * stay. Not Pen.stop: that would end a pen the panel started during a stay. Outside a step back nothing reads
	 * backoffStay.
	 */
	static void stayLifted() { backoffStay = null; }

	/** A new connection: what was done means nothing in the new world; a pen that was on comes back once the buddy stands at it. */
	static void newConnection() {
		if (state != OFF) stop("new connection", false);
		state = OFF;
		pending = null;
		blows.clear();
		deaths.clear();
		hitMobs.clear();
		victims.clear();
		tail = null;
		tailUntil = 0;
		fellCols = null;
		clearCreepers();
		forgetCover();
		coverRepeats.clear();
		coverSource = null;
		Follow.unfence();
		AfkHelperClient.later(RESUME_AT, Pen::tryResume);
	}

	/** Forget the pen of this player on this server: the entry goes, and a pen of that player's that runs stops. */
	static boolean forget(String owner) {
		if (hok != null && hok.owner.equalsIgnoreCase(owner)) stop("forgotten", false);
		JsonObject all = read();
		boolean had = all.remove(key(owner)) != null;
		if (had) write(all);
		return had;
	}

	// ---------- every tick ----------

	/**
	 * The farm step of the chain (after the taps, before the carrier): true while it keeps the buddy busy. Paused for the
	 * night without a bed to go to, it gives the tick back, so that the sleep step can go to bed.
	 */
	static boolean tick(Minecraft mc, LocalPlayer p, AfkConfig c, Player owner) {
		if (state == OFF) return false;
		long now = System.currentTimeMillis();
		int tick = Follow.ticks();
		// a swelling creeper first, in every state of the pen: away from it, while it would go off before the blow kills it.
		// The chain's creeper step (creeper) has done it already this tick, before the fight; here for a tick without it
		if ((state == RESUMING || state == BACKOFF_STATE) && creeperRanAt != tick && creeperTick(mc, p, now, false)) return true;
		if (state == RESUMING) return resumeTick(mc, p, owner, now);
		if (state == BACKOFF_STATE) return backoffTick(mc, p, now);
		String why = guard(p, owner, now, tick);
		if (why != null) {
			stop(why, false);
			return false;
		}
		// the fence and a fall into the pen are watch's, every tick
		if (tick % 20 == 0 && state != NIGHT) every20(mc, p, owner, now);
		if (hok == null) return state != OFF;   // stopped by the re-check, or the player away (then a step back next tick)
		damageTick(p, now);
		if (trace && tick % TRACE_EVERY == 0) trace(p, now);
		if (now >= nextStatus) {
			nextStatus = now + STATUS;
			Exchange.emit("pen", "action", "status", "killed", killed, "hits", hits, "mobs", mobs(p).size(), "minutes", (now - started) / 60_000,
				"phase", PHASE[state]);
		}
		if (creeperRanAt != tick && creeperTick(mc, p, now, false)) return true;
		// no farming under a getting away that is still on: the creeper step gives its tick to a fight only (while waiting
		// out a creeper, or on the walk to the cell), and the fight step always takes it, so this is a guard
		if (creeperRanAt == tick && evadeId != -1) return true;
		if (state == OFF || hok == null) return state != OFF;   // too dangerous: stopped, and stepping back next tick
		// not while striking a swelling creeper: the pause stops the look, and the strike would end in a walk off with
		// too little of its fuse left (the third review of 22 Oct 2026)
		if (state != NIGHT && state != RETREAT && urgent == null && nightCheck(p, now)) return nightTick(mc, p, now);
		return switch (state) {
			case GOING, SWITCHING -> goTick(mc, p, now);
			case LEAN_IN -> leanTick(mc, p, now);
			case FARMING -> farmTick(mc, p, now);
			case RETREAT -> retreatTick(mc, p, now);
			case NIGHT -> nightTick(mc, p, now);
			default -> false;
		};
	}

	/**
	 * What ends the pen whatever happens: someone else followed, death, another world, a teleport, too far. Death and a
	 * fall into the pen are watch's too, which comes every tick, also while a fight or a death holds this step back.
	 */
	private static String guard(LocalPlayer p, Player owner, long now, int tick) {
		Hok h = hok;
		if (h == null) return "pen gone";
		if (Follow.target() == null || !h.owner.equalsIgnoreCase(Follow.target())) return "following off";
		if (!p.isAlive()) return "dead";
		if (!Work.dimOf(p.level()).equals(h.dim)) return "another dimension";
		boolean consecutive = tick - lastTick <= 1;
		Vec3 was = lastPos;
		lastPos = p.position();
		lastTick = tick;
		if (consecutive && was != null && was.distanceTo(p.position()) > TELEPORT) return "teleported";
		if (h.flat(p.position()) > (state == NIGHT ? LEASH_NIGHT : LEASH)) return "away from the pen";
		return null;
	}

	/** Once a second: the weapon, what is lacking, whether the pen still is one, the player away. */
	private static void every20(Minecraft mc, LocalPlayer p, Player owner, long now) {
		Hok h = hok;
		Guard.arm(mc, p, false);
		Work.needsTick(p);
		int hash = blockHash(p.level(), h);
		boolean changed = blockHash != 0 && hash != blockHash;
		blockHash = hash;
		if (changed || now >= nextRecheck) {
			nextRecheck = now + RECHECK;
			recheck(p, changed);
			// a pen that changed: the second look within seconds (it was ten, in which the buddy walked to spots the blast had taken)
			if (recheckFails > 0) nextRecheck = now + RECHECK_AGAIN;
		}
		if (hok == null) return;
		if (research != null && research.step(SEARCH_BUDGET)) {
			PenShape.Result r = research.result();
			if (!r.spots().isEmpty()) h.result = r;
			research = null;
		}
		int away = AfkConfig.get().penOwnerAwayMin;
		if (away > 0) {
			boolean gone = owner == null && !Follow.ownerOnline();
			if (!gone) ownerAwaySince = 0;
			else if (ownerAwaySince == 0) ownerAwaySince = now;
			else if (now - ownerAwaySince > away * 60_000L) { stop("owner away", true); Follow.stayHere("owner away"); }
		}
		if (!AfkConfig.get().followMobFarm) stop("switched off", false);
	}

	private static int blockHash(Level level, Hok h) {
		int out = 17;
		int x1 = h.flood.minX() - 1, x2 = h.flood.maxX() + 1, z1 = h.flood.minZ() - 1, z2 = h.flood.maxZ() + 1;
		int y1 = Mth.floor(h.floor()) - 1, y2 = Math.min(Mth.ceil(h.top) + 1, Mth.floor(h.floor()) + 12);
		for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) for (int y = y1; y <= y2; y++)
			out = out * 31 + Block.getId(level.getBlockState(new BlockPos(x, y, z)));
		return out == 0 ? 1 : out;
	}

	/**
	 * The pen once more: from its own columns with a mob of its size, on its floor. Another set of columns, a floor that
	 * moved, or no farm any more, twice in a row: it is gone. Changed but the same pen: the spots worked out again, while
	 * the farming goes on.
	 */
	private static void recheck(LocalPlayer p, boolean changed) {
		Hok h = hok;
		Level level = p.level();
		LevelWorld w = new LevelWorld(level);
		long first = h.cols.get(0);
		PenShape.Mob m = PenShape.Mob.at(h.w, h.h, h.eye, PenShape.kx(first), PenShape.kz(first), h.floor());
		PenShape.Flood f = PenShape.flood(w, m, h.floor());
		boolean same = f.closed() && f.cols().equals(h.flood.cols());
		if (same) for (long c : f.cols()) if (Math.abs(f.floorOf().get(c) - h.flood.floorOf().get(c)) > 0.1) same = false;
		PenShape.Evidence ev = null;
		if (same) {
			int[] crowd = { 0 };
			boolean weak = weakenedInPen(level, f, m, crowd);
			ev = PenShape.evidence(w, f, m, weak || h.ev.why().contains("weakened"), crowd[0], cart(level, f));
			if (!ev.enough()) same = false;
		}
		if (!same) {
			if (++recheckFails >= 2) stop("pen gone", false);
			return;
		}
		recheckFails = 0;
		h.ev = ev;
		if (changed && research == null) research = new PenShape.Search(w, h.flood, m, ev);
	}

	// ---------- walking to a spot ----------

	/** A free spot: its ground still there, nobody else in it, not bad lately, not a goal the walker gave up on. */
	private static boolean free(LocalPlayer p, PenShape.Spot s, long now) {
		Long bad = badSpot.get(s.cell());
		if (bad != null && bad > now) return false;
		if (!grounded(p.level(), s)) return false;
		if (Failures.blocked(Work.walkName("pen-", s.cell().getX() + 0.5, s.feet(), s.cell().getZ() + 0.5))) return false;
		for (Player o : p.level().players()) {
			if (o == p) continue;
			if (Math.hypot(o.getX() - s.at().x, o.getZ() - s.at().z) < 1.0 && Math.abs(o.getY() - s.feet()) < 1.5) return false;
		}
		return true;
	}

	/**
	 * The spot's ground as it was worked out. A creeper that blew the rim away, a block broken under it or a slab put on
	 * it make it no spot to walk to: another is taken, while the recheck tells the pen gone or works its spots out anew.
	 * Without this the buddy walked after an explosion to a spot over the crater, was held at the arrival by the edge of the
	 * block beside it, and fell in (the test of 22 Oct 2026).
	 */
	private static boolean grounded(Level level, PenShape.Spot s) {
		double f = Follow.floor(level, s.cell());
		return !Double.isNaN(f) && Math.abs(f - s.feet()) < 0.1;
	}

	/** Mobs of the pen per column now, for a spot's worth. */
	private static Map<Long, Integer> perColumn(List<LivingEntity> mobs) {
		Map<Long, Integer> m = new HashMap<>();
		for (LivingEntity e : mobs) m.merge(PenShape.key(Mth.floor(e.getX()), Mth.floor(e.getZ())), 1, Integer::sum);
		return m;
	}

	/**
	 * The best free spot: none within swelling distance of a creeper that could not be struck in time, nor of two creepers
	 * (noSpot), while another is free, no creeper's spot while a creeper is in the pen, then what it reaches of the mobs
	 * there now, then nearest.
	 */
	private static PenShape.Spot choose(LocalPlayer p, long now, PenShape.Spot not) {
		Hok h = hok;
		List<LivingEntity> mobs = mobs(p);
		Map<Long, Integer> per = perColumn(mobs);
		boolean creeper = false;
		for (LivingEntity e : mobs) if (e instanceof Creeper) creeper = true;
		List<Creeper> strong = unstrikeable(p), all = creepersByPen(p);
		PenShape.Spot best = null;
		double bestScore = 0;
		for (PenShape.Spot s : h.result.spots()) {
			if (not != null && s.cell().equals(not.cell())) continue;
			if (!free(p, s, now)) continue;
			double worth = 0;
			for (int i = 0; i < h.cols.size(); i++) if ((s.reach() & (1L << i)) != 0) worth += per.isEmpty() ? 1 : per.getOrDefault(h.cols.get(i), 0) + 0.01;
			double score = (noSpot(s.at(), strong, all) ? -1e9 : 0) + (creeper && s.creeper() ? 0 : 1_000_000) + worth * 1000 - s.at().distanceTo(p.position());
			if (best == null || score > bestScore) { best = s; bestScore = score; }
		}
		return best;
	}

	private static void go(String why, long now) {
		LocalPlayer p = AfkHelperClient.mc().player;
		if (p == null || hok == null) return;
		PenShape.Spot s = choose(p, now, null);
		if (s == null) {
			if (allBad(now)) { giveUpNoSpot(now); return; }
			// every spot taken: stand in place and look again in a second
			if (!waitSpotTold) { waitSpotTold = true; Exchange.emit("pen", "action", "waits for a spot"); }
			spot = null;
			setState(GOING, now);
			goSince = now;
			wentTick = Follow.ticks();
			return;
		}
		goTo(s, why, now);
	}

	private static void goTo(PenShape.Spot s, String why, long now) {
		LocalPlayer p = AfkHelperClient.mc().player;
		wentTick = Follow.ticks();
		spot = s;
		goWhy = why;
		goSince = now;
		Work.walkFresh();
		double far = p == null ? 0 : p.position().distanceTo(cellAt(s));
		goLimit = Math.max(GO_MIN, GO_MIN + (long) (far * 800));
		waitSpotTold = false;
		setState(why.equals("to a spot") ? GOING : SWITCHING, now);
		if (why.equals("to a spot")) Exchange.emit("pen", "action", "to a spot", "side", side(s.side()), "x", s.cell().getX(), "y", s.cell().getY(), "z", s.cell().getZ());
	}

	private static boolean allBad(long now) {
		for (PenShape.Spot s : hok.result.spots()) {
			Long bad = badSpot.get(s.cell());
			if (bad == null || bad <= now) return false;
		}
		return true;
	}

	private static void giveUpNoSpot(long now) {
		boolean early = now - started < 30_000;
		stop("cannot reach it", false);
		if (early) Follow.respond(Follow.NO);
	}

	/** The middle of the cell's floor: where the walker goes; the lean does the last part. */
	private static Vec3 cellAt(PenShape.Spot s) { return new Vec3(s.cell().getX() + 0.5, s.feet(), s.cell().getZ() + 0.5); }

	private static boolean goTick(Minecraft mc, LocalPlayer p, long now) {
		if (p.isPassenger()) { HeldKeys.set(mc.options.keyShift, !mc.options.keyShift.isDown()); return true; }
		// on the way round the pen too: low after a blow, a spear that reaches the buddy; not under a strike (safety)
		if (urgent == null && safety(mc, p, mobs(p), now)) return true;
		// a swelling creeper of the pen that a blow kills in time (creeperTick): stop, turn to it and strike
		if (urgent != null) {
			standOnWalk(mc, p);
			look.tick(p);
			strike(mc, p, now);
			return true;
		}
		// eating on the way: stand and eat, and neither the walk's time nor the walker's clock runs meanwhile (a
		// walk at a fifth of the speed while eating ran out its time and marked the spot bad, the test of 22 Oct 2026)
		if (AfkHelperClient.isEating()) {
			standOnWalk(mc, p);
			if (eatFrom == 0) eatFrom = now;
			return true;
		}
		if (eatFrom != 0) {
			if (eatFrom >= goSince) { goSince += Math.min(now - eatFrom, 10_000); Work.walkFresh(); }
			eatFrom = 0;
		}
		if (spot == null) {
			standOnWalk(mc, p);
			if (now - goSince > 1000) { goSince = now; go("to a spot", now); }
			return true;
		}
		Vec3 at = cellAt(spot);
		// in the spot's own cell, at its height, on its own ground: the lean does the rest, as far as the middle and on.
		// The walker stops on entering a goal's cell, at its edge as often as not: with only the cell's middle as there
		// the buddy stood five seconds in it and gave the spot up as not reached (the test of 22 Oct 2026)
		boolean inCell = Mth.floor(p.getX()) == spot.cell().getX() && Mth.floor(p.getZ()) == spot.cell().getZ();
		if (inCell && Math.abs(at.y - p.getY()) < ARRIVE_V && p.onGround() && grounded(p.level(), spot)) { startLean(p, now); return true; }
		if (!free(p, spot, now)) { go(goWhy, now); return true; }
		// close enough for the walker is not there yet: the spot has to be reached (Work.approach stops at 1.6)
		walkTick = Follow.ticks();
		if (!Work.approach(mc, p, "pen-" + spot.cell().toShortString(), at, now) || now - goSince > goLimit) {
			badSpot.put(spot.cell(), now + SPOT_BAD);
			Exchange.emit("pen", "action", "spot not reached", "x", spot.cell().getX(), "y", spot.cell().getY(), "z", spot.cell().getZ());
			PenShape.Spot next = choose(p, now, spot);
			if (next == null) { giveUpNoSpot(now); return false; }
			goTo(next, goWhy, now);
		}
		return true;
	}

	/**
	 * The lean begins where the walk ends, crouched as it came: Follow.stand lets go of every key, crouch too, and the
	 * lean's own first tick pressed it again a tick later, a flicker of the key at every arrival (the test of 22 Oct 2026).
	 */
	private static void startLean(LocalPlayer p, long now) {
		walkTick = Follow.ticks();
		Follow.stand();
		HeldKeys.set(AfkHelperClient.mc().options.keyShift, !p.isInWater());
		setState(LEAN_IN, now);
		leanTicks = 0;
		leanStill = 0;
		leanLast = p.position();
		look.startByAngle(p, new Vec3(hok.centre.x, p.getEyeY(), hok.centre.z), 0.5f);
	}

	/**
	 * Round the pen crouched, as a player walks round a pit: its walk to a spot and the lean, from the first step to the
	 * end of the lean, within CROUCH_NEAR of it (a walk from farther off from there on), never while getting away from a
	 * creeper. The walker (Follow.walk) crouches then, also in a cell beside none of the pen's columns, where it let go of
	 * crouch at every corner of a small pen, three or four times a second on a round (the test of 22 Oct 2026); it still
	 * stands up for a drop, in water and on a ladder.
	 */
	static boolean crouchWalk(LocalPlayer p) {
		Hok h = hok;
		if (h == null || p == null || evadeId != -1 || !(state == GOING || state == SWITCHING || state == LEAN_IN)) return false;
		// the pen's own walk, this tick or the last: a fight's, the corpse guard's or a tap's walk in those states is not
		// crouched (the check of the third review of 22 Oct 2026: the buddy chased a zombie at a sneak's speed)
		if (Follow.ticks() - walkTick > 1) return false;
		return Work.dimOf(p.level()).equals(h.dim) && h.flat(p.position()) <= CROUCH_NEAR && Math.abs(p.getY() - h.floor()) < CROUCH_NEAR + 4;
	}

	/**
	 * The getaway's own walk drives the buddy this tick: the walker does not crouch beside the pen then (Follow.walk). At a
	 * sneak's pace off a pit's rim, getting out of the sight of the creeper in it took half a second and more, and it went off
	 * (the test of 22 Oct 2026); the walk off was meant to stand up all along (evadeTick), and the walker crouched it
	 * again. Not the whole getaway: a fight's walk in a tick it gives to the fight crouches beside the fence as any walk.
	 */
	static boolean gettingAway() { return evadeId != -1 && evadeWalkTick == Follow.ticks(); }

	/** The tick the getaway's own walk last drove the walker (evadeTick, before its Work.approach): gettingAway. */
	private static int evadeWalkTick = -10;

	/** A drop walked down and back up again (Pen.walkable): more than this below the feet in the air is a gap. */
	static final double GAP = 1.25;

	/**
	 * In the air over a gap: more than GAP over the floor below, or none within four (a jump the walker makes across
	 * a ditch, a fall). A
	 * getaway's pick there measured the walk from the ditch's floor, where no walkable cell lies, took a cell down
	 * there, and the walk begun anew let go of the jump's keys, so that the buddy dropped in (the review of 22 Oct 2026): no
	 * pick over a gap, the walk goes on, and a bout that begins there picks on landing (evadePickDue). A hop on flat
	 * ground or a step down is no gap, and a pick then measures from the landing (Getaway.standing).
	 */
	private static boolean overGap(LocalPlayer p) {
		if (p.onGround() || p.isInWater() || p.onClimbable()) return false;
		double g = PenShape.floor(new LevelWorld(p.level()), new PenShape.Mob(2 * PenShape.HALF, PenShape.STAND_H, PenShape.STAND_EYE, p.getBoundingBox(), false));
		return Double.isNaN(g) || p.getY() - g > GAP;
	}

	/** Standing still on a walk of the pen (to eat, to strike, to wait for a spot), crouched where the walk would be. */
	private static void standOnWalk(Minecraft mc, LocalPlayer p) {
		walkTick = Follow.ticks();
		Follow.stand();
		if (crouchWalk(p) && !p.isInWater()) HeldKeys.set(mc.options.keyShift, true);
	}

	/**
	 * The last bit, crouched (vanilla's edge keeps a crouched player on the block): onto the spot as it was worked out,
	 * up to 0.3 nearer the pen than the middle of the cell, by the keys as the head points.
	 */
	private static boolean leanTick(Minecraft mc, LocalPlayer p, long now) {
		walkTick = Follow.ticks();
		var o = mc.options;
		if (urgent == null && safety(mc, p, mobs(p), now)) return true;
		look.tick(p);
		HeldKeys.set(o.keyShift, true);
		HeldKeys.set(o.keySprint, false);
		// a swelling creeper of the pen that a blow kills in time: the lean waits, crouched in place, for the strike
		if (urgent != null) {
			HeldKeys.set(o.keyUp, false); HeldKeys.set(o.keyDown, false); HeldKeys.set(o.keyLeft, false); HeldKeys.set(o.keyRight, false);
			strike(mc, p, now);
			return true;
		}
		double dx = spot.at().x - p.getX(), dz = spot.at().z - p.getZ(), d = Math.hypot(dx, dz);
		Vec3 now3 = p.position();
		leanStill = leanLast != null && now3.distanceTo(leanLast) < 0.01 ? leanStill + 1 : 0;
		leanLast = now3;
		if (d < LEAN || (leanTicks > 2 && leanStill >= 2) || ++leanTicks > LEAN_TICKS) {
			HeldKeys.set(o.keyUp, false); HeldKeys.set(o.keyDown, false); HeldKeys.set(o.keyLeft, false); HeldKeys.set(o.keyRight, false);
			boolean crouch = spot.crouch() || spot.edge();
			HeldKeys.set(o.keyShift, crouch);
			setState(FARMING, now);
			emptySince = 0;
			nextHit = Math.max(nextHit, now + 200 + R.nextInt(400));
			Exchange.emit("pen", "action", "at the spot", "side", side(spot.side()), "crouched", crouch, "columns", spot.columns(),
				"x", round3(p.getX()), "y", round3(p.getY()), "z", round3(p.getZ()));
			return true;
		}
		double yaw = Math.toRadians(p.getYRot());
		double forward = -Math.sin(yaw) * dx + Math.cos(yaw) * dz, right = -Math.cos(yaw) * dx - Math.sin(yaw) * dz;
		HeldKeys.set(o.keyUp, forward > 0.03);
		HeldKeys.set(o.keyDown, forward < -0.03);
		HeldKeys.set(o.keyRight, right > 0.03);
		HeldKeys.set(o.keyLeft, right < -0.03);
		return true;
	}

	// ---------- farming ----------

	/** The mobs the buddy may kill in the pen now. */
	private static List<LivingEntity> mobs(LocalPlayer p) {
		Hok h = hok;
		if (h == null) return List.of();
		AABB scan = new AABB(h.flood.minX() - 0.5, h.floor() - 1, h.flood.minZ() - 0.5, h.flood.maxX() + 1.5, h.floor() + h.h + 2, h.flood.maxZ() + 1.5);
		return p.level().getEntitiesOfClass(LivingEntity.class, scan, e -> huntable(e, h, p));
	}

	/**
	 * A mob of the pen the buddy may kill: Grinder's kinds (hostile, not named, no pet, no villager or player), less the bosses,
	 * the warden, a shulker, a ghast, a phantom and a creaking; a guardian only when a blow kills it; a mob that is only
	 * angry when hit (an enderman, a zombified piglin, a piglin) only when it already is, or when a blow kills it; no
	 * zombie villager being cured. In the pen: its box over the columns, its feet at the floor (a spider climbs higher),
	 * and not still falling (a spider on the wall, Spider.onClimbable, does not fall: it is taken up to h + 1).
	 */
	static boolean huntable(Entity e, Hok h, LocalPlayer p) {
		if (!Grinder.candidate(e)) return false;
		if (excluded(e) || e instanceof Ghast || e instanceof Phantom) return false;
		LivingEntity le = (LivingEntity) e;
		boolean oneHit = le.getHealth() <= blow(p);
		if (e instanceof Guardian && !oneHit) return false;
		if ((e instanceof NeutralMob || e instanceof AbstractPiglin) && !(e instanceof Mob m && m.isAggressive()) && !oneHit) return false;
		if (e instanceof ZombieVillager zv && zv.isConverting()) return false;
		AABB b = e.getBoundingBox();
		double top = e instanceof Spider ? h.floor() + h.h + 1 : h.floor() + 1.2;
		if (b.minY < h.floor() - 0.5 || b.minY > top) return false;
		boolean over = false;
		for (long c : h.cols) {
			int x = PenShape.kx(c), z = PenShape.kz(c);
			if (b.maxX > x && b.minX < x + 1 && b.maxZ > z && b.minZ < z + 1) { over = true; break; }
		}
		if (!over) return false;
		Double floor = h.flood.floorOf().get(PenShape.key(Mth.floor(e.getX()), Mth.floor(e.getZ())));
		return e.onGround() || le.onClimbable() || floor == null || b.minY - floor <= 1.2;
	}

	/**
	 * What one blow with what is in hand does at full strength: one, the attack modifiers of the item, and those of
	 * Strength (+3 a level) and Weakness (-4 a level) on the buddy, never under nothing, as Player.attack takes the attribute.
	 * The client counts the effects itself: it applies no effect's modifiers to its own attributes
	 * (LivingEntity.onEffectAdded) and is not sent attack damage. Without them a witch's Weakness had the buddy stay beside a
	 * creeper a blow did not kill (the third review of 22 Oct 2026).
	 */
	private static double blow(LocalPlayer p) {
		double damage = 1;
		ItemStack st = p.getMainHandItem();
		for (ItemAttributeModifiers.Entry en : st.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY).modifiers())
			if (en.slot().test(EquipmentSlot.MAINHAND) && en.attribute().value() == Attributes.ATTACK_DAMAGE.value()) damage += en.modifier().amount();
		MobEffectInstance strong = p.getEffect(MobEffects.STRENGTH), weak = p.getEffect(MobEffects.WEAKNESS);
		if (strong != null) damage += 3.0 * (strong.getAmplifier() + 1);
		if (weak != null) damage -= 4.0 * (weak.getAmplifier() + 1);
		return Math.max(0, damage);
	}

	private static boolean farmTick(Minecraft mc, LocalPlayer p, long now) {
		var o = mc.options;
		Hok h = hok;
		// knocked off the spot, or a fight took the buddy away: back to it
		if (Math.hypot(spot.at().x - p.getX(), spot.at().z - p.getZ()) > 0.9 || Math.abs(spot.feet() - p.getY()) > 0.9) {
			go("to a spot", now);
			return true;
		}
		HeldKeys.set(o.keyShift, spot.crouch() || spot.edge());
		HeldKeys.set(o.keyUp, false);
		HeldKeys.set(o.keySprint, false);
		boolean eating = AfkHelperClient.isEating() || AfkHelperClient.lookBusy();
		if (!eating) look.tick(p);
		else look.stop();
		retreatCell(p, 2_000_000L);
		// someone in the spot: another side
		for (Player other : p.level().players()) {
			if (other == p) continue;
			if (Math.hypot(other.getX() - spot.at().x, other.getZ() - spot.at().z) < 1.0 && Math.abs(other.getY() - spot.feet()) < 1.5) {
				PenShape.Spot next = choose(p, now, spot);
				if (next != null) {
					switchTo(next, other.getGameProfile().name().equalsIgnoreCase(h.owner) ? "owner" : "player", now);
					return true;
				}
			}
		}
		List<LivingEntity> mobs = mobs(p);
		if (urgent == null && safety(mc, p, mobs, now)) return true;
		// the swelling creeper of the pen that a blow kills in time (creeperTick chose it): before anything else
		if (urgent != null) { strike(mc, p, now); return true; }
		if (mobs.isEmpty()) { waiting(p, now); return true; }
		if (waitTold) {
			Exchange.emit("pen", "action", "mobs again");
			waitTold = false;
		}
		emptySince = 0;
		centreLooked = false;
		Vec3 eye = p.getEyePosition();
		// the six nearest, then: what hurt the buddy or the player lately, a swelling creeper, any creeper, what shoots, the
		// weakest, the smallest turn
		List<LivingEntity> near = new ArrayList<>(mobs);
		near.sort(Comparator.comparingDouble(e -> e.distanceToSqr(eye)));
		if (near.size() > 6) near = near.subList(0, 6);
		LivingEntity target = null;
		Vec3 aim = null;
		double best = Double.MAX_VALUE;
		boolean anyInReach = false;
		long cols = spot.reach();
		boolean unreachedMob = false;
		for (LivingEntity e : near) {
			Vec3 pt = aimAt(p, e);
			if (pt == null) continue;
			anyInReach = true;
			double score = (e.getId() == threatId && now - threatAt < THREAT_FRESH ? 0 : 1e7)
				+ (e instanceof Creeper c && c.getSwellDir() > 0 ? 0 : 1e6)
				+ (e instanceof Creeper ? 0 : 1e5)
				+ (e instanceof AbstractSkeleton || e instanceof Witch || e instanceof Pillager || e instanceof Blaze || e instanceof Breeze ? 0 : 1e4)
				+ e.getHealth() * 100 + turn(p, pt);
			if (score < best) { best = score; target = e; aim = pt; }
		}
		for (LivingEntity e : mobs) {
			int ci = h.cols.indexOf(PenShape.key(Mth.floor(e.getX()), Mth.floor(e.getZ())));
			if (ci >= 0 && (cols & (1L << ci)) == 0) unreachedMob = true;
		}
		if (anyInReach) inReachAt = now;
		// a mob where this spot does not reach, and nothing in reach: another side (walking round, as the AFK farm did)
		if (unreachedMob && !anyInReach) {
			if (unreachableSince == 0) unreachableSince = now;
			if (now - unreachableSince > UNREACHABLE && now - lastSwitch > SWITCH_MIN) {
				PenShape.Spot next = reaching(p, mobs, now);
				if (next != null) { switchTo(next, "reach", now); return true; }
			}
		} else unreachableSince = 0;
		if (rounds(p, now)) return true;
		if (target == null) return true;
		if (!eating && !look.busy() && angle(p, aim) > 2) look.startByAngle(p, aim, 0.1f);
		hitTick(mc, p, target, now);
		return true;
	}

	/** A point of its box in sight within reach, the one nearest to the look; an enderman only low, never at its eyes. */
	private static Vec3 aimAt(LocalPlayer p, LivingEntity e) {
		AABB b = e.getBoundingBox();
		Vec3 eye = p.getEyePosition();
		if (e instanceof Enderman) b = new AABB(b.minX, b.minY, b.minZ, b.maxX, b.minY + (b.maxY - b.minY) * 0.45, b.maxZ);
		Vec3 pt = Grinder.aimPoint(b, eye, p.level(), p, false, REACH, p.getViewVector(1f));
		if (pt == null) return null;
		if (e instanceof Enderman) {
			Vec3 eyes = e.getEyePosition(), to = pt.subtract(eye).normalize(), toEyes = eyes.subtract(eye).normalize();
			if (Math.toDegrees(Math.acos(Mth.clamp(to.dot(toEyes), -1, 1))) < 15) return null;
		}
		return pt;
	}

	private static double angle(LocalPlayer p, Vec3 at) {
		Vec3 to = at.subtract(p.getEyePosition()).normalize(), view = p.getViewVector(1f);
		return Math.toDegrees(Math.acos(Mth.clamp(to.dot(view), -1, 1)));
	}

	private static double turn(LocalPlayer p, Vec3 at) { return angle(p, at); }

	/**
	 * The blow: with the crosshair on a mob of the pen, the strength back at STRONG and the pace's gap gone by, standing,
	 * not sprinting, not eating, no menu, and what is in hand may hit (no sword beside a bystander: {@link #handReady}); the
	 * hit itself at the start of the next tick, where vanilla hits, and only if the crosshair is still on it then
	 * (Guard.startTick). No jumps for critical hits at the pen: a jump at its edge is a fall. A swelling creeper that a
	 * blow kills in time (urgent) is the one exception to the strength and the pace: struck as soon as the strength
	 * kills it, with nothing else hit meanwhile.
	 */
	private static void hitTick(Minecraft mc, LocalPlayer p, LivingEntity target, long now) {
		// what Guard hit at the start of this tick, if it was one of ours (not a mob on the rim that Guard fought)
		long at = Guard.lastHitAt();
		if (at != lastHitSeen) {
			lastHitSeen = at;
			Entity e = p.level().getEntity(Guard.lastHitId());
			if (e != null && hok != null && hok.holds(e)) {
				hits++; hitsTotal++;
				hitMobs.put(e.getId(), now);
				ClickLog.swing(true);
				nextHit = now + pace.gap(p.getCurrentItemAttackStrengthDelay() * 50.0, R);
			}
		}
		if (dry) return;
		if (p.isUsingItem() || AfkHelperClient.isEating() || p.containerMenu != p.inventoryMenu || !p.onGround() || p.isSprinting()) return;
		LivingEntity on = mc.hitResult instanceof EntityHitResult eh && eh.getEntity() instanceof LivingEntity le && huntable(le, hok, p) ? le : null;
		if (urgent != null) {
			// the swelling creeper: that one only, a moment's reaction after its swell was seen, as soon as the strength kills
			// it (a player's click at a creeper is no rhythm: no pace), with what is in hand and no switch. Once: struck, its
			// death is waited for (struckId); never sooner than a person clicks after the last blow at anything; and not
			// while a hurt of its own would absorb it (Fuse.cooldownWait). It went out once a tick until the client learnt
			// of the death, five at twenty a second (the third review of 22 Oct 2026)
			if (on == urgent && now >= urgentAt && urgent.getId() != struckId && now - Guard.lastHitAt() >= PenShape.MIN_GAP
				&& Fuse.cooldownWait(urgent.damageCooldownTime) == 0 && handWillHit(p, urgent) && blowAt(p, safeStrength(p)) >= urgent.getHealth())
				Guard.queueHit(urgent);
			return;
		}
		if (!handReady(p, on != null ? on : target, now)) return;
		if (on == null || p.getAttackStrengthScale(0.5f) < STRONG || now < nextHit) return;
		Guard.queueHit(on);
	}

	/**
	 * Whether what is in hand may hit now. A sword's full blow sweeps (Player.isSweepAttack: a sword, at full strength, on
	 * the ground), and the sweep would hit the one beside the mob too and push that one over the pit: then, a moment's
	 * reaction after seeing the bystander, an axe of the hotbar (none sweeps), at full strength and the pace as ever; without
	 * one no blow until the sweep's reach is clear. The sword (the best weapon, Guard.arm) again two to five seconds after
	 * it is clear. Not a sword blow at half strength, which does not sweep either: a window missed took a swing at the
	 * air to start the strength again, and a blow that soon after a miss is one vanilla does not let through
	 * (Minecraft.startAttack's missTime of ten ticks).
	 */
	private static boolean handReady(LocalPlayer p, LivingEntity aimed, long now) {
		if (unsafe) return true;
		boolean by = bystander(p, aimed);
		if (by) bystanderAt = now;
		if (p.getMainHandItem().is(ItemTags.SWORDS)) {
			axeHeld = false;   // a sword in hand (a fight's Guard.arm may have brought it back): no axe of the pen's
			if (!by) {
				axeAt = 0;
				if (now - bystanderAt > 5000) heldOffTold = false;   // told once a bout, not at every flicker of the edge
				return true;
			}
			int axe = axeSlot(p);
			if (axe < 0) {
				if (!heldOffTold) { heldOffTold = true; Exchange.emit("pen", "action", "holds off", "reason", "no axe"); }
				return false;
			}
			if (axeAt == 0) axeAt = now + 250 + R.nextInt(450);
			if (now >= axeAt && !AfkHelperClient.isEating()) {
				p.getInventory().setSelectedSlot(axe);
				axeHeld = true;
				axeAt = 0;
				axeBack = 2000 + R.nextInt(3000);
				paceFromSwitch(p, now);
				Exchange.emit("pen", "action", "takes the axe", "reason", "someone beside the mob");
			}
			return false;
		}
		if (axeHeld) {
			boolean worn = Work.nearlyBroken(p.getMainHandItem());
			if (by && worn) {
				// about to break beside someone: another axe of the hotbar, or no blow (never an axe broken at a farm)
				int other = axeSlot(p);
				if (other >= 0) { p.getInventory().setSelectedSlot(other); paceFromSwitch(p, now); }
				else if (!heldOffTold) { heldOffTold = true; Exchange.emit("pen", "action", "holds off", "reason", "no axe"); }
				return false;
			}
			if (!by && (worn || now - bystanderAt > axeBack)) {
				axeHeld = false;
				Guard.arm(AfkHelperClient.mc(), p, true);
				paceFromSwitch(p, now);
				Exchange.emit("pen", "action", "takes the sword");
				return false;
			}
		}
		return true;
	}

	/**
	 * The first blow after a switch of what is in hand, by the pace from the switch, or every first blow would come on the
	 * tick its strength is back, the same count of ticks after each switch. The strength starts again a tick after the
	 * switch (Player.tick sees the new item), where after a blow it already counts one: two ticks later than the pace's
	 * own gate. The item's own speed, as the attribute only follows when the server sends it.
	 */
	private static void paceFromSwitch(LocalPlayer p, long now) {
		double speed = 4;
		for (ItemAttributeModifiers.Entry en : p.getMainHandItem().getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY).modifiers())
			if (en.slot().test(EquipmentSlot.MAINHAND) && en.attribute().value() == Attributes.ATTACK_SPEED.value()) speed += en.modifier().amount();
		nextHit = now + 100 + pace.gap(1000.0 / Math.max(0.1, speed), R);
	}

	/** The axe of the hotbar to take beside someone: the best one, never one about to break. -1: none. */
	private static int axeSlot(LocalPlayer p) {
		int best = -1;
		double rank = Double.NEGATIVE_INFINITY;
		for (int i = 0; i < 9; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (!st.is(ItemTags.AXES) || Work.nearlyBroken(st)) continue;
			if (Guard.rank(st) > rank) { rank = Guard.rank(st); best = i; }
		}
		return best;
	}

	/** The pen holds an axe in hand for someone beside the mob: Guard.arm leaves it there, but for a fight. */
	static boolean holdsAxe() { return axeHeld && busy(); }

	/**
	 * Someone or something in a sweep's reach round the mob that a sweep must not hit (Player.doSweepAttack: the target's
	 * box inflated 1.0 and 0.25, within three of the buddy, by the server's places): a player, a pet, a villager, a golem, an
	 * animal, a neutral mob (which a blow makes angry), an armour stand. A monster beside it, on the rim or falling down
	 * the shaft onto it, is a farm's own and may be swept. Both by where the client draws them and by where the server
	 * last put them, which the client draws a few ticks later; for a player or a pet, which can walk in within those
	 * ticks and must never be pushed over the pit, with a block more of margin sideways, and up and down only while it
	 * is off the ground: one who stands on the rim of a pit three deep is out of the sweep's reach (its box ends a
	 * quarter over the mob's head), and those feet cannot sink without leaving the ground, which the server's next movement
	 * packet tells the client (a remote entity's onGround is the packet's).
	 */
	private static boolean bystander(LocalPlayer p, LivingEntity target) {
		AABB tb = target.getBoundingBox(), t = tb.minmax(tb.move(sent(target).subtract(target.position())));
		// wide enough for one drawn a few blocks behind where the server has it already
		double wide = 1 + SWEEP_MARGIN_MOVER + 2;
		for (LivingEntity e : p.level().getEntitiesOfClass(LivingEntity.class, t.inflate(wide, wide, wide), x -> x != p && x != target && x.isAlive() && !x.isSpectator())) {
			if (e instanceof ArmorStand a && a.isMarker()) continue;
			if (huntable(e, hok, p) || sweepable(e)) continue;
			boolean mover = e instanceof Player || (e instanceof TamableAnimal ta && ta.isTame()) || (e instanceof AbstractHorse ah && ah.isTamed());
			double m = mover ? SWEEP_MARGIN_MOVER : SWEEP_MARGIN, reach = 3 + m;
			double v = e.onGround() ? SWEEP_MARGIN_GROUND : mover ? SWEEP_MARGIN_MOVER : SWEEP_MARGIN;
			AABB sweep = t.inflate(1 + m, 0.25 + v, 1 + m);
			Vec3 at = sent(e);
			AABB late = e.getBoundingBox().move(at.subtract(e.position()));
			if ((e.getBoundingBox().intersects(sweep) && e.position().distanceTo(p.position()) < reach)
				|| (late.intersects(sweep) && at.distanceTo(p.position()) < reach)) return true;
		}
		return false;
	}

	/** Where the server last put it: the end of what the client still draws it moving to (its interpolation), or where it is. */
	private static Vec3 sent(Entity e) {
		PositionAndRotation to = e.getInterpolation().target();
		return to == null ? e.position() : to.position();
	}

	/** A monster of the farm's kinds that is no mob of the pen only by where it stands: a sweep may hit it too. */
	private static boolean sweepable(LivingEntity e) {
		return Grinder.candidate(e) && !excluded(e) && !(e instanceof NeutralMob) && !(e instanceof AbstractPiglin)
			&& !(e instanceof ZombieVillager zv && zv.isConverting());
	}

	/** The free spot that reaches the most of the mobs the current spot does not. */
	private static PenShape.Spot reaching(LocalPlayer p, List<LivingEntity> mobs, long now) {
		Hok h = hok;
		PenShape.Spot best = null;
		int bestN = 0;
		for (PenShape.Spot s : h.result.spots()) {
			if (s.cell().equals(spot.cell()) || !free(p, s, now)) continue;
			int n = 0;
			for (LivingEntity e : mobs) {
				int ci = h.cols.indexOf(PenShape.key(Mth.floor(e.getX()), Mth.floor(e.getZ())));
				if (ci >= 0 && (s.reach() & (1L << ci)) != 0) n++;
			}
			if (n > bestN || (n == bestN && n > 0 && best != null && s.at().distanceTo(p.position()) < best.at().distanceTo(p.position()))) { best = s; bestN = n; }
		}
		return bestN > 0 ? best : null;
	}

	/**
	 * Round the pen now and then, also while it is empty, as the AFK farm walked to the far side (ours: about three
	 * minutes): to the next side round the pen with a free spot, its best one (what it reaches, then nearest), past a
	 * side whose best reaches less than three quarters of what the current spot does. It took the next spot in the list on
	 * another side, a corner as often as not: from the middle of a side, six columns, to one that reached three (the
	 * test of 22 Oct 2026).
	 */
	private static boolean rounds(LocalPlayer p, long now) {
		int sec = AfkConfig.get().penSideSwitchSec;
		if (sec <= 0 || now < nextRound || now - inReachAt < 3000 || now - lastSwitch < SWITCH_MIN) return false;
		nextRound = now + roundGap();
		PenShape.Spot[] best = new PenShape.Spot[4];
		List<Creeper> strong = unstrikeable(p), all = creepersByPen(p);
		for (PenShape.Spot s : hok.result.spots()) {
			int i = s.side();
			if (i < 0 || i > 3 || i == spot.side() || !free(p, s, now) || noSpot(s.at(), strong, all)) continue;
			PenShape.Spot b = best[i];
			if (b == null || s.columns() > b.columns()
					|| (s.columns() == b.columns() && s.at().distanceTo(p.position()) < b.at().distanceTo(p.position()))) best[i] = s;
		}
		for (int k = 1; k < 4; k++) {
			PenShape.Spot s = best[Math.floorMod(spot.side() + k, 4)];
			if (s != null && s.columns() * 4 >= spot.columns() * 3) { switchTo(s, "round", now); return true; }
		}
		return false;
	}

	private static long roundGap() {
		int sec = Math.max(1, AfkConfig.get().penSideSwitchSec);
		return (long) (sec * 1000L * (0.7 + R.nextDouble() * 0.6));
	}

	private static void switchTo(PenShape.Spot s, String why, long now) {
		lastSwitch = now;
		Exchange.emit("pen", "action", "other side", "reason", why, "side", side(s.side()), "x", s.cell().getX(), "y", s.cell().getY(), "z", s.cell().getZ());
		goTo(s, "other side", now);
	}

	/** Nothing in the pen: look at its middle after a moment, a glance now and then, and say so after half a minute. */
	private static void waiting(LocalPlayer p, long now) {
		if (emptySince == 0) { emptySince = now; nextGlance = now + 1200 + R.nextInt(1300); centreLooked = false; }
		if (!centreLooked && now >= nextGlance && !AfkHelperClient.isEating()) {
			centreLooked = true;
			look.start(p, new Vec3(hok.centre.x, hok.floor() + 0.6 * hok.h, hok.centre.z), 1.0f);
			nextGlance = now + 2500 + R.nextInt(5500);
		} else if (centreLooked && now >= nextGlance && !look.busy() && !AfkHelperClient.isEating()) {
			nextGlance = now + 2500 + R.nextInt(5500);
			double yaw = Math.toRadians(p.getYRot() + (R.nextDouble() * 12 - 6)), pitch = Math.toRadians(Mth.clamp(p.getXRot() + (R.nextDouble() * 8 - 4), -60, 80));
			Vec3 dir = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
			look.start(p, p.getEyePosition().add(dir.scale(5)), 0.5f, 6, 14);
		}
		long empty = now - emptySince;
		if (!waitTold && empty > WAIT_EVENT) { waitTold = true; waitToldAt = now; Exchange.emit("pen", "action", "waits for mobs", "since", emptySince); }
		else if (waitTold && now - waitToldAt > WAIT_AGAIN) { waitToldAt = now; Exchange.emit("pen", "action", "waits for mobs", "since", emptySince); }
		rounds(p, now);
	}

	// ---------- danger ----------

	/**
	 * Health and the damage events taken. Any drop is damage (the retreat at five hearts, its minute without
	 * damage). A blow is damage with an attacker: an event that names one (also when absorption took it), or a drop
	 * that came with no event of its own, which only a stronger hurt within the cooldown of another one is
	 * (LivingEntity.hurtServer sends no event then, and no poison, wither or fire tick ever lands there). A poison, wither
	 * or fire tick, a fall or lava: damage, no blow (their sources name no attacker). Each event the client gets is a new
	 * DamageSource (ClientPacketListener.handleDamageEvent), which tells one from the next.
	 */
	private static void damageTick(LocalPlayer p, long now) {
		DamageSource s = p.getLastDamageSource();
		float hp = p.getHealth();
		if (lastHealth < 0) { lastSource = s; freshSource = null; lastHealth = hp; return; }
		if (s != null && s != lastSource) {
			lastSource = s;
			freshSource = s;
			if (s.getEntity() != null) lastBlowAt = now;
		}
		if (hp < lastHealth - 0.01f) {
			lastDamageAt = now;
			if (freshSource == null) lastBlowAt = now;
			freshSource = null;
		}
		lastHealth = hp;
	}

	/**
	 * Low after a blow: away from the pen out of the sight of its mobs, eat, back at sixteen or after a minute without a
	 * blow; three times in ten minutes is too dangerous. A mob of the pen with a spear's reach that reaches the buddy: another
	 * spot, or away until it is gone. Hungry and low with nothing to eat: giving up, following again and fetching food. A
	 * swelling creeper is creeperTick's, before any of these, and a strike at one under way (urgent, its death waited for
	 * too) comes before all of them: its callers leave this out meanwhile, as the night's pause waits for it. A retreat
	 * there made the strike a walk off the rim with too little of its fuse left, which the strike was kept for (the review
	 * of the fourth round, 22 Oct 2026). A hit counts five seconds and a strike with its wait is under two, so low health,
	 * no food or a spear come on the first tick after it. While waiting out a creeper at the getaway's cell this runs
	 * from there (waitChecks).
	 */
	private static boolean safety(Minecraft mc, LocalPlayer p, List<LivingEntity> mobs, long now) {
		if (p.getFoodData().getFoodLevel() <= 6 && p.getHealth() <= 12 && AfkHelperClient.findFood(p) < 0 && AfkHelperClient.countFood(p, 0, 36) == 0) {
			stop("no food", false);
			return true;
		}
		if (now - lastDamageAt < DAMAGE_RECENT && p.getHealth() <= RETREAT_HP) { retreat("low hp", now, RETREAT_MAX); return true; }
		for (LivingEntity e : mobs) {
			ItemStack held = e.getMainHandItem();
			AttackRange range = held.get(DataComponents.ATTACK_RANGE);
			if (range != null) {
				double r = range.effectiveMaxRange(e);
				if (e.getBoundingBox().inflate(r, 0, r).intersects(p.getBoundingBox()) && e.hasLineOfSight(p)) {
					// on the way to a spot out of its reach already: on there (chosen anew every tick, it swung between two)
					if ((state == GOING || state == SWITCHING) && spot != null && !reaches(e, r, spot)) continue;
					// in the night's pause away from it, as the pause would bring the buddy back from another side at once
					PenShape.Spot next = state == NIGHT ? null : outOfReach(p, e, r, now);
					if (next != null) { switchTo(next, "spear", now); return true; }
					retreat("spear", now, RETREAT_MAX);
					return true;
				}
			}
		}
		return false;
	}

	/** A mob with a spear's reach r reaches a player standing at this spot. */
	private static boolean reaches(LivingEntity e, double r, PenShape.Spot s) {
		AABB body = new AABB(s.at().x - 0.3, s.feet(), s.at().z - 0.3, s.at().x + 0.3, s.feet() + 1.8, s.at().z + 0.3);
		return e.getBoundingBox().inflate(r, 0, r).intersects(body);
	}

	private static PenShape.Spot outOfReach(LocalPlayer p, LivingEntity e, double r, long now) {
		for (PenShape.Spot s : hok.result.spots()) {
			if ((spot != null && s.cell().equals(spot.cell())) || !free(p, s, now)) continue;
			if (!reaches(e, r, s)) return s;
		}
		return null;
	}

	private static void retreat(String why, long now, long most) {
		if (state == RETREAT) return;
		if (why.equals("low hp")) {
			retreats.addLast(now);
			while (!retreats.isEmpty() && now - retreats.peekFirst() > RETREAT_WINDOW) retreats.pollFirst();
			if (retreats.size() >= RETREATS) {
				tooDangerous("{me} left the mob farm after being hit there three times.");
				return;
			}
		}
		retreatWhy = why;
		retreatSince = now;
		retreatUntil = now + most;
		retreatArrived = 0;
		retreatTo = null; retreatAway = null; retreatGoal = null; retreatAround = null;
		retreatStays = false; retreatAwayLooked = false;
		setState(RETREAT, now);
		Work.walkFresh();
		LocalPlayer p = AfkHelperClient.mc().player;
		Exchange.emit("pen", "action", "backs off", "reason", why, "hp", p == null ? 0 : Math.round(p.getHealth()));
	}

	/**
	 * Too dangerous: away from the pen for good. To the cell no column of it sees, to wait there once arrived; where
	 * none is known, or the walk there does not get there in its time, the buddy follows the player away from it: a hit
	 * came beside the pit, two blocks back from it the pen's mobs still see the buddy, and there the fight leaves them be
	 * (Follow.pitFoe), so the walk's end is never a place to stay.
	 */
	private static void tooDangerous(String need) { tooDangerous(need, true); }

	/**
	 * Too dangerous; {@code cellOk} false: not to the cell no column of the pen sees, for a creeper that was no creeper
	 * of the pen drove the buddy off, and that one may follow there, where nothing watches for it during the stay (the
	 * third review of 22 Oct 2026): following the player instead. Nor to that cell while a creeper stands by it (creeperBy),
	 * of the pen or not, which would mean staying beside it with no creeper watch at all; it led to the very cell a
	 * creeper had kept the buddy from (the review of the fourth round, 22 Oct 2026).
	 */
	private static void tooDangerous(String need, boolean cellOk) {
		Needs.want("pen_danger", need);
		BlockPos hidden = cellOk && retreatHidden && !creeperBy(retreatCell) ? retreatCell : null;
		if (hidden == null) { stop("too dangerous", false); return; }
		stop("too dangerous", true, hidden);
		if (state == BACKOFF_STATE) backoffStay = "too dangerous";
	}

	/**
	 * The cell to back off to: the search started with the pen, worked on a little more where it is not done yet (null:
	 * none, or not yet). Whether it is out of sight is {@link #retreatHidden}.
	 */
	private static BlockPos retreatCell(LocalPlayer p, long budget) {
		if (retreatSearch == null && !retreatSearched && hok != null) { retreatSearch = new RetreatSearch(p.level(), hok); retreatSearched = true; }
		if (retreatCell == null && retreatSearch != null && retreatSearch.step(budget)) {
			retreatCell = retreatSearch.best();
			retreatHidden = retreatSearch.hidden();
			retreatSearch = null;
		}
		return retreatCell;
	}

	/**
	 * The retreat: its own checks first (retreatBack, retreatHit, which the wait at a getaway's cell runs as well), then
	 * the walk to its cell out of the pen's sight. Where a creeper stood by that cell, another one of this retreat's own
	 * (retreatTo, looked for by retreatAround while standing), or where the buddy got to (retreatStays): it waited up to two
	 * minutes at the getaway's cell for that creeper to go, these checks not running there (the review of the fourth
	 * round, 22 Oct 2026). While no cell is known, one away from the pen (retreatAway).
	 */
	private static boolean retreatTick(Minecraft mc, LocalPlayer p, long now) {
		HeldKeys.set(mc.options.keyShift, false);
		if (retreatBack(p, now)) {
			toldBack();
			go("to a spot", now);
			return true;
		}
		if (retreatHit(p, now, retreatArrived)) { hitAgain(); return false; }
		if (retreatAround != null) {
			if (!retreatAround.step(SEARCH_BUDGET)) { Follow.stand(); return true; }
			CoverShape.Cell c = retreatAround.found();
			retreatAround = null;
			retreatArrived = 0;
			if (c != null) retreatTo = c.pos();
			else retreatStays = true;
		}
		// the place reached: a blow there from now on is one at the cell
		if (retreatStays) {
			if (retreatArrived == 0) retreatArrived = now;
			Follow.stand();
			return true;
		}
		BlockPos cell = retreatTo != null ? retreatTo : retreatCell(p, SEARCH_BUDGET);
		Vec3 to = cell != null ? Vec3.atBottomCenterOf(cell) : retreatAway(p);
		// there as the walker counts it there (Work.approach: within 1.6 is close enough)
		if (cell != null && retreatArrived == 0 && Math.hypot(to.x - p.getX(), to.z - p.getZ()) <= 1.6 && Math.abs(to.y - p.getY()) <= 1.1)
			retreatArrived = now;
		if (to == null || p.position().distanceTo(to) < 0.8) { Follow.stand(); return true; }
		// a goal of its own: its walk begun anew, where the one key of all its walks carried the last goal's record over
		BlockPos goal = BlockPos.containing(to);
		if (!goal.equals(retreatGoal)) { retreatGoal = goal; Work.walkFresh(); }
		if (!Work.approach(mc, p, "pen-back", to, now)) {
			// no way there: the cell away from the pen given up (standing still), this retreat's own (staying in place), or
			// the pen's, which then is known no more
			if (cell == null) retreatAway = null;
			else if (cell.equals(retreatTo)) { retreatTo = null; retreatStays = true; }
			else { retreatCell = null; retreatHidden = false; }
			Follow.stand();
		}
		return true;
	}

	/** The retreat is over: health back at sixteen or a minute without damage (low), the spear gone, or its time. */
	private static boolean retreatBack(LocalPlayer p, long now) {
		if (retreatWhy.equals("spear")) return now > retreatUntil || !spearNear(p);
		return retreatWhy.equals("low hp")
			? p.getHealth() >= RESUME_HP || (now - lastDamageAt > RETREAT_MAX && now - retreatSince > 5000)
			: now > retreatUntil;
	}

	/**
	 * Still hit where the buddy backed off to (after {@code arrived}; 0: not there yet), hit again since the retreat began at
	 * three hearts or less, or hit for ten seconds on end: no standing there to be hit, nor going back to the pen (the
	 * blow backed off for is none of these). Blows only (damageTick): a witch's poison ticks on where nothing reaches
	 * the buddy, and is waited out there.
	 */
	private static boolean retreatHit(LocalPlayer p, long now, long arrived) {
		if (now - lastBlowAt >= DAMAGE_RECENT) return false;
		return (arrived > 0 && lastBlowAt > arrived) || (p.getHealth() <= RETREAT_LOW && lastBlowAt > retreatSince)
			|| (now - retreatSince > RETREAT_HIT && now - lastBlowAt < 2000);
	}

	/** Hit again in the retreat (retreatHit): too dangerous, and the buddy follows the player. */
	private static void hitAgain() {
		Needs.want("pen_danger", "{me} left the mob farm after being hit there again and again.");
		stop("too dangerous", false);
	}

	private static boolean spearNear(LocalPlayer p) {
		for (LivingEntity e : mobs(p)) {
			AttackRange range = e.getMainHandItem().get(DataComponents.ATTACK_RANGE);
			if (range != null && e.distanceTo(p) < range.effectiveMaxRange(e) + 3) return true;
		}
		return false;
	}

	/**
	 * Where the retreat goes while no cell out of the pen's sight is known (its search not done yet, or the walk to it
	 * given up): awayCell, looked for once a retreat. Null: none, and the buddy stands.
	 */
	private static Vec3 retreatAway(LocalPlayer p) {
		if (!retreatAwayLooked) { retreatAwayLooked = true; retreatAway = awayCell(p); }
		return retreatAway == null ? null : Vec3.atBottomCenterOf(retreatAway);
	}

	/**
	 * Of the cells the buddy walks to (walkable) without coming nearer to the pen than now (flat, less Getaway.SLACK), the
	 * one farthest from it with no drop beside it, if it is half a block farther than the current place, as the getaway from a
	 * creeper takes its fallback; null: none. It was a cell three to six blocks straight away from the pen's middle, or
	 * that turned an eighth or a quarter (awayFrom), that no walk was asked about: past a wall with no way to it, or the
	 * floor of the next pit two blocks down, which the getaway from a creeper dropped in the third review of 22 Oct 2026
	 * and the retreat kept until the review of the fourth round.
	 */
	private static BlockPos awayCell(LocalPlayer p) {
		Hok h = hok;
		Level level = p.level();
		double feet = ground(p);
		BlockPos from = standCell(level, p.getX(), feet, p.getZ());
		double mine = h.flat(p.position()), limit = mine - Getaway.SLACK, best = mine + Getaway.BETTER;
		BlockPos out = null;
		for (BlockPos q : walkable(level, from, feet, CREEPER_RANGE, WALK_MAX, c -> h.flat(Getaway.point(c.getX(), c.getY(), c.getZ())) >= limit).keySet()) {
			if (q.equals(from) || drop(level, q)) continue;
			double d = h.flat(Getaway.point(q.getX(), q.getY(), q.getZ()));
			if (d > best) { best = d; out = q; }
		}
		return out;
	}

	/**
	 * Where to go when it gets dangerous, for the night and after the end: the nearest cell five to ten blocks from the
	 * pen that no column of the pen can see, worked out once per pen from its start, a little each tick; none such: the
	 * farthest standing place within ten (not {@link #hidden}).
	 */
	private static final class RetreatSearch {
		private final LevelWorld w;
		private final Hok h;
		private final List<BlockPos> cells = new ArrayList<>();
		private int next;
		private BlockPos best, far;
		private double farD = -1;

		RetreatSearch(Level level, Hok h) {
			this.w = new LevelWorld(level);
			this.h = h;
			int fy = Mth.floor(h.floor());
			for (int x = h.flood.minX() - 10; x <= h.flood.maxX() + 10; x++) for (int z = h.flood.minZ() - 10; z <= h.flood.maxZ() + 10; z++) {
				double d = h.flat(new Vec3(x + 0.5, 0, z + 0.5));
				if (d < 5 || d > 10) continue;
				for (int y = fy - 2; y <= fy + 5; y++) cells.add(new BlockPos(x, y, z));
			}
			cells.sort(Comparator.comparingDouble(c -> h.flat(Vec3.atCenterOf(c))));
		}

		boolean step(long budget) {
			long until = System.nanoTime() + budget;
			while (next < cells.size() && best == null) {
				BlockPos c = cells.get(next++);
				double feet = w.stand(c.getX(), c.getY(), c.getZ());
				if (Double.isNaN(feet) || Follow.fenced(c)) continue;
				Vec3 eye = new Vec3(c.getX() + 0.5, feet + 1.62, c.getZ() + 0.5);
				double d = h.flat(eye);
				if (d > farD) { farD = d; far = c; }
				boolean seen = false;
				for (long k : h.cols) if (sees(k, eye)) { seen = true; break; }
				if (!seen) best = c;
				if (System.nanoTime() > until) break;
			}
			return best != null || next >= cells.size();
		}

		/**
		 * A mob of this column sees that eye (COLLIDER, as a mob's sight goes): from its eyes in the middle of the column
		 * and pushed to the column's far walls from there, where knockback and crowding put the mobs, a mob as wide as the
		 * pen's half its width off them. From the middle alone the far wall's mobs, which see over the near rim higher,
		 * were not counted.
		 */
		private boolean sees(long k, Vec3 eye) {
			int x = PenShape.kx(k), z = PenShape.kz(k);
			double y = h.flood.floorOf().get(k) + h.eye, half = Math.min(0.5, h.w / 2);
			double fx = eye.x < x + 0.5 ? x + 1 - half : x + half, fz = eye.z < z + 0.5 ? z + 1 - half : z + half;
			double[][] at = { { x + 0.5, z + 0.5 }, { fx, z + 0.5 }, { x + 0.5, fz }, { fx, fz } };
			for (double[] a : at) if (PenShape.clear(w, new Vec3(a[0], y, a[1]), eye, false)) return true;
			return false;
		}

		BlockPos best() { return best != null ? best : far; }

		/** The cell found is out of the sight of every column, not only the farthest one. */
		boolean hidden() { return best != null; }
	}

	// ---------- a swelling creeper ----------

	/**
	 * The creeper step of the chain (Follow.tick, after the watch of the fight and before the fight): a creeper swelling at
	 * the buddy while the pen keeps the hands busy, also while a fight, the unloader or the corpse guard holds the farm step back
	 * ({@code held}; {@code fight}: a fight of them). Until the third review of 22 Oct 2026 it was the farm step's alone,
	 * and a fight with a zombie at the spot left a creeper beside the buddy swelling, and undid the strike turned for.
	 * Not in Pen.watch, which comes before the chain's pause for a death, a menu, AFK mode and another job, where
	 * nothing may steer. True while it keeps the hands busy: getting away, or, while the chain is held, the strike itself
	 * (the farm step, which strikes otherwise, does not come then). False in a tick of a getaway that creeperTick gave to
	 * the fight (yieldAt), which the fight step then always takes; the farm step farms nothing while the getaway is on.
	 */
	static boolean creeper(Minecraft mc, LocalPlayer p, boolean held, boolean fight) {
		if (state == OFF || !p.isAlive()) return false;
		long now = System.currentTimeMillis();
		// the health as the farm step counts it, which does not come while this keeps the hands busy
		if (hok != null) damageTick(p, now);
		if (creeperTick(mc, p, now, fight)) {
			heldChecks(mc, p, now);
			if (yieldAt == Follow.ticks()) return false;
			return state != OFF;
		}
		if (!held || urgent == null || !striking()) return false;
		var o = mc.options;
		Follow.stand();
		HeldKeys.set(o.keyUp, false); HeldKeys.set(o.keyDown, false); HeldKeys.set(o.keyLeft, false); HeldKeys.set(o.keyRight, false);
		HeldKeys.set(o.keySprint, false);
		look.tick(p);
		strike(mc, p, now);
		heldChecks(mc, p, now);
		return state != OFF;
	}

	/**
	 * What the farm step does every tick before its creeper, for a tick in which this step keeps the hands busy, when the
	 * farm step does not come: what ends the pen whatever happens (guard: teleported, too far, another player followed) and
	 * the checks of once a second (every20), also while getting away from a creeper or waiting for one, up to two minutes.
	 */
	private static void heldChecks(Minecraft mc, LocalPlayer p, long now) {
		if (hok == null || state == RESUMING || state == BACKOFF_STATE) return;
		int tick = Follow.ticks();
		String why = guard(p, null, now, tick);
		if (why != null) { stop(why, false); return; }
		if (tick % 20 == 0 && state != NIGHT) every20(mc, p, null, now);
	}

	/** A state in which a swelling creeper of the pen is struck: at a spot, leaning in, or on the way to one. */
	private static boolean striking() { return state == GOING || state == SWITCHING || state == LEAN_IN || state == FARMING; }

	/** A creeper swells at the buddy at the pen, or a strike at one or a walk away from one is under way: no bite now (AfkHelperClient). */
	static boolean creeperDanger() { return state != OFF && Follow.ticks() - dangerAt <= 1; }

	/**
	 * A swelling creeper that would hurt the buddy, any: the pen's, one on its rim, one that came up behind, one that came
	 * close in the night's pause; in every state of the pen, from the chain's creeper step or the farm step's own,
	 * once a tick. One of the pen that a blow kills before its fuse is full is struck (urgent, strikeOn: the state's own
	 * tick, or the creeper step while the chain is held, turns to it and hits); any other the buddy gets away from (startEvade,
	 * evadeTick), to a cell out of its sight or past its blast, and waits there three seconds at least and until no
	 * creeper that could not be struck is near the place to go back to. True while getting away keeps the hands busy, but in
	 * a tick it gives to a fight ({@code fight}: one holds the chain): once none has swelled within its reach for
	 * SWELL_QUIET, seen or not, decided here with this tick's swell and before evadeTick touches a key or the route, which
	 * it then leaves alone. Until the review of the fourth round, 22 Oct 2026, the creeper step decided it after evadeTick
	 * had stopped or walked the buddy, also for the unloader and the corpse guard: their walks got a tick in ten and were
	 * walked back to the cell past a block, and the head turned twice in a tick on the way. Until the review of 22 Oct
	 * only a creeper of the pen counted, and the buddy stayed for one a blow kills whether or not that blow could land in time,
	 * and backed off to the pen's retreat cell, past the creeper as often as not.
	 */
	private static boolean creeperTick(Minecraft mc, LocalPlayer p, long now, boolean fight) {
		int t = Follow.ticks();
		creeperRanAt = t;
		struckTick(mc, p, now);
		if (urgent != null && (!urgent.isAlive() || urgent.isRemoved())) urgent = null;
		if (urgent != null && urgent.getSwellDir() > 0) urgentSwellTick = t;
		List<Creeper> near = swelling(p);
		if (!near.isEmpty() || urgent != null || (evadeId != -1 && evadeCell != null && !evadeAtCell)) dangerAt = t;
		if (!near.isEmpty() || urgent != null || anySwelling(p)) swellAt = t;
		// the one just struck: its death arrives a ping and a tick or two later, and meanwhile it is no reason to get
		// away (the third review of 22 Oct 2026: the buddy walked off from a creeper that was dead already, and counted it)
		if (struckId != -1) near.removeIf(c -> c.getId() == struckId);
		if (evadeId == -1 && strikeOn(mc, p, near, now, t)) return false;
		urgent = null;
		unseenHitch = false;
		if (near.isEmpty() && evadeId == -1) return false;
		// a bout of one that swells at the buddy (new to this getting away, or swelling again after it had stopped) is counted,
		// and the cell is chosen anew against all that swell now: one that followed to the cell and swelled again found
		// the buddy standing there (the third review). Stopped is its own swell ended, not a line of sight lost for a tick on
		// the way, which would count one swell as two
		boolean bout = false;
		for (Creeper c : near) {
			Boolean stopped = evadeFor.get(c.getId());
			if (stopped == null || stopped) { evadeFor.put(c.getId(), false); counted(c, now); bout = true; }
		}
		for (Map.Entry<Integer, Boolean> e : evadeFor.entrySet()) {
			if (e.getValue() || has(near, e.getKey())) continue;
			Entity x = p.level().getEntity(e.getKey());
			if (!(x instanceof Creeper k) || !k.isAlive() || k.getSwellDir() <= 0) e.setValue(true);
		}
		if (bout) startEvade(p, near, now);
		if (evadeId == -1) return false;
		// a tick given to a fight, never one with a new bout (that one swelled this tick): the first of them halts the walk and
		// has the fight plan at once, with no route, keys or cells kept out of left of the getaway's walk; the first tick
		// after them begins the walk to the cell anew, as the walk record of before a long fight failed it at once
		boolean give = fight && t - swellAt > SWELL_QUIET;
		if (give) {
			if (!evadeYielded) { Follow.stand(); Follow.planNow(); unshunEvade(); }
			evadeYielded = true;
			yieldAt = t;
		} else if (evadeYielded && !overGap(p)) {
			// the walk to the cell begun anew once standing: over a gap the fight's jump goes on (evadeTick holds)
			evadeYielded = false;
			if (!bout && evadeCell != null) evadeWalkAgain();
		}
		return evadeTick(mc, p, now, near, give);
	}

	/**
	 * The creeper being struck at, as long as the strike is to be kept (strikeOn): alive, not struck yet, within its
	 * blast's reach and one more of the buddy, its swell seen within URGENT_GRACE; null otherwise.
	 */
	private static Creeper kept(LocalPlayer p, int t) {
		Creeper c = urgent;
		if (c == null || !c.isAlive() || c.isRemoved() || c.getId() == struckId) return null;
		return t - urgentSwellTick <= URGENT_GRACE && c.distanceTo(p) <= blast(c) + 1 ? c : null;
	}

	/**
	 * Ticks a strike begun is kept through, without its creeper seen swelling at the buddy (near: the server's swell direction
	 * and the client's own line of sight) or without a plan for its blow. The client's sight loses the buddy at a pit's rim a
	 * tick before the server does or after, and the server sets the swell back for a tick it loses sight within three and
	 * goes on (SwellGoal); either dropped the strike, and the next tick made it a new one, with a reaction and a turn of
	 * its own and none of the room a strike begun has, or a walk off the rim it was kept for as getting nowhere clear
	 * (the review of the fourth round, 22 Oct 2026). The review's two of the keeping and its three more to take it up
	 * again are one keeping here, in which nothing else is struck or looked at.
	 */
	static final int URGENT_GRACE = 4;

	/**
	 * Whether a strike is on this tick (creeperTick returns then, the state's own tick or the held creeper step strikes):
	 * the struck one's death waited for; the one begun (kept, seen in near or not) as long as Fuse.keeps keeps it (its
	 * blow still fits by Fuse.strike's rules for one begun, with another one swelling too only while late, and a
	 * moment without a plan while late, within URGENT_GRACE); or, with one alone swelling at the buddy, a new one. A strike
	 * dropped joins near, to get away from: it still swells.
	 */
	private static boolean strikeOn(Minecraft mc, LocalPlayer p, List<Creeper> near, long now, int t) {
		if (urgent != null && urgent.getId() == struckId && near.isEmpty()) return true;
		Creeper held = kept(p, t);
		if (held == null) {
			if (near.size() != 1) return false;
			Plan pl = plan(mc, p, near.get(0), now);
			return pl != null && strikes(near.get(0), pl, now, t);
		}
		boolean alone = near.isEmpty() || (near.size() == 1 && near.get(0).getId() == held.getId());
		boolean lastLate = urgentLate;
		int sincePlan = t - urgentPlanTick;
		Plan pl = plan(mc, p, held, now);
		boolean ahead = pl != null && strikes(held, pl, now, t);
		if (Fuse.keeps(pl != null, ahead, pl != null && pl.late(), alone, lastLate, sincePlan, URGENT_GRACE)) return true;
		if (!has(near, held.getId())) near.add(held);
		return false;
	}

	private static boolean has(List<Creeper> list, int id) {
		for (Creeper c : list) if (c.getId() == id) return true;
		return false;
	}

	/**
	 * A creeper within its blast's reach and one more that swells, seen or not: the swell's direction is the server's
	 * (DATA_SWELL_DIR), where the sight is the client's own reckoning, which loses the buddy at a pit's rim a tick before the
	 * server does or after. While one does, or did within SWELL_QUIET (swellAt), a getaway comes before a fight, and a
	 * wait at its cell runs none of the state's own checks, which may end the pen and with it every creeper watch (the
	 * test of 22 Oct 2026: a fight took the buddy back beside one that had lost sight for a moment, and it went off).
	 */
	private static boolean anySwelling(LocalPlayer p) {
		for (Creeper c : p.level().getEntitiesOfClass(Creeper.class, p.getBoundingBox().inflate(13), c -> c.isAlive() && c.getSwellDir() > 0))
			if (c.distanceTo(p) <= blast(c) + 1) return true;
		return false;
	}

	/**
	 * Ticks with no creeper swelling within its reach of the buddy, seen or not (swellAt), before a fight may have the tick of a
	 * getaway (creeperTick), also on the way to the cell, and before the wait at the cell runs the state's own checks
	 * (waitChecks). Only the fight: the unloader and the corpse guard wait behind a getaway, as before the fourth round.
	 */
	static final int SWELL_QUIET = 10;

	/** Creepers swelling that would hurt the buddy if they went off now: within their blast's reach and one more, in sight. */
	private static List<Creeper> swelling(LocalPlayer p) {
		List<Creeper> out = new ArrayList<>();
		for (Creeper c : p.level().getEntitiesOfClass(Creeper.class, p.getBoundingBox().inflate(13), c -> c.isAlive() && c.getSwellDir() > 0))
			if (c.distanceTo(p) <= blast(c) + 1 && c.hasLineOfSight(p)) out.add(c);
		return out;
	}

	/** How far a creeper's blast hurts: twice its radius of three, twice that when it is charged. */
	private static double blast(Creeper c) { return c.isPowered() ? 12 : 6; }

	/**
	 * Guard's blow at the start of this tick, when it was at the urgent creeper: struck. Its death is waited for, the
	 * ping and two ticks and never less than a click's gap (PenShape.MIN_GAP): no second blow at it meanwhile, and no
	 * getting away from it for still swelling (the third review of 22 Oct 2026: the buddy struck once a tick until the death
	 * was known, four to six blows at twenty a second).
	 */
	private static void struckTick(Minecraft mc, LocalPlayer p, long now) {
		long at = Guard.lastHitAt();
		if (at != creeperHitSeen) {
			creeperHitSeen = at;
			if (urgent != null && Guard.lastHitId() == urgent.getId()) {
				struckId = urgent.getId();
				struckUntil = now + Fuse.struckHold(pingTicks(mc, p), PenShape.MIN_GAP);
			}
		}
		if (struckId != -1 && now >= struckUntil) struckId = -1;
	}

	/** The sums of a blow at a swelling creeper (plan): the latest it lands, the fuse left, a hitch counted, one of unknown length, and late. */
	private record Plan(int sum, int fuse, int hitch, boolean unseen, boolean late) {}

	/**
	 * The latest a blow at this swelling creeper lands (Fuse.sum) and the fuse left, or null where no blow can
	 * kill it: no pen, a dry run, not at or on the way to a spot (not in the night's pause, a retreat or the step back),
	 * no creeper of the pen, riding, a menu, a hand that may not hit it as it is (handWillHit), a full blow that does not
	 * kill it. The sum: the turn to it at Look's slowest, the strength that kills it, a moment's reaction, the cooldown
	 * of its last hurt (Fuse.cooldownWait: a fall that just hurt it absorbs a blow no stronger), and a hitch of the buddy's
	 * own (the ticks until landing, what is left of an item in use, a tick to stop sprinting; a mob in the line or no point
	 * of it in reach, of unknown length: UNSEEN_HITCH a look), then the tick it is queued for, the ping and a margin. The
	 * fuse: by the more of the client's own count and the server's as the client can tell it (Fuse.Count: what a landing
	 * adds, which only the server counts; the third review of 22 Oct 2026). Late: so little of it is left that a walk out
	 * of its blast at the walker's pace (Fuse.walkOut; the walk off stands up beside the fence, gettingAway) no longer
	 * gets clear.
	 */
	private static Plan plan(Minecraft mc, LocalPlayer p, Creeper c, long now) {
		if (hok == null || dry || !striking()) return null;
		if (!huntable(c, hok, p) || p.isPassenger() || p.containerMenu != p.inventoryMenu || !handWillHit(p, c)) return null;
		int strength = strengthTicks(p, c);
		if (strength < 0) return null;
		int hitch = 0;
		if (!p.onGround()) hitch = Fuse.airTicks(p.getY() - ground(p), p.getDeltaMovement().y);
		if (p.isUsingItem()) hitch = Math.max(hitch, p.getUseItemRemainingTicks());
		else if (AfkHelperClient.isEating()) hitch = Math.max(hitch, 33);   // a bite about to begin: its whole length
		if (p.isSprinting()) hitch = Math.max(hitch, 1);
		Vec3 pt = aimAt(p, c);
		boolean unseen = pt == null || screened(p, c, pt) || AfkHelperClient.lookBusy();
		boolean onIt = mc.hitResult instanceof EntityHitResult eh && eh.getEntity() == c;
		int turn = onIt ? 0 : urgentTurn == c.getId() && look.busy() ? look.ticks : turnBound(p, pt != null ? pt : c.getBoundingBox().getCenter());
		int react = urgent != null && urgent.getId() == c.getId() ? (int) Math.max(0, Math.ceil((urgentAt - now) / 50.0)) : REACT_MAX;
		int ping = pingTicks(mc, p);
		int fuse = Fuse.fuse(clientSwell(c), count(c));
		int sum = Fuse.sum(turn, strength, react, Fuse.cooldownWait(c.damageCooldownTime), unseen ? Math.max(hitch, UNSEEN_HITCH) : hitch, ping, CREEPER_MARGIN);
		boolean late = fuse <= Fuse.walkOut(c.distanceTo(p), blast(c)) + ping;
		return new Plan(sum, fuse, hitch, unseen, late);
	}

	/**
	 * Whether the buddy strikes this creeper by its plan: a new one when the blow lands within its fuse with nothing in the way
	 * of it (it begins: urgent); one turned to already as long as its sum, counting the hitch's own ticks, still fits
	 * (the third review of 22 Oct 2026: one tick in which the blow could not go out, an arrow's knock or a mob in the
	 * line, ended it for good, and the walk off came too late to get clear). A hitch of unknown length is waited out only
	 * while a walk out of its blast still gets clear, or when it began only after that; and once getting away no longer
	 * gets clear, the blow is kept while it fits but for its margin. When it goes ahead, the tick and whether it is late
	 * are kept, for a moment without a plan (strikeOn).
	 */
	private static boolean strikes(Creeper c, Plan plan, long now, int t) {
		boolean committed = urgent != null && urgent.getId() == c.getId();
		// a hitch of unknown length: whether it began only once a walk out of the blast no longer got clear
		if (!committed || !plan.unseen()) unseenHitch = false;
		else if (!unseenHitch) { unseenHitch = true; unseenLate = plan.late(); }
		if (!Fuse.strike(committed, plan.hitch(), plan.unseen(), unseenLate, plan.sum(), plan.fuse(), plan.late(), CREEPER_MARGIN)) return false;
		if (!committed) {
			urgent = c;
			urgentAt = now + 100 + R.nextInt(151);
			urgentTurn = -1;
			urgentSwellTick = t;
			unseenHitch = false;
			Exchange.emit("pen", "action", "strikes a creeper", "fuse", plan.fuse(), "sum", plan.sum(),
				"x", Mth.floor(c.getX()), "y", Mth.floor(c.getY()), "z", Mth.floor(c.getZ()));
		}
		urgentPlanTick = t;
		urgentLate = plan.late();
		return true;
	}

	/**
	 * What the buddy holds hits this one now as it is: no sword with someone in the reach of its sweep (handReady takes an axe
	 * there first, which takes its time), no axe about to break beside someone. An axe taken for someone stays for it.
	 */
	private static boolean handWillHit(LocalPlayer p, LivingEntity aimed) {
		if (unsafe) return true;
		boolean by = bystander(p, aimed);
		if (p.getMainHandItem().is(ItemTags.SWORDS)) return !by;
		return !(axeHeld && by && Work.nearlyBroken(p.getMainHandItem()));
	}

	/**
	 * Ticks until a blow with what is in hand kills it, by the strength as the server weighs a blow (Player.attack: the
	 * item's damage times 0.2 + 0.8 times the strength squared) at safeStrength; -1 when not even a full blow kills it.
	 */
	private static int strengthTicks(LocalPlayer p, LivingEntity e) {
		double b = blow(p);
		if (b <= 0) return -1;
		double need = e.getHealth() / b;
		if (need > 1 + 1e-6) return -1;
		double s = need <= 0.2 ? 0 : Math.sqrt((need - 0.2) / 0.8);
		float delay = Math.max(1f, p.getCurrentItemAttackStrengthDelay());
		// the ticks since the last blow or switch (up to the delay, where the strength is full), and the blow lands with
		// a tick less than that, as safeStrength reads it: (ticker + k - 1) / delay >= s
		double ticker = p.getAttackStrengthScale(0f) * delay;
		return (int) Math.max(0, Math.ceil(s * delay - ticker + 1));
	}

	/**
	 * The strength a blow sent now lands with, as can be counted on: a tick and a half under what the client reads at
	 * half a tick on (Player.attack's own reading), as the server counts its own ticker from when it got the last blow.
	 */
	private static float safeStrength(LocalPlayer p) { return p.getAttackStrengthScale(-1f); }

	/** What a blow with what is in hand does at this strength, without enchantments: never more than it does. */
	private static double blowAt(LocalPlayer p, float strength) { return blow(p) * (0.2 + 0.8 * strength * strength); }

	/**
	 * The most ticks Look.startByAngle takes to turn to this point, by the larger of the turns in yaw and pitch as it
	 * counts them, at its slowest pace and its longest spread: the crosshair is on the box before the end, the end is
	 * the bound.
	 */
	private static int turnBound(LocalPlayer p, Vec3 at) {
		Vec3 eye = p.getEyePosition();
		double dx = at.x - eye.x, dy = at.y - eye.y, dz = at.z - eye.z;
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)), pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		float angle = Math.max(Math.abs(Mth.wrapDegrees(yaw - p.getYRot())), Math.abs(pitch - p.getXRot()));
		return (int) Mth.clamp((4 + angle / 9f) * 1.6f, 3, 42) + 6;
	}

	/** Something else on the line from the eye to this point of the target, which the crosshair takes first (the nearest pickable box). */
	private static boolean screened(LocalPlayer p, Entity target, Vec3 pt) {
		Vec3 eye = p.getEyePosition();
		double d = eye.distanceTo(pt);
		for (Entity e : p.level().getEntities(p, new AABB(eye, pt).inflate(1), x -> x != target && !x.isSpectator() && x.isPickable())) {
			AABB b = e.getBoundingBox().inflate(e.getPickRadius());
			if (b.contains(eye)) return true;
			var hit = b.clip(eye, pt);
			if (hit.isPresent() && hit.get().distanceTo(eye) < d) return true;
		}
		return false;
	}

	/** The ping in ticks, as the player list has it (150 ms where it does not). */
	private static int pingTicks(Minecraft mc, LocalPlayer p) {
		var c = mc.getConnection();
		var info = c == null ? null : c.getPlayerInfo(p.getUUID());
		int ms = info == null ? 150 : Math.max(0, info.getLatency());
		return (int) Math.ceil(ms / 50.0);
	}

	/**
	 * The swelling creeper of the pen: the crosshair onto it (a look under way gives way to the turn to it, and a turn
	 * that ended off it is made once more) and the one blow that kills it (hitTick).
	 */
	private static void strike(Minecraft mc, LocalPlayer p, long now) {
		Creeper c = urgent;
		Vec3 pt = aimAt(p, c);
		boolean onIt = mc.hitResult instanceof EntityHitResult eh && eh.getEntity() == c;
		if (pt != null && !onIt && (urgentTurn != c.getId() || !look.busy())) {
			look.stop();
			look.startByAngle(p, pt, 0.1f);
			urgentTurn = c.getId();
		}
		hitTick(mc, p, c, now);
	}

	/**
	 * A bout of this creeper swelling at the buddy, counted: the third within CREEPER_WAIT is too dangerous, decided once
	 * clear of it (evadeTick; the third review of 22 Oct 2026: decided while it swelled, the buddy followed the player, who
	 * stood at the pen, and stayed inside its blast, or waited at the pen's hidden cell with nothing watching). One that
	 * is no creeper of the pen makes that hidden cell no place to wait at.
	 */
	private static void counted(Creeper c, long now) {
		ArrayDeque<Long> times = evades.computeIfAbsent(c.getId(), k -> new ArrayDeque<>());
		times.addLast(now);
		for (ArrayDeque<Long> t : evades.values()) while (!t.isEmpty() && now - t.peekFirst() > CREEPER_WAIT) t.pollFirst();
		evades.values().removeIf(ArrayDeque::isEmpty);
		if (hok == null || !hok.holds(c)) evadeOutside = true;
		if (hok != null && times.size() >= CREEPER_EVADES && evadeDanger == null) evadeDanger = "{me} left the mob farm, kept from it by a creeper.";
	}

	/**
	 * Away from these swelling creepers, from the nearest of them: to the cell evadePick gives, the walk begun anew. A
	 * bout while already getting away takes another cell against all that swell now, and the three seconds start over.
	 */
	private static void startEvade(LocalPlayer p, List<Creeper> near, long now) {
		Creeper c = nearest(p, near);
		boolean first = evadeId == -1;
		evadeId = c.getId();
		if (first) { evadeSince = now; evadeTried.clear(); evadeUntil = now + CREEPER_BACK; }
		else evadeUntil = Math.max(evadeUntil, now + CREEPER_BACK);
		evadeWaitTold = false;
		urgent = null;
		look.stop();
		// a bite under way would slow the walk out of its blast to a fifth
		AfkHelperClient.stopEating("creeper");
		// over a gap (a jump across a ditch): the pick on landing (evadeTick), and a fight's jump under way goes on
		// meanwhile (evadeYielded kept, so that its end begins the walk anew)
		if (overGap(p)) evadePickDue = true;
		else { evadeYielded = false; evadePickDue = false; evadeWalkTo(evadePick(p, near)); }
		Exchange.emit("pen", "action", "backs off", "reason", "creeper", "hp", Math.round(p.getHealth()),
			"x", Mth.floor(c.getX()), "y", Mth.floor(c.getY()), "z", Mth.floor(c.getZ()));
	}

	/**
	 * The swelling creepers as the getaway counts them (Getaway.Foe): with how near the buddy may come to each from the
	 * current place, on the ground underneath ({@code feet}: ground) while in the air (Getaway.standing).
	 */
	private static List<Getaway.Foe> foes(LocalPlayer p, List<Creeper> near, double feet) {
		Vec3 me = Getaway.standing(p.position(), feet);
		List<Getaway.Foe> out = new ArrayList<>();
		for (Creeper c : near) out.add(Getaway.Foe.of(c.position(), c.getEyePosition(), blast(c), me));
		return out;
	}

	private static List<Getaway.Foe> foes(LocalPlayer p, List<Creeper> near) { return foes(p, near, ground(p)); }

	/**
	 * A pick of the getaway: its cell (null: none, and the buddy stands), the cells the walk there keeps out of (Follow.shun),
	 * and the steps of its walk to the cell walked to now (-1: none, or not on that walk; Getaway.nearer).
	 */
	private record Pick(Getaway.Cell cell, Set<BlockPos> shun, int left) {}

	private static final Pick NO_PICK = new Pick(null, Set.of(), -1);

	/**
	 * The cell to get away to from these creepers (Getaway.pick): of the cells the buddy walks to (walkable) without coming
	 * nearer to any of them than now (Getaway.refuses), within CREEPER_RANGE, the nearest by the walk out of the
	 * sight of each or a block and a half past its blast, else the one farthest out of all their blasts as a fallback;
	 * never one given up in this getting away. Until the review of the fourth round, 22 Oct 2026, the cells came from a
	 * walk that went anywhere, kept to the buddy's side of the nearest creeper only, and the fallback was the cell farthest from
	 * that one: past it in a dead end with the way there through its own cell, or beside a second one.
	 */
	private static Pick evadePick(LocalPlayer p, List<Creeper> near) {
		Level level = p.level();
		double feet = ground(p);
		List<Getaway.Foe> foes = foes(p, near, feet);
		BlockPos from = standCell(level, p.getX(), feet, p.getZ());
		Map<BlockPos, Integer> walk = walkable(level, from, feet, CREEPER_RANGE, WALK_MAX, q -> !Getaway.refuses(foes, q.getX(), q.getY(), q.getZ()));
		Getaway.Cell c = Getaway.pick(new LevelWorld(level), walk, foes, Getaway.standing(p.position(), feet), evadeTried, CREEPER_RANGE);
		int left = evadeCell == null ? -1 : walk.getOrDefault(evadeCell, -1);
		return c == null ? NO_PICK : new Pick(c, Getaway.refused(foes, from, CREEPER_RANGE + 2), left);
	}

	private static Creeper nearest(LocalPlayer p, List<Creeper> list) {
		Creeper c = list.get(0);
		for (Creeper o : list) if (o.distanceToSqr(p) < c.distanceToSqr(p)) c = o;
		return c;
	}

	/**
	 * The walk to this pick's cell (none: standing still) begins, with the cells it keeps out of: its clock anew
	 * (Work.walkFresh; with the last walk's record a second walk failed at once, the third review of 22 Oct 2026), and a
	 * plan at once, not at the walker's next due one, which may be half a second off, with the buddy walking on to the spot
	 * meanwhile (Follow.planNow).
	 */
	private static void evadeWalkTo(Pick pick) {
		Getaway.Cell c = pick.cell();
		BlockPos cell = c == null ? null : c.pos();
		if (cell == null || !cell.equals(evadeCell)) evadeArrivedAt = 0;
		evadeCell = cell;
		evadeFeet = c == null ? 0 : c.feet();
		evadeFallback = c != null && c.fallback();
		evadeShun = pick.shun();
		if (evadeShun.isEmpty()) Follow.unshun();
		evadeWalkAgain();
	}

	/** The walk to the cell begun anew as it was picked: after a knock off it, or once a fight has had the tick. */
	private static void evadeWalkAgain() {
		evadePlanTick = evadeLookTick = Follow.ticks();
		evadeAtCell = false;
		Work.walkFresh();
		Follow.stand();
		Follow.planNow();
	}

	/** The getaway's walk keeps out of no cells any more: it ended, or a fight has the tick and walks its own way. */
	private static void unshunEvade() {
		evadeShun = Set.of();
		Follow.unshun();
	}

	/** How long the cells a getaway's walk keeps out of hold after the last tick it walked (Follow.shun). */
	static final long SHUN_HOLD = 500;

	/**
	 * Getting away from the creeper: to its cell, and standing there while one still swells at the buddy; back to what was
	 * going on once its three seconds are over and no creeper that could not be struck is near the place to go back to
	 * (clearToGoBack; a creeper in a 1x1 pit is near every spot of it), the state's own checks running during the wait
	 * there (waitChecks). The cell against the creepers that swell at the buddy now (replan). Too dangerous (three bouts of one,
	 * or waiting longer than CREEPER_WAIT) once clear of them, not while one swells. In a tick given to a fight
	 * ({@code give}) only its clock runs, the wait and its end: no key, crouch or route is touched, and whether the buddy
	 * was at the cell is left as it was (the review of the fourth round, 22 Oct 2026: standing at the cell, and the
	 * walk back to it past a block, took every tick from the fight, and the head turned twice in a tick on the way).
	 */
	private static boolean evadeTick(Minecraft mc, LocalPlayer p, long now, List<Creeper> near, boolean give) {
		// over a gap with no walk of the getaway's own under way (ticks given to a fight whose walk jumps, a first bout
		// begun there): that jump's keys as they are, until landing; the getaway's own jump goes on by its walk
		// (Work.approach, with its control in the air), and its pick waits for the landing (the review of 22 Oct 2026)
		boolean hold = !give && overGap(p) && (evadeYielded || (evadePickDue && evadeCell == null));
		// standing up for the walk off; standing in place with no cell to go to, still crouched on the pen's rim
		if (!give && !hold) HeldKeys.set(mc.options.keyShift, evadeCell == null && fenceBeside(p.blockPosition()));
		boolean swells = !near.isEmpty();
		if (!swells && now > evadeUntil) {
			waitChecks(mc, p, now);
			if (state == OFF || evadeId == -1) return state != OFF;
			if (hok != null && (evadeDanger != null || now - evadeSince > CREEPER_WAIT)) {
				tooDangerous(evadeDanger != null ? evadeDanger : "{me} left the mob farm, kept from it by a creeper.", !evadeOutside);
				return state != OFF;
			}
			if (clearToGoBack(p, now)) { endEvade(now); return false; }
			if (!evadeWaitTold) { evadeWaitTold = true; Exchange.emit("pen", "action", "waits for a creeper"); }
		}
		if (give) return true;
		// no second jump on landing
		if (hold) { HeldKeys.set(mc.options.keyJump, false); return true; }
		if (evadePickDue && !overGap(p)) {
			// landed: the pick, or the walk to the cell begun anew (its record and whether the buddy was there are of before);
			// over a gap on the getaway's own walk the jump goes on by it (below), the pick waiting for the landing
			evadePickDue = false;
			if (swells) { evadeWalkTo(evadePick(p, near)); evadePlanTick = Follow.ticks(); }
			else if (evadeCell != null) evadeWalkAgain();
		}
		if (swells) replan(p, near);
		if (evadeCell == null) { Follow.stand(); return true; }
		Vec3 to = Vec3.atBottomCenterOf(evadeCell);
		// there by the walker's own last step (within 0.35 of the middle of the cell's floor): out of its sight from the
		// middle; with 0.8 the buddy stopped at the edge of the cell before it, still in the sight of the creeper in the pit (the
		// test of 22 Oct 2026)
		if (Getaway.arrived(p.position(), evadeCell, evadeFeet, evadeAtCell)) {
			evadeAtCell = true;
			if (evadeArrivedAt == 0) evadeArrivedAt = now;
			Follow.stand();
			return true;
		}
		// off the cell (a knock): a walk anew, or the last walk's clock fails it at once
		if (evadeAtCell) evadeWalkAgain();
		if (!evadeShun.isEmpty()) Follow.shun(evadeShun, now + SHUN_HOLD);
		evadeWalkTick = Follow.ticks();
		if (!Work.approach(mc, p, "pen-creeper", to, now)) {
			if (swells) nextEvadeCell(p, near, "cannot get there", null);
			else { evadeTried.add(evadeCell); evadeWalkTo(NO_PICK); }
		}
		return true;
	}

	/**
	 * The cell, against the creepers that swell at the buddy now, every tick: one of them sees it (Getaway.seen), the walker
	 * finds no way there after three ticks, or there was none and one may be found now: the next (until the third review
	 * of 22 Oct 2026 nothing was planned again, and the buddy stood beside one that followed and swelled anew, or before a
	 * cell out of reach). A fallback, seen by design, is looked past every five ticks instead, and given up only
	 * for a cell out of their sight or one half a block farther out of their blasts (Getaway.better): given up for being
	 * seen, it was a cell a little farther on every five ticks, with the keys let go and "another cell" each time, and
	 * past the creeper as often as not (the review of the fourth round). On the way to a cell out of their sight, every
	 * five ticks one nearer by the walk (Getaway.nearer, the test of 22 Oct 2026).
	 */
	private static void replan(LocalPlayer p, List<Creeper> near) {
		if (overGap(p)) return;
		int t = Follow.ticks(), since = t - evadePlanTick;
		if (evadeCell == null) {
			if (since >= 10) nextEvadeCell(p, near, "no spot out of sight", null);
			return;
		}
		if (evadeFallback) {
			if (t - evadeLookTick >= 5) {
				evadeLookTick = t;
				Pick next = evadePick(p, near);
				double now = Getaway.score(foes(p, near), new Vec3(evadeCell.getX() + 0.5, evadeFeet, evadeCell.getZ() + 0.5));
				if (Getaway.better(next.cell(), now) && !next.cell().pos().equals(evadeCell)) {
					nextEvadeCell(p, near, next.cell().fallback() ? "farther off" : "out of sight", next);
					return;
				}
			}
		} else if (since >= 5 && Getaway.seen(new LevelWorld(p.level()), foes(p, near), evadeCell)) {
			nextEvadeCell(p, near, "seen", null);
			return;
		} else if (!evadeAtCell && t - evadeLookTick >= 5) {
			// on the way, every five ticks: a cell out of their sight from where they are now, nearer by the walk
			// (Getaway.nearer), as one that fell into the pit while it swelled is out of sight from a step back
			evadeLookTick = t;
			Pick next = evadePick(p, near);
			if (Getaway.nearer(next.cell(), next.left(), evadeCell)) { nextEvadeCell(p, near, "nearer", next); return; }
		}
		if (since >= 3 && !evadeAtCell && !Follow.routing() && !inCell(p, evadeCell)) nextEvadeCell(p, near, "no path", null);
	}

	/**
	 * This cell given up for why it is no good now: the next one, and the walk to it begun. With {@code pre}, a pick that
	 * outdid a fallback: that one, and the fallback is not given up.
	 */
	private static void nextEvadeCell(LocalPlayer p, List<Creeper> near, String why, Pick pre) {
		BlockPos was = evadeCell;
		if (was != null && pre == null) evadeTried.add(was);
		Pick next = pre != null ? pre : evadePick(p, near);
		BlockPos to = next.cell() == null ? null : next.cell().pos();
		if (was == null && to == null) { evadePlanTick = Follow.ticks(); return; }
		Exchange.emit("pen", "action", "another cell", "reason", why,
			"x", to == null ? p.getBlockX() : to.getX(), "y", to == null ? p.getBlockY() : to.getY(), "z", to == null ? p.getBlockZ() : to.getZ());
		evadeWalkTo(next);
	}

	/** The feet in that cell: the walker stops as it enters a goal's cell, at its edge as often as not. */
	private static boolean inCell(LocalPlayer p, BlockPos cell) {
		return Mth.floor(p.getX()) == cell.getX() && Mth.floor(p.getZ()) == cell.getZ() && Math.abs(p.getY() - cell.getY()) < 1.2;
	}

	/**
	 * While the buddy waits at the getaway's cell, what the current state looks at in its own tick, which does not come
	 * meanwhile: the retreat's way back and its "hit again" (retreatBack, retreatHit; a blow at this cell counts from a
	 * moment after the first arrival there, as for cover, since one may have been in the air), and at a spot, on the way to
	 * one or in the night's pause low health, no food and a spear (safety), whose retreat then has the wait. Only once
	 * nothing has swelled within its reach for SWELL_QUIET, seen or not, as a stop takes every creeper watch with it.
	 * Until the review of the fourth round, 22 Oct 2026, none of it ran for the two minutes the wait there could last, and a
	 * retreat stood at a cell in the pit's sight and took its arrows.
	 */
	private static void waitChecks(Minecraft mc, LocalPlayer p, long now) {
		if (hok == null || Follow.ticks() - swellAt <= SWELL_QUIET) return;
		if (state == RETREAT) {
			// the retreat ends, and endEvade tells it and goes to a spot once the getaway ends (now or after its wait):
			// told here as well, the getaway's end told it again with a second "naar plek", ticks later if it waited on
			if (retreatBack(p, now)) { spot = null; setState(GOING, now); goSince = now; }
			else if (retreatHit(p, now, evadeArrivedAt > 0 ? evadeArrivedAt + COVER_GRACE : 0)) hitAgain();
		} else if (state == GOING || state == SWITCHING || state == LEAN_IN || state == FARMING || state == NIGHT) safety(mc, p, mobs(p), now);
	}

	/**
	 * Done getting away. Walking to a spot or leaning in when it came: a spot chosen anew, with the walk's own time
	 * again (else the time it spent away ran out the walk and marked the spot bad); at the spot, farmTick sends the buddy back
	 * to it; the retreat and the night go on to their cell.
	 */
	private static void endEvade(long now) {
		evadeId = -1;
		evadePickDue = false;
		evadeCell = null;
		evadeWaitTold = false;
		evadeAtCell = false;
		evadeFallback = false;
		evadeArrivedAt = 0;
		evadeYielded = false;
		unshunEvade();
		evadeFor.clear();
		evadeTried.clear();
		evadeDanger = null;
		evadeOutside = false;
		if (hok == null) return;
		// back to the pen only from a spot or on the way to one: the retreat, the night and the step back go on to their
		// cell, and the retreat's own end tells it (by the tick the retreat began, a getaway during an older one told it
		// while the buddy walked on away from the pen, and one that began it in the wait did not)
		boolean back = state == GOING || state == SWITCHING || state == LEAN_IN || state == FARMING;
		if (back) toldBack();
		if (back && state != FARMING && wentTick != Follow.ticks()) go("to a spot", now);
	}

	/**
	 * "terug naar hok", once a tick: the retreat's way back in the wait at a getaway's cell and the end of the getaway fell
	 * in one tick, and told it twice, each with its own "naar plek" (the check of the review of the fourth round, 22 Oct
	 * 2026; the wait ends the retreat only now, and no walk to a spot is set up again in a tick one was, wentTick).
	 */
	private static void toldBack() {
		long t = Follow.ticks();
		if (backToldTick == t) return;
		backToldTick = t;
		Exchange.emit("pen", "action", "back to the pen");
	}

	/**
	 * Whether going back is allowed after getting away. To a spot of the pen: none within CREEPER_SWELL of a creeper that
	 * could not be struck there, nor of two creepers (noSpot; choose keeps away from such a spot while another is free).
	 * Until the third review of 22 Oct 2026 only one a full blow does not kill counted, and the buddy went back three times
	 * to one a blow kills but that could not be struck in time. The retreat and the night to their cell out of the pen's
	 * sight: no creeper near it at all (none is hit then), where they went back to it whatever stood there. With one
	 * there, the night stays put, and the retreat takes another cell of its own (lookAround), or stays put as well:
	 * it waited for that cell up to two minutes, with none of its own checks running (the review of the fourth round).
	 * The step back and the resume walk to theirs.
	 */
	private static boolean clearToGoBack(LocalPlayer p, long now) {
		if (hok == null) return true;
		if (state == RETREAT || state == NIGHT) {
			if (state == NIGHT && nightStays) return true;
			if (state == RETREAT && (retreatStays || retreatAround != null)) return true;
			BlockPos cell = state == RETREAT && retreatTo != null ? retreatTo : retreatCell;
			if (cell == null) return true;
			Vec3 at = Vec3.atBottomCenterOf(cell);
			List<Creeper> by = p.level().getEntitiesOfClass(Creeper.class, new AABB(at, at).inflate(CREEPER_SWELL + 1), Creeper::isAlive);
			if (!creeperAt(by, at)) return true;
			// the night's pause waits where it is, out of the creeper's sight; the retreat looks for a cell of its own
			if (state == NIGHT) nightStays = true;
			else lookAround(p, by);
			return true;
		}
		PenShape.Spot s = choose(p, now, null);
		return s == null || !noSpot(s.at(), unstrikeable(p), creepersByPen(p));
	}

	/** How far the retreat looks for a cell of its own where a creeper stood by its cell (lookAround), each way. */
	static final int RETREAT_AROUND = 10;

	/**
	 * A creeper stood by the retreat's cell: another one for this retreat, the nearest by the walk there (walkable, never
	 * nearer to those creepers than now: Getaway.refuses) that no column of the pen sees anywhere in it, with no
	 * creeper within its swell and one more, looked for a little each tick while standing (CoverShape.Search, in
	 * retreatTick); the current cell when it is such a one, and with none the retreat stays put (retreatStays). The pen's
	 * own cell stays what it is, for the night, the step back and too dangerous.
	 */
	private static void lookAround(LocalPlayer p, List<Creeper> by) {
		Level level = p.level();
		LevelWorld w = new LevelWorld(level);
		double feet = ground(p);
		BlockPos from = standCell(level, p.getX(), feet, p.getZ());
		List<CoverShape.Foe> pen = List.of(new CoverShape.Foe(null, hok.flood, hok.eye, hok.w));
		List<Creeper> all = level.getEntitiesOfClass(Creeper.class, p.getBoundingBox().inflate(RETREAT_AROUND + CREEPER_SWELL + 1), Creeper::isAlive);
		java.util.function.Predicate<BlockPos> clear = q -> {
			Vec3 at = Vec3.atBottomCenterOf(q);
			for (Creeper c : all) if (c.position().distanceTo(at) < CREEPER_SWELL + 1) return false;
			return true;
		};
		retreatTo = null;
		retreatArrived = 0;
		if (clear.test(from) && CoverShape.hidden(w, pen, from, feet)) { retreatStays = true; return; }
		List<Getaway.Foe> keep = foes(p, by, feet);
		Map<BlockPos, Integer> walk = walkable(level, from, feet, RETREAT_AROUND, WALK_MAX, q -> !Getaway.refuses(keep, q.getX(), q.getY(), q.getZ()));
		retreatAround = new CoverShape.Search(w, walk, from, RETREAT_AROUND, pen, List.of(), clear);
	}

	/**
	 * The creepers by the pen that could not be struck in time if one swelled at the buddy at a spot: no creeper of the pen, one a
	 * full blow does not kill (Weakness and Strength counted), one beside someone a sword's sweep would hit, one whose
	 * count is not down to nothing (Fuse.Count: it just landed, or came into sight).
	 */
	private static List<Creeper> unstrikeable(LocalPlayer p) {
		Hok h = hok;
		if (h == null) return List.of();
		double b = blow(p);
		return p.level().getEntitiesOfClass(Creeper.class, h.box.inflate(CREEPER_SWELL + 4),
			c -> c.isAlive() && (!huntable(c, h, p) || c.getHealth() > b || sweepsSomeone(p, c) || count(c) > 0));
	}

	/** Every creeper by the pen, alive, where unstrikeable looks: for two at a spot (noSpot). */
	private static List<Creeper> creepersByPen(LocalPlayer p) {
		Hok h = hok;
		return h == null ? List.of() : p.level().getEntitiesOfClass(Creeper.class, h.box.inflate(CREEPER_SWELL + 4), Creeper::isAlive);
	}

	/**
	 * A spot not to go back to, nor to walk round to (choose, rounds, clearToGoBack): within CREEPER_SWELL of a creeper that
	 * could not be struck there ({@code strong}), or of two creepers, any ({@code all}), as no new one is struck while two
	 * swell at the buddy: the getaway is from both. Two of the pen in the pit below the spot, each one a blow kills, had the
	 * buddy walk back to them twice before their third bout ended the pen (the review of the fourth round, 22 Oct 2026).
	 */
	private static boolean noSpot(Vec3 at, List<Creeper> strong, List<Creeper> all) {
		if (creeperAt(strong, at)) return true;
		int n = 0;
		for (Creeper c : all) if (c.position().distanceTo(at) < CREEPER_SWELL && ++n >= 2) return true;
		return false;
	}

	/**
	 * A sword in hand and someone in the reach of its sweep round this creeper, as a blow at it would go from a spot
	 * beside it: handWillHit's bystander, from wherever the buddy stands now.
	 */
	private static boolean sweepsSomeone(LocalPlayer p, Creeper c) {
		if (unsafe || !p.getMainHandItem().is(ItemTags.SWORDS)) return false;
		AABB sweep = c.getBoundingBox().inflate(1 + SWEEP_MARGIN_MOVER, 0.25 + SWEEP_MARGIN_MOVER, 1 + SWEEP_MARGIN_MOVER);
		for (LivingEntity e : p.level().getEntitiesOfClass(LivingEntity.class, sweep, x -> x != p && x != c && x.isAlive() && !x.isSpectator())) {
			if (e instanceof ArmorStand a && a.isMarker()) continue;
			if (!huntable(e, hok, p) && !sweepable(e)) return true;
		}
		return false;
	}

	/** One of these creepers within swelling distance of that point. */
	private static boolean creeperAt(List<Creeper> creepers, Vec3 at) {
		for (Creeper c : creepers) if (c.position().distanceTo(at) < CREEPER_SWELL) return true;
		return false;
	}

	/** A live creeper within its swell's reach and one more of that cell (null: none). */
	private static boolean creeperBy(BlockPos cell) {
		Level level = AfkHelperClient.mc().level;
		if (cell == null || level == null) return false;
		Vec3 at = Vec3.atBottomCenterOf(cell);
		for (Creeper c : level.getEntitiesOfClass(Creeper.class, new AABB(at, at).inflate(CREEPER_SWELL + 1), Creeper::isAlive))
			if (c.position().distanceTo(at) < CREEPER_SWELL + 1) return true;
		return false;
	}

	private static void clearCreepers() {
		urgent = null;
		urgentTurn = -1;
		urgentLate = false;
		unseenHitch = false;
		struckId = -1;
		evadeId = -1;
		evadePickDue = false;
		evadeCell = null;
		evadeWaitTold = false;
		evadeAtCell = false;
		evadeFallback = false;
		evadeArrivedAt = 0;
		evadeYielded = false;
		unshunEvade();
		evades.clear();
		evadeFor.clear();
		evadeTried.clear();
		evadeDanger = null;
		evadeOutside = false;
		counts.clear();
	}

	/** The most cells {@link #walkable} looks at: a square of ten round the buddy over a few heights. */
	static final int WALK_MAX = 3000;

	/** {@link #walkable} into any cell. */
	static Map<BlockPos, Integer> walkable(Level level, BlockPos from, double fromFeet, int range, int max) {
		return walkable(level, from, fromFeet, range, max, null);
	}

	/**
	 * The cells the buddy walks to from {@code from} (the feet at {@code fromFeet}) within {@code range} each way, with the
	 * steps of that walk, nearest by it first: to a cell beside one (four ways) at the height of a slab, up a jump of a
	 * block and a slab with headroom, or down as far, so that the way back is always open; never into a cell of
	 * the pen's fence, never through a block; no digging, no building, no swimming down; with {@code enter}, never into a
	 * cell it refuses, nor so on to the cells beyond it (a getaway's, which never comes nearer to a swelling creeper than
	 * the buddy's own cell: Getaway.refuses). What a cover or a getting away may take; the walker itself takes drops of
	 * three, which is how the buddy came to stand in a hole.
	 */
	static Map<BlockPos, Integer> walkable(Level level, BlockPos from, double fromFeet, int range, int max, java.util.function.Predicate<BlockPos> enter) {
		Map<BlockPos, Integer> steps = new LinkedHashMap<>();
		Map<BlockPos, Double> feetOf = new HashMap<>();
		ArrayDeque<BlockPos> open = new ArrayDeque<>();
		steps.put(from, 0);
		feetOf.put(from, fromFeet);
		open.add(from);
		while (!open.isEmpty() && steps.size() < max) {
			BlockPos cur = open.poll();
			int n = steps.get(cur);
			double fc = feetOf.get(cur);
			for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
				BlockPos h = cur.relative(d);
				if (Math.abs(h.getX() - from.getX()) > range || Math.abs(h.getZ() - from.getZ()) > range) continue;
				for (BlockPos cand : new BlockPos[] { h, h.above(), h.below() }) {
					double fh = Follow.floor(level, cand);
					if (Double.isNaN(fh)) continue;
					double dy = fh - fc;
					if (dy > 1.25 || dy < -1.25) continue;
					// up: headroom where the jump goes; down: through the neighbouring cell, not through a block
					if (dy > 0.6 && !headroom(level, cur, fc, dy)) continue;
					if (cand.getY() < h.getY() && (!Follow.passable(level, h, true) || !Follow.passable(level, h.above(), false))) continue;
					if (!steps.containsKey(cand) && !Follow.fenced(cand) && (enter == null || enter.test(cand))) {
						steps.put(cand, n + 1);
						feetOf.put(cand, fh);
						open.add(cand);
					}
					break;
				}
			}
		}
		return steps;
	}

	/** Headroom over the cell for a jump of {@code rise} from feet at {@code feet}, as Follow's planner wants it. */
	private static boolean headroom(Level level, BlockPos cur, double feet, double rise) {
		for (int y = cur.getY() + 1; y < feet + 1.8 + rise + 0.05; y++) if (!Follow.passable(level, new BlockPos(cur.getX(), y, cur.getZ()), false)) return false;
		return true;
	}

	// ---------- the night ----------

	/**
	 * Night with someone asleep, or a bed wanted (the player asleep nearby, or the base's bed): the farming pauses
	 * (penNightPause). No blows, no rounds, no turns, so that after five idle minutes the server can count the buddy away
	 * and the night pass with only the two of them on; the buddy waits at the cell out of the sight of the pen's mobs, back
	 * at the spot in the morning. With a bed to go to, the sleep step of the chain goes there.
	 */
	private static boolean nightCheck(LocalPlayer p, long now) {
		if (!AfkConfig.get().penNightPause || !Follow.bedtime(p.level())) return false;
		if (!Sleepers.now(p) && !Follow.bedWanted(p)) return false;
		nightSince = now;
		nightStays = false;
		setState(NIGHT, now);
		Work.walkFresh();
		look.stop();
		Exchange.emit("pen", "action", "night");
		return true;
	}

	private static boolean nightTick(Minecraft mc, LocalPlayer p, long now) {
		if (!Follow.bedtime(p.level()) || (!Sleepers.lately(p) && !Follow.bedWanted(p) && !p.isSleeping())) {
			Exchange.emit("pen", "action", "night over");
			if (spot != null && Math.hypot(spot.at().x - p.getX(), spot.at().z - p.getZ()) <= 0.5) setState(FARMING, now);
			else go("to a spot", now);
			return true;
		}
		if (Follow.bedWanted(p) || p.isSleeping()) return false;   // the sleep step goes to bed
		// the pause out of the sight of every mob of the pen: none swells at the buddy, none shoots, and standing still there
		// counts as idle as it did at the spot (it was minutes at a spot a creeper in a near column swells at, with no blow and
		// no step back). The danger of the farm still holds on the way: low, a spear; and a creeper that came up close
		// there, the pen's or not, is creeperTick's
		if (safety(mc, p, mobs(p), now)) return true;
		HeldKeys.set(mc.options.keySprint, false);
		BlockPos cell = nightStays ? null : retreatCell(p, SEARCH_BUDGET);
		if (cell != null) {
			// as the retreat takes it there: within 0.8 counts as there, and standing still, which the idle count wants
			Vec3 to = Vec3.atBottomCenterOf(cell);
			if (p.position().distanceTo(to) >= 0.8) {
				// no way there: as for a retreat, that cell is given up, and the buddy waits in place (at the spot, mostly)
				if (!Work.approach(mc, p, "pen-night", to, now)) { nightStays = true; retreatCell = null; retreatHidden = false; Follow.stand(); }
				return true;
			}
		}
		// there, or no cell known (yet): stand, crouched and still, in place (at the edge of the spot)
		Follow.stand();
		HeldKeys.set(mc.options.keyUp, false);
		boolean atSpot = spot != null && Math.hypot(spot.at().x - p.getX(), spot.at().z - p.getZ()) <= 0.5;
		HeldKeys.set(mc.options.keyShift, atSpot && (spot.crouch() || spot.edge()));
		return true;
	}

	// ---------- stepping back after the end ----------

	/** The nearest cell two blocks or more from every fenced column, with no drop beside it, within six. */
	private static BlockPos backoffCell(LocalPlayer p, Hok h) {
		Level level = p.level();
		BlockPos at = p.blockPosition(), best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos q : BlockPos.betweenClosed(at.offset(-6, -2, -6), at.offset(6, 2, 6))) {
			if (Double.isNaN(Follow.floor(level, q)) || Follow.fenced(q)) continue;
			if (h.flat(Vec3.atCenterOf(q)) < BACKOFF) continue;
			double d = Vec3.atBottomCenterOf(q).distanceTo(p.position());
			if (d > BACKOFF_RANGE) continue;
			if (drop(level, q)) continue;
			if (d < bestD) { bestD = d; best = q.immutable(); }
		}
		return best;
	}

	private static boolean drop(Level level, BlockPos q) {
		// the cell beside it at the height of the feet, as Grinder takes it: empty down two more is a drop of two blocks or more
		for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) if (Grinder.dropAhead(level, q.relative(d))) return true;
		return false;
	}

	/**
	 * The step back, every tick (watch), also while a fight or a creeper's getaway holds it up: more than TELEPORT between
	 * two ticks in a row (an accepted /tpahere) or another dimension ends it, as guard does for the farm, and a walk off
	 * it, however far, does not (backoffTick walks to its cell after the hold). Until the review of the fourth round
	 * of 22 Oct 2026 a cell over 16 blocks off ended it in backoffTick, also after a fight or a getaway had walked the buddy
	 * there, and a too-dangerous step back then gave no stay. While it lasts the pen's fence and its caution (tail) stand,
	 * under a hold too: their half minute from the stop could run out in a long one, and then nothing kept the walk to the
	 * cell afterwards off the pen.
	 */
	private static void backoffWatch(LocalPlayer p, int tick, long now) {
		Vec3 was = backoffPos;
		boolean consecutive = tick - backoffPosTick <= 1;
		backoffPos = p.position();
		backoffPosTick = tick;
		if ((consecutive && was != null && was.distanceTo(backoffPos) > TELEPORT) || !Work.dimOf(p.level()).equals(backoffDim)) {
			endBackoff();
			return;
		}
		if (tail != null) {
			tailUntil = Math.max(tailUntil, now + 2000);
			Follow.fence(tail.fence, tailUntil);
		}
	}

	/**
	 * The walk of the step back; there the stay it waits to give (too dangerous) takes hold, and not before. A step back
	 * with neither a stay to give nor one to keep (the stay lifted meanwhile: stayLifted) ends: following resumes at once. Held
	 * up by an earlier step of the chain, also from its first tick (a fight walks by its own way, not Work.approach; a
	 * creeper's getaway), it goes on afterwards as a walk anew from wherever the hold left the buddy (backoffAnew). Until the third
	 * review of 22 Oct 2026 its first tick after a hold failed as a walk that got no nearer in four seconds; until the
	 * review of the fourth round that day the hold moved its clock on by ten seconds at most and its time stayed that of
	 * the way from where it began, so a longer hold, or one that ended farther off, ended it by time.
	 */
	private static boolean backoffTick(Minecraft mc, LocalPlayer p, long now) {
		if (backoffCell != null && backoffSeen > 0 && now - backoffSeen > 1000) backoffAnew(p, now);
		backoffSeen = now;
		if (backoffCell == null || now - backoffSince > backoffLimit || !p.isAlive()) { endBackoff(); return false; }
		if (backoffStay == null && !Follow.staying()) { endBackoff(); return false; }
		Vec3 to = Vec3.atBottomCenterOf(backoffCell);
		// there: within 0.4 of its middle, or in the cell once the walker is done (it stops as it enters a goal's cell, at
		// its edge as often as not; with the middle alone the step back of too dangerous ran out its time there and ended
		// without the stay it was to give)
		boolean inCell = Mth.floor(p.getX()) == backoffCell.getX() && Mth.floor(p.getZ()) == backoffCell.getZ();
		if ((Math.hypot(to.x - p.getX(), to.z - p.getZ()) < 0.4 || (inCell && !Follow.routing())) && Math.abs(to.y - p.getY()) < 0.6) {
			String stay = backoffStay;
			endBackoff();
			if (stay != null) Follow.stayHere(stay);
			return false;
		}
		if (!Work.approach(mc, p, "pen-away", to, now)) { endBackoff(); return false; }
		return true;
	}

	/**
	 * After a hold, the step back's walk from where the buddy is now, as a walk of its own: the time for the way from there
	 * (backoffTime) and Work.approach's record anew (the review of the fourth round, 22 Oct 2026).
	 */
	private static void backoffAnew(LocalPlayer p, long now) {
		backoffSince = now;
		backoffLimit = backoffTime(Vec3.atBottomCenterOf(backoffCell).distanceTo(p.position()));
		Work.walkFresh();
	}

	/** A step back's time for a walk of this many blocks: the walk's own time, never under BACKOFF_MAX. */
	private static long backoffTime(double d) { return Math.max(BACKOFF_MAX, GO_MIN + (long) (d * 800)); }

	// ---------- cover during a stay ----------

	/**
	 * Staying, and hit by a foe the fight leaves be where it stands (in a pit or a farm's cell: Guard.leftBeHit): the buddy gets
	 * out of the sight of it and of every other such foe near, never left standing still to be hit (0.4.80 began to leave
	 * such foes be, after the pen or by any pit, and a staying alt took their arrows on the spot). The cell is the
	 * nearest by the walk there (walkable) that no column of their pits sees, middle and far walls, nor their eyes now
	 * (CoverShape), and the walk is timed by its steps; until the third review of 22 Oct 2026 it was the nearest by straight
	 * line hidden from the one eye of that tick: a cell behind the rock beside the tunnel, the floor of a hole, the very cell
	 * stood in. The cell is held while the cover is fresh (half a minute from the arrival there), as the stay does, so that the
	 * carrier and the base wait: the base walked the buddy straight back into the archer's sight, and the next arrow
	 * anywhere was "hit again there", which lifted the stay and the base keeping. Hit there after the grace, or a walk that
	 * does not get there in its time: the next cell, three in a bout. None (left): following the player again, and the
	 * need says why; in a base the keeping goes on, with cells in that base only and the need saying that none was found (at
	 * three hearts or less: following after all), and a third cover from one foe within five minutes is told as being shot
	 * at again and again. The chain's step after the farm (Follow.tick), during a stay; true while the walk or the hold
	 * keeps the hands busy.
	 */
	static boolean cover(Minecraft mc, LocalPlayer p) {
		long now = System.currentTimeMillis();
		Entity shot = Guard.leftBeHit(now);
		// a new blow of that foe: one damage event is one blow (each is a new DamageSource), for however many ticks Guard notes it
		DamageSource src = p.getLastDamageSource();
		boolean hit = shot != null && src != null && src != coverSource && src.getEntity() == shot;
		if (hit) coverSource = src;
		coverHolds = false;
		// not asked for a while (following again, a fight came first), quiet at the cell for a while, or what was hidden from
		// is gone: what was is forgotten, and the stay, the carrier or the base take over again
		if (now - coverSeen > 1000 || (coverArrived > 0 && !hit && (now - coverQuiet > COVER_FORGET || coverFoeGone(p)))) forgetCover();
		coverSeen = now;
		if (coverFoe == -1) {
			if (!hit) return false;
			coverFoe = shot.getId();
			repeatedCover(shot, now);
			searchCover(p, shot, "no spot out of sight");
		} else if (hit && CoverShape.seenAfterAll(coverCell != null, coverFirst, now - coverQuiet, COVER_GRACE, coverThere(p), coverArrived > 0)) {
			// hit there once what was in the air at the first arrival has landed, or on the way back to it after a push
			// (that blow's own knockback): that cell is seen after all. A hit on the way there the first time, or within the
			// grace, came from before; the walk goes on. The grace counts from the first arrival: from each return after a push,
			// as until the check of the third review of 22 Oct 2026, every arrow on hard fell in it and a seen cell was held
			// for its whole half minute. A hit while a search runs has no cell to blame, and the search goes on with its own
			// reason (the review of the fourth round, 22 Oct 2026: it began anew as "weer geraakt")
			if (!retryCover(p, shot, "hit again")) return false;
		}
		if (coverSearch != null) {
			if (!coverSearch.step(SEARCH_BUDGET)) { Follow.stand(); return true; }
			CoverShape.Cell c = coverSearch.found();
			coverSearch = null;
			boolean far = false;
			// none out of sight within ten (round a pit two deep on flat ground every cell is in the sight of some part of
			// it): out of its reach then, where it does not even take aim (the test of 22 Oct 2026: on the open platform
			// none was found, and the buddy left the stay to follow the player, who was offline)
			if (c == null) { c = outOfReach(p, shot); far = c != null; }
			if (c == null) { noCover(p, shot, coverWhy); return false; }
			goCover(p, c, now);
			if (far) Exchange.emit("guard", "action", "out of reach", "steps", c.steps(), "x", c.pos().getX(), "y", c.pos().getY(), "z", c.pos().getZ());
		}
		if (coverCell == null) { forgetCover(); return false; }
		Vec3 to = coverAt();
		double flat = Math.hypot(to.x - p.getX(), to.z - p.getZ()), dy = Math.abs(to.y - p.getY());
		// in the cell, all of which is out of sight (the walker stops as it enters a goal's cell, at its edge as often as not)
		boolean inCell = Mth.floor(p.getX()) == coverCell.getX() && Mth.floor(p.getZ()) == coverCell.getZ() && dy < 0.6;
		if (coverArrived == 0) {
			// there: within 0.4 of its middle, or in the cell once the walker is done with its way there
			if ((flat < 0.4 || (inCell && !Follow.routing())) && dy < 0.6 && p.onGround()) {
				coverArrived = now;
				if (coverFirst) { coverQuiet = now; coverFirst = false; }
				Follow.stand();
				coverHolds = true;
				return true;
			}
			if (now - coverSince > coverLimit || !Work.approach(mc, p, "cover", to, now)) return retryCover(p, shot, "cannot get there");
			return true;
		}
		// pushed out of the cell while holding it (knockback, a push, water): back to it, a walk of its own and no try; its
		// half minute runs on
		if (!inCell && (flat > COVER_DRIFT || dy > 0.6)) {
			coverArrived = 0;
			coverSince = now;
			coverLimit = GO_MIN + (long) (flat * 800);
			Work.walkFresh();
			return Work.approach(mc, p, "cover", to, now) || retryCover(p, shot, "cannot get there");
		}
		Follow.stand();
		coverHolds = true;
		return true;
	}

	/** The cell of the cover is held this tick: Follow looks at the player meanwhile, as the stay does. */
	static boolean holdsCover() { return coverHolds; }

	/** A search for cover from where the buddy stands (the ground below, while airborne from an arrow's knockback). */
	private static void searchCover(LocalPlayer p, Entity shot, String why) {
		Level level = p.level();
		double feet = ground(p);
		BlockPos from = standCell(level, p.getX(), feet, p.getZ());
		// in a base its own cells only: a cell past its edge ended the keeping, and the stay then held the buddy out there for good
		String keeper = Base.keeper();
		java.util.function.Predicate<BlockPos> allowed = keeper == null ? c -> true
			: c -> keeper.equalsIgnoreCase(Base.ownerAt(level, Vec3.atBottomCenterOf(c)));
		coverSearch = new CoverShape.Search(new LevelWorld(level), walkable(level, from, feet, COVER_RANGE, WALK_MAX), from, COVER_RANGE,
			coverFoes(p, shot), coverBad, allowed);
		coverWhy = why;
		coverCell = null;
		coverArrived = 0;
	}

	/** The cell whose floor the feet are at: on soul sand, a chest or a path the one above that block, as the walker has it. */
	private static BlockPos standCell(Level level, double x, double feet, double z) {
		BlockPos c = BlockPos.containing(x, feet + 0.01, z);
		if (Double.isNaN(Follow.floor(level, c)) && Math.abs(Follow.floor(level, c.above()) - feet) < 0.2) return c.above();
		return c;
	}

	/**
	 * Whom to hide from: the foe that hit the buddy, the one the bout began for, and every other foe the fight left be lately
	 * (Guard.leftBeIds) within COVER_FOES, each with the closed space it stands in (enclosedWhy keeps it a second per mob).
	 */
	private static List<CoverShape.Foe> coverFoes(LocalPlayer p, Entity shot) {
		Set<Integer> ids = new LinkedHashSet<>();
		if (shot != null) ids.add(shot.getId());
		if (coverFoe >= 0) ids.add(coverFoe);
		ids.addAll(Guard.leftBeIds());
		List<CoverShape.Foe> out = new ArrayList<>();
		for (int id : ids) {
			Entity e = p.level().getEntity(id);
			if (!(e instanceof LivingEntity) || !e.isAlive() || e.distanceTo(p) > COVER_FOES) continue;
			enclosedWhy(p, e);
			PitSeen seen = pitCache.get(id);
			PenShape.Pit pit = seen == null ? null : seen.pit();
			Entity body = e.getVehicle() instanceof LivingEntity v ? v : e;
			AABB b = body.getBoundingBox();
			out.add(new CoverShape.Foe(e.getEyePosition(), pit == null ? null : pit.flood(), e.getEyeY() - body.getY(), b.maxX - b.minX));
		}
		return out;
	}

	/**
	 * The nearest cell by the walk there (walkable, to OUT_RANGE) that lies farther than FOE_REACH from each foe hidden
	 * from and from every column of its pit: past a skeleton's follow range (16), where it neither targets nor
	 * shoots. Not the current cell, not one that failed this bout, not beside a drop, in a base its own cells only.
	 * Null for none.
	 */
	private static CoverShape.Cell outOfReach(LocalPlayer p, Entity shot) {
		Level level = p.level();
		double feet = ground(p);
		BlockPos from = standCell(level, p.getX(), feet, p.getZ());
		List<CoverShape.Foe> foes = coverFoes(p, shot);
		if (foes.isEmpty()) return null;
		String keeper = Base.keeper();
		for (Map.Entry<BlockPos, Integer> e : walkable(level, from, feet, OUT_RANGE, OUT_MAX).entrySet()) {
			BlockPos q = e.getKey();
			if (q.equals(from) || coverBad.contains(q) || drop(level, q)) continue;
			if (keeper != null && !keeper.equalsIgnoreCase(Base.ownerAt(level, Vec3.atBottomCenterOf(q)))) continue;
			double f = Follow.floor(level, q);
			if (Double.isNaN(f)) continue;
			Vec3 c = new Vec3(q.getX() + 0.5, f, q.getZ() + 0.5);
			boolean clear = true;
			for (CoverShape.Foe foe : foes) {
				if (foe.eye().distanceTo(c) <= FOE_REACH) { clear = false; break; }
				if (foe.pit() != null) for (long col : foe.pit().cols())
					if (Math.hypot(PenShape.kx(col) + 0.5 - c.x, PenShape.kz(col) + 0.5 - c.z) <= FOE_REACH) { clear = false; break; }
				if (!clear) break;
			}
			if (clear) return new CoverShape.Cell(q, f, e.getValue());
		}
		return null;
	}

	/** How far out of reach: past a skeleton's follow range of 16 by one; within a walk of this many each way, these many cells at most. */
	static final double FOE_REACH = 17;
	static final int OUT_RANGE = 24, OUT_MAX = 9000;

	/** To this cell, timed by the steps of the walk there. */
	private static void goCover(LocalPlayer p, CoverShape.Cell c, long now) {
		coverCell = c.pos();
		coverFeet = c.feet();
		coverSince = now;
		coverLimit = GO_MIN + (long) c.steps() * 800;
		coverArrived = 0;
		coverFirst = true;
		coverTries++;
		Work.walkFresh();
		Entity foe = p.level().getEntity(coverFoe);
		Exchange.emit("guard", "action", "takes cover", "goal", foe == null ? "" : name(foe), "steps", c.steps(), "attempt", coverTries,
			"x", c.pos().getX(), "y", c.pos().getY(), "z", c.pos().getZ());
	}

	/**
	 * That cell failed (not reached in its time, or seen there after all): the next, from where the buddy stands now, three
	 * in a bout; then no cover. True while there is a next to look for.
	 */
	private static boolean retryCover(LocalPlayer p, Entity shot, String why) {
		if (coverCell != null) coverBad.add(coverCell);
		coverCell = null;
		coverArrived = 0;
		Follow.stand();
		if (coverTries >= COVER_TRIES) { noCover(p, shot, why); return false; }
		searchCover(p, shot, why);
		return true;
	}

	private static Vec3 coverAt() { return new Vec3(coverCell.getX() + 0.5, coverFeet, coverCell.getZ() + 0.5); }

	/** At the cover's cell, as a hit there counts: within an arrow's knockback of its middle. */
	private static boolean coverThere(LocalPlayer p) {
		if (coverCell == null) return false;
		Vec3 to = coverAt();
		return Math.hypot(to.x - p.getX(), to.z - p.getZ()) <= COVER_THERE && Math.abs(to.y - p.getY()) <= 1.1;
	}

	/** The foe the bout began for is dead or gone: nothing to hold on for. */
	private static boolean coverFoeGone(LocalPlayer p) {
		Entity e = p.level().getEntity(coverFoe);
		return e == null || !e.isAlive() || e.isRemoved();
	}

	private static void forgetCover() {
		coverFoe = -1;
		coverSearch = null;
		coverCell = null;
		coverArrived = 0;
		coverFirst = false;
		coverTries = 0;
		coverBad.clear();
	}

	/**
	 * Covers from one foe, counted: a third within five minutes in a base is the base's work taking the buddy back into its sight
	 * again and again (the bed or a chore in view of a pit), which nothing ends while the mob lives. Told by the need while
	 * it goes on (Needs says it once in half an hour).
	 */
	private static void repeatedCover(Entity foe, long now) {
		ArrayDeque<Long> times = coverRepeats.computeIfAbsent(foe.getId(), k -> new ArrayDeque<>());
		times.addLast(now);
		for (ArrayDeque<Long> t : coverRepeats.values()) while (!t.isEmpty() && now - t.peekFirst() > COVER_REPEAT) t.pollFirst();
		coverRepeats.values().removeIf(ArrayDeque::isEmpty);
		if (times.size() < COVER_REPEATS || Base.keeper() == null) return;
		if (times.size() == COVER_REPEATS) Exchange.emit("guard", "action", "shot at again and again", "goal", name(foe), "times", times.size());
		Needs.want("shot_at", "{me} keeps being shot at in the base by a mob out of reach, takes cover each time and keeps the base.");
	}

	/**
	 * No cover to be had (none out of sight, or none of the cells came off): the event says why. The buddy follows the
	 * player again and the need tells the player; in a base the keeping goes on, the need says so, and the base's work goes
	 * on (as before cover was made: the owner's word is that a stop does not end keeping the base), unless the buddy is at
	 * three hearts or less.
	 */
	private static void noCover(LocalPlayer p, Entity shot, String why) {
		Entity foe = shot != null ? shot : p.level().getEntity(coverFoe);
		boolean keep = Base.keeper() != null && p.getHealth() > RETREAT_LOW;
		Exchange.emit("guard", "action", "no cover", "reason", why, "goal", foe == null ? "" : name(foe), "stays", keep);
		forgetCover();
		if (keep) {
			Needs.want("shot_at", "{me} was shot at in the base and found no cover, but keeps the base.");
			return;
		}
		Needs.want("shot_at", "{me} was shot at where told to stay, and follows you again.");
		Follow.unstay("no cover");
	}

	private static String name(Entity e) {
		return e instanceof Player pl ? pl.getGameProfile().name() : BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
	}

	// ---------- after a fall into the pen ----------

	/** The need of the fall goes once the buddy has been out of the pen's columns for five seconds. */
	private static void fellTick(LocalPlayer p, long now) {
		if (fellCols == null) return;
		boolean in = fellCols.contains(PenShape.key(p.getBlockX(), p.getBlockZ()));
		if (in) { fellOutSince = 0; return; }
		if (fellOutSince == 0) fellOutSince = now;
		else if (now - fellOutSince > 5000) { Needs.solved("pen_stuck"); fellCols = null; }
	}

	// ---------- for the walker and the fight ----------

	/** The pen being farmed, or the one of a moment ago while its fence still stands. */
	private static Hok near() {
		if (hok != null) return hok;
		if (tail != null && System.currentTimeMillis() < tailUntil) return tail;
		tail = null;
		return null;
	}

	/** The buddy is within this many blocks of the pen (no digging, pillaring or getting loose there). */
	static boolean near(LocalPlayer p, double r) {
		Hok h = near();
		return h != null && p != null && Work.dimOf(p.level()).equals(h.dim) && h.flat(p.position()) < r && Math.abs(p.getY() - h.floor()) < r + 4;
	}

	/** A point within this many blocks of the pen, flat (thrown loot that came out of the farm). */
	static boolean nearXZ(double x, double z, double r) {
		Hok h = hok;
		return h != null && h.flat(new Vec3(x, 0, z)) <= r;
	}

	/** The cell or a cell beside it is fenced: the walker crouches there. */
	static boolean fenceBeside(BlockPos pos) {
		if (near() == null) return false;
		if (Follow.fenced(pos)) return true;
		for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) if (Follow.fenced(pos.relative(d)) || Follow.fenced(pos.relative(d).below())) return true;
		return false;
	}

	/** A jump from cur to c whose track crosses the pen (a little widened): left out of the plans. */
	static boolean over(BlockPos cur, BlockPos c) {
		Hok h = near();
		if (h == null) return false;
		double x0 = cur.getX() + 0.5, z0 = cur.getZ() + 0.5, x1 = c.getX() + 0.5, z1 = c.getZ() + 0.5;
		AABB box = new AABB(h.flood.minX() - 0.3, -1e9, h.flood.minZ() - 0.3, h.flood.maxX() + 1.3, 1e9, h.flood.maxZ() + 1.3);
		return box.clip(new Vec3(x0, 0, z0), new Vec3(x1, 0, z1)).isPresent() || box.contains(x0, 0, z0) || box.contains(x1, 0, z1);
	}

	/** The cell 0.6 ahead along the look is fenced, or a drop: the fight does not step there. */
	static boolean unsafeAhead(LocalPlayer p) {
		double yaw = Math.toRadians(p.getYRot());
		BlockPos ahead = BlockPos.containing(p.getX() - Math.sin(yaw) * 0.6, p.getY() + 0.01, p.getZ() + Math.cos(yaw) * 0.6);
		return Follow.fenced(ahead) || Grinder.dropAhead(p.level(), ahead);
	}

	/** The closed space a mob stood in a moment ago (null: none), per mob. */
	private record PitSeen(long at, PenShape.Pit pit) {}

	private static final Map<Integer, PitSeen> pitCache = new HashMap<>();

	/** {@link #enclosedWhy} as a yes or no: the fight does not walk to it, nor jump at it. */
	static boolean enclosed(LocalPlayer p, Entity e) { return enclosedWhy(p, e) != null; }

	/**
	 * Why the fight leaves this mob be, or null (PenShape.pitFoe): it stands in a closed space that is a drop below
	 * ("in een put") or a farm ("in een hok"), and the buddy does not stand in it. The shape is kept a second per
	 * mob, as long as the mob is still over its columns (one that climbs out is fought at once); the feet and the
	 * ground under them are measured every time.
	 */
	static String enclosedWhy(LocalPlayer p, Entity e) {
		if (!(e instanceof LivingEntity le) || e instanceof Player) return null;
		long now = System.currentTimeMillis();
		PitSeen c = pitCache.get(e.getId());
		PenShape.Pit pit;
		if (c != null && now - c.at() < 1000 && (c.pit() == null || c.pit().flood().has(Mth.floor(e.getX()), Mth.floor(e.getZ())))) pit = c.pit();
		else {
			if (pitCache.size() > 100) pitCache.clear();
			try { pit = PenShape.pit(new LevelWorld(p.level()), liveMob(le)); } catch (RuntimeException ex) { pit = null; }
			pitCache.put(e.getId(), new PitSeen(now, pit));
		}
		return pit == null ? null : PenShape.pitFoe(pit, p.position(), ground(p));
	}

	/** The height of the ground underfoot: the feet, and during a jump or fall what is below them (within four). */
	private static double ground(LocalPlayer p) {
		if (p.onGround()) return p.getY();
		double g = PenShape.floor(new LevelWorld(p.level()), new PenShape.Mob(2 * PenShape.HALF, PenShape.STAND_H, PenShape.STAND_EYE, p.getBoundingBox(), false));
		return Double.isNaN(g) ? p.getY() : g;
	}

	// ---------- a restart ----------

	/** Once the world is there after a restart: back to the pen that was on, when following its owner and standing at it. */
	private static void tryResume() {
		LocalPlayer p = AfkHelperClient.mc().player;
		if (p == null || state != OFF || Follow.target() == null || Follow.staying()) return;
		JsonObject e = entry(Follow.target());
		if (e == null || !e.get("on").getAsBoolean() || !e.get("dim").getAsString().equals(Work.dimOf(p.level()))) return;
		// AFK mode, the mod off here or another job holds the chain: the pen would be on without its step (watch would end
		// it), so it is written off at once. Grinder's farm too: running now, it was switched on after the connection, the
		// newer instruction; only a pour of the water bucket, which ends by itself, is let be
		String held = held(Jobs.Job.WATER);
		if (held != null) { notBack(held); return; }
		resumeBy = "restart";
		long now = System.currentTimeMillis();
		resumeUntil = now + RESUME_HOLD;
		nextResumeTry = 0;
		setState(RESUMING, now);
		// the remembered pen fenced while it comes back, as its start fences it: getting away from a creeper meanwhile,
		// the one walk of the resume, must not lead into its pit (the third review of 22 Oct 2026)
		Follow.fence(entryFence(e), resumeUntil);
	}

	/** The cells of a remembered pen's columns, from three under its floor to one over a mob of its height in it. */
	private static Set<BlockPos> entryFence(JsonObject e) {
		Set<BlockPos> out = new LinkedHashSet<>();
		try {
			double floor = e.get("floor").getAsDouble(), mh = e.get("h").getAsDouble();
			int lo = Mth.floor(floor) - 3, hi = Mth.ceil(floor + mh) + 1;
			for (JsonElement c : e.getAsJsonArray("cols")) {
				int x = c.getAsJsonArray().get(0).getAsInt(), z = c.getAsJsonArray().get(1).getAsInt();
				for (int y = lo; y <= hi; y++) out.add(new BlockPos(x, y, z));
			}
		} catch (RuntimeException ignored) {
			// an entry of another shape: nothing to fence
		}
		return out;
	}

	private static boolean resumeTick(Minecraft mc, LocalPlayer p, Player owner, long now) {
		Follow.stand();
		if (now > resumeUntil) {
			setState(OFF, now);
			notBack("not back");
			return false;
		}
		if (now < nextResumeTry) return true;
		nextResumeTry = now + 1000;
		JsonObject e = entry(Follow.target());
		if (e == null) { setState(OFF, now); return false; }
		if (flatToEntry(e, p.position()) > RESUME_NEAR) return true;
		Hok h = fromEntry(p, e);
		if (h == null) return true;
		if (h.flat(p.position()) > RESUME_NEAR) return true;
		setState(OFF, now);
		Exchange.emit("pen", "action", "resumed", "by", resumeBy, "x", h.flood.minX(), "y", Mth.floor(h.floor()), "z", h.flood.minZ());
		start(h, resumeBy, now);
		return true;
	}

	/** How far the buddy stands from a remembered pen's columns, flat. */
	private static double flatToEntry(JsonObject e, Vec3 at) {
		double best = Double.MAX_VALUE;
		for (JsonElement c : e.getAsJsonArray("cols")) {
			int x = c.getAsJsonArray().get(0).getAsInt(), z = c.getAsJsonArray().get(1).getAsInt();
			double dx = Math.max(Math.max(x - at.x, 0), at.x - (x + 1)), dz = Math.max(Math.max(z - at.z, 0), at.z - (z + 1));
			best = Math.min(best, Math.hypot(dx, dz));
		}
		return best;
	}

	private static void notBack(String why) {
		String owner = Follow.target();
		if (owner != null) {
			JsonObject all = read();
			if (all.get(key(owner)) instanceof JsonObject o) { o.addProperty("on", false); write(all); }
		}
		Exchange.emit("pen", "action", "no pen here", "reason", why);
	}

	/** The remembered pen as it stands now, when it is the same pen: its columns, its floor within 0.1, still a farm. */
	private static Hok fromEntry(LocalPlayer p, JsonObject e) {
		Level level = p.level();
		LevelWorld w = new LevelWorld(level);
		JsonArray cols = e.getAsJsonArray("cols");
		Set<Long> want = new LinkedHashSet<>();
		for (JsonElement c : cols) want.add(PenShape.key(c.getAsJsonArray().get(0).getAsInt(), c.getAsJsonArray().get(1).getAsInt()));
		if (want.isEmpty()) return null;
		for (long c : want) if (!w.loaded(PenShape.kx(c), PenShape.kz(c))) return null;
		double floor = e.get("floor").getAsDouble(), mw = e.get("w").getAsDouble(), mh = e.get("h").getAsDouble(), eye = e.get("eye").getAsDouble();
		long first = want.iterator().next();
		PenShape.Mob m = PenShape.Mob.at(mw, mh, eye, PenShape.kx(first), PenShape.kz(first), floor);
		PenShape.Flood f = PenShape.flood(w, m, floor);
		if (!f.closed() || !f.cols().equals(want)) return null;
		int[] crowd = { 0 };
		boolean weak = weakenedInPen(level, f, m, crowd);
		PenShape.Evidence ev = PenShape.evidence(w, f, m, weak, crowd[0], cart(level, f));
		// a farm by its blocks: a collector or a shaft (no weakened mob to see after a restart)
		if (!ev.enough() && !(ev.collector() || ev.shaft())) return null;
		PenShape.Search s = new PenShape.Search(w, f, m, ev);
		while (!s.step(SEARCH_BUDGET * 4)) { }
		PenShape.Result r = s.result();
		if (r.spots().isEmpty()) return null;
		return new Hok(Follow.target(), Work.dimOf(level), e.has("mob") ? e.get("mob").getAsString() : "zombie", m, f, ev, r);
	}

	// ---------- the file ----------

	private static Path file() { return Paths.get(AfkConfig.get().exchangeDir).resolve("pens.json"); }

	private static String key(String owner) { return Profiles.key() + "|" + owner.toLowerCase(Locale.ROOT); }

	private static JsonObject read() {
		try {
			Path f = file();
			if (Files.exists(f)) return JsonParser.parseString(Files.readString(f)).getAsJsonObject();
		} catch (Exception ex) {
			Exchange.emit("error", "msg", "mob farms not read: " + ex.getMessage());
		}
		return new JsonObject();
	}

	private static JsonObject entry(String owner) {
		return read().get(key(owner)) instanceof JsonObject o ? o : null;
	}

	private static void write(JsonObject all) {
		try {
			Path f = file(), tmp = f.resolveSibling("pens.json.tmp");
			Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(all));
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception ex) {
			Exchange.emit("error", "msg", "mob farms not saved: " + ex.getMessage());
		}
	}

	private static void persist(Hok h, boolean on) {
		JsonObject all = read();
		JsonObject o = new JsonObject();
		o.addProperty("dim", h.dim);
		JsonArray cols = new JsonArray();
		for (long c : h.cols) { JsonArray a = new JsonArray(); a.add(PenShape.kx(c)); a.add(PenShape.kz(c)); cols.add(a); }
		o.add("cols", cols);
		o.addProperty("floor", h.floor());
		o.addProperty("h", h.h);
		o.addProperty("w", h.w);
		o.addProperty("eye", h.eye);
		o.addProperty("mob", h.mobId);
		o.addProperty("on", on);
		o.addProperty("since", h.since);
		all.add(key(h.owner), o);
		write(all);
	}

	// ---------- the command ----------

	/**
	 * {@code {"cmd":"pen"}} answers how it stands. {@code "on": false} stops it, steps back and waits (as the sign does);
	 * {@code "on": true} takes up the remembered pen when standing at it. {@code "probe": true} with x, y, z (and
	 * {@code "mob"}, zombie by default) only recognises, as if such a mob stood in that block, and writes pen.txt; the
	 * same without "probe" starts there for the followed player, as a punch would (a test). {@code "hit"},
	 * {@code "trace"} and {@code "safe"} are test switches: no blows, a trace, the sweep rule off.
	 */
	static void command(JsonObject o) {
		LocalPlayer p = AfkHelperClient.mc().player;
		if (p == null) { Exchange.emit("error", "msg", "pen: not in a world"); return; }
		boolean any = false;
		if (o.has("hit")) { dry = !o.get("hit").getAsBoolean(); any = true; Exchange.emit("pen", "action", "set", "hit", !dry); }
		if (o.has("trace")) { trace = o.get("trace").getAsBoolean(); any = true; Exchange.emit("pen", "action", "set", "trace", trace); }
		if (o.has("safe")) { unsafe = !o.get("safe").getAsBoolean(); any = true; Exchange.emit("pen", "action", "set", "safe", !unsafe); }
		if (o.has("on")) {
			any = true;
			if (!o.get("on").getAsBoolean()) {
				if (on()) { stop("panel", true); Follow.stayHere("panel"); }
				else Exchange.emit("pen", "action", "off", "reason", "was off already");
			} else if (!busy()) {
				if (Follow.target() == null) { Exchange.emit("error", "msg", Needs.named("pen: {me} follows nobody")); return; }
				// as the gesture: with the chain held by AFK mode, the mod off or another job the pen would be on without its
				// step; Grinder's farm the start ends, a pour of the bucket ends by itself
				String held = held(Jobs.Job.MOB_FARM, Jobs.Job.WATER);
				if (held != null) { Exchange.emit("pen", "action", "no pen here", "reason", held); return; }
				JsonObject e = entry(Follow.target());
				if (e == null || !e.get("dim").getAsString().equals(Work.dimOf(p.level()))) { Exchange.emit("pen", "action", "no pen here", "reason", "no pen remembered"); return; }
				Hok h = fromEntry(p, e);
				if (h == null || h.flat(p.position()) > RESUME_NEAR) { Exchange.emit("pen", "action", "no pen here", "reason", h == null ? "not back" : "too far"); return; }
				Exchange.emit("pen", "action", "resumed", "by", "panel", "x", h.flood.minX(), "y", Mth.floor(h.floor()), "z", h.flood.minZ());
				start(h, "panel", System.currentTimeMillis());
			} else status(p);   // on already: how it stands
		}
		if (o.has("x") && o.has("y") && o.has("z")) {
			any = true;
			boolean probe = o.has("probe") && o.get("probe").getAsBoolean();
			if (!probe && Follow.target() == null) { Exchange.emit("error", "msg", Needs.named("pen: {me} follows nobody")); return; }
			String mob = o.has("mob") ? o.get("mob").getAsString() : "zombie";
			EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.tryParse(mob.contains(":") ? mob : "minecraft:" + mob)).orElse(null);
			if (type == null) { Exchange.emit("error", "msg", "pen: unknown mob " + mob); return; }
			var d = type.getDimensions();
			int x = o.get("x").getAsInt(), y = o.get("y").getAsInt(), z = o.get("z").getAsInt();
			double w = d.width(), h = d.height();
			// as if the mob stood in that block: its floor is what it finds there or below
			PenShape.Mob m = new PenShape.Mob(w, h, d.eyeHeight(), new AABB(x + 0.5 - w / 2, y + 0.999, z + 0.5 - w / 2, x + 0.5 + w / 2, y + 0.999 + h, z + 0.5 + w / 2), false);
			LevelWorld world = new LevelWorld(p.level());
			double F = PenShape.floor(world, m);
			Pending pd = new Pending();
			pd.probe = probe; pd.test = !probe; pd.door = "test"; pd.mobId = BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
			pd.owner = Follow.target(); pd.world = world; pd.eventTick = Follow.ticks(); pd.handChecked = true;
			if (Double.isNaN(F)) { refuseProbe(p, pd, "no floor under the mob"); return; }
			m = new PenShape.Mob(w, h, d.eyeHeight(), new AABB(m.box().minX, F, m.box().minZ, m.box().maxX, F + h, m.box().maxZ), true);
			pd.mob = m;
			PenShape.Flood f = PenShape.flood(world, m, F);
			pd.flood = f;
			if (!f.closed()) { refuseProbe(p, pd, f.refusal()); return; }
			pending = pd;
			// a command waits for its answer: the checks run to their end at once, where a punch's take a few ms a tick
			for (int i = 0; i < 400 && pending == pd; i++) pendingTick(AfkHelperClient.mc(), p, findOwner(p), System.currentTimeMillis(), Follow.ticks());
		}
		if (!any) status(p);
	}

	private static Player findOwner(LocalPlayer p) {
		String t = Follow.target();
		if (t == null) return null;
		for (Player o : p.level().players()) if (o != p && o.getGameProfile().name().equalsIgnoreCase(t)) return o;
		return null;
	}

	/** A probe or test on open ground: said, and in pen.txt (a gesture there would get no answer at all). */
	private static void refuseProbe(LocalPlayer p, Pending pd, String why) {
		PenShape.Flood f = pd.flood;
		Exchange.emit("pen", "action", "no pen here", "reason", why, "open", true, "size", f == null ? "" : f.size(), "mob", pd.mobId);
		writeTxt(p, pd, null, why);
	}

	private static void status(LocalPlayer p) {
		Hok h = hok;
		Exchange.emit("pen", "action", "status", "on", on(), "phase", PHASE[state],
			"x", h == null ? 0 : h.flood.minX(), "y", h == null ? 0 : Mth.floor(h.floor()), "z", h == null ? 0 : h.flood.minZ(),
			"size", h == null ? "" : h.flood.size(), "killed", killed, "hits", hits, "minutes", h == null ? 0 : (System.currentTimeMillis() - started) / 60_000,
			"spots", h == null ? 0 : h.result.spots().size(), "hit", !dry, "trace", trace, "safe", !unsafe);
	}

	private static void trace(LocalPlayer p, long now) {
		Exchange.emit("pen", "action", "trace", "x", round3(p.getX()), "y", round3(p.getY()), "z", round3(p.getZ()),
			"yaw", Math.round(p.getYRot() * 10) / 10.0, "pitch", Math.round(p.getXRot() * 10) / 10.0, "crouched", p.isShiftKeyDown(),
			"phase", PHASE[state], "goal", spot == null ? "" : spot.cell().toShortString(), "strength", Math.round(p.getAttackStrengthScale(0.5f) * 100) / 100.0);
	}

	// ---------- pen.txt ----------

	/** What the buddy made of a pen, for a person to read: the evidence, the sides, and its layers from above. */
	private static void writeTxt(LocalPlayer p, Pending pd, PenShape.Result r, String refusal) {
		try {
			PenShape.Flood f = pd.flood;
			StringBuilder out = new StringBuilder();
			out.append("pen for ").append(pd.mobId).append(", F ").append(f == null ? "?" : round3(f.floor())).append(", size ")
				.append(f == null ? "" : f.size()).append(refusal != null ? ", refused: " + refusal : "").append('\n');
			if (pd.ev != null) out.append("evidence ").append(pd.ev.points()).append(" (").append(String.join(",", pd.ev.why())).append("), shaft ")
				.append(pd.ev.shaft()).append(", top ").append(round3(pd.ev.top())).append('\n');
			if (r != null) {
				out.append("kind ").append(r.kind()).append(", spots ").append(r.spots().size()).append('\n');
				String[] names = { "N", "E", "S", "W" };
				for (int i = 0; i < 4; i++) {
					PenShape.Spot s = r.ring()[i];
					if (s == null) { out.append(names[i]).append(": none\n"); continue; }
					out.append(names[i]).append(": ").append(s.cell().toShortString()).append(", crouched ").append(s.crouch()).append(", columns ")
						.append(s.columns()).append(", seen ").append(s.seen()).append(", creeper ").append(s.creeper()).append('\n');
				}
			}
			if (f != null && !f.cols().isEmpty()) {
				Map<BlockPos, Character> marks = new HashMap<>();
				if (r != null) {
					for (PenShape.Spot s : r.spots()) marks.put(s.cell(), "nesw".charAt(s.side()));
					for (PenShape.Spot s : r.ring()) if (s != null) marks.put(s.cell(), "NESW".charAt(s.side()));
				}
				Player owner = findOwner(p);
				int fy = Mth.floor(f.floor());
				LevelWorld w = pd.world;
				for (int y = fy + 3; y >= fy - 1; y--) {
					out.append("\ny ").append(y).append(", x from ").append(f.minX() - 4).append('\n');
					for (int z = f.minZ() - 4; z <= f.maxZ() + 4; z++) {
						StringBuilder row = new StringBuilder();
						for (int x = f.minX() - 4; x <= f.maxX() + 4; x++) {
							BlockPos q = new BlockPos(x, y, z);
							char c = marks.containsKey(q) ? marks.get(q)
								: p.blockPosition().equals(q) ? '@'
								: owner != null && owner.blockPosition().equals(q) ? 'O'
								: f.has(x, z) ? 'P'
								: !w.collision(x, y, z).isEmpty() ? '#' : '.';
							row.append(c);
						}
						out.append(String.format("%6d ", z)).append(row).append('\n');
					}
				}
				out.append("\nP pen column, # blocked, . open, N E S W the best spot per side, n e s w other spots, @ the buddy, O the player\n");
			}
			Files.writeString(Paths.get(AfkConfig.get().exchangeDir, "pen.txt"), out.toString());
		} catch (Exception ex) {
			Exchange.emit("error", "msg", "mob farm view not saved: " + ex.getMessage());
		}
	}

	// ---------- the panel ----------

	/** The pen for state.json, while it is on or keeps the hands busy. */
	static Map<String, Object> details(LocalPlayer p) {
		Hok h = hok != null ? hok : state == BACKOFF_STATE ? tail : null;
		if (h == null || state == OFF) return null;
		Map<String, Object> m = new LinkedHashMap<>();
		long now = System.currentTimeMillis();
		m.put("on", on());
		m.put("phase", PHASE[state]);
		m.put("dim", h.dim);
		m.put("x1", h.flood.minX()); m.put("z1", h.flood.minZ()); m.put("x2", h.flood.maxX()); m.put("z2", h.flood.maxZ());
		m.put("y", Mth.floor(h.floor()));
		m.put("size", h.flood.size());
		// state.json does not go through the protocol table, so the two words are put into English here
		m.put("kind", h.result == null ? "" : h.result.kind());
		if (spot != null) {
			Map<String, Object> s = new LinkedHashMap<>();
			s.put("x", spot.cell().getX()); s.put("y", spot.cell().getY()); s.put("z", spot.cell().getZ());
			s.put("side", side(spot.side()));
			s.put("crouched", spot.crouch() || spot.edge());
			m.put("spot", s);
		}
		m.put("spots", h.result == null ? 0 : h.result.spots().size());
		m.put("hits", hits);
		m.put("killed", killed);
		m.put("since", h.since);
		m.put("waiting_s", emptySince == 0 ? 0 : (now - emptySince) / 1000);
		m.put("mobs", hok == null ? 0 : mobs(p).size());
		return m;
	}

	// ---------- small things ----------

	private static void setState(int s, long now) {
		// the keys of the lean are its own: whatever comes after it (the farm, a retreat, the night, another side)
		// starts without them
		if (state == LEAN_IN && s != LEAN_IN) {
			var o = AfkHelperClient.mc().options;
			HeldKeys.set(o.keyUp, false); HeldKeys.set(o.keyDown, false); HeldKeys.set(o.keyLeft, false); HeldKeys.set(o.keyRight, false);
		}
		if (state == OFF && s != OFF) heldSince = 0;   // a pen that begins has not been held yet
		state = s;
		stateSince = now;
	}

	private static String side(int s) { return s < 0 || s > 3 ? "" : new String[] { "north", "east", "south", "west" }[s]; }

	private static double round3(double v) { return Math.round(v * 1000) / 1000.0; }
}
