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

package baritone.pathing.path;

import baritone.pathing.sim.ArrayWorld;
import baritone.pathing.sim.PlayerSim;
import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.*;

// braking with S (or by letting go) instead of spinning the head around, and the lean not wobbling while it steers
public class SprintJumpBrakeTest {

    private static final double ACCEL = 0.13 * 0.98, SPRINT = ACCEL * SprintJump.GROUND_FRICTION / (1 - SprintJump.GROUND_FRICTION);

    // a flat hop is 12 ticks up: the jump tick on ground, 10 in the air with the same accel, and the touchdown tick,
    // which always pushes
    private static double flatLanding(double v, double air) {
        double disp = v + SprintJump.JUMP_BOOST + ACCEL, s = disp;
        v = disp * SprintJump.GROUND_FRICTION;
        for (int tick = 1; tick < 12; tick++) {
            disp = v + (tick == 11 ? SprintJump.AIR_ACCEL : air);
            s += disp;
            v = disp * SprintJump.AIR_FRICTION;
        }
        return s;
    }

    private static SprintJump flat(int cells) {
        return new SprintJump(new double[cells], 1, ACCEL);
    }

    @Test
    public void fullSendLandsWhereItAlwaysDid() {
        SprintJump.Landing l = flat(10).jump(0, SPRINT, Double.NaN);
        assertEquals(12, l.ticks());
        assertEquals(flatLanding(SPRINT, SprintJump.AIR_ACCEL), l.s(), 1e-9);
        assertTrue(l.pushed());
    }

    @Test
    public void holdingSIsMostOfTheOldBrake() {
        // a target right under our feet brakes every air tick it can, the hardest each kind does
        double full = flatLanding(SPRINT, SprintJump.AIR_ACCEL);
        double old = full - flatLanding(SPRINT, -SprintJump.AIR_ACCEL); // W held facing backwards
        SprintJump jump = flat(10);
        SprintJump.Landing back = jump.jump(0, SPRINT, 0.01, SprintJump.BACK), coast = jump.jump(0, SPRINT, 0.01, SprintJump.COAST);
        assertEquals(flatLanding(SPRINT, -SprintJump.BACK_ACCEL), back.s(), 1e-9);
        assertEquals(flatLanding(SPRINT, 0), coast.s(), 1e-9);
        assertTrue("back " + (full - back.s()) + " old " + old, full - back.s() >= 0.75 * old);
        assertTrue(full - coast.s() > 0 && coast.s() > back.s());
        // even braking the whole way, the touchdown tick has W down so we land sprinting
        assertTrue(back.pushed() && coast.pushed());
    }

    // the runways the old flip brake was there for: the first step of a hill, a step the next hop has to pop, a gap with
    // one cell of floor after it. full send can't do any of them, the brake still has to, from anywhere it would jump,
    // landing sprinting and still moving forward
    @Test
    public void shortRunwaysStillGetABrakedPlan() {
        double[][] runways = {{0, 0, -1, -1, -2, -2, -2, -2}, {0, 0, 0, 0, 1, 1, 1, 1, 1, 1}, {0, SprintJump.NO_FLOOR, 0, 0}};
        for (double[] floors : runways) {
            int braked = 0;
            for (double s = -0.3; s <= 0.3; s += 0.05) {
                SprintJump jump = new SprintJump(floors, 1, ACCEL);
                jump.hop = floors[1] == SprintJump.NO_FLOOR;
                assertFalse(jump.safe(jump.jump(s, SPRINT, Double.NaN)));
                Double target = jump.plan(s, SPRINT);
                if (target == null) {
                    continue;
                }
                braked++;
                assertFalse(Double.isNaN(target));
                assertTrue(jump.brake == SprintJump.COAST || jump.brake == SprintJump.BACK);
                for (double margin : new double[]{0.85, 1, 1.15}) {
                    String at = "floors " + Arrays.toString(floors) + " s " + s + " x" + margin;
                    SprintJump.Landing l = jump.jump(s, SPRINT * margin, target);
                    assertTrue(at, jump.safe(l));
                    assertTrue(at, l.pushed());
                    assertTrue(at + " came down sliding back at " + l.v(), l.v() >= 0);
                }
            }
            assertTrue("floors " + Arrays.toString(floors), braked > 0);
        }
    }

    @Test
    public void aBrakeThatCoastingCantDoUsesS() {
        // a 2 gap with one cell after the landing, taken from the far end of the takeoff cell. no coast plan works from
        // there, holding S does
        SprintJump jump = new SprintJump(new double[]{0, SprintJump.NO_FLOOR, 0, 0}, 1, ACCEL);
        jump.hop = true;
        Double target = jump.plan(0.5, SPRINT);
        assertNotNull(target);
        assertFalse(Double.isNaN(target));
        assertEquals(SprintJump.BACK, jump.brake);
        SprintJump.Landing l = jump.jump(0.5, SPRINT, target);
        assertTrue(jump.safe(l));
        assertTrue(l.pushed());
        assertEquals(2, Math.round(l.s()));
    }

    @Test
    public void aGapWithNothingPastItIsWalkedLikeHopPriceSays() {
        for (double s = -0.5; s <= 0.5; s += 0.05) {
            SprintJump jump = new SprintJump(new double[]{0, SprintJump.NO_FLOOR, 0}, 1, ACCEL);
            jump.hop = true;
            assertNull(jump.plan(s, SPRINT));
        }
    }

    // one hop with the lean steering it, the touchdown offset back. brakeFrom..brakeTo are the brake ticks, 11 is the
    // touchdown and always pushes
    private static double hop(String at, double p, double v, int brakeFrom, int brakeTo, int kind, Random noise) {
        SprintJump.Lean lean = new SprintJump.Lean();
        float lastYaw = Float.NaN;
        double worst = 0;
        int swaps = 0, side = 0;
        for (int tick = 0; tick < 12; tick++) {
            int throttle = tick >= brakeFrom && tick <= brakeTo && tick != 0 && tick != 11 ? kind : SprintJump.PUSH;
            double accel = tick == 0 ? SprintJump.JUMP_BOOST + ACCEL : SprintJump.AIR_ACCEL;
            double l = lean.next(p, v + (noise.nextDouble() - 0.5) * 0.004, accel, throttle);
            float yaw = SprintJump.yaw(0, 1, l);
            if (!Float.isNaN(lastYaw)) {
                worst = Math.max(worst, Math.abs(yaw - lastYaw));
            }
            lastYaw = yaw;
            if (Math.abs(l) > SprintJump.VISIBLE_LEAN) {
                swaps += side != 0 && Math.signum(l) != side ? 1 : 0;
                side = (int) Math.signum(l);
            }
            // W shoves the way the head faces, S the opposite way, nothing does nothing
            double shove = throttle == SprintJump.PUSH ? accel * l : throttle == SprintJump.BACK ? -SprintJump.BACK_ACCEL * l : 0;
            double disp = v + shove;
            p += disp;
            v = disp * (tick == 0 ? SprintJump.GROUND_FRICTION : SprintJump.AIR_FRICTION);
            assertTrue(at + " left the lane at tick " + tick + ": " + p, Math.abs(p) < 0.3);
        }
        assertTrue(at + " turned " + worst + " in a tick", worst < 10);
        assertTrue(at + " lean swapped sides " + swaps + " times in one hop", swaps <= 1);
        return p;
    }

    // the old brake faced the yaw 180 the other way for a tick or two. now a hop with a brake anywhere in it (early, late,
    // all the way to touchdown), noise on the drift and drift up to the 0.08 plan() lets through, never turns the head
    // more than a few degrees a tick, swaps sides at most once and stays on the lane
    @Test
    public void theLeanNeverWhipsAndStaysOnTheLane() {
        Random random = new Random(1234);
        for (int run = 0; run < 2000; run++) {
            double p = (random.nextDouble() - 0.5) * 0.4, v = (random.nextDouble() - 0.5) * 0.16;
            int from = 1 + random.nextInt(9);
            int to = from + random.nextInt(11 - from);
            int kind = random.nextBoolean() ? SprintJump.BACK : SprintJump.COAST;
            hop("run " + run + " p " + p + " v " + v + " brake " + from + ".." + to + " kind " + kind, p, v, from, to, kind, random);
        }
        // coast the whole way to touchdown with the worst drift, and S from the first air tick with a big lean held
        for (double v : new double[]{-0.08, 0.08}) {
            hop("coast to touchdown v " + v, 0, v, 1, 10, SprintJump.COAST, random);
            hop("S with a big lean v " + v, 0.2 * Math.signum(v), v, 1, 4, SprintJump.BACK, random);
        }
    }

    @Test
    public void aPushedHopPullsTheDriftBackIn() {
        // no brake, W the whole way: from up to 0.2 off the line and 0.08 a tick sideways, touchdown is close to the line.
        // the one swap rule costs a little here (a swap it can't make is drift it leaves alone), so not dead center
        Random random = new Random(99);
        for (int run = 0; run < 2000; run++) {
            double p = (random.nextDouble() - 0.5) * 0.4, v = (random.nextDouble() - 0.5) * 0.16;
            String at = "run " + run + " p " + p + " v " + v;
            double end = hop(at, p, v, 99, 99, SprintJump.COAST, random);
            assertTrue(at + " came down " + end + " off", Math.abs(end) < 0.12);
        }
        assertEquals(0, hop("small drift", 0.05, 0.01, 99, 99, SprintJump.COAST, new Random(1)), 0.03);
    }

    @Test
    public void aBrakeTickHoldsTheHead() {
        // the head only steers while W is down. S or nothing keeps whatever lean it had, wherever the drift is
        SprintJump.Lean lean = new SprintJump.Lean();
        double l = lean.next(0.2, 0.02, SprintJump.JUMP_BOOST + ACCEL, SprintJump.PUSH);
        assertTrue(l < 0);
        assertEquals(l, lean.next(-0.3, -0.05, SprintJump.AIR_ACCEL, SprintJump.BACK), 0);
        assertEquals(l, lean.next(0.3, 0.05, SprintJump.AIR_ACCEL, SprintJump.COAST), 0);
        assertTrue(Math.abs(SprintJump.yaw(0, 1, 0.5)) < 90);
    }

    // flat floor top at y = 64, forward is +z at yaw 0, same as PlayerSimTest
    private static PlayerSim sprinting() {
        ArrayWorld world = new ArrayWorld(16, 80, 64);
        world.fill(0, 60, 0, 15, 63, 63, ArrayWorld.STONE);
        PlayerSim sim = new PlayerSim(PlayerSim.Params.vanilla(), world);
        sim.x = 8.5;
        sim.y = 64;
        sim.z = 2;
        sim.vy = -0.0784;
        sim.onGround = true;
        sim.sprinting = true;
        return sim;
    }

    @Test
    public void sprintComesBackForTheNextTakeoff() {
        PlayerSim.Controls push = new PlayerSim.Controls(1, 0, false, true, 0F);
        // the executor lets go of sprint on brake ticks too, sprint held with S down is what a sprint check hunts for
        PlayerSim.Controls back = new PlayerSim.Controls(-1, 0, false, false, 0F);
        PlayerSim.Controls coast = new PlayerSim.Controls(0, 0, false, false, 0F);
        PlayerSim sim = sprinting();
        for (int i = 0; i < 20; i++) {
            assertTrue(sim.tick(push));
        }
        assertTrue(sim.tick(push.withJump(true)));
        assertTrue(sim.jumped);
        // S drops sprint before travel, so the brake is the walking 0.02 and not the sprinting 0.026 the old flip got
        double vz = sim.vz;
        assertTrue(sim.tick(back));
        assertFalse(sim.sprinting);
        assertEquals((vz - SprintJump.BACK_ACCEL) * SprintJump.AIR_FRICTION, sim.vz, 1e-6);
        vz = sim.vz;
        assertTrue(sim.tick(coast));
        assertFalse(sim.sprinting);
        assertEquals(vz * SprintJump.AIR_FRICTION, sim.vz, 1e-6);
        // and the first W after it is a sprinting push again, mid air
        vz = sim.vz;
        assertTrue(sim.tick(push));
        assertTrue(sim.sprinting);
        assertEquals((vz + SprintJump.AIR_ACCEL) * SprintJump.AIR_FRICTION, sim.vz, 1e-6);
        while (!sim.onGround) {
            assertTrue(sim.tick(push));
        }
        // the next takeoff still gets its sprint boost, next to one that lost sprint for good
        assertTrue(sim.sprinting);
        PlayerSim walker = sim.copy();
        walker.sprinting = false;
        assertTrue(sim.tick(push.withJump(true)));
        assertTrue(walker.tick(new PlayerSim.Controls(1, 0, true, false, 0F)));
        assertTrue(sim.jumped && walker.jumped);
        assertTrue("sprinter " + sim.vz + " walker " + walker.vz, sim.vz - walker.vz > 0.1);
    }
}
