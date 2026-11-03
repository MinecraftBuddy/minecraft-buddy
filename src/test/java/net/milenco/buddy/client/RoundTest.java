package net.milenco.buddy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/**
 * The jump around a wall on the parkour course as the server builds it: a single
 * block, straight after it a pillar of obsidian four high, and the landing block straight behind the pillar, all in
 * the void. Here the start block is at x -1, the pillar at x 0 and the landing at x 1, their tops at y 101.
 */
class RoundTest {
	private static final int Y = 100;
	private static final AABB START = new AABB(-1, Y, 0, 0, Y + 1, 1);
	private static final AABB PILLAR = new AABB(0, Y, 0, 1, Y + 4, 1);
	private static final AABB LAND = new AABB(1, Y, 0, 2, Y + 1, 1);
	private static final BlockPos A = new BlockPos(-1, Y + 1, 0), B = new BlockPos(1, Y + 1, 0);

	private static Round.Plan best(List<AABB> obs, int side) {
		Round.Frame fr = new Round.Frame(START, A, B, side);
		double x = fr.x(Round.START_U, Round.START_W), z = fr.z(Round.START_U, Round.START_W);
		return Round.best(obs, fr, x, Y + 1, z, 0, 0, 0, Round.FIRSTS, Round.AIRS, Round.KS, Round.AIRS, LAND, Y + 1);
	}

	@Test
	void getsRoundThePillarOnEitherSide() {
		List<AABB> obs = List.of(START, PILLAR, LAND);
		for (int side : new int[] { 1, -1 }) {
			Round.Plan p = best(obs, side);
			assertNotNull(p, "no way round on side " + side);
			// the planner takes the jump only with this much over (PLAN_SPARE)
			assertTrue(p.score >= 0.06, "side " + side + " leaves only " + p.score);
		}
	}

	@Test
	void notRoundAWallThreeWide() {
		// the same pillar with a block beside it on either side: past its side is 1.5 blocks out, too far to come back in
		List<AABB> obs = List.of(START, LAND, new AABB(0, Y, -1, 1, Y + 4, 2));
		assertNull(best(obs, 1));
		assertNull(best(obs, -1));
	}

	@Test
	void notThroughAPillarStraightAhead() {
		// straight on from the middle of the block, with no steering, he runs into the pillar
		Round.Frame fr = new Round.Frame(START, A, B, 1);
		double x = fr.x(0, 0), z = fr.z(0, 0);
		assertTrue(Double.isNaN(Round.sim(List.of(START, PILLAR, LAND), fr, x, Y + 1, z, 0, 0, 0, true, 0, 0, 1, 0, LAND, Y + 1, null)));
	}

	@Test
	void frameTurnsBothWays() {
		for (int side : new int[] { 1, -1 }) {
			Round.Frame fr = new Round.Frame(START, A, B, side);
			double x = fr.x(0.4, -0.7), z = fr.z(0.4, -0.7);
			assertEquals(0.4, fr.u(x, z), 1e-9);
			assertEquals(-0.7, fr.w(x, z), 1e-9);
			for (double a : new double[] { -90, -30, 0, 15, 60 }) assertEquals(a, fr.rel(fr.yaw(a)), 1e-4);
		}
		// along +x the game's yaw is -90: moving along (-sin yaw, cos yaw)
		assertEquals(-90, new Round.Frame(START, A, B, 1).yaw(0), 1e-4);
	}
}
