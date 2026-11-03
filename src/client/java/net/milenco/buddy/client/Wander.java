package net.milenco.buddy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Wandering while AFK: a route of 2-4 legs in random directions (free angles), each 1-4 blocks,
 * with pauses, glances and sometimes sneaking, and then back to the start via the same points.
 * Walks with the real walk key while steering the view direction, only over a flat, safe path checked beforehand.
 */
public class Wander {
	enum Phase { IDLE, TURN, WALK, PAUSE, DONE }
	private static Phase phase = Phase.IDLE;
	private static final List<Vec3> route = new ArrayList<>();   // waypoints out; back in reverse order
	private static int idx = 0;              // index of the next waypoint in 'plan'
	private static List<Vec3> plan = new ArrayList<>();
	private static int wait = 0, guard = 0, sneakTicks = 0;
	private static float startHp;
	private static boolean manual = false;
	private static final AfkHelperClient.Look look = new AfkHelperClient.Look();
	private static final ThreadLocalRandom R = ThreadLocalRandom.current();

	public static boolean busy() { return phase != Phase.IDLE; }
	public static boolean start(int maxBlocks) { return start(maxBlocks, false); }

	/** Plans a route and starts. False if no safe leg can be found. */
	public static boolean start(int maxBlocks, boolean isManual) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null || busy() || !p.onGround() || p.isInWater() || p.isInLava() || p.containerMenu != p.inventoryMenu) return false;
		Vec3 origin = new Vec3(p.getX(), p.getY(), p.getZ());
		route.clear();
		Vec3 cur = origin;
		int legs = R.nextInt(2, 5);
		for (int leg = 0; leg < legs; leg++) {
			Vec3 next = null;
			for (int attempt = 0; attempt < 12 && next == null; attempt++) {
				double yaw = R.nextDouble(0, 360);
				double len = R.nextDouble(1.0, maxBlocks + 0.5);
				double reach = safeReach(mc.level, cur, yaw, len);
				if (reach >= 1.0) {
					double rad = Math.toRadians(yaw);
					next = new Vec3(cur.x - Math.sin(rad) * reach, cur.y, cur.z + Math.cos(rad) * reach);
				}
			}
			if (next == null) break;
			route.add(next); cur = next;
		}
		if (route.isEmpty()) return false;
		plan = new ArrayList<>(route);
		for (int i = route.size() - 2; i >= 0; i--) plan.add(route.get(i));
		plan.add(origin);
		idx = 0; guard = 0; manual = isManual; startHp = p.getHealth();
		phase = Phase.TURN;
		look.start(p, plan.get(0).add(0, p.getEyeHeight() - 0.4, 0), 3f);
		Exchange.emit("wander", "action", "start", "legs", route.size(), "points", plan.size());
		return true;
	}

	/** How far (at most len) you can safely walk from 'from' at angle yaw; checks the blocks under the hitbox (0.6 wide) every 0.4 block. */
	static double safeReach(Level level, Vec3 from, double yaw, double len) {
		double rad = Math.toRadians(yaw), dx = -Math.sin(rad), dz = Math.cos(rad);
		double ok = 0;
		for (double s = 0.4; s <= len + 0.4; s += 0.4) {
			double x = from.x + dx * s, z = from.z + dz * s;
			boolean safe = true;
			for (double ox = -0.35; ox <= 0.35 && safe; ox += 0.7) for (double oz = -0.35; oz <= 0.35 && safe; oz += 0.7)
				safe = safeColumn(level, BlockPos.containing(x + ox, from.y, z + oz));
			if (!safe) break;
			ok = Math.min(s, len);
		}
		return ok;
	}

	static boolean safeColumn(Level level, BlockPos f) {
		BlockState below = level.getBlockState(f.below()), feet = level.getBlockState(f), head = level.getBlockState(f.above());
		if (!below.isCollisionShapeFullBlock(level, f.below())) return false;
		if (!below.getFluidState().isEmpty() || !feet.getFluidState().isEmpty() || !head.getFluidState().isEmpty()) return false;
		if (!feet.getCollisionShape(level, f).isEmpty() || !head.getCollisionShape(level, f.above()).isEmpty()) return false;
		if (below.is(Blocks.MAGMA_BLOCK) || below.is(Blocks.CAMPFIRE) || below.is(Blocks.SOUL_CAMPFIRE) || below.is(Blocks.POWDER_SNOW)) return false;
		if (feet.is(Blocks.SWEET_BERRY_BUSH) || feet.is(Blocks.CACTUS) || feet.is(Blocks.FIRE) || feet.is(Blocks.WITHER_ROSE)) return false;
		for (Direction s : Direction.Plane.HORIZONTAL) if (!level.getBlockState(f.relative(s)).getFluidState().isEmpty()) return false;
		return true;
	}

	private static void forward(boolean down) { HeldKeys.set(Minecraft.getInstance().options.keyUp, down); }
	private static void sneak(boolean down) { HeldKeys.set(Minecraft.getInstance().options.keyShift, down); }

	public static void abort(String why) {
		if (!busy()) return;
		forward(false); sneak(false);
		phase = Phase.IDLE;
		Exchange.emit("wander", "action", "abort", "why", why);
	}

	/** Every tick. True if the mod is moving by itself right now. */
	public static boolean tick(LocalPlayer p, AfkConfig c) {
		if (!busy()) return false;
		if (!c.afk && !manual) { abort("afk off"); return false; }
		if (p.getHealth() < startHp - 0.01f || p.isInWater() || p.isInLava() || p.containerMenu != p.inventoryMenu) { abort("danger/menu"); return false; }
		if (++guard > 20 * 90) { abort("timeout"); return false; }
		if (sneakTicks > 0 && --sneakTicks == 0) sneak(false);
		Vec3 goal = plan.get(Math.min(idx, plan.size() - 1));
		switch (phase) {
			case TURN -> { look.tick(p); if (!look.busy()) { forward(true); phase = Phase.WALK; } }
			case WALK -> {
				// steer toward the goal, with a little noise like a mouse movement
				double dx = goal.x - p.getX(), dz = goal.z - p.getZ();
				float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
				float delta = Mth.wrapDegrees(want - p.getYRot());
				MouseTurn.toward(p, p.getYRot() + Mth.clamp(delta, -6f, 6f) + (float) (R.nextDouble(-0.6, 0.6)),
					Mth.clamp(p.getXRot() + (float) R.nextDouble(-0.3, 0.3), -30f, 40f));
				if (Math.sqrt(dx * dx + dz * dz) < 0.3) {
					forward(false);
					idx++;
					if (idx >= plan.size()) { phase = Phase.DONE; wait = R.nextInt(10, 30); }
					else {
						phase = Phase.PAUSE; wait = R.nextInt(5, 50);
						int roll = R.nextInt(10);
						if (roll < 3) look.start(p, p.getEyePosition().add(R.nextDouble(-4, 4), R.nextDouble(-1.5, 1), R.nextDouble(-4, 4)), 3f);   // look around for a moment
						else if (roll < 5) { sneak(true); sneakTicks = R.nextInt(8, 25); }                                                        // sneak briefly
					}
				}
			}
			case PAUSE -> { look.tick(p); if (--wait <= 0 && !look.busy()) { phase = Phase.TURN; look.start(p, goal.add(0, p.getEyeHeight() - 0.4, 0), 3f); } }
			case DONE -> {
				// the last glance used to start after going IDLE, when nothing ticked it any more, so it never turned
				look.tick(p);
				if (wait > 0) {
					if (--wait == 0) {
						sneak(false);
						look.start(p, p.getEyePosition().add(R.nextDouble(-4, 4), R.nextDouble(-1, 1), R.nextDouble(-4, 4)), 3f);
					}
				} else if (!look.busy()) {
					phase = Phase.IDLE;
					Exchange.emit("wander", "action", "done");
				}
			}
			default -> { }
		}
		return true;
	}
}
