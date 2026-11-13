package net.milenco.buddy.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.milenco.buddy.version.Game;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Work alongside the player the alt follows: when that player digs a tunnel (2x2, 2x3, 3x3 and so on) the buddy helps
 * for as long as the player keeps breaking, once sure it is a tunnel, and stops in time; when the player chops
 * trees nearby, the buddy may do so too.
 * <p>
 * What the player breaks the client sees by itself: every tick it notes the block the player looks at (that look is
 * synced) and the blocks around it, and one of those that is gone half a second later is the player's. A tunnel is a
 * straight run of at least three open slices of one cross-section, two to five wide and high, with a floor under it,
 * of which the player broke at least half in two of them, seen the same twice in a row. Then the alt breaks along in
 * the slice being broken and behind it, never in front of it, so the tunnel never gets longer than where the player
 * put the pickaxe; three seconds without a block of the player's and the alt stops, half a minute and the tunnel is
 * forgotten. Trees: once the player chopped two logs of a tree that grew (leaves that are not placed) within a minute,
 * the alt chops the other trees within fourteen blocks of the player that can be reached from the ground, one trunk,
 * at most six high, picks up what fell and plants a sapling of the kind back. Breaking is the real crosshair on the
 * block, never through another block or out of reach, with the fastest tool that gives the drop; a block next to water
 * or lava, one with something inside (a chest, a spawner) and one only a missing tool gives are left, and the last is
 * told through {@link Needs}.
 */
final class Work {
	private Work() {}

	/** All of it, tunnels, trees, crops: on by default, also after a restart. The chests are {@link Stash}'s. */
	// Digging, chopping and the fields only after a tap of the player's (Tap): a few things such as the corpse guard and the tap
	// itself run by themselves, the rest only on the player's command. Kept
	// over a restart in the config. The chests and giving stay on.
	static boolean on = true;
	static boolean dig = AfkConfig.get().assistDig, chop = AfkConfig.get().assistChop, farm = AfkConfig.get().assistFarm;

	/** A tap: this job on when it was off, off when it was on; a crop also learns its field. The state after it. */
	static boolean tapToggle(String job, Level level, BlockPos pos) {
		AfkConfig c = AfkConfig.get();
		boolean now;
		switch (job) {
			case "dig" -> { now = dig = !dig; c.assistDig = now; if (!now) { endTunnel("tapped off"); endPit("tapped off"); } }
			case "chop" -> { now = chop = !chop; c.assistChop = now; if (!now) dropTree("tapped off"); }
			default -> { now = farm = !farm; c.assistFarm = now; if (now) learnField(level, pos); else endRound("tapped off"); }
		}
		AfkConfig.save();
		return now;
	}

	private record Seen(int tick, BlockState state) {}
	private record Brk(BlockPos pos, BlockState state, long at, String dim, boolean naturalLog) {}

	// what the player breaks
	private static final Map<BlockPos, Seen> seen = new HashMap<>();
	private static final ArrayDeque<Brk> breaks = new ArrayDeque<>();
	private static BlockPos hisLook;
	private static long lastHisLog, chopTold;
	/** How long after the player's last log the chopping still counts as going on. */
	static final long CHOPPING = 15_000;
	/** Blocks the alt broke, with the moment: never taken for the player's, and where the alt's own drops lie. */
	private static final Map<BlockPos, Long> mine = new HashMap<>();
	/** The alt's own breaks of the last five minutes, to see the player put one back: a correction (0.4.44). */
	private static final Map<BlockPos, Long> mineLong = new HashMap<>();
	private record Fix(String dim, BlockPos pos, long at) {}
	/** A block of {@link #mineLong} that is solid again, with the moment it was first seen so. */
	private static final Map<BlockPos, Long> fixSeen = new HashMap<>();
	/** Blocks the player put back where the alt broke, for ten minutes: never broken again, and a wall for the pit's look. */
	private static final List<Fix> fixes = new ArrayList<>();
	static final long FIX_KEEP = 600_000;
	private static int workedAt = -10, lastNeeds;

	// the tunnel
	private static final class Tunnel {
		Direction.Axis axis;
		int dir, pMin, pMax, yMin, yMax, face, testEnd;
		String dim;
		long lastHis, oddAt;
		int odd;
		boolean test, paused;

		boolean same(Tunnel o) {
			return o != null && axis == o.axis && dir == o.dir && pMin == o.pMin && pMax == o.pMax && yMin == o.yMin
				&& yMax == o.yMax && dim.equals(o.dim);
		}
	}
	private static Tunnel tunnel, candidate;

	// an open stretch the player makes larger by breaking its edges away (0.4.42)
	private static final class Pit {
		String dim;
		int xMin, xMax, zMin, zMax, yMin, yMax;
		final Set<Block> kinds = new HashSet<>();
		long firstHis = Long.MAX_VALUE, lastHis, testUntil;
		boolean test, paused;
	}
	private static Pit pit;
	static final int PIT_START = 20;
	private static boolean pitSeen;

	// a tree of the kind the player chops
	private static final class Tree {
		BlockPos base;
		String dim, sapling;
		int phase;          // 0 chop, 1 pick up, 2 plant
		long phaseAt;
		boolean own;        // the buddy's own: wood for sticks, in the base, without anyone chopping
	}
	private static Tree tree;
	/** Whether the last look for a tree for the buddy's own wood found one: when not, the panel says wood is lacking. */
	static boolean woodTree = true;
	/** Trees done, or not for the buddy (too tall, two trunks, placed): left alone until the moment given. */
	private static final Map<BlockPos, Long> treeDone = new HashMap<>();

	// breaking: the block to hit at the start of the next tick, and the one under way
	private static BlockPos digAt, digging, stand, standFor;
	private static long digSince, digLimit;
	private static boolean hitting;
	private static final Map<BlockPos, Long> skipped = new HashMap<>();
	// planting: the block to click the sapling on at the start of the next tick
	private static BlockPos plantAt;
	private static int plantedAt = -1;
	// picking up the buddy's own drops
	private static ItemEntity fetching;
	private static long fetchSince;
	private static final Set<Integer> gaveUp = new HashSet<>();

	/** What a hit may be from the eye: a little under the game's 4.5, so the crosshair is always on it. */
	static final double REACH = 4.4;

	static boolean busy() { return Follow.ticks() - workedAt <= 1; }

	static boolean testing() { return (tunnel != null && tunnel.test) || (pit != null && pit.test) || testTree || testFarm; }

	/** A harvest without the player, to try the farming: the nearest crops count as a field, for three minutes. */
	private static boolean testFarm;
	private static long testFarmUntil;

	/** Trees to chop without the player, to try the chopping: the alt stands in for the player, for two minutes. */
	private static boolean testTree;
	private static long testTreeUntil;

	private static boolean worked() { workedAt = Follow.ticks(); return true; }

	static String dimOf(Level level) { return level.dimension().identifier().toString(); }

	// ---------- switching ----------

	/** {@code {"cmd":"help"}}: "on", "dig", "chop", "harvest", "chests", "give" switch; "test" for a tunnel without the player. */
	static void command(JsonObject o) {
		boolean changed = false;
		if (o.has("on")) { on = o.get("on").getAsBoolean(); changed = true; }
		if (o.has("dig")) { dig = AfkConfig.get().assistDig = o.get("dig").getAsBoolean(); changed = true; }
		if (o.has("chop")) { chop = AfkConfig.get().assistChop = o.get("chop").getAsBoolean(); changed = true; }
		if (o.has("harvest")) { farm = AfkConfig.get().assistFarm = o.get("harvest").getAsBoolean(); changed = true; }
		if (changed) AfkConfig.save();
		if (o.has("chests")) { Stash.on = o.get("chests").getAsBoolean(); changed = true; }
		if (o.has("give")) { Dump.on = o.get("give").getAsBoolean(); changed = true; }
		if (!on || !dig) { endTunnel("switched off"); endPit("switched off"); }
		if (!on || !chop) dropTree("switched off");
		if (!on || !farm) endRound("switched off");
		if (!on || !Stash.on) Stash.stop("switched off");
		if (!on || !Dump.on) Dump.stop("switched off");
		if (o.has("test")) { test(o); return; }
		status(changed ? "set" : "status");
	}

	private static void status(String action) {
		Exchange.emit("helper", "action", action, "on", on, "dig", dig, "chop", chop, "harvest", farm, "chests", Stash.on, "give", Dump.on, "gives", Dump.state(),
			"tunnel", tunnel == null ? "" : describe(tunnel), "pit", pit == null ? "" : describe(pit), "tree", tree == null ? "" : tree.base.toShortString(),
			"harvesting", round == null ? "" : round.center.toShortString(), "for", Follow.target() == null ? "" : Follow.target(),
			"chests_known", Stash.count(), "fields_known", fieldCount(), "lacks", Needs.summary());
	}

	private static String describe(Pit pt) {
		return (pt.xMax - pt.xMin + 1) + "x" + (pt.zMax - pt.zMin + 1) + " y" + pt.yMin + "-" + pt.yMax + (pt.paused ? " (paused)" : "");
	}

	private static String describe(Tunnel t) {
		return (t.pMax - t.pMin + 1) + "x" + (t.yMax - t.yMin + 1) + " " + (t.dir > 0 ? "+" : "-") + t.axis.getName()
			+ (t.paused ? " (paused)" : "");
	}

	/**
	 * A tunnel to dig without the player, to try the digging: {@code "test":"tunnel"} with the first slice's lower corner
	 * x, y, z, the axis "x" or "z", "direction" 1 or -1, "wide", "high" and "length". It stands in for the player's
	 * breaking: a slice done, the next one counts as the player's. {@code "test":"stop"} ends it.
	 */
	private static void test(JsonObject o) {
		String what = o.get("test").getAsString();
		if (what.equals("stop")) { endPit("test stopped"); endTunnel("test stopped"); dropTree("test stopped"); endRound("test stopped"); testTree = false; testFarm = false; fields.removeIf(f -> f.owner.equals("test")); return; }
		LocalPlayer p = AfkHelperClient.mc().player;
		if (p != null && what.equals("harvest")) {
			BlockPos near = null;
			for (BlockPos q : BlockPos.betweenClosed(p.blockPosition().offset(-16, -4, -16), p.blockPosition().offset(16, 4, 16)))
				if (seedOf(p.level().getBlockState(q)) != null && (near == null || q.distSqr(p.blockPosition()) < near.distSqr(p.blockPosition()))) near = q.immutable();
			if (near == null) { Exchange.emit("error", "msg", "test harvest: no crops within 16 blocks"); return; }
			loadFields();
			Field f = new Field();
			f.server = server(); f.dim = dimOf(p.level()); f.owner = "test"; f.pos = near;
			fields.add(f);
			testFarm = true;
			testFarmUntil = System.currentTimeMillis() + 180_000;
			Exchange.emit("helper", "action", "harvest test", "x", near.getX(), "y", near.getY(), "z", near.getZ());
			return;
		}
		if (p != null && what.equals("tree")) {
			testTree = true;
			testTreeUntil = System.currentTimeMillis() + 120_000;
			Exchange.emit("helper", "action", "tree test", "x", p.getBlockX(), "y", p.getBlockY(), "z", p.getBlockZ());
			return;
		}
		if (p != null && what.equals("pit")) {
			// a pit of this box without the player: every ground block of it (no ring since 0.4.44), for "duration" seconds
			Pit pt = new Pit();
			pt.xMin = Math.min(o.get("x1").getAsInt(), o.get("x2").getAsInt()); pt.xMax = Math.max(o.get("x1").getAsInt(), o.get("x2").getAsInt());
			pt.zMin = Math.min(o.get("z1").getAsInt(), o.get("z2").getAsInt()); pt.zMax = Math.max(o.get("z1").getAsInt(), o.get("z2").getAsInt());
			pt.yMin = Math.min(o.get("y1").getAsInt(), o.get("y2").getAsInt()); pt.yMax = Math.max(o.get("y1").getAsInt(), o.get("y2").getAsInt());
			pt.dim = dimOf(p.level());
			pt.test = true;
			pt.lastHis = System.currentTimeMillis();
			pt.testUntil = pt.lastHis + 1000L * (o.has("duration") ? o.get("duration").getAsInt() : 60);
			endTunnel("test put");
			pit = pt;
			Exchange.emit("helper", "action", "dig out", "test", true, "shape", describe(pt), "x", pt.xMin, "y", pt.yMin, "z", pt.zMin);
			return;
		}
		if (p == null || !what.equals("tunnel")) { Exchange.emit("error", "msg", "test: tunnel, pit, tree, harvest or stop"); return; }
		Tunnel t = new Tunnel();
		t.axis = o.get("axis").getAsString().equals("z") ? Direction.Axis.Z : Direction.Axis.X;
		t.dir = o.has("direction") ? o.get("direction").getAsInt() : 1;
		BlockPos c = new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
		int w = o.has("wide") ? o.get("wide").getAsInt() : 2, h = o.has("high") ? o.get("high").getAsInt() : 2;
		t.pMin = perp(c, t.axis); t.pMax = t.pMin + w - 1;
		t.yMin = c.getY(); t.yMax = t.yMin + h - 1;
		t.face = along(c, t.axis);
		t.testEnd = t.face + t.dir * ((o.has("length") ? o.get("length").getAsInt() : 4) - 1);
		t.dim = dimOf(p.level());
		t.test = true;
		t.lastHis = System.currentTimeMillis();
		tunnel = t;
		Exchange.emit("helper", "action", "tunnel", "test", true, "shape", describe(t), "x", c.getX(), "y", c.getY(), "z", c.getZ());
	}

	/** Stop everything (following stopped, a new connection). */
	static void reset(String why) {
		endTunnel(why);
		endPit(why);
		dropTree(why);
		endRound(why);
		Stash.stop(why);
		Dump.reset();
		candidate = null;
		seen.clear();
		plantWatch.clear();
		digAt = null; digging = null; stand = null; standFor = null; plantAt = null; fetching = null;
	}

	// ---------- what the player breaks ----------

	/** Every tick the player is in sight: what the player looks at, and what was looked at a moment ago that is gone. */
	static void watch(LocalPlayer p, Player t) {
		Level level = p.level();
		long now = System.currentTimeMillis();
		int tick = Follow.ticks();
		String dim = dimOf(level);
		for (var it = seen.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			BlockPos pos = e.getKey();
			Seen s = e.getValue();
			BlockState st = level.getBlockState(pos);
			if (st.getBlock() != s.state().getBlock()) {
				it.remove();
				Long ours = mine.get(pos);
				if (st.getCollisionShape(level, pos).isEmpty() && !pos.equals(digging) && (ours == null || now - ours > 3000))
					heBroke(level, pos, s.state(), now, dim);
				continue;
			}
			if (tick - s.tick() > 10) it.remove();
		}
		mine.values().removeIf(at -> now - at > 60_000);
		ourPlants.values().removeIf(at -> now - at > 10_000);
		// something planted where the player looked a moment ago: the player sows or renews a field there
		for (var it = plantWatch.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			if (tick - e.getValue() > 20) { it.remove(); continue; }
			BlockState st = level.getBlockState(e.getKey());
			if (st.isAir()) continue;
			it.remove();
			if (planted(st) && !ourPlants.containsKey(e.getKey())) learnField(level, e.getKey());
		}
		hisLook = null;
		if (t == null || t.distanceTo(p) > 40) return;
		if (t.pick(5.0, 1f, false) instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK) {
			hisLook = bh.getBlockPos().immutable();
			// the block the player looks at, and those around it: that look reaches us a little rounded, a block off at the edge
			note(level, hisLook, tick);
			for (Direction d : Direction.values()) note(level, hisLook.relative(d), tick);
			if (level.getBlockState(hisLook.above()).isAir()) plantWatch.put(hisLook.above().immutable(), tick);
		}
		Stash.watch(p, t, hisLook);
	}

	private static void note(Level level, BlockPos pos, int tick) {
		BlockState st = level.getBlockState(pos);
		if (st.getCollisionShape(level, pos).isEmpty() && !planted(st)) return;   // a crop has no collision, and is the player's harvest
		seen.put(pos.immutable(), new Seen(tick, st));
	}


	private static void heBroke(Level level, BlockPos pos, BlockState was, long now, String dim) {
		boolean log = was.is(BlockTags.LOGS) && natural(level, pos);
		breaks.addLast(new Brk(pos.immutable(), was, now, dim, log));
		while (!breaks.isEmpty() && (breaks.size() > 80 || now - breaks.peekFirst().at() > 120_000)) breaks.removeFirst();
		if (log) lastHisLog = now;
		if (planted(was) || was.is(Blocks.MELON) || was.is(Blocks.PUMPKIN)) {
			learnField(level, pos);
			lastHisHarvest = now;
			hisHarvestAt = pos.immutable();
			return;
		}
		if (tunnel != null && !tunnel.test) hisInTunnel(pos, now);
	}

	/**
	 * A block the alt broke that is solid again, and did not fall there: the player put it back, so the alt was wrong there
	 * (0.4.44; on 19 Oct 2026 the player set cobblestone back in 44 of the 253 blocks the alt broke in the player's room).
	 */
	private static void corrections(Level level, LocalPlayer p, long now) {
		String dim = dimOf(level);
		fixes.removeIf(f -> now - f.at() > FIX_KEEP);
		fixSeen.keySet().retainAll(mineLong.keySet());
		for (var it = mineLong.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			if (now - e.getValue() > 300_000) { it.remove(); continue; }
			BlockPos pos = e.getKey();
			if (pos.distSqr(p.blockPosition()) > 48 * 48) continue;
			BlockState st = level.getBlockState(pos);
			if (st.getCollisionShape(level, pos).isEmpty() || st.getBlock() instanceof FallingBlock) { fixSeen.remove(pos); continue; }
			// a block the server sends back for a moment after a break (a ghost) is no correction: it has to stay
			Long since = fixSeen.putIfAbsent(pos, now);
			if (since == null || now - since < 1500) continue;
			fixSeen.remove(pos);
			it.remove();
			fixes.add(new Fix(dim, pos, now));
			Exchange.emit("helper", "action", "put back", "x", pos.getX(), "y", pos.getY(), "z", pos.getZ(), "block",
				BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath());
		}
	}

	/** The player put a block back in this column at these heights. */
	private static boolean fixedColumn(String dim, int x, int z, int yMin, int yMax) {
		for (Fix f : fixes)
			if (f.dim().equals(dim) && f.pos().getX() == x && f.pos().getZ() == z && f.pos().getY() >= yMin - 1 && f.pos().getY() <= yMax + 1) return true;
		return false;
	}

	private static boolean fixedAt(String dim, int x, int y, int z) {
		for (Fix f : fixes) if (f.dim().equals(dim) && f.pos().getX() == x && f.pos().getY() == y && f.pos().getZ() == z) return true;
		return false;
	}

	/** Leaves that grew (not placed) above and around: a tree, not a house of logs. */
	private static boolean natural(Level level, BlockPos pos) {
		int n = 0;
		for (BlockPos q : BlockPos.betweenClosed(pos.offset(-3, -1, -3), pos.offset(3, 7, 3))) {
			BlockState st = level.getBlockState(q);
			if (st.getBlock() instanceof LeavesBlock && st.hasProperty(LeavesBlock.PERSISTENT) && !st.getValue(LeavesBlock.PERSISTENT) && ++n >= 3) return true;
		}
		return false;
	}

	private static int hisLogs(long now) {
		int n = 0;
		for (Brk b : breaks) if (b.naturalLog() && now - b.at() < 60_000) n++;
		return n;
	}

	// ---------- every tick ----------

	/** While following and the player is in sight: work along. True while that works. */
	/** Set by {@link Base} while it runs the base's work: then the base's own switches decide (Modes). */
	static boolean baseRun;

	static void stopDigging(String why) { endTunnel(why); endPit(why); }

	static boolean tick(Minecraft mc, LocalPlayer p, Player t) {
		long now = System.currentTimeMillis();
		Level level = p.level();
		if (digging != null && gone(level, digging)) {
			mine.put(digging, now);
			mineLong.put(digging, now);
			if (round != null && round.replant.containsKey(digging)) round.plantNext = digging;
			digging = null;
		}
		skipped.values().removeIf(until -> until < now);
		treeDone.values().removeIf(until -> until < now);
		badStand.values().removeIf(until -> until < now);
		needsTick(p);
		if (Follow.ticks() % 5 == 0) corrections(level, p, now);
		if (!on) return false;
		// a torch first where it is dark, between two blocks of the dig-along (Torches)
		if (torch(mc, p)) return worked();
		if (Follow.ticks() % 10 == 0 && dig && tunnel == null) detectTunnel(level, now);
		if (tunnel != null && tunnelTick(mc, p, t, now)) return worked();
		if (Follow.ticks() % 10 == 0 && dig && tunnel == null) detectPit(level, now);
		if (pit != null && pitTick(mc, p, t, now)) return worked();
		if ((tunnel == null || tunnel.paused) && (pit == null || pit.paused)) {
			// a visit to a chest or a chore under way is finished first: a harvest round or a tree that starts in the
			// middle of it left it lying, and its own time limit then called the chest "unreachable" or the chore too
			// long, five minutes skipped (21 Oct 2026, the cellar chests and the shearing)
			if (Stash.busy() && tree == null && round == null && Stash.tick(mc, p, t)) return worked();
			if (Chores.busy() && tree == null && round == null && Chores.tick(mc, p, t)) return worked();
			if (treeTick(mc, p, t, now)) return worked();
			if (farmTick(mc, p, t, now)) return worked();
			if (collect(mc, p, now)) return worked();
			if ((!baseRun || AfkConfig.get().baseStore) && Stash.tick(mc, p, t)) return worked();
			if ((!baseRun || AfkConfig.get().baseFurnaces) && Ovens.tick(mc, p, t)) return worked();
			if (Chores.tick(mc, p, t)) return worked();
			if (tree == null && round == null && tunnel == null && pit == null && Dump.tick(mc, p, t)) return worked();
		}
		return false;
	}

	private static int lastNeedsTick = -1;

	/**
	 * What the buddy lacks every five seconds, and the need told when it is time: from Work.tick and from the pen (Pen),
	 * which holds the tick while farming. Once a tick at most, whoever calls it.
	 */
	static void needsTick(LocalPlayer p) {
		int t = Follow.ticks();
		if (t - lastNeeds >= 100) { lastNeeds = t; needs(p); }
		if (t % 20 == 0 && t != lastNeedsTick) { lastNeedsTick = t; Needs.tick(); }
	}

	/** A tunnel to dig without the player: the same, with the buddy as the one who stands there. */
	static boolean testTick(Minecraft mc, LocalPlayer p) {
		Level level = p.level();
		long now = System.currentTimeMillis();
		if (digging != null && gone(level, digging)) {
			mine.put(digging, now);
			mineLong.put(digging, now);
			if (round != null && round.replant.containsKey(digging)) round.plantNext = digging;
			digging = null;
		}
		if (Follow.ticks() % 5 == 0) corrections(level, p, now);
		if (testFarm) {
			if (now > testFarmUntil && round == null) {
				testFarm = false;
				fields.removeIf(f -> f.owner.equals("test"));
				Exchange.emit("helper", "action", "harvest test done");
				return false;
			}
			return farmTick(mc, p, p, now) && worked();
		}
		if (testTree) {
			if (now > testTreeUntil && tree == null) { testTree = false; Exchange.emit("helper", "action", "tree test done"); return false; }
			return treeTick(mc, p, p, now) && worked();
		}
		if (torch(mc, p)) return worked();
		if (pit != null && pit.test) return pitTick(mc, p, null, now) && worked();
		return tunnelTick(mc, p, null, now) && worked();
	}

	/** Start of the tick, where vanilla hits and clicks: the block, if the crosshair is on it. */
	static void startTick(Minecraft mc) {
		BlockPos hit = digAt, plant = plantAt;
		digAt = null;
		plantAt = null;
		LocalPlayer p = mc.player;
		if (p == null || mc.gameMode == null) return;
		boolean screen = Game.openScreen(mc) instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>;
		BlockHitResult bh = mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK ? b : null;
		if (hit != null && bh != null && bh.getBlockPos().equals(hit) && !screen && !AfkHelperClient.isEating()) {
			if (!hit.equals(hitTarget)) { hitTarget = hit.immutable(); hitCount = 0; }
			hitCount++;
			if (mc.gameMode.continueDestroyBlock(hit, bh.getDirection())) Game.swingAttack(p);
			hitting = true;
			return;
		}
		if (hitting) { mc.gameMode.stopDestroyBlock(); hitting = false; }
		if (plant != null && bh != null && bh.getBlockPos().equals(plant) && bh.getDirection() == Direction.UP && !screen && !p.isShiftKeyDown()) {
			InteractionResult r = AfkHelperClient.useOn(p, InteractionHand.MAIN_HAND, bh);
			if (r.consumesAction()) Game.swingUse(p, InteractionHand.MAIN_HAND);
			plantedAt = Follow.ticks();
		}
	}

	/** A chat line: a claim that does not let the buddy break here. */
	static void chat(String line) {
		if (digging == null || !Profiles.has("noPermission", line)) return;
		skipped.put(digging, System.currentTimeMillis() + 600_000);
		if (tree != null) dropTree("no permission");
		else if (tunnel != null) endTunnel("no permission");
	}

	// ---------- the tunnel ----------

	private static void detectTunnel(Level level, long now) {
		String dim = dimOf(level);
		List<Brk> rec = new ArrayList<>();
		for (Brk b : breaks)
			if (b.dim().equals(dim) && now - b.at() < 60_000 && !b.state().is(BlockTags.LOGS) && !b.state().is(BlockTags.LEAVES)
					&& !planted(b.state()) && !b.state().is(Blocks.MELON) && !b.state().is(Blocks.PUMPKIN)) rec.add(b);
		if (rec.size() > 24) rec = new ArrayList<>(rec.subList(rec.size() - 24, rec.size()));
		Tunnel found = null;
		if (rec.size() >= 12) for (Direction.Axis axis : new Direction.Axis[] { Direction.Axis.X, Direction.Axis.Z }) {
			Tunnel tn = shape(level, rec, axis, dim);
			if (tn == null) continue;
			if (found != null) { found = null; break; }   // both ways at once: no clear tunnel
			found = tn;
		}
		// the same tunnel twice in a row, half a second apart: sure enough
		if (found != null && found.same(candidate)) {
			found.lastHis = now;
			tunnel = found;
			candidate = null;
			BlockPos c = at(found.axis, found.face, found.pMin, found.yMin);
			Exchange.emit("helper", "action", "tunnel", "shape", describe(found), "x", c.getX(), "y", c.getY(), "z", c.getZ());
		} else candidate = found;
	}

	/**
	 * Is what the player broke last a tunnel along this axis? Long along it, narrow across, the breaking order going one
	 * way; the cross-section is the cells broken in two slices or more; at least three slices of it open with a floor
	 * under them, all three at least half the player's work (two until 0.4.44: the buddy started too soon).
	 */
	private static Tunnel shape(Level level, List<Brk> rec, Direction.Axis axis, String dim) {
		int aMin = Integer.MAX_VALUE, aMax = Integer.MIN_VALUE, qMin = Integer.MAX_VALUE, qMax = Integer.MIN_VALUE;
		int yLow = Integer.MAX_VALUE, yHigh = Integer.MIN_VALUE;
		for (Brk b : rec) {
			int a = along(b.pos(), axis), q = perp(b.pos(), axis), y = b.pos().getY();
			aMin = Math.min(aMin, a); aMax = Math.max(aMax, a);
			qMin = Math.min(qMin, q); qMax = Math.max(qMax, q);
			yLow = Math.min(yLow, y); yHigh = Math.max(yHigh, y);
		}
		if (aMax - aMin < 2 || qMax - qMin > 4 || yHigh - yLow > 4 || aMax - aMin <= qMax - qMin) return null;
		int n = rec.size(), third = Math.max(2, n / 3);
		double first = 0, last = 0;
		for (int i = 0; i < third; i++) { first += along(rec.get(i).pos(), axis); last += along(rec.get(n - 1 - i).pos(), axis); }
		first /= third; last /= third;
		if (Math.abs(last - first) < 1) return null;
		int dir = last > first ? 1 : -1;
		Map<Long, Set<Integer>> slices = new HashMap<>();
		for (Brk b : rec) slices.computeIfAbsent(key(perp(b.pos(), axis), b.pos().getY()), k -> new HashSet<>()).add(along(b.pos(), axis));
		int pMin = Integer.MAX_VALUE, pMax = Integer.MIN_VALUE, yMin = Integer.MAX_VALUE, yMax = Integer.MIN_VALUE;
		for (var e : slices.entrySet()) {
			if (e.getValue().size() < 2) continue;
			int q = (int) (e.getKey() >> 32), y = (int) (long) e.getKey();
			pMin = Math.min(pMin, q); pMax = Math.max(pMax, q);
			yMin = Math.min(yMin, y); yMax = Math.max(yMax, y);
		}
		if (pMin == Integer.MAX_VALUE) return null;
		int w = pMax - pMin + 1, h = yMax - yMin + 1;
		if (w < 2 || h < 2 || w > 5 || h > 5) return null;
		int face = dir > 0 ? aMax : aMin;
		int complete = 0, his = 0;
		for (int k = 0; k <= 5 && complete < 4; k++) {
			int a = face - dir * k;
			if (!open(level, axis, a, pMin, pMax, yMin, yMax)) { if (k == 0) continue; break; }   // the face may be half done
			if (!floor(level, axis, a, pMin, pMax, yMin)) return null;
			complete++;
			int c = 0;
			for (Brk b : rec) {
				int q = perp(b.pos(), axis), y = b.pos().getY();
				if (along(b.pos(), axis) == a && q >= pMin && q <= pMax && y >= yMin && y <= yMax) c++;
			}
			if (c * 2 >= w * h) his++;
		}
		if (complete < 3 || his < 3) return null;
		Tunnel t = new Tunnel();
		t.axis = axis; t.dir = dir; t.pMin = pMin; t.pMax = pMax; t.yMin = yMin; t.yMax = yMax; t.face = face; t.dim = dim;
		return t;
	}

	private static long key(int q, int y) { return ((long) q << 32) | (y & 0xffffffffL); }

	private static boolean open(Level level, Direction.Axis axis, int a, int pMin, int pMax, int yMin, int yMax) {
		for (int q = pMin; q <= pMax; q++) for (int y = yMin; y <= yMax; y++) {
			BlockPos c = at(axis, a, q, y);
			BlockState st = level.getBlockState(c);
			if (!st.getCollisionShape(level, c).isEmpty() || !st.getFluidState().isEmpty()) return false;
		}
		return true;
	}

	private static boolean floor(Level level, Direction.Axis axis, int a, int pMin, int pMax, int yMin) {
		int solid = 0;
		for (int q = pMin; q <= pMax; q++) {
			BlockPos c = at(axis, a, q, yMin - 1);
			if (!level.getBlockState(c).getCollisionShape(level, c).isEmpty()) solid++;
		}
		return solid * 10 >= (pMax - pMin + 1) * 6;
	}

	private static void hisInTunnel(BlockPos pos, long now) {
		Tunnel tn = tunnel;
		int a = along(pos, tn.axis), q = perp(pos, tn.axis), y = pos.getY();
		int ahead = (a - tn.face) * tn.dir;
		boolean in = q >= tn.pMin && q <= tn.pMax && y >= tn.yMin && y <= tn.yMax;
		if (in && ahead >= -4 && ahead <= 2) {
			tn.lastHis = now;
			if (ahead > 0) tn.face = a;
			return;
		}
		// three blocks of the player's near the face outside the cross-section: the player widens it or turns off
		if (!in && ahead >= -2 && ahead <= 3) {
			if (now - tn.oddAt > 15_000) tn.odd = 0;
			tn.odd++;
			tn.oddAt = now;
			if (tn.odd >= 3) endTunnel("another shape");
		}
	}

	private static boolean tunnelTick(Minecraft mc, LocalPlayer p, Player t, long now) {
		Tunnel tn = tunnel;
		Level level = p.level();
		if (!dimOf(level).equals(tn.dim)) { endTunnel("another dimension"); return false; }
		if (tn.test) {
			// standing in for the player: a slice done, the next one counts as the player's, until the length asked for
			while (open(level, tn.axis, tn.face, tn.pMin, tn.pMax, tn.yMin, tn.yMax)) {
				if (tn.face == tn.testEnd) { endTunnel("test done"); return false; }
				tn.face += tn.dir;
			}
		} else {
			if (now - tn.lastHis > 30_000) { endTunnel("you no longer break"); return false; }
			if (t != null) {
				BlockPos f = t.blockPosition();
				int behind = (tn.face - along(f, tn.axis)) * tn.dir, q = perp(f, tn.axis);
				if (behind > 8 || q < tn.pMin - 2 || q > tn.pMax + 2 || Math.abs(f.getY() - tn.yMin) > 3) { endTunnel("you are out of it"); return false; }
			}
			// the player does not break: neither does the alt
			boolean pause = now - tn.lastHis > 3000;
			if (pause != tn.paused) { tn.paused = pause; Exchange.emit("helper", "action", pause ? "tunnel pause" : "tunnel on"); }
			if (pause) return false;
		}
		BlockPos target = tunnelTarget(level, p, t, tn, now);
		if (target == null) return false;
		BlockPos hisCell = t == null ? null : t.blockPosition();
		return dig(mc, p, target, t, f -> inTunnel(tn, f) && (hisCell == null || (!f.equals(hisCell) && !f.equals(hisCell.above()))));
	}

	/** While a tunnel or pit is on and no block is half broken: a torch, or torches made (Torches). */
	private static boolean torch(Minecraft mc, LocalPlayer p) {
		if (tunnel == null && pit == null) return Torches.busy() && Torches.tick(mc, p, null, Work::stillToDig);
		return (digging == null || Torches.busy()) && Torches.tick(mc, p, tunnelForward(), Work::stillToDig);
	}

	/** The way the tunnel goes, or null when there is none (a pit). */
	private static Direction tunnelForward() {
		Tunnel tn = tunnel;
		return tn == null ? null : Direction.fromAxisAndDirection(tn.axis, tn.dir > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
	}

	/** A block in the tunnel's cross-section or inside the pit at its heights: one still to dig, no torch goes on it. */
	static boolean stillToDig(BlockPos w) {
		Tunnel tn = tunnel;
		if (tn != null) {
			int q = perp(w, tn.axis);
			if (q >= tn.pMin && q <= tn.pMax && w.getY() >= tn.yMin && w.getY() <= tn.yMax) return true;
		}
		Pit pt = pit;
		return pt != null && w.getX() >= pt.xMin && w.getX() <= pt.xMax && w.getZ() >= pt.zMin && w.getZ() <= pt.zMax
			&& w.getY() >= pt.yMin && w.getY() <= pt.yMax;
	}

	/** A cell to stand in while digging: in the tunnel itself, on its floor, behind the face. */
	private static boolean inTunnel(Tunnel tn, BlockPos f) {
		int q = perp(f, tn.axis), ahead = (along(f, tn.axis) - tn.face) * tn.dir;
		return f.getY() == tn.yMin && q >= tn.pMin && q <= tn.pMax && ahead <= -1 && ahead >= -4;
	}

	/**
	 * The next block of the tunnel: in the slice the player breaks or a slice behind it, inside the cross-section, still
	 * solid, safe, and not the one the player hits. First one within reach of the alt's own spot, then the nearest.
	 */
	private static BlockPos tunnelTarget(Level level, LocalPlayer p, Player t, Tunnel tn, long now) {
		Vec3 eye = p.getEyePosition();
		BlockPos best = null;
		double bestCost = Double.MAX_VALUE;
		for (int k = 0; k <= 3; k++) {
			int a = tn.face - tn.dir * k;
			for (int q = tn.pMin; q <= tn.pMax; q++) for (int y = tn.yMin; y <= tn.yMax; y++) {
				BlockPos c = at(tn.axis, a, q, y);
				if (level.getBlockState(c).getCollisionShape(level, c).isEmpty() || skipped.containsKey(c) || c.equals(hisLook)) continue;
				if (fixedLine(tn, q, y) || fixSeen.containsKey(c)) continue;
				if (!safe(level, c)) { skipped.put(c, now + 30_000); continue; }
				double cost = eye.distanceTo(Vec3.atCenterOf(c)) + (aimPoint(level, p, eye, c) == null ? 3 : 0) + k * 0.5;
				if (cost < bestCost) { bestCost = cost; best = c; }
			}
		}
		return best;
	}

	/** The player put a block back in this row of the cross-section, near the face: the tunnel keeps that row. */
	private static boolean fixedLine(Tunnel tn, int q, int y) {
		for (Fix f : fixes)
			if (f.dim().equals(tn.dim) && perp(f.pos(), tn.axis) == q && f.pos().getY() == y && Math.abs(along(f.pos(), tn.axis) - tn.face) <= 32) return true;
		return false;
	}

	private static void endTunnel(String why) {
		if (tunnel == null) return;
		Exchange.emit("helper", "action", "tunnel done", "reason", why, "shape", describe(tunnel));
		if (!tunnel.test) Dump.jobDone();
		tunnel = null;
		candidate = null;
		digging = null;
		stand = null;
	}

	// ---------- a pit ----------

	/**
	 * Natural ground: what a pit is dug in. Never planks, cobblestone, bricks or anything else a player sets down, and
	 * no ores (the player's to take): the alt breaks only kinds of this the player broke there.
	 */
	static boolean ground(BlockState st) {
		if (st.is(Blocks.SUSPICIOUS_SAND) || st.is(Blocks.SUSPICIOUS_GRAVEL)) return false;
		return st.is(BlockTags.SUBSTRATE_OVERWORLD) || st.is(BlockTags.SAND) || st.is(Blocks.GRAVEL) || st.is(Blocks.CLAY)
			|| st.is(Blocks.PODZOL) || st.is(Blocks.MYCELIUM) || st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(BlockTags.BASE_STONE_NETHER)
			|| st.is(Blocks.SANDSTONE) || st.is(Blocks.RED_SANDSTONE) || st.is(Blocks.CALCITE);
	}

	/**
	 * The player's ground breaks of the last window near the latest one: the box they fill and the kinds. Null with fewer than
	 * {@code least}, or when the box is not wide both ways (a tunnel, a trench, a staircase) or higher than six.
	 */
	private static Pit pitOf(String dim, long now, long window, int least) {
		Brk last = null;
		for (Brk b : breaks) if (b.dim().equals(dim) && ground(b.state()) && now - b.at() < window) last = b;
		if (last == null) return null;
		Pit pt = new Pit();
		pt.dim = dim;
		pt.xMin = pt.zMin = pt.yMin = Integer.MAX_VALUE;
		pt.xMax = pt.zMax = pt.yMax = Integer.MIN_VALUE;
		int n = 0;
		for (Brk b : breaks) {
			BlockPos q = b.pos();
			if (!b.dim().equals(dim) || !ground(b.state()) || now - b.at() >= window) continue;
			if (Math.abs(q.getX() - last.pos().getX()) > 16 || Math.abs(q.getZ() - last.pos().getZ()) > 16) continue;
			n++;
			pt.xMin = Math.min(pt.xMin, q.getX()); pt.xMax = Math.max(pt.xMax, q.getX());
			pt.zMin = Math.min(pt.zMin, q.getZ()); pt.zMax = Math.max(pt.zMax, q.getZ());
			pt.yMin = Math.min(pt.yMin, q.getY()); pt.yMax = Math.max(pt.yMax, q.getY());
			pt.kinds.add(b.state().getBlock());
			pt.lastHis = Math.max(pt.lastHis, b.at());
			pt.firstHis = Math.min(pt.firstHis, b.at());
		}
		if (n < least || pt.xMax - pt.xMin < 3 || pt.zMax - pt.zMin < 3 || pt.yMax - pt.yMin > 5) return null;
		return pt;
	}

	/**
	 * Twenty ground breaks of the player's in a minute, at least twenty seconds of work, wide both ways: a pit, the second
	 * time running half a second apart. Twelve until 0.4.44, when the buddy joined in too eagerly and too soon.
	 */
	private static void detectPit(Level level, long now) {
		if (pit != null) return;
		Pit found = pitOf(dimOf(level), now, 60_000, PIT_START);
		if (found != null && found.lastHis - found.firstHis < 20_000) found = null;
		if (found != null && pitSeen) {
			Pit full = pitOf(found.dim, now, 120_000, PIT_START);
			pit = full != null ? full : found;
			pitSeen = false;
			Exchange.emit("helper", "action", "dig out", "shape", describe(pit), "x", pit.xMin, "y", pit.yMin, "z", pit.zMin);
		} else pitSeen = found != null;
	}

	private static boolean pitTick(Minecraft mc, LocalPlayer p, Player t, long now) {
		Pit pt = pit;
		Level level = p.level();
		if (!dimOf(level).equals(pt.dim)) { endPit("another dimension"); return false; }
		if (pt.test) {
			if (now > pt.testUntil) { endPit("test done"); return false; }
		} else {
			// the box follows the player's work: the last two minutes of it
			Pit now2 = pitOf(pt.dim, now, 120_000, 6);
			if (now2 != null) {
				pt.xMin = now2.xMin; pt.xMax = now2.xMax; pt.zMin = now2.zMin; pt.zMax = now2.zMax;
				pt.yMin = now2.yMin; pt.yMax = now2.yMax; pt.kinds.addAll(now2.kinds); pt.lastHis = Math.max(pt.lastHis, now2.lastHis);
			}
			if (now - pt.lastHis > 30_000) { endPit("you no longer break"); return false; }
			if (t != null) {
				double dx = Math.max(0, Math.max(pt.xMin - t.getX(), t.getX() - pt.xMax - 1)), dz = Math.max(0, Math.max(pt.zMin - t.getZ(), t.getZ() - pt.zMax - 1));
				if (Math.hypot(dx, dz) > 12 || Math.abs(t.getY() - pt.yMin) > 8) { endPit("you are out of it"); return false; }
			}
			boolean pause = now - pt.lastHis > 3000;
			if (pause != pt.paused) { pt.paused = pause; Exchange.emit("helper", "action", pause ? "digging out pause" : "digging out on"); }
			if (pause) return false;
		}
		BlockPos target = pitTarget(level, p, t, pt, now);
		if (target == null) return false;
		BlockPos hisCell = t == null ? null : t.blockPosition();
		return dig(mc, p, target, t, f -> pitStand(pt, f, target, hisCell));
	}

	/**
	 * The next block: in the player's box (never the ring around it since 0.4.44), between the player's work (see
	 * {@link PitShape}), at the heights dug at (never the floor under them or the roof above), of a kind the player broke
	 * there, with an open side, safe, not the one being hit, not where a block was put back, and never in the column
	 * under the player's feet or the alt's own. First one within reach of the alt's own spot, then the nearest.
	 */
	private static BlockPos pitTarget(Level level, LocalPlayer p, Player t, Pit pt, long now) {
		Vec3 eye = p.getEyePosition();
		BlockPos me = p.blockPosition(), him = t == null ? null : t.blockPosition();
		BlockPos best = null;
		double bestCost = Double.MAX_VALUE;
		PitShape.Cells cells = new PitShape.Cells() {
			public boolean open(int x, int y, int z) {
				BlockPos c = new BlockPos(x, y, z);
				// the alt's own breaks are no anchor: what it opens must not draw it further on
				return level.getBlockState(c).getCollisionShape(level, c).isEmpty() && !mineLong.containsKey(c);
			}
			public boolean fixed(int x, int y, int z) { return fixedAt(pt.dim, x, y, z); }
		};
		for (int x = pt.xMin; x <= pt.xMax; x++) for (int z = pt.zMin; z <= pt.zMax; z++) {
			if ((x == me.getX() && z == me.getZ()) || (him != null && x == him.getX() && z == him.getZ())) continue;
			if (fixedColumn(pt.dim, x, z, pt.yMin, pt.yMax)) continue;
			for (int y = pt.yMin; y <= pt.yMax; y++) {
				BlockPos c = new BlockPos(x, y, z);
				BlockState st = level.getBlockState(c);
				if (st.getCollisionShape(level, c).isEmpty() || skipped.containsKey(c) || c.equals(hisLook) || fixSeen.containsKey(c)) continue;
				if (!(pt.test ? ground(st) : pt.kinds.contains(st.getBlock()) && ground(st))) continue;
				if (!openSide(level, c)) continue;
				if (!pt.test && !PitShape.between(cells, x, y, z, pt.xMin, pt.xMax, pt.zMin, pt.zMax)) continue;
				if (!safe(level, c)) { skipped.put(c, now + 30_000); continue; }
				double cost = eye.distanceTo(Vec3.atCenterOf(c)) + (aimPoint(level, p, eye, c) == null ? 3 : 0);
				if (cost < bestCost) { bestCost = cost; best = c; }
			}
		}
		return best;
	}

	private static boolean openSide(Level level, BlockPos c) {
		for (Direction d : Direction.values()) {
			BlockPos n = c.relative(d);
			if (level.getBlockState(n).getCollisionShape(level, n).isEmpty()) return true;
		}
		return false;
	}

	/** Where to stand for a pit block: in or around the pit, never on top of the block itself, never in the player's cell. */
	private static boolean pitStand(Pit pt, BlockPos f, BlockPos target, BlockPos hisCell) {
		if (f.getX() == target.getX() && f.getZ() == target.getZ() && f.getY() > target.getY()) return false;
		if (hisCell != null && (f.equals(hisCell) || f.equals(hisCell.above()))) return false;
		return f.getX() >= pt.xMin - 3 && f.getX() <= pt.xMax + 3 && f.getZ() >= pt.zMin - 3 && f.getZ() <= pt.zMax + 3
			&& f.getY() >= pt.yMin - 1 && f.getY() <= pt.yMax + 2;
	}

	private static void endPit(String why) {
		if (pit == null) return;
		Exchange.emit("helper", "action", "digging out done", "reason", why, "shape", describe(pit));
		if (!pit.test) Dump.jobDone();
		pit = null;
		pitSeen = false;
		digging = null;
		stand = null;
	}

	private static int along(BlockPos b, Direction.Axis axis) { return axis == Direction.Axis.X ? b.getX() : b.getZ(); }

	private static int perp(BlockPos b, Direction.Axis axis) { return axis == Direction.Axis.X ? b.getZ() : b.getX(); }

	private static BlockPos at(Direction.Axis axis, int a, int q, int y) { return axis == Direction.Axis.X ? new BlockPos(a, y, q) : new BlockPos(q, y, a); }

	// ---------- trees ----------

	private static boolean treeTick(Minecraft mc, LocalPlayer p, Player t, long now) {
		Level level = p.level();
		if (tree == null && baseRun && t == p && Follow.ticks() % 100 == 0 && Chores.woodWanted(p)) {
			// no sticks, planks or logs carried or in the chests for the tool to make: a tree of the base, outside the
			// garden around the chests
			var home = Tap.baseAt(dimOf(level), p.position(), Base.RADIUS);
			Tree own = home == null ? null : findTree(level, p, p, home.bed(), (int) Base.RADIUS - 4, 8, now);
			woodTree = own != null;
			if (own != null) {
				own.own = true;
				tree = own;
				Exchange.emit("helper", "action", "tree", "x", tree.base.getX(), "y", tree.base.getY(), "z", tree.base.getZ(), "reason", "wood for sticks",
					"kind", BuiltInRegistries.BLOCK.getKey(level.getBlockState(tree.base).getBlock()).getPath());
			}
		}
		if (tree == null) {
			if (!chop || t == null || Follow.ticks() % 20 != 0) return false;
			// only while the player still chops: a log of the player's in the last fifteen seconds; once a tree is done, look whether the
			// player still chops, and if not, collect and stop
			if (!testTree && (now - lastHisLog > CHOPPING || hisLogs(now) < 2)) {
				if (chopTold < lastHisLog && now - lastHisLog > CHOPPING && hisLogs(now) >= 2) {
					chopTold = lastHisLog;
					Exchange.emit("helper", "action", "chopping done", "reason", "you no longer chop");
					Dump.jobDone();
				}
				return false;
			}
			// the player chops too far off: no tree picked and dropped again every second
			if (t != p && t.distanceTo(p) > 24) return false;
			tree = findTree(level, p, t, t.blockPosition(), 14, 4, now);
			if (tree == null) return false;
			Exchange.emit("helper", "action", "tree", "x", tree.base.getX(), "y", tree.base.getY(), "z", tree.base.getZ(),
				"kind", BuiltInRegistries.BLOCK.getKey(level.getBlockState(tree.base).getBlock()).getPath());
		}
		if (!dimOf(level).equals(tree.dim)) { dropTree("another dimension"); return false; }
		if (t != null && t != p && t.distanceTo(p) > 24) { dropTree("too far from you"); return false; }
		switch (tree.phase) {
			case 0 -> {
				BlockPos target = nextLog(level, p);
				if (target == null) { tree.phase = 1; tree.phaseAt = now; Follow.stand(); return true; }
				if (dig(mc, p, target, t, f -> true)) return true;
				return true;   // that log skipped: the next one next tick
			}
			case 1 -> {
				// the leaves fall in a few seconds: then pick up what lies around the trunk
				if (now - tree.phaseAt < 3000) { Follow.stand(); return true; }
				ItemEntity it = treeDrop(level, p);
				if (it != null && now - tree.phaseAt < 15_000) {
					if (!approach(mc, p, it.getId(), it.position(), now)) gaveUp.add(it.getId());
					return true;
				}
				tree.phase = 2;
				tree.phaseAt = now;
				return true;
			}
			default -> { return plant(mc, p, t, now); }
		}
	}

	/** The nearest tree the buddy could chop: grown, one trunk on dirt, all of it within reach from the ground, not the player's. */
	private static Tree findTree(Level level, LocalPlayer p, Player t, BlockPos c, int r, int dy, long now) {
		Tree best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos q : BlockPos.betweenClosed(c.offset(-r, -dy, -r), c.offset(r, dy, r))) {
			if (!level.getBlockState(q).is(BlockTags.LOGS) || !level.getBlockState(q.below()).is(BlockTags.DIRT)) continue;
			BlockPos base = q.immutable();
			if (treeDone.containsKey(base) || skipped.containsKey(base)) continue;
			if (t != p && (Math.hypot(base.getX() + 0.5 - t.getX(), base.getZ() + 0.5 - t.getZ()) < 3.5 || his(base, now))) continue;
			// the trees around the owner's chests are the owner's garden: the owner chops those
			if (Stash.nearChest(level, base, 24)) continue;
			double d = p.distanceToSqr(Vec3.atCenterOf(base));
			if (d >= bestD) continue;
			Tree tr = tree(level, base);
			if (tr == null) { treeDone.put(base, now + 600_000); continue; }
			best = tr;
			bestD = d;
		}
		return best;
	}

	/** The tree the player chops right now: one with a log broken near it in the last twenty seconds, or one looked at. */
	private static boolean his(BlockPos base, long now) {
		for (Brk b : breaks)
			if (now - b.at() < 20_000 && Math.abs(b.pos().getX() - base.getX()) <= 4 && Math.abs(b.pos().getZ() - base.getZ()) <= 4) return true;
		return hisLook != null && Math.abs(hisLook.getX() - base.getX()) <= 3 && Math.abs(hisLook.getZ() - base.getZ()) <= 3;
	}

	private static Tree tree(Level level, BlockPos base) {
		for (Direction d : Direction.Plane.HORIZONTAL)
			if (level.getBlockState(base.relative(d)).is(BlockTags.LOGS) || level.getBlockState(base.relative(d).above()).is(BlockTags.LOGS)) return null;
		if (!natural(level, base)) return null;
		Set<BlockPos> logs = logsOf(level, base);
		if (logs.isEmpty() || logs.size() > 36) return null;
		for (BlockPos l : logs)
			if (l.getY() - base.getY() > 5 || Math.abs(l.getX() - base.getX()) > 2 || Math.abs(l.getZ() - base.getZ()) > 2) return null;
		Tree t = new Tree();
		t.base = base;
		t.dim = dimOf(level);
		t.sapling = saplingFor(BuiltInRegistries.BLOCK.getKey(level.getBlockState(base).getBlock()).getPath());
		return t;
	}

	/** The logs of the tree on this base, as far as they hang together (branches too). */
	private static Set<BlockPos> logsOf(Level level, BlockPos base) {
		Set<BlockPos> logs = new HashSet<>();
		ArrayDeque<BlockPos> todo = new ArrayDeque<>();
		if (level.getBlockState(base).is(BlockTags.LOGS)) { logs.add(base); todo.add(base); }
		while (!todo.isEmpty() && logs.size() <= 40) {
			BlockPos c = todo.poll();
			for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
				BlockPos n = c.offset(dx, dy, dz);
				if (n.getY() < base.getY() || Math.abs(n.getX() - base.getX()) > 3 || Math.abs(n.getZ() - base.getZ()) > 3 || n.getY() - base.getY() > 9) continue;
				if (logs.contains(n) || !level.getBlockState(n).is(BlockTags.LOGS)) continue;
				logs.add(n.immutable());
				todo.add(n.immutable());
			}
		}
		return logs;
	}

	/**
	 * The next log: the lowest that is left, nearest first. If the way to it is a leaf of the tree, that leaf; with the
	 * base gone the rest of the tree is found from where the base stood.
	 */
	private static BlockPos nextLog(Level level, LocalPlayer p) {
		Set<BlockPos> logs = logsOf(level, tree.base);
		if (logs.isEmpty()) for (BlockPos up = tree.base.above(); up.getY() <= tree.base.getY() + 6 && logs.isEmpty(); up = up.above()) logs = logsOf(level, up);
		Vec3 eye = p.getEyePosition();
		List<BlockPos> order = new ArrayList<>(logs);
		order.removeIf(skipped::containsKey);
		order.sort(Comparator.comparingInt((BlockPos b) -> b.getY()).thenComparingDouble(b -> eye.distanceToSqr(Vec3.atCenterOf(b))));
		if (order.isEmpty()) return null;
		BlockPos log = order.get(0);
		if (aimPoint(level, p, eye, log) == null) {
			BlockHitResult in = level.clip(new ClipContext(eye, Vec3.atCenterOf(log), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
			if (in.getType() == HitResult.Type.BLOCK && eye.distanceTo(in.getLocation()) < REACH) {
				BlockState st = level.getBlockState(in.getBlockPos());
				if (st.getBlock() instanceof LeavesBlock && !st.getValue(LeavesBlock.PERSISTENT)) return in.getBlockPos().immutable();
			}
		}
		return log;
	}

	/** What fell from this tree: wood, saplings, apples, sticks, within six blocks of where it stood. */
	private static ItemEntity treeDrop(Level level, LocalPlayer p) {
		Vec3 base = Vec3.atBottomCenterOf(tree.base);
		ItemEntity best = null;
		for (ItemEntity it : level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(tree.base).inflate(6, 4, 6))) {
			ItemStack st = it.getItem();
			if (!(st.is(ItemTags.LOGS) || st.is(ItemTags.SAPLINGS) || st.is(Items.APPLE) || st.is(Items.STICK)) || gaveUp.contains(it.getId())) continue;
			if (it.position().distanceTo(base) > 6.5) continue;
			if (best == null || it.distanceToSqr(p) < best.distanceToSqr(p)) best = it;
		}
		return best;
	}

	/** A sapling of the kind back where the trunk stood, when one is carried. */
	private static boolean plant(Minecraft mc, LocalPlayer p, Player t, long now) {
		Level level = p.level();
		BlockPos ground = tree.base.below();
		BlockState here = level.getBlockState(tree.base);
		if (here.getBlock() instanceof SaplingBlock || here.is(Blocks.MANGROVE_PROPAGULE)) return finishTree(plantedAt >= 0 ? "planted" : "sapling already there");
		if (tree.sapling == null || now - tree.phaseAt > 10_000 || !here.isAir() || !Game.plantable(level.getBlockState(ground))) return finishTree("not planted");
		int slot = -1;
		for (int i = 0; i < 36 && slot < 0; i++) if (AfkHelperClient.itemId(p.getInventory().getItem(i)).equals(tree.sapling)) slot = i;
		if (slot < 0) return finishTree("no sapling");
		Vec3 point = new Vec3(tree.base.getX() + 0.5, tree.base.getY() - 0.02, tree.base.getZ() + 0.5);
		Vec3 eye = p.getEyePosition();
		BlockHitResult ray = level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
		boolean reach = eye.distanceTo(point) < REACH && ray.getType() == HitResult.Type.BLOCK && ray.getBlockPos().equals(ground) && ray.getDirection() == Direction.UP;
		if (!reach) {
			if (stand == null || !ground.equals(standFor)) {
				standFor = ground;
				stand = null;
				for (Direction d : Direction.Plane.HORIZONTAL) {
					BlockPos f = tree.base.relative(d);
					if (!Double.isNaN(Follow.floor(level, f))) { stand = f; break; }
				}
				if (stand == null) return finishTree("unreachable");
			}
			if (!approach(mc, p, stand, Vec3.atBottomCenterOf(stand), now)) return finishTree("unreachable");
			return true;
		}
		Follow.stand();
		if (slot >= 9) {
			String want = tree.sapling;
			AfkHelperClient.toHotbar(p, mc, s -> AfkHelperClient.itemId(s).equals(want), Comparator.comparingInt(ItemStack::getCount), "sapling");
			return true;
		}
		if (p.getInventory().getSelectedSlot() != slot) p.getInventory().setSelectedSlot(slot);
		Follow.aim(p, point);
		plantAt = ground;
		return true;
	}

	private static boolean finishTree(String how) {
		if (tree != null) {
			Exchange.emit("helper", "action", "tree done", "how", how, "x", tree.base.getX(), "y", tree.base.getY(), "z", tree.base.getZ());
			treeDone.put(tree.base, System.currentTimeMillis() + 30 * 60_000L);
		}
		tree = null;
		plantedAt = -1;
		stand = null;
		return false;
	}

	private static void dropTree(String why) {
		if (tree == null) return;
		Exchange.emit("helper", "action", "tree stopped", "reason", why, "x", tree.base.getX(), "y", tree.base.getY(), "z", tree.base.getZ());
		treeDone.put(tree.base, System.currentTimeMillis() + 5 * 60_000L);
		tree = null;
		digging = null;
		stand = null;
	}

	private static String saplingFor(String log) {
		String kind = log.replace("stripped_", "").replace("_log", "").replace("_wood", "");
		return switch (kind) {
			case "mangrove" -> "mangrove_propagule";
			case "crimson_stem", "warped_stem", "crimson", "warped" -> null;
			default -> kind + "_sapling";
		};
	}

	// ---------- crops ----------

	/**
	 * A place where the player the buddy works for sowed or renewed crops, one per four by four blocks, kept per player in
	 * {@code fields.json}. Its field is
	 * what grows within seven blocks of it.
	 */
	private static final class Field {
		String server, dim, owner;
		BlockPos pos;
		long last;          // when the alt last harvested here
	}
	private static final List<Field> fields = new ArrayList<>();
	private static boolean fieldsLoaded;

	/** The known fields on this server in this dimension, for the panel's map. */
	static List<BlockPos> knownFields(String dim) {
		List<BlockPos> out = new ArrayList<>();
		for (Field f : fields) if (Profiles.key().equals(f.server) && dim.equals(f.dim)) out.add(f.pos);
		return out;
	}
	/** The air above a block the player looked at, with the tick: something planted there within a second is the player's. */
	private static final Map<BlockPos, Integer> plantWatch = new HashMap<>();
	/** What the alt planted, a moment ago: never taken for the player's sowing. */
	private static final Map<BlockPos, Long> ourPlants = new HashMap<>();
	private static long lastHisHarvest;
	private static BlockPos hisHarvestAt;

	private static final class Round {
		Field field;
		BlockPos center;
		boolean join;       // the player harvests here in person: alongside, whatever is ripe
		int phase;          // 0 harvest, 1 pick up, 2 plant again
		long phaseAt, startedAt, cellSince;
		BlockPos plantNext, cellFor;
		final Map<BlockPos, String> replant = new HashMap<>();
		/** Trampled farmland of the field (dirt again): hoed back and sown, with what grows beside it. */
		final Map<BlockPos, String> till = new HashMap<>();
		BlockPos tillFor, tillStand;
		long tillSince;
		int broke;          // ripe crops gone for in this round
		boolean bareLooked; // the field's bare farmland put on the list to sow, once a round
	}
	private static Round round;
	/** A cell that could not be sown (no reach, the click did nothing): left alone for a while, not tried every round. */
	private static final Map<BlockPos, Long> plantFailed = new HashMap<>();
	private static final long PLANT_RETRY = 10 * 60_000L;

	/** Harvest a field of the player's alone only when this much of it is ripe, and not twice within ten minutes. */
	private static final int RIPE_MANY = 24, RIPE_PERCENT = 60;
	private static final long FIELD_REST = 10 * 60_000L;

	/** What grows on farmland or soul sand and is planted again from its own drop: the seed's id. */
	private static String seedOf(BlockState st) {
		if (st.is(Blocks.WHEAT)) return "wheat_seeds";
		if (st.is(Blocks.CARROTS)) return "carrot";
		if (st.is(Blocks.POTATOES)) return "potato";
		if (st.is(Blocks.BEETROOTS)) return "beetroot_seeds";
		if (st.is(Blocks.NETHER_WART)) return "nether_wart";
		return null;
	}

	static boolean planted(BlockState st) {
		return seedOf(st) != null || st.is(Blocks.SUGAR_CANE) || st.is(Blocks.MELON_STEM) || st.is(Blocks.PUMPKIN_STEM);
	}

	/** Ready: a crop at its last age, the second block of a stalk of sugar cane, a melon or pumpkin on its stem. */
	private static boolean ripe(Level level, BlockPos pos, BlockState st) {
		if (st.getBlock() instanceof CropBlock crop && seedOf(st) != null) return crop.isMaxAge(st);
		if (st.is(Blocks.NETHER_WART)) return st.getValue(NetherWartBlock.AGE) >= 3;
		if (st.is(Blocks.SUGAR_CANE)) return level.getBlockState(pos.below()).is(Blocks.SUGAR_CANE) && !level.getBlockState(pos.below(2)).is(Blocks.SUGAR_CANE);
		if (st.is(Blocks.MELON) || st.is(Blocks.PUMPKIN)) {
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockState n = level.getBlockState(pos.relative(d));
				if ((n.is(Blocks.ATTACHED_MELON_STEM) || n.is(Blocks.ATTACHED_PUMPKIN_STEM)) && n.getValue(AttachedStemBlock.FACING) == d.getOpposite()) return true;
			}
		}
		return false;
	}

	private static void learnField(Level level, BlockPos pos) {
		String owner = Follow.boss();
		if (owner == null) return;
		loadFields();
		String server = server(), dim = dimOf(level), who = owner.toLowerCase(Locale.ROOT);
		for (Field f : fields)
			if (f.server.equals(server) && f.dim.equals(dim) && f.owner.equals(who) && Math.abs(f.pos.getX() - pos.getX()) <= 4
				&& Math.abs(f.pos.getZ() - pos.getZ()) <= 4 && Math.abs(f.pos.getY() - pos.getY()) <= 2) return;
		Field f = new Field();
		f.server = server; f.dim = dim; f.owner = who; f.pos = pos.immutable();
		fields.add(f);
		saveFields();
		Exchange.emit("helper", "action", "field learnt", "for", owner, "x", pos.getX(), "y", pos.getY(), "z", pos.getZ(), "fields", fieldCount());
	}

	static int fieldCount() {
		loadFields();
		String owner = Follow.boss();
		if (owner == null) return 0;
		String server = server(), who = owner.toLowerCase(Locale.ROOT);
		int n = 0;
		for (Field f : fields) if (f.server.equals(server) && f.owner.equals(who)) n++;
		return n;
	}

	private static boolean farmTick(Minecraft mc, LocalPlayer p, Player t, long now) {
		Level level = p.level();
		if (round == null) {
			if (!(baseRun ? AfkConfig.get().baseHarvest : farm) || t == null || Follow.ticks() % 20 != 0) return false;
			round = planRound(level, p, t, now);
			if (round == null) return false;
			Exchange.emit("helper", "action", "harvest", "along", round.join, "x", round.center.getX(), "y", round.center.getY(), "z", round.center.getZ());
		}
		if (!dimOf(level).equals(round.field.dim) || (t != null && t.distanceTo(p) > 32)) { endRound("too far from you"); return false; }
		if (now - round.startedAt > 240_000) { endRound("took too long"); return false; }
		switch (round.phase) {
			case 0 -> {
				if (round.plantNext != null) {
					if (plantCell(mc, p, round.plantNext, now)) return true;
					round.plantNext = null;
				}
				BlockPos target = nextRipe(level, p);
				if (target == null) { round.phase = 1; round.phaseAt = now; Follow.stand(); return true; }
				String seed = seedOf(level.getBlockState(target));
				round.broke++;
				if (seed != null) round.replant.put(target.immutable(), seed);
				dig(mc, p, target, t, f -> !f.equals(target));
				return true;
			}
			case 1 -> {
				ItemEntity it = fieldDrop(level, p);
				if (it != null && now - round.phaseAt < 15_000) {
					if (!approach(mc, p, it.getId(), it.position(), now)) gaveUp.add(it.getId());
					return true;
				}
				round.phase = 2;
				round.phaseAt = now;
				return true;
			}
			default -> {
				if (!round.bareLooked) { round.bareLooked = true; addBare(level, now); }
				BlockPos dirt = nextTill(level);
				if (dirt != null && now - round.phaseAt < 40_000) {
					if (!tillCell(mc, p, dirt, now)) round.till.remove(dirt);
					return true;
				}
				BlockPos cell = nextReplant(level, p);
				if (cell == null || now - round.phaseAt > 40_000) { endRound(cell == null ? "done" : "took too long"); return false; }
				if (!plantCell(mc, p, cell, now)) round.replant.remove(cell);
				return true;
			}
		}
	}

	/**
	 * A field of the player's to harvest now: alongside the player where the player harvested in the last half minute,
	 * whatever is ripe; alone where much of it is ripe, not back at every single crop that ripens, and not within ten
	 * minutes of the last time. Near the player and near the alt.
	 */
	private static Round planRound(Level level, LocalPlayer p, Player t, long now) {
		loadFields();
		String owner = testFarm ? "test" : Follow.boss();
		if (owner == null) return null;
		String server = server(), dim = dimOf(level), who = owner.toLowerCase(Locale.ROOT);
		Field best = null;
		boolean bestJoin = false;
		double bestD = Double.MAX_VALUE;
		for (Field f : fields) {
			if (!f.server.equals(server) || !f.dim.equals(dim) || !f.owner.equals(who)) continue;
			// a field whose round came to nothing twice (what is ripe cannot be reached) is left for a while (Failures)
			if (Failures.blocked(Failures.near("harvest", f.pos.getX(), f.pos.getY(), f.pos.getZ()))) continue;
			Vec3 c = Vec3.atCenterOf(f.pos);
			if (c.distanceTo(t.position()) > 32 || c.distanceTo(p.position()) > 40) continue;
			boolean join = testFarm || (hisHarvestAt != null && now - lastHisHarvest < 30_000 && hisHarvestAt.distSqr(f.pos) < 100);
			int total = 0, ripe = 0, other = 0;
			for (BlockPos q : BlockPos.betweenClosed(f.pos.offset(-7, -3, -7), f.pos.offset(7, 3, 7))) {
				BlockState st = level.getBlockState(q);
				boolean crop = seedOf(st) != null;
				if (crop) total++;
				if (!ripe(level, q, st)) continue;
				if (crop) ripe++; else other++;
			}
			boolean due = now - f.last > FIELD_REST && (ripe >= RIPE_MANY || (total >= 4 && ripe * 100 >= total * RIPE_PERCENT) || other >= 8);
			// trampled farmland is mended at once, when a hoe is carried
			if (!due && hasHoe(p) && !trampledOf(level, f.pos).isEmpty()) due = true;
			if (!(join && ripe + other > 0) && !due) continue;
			double d = c.distanceToSqr(p.position());
			if (d < bestD) { bestD = d; best = f; bestJoin = join; }
		}
		if (best == null) return null;
		Round r = new Round();
		r.field = best;
		r.center = best.pos;
		r.join = bestJoin;
		r.startedAt = r.phaseAt = now;
		if (hasHoe(p)) r.till.putAll(trampledOf(level, best.pos));
		return r;
	}

	/** The nearest ripe crop of the round's field, the one reached first from where the alt stands. */
	private static BlockPos nextRipe(Level level, LocalPlayer p) {
		Vec3 eye = p.getEyePosition();
		BlockPos best = null;
		double bestCost = Double.MAX_VALUE;
		for (BlockPos q : BlockPos.betweenClosed(round.center.offset(-7, -3, -7), round.center.offset(7, 3, 7))) {
			BlockState st = level.getBlockState(q);
			if (!ripe(level, q, st) || skipped.containsKey(q)) continue;
			double cost = eye.distanceToSqr(Vec3.atCenterOf(q));
			if (cost < bestCost) { bestCost = cost; best = q.immutable(); }
		}
		return best;
	}

	/** What a field drops: the crops, their seeds, melon slices, pumpkins, sugar cane. */
	private static ItemEntity fieldDrop(Level level, LocalPlayer p) {
		ItemEntity best = null;
		for (ItemEntity it : level.getEntitiesOfClass(ItemEntity.class, new AABB(round.center).inflate(8, 4, 8))) {
			String id = AfkHelperClient.itemId(it.getItem());
			if (!Set.of("wheat", "wheat_seeds", "carrot", "potato", "poisonous_potato", "beetroot", "beetroot_seeds", "nether_wart",
				"melon_slice", "pumpkin", "sugar_cane").contains(id) || gaveUp.contains(it.getId())) continue;
			if (best == null || it.distanceToSqr(p) < best.distanceToSqr(p)) best = it;
		}
		return best;
	}

	// ---------- trampled farmland ----------

	/** Farmland seen, per block: a block of a field that was farmland and is dirt now was trampled. */
	private static final Map<BlockPos, Long> wasFarmland = new HashMap<>();
	private static long trampledAt;
	private static boolean trampledLast;

	/**
	 * The trampled cells of the field around this spot, with the seed to sow there: plain dirt with air above that was
	 * farmland at the last look, or that has farmland on three sides; the seed of the crop most grown beside it.
	 */
	private static Map<BlockPos, String> trampledOf(Level level, BlockPos center) {
		Map<BlockPos, String> out = new HashMap<>();
		long now = System.currentTimeMillis();
		for (BlockPos q : BlockPos.betweenClosed(center.offset(-7, -3, -7), center.offset(7, 3, 7))) {
			BlockState st = level.getBlockState(q);
			if (st.is(Blocks.FARMLAND)) { wasFarmland.put(q.immutable(), now); continue; }
			if (!st.is(Blocks.DIRT) || !level.getBlockState(q.above()).isAir()) continue;
			int sides = 0;
			Map<String, Integer> seeds = new HashMap<>();
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos n = q.relative(d);
				if (!level.getBlockState(n).is(Blocks.FARMLAND)) continue;
				sides++;
				String s = seedOf(level.getBlockState(n.above()));
				if (s != null) seeds.merge(s, 1, Integer::sum);
			}
			if (sides < 3 && !wasFarmland.containsKey(q)) continue;
			String seed = seeds.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
			if (seed == null) {
				// nothing grows beside it: what grows most in the field
				Map<String, Integer> all = new HashMap<>();
				for (BlockPos r : BlockPos.betweenClosed(center.offset(-7, -3, -7), center.offset(7, 3, 7))) {
					String s = seedOf(level.getBlockState(r));
					if (s != null && !s.equals("nether_wart")) all.merge(s, 1, Integer::sum);
				}
				seed = all.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
			}
			out.put(q.immutable(), seed);
		}
		return out;
	}

	/** A field of the base has trampled farmland (looked at every ten seconds): a hoe is wanted for it (Chores). */
	static boolean trampled() {
		long now = System.currentTimeMillis();
		if (now - trampledAt < 10_000) return trampledLast;
		trampledAt = now;
		trampledLast = false;
		LocalPlayer p = AfkHelperClient.mc().player;
		String owner = Follow.boss();
		if (p == null || owner == null) return false;
		loadFields();
		String server = server(), dim = dimOf(p.level()), who = owner.toLowerCase(Locale.ROOT);
		for (Field f : fields)
			if (f.server.equals(server) && f.dim.equals(dim) && f.owner.equals(who) && Vec3.atCenterOf(f.pos).distanceTo(p.position()) <= 48
					&& !trampledOf(p.level(), f.pos).isEmpty()) { trampledLast = true; break; }
		return trampledLast;
	}

	/** A usable hoe, not about to break. */
	private static boolean hasHoe(LocalPlayer p) {
		for (int i = 0; i < 36; i++) { ItemStack st = p.getInventory().getItem(i); if (st.is(ItemTags.HOES) && !nearlyBroken(st)) return true; }
		return false;
	}

	/** The nearest trampled cell of the round that is still dirt. */
	private static BlockPos nextTill(Level level) {
		// hoed: farmland now, its seed on the list to sow; anything else (a block on it, built over) dropped
		for (var it = round.till.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			BlockPos q = e.getKey();
			if (level.getBlockState(q).is(Blocks.FARMLAND)) {
				wasFarmland.put(q, System.currentTimeMillis());
				if (!e.getValue().isEmpty()) round.replant.put(q.above().immutable(), e.getValue());
				Exchange.emit("helper", "action", "field mended", "x", q.getX(), "y", q.getY(), "z", q.getZ(), "seed", e.getValue());
				it.remove();
			} else if (!level.getBlockState(q).is(Blocks.DIRT) || !level.getBlockState(q.above()).isAir()) it.remove();
		}
		LocalPlayer p = AfkHelperClient.mc().player;
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos q : round.till.keySet()) {
			double d = p.distanceToSqr(Vec3.atCenterOf(q));
			if (d < bestD) { bestD = d; best = q; }
		}
		return best;
	}

	/** The hoe on the top of this dirt, from a spot within reach; when it is farmland, its seed on the list to sow. */
	private static boolean tillCell(Minecraft mc, LocalPlayer p, BlockPos dirt, long now) {
		Level level = p.level();
		if (!dirt.equals(round.tillFor)) { round.tillFor = dirt.immutable(); round.tillSince = now; round.tillStand = null; }
		if (now - round.tillSince > 10_000) return false;
		Vec3 point = new Vec3(dirt.getX() + 0.5, dirt.getY() + 1.0, dirt.getZ() + 0.5);
		Vec3 eye = p.getEyePosition();
		BlockHitResult ray = level.clip(new ClipContext(eye, point.add(0, -0.07, 0), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
		boolean reach = eye.distanceTo(point) < REACH && ray.getType() == HitResult.Type.BLOCK && ray.getBlockPos().equals(dirt) && ray.getDirection() == Direction.UP;
		if (!reach) {
			// beside it, never on it: standing on farmland is fine, but the next cell may be trampled
			if (round.tillStand == null) round.tillStand = standCell(level, p, dirt, p, f -> !f.equals(dirt.above()));
			if (round.tillStand == null || !approach(mc, p, "farmland-" + dirt.toShortString(), Vec3.atBottomCenterOf(round.tillStand), now)) return false;
			return true;
		}
		Follow.stand();
		if (!p.getMainHandItem().is(ItemTags.HOES)) {
			AfkHelperClient.toHotbar(p, mc, s -> s.is(ItemTags.HOES) && !nearlyBroken(s), Comparator.comparingInt(ItemStack::getDamageValue), "hoe");
			for (int i = 0; i < 9; i++) if (p.getInventory().getItem(i).is(ItemTags.HOES)) { p.getInventory().setSelectedSlot(i); break; }
			return true;
		}
		Follow.aim(p, point.add(0, -0.07, 0));
		plantAt = dirt;
		return true;
	}

	/**
	 * The field's bare farmland onto the list to sow, with the seed most of the crops beside it grow from. A cell whose
	 * sowing failed once (the click did nothing, no way to it) left the list and stayed bare for good (the review of
	 * 22 Oct 2026); now every round looks again, but not within {@link #PLANT_RETRY} of a failure. Not beside a melon or
	 * pumpkin stem, whose fruit grows on the bare cell beside it, and not where no crop grows beside it at all.
	 */
	private static void addBare(Level level, long now) {
		plantFailed.values().removeIf(t -> now - t > PLANT_RETRY);
		int added = 0;
		for (BlockPos q : BlockPos.betweenClosed(round.center.offset(-7, -3, -7), round.center.offset(7, 3, 7))) {
			if (!level.getBlockState(q).is(Blocks.FARMLAND)) continue;
			BlockPos cell = q.above();
			if (!level.getBlockState(cell).isAir() || round.replant.containsKey(cell) || plantFailed.containsKey(cell)) continue;
			Map<String, Integer> beside = new HashMap<>();
			boolean stem = false;
			for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) continue;
				BlockState n = level.getBlockState(cell.offset(dx, 0, dz));
				if (n.is(Blocks.MELON_STEM) || n.is(Blocks.PUMPKIN_STEM) || n.is(Blocks.ATTACHED_MELON_STEM) || n.is(Blocks.ATTACHED_PUMPKIN_STEM)) stem = true;
				String s = seedOf(n);
				if (s != null && !s.equals("nether_wart")) beside.merge(s, 1, Integer::sum);
			}
			if (stem || beside.isEmpty()) continue;
			String seed = beside.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
			round.replant.put(cell.immutable(), seed);
			added++;
		}
		if (added > 0) Exchange.emit("helper", "action", "bare farmland", "count", added, "x", round.center.getX(), "y", round.center.getY(), "z", round.center.getZ());
	}

	/** A cell the alt harvested that is still empty, and whose seed it now has. */
	private static BlockPos nextReplant(Level level, LocalPlayer p) {
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (var e : round.replant.entrySet()) {
			if (!level.getBlockState(e.getKey()).isAir() || slotOf(p, e.getValue()) < 0) continue;
			double d = p.distanceToSqr(Vec3.atCenterOf(e.getKey()));
			if (d < bestD) { bestD = d; best = e.getKey(); }
		}
		return best;
	}

	private static int slotOf(LocalPlayer p, String id) {
		int slot = -1;
		for (int i = 0; i < 36 && slot < 0; i++) if (AfkHelperClient.itemId(p.getInventory().getItem(i)).equals(id)) slot = i;
		return slot;
	}

	/** The seed of the kind on the farmland (or soul sand) under this cell. True while busy with it. */
	private static boolean plantCell(Minecraft mc, LocalPlayer p, BlockPos cell, long now) {
		Level level = p.level();
		String seed = round.replant.get(cell);
		BlockState ground = level.getBlockState(cell.below());
		if (seed == null || !level.getBlockState(cell).isAir() || !(ground.is(Blocks.FARMLAND) || ground.is(Blocks.SOUL_SAND))) {
			round.replant.remove(cell);
			return false;
		}
		int slot = slotOf(p, seed);
		if (slot < 0) return false;   // picked up later, planted then
		if (!cell.equals(round.cellFor)) { round.cellFor = cell.immutable(); round.cellSince = now; }
		if (now - round.cellSince > 8000) { round.replant.remove(cell); plantFailed.put(cell.immutable(), now); return false; }
		Vec3 point = new Vec3(cell.getX() + 0.5, cell.getY() - 0.07, cell.getZ() + 0.5);
		Vec3 eye = p.getEyePosition();
		BlockHitResult ray = level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
		boolean reach = eye.distanceTo(point) < REACH && ray.getType() == HitResult.Type.BLOCK && ray.getBlockPos().equals(cell.below()) && ray.getDirection() == Direction.UP;
		if (!reach) {
			// standing on the cell itself, the farmland is straight underfoot: a seed goes in under your own feet
			if (!approach(mc, p, cell, Vec3.atBottomCenterOf(cell), now)) { round.replant.remove(cell); plantFailed.put(cell.immutable(), now); return false; }
			return true;
		}
		Follow.stand();
		if (slot >= 9) {
			AfkHelperClient.toHotbar(p, mc, s -> AfkHelperClient.itemId(s).equals(seed), Comparator.comparingInt(ItemStack::getCount), "seed");
			return true;
		}
		if (p.getInventory().getSelectedSlot() != slot) p.getInventory().setSelectedSlot(slot);
		Follow.aim(p, point);
		plantAt = cell.below();
		ourPlants.put(cell.immutable(), now);
		return true;
	}

	private static void endRound(String why) {
		if (round == null) return;
		round.field.last = System.currentTimeMillis();
		BlockPos fp = round.field.pos;
		// done within three seconds without a crop broken: the ripe ones are out of reach, and the next round would be the same
		if (why.equals("done") && round.field.last - round.startedAt < 3000 && round.replant.isEmpty() && round.broke == 0)
			Failures.failed(Failures.near("harvest", fp.getX(), fp.getY(), fp.getZ()), "nothing to harvest", fp.getX(), fp.getY(), fp.getZ());
		else if (round.broke > 0) Failures.succeeded(Failures.near("harvest", fp.getX(), fp.getY(), fp.getZ()));
		Exchange.emit("helper", "action", "harvest done", "reason", why, "x", round.center.getX(), "y", round.center.getY(), "z", round.center.getZ(),
			"not_planted", round.replant.size());
		if (!round.field.owner.equals("test")) Dump.jobDone();
		round = null;
		digging = null;
		stand = null;
	}

	/** The server's profile (see Profiles): a field is known for the server it lies on. */
	private static String server() { return Profiles.key(); }

	/** Forget every field of this player on this server; how many. */
	static int forgetFields(String player) {
		loadFields();
		String server = server(), who = player.toLowerCase(Locale.ROOT);
		int before = fields.size();
		fields.removeIf(f -> f.server.equals(server) && f.owner.equals(who));
		saveFields();
		if (round != null) endRound("forgotten");
		return before - fields.size();
	}

	private static Path fieldsFile() { return Paths.get(AfkConfig.get().exchangeDir).resolve("fields.json"); }

	private static void loadFields() {
		if (fieldsLoaded) return;
		fieldsLoaded = true;
		try {
			Path f = fieldsFile();
			if (!Files.exists(f)) return;
			JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
			for (JsonElement el : root.getAsJsonArray("fields")) {
				JsonObject o = el.getAsJsonObject();
				Field v = new Field();
				v.server = Profiles.migrate(o.get("server").getAsString());
				v.dim = o.get("dim").getAsString();
				v.owner = o.get("owner").getAsString();
				v.pos = new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
				fields.add(v);
			}
		} catch (Exception e) {
			Exchange.emit("error", "msg", "fields not read: " + e.getMessage());
		}
	}

	private static void saveFields() {
		JsonArray a = new JsonArray();
		for (Field v : fields) {
			if (v.owner.equals("test")) continue;
			JsonObject o = new JsonObject();
			o.addProperty("server", v.server);
			o.addProperty("dim", v.dim);
			o.addProperty("owner", v.owner);
			o.addProperty("x", v.pos.getX());
			o.addProperty("y", v.pos.getY());
			o.addProperty("z", v.pos.getZ());
			a.add(o);
		}
		JsonObject root = new JsonObject();
		root.add("fields", a);
		try {
			Path f = fieldsFile(), tmp = f.resolveSibling("fields.json.tmp");
			Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(root));
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			Exchange.emit("error", "msg", "fields not saved: " + e.getMessage());
		}
	}

	/** {@code {"cmd":"fields"}}: which known fields of the player being worked for; "forget" forgets them. */
	static void fieldsCommand(JsonObject o) {
		loadFields();
		String owner = Follow.boss();
		String server = server(), who = owner == null ? null : owner.toLowerCase(Locale.ROOT);
		if (o.has("forget") && o.get("forget").getAsBoolean() && who != null) {
			fields.removeIf(f -> f.server.equals(server) && f.owner.equals(who));
			saveFields();
			endRound("forgotten");
		}
		List<String> list = new ArrayList<>();
		for (Field f : fields) if (f.server.equals(server) && f.owner.equals(who)) list.add(f.dim.replace("minecraft:", "") + " " + f.pos.toShortString());
		Exchange.emit("fields", "for", owner == null ? "" : owner, "count", list.size(), "list", String.join("; ", list));
	}

	// ---------- the buddy's own drops ----------

	/** What the buddy broke fell somewhere: pick it up, if it is still there and not underfoot. */
	// ---------- walking ----------

	private static Object walkKey;
	private static double walkBest;
	private static long walkBestAt;
	private static int walkCells;
	/** Cells a walk got no closer to: not chosen to stand in for a minute. */
	private static final Map<BlockPos, Long> badStand = new HashMap<>();

	/** A cell that got the buddy nowhere: no spot for a minute (a chore's own spots, which are no standCell). */
	static void badStand(BlockPos c) { badStand.put(c.immutable(), System.currentTimeMillis() + 60_000); }

	/** The cells kept as bad stands (RunMemories). */
	static int badStands() { return badStand.size(); }

	static void forgetBadStands() { badStand.clear(); }

	static boolean badStandAt(BlockPos c) { Long until = badStand.get(c); return until != null && until > System.currentTimeMillis(); }

	/**
	 * Walk toward a goal (the key says which: a cell, an item's number). False when four seconds went by without getting
	 * half a block closer: there is no way there (after the alt stood still by a tree for 25 s).
	 */
	/**
	 * A walk's clock anew: its best distance and the time of it start again, as for a new goal. For a walk that begins
	 * (each of the pen's walks: a spot, the retreat, the night, the step back, a creeper, cover), whose fixed key would
	 * otherwise carry the last walk's record (the third review of 22 Oct 2026: a second cover the same night failed on
	 * its first tick, "komt er niet", and lifted the stay), and after a pause on the way (eating).
	 */
	static void walkFresh() { walkKey = null; }

	/**
	 * The name a failed walk goes by in {@link Failures}: what it was for (a string key's word before its first dash,
	 * "cell" for a cell to stand in, "thing" for an item) and the three-block cube of its goal. Without the purpose a
	 * shearing round that could not reach the cells at the far end of the pen blocked that cube for ten minutes, and the
	 * herding round that came after could not lead its stray there (23 Oct 2026; the review of 22 Oct had it: "walk
	 * failures are keyed by a 3-block cube without purpose").
	 */
	static String walkName(Object key, double x, double y, double z) {
		String what = key instanceof String s ? (s.indexOf('-') > 0 ? s.substring(0, s.indexOf('-')) : s) : key instanceof BlockPos ? "cell" : "thing";
		return Failures.near("walk " + what, x, y, z);
	}

	static boolean approach(Minecraft mc, LocalPlayer p, Object key, Vec3 goal, long now) {
		// in bed nobody walks: no failure counted for the goal (the bed is left by the morning or by sleep())
		if (p.isSleeping()) { walkKey = null; return true; }
		double d = p.position().distanceTo(goal);
		// a goal not reached twice is left alone for a while (Failures): no walking there every few seconds
		String goalName = walkName(key, goal.x, goal.y, goal.z);
		if (Failures.blocked(goalName)) return false;
		if (d < 2) Failures.succeeded(goalName);
		if (!key.equals(walkKey)) { walkKey = key; walkBest = d; walkBestAt = now; walkCells = Follow.cellsWalked(); }
		if (d < walkBest - 0.5) { walkBest = d; walkBestAt = now; }
		// on along the planned route is getting there too, also when the way goes off first: up out of the farm strip and
		// down the stairs to a chest of the cellar the buddy gave up after four seconds, the goal blocked ten minutes (22 Oct 2026)
		if (Follow.cellsWalked() != walkCells) { walkCells = Follow.cellsWalked(); walkBestAt = now; }
		// opening a door on the way (turning to it, the click, the server's answer) is no standing still (21 Oct 2026:
		// in the base the buddy gave up in front of the buddy's own door, "cannot get there" for the cell beside the bed)
		if (Follow.atDoor()) walkBestAt = now;
		if (now - walkBestAt > 4000) {
			// close and no nearer: that is there, as near as the room allows (beside a bed, in a corner by a chest), and
			// that is no failure (21 Oct 2026: 189 "cannot get there" in a night, most of them a step from the goal)
			if (Math.hypot(p.getX() - goal.x, p.getZ() - goal.z) <= 1.6 && Math.abs(p.getY() - goal.y) <= 1.1) {
				Failures.succeeded(goalName);
				Follow.stand();
				return true;
			}
			walkKey = null;
			if (key instanceof BlockPos b) badStand.put(b, now + 60_000);
			Exchange.emit("helper", "action", "cannot get there", "x", (int) Math.floor(goal.x), "y", (int) Math.floor(goal.y), "z", (int) Math.floor(goal.z),
				"for", String.valueOf(key), "doing", Arbiter.doing(), "distance", Math.round(d * 10) / 10.0,
				"from", p.getBlockX() + "," + p.getBlockY() + "," + p.getBlockZ());
			Failures.failed(goalName, "cannot get there", (int) Math.floor(goal.x), (int) Math.floor(goal.y), (int) Math.floor(goal.z));
			return false;
		}
		// an order of items walks with Baritone when it is there (Bari); while it has a path or works one out, that is no
		// standing still
		if (Obtain.on() && Bari.on()) { Bari.goTo(goal, 1); if (Bari.busy()) walkBestAt = now; }
		else Follow.goTo(mc, p, goal);
		return true;
	}

	private static boolean collect(Minecraft mc, LocalPlayer p, long now) {
		if (mine.isEmpty()) return false;
		if (fetching != null && (!fetching.isAlive() || now - fetchSince > 6000)) {
			if (fetching.isAlive()) gaveUp.add(fetching.getId());
			fetching = null;
		}
		if (fetching == null) {
			if (Follow.ticks() % 10 != 0 || freeSlots(p) == 0) return false;
			for (ItemEntity it : p.level().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(6, 3, 6))) {
				if (gaveUp.contains(it.getId())) continue;
				boolean ours = false;
				for (BlockPos m : mine.keySet()) if (it.position().distanceToSqr(Vec3.atCenterOf(m)) < 2.5) { ours = true; break; }
				if (ours && (fetching == null || it.distanceToSqr(p) < fetching.distanceToSqr(p))) fetching = it;
			}
			if (fetching == null) return false;
			fetchSince = now;
		}
		if (!approach(mc, p, fetching.getId(), fetching.position(), now)) { gaveUp.add(fetching.getId()); fetching = null; return false; }
		return true;
	}

	// ---------- breaking ----------

	static final int HAND = -1, NONE = -2;

	/**
	 * Break this block: from where the alt stands on reaching it, otherwise from a cell the test allows first.
	 * True while busy with it; false when it gave the block up (it will not be asked again for a while).
	 */
	private static boolean dig(Minecraft mc, LocalPlayer p, BlockPos target, Player t, Predicate<BlockPos> standOk) {
		Level level = p.level();
		long now = System.currentTimeMillis();
		BlockState st = level.getBlockState(target);
		if (gone(level, target)) return true;
		int tool = toolFor(p, st);
		if (tool == NONE) {
			lacking(st);
			skipped.put(target, now + 120_000);
			return false;
		}
		byHand(p, st);
		Vec3 eye = p.getEyePosition();
		Vec3 point = aimPoint(level, p, eye, target);
		if (point == null) {
			if (!target.equals(standFor) || stand == null) {
				standFor = target;
				stand = standCell(level, p, target, t, standOk);
				digSince = now;
				if (stand == null) { skipped.put(target, now + 60_000); return false; }
			}
			if (now - digSince > 20_000) { skipped.put(target, now + 60_000); stand = null; return false; }
			if (!approach(mc, p, stand, Vec3.atBottomCenterOf(stand), now)) {
				// no way to that cell: another one, or the block is given up
				stand = standCell(level, p, target, t, standOk);
				if (stand == null) { skipped.put(target, now + 60_000); return false; }
			}
			return true;
		}
		Follow.stand();
		stand = null;
		if (!select(mc, p, tool)) return true;
		Follow.aim(p, point);
		if (!target.equals(digging)) {
			float progress = st.getDestroyProgress(p, level, target);
			if (progress <= 0 || 1 / progress > 600) { skipped.put(target, now + 300_000); return false; }   // over half a minute: not the buddy's to do
			digging = target.immutable();
			digSince = now;
			digLimit = (long) (1 / progress * 50 * 2.5) + 4000;
		}
		if (now - digSince > digLimit) {
			// what was in sight, to tell a protected block from an aim that never lands (21 Oct 2026: many of these
			// in the owner's field while the buddy stood there)
			Exchange.emit("helper", "action", "does not break", "x", target.getX(), "y", target.getY(), "z", target.getZ(),
				"block", BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath(), "sees", Work.sight(mc), "hits", hitsOn(target));
			skipped.put(target, now + 120_000);
			digging = null;
			return false;
		}
		digAt = target;
		return true;
	}

	/**
	 * A block in the way of the buddy's walk (PathShape's dig): the fastest tool in hand, the crosshair on an open face, the
	 * hit at the start of the next tick. True while it goes; false when that cannot be (no tool for it, no face in reach).
	 */
	static boolean breakForWalk(Minecraft mc, LocalPlayer p, BlockPos target) {
		Level level = p.level();
		BlockState st = level.getBlockState(target);
		int tool = toolFor(p, st);
		if (tool == NONE) { lacking(st); return false; }
		Vec3 point = aimPoint(level, p, p.getEyePosition(), target);
		if (point == null) return false;
		if (!select(mc, p, tool)) return true;
		Follow.aim(p, point);
		digging = target.immutable();
		digAt = target;
		return true;
	}

	private static BlockPos hitTarget;
	private static int hitCount;

	/** How many ticks of breaking really went to this block (0: the crosshair never was on it at the start of a tick). */
	private static int hitsOn(BlockPos target) { return target.equals(hitTarget) ? hitCount : 0; }

	/** What the crosshair is on, as "block x,y,z face", "entity", or "nothing". */
	static String sight(Minecraft mc) {
		if (mc.hitResult instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK && mc.level != null)
			return BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(bh.getBlockPos()).getBlock()).getPath() + " "
				+ bh.getBlockPos().getX() + "," + bh.getBlockPos().getY() + "," + bh.getBlockPos().getZ() + " " + bh.getDirection().getName();
		if (mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult eh) return "entity " + BuiltInRegistries.ENTITY_TYPE.getKey(eh.getEntity().getType()).getPath();
		// a miss beside the pick's own: an entity in front beyond the reach for entities (an item frame on a chest)
		if (mc.hitResult instanceof BlockHitResult bh && mc.level != null) {
			var f = mc.level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class, new AABB(bh.getBlockPos()).inflate(1));
			if (!f.isEmpty()) return "nothing, item frame near " + bh.getBlockPos().getX() + "," + bh.getBlockPos().getY() + "," + bh.getBlockPos().getZ();
		}
		return "nothing";
	}

	/** Nothing left to break here: air or water, no crop either. */
	private static boolean gone(Level level, BlockPos pos) {
		BlockState st = level.getBlockState(pos);
		return st.getCollisionShape(level, pos).isEmpty() && !planted(st) && !st.is(BlockTags.LEAVES);
	}

	/**
	 * Wood or earth broken by hand for want of an axe or shovel, none in a known chest either: a need
	 * all the same (0.4.41; until then only a block without drops was reported). It does
	 * not stop the work: by hand it goes, only slower.
	 */
	private static void byHand(LocalPlayer p, BlockState st) {
		if (st.is(BlockTags.MINEABLE_WITH_PICKAXE)) return;
		boolean axe = st.is(BlockTags.MINEABLE_WITH_AXE), shovel = !axe && st.is(BlockTags.MINEABLE_WITH_SHOVEL);
		if (!axe && !shovel) return;
		var inv = p.getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && !nearlyBroken(s) && s.is(axe ? ItemTags.AXES : ItemTags.SHOVELS)) return;
		}
		if (Stash.has(id -> Stash.allowed(id) && (axe ? id.endsWith("_axe") && !id.endsWith("pickaxe") : id.endsWith("_shovel")))) return;
		String name = plainName(st);
		if (axe) Needs.want("axe", "{me} has no axe and now chops " + name + " by hand.");
		else Needs.want("shovel", "{me} has no shovel and now digs " + name + " by hand.");
	}

	/**
	 * A block's name in English whatever the game's language: its id without the namespace, with spaces ("diamond ore").
	 * The game's own name follows the language the buddy's game is set to, which put Dutch block names in the needs (31 Oct 2026).
	 */
	static String plainName(BlockState st) {
		return BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath().replace('_', ' ');
	}

	/** A block only a missing tool gives: a need, with the block's name. */
	private static void lacking(BlockState st) {
		String name = plainName(st);
		boolean pick = st.is(BlockTags.MINEABLE_WITH_PICKAXE);
		boolean hasPick = false;
		LocalPlayer p = AfkHelperClient.mc().player;
		if (p != null) for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).is(ItemTags.PICKAXES)) hasPick = true;
		if (pick && hasPick) Needs.want("better_pickaxe", "{me} needs a better pickaxe for " + name + ".");
		else if (pick) Needs.want("pickaxe", "{me} has no pickaxe to break " + name + ".");
		else if (st.is(BlockTags.MINEABLE_WITH_AXE)) Needs.want("axe", "{me} has no axe for " + name + ".");
		else if (st.is(BlockTags.MINEABLE_WITH_SHOVEL)) Needs.want("shovel", "{me} has no shovel for " + name + ".");
		else Needs.want("tool", "{me} has no tool for " + name + ".");
	}

	/**
	 * Slot 0-35 of the fastest tool for this block that is not about to break, {@link #HAND} if the hand does as well,
	 * {@link #NONE} if only a missing tool gives the drop.
	 */
	static int toolFor(LocalPlayer p, BlockState st) {
		int slot = toolFor(p, st, false);
		// on an order a tool is used to its last blow rather than kept for a repair: shut in a shelter with a stone pickaxe
		// of three uses, it could break no wall for a table and no way out
		if (slot == NONE && Obtain.on()) slot = toolFor(p, st, true);
		return slot;
	}

	private static int toolFor(LocalPlayer p, BlockState st, boolean lastUses) {
		if (Obtain.on()) return cheapestFor(p, st, lastUses);
		var inv = p.getInventory();
		boolean need = st.requiresCorrectToolForDrops();
		int slot = need ? NONE : HAND;
		float best = need ? 0f : 1f;
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || (nearlyBroken(s) && !lastUses)) continue;
			// a weapon is for fighting: a sword through leaves cost the test's sword ninety uses (17 Oct 2026)
			if (s.is(ItemTags.SWORDS) || s.is(Items.MACE) || s.is(Items.TRIDENT)) continue;
			// shears take a leaf, a vine or a plant whole, so no sapling, apple, stick or seed falls: only for wool and
			// cobwebs (the review of 22 Oct 2026)
			if (s.is(Items.SHEARS) && !st.is(BlockTags.WOOL) && !st.is(Blocks.COBWEB)) continue;
			if (need && !s.isCorrectToolForDrops(st)) continue;
			float speed = s.getDestroySpeed(st);
			// of equally fast tools the most worn first, so a new one that is carried waits until the old one is used up;
			// then the hotbar
			boolean same = speed == best && slot >= 0, worn = same && left(s) < left(inv.getItem(slot));
			if (speed > best || worn || (same && left(s) == left(inv.getItem(slot)) && slot >= 9 && i < 9)) { best = speed; slot = i; }
		}
		return slot;
	}

	/**
	 * On an order, of the tools that are right for the block the one of the cheapest material first, and of one material the
	 * most worn, as Soprano's Baritone does (its toolWearChain): stone before copper before iron before diamond before
	 * netherite, wood and gold last. The fastest one first wore the only iron pickaxe out on the stone of the staircase to the
	 * diamonds with two stone pickaxes in the bag, and the diamonds could not be mined.
	 */
	private static int cheapestFor(LocalPlayer p, BlockState st, boolean lastUses) {
		var inv = p.getInventory();
		boolean need = st.requiresCorrectToolForDrops();
		int slot = need ? NONE : HAND, rank = Integer.MAX_VALUE, uses = Integer.MAX_VALUE;
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || (nearlyBroken(s) && !lastUses)) continue;
			if (s.is(ItemTags.SWORDS) || s.is(Items.MACE) || s.is(Items.TRIDENT)) continue;
			if (s.is(Items.SHEARS) && !st.is(BlockTags.WOOL) && !st.is(Blocks.COBWEB)) continue;
			if (need && !s.isCorrectToolForDrops(st)) continue;
			// the right kind of tool only: a pickaxe on dirt is no faster than the hand
			if (s.getDestroySpeed(st) <= 1f) continue;
			int r = material(AfkHelperClient.itemId(s)), l = left(s);
			if (r < rank || r == rank && (l < uses || l == uses && slot >= 9 && i < 9)) { rank = r; uses = l; slot = i; }
		}
		return slot;
	}

	/** Soprano's order of materials for wearing tools out: the cheap first, wood and gold last. */
	private static int material(String id) {
		if (id.startsWith("stone_")) return 0;
		if (id.startsWith("copper_")) return 1;
		if (id.startsWith("iron_")) return 2;
		if (id.startsWith("diamond_")) return 3;
		if (id.startsWith("netherite_")) return 4;
		if (id.startsWith("wooden_")) return 5;
		if (id.startsWith("golden_")) return 6;
		return 7;
	}

	/** Uses left before it breaks; an item that does not wear has all the uses in the world. */
	private static int left(ItemStack s) { return s.isDamageableItem() ? s.getMaxDamage() - s.getDamageValue() : Integer.MAX_VALUE; }

	/**
	 * Three uses left or fewer and worth a repair: kept whole. Only with an enchantment on it that is no curse: a plain one
	 * is used up and a new one made.
	 */
	static boolean nearlyBroken(ItemStack s) {
		return s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() <= 3 && worthKeeping(s);
	}

	/** An enchantment on it that is no curse. */
	static boolean worthKeeping(ItemStack s) {
		for (var e : s.getEnchantments().entrySet())
			if (!e.getKey().is(net.minecraft.tags.EnchantmentTags.CURSE)) return true;
		return false;
	}

	/**
	 * Nearly worn out: a tenth of its durability or less left, ten uses at least. With {@code baseRenew} a new iron one is made
	 * then, while there is enough iron (a switch in the panel).
	 */
	static boolean lowWear(ItemStack s) {
		return s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() <= Math.max(10, s.getMaxDamage() / 10);
	}

	/** The tool in hand; from the backpack it goes to the hotbar first (and the hit waits a tick). */
	private static boolean select(Minecraft mc, LocalPlayer p, int tool) {
		// (the autopilot's meal_hand_hold) a meal under way keeps the hand: the dig waits for the swallow
		if (ObtainSteps.mealHold(p)) return false;
		var inv = p.getInventory();
		if (tool == HAND) {
			int h = inv.getSelectedSlot();
			if (inv.getItem(h).isDamageableItem()) for (int i = 0; i < 9; i++) if (!inv.getItem(i).isDamageableItem()) { h = i; break; }
			if (inv.getSelectedSlot() != h) inv.setSelectedSlot(h);
			return true;
		}
		if (tool < 9) {
			if (inv.getSelectedSlot() != tool) inv.setSelectedSlot(tool);
			return true;
		}
		ItemStack want = inv.getItem(tool).copy();
		AfkHelperClient.toHotbar(p, mc, s -> ItemStack.isSameItemSameComponents(s, want), Comparator.comparingInt(s -> 0), "tool");
		return false;
	}

	/** Nothing with something inside it, nothing that cannot be broken, nothing water or lava would run in after. */
	static boolean safe(Level level, BlockPos pos) {
		BlockState st = level.getBlockState(pos);
		if (st.getDestroySpeed(level, pos) < 0 || level.getBlockEntity(pos) != null || !st.getFluidState().isEmpty()) return false;
		for (Direction d : Direction.values()) if (!level.getBlockState(pos.relative(d)).getFluidState().isEmpty()) return false;
		return true;
	}

	/**
	 * A point on the block the crosshair lands on from this eye: the middle of a face with nothing in front of it that
	 * looks toward the eye, with nothing in between, within reach. The nearest, or null.
	 */
	/**
	 * An item frame on the way to this point: the game's pick takes it instead of the block behind it at any distance
	 * within the block reach (GameRenderer.pick looks for entities as far as for blocks); within the reach for entities a
	 * click turns the thing in it, beyond it the crosshair hits nothing at all. The base's cellar has frames on 45 of its chests,
	 * each covering all of the front but a sixteenth at its edges, and from 4.1 blocks the chest never opened: a
	 * right-click has to be very precise or it lands in the frame.
	 */
	static boolean framed(Level level, LocalPlayer p, Vec3 eye, Vec3 pt) {
		for (net.minecraft.world.entity.decoration.ItemFrame f : level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class, new AABB(eye, pt).inflate(0.2)))
			if (f.getBoundingBox().clip(eye, pt).isPresent()) return true;
		return false;
	}

	static Vec3 aimPoint(Level level, LocalPlayer p, Vec3 eye, BlockPos target) { return aimPoint(level, p, eye, target, false); }

	/**
	 * A mob, an animal or a player on the way to this point: the game's pick takes what it may pick in front of the block
	 * (GameRenderer.pick, by its pick radius), within the entity reach the thing itself, beyond it nothing at all.
	 */
	static boolean behindEntity(Level level, LocalPlayer p, Vec3 eye, Vec3 pt) {
		for (net.minecraft.world.entity.Entity e : level.getEntities(p, new AABB(eye, pt).inflate(1.0), e -> !e.isSpectator() && e.isPickable()))
			if (e.getBoundingBox().inflate(e.getPickRadius()).clip(eye, pt).isPresent()) return true;
		return false;
	}

	/** As {@link #aimPoint(Level, LocalPlayer, Vec3, BlockPos)}; with {@code clearOfEntities} no point behind a mob or a player. */
	static Vec3 aimPoint(Level level, LocalPlayer p, Vec3 eye, BlockPos target, boolean clearOfEntities) {
		Vec3 best = null;
		double bestD = REACH * REACH;
		// on the block's own outline: a ripe carrot is half a block high, and the middle of the cell is above it
		VoxelShape shape = level.getBlockState(target).getShape(level, target);
		AABB b = shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds();
		double cx = (b.minX + b.maxX) / 2, cy = (b.minY + b.maxY) / 2, cz = (b.minZ + b.maxZ) / 2;
		for (Direction d : Direction.values()) {
			BlockPos n = target.relative(d);
			boolean flush = switch (d) {
				case UP -> b.maxY > 0.99; case DOWN -> b.minY < 0.01;
				case EAST -> b.maxX > 0.99; case WEST -> b.minX < 0.01;
				case SOUTH -> b.maxZ > 0.99; default -> b.minZ < 0.01;
			};
			if (flush && !level.getBlockState(n).getCollisionShape(level, n).isEmpty()) continue;
			double x = d.getStepX() > 0 ? b.maxX - 0.03 : d.getStepX() < 0 ? b.minX + 0.03 : cx;
			double z = d.getStepZ() > 0 ? b.maxZ - 0.03 : d.getStepZ() < 0 ? b.minZ + 0.03 : cz;
			// a side face at mid height first; then high and low on it, for a block that is narrow in the middle
			// (a hopper's funnel: the ray at mid height passes beside it and the hopper could not be opened, 21 Oct 2026);
			// then its edges, the seam between two item frames and the strip under one, where a player clicks a chest that
			// has a frame on it
			double[][] spots;   // {x, y, z} on the face
			if (d.getStepY() != 0) spots = new double[][] { { cx, d.getStepY() > 0 ? b.maxY - 0.03 : b.minY + 0.03, cz } };
			else {
				boolean alongX = d.getStepZ() != 0;   // the face's sideways axis
				double lo = (alongX ? b.minX : b.minZ) + 0.03, hi = (alongX ? b.maxX : b.maxZ) - 0.03, mid = alongX ? cx : cz;
				double[] side = { mid, mid, mid, mid, lo, hi, lo, hi }, up = { cy, b.maxY - 0.1, b.minY + 0.1, b.minY + 0.03, cy, cy, b.minY + 0.03, b.minY + 0.03 };
				spots = new double[side.length][];
				for (int i = 0; i < side.length; i++) spots[i] = alongX ? new double[] { side[i], up[i], z } : new double[] { x, up[i], side[i] };
			}
			for (double[] s : spots) {
				Vec3 pt = new Vec3(target.getX() + s[0], target.getY() + s[1], target.getZ() + s[2]);
				double dd = eye.distanceToSqr(pt);
				if (dd >= bestD) continue;
				Vec3 to = eye.subtract(pt);
				if (to.x * d.getStepX() + to.y * d.getStepY() + to.z * d.getStepZ() <= 0) continue;
				BlockHitResult hit = level.clip(new ClipContext(eye, pt, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
				if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(target)) continue;
				if (framed(level, p, eye, pt)) continue;
				if (clearOfEntities && behindEntity(level, p, eye, pt)) continue;
				best = pt;
				bestD = dd;
				break;
			}
		}
		return best;
	}

	/**
	 * A cell near the block to stand in and reach it from: one the test allows, not where the player stands, nearest to
	 * the buddy. First one from which the block is in reach wherever in the cell the buddy stops ({@link #CELL_SLACK}
	 * inside {@link #REACH}), only then one in reach from its middle alone: on 22 Oct 2026 the buddy stopped off the
	 * middle of such a cell, the chest 4.1 off, and the click never went.
	 */
	static BlockPos standCell(Level level, LocalPlayer p, BlockPos target, Player t, Predicate<BlockPos> ok) {
		BlockPos best = standCell(level, p, target, t, ok, REACH - CELL_SLACK);
		return best != null ? best : standCell(level, p, target, t, ok, REACH);
	}

	/**
	 * A door's or a gate's own cell: walked through but never stopped in to work, or it cannot be shut behind the buddy and
	 * the animals of a pen walk out past. The buddy chose the open doorway of the sheep pen as the cell to shear from and stood
	 * there seven seconds while three sheep went out (the test server's copy of the base, 22 Oct 2026; a chest under the
	 * door, so a chest round could have chosen it as well).
	 */
	static boolean inDoorway(Level level, BlockPos c) {
		var b = level.getBlockState(c).getBlock();
		return b instanceof net.minecraft.world.level.block.DoorBlock || b instanceof net.minecraft.world.level.block.FenceGateBlock;
	}

	/** Half a cell's diagonal: how far off its middle a spot in it can be. */
	static final double CELL_SLACK = 0.71;

	private static BlockPos standCell(Level level, LocalPlayer p, BlockPos target, Player t, Predicate<BlockPos> ok, double reach) {
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -4; dy <= 1; dy++) {
			BlockPos f = target.offset(dx, dy, dz);
			if (!ok.test(f) || badStand.containsKey(f) || inDoorway(level, f)) continue;
			double feet = Follow.floor(level, f);
			if (Double.isNaN(feet)) continue;
			if (t != null && Math.hypot(f.getX() + 0.5 - t.getX(), f.getZ() + 0.5 - t.getZ()) < 1.0 && Math.abs(feet - t.getY()) < 1.5) continue;
			double d = p.position().distanceToSqr(Vec3.atBottomCenterOf(f));
			if (d >= bestD) continue;
			Vec3 eye = new Vec3(f.getX() + 0.5, feet + p.getEyeHeight(), f.getZ() + 0.5);
			Vec3 pt = aimPoint(level, p, eye, target);
			if (pt == null || eye.distanceTo(pt) > reach) continue;
			best = f.immutable();
			bestD = d;
		}
		return best;
	}

	static int freeSlots(LocalPlayer p) {
		int n = 0;
		for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) n++;
		return n;
	}

	// ---------- what the buddy lacks ----------

	/** Every five seconds: food, a weapon, room, a tool about to break. */
	private static void needs(LocalPlayer p) {
		var inv = p.getInventory();
		int food = AfkHelperClient.countFood(p, 0, 36);
		Needs.set("food", food == 0 ? "{me} has no food left." : "{me} is almost out of food (" + food + " left).",
			food < 3 && !Stash.hasFood());
		boolean armed = false;
		for (int i = 0; i < 36; i++) if (Guard.isWeapon(inv.getItem(i))) armed = true;
		Needs.set("wood", "{me} has no wood for sticks and finds no tree in the base to chop.", !woodTree && Chores.woodWanted(p));
		Needs.set("weapon", "{me} has no weapon to guard you with.", Guard.on() && !armed && !Stash.has(id -> id.endsWith("_sword") && Stash.allowed(id)));
		// two and a half game days without a bed outside a base: phantoms come at three (Rest; in a base the buddy goes to bed unprompted)
		int rest = Rest.estimate();
		Needs.set("rest", "{me} has not slept for " + String.format(java.util.Locale.ROOT, "%.1f", rest / 24000.0) + " game days; phantoms come after three.",
			rest >= Rest.WARN && Base.keeper() == null);
		// a bow and arrows for what is out of reach (Bow): none carried, in a chest, or to make
		boolean bow = Chores.hasBow(p);
		Needs.set("bow", "{me} has no bow for phantoms and skeletons out of reach, and makes one from three string.",
			Guard.on() && !bow && !Stash.chestHasTool("bow") && !Chores.bowToMake(p) && Base.keeper() != null);
		Needs.set("arrows", "{me} has a bow but no arrows, and makes them from flint, sticks and feathers.",
			Guard.on() && bow && !Bow.armed(p) && !Stash.has(id -> id.endsWith("arrow")) && !Chores.arrowsToMake(p));
		Needs.set("pen_weapon", "{me} has no weapon for the mob farm and hits with bare fists.", Pen.on() && !armed);
		Needs.set("full", "{me}'s inventory is full" + (Stash.count() > 0 ? " and your chests have no room for it." : "."),
			freeSlots(p) == 0 && !Stash.hasRoom(p));
		wearing(p, ItemTags.PICKAXES, "pickaxe");
		wearing(p, ItemTags.AXES, "axe");
		wearing(p, ItemTags.SHOVELS, "shovel");
		wearing(p, ItemTags.SWORDS, "sword");
	}

	/** The best tool of a kind about to break, and no other of it to fall back on. */
	private static void wearing(LocalPlayer p, net.minecraft.tags.TagKey<net.minecraft.world.item.Item> kind, String name) {
		int best = -1, max = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (!s.is(kind)) continue;
			int left = s.getMaxDamage() - s.getDamageValue();
			if (left > best) { best = left; max = s.getMaxDamage(); }
		}
		boolean low = best >= 0 && best <= Math.max(10, max / 20);
		// not while a trip home makes a new one, or could (Renew)
		Needs.set("broken_" + name, "{me}'s " + name + " is almost broken (" + Math.max(0, best) + " uses left).",
			low && !Stash.has(id -> id.endsWith("_" + name(kind)) && Stash.allowed(id)) && !Renew.handles(p, name));
	}

	private static String name(net.minecraft.tags.TagKey<net.minecraft.world.item.Item> kind) {
		return kind == ItemTags.PICKAXES ? "pickaxe" : kind == ItemTags.AXES ? "axe" : kind == ItemTags.SWORDS ? "sword" : "shovel";
	}
}
