package net.milenco.buddy.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.milenco.buddy.version.Game;

/**
 * Digging and placing as steps of the buddy's way (after SoulFire's and Baritone's moves). The plan knows three more moves
 * besides walking, jumping and swimming:
 * <ul>
 * <li><b>dig</b>: on to the next cell at the same height through one or two blocks of natural ground (earth, stone,
 * sand, leaves; never a block with contents, a placed-looking block, one beside water or lava, or one with sand or
 * gravel above it that would come down), at a cost of the time it takes with the best tool carried;</li>
 * <li><b>pillar</b>: one up from the cell stood in, by jumping and putting a block underfoot;</li>
 * <li><b>bridge</b>: across a cell without a floor, crouched at the edge with a block against the side of the buddy's own
 * floor.</li>
 * </ul>
 * They cost much more than walking, so the plan only takes them where walking does not get there. Placing takes a
 * block the buddy can spare (dirt, cobblestone and the like, {@link #THROWAWAY}); what is put down stays. A dig the server
 * refuses (a claim) or that does not end, twice at one spot, is not planned there for ten minutes (Failures).
 */
final class PathShape {
	private PathShape() {}

	static final byte DIG = 5, PILLAR = 6, BRIDGE = 7;

	/** What is put down to stand on: what the buddy digs out of the ground anyway. */
	static final Set<String> THROWAWAY = Set.of("dirt", "coarse_dirt", "cobblestone", "cobbled_deepslate", "netherrack",
		"andesite", "diorite", "granite", "tuff", "blackstone", "end_stone", "sandstone");

	// ---------- the plan ----------

	private static LocalPlayer planner;
	static boolean digOk, placeOk;
	private static final List<Vec3> FOES = new ArrayList<>();
	private static final Map<BlockPos, Integer> DANGER = new HashMap<>();

	/** Before a plan: what the buddy may do, and where the monsters are. */
	static void prepare(LocalPlayer p) {
		planner = p;
		AfkConfig c = AfkConfig.get();
		// no digging in or near a base, nor by chests known: there the blocks are someone's build, not the land (on the
		// first day the buddy dug two blocks in a base on the way to a chest); nor by a pen being farmed (Pen), which
		// is someone's farm, and where a pillar or a bridge is a step toward the pit
		boolean built = nearBuild(p) || Pen.near(p, Pen.NO_DIG_NEAR);
		digOk = c.pathDig && !built && !p.isCreative() && !p.isSpectator();
		// a block put down takes nothing of a build: by a base too, so a step two up or a gap there is crossed (on a high
		// island the buddy laid a short path of cobblestone and gave up within the base's 64 blocks, where the path was
		// easy). Not by a pen being farmed, where a pillar or a bridge is a step toward the pit
		placeOk = c.pathPlace && !Pen.near(p, Pen.NO_DIG_NEAR) && !p.isSpectator() && throwaways(p) > 0;
		DANGER.clear();
		FOES.clear();
		for (Entity e : p.level().getEntities(p, new AABB(p.blockPosition()).inflate(48), e -> e instanceof Enemy && e.isAlive()))
			FOES.add(e.position());
	}

	/** Within 64 blocks of a base's bed, or 32 of a known chest: someone builds here. */
	static boolean nearBuild(LocalPlayer p) {
		String dim = Work.dimOf(p.level());
		if (Tap.baseAt(dim, p.position(), 64) != null) return true;
		for (BlockPos c : Stash.known(dim)) if (Vec3.atCenterOf(c).distanceTo(p.position()) < 32) return true;
		return false;
	}

	static int throwaways(LocalPlayer p) {
		int n = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (!s.isEmpty() && THROWAWAY.contains(AfkHelperClient.itemId(s))) n += s.getCount();
		}
		return n;
	}

	/** A full block top to stand on. */
	static boolean sturdy(Level level, BlockPos below) {
		return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
	}

	/**
	 * What digging into this cell (feet and head) costs, -1 when it may not be dug. In the same units as the plan: ten a
	 * block walked, which is about four and a half ticks, so a tick of breaking is two, and each block 25 on top so that
	 * a short way round always wins.
	 */
	static int digCost(Level level, BlockPos cell) {
		if (!digOk || planner == null || !sturdy(level, cell.below())) return -1;
		if (Failures.blocked(Failures.near("dig", cell.getX(), cell.getY(), cell.getZ()))) return -1;
		if (level.getBlockState(cell.above(2)).getBlock() instanceof FallingBlock) return -1;
		int cost = 0;
		for (BlockPos b : new BlockPos[] { cell, cell.above() }) {
			BlockState st = level.getBlockState(b);
			if (st.getCollisionShape(level, b).isEmpty() && st.getFluidState().isEmpty()) continue;
			if (!diggable(level, b, st)) return -1;
			int t = breakTicks(level, b, st);
			if (t < 0) return -1;
			cost += 25 + 2 * t;
		}
		return cost;
	}

	private static boolean diggable(Level level, BlockPos pos, BlockState st) {
		if (!Work.ground(st) && !st.is(BlockTags.LEAVES)) return false;
		if (st.getBlock() instanceof FallingBlock) return false;
		return Work.safe(level, pos);
	}

	/** Ticks to break it with the best tool carried (no enchantments counted), -1 over three seconds or without a tool that gives it. */
	static int breakTicks(Level level, BlockPos pos, BlockState st) {
		float hard = st.getDestroySpeed(level, pos);
		if (hard < 0) return -1;
		if (hard == 0) return 1;
		boolean needs = st.requiresCorrectToolForDrops(), correct = !needs;
		float best = 1;
		for (int i = 0; i < 36; i++) {
			ItemStack s = planner.getInventory().getItem(i);
			if (s.isEmpty() || Work.nearlyBroken(s) || s.is(ItemTags.SWORDS) || s.is(Items.MACE) || s.is(Items.TRIDENT)) continue;
			boolean ok = !needs || s.isCorrectToolForDrops(st);
			float sp = s.getDestroySpeed(st);
			if (ok && (sp > best || !correct)) { best = Math.max(sp, 1); correct = true; }
		}
		if (!correct) return -1;
		int t = (int) Math.ceil(hard * 30 / best);
		return t > 60 ? -1 : t;
	}

	/**
	 * Extra cost for a cell near what hurts (lava, fire, magma, cactus, berry bushes, cobwebs, powder snow, wither roses)
	 * and near monsters: a way round is taken when there is one.
	 */
	static int danger(Level level, BlockPos cell) {
		Integer known = DANGER.get(cell);
		if (known != null) return known;
		int d = 0;
		search:
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) {
			BlockState st = level.getBlockState(cell.offset(dx, dy, dz));
			if (st.is(Blocks.LAVA) || st.is(BlockTags.FIRE) || st.is(Blocks.MAGMA_BLOCK) || st.is(Blocks.CACTUS)
					|| st.is(Blocks.SWEET_BERRY_BUSH) || st.is(Blocks.COBWEB) || st.is(Blocks.POWDER_SNOW) || st.is(Blocks.WITHER_ROSE)
					|| st.is(Blocks.CAMPFIRE) || st.is(Blocks.SOUL_CAMPFIRE)) { d += 30; break search; }
		}
		Vec3 c = Vec3.atBottomCenterOf(cell);
		int near = 0;
		for (Vec3 f : FOES) if (f.distanceToSqr(c) < 16 && ++near >= 3) break;
		d += 15 * near;
		DANGER.put(cell.immutable(), d);
		return d;
	}

	// ---------- walking the plan ----------

	private static BlockPos shaping, placeAt;
	private static Direction placeFace;
	private static long shapingSince;

	/**
	 * The step at this cell of the route is a dig, a pillar or a bridge: do it. True while busy (the walk waits); false
	 * when it is done and the cell can be walked into, which the caller then does.
	 */
	static boolean tick(Minecraft mc, LocalPlayer p, BlockPos wp, BlockPos from, byte mv) {
		Level level = p.level();
		long now = System.currentTimeMillis();
		if (!wp.equals(shaping)) { shaping = wp.immutable(); shapingSince = now; }
		if (now - shapingSince > 10_000) {
			shaping = null;
			Failures.failed(Failures.near("dig", wp.getX(), wp.getY(), wp.getZ()), mv == DIG ? "does not dig" : "does not build", wp.getX(), wp.getY(), wp.getZ());
			Follow.stuckAt(wp, mv == DIG ? "does not dig" : "does not build");
			return true;
		}
		BlockPos at = from;
		switch (mv) {
			case DIG -> {
				for (BlockPos b : new BlockPos[] { wp.above(), wp }) {
					BlockState st = level.getBlockState(b);
					if (st.getCollisionShape(level, b).isEmpty() && st.getFluidState().isEmpty()) continue;
					stopFeet(mc);
					if (!Work.breakForWalk(mc, p, b)) {
						Failures.failed(Failures.near("dig", wp.getX(), wp.getY(), wp.getZ()), "does not dig", b.getX(), b.getY(), b.getZ());
						Follow.stuckAt(wp, "does not dig");
					}
					return true;
				}
				done("dug", wp);
				return false;
			}
			case PILLAR -> {
				BlockPos base = wp.below();
				if (!level.getBlockState(base).getCollisionShape(level, base).isEmpty()) {
					HeldKeys.set(mc.options.keyJump, false);
					if (p.onGround() && p.getY() >= base.getY() + 0.9) { done("built up", wp); return false; }
					return true;
				}
				if (!block(mc, p)) return true;
				Vec3 mid = Vec3.atBottomCenterOf(base);
				double off = Math.hypot(mid.x - p.getX(), mid.z - p.getZ());
				if (off > 0.25 && p.onGround()) {
					// to the middle of the cell first, crouched so as not to step off
					Follow.aim(p, new Vec3(mid.x, p.getEyeY() - 1.2, mid.z));
					HeldKeys.set(mc.options.keyShift, true);
					HeldKeys.set(mc.options.keyUp, true);
					return true;
				}
				HeldKeys.set(mc.options.keyUp, false);
				HeldKeys.set(mc.options.keyShift, false);
				Follow.aim(p, new Vec3(mid.x, base.getY() + 0.01, mid.z));
				HeldKeys.set(mc.options.keyJump, p.onGround());
				if (!p.onGround() && p.getY() > base.getY() + 1.02) { placeAt = base.below(); placeFace = Direction.UP; }
				return true;
			}
			case BRIDGE -> {
				BlockPos target = wp.below();
				if (!level.getBlockState(target).getCollisionShape(level, target).isEmpty()) {
					HeldKeys.set(mc.options.keyShift, false);
					HeldKeys.set(mc.options.keyUp, false);
					done("bridged", wp);
					return false;
				}
				// the cell to bridge from is the one beside the gap on the buddy's side, worked out from the current place:
				// after a new plan the route's cell before it can be the gap's other neighbour (a first try stood still there)
				double ddx = wp.getX() + 0.5 - p.getX(), ddz = wp.getZ() + 0.5 - p.getZ();
				Direction dx = ddx > 0 ? Direction.EAST : Direction.WEST, dz = ddz > 0 ? Direction.SOUTH : Direction.NORTH;
				// the side it is mostly faced from, or the other where only that one has a floor (standing at a corner)
				Direction d = Math.abs(ddx) > Math.abs(ddz) ? dx : dz;
				if (!sturdy(level, wp.relative(d.getOpposite()).below())) d = d == dx ? dz : dx;
				at = wp.relative(d.getOpposite());
				if (!sturdy(level, at.below())) {
					Follow.stuckAt(wp, "bridge not at the edge");
					return true;
				}
				if (!block(mc, p)) return true;
				Vec3 stand = Vec3.atBottomCenterOf(at);
				double toStand = Math.hypot(stand.x - p.getX(), stand.z - p.getZ());
				double along = (p.getX() - stand.x) * d.getStepX() + (p.getZ() - stand.z) * d.getStepZ();
				if (toStand > 0.9 && along < 0) {
					// not on that cell yet: there first, crouched so as not to go over any edge
					HeldKeys.set(mc.options.keyShift, true);
					Follow.aim(p, new Vec3(stand.x, p.getEyeY() - 1.2, stand.z));
					HeldKeys.set(mc.options.keyUp, true);
					return true;
				}
				BlockPos against = at.below();
				Vec3 face = Vec3.atCenterOf(against).add(d.getStepX() * 0.5, 0, d.getStepZ() * 0.5);
				// how far the buddy leans past the side of the floor underfoot: crouched, the edge holds up to 0.3 beyond it
				double beyond = (p.getX() - face.x) * d.getStepX() + (p.getZ() - face.z) * d.getStepZ();
				HeldKeys.set(mc.options.keyShift, true);
				HeldKeys.set(mc.options.keySprint, false);
				if (beyond < 0.12) {
					Follow.aim(p, new Vec3(wp.getX() + 0.5, p.getEyeY() - 1.4, wp.getZ() + 0.5));
					HeldKeys.set(mc.options.keyUp, true);
				} else {
					HeldKeys.set(mc.options.keyUp, false);
					Follow.aim(p, face.add(-d.getStepX() * 0.02, -0.15, -d.getStepZ() * 0.02));
					placeAt = against;
					placeFace = d;
				}
				return true;
			}
			default -> { return false; }
		}
	}

	private static void done(String what, BlockPos wp) {
		shaping = null;
		placeAt = null;
		Failures.succeeded(Failures.near("dig", wp.getX(), wp.getY(), wp.getZ()));
		Exchange.emit("follow", "action", what, "x", wp.getX(), "y", wp.getY(), "z", wp.getZ());
	}

	/** The place a block goes against, at the start of the tick when the crosshair is on that face. */
	static void startTick(Minecraft mc) {
		BlockPos at = placeAt;
		Direction face = placeFace;
		placeAt = null;
		LocalPlayer p = mc.player;
		if (at == null || p == null || mc.gameMode == null) return;
		if (!THROWAWAY.contains(AfkHelperClient.itemId(p.getMainHandItem()))) return;
		if (!(mc.hitResult instanceof BlockHitResult bh) || bh.getType() != HitResult.Type.BLOCK) return;
		if (!bh.getBlockPos().equals(at) || bh.getDirection() != face) return;
		InteractionResult r = AfkHelperClient.useOn(p, InteractionHand.MAIN_HAND, bh);
		if (r.consumesAction()) Game.swingUse(p, InteractionHand.MAIN_HAND);
	}

	/** A block the buddy can spare in hand; from the backpack it goes to the hotbar first. */
	private static boolean block(Minecraft mc, LocalPlayer p) {
		var inv = p.getInventory();
		for (int i = 0; i < 9; i++) if (THROWAWAY.contains(AfkHelperClient.itemId(inv.getItem(i)))) {
			if (inv.getSelectedSlot() != i) inv.setSelectedSlot(i);
			return true;
		}
		if (throwaways(p) == 0) { Follow.stuckAt(shaping == null ? p.blockPosition() : shaping, "no blocks"); return false; }
		AfkHelperClient.toHotbar(p, mc, s -> THROWAWAY.contains(AfkHelperClient.itemId(s)), java.util.Comparator.comparingInt(ItemStack::getCount), "blocks");
		return false;
	}

	private static void stopFeet(Minecraft mc) {
		HeldKeys.set(mc.options.keyUp, false);
		HeldKeys.set(mc.options.keySprint, false);
		HeldKeys.set(mc.options.keyJump, false);
	}
}
