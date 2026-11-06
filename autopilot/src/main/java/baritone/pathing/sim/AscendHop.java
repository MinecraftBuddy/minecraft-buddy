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

import baritone.Baritone;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.RotationUtils;
import baritone.utils.BlockStateInterface;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// when to press space for a one block hop up, asked of the sim instead of guessed. a sprint hop spends its first two moves
// (about 0.77 of a block) still below the top of the step, so it clips the face if it starts too close, and it comes down
// against the face if it starts too far back: there is a window about two blocks wide, and the old rules ("jump the moment
// the ceiling is clear", "jump inside 1.2 blocks") are somewhere in or near it depending on where in the cell the last
// movement happened to hand over. a clip is a horizontal collision vanilla does not call minor, which costs the sprint and
// all the speed with it
//
// standing inside the near edge of that window (flush against the face is the extreme) no wait fixes it, the hop can't back
// up. the brake does: W held with the yaw turned round, which 26.3 does not count as letting go of forward (only the input
// is looked at, not where it points), so the sprint survives and the player accelerates backwards. a few ticks of that, then
// forward again and the usual jump. see Brakes for the plan that keeps it from being a search every tick
public final class AscendHop {

    public enum Verdict {
        JUMP, // press it now
        WAIT, // keep going and ask again, a better tick is coming
        BRAKE, // W held facing Decision.yaw (backwards), no space
        UNKNOWN // the sim cannot say (or no tick in reach is any good), do what the movement always did
    }

    // yaw only means something for a BRAKE. fresh is the plan on the one tick it was picked, for the telemetry. planned: the
    // verdict is a tick of a brake plan, so a WAIT in it is not one of the few waits the callers allow themselves
    public record Decision(Verdict verdict, float yaw, BrakePlan fresh, boolean planned) {
        public static Decision of(Verdict verdict) {
            return new Decision(verdict, 0F, null, false);
        }
    }

    // how many ticks ahead to look for a better moment. a window opening further off than this is not worth holding still for
    private static final int MAX_WAIT = 5;
    private static final int MAX_FLIGHT = 30;
    // the world is not exactly where the sim thinks (a tick of lag, a degree of yaw jitter), so a jump only counts as good
    // if it is also good a tenth of a block further back and a tenth further on. the exact one goes first, it is the one
    // that rules most ticks out and the other two are not worth simulating then
    private static final double[] SHIFTS = {0.0, -0.1, 0.1};

    // brake plans: up to this many ticks of backwards W, then up to this many of walking forward before the jump
    private static final int MAX_BRAKE = 3;
    private static final int MAX_PUSH = 4;
    // a brake is for standing inside the near edge of the window. further back than this the plain search either has a plan or
    // it is so far off that backing up is the wrong way to go
    private static final double BRAKE_REACH = 1.5;
    // plans and the baseline are scored by the tick the sim reaches this far past the middle of the step, capped
    private static final double FINISH_AHEAD = 1.0;
    private static final int ROLLOUT_CAP = 40;
    // a tick without the sprint flag on the way up counts as this many ticks late, see Outcome.score
    static final int SPRINT_TICK_COST = 1;
    // PathExecutor.MAX_GUARD_TICKS, the guard stops taking keys away after this many ticks on one movement
    private static final int GUARD_TICKS = 16;
    // the world is not exactly where the sim thinks (yaw jitter of a degree a tick, a tick of lag), a plan is followed while the
    // player is this close to where the sim said and goes back to being a search the moment it is not
    private static final double REPLAY_EPS = 0.02;
    // a velocity this slow has no direction worth cancelling
    private static final double DRIFT_SPEED = 0.05;

    // how much sim the asking cost, for the telemetry report and the tests. the client thread is the only one that asks
    public static long simTicks, decides, worstDecideTicks;

    private AscendHop() {}

    public static Decision ask(IPlayerContext ctx, BlockStateInterface bsi, BetterBlockPos src, BetterBlockPos dest, Brakes brakes) {
        LocalPlayer player = ctx.player();
        int dx = Integer.signum(dest.x - src.x), dz = Integer.signum(dest.z - src.z);
        if (bsi == null || !player.onGround() || dest.y != src.y + 1 || (dx != 0) == (dz != 0)) {
            return Decision.of(Verdict.UNKNOWN); // only the straight single step up is a window we know the shape of
        }
        PlayerSim sim = LiveSim.of(player, new WorldCollision(bsi));
        if (sim == null) {
            return Decision.of(Verdict.UNKNOWN);
        }
        Vec3 target = new Vec3(dest.x + 0.5, dest.y, dest.z + 0.5);
        float yaw = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), target, ctx.playerRotations()).getYaw();
        // smoothLook with the camera following the aim averages the last few aims into the head, a half turn in that average is not
        // something the view yaw model has an answer for
        boolean braking = brakes != null && Baritone.settings().brakeAscends.value && (Baritone.settings().freeLook.value || !Baritone.settings().smoothLook.value);
        if (brakes != null && !braking) {
            brakes.forget();
        }
        if (braking) {
            brakes.guarded(Baritone.settings().guardAscends.value);
        }
        // freeLook leaves the camera alone (the server or no side gets the aim), so aiStep reads the camera yaw every tick. without
        // it the head is turned to the aim at PlayerUpdate PRE and aiStep reads last tick's
        boolean viewLags = !Baritone.settings().freeLook.value;
        return decide(sim, dx, dz, dest, yaw, player.getYRot(), player.isSprinting(), braking ? brakes : null, viewLags);
    }

    // the plain answer, no brakes
    public static Verdict decide(PlayerSim sim, int dx, int dz, BetterBlockPos dest, float moveYaw, float viewYaw, boolean sprint) {
        return decide(sim, dx, dz, dest, moveYaw, viewYaw, sprint, null, false).verdict();
    }

    // sim is the player right now, at the start of the tick the jump key would go down in. dx and dz say which way the step
    // is, one of them is 0 and the other 1 or -1. brakes is null when braking is off. viewLags: the head is turned to the aim
    // by the end of every tick, so the view yaw aiStep reads is the previous tick's aim and not the camera
    public static Decision decide(PlayerSim sim, int dx, int dz, BetterBlockPos dest, float moveYaw, float viewYaw, boolean sprint,
                                  Brakes brakes, boolean viewLags) {
        long before = PlayerSim.ticksRun;
        decides++;
        try {
            return decideInner(sim, dx, dz, dest, moveYaw, viewYaw, sprint, brakes, viewLags);
        } finally {
            long used = PlayerSim.ticksRun - before;
            simTicks += used;
            worstDecideTicks = Math.max(worstDecideTicks, used);
        }
    }

    private static Decision decideInner(PlayerSim sim, int dx, int dz, BetterBlockPos dest, float moveYaw, float viewYaw, boolean sprint,
                                        Brakes brakes, boolean viewLags) {
        if (brakes != null) {
            Decision replayed = brakes.replay(sim, dx, dz, dest);
            if (replayed != null) {
                return replayed;
            }
        }
        int first = -1;
        for (int wait = 0; wait <= MAX_WAIT; wait++) {
            int quality = quality(sim, wait, dx, dz, dest, moveYaw, viewYaw, sprint);
            if (quality < 0) {
                return Decision.of(Verdict.UNKNOWN);
            }
            if (quality == 2) {
                return Decision.of(wait == 0 ? Verdict.JUMP : Verdict.WAIT);
            }
            if (quality == 1 && first < 0) {
                first = wait;
            }
        }
        if (first >= 0) {
            return Decision.of(first == 0 ? Verdict.JUMP : Verdict.WAIT);
        }
        if (brakes == null || !sprint || !sim.sprinting || !sim.onGround) {
            return Decision.of(Verdict.UNKNOWN);
        }
        BrakePlan plan = brakePlan(sim, dx, dz, dest, moveYaw, viewYaw, viewLags, brakes.guarded);
        if (plan == null) {
            return Decision.of(Verdict.UNKNOWN);
        }
        brakes.adopt(plan, dx, dz, dest);
        return new Decision(plan.verdictAt(0), plan.yaw, plan, true);
    }

    // -1 the sim gave up, 0 clips or misses, 1 clean, 2 clean with a tenth of a block to spare either way
    private static int quality(PlayerSim start, int wait, int dx, int dz, BetterBlockPos dest, float moveYaw, float viewYaw, boolean sprint) {
        int quality = 2;
        for (double shift : SHIFTS) {
            PlayerSim sim = start.copy();
            sim.x += shift * dx;
            sim.z += shift * dz;
            int result = hop(sim, wait, dx, dz, dest, moveYaw, viewYaw, sprint);
            if (result < 0 && shift == 0.0) {
                return -1;
            }
            if (result <= 0) {
                if (shift == 0.0) {
                    return 0;
                }
                quality = 1;
            }
        }
        return quality;
    }

    private static boolean step(PlayerSim sim, PlayerSim.Controls controls) {
        return sim.tick(controls);
    }

    // hold forward for `wait` ticks, press space, hold forward all the way down. 1 if that lands on the step with no wall hit
    private static int hop(PlayerSim sim, int wait, int dx, int dz, BetterBlockPos dest, float moveYaw, float viewYaw, boolean sprint) {
        PlayerSim.Controls walk = new PlayerSim.Controls(1, 0, false, sprint, moveYaw, viewYaw);
        for (int i = 0; i < wait; i++) {
            if (!step(sim, walk)) {
                return -1;
            }
            if (!sim.onGround || wall(sim)) {
                return 0; // waiting walked us off a ledge or into the face
            }
        }
        if (!step(sim, walk.withJump(true))) {
            return -1;
        }
        if (!sim.jumped) {
            return 0;
        }
        for (int i = 0; i < MAX_FLIGHT; i++) {
            if (wall(sim)) {
                return 0;
            }
            if (sim.onGround) {
                return landedOnTheStep(sim, dx, dz, dest) ? 1 : 0;
            }
            if (!step(sim, walk)) {
                return -1;
            }
        }
        return 0;
    }

    // a horizontal collision that vanilla does not wave through as a graze (LocalPlayer.shouldStopRunSprinting)
    private static boolean wall(PlayerSim sim) {
        return sim.horizontalCollision && !sim.minorHorizontalCollision;
    }

    // on the step, in the lane, and not behind its near edge. anywhere further along is fine, the path goes on from there
    private static boolean landedOnTheStep(PlayerSim sim, int dx, int dz, BetterBlockPos dest) {
        double fromCenterX = sim.x - (dest.x + 0.5), fromCenterZ = sim.z - (dest.z + 0.5);
        double along = fromCenterX * dx + fromCenterZ * dz;
        double across = Math.abs(fromCenterX * dz) + Math.abs(fromCenterZ * dx);
        return Math.abs(sim.y - dest.y) < 1.0E-3 && along >= -0.5 && across <= 0.5;
    }

    private static double along(PlayerSim sim, int dx, int dz, BetterBlockPos dest) {
        return (sim.x - (dest.x + 0.5)) * dx + (sim.z - (dest.z + 0.5)) * dz;
    }

    // ---- brake plans ----

    // what a plan did when the sim ran it: no wall hit before landing, landed on the step, the tick it got past the step, and how
    // many of the ticks on the way began without the sprint flag
    record Outcome(boolean clean, boolean landed, int finish, int sprintless) {
        static final Outcome FAILED = new Outcome(false, false, ROLLOUT_CAP, 0);

        boolean good() {
            return clean && landed;
        }

        // what two plans are ranked by: the finish, and a sprint that is off costs more than the sim says (the speed comes back
        // in a tick, the executor's own say on sprint and the next movement's timing do not)
        int score() {
            return finish + sprintless * SPRINT_TICK_COST;
        }
    }

    // b ticks of W facing backwards, w of W facing the step, then space. everything after the jump is W facing the step until the
    // sim is FINISH_AHEAD past the middle of it or runs into something on the step (the next face of a staircase, which every
    // plan reaches the same way). any wall hit on the way up and the plan is no good, record gets the player at the start of each tick
    static Outcome run(PlayerSim sim, int dx, int dz, BetterBlockPos dest, float moveYaw, float viewYaw, boolean viewLags,
                       float brakeYaw, int b, int w, List<double[]> record) {
        float head = viewYaw; // where the head was left, which aiStep reads when the view lags
        int ticks = 0, sprintless = 0;
        for (int t = 0; t <= b + w; t++) {
            boolean jump = t == b + w;
            float yaw = t < b ? brakeYaw : moveYaw;
            if (record != null) {
                record.add(new double[]{sim.x, sim.y, sim.z, sim.vx, sim.vy, sim.vz, sim.sprinting ? 1 : 0, sim.onGround ? 1 : 0});
            }
            if (!step(sim, new PlayerSim.Controls(1, 0, jump, true, yaw, viewLags ? head : viewYaw))) {
                return Outcome.FAILED;
            }
            head = yaw;
            ticks++;
            sprintless += sim.sprinting ? 0 : 1;
            if (jump ? !sim.jumped : !sim.onGround || wall(sim)) {
                return Outcome.FAILED; // walked off the floor, into the face, or the key did nothing
            }
        }
        while (ticks < ROLLOUT_CAP) {
            if (wall(sim)) {
                return Outcome.FAILED;
            }
            if (sim.onGround) {
                return landedOnTheStep(sim, dx, dz, dest) ? rollOn(sim, ticks, sprintless, true, dx, dz, dest, moveYaw, viewYaw, viewLags)
                        : new Outcome(true, false, ROLLOUT_CAP, sprintless);
            }
            if (!step(sim, new PlayerSim.Controls(1, 0, false, true, moveYaw, viewLags ? head : viewYaw))) {
                return Outcome.FAILED;
            }
            head = moveYaw;
            ticks++;
            sprintless += sim.sprinting ? 0 : 1;
        }
        return new Outcome(true, false, ROLLOUT_CAP, sprintless);
    }

    // W until FINISH_AHEAD past the middle of the step, or until something stops us on it
    private static Outcome rollOn(PlayerSim sim, int ticks, int sprintless, boolean clean, int dx, int dz, BetterBlockPos dest,
                                  float moveYaw, float viewYaw, boolean viewLags) {
        while (ticks < ROLLOUT_CAP && along(sim, dx, dz, dest) < FINISH_AHEAD && !sim.horizontalCollision) {
            if (!step(sim, new PlayerSim.Controls(1, 0, false, true, moveYaw, viewLags ? moveYaw : viewYaw))) {
                break;
            }
            ticks++;
            sprintless += sim.sprinting ? 0 : 1;
        }
        return new Outcome(clean, true, ticks, sprintless);
    }

    // what happens today when this sim has no answer: space on the spot (the movement's headBonkClear) and W held, the ascend
    // guard over every tick's keys the way PathExecutor.guardAscend has it, and it gives up after the same number of ticks.
    // a wall hit does not end it, a baseline that clips still has a finish to be held against
    static Outcome today(PlayerSim sim, int dx, int dz, BetterBlockPos dest, float moveYaw, float viewYaw, boolean viewLags, boolean guard) {
        float head = viewYaw;
        int ticks = 0, guardedTicks = 0, sprintless = 0;
        boolean clean = true;
        while (ticks < ROLLOUT_CAP) {
            if (ticks > 0 && wall(sim)) {
                clean = false;
            }
            if (ticks > 0 && sim.onGround && Math.abs(sim.y - dest.y) < 1.0E-3) {
                return landedOnTheStep(sim, dx, dz, dest) ? rollOn(sim, ticks, sprintless, clean, dx, dz, dest, moveYaw, viewYaw, viewLags)
                        : new Outcome(clean, false, ROLLOUT_CAP, sprintless);
            }
            PlayerSim.Controls keys = new PlayerSim.Controls(1, 0, sim.onGround && sim.y < dest.y - 1.0E-3, true, moveYaw, viewLags ? head : viewYaw);
            if (guard && guardedTicks < GUARD_TICKS) {
                AscendGuard.Move move = AscendGuard.decide(sim, keys, dest.y);
                if (move != AscendGuard.Move.AS_WANTED) {
                    guardedTicks++;
                    keys = new PlayerSim.Controls(0, 0, move == AscendGuard.Move.NO_FORWARD && keys.jump(), true, moveYaw, keys.viewYaw());
                }
            }
            if (!step(sim, keys)) {
                return Outcome.FAILED;
            }
            head = moveYaw;
            ticks++;
            sprintless += sim.sprinting ? 0 : 1;
        }
        return new Outcome(clean, false, ROLLOUT_CAP, sprintless);
    }

    private static float brakeYawAgainst(int dx, int dz) {
        // forward is (-sin yaw, cos yaw), the way the keys push, and backwards is the other way
        return (float) Math.toDegrees(Math.atan2(dx, -dz));
    }

    private static BrakePlan brakePlan(PlayerSim start, int dx, int dz, BetterBlockPos dest, float moveYaw, float viewYaw, boolean viewLags, boolean guard) {
        if (along(start, dx, dz, dest) < -BRAKE_REACH) {
            return null;
        }
        float[] yaws = {brakeYawAgainst(dx, dz), Float.NaN};
        double speed = Math.hypot(start.vx, start.vz);
        if (speed > DRIFT_SPEED) {
            // sprint pace straight at the step this is the same yaw. with sideways drift it is the one that kills it
            float against = (float) Math.toDegrees(Math.atan2(start.vx, -start.vz));
            if (Math.abs(Mth.wrapDegrees(against - yaws[0])) > 3F) {
                yaws[1] = against;
            }
        }
        List<Candidate> good = new ArrayList<>();
        for (float yaw : yaws) {
            if (Float.isNaN(yaw)) {
                continue;
            }
            for (int b = 1; b <= MAX_BRAKE; b++) {
                for (int w = 0; w <= MAX_PUSH; w++) {
                    Outcome o = run(start.copy(), dx, dz, dest, moveYaw, viewYaw, viewLags, yaw, b, w, null);
                    if (o.good()) {
                        good.add(new Candidate(yaw, b, w, o));
                    }
                }
            }
        }
        if (good.isEmpty()) {
            return null;
        }
        // never later than what happens now (worked out only once there is a plan to hold it against), and better than it:
        // clean where that clips, a sprint that stays on, or just sooner
        Outcome baseline = today(start.copy(), dx, dz, dest, moveYaw, viewYaw, viewLags, guard);
        good.removeIf(c -> c.outcome.finish > baseline.finish
                || baseline.good() && c.outcome.score() >= baseline.score() && c.outcome.finish >= baseline.finish);
        if (good.isEmpty()) {
            return null;
        }
        good.sort(Comparator.comparingInt((Candidate c) -> c.outcome.score()).thenComparingInt(c -> c.outcome.finish).thenComparingInt(c -> c.b).thenComparingInt(c -> c.w));
        Candidate best = good.get(0);
        List<double[]> states = new ArrayList<>();
        run(start.copy(), dx, dz, dest, moveYaw, viewYaw, viewLags, best.yaw, best.b, best.w, states);
        return new BrakePlan(best.yaw, best.b, best.w, best.outcome.finish, baseline.finish, baseline.good(), states);
    }

    private record Candidate(float yaw, int b, int w, Outcome outcome) {}

    // the plan a search picked, kept so the ticks after it are a comparison and not another search. states[i] is the player at
    // the start of tick i, the last tick is the jump
    public static final class BrakePlan {
        public final float yaw;
        public final int brakeTicks, pushTicks, finish, baselineFinish;
        public final boolean baselineClean;
        private final List<double[]> states;

        BrakePlan(float yaw, int brakeTicks, int pushTicks, int finish, int baselineFinish, boolean baselineClean, List<double[]> states) {
            this.yaw = yaw;
            this.brakeTicks = brakeTicks;
            this.pushTicks = pushTicks;
            this.finish = finish;
            this.baselineFinish = baselineFinish;
            this.baselineClean = baselineClean;
            this.states = states;
        }

        int length() {
            return brakeTicks + pushTicks + 1;
        }

        Verdict verdictAt(int tick) {
            return tick < brakeTicks ? Verdict.BRAKE : tick < brakeTicks + pushTicks ? Verdict.WAIT : Verdict.JUMP;
        }

        boolean matches(int tick, PlayerSim sim) {
            double[] s = states.get(tick);
            return Math.abs(sim.x - s[0]) < REPLAY_EPS && Math.abs(sim.y - s[1]) < REPLAY_EPS && Math.abs(sim.z - s[2]) < REPLAY_EPS
                    && Math.abs(sim.vx - s[3]) < REPLAY_EPS && Math.abs(sim.vy - s[4]) < REPLAY_EPS && Math.abs(sim.vz - s[5]) < REPLAY_EPS
                    && sim.sprinting == (s[6] != 0) && sim.onGround == (s[7] != 0);
        }
    }

    // the plan being followed. asked more than once in a game tick (a path switch ticks two executors) it says the same thing
    // each time, since the player has not moved: a tick is the same tick as last time or the next one
    public static final class Brakes {
        // is the ascend guard on (guardAscends), which decides what a plan has to beat: today is the old keys with the guard over them
        private boolean guarded = true;
        private BrakePlan plan;
        private int cursor;
        private int dx, dz;
        private BetterBlockPos dest;

        public Brakes guarded(boolean guarded) {
            this.guarded = guarded;
            return this;
        }

        public void forget() {
            plan = null;
        }

        void adopt(BrakePlan plan, int dx, int dz, BetterBlockPos dest) {
            this.plan = plan;
            this.dx = dx;
            this.dz = dz;
            this.dest = dest;
            cursor = 0;
        }

        // null when there is no plan or the player is not where it said (then it is searched for again)
        Decision replay(PlayerSim sim, int dx, int dz, BetterBlockPos dest) {
            if (plan == null) {
                return null;
            }
            if (dx != this.dx || dz != this.dz || !dest.equals(this.dest)) {
                plan = null;
                return null;
            }
            int at = -1;
            if (plan.matches(cursor, sim)) {
                at = cursor;
            } else if (cursor + 1 < plan.length() && plan.matches(cursor + 1, sim)) {
                at = cursor + 1;
            }
            if (at < 0) {
                plan = null; // off the plan, or it is over and the player is in the air
                return null;
            }
            cursor = at;
            return new Decision(plan.verdictAt(at), plan.yaw, null, true);
        }
    }
}
