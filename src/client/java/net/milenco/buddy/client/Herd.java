package net.milenco.buddy.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * An animal that got out of its pen, brought back with wheat in hand: the buddy carries wheat to lure escaped sheep
 * back to their pen.
 * A chore of the base ({@link Chores}, kind HERD).
 * <ul>
 * <li><b>A pen</b>: the closed space behind a fence gate or a wooden door within reach of him, the other side open
 * ground: an animal walks from the cell on one side of it to at most {@link #LIMIT} cells, through no gate or door, and
 * from the other side to more. Its kind is what most of the animals in it are, and stays known while it is empty.</li>
 * <li><b>A stray</b>: an animal that wheat tempts (a sheep, a cow, a mooshroom, a goat) in the base, on open ground, in
 * no pen, not on a lead, with a pen of its kind within {@link #FROM_GATE}.</li>
 * <li><b>The round</b>: to the stray, wheat in hand from {@link #LURE} (the game's animals follow a player holding their
 * food from ten blocks); to the gate's outside cell, waiting while it lags and until it is close; through the gate,
 * which is held open ({@link Follow#holdOpen}), to the pen's far end, so that the pen's own animals crowd round him there
 * and not behind the gate; the wheat out of hand the moment the stray is in, the gate shut from the cell just inside it,
 * and out again, the gate shut behind him as always. The game makes an animal that stopped following wait five seconds
 * before it follows again (TemptGoal's calm-down), so a stray that is lost is gone after for a fresh start, three times
 * at most.</li>
 * </ul>
 * He carries wheat for it always: Stash keeps one stack of it and fetches one when he has none ({@link Chores#wants}).
 */
final class Herd {
	private Herd() {}

	/** A closed space holds at most this many cells; past it the side of a gate is open ground. */
	static final int LIMIT = 256;
	/** Gates looked for this far round him; strays anywhere in the base, no farther from their pen's gate than FROM_GATE. */
	static final int SCAN_R = 32;
	static final double FROM_GATE = 32;
	/** Wheat in hand from this near to the animal; led on while it stays within LAG, gone after again past LOST. */
	static final double LURE = 9, CLOSE = 4, LAG = 6, LOST = 11;
	/** Inside: this far from the gate's middle at least, before he lets go of the wheat. */
	static final double INSIDE = 1.6;
	static final long SCAN_EVERY = 30_000, SKIP_FOR = 600_000, ROUND_MAX = 120_000, WAIT_MAX = 15_000, OUT_MAX = 8000;
	/** In the pen: how long he waits for the stray to come in; for the gate to shut. */
	static final long CALL_MAX = 15_000, SHUT_MAX = 10_000;
	static final int TRIES = 3;

	/** A pen: its gate, the cells on either side of it, the cells inside (column to feet height) and its far end. */
	record Pen(BlockPos gate, BlockPos inside, BlockPos outside, Map<Long, Integer> cells, BlockPos far) {
		boolean holds(Vec3 v) {
			Integer y = cells.get(key(Mth.floor(v.x), Mth.floor(v.z)));
			return y != null && Math.abs(v.y - y) < 1.5;
		}
	}

	private static final ItemStack WHEAT = new ItemStack(Items.WHEAT);
	private static List<Pen> pens = List.of();
	/** What each pen holds, by its gate: the kind most of its animals are, kept while it is empty. */
	private static final Map<BlockPos, String> kindOf = new HashMap<>();
	private static long nextScan;
	private static final Map<Integer, Long> skip = new HashMap<>();
	private static final Map<Integer, Integer> tries = new HashMap<>();

	// the round
	private static final int GO = 1, LEAD = 2, IN = 3, LET_GO = 4, SHUT = 5, OUT = 6;
	private static int phase;
	private static Animal animal;
	private static Pen pen;
	private static long since, waitSince = -1, phaseSince;
	private static int prevSlot = -1;
	/** Why the round ended, for Chores' line. */
	static String why = "";

	static boolean hasPens() { return !kindOf.isEmpty(); }

	/** A round is under way: he leads an animal, and opens a gate it waits behind on purpose. */
	static boolean active() { return phase != 0; }

	private static final Map<BlockPos, Long> closedAt = new HashMap<>();
	private static final Map<BlockPos, Boolean> closedIs = new HashMap<>();

	/**
	 * The cell beyond a door or gate lies in a closed space (a pen, a stable): an animal walks from it to at most
	 * {@link #LIMIT} cells. Open ground otherwise, or when no animal could stand there. Kept ten seconds per cell.
	 */
	static boolean closedSide(Level level, BlockPos cell) {
		long now = System.currentTimeMillis();
		Long at = closedAt.get(cell);
		if (at != null && now - at < 10_000) return closedIs.get(cell);
		BlockPos f = foot(level, cell);
		boolean closed = f != null && flood(level, f) != null;
		closedAt.put(cell.immutable(), now);
		closedIs.put(cell.immutable(), closed);
		if (closedAt.size() > 200) { closedAt.clear(); closedIs.clear(); }
		return closed;
	}

	private static final Map<BlockPos, Long> penAt = new HashMap<>();
	private static final Map<BlockPos, Boolean> penIs = new HashMap<>();

	/**
	 * A pen's gate or door: on one side of it a closed space ({@link #flood}) with a living animal in it. With
	 * {@code basePenGates} off the walk never goes through one (Follow.opensBy): over the pen's fence where carpets lie on
	 * it, which an animal does not jump, else not at all: if the buddy uses no fence gates, no sheep can escape either.
	 * Kept ten seconds per gate.
	 */
	static boolean penGate(Level level, BlockPos gate) {
		long now = System.currentTimeMillis();
		Long at = penAt.get(gate);
		if (at != null && now - at < 10_000) return penIs.get(gate);
		boolean pen = false;
		BlockState st = level.getBlockState(gate);
		if (isGate(st) || st.getBlock() instanceof DoorBlock) {
			Direction f = st.getValue(BlockStateProperties.HORIZONTAL_FACING);
			for (Direction d : new Direction[] { f, f.getOpposite() }) {
				BlockPos side = foot(level, gate.relative(d));
				Map<Long, Integer> cells = side == null ? null : flood(level, side);
				if (cells == null) continue;
				for (Animal a : level.getEntitiesOfClass(Animal.class, new AABB(gate).inflate(20, 4, 20)))
					if (a.isAlive() && penAnimal(a) && cells.containsKey(key(Mth.floor(a.getX()), Mth.floor(a.getZ())))) { pen = true; break; }
				if (pen) break;
			}
		}
		penAt.put(gate.immutable(), now);
		penIs.put(gate.immutable(), pen);
		if (penAt.size() > 200) { penAt.clear(); penIs.clear(); }
		return pen;
	}

	/**
	 * An animal a pen holds: no pet (a wolf, cat or parrot) and no happy ghast. Two dogs in a room of the house made
	 * the house a pen and its door a wall, so from 0.4.128 on he could not get out to the field or the pens.
	 */
	private static boolean penAnimal(Animal a) { return !(a instanceof TamableAnimal) && !(a instanceof HappyGhast); }

	/** The kind of the animal of the round, for Chores' line. */
	static String what() { return animal == null ? "" : kind(animal); }

	// ---------- the start ----------

	/** In the base, with wheat on him: a stray, and the round set up for it. */
	static boolean find(LocalPlayer p, long now) {
		skip.values().removeIf(u -> u < now);
		for (Pen pn : pens) if (p.level().isLoaded(pn.gate) && !isGate(p.level().getBlockState(pn.gate))) { nextScan = 0; break; }
		if (now >= nextScan) { nextScan = now + SCAN_EVERY; scan(p.level(), p); }
		if (kindOf.isEmpty() || Chores.count(p, "wheat") == 0) return false;
		Level level = p.level();
		Animal best = null;
		Pen bestPen = null;
		double bd = Double.MAX_VALUE;
		// in the whole base, as shearing: he waits by his bed, more than 24 blocks from the pens (23 Oct 2026)
		for (Animal a : level.getEntitiesOfClass(Animal.class, p.getBoundingBox().inflate(Base.RADIUS, 8, Base.RADIUS))) {
			if (!lured(a) || !a.isAlive() || a.isLeashed() || a.isPassenger() || skip.containsKey(a.getId())) continue;
			Pen home = homeOf(kind(a), a.position());
			if (home == null || penned(a.position()) || !inBase(level, a.position())) continue;
			// on open ground: not shut in somewhere else (a pen with no gate he knows, a barn), which a walk cannot open
			BlockPos f = foot(level, a.blockPosition());
			if (f == null || flood(level, f) != null) continue;
			double d = a.distanceToSqr(p);
			if (d < bd) { bd = d; best = a; bestPen = home; }
		}
		if (best == null) return false;
		animal = best;
		pen = bestPen;
		phase = GO;
		since = phaseSince = now;
		waitSince = -1;
		prevSlot = -1;
		why = "";
		Exchange.emit("herd", "action", "to animal", "animal", kind(best), "x", best.getBlockX(), "y", best.getBlockY(), "z", best.getBlockZ(),
			"gate", pen.gate.toShortString());
		return true;
	}

	// ---------- the round ----------

	/**
	 * One tick of the round; false when it is over ({@link #why} says how). Wheat in hand only while it brings the stray
	 * along, never otherwise, and gates are shut properly. The gate is open only while the stray comes in, and shut from
	 * inside before he
	 * walks out: the first measured version walked out through the gate it had held open, and one of the pen's own sheep
	 * went out with him; a second one let go of the wheat at the gate, the stray stayed outside and the gate stood open
	 * for half a minute; a third called from four blocks inside a pen three wide, its own sheep crowded behind the gate
	 * and the stray stood in front of it. From farther off a sheep between him and the gate kept his click off it.
	 */
	static boolean tick(Minecraft mc, LocalPlayer p, long now) {
		if (animal == null || pen == null) return end(p, "no animal");
		if (!animal.isAlive() || animal.isRemoved()) return end(p, "animal gone");
		// the gate taken away or moved: the pens looked for anew (the measurement: a gate moved three blocks, and he led
		// the stray to the fence where it had stood, through the new gate, which shut behind him)
		if (!isGate(p.level().getBlockState(pen.gate))) { nextScan = 0; return end(p, "gate gone"); }
		if (now - since > ROUND_MAX && phase < LET_GO) { why = "took too long"; to(LET_GO, now); }
		if (phase < LET_GO && Chores.count(p, "wheat") == 0) return end(p, "no wheat");
		double d = animal.distanceTo(p);
		switch (phase) {
			case GO -> {
				if (d <= LURE) hold(mc, p);
				if (d <= CLOSE && p.getMainHandItem().is(Items.WHEAT)) { to(LEAD, now); return true; }
				if (!Work.approach(mc, p, "herd-" + animal.getId(), animal.position(), now)) return fail(p, "cannot reach it");
				return true;
			}
			case LEAD -> {
				hold(mc, p);
				if (d > LOST) return lost(p, now);
				Vec3 out = Vec3.atBottomCenterOf(pen.outside);
				boolean there = Math.hypot(p.getX() - out.x, p.getZ() - out.z) <= 1.2;
				// at the gate it has to be close: the game's animals stop 2.5 from the player, and walk on as he walks
				if (d > LAG || (there && d > CLOSE - 0.8)) return waitFor(p, now);
				waitSince = -1;
				if (there) { to(IN, now); return true; }
				if (!Work.approach(mc, p, "herd-outside-" + pen.gate.toShortString(), out, now)) return fail(p, "cannot reach the gate");
				return true;
			}
			case IN -> {
				hold(mc, p);
				Follow.holdOpen(pen.gate);
				if (pen.holds(animal.position()) && animal.position().distanceTo(Vec3.atBottomCenterOf(pen.gate)) >= INSIDE) { to(LET_GO, now); return true; }
				if (d > LOST) { why = "does not follow"; to(LET_GO, now); return true; }
				Vec3 far = Vec3.atBottomCenterOf(pen.far);
				if (Math.hypot(p.getX() - far.x, p.getZ() - far.z) <= 1.0) {
					Follow.stand();
					Follow.aim(p, animal.getEyePosition());
					if (waitSince < 0) waitSince = now;
					if (now - waitSince > CALL_MAX) { why = "cannot get into the pen"; to(LET_GO, now); }
					return true;
				}
				// it lags on the way in: he stands, the gate open, until it has caught up
				if (d > LAG) { if (waitSince < 0) waitSince = now; Follow.stand(); Follow.aim(p, animal.getEyePosition()); Work.walkFresh();
					if (now - waitSince > WAIT_MAX) { why = "does not follow"; to(LET_GO, now); } return true; }
				waitSince = -1;
				if (!Work.approach(mc, p, "herd-in-" + pen.gate.toShortString(), far, now)) { why = "cannot get into the pen"; to(LET_GO, now); }
				return true;
			}
			case LET_GO -> {
				Follow.holdOpen(pen.gate);
				letGo(p);
				Follow.stand();
				// the game's animals look at a player's hand every other tick: a moment, and nothing follows him to the gate
				if (now - phaseSince >= 600) { Follow.holdOpen(null); to(SHUT, now); }
				return true;
			}
			case SHUT -> {
				// shut from inside, at once, from the cell just inside it, where no animal stands between him and the gate:
				// nothing wanders in or out while he walks out
				letGo(p);
				if (!open(p.level().getBlockState(pen.gate)) || now - phaseSince > SHUT_MAX) { to(OUT, now); return true; }
				Vec3 mid = Vec3.atCenterOf(pen.gate);
				boolean inDoorway = p.getBoundingBox().intersects(new AABB(pen.gate).expandTowards(0, 1, 0));
				if (pen.holds(p.position()) && p.getEyePosition().distanceTo(mid) <= 2.3 && !inDoorway) { Follow.shut(pen.gate); Follow.stand(); return true; }
				if (!Work.approach(mc, p, "herd-shut-" + pen.gate.toShortString(), Vec3.atBottomCenterOf(pen.inside), now)) to(OUT, now);
				return true;
			}
			case OUT -> {
				letGo(p);
				boolean outside = !pen.holds(p.position()) && !p.getBoundingBox().intersects(new AABB(pen.gate).expandTowards(0, 1, 0));
				Vec3 gate = Vec3.atBottomCenterOf(pen.gate);
				// a step past the gate's outside cell, away from the pen, out of the doorway; the outside cell itself when
				// there is no floor beyond it (the measurement: a hole there, and a minute to climb out of it)
				BlockPos o = pen.outside;
				int sx = o.getX() - pen.gate.getX(), sz = o.getZ() - pen.gate.getZ();
				boolean beyond = foot(p.level(), o.offset(sx, 0, sz)) != null;
				if (outside && Math.hypot(p.getX() - gate.x, p.getZ() - gate.z) >= (beyond ? 1.5 : 0.9)) {
					if (!open(p.level().getBlockState(pen.gate)) || now - phaseSince > OUT_MAX) {
						String extra = strangersIn();
						if (!extra.isEmpty()) Exchange.emit("herd", "action", "also inside", "animals", extra, "gate", pen.gate.toShortString());
						if (pen.holds(animal.position())) { tries.remove(animal.getId()); return end(p, why.isEmpty() ? "inside" : why); }
						return fail(p, why.isEmpty() ? "not inside" : why);
					}
					Follow.stand();
					return true;
				}
				Vec3 goal = Vec3.atBottomCenterOf(o).add(sx * (beyond ? 0.8 : 0.1), 0, sz * (beyond ? 0.8 : 0.1));
				if (!Work.approach(mc, p, "herd-out-" + pen.gate.toShortString(), goal, now)) return end(p, "cannot get out of the pen");
				return true;
			}
			default -> { return end(p, "no phase"); }
		}
	}

	private static boolean open(BlockState g) { return g.hasProperty(BlockStateProperties.OPEN) && g.getValue(BlockStateProperties.OPEN); }

	/** Animals of another kind than the pen's in it now, for the line after the round. */
	private static String strangersIn() {
		String k = kindOf.getOrDefault(pen.gate, "");
		Map<String, Integer> n = new HashMap<>();
		for (Animal a : animal.level().getEntitiesOfClass(Animal.class, box(pen))) if (lured(a) && pen.holds(a.position()) && !kind(a).equals(k)) n.merge(kind(a), 1, Integer::sum);
		StringBuilder sb = new StringBuilder();
		n.forEach((kk, v) -> sb.append(sb.length() == 0 ? "" : ", ").append(v).append(" ").append(kk));
		return sb.toString();
	}

	private static void to(int next, long now) {
		phase = next;
		phaseSince = now;
		waitSince = -1;
		Work.walkFresh();
		if (next == LEAD) Exchange.emit("herd", "action", "leads", "animal", kind(animal));
		if (next == IN) Exchange.emit("herd", "action", "into the pen", "animal", kind(animal), "gate", pen.gate.toShortString());
		if (next == LET_GO) Exchange.emit("herd", "action", "gate shut", "animal", kind(animal), "inside", pen.holds(animal.position()));
	}

	/** It lags: he stands and waits for it, a while. */
	private static boolean waitFor(LocalPlayer p, long now) {
		if (waitSince < 0) waitSince = now;
		if (now - waitSince > WAIT_MAX) return lost(p, now);
		Follow.stand();
		Follow.aim(p, animal.getEyePosition());
		Work.walkFresh();
		return true;
	}

	/** It no longer follows: after it again, from the start; the third time, left for a while. */
	private static boolean lost(LocalPlayer p, long now) {
		int n = tries.merge(animal.getId(), 1, Integer::sum);
		if (n >= TRIES) return fail(p, "does not follow");
		Exchange.emit("herd", "action", "lost", "animal", kind(animal), "times", n);
		to(GO, now);
		return true;
	}

	/** The round failed for this animal: it is left alone for ten minutes. */
	private static boolean fail(LocalPlayer p, String reason) {
		if (animal != null) { skip.put(animal.getId(), System.currentTimeMillis() + SKIP_FOR); tries.remove(animal.getId()); }
		return end(p, reason);
	}

	private static boolean end(LocalPlayer p, String reason) {
		why = reason;
		reset(p);
		return false;
	}

	/** The round over or broken off: the gate no longer held, no wheat left in hand. */
	static void reset(LocalPlayer p) {
		Follow.holdOpen(null);
		if (p != null && phase != 0) letGo(p);
		phase = 0;
		animal = null;
		pen = null;
		prevSlot = -1;
	}

	// ---------- the wheat ----------

	/** Wheat in hand, from his hotbar or his backpack; the slot he had in hand remembered. */
	private static void hold(Minecraft mc, LocalPlayer p) {
		if (p.getMainHandItem().is(Items.WHEAT)) return;
		var inv = p.getInventory();
		if (prevSlot < 0) prevSlot = inv.getSelectedSlot();
		for (int i = 0; i < 9; i++) if (inv.getItem(i).is(Items.WHEAT)) { inv.setSelectedSlot(i); return; }
		AfkHelperClient.toHotbar(p, mc, s -> s.is(Items.WHEAT), java.util.Comparator.comparingInt(ItemStack::getCount), "wheat");
	}

	/** Something else in hand than what the animals follow: the slot he had, else one without their food. */
	private static void letGo(LocalPlayer p) {
		if (!p.getMainHandItem().is(Items.WHEAT)) return;
		var inv = p.getInventory();
		if (prevSlot >= 0 && !inv.getItem(prevSlot).is(Items.WHEAT)) { inv.setSelectedSlot(prevSlot); return; }
		for (int i = 0; i < 9; i++) if (inv.getItem(i).isEmpty()) { inv.setSelectedSlot(i); return; }
		for (int i = 0; i < 9; i++) if (!inv.getItem(i).is(Items.WHEAT)) { inv.setSelectedSlot(i); return; }
	}

	// ---------- the pens ----------

	/** The pens behind the gates and wooden doors round him, and their kinds from the animals in them now. */
	private static void scan(Level level, LocalPlayer p) {
		List<Pen> found = new ArrayList<>();
		BlockPos me = p.blockPosition();
		for (BlockPos q : BlockPos.betweenClosed(me.offset(-SCAN_R, -5, -SCAN_R), me.offset(SCAN_R, 5, SCAN_R))) {
			BlockState st = level.getBlockState(q);
			if (!isGate(st)) continue;
			BlockPos g = q.immutable();
			if (!inBase(level, Vec3.atCenterOf(g))) continue;
			Direction f = st.getValue(BlockStateProperties.HORIZONTAL_FACING);
			BlockPos a = foot(level, g.relative(f)), b = foot(level, g.relative(f.getOpposite()));
			if (a == null || b == null) continue;
			Map<Long, Integer> fa = flood(level, a), fb = flood(level, b);
			if (fa == null && fb != null && fb.size() >= 2) found.add(pen(g, b, a, fb));
			else if (fb == null && fa != null && fa.size() >= 2) found.add(pen(g, a, b, fa));
		}
		boolean changed = found.size() != pens.size();
		pens = found;
		for (Pen pn : pens) {
			Map<String, Integer> count = new HashMap<>();
			for (Animal a : level.getEntitiesOfClass(Animal.class, box(pn))) if (lured(a) && pn.holds(a.position())) count.merge(kind(a), 1, Integer::sum);
			String most = count.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
			if (most != null && !most.equals(kindOf.put(pn.gate, most))) changed = true;
		}
		if (changed) {
			StringBuilder sb = new StringBuilder();
			for (Pen pn : pens) sb.append(sb.length() == 0 ? "" : "; ").append(pn.gate.toShortString()).append(" ").append(kindOf.getOrDefault(pn.gate, "?")).append(" ").append(pn.cells.size());
			Exchange.emit("herd", "action", "pens", "count", pens.size(), "which", sb.toString());
		}
	}

	private static Pen pen(BlockPos gate, BlockPos inside, BlockPos outside, Map<Long, Integer> cells) {
		BlockPos far = inside;
		double fd = -1;
		for (var e : cells.entrySet()) {
			int x = (int) (e.getKey() >> 32), z = (int) (long) e.getKey();
			double d = Math.hypot(x - gate.getX(), z - gate.getZ());
			if (d > fd) { fd = d; far = new BlockPos(x, e.getValue(), z); }
		}
		return new Pen(gate, inside, outside, cells, far);
	}

	private static AABB box(Pen pn) {
		int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE, y = pn.inside.getY();
		for (long k : pn.cells.keySet()) {
			int x = (int) (k >> 32), z = (int) k;
			minX = Math.min(minX, x); maxX = Math.max(maxX, x); minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
		}
		return new AABB(minX, y - 2, minZ, maxX + 1, y + 3, maxZ + 1);
	}

	/** The pen of this kind whose gate is nearest to here, within FROM_GATE; null for none. */
	private static Pen homeOf(String kind, Vec3 at) {
		Pen best = null;
		double bd = FROM_GATE * FROM_GATE;
		for (Pen pn : pens) {
			if (!kind.equals(kindOf.get(pn.gate))) continue;
			double d = Vec3.atCenterOf(pn.gate).distanceToSqr(at);
			if (d < bd) { bd = d; best = pn; }
		}
		return best;
	}

	private static boolean penned(Vec3 at) {
		for (Pen pn : pens) if (pn.holds(at)) return true;
		return false;
	}

	/** An animal the game makes follow a player with wheat in hand; no horse or pet, which take wheat as food only. */
	private static boolean lured(Animal a) {
		return !(a instanceof TamableAnimal) && !(a instanceof AbstractHorse) && a.isFood(WHEAT);
	}

	private static String kind(Animal a) { return BuiltInRegistries.ENTITY_TYPE.getKey(a.getType()).getPath(); }

	/** In a base of the player he works for. */
	private static boolean inBase(Level level, Vec3 at) {
		String boss = Follow.boss();
		var e = Tap.baseAt(Work.dimOf(level), at, Base.RADIUS);
		return boss != null && e != null && e.owner().equalsIgnoreCase(boss);
	}

	// ---------- where an animal walks ----------

	static long key(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }

	/** The cell an animal stands in here: this one, one lower or one higher; null for none. */
	private static BlockPos foot(Level level, BlockPos c) {
		for (int dy : new int[] { 0, -1, 1 }) if (standable(level, c.getX(), c.getY() + dy, c.getZ())) return c.offset(0, dy, 0);
		return null;
	}

	/**
	 * The cells (column to feet height) an animal walks to from here, four ways, a step up or down at a time, through
	 * no gate or door; null past {@link #LIMIT} or into ground that is not loaded, which is open ground.
	 */
	static Map<Long, Integer> flood(Level level, BlockPos start) {
		Map<Long, Integer> cells = new HashMap<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		cells.put(key(start.getX(), start.getZ()), start.getY());
		queue.add(start);
		while (!queue.isEmpty()) {
			BlockPos c = queue.poll();
			for (Direction d : Direction.Plane.HORIZONTAL) {
				int nx = c.getX() + d.getStepX(), nz = c.getZ() + d.getStepZ();
				long k = key(nx, nz);
				if (cells.containsKey(k)) continue;
				if (!level.hasChunkAt(new BlockPos(nx, c.getY(), nz))) return null;
				for (int dy : new int[] { 0, -1, 1 }) {
					int ny = c.getY() + dy;
					if (dy == 1 && top(level, c.above(2)) > 0) continue;   // no room to jump up from here
					if (!standable(level, nx, ny, nz)) continue;
					cells.put(k, ny);
					queue.add(new BlockPos(nx, ny, nz));
					break;
				}
				if (cells.size() > LIMIT) return null;
			}
		}
		return cells;
	}

	/** Feet here: nothing to bump into (a carpet or a plate is fine), room for the head, a floor of a block's height. */
	private static boolean standable(Level level, int x, int y, int z) {
		BlockPos at = new BlockPos(x, y, z);
		if (entrance(level.getBlockState(at)) || entrance(level.getBlockState(at.above()))) return false;
		if (top(level, at) > 0.2 || top(level, at.above()) > 0) return false;
		double floor = top(level, at.below());
		return floor >= 0.5 && floor <= 1.0;
	}

	private static boolean entrance(BlockState st) { return st.getBlock() instanceof FenceGateBlock || st.getBlock() instanceof DoorBlock; }

	/** A fence gate or the lower half of a wooden door: what a pen is entered by. */
	private static boolean isGate(BlockState st) {
		return st.getBlock() instanceof FenceGateBlock || (st.is(BlockTags.WOODEN_DOORS) && st.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER);
	}

	/** The top of a block's collision box, 0 for none. */
	private static double top(Level level, BlockPos pos) {
		var shape = level.getBlockState(pos).getCollisionShape(level, pos);
		return shape.isEmpty() ? 0 : shape.max(Direction.Axis.Y);
	}
}
