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

package baritone.pathing.movement.movements;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.behavior.PathingBehavior;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.pathing.movement.StairSteps;
import baritone.pathing.path.SprintPolicy;
import baritone.pathing.sim.AscendHop;
import baritone.utils.BlockStateInterface;
import com.google.common.collect.ImmutableSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class MovementAscend extends Movement {

    // the lateral stall waits out sideways drift, a sprint jump landing has a little of it by itself
    private static final double SPRINT_DRIFT_LATERAL = 0.15;

    private static final int MAX_SIM_WAITS = 8;

    private int ticksWithoutPlacement = 0;
    private int simWaits = 0;
    // set by the executor before every update: does the sprint carry on through this hop. a brake is only worth it if it does
    private boolean keepsSprint;
    // this tick's keys are a brake, so nothing downstream gets to "fix" W (the ascend guard would see a run at the face)
    private boolean braked;

    public MovementAscend(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest) {
        super(baritone, src, dest, new BetterBlockPos[]{dest, src.above(2), dest.above()}, dest.below());
    }

    @Override
    public void reset() {
        super.reset();
        ticksWithoutPlacement = 0;
        simWaits = 0;
        keepsSprint = false;
        braked = false;
    }

    public void setKeepsSprint(boolean keepsSprint) {
        this.keepsSprint = keepsSprint;
    }

    public boolean braked() {
        return braked;
    }

    @Override
    public double calculateCost(CalculationContext context) {
        return cost(context, src.x, src.y, src.z, dest.x, dest.z);
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        BetterBlockPos prior = new BetterBlockPos(src.subtract(getDirection()).above()); // sometimes we back up to place the block, also sprint ascends, also skip descend to straight ascend
        return ImmutableSet.of(src,
                src.above(),
                dest,
                prior,
                prior.above()
        );
    }

    public static double cost(CalculationContext context, int x, int y, int z, int destX, int destZ) {
        BlockState toPlace = context.get(destX, y, destZ);
        double additionalPlacementCost = 0;
        if (!MovementHelper.canWalkOn(context, destX, y, destZ, toPlace)) {
            additionalPlacementCost = context.costOfPlacingAt(destX, y, destZ, toPlace);
            if (additionalPlacementCost >= COST_INF) {
                return COST_INF;
            }
            if (!MovementHelper.isReplaceable(destX, y, destZ, toPlace, context.bsi)) {
                return COST_INF;
            }
            boolean foundPlaceOption = false;
            for (int i = 0; i < 5; i++) {
                int againstX = destX + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getStepX();
                int againstY = y + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getStepY();
                int againstZ = destZ + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getStepZ();
                if (againstX == x && againstZ == z) { // we might be able to backplace now, but it doesn't matter because it will have been broken by the time we'd need to use it
                    continue;
                }
                if (MovementHelper.canPlaceAgainst(context, againstX, againstY, againstZ)) {
                    foundPlaceOption = true;
                    break;
                }
            }
            if (!foundPlaceOption) { // didn't find a valid place =(
                return COST_INF;
            }
        }
        BlockState srcUp2 = context.get(x, y + 2, z); // used lower down anyway
        if (context.get(x, y + 3, z).getBlock() instanceof FallingBlock && (MovementHelper.canWalkThrough(context, x, y + 1, z) || !(srcUp2.getBlock() instanceof FallingBlock))) {//it would fall on us and possibly suffocate us
            // HOWEVER, we assume that we're standing in the start position
            // that means that src and src.up(1) are both air
            // maybe they aren't now, but they will be by the time this starts
            // if the lower one is can't walk through and the upper one is falling, that means that by standing on src
            // (the presupposition of this Movement)
            // we have necessarily already cleared the entire FallingBlock stack
            // on top of our head

            // as in, if we have a block, then two FallingBlocks on top of it
            // and that block is x, y+1, z, and we'd have to clear it to even start this movement
            // we don't need to worry about those FallingBlocks because we've already cleared them
            return COST_INF;
            // you may think we only need to check srcUp2, not srcUp
            // however, in the scenario where glitchy world gen where unsupported sand / gravel generates
            // it's possible srcUp is AIR from the start, and srcUp2 is falling
            // and in that scenario, when we arrive and break srcUp2, that lets srcUp3 fall on us and suffocate us
        }
        BlockState srcDown = context.get(x, y - 1, z);
        if (MovementHelper.isClimbable(srcDown.getBlock())) {
            return COST_INF;
        }
        // we can jump from soul sand, but not from a bottom slab
        boolean jumpingFromBottomSlab = MovementHelper.isBottomSlab(srcDown);
        boolean jumpingToBottomSlab = MovementHelper.isBottomSlab(toPlace);
        if (jumpingFromBottomSlab && !jumpingToBottomSlab) {
            return COST_INF;// the only thing we can ascend onto from a bottom slab is another bottom slab
        }
        double walk;
        if (jumpingToBottomSlab) {
            if (jumpingFromBottomSlab) {
                walk = Math.max(JUMP_ONE_BLOCK_COST, WALK_ONE_BLOCK_COST); // we hit space immediately on entering this action
                walk += context.jumpPenalty;
            } else {
                walk = WALK_ONE_BLOCK_COST; // we don't hit space we just walk into the slab
            }
        } else if (toPlace.getBlock() instanceof StairBlock && StairSteps.walkUp(toPlace, new BlockPos(x, y, z), new BlockPos(destX, y + 1, destZ), srcDown, context.bsi.access)) {
            walk = WALK_ONE_BLOCK_COST; // stairs go up by themselves, no space bar and nothing to pay for it
        } else {
            // jumpingFromBottomSlab must be false
            if (toPlace.is(Blocks.SOUL_SAND)) {
                walk = Math.max(JUMP_ONE_BLOCK_COST, context.soulSandWalkCost);
            } else if (toPlace.is(Blocks.MAGMA_BLOCK)) {
                walk = SNEAK_ONE_BLOCK_COST;
            } else {
                walk = Math.max(JUMP_ONE_BLOCK_COST, WALK_ONE_BLOCK_COST);
            }
            walk += context.jumpPenalty;
        }

        double totalCost = walk + additionalPlacementCost;
        // start with srcUp2 since we already have its state
        // includeFalling isn't needed because of the falling check above -- if srcUp3 is falling we will have already exited with COST_INF if we'd actually have to break it
        // all three are broken from src, so all three pay for the water we're standing in
        double raw1 = MovementHelper.getMiningDurationTicks(context, x, y + 2, z, srcUp2, false);
        totalCost += MovementHelper.waterBreakCost(context, x, y, z, raw1);
        if (totalCost >= COST_INF) {
            return COST_INF;
        }
        double raw2 = MovementHelper.getMiningDurationTicks(context, destX, y + 1, destZ, false);
        totalCost += MovementHelper.waterBreakCost(context, x, y, z, raw2);
        if (totalCost >= COST_INF) {
            return COST_INF;
        }
        double raw3 = MovementHelper.getMiningDurationTicks(context, destX, y + 2, destZ, true);
        totalCost += MovementHelper.waterBreakCost(context, x, y, z, raw3);
        // the jump and the walk happen inside the last break's wait, same as MovementTraverse
        if (totalCost < COST_INF && (raw1 <= 0 || MovementHelper.isMultiTickBreak(context, raw1))
                && (raw2 <= 0 || MovementHelper.isMultiTickBreak(context, raw2))
                && (raw3 <= 0 || MovementHelper.isMultiTickBreak(context, raw3))) {
            totalCost -= MovementHelper.breakWaitCredit(context, Math.max(raw1, Math.max(raw2, raw3)), Math.max(JUMP_ONE_BLOCK_COST, WALK_ONE_BLOCK_COST));
        }
        return totalCost + context.boatHandoff(x, y, z, destX, y + 1, destZ);
    }

    @Override
    public MovementState updateState(MovementState state) {
        braked = false;
        if (ctx.playerFeet().y < src.y) {
            // this check should run even when in preparing state (breaking blocks)
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        super.updateState(state);
        // TODO incorporate some behavior from ActionClimb (specifically how it waited until it was at most 1.2 blocks away before starting to jump
        // for efficiency in ascending minimal height staircases, which is just repeated MovementAscend, so that it doesn't bonk its head on the ceiling repeatedly)
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        if (ctx.playerFeet().equals(dest) || ctx.playerFeet().equals(dest.offset(getDirection().below()))) {
            return state.setStatus(MovementStatus.SUCCESS);
        }

        if (!MovementHelper.openDoors(ctx, state, src.above(), dest)) {
            return state;
        }

        BlockState jumpingOnto = BlockStateInterface.get(ctx, positionToPlace);
        if (!MovementHelper.canWalkOn(ctx, positionToPlace, jumpingOnto)) {
            ticksWithoutPlacement++;
            if (MovementHelper.attemptToPlaceABlock(state, baritone, dest.below(), false, true) == PlaceResult.READY_TO_PLACE) {
                state.setInput(Input.SNEAK, true);
                if (ctx.player().isCrouching()) {
                    state.setInput(Input.CLICK_RIGHT, true);
                }
            }
            // after 10 ticks without placement we might be standing in the way. only back up while that is really true, and
            // never off the back of the source block (see AscendBackUp)
            Vec3 at = ctx.player().position();
            double toEdge = AscendBackUp.centreToBackEdge(at.x, at.z, src.getX(), src.getZ(), dest.getX() - src.getX(), dest.getZ() - src.getZ());
            if (AscendBackUp.mayBack(ticksWithoutPlacement, AscendBackUp.boxInCell(ctx.player().getBoundingBox(), positionToPlace), toEdge)) {
                state.setInput(Input.MOVE_BACK, true);
            }

            return state;
        }
        MovementHelper.moveTowards(ctx, state, dest);

        state.setInput(Input.SNEAK, Baritone.settings().allowWalkOnMagmaBlocks.value && jumpingOnto.is(Blocks.MAGMA_BLOCK));

        if (MovementHelper.isBottomSlab(jumpingOnto) && !MovementHelper.isBottomSlab(BlockStateInterface.get(ctx, src.below()))) {
            return state; // don't jump while walking from a non double slab into a bottom slab
        }

        if (StairSteps.walkUp(jumpingOnto, src, dest, BlockStateInterface.get(ctx, src.below()), ctx.world())) {
            return state; // vanilla steps up the half block lip by itself, a jump here only costs the sprint
        }

        if (Baritone.settings().assumeStep.value || ctx.playerFeet().equals(src.above())) {
            // no need to hit space if we're already jumping
            return state;
        }

        int xAxis = Math.abs(src.getX() - dest.getX()); // either 0 or 1
        int zAxis = Math.abs(src.getZ() - dest.getZ()); // either 0 or 1
        double flatDistToNext = xAxis * Math.abs((dest.getX() + 0.5D) - ctx.player().position().x) + zAxis * Math.abs((dest.getZ() + 0.5D) - ctx.player().position().z);
        double sideDist = zAxis * Math.abs((dest.getX() + 0.5D) - ctx.player().position().x) + xAxis * Math.abs((dest.getZ() + 0.5D) - ctx.player().position().z);

        double lateralMotion = xAxis * ctx.player().getDeltaMovement().z + zAxis * ctx.player().getDeltaMovement().x;
        // the stall is for getting going again after a lateral move. a sprint jump landing drifts a little sideways on its own,
        // and waiting that out is a standing start at the foot of the next step. sideDist still has to say we're on the lane
        boolean sprintDrift = ctx.player().isSprinting() && SprintPolicy.centered(sideDist, sideOpen());
        if (Math.abs(lateralMotion) > (sprintDrift ? SPRINT_DRIFT_LATERAL : 0.1)) {
            return state;
        }

        // a hop clips the face if it starts too close and comes down against it if it starts too far back (at sprint speed the window
        // is about two blocks wide, walking it is a block or so and sits where the 1.2 rule below puts it), and the two rules below
        // don't know that. the sim does, for either pace, so when it has an answer it gets the say
        AscendHop.Verdict hop = null; // stays null when the sim was not asked
        if (baritone instanceof Baritone) {
            PathingBehavior behavior = ((Baritone) baritone).getPathingBehavior();
            AscendHop.Decision decision = AscendHop.ask(ctx, ((Baritone) baritone).bsi, src, dest, keepsSprint ? behavior.ascendBrakes() : null);
            hop = decision.verdict();
            behavior.sprintTelemetry().verdict(decision, src, dest);
            switch (hop) {
                case BRAKE:
                    // W held facing away, no space, and nothing below (headBonkClear, the distance rule) gets a say this tick. the
                    // lateral stall above returns before the sim is asked, so a plan that picks up that much sideways speed is
                    // left, not clobbered, and the next ask searches again
                    braked = true;
                    state.setTarget(new MovementState.MovementTarget(new Rotation(decision.yaw(), ctx.playerRotations().getPitch()), false));
                    state.setInput(Input.MOVE_FORWARD, true);
                    return state;
                case JUMP:
                    return jump(state, "sim JUMP", hop);
                case WAIT:
                    // a few ticks at most, if the window never shows up (stuck on something the sim can't see) the old rules take over
                    // (a brake plan's own push ticks are not part of that budget, jumping early out of one is the clip it avoids)
                    if (decision.planned() || simWaits++ < MAX_SIM_WAITS) {
                        return state;
                    }
                    break;
                default:
                    break;
            }
        }

        if (headBonkClear()) {
            return jump(state, "headBonkClear", hop);
        }

        if (flatDistToNext > 1.2 || !SprintPolicy.centered(sideDist, sideOpen())) {
            return state;
        }

        // Once we are pointing the right way and moving, start jumping
        // This is slightly more efficient because otherwise we might start jumping before moving, and fall down without moving onto the block we want to jump onto
        // Also wait until we are close enough, because we might jump and hit our head on an adjacent block
        return jump(state, "distance rule", hop);
    }

    // nothing solid in the columns beside the hop on the side we are leaning to, see SprintPolicy.centered
    private boolean sideOpen() {
        Vec3 pos = ctx.player().position();
        double leanX = pos.x - (src.x + 0.5), leanZ = pos.z - (src.z + 0.5);
        int dirX = Integer.signum(dest.x - src.x), dirZ = Integer.signum(dest.z - src.z);
        return MovementHelper.sideOpen(ctx, src.x, src.y, src.z, dirX, dirZ, leanX, leanZ)
                && MovementHelper.sideOpen(ctx, dest.x, dest.y, dest.z, dirX, dirZ, leanX, leanZ);
    }

    // space, and a line for the sprint telemetry saying which of the rules above pressed it
    private MovementState jump(MovementState state, String branch, AscendHop.Verdict hop) {
        if (baritone instanceof Baritone) {
            ((Baritone) baritone).getPathingBehavior().sprintTelemetry().takeoff(branch, hop, src, dest);
        }
        return state.setInput(Input.JUMP, true);
    }

    public boolean headBonkClear() {
        BetterBlockPos startUp = src.above(2);
        for (int i = 0; i < 4; i++) {
            BetterBlockPos check = startUp.relative(Direction.from2DDataValue(i));
            if (!MovementHelper.canWalkThrough(ctx, check)) {
                // We might bonk our head
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        // if we had to place, don't allow pause
        return state.getStatus() != MovementStatus.RUNNING || ticksWithoutPlacement == 0;
    }
}
