package net.milenco.buddy.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Getting loose when a walk goes nowhere (after Player2's UnstuckChain). The walk's own check sees one second of
 * standing still with the walk key down; this one looks longer, while he has a route: twelve seconds without getting
 * more than a block and a bit from where he was, thirty seconds of going to and fro within four blocks over twenty
 * blocks of walking, or two seconds held in a cobweb, powder snow or a berry bush. Then he shuffles loose for one and a
 * half to three seconds (a random side, a step back, jumps), leaves the cells he wandered over out of his plans for a
 * minute, and plans anew. Not again within ten seconds; the same spot twice within ten minutes is told once (Failures).
 */
final class Unstuck {
	private Unstuck() {}

	private record Sample(long t, double x, double y, double z) {}

	private static final ArrayDeque<Sample> SEEN = new ArrayDeque<>();
	private static int shuffle, trapTicks, ticks;
	private static boolean toLeft, crouched;
	private static long coolUntil, lastMoving;

	static boolean busy() { return shuffle > 0; }

	/** Every tick of the chain, before the jobs; true while he shuffles loose. */
	static boolean tick(Minecraft mc, LocalPlayer p, boolean moving) {
		long now = System.currentTimeMillis();
		if (shuffle > 0) {
			int t = shuffle--;
			HeldKeys.set(mc.options.keyDown, t > 22);
			HeldKeys.set(mc.options.keyUp, t <= 22);
			HeldKeys.set(toLeft ? mc.options.keyLeft : mc.options.keyRight, true);
			HeldKeys.set(mc.options.keyJump, t % 10 < 3);
			// beside a deep drop: crouched and no jumps, so the shuffle cannot take the buddy over the edge (it jumped it into a
			// trench six deep, and off a cliff of fifteen blocks, 19 to 6)
			boolean brink = ObtainSafe.brinkNear(p);
			if (brink) { HeldKeys.set(mc.options.keyJump, false); HeldKeys.set(mc.options.keyShift, true); crouched = true; }
			else if (crouched) { HeldKeys.set(mc.options.keyShift, false); crouched = false; }
			if (shuffle == 0) {
				if (crouched) { HeldKeys.set(mc.options.keyShift, false); crouched = false; }
				for (var k : new net.minecraft.client.KeyMapping[] { mc.options.keyDown, mc.options.keyUp, mc.options.keyLeft, mc.options.keyRight, mc.options.keyJump })
					HeldKeys.set(k, false);
				List<BlockPos> cells = new ArrayList<>();
				for (Sample s : SEEN) cells.add(BlockPos.containing(s.x, s.y, s.z));
				Follow.unstuckDone(cells);
				SEEN.clear();
				coolUntil = now + 10_000;
			}
			return true;
		}
		if (!moving) {
			if (now - lastMoving > 3000) SEEN.clear();
			trapTicks = 0;
			return false;
		}
		lastMoving = now;
		BlockState feet = p.level().getBlockState(p.blockPosition()), head = p.level().getBlockState(p.blockPosition().above());
		String trap = trap(feet) != null ? trap(feet) : trap(head);
		trapTicks = trap == null ? 0 : trapTicks + 1;
		if (++ticks % 10 == 0) {
			SEEN.addLast(new Sample(now, p.getX(), p.getY(), p.getZ()));
			while (!SEEN.isEmpty() && now - SEEN.peekFirst().t > 32_000) SEEN.removeFirst();
		}
		if (now < coolUntil) return false;
		if (trapTicks > 40) return start(mc, p, "trap: " + trap);
		if (SEEN.size() < 2) return false;
		Sample last = SEEN.peekLast();
		// twelve seconds and never more than 1.2 from where he is now
		boolean still = true;
		Sample from = null;
		for (Sample s : SEEN) if (last.t - s.t <= 12_000) { if (from == null) from = s; if (dist(s, last) > 1.2) still = false; }
		if (still && from != null && last.t - from.t >= 12_000 - 600 && SEEN.peekFirst().t <= last.t - 12_000 + 600) return start(mc, p, "standstill");
		// thirty seconds of walking that stays inside four blocks
		if (last.t - SEEN.peekFirst().t >= 30_000) {
			double walked = 0, minX = 1e9, maxX = -1e9, minZ = 1e9, maxZ = -1e9;
			Sample prev = null;
			for (Sample s : SEEN) {
				if (prev != null) walked += dist(prev, s);
				prev = s;
				minX = Math.min(minX, s.x); maxX = Math.max(maxX, s.x); minZ = Math.min(minZ, s.z); maxZ = Math.max(maxZ, s.z);
			}
			if (walked > 20 && maxX - minX < 4 && maxZ - minZ < 4) return start(mc, p, "going to and fro");
		}
		return false;
	}

	private static String trap(BlockState s) {
		if (s.is(Blocks.COBWEB)) return "cobweb";
		if (s.is(Blocks.POWDER_SNOW)) return "powder_snow";
		if (s.is(Blocks.SWEET_BERRY_BUSH)) return "sweet_berry_bush";
		return null;
	}

	private static double dist(Sample a, Sample b) { return Math.sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z)); }

	private static boolean start(Minecraft mc, LocalPlayer p, String why) {
		shuffle = ThreadLocalRandom.current().nextInt(30, 61);
		toLeft = ThreadLocalRandom.current().nextBoolean();
		trapTicks = 0;
		Exchange.emit("follow", "action", "getting loose", "reason", why, "x", p.getBlockX(), "y", p.getBlockY(), "z", p.getBlockZ());
		Failures.failed(Failures.near("stuck", p.getX(), p.getY(), p.getZ()), why, p.getBlockX(), p.getBlockY(), p.getBlockZ());
		return true;
	}
}
