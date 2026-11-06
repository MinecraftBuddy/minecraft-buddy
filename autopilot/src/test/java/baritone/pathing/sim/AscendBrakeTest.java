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

import baritone.api.utils.BetterBlockPos;
import org.junit.Test;

import static baritone.pathing.sim.AscendHop.Verdict.BRAKE;
import static baritone.pathing.sim.AscendHop.Verdict.JUMP;
import static baritone.pathing.sim.AscendHop.Verdict.UNKNOWN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// the step is the one in AscendHopTest: floor top at y = 64, a one block step with its face at z = 12, running +z. the
// driver below is MovementAscend's side of the deal: the sim's verdict where it has one, space on the spot where it does not
// (headBonkClear), W and the sprint key held the whole way, and the ascend guard over whatever keys come out (not over a brake)
public class AscendBrakeTest {

    private static final BetterBlockPos DEST = new BetterBlockPos(8, 65, 12);
    // 0.3 is half the hitbox, 12 is the face
    private static final double FLUSH = 12 - 0.3;

    private static ArrayWorld world() {
        ArrayWorld world = new ArrayWorld(16, 80, 64);
        world.fill(0, 60, 0, 15, 63, 63, ArrayWorld.STONE);
        world.fill(0, 64, 12, 15, 64, 63, ArrayWorld.STONE);
        return world;
    }

    private static PlayerSim sprintingAt(double z, double vz) {
        PlayerSim sim = new PlayerSim(PlayerSim.Params.vanilla(), world());
        sim.x = 8.5;
        sim.y = 64;
        sim.z = z;
        sim.vy = -0.0784;
        sim.vz = vz;
        sim.onGround = true;
        sim.sprinting = true;
        return sim;
    }

    // stopped against the face, the last tick was a graze so the sprint flag is still up
    private static PlayerSim flush() {
        PlayerSim sim = sprintingAt(FLUSH, 0);
        sim.horizontalCollision = true;
        sim.minorHorizontalCollision = true;
        return sim;
    }

    // ticks until standing on the last step and one block past the middle of it (or -1), wall hits on the way, ticks that began
    // without the sprint flag, brake ticks, brake plans made
    private record Run(int ticks, int walls, int sprintless, int brakes, int plans) {}

    private static Run drive(PlayerSim from, AscendHop.Brakes brakes, boolean viewLags) {
        return climb(from, 1, brakes, viewLags, true);
    }

    // the verdict for the step above the floor we stand on, then the ascend guard over whatever keys come out of it unless they
    // are a brake, which is the order PathExecutor does it in. 16 guarded ticks a movement
    private static Run climb(PlayerSim from, int steps, AscendHop.Brakes brakes, boolean viewLags, boolean guard) {
        PlayerSim sim = from.copy();
        int walls = 0, sprintless = 0, brakeTicks = 0, plans = 0, guarded = 0, lastStep = -1;
        float head = 0F; // the view yaw aiStep reads when the head lags: last tick's aim
        for (int t = 1; t <= 200; t++) {
            int k = (int) Math.floor(sim.y - AscendChainTest.FLOOR + 1.0E-3); // the floor we are over, in steps
            if (k != lastStep) {
                lastStep = k;
                guarded = 0; // a new movement
            }
            PlayerSim.Controls keys = new PlayerSim.Controls(1, 0, false, true, 0F, viewLags ? head : 0F);
            boolean braking = false;
            BetterBlockPos dest = new BetterBlockPos(8, AscendChainTest.FLOOR + k + 1, AscendChainTest.FACE + k);
            if (k < steps && sim.onGround && Math.abs(sim.y - (AscendChainTest.FLOOR + k)) < 1.0E-3) {
                AscendHop.Decision d = AscendHop.decide(sim, 0, 1, dest, 0F, 0F, true, brakes, viewLags);
                plans += d.fresh() != null ? 1 : 0;
                switch (d.verdict()) {
                    case BRAKE:
                        braking = true;
                        brakeTicks++;
                        keys = new PlayerSim.Controls(1, 0, false, true, d.yaw(), keys.viewYaw());
                        break;
                    case JUMP:
                    case UNKNOWN:
                        keys = keys.withJump(true);
                        break;
                    default:
                        break;
                }
            }
            if (guard && k < steps && !braking && guarded < 16) {
                AscendGuard.Move move = AscendGuard.decide(sim, keys, dest.y);
                guarded += move == AscendGuard.Move.AS_WANTED ? 0 : 1;
                keys = AscendChainTest.apply(move, keys);
            }
            head = keys.moveYaw();
            if (!sim.tick(keys)) {
                return new Run(-2, walls, sprintless, brakeTicks, plans);
            }
            walls += AscendChainTest.wall(sim) ? 1 : 0;
            sprintless += sim.sprinting ? 0 : 1;
            if (sim.onGround && Math.abs(sim.y - (AscendChainTest.FLOOR + steps)) < 1.0E-3 && sim.z >= AscendChainTest.FACE + steps + 0.5) {
                return new Run(t, walls, sprintless, brakeTicks, plans);
            }
        }
        return new Run(-1, walls, sprintless, brakeTicks, plans);
    }

    @Test
    public void flushAgainstTheFaceBacksUpAndKeepsTheSprint() {
        Run plain = drive(flush(), null, false);
        Run braked = drive(flush(), new AscendHop.Brakes(), false);
        System.out.println("BRAKE| flush: plain " + plain + ", braked " + braked);
        assertEquals(0, braked.walls);
        assertEquals(1, braked.plans);
        assertTrue(braked.brakes >= 1);
        assertTrue("sprint ticks lost: braked " + braked + ", plain " + plain, braked.sprintless < plain.sprintless);
        assertTrue("braked " + braked + " plain " + plain, braked.ticks > 0 && braked.ticks <= plain.ticks);
    }

    @Test
    public void withoutTheGuardUnderItTheJumpOnTheSpotClipsAndTheBrakeDoesNot() {
        // guardAscends off: the old keys run into the face from flush and from a block away at sprint pace, hits and all
        PlayerSim from = flush();
        Run plain = climb(from, 1, null, false, false);
        Run braked = climb(from, 1, new AscendHop.Brakes().guarded(false), false, false);
        System.out.println("BRAKE| flush, no guard: plain " + plain + ", braked " + braked);
        assertTrue("the old keys clip the face from there: " + plain, plain.walls > 0);
        assertEquals(0, braked.walls);
        assertTrue("braked " + braked + " plain " + plain, braked.ticks > 0 && braked.ticks <= plain.ticks);
    }

    @Test
    public void theFirstTickOfThePlanIsABrakeFacingBackwards() {
        AscendHop.Decision d = AscendHop.decide(flush(), 0, 1, DEST, 0F, 0F, true, new AscendHop.Brakes(), false);
        assertEquals(BRAKE, d.verdict());
        assertEquals(180F, Math.abs(d.yaw()), 0.5F);
        assertNotNull(d.fresh());
    }

    @Test
    public void withTheBrakesOffTheAnswerIsTheOldOne() {
        assertEquals(UNKNOWN, AscendHop.decide(flush(), 0, 1, DEST, 0F, 0F, true));
    }

    @Test
    public void aCleanHopIsLeftAloneAndNoPlanIsMade() {
        int clean = 0;
        for (double z = 9.0; z <= 11.2; z += 0.05) {
            PlayerSim sim = sprintingAt(z, 0.1532);
            AscendHop.Verdict plain = AscendHop.decide(sim, 0, 1, DEST, 0F, 0F, true);
            if (plain == UNKNOWN) {
                continue;
            }
            clean++;
            AscendHop.Decision braked = AscendHop.decide(sim, 0, 1, DEST, 0F, 0F, true, new AscendHop.Brakes(), false);
            assertEquals("z=" + z, plain, braked.verdict());
            assertNull(braked.fresh());
        }
        assertTrue(clean > 20);
    }

    @Test
    public void aWalkingPlayerNeverBrakes() {
        PlayerSim sim = sprintingAt(FLUSH, 0);
        sim.sprinting = false;
        assertEquals(UNKNOWN, AscendHop.decide(sim, 0, 1, DEST, 0F, 0F, false, new AscendHop.Brakes(), false).verdict());
    }

    @Test
    public void followingThePlanTakesNoSimAfterTheFirstTick() {
        AscendHop.Brakes brakes = new AscendHop.Brakes();
        PlayerSim sim = flush();
        AscendHop.Decision first = AscendHop.decide(sim, 0, 1, DEST, 0F, 0F, true, brakes, false);
        assertEquals(BRAKE, first.verdict());
        // the same tick asked again (a path switch ticks two executors) is the same answer, and cheap
        long before = AscendHop.simTicks;
        assertEquals(BRAKE, AscendHop.decide(sim, 0, 1, DEST, 0F, 0F, true, brakes, false).verdict());
        assertEquals(0, AscendHop.simTicks - before);
        // and so is every tick that goes the way the sim said
        int ticks = 0;
        AscendHop.Verdict last = BRAKE;
        while (last != JUMP && ticks++ < 20) {
            before = AscendHop.simTicks;
            AscendHop.Decision d = AscendHop.decide(sim, 0, 1, DEST, 0F, 0F, true, brakes, false);
            assertEquals(0, AscendHop.simTicks - before);
            assertTrue("every tick of a plan says so", d.planned());
            last = d.verdict();
            float yaw = last == BRAKE ? d.yaw() : 0F;
            sim.tick(new PlayerSim.Controls(1, 0, last == JUMP, true, yaw, 0F));
        }
        assertEquals(JUMP, last);
    }

    @Test
    public void notBeingWhereThePlanSaidMeansAskingAgain() {
        AscendHop.Brakes brakes = new AscendHop.Brakes();
        PlayerSim sim = flush();
        assertEquals(BRAKE, AscendHop.decide(sim, 0, 1, DEST, 0F, 0F, true, brakes, false).verdict());
        sim.tick(new PlayerSim.Controls(1, 0, false, true, 180F, 0F));
        // a shove from outside, a tenth of a block on the way back
        sim.z -= 0.1;
        long before = AscendHop.simTicks;
        AscendHop.Decision d = AscendHop.decide(sim, 0, 1, DEST, 0F, 0F, true, brakes, false);
        assertTrue("searched again", AscendHop.simTicks - before > 0);
        // whatever it says now, a brake is a plan of its own from where the player is, not the old one's second tick
        if (d.verdict() == BRAKE) {
            assertNotNull(d.fresh());
        }
    }

    @Test
    public void sprintAndGroundHaveToMatchToo() {
        AscendHop.Brakes brakes = new AscendHop.Brakes();
        PlayerSim sim = flush();
        assertEquals(BRAKE, AscendHop.decide(sim, 0, 1, DEST, 0F, 0F, true, brakes, false).verdict());
        sim.sprinting = false; // something dropped it, the sim's idea of the next tick is gone
        long before = AscendHop.simTicks;
        AscendHop.decide(sim, 0, 1, DEST, 0F, 0F, true, brakes, false);
        assertTrue(AscendHop.simTicks - before > 0);
    }

    @Test
    public void aDifferentStepDropsThePlan() {
        AscendHop.Brakes brakes = new AscendHop.Brakes();
        PlayerSim sim = flush();
        assertEquals(BRAKE, AscendHop.decide(sim, 0, 1, DEST, 0F, 0F, true, brakes, false).verdict());
        long before = AscendHop.simTicks;
        AscendHop.decide(sim, 0, 1, new BetterBlockPos(8, 65, 13), 0F, 0F, true, brakes, false);
        assertTrue(AscendHop.simTicks - before > 0);
    }

    @Test
    public void theHeadLaggingBehindTheAimStillGetsOver() {
        Run plain = drive(flush(), null, true);
        Run braked = drive(flush(), new AscendHop.Brakes(), true);
        assertEquals(0, braked.walls);
        assertTrue(braked.ticks > 0 && braked.ticks <= plain.ticks);
    }

    @Test
    public void aRunUpToASingleStepIsNeverSlowerAndMostlyUntouched() {
        int starts = 0, same = 0;
        for (double z = 5.0; z <= 11.2; z += 0.1) {
            PlayerSim from = sprintingAt(z, 0.1532);
            Run off = drive(from, null, false);
            Run on = drive(from, new AscendHop.Brakes(), false);
            assertTrue("z=" + z + " off " + off + " on " + on, on.ticks <= off.ticks && on.walls <= off.walls);
            starts++;
            same += on.equals(off) ? 1 : 0;
        }
        System.out.println("BRAKE| single step run ups: " + same + " of " + starts + " identical with brakes on");
        assertTrue(same > starts / 2);
    }

    @Test
    public void aSixStepStaircaseIsNotSlowerWithTheBrakesAndClipsNoMoreOften() {
        for (boolean guard : new boolean[]{true, false}) {
            int steps = 6;
            ArrayWorld world = AscendChainTest.staircase(steps);
            long offTicks = 0, onTicks = 0;
            int offWalls = 0, onWalls = 0, offSprintless = 0, onSprintless = 0, starts = 0, brakeTicks = 0, plans = 0;
            StringBuilder rows = new StringBuilder();
            for (double z = 5.0; z <= 11.6; z += 0.2) {
                PlayerSim from = AscendChainTest.start(world, z, true);
                Run off = climb(from, steps, null, false, guard);
                Run on = climb(from, steps, new AscendHop.Brakes().guarded(guard), false, guard);
                assertTrue("never got up without brakes from z=" + z + ": " + off, off.ticks > 0);
                assertTrue("never got up with brakes from z=" + z + ": " + on, on.ticks > 0);
                if (!on.equals(off)) {
                    rows.append(String.format("BRAKE|   z=%.1f: %d (%d walls, %d sprintless) off / %d (%d, %d) on, %d brake ticks, %d plans%n", z, off.ticks, off.walls,
                            off.sprintless, on.ticks, on.walls, on.sprintless, on.brakes, on.plans));
                }
                starts++;
                offTicks += off.ticks;
                onTicks += on.ticks;
                offWalls += off.walls;
                onWalls += on.walls;
                offSprintless += off.sprintless;
                onSprintless += on.sprintless;
                brakeTicks += on.brakes;
                plans += on.plans;
            }
            String summary = String.format("BRAKE| six steps, sprint run up, guard %s, %d starts: ticks %.2f off, %.2f on; wall hits %d off, %d on; sprintless ticks %d off, %d on; %d plans, %d brake ticks%n%s",
                    guard ? "on" : "off", starts, offTicks / (double) starts, onTicks / (double) starts, offWalls, onWalls, offSprintless, onSprintless, plans, brakeTicks, rows);
            System.out.println(summary);
            assertTrue(summary, onTicks <= offTicks);
            assertTrue(summary, onWalls <= offWalls);
            assertTrue(summary, onSprintless <= offSprintless);
        }
    }

    @Test
    public void whatAPlanCostsAndWhatFollowingItCosts() {
        // typical: the plain search every tick of a run up to the step. worst: the tick a plan gets made. then the replay
        AscendHop.simTicks = 0;
        AscendHop.decides = 0;
        AscendHop.worstDecideTicks = 0;
        for (double z = 5.0; z <= 11.2; z += 0.1) {
            PlayerSim sim = sprintingAt(z, 0.1532);
            AscendHop.decide(sim, 0, 1, DEST, 0F, 0F, true, new AscendHop.Brakes(), false);
        }
        double typical = AscendHop.simTicks / (double) AscendHop.decides;
        long typicalWorst = AscendHop.worstDecideTicks;

        AscendHop.simTicks = 0;
        AscendHop.decides = 0;
        AscendHop.worstDecideTicks = 0;
        long made = 0, makingTicks = 0, searched = 0, searchedTicks = 0;
        for (double z = 11.0; z <= FLUSH + 1.0E-9; z += 0.05) {
            for (double vz : new double[]{0, 0.0836, 0.1532, 0.2593}) {
                PlayerSim sim = sprintingAt(z, vz);
                long before = AscendHop.simTicks;
                AscendHop.Decision d = AscendHop.decide(sim, 0, 1, DEST, 0F, 0F, true, new AscendHop.Brakes(), false);
                if (d.fresh() != null) {
                    made++;
                    makingTicks += AscendHop.simTicks - before;
                } else if (d.verdict() == UNKNOWN) {
                    searched++; // went looking for a plan and found nothing better than today
                    searchedTicks += AscendHop.simTicks - before;
                }
            }
        }
        long worstMaking = AscendHop.worstDecideTicks;
        // microseconds per ask, wall clock
        PlayerSim flush = flush();
        for (int i = 0; i < 300; i++) { // JIT
            AscendHop.decide(flush, 0, 1, DEST, 0F, 0F, true, new AscendHop.Brakes(), false);
        }
        int rounds = 400;
        long begin = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            AscendHop.decide(flush, 0, 1, DEST, 0F, 0F, true, new AscendHop.Brakes(), false);
        }
        double makeMicros = (System.nanoTime() - begin) / 1000.0 / rounds;
        AscendHop.Brakes brakes = new AscendHop.Brakes();
        AscendHop.decide(flush, 0, 1, DEST, 0F, 0F, true, brakes, false);
        begin = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            AscendHop.decide(flush, 0, 1, DEST, 0F, 0F, true, brakes, false);
        }
        double replayMicros = (System.nanoTime() - begin) / 1000.0 / rounds;
        PlayerSim cruising = sprintingAt(9.5, 0.1532);
        begin = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            AscendHop.decide(cruising, 0, 1, DEST, 0F, 0F, true, new AscendHop.Brakes(), false);
        }
        double plainMicros = (System.nanoTime() - begin) / 1000.0 / rounds;
        System.out.println(String.format("BRAKE| sim cost: plain search %.1f sim ticks per ask over a run up (worst %d); picking a plan %.0f sim ticks on average over %d plans, "
                        + "a search that found nothing better %.0f on average over %d asks (worst of both %d); following a plan 0. wall clock per ask: plain %.0f us, picking a plan %.0f us, following one %.1f us",
                typical, typicalWorst, makingTicks / (double) Math.max(1, made), made, searchedTicks / (double) Math.max(1, searched), searched, worstMaking,
                plainMicros, makeMicros, replayMicros));
        assertTrue(made > 0);
        assertTrue("a plan took " + worstMaking + " sim ticks", worstMaking < 4000);
    }

    @Test
    public void startingAtEveryDepthTheBrakeIsNeverSlowerThanTheOldKeys() {
        StringBuilder table = new StringBuilder("BRAKE| ticks until over the step, old keys + guard vs braked (walls in brackets)\n");
        int used = 0;
        for (double z = 11.0; z <= FLUSH + 1.0E-9; z += 0.1) {
            for (double vz : new double[]{0, 0.0836, 0.1532}) {
                PlayerSim from = sprintingAt(Math.min(z, FLUSH), vz);
                Run plain = drive(from, null, false);
                Run braked = drive(from, new AscendHop.Brakes(), false);
                table.append(String.format("BRAKE|   z=%.2f vz=%.4f  old %d (%d)  braked %d (%d), brake ticks %d, sprintless %d vs %d%n", z, vz, plain.ticks, plain.walls, braked.ticks,
                        braked.walls, braked.brakes, plain.sprintless, braked.sprintless));
                if (braked.plans > 0) {
                    used++;
                    assertTrue("slower at z=" + z + " vz=" + vz + ": " + plain + " vs " + braked, braked.ticks <= plain.ticks);
                    assertTrue("clips at z=" + z + " vz=" + vz + ": " + braked, braked.walls <= plain.walls);
                } else {
                    assertEquals("no plan, so the same as the old keys at z=" + z + " vz=" + vz, plain, braked);
                }
            }
        }
        System.out.println(table);
        assertTrue("never used", used > 0);
    }
}
