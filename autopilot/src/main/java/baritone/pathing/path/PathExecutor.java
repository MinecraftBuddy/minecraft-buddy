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

import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.behavior.PathingBehavior;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.movement.LavaWake;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.FailedMoves;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.pathing.movement.StairSteps;
import baritone.pathing.movement.Surfacing;
import baritone.pathing.movement.movements.*;
import baritone.pathing.sim.AscendGuard;
import baritone.pathing.sim.AscendHop;
import baritone.pathing.sim.LiveSim;
import baritone.pathing.sim.PlayerSim;
import baritone.pathing.sim.WorldCollision;
import baritone.utils.BlockStateInterface;
import baritone.utils.InputOverrideHandler;
import baritone.utils.ExperimentalMovement;
import baritone.utils.ToolSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.function.Predicate;

import static baritone.api.pathing.movement.MovementStatus.*;

/**
 * Behavior to execute a precomputed path
 *
 * @author leijurv
 */
public class PathExecutor implements IPathExecutor, Helper {

    private static final double MAX_MAX_DIST_FROM_PATH = 3;
    private static final double MAX_DIST_FROM_PATH = 2;
    // anybody holding one of these needs the player exactly where the movement put them, so smoothing and shortcuts stay out
    private static final Input[] PRECISE_STEERING_INPUTS = {Input.CLICK_LEFT, Input.CLICK_RIGHT,
            Input.SNEAK, Input.JUMP, Input.MOVE_BACK, Input.MOVE_LEFT, Input.MOVE_RIGHT};

    /**
     * Default value is equal to 10 seconds. It's find to decrease it, but it must be at least 5.5s (110 ticks).
     * For more information, see issue #102.
     *
     * @see <a href="https://github.com/cabaletta/baritone/issues/102">Issue #102</a>
     * @see <a href="https://i.imgur.com/5s5GLnI.png"></a>
     */
    private static final double MAX_TICKS_AWAY = 200;

    private final IPath path;
    private int pathPosition;
    private int ticksAway;
    private int ticksOnCurrent;
    // the furthest we've got on this path and how long ago, see HighWater
    private HighWater highWater = new HighWater();
    private Double currentMovementOriginalCostEstimate;
    private Integer costEstimateIndex;
    // planned versus real ticks per movement, handed on with the rest when a splice or a cut makes a new executor
    private MovementTimer timer = new MovementTimer();
    private boolean failed;
    // the movement that failed us was a sneak bridge giving up while we hang over a drop (MovementTraverse.bridgeStall)
    private boolean bailedOverDrop;
    private boolean recalcBP = true;
    private HashSet<BlockPos> toBreak = new HashSet<>();
    private HashSet<BlockPos> toPlace = new HashSet<>();
    private HashSet<BlockPos> toWalkInto = new HashSet<>();

    private final PathingBehavior behavior;
    private final IPlayerContext ctx;

    private boolean sprintNextTick;
    // a skip hands the tick to the next movement's frame, which settles sprint for it. see handOff
    private int depth;
    private boolean handedOff;
    private boolean handedOffResult;
    // ticks the traverse before an ascend has held off the hop for the sim, see AscendHop
    private static final int MAX_HOP_WAITS = 8;
    private int hopWaits;
    // ticks AscendGuard has taken keys away on this movement. if the sim keeps saying no the movement gets its keys back
    private static final int MAX_GUARD_TICKS = 16;
    private int guardTicks;
    // only the sprint telemetry reads these: why shouldSprintNextTick said no this tick, and what let go of the keys
    private String sprintWhy = "";
    private String releasedWhy = "";
    // ...and who had a hand on the steering (STEER_*), and how far a ground shortcut was from its target
    private static final int STEER_SMOOTH = 1, STEER_CORNER = 2, STEER_JUMP = 4, STEER_SHORTCUT = 8, STEER_BRAKE = 16;
    private int steerMask;
    private double shortcutDist = Double.NaN;
    private Vec3 shortcutAim, shortcutFrom;
    private int shortcutEnd = -1;
    // see carefulSprint
    private int carefulAt = -1;
    private boolean carefulLava;
    private SprintJump flight;
    private GroundShortcut groundShortcut;
    private int ticksOnShortcut;
    // a shortcut that was let go of mid way keeps the next one off until the path is at the node it was heading for
    private int shortcutCooldownEnd = -1;
    // consecutive ticks of horizontalCollision on this shortcut, and whether W is being held back by a check right now (that is
    // what the telemetry logs the start of)
    private int shortcutContactTicks;
    private boolean shortcutHolding;
    // below this the player is as good as standing: walking is 0.2 a tick, the same bar MovementTimer calls still
    private static final double STANDING_SPEED = 0.03;

    // the boat crossing we're in the middle of, if any. it owns the tick while it's alive
    private BoatTrip boat;
    // the last node of a run a trip gave up on, so we don't try again at every node while swimming it
    private int boatRefusedUntil = -1;
    // the stretches the renderer paints in the boat color, refreshed now and then since they depend on the world
    private List<int[]> boatRuns;
    private List<List<Vec3>> boatLanes;
    private long boatRunsComputedAt;

    public PathExecutor(PathingBehavior behavior, IPath path) {
        this.behavior = behavior;
        this.ctx = behavior.ctx;
        this.path = path;
        this.pathPosition = 0;
    }

    /**
     * Tick this executor
     *
     * @return True if a movement just finished (and the player is therefore in a "stable" state, like,
     * not sneaking out over lava), false otherwise
     */
    public boolean onTick() {
        if (depth++ == 0) {
            sprintWhy = "";
            releasedWhy = "";
            steerMask = 0;
            shortcutDist = Double.NaN;
            shortcutAim = null;
            shortcutFrom = null;
            shortcutEnd = -1;
        }
        try {
            boolean done = tick();
            if (depth == 1) {
                guardAscend(); // after every skip and hand off has had its say, the keys that go out are the ones to check
            }
            return done;
        } finally {
            depth--;
        }
    }

    // a hop up a step is checked against the sim one more time, with the keys exactly as they are about to be sent: if holding
    // them would run into the face of the step, W (and space) are let go of for this tick. see AscendGuard
    private void guardAscend() {
        if (!Baritone.settings().guardAscends.value || flight != null || groundShortcut != null || boat != null
                || pathPosition >= path.movements().size() || guardTicks >= MAX_GUARD_TICKS || behavior.baritone.bsi == null) {
            return;
        }
        IMovement current = path.movements().get(pathPosition);
        if (!(current instanceof MovementAscend)) {
            return;
        }
        if (((MovementAscend) current).braked()) {
            return; // W is held on purpose, facing away. the guard would see it running at the face and let go of the very key that keeps sprint
        }
        InputOverrideHandler keys = behavior.baritone.getInputOverrideHandler();
        boolean forward = keys.isInputForcedDown(Input.MOVE_FORWARD), jump = keys.isInputForcedDown(Input.JUMP);
        if (!forward && !jump) {
            return; // nothing to take away
        }
        // a walking hop has no sprint to save, and a walker leaning on the face is the movement sliding to the middle of the lane
        // at an angle: holding W off there is how it never gets to jump
        if (!sprintNextTick && !ctx.player().isSprinting()) {
            return;
        }
        for (Input precise : PRECISE_STEERING_INPUTS) {
            if (precise != Input.JUMP && keys.isInputForcedDown(precise)) {
                return; // somebody is breaking, placing, sneaking or strafing: not our kind of hop
            }
        }
        BetterBlockPos src = current.getSrc(), dest = current.getDest();
        if (StairSteps.walkUp(ctx.world().getBlockState(dest.below()), src, dest, ctx.world().getBlockState(src.below()), ctx.world())
                || MovementHelper.isBottomSlab(ctx.world().getBlockState(dest.below())) || MovementHelper.isBottomSlab(ctx.world().getBlockState(src.below()))) {
            return; // no jump to guard, the lip is stepped up
        }
        PlayerSim sim = LiveSim.of(ctx.player(), new WorldCollision(behavior.baritone.bsi));
        if (sim == null) {
            return;
        }
        float yaw = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vec3(dest.x + 0.5, dest.y, dest.z + 0.5), ctx.playerRotations()).getYaw();
        PlayerSim.Controls wanted = new PlayerSim.Controls(forward ? 1 : 0, 0, jump, sprintNextTick, yaw, ctx.player().getYRot());
        AscendGuard.Move move = AscendGuard.decide(sim, wanted, dest.y);
        if (move == AscendGuard.Move.AS_WANTED) {
            return;
        }
        guardTicks++;
        releasedWhy = "ascend guard";
        keys.setInputForceState(Input.MOVE_FORWARD, false);
        if (move == AscendGuard.Move.STAND_STILL) {
            keys.setInputForceState(Input.JUMP, false);
        }
        behavior.sprintTelemetry().guarded(move, src, dest);
    }

    // for the planner: a crossing is under way, so the boat is spoken for even though it's not in the bag
    public boolean boatTripActive() {
        return boat != null;
    }

    private boolean tick() {
        if (flight != null && fly()) {
            steerMask |= STEER_JUMP;
            return false; // mid air is not a stable state, no matter what the movement we left behind thinks
        }
        if (pathPosition == path.length() - 1) {
            pathPosition++;
        }
        if (pathPosition >= path.length()) {
            return true; // stop bugging me, I'm done
        }
        // the boat goes first, and a shortcut already under way is never cut off for one. a shortcut can't carry on into water
        // either (clearSmoothingColumn says no to it) so it ends on land and the next tick gets to ask about the boat
        if (boat == null && groundShortcut == null) {
            boat = BoatTrip.plan(behavior.baritone, path, pathPosition, boatRefusedUntil);
        }
        if (boat != null) {
            // everything below assumes feet on the path. in a boat the feet are wherever the boat says, so
            // the trip does all the steering and we only come back here once it's over
            switch (boat.tick()) {
                case CONTINUE:
                    sprintNextTick = false; // nobody's sprinting in a boat
                    // keep the position moving with the boat: plan ahead measures the ticks left in this
                    // segment from here, and with it stuck on the shore node the next segment never got
                    // calculated until we'd already stopped at the end of this one
                    pathPosition = Math.max(pathPosition, Math.min(boat.currentPosition(), path.length() - 2));
                    return boat.safeToCancel();
                case DONE: {
                    int end = boat.endPosition();
                    for (int j = pathPosition; j <= end && j < path.length() - 1; j++) {
                        path.movements().get(j).reset();
                    }
                    pathPosition = end;
                    boat = null;
                    onChangeInPathPosition();
                    onTick();
                    return true;
                }
                case ABORT:
                    boatRefusedUntil = boat.endPosition();
                    boat = null;
                    onChangeInPathPosition();
                    highWater.forget(); // the boat pushed pathPosition along, swimming back up to it isn't being stuck
                    break; // carry on on foot from wherever we ended up, the resync below sorts out where that is
                default:
                    throw new IllegalStateException();
            }
        }
        if (groundShortcut != null) {
            return tickGroundShortcut();
        }
        Movement movement = (Movement) path.movements().get(pathPosition);
        BetterBlockPos whereAmI = ctx.playerFeet();
        if (!movement.getValidPositions().contains(whereAmI)) {
            for (int i = 0; i < pathPosition && i < path.length(); i++) {//this happens for example when you lag out and get teleported back a couple blocks
                if (((Movement) path.movements().get(i)).getValidPositions().contains(whereAmI)) {
                    int previousPos = pathPosition;
                    pathPosition = i;
                    for (int j = pathPosition; j <= previousPos; j++) {
                        path.movements().get(j).reset();
                    }
                    onChangeInPathPosition();
                    onTick();
                    return false;
                }
            }
            for (int i = pathPosition + 3; i < path.length() - 1; i++) { //dont check pathPosition+1. the movement tells us when it's done (e.g. sneak placing)
                // also don't check pathPosition+2 because reasons
                if (((Movement) path.movements().get(i)).getValidPositions().contains(whereAmI)) {
                    if (i - pathPosition > 2) {
                        logDebug("Skipping forward " + (i - pathPosition) + " steps, to " + i);
                    }
                    //System.out.println("Double skip sundae");
                    pathPosition = i - 1;
                    onChangeInPathPosition();
                    onTick();
                    return false;
                }
            }
        }
        Pair<Double, BlockPos> status = closestPathPos(path);
        if (possiblyOffPath(status, MAX_DIST_FROM_PATH)) {
            ticksAway++;
            System.out.println("FAR AWAY FROM PATH FOR " + ticksAway + " TICKS. Current distance: " + status.first() + ". Threshold: " + MAX_DIST_FROM_PATH);
            if (ticksAway > MAX_TICKS_AWAY) {
                logDebug("Too far away from path for too long, cancelling path");
                cancel();
                return false;
            }
        } else {
            ticksAway = 0;
        }
        if (possiblyOffPath(status, MAX_MAX_DIST_FROM_PATH)) { // ok, stop right away, we're way too far.
            logDebug("too far from path");
            cancel();
            return false;
        }
        //long start = System.nanoTime() / 1000000L;
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        for (int i = pathPosition - 10; i < pathPosition + 10; i++) {
            if (i < 0 || i >= path.movements().size()) {
                continue;
            }
            Movement m = (Movement) path.movements().get(i);
            List<BlockPos> prevBreak = m.toBreak(bsi);
            List<BlockPos> prevPlace = m.toPlace(bsi);
            List<BlockPos> prevWalkInto = m.toWalkInto(bsi);
            m.resetBlockCache();
            if (!prevBreak.equals(m.toBreak(bsi))) {
                recalcBP = true;
            }
            if (!prevPlace.equals(m.toPlace(bsi))) {
                recalcBP = true;
            }
            if (!prevWalkInto.equals(m.toWalkInto(bsi))) {
                recalcBP = true;
            }
        }
        if (recalcBP) {
            HashSet<BlockPos> newBreak = new HashSet<>();
            HashSet<BlockPos> newPlace = new HashSet<>();
            HashSet<BlockPos> newWalkInto = new HashSet<>();
            for (int i = pathPosition; i < path.movements().size(); i++) {
                Movement m = (Movement) path.movements().get(i);
                newBreak.addAll(m.toBreak(bsi));
                newPlace.addAll(m.toPlace(bsi));
                newWalkInto.addAll(m.toWalkInto(bsi));
            }
            toBreak = newBreak;
            toPlace = newPlace;
            toWalkInto = newWalkInto;
            recalcBP = false;
        }
        /*long end = System.nanoTime() / 1000000L;
        if (end - start > 0) {
            System.out.println("Recalculating break and place took " + (end - start) + "ms");
        }*/
        if (pathPosition < path.movements().size() - 1) {
            IMovement next = path.movements().get(pathPosition + 1);
            if (!behavior.baritone.bsi.worldContainsLoadedChunk(next.getDest().x, next.getDest().z)) {
                logDebug("Pausing since destination is at edge of loaded chunks");
                pauseKeys("chunk edge");
                return true;
            }
        }
        boolean canCancel = movement.safeToCancel();
        if (costEstimateIndex == null || costEstimateIndex != pathPosition) {
            costEstimateIndex = pathPosition;
            // do this only once, when the movement starts, and deliberately get the cost as cached when this path was calculated, not the cost as it is right now
            currentMovementOriginalCostEstimate = movement.getCost();
            for (int i = 1; i < Baritone.settings().costVerificationLookahead.value && pathPosition + i < path.length() - 1; i++) {
                if (((Movement) path.movements().get(pathPosition + i)).calculateCost(behavior.secretInternalGetCalculationContext()) >= ActionCosts.COST_INF && canCancel) {
                    logDebug("Something has changed in the world and a future movement has become impossible. Cancelling.");
                    cancel();
                    return true;
                }
            }
        }
        double currentCost = movement.recalculateCost(behavior.secretInternalGetCalculationContext());
        if (currentCost >= ActionCosts.COST_INF && canCancel) {
            logDebug("Something has changed in the world and this movement has become impossible. Cancelling.");
            cancel();
            return true;
        }
        if (!movement.calculatedWhileLoaded() && currentCost - currentMovementOriginalCostEstimate > Baritone.settings().maxCostIncrease.value && canCancel) {
            // don't do this if the movement was calculated while loaded
            // that means that this isn't a cache error, it's just part of the path interfering with a later part
            logDebug("Original cost " + currentMovementOriginalCostEstimate + " current cost " + currentCost + ". Cancelling.");
            cancel();
            return true;
        }
        if (shouldPause()) {
            logDebug("Pausing since current best path is a backtrack");
            pauseKeys("best path is a backtrack");
            return true;
        }
        if (ticksOnCurrent == 0) {
            timer.begin(behavior, movement, bsi, currentMovementOriginalCostEstimate, currentCost);
        }
        if (movement instanceof MovementAscend) {
            // the ascend decides on a brake before shouldSprintNextTick has spoken, and a brake is a waste if sprint isn't kept anyway
            ((MovementAscend) movement).setKeepsSprint(ascendBlocker((MovementAscend) movement, false) == null);
        }
        MovementStatus movementStatus = movement.update();
        behavior.noteTicked(movement);
        if (movementStatus == UNREACHABLE || movementStatus == FAILED) {
            logDebug("Movement returns status " + movementStatus);
            bailedOverDrop = movement instanceof MovementTraverse traverse && traverse.bailedOverDrop();
            rememberFailure(movement, false);
            cancel();
            return true;
        }
        if (movementStatus == SUCCESS) {
            //System.out.println("Movement done, next path");
            timer.finish(behavior, movement, ticksOnCurrent);
            pathPosition++;
            onChangeInPathPosition();
            onTick();
            return true;
        } else {
            if (movementStatus == RUNNING) {
                if (startGroundShortcut(movement, bsi)) {
                    return tickGroundShortcut();
                }
                smoothSteering(movement, bsi);
                cornerSteering(movement, bsi);
            }
            handedOff = false;
            boolean sprint = shouldSprintNextTick();
            if (handedOff) {
                // a skip gave the tick to the next movement's frame. that frame set sprint for the tick, ran the movement timer
                // and counted the tick on its own, and when we wrote our answer over its answer vanilla and the flag disagreed
                return handedOffResult;
            }
            sprintNextTick = sprint;
            timer.afterSprint(movement);
            if (!sprintNextTick) {
                ctx.player().setSprinting(false); // letting go of control doesn't make you stop sprinting actually
            }
            ticksOnCurrent++;
            if (ticksOnCurrent > currentMovementOriginalCostEstimate + Baritone.settings().movementTimeoutTicks.value) {
                // only cancel if the total time has exceeded the initial estimate
                // as you break the blocks required, the remaining cost goes down, to the point where
                // ticksOnCurrent is greater than recalculateCost + 100
                // this is why we cache cost at the beginning, and don't recalculate for this comparison every tick
                logDebug("This movement has taken too long (" + ticksOnCurrent + " ticks, expected " + currentMovementOriginalCostEstimate + ", "
                        + movement.getClass().getSimpleName() + " " + movement.getSrc() + " -> " + movement.getDest() + "). Cancelling.");
                rememberFailure(movement, true);
                cancel();
                return true;
            }
            if (highWater.tick(pathPosition, currentMovementOriginalCostEstimate, Baritone.settings().movementTimeoutTicks.value)) {
                // bouncing between two movements resets ticksOnCurrent every bounce, so the check above never fires. the one
                // we keep reaching and never get past is the one to blame
                Movement stuck = (Movement) path.movements().get(highWater.high());
                logDebug("Haven't got past movement " + highWater.high() + " in " + highWater.ticks() + " ticks (expected " + highWater.cost() + "). Cancelling.");
                rememberFailure(stuck, true);
                cancel();
                return true;
            }
        }
        return canCancel && flight == null; // movement is in progress, but if it reports cancellable, PathingBehavior is good to cut onto the next path
    }

    private void smoothSteering(Movement movement, BlockStateInterface bsi) {
        if (!ExperimentalMovement.preferFasterPathing() || !canSteerOnGround(movement)) {
            return;
        }
        Vec3 target = PathSmoothing.lookAhead(path.positions(), pathPosition, ctx.player().position(), i -> {
            IMovement next = path.movements().get(i);
            return next instanceof MovementTraverse || next instanceof MovementDiagonal;
        }, pos -> clearSmoothingColumn(bsi, pos));
        if (target != null) {
            steerMask |= STEER_SMOOTH;
            behavior.baritone.getLookBehavior().updateTarget(
                    RotationUtils.calcRotationFromVec3d(ctx.playerHead(), target, ctx.playerRotations())
                            .withPitch(ctx.playerRotations().getPitch()), false);
        }
    }

    // smoothing and shortcuts only ever touch plain walking. parkour, neos and climbs need the exact spot their sim was
    // run from, and anything holding the crosshair or a precise key needs it too
    private boolean canSteerOnGround(Movement movement) {
        if (!(movement instanceof MovementTraverse || movement instanceof MovementDiagonal) || flight != null
                || !ctx.player().onGround() || ctx.player().isInWater() || ctx.player().isPassenger() || ctx.player().onClimbable()
                || ctx.player().horizontalCollision || movement.isTargetingBlock()
                || !behavior.baritone.getInputOverrideHandler().isInputForcedDown(Input.MOVE_FORWARD)) {
            return false;
        }
        for (Input input : PRECISE_STEERING_INPUTS) {
            if (behavior.baritone.getInputOverrideHandler().isInputForcedDown(input)) {
                return false;
            }
        }
        return true;
    }

    private boolean startGroundShortcut(Movement movement, BlockStateInterface bsi) {
        if (!ExperimentalMovement.allowGroundShortcuts() || pathPosition < shortcutCooldownEnd || !canSteerOnGround(movement)
                || !movement.safeToCancel() || ctx.player().getBbWidth() > 0.6F
                || ctx.player().getBbHeight() > 1.8F) {
            return false;
        }
        Map<BlockPos, Boolean> clearance = new HashMap<>();
        groundShortcut = GroundShortcut.find(path.positions(), pathPosition, ctx.player().position(), i -> {
            Movement next = (Movement) path.movements().get(i);
            if (!(next instanceof MovementTraverse || next instanceof MovementDiagonal)) {
                return false;
            }
            if (next instanceof MovementTraverse ? ((MovementTraverse) next).onLavaShore() : ((MovementDiagonal) next).onLavaShore(bsi)) {
                return false; // the shortcut sprints every node it covers, the lava shore veto has to hold there too
            }
            next.resetBlockCache();
            return next.toBreak(bsi).isEmpty() && next.toPlace(bsi).isEmpty() && next.toWalkInto(bsi).isEmpty()
                    && clearSmoothingColumn(bsi, next.getSrc()) && clearSmoothingColumn(bsi, next.getDest());
        }, pos -> clearance.computeIfAbsent(pos, p -> clearSmoothingColumn(bsi, p)));
        if (groundShortcut != null && yieldsToSprintJump()) {
            groundShortcut = null; // asked last on purpose, planning a jump is the expensive question
        }
        ticksOnShortcut = 0;
        shortcutContactTicks = 0;
        shortcutHolding = false;
        return groundShortcut != null;
    }

    // if a sprint jump would take off from right here, it is the faster way along this run and it gets the tick. same
    // rule the 1.19.4 version had, it just asks SprintJump now
    private boolean yieldsToSprintJump() {
        return ExperimentalMovement.sprintJumping(ctx.world())
                && !behavior.baritone.getInputOverrideHandler().isInputForcedDown(Input.SNEAK)
                && SprintJump.plan(ctx, path, pathPosition) != null;
    }

    // null while the shortcut can go on, else why it can't because we are not walking on the ground any more. nothing to
    // carry on with from there, that one is a replan
    private String shortcutHardStop() {
        if (!ExperimentalMovement.allowGroundShortcuts()) {
            return "ground shortcuts switched off";
        }
        if (!ctx.player().onGround() || ctx.player().isInWater() || ctx.player().onClimbable()) {
            return "off the ground";
        }
        return null;
    }

    // null while the shortcut can go on, else why it can't. the player is still on the ground here, so the movements it skipped
    // can take over from where the feet are (leaveShortcut)
    private String shortcutSoftStop(Vec3 player, Vec3 velocity, Predicate<BlockPos> clearColumn) {
        if (ctx.player().horizontalCollision) {
            // brushing a corner for a tick while on the line is not a reason to give up, leaning on it is
            if (++shortcutContactTicks >= 2 || Math.hypot(velocity.x, velocity.z) < STANDING_SPEED) {
                return "hit something";
            }
        } else {
            shortcutContactTicks = 0;
        }
        return groundShortcut.stopReason(player, velocity, clearColumn);
    }

    // the shortcut ends here without arriving. the feet are on some node of the movements it covers (pathPosition up to its end,
    // both included) most of the time, and then the path carries on with that movement as if the shortcut had never been: the
    // skipped ones are not reset, same as when it arrives. feet on none of them is a replan, as it was for every stop before
    private boolean leaveShortcut(String reason, String detail) {
        int end = groundShortcut.endIndex;
        BetterBlockPos feet = ctx.playerFeet();
        List<? extends IMovement> movements = path.movements();
        int at = GroundShortcut.resumeIndex(i -> ((Movement) movements.get(i)).getValidPositions(), pathPosition, end, movements.size(), feet,
                path.positions().get(end));
        if (at < 0) {
            logDebug("Ground shortcut interrupted (" + reason + "); recalculating from current position");
            behavior.sprintTelemetry().shortcutEnded(SprintTelemetry.ShortcutEnd.REPLAN, reason,
                    detail + " feet=" + feet + " on no node of movements " + pathPosition + ".." + end);
            cancel();
            return true;
        }
        logDebug("Ground shortcut interrupted (" + reason + "); carrying on with movement " + at);
        behavior.sprintTelemetry().shortcutEnded(SprintTelemetry.ShortcutEnd.RESUMED, reason,
                detail + " feet=" + feet + " resumed at movement " + at + " of " + pathPosition + ".." + end);
        shortcutCooldownEnd = end;
        groundShortcut = null;
        // the one that was mid way when the shortcut took over has state from before it, same as a resync back
        for (int j = pathPosition; j <= at && j < movements.size(); j++) {
            movements.get(j).reset();
        }
        pathPosition = at;
        recalcBP = true;
        onChangeInPathPosition();
        return onTick();
    }

    // W is held back by a check and there is no speed to carry us anywhere, so waiting is standing there until the timeout.
    // right at the end node it is as good as arrived, otherwise let go of the shortcut
    private boolean unstickShortcut(GroundShortcut.Blocked blocked, Vec3 player, BlockStateInterface bsi) {
        int end = groundShortcut.endIndex;
        Vec3 target = groundShortcut.target;
        String reason = "stood still, blocked ahead (" + blocked.check() + ")";
        String detail = ExperimentalMovement.sprintTelemetry() ? "at " + describeCell(bsi, blocked.cell()) : "";
        List<? extends IMovement> movements = path.movements();
        if (player.distanceTo(target) <= 0.6 && Math.abs(player.y - target.y) <= 0.05
                && ctx.playerFeet().equals(path.positions().get(end))) {
            behavior.sprintTelemetry().shortcutEnded(SprintTelemetry.ShortcutEnd.HANDOFF, reason, detail
                    + String.format(Locale.ROOT, " toTarget=%.2f", player.distanceTo(target)));
            pathPosition = end;
            groundShortcut = null;
            recalcBP = true;
            onChangeInPathPosition();
            onTick();
            return true;
        }
        return leaveShortcut(reason, detail);
    }

    // what the telemetry says about a cell the shortcut was stopped by: where, and which test of clearSmoothingColumn it failed
    private String describeCell(BlockStateInterface bsi, BlockPos cell) {
        String why = smoothingColumnFailure(bsi, cell);
        if (why == null) {
            return cell.toShortString() + " (the column is fine, the segment itself was too long or not level)";
        }
        BlockPos looked = why.startsWith("floor") ? cell.below() : why.startsWith("head") ? cell.above() : cell;
        return cell.toShortString() + " " + why + (why.startsWith("chunk") ? "" : ": " + bsi.get0(looked));
    }

    // the same, for a stop that is not a hold: the cell is whichever stopReason tripped on
    private String describeStop(String stop, Vec3 player, Vec3 velocity, Predicate<BlockPos> clearColumn, BlockStateInterface bsi) {
        if (!ExperimentalMovement.sprintTelemetry() || !stop.contains("blocked")) {
            return "";
        }
        BlockPos cell = groundShortcut.stopCell(player, velocity, clearColumn);
        return cell == null ? "" : "at " + describeCell(bsi, cell);
    }

    private boolean tickGroundShortcut() {
        Vec3 player = ctx.player().position();
        Vec3 velocity = ctx.player().getDeltaMovement();
        BlockStateInterface bsi = behavior.baritone.bsi;
        Map<BlockPos, Boolean> clearance = new HashMap<>();
        Predicate<BlockPos> clear = pos -> clearance.computeIfAbsent(pos, p -> clearSmoothingColumn(bsi, p));
        String hard = shortcutHardStop();
        if (hard != null) {
            logDebug("Ground shortcut interrupted (" + hard + "); recalculating from current position");
            behavior.sprintTelemetry().shortcutEnded(SprintTelemetry.ShortcutEnd.REPLAN, hard, "");
            cancel();
            return true;
        }
        String stop = shortcutSoftStop(player, velocity, clear);
        if (stop != null) {
            // we're between the original waypoints, and the movements it skipped take over from the node we are on
            return leaveShortcut(stop, describeStop(stop, player, velocity, clear, bsi));
        }
        if (groundShortcut.arrived(player, velocity)) {
            pathPosition = groundShortcut.endIndex;
            groundShortcut = null;
            recalcBP = true;
            onChangeInPathPosition();
            onTick();
            return true;
        }
        if (++ticksOnShortcut > groundShortcut.start.distanceTo(groundShortcut.target)
                * ActionCosts.WALK_ONE_BLOCK_COST + Baritone.settings().movementTimeoutTicks.value) {
            return leaveShortcut("took too long", "");
        }
        clearKeys();
        Rotation desired = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), groundShortcut.target,
                ctx.playerRotations()).withPitch(ctx.playerRotations().getPitch());
        Rotation actual = behavior.baritone.getLookBehavior().getAimProcessor().peekRotation(desired);
        double yawError = Math.abs(Rotation.normalizeYaw(actual.getYaw() - desired.getYaw()));
        boolean turning = yawError >= 10;
        boolean braking = !turning && !groundShortcut.shouldMoveForward(player, velocity);
        boolean forward = !turning && !braking;
        GroundShortcut.Blocked blocked = null;
        if (forward) {
            // the line to the far end, and the curve the momentum really takes there (it bulges on a bend). both stop at the target
            blocked = GroundShortcut.blockedAhead(groundShortcut.start, groundShortcut.target, player, velocity,
                    Math.toRadians(actual.getYaw()), clear);
            forward = blocked == null;
        }
        if (blocked == null) {
            shortcutHolding = false;
        } else {
            // a hold that is waiting on a turn or the momentum clears itself in a few ticks. one with nothing left to wait on
            // (no speed, so the same trajectory gets asked again and says the same) never does
            double speed = Math.hypot(velocity.x, velocity.z);
            if (speed < STANDING_SPEED) {
                return unstickShortcut(blocked, player, bsi);
            }
            if (!shortcutHolding) {
                shortcutHolding = true;
                if (ExperimentalMovement.sprintTelemetry()) {
                    behavior.sprintTelemetry().shortcutHold(blocked.check(), describeCell(bsi, blocked.cell()), speed,
                            player.distanceTo(groundShortcut.target));
                }
            }
        }
        behavior.baritone.getLookBehavior().updateTarget(desired, false);
        behavior.baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, forward);
        steerMask |= STEER_SHORTCUT;
        shortcutDist = player.distanceTo(groundShortcut.target);
        shortcutAim = groundShortcut.target;
        shortcutFrom = groundShortcut.start;
        shortcutEnd = groundShortcut.endIndex;
        // sprint whenever W is held. shouldMoveForward lets go early enough for the slide to carry us onto the node (it uses the
        // real speed), and vanilla drops the sprint the tick W comes up, so a distance cutoff only cost the sprint over the last
        // stretch where we are still pushing
        sprintNextTick = forward && Baritone.settings().allowSprint.value && ctx.player().getFoodData().getFoodLevel() > 6;
        if (!sprintNextTick) {
            // only the first one is the shortcut letting go of W to slide onto its node, the other two are the aim and the floor
            sprintWhy = braking ? "ground shortcut braking at its target" : turning ? "ground shortcut turning to its target"
                    : blocked != null ? "ground shortcut blocked ahead" : "allowSprint off or food";
            ctx.player().setSprinting(false);
        }
        return true; // all ground under our footprint was verified this tick, so pausing is safe
    }

    private boolean clearSmoothingColumn(BlockStateInterface bsi, BlockPos pos) {
        return smoothingColumnFailure(bsi, pos) == null;
    }

    // the labels for the second cell up are "head ...", the first is "feet ...", see describeCell
    private static final String[] BODY_AVOIDED = {"feet cell is to be avoided", "head cell is to be avoided"};
    private static final String[] BODY_FLUID = {"feet cell has fluid", "head cell has fluid"};
    private static final String[] BODY_SOLID = {"feet cell is solid", "head cell is solid"};

    // null when the column is fine to walk through, else which test it failed (a constant, this runs for every cell of every
    // projection and most of them fail something)
    private String smoothingColumnFailure(BlockStateInterface bsi, BlockPos pos) {
        if (Baritone.settings().pathThroughCachedOnly.value || !bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ())) {
            return "chunk not loaded";
        }
        BlockPos floor = pos.below();
        BlockState support = bsi.get0(floor);
        Block block = support.getBlock();
        if (!support.getFluidState().isEmpty()) {
            return "floor has fluid";
        }
        if (!support.isCollisionShapeFullBlock(bsi.access, floor)) {
            return "floor is not a full block";
        }
        if (!MovementHelper.canWalkOn(bsi, floor.getX(), floor.getY(), floor.getZ(), support)) {
            return "floor is not walkable";
        }
        if (block == Blocks.MAGMA_BLOCK || block == Blocks.ICE || block == Blocks.PACKED_ICE
                || block == Blocks.BLUE_ICE || block == Blocks.FROSTED_ICE
                || block == Blocks.SLIME_BLOCK || block == Blocks.HONEY_BLOCK) {
            return "floor is magma, ice, slime or honey";
        }
        for (int y = 0; y < 2; y++) {
            BlockPos body = pos.above(y);
            BlockState state = bsi.get0(body);
            if (MovementHelper.avoidWalkingInto(state) || state.is(Blocks.WITHER_ROSE)
                    || state.is(Blocks.POWDER_SNOW) || MovementHelper.isClimbable(state.getBlock())) {
                return BODY_AVOIDED[y];
            }
            if (!state.getFluidState().isEmpty()) {
                return BODY_FLUID[y];
            }
            if (!state.getCollisionShape(bsi.access, body).isEmpty()) {
                return BODY_SOLID[y];
            }
        }
        return null;
    }

    private Pair<Double, BlockPos> closestPathPos(IPath path) {
        double best = -1;
        BlockPos bestPos = null;
        for (IMovement movement : path.movements()) {
            for (BlockPos pos : ((Movement) movement).getValidPositions()) {
                double dist = VecUtils.entityDistanceToCenter(ctx.player(), pos);
                if (dist < best || best == -1) {
                    best = dist;
                    bestPos = pos;
                }
            }
        }
        return new Pair<>(best, bestPos);
    }

    private boolean shouldPause() {
        Optional<AbstractNodeCostSearch> current = behavior.getInProgress();
        if (!current.isPresent()) {
            return false;
        }
        if (!ctx.player().onGround()) {
            return false;
        }
        if (!MovementHelper.canWalkOn(ctx, ctx.playerFeet().below())) {
            // we're in some kind of sketchy situation, maybe parkouring
            return false;
        }
        if (!MovementHelper.canWalkThrough(ctx, ctx.playerFeet()) || !MovementHelper.canWalkThrough(ctx, ctx.playerFeet().above())) {
            // suffocating?
            return false;
        }
        if (!path.movements().get(pathPosition).safeToCancel()) {
            return false;
        }
        Optional<IPath> currentBest = current.get().bestPathSoFar();
        if (!currentBest.isPresent()) {
            return false;
        }
        List<BetterBlockPos> positions = currentBest.get().positions();
        if (positions.size() < 3) {
            return false; // not long enough yet to justify pausing, its far from certain we'll actually take this route
        }
        // the first block of the next path will always overlap
        // no need to pause our very last movement when it would have otherwise cleanly exited with MovementStatus SUCCESS
        positions = positions.subList(1, positions.size());
        return positions.contains(ctx.playerFeet());
    }

    private boolean possiblyOffPath(Pair<Double, BlockPos> status, double leniency) {
        double distanceFromPath = status.first();
        if (distanceFromPath > leniency) {
            // when we're midair in the middle of a fall, we're very far from both the beginning and the end, but we aren't actually off path
            if (path.movements().get(pathPosition) instanceof MovementFall) {
                BlockPos fallDest = path.positions().get(pathPosition + 1); // .get(pathPosition) is the block we fell off of
                return VecUtils.entityFlatDistanceToCenter(ctx.player(), fallDest) >= leniency; // ignore Y by using flat distance
            } else {
                return true;
            }
        } else {
            return false;
        }
    }

    /**
     * Regardless of current path position, snap to the current player feet if possible
     *
     * @return Whether or not it was possible to snap to the current player feet
     */
    public boolean snipsnapifpossible() {
        if (!ctx.player().onGround() && ctx.world().getFluidState(ctx.playerFeet()).isEmpty()) {
            // if we're falling in the air, and not in water, don't splice
            return false;
        } else {
            // we are either onGround or in liquid
            if (ctx.player().getDeltaMovement().y < -0.1) {
                // if we are strictly moving downwards (not stationary)
                // we could be falling through water, which could be unsafe to splice
                return false; // so don't
            }
        }
        int index = path.positions().indexOf(ctx.playerFeet());
        if (index == -1) {
            return false;
        }
        pathPosition = index; // jump directly to current position
        clearKeys();
        return true;
    }

    private boolean shouldSprintNextTick() {
        boolean requested = behavior.baritone.getInputOverrideHandler().isInputForcedDown(Input.SPRINT);

        // we'll take it from here, no need for minecraft to see we're holding down control and sprint for us
        behavior.baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);

        // first and foremost, if allowSprint is off, or if we don't have enough hunger, don't try and sprint
        // same thing CalculationContext.canSprint works out. this used to build a whole context every tick to read it,
        // which means a chunk provider, a ToolSet, an inventory scan and two enchantment scans for one boolean
        if (!Baritone.settings().allowSprint.value || ctx.player().getFoodData().getFoodLevel() <= 6) {
            return no("allowSprint off or food");
        }
        IMovement current = path.movements().get(pathPosition);
        if (current instanceof Movement && ((Movement) current).standingUp()) {
            // the swim escape wants us upright, the ascend/stair rules below would turn sprint right back on
            return no("standing up out of water");
        }

        if (ExperimentalMovement.sprintJumping(ctx.world()) && !behavior.baritone.getInputOverrideHandler().isInputForcedDown(Input.SNEAK)
                && (flight = SprintJump.plan(ctx, path, pathPosition)) != null) {
            steerMask |= STEER_JUMP;
            steer(true);
            return true;
        }

        // traverse requests sprinting, so we need to do this check first
        if (current instanceof MovementTraverse && pathPosition < path.length() - 3) {
            IMovement next = path.movements().get(pathPosition + 1);
            if (next instanceof MovementAscend && sprintableAscend(ctx, (MovementTraverse) current, (MovementAscend) next, path.movements().get(pathPosition + 2))) {
                // skipNow guesses when the hop is safe from how far away the step is. the sim has the real answer, at this
                // speed, from this spot: not yet (wait, the traverse carries on and we ask again next tick), now, or no idea
                AscendHop.Decision decision = ctx.player().isSprinting()
                        ? AscendHop.ask(ctx, behavior.baritone.bsi, next.getSrc(), next.getDest(), behavior.ascendBrakes())
                        : AscendHop.Decision.of(AscendHop.Verdict.UNKNOWN);
                AscendHop.Verdict hop = decision.verdict();
                // (a few ticks of waiting at most, in case the window never shows up)
                boolean waiting = hop == AscendHop.Verdict.WAIT && (decision.planned() || hopWaits++ < MAX_HOP_WAITS);
                behavior.sprintTelemetry().verdict(decision, next.getSrc(), next.getDest());
                if (hop == AscendHop.Verdict.BRAKE) {
                    // too close to the face for any jump from here: W held facing away from the step, over the traverse's own aim.
                    // sprint stays on (sprintableAscend said the hop sprints), and the jump the skip below would force is what
                    // this must not do
                    steerMask |= STEER_BRAKE;
                    InputOverrideHandler keys = behavior.baritone.getInputOverrideHandler();
                    behavior.baritone.getLookBehavior().updateTarget(new Rotation(decision.yaw(), ctx.playerRotations().getPitch()), false);
                    keys.setInputForceState(Input.MOVE_FORWARD, true);
                    keys.setInputForceState(Input.JUMP, false);
                    return true;
                }
                if (!waiting) {
                    if (hop == AscendHop.Verdict.JUMP ? onLane(ctx, current, next) : skipNow(ctx, current, next)) {
                        logDebug("Skipping traverse to straight ascend");
                        behavior.sprintTelemetry().takeoff(hop == AscendHop.Verdict.JUMP ? "skip, sim JUMP" : "skip, skipNow", hop,
                                next.getSrc(), next.getDest());
                        pathPosition++;
                        onChangeInPathPosition();
                        boolean sprint = handOff();
                        // the ascend's own frame has had its say about space by now: a brake it picked (the sim can change its mind
                        // from the other side of the hand off) stays a brake
                        if (!((MovementAscend) next).braked()) {
                            behavior.baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                        }
                        return sprint;
                    } else {
                        logDebug("Too far to the side to safely sprint ascend");
                    }
                }
            }
        }

        // if the movement requested sprinting, then we're done
        if (requested) {
            if (ExperimentalMovement.headHitters(ctx.world())) {
                headHitJump(current);
            }
            return true;
        }

        // however, descend and ascend don't request sprinting, because they don't know the context of what movement comes after it
        if (current instanceof MovementDescend) {

            if (pathPosition < path.length() - 2) {
                // keep this out of onTick, even if that means a tick of delay before it has an effect
                IMovement next = path.movements().get(pathPosition + 1);
                if (MovementHelper.canUseFrostWalker(ctx, next.getDest().below())) {
                    // frostwalker only works if you cross the edge of the block on ground so in some cases we may not overshoot
                    // Since MovementDescend can't know the next movement we have to tell it
                    if (next instanceof MovementTraverse || next instanceof MovementParkour) {
                        boolean couldPlaceInstead = Baritone.settings().allowPlace.value && behavior.baritone.getInventoryBehavior().hasGenericThrowaway() && next instanceof MovementParkour; // traverse doesn't react fast enough
                        // this is true if the next movement does not ascend or descends and goes into the same cardinal direction (N-NE-E-SE-S-SW-W-NW) as the descend
                        // in that case current.getDirection() is e.g. (0, -1, 1) and next.getDirection() is e.g. (0, 0, 3) so the cross product of (0, 0, 1) and (0, 0, 3) is taken, which is (0, 0, 0) because the vectors are colinear (don't form a plane)
                        // since movements in exactly the opposite direction (e.g. descend (0, -1, 1) and traverse (0, 0, -1)) would also pass this check we also have to rule out that case
                        // we can do that by adding the directions because traverse is always 1 long like descend and parkour can't jump through current.getSrc().down()
                        boolean sameFlatDirection = !current.getDirection().above().offset(next.getDirection()).equals(BlockPos.ZERO)
                                && current.getDirection().above().cross(next.getDirection()).equals(BlockPos.ZERO); // here's why you learn maths in school
                        if (sameFlatDirection && !couldPlaceInstead) {
                            ((MovementDescend) current).forceSafeMode();
                        }
                    }
                }
            }
            if (((MovementDescend) current).safeMode() && !((MovementDescend) current).skipToAscend()) {
                logDebug("Sprinting would be unsafe");
                return no("descend safe mode");
            }

            if (pathPosition < path.length() - 2) {
                IMovement next = path.movements().get(pathPosition + 1);
                if (next instanceof MovementAscend && current.getDirection().above().equals(next.getDirection().below())) {
                    // a descend then an ascend in the same direction. the skip is an overshoot into the wall, so it gets the same
                    // carefulSprint as the other shortcuts. refused is a flat no: skipToAscend forces safeMode on and the check above
                    // lets it through on purpose, so falling out of here would reach the sprint rules below with nothing guarding them
                    if (carefulSprint()) {
                        return no("descend to ascend skip, careful");
                    }
                    pathPosition++;
                    onChangeInPathPosition();
                    // okay to skip clearKeys and / or onChangeInPathPosition here since this isn't possible to repeat, since it's asymmetric
                    logDebug("Skipping descend to straight ascend");
                    return handOff();
                }
                if (canSprintFromDescendInto(ctx, current, next) || sprintsPastLanding(current, next, 1)) {

                    if (next instanceof MovementDescend && pathPosition < path.length() - 3) {
                        IMovement next_next = path.movements().get(pathPosition + 2);
                        if (next_next instanceof MovementDescend && !canSprintFromDescendInto(ctx, next, next_next)) {
                            return no("descend chain ends unsafe");
                        }

                    }
                    if (ctx.playerFeet().equals(current.getDest())) {
                        pathPosition++;
                        onChangeInPathPosition();
                        return handOff();
                    }

                    return true;
                }
                //logDebug("Turning off sprinting " + movement + " " + next + " " + movement.getDirection() + " " + next.getDirection().down() + " " + next.getDirection().down().equals(movement.getDirection()));
            }
        }
        if (current instanceof MovementAscend && sprintsUpStairs((MovementAscend) current)) {
            return true;
        }
        if (current instanceof MovementAscend && pathPosition != 0) {
            IMovement prev = path.movements().get(pathPosition - 1);
            if (prev instanceof MovementDescend && prev.getDirection().above().equals(current.getDirection().below())) {
                BlockPos center = current.getSrc().above();
                // playerFeet adds 0.1251 to account for soul sand
                // farmland is 0.9375
                // 0.07 is to account for farmland
                if (ctx.player().position().y >= center.getY() - 0.07) {
                    behavior.baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, false);
                    return true;
                }
            }
            if (pathPosition < path.length() - 2 && prev instanceof MovementTraverse && sprintableAscend(ctx, (MovementTraverse) prev, (MovementAscend) current, path.movements().get(pathPosition + 1))) {
                return true;
            }
        }
        // below the prev descend branch on purpose: that one returns for itself (and lets go of the jump) once we're up at the
        // ascend's height, and down in the dip of a descend -> ascend the sprint now carries on through instead of dropping
        if (current instanceof MovementAscend && sprintsThroughAscend((MovementAscend) current)) {
            return true;
        }
        if (current instanceof MovementFall) {
            Pair<Vec3, BlockPos> data = overrideFall((MovementFall) current);
            if (data != null) {
                BetterBlockPos fallDest = new BetterBlockPos(data.second());
                if (!path.positions().contains(fallDest)) {
                    throw new IllegalStateException(String.format(
                            "Fall override at %s %s %s returned illegal destination %s %s %s",
                            current.getSrc(), fallDest));
                }
                if (ctx.playerFeet().equals(fallDest)) {
                    pathPosition = path.positions().indexOf(fallDest);
                    onChangeInPathPosition();
                    return handOff();
                }
                clearKeys();
                // we are not ticking the movement, so we gotta do this ourselves
                BetterBlockPos src = current.getSrc();
                BetterBlockPos dest = current.getDest();
                MovementState fakeState = new MovementState();
                if (!MovementHelper.openDoors(ctx, fakeState, src, new BetterBlockPos(dest.x, src.y, dest.z))) {
                    boolean forceRotations = fakeState.getTarget().hasToForceRotations();
                    fakeState.getTarget().getRotation().ifPresent(rotation ->
                            behavior.baritone.getLookBehavior().updateTarget(rotation, forceRotations));
                    fakeState.getInputStates().forEach(behavior.baritone.getInputOverrideHandler()::setInputForceState);
                    fakeState.getInputStates().clear();
                    return true;
                }
                behavior.baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), data.first(), ctx.playerRotations()), false);
                behavior.baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                return true;
            }
            // overrideFall only knows straight traverses, this is every other way the path can carry on from the landing
            return sprintsThroughFall((MovementFall) current) || no("fall does not sprint on");
        }
        if (current instanceof MovementAscend && ExperimentalMovement.sprintTelemetry()) {
            // the ascend branches above all said no, and the telemetry wants to know which of their checks it was
            String why = ascendBlocker((MovementAscend) current, true);
            return no("movement does not sprint MovementAscend: " + (why == null ? "no check failed" : why));
        }
        return no("movement does not sprint " + current.getClass().getSimpleName());
    }

    // shouldSprintNextTick's answer when it is no, with the reason for the sprint telemetry to quote
    private boolean no(String why) {
        sprintWhy = why;
        return false;
    }

    // a skip hands the tick to the next movement: its frame runs update() and settles sprint, so what it left in sprintNextTick
    // is the answer and the frame that skipped has to keep its hands off. before this the outer frame wrote its own answer over
    // it, and the nested one had already let go of the sprint flag (ticksOnCurrent was counted twice for the same reason)
    private boolean handOff() {
        handedOffResult = onTick();
        handedOff = true; // after the call, the frame we just ran resets this for its own sake
        return sprintNextTick;
    }

    // a pause is every key let go of at once. with no forward impulse vanilla drops the sprint in aiStep anyway, so don't leave
    // the flag claiming it is still going to be sprinting
    private void pauseKeys(String why) {
        clearKeys();
        releasedWhy = why;
        sprintNextTick = false;
        ctx.player().setSprinting(false);
        // waiting on a chunk is fine, waiting on it at the bottom of a lake is not. same rule as Movement.update's water bit
        if (Surfacing.wantsAir(ctx.player())) {
            behavior.baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
        }
    }

    // a stair we just walk up (MovementAscend doesn't jump for it either) takes no jump to sprint through, so a whole flight
    // of them can be one sprint instead of a stop and a hop at every step. the path has to go on, since the last
    // movement is the goal and we don't sprint past that, and it can't bend more than a descend's worth at the top
    private boolean sprintsUpStairs(MovementAscend ascend) {
        if (!Baritone.settings().sprintAscends.value || pathPosition >= path.length() - 2
                || !StairSteps.walkUp(ctx.world().getBlockState(ascend.getDest().below()), ascend.getSrc(), ascend.getDest(),
                ctx.world().getBlockState(ascend.getSrc().below()), ctx.world())) {
            return false;
        }
        BlockPos dir = ascend.getDirection();
        BlockPos nextDir = path.movements().get(pathPosition + 1).getDirection();
        BlockStateInterface bsi = behavior.baritone.bsi;
        return SprintPolicy.turnDegrees(dir.getX(), dir.getZ(), nextDir.getX(), nextDir.getZ()) <= SprintPolicy.DESCEND_MAX_TURN
                && ascend.toBreak(bsi).isEmpty() && ascend.toPlace(bsi).isEmpty() && !carefulSprint();
    }

    // sprintableAscend knows traverse -> ascend -> straight on and nothing else, so an ascend into another ascend, a parkour or
    // a bend let go of the sprint in mid air and landed at walk speed. not vanilla's doing, 26.3 re-arms sprint on any tick
    // forward is held, it was our own "no" keeping the key off. this is the same question asked from the ascend itself: does
    // whatever comes next sprint, and is it roughly the way we are going. the jump is the movement's, sprint doesn't replace it
    private boolean sprintsThroughAscend(MovementAscend ascend) {
        return throughAscendBlocker(ascend, false) == null;
    }

    // null when sprintsThroughAscend says yes, else which check said no. detail builds the text that names the cell or the angle,
    // which is only worth the string work when the sprint telemetry is going to quote it
    private String throughAscendBlocker(MovementAscend ascend, boolean detail) {
        if (!Baritone.settings().sprintAscends.value || pathPosition >= path.length() - 2) {
            return "sprintAscends off or last movement"; // the last movement is the goal and we don't sprint past that
        }
        if (StairSteps.walkUp(ctx.world().getBlockState(ascend.getDest().below()), ascend.getSrc(), ascend.getDest(),
                ctx.world().getBlockState(ascend.getSrc().below()), ctx.world())) {
            return "walk-up stair"; // sprintsUpStairs already had its say
        }
        Movement next = (Movement) path.movements().get(pathPosition + 1);
        // no parkour: the hop can fire from a block or more back and flies a couple of cells at sprint speed, which is fine over
        // floor and not over the gap. descends only when the descend itself would let us sprint (its safeMode is the hazard past the landing).
        // a flat diagonal sprints on its own corner check, and the corners it asks about are the columns beside the landing block
        boolean sprintsNext = next instanceof MovementTraverse || next instanceof MovementAscend
                || next instanceof MovementDescend && Baritone.settings().sprintThroughDescends.value && !((MovementDescend) next).safeMode();
        boolean diagonal = next instanceof MovementDiagonal && next.getDirection().getY() == 0;
        BlockStateInterface bsi = behavior.baritone.bsi;
        if (!(sprintsNext || diagonal)) {
            return detail ? "next movement is " + next.getClass().getSimpleName() : "next movement";
        }
        if (!ascend.toBreak(bsi).isEmpty() || !ascend.toPlace(bsi).isEmpty() || !next.toBreak(bsi).isEmpty() || !next.toPlace(bsi).isEmpty()) {
            return "break or place";
        }
        BlockPos dir = ascend.getDirection();
        if (pathPosition > 0) {
            // sprintableAscend wants the traverse before the hop to be in line, and so do we. a corner into a hop still has the
            // old heading's momentum in it, and that is what the lateral stall in MovementAscend is for
            BlockPos prevDir = path.movements().get(pathPosition - 1).getDirection();
            double turn = SprintPolicy.turnDegrees(prevDir.getX(), prevDir.getZ(), dir.getX(), dir.getZ());
            if (turn > SprintPolicy.STRAIGHT_ON) {
                return detail ? String.format(Locale.ROOT, "prev turn %.0f deg > %.0f", turn, SprintPolicy.STRAIGHT_ON) : "prev turn";
            }
        }
        BlockPos nextDir = next.getDirection();
        boolean careful = carefulSprint();
        double nextTurn = SprintPolicy.turnDegrees(dir.getX(), dir.getZ(), nextDir.getX(), nextDir.getZ());
        if (!SprintPolicy.ascendSprintsInto(sprintsNext, diagonal && ((MovementDiagonal) next).cornersOpen(bsi), careful, nextTurn)) {
            return detail ? String.format(Locale.ROOT, "next turn %.0f deg%s%s", nextTurn, careful ? ", careful" : "", diagonal ? ", diagonal corners" : "") : "next turn";
        }
        if (!MovementHelper.canWalkOn(ctx, ascend.getDest().below())) {
            return "step floor not walkable";
        }
        String room = hopRoomBlocker(ctx, ascend, detail);
        return room == null ? null : "hop room: " + room;
    }

    // null when an ascend keeps the sprint on, else why not. the same chain shouldSprintNextTick walks for an ascend (its stair
    // and descend branches, a traverse before it, then sprintsThroughAscend), asked from outside so the movement can know
    // before it picks its keys. the descend branch's side effect (letting go of space) stays in shouldSprintNextTick
    private String ascendBlocker(MovementAscend current, boolean detail) {
        if (!Baritone.settings().allowSprint.value || ctx.player().getFoodData().getFoodLevel() <= 6) {
            return "allowSprint off or food";
        }
        if (sprintsUpStairs(current)) {
            return null;
        }
        String after = "";
        if (pathPosition != 0) {
            IMovement prev = path.movements().get(pathPosition - 1);
            if (prev instanceof MovementDescend && prev.getDirection().above().equals(current.getDirection().below())
                    && ctx.player().position().y >= current.getSrc().above().getY() - 0.07) {
                return null;
            }
            if (pathPosition < path.length() - 2 && prev instanceof MovementTraverse) {
                String why = sprintableAscendBlocker(ctx, (MovementTraverse) prev, current, path.movements().get(pathPosition + 1), detail);
                if (why == null) {
                    return null;
                }
                after = detail ? "after traverse: " + why + "; " : "after traverse; ";
            }
        }
        String through = throughAscendBlocker(current, detail);
        return through == null ? null : after + through;
    }

    // a small drop, no bucket and no clutch in sight, and the path goes on roughly the way we were going
    private boolean sprintsThroughFall(MovementFall fall) {
        int drop = -fall.getDirection().getY();
        if (!Baritone.settings().sprintThroughDescends.value || drop < 2 || drop > ExperimentalMovement.SAFE_FALL
                || drop > Baritone.settings().maxFallHeightNoWater.value || pathPosition >= path.length() - 3) {
            return false; // anything that is not a plain safe fall lands somewhere it wants a say in (it centers on the landing)
        }
        return sprintsPastLanding(fall, path.movements().get(pathPosition + 1), drop);
    }

    // the descend rules above only know "straight on". this one asks what the cells we would coast over actually are
    private boolean sprintsPastLanding(IMovement current, IMovement next, int plannedDrop) {
        if (!Baritone.settings().sprintThroughDescends.value || pathPosition >= path.length() - 3) {
            return false; // the last movement is the goal, and nobody wants to be sprinting past that
        }
        if (current instanceof MovementDescend && ((MovementDescend) current).skipToAscend()) {
            return false; // the weird overshoot glitch has its own handling
        }
        boolean walksOn = next instanceof MovementTraverse || next instanceof MovementDescend
                || next instanceof MovementDiagonal && Baritone.settings().allowOvershootDiagonalDescend.value;
        BlockPos dir = current.getDirection();
        BlockPos nextDir = next.getDirection();
        if (!walksOn || nextDir.getY() > 0 || Math.abs(dir.getX()) + Math.abs(dir.getZ()) != 1 || carefulSprint()) {
            return false;
        }
        BlockPos land = current.getDest();
        double turn = SprintPolicy.turnDegrees(dir.getX(), dir.getZ(), nextDir.getX(), nextDir.getZ());
        return SprintPolicy.descendKeepsSprint(false, turn,
                SprintPolicy.overshootSafe(new WorldTerrain(behavior.baritone.bsi), land.getX(), land.getY(), land.getZ(), dir.getX(), dir.getZ(), plannedDrop));
    }

    // one answer to "should we do this the old careful way" for every rule that sprints where it used to stop: the nether,
    // lava near where we are going, low health, or something close by placing / breaking (bridging, pillaring, parkour places)
    private boolean carefulSprint() {
        if (carefulAt != pathPosition) {
            carefulAt = pathPosition; // 4 lava scans are a lot to do every tick, the path only changes under us once per movement
            List<BlockPos> centers = new ArrayList<>();
            centers.add(ctx.playerFeet());
            for (int i = pathPosition; i <= pathPosition + 2 && i < path.movements().size(); i++) {
                centers.add(path.movements().get(i).getDest());
            }
            carefulLava = SprintPolicy.lavaNear(new WorldTerrain(behavior.baritone.bsi), centers);
        }
        return SprintPolicy.careful(ctx.world().dimension() == Level.NETHER, carefulLava, ctx.player().getHealth(), placingNearby());
    }

    private boolean placingNearby() {
        BlockStateInterface bsi = behavior.baritone.bsi;
        double[] progress = new double[SprintPolicy.MAX_INSTANT_BREAKS];
        int breaks = 0;
        ToolSet tools = null;
        // breaking is like 5x slower in water and in the air, and the click can land while we're still coming down a descend
        boolean slowSwing = ctx.player().isInWater() || !ctx.player().onGround()
                || pathPosition < path.movements().size() && (path.movements().get(pathPosition) instanceof MovementDescend || path.movements().get(pathPosition) instanceof MovementFall);
        for (int i = pathPosition; i <= pathPosition + 2 && i < path.movements().size(); i++) {
            Movement movement = (Movement) path.movements().get(i);
            if (movement instanceof MovementPillar || !movement.toPlace(bsi).isEmpty()) {
                return true;
            }
            for (BlockPos pos : movement.toBreak(bsi)) {
                if (breaks == progress.length) {
                    return true; // a tunnel, however fast the pick is
                }
                if (tools == null) {
                    tools = new ToolSet(ctx.player());
                }
                progress[breaks++] = tools.getStrVsBlock(bsi.get0(pos)) * (slowSwing ? 0.2 : 1);
            }
        }
        return !SprintPolicy.trivialBreaks(progress, breaks);
    }

    // turn a little before the corner instead of at it. a movement is done the moment we step into its dest block, and at
    // sprint speed the old heading carries a third of a block past that, so the hitbox leans on the wall on the inside of
    // the bend, vanilla calls that a collision, and a collision ends the sprint
    private void cornerSteering(Movement movement, BlockStateInterface bsi) {
        if (!Baritone.settings().sprintThroughCorners.value || pathPosition >= path.movements().size() - 1
                || !behavior.baritone.getInputOverrideHandler().isInputForcedDown(Input.SPRINT) || !canSteerOnGround(movement)) {
            return; // not sprinting into it means there is no momentum to steer, and the movement knows best
        }
        IMovement next = path.movements().get(pathPosition + 1);
        if (!flatWalk(movement, bsi) || !flatWalk(next, bsi)) {
            return;
        }
        BlockPos dir = movement.getDirection();
        BlockPos nextDir = next.getDirection();
        double turn = SprintPolicy.turnDegrees(dir.getX(), dir.getZ(), nextDir.getX(), nextDir.getZ());
        if (!SprintPolicy.preTurnable(turn)) {
            return;
        }
        Vec3 position = ctx.player().position();
        double[] aim = SprintPolicy.cornerAim(position.x, position.z, movement.getSrc(), movement.getDest(), next.getDest());
        if (aim == null || carefulSprint()) {
            return;
        }
        boolean tight = !SprintPolicy.cornerBoxClear(movement.getSrc(), movement.getDest(), next.getDest(), pos -> clearSmoothingColumn(bsi, pos));
        boolean hazards = SprintPolicy.hazardAround(new WorldTerrain(bsi), movement.getDest());
        if (!SprintPolicy.cornerKeepsSprint(false, turn, hazards, tight)) {
            return;
        }
        steerMask |= STEER_CORNER;
        behavior.baritone.getLookBehavior().updateTarget(
                RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vec3(aim[0], movement.getDest().y, aim[1]), ctx.playerRotations())
                        .withPitch(ctx.playerRotations().getPitch()), false);
    }

    // flat walking with nothing to break or place on the way
    private boolean flatWalk(IMovement movement, BlockStateInterface bsi) {
        return (movement instanceof MovementTraverse || movement instanceof MovementDiagonal)
                && movement.getSrc().y == movement.getDest().y
                && ((Movement) movement).toPlace(bsi).isEmpty() && ((Movement) movement).toBreak(bsi).isEmpty();
    }

    // what the sprint rules want to know about blocks, answered from the live world
    private static final class WorldTerrain implements SprintPolicy.Terrain {

        private final BlockStateInterface bsi;

        private WorldTerrain(BlockStateInterface bsi) {
            this.bsi = bsi;
        }

        @Override
        public boolean hazard(int x, int y, int z) {
            BlockState state = bsi.get0(x, y, z);
            return MovementHelper.avoidWalkingInto(state) || MovementHelper.isLava(state) || state.is(Blocks.MAGMA_BLOCK)
                    || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.WITHER_ROSE);
        }

        @Override
        public boolean lava(int x, int y, int z) {
            return MovementHelper.isLava(bsi.get0(x, y, z));
        }

        @Override
        public boolean passable(int x, int y, int z) {
            return MovementHelper.canWalkThrough(bsi, x, y, z);
        }

        @Override
        public boolean standable(int x, int y, int z) {
            return MovementHelper.canWalkOn(bsi, x, y, z);
        }
    }

    /**
     * @return true if we're still in the air and this tick is handled
     */
    private boolean fly() {
        if (!ctx.player().onGround() && !ctx.player().isInWater() && !ctx.player().isInLava() && !ctx.player().onClimbable()
                && flight.ticks < SprintJump.MAX_TICKS && ExperimentalMovement.sprintJumping(ctx.world())) {
            steer(false);
            // vanilla drops sprint on its own the tick W goes up, but sprint held with S down is exactly what a sprint check
            // looks for, so let go of it too. the next push presses it again before aiStep and sprint comes straight back
            sprintNextTick = flight.throttle() == SprintJump.PUSH;
            return true;
        }
        // we probably flew over a few movements, pick up at whichever one we came down in (diagonal side cells count).
        // anywhere else and the usual valid positions / off path recovery takes it from here
        for (int i = pathPosition; i < Math.min(path.movements().size(), pathPosition + flight.floors.length); i++) {
            if (((Movement) path.movements().get(i)).getValidPositions().contains(ctx.playerFeet())) {
                pathPosition = i;
                break;
            }
        }
        flight = null;
        onChangeInPathPosition();
        return false;
    }

    private void steer(boolean takeoff) {
        // the movements steer at their own dest and drop W the moment our feet are a block up ("Wrong Y coordinate"),
        // which was the mid air stall, so while we're up here nobody else gets a say. space only on takeoff: letting go
        // in the air resets the vanilla 10 tick jump delay, holding it made a jump up a step (~9 ticks) wait on landing
        // a brake is S or letting go, never a head turn. every flight tick clears and re-presses, and landing clears
        // again (onChangeInPathPosition), so S never outlives its brake tick
        clearKeys();
        behavior.baritone.getLookBehavior().updateTarget(flight.steer(ctx), false);
        behavior.baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, flight.throttle() == SprintJump.PUSH);
        behavior.baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, flight.throttle() == SprintJump.BACK);
        behavior.baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, takeoff);
    }

    /**
     * Sprint jump into a low ceiling (1x2 corridors, overhangs, etc.) for a bit of extra speed.
     * The sprint jump boost applies on the first ticks of the jump, before we bonk our head on the ceiling.
     * This runs after movement.update() has cleared and reasserted the forced inputs,
     * so a jump forced here lasts exactly one tick.
     */
    private void headHitJump(IMovement current) {
        if (!canStartHeadHitting(current) || !underHeadBonkCeiling(current.getDirection(), ctx.playerFeet()) || !clearOfLedgesAhead(current.getDirection())) {
            return;
        }
        behavior.baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
    }

    private boolean canStartHeadHitting(IMovement current) {
        if (!(current instanceof MovementTraverse || current instanceof MovementDiagonal) || current.getDirection().getY() != 0) {
            return false; // head hitting only applies to flat walking movements
        }
        if (current instanceof MovementDiagonal && !Baritone.settings().headHittersDiagonal.value) {
            return false;
        }
        if (!ctx.player().onGround() || MovementHelper.isLiquid(ctx, ctx.playerFeet())) {
            return false;
        }
        if (!ctx.player().onGround() || MovementHelper.isLiquid(ctx, ctx.playerFeet()) || ctx.player().isInWater()) {
            return false; // hopping in water or on a vine just sticks us to it instead
        }
        if (((Movement) current).toBreakCached == null || !((Movement) current).toBreakCached.isEmpty()) {
            return false; // breaking is like 5x slower when you're jumping
        }
        // not while sneaking either, e.g. walking on magma, a jump would break the sneak and the edge safety with it
        return !behavior.baritone.getInputOverrideHandler().isInputForcedDown(Input.SNEAK);
    }

    private boolean underHeadBonkCeiling(BlockPos dir, BetterBlockPos feet) {
        BlockPos ceiling = feet.above(2);
        if (MovementHelper.fullyPassable(ctx, ceiling) || !MovementHelper.isBlockNormalCube(ctx.world().getBlockState(ceiling))) {
            return false; // not under a ceiling yet, or the thing overhead is something like a trapdoor that we can't reliably bonk against
        }
        // diagonals can enter through either face, so clear each axis separately
        return clearOfCeilingEntrance(feet, dir.getX(), 0) && clearOfCeilingEntrance(feet, 0, dir.getZ());
    }

    private boolean clearOfCeilingEntrance(BetterBlockPos feet, int dx, int dz) {
        if (dx == 0 && dz == 0) {
            return true;
        }
        // make sure we're fully inside the corridor before we start jumping, same idea as skipNow
        BlockPos behind = feet.offset(-dx, 0, -dz).above(2);
        if (MovementHelper.fullyPassable(ctx, behind)) {
            double flatDist = Math.abs(dx * (behind.getX() + 0.5D - ctx.player().position().x)) + Math.abs(dz * (behind.getZ() + 0.5D - ctx.player().position().z));
            return flatDist >= 0.8; // just entered, wait until we're clear of the entrance face
        }
        return true;
    }

    private boolean clearOfLedgesAhead(BlockPos dir) {
        // momentum from the head bonk can carry us an extra block or two, so don't headhit unless the next two blocks
        // in this direction are also part of the path, that way momentum can never send us off it
        for (int i = 1; i <= 2; i++) {
            if (pathPosition + i > path.length() - 2 || !path.movements().get(pathPosition + i).getDirection().equals(dir)) {
                return false; // the path turns or ends within two blocks, don't add any momentum
            }
            Movement next = (Movement) path.movements().get(pathPosition + i);
            if (next.toPlaceCached != null && !next.toPlaceCached.isEmpty()) {
                return false; // the movement is going to place its own support, and momentum can arrive before those blocks do
            }
            BlockPos floor = next.getDest().below();
            if (MovementHelper.isLiquid(ctx, floor) || !MovementHelper.canWalkOn(ctx, floor)) {
                return false; // no real support under the destination yet: liquid (frostwalker ice hasn't frozen yet) or passable (ladders/vines have no floor at all), and momentum can't wait for it to appear
            }
        }
        return true;
    }

    private Pair<Vec3, BlockPos> overrideFall(MovementFall movement) {
        Vec3i dir = movement.getDirection();
        if (dir.getY() < -3) {
            return null;
        }
        if (!movement.toBreakCached.isEmpty()) {
            return null; // it's breaking
        }
        Vec3i flatDir = new Vec3i(dir.getX(), 0, dir.getZ());
        int i;
        outer:
        for (i = pathPosition + 1; i < path.length() - 1 && i < pathPosition + 3; i++) {
            IMovement next = path.movements().get(i);
            if (!(next instanceof MovementTraverse)) {
                break;
            }
            if (!flatDir.equals(next.getDirection())) {
                break;
            }
            for (int y = next.getDest().y; y <= movement.getSrc().y + 1; y++) {
                BlockPos chk = new BlockPos(next.getDest().x, y, next.getDest().z);
                if (!MovementHelper.fullyPassable(ctx, chk)) {
                    break outer;
                }
            }
            if (!MovementHelper.canWalkOn(ctx, next.getDest().below())) {
                break;
            }
            // aiming here moves the landing, so it has to pass the same rule the planned one did in dynamicFallCost
            if (LavaWake.besideLanding(behavior.secretInternalGetCalculationContext().lavaCells, next.getDest().x, next.getDest().y, next.getDest().z)) {
                break;
            }
        }
        i--;
        if (i == pathPosition) {
            return null; // no valid extension exists
        }
        double len = i - pathPosition - 0.4;
        return new Pair<>(
                new Vec3(flatDir.getX() * len + movement.getDest().x + 0.5, movement.getDest().y, flatDir.getZ() * len + movement.getDest().z + 0.5),
                movement.getDest().offset(flatDir.getX() * (i - pathPosition), 0, flatDir.getZ() * (i - pathPosition)));
    }

    // same number SprintJump.plan and the ascend's own side check use, they used to disagree
    // (a wall beside the cells we are about to cross makes the lane narrower, see SprintPolicy.centered)
    private static boolean onLane(IPlayerContext ctx, IMovement current, IMovement hop) {
        double leanX = ctx.player().position().x - (current.getSrc().x + 0.5D), leanZ = ctx.player().position().z - (current.getSrc().z + 0.5D);
        double offTarget = Math.abs(current.getDirection().getX() * leanZ) + Math.abs(current.getDirection().getZ() * leanX);
        int dirX = current.getDirection().getX(), dirZ = current.getDirection().getZ();
        boolean open = MovementHelper.sideOpen(ctx, current.getSrc().x, current.getSrc().y, current.getSrc().z, dirX, dirZ, leanX, leanZ)
                && MovementHelper.sideOpen(ctx, current.getDest().x, current.getDest().y, current.getDest().z, dirX, dirZ, leanX, leanZ)
                && MovementHelper.sideOpen(ctx, hop.getDest().x, hop.getDest().y, hop.getDest().z, dirX, dirZ, leanX, leanZ);
        return SprintPolicy.centered(offTarget, open);
    }

    private static boolean skipNow(IPlayerContext ctx, IMovement current, IMovement hop) {
        if (!onLane(ctx, current, hop)) {
            return false;
        }
        // we are centered
        BlockPos headBonk = current.getSrc().subtract(current.getDirection()).above(2);
        if (MovementHelper.fullyPassable(ctx, headBonk)) {
            return true;
        }
        // wait 0.3
        double flatDist = Math.abs(current.getDirection().getX() * (headBonk.getX() + 0.5D - ctx.player().position().x)) + Math.abs(current.getDirection().getZ() * (headBonk.getZ() + 0.5 - ctx.player().position().z));
        return flatDist > 0.8;
    }

    private static boolean sprintableAscend(IPlayerContext ctx, MovementTraverse current, MovementAscend next, IMovement nextnext) {
        return sprintableAscendBlocker(ctx, current, next, nextnext, false) == null;
    }

    // null when the traverse -> ascend -> straight on shape sprints, else which check said no (detail: with the cell or block in it)
    private static String sprintableAscendBlocker(IPlayerContext ctx, MovementTraverse current, MovementAscend next, IMovement nextnext, boolean detail) {
        if (!Baritone.settings().sprintAscends.value) {
            return "sprintAscends off";
        }
        if (StairSteps.walkUp(ctx.world().getBlockState(next.getDest().below()), next.getSrc(), next.getDest(),
                ctx.world().getBlockState(next.getSrc().below()), ctx.world())) {
            return "walk-up stair"; // no hop to time for this one, sprintsUpStairs walks it
        }
        if (!current.getDirection().equals(next.getDirection().below())) {
            return "traverse not in line with the hop";
        }
        if (nextnext.getDirection().getX() != next.getDirection().getX() || nextnext.getDirection().getZ() != next.getDirection().getZ()) {
            return detail ? "movement after the hop turns (" + nextnext.getClass().getSimpleName() + ")" : "movement after the hop turns";
        }
        if (!MovementHelper.canWalkOn(ctx, current.getDest().below())) {
            return "traverse floor not walkable";
        }
        if (!MovementHelper.canWalkOn(ctx, next.getDest().below())) {
            return "step floor not walkable";
        }
        if (!next.toBreakCached.isEmpty()) {
            return "hop is breaking"; // it's breaking
        }
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 3; y++) {
                BlockPos chk = current.getSrc().above(y);
                if (x == 1) {
                    chk = chk.offset(current.getDirection());
                }
                if (!MovementHelper.fullyPassable(ctx, chk)) {
                    return detail ? "column " + cellNote(ctx, current.getSrc(), chk) : "column";
                }
            }
        }
        if (MovementHelper.avoidWalkingInto(ctx.world().getBlockState(current.getSrc().above(3)))) {
            return detail ? "avoidWalkingInto " + cellNote(ctx, current.getSrc(), current.getSrc().above(3)) : "avoidWalkingInto";
        }
        if (AltoClefSettings.getInstance().shouldAvoidWalkThroughForce(current.getSrc().above(3)) || AltoClefSettings.getInstance().shouldAvoidWalkThroughForce(current.getSrc().above(2))) {
            return "altoclef avoid"; // sprinting would carry us into somewhere altoclef wants us to stay out of
        }
        if (MovementHelper.avoidWalkingInto(ctx.world().getBlockState(next.getDest().above(2)))) { // codacy smh my head
            return detail ? "avoidWalkingInto " + cellNote(ctx, current.getSrc(), next.getDest().above(2)) : "avoidWalkingInto";
        }
        return null;
    }

    // the jump goes up through the ascend's own column, so it has to be open head high and not somewhere altoclef or the
    // terrain wants us kept out of. sprintableAscend asks the same things of the traverse's column
    private static String hopRoomBlocker(IPlayerContext ctx, MovementAscend ascend, boolean detail) {
        BlockPos src = ascend.getSrc();
        for (int y = 0; y < 3; y++) {
            if (!MovementHelper.fullyPassable(ctx, src.above(y))) {
                return detail ? cellNote(ctx, src, src.above(y)) : "column";
            }
            if (y < 2 && !MovementHelper.fullyPassable(ctx, ascend.getDest().above(y))) {
                return detail ? cellNote(ctx, src, ascend.getDest().above(y)) : "column";
            }
        }
        if (MovementHelper.avoidWalkingInto(ctx.world().getBlockState(src.above(3)))) {
            return detail ? "avoidWalkingInto " + cellNote(ctx, src, src.above(3)) : "avoidWalkingInto";
        }
        if (MovementHelper.avoidWalkingInto(ctx.world().getBlockState(ascend.getDest().above(2)))) {
            return detail ? "avoidWalkingInto " + cellNote(ctx, src, ascend.getDest().above(2)) : "avoidWalkingInto";
        }
        if (AltoClefSettings.getInstance().shouldAvoidWalkThroughForce(src.above(3)) || AltoClefSettings.getInstance().shouldAvoidWalkThroughForce(src.above(2))) {
            return "altoclef avoid";
        }
        return null;
    }

    // "(+1,+2,+0)stone", the cell relative to the hop's start. only built when the telemetry is going to print it
    private static String cellNote(IPlayerContext ctx, BlockPos origin, BlockPos at) {
        return String.format(Locale.ROOT, "(%+d,%+d,%+d)%s", at.getX() - origin.getX(), at.getY() - origin.getY(), at.getZ() - origin.getZ(),
                BuiltInRegistries.BLOCK.getKey(ctx.world().getBlockState(at).getBlock()).getPath());
    }

    private static boolean canSprintFromDescendInto(IPlayerContext ctx, IMovement current, IMovement next) {
        if (next instanceof MovementDescend && next.getDirection().equals(current.getDirection())) {
            return true;
        }
        if (!MovementHelper.canWalkOn(ctx, current.getDest().offset(current.getDirection()))) {
            return false;
        }
        if (next instanceof MovementTraverse && next.getDirection().equals(current.getDirection())) {
            return true;
        }
        return next instanceof MovementDiagonal && Baritone.settings().allowOvershootDiagonalDescend.value;
    }

    private void onChangeInPathPosition() {
        clearKeys();
        ticksOnCurrent = 0;
        hopWaits = 0;
        guardTicks = 0;
    }

    private void clearKeys() {
        // i'm just sick and tired of this snippet being everywhere lol
        behavior.baritone.getInputOverrideHandler().clearAllKeys();
    }

    // before the cancel, so the replan it triggers already sees it and a fast fail loop climbs the ladder. only the movement
    // itself failing counts, a process pulling the path out from under us says nothing about the edge
    private void rememberFailure(Movement movement, boolean timedOut) {
        if (FailedMoves.forgives(timedOut, timedOut && climbEdge(movement))) {
            return;
        }
        behavior.failedMoves().record(movement.getSrc(), movement.getDest(), ctx.world(), System.currentTimeMillis());
    }

    // a climb that runs out of time is the bot swaying on the rungs, not a bad edge. remembering those made A* walk the
    // long way round every ladder it had ever wobbled on. only edges that change height (plus the climb jumps, a leap
    // can land level): a flat walk under hanging cave vines that really is blocked still has to count. the cell over src
    // is the one a climb up goes into, the one under dest is the top rung it comes off
    private boolean climbEdge(Movement movement) {
        BetterBlockPos src = movement.getSrc();
        BetterBlockPos dest = movement.getDest();
        if (src.y == dest.y && !(movement instanceof MovementClimb)) {
            return false;
        }
        return climbable(src) || climbable(src.above()) || climbable(dest) || climbable(dest.below());
    }

    private boolean climbable(BetterBlockPos pos) {
        return FailedMoves.climbable(ctx.world().getBlockState(pos).getBlock());
    }

    private void cancel() {
        if (releasedWhy.isEmpty()) {
            releasedWhy = "path cancelled";
        }
        groundShortcut = null;
        sprintNextTick = false;
        clearKeys();
        behavior.baritone.getInputOverrideHandler().getBlockBreakHelper().stopBreakingBlock();
        pathPosition = path.length() + 3;
        failed = true;
    }

    @Override
    public int getPosition() {
        return pathPosition;
    }

    public PathExecutor trySplice(PathExecutor next) {
        if (groundShortcut != null) {
            return this; // finish the short ground segment before replacing its executor and indices
        }
        if (next == null) {
            return cutIfTooLong();
        }
        return SplicedPath.trySplice(path, next.path, false).map(path -> {
            if (!path.getDest().equals(next.getPath().getDest())) {
                throw new IllegalStateException(String.format(
                        "Path has end %s instead of %s after splicing",
                        path.getDest(), next.getPath().getDest()));
            }
            PathExecutor ret = new PathExecutor(behavior, path);
            ret.pathPosition = pathPosition;
            ret.currentMovementOriginalCostEstimate = currentMovementOriginalCostEstimate;
            ret.costEstimateIndex = costEstimateIndex;
            ret.ticksOnCurrent = ticksOnCurrent;
            if (pathPosition < highWater.high()) {
                // the new path keeps our index but not necessarily what came after it, the old high might be another movement now
                highWater.forget();
            }
            ret.highWater = highWater;
            ret.timer = timer;
            ret.flight = flight; // a new path showing up doesn't make us any less airborne
            // a splice keeps every index of the first path, so the trip carries straight over. losing it
            // mid placement would have the new executor place a second boat
            if (boat != null) {
                boat.rebase(path);
                ret.boat = boat;
            }
            ret.boatRefusedUntil = boatRefusedUntil;
            ret.shortcutCooldownEnd = shortcutCooldownEnd;
            return ret;
        }).orElseGet(this::cutIfTooLong); // dont actually call cutIfTooLong every tick if we won't actually use it, use a method reference
    }

    // the lane the renderer draws for each of boatRuns(), same order, cached alongside them
    public List<Vec3> boatLane(int[] run) {
        boatRuns();
        for (int i = 0; i < boatRuns.size(); i++) {
            if (boatRuns.get(i) == run) {
                return boatLanes.get(i);
            }
        }
        return BoatTrip.smoothLane(new BlockStateInterface(behavior.baritone.getPlayerContext()), path.positions(), run[0], run[1]);
    }

    // the stretches of this path that will be crossed by boat, as [first land index, last water index] pairs.
    // for the renderer, which asks every frame, so it's only recomputed twice a second
    public List<int[]> boatRuns() {
        long now = System.currentTimeMillis();
        if (boatRuns == null || now - boatRunsComputedAt > 500) {
            boatRuns = BoatTrip.runs(behavior.baritone, path);
            // the drawn lanes too, the renderer was rebuilding them every frame
            boatLanes = new ArrayList<>();
            BlockStateInterface bsi = boatRuns.isEmpty() ? null : new BlockStateInterface(behavior.baritone.getPlayerContext());
            for (int[] run : boatRuns) {
                boatLanes.add(BoatTrip.smoothLane(bsi, path.positions(), run[0], run[1]));
            }
            boatRunsComputedAt = now;
        }
        if (boatRefusedUntil < 0) {
            return boatRuns;
        }
        // a run we gave up on goes back to being drawn as a swim, since that's what it is now
        List<int[]> stillOn = new ArrayList<>();
        for (int[] run : boatRuns) {
            if (run[1] > boatRefusedUntil) {
                stillOn.add(run);
            }
        }
        return stillOn;
    }

    private PathExecutor cutIfTooLong() {
        // not while boating. a long crossing walks pathPosition past the limit, the cut shifts the trip's
        // start to like -30 and the lane goes looking for positions[-30]. the history can wait for dry land
        if (boat != null) {
            return this;
        }
        if (pathPosition > Baritone.settings().maxPathHistoryLength.value) {
            int cutoffAmt = Baritone.settings().pathHistoryCutoffAmount.value;
            CutoffPath newPath = new CutoffPath(path, cutoffAmt, path.length() - 1);
            if (!newPath.getDest().equals(path.getDest())) {
                throw new IllegalStateException(String.format(
                        "Path has end %s instead of %s after trimming its start",
                        newPath.getDest(), path.getDest()));
            }
            logDebug("Discarding earliest segment movements, length cut from " + path.length() + " to " + newPath.length());
            PathExecutor ret = new PathExecutor(behavior, newPath);
            ret.pathPosition = pathPosition - cutoffAmt;
            ret.currentMovementOriginalCostEstimate = currentMovementOriginalCostEstimate;
            if (costEstimateIndex != null) {
                ret.costEstimateIndex = costEstimateIndex - cutoffAmt;
            }
            ret.ticksOnCurrent = ticksOnCurrent;
            highWater.shift(cutoffAmt);
            ret.highWater = highWater;
            ret.timer = timer;
            ret.flight = flight;
            ret.boatRefusedUntil = boatRefusedUntil < 0 ? -1 : boatRefusedUntil - cutoffAmt;
            ret.shortcutCooldownEnd = shortcutCooldownEnd < 0 ? -1 : shortcutCooldownEnd - cutoffAmt;
            return ret;
        }
        return this;
    }

    @Override
    public IPath getPath() {
        return path;
    }

    public boolean failed() {
        return failed;
    }

    public boolean bailedOverDrop() {
        return failed && bailedOverDrop;
    }

    public boolean finished() {
        return pathPosition >= path.length();
    }

    public Set<BlockPos> toBreak() {
        return Collections.unmodifiableSet(toBreak);
    }

    public Set<BlockPos> toPlace() {
        return Collections.unmodifiableSet(toPlace);
    }

    public Set<BlockPos> toWalkInto() {
        return Collections.unmodifiableSet(toWalkInto);
    }

    public boolean isSprinting() {
        return sprintNextTick;
    }

    // for SprintTelemetry. empty unless this tick had a reason
    String sprintWhy() {
        return sprintWhy;
    }

    String releasedWhy() {
        return releasedWhy;
    }

    String steering() {
        if (steerMask == 0) {
            return "none";
        }
        StringBuilder out = new StringBuilder();
        String[] names = {"smooth", "corner", "sprintjump", "shortcut", "brake"};
        for (int i = 0; i < names.length; i++) {
            if ((steerMask & 1 << i) != 0) {
                out.append(out.length() == 0 ? "" : "+").append(names[i]);
            }
        }
        return out.toString();
    }

    // NaN when no ground shortcut ticked
    double shortcutDistance() {
        return shortcutDist;
    }

    // null likewise
    Vec3 shortcutTarget() {
        return shortcutAim;
    }

    // where the shortcut set off from, null likewise
    Vec3 shortcutStart() {
        return shortcutFrom;
    }

    // the path node the shortcut aims for, -1 likewise
    int shortcutEndIndex() {
        return shortcutEnd;
    }
}
