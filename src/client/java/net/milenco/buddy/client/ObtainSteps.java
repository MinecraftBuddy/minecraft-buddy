package net.milenco.buddy.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.milenco.buddy.version.Game;

/**
 * The steps of an order ({@link Obtain}), one class each: break blocks of a kind and pick up what falls (with a search
 * for them when none is in view: over the ground, or down a staircase and along a mine at the depth they are at), craft
 * in the 2x2 grid or at a table it places itself when none is near, smelt in a furnace, kill mobs of a kind. Every move
 * is the game's own (the keys, the crosshair on a face, the clicks of an inventory), as everywhere in the mod.
 */
final class ObtainSteps {
	private ObtainSteps() {}

	enum Result { RUN, WAIT, DONE, FAIL }

	abstract static class Exec {
		String why;

		String why() { return why; }

		abstract Result tick(Minecraft mc, LocalPlayer p, long now);

		boolean inMenu(LocalPlayer p) { return false; }

		Result menuTick(Minecraft mc, LocalPlayer p, long now) { return Result.RUN; }

		void startTick(Minecraft mc) {}

		void stop() {}

		Result fail(String w) { why = w; return Result.FAIL; }

		/** Its screen shut by something else: what it had queued is gone, it fills again. */
		void menuClosed() {}

		/** What it is at, for the status event: the block it breaks, the cell it walks to, the phase. */
		String detail() { return ""; }

		/** A station this step placed, to be picked up again later; null when none. */
		BlockPos placedAt() { return null; }

		String placedBlock() { return null; }
	}

	static Exec of(ObtainPlan.Step s, LocalPlayer p) {
		return switch (s.act()) {
			case TAKE -> new Take(s, p);
			case SPECIAL -> switch (s.special()) {
				case "fill water" -> new Fill(s, p, "water");
				case "fill lava" -> new Fill(s, p, "lava");
				case "cast obsidian" -> new Cast(s, p);
				case "nether portal" -> new Portal(s, p);
				case "through the portal", "back to the overworld" -> new Through(s, p);
				case "find stronghold" -> new FindStronghold(s, p);
				case "strip a log" -> new Strip(s, p);
				case "end portal" -> new EndPortal(s, p);
				default -> null;
			};
			case MINE -> new Mine(s, p);
			case CRAFT -> new Craft(s, p);
			case SMELT -> new Smelt(s, p);
			case KILL -> new Kill(s, p);
			default -> null;
		};
	}

	// ---------- the inventory ----------

	static int count(LocalPlayer p, String item) {
		int n = 0;
		var in = p.getInventory();
		for (int i = 0; i < 36; i++) { ItemStack s = in.getItem(i); if (!s.isEmpty() && AfkHelperClient.itemId(s).equals(item)) n += s.getCount(); }
		ItemStack o = p.getOffhandItem();
		if (!o.isEmpty() && AfkHelperClient.itemId(o).equals(item)) n += o.getCount();
		return n;
	}

	static int countAny(LocalPlayer p, List<String> items) {
		int n = 0;
		for (String i : items) n += count(p, i);
		return n;
	}

	static String id(BlockState st) { return BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath(); }

	/** The item in hand, from anywhere in the inventory: true when it is there now. */
	static boolean hold(Minecraft mc, LocalPlayer p, Predicate<ItemStack> what, String why) {
		var in = p.getInventory();
		if (what.test(in.getItem(in.getSelectedSlot()))) return true;
		for (int i = 0; i < 9; i++) if (what.test(in.getItem(i))) { in.setSelectedSlot(i); return true; }
		AfkHelperClient.toHotbar(p, mc, what, java.util.Comparator.comparingInt(s -> 0), why);
		return false;
	}

	/** Worth picking up beside what the step is for: what an order takes often. */
	static final Set<String> KEEP = Set.of("cobblestone", "cobbled_deepslate", "coal", "raw_iron", "raw_copper", "raw_gold", "diamond",
		"redstone", "lapis_lazuli", "emerald", "flint", "stick", "apple");

	// ---------- picking up what fell ----------

	/** What fell where it broke or killed, fetched: walking over it picks it up. */
	static final class Pickup {
		final List<Vec3> spots = new ArrayList<>();
		final List<Long> spotAt = new ArrayList<>();
		final Set<Integer> gaveUp = new HashSet<>();
		ItemEntity fetching;
		long fetchSince;

		void at(Vec3 v) { spots.add(v); spotAt.add(System.currentTimeMillis()); }

		/** Walking to an item, or a spot to look at still known. */
		boolean busy() { return fetching != null || !spots.isEmpty(); }

		/** True while it walks to an item. */
		boolean tick(Minecraft mc, LocalPlayer p, long now, Predicate<ItemStack> wanted) {
			for (int i = spots.size() - 1; i >= 0; i--) if (now - spotAt.get(i) > 25_000) { spots.remove(i); spotAt.remove(i); }
			if (fetching != null && (!fetching.isAlive() || now - fetchSince > 8000)) {
				if (fetching.isAlive()) gaveUp.add(fetching.getId());
				fetching = null;
			}
			if (fetching == null) {
				if (spots.isEmpty() || Work.freeSlots(p) == 0) return false;
				double best = 1e9;
				for (ItemEntity it : p.level().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(10, 6, 10))) {
					if (gaveUp.contains(it.getId()) || !wanted.test(it.getItem()) || ObtainSafe.thrown(it)) continue;
					boolean ours = false;
					for (Vec3 s : spots) if (it.position().distanceToSqr(s) < 9) { ours = true; break; }
					if (!ours) continue;
					double d = it.distanceToSqr(p);
					if (d < best) { best = d; fetching = it; }
				}
				if (fetching == null) return false;
				fetchSince = now;
			}
			if (!Work.approach(mc, p, fetching.getId(), fetching.position(), now)) { gaveUp.add(fetching.getId()); fetching = null; return false; }
			return true;
		}
	}

	// ---------- breaking ----------

	/**
	 * Breaking one block: the crosshair on an open face within reach and the hit at the start of the tick (Work's own
	 * breaking), or a walk to a cell beside it first. RUN while busy, DONE when it is gone, FAIL when it cannot.
	 */
	static final class Breaker {
		BlockPos target, stand, obstacle;
		long since;
		int offTicks;
		/** Why the last FAIL: "reach" (no cell to stand in or no way there), "slow" (it did not break in time), "tool". */
		String failed;

		Result tick(Minecraft mc, LocalPlayer p, BlockPos b, long now) {
			Level level = p.level();
			if (!b.equals(target)) { target = b.immutable(); stand = null; since = now; obstacle = null; offTicks = 0; }
			BlockState st = level.getBlockState(b);
			if (st.isAir()) return Result.DONE;
			if (now - since > 30_000) { failed = "slow"; return Result.FAIL; }
			// a block in front of it that the crosshair keeps landing on: that one first
			if (obstacle != null) {
				if (level.getBlockState(obstacle).isAir() || !breakable(level, obstacle)) obstacle = null;
				else {
					Follow.stand();
					BlockPos o = obstacle;
					if (Work.breakForWalk(mc, p, o)) { watch(mc, level, o); return Result.RUN; }
					obstacle = null;
				}
			}
			Vec3 eye = p.getEyePosition();
			Vec3 aim = Work.aimPoint(level, p, eye, b);
			if (aim != null && eye.distanceTo(aim) <= 4.4) {
				Follow.stand();
				if (!Work.breakForWalk(mc, p, b)) { failed = "tool"; return Result.FAIL; }
				watch(mc, level, b);
				return Result.RUN;
			}
			// close, but something in the way (leaves over a trunk, earth over a stone): that first, as a player digs to it
			BlockPos block = inTheWay(level, p, b);
			if (block != null) {
				Follow.stand();
				if (Work.breakForWalk(mc, p, block)) { watch(mc, level, block); return Result.RUN; }
			}
			if (stand == null) stand = Work.standCell(level, p, b, null, c -> true);
			if (stand == null) { failed = "reach"; return Result.FAIL; }
			if (!Work.approach(mc, p, "mine-" + b.toShortString(), Vec3.atBottomCenterOf(stand), now)) { failed = "reach"; return Result.FAIL; }
			return Result.RUN;
		}

		/**
		 * The crosshair after the aim: on another block for most of half a second (a leaf in front of the face it aims at,
		 * which the aim takes for open because a leaf is no full block), that block becomes the one to break first.
		 */
		void watch(Minecraft mc, Level level, BlockPos aimed) {
			if (mc.hitResult instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK && !bh.getBlockPos().equals(aimed)) {
				if (++offTicks > 8 && breakable(level, bh.getBlockPos())) { obstacle = bh.getBlockPos().immutable(); offTicks = 0; }
			} else offTicks = 0;
		}

		/**
		 * A block the buddy may break on the way to another: natural ground, leaves, a log, a plant; nothing with contents.
		 */
		static boolean breakable(Level level, BlockPos q) {
			BlockState st = level.getBlockState(q);
			if (st.isAir() || st.getDestroySpeed(level, q) < 0 || level.getBlockEntity(q) != null || wet(level, q)) return false;
			return ObtainSafe.earth(st) || st.is(net.minecraft.tags.BlockTags.LEAVES) || st.is(net.minecraft.tags.BlockTags.LOGS) || st.canBeReplaced();
		}

		/**
		 * Within reach of the target but no open face: the first block on the line from the eye to it, when that is one to
		 * break here (natural ground, leaves, a plant; nothing with something in it, nothing beside water or lava), and not
		 * the floor underfoot unless what lies under that holds the buddy.
		 */
		static BlockPos inTheWay(Level level, LocalPlayer p, BlockPos b) {
			Vec3 eye = p.getEyePosition();
			if (eye.distanceTo(Vec3.atCenterOf(b)) > 4.6) return null;
			BlockHitResult hit = level.clip(new net.minecraft.world.level.ClipContext(eye, Vec3.atCenterOf(b), net.minecraft.world.level.ClipContext.Block.OUTLINE,
				net.minecraft.world.level.ClipContext.Fluid.NONE, p));
			if (hit.getType() != HitResult.Type.BLOCK) return null;
			BlockPos q = hit.getBlockPos();
			if (q.equals(b) || eye.distanceTo(hit.getLocation()) > 4.4) return null;
			BlockState st = level.getBlockState(q);
			if (st.getDestroySpeed(level, q) < 0 || level.getBlockEntity(q) != null || wet(level, q)) return null;
			if (!(ObtainSafe.earth(st) || st.is(net.minecraft.tags.BlockTags.LEAVES) || st.is(net.minecraft.tags.BlockTags.LOGS) || st.canBeReplaced())) return null;
			BlockPos feet = p.blockPosition();
			if (q.equals(feet.below())) {
				BlockState under = level.getBlockState(q.below());
				if (!under.isSolidRender() || lava(level, q) || lava(level, q.below())) return null;
			}
			return q;
		}
	}

	/**
	 * Where to walk to at a column, near the height the buddy is at: over the ground in the overworld, and in a dimension
	 * with a roof (the nether) the standing cell nearest its own height, never the roof's top (the heightmap there is the
	 * bedrock at 127). Null when there is none within reach of its height or it is beside lava.
	 */
	static BlockPos ground(Level level, int x, int z, int nearY) {
		if (!level.dimensionType().hasCeiling()) return surface(level, x, z);
		BlockPos best = null;
		int bd = Integer.MAX_VALUE;
		for (int y = Math.min(nearY + 10, level.getMaxY() - 8); y >= Math.max(nearY - 16, level.getMinY() + 1); y--) {
			BlockPos c = new BlockPos(x, y, z);
			BlockState st = level.getBlockState(c), head = level.getBlockState(c.above()), under = level.getBlockState(c.below());
			if (!st.getCollisionShape(level, c).isEmpty() || !head.getCollisionShape(level, c.above()).isEmpty()) continue;
			if (!st.getFluidState().isEmpty() || !head.getFluidState().isEmpty()) continue;
			if (!under.isFaceSturdy(level, c.below(), Direction.UP) || lava(level, c) || lava(level, c.below())) continue;
			int d = Math.abs(y - nearY);
			if (d < bd) { bd = d; best = c; }
		}
		return best;
	}

	/** The cell to stand in on the ground at a column: under trees and plants, on what holds the buddy, out of water. */
	static BlockPos surface(Level level, int x, int z) {
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		if (y <= level.getMinY()) y = Math.min(level.getMaxY(), 200);
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, y, z);
		for (int i = 0; i < 120 && m.getY() > level.getMinY(); i++) {
			BlockState st = level.getBlockState(m);
			boolean soft = st.isAir() || st.is(net.minecraft.tags.BlockTags.LEAVES) || st.is(net.minecraft.tags.BlockTags.LOGS) || st.canBeReplaced()
				|| st.getCollisionShape(level, m).isEmpty();
			if (!soft || !st.getFluidState().isEmpty()) break;
			m.move(Direction.DOWN);
		}
		if (!level.getFluidState(m).isEmpty()) return null;
		BlockState top = level.getBlockState(m);
		if (top.isAir() || top.getCollisionShape(level, m).isEmpty()) return null;
		return m.above().immutable();
	}

	/** Lava or water beside a block: breaking it lets them in. */
	static boolean wet(Level level, BlockPos c) {
		for (Direction d : Direction.values()) if (!level.getFluidState(c.relative(d)).isEmpty()) return true;
		return false;
	}

	static boolean lava(Level level, BlockPos c) {
		for (Direction d : Direction.values()) if (level.getFluidState(c.relative(d)).is(net.minecraft.tags.FluidTags.LAVA)) return true;
		return false;
	}

	// ---------- searching ----------

	/** Walking outward over the ground in one direction, round water, to new places in view. */
	static final class Explore {
		double angle;
		BlockPos goal;
		long goalSince;
		int legs, noGround;

		/**
		 * Round where it started instead of on in one way: legs of a widening spiral about that point, the nearest ground
		 * first (AltoClef searches the nearest chunks it has not seen). For what lives anywhere near (animals); a straight
		 * search is for what lies far off (a desert's sand).
		 */
		boolean spiral;
		BlockPos centre;
		/** Legs round the present centre, and how often the centre moved on. */
		int here, moves;

		Explore(LocalPlayer p) { angle = Math.toRadians(p.getYRot() + 90); }

		Explore(LocalPlayer p, boolean spiral) { this(p); this.spiral = spiral; centre = p.blockPosition(); }

		boolean tick(Minecraft mc, LocalPlayer p, long now) {
			if (goal != null && (Math.hypot(p.getX() - goal.getX() - 0.5, p.getZ() - goal.getZ() - 0.5) < 3 || now - goalSince > 60_000)) goal = null;
			if (goal == null) {
				if (legs > 60) return false;
				goal = next(p.level(), p);
				goalSince = now;
				if (goal == null) {
					// no ground in reach on any side (a beach, an island, the sea): the next leg straight on over the water, which the
					// walker swims; the legs counted only as legs are walked (they ran out in three seconds on a coast, and every kind
					// of log was given up)
					angle += Math.PI / 2;
					if (++noGround < 4) return true;
					noGround = 0;
					goal = BlockPos.containing(p.getX() + Math.cos(angle) * 40, p.level().getSeaLevel(), p.getZ() + Math.sin(angle) * 40);
				}
				legs++;
				Exchange.emit("obtain", "action", "search", "x", goal.getX(), "y", goal.getY(), "z", goal.getZ(), "leg", legs);
			}
			if (!Work.approach(mc, p, "explore-" + legs, Vec3.atBottomCenterOf(goal), now)) { angle += Math.PI / 2; goal = null; }
			return true;
		}

		BlockPos next(Level level, LocalPlayer p) {
			if (spiral) {
				// two turns round one place with nothing found (r up to about 48, as far as an animal is seen): a new place 160
				// blocks on, a quarter turn further each time. Round one point it searched 39 legs in a birch wood and on a beach
				// with no cow, in a circle of a hundred blocks
				// (96 blocks, inside the ground the client has: at 160 the chunks were not there yet, no point was found, and it
				// moved on three times in a second and a half)
				if (here >= 14) {
					double a0 = angle + moves * Math.PI / 2;
					centre = BlockPos.containing(p.getX() + Math.cos(a0) * 96, centre.getY(), p.getZ() + Math.sin(a0) * 96);
					moves++;
					here = 0;
					Exchange.emit("obtain", "action", "search elsewhere", "x", centre.getX(), "z", centre.getZ());
				}
				// the point on the spiral: a seventh of a turn a leg, sixteen blocks further out a turn; a leg counts when it has one
				for (int t = 0; t < 4; t++) {
					double a = angle + (here + 1) * Math.PI * 2 / 7 + t * Math.PI / 3;
					double r = 16 + 16 * (here + 1 + t) / 7.0;
					int x = (int) Math.floor(centre.getX() + Math.cos(a) * r), z = (int) Math.floor(centre.getZ() + Math.sin(a) * r);
					if (!level.hasChunk(x >> 4, z >> 4)) continue;
					BlockPos stand = ground(level, x, z, p.getBlockY());
					if (stand == null || ObtainSafe.dangerous(stand)) continue;
					here++;
					return stand;
				}
				// nothing on this turn's points: on a little, so a turn over sea does not hold it
				here++;
				return null;
			}
			for (int t = 0; t < 8; t++) {
				double a = angle + (t % 2 == 0 ? 1 : -1) * ((t + 1) / 2) * Math.PI / 4;
				int x = (int) Math.floor(p.getX() + Math.cos(a) * 28), z = (int) Math.floor(p.getZ() + Math.sin(a) * 28);
				if (!level.hasChunk(x >> 4, z >> 4)) continue;
				BlockPos stand = ground(level, x, z, p.getBlockY());
				if (stand == null || ObtainSafe.dangerous(stand)) continue;
				angle = a;
				return stand;
			}
			return null;
		}
	}

	/**
	 * Down a staircase to a height, then along a mine there, two high and one wide: what it opens up the scan sees for
	 * real (anti-xray), and the step breaks what it wants of that. Never straight down, never into a cell beside lava or
	 * water (another way then), never onto a floor that is not there.
	 */
	static final class Tunnel {
		int dx, dz, turns;
		final int toY;
		BlockPos next;
		final Breaker breaker = new Breaker();
		final Pickup pickup;
		int steps;

		/** A block to dig to (an ore the buddy sees and cannot walk to), or null for a mine along one way. */
		final BlockPos goal;

		Tunnel(LocalPlayer p, int toY, Pickup pickup) { this(p, toY, pickup, null); }

		Tunnel(LocalPlayer p, int toY, Pickup pickup, BlockPos goal) {
			Direction d = p.getDirection();
			dx = d.getStepX(); dz = d.getStepZ();
			if (dx == 0 && dz == 0) dx = 1;
			this.toY = toY;
			this.pickup = pickup;
			this.goal = goal == null ? null : goal.immutable();
		}

		/** Toward the goal along the axis it is furthest off, until a turn sends it another way for a while. */
		void aim(BlockPos f) {
			if (goal == null || turns > 0) return;
			int gx = goal.getX() - f.getX(), gz = goal.getZ() - f.getZ();
			if (Math.abs(gx) >= Math.abs(gz) && gx != 0) { dx = Integer.signum(gx); dz = 0; }
			else if (gz != 0) { dx = 0; dz = Integer.signum(gz); }
		}

		void turn() { int t = dx; dx = -dz; dz = t; turns++; next = null; breaker.target = null; bridging = false; }

		/**
		 * Something over this cell that comes down when the cell under it opens: sand or gravel within three blocks up, or
		 * water or lava within two (a stair broke up under a lake: the sand fell in and the lake after it).
		 */
		static boolean loose(Level level, BlockPos c) {
			for (int k = 0; k < 3; k++) {
				BlockPos q = c.above(k);
				if (level.getBlockState(q).getBlock() instanceof net.minecraft.world.level.block.FallingBlock) return true;
				if (k < 2 && (!level.getFluidState(q).isEmpty() || wet(level, q))) return true;
			}
			return false;
		}

		/** RUN while it digs or walks on, FAIL when it cannot go any way. */
		Result tick(Minecraft mc, LocalPlayer p, long now) {
			Level level = p.level();
			if (!p.onGround() && !p.isInWater()) return Result.RUN;
			// the cell over the block it stands on: crouched at the edge of a bridge a player leans over the gap, and its own
			// position is the gap (a bridge counted 224 steps on one cell and stuck "not at the edge")
			BlockPos f = p.onGround() ? p.getOnPos().above() : p.blockPosition();
			if (next != null && f.equals(next)) { next = null; steps++; turns = 0; }
			// pushed or stepped back off its line (a mob, a fall onto the stairs): the next cell from where it stands now, or it
			// is a cell beside it at a corner, whose face it cannot see (a turn a second at one block)
			if (next != null && (Math.abs(next.getX() - f.getX()) + Math.abs(next.getZ() - f.getZ()) != 1 || next.getY() > f.getY() || next.getY() < f.getY() - 1)) {
				next = null;
				breaker.target = null;
			}
			if (turns >= 4) return Result.FAIL;
			// between two steps, standing: what fell in the last one is fetched (never while a step is half dug: the walk to it
			// took the buddy into the next cell before its headroom was open, and up the stairs again it could not)
			if (next == null && pickup.tick(mc, p, now, st -> true)) return Result.RUN;
			// a torch on the wall where it is dark, as when it digs along (Torches: never on a block still to be dug)
			if (next == null && Torches.count(p) > 0 && Torches.tick(mc, p, Direction.getApproximateNearest(dx, 0, dz), c -> false)) return Result.RUN;
			if (next == null) {
				aim(f);
				// toward a goal: down while it lies lower, level once at its height (an ore one above the floor is in reach)
				int targetY = goal == null ? toY : goal.getY();
				int up = f.getY() < targetY ? 1 : 0;
				// up: a stair cut into the rock, only where the block in front holds the step and nothing loose or wet lies over
				// the cells (a stair out of a hole the walker found no way out of)
				if (up > 0) {
					BlockPos step = f.offset(dx, 0, dz);
					if (!level.getBlockState(step).isFaceSturdy(level, step, Direction.UP) || loose(level, f.offset(dx, 3, dz)) || loose(level, f.above(3))) up = 0;
				}
				next = f.offset(dx, f.getY() > targetY ? -1 : up, dz);
				// a floor of its own ends a bridge: the twelve count again from the next gap
				if (level.getBlockState(next.below()).isFaceSturdy(level, next.below(), Direction.UP)) bridged = 0;
			}
			int dy = next.getY() - f.getY();
			// down: the cell above its head in the next column too, which the way back up jumps through
			List<BlockPos> cells = dy < 0 ? List.of(next.above(2), next.above(), next) : dy > 0 ? List.of(f.above(2), next.above(), next) : List.of(next.above(), next);
			for (BlockPos c : cells) if (wet(level, c) || !level.getFluidState(c).isEmpty()) return turn("water or lava", c);
			BlockPos fl = next.below();
			BlockState floor = level.getBlockState(fl);
			boolean noFloor = !floor.isFaceSturdy(level, fl, Direction.UP) || !floor.getFluidState().isEmpty() || floor.is(net.minecraft.tags.BlockTags.LEAVES);
			if (noFloor && dy < 0) {
				// a cave under the next step: across it level, the stairs go on down at its other side; but not for ever (a crust
				// over a long cave took it 120 blocks level at y 66 on its way to the iron at 16)
				if (++levelOver > 20) { levelOver = 0; return turn("no way down here", f); }
				next = f.offset(dx, 0, dz);
				return Result.RUN;
			}
			if (dy < 0) levelOver = 0;
			if (noFloor) {
				// a cave or a gap: a block against the side of its own floor, crouched at the edge, as a player bridges; never over
				// lava, never longer than twelve blocks (a ravine is turned from), and only with blocks to spare and never over a
				// drop a slip does not survive: three blocks at most under the cell (a bridge over a ravine 41 deep ended in a fall
				// when it gave up and let go of the crouch)
				if (bridged >= 12 || !floor.canBeReplaced() || !floor.getFluidState().isEmpty() || lavaUnder(level, fl, 10) || PathShape.throwaways(p) < 4
						|| ObtainSafe.drop(level, fl) > 3)
					return turn("no floor", fl);
				bridging = true;
			}
			if (lava(level, fl)) return turn("lava under the floor", fl);
			for (BlockPos c : cells) {
				BlockState st = level.getBlockState(c);
				if (st.getCollisionShape(level, c).isEmpty() && st.getFluidState().isEmpty()) continue;
				if (st.getDestroySpeed(level, c) < 0) return turn("unbreakable", c);
				if (level.getBlockEntity(c) != null) return turn("a block with contents", c);
				Result r = breaker.tick(mc, p, c, now);
				if (r == Result.DONE) { pickup.at(Vec3.atCenterOf(c)); continue; }
				// the pickaxe worn out on the way down (a wooden one through 45 blocks of terracotta): no turn helps, the step ends
				// and the plan makes a new one
				if (r == Result.FAIL && st.requiresCorrectToolForDrops() && Work.toolFor(p, st) == Work.NONE) {
					why = "no tool left for " + id(st);
					return Result.FAIL;
				}
				if (r == Result.FAIL) return turn("does not break", c);
				return Result.RUN;
			}
			for (BlockPos c : cells) if (!level.getBlockState(c).getCollisionShape(level, c).isEmpty()) return Result.RUN;
			if (bridging) {
				if (PathShape.tick(mc, p, next, f, PathShape.BRIDGE)) return Result.RUN;
				bridging = false;
				bridged++;
				if (level.getBlockState(fl).getCollisionShape(level, fl).isEmpty()) return turn("bridge failed", fl);
			}
			if (!Work.approach(mc, p, "tunnel-" + next.toShortString(), Vec3.atBottomCenterOf(next), now)) return turn("cannot step on", next);
			return Result.RUN;
		}

		boolean bridging;
		int bridged;
		/** Why it failed, when a turn cannot help. */
		String why;

		/** Lava in the column under this cell within so many blocks: a slip there is the end in hardcore. */
		static boolean lavaUnder(Level level, BlockPos c, int depth) {
			for (int i = 0; i <= depth; i++) {
				BlockPos q = c.below(i);
				if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA)) return true;
				if (i > 0 && !level.getBlockState(q).getCollisionShape(level, q).isEmpty()) return false;
			}
			return false;
		}

		Result turn(String why, BlockPos at) {
			// turned round and round at one place: the step back toward its goal undid each turn (at an ore beside water it
			// turned fourteen times in fourteen seconds and stood a minute): this way is no way
			if (turnedAt != null && turnedAt.distManhattan(at) <= 2) turnsHere++;
			else { turnedAt = at.immutable(); turnsHere = 1; }
			Exchange.emit("obtain", "action", "tunnel turns", "why", why, "x", at.getX(), "y", at.getY(), "z", at.getZ(), "turns", turns + 1, "here", turnsHere);
			if (turnsHere >= 6) { this.why = "turned round at one place: " + why; return Result.FAIL; }
			turn();
			return Result.RUN;
		}

		BlockPos turnedAt;
		int turnsHere, levelOver;
	}

	/** Break blocks of these kinds until it has the count, fetching what falls; searching when none is known. */
	static final class Mine extends Exec {
		private long toolLook;
		final Set<String> blocks;
		final String item;
		final int want;
		final Nature.Place place;
		BlockPos target;
		final Map<BlockPos, Long> skip = new HashMap<>();
		final Breaker breaker = new Breaker();
		final Pickup pickup = new Pickup();
		Explore explore;
		Tunnel tunnel;
		long progressAt;
		int lastCount;

		Mine(ObtainPlan.Step s, LocalPlayer p) {
			blocks = new LinkedHashSet<>(s.sources());
			item = s.item();
			want = count(p, item) + s.count();
			place = Nature.block(s.sources().get(0));
			progressAt = System.currentTimeMillis();
			lastCount = count(p, item);
		}

		Result tick(Minecraft mc, LocalPlayer p, long now) {
			int have = count(p, item);
			if (have >= want) return Result.DONE;
			if (have != lastCount) { lastCount = have; progressAt = now; }
			if (now - progressAt > 600_000) return fail("ten minutes without " + item);
			// no tool left that gets the drop (the pickaxe the plan counted on wore out): the plan again, which makes one first.
			// With the iron pickaxe gone it tunnelled on for diamonds with a stone one
			if (now >= toolLook) {
				toolLook = now + 2000;
				boolean any = false;
				for (String b : blocks) {
					var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.withDefaultNamespace(b));
					if (block != null && Work.toolFor(p, block.defaultBlockState()) != Work.NONE) { any = true; break; }
				}
				if (!any) return fail("no tool left for " + blocks);
			}
			// while Baritone mines it picks up what falls itself: a walk of the buddy's to an item would stop its mine
			if (tunnel == null && !Bari.mining() && pickup.tick(mc, p, now, st -> AfkHelperClient.itemId(st).equals(item) || KEEP.contains(AfkHelperClient.itemId(st)))) return Result.RUN;
			Level level = p.level();
			skip.values().removeIf(u -> u < now);
			if (target != null && !blocks.contains(id(level.getBlockState(target)))) {
				pickup.at(Vec3.atCenterOf(target));
				BlockScan.gone(id(level.getBlockState(target)), target);
				target = null;
			}
			// on the way: an ore the rest of the plan wants, open and in reach, taken now, as a player changes their plan when a
			// tunnel opens one (a diamond in the tunnel wall on the way to iron was left to be walked back to). And one of this
			// step's own kind much nearer than the target goes first
			if (extra == null && now >= extraLook) { extraLook = now + 1000; extra = onTheWay(level, p); }
			if (extra != null) {
				if (ObtainSafe.foes(p).isEmpty() && !lava(level, extra) && !wet(level, extra) && Work.toolFor(p, level.getBlockState(extra)) != Work.NONE
						&& BlockScan.ore(id(level.getBlockState(extra)))) {
					Result x = extraBreaker.tick(mc, p, extra, now);
					if (x == Result.RUN) return Result.RUN;
					if (x == Result.DONE) { pickup.at(Vec3.atCenterOf(extra)); Exchange.emit("obtain", "action", "on the way", "block", extraId, "x", extra.getX(), "y", extra.getY(), "z", extra.getZ()); }
				}
				extraSkip.add(extra);
				extra = null;
			}
			if (target != null && digTo != null && now >= nearerLook) {
				nearerLook = now + 2000;
				BlockPos alt = pick(level, p);
				if (alt != null && !alt.equals(target) && alt.distSqr(p.blockPosition()) * 4 < target.distSqr(p.blockPosition())) { target = alt; digTo = null; }
			}
			if (target == null) target = pick(level, p);
			if (target == null) return search(mc, p, now);
			if (tunnel != null && pickup.tick(mc, p, now, st -> AfkHelperClient.itemId(st).equals(item) || KEEP.contains(AfkHelperClient.itemId(st)))) return Result.RUN;
			if (digTo != null && digTo.goal.equals(target)) {
				Vec3 eye = p.getEyePosition();
				Vec3 aim = Work.aimPoint(level, p, eye, target);
				if (aim == null || eye.distanceTo(aim) > 4.4) {
					// no new cell dug or walked in twenty seconds: that ore (or log) is left (two minutes under a jungle tree digging
					// at a log eight blocks overhead, until a skeleton came)
					if (digTo.steps != digSteps) { digSteps = digTo.steps; digAt = now; }
					if (now - digAt > 20_000) { skip.put(target, now + 300_000); target = null; digTo = null; return Result.RUN; }
					if (digTo.tick(mc, p, now) == Result.FAIL) {
						if (digTo.why != null) return fail(digTo.why);
						skip.put(target, now + 300_000); target = null; digTo = null;
					}
					return Result.RUN;
				}
				digTo = null;
			}
			Result r = breaker.tick(mc, p, target, now);
			if (r == Result.DONE) { pickup.at(Vec3.atCenterOf(target)); target = null; }
			else if (r == Result.FAIL && "tool".equals(breaker.failed)) {
				BlockState ts = level.getBlockState(target);
				if (ts.requiresCorrectToolForDrops() && Work.toolFor(p, ts) == Work.NONE) return fail("no tool left for " + id(ts));
				skip.put(target, now + 120_000); target = null;
			}
			else if (r == Result.FAIL) {
				// in view and not to be walked to (in a cave under it, behind rock): dug to, as a player does, when close
				if (digTo == null && "reach".equals(breaker.failed) && target.distSqr(p.blockPosition()) <= 32 * 32 && p.getY() >= target.getY() - 2) {
					digTo = new Tunnel(p, target.getY(), pickup, target);
					digSteps = 0;
					digAt = now;
					Exchange.emit("obtain", "action", "digs to", "x", target.getX(), "y", target.getY(), "z", target.getZ());
				} else { skip.put(target, now + 120_000); target = null; digTo = null; }
			}
			return Result.RUN;
		}

		Tunnel digTo;
		int digSteps;
		long digAt;

		String detail() {
			return (target == null ? "no target" : "target " + target.toShortString() + " " + (breaker.obstacle != null ? "obstacle " + breaker.obstacle.toShortString() : "")
				+ (breaker.stand != null ? " stand " + breaker.stand.toShortString() : "") + (breaker.failed != null ? " last " + breaker.failed : ""))
				+ (digTo != null ? " digging to it" : "") + (tunnel != null ? " tunnel " + tunnel.steps + " steps" : "") + (explore != null ? " explore leg " + explore.legs : "")
				+ " skip " + skip.size();
		}

		BlockPos extra;
		String extraId;
		long extraLook, nearerLook;
		final Set<BlockPos> extraSkip = new java.util.HashSet<>();
		final Breaker extraBreaker = new Breaker();

		/**
		 * An ore the rest of the plan mines, not this step's, open and within reach of where the buddy stands: the nearest.
		 */
		BlockPos onTheWay(Level level, LocalPlayer p) {
			BlockPos best = null;
			double bd = 4.3 * 4.3;
			Vec3 eye = p.getEyePosition();
			for (String b : Obtain.wantedLater) {
				if (blocks.contains(b)) continue;
				for (BlockPos q : BlockScan.places(b)) {
					if (extraSkip.contains(q) || !b.equals(id(level.getBlockState(q)))) continue;
					double d = eye.distanceToSqr(Vec3.atCenterOf(q));
					if (d < bd && Work.aimPoint(level, p, eye, q) != null) { bd = d; best = q; extraId = b; }
				}
			}
			return best;
		}

		BlockPos pick(Level level, LocalPlayer p) {
			BlockPos best = null;
			double bd = 1e18;
			for (String b : blocks)
				for (BlockPos q : BlockScan.places(b)) {
					if (skip.containsKey(q) || !b.equals(id(level.getBlockState(q))) || lava(level, q) || ObtainSafe.dangerous(q)) continue;
					// further than this the walk seldom finds a way (a cave far below): the mine to its depth goes there
					if (q.distSqr(p.blockPosition()) > 24 * 24) continue;
					double d = q.distSqr(p.blockPosition()) + 4 * Math.pow(q.getY() - p.getY(), 2);
					if (d < bd) { bd = d; best = q; }
				}
			return best;
		}

		/**
		 * Where the buddy stands is no ground to dig from (a log, leaves, a block above the column's ground): the ground cell
		 * nearest it within a few columns, lower than it is; null when it stands on the ground.
		 */
		static BlockPos groundNear(Level level, LocalPlayer p) {
			BlockPos f = p.blockPosition();
			BlockState under = level.getBlockState(f.below());
			boolean tree = under.is(net.minecraft.tags.BlockTags.LOGS) || under.is(net.minecraft.tags.BlockTags.LEAVES);
			BlockPos own = surface(level, f.getX(), f.getZ());
			if (!tree && (own == null || own.getY() >= f.getY())) return null;
			BlockPos best = null;
			double bd = 1e9;
			for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
				BlockPos c = surface(level, f.getX() + dx, f.getZ() + dz);
				if (c == null || c.getY() >= f.getY()) continue;
				BlockState st = level.getBlockState(c.below());
				if (st.is(net.minecraft.tags.BlockTags.LOGS) || st.is(net.minecraft.tags.BlockTags.LEAVES)) continue;
				double d = dx * dx + dz * dz + (f.getY() - c.getY()) * 0.5;
				if (d < bd) { bd = d; best = c; }
			}
			return best;
		}

		/** None known: over the ground, or down to the depth it is found at and along a mine. */
		Result search(Minecraft mc, LocalPlayer p, long now) {
			boolean under = place != null && (place.where() == Nature.Where.UNDERGROUND || place.where() == Nature.Where.DEEP);
			// under the ground with Baritone there: its own mine at the ore's height, seen ores only (Bari). Only once at that
			// height: Baritone's legit mine runs its branch from where it stands and never goes down to it (its own step down is
			// commented out, "see if the GoalRunAway with maintain Y at 11 works even from the surface"), and from the surface
			// it found a path of no steps every few seconds for minutes. The buddy's own staircase below takes it down first.
			if (under && Bari.on()) {
				int y = place.bestY() == Integer.MIN_VALUE ? (int) p.getY() - 12 : Math.max(place.bestY(), p.level().getMinY() + 6);
				if (Math.abs(p.getBlockY() - y) <= 4) {
					if (tunnel != null) { tunnel = null; HeldKeys.releaseAll(); }
					return Bari.mine(blocks, y) ? Result.RUN : fail("Baritone's mine stopped for " + blocks);
				}
				if (Bari.mining()) Bari.stop();
			}
			if (under) {
				// on a trunk, in a canopy or a block over the ground: down to the ground first, where a staircase starts
				BlockPos down = groundNear(p.level(), p);
				if (down != null) {
					if (!Work.approach(mc, p, "ground-" + down.toShortString(), Vec3.atBottomCenterOf(down), now)) return fail("cannot get down to the ground");
					return Result.RUN;
				}
				if (tunnel == null) tunnel = new Tunnel(p, place.bestY() == Integer.MIN_VALUE ? (int) p.getY() - 12 : Math.max(place.bestY(), p.level().getMinY() + 6), pickup);
				Result r = tunnel.tick(mc, p, now);
				return r == Result.FAIL ? fail(tunnel.why != null ? tunnel.why : "the mine cannot go on any way") : Result.RUN;
			}
			if (explore == null) explore = new Explore(p);
			return explore.tick(mc, p, now) ? Result.RUN : fail("no " + blocks + " found");
		}
	}

	// ---------- killing ----------

	static final class Kill extends Exec {
		final String mob, item;
		final int want;
		LivingEntity target;
		final Pickup pickup = new Pickup();
		Explore explore;
		long progressAt;
		int lastCount;

		Kill(ObtainPlan.Step s, LocalPlayer p) {
			mob = s.sources().get(0);
			item = s.item();
			want = count(p, item) + s.count();
			progressAt = System.currentTimeMillis();
			lastCount = count(p, item);
		}

		Result tick(Minecraft mc, LocalPlayer p, long now) {
			int have = count(p, item);
			if (have >= want) return Result.DONE;
			if (have != lastCount) { lastCount = have; progressAt = now; }
			if (now - progressAt > 600_000) return fail("ten minutes without " + item);
			if (target != null && !target.isAlive()) { pickup.at(target.position()); target = null; }
			// what it is after lying about, whoever killed it (the fight's own blow killed a blaze and its rod stayed on the
			// floor)
			if (!pickup.busy()) for (var it : p.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, p.getBoundingBox().inflate(12, 4, 12)))
				if (it.isAlive() && AfkHelperClient.itemId(it.getItem()).equals(item) && !ObtainSafe.dangerous(it.blockPosition())) { pickup.at(it.position()); break; }
			if (pickup.tick(mc, p, now, st -> AfkHelperClient.itemId(st).equals(item) || KEEP.contains(AfkHelperClient.itemId(st)))) return Result.RUN;
			// a monster that fights by hand on its way to the buddy comes first, with the sword: the bow at a blaze 23 blocks
			// off while a wither skeleton came up gave the skeleton three blows and the buddy's life
			LivingEntity close = null;
			double cd = 8 * 8;
			for (var m : p.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, p.getBoundingBox().inflate(8, 4, 8))) {
				if (!m.isAlive() || ObtainSafe.shoots(m) || m instanceof net.minecraft.world.entity.monster.Creeper) continue;
				if (!(m.isAggressive() || m instanceof net.minecraft.world.entity.monster.skeleton.WitherSkeleton)) continue;
				double d = m.distanceToSqr(p);
				if (d < cd) { cd = d; close = m; }
			}
			if (close != null) {
				if (close.distanceTo(p) > 3.2) { Work.approach(mc, p, "kill-" + close.getId(), close.position(), now); return Result.RUN; }
				Follow.attack(mc, p, close);
				return Result.RUN;
			}
			if (target == null) {
				double bd = 1e18;
				for (LivingEntity e : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(48, 16, 48))) {
					if (!e.isAlive() || e.isBaby() || !BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().equals(mob) || ObtainSafe.dangerous(e.blockPosition())) continue;
					Long sk = skip.get(e.getId());
					if (sk != null && sk > now) continue;
					double d = e.distanceToSqr(p);
					if (d < bd) { bd = d; target = e; }
				}
			}
			if (target == null) {
				Nature.Place pl = Nature.mob(mob);
				if (pl != null && "fortress".equals(pl.hint()) && p.level().dimension().equals(Level.NETHER)) return fortress(mc, p, now);
				if (explore == null) explore = new Explore(p, true);
				return explore.tick(mc, p, now) ? Result.RUN : fail("no " + mob + " found");
			}
			// what flies or hangs out of reach (a blaze over a fortress' floor): the bow, standing (four minutes under two
			// blazes, on fire, its sword never used)
			if (Bow.wanted(p, target, Follow.reach(p, target), now - progressAt)) {
				Follow.stand();
				Bow.tick(mc, p, target);
				return Result.RUN;
			}
			// the fight takes it the last few blocks only: to it first (it stood a minute before a pig 20 away)
			if (target.distanceTo(p) > 3.5) {
				if (!Work.approach(mc, p, "kill-" + target.getId(), target.position(), now)) {
					skip.put(target.getId(), now + 20_000);   // an animal that wandered off: another try soon
					target = null;
				}
				return Result.RUN;
			}
			Follow.attack(mc, p, target);
			return Result.RUN;
		}

		final Map<Integer, Long> skip = new HashMap<>();

		/**
		 * A fortress' own monsters: to its bricks when the scan knows them, or else a tunnel through the netherrack at the
		 * height fortresses stand at, in legs that turn and grow, until it sees them; never a walk over the nether's open
		 * ground with its lava seas and ghasts (AltoClef searches the nether's surface; in hardcore a tunnel is the safe
		 * way). At the bricks it looks round them for the monsters.
		 */
		Result fortress(Minecraft mc, LocalPlayer p, long now) {
			Level level = p.level();
			BlockPos f = p.blockPosition(), brick = null;
			double bd = 200;
			for (String id : List.of("spawner", "nether_bricks")) {
				for (BlockPos q : BlockScan.places(id)) {
					double d = Math.sqrt(q.distSqr(f));
					if (d < bd && !ObtainSafe.dangerous(q)) { bd = d; brick = q; }
				}
				if (brick != null) break;
			}
			if (brick != null) {
				search = null;
				// by a spawner: its blazes come to it, close enough for a blow; standing there beats a walk round the fortress
				if (id(level.getBlockState(brick)).equals("spawner")) {
					if (spawnerStand == null || !spawnerFor.equals(brick)) { spawnerFor = brick.immutable(); spawnerStand = campCell(level, p, brick); }
					if (spawnerStand != null && p.blockPosition().distSqr(spawnerStand) > 2) {
						if (Work.approach(mc, p, "spawner-" + brick.toShortString(), Vec3.atBottomCenterOf(spawnerStand), now)) return Result.RUN;
						spawnerStand = null;
					} else if (spawnerStand != null) {
						Follow.stand();
						if (waitedSince > 0 && now - Math.max(waitedSince, progressAt) > 180_000) return fail("three minutes by the spawner without a " + mob);
						if (waitedSince == 0) { waitedSince = now; Exchange.emit("obtain", "action", "waits by the spawner", "x", brick.getX(), "y", brick.getY(), "z", brick.getZ()); }
						return Result.RUN;
					}
				}
				if (bd < 6) {
					// there: round the bricks for the monsters, the spawner's surroundings first
					if (explore == null || explore.centre == null || explore.centre.distSqr(brick) > 32 * 32) explore = new Explore(p, true);
					return explore.tick(mc, p, now) ? Result.RUN : fail("no " + mob + " at the fortress");
				}
				if (Work.approach(mc, p, "fortress-" + brick.toShortString(), Vec3.atCenterOf(brick), now)) return Result.RUN;
				if (toBrick == null || !brick.equals(toBrickGoal)) { toBrick = new Tunnel(p, brick.getY(), pickup, brick); toBrickGoal = brick.immutable(); }
				Result r = toBrick.tick(mc, p, now);
				return r == Result.FAIL ? fail("no way to the fortress: " + toBrick.why) : Result.RUN;
			}
			if (search == null || search.steps >= legLength) {
				if (search != null) { legs++; legLength = 160 + 80 * (legs / 2); }
				Direction d = search == null ? p.getDirection() : Direction.from2DDataValue((searchDir + legs) % 4);
				if (search == null) searchDir = d.get2DDataValue();
				BlockPos goal = f.relative(d, 600);
				int y = Math.max(48, Math.min(80, f.getY()));
				search = new Tunnel(p, y, pickup, goal);
				Exchange.emit("obtain", "action", "fortress search", "leg", legs, "dir", d.getName(), "y", y, "blocks", legLength);
			}
			if (legs > 12) return fail("no fortress in twelve legs of tunnel");
			Result r = search.tick(mc, p, now);
			if (r == Result.FAIL) { search = null; legs++; }
			return Result.RUN;
		}

		/**
		 * Where to wait for a spawner's blazes: six to eight blocks to its side, on a floor, the spawner in sight, no lava
		 * within two blocks. They spawn within four of it and come over one or two at a time; beside it four shoot at once
		 * (Soprano's LavaShore.campSpot; one that stood by a spawner burned).
		 */
		static BlockPos campCell(Level level, LocalPlayer p, BlockPos spawner) {
			BlockPos best = null;
			double bd = 1e18;
			for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) for (int dy = -3; dy <= 2; dy++) {
				double h = Math.hypot(dx, dz);
				if (h < 6 || h > 8.5) continue;
				BlockPos c = spawner.offset(dx, dy, dz);
				if (!level.getBlockState(c).canBeReplaced() || !level.getBlockState(c.above()).canBeReplaced()) continue;
				if (!level.getBlockState(c.below()).isFaceSturdy(level, c.below(), Direction.UP) || ObtainSafe.dangerous(c)) continue;
				boolean hot = false;
				for (BlockPos q : BlockPos.betweenClosed(c.offset(-2, -2, -2), c.offset(2, 1, 2))) if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA)) { hot = true; break; }
				if (hot) continue;
				Vec3 eye = Vec3.atBottomCenterOf(c).add(0, 1.62, 0);
				BlockHitResult hit = level.clip(new net.minecraft.world.level.ClipContext(eye, Vec3.atCenterOf(spawner),
					net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
				if (hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(spawner)) continue;
				double d = p.position().distanceToSqr(Vec3.atBottomCenterOf(c));
				if (d < bd) { bd = d; best = c.immutable(); }
			}
			return best;
		}

		Tunnel search, toBrick;
		BlockPos toBrickGoal, spawnerStand, spawnerFor;
		long waitedSince;
		int legs, searchDir, legLength = 160;

		String detail() {
			if (target != null) return mob + " " + Math.round(target.distanceTo(AfkHelperClient.mc().player)) + " away";
			if (search != null) return "fortress search, leg " + legs + ", " + search.steps + " of " + legLength;
			if (toBrick != null) return "to the fortress, " + toBrick.steps + " steps";
			return explore != null ? "searching, leg " + explore.legs : "no target";
		}
	}

	// ---------- a station: a crafting table or a furnace, near or placed ----------

	/** A station of this kind within reach and open, or placed from the inventory first. */
	static final class Station {
		final String block;
		BlockPos at, placeOn;
		boolean placed;
		long since, clickAt;
		BlockPos clicked;
		Direction clickFace;
		boolean wantOpen;

		/** Only one that stands there: none is placed (a chest of the base). */
		boolean noPlace;
		int approachFails;

		Station(String block) { this.block = block; }

		/** RUN while it walks, places or opens; DONE once its menu is open. */
		Result tick(Minecraft mc, LocalPlayer p, long now) {
			Level level = p.level();
			if (since == 0) since = now;
			if (now - since > 60_000) return Result.FAIL;
			if (at != null && !id(level.getBlockState(at)).equals(block)) at = null;
			if (at == null) {
				for (BlockPos q : BlockScan.places(block))
					if (id(level.getBlockState(q)).equals(block) && q.distSqr(p.blockPosition()) <= 12 * 12 && !Obtain.badStation(q)) { at = q; break; }
			}
			if (at == null) return noPlace ? Result.FAIL : place(mc, p, now);
			Vec3 eye = p.getEyePosition();
			Vec3 aim = Work.aimPoint(level, p, eye, at);
			if (aim == null || eye.distanceTo(aim) > 4.3) {
				BlockPos stand = Work.standCell(level, p, at, null, c -> true);
				if (stand == null || !Work.approach(mc, p, "station-" + at.toShortString(), Vec3.atBottomCenterOf(stand), now)) {
					// the walker's planner misses a way now and then (its 30 ms): three tries before it counts as out of reach
					if (stand != null && ++approachFails < 3) return Result.RUN;
					approachFails = 0;
					// one it cannot get at is no station for it: it is passed by, and one it carries is placed instead
					Obtain.stationOutOfReach(at);
					at = null;
					return count(p, block) > 0 ? Result.RUN : Result.FAIL;
				}
				return Result.RUN;
			}
			Follow.stand();
			Follow.aimExact(p, aim);
			clicked = at;
			clickFace = null;
			wantOpen = true;
			return Result.RUN;
		}

		int moves;
		BlockPos moveTo;

		/**
		 * Down on a free cell near the buddy: on top of any block with a sturdy top (leaves too), within reach and in sight,
		 * never in its own cells. No such cell here: a few steps away over the ground, and again.
		 */
		Result place(Minecraft mc, LocalPlayer p, long now) {
			Level level = p.level();
			// just placed: it stands there now and none is left in the bag (a furnace placed and given up as "no furnace" in the
			// same tick): the one that stands is the station
			if (placeOn != null && id(level.getBlockState(placeOn.above())).equals(block)) { at = placeOn.above(); placed = true; Exchange.emit("obtain", "action", "placed", "block", block); return Result.RUN; }
			if (count(p, block) == 0) return Result.FAIL;
			if (placeOn != null && !level.getBlockState(placeOn.above()).canBeReplaced() && !id(level.getBlockState(placeOn.above())).equals(block)) placeOn = null;
			if (placeOn == null && moveTo == null) placeOn = spot(level, p);
			// underground, shut in or in a one-wide tunnel: no free cell, and the surface is no place to walk to (it set off up
			// to the sky 35 blocks over its head). A wall block beside its feet is broken and the station goes in its place, as
			// a player makes room
			if (placeOn == null && moveTo == null && !level.canSeeSky(p.blockPosition())) {
				if (roomAt == null && roomTries < 4) roomAt = roomCell(level, p);
				if (roomAt != null) {
					Result r = room.tick(mc, p, roomAt, now);
					if (r == Result.RUN) return r;
					roomAt = null;
					roomTries++;
					return Result.RUN;
				}
			}
			if (placeOn == null) {
				if (moveTo == null) {
					if (!level.canSeeSky(p.blockPosition())) return Result.FAIL;
					if (++moves > 4) return Result.FAIL;
					double a = Math.toRadians(p.getYRot() + 90 * moves);
					moveTo = surface(level, (int) Math.floor(p.getX() + Math.cos(a) * 5), (int) Math.floor(p.getZ() + Math.sin(a) * 5));
					if (moveTo == null) return Result.RUN;
				}
				if (!Work.approach(mc, p, "station-" + moveTo.toShortString(), Vec3.atBottomCenterOf(moveTo), now)
						|| p.blockPosition().distSqr(moveTo) <= 2) { moveTo = null; }
				return Result.RUN;
			}
			if (id(level.getBlockState(placeOn.above())).equals(block)) { at = placeOn.above(); placed = true; Exchange.emit("obtain", "action", "placed", "block", block); return Result.RUN; }
			if (!hold(mc, p, st -> AfkHelperClient.itemId(st).equals(block), block)) return Result.RUN;
			Vec3 top = Vec3.atCenterOf(placeOn).add(0, 0.5, 0);
			Follow.stand();
			Follow.aimExact(p, top);
			clicked = placeOn;
			clickFace = Direction.UP;
			wantOpen = false;
			return Result.RUN;
		}

		final Breaker room = new Breaker();
		BlockPos roomAt;
		int roomTries;

		/** A block beside its feet that may go: ground or a block of its own, a floor under it, no water or lava about. */
		static BlockPos roomCell(Level level, LocalPlayer p) {
			BlockPos f = p.blockPosition();
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos c = f.relative(d);
				BlockState st = level.getBlockState(c);
				if (st.isAir() || !(Breaker.breakable(level, c) || ObtainSafe.throwaway(new net.minecraft.world.item.ItemStack(st.getBlock().asItem())))) continue;
				if (wet(level, c) || lava(level, c) || level.getBlockEntity(c) != null) continue;
				if (!level.getBlockState(c.below()).isFaceSturdy(level, c.below(), Direction.UP)) continue;
				return c;
			}
			return null;
		}

		/**
		 * The block to put it on: its top sturdy, the cell above free and not the buddy's, the top face in reach and in
		 * sight.
		 */
		static BlockPos spot(Level level, LocalPlayer p) {
			BlockPos f = p.blockPosition();
			Vec3 eye = p.getEyePosition();
			BlockPos best = null;
			double bd = 1e9;
			for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -2; dy <= 1; dy++) {
				BlockPos c = f.offset(dx, dy, dz);
				// never a cell its body is in, also not half (the game refuses a block there: a minute of clicks)
				if (c.equals(f) || c.equals(f.above()) || p.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(c))) continue;
				BlockState here = level.getBlockState(c);
				if (!here.canBeReplaced() || !here.getFluidState().isEmpty()) continue;
				BlockPos on = c.below();
				BlockState st = level.getBlockState(on);
				if (!st.isFaceSturdy(level, on, Direction.UP) || level.getBlockEntity(on) != null) continue;
				Vec3 top = Vec3.atCenterOf(on).add(0, 0.5, 0);
				double d = eye.distanceTo(top);
				if (d > 4.3) continue;
				BlockHitResult hit = level.clip(new net.minecraft.world.level.ClipContext(eye, top.add(0, 0.01, 0), net.minecraft.world.level.ClipContext.Block.OUTLINE,
					net.minecraft.world.level.ClipContext.Fluid.NONE, p));
				if (hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(on)) continue;
				if (d < bd) { bd = d; best = on; }
			}
			return best;
		}

		/** The click at the start of the tick: the table opened, or the block placed on top of the one under it. */
		void startTick(Minecraft mc) {
			LocalPlayer p = mc.player;
			BlockPos c = clicked;
			Direction face = clickFace;
			clicked = null;
			if (c == null || p == null || mc.gameMode == null || (wantOpen && p.isShiftKeyDown())) return;
			if (!(mc.hitResult instanceof BlockHitResult bh) || bh.getType() != HitResult.Type.BLOCK || !bh.getBlockPos().equals(c)) return;
			if (face != null && bh.getDirection() != face) return;
			long now = System.currentTimeMillis();
			if (now - clickAt < 600) return;
			clickAt = now;
			InteractionResult r = AfkHelperClient.useOn(p, InteractionHand.MAIN_HAND, bh);
			if (r.consumesAction()) Game.swingUse(p, InteractionHand.MAIN_HAND);
		}
	}

	/**
	 * A crafting table or furnace the buddy placed, broken and picked up again before it walks off: it is carried, not
	 * left in the wild (and on a server, not left on another's ground). A furnace gives what lay in it too.
	 */
	static final class Retrieve extends Exec {
		final BlockPos at;
		final String block;
		final int had;
		final Breaker breaker = new Breaker();
		final Pickup pickup = new Pickup();
		boolean broken;
		long since = System.currentTimeMillis(), brokenAt;

		Retrieve(BlockPos at, String block, LocalPlayer p) { this.at = at; this.block = block; this.had = count(p, block); }

		Result tick(Minecraft mc, LocalPlayer p, long now) {
			if (count(p, block) > had) return Result.DONE;
			if (now - since > 40_000) return fail("could not pick up the " + block);
			if (!broken) {
				if (!id(p.level().getBlockState(at)).equals(block)) { broken = true; brokenAt = now; pickup.at(Vec3.atCenterOf(at)); return Result.RUN; }
				Result r = breaker.tick(mc, p, at, now);
				if (r == Result.DONE) { broken = true; brokenAt = now; pickup.at(Vec3.atCenterOf(at)); }
				else if (r == Result.FAIL) return fail("could not break the " + block);
				return Result.RUN;
			}
			if (pickup.tick(mc, p, now, st -> true)) return Result.RUN;
			return now - brokenAt > 5000 ? fail("the " + block + " was not picked up") : Result.RUN;
		}
	}

	// ---------- a bucket filled ----------

	/**
	 * A bucket of water or lava: a source block found, a cell beside it that is no fluid, the bucket in hand and the view
	 * on the source as the game's own use sees it (a fluid ray, sources only), the use at the start of the tick. Lava from
	 * the side and from a cell with a floor, never stepping in: the walker keeps off lava already.
	 */
	static final class Fill extends Exec {
		final String fluid, result;
		final int want;
		final Map<BlockPos, Long> skip = new HashMap<>();
		BlockPos src, stand;
		boolean use;
		long since = System.currentTimeMillis(), useAt;
		Explore explore;

		Fill(ObtainPlan.Step s, LocalPlayer p, String fluid) {
			this.fluid = fluid;
			result = fluid + "_bucket";
			want = count(p, result) + s.count();
		}

		Result tick(Minecraft mc, LocalPlayer p, long now) {
			if (count(p, result) >= want) return Result.DONE;
			if (count(p, "bucket") == 0) return fail("no bucket");
			if (now - since > 300_000) return fail("five minutes without " + fluid);
			Level level = p.level();
			skip.values().removeIf(u -> u < now);
			if (src != null && (!level.getFluidState(src).isSource() || skip.containsKey(src))) src = null;
			if (src == null) {
				double bd = 1e18;
				for (BlockPos q : BlockScan.places(fluid)) {
					if (skip.containsKey(q) || !level.getFluidState(q).isSource()) continue;
					double d = q.distSqr(p.blockPosition());
					if (d < bd) { bd = d; src = q; }
				}
				stand = null;
			}
			// what the scan has not got to yet (just come to a new place: a basin four blocks off was "no lava in view"): the
			// blocks round it looked at directly
			if (src == null) {
				double bd = 1e18;
				BlockPos f = p.blockPosition();
				for (BlockPos q : BlockPos.betweenClosed(f.offset(-10, -6, -10), f.offset(10, 4, 10))) {
					if (!level.getFluidState(q).isSource() || !level.getFluidState(q).is(fluid.equals("lava") ? net.minecraft.tags.FluidTags.LAVA : net.minecraft.tags.FluidTags.WATER)) continue;
					if (skip.containsKey(q)) continue;
					double d = q.distSqr(f);
					if (d < bd) { bd = d; src = q.immutable(); }
				}
				stand = null;
			}
			if (src == null) {
				if (fluid.equals("lava") && now - since < 6000) { Follow.stand(); return Result.RUN; }
				if (fluid.equals("lava")) {
					int any = 0, sources = 0;
					BlockPos f = p.blockPosition();
					for (BlockPos q : BlockPos.betweenClosed(f.offset(-10, -6, -10), f.offset(10, 4, 10))) {
						if (level.getFluidState(q).is(net.minecraft.tags.FluidTags.LAVA)) { any++; if (level.getFluidState(q).isSource()) sources++; }
					}
					return fail("no lava in view (" + any + " lava cells, " + sources + " sources near, scan " + BlockScan.places("lava").size() + ")");
				}
				if (explore == null) explore = new Explore(p);
				return explore.tick(mc, p, now) ? Result.RUN : fail("no water found");
			}
			Vec3 eye = p.getEyePosition();
			Vec3 top = Vec3.atCenterOf(src).add(0, 0.35, 0);
			if (eye.distanceTo(top) <= 4.0 && sees(level, p, eye, top)) {
				Follow.stand();
				if (!hold(mc, p, st -> AfkHelperClient.itemId(st).equals("bucket"), "bucket")) return Result.RUN;
				Follow.aimExact(p, top);
				use = true;
				return Result.RUN;
			}
			use = false;
			// a cell a step back from the edge: the walker plans no way to a cell beside lava, so a stand by the pool was no way
			// at all and every source was passed by; the bucket reaches over one cell
			if (stand == null) stand = standFor(level, p, src);
			if (stand == null || !Work.approach(mc, p, "fill-" + src.toShortString(), Vec3.atBottomCenterOf(stand), now)) { skip.put(src, now + 120_000); src = null; }
			return Result.RUN;
		}

		/**
		 * A cell to scoop from: on a floor, dry, a step back from any lava, the source's top in reach and in sight from its
		 * eye. The walker's own choice of a cell aims at a block's shape, and a fluid has none (eight lava sources in a basin
		 * four blocks off were each passed by as out of reach).
		 */
		BlockPos standFor(Level level, LocalPlayer p, BlockPos src) {
			Vec3 top = Vec3.atCenterOf(src).add(0, 0.35, 0);
			BlockPos best = null;
			double bd = 1e18;
			for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -1; dy <= 3; dy++) {
				BlockPos c = src.offset(dx, dy, dz);
				if (!level.getFluidState(c).isEmpty() || !level.getFluidState(c.above()).isEmpty() || lava(level, c) || lava(level, c.below())) continue;
				double feet = Follow.floor(level, c);
				if (Double.isNaN(feet)) continue;
				Vec3 eye = new Vec3(c.getX() + 0.5, feet + p.getEyeHeight(), c.getZ() + 0.5);
				if (eye.distanceTo(top) > 3.8) continue;
				BlockHitResult hit = level.clip(new net.minecraft.world.level.ClipContext(eye, top.add(top.subtract(eye).normalize().scale(0.3)),
					net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY, p));
				if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(src)) continue;
				double d = p.position().distanceToSqr(Vec3.atBottomCenterOf(c));
				if (d < bd) { bd = d; best = c.immutable(); }
			}
			return best;
		}

		boolean sees(Level level, LocalPlayer p, Vec3 eye, Vec3 pt) {
			BlockHitResult hit = level.clip(new net.minecraft.world.level.ClipContext(eye, pt.add(pt.subtract(eye).normalize().scale(0.3)),
				net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY, p));
			return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(src);
		}

		void startTick(Minecraft mc) {
			LocalPlayer p = mc.player;
			if (!use || p == null || mc.gameMode == null || !AfkHelperClient.itemId(p.getMainHandItem()).equals("bucket")) return;
			long now = System.currentTimeMillis();
			if (now - useAt < 500) return;
			Vec3 eye = p.getEyePosition(), view = p.getViewVector(1f);
			BlockHitResult seen = p.level().clip(new net.minecraft.world.level.ClipContext(eye, eye.add(view.scale(p.blockInteractionRange())),
				net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY, p));
			if (seen.getType() != HitResult.Type.BLOCK || !seen.getBlockPos().equals(src)) return;
			useAt = now;
			InteractionResult r = mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
			if (r.consumesAction()) Game.swingUse(p, InteractionHand.MAIN_HAND);
		}

		String detail() { return src == null ? "no source" : "source " + src.toShortString() + (use ? " using" : ""); }
	}

	/**
	 * Obsidian cast in a mould (AltoClef's PlaceObsidianBucketTask): a pit one block deep dug in the floor beside the
	 * buddy, walls and floor of stone, lava poured into it from a bucket filled at a pool, water poured over it, the water
	 * scooped up again, and the obsidian mined. Never over a pool itself: the block mined there falls into the lava under
	 * it, and the edge of a pool is where a slip ends a hardcore world. One block a round.
	 */
	static final class Cast extends Exec {
		final int want;
		BlockPos pit, water;
		/** 0 a pit, 1 lava in the bucket, 2 to the pit and the lava in, 3 the water over it, 4 the water back, 5 mine. */
		int phase;
		Fill fill;
		final Breaker breaker = new Breaker();
		final Pickup pickup = new Pickup();
		final ObtainPlan.Step step;
		long since = System.currentTimeMillis(), phaseAt, useAt;
		/** What startTick does: 1 pour lava or water, 2 scoop water. */
		int use;
		BlockPos aimAt;
		Direction aimFace;
		int rounds;

		Cast(ObtainPlan.Step s, LocalPlayer p) {
			step = s;
			want = count(p, "obsidian") + s.count();
		}

		Result tick(Minecraft mc, LocalPlayer p, long now) {
			if (count(p, "obsidian") >= want && phase != 4) return Result.DONE;
			if (now - since > 20 * 60_000) return fail("twenty minutes without obsidian enough");
			if (count(p, "diamond_pickaxe") + count(p, "netherite_pickaxe") == 0) return fail("no diamond pickaxe");
			Level level = p.level();
			use = 0;
			if (phase != 1 && pickup.tick(mc, p, now, st -> AfkHelperClient.itemId(st).equals("obsidian"))) return Result.RUN;
			switch (phase) {
				case 0 -> {
					// the pit: a block of the floor beside the buddy, stone or earth all round and under it, no fluid near
					if (pit == null || !moulds(level, pit)) pit = mould(level, p);
					if (pit == null) return fail("no floor here to cast in");
					if (level.getBlockState(pit).isAir()) { phase = count(p, "lava_bucket") > 0 ? 2 : 1; phaseAt = now; return Result.RUN; }
					Result r = breaker.tick(mc, p, pit, now);
					if (r == Result.FAIL) { pit = null; return fail("the mould does not dig: " + breaker.failed); }
					return Result.RUN;
				}
				case 1 -> {
					if (count(p, "lava_bucket") > 0) { fill = null; phase = 2; phaseAt = now; return Result.RUN; }
					if (fill == null) fill = new Fill(new ObtainPlan.Step(ObtainPlan.Act.SPECIAL, "lava_bucket", 1, null, 1, List.of(), "fill lava"), p, "lava");
					Result r = fill.tick(mc, p, now);
					if (r == Result.FAIL) return fail("no lava: " + fill.why());
					return Result.RUN;
				}
				case 2 -> {
					if (level.getFluidState(pit).is(net.minecraft.tags.FluidTags.LAVA)) { phase = 3; phaseAt = now; return Result.RUN; }
					if (count(p, "lava_bucket") == 0) { phase = 1; return Result.RUN; }
					// its body over the pit or beside it: a new pit, never lava where it stands
					if (p.getBoundingBox().inflate(0.4, 0, 0.4).intersects(new net.minecraft.world.phys.AABB(pit).expandTowards(0, 2, 0))) { pit = null; phase = 0; return Result.RUN; }
					return pour(mc, p, now, "lava_bucket", pit.below(), Direction.UP);
				}
				case 3 -> {
					if (id(level.getBlockState(pit)).equals("obsidian")) { phase = 4; phaseAt = now; return Result.RUN; }
					if (now - phaseAt > 30_000) return fail("the water did not reach the lava");
					// the water on the floor beside the pit, never in its own cell: it runs over into the pit and onto the lava
					if (water != null && level.getFluidState(water).isSource()) { Follow.stand(); return Result.RUN; }
					if (water == null) {
						BlockPos me = p.blockPosition();
						BlockPos mine = null;
						for (Direction d : Direction.Plane.HORIZONTAL) {
							BlockPos w = pit.relative(d).above();
							if (!solidTop(level, pit.relative(d))) continue;
							if (w.equals(me) || w.equals(me.above())) { mine = w; continue; }
							water = w;
							break;
						}
						// the one side left is where it stands: a step off it first
						if (water == null && mine != null) {
							BlockPos away = null;
							for (Direction d : Direction.Plane.HORIZONTAL) {
								BlockPos c = mine.relative(d);
								if (!c.equals(pit.above()) && Follow.floor(level, c) == c.getY() && level.getFluidState(c).isEmpty() && !lava(level, c)) { away = c; break; }
							}
							if (away != null) { Work.approach(mc, p, "cast-aside", Vec3.atBottomCenterOf(away), now); return Result.RUN; }
						}
						if (water == null) return fail("no floor beside the mould");
					}
					return pour(mc, p, now, "water_bucket", water.below(), Direction.UP, water);
				}
				case 4 -> {
					// the water back into the bucket, from its source beside the pit
					BlockPos over = water;
					if (over == null || !level.getFluidState(over).isSource() || count(p, "water_bucket") > 0) {
						if (count(p, "water_bucket") == 0 && now - phaseAt > 15_000) return fail("the water is not to be scooped");
						if (count(p, "water_bucket") > 0) { phase = 5; phaseAt = now; water = null; }
						return Result.RUN;
					}
					return scoop(mc, p, now, over);
				}
				case 5 -> {
					if (!id(level.getBlockState(pit)).equals("obsidian")) {
						pickup.at(Vec3.atCenterOf(pit));
						rounds++;
						Exchange.emit("obtain", "action", "cast", "round", rounds, "have", count(p, "obsidian"));
						phase = 0;
						return Result.RUN;
					}
					Result r = breaker.tick(mc, p, pit, now);
					if (r == Result.FAIL) return fail("the obsidian does not break: " + breaker.failed);
					return Result.RUN;
				}
			}
			return Result.RUN;
		}

		Result pour(Minecraft mc, LocalPlayer p, long now, String bucket, BlockPos on, Direction face) { return pour(mc, p, now, bucket, on, face, on.relative(face)); }

		/**
		 * The bucket emptied against that face, so that what is in it goes into the cell beyond: from where the buddy stands,
		 * in reach.
		 */
		Result pour(Minecraft mc, LocalPlayer p, long now, String bucket, BlockPos on, Direction face, BlockPos into) {
			Vec3 eye = p.getEyePosition();
			Vec3 pt = Vec3.atCenterOf(on).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5));
			// from where the point itself is in sight: the walker's own cell choice saw the block somewhere, and two blocks off
			// the floor beside the mould hid its bottom (84 seconds aiming at the floor)
			if (eye.distanceTo(pt) > 4.0 || !faceInSight(p.level(), p, eye, pt, on, face)) {
				if (pourStand == null || pourFor == null || !pourFor.equals(on)) {
					pourFor = on.immutable();
					pourStand = pourCell(p.level(), p, on, face, into);
				}
				if (pourStand == null || !Work.approach(mc, p, "cast-" + on.toShortString(), Vec3.atBottomCenterOf(pourStand), now)) { pourStand = null; return fail("no place to pour from"); }
				if (p.blockPosition().equals(pourStand)) Follow.stand();
				return Result.RUN;
			}
			Follow.stand();
			if (!hold(mc, p, st -> AfkHelperClient.itemId(st).equals(bucket), bucket)) return Result.RUN;
			Follow.aimExact(p, pt);
			aimAt = on;
			aimFace = face;
			use = 1;
			return Result.RUN;
		}

		BlockPos pourStand, pourFor;

		static boolean faceInSight(Level level, LocalPlayer p, Vec3 eye, Vec3 pt, BlockPos on, Direction face) {
			BlockHitResult hit = level.clip(new net.minecraft.world.level.ClipContext(eye, pt.add(pt.subtract(eye).normalize().scale(0.2)),
				net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
			return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(on) && hit.getDirection() == face;
		}

		/** A dry cell on a floor, a step back from lava, not the cell poured into, with the face's middle in reach and sight. */
		BlockPos pourCell(Level level, LocalPlayer p, BlockPos on, Direction face, BlockPos into) {
			Vec3 pt = Vec3.atCenterOf(on).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5));
			BlockPos best = null;
			double bd = 1e18;
			for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -1; dy <= 3; dy++) {
				BlockPos c = on.offset(dx, dy, dz);
				if (c.equals(into) || c.equals(pit) || c.equals(into.above()) || !level.getFluidState(c).isEmpty() || lava(level, c) || lava(level, c.below())) continue;
				double feet = Follow.floor(level, c);
				if (Double.isNaN(feet)) continue;
				Vec3 eye = new Vec3(c.getX() + 0.5, feet + p.getEyeHeight(), c.getZ() + 0.5);
				if (eye.distanceTo(pt) > 3.8 || !faceInSight(level, p, eye, pt, on, face)) continue;
				double d = p.position().distanceToSqr(Vec3.atBottomCenterOf(c));
				if (d < bd) { bd = d; best = c.immutable(); }
			}
			return best;
		}

		Result scoop(Minecraft mc, LocalPlayer p, long now, BlockPos src) {
			Vec3 eye = p.getEyePosition();
			Vec3 top = Vec3.atCenterOf(src).add(0, 0.3, 0);
			if (eye.distanceTo(top) > 4.0) return fail("the water is out of reach");
			Follow.stand();
			if (!hold(mc, p, st -> AfkHelperClient.itemId(st).equals("bucket"), "bucket")) return Result.RUN;
			Follow.aimExact(p, top);
			aimAt = src;
			use = 2;
			return Result.RUN;
		}

		void startTick(Minecraft mc) {
			if (fill != null && phase == 1) { fill.startTick(mc); return; }
			LocalPlayer p = mc.player;
			if (use == 0 || p == null || mc.gameMode == null || aimAt == null) return;
			long now = System.currentTimeMillis();
			if (now - useAt < 600) return;
			Vec3 eye = p.getEyePosition(), view = p.getViewVector(1f);
			if (use == 2) {
				BlockHitResult seen = p.level().clip(new net.minecraft.world.level.ClipContext(eye, eye.add(view.scale(p.blockInteractionRange())),
					net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY, p));
				if (seen.getType() != HitResult.Type.BLOCK || !seen.getBlockPos().equals(aimAt)) return;
				useAt = now;
				InteractionResult r = mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
				if (r.consumesAction()) Game.swingUse(p, InteractionHand.MAIN_HAND);
				return;
			}
			// a filled bucket looks past fluids to the block behind: that face has to be the one aimed at
			BlockHitResult seen = p.level().clip(new net.minecraft.world.level.ClipContext(eye, eye.add(view.scale(p.blockInteractionRange())),
				net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
			if (seen.getType() != HitResult.Type.BLOCK || !seen.getBlockPos().equals(aimAt) || seen.getDirection() != aimFace) return;
			useAt = now;
			InteractionResult r = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, seen);
			if (!r.consumesAction()) r = mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
			if (r.consumesAction()) Game.swingUse(p, InteractionHand.MAIN_HAND);
		}

		/**
		 * A floor block beside the buddy that makes a mould: earth or stone, the same all round and under it, no fluid near.
		 */
		static BlockPos mould(Level level, LocalPlayer p) {
			BlockPos f = p.blockPosition();
			BlockPos best = null;
			double bd = 1e9;
			for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) for (int dy = -2; dy <= 1; dy++) {
				// two away at least: never a pit it stands half over when the lava goes in. A plant over it goes with the lava (on
				// grass with flowers there was "no floor here to cast in")
				if (dx * dx + dz * dz < 4) continue;
				BlockPos c = f.offset(dx, dy - 1, dz);
				if (!moulds(level, c) || !level.getBlockState(c.above()).canBeReplaced() || !level.getBlockState(c.above(2)).canBeReplaced()
						|| !level.getFluidState(c.above()).isEmpty()) continue;
				double d = dx * dx + dz * dz + dy * dy * 2;
				if (d < bd) { bd = d; best = c; }
			}
			return best;
		}

		static boolean moulds(Level level, BlockPos c) {
			BlockState st = level.getBlockState(c);
			if (!(st.isAir() || ObtainSafe.earth(st) || id(st).equals("obsidian")) || level.getBlockEntity(c) != null) return false;
			if (!ObtainSafe.solid(level, c.below())) return false;
			for (Direction d : Direction.Plane.HORIZONTAL) if (!ObtainSafe.solid(level, c.relative(d))) return false;
			for (Direction d : Direction.values()) if (!level.getFluidState(c.relative(d)).isEmpty() && d != Direction.UP) return false;
			return true;
		}

		/** A floor block to pour the water on: its top sturdy, and over it nothing but air or a plant the water washes away. */
		static boolean solidTop(Level level, BlockPos c) {
			return level.getBlockState(c).isFaceSturdy(level, c, Direction.UP) && level.getBlockState(c.above()).canBeReplaced()
				&& level.getFluidState(c.above()).isEmpty();
		}

		String detail() { return "phase " + phase + (pit != null ? " pit " + pit.toShortString() : "") + " rounds " + rounds; }
	}

	/**
	 * A nether portal built where the buddy is: a flat spot four wide and five high, a frame of ten obsidian with corners
	 * of cobblestone (or any block to spend) so that every block leans on one placed before it, lit with flint and steel
	 * on the inside of its floor, and walked into. Done when the nether is around it.
	 */
	static final class Portal extends Exec {
		BlockPos o;
		Direction along, front;
		final ObtainSafe.Placer obsidian = new ObtainSafe.Placer(st -> AfkHelperClient.itemId(st).equals("obsidian"), "obsidian");
		final ObtainSafe.Placer corner = new ObtainSafe.Placer();
		ObtainSafe.Placer placing;
		/** 0 the site, 1 to its front, 2 the frame, 3 lit, 4 in it until the nether. */
		int phase;
		long since = System.currentTimeMillis(), phaseAt, lightAt;
		int tries;
		boolean light;

		Portal(ObtainPlan.Step s, LocalPlayer p) {}

		/** The frame from the bottom up, each with what it is; i along, j up from the cell over the ground. */
		List<Object[]> frame() {
			List<Object[]> out = new ArrayList<>();
			for (int i = 0; i < 4; i++) out.add(new Object[] { cell(i, 0), i == 0 || i == 3 ? "corner" : "obsidian" });
			for (int j = 1; j <= 3; j++) { out.add(new Object[] { cell(0, j), "obsidian" }); out.add(new Object[] { cell(3, j), "obsidian" }); }
			out.add(new Object[] { cell(0, 4), "corner" });
			out.add(new Object[] { cell(3, 4), "corner" });
			out.add(new Object[] { cell(1, 4), "obsidian" });
			out.add(new Object[] { cell(2, 4), "obsidian" });
			return out;
		}

		BlockPos cell(int i, int j) { return o.relative(along, i).above(j); }

		Result tick(Minecraft mc, LocalPlayer p, long now) {
			if (!p.level().dimension().identifier().getPath().equals("overworld")) return Result.DONE;
			if (now - since > 10 * 60_000) return fail("ten minutes without a portal");
			Level level = p.level();
			light = false;
			switch (phase) {
				case 0 -> {
					if (count(p, "obsidian") < 10) return fail("fewer than ten obsidian");
					if (count(p, "flint_and_steel") == 0) return fail("no flint and steel");
					if (!site(level, p)) return fail("no flat place four wide and five high here");
					Exchange.emit("obtain", "action", "portal site", "x", o.getX(), "y", o.getY(), "z", o.getZ(), "along", along.getName());
					phase = 1;
				}
				case 1 -> {
					BlockPos stand = o.relative(along, 1).relative(front, 2);
					if (p.blockPosition().equals(stand)) { phase = 2; return Result.RUN; }
					if (!Work.approach(mc, p, "portal-" + stand.toShortString(), Vec3.atBottomCenterOf(stand), now)) { if (++tries > 3) return fail("cannot get in front of the site"); phase = 0; }
				}
				case 2 -> {
					for (Object[] c : frame()) {
						BlockPos q = (BlockPos) c[0];
						boolean isObsidian = "obsidian".equals(c[1]);
						BlockState st = level.getBlockState(q);
						if (isObsidian ? id(st).equals("obsidian") : ObtainSafe.solid(level, q)) continue;
						placing = isObsidian ? obsidian : corner;
						Result r = placing.tick(mc, p, q);
						if (r == Result.FAIL) return fail("the frame does not go up at " + q.toShortString() + (isObsidian && count(p, "obsidian") == 0 ? ": no obsidian left" : ""));
						return Result.RUN;
					}
					placing = null;
					phase = 3;
					phaseAt = now;
				}
				case 3 -> {
					if (id(level.getBlockState(cell(1, 1))).equals("nether_portal")) { phase = 4; phaseAt = now; return Result.RUN; }
					if (now - phaseAt > 20_000) return fail("it does not light");
					Follow.stand();
					if (!hold(mc, p, st -> AfkHelperClient.itemId(st).equals("flint_and_steel"), "flint and steel")) return Result.RUN;
					Follow.aimExact(p, Vec3.atCenterOf(cell(1, 0)).add(0, 0.5, 0));
					light = true;
				}
				case 4 -> {
					BlockPos in = cell(1, 1);
					if (!id(level.getBlockState(in)).equals("nether_portal")) return fail("the portal went out");
					if (now - phaseAt > 40_000) return fail("the portal does not take the buddy");
					if (!p.blockPosition().equals(in)) Work.approach(mc, p, "portal-in", Vec3.atBottomCenterOf(in), now);
					else Follow.stand();
				}
			}
			return Result.RUN;
		}

		/**
		 * Four cells along one way with ground under each, the cells over them free for five, and in front of the middle two
		 * a cell to stand on two away: nearest first, on the buddy's own level or one off.
		 */
		boolean site(Level level, LocalPlayer p) {
			BlockPos f = p.blockPosition();
			double bd = 1e9;
			o = null;
			for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) for (int dy = -1; dy <= 1; dy++)
				for (Direction a : new Direction[] { Direction.EAST, Direction.SOUTH }) for (Direction fr : new Direction[] { a.getClockWise(), a.getCounterClockWise() }) {
					BlockPos c = f.offset(dx, dy, dz);
					if (!fits(level, c, a, fr, p)) continue;
					double d = dx * dx + dz * dz + dy * dy;
					if (d < bd) { bd = d; o = c; along = a; front = fr; }
				}
			return o != null;
		}

		static boolean fits(Level level, BlockPos c, Direction a, Direction fr, LocalPlayer p) {
			for (int i = 0; i < 4; i++) {
				BlockPos g = c.relative(a, i).below();
				if (!ObtainSafe.solid(level, g)) return false;
				for (int j = 0; j < 5; j++) {
					BlockPos q = c.relative(a, i).above(j);
					BlockState st = level.getBlockState(q);
					if (!st.canBeReplaced() || !st.getFluidState().isEmpty() || p.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(q))) return false;
				}
			}
			BlockPos stand = c.relative(a, 1).relative(fr, 2);
			return ObtainSafe.solid(level, stand.below()) && level.getBlockState(stand).isAir() && level.getBlockState(stand.above()).isAir()
				&& level.getBlockState(c.relative(a, 1).relative(fr, 1)).isAir();
		}

		void startTick(Minecraft mc) {
			if (placing != null) { placing.startTick(mc); return; }
			LocalPlayer p = mc.player;
			if (!light || p == null || mc.gameMode == null) return;
			if (!(mc.hitResult instanceof BlockHitResult bh) || !bh.getBlockPos().equals(cell(1, 0)) || bh.getDirection() != Direction.UP) return;
			long now = System.currentTimeMillis();
			if (now - lightAt < 800) return;
			lightAt = now;
			InteractionResult r = AfkHelperClient.useOn(p, InteractionHand.MAIN_HAND, bh);
			if (r.consumesAction()) Game.swingUse(p, InteractionHand.MAIN_HAND);
		}

		String detail() { return "phase " + phase + (o != null ? " at " + o.toShortString() + " " + along.getName() : ""); }
	}

	/**
	 * Through a portal that stands (one it built or found): to the nearest nether portal block in view, into it, and still
	 * until the other dimension is around it.
	 */
	static final class Through extends Exec {
		final String from;
		BlockPos in;
		long since = System.currentTimeMillis(), inSince;
		Explore explore;

		Through(ObtainPlan.Step s, LocalPlayer p) { from = p.level().dimension().identifier().getPath(); }

		Result tick(Minecraft mc, LocalPlayer p, long now) {
			if (!p.level().dimension().identifier().getPath().equals(from)) return Result.DONE;
			if (now - since > 5 * 60_000) return fail("five minutes without getting through");
			Level level = p.level();
			if (in == null || !id(level.getBlockState(in)).equals("nether_portal")) {
				in = null;
				double bd = 1e18;
				for (BlockPos q : BlockScan.places("nether_portal")) {
					if (!id(level.getBlockState(q)).equals("nether_portal") || !ObtainSafe.solid(level, q.below()) && !id(level.getBlockState(q.below())).equals("nether_portal")) continue;
					double d = q.distSqr(p.blockPosition());
					if (d < bd) { bd = d; in = q; }
				}
				// the bottom cell of that portal: it is the one walked into
				while (in != null && id(level.getBlockState(in.below())).equals("nether_portal")) in = in.below();
			}
			if (in == null) {
				if (explore == null) explore = new Explore(p);
				return explore.tick(mc, p, now) ? Result.RUN : fail("no portal found");
			}
			if (p.blockPosition().equals(in)) { Follow.stand(); if (inSince == 0) inSince = now; return now - inSince > 30_000 ? fail("the portal does not take the buddy") : Result.RUN; }
			inSince = 0;
			if (!Work.approach(mc, p, "through-" + in.toShortString(), Vec3.atBottomCenterOf(in), now)) return fail("cannot get to the portal");
			return Result.RUN;
		}

		String detail() { return in == null ? "looking for a portal" : "portal " + in.toShortString(); }
	}

	/**
	 * An eye of ender thrown, and the way it flew: from under the open sky, a little up, and the eye followed where the
	 * client sees it until it has gone six blocks or is gone; what it dropped is picked up. {@code dove} when it went more
	 * down than along: the stronghold is underneath.
	 */
	static final class Throw {
		Vec3 origin, first, last, dir;
		long thrownAt, seenAt;
		boolean use, dove;
		int eyeId = -1;
		int before;

		Result tick(Minecraft mc, LocalPlayer p, long now, Pickup pickup) {
			Level level = p.level();
			if (thrownAt == 0) {
				if (count(p, "ender_eye") == 0) return Result.FAIL;
				Follow.stand();
				if (!hold(mc, p, st -> AfkHelperClient.itemId(st).equals("ender_eye"), "eye of ender")) return Result.RUN;
				Follow.aim(p, p.getEyePosition().add(Vec3.directionFromRotation(-15, p.getYRot()).scale(5)));
				before = count(p, "ender_eye");
				use = true;
				return Result.RUN;
			}
			use = false;
			if (now - thrownAt > 7000) return dir != null ? Result.DONE : Result.FAIL;
			net.minecraft.world.entity.projectile.EyeOfEnder eye = null;
			if (eyeId >= 0 && level.getEntity(eyeId) instanceof net.minecraft.world.entity.projectile.EyeOfEnder e) eye = e;
			if (eye == null && eyeId < 0) {
				for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.projectile.EyeOfEnder.class, p.getBoundingBox().inflate(4)))
					if (eyeId < 0) { eyeId = e.getId(); eye = e; first = e.position(); }
			}
			if (eye != null) {
				last = eye.position();
				seenAt = now;
				Vec3 d = last.subtract(first);
				double flat = Math.hypot(d.x, d.z);
				if (flat >= 6) { dir = new Vec3(d.x, 0, d.z).normalize(); return Result.DONE; }
				if (first.y - last.y > 1.5 && flat < 1.5) { dove = true; return Result.DONE; }
				return Result.RUN;
			}
			if (eyeId >= 0) {
				// gone before six blocks: what it flew counts if it flew three; it drops where it went out
				if (last != null) pickup.at(last);
				Vec3 d = last == null ? Vec3.ZERO : last.subtract(first);
				if (Math.hypot(d.x, d.z) >= 3) { dir = new Vec3(d.x, 0, d.z).normalize(); return Result.DONE; }
				if (last != null && first.y - last.y > 1) { dove = true; return Result.DONE; }
				return Result.FAIL;
			}
			return Result.RUN;
		}

		void startTick(Minecraft mc) {
			LocalPlayer p = mc.player;
			if (!use || p == null || mc.gameMode == null || !AfkHelperClient.itemId(p.getMainHandItem()).equals("ender_eye")) return;
			use = false;
			origin = p.position();
			thrownAt = System.currentTimeMillis();
			InteractionResult r = mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
			if (r.consumesAction()) Game.swingUse(p, InteractionHand.MAIN_HAND);
		}
	}

	/**
	 * The stronghold by eyes of ender (AltoClef's LocateStrongholdTask): a throw from under the open sky, 200 blocks to
	 * the side, a second throw; where the two lines cross, walked to in legs; a throw again close by, which dives when it
	 * is underneath; then down a staircase to the height of strongholds while the scan looks for its portal room. Done
	 * when a frame of the portal is seen.
	 */
	static final class FindStronghold extends Exec {
		/** 0 first throw, 1 to the side, 2 second throw, 3 to the crossing, 4 a throw close by, 5 down. */
		int phase;
		Throw thrown;
		Vec3 o1, d1, o2, d2;
		double[] at;
		BlockPos goal, leg;
		long since = System.currentTimeMillis(), legAt;
		int legs, throwsDone;
		final Pickup pickup = new Pickup();
		Tunnel tunnel;

		FindStronghold(ObtainPlan.Step s, LocalPlayer p) {}

		Result tick(Minecraft mc, LocalPlayer p, long now) {
			if (seen(p)) return Result.DONE;
			if (now - since > 60 * 60_000) return fail("an hour without the stronghold");
			Level level = p.level();
			if (pickup.tick(mc, p, now, st -> AfkHelperClient.itemId(st).equals("ender_eye"))) return Result.RUN;
			switch (phase) {
				case 0, 2, 4 -> {
					if (thrown == null) {
						if (!level.canSeeSky(p.blockPosition())) {
							BlockPos up = surface(level, p.getBlockX(), p.getBlockZ());
							if (up == null || !Work.approach(mc, p, "sky-" + up.toShortString(), Vec3.atBottomCenterOf(up), now)) return fail("no open sky to throw under");
							return Result.RUN;
						}
						thrown = new Throw();
					}
					Result r = thrown.tick(mc, p, now, pickup);
					if (r == Result.RUN) return r;
					throwsDone++;
					if (r == Result.FAIL) {
						if (count(p, "ender_eye") == 0) return fail("no eyes left to throw");
						thrown = null;
						return throwsDone > 8 ? fail("the eyes do not show a way") : Result.RUN;
					}
					Exchange.emit("obtain", "action", "eye", "x", (int) thrown.origin.x, "z", (int) thrown.origin.z,
						"dir", thrown.dir == null ? null : Math.round(Math.toDegrees(Math.atan2(thrown.dir.z, thrown.dir.x))), "dove", thrown.dove);
					if (phase == 4 || thrown.dove) {
						phase = 5;
						tunnel = null;
						if (thrown.dir != null && !thrown.dove) { phase = 3; at = new double[] { p.getX() + thrown.dir.x * 40, p.getZ() + thrown.dir.z * 40 }; }
						thrown = null;
						return Result.RUN;
					}
					if (phase == 0) {
						o1 = thrown.origin; d1 = thrown.dir;
						// to the side: square to the line, 200 blocks
						goal = BlockPos.containing(o1.x - d1.z * 200, 0, o1.z + d1.x * 200);
						phase = 1;
					} else {
						o2 = thrown.origin; d2 = thrown.dir;
						at = Stronghold.cross(o1.x, o1.z, d1.x, d1.z, o2.x, o2.z, d2.x, d2.z);
						if (at == null) { o1 = o2; d1 = d2; goal = BlockPos.containing(o1.x - d1.z * 200, 0, o1.z + d1.x * 200); phase = 1; }
						else {
							Exchange.emit("obtain", "action", "stronghold", "x", Math.round(at[0]), "z", Math.round(at[1]));
							phase = 3;
						}
					}
					thrown = null;
					return Result.RUN;
				}
				case 1 -> {
					if (walk(mc, p, now, goal.getX(), goal.getZ(), 8)) phase = 2;
					return Result.RUN;
				}
				case 3 -> {
					if (walk(mc, p, now, (int) at[0], (int) at[1], 24)) { phase = 4; }
					return Result.RUN;
				}
				case 5 -> {
					// down to the height of a stronghold's rooms, and along a mine there toward the crossing
					if (tunnel == null) tunnel = new Tunnel(p, 30, pickup, at == null ? null : BlockPos.containing(at[0], 30, at[1]));
					Result r = tunnel.tick(mc, p, now);
					if (r == Result.FAIL) return fail(tunnel.why != null ? tunnel.why : "the mine to the stronghold cannot go on");
					return Result.RUN;
				}
			}
			return Result.RUN;
		}

		/** Toward a column over the ground in legs of 28: true when within {@code close} of it. */
		boolean walk(Minecraft mc, LocalPlayer p, long now, int x, int z, int close) {
			double dx = x + 0.5 - p.getX(), dz = z + 0.5 - p.getZ(), d = Math.hypot(dx, dz);
			if (d <= close) { leg = null; return true; }
			if (leg == null || p.blockPosition().distSqr(leg) < 9 || now - legAt > 45_000) {
				double a = Math.atan2(dz, dx);
				leg = null;
				for (int t = 0; t < 7 && leg == null; t++) {
					double b = a + (t % 2 == 0 ? 1 : -1) * ((t + 1) / 2) * Math.PI / 6;
					double step = Math.min(28, d);
					BlockPos c = surface(p.level(), (int) Math.floor(p.getX() + Math.cos(b) * step), (int) Math.floor(p.getZ() + Math.sin(b) * step));
					if (c != null && !ObtainSafe.dangerous(c)) leg = c;
				}
				legAt = now;
				legs++;
				if (leg == null) return false;
			}
			if (!Work.approach(mc, p, "toward-" + leg.toShortString(), Vec3.atBottomCenterOf(leg), now)) leg = null;
			return false;
		}

		boolean seen(LocalPlayer p) {
			for (BlockPos q : BlockScan.places("end_portal_frame")) {
				Obtain.portalRoom = q.immutable();
				Exchange.emit("obtain", "action", "stronghold found", "x", q.getX(), "y", q.getY(), "z", q.getZ());
				return true;
			}
			return false;
		}

		void startTick(Minecraft mc) { if (thrown != null) thrown.startTick(mc); else if (tunnel != null) {} }

		String detail() { return "phase " + phase + " throws " + throwsDone + (at != null ? " at " + Math.round(at[0]) + " " + Math.round(at[1]) : "") + " legs " + legs; }
	}

	/**
	 * The End's portal: to the portal room, an eye into every frame without one (a click on its top), and in. Done when
	 * the End is around the buddy.
	 */
	static final class EndPortal extends Exec {
		BlockPos frame;
		long since = System.currentTimeMillis(), clickAt;
		boolean use;

		EndPortal(ObtainPlan.Step s, LocalPlayer p) {}

		Result tick(Minecraft mc, LocalPlayer p, long now) {
			Level level = p.level();
			if (level.dimension().identifier().getPath().equals("the_end")) return Result.DONE;
			if (now - since > 15 * 60_000) return fail("a quarter of an hour at the portal");
			use = false;
			// the portal open: into it
			BlockPos in = null;
			double bd = 1e18;
			for (BlockPos q : BlockScan.places("end_portal")) {
				if (!id(level.getBlockState(q)).equals("end_portal")) continue;
				double d = q.distSqr(p.blockPosition());
				if (d < bd) { bd = d; in = q; }
			}
			if (in != null) {
				if (!Work.approach(mc, p, "end-" + in.toShortString(), Vec3.atBottomCenterOf(in), now)) return fail("cannot step into the portal");
				return Result.RUN;
			}
			// a frame without an eye, its top in reach
			if (frame == null || !empty(level, frame)) {
				frame = null;
				bd = 1e18;
				for (BlockPos q : BlockScan.places("end_portal_frame")) {
					if (!empty(level, q)) continue;
					double d = q.distSqr(p.blockPosition());
					if (d < bd) { bd = d; frame = q; }
				}
			}
			if (frame == null) return Obtain.portalRoom == null ? fail("no portal room in view") : approachRoom(mc, p, now);
			if (count(p, "ender_eye") == 0) return fail("no eye left for the frame at " + frame.toShortString());
			Vec3 top = Vec3.atCenterOf(frame).add(0, 0.3, 0);
			Vec3 eye = p.getEyePosition();
			if (eye.distanceTo(top) > 4.0) {
				BlockPos stand = Work.standCell(level, p, frame, null, c -> !id(level.getBlockState(c.below())).equals("end_portal") && level.getFluidState(c).isEmpty());
				if (stand == null || !Work.approach(mc, p, "frame-" + frame.toShortString(), Vec3.atBottomCenterOf(stand), now)) return fail("cannot get to the frame at " + frame.toShortString());
				return Result.RUN;
			}
			Follow.stand();
			if (!hold(mc, p, st -> AfkHelperClient.itemId(st).equals("ender_eye"), "eye of ender")) return Result.RUN;
			Follow.aimExact(p, top);
			use = true;
			return Result.RUN;
		}

		Result approachRoom(Minecraft mc, LocalPlayer p, long now) {
			BlockPos r = Obtain.portalRoom;
			if (!Work.approach(mc, p, "room-" + r.toShortString(), Vec3.atBottomCenterOf(r.above()), now)) return fail("cannot get to the portal room");
			return Result.RUN;
		}

		static boolean empty(Level level, BlockPos q) {
			BlockState st = level.getBlockState(q);
			return id(st).equals("end_portal_frame") && !st.getValue(net.minecraft.world.level.block.EndPortalFrameBlock.HAS_EYE);
		}

		void startTick(Minecraft mc) {
			LocalPlayer p = mc.player;
			if (!use || frame == null || p == null || mc.gameMode == null) return;
			if (!(mc.hitResult instanceof BlockHitResult bh) || !bh.getBlockPos().equals(frame) || bh.getDirection() != Direction.UP) return;
			long now = System.currentTimeMillis();
			if (now - clickAt < 700) return;
			clickAt = now;
			InteractionResult r = AfkHelperClient.useOn(p, InteractionHand.MAIN_HAND, bh);
			if (r.consumesAction()) Game.swingUse(p, InteractionHand.MAIN_HAND);
		}

		String detail() { return frame == null ? "no frame" : "frame " + frame.toShortString(); }
	}

	/** A log in the world clicked with an axe and chopped, until it has the stripped logs: the trees nearest first. */
	static final class Strip extends Exec {
		final String item, log;
		final int want;
		BlockPos target;
		final Breaker breaker = new Breaker();
		final Pickup pickup = new Pickup();
		final Map<BlockPos, Long> skip = new HashMap<>();
		Explore explore;
		long since = System.currentTimeMillis(), clickAt;
		boolean use;

		Strip(ObtainPlan.Step s, LocalPlayer p) {
			item = s.item();
			log = item.replace("stripped_", "");
			want = count(p, item) + s.count();
		}

		Result tick(Minecraft mc, LocalPlayer p, long now) {
			if (count(p, item) >= want) return Result.DONE;
			if (now - since > 10 * 60_000) return fail("ten minutes without " + item);
			use = false;
			Level level = p.level();
			if (pickup.tick(mc, p, now, st -> AfkHelperClient.itemId(st).equals(item))) return Result.RUN;
			skip.values().removeIf(u -> u < now);
			if (target != null) {
				String here = id(level.getBlockState(target));
				if (here.equals(item)) {
					Result r = breaker.tick(mc, p, target, now);
					if (r == Result.DONE) { pickup.at(Vec3.atCenterOf(target)); target = null; }
					else if (r == Result.FAIL) { skip.put(target, now + 120_000); target = null; }
					return Result.RUN;
				}
				if (!here.equals(log)) target = null;
			}
			if (target == null) {
				double bd = 1e18;
				for (BlockPos q : BlockScan.places(log)) {
					if (skip.containsKey(q) || !id(level.getBlockState(q)).equals(log) || ObtainSafe.dangerous(q)) continue;
					double d = q.distSqr(p.blockPosition()) + 4 * Math.pow(q.getY() - p.getY(), 2);
					if (d < bd) { bd = d; target = q; }
				}
			}
			if (target == null) {
				if (explore == null) explore = new Explore(p);
				return explore.tick(mc, p, now) ? Result.RUN : fail("no " + log + " found");
			}
			Vec3 eye = p.getEyePosition();
			Vec3 aim = Work.aimPoint(level, p, eye, target);
			if (aim == null || eye.distanceTo(aim) > 4.3) {
				BlockPos stand = Work.standCell(level, p, target, null, c -> true);
				if (stand == null || !Work.approach(mc, p, "strip-" + target.toShortString(), Vec3.atBottomCenterOf(stand), now)) { skip.put(target, now + 120_000); target = null; }
				return Result.RUN;
			}
			Follow.stand();
			if (!hold(mc, p, st -> st.is(net.minecraft.tags.ItemTags.AXES), "axe")) return Result.RUN;
			Follow.aimExact(p, aim);
			use = true;
			return Result.RUN;
		}

		void startTick(Minecraft mc) {
			LocalPlayer p = mc.player;
			if (!use || target == null || p == null || mc.gameMode == null || !p.getMainHandItem().is(net.minecraft.tags.ItemTags.AXES)) return;
			if (!(mc.hitResult instanceof BlockHitResult bh) || !bh.getBlockPos().equals(target)) return;
			long now = System.currentTimeMillis();
			if (now - clickAt < 600) return;
			clickAt = now;
			InteractionResult r = AfkHelperClient.useOn(p, InteractionHand.MAIN_HAND, bh);
			if (r.consumesAction()) Game.swingUse(p, InteractionHand.MAIN_HAND);
		}

		String detail() { return target == null ? "looking for " + log : log + " " + target.toShortString(); }
	}

	/**
	 * Out of a hole the walker finds no way out of: a stair up to the open sky, cut into the rock in the way it faces,
	 * turning where a step does not hold. Done once the sky is overhead.
	 */
	static final class DigOut extends Exec {
		final Tunnel tunnel;
		final Pickup pickup = new Pickup();
		final long since = System.currentTimeMillis();

		DigOut(LocalPlayer p) {
			int sky = p.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, p.getBlockX(), p.getBlockZ());
			tunnel = new Tunnel(p, Math.max(sky, p.getBlockY() + 2), pickup);
		}

		Result tick(Minecraft mc, LocalPlayer p, long now) {
			if (p.level().canSeeSky(p.blockPosition().above())) return Result.DONE;
			if (now - since > 180_000) return fail("three minutes without the sky");
			// no pickaxe left to cut a stair: back up the way it came, to where it last saw the sky (its own stair down is
			// a way the walker knows)
			if (walkBack || (Work.toolFor(p, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()) == Work.NONE && Obtain.lastSky() != null)) {
				walkBack = true;
				BlockPos sky = Obtain.lastSky();
				if (sky == null) return fail("no pickaxe and no way known up");
				if (Work.approach(mc, p, "back up-" + sky.toShortString(), Vec3.atBottomCenterOf(sky), now)) return Result.RUN;
				return fail("no pickaxe and no walk back up");
			}
			Result r = tunnel.tick(mc, p, now);
			return r == Result.FAIL ? fail(tunnel.why != null ? tunnel.why : "no stair up here") : Result.RUN;
		}

		boolean walkBack;

		String detail() { return "stair up, " + tunnel.steps + " steps"; }
	}

	// ---------- out of the base ----------

	/** Out of the base's chests that hold it (Stash's record of what lay in each): walked to, opened, shift-clicked out. */
	static final class Take extends Exec {
		final String item;
		final int want;
		Station chest;
		BlockPos at;
		final Set<BlockPos> tried = new HashSet<>();
		final Deque<Runnable> queue = new ArrayDeque<>();
		boolean filled;
		long nextClick;
		int before;

		Take(ObtainPlan.Step s, LocalPlayer p) { item = s.item(); want = count(p, item) + s.count(); }

		Result tick(Minecraft mc, LocalPlayer p, long now) {
			if (count(p, item) >= want) return Result.DONE;
			if (at == null) {
				for (BlockPos c : Stash.chestsWith(item, p.level().dimension().identifier().toString(), p.blockPosition()))
					if (!tried.contains(c)) { at = c; break; }
				if (at == null) return fail("no chest of the base holds " + item);
				// a chest, a barrel or a shulker box: the station is the block that stands there, never one placed
				chest = new Station(id(p.level().getBlockState(at)));
				chest.at = at;
				chest.noPlace = true;
			}
			if (Work.freeSlots(p) == 0) return fail("no room");
			Result st = chest.tick(mc, p, now);
			if (st == Result.FAIL) { tried.add(at); at = null; }
			return Result.RUN;
		}

		boolean inMenu(LocalPlayer p) {
			return at != null && (p.containerMenu instanceof net.minecraft.world.inventory.ChestMenu || p.containerMenu instanceof net.minecraft.world.inventory.ShulkerBoxMenu);
		}

		Result menuTick(Minecraft mc, LocalPlayer p, long now) {
			AbstractContainerMenu m = p.containerMenu;
			if (!filled) {
				filled = true;
				before = count(p, item);
				nextClick = now + 300;
				int id = m.containerId, size = m.slots.size() - 36, left = want - count(p, item);
				for (int i = 0; i < size && left > 0; i++) {
					ItemStack st = m.slots.get(i).getItem();
					if (st.isEmpty() || !AfkHelperClient.itemId(st).equals(item)) continue;
					int slot = i;
					queue.add(() -> Game.quickMove(mc.gameMode, id, slot, p));
					left -= st.getCount();
				}
			}
			if (now < nextClick) return Result.RUN;
			nextClick = now + 150;
			if (!queue.isEmpty()) { queue.poll().run(); return Result.RUN; }
			p.closeContainer();
			Stash.took(at, item, count(p, item) - before);
			tried.add(at);
			at = null;
			filled = false;
			return count(p, item) >= want ? Result.DONE : Result.RUN;
		}

		void startTick(Minecraft mc) { if (chest != null) chest.startTick(mc); }

		void menuClosed() { queue.clear(); filled = false; }
	}

	// ---------- crafting ----------

	/** Real clicks: the ingredients into the grid cell by cell, the result shift-clicked out, what is left back. */
	static final class Craft extends Exec {
		final Recipes.Recipe r;
		final String result;
		final int want;
		final Station table;
		final Deque<Runnable> queue = new ArrayDeque<>();
		boolean filled;
		long nextClick, menuSince;
		int rounds;

		Station station() { return table; }

		Craft(ObtainPlan.Step s, LocalPlayer p) {
			r = s.recipe();
			result = r.result();
			want = count(p, result) + s.count();
			table = r.small() ? null : new Station("crafting_table");
		}

		Result tick(Minecraft mc, LocalPlayer p, long now) {
			if (count(p, result) >= want) return Result.DONE;
			if (rounds > 6) return fail("the recipe does not come off");
			if (table == null) {
				// the 2x2 grid: no screen, clicks on the inventory itself
				if (!filled) { fill(mc, p, p.inventoryMenu); filled = true; nextClick = now; }
				return click(mc, p, now, p.inventoryMenu);
			}
			Result st = table.tick(mc, p, now);
			return st == Result.FAIL ? fail("no crafting table") : Result.RUN;
		}

		boolean inMenu(LocalPlayer p) { return table != null && p.containerMenu instanceof CraftingMenu; }

		Result menuTick(Minecraft mc, LocalPlayer p, long now) {
			AbstractContainerMenu m = p.containerMenu;
			if (menuSince == 0) { menuSince = now; nextClick = now + 300; }
			if (now - menuSince > 45_000) { p.closeContainer(); return fail("the crafting table took too long"); }
			if (!filled) { fill(mc, p, m); filled = true; }
			Result res = click(mc, p, now, m);
			if (res != Result.RUN) p.closeContainer();
			return res;
		}

		void startTick(Minecraft mc) { if (table != null) table.startTick(mc); }

		void menuClosed() { queue.clear(); filled = false; menuSince = 0; }

		BlockPos placedAt() { return station() != null && station().placed ? station().at : null; }

		String placedBlock() { return station() == null ? null : station().block; }

		void stop() {
			LocalPlayer p = AfkHelperClient.mc().player;
			if (p != null && p.containerMenu instanceof CraftingMenu) p.closeContainer();
		}

		/** One click per few ticks; when they are done, the result is counted. */
		Result click(Minecraft mc, LocalPlayer p, long now, AbstractContainerMenu m) {
			if (now < nextClick) return Result.RUN;
			nextClick = now + 150;
			if (!queue.isEmpty()) { queue.poll().run(); return Result.RUN; }
			if (count(p, result) >= want) return Result.DONE;
			// not there yet: the grid had too little, or a click went wrong; again, a few times at most
			filled = false;
			rounds++;
			return Result.RUN;
		}

		/** The clicks for as many crafts as the step wants and the inventory allows, in one go. */
		void fill(Minecraft mc, LocalPlayer p, AbstractContainerMenu m) {
			queue.clear();
			boolean inv = m == p.inventoryMenu;
			int g = inv ? 2 : 3, gridStart = 1;
			int n = m.slots.size();
			// the player's inventory index in this menu: the hotbar after the main part
			java.util.function.IntUnaryOperator slot = inv ? i -> i < 9 ? 36 + i : i : i -> (n - 36) + (i < 9 ? 27 + i : i - 9);
			int times = Math.max(1, (want - count(p, result) + r.count() - 1) / r.count());
			times = Math.min(times, 64);
			// which item fills each ingredient: the one it has most of
			Map<Recipes.Ing, String> choice = new HashMap<>();
			Map<Recipes.Ing, Integer> per = r.perCraft();
			for (Map.Entry<Recipes.Ing, Integer> e : per.entrySet()) {
				String bestItem = null; int bestN = -1;
				for (String it : e.getKey().items()) { int c = count(p, it); if (c > bestN) { bestN = c; bestItem = it; } }
				choice.put(e.getKey(), bestItem);
				times = Math.min(times, Math.max(1, bestN / e.getValue()));
			}
			List<int[]> cells = new ArrayList<>();   // {grid slot, ingredient index}
			List<Recipes.Ing> ings = new ArrayList<>();
			if (r.shaped()) {
				for (int y = 0; y < r.height(); y++) for (int x = 0; x < r.width(); x++) {
					Recipes.Ing i = r.grid()[y * r.width() + x];
					if (i == null) continue;
					ings.add(i);
					cells.add(new int[] { gridStart + y * g + x, ings.size() - 1 });
				}
			} else {
				for (int k = 0; k < r.parts().size(); k++) { ings.add(r.parts().get(k)); cells.add(new int[] { gridStart + k, k }); }
			}
			final int t = times;
			int menuId = m.containerId;
			for (int[] cell : cells) {
				String it = choice.get(ings.get(cell[1]));
				// pick a stack up, one into the cell per craft with the right button, the rest back where it was
				int left = t;
				var in = p.getInventory();
				for (int i = 0; i < 36 && left > 0; i++) {
					ItemStack st = in.getItem(i);
					if (st.isEmpty() || !AfkHelperClient.itemId(st).equals(it)) continue;
					int from = slot.applyAsInt(i), k = Math.min(left, st.getCount());
					queue.add(() -> Game.click(mc.gameMode, menuId, from, 0, false, p));
					for (int j = 0; j < k; j++) queue.add(() -> Game.click(mc.gameMode, menuId, cell[0], 1, false, p));
					queue.add(() -> Game.click(mc.gameMode, menuId, from, 0, false, p));
					left -= k;
				}
			}
			queue.add(() -> Game.quickMove(mc.gameMode, menuId, 0, p));
			int last = gridStart + g * g;
			queue.add(() -> { for (int s = gridStart; s < last; s++) if (!m.slots.get(s).getItem().isEmpty()) Game.quickMove(mc.gameMode, menuId, s, p); });
			Exchange.emit("obtain", "action", "craft", "recipe", r.id(), "times", t, "at", inv ? "grid" : "table");
		}
	}

	/**
	 * A furnace of this order that still holds what was put in when its step was broken off (the night, a flight): its
	 * result taken out, and what was not smelted yet with it.
	 */
	static final class Empty extends Exec {
		final Station furnace = new Station("furnace");
		final Deque<Runnable> queue = new ArrayDeque<>();
		boolean filled;
		long nextClick;

		/** That furnace and no other: none is placed for it, and one out of reach ends it. */
		Empty(BlockPos at) { furnace.at = at; furnace.noPlace = true; }

		Result tick(Minecraft mc, LocalPlayer p, long now) {
			Result st = furnace.tick(mc, p, now);
			return st == Result.FAIL ? fail("cannot open the furnace") : Result.RUN;
		}

		boolean inMenu(LocalPlayer p) { return p.containerMenu instanceof AbstractFurnaceMenu; }

		Result menuTick(Minecraft mc, LocalPlayer p, long now) {
			AbstractContainerMenu m = p.containerMenu;
			if (!filled) {
				filled = true;
				nextClick = now + 300;
				int id = m.containerId;
				// still burning with something in it: wait for it, as long as a smelt takes
				if (!m.slots.get(0).getItem().isEmpty() && !m.slots.get(1).getItem().isEmpty() && waited < 2) { waited++; p.closeContainer(); filled = false; return Result.WAIT; }
				queue.add(() -> Game.quickMove(mc.gameMode, id, 2, p));
				queue.add(() -> Game.quickMove(mc.gameMode, id, 0, p));
			}
			if (now < nextClick) return Result.RUN;
			nextClick = now + 150;
			if (!queue.isEmpty()) { queue.poll().run(); return Result.RUN; }
			p.closeContainer();
			return Result.DONE;
		}

		int waited;

		void startTick(Minecraft mc) { furnace.startTick(mc); }

		void menuClosed() { queue.clear(); filled = false; }
	}

	// ---------- smelting ----------

	static final class Smelt extends Exec {
		final Recipes.Recipe r;
		final String result;
		final int want, times;
		final Station furnace = new Station("furnace");
		final Deque<Runnable> queue = new ArrayDeque<>();
		int phase;   // 0 to the furnace and in, 1 waiting, 2 to the furnace and out
		long nextClick, readyAt, menuSince;
		boolean filled;

		Station station() { return furnace; }

		Smelt(ObtainPlan.Step s, LocalPlayer p) {
			r = s.recipe();
			result = r.result();
			times = s.times();
			want = count(p, result) + s.count();
		}

		Result tick(Minecraft mc, LocalPlayer p, long now) {
			if (count(p, result) >= want) return Result.DONE;
			if (phase == 1) {
				if (now < readyAt) { Follow.stand(); return Result.WAIT; }
				phase = 2;
				filled = false;
				furnace.since = 0;
			}
			Result st = furnace.tick(mc, p, now);
			return st == Result.FAIL ? fail("no furnace") : Result.RUN;
		}

		boolean inMenu(LocalPlayer p) { return p.containerMenu instanceof AbstractFurnaceMenu; }

		Result menuTick(Minecraft mc, LocalPlayer p, long now) {
			AbstractContainerMenu m = p.containerMenu;
			if (menuSince == 0 || !filled) { if (menuSince == 0) menuSince = now; }
			if (!filled) {
				queue.clear();
				filled = true;
				nextClick = now + 300;
				int menuId = m.containerId;
				java.util.function.IntUnaryOperator slot = i -> 3 + (i < 9 ? 27 + i : i - 9);
				if (phase == 0) {
					var in = p.getInventory();
					int left = times;
					for (int i = 0; i < 36 && left > 0; i++) {
						ItemStack st = in.getItem(i);
						if (st.isEmpty() || !r.parts().get(0).test(AfkHelperClient.itemId(st))) continue;
						int from = slot.applyAsInt(i), k = Math.min(left, st.getCount());
						queue.add(() -> Game.click(mc.gameMode, menuId, from, 0, false, p));
						for (int j = 0; j < k; j++) queue.add(() -> Game.click(mc.gameMode, menuId, 0, 1, false, p));
						queue.add(() -> Game.click(mc.gameMode, menuId, from, 0, false, p));
						left -= k;
					}
					double fuel = times;
					for (int i = 0; i < 36 && fuel > 0; i++) {
						ItemStack st = in.getItem(i);
						double v = st.isEmpty() ? 0 : ObtainPlan.fuelValue(AfkHelperClient.itemId(st));
						if (v <= 0 || r.parts().get(0).test(AfkHelperClient.itemId(st))) continue;
						int from = slot.applyAsInt(i), k = (int) Math.min(st.getCount(), Math.ceil(fuel / v));
						queue.add(() -> Game.click(mc.gameMode, menuId, from, 0, false, p));
						for (int j = 0; j < k; j++) queue.add(() -> Game.click(mc.gameMode, menuId, 1, 1, false, p));
						queue.add(() -> Game.click(mc.gameMode, menuId, from, 0, false, p));
						fuel -= k * v;
					}
				} else {
					queue.add(() -> Game.quickMove(mc.gameMode, menuId, 2, p));
				}
			}
			if (now < nextClick) return Result.RUN;
			nextClick = now + 150;
			if (!queue.isEmpty()) { queue.poll().run(); return Result.RUN; }
			p.closeContainer();
			if (phase == 0) {
				if (furnace.at != null) Obtain.furnaceHolds(furnace.at);
				phase = 1;
				readyAt = now + (long) times * r.cookTime() * 50 + 1500;
				Exchange.emit("obtain", "action", "smelting", "recipe", r.id(), "times", times, "s", (readyAt - now) / 1000);
				return Result.RUN;
			}
			if (count(p, result) >= want) return Result.DONE;
			// not all of it yet: wait a little more
			phase = 1;
			readyAt = now + 5000;
			return Result.RUN;
		}

		void startTick(Minecraft mc) { furnace.startTick(mc); }

		void menuClosed() { queue.clear(); filled = false; menuSince = 0; }

		BlockPos placedAt() { return station() != null && station().placed ? station().at : null; }

		String placedBlock() { return station() == null ? null : station().block; }

		void stop() {
			LocalPlayer p = AfkHelperClient.mc().player;
			if (p != null && p.containerMenu instanceof AbstractFurnaceMenu) p.closeContainer();
		}
	}

}
