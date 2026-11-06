/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.sim;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// a staircase of full blocks going up +z, one block deep and one block high each, the worst case for chained ascends: every
// landing is a fraction of a block from the next face. the question was whether any keys at all get up it without a wall hit
// (they do, a search over every key finds them, and they are not slower than holding W and clipping), and then the guard is held
// to doing it with a rule instead of a search
public class AscendChainTest {

    // the top of the first step is at y = 65, its face at z = 12, step k covers z = 12 + k
    static final int FACE = 12;
    static final int FLOOR = 64;

    static ArrayWorld staircase(int steps) {
        ArrayWorld world = new ArrayWorld(16, 100, 64);
        world.fill(0, 50, 0, 15, FLOOR - 1, 63, ArrayWorld.STONE);
        for (int k = 0; k < steps; k++) {
            world.fill(0, FLOOR, FACE + k, 15, FLOOR + k, FACE + k, ArrayWorld.STONE);
        }
        world.fill(0, FLOOR, FACE + steps, 15, FLOOR + steps - 1, 63, ArrayWorld.STONE);
        return world;
    }

    static PlayerSim start(ArrayWorld world, double z, boolean sprint) {
        PlayerSim sim = new PlayerSim(PlayerSim.Params.vanilla(), world);
        sim.x = 8.5;
        sim.y = FLOOR;
        sim.z = z;
        sim.vy = -0.0784;
        sim.vz = sprint ? 0.1532 : 0.1179; // steady pace, 0.28 and 0.216 a tick
        sim.onGround = true;
        sim.sprinting = sprint;
        return sim;
    }

    // a non minor horizontal collision, the kind that costs the sprint
    static boolean wall(PlayerSim sim) {
        return sim.horizontalCollision && !sim.minorHorizontalCollision;
    }

    static boolean onTop(PlayerSim sim, int steps) {
        return sim.onGround && Math.abs(sim.y - (FLOOR + steps)) < 1.0E-3 && sim.z >= FACE + steps - 1;
    }

    interface Policy {
        PlayerSim.Controls pick(PlayerSim sim);
    }

    // what MovementAscend does when nobody is looking: W and the sprint key always, space once the middle of the next step is
    // within 1.2 blocks
    static PlayerSim.Controls movement(PlayerSim sim, int steps) {
        int k = (int) Math.round(sim.y - FLOOR);
        boolean near = k < steps && FACE + k + 0.5 - sim.z <= 1.2;
        return new PlayerSim.Controls(1, 0, sim.onGround && near, true, 0F, 0F);
    }

    static PlayerSim.Controls apply(AscendGuard.Move move, PlayerSim.Controls wanted) {
        switch (move) {
            case NO_FORWARD:
                return new PlayerSim.Controls(0, 0, wanted.jump(), wanted.sprint(), wanted.moveYaw(), wanted.viewYaw());
            case STAND_STILL:
                return new PlayerSim.Controls(0, 0, false, wanted.sprint(), wanted.moveYaw(), wanted.viewYaw());
            default:
                return wanted;
        }
    }

    // the step being hopped onto is the one above the floor we stand on, the same thing MovementAscend's dest.y is
    static Policy guarded(int steps) {
        return sim -> {
            PlayerSim.Controls wanted = movement(sim, steps);
            double hopY = Math.min(FLOOR + steps, Math.floor(sim.y + 1.0E-3) + 1);
            return apply(AscendGuard.decide(sim, wanted, hopY), wanted);
        };
    }

    // {ticks or -1, wall hits}
    static int[] run(PlayerSim from, int steps, int maxTicks, Policy policy) {
        PlayerSim sim = from.copy();
        int walls = 0;
        for (int t = 1; t <= maxTicks; t++) {
            if (!sim.tick(policy.pick(sim))) {
                return new int[]{-2, walls};
            }
            if (wall(sim)) {
                walls++;
            }
            if (onTop(sim, steps)) {
                return new int[]{t, walls};
            }
        }
        return new int[]{-1, walls};
    }

    @Test
    public void theGuardClimbsAStaircaseWithoutAWallHitInAsManyTicksAsClipping() {
        int steps = 6;
        ArrayWorld world = staircase(steps);
        for (boolean sprint : new boolean[]{true, false}) {
            int starts = 0, plainWalls = 0;
            long guardTicks = 0, plainTicks = 0;
            for (double z = 5.0; z <= 11.6; z += 0.05) {
                PlayerSim from = start(world, z, sprint);
                int[] guard = run(from, steps, 200, guarded(steps));
                int[] plain = run(from, steps, 200, sim -> movement(sim, steps));
                assertTrue("never got up from z=" + z + " sprinting=" + sprint, guard[0] > 0);
                assertEquals("wall hits from z=" + z + " sprinting=" + sprint, 0, guard[1]);
                starts++;
                plainWalls += plain[1];
                guardTicks += guard[0];
                plainTicks += plain[0];
            }
            // the old keys clip about once a step, so the comparison means something
            assertTrue("plain keys only clipped " + plainWalls + " times", plainWalls > starts * 4);
            assertTrue("guard " + guardTicks / (double) starts + " ticks, plain " + plainTicks / (double) starts,
                    guardTicks <= plainTicks + starts);
        }
    }

    @Test
    public void aSearchOverEveryKeyFindsCleanWaysUpAsFastAsClipping() {
        // breadth first over W, space and the sprint key each tick, never a wall hit. the first state on top of the last step is
        // reached in as many ticks as the old keys take to get there clipping, from a sprint start and from a walking one
        int steps = 4;
        ArrayWorld world = staircase(steps);
        for (boolean sprint : new boolean[]{true, false}) {
            PlayerSim from = start(world, 9.5, sprint);
            int ticks = search(from, steps, 3000, 60, true);
            int[] plain = run(from, steps, 200, sim -> movement(sim, steps));
            assertTrue("search " + ticks + " ticks, plain " + plain[0] + " (sprinting=" + sprint + ")", ticks > 0 && ticks <= plain[0]);
        }
    }

    @Test
    public void holdingWAndTimingTheJumpIsEnoughForOneStepAndNotForFour() {
        // what AscendHop does (W held, only the moment of space is chosen) is clean for a single step and for a pair, and
        // there is no clean way up four steps without letting go of W
        PlayerSim one = start(staircase(1), 9.0, true);
        assertTrue(search(one, 1, 3000, 60, false) > 0);
        PlayerSim four = start(staircase(4), 9.0, true);
        assertEquals(-1, search(four, 4, 3000, 60, false));
    }

    @Test
    public void aCleanSprintHopIsLeftAlone() {
        // one step, the player in the middle of the window AscendHopTest checks: nothing to take away
        PlayerSim sim = start(staircase(1), 10.0, true);
        PlayerSim.Controls wanted = new PlayerSim.Controls(1, 0, true, true, 0F, 0F);
        assertEquals(AscendGuard.Move.AS_WANTED, AscendGuard.decide(sim, wanted, FLOOR + 1));
    }

    @Test
    public void onTheStairsItLetsGoOfWAboutOnceAStep() {
        int steps = 6;
        PlayerSim sim = start(staircase(steps), 9.5, true);
        Policy policy = guarded(steps);
        int released = 0, ticks = 0;
        while (!onTop(sim, steps) && ticks++ < 200) {
            PlayerSim.Controls keys = policy.pick(sim);
            released += keys.forward() == 0 ? 1 : 0;
            sim.tick(keys);
        }
        assertTrue("released W on " + released + " of " + ticks + " ticks", released >= steps && released <= steps * 3);
    }

    @Test
    public void walkingToTheStepWithoutJumpingIsLeftAlone() {
        // the movement picks the moment for space. a walk towards the face is not the guard's to stop, the first touch of a wall
        // is a graze and the next tick lets go
        ArrayWorld world = staircase(1);
        PlayerSim.Controls wanted = new PlayerSim.Controls(1, 0, false, false, 0F, 0F);
        assertEquals(AscendGuard.Move.AS_WANTED, AscendGuard.decide(start(world, 9.0, false), wanted, FLOOR + 1));
        assertEquals(AscendGuard.Move.AS_WANTED, AscendGuard.decide(start(world, 11.62, false), wanted, FLOOR + 1));
    }

    @Test
    public void nothingIsTakenAwayWhenNothingIsPressed() {
        PlayerSim sim = start(staircase(1), 11.9, true);
        PlayerSim.Controls wanted = new PlayerSim.Controls(0, 0, false, false, 0F, 0F);
        assertEquals(AscendGuard.Move.AS_WANTED, AscendGuard.decide(sim, wanted, FLOOR + 1));
    }

    @Test
    public void aTickOfGuardingStaysUnderAMillisecond() {
        int steps = 6;
        ArrayWorld world = staircase(steps);
        List<PlayerSim> states = new ArrayList<>();
        for (double z = 5.0; z <= 11.6; z += 0.1) {
            PlayerSim sim = start(world, z, true);
            for (int t = 0; t < 40; t++) {
                states.add(sim.copy());
                sim.tick(movement(sim, steps));
            }
        }
        long worst = 0;
        double total = 0;
        int rounds = 3;
        for (int round = 0; round < rounds; round++) {
            long begin = System.nanoTime();
            for (PlayerSim sim : states) {
                long one = System.nanoTime();
                AscendGuard.decide(sim, movement(sim, steps), Math.min(FLOOR + steps, Math.floor(sim.y + 1.0E-3) + 1));
                worst = Math.max(worst, System.nanoTime() - one);
            }
            if (round > 0) { // the first round is the JIT warming up
                total += (System.nanoTime() - begin) / (double) states.size();
            }
        }
        double mean = total / (rounds - 1) / 1.0E6;
        System.out.println(String.format("guard: %.4f ms a tick on average, %.3f ms at worst, over %d states", mean, worst / 1.0E6, states.size()));
        assertTrue("mean " + mean + " ms", mean < 1.0);
    }

    // one control per tick, encoded as bits: 1 forward, 2 jump, 4 sprint key
    private static PlayerSim.Controls bits(int bits) {
        return new PlayerSim.Controls((bits & 1) != 0 ? 1 : 0, 0, (bits & 2) != 0, (bits & 4) != 0, 0F, 0F);
    }

    private static long key(PlayerSim s) {
        long h = Math.round(s.x * 100);
        h = h * 31 + Math.round(s.z * 200);
        h = h * 31 + Math.round(s.y * 100);
        h = h * 31 + Math.round(s.vx * 500);
        h = h * 31 + Math.round(s.vz * 500);
        h = h * 31 + Math.round(s.vy * 100);
        h = h * 31 + (s.onGround ? 1 : 0) + (s.sprinting ? 2 : 0) + s.noJumpDelay * 4;
        return h;
    }

    // ticks to get on top of the last step without a wall hit, best `width` states by progress kept per level. -1 if none.
    // anyKey: all eight combinations of W, space and the sprint key each tick, else W and the sprint key held and only space free
    private static int search(PlayerSim from, int steps, int width, int maxTicks, boolean anyKey) {
        List<PlayerSim> level = new ArrayList<>();
        level.add(from);
        for (int t = 1; t <= maxTicks; t++) {
            Map<Long, PlayerSim> next = new HashMap<>();
            for (PlayerSim n : level) {
                for (int b = 0; b < 8; b++) {
                    if (!anyKey && (b & 5) != 5) {
                        continue;
                    }
                    PlayerSim sim = n.copy();
                    if (!sim.tick(bits(b)) || wall(sim) || sim.y < FLOOR - 0.01) {
                        continue;
                    }
                    if (onTop(sim, steps)) {
                        return t;
                    }
                    next.putIfAbsent(key(sim), sim);
                }
            }
            level = new ArrayList<>(next.values());
            level.sort(Comparator.comparingDouble((PlayerSim s) -> -(s.y * 4 + s.z + (s.onGround ? 0.01 : 0))));
            if (level.size() > width) {
                level = new ArrayList<>(level.subList(0, width));
            }
            if (level.isEmpty()) {
                return -1;
            }
        }
        return -1;
    }
}
