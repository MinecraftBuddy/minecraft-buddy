package net.milenco.buddy.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * In which order the builder places, the way players go about a build. Placing strictly the lowest layer by what
 * was nearest walled him out of corners and rooms he still had to fill. Five rules now work together:
 * <ol>
 *   <li>Never shut yourself out: a block that seals another remaining block off from open space, or takes away
 *       the last spot from where it can be reached, waits ({@link #closesOff}).</li>
 *   <li>Inside out: the middle of a layer before its edge, so the outer walls come last.</li>
 *   <li>Back to front: far from where you came in first, working back toward it.</li>
 *   <li>From above: a spot on top of the build is preferred to stand on ({@link #standBonus}).</li>
 *   <li>Hard ones first: a block that is already closed in on many sides, or whose facing comes from where you
 *       stand, goes before it gets harder to reach.</li>
 * </ol>
 * Rules 2, 3 and 5 give one {@link #priority}; lower goes first. It is in degrees of turning, so it weighs against
 * the turn to a block within reach.
 */
final class BuildOrder {
	private BuildOrder() {}

	/** Degrees per block deeper into the layer (rule 2). */
	private static final double DEPTH = 12;
	/** Degrees per block farther from where you came in (rule 3). */
	private static final double FROM_ENTRY = 4;
	/** Degrees per side that is closed in (rule 5). */
	private static final double CLOSED_SIDE = 15;
	/** Walking cost (a step is 10) that a spot on top of the build is worth (rule 4). */
	static final int ABOVE_BONUS = 30;
	/** The walkable area is searched this far around the build, and never beyond this many cells. */
	private static final int MARGIN = 6, MAX_CELLS = 5000;

	/** What one plan needs: the build, what is still to come, where you came in, and where you can walk now. */
	static final class Ctx {
		final Level level;
		final Schematic.Placement pl;
		final Set<BlockPos> remaining;
		final BlockPos entry;
		final BlockPos from;
		final double reach;
		private Set<BlockPos> walkable;
		Ctx(Level level, Schematic.Placement pl, Set<BlockPos> remaining, BlockPos entry, BlockPos from, double reach) {
			this.level = level; this.pl = pl; this.remaining = remaining; this.entry = entry; this.from = from; this.reach = reach;
		}
		/** Where you can walk now; worked out on first use, since most plans never ask (a field of crops shuts nothing off). */
		Set<BlockPos> walkable() {
			if (walkable == null) walkable = BuildOrder.walkable(this, null);
			return walkable;
		}
	}

	/** Is there farmland in the schematic that water here keeps wet (four blocks around, level with it or one below)? */
	private static boolean wetsFarmland(BlockPos q) {
		net.minecraft.world.level.Level schem = Schematic.world();
		if (schem == null) return false;
		for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) for (int dy = -1; dy <= 0; dy++)
			if (schem.getBlockState(q.offset(dx, dy, dz)).is(net.minecraft.world.level.block.Blocks.FARMLAND)) return true;
		return false;
	}

	/** Floor-only blocks go before everything else in reach, once their floor stands. */
	private static final double FLOOR_FIRST = -1000;

	static boolean floorOnly(net.minecraft.world.level.block.state.BlockState st) {
		var b = st.getBlock();
		return b == net.minecraft.world.level.block.Blocks.REDSTONE_WIRE || b instanceof net.minecraft.world.level.block.BaseRailBlock
			|| b instanceof net.minecraft.world.level.block.TripWireBlock || b instanceof net.minecraft.world.level.block.CarpetBlock;
	}

	private static final double POWER_LAST = 800, TNT_LAST = 2000, SOW_NOW = -1500;

	/** Water goes after everything else in reach. */
	private static final double WATER_LAST = 1000;

	/** Degrees for a block whose facing comes from where you stand (stairs, gates, observers): its one right side may get built over. */
	private static final double FACING = 40;

	/** Lower goes first: deep in the layer, far from the entry, closed in on many sides, facing set by where you stand. */
	static double priority(Ctx x, BlockPos q, net.minecraft.world.level.block.state.BlockState want) {
		BlockPos min = x.pl.min(), max = x.pl.max();
		int depth = Math.min(Math.min(q.getX() - min.getX(), max.getX() - q.getX()), Math.min(q.getZ() - min.getZ(), max.getZ() - q.getZ()));
		double fromEntry = Math.hypot(q.getX() - x.entry.getX(), q.getZ() - x.entry.getZ());
		int closed = 0;
		for (Direction d : Direction.values()) {
			BlockPos n = q.relative(d);
			// what stands now, not what is still to come: a block whose neighbours are yet to come has nothing to
			// click on, and preferring those put supports all through the build
			if (Builder.solid(x.level, n)) closed++;
		}
		// water last: it runs into the cells around it, and you cannot stand or build in the flow. Not the water that
		// keeps farmland wet: that goes with the farmland, the crops grow from the start
		if (want != null && (want.getBlock() == net.minecraft.world.level.block.Blocks.WATER
			|| (Builder.wantsWaterlogged(want) && x.level.getBlockState(q).getBlock() == want.getBlock())) && !wetsFarmland(q)) return WATER_LAST;
		// seeds on farmland no water reaches go in at once: bare and dry it turns back to dirt
		if (want != null && Finisher.needsFarmland(want) && !Builder.wetNear(x.level, q.below())) return SOW_NOW;
		// TNT at the very end, and what gives power after everything but water: nothing may start moving or go off
		// while the rest is still being built
		if (want != null && want.getBlock() == net.minecraft.world.level.block.Blocks.TNT) return TNT_LAST;
		if (want != null && Finisher.powerSource(want)) return POWER_LAST;
		// what can only go on top of a floor (redstone, rails, tripwire, carpet) as soon as the floor is
		// there: once walls and roof stand, that floor is out of sight and the block cannot be placed any more
		if (want != null && floorOnly(want) && Builder.solid(x.level, q.below())) return FLOOR_FIRST;
		boolean facing = want != null && (want.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
			|| want.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING));
		return -(Math.max(0, depth) * DEPTH + fromEntry * FROM_ENTRY + closed * CLOSED_SIDE + (facing ? FACING : 0));
	}

	/** Rule 4: standing on the build, at or above the block, rather than beside it on the ground. */
	static int standBonus(BlockPos cell, BlockPos target) {
		return cell.getY() > target.getY() ? ABOVE_BONUS : 0;
	}

	/**
	 * Rule 1: would a block here take away the last spot from where some other remaining block can be reached?
	 * Compares the area you can walk (without scaffold) with and without it. Closing a room whose inside is done
	 * is fine; closing it while a block inside still waits is not.
	 */
	static boolean closesOff(Ctx x, BlockPos block) {
		if (x.walkable().isEmpty()) return false;
		// Standing room alone missed the pits and corners: a spot you can stand near, but that the block seals off
		// from sight. So first: does every remaining block around it keep an open way to where you can stand?
		for (Direction d : Direction.values()) for (Direction e : Direction.values()) {
			BlockPos k = block.relative(d).relative(e);
			if (k.equals(block) || !x.remaining.contains(k)) continue;
			if (inSight(x, k, null) && !inSight(x, k, block)) return true;
		}
		Set<BlockPos> after = walkable(x, block);
		Set<BlockPos> lost = new HashSet<>();
		for (BlockPos c : x.walkable()) if (!after.contains(c) && !c.equals(block) && !c.equals(block.above())) lost.add(c);
		if (lost.isEmpty()) return false;
		int r = (int) Math.ceil(x.reach) + 2;
		for (BlockPos k : x.remaining) {
			if (k.equals(block) || !near(lost, k, r)) continue;
			if (reachable(x.walkable(), k, x.reach) && !reachable(after, k, x.reach)) return true;
		}
		return false;
	}

	/** Steps through open space a click may take from where you stand to the block. */
	private static final int SIGHT_STEPS = 6;

	/**
	 * Is there open space from this block to where your body can be (a walkable cell or the one above it), within
	 * reach, as if a block stood at {@code extra}? Every click on it has to come through that space.
	 */
	private static boolean inSight(Ctx x, BlockPos k, BlockPos extra) {
		ArrayDeque<BlockPos> q = new ArrayDeque<>();
		java.util.Map<BlockPos, Integer> dist = new java.util.HashMap<>();
		q.add(k); dist.put(k, 0);
		while (!q.isEmpty()) {
			BlockPos cur = q.poll();
			int n = dist.get(cur);
			if (!cur.equals(k) && (x.walkable().contains(cur) || x.walkable().contains(cur.below()))) return true;
			if (n >= SIGHT_STEPS) continue;
			for (Direction d : Direction.values()) {
				BlockPos nb = cur.relative(d);
				if (dist.containsKey(nb) || nb.equals(extra) || !Builder.passable(x.level, nb, false)) continue;
				dist.put(nb, n + 1); q.add(nb);
			}
		}
		return false;
	}

	private static boolean near(Set<BlockPos> cells, BlockPos k, int r) {
		for (BlockPos c : cells) if (Math.abs(c.getX() - k.getX()) <= r && Math.abs(c.getY() - k.getY()) <= r && Math.abs(c.getZ() - k.getZ()) <= r) return true;
		return false;
	}

	/** Is there a cell to stand on from where the eye is within reach of this block? */
	private static boolean reachable(Set<BlockPos> cells, BlockPos k, double reach) {
		int r = (int) Math.ceil(reach);
		Vec3 target = Vec3.atCenterOf(k);
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -r; dx <= r; dx++) for (int dy = -r - 2; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
			m.set(k.getX() + dx, k.getY() + dy, k.getZ() + dz);
			if (!cells.contains(m)) continue;
			if (new Vec3(m.getX() + 0.5, m.getY() + 1.62, m.getZ() + 0.5).distanceTo(target) <= reach) return true;
		}
		return false;
	}

	/**
	 * The cells you can walk to from where you stand, with walking, a step up and a drop of up to three, as if a
	 * block stood at {@code extra} (null: the world as it is). Only around the build.
	 */
	private static Set<BlockPos> walkable(Ctx x, BlockPos extra) {
		Set<BlockPos> seen = new HashSet<>();
		if (!standable(x, x.from, extra)) return seen;
		ArrayDeque<BlockPos> q = new ArrayDeque<>();
		q.add(x.from); seen.add(x.from);
		while (!q.isEmpty() && seen.size() < MAX_CELLS) {
			BlockPos cur = q.poll();
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos h = cur.relative(d);
				if (!x.pl.contains(h, MARGIN)) continue;
				if (standable(x, h, extra)) { if (seen.add(h)) q.add(h); continue; }
				if (standable(x, h.above(), extra) && passable(x, cur.above(2), extra)) { if (seen.add(h.above())) q.add(h.above()); continue; }
				if (!passable(x, h, extra) || !passable(x, h.above(), extra)) continue;
				for (int k = 1; k <= 3; k++) {
					BlockPos down = h.below(k);
					if (standable(x, down, extra)) { if (seen.add(down)) q.add(down); break; }
					if (!passable(x, down, extra)) break;
				}
			}
		}
		return seen;
	}

	private static boolean standable(Ctx x, BlockPos f, BlockPos extra) {
		if (extra == null) return Builder.standable(x.level, f);
		if (f.equals(extra) || f.above().equals(extra)) return false;
		if (f.below().equals(extra)) return passable(x, f, extra) && passable(x, f.above(), extra) && !Grinder.hazard(x.level, f);
		return Builder.standable(x.level, f);
	}

	private static boolean passable(Ctx x, BlockPos q, BlockPos extra) {
		return !q.equals(extra) && Builder.passable(x.level, q, false);
	}
}
