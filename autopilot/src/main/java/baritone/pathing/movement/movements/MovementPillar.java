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
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.HitBlocker;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.utils.BlockStateInterface;
import com.google.common.collect.ImmutableSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

public class MovementPillar extends Movement {

    public MovementPillar(IBaritone baritone, BetterBlockPos start, BetterBlockPos end) {
        super(baritone, start, end, new BetterBlockPos[]{start.above(2)}, start);
    }

    @Override
    public double calculateCost(CalculationContext context) {
        return cost(context, src.x, src.y, src.z);
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        return ImmutableSet.of(src, dest);
    }

    public static double cost(CalculationContext context, int x, int y, int z) {
        BlockState fromState = context.get(x, y, z);
        Block from = fromState.getBlock();
        boolean ladder = MovementHelper.isClimbable(from);
        BlockState fromDown = context.get(x, y - 1, z);
        if (!ladder) {
            if (MovementHelper.isClimbable(fromDown.getBlock())) {
                return COST_INF; // can't pillar from a ladder or vine onto something that isn't also climbable
            }
            if (fromDown.getBlock() instanceof SlabBlock && fromDown.getValue(SlabBlock.TYPE) == SlabType.BOTTOM) {
                return COST_INF; // can't pillar up from a bottom slab onto a non ladder
            }
        }
        BlockState toBreak = context.get(x, y + 2, z);
        Block toBreakBlock = toBreak.getBlock();
        if (toBreakBlock instanceof FenceGateBlock) { // see issue #172
            return COST_INF;
        }
        BlockState srcUp = null;
        if (MovementHelper.isWater(fromState)) {
            srcUp = context.get(x, y + 1, z);
            if (swimsUp(context, x, y, z, srcUp, toBreak)) {
                return LADDER_UP_ONE_COST; // allow ascending pillars of water, but only if we're already in one
            }
        }
        double placeCost = 0;
        if (!ladder) {
            if (cantPlaceInto(context, x, y, z, fromState)) {
                return COST_INF;
            }
            // we need to place a block where we started to jump on it
            placeCost = context.costOfPlacingAt(x, y, z, fromState);
            if (placeCost >= COST_INF) {
                return COST_INF;
            }
            if (fromDown.getBlock() instanceof AirBlock) {
                placeCost += 0.1; // slightly (1/200th of a second) penalize pillaring on what's currently air
            }
        }
        if ((MovementHelper.isLiquid(fromState) && !MovementHelper.canPlaceAgainst(context, x, y - 1, z, fromDown)) || (MovementHelper.isLiquid(fromDown) && context.assumeWalkOnWater)) {
            // otherwise, if we're standing in water, we cannot pillar
            // if we're standing on water and assumeWalkOnWater is true, we cannot pillar
            // if we're standing on water and assumeWalkOnWater is false, we must have ascended to here, or sneak backplaced, so it is possible to pillar again
            return COST_INF;
        }
        if ((from == Blocks.LILY_PAD || from instanceof CarpetBlock) && !fromDown.getFluidState().isEmpty()) {
            // to ascend here we'd have to break the block we are standing on
            return COST_INF;
        }
        double hardness = MovementHelper.getMiningDurationTicks(context, x, y + 2, z, toBreak, true);
        if (hardness >= COST_INF) {
            return COST_INF;
        }
        // the block over our head still takes forever to break when we're swimming while we do it
        double waitCredit = MovementHelper.breakWaitCredit(context, hardness, JUMP_ONE_BLOCK_COST); // the jump happens during the wait
        hardness = MovementHelper.waterBreakCost(context, x, y, z, hardness);
        if (hardness != 0) {
            if (MovementHelper.isClimbable(toBreakBlock)) {
                hardness = 0; // we won't actually need to break the ladder / vine because we're going to use it
                waitCredit = 0;
            } else {
                BlockState check = context.get(x, y + 3, z); // the block on top of the one we're going to break, could it fall on us?
                if (check.getBlock() instanceof FallingBlock) {
                    // see MovementAscend's identical check for breaking a falling block above our head
                    if (srcUp == null) {
                        srcUp = context.get(x, y + 1, z);
                    }
                    if (!(toBreakBlock instanceof FallingBlock) || !(srcUp.getBlock() instanceof FallingBlock)) {
                        return COST_INF;
                    }
                }
                // this is commented because it may have had a purpose, but it's very unclear what it was. it's from the minebot era.
                //if (!MovementHelper.canWalkOn(context, chkPos, check) || MovementHelper.canWalkThrough(context, chkPos, check)) {//if the block above where we want to break is not a full block, don't do it
                // TODO why does canWalkThrough mean this action is COST_INF?
                // FallingBlock makes sense, and !canWalkOn deals with weird cases like if it were lava
                // but I don't understand why canWalkThrough makes it impossible
                //    return COST_INF;
                //}
            }
        }
        if (ladder) {
            return LADDER_UP_ONE_COST + hardness * 5;
        } else {
            return JUMP_ONE_BLOCK_COST + placeCost + context.jumpPenalty + hardness - waitCredit;
        }
    }

    // the old rule wanted water at y+2 too, so the bottom of a 2 deep pool planned a block pillar instead. that pillar
    // never fires: dest is still water, so isInWater never goes false, we never crouch and never click. floating up is
    // what actually happens, same as the 3 deep case, and we end up at the waterline with our feet in dest. y+1 has to be
    // still water we can float in (canWalkThrough says no to a current or a waterlogged slab), y+2 plain air, nothing to break
    private static boolean swimsUp(CalculationContext context, int x, int y, int z, BlockState srcUp, BlockState toBreak) {
        if (!MovementHelper.isWater(srcUp)) {
            return false;
        }
        if (MovementHelper.isWater(toBreak)) {
            return true;
        }
        return toBreak.getBlock() instanceof AirBlock && MovementHelper.canWalkThrough(context, x, y + 1, z, srcUp);
    }

    // a block pillar out of water that can't work. a current shoves us off center every tick, and the jump only goes once
    // we're centered and still, so we drift until the movement times out and the planner picks the same pillar again.
    // and a waterlogged stair or slab at our feet isn't something we place into, the runtime just punches it forever.
    // kelp and friends break in one hit (destroy time 0) so the punch clears them and that one still works
    private static boolean cantPlaceInto(CalculationContext context, int x, int y, int z, BlockState fromState) {
        if (fromState.getFluidState().isEmpty()) {
            return false;
        }
        if (MovementHelper.isFlowing(x, y, z, fromState, context.bsi)) {
            return true;
        }
        return !(fromState.getBlock() instanceof AirBlock) && !fromState.canBeReplaced() && fromState.getBlock().defaultDestroyTime() != 0;
    }

    // set on the ticks we're crouching to place a block, see swimAllowed
    private boolean placing;

    @Override
    public void reset() {
        super.reset();
        placing = false;
    }

    // the click only goes out once isCrouching(), and the swim pose beats the crouch pose (Player.getDesiredPose), so while
    // sprint holds the swim state in the water we stand there with SNEAK down and never crouch. so no swimming while we
    // place: sprint stays off (Movement.update lets go of it), and the waterline hold, which takes over the pitch we aim
    // the block with and forces jump, never runs. the swim up a water column below is not placing and swims as before
    @Override
    protected boolean swimAllowed() {
        return !placing;
    }

    @Override
    public MovementState updateState(MovementState state) {
        placing = false;
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        if (ctx.playerFeet().y < src.y) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }

        BlockState fromDown = BlockStateInterface.get(ctx, src);
        if (MovementHelper.isWater(fromDown) && MovementHelper.isWater(ctx, dest)) {
            // stay centered while swimming up a water column
            state.setTarget(new MovementState.MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(dest), ctx.playerRotations()), false));
            Vec3 destCenter = VecUtils.getBlockPosCenter(dest);
            if (Math.abs(ctx.player().position().x - destCenter.x) > 0.2 || Math.abs(ctx.player().position().z - destCenter.z) > 0.2) {
                state.setInput(Input.MOVE_FORWARD, true);
            }
            if (ctx.playerFeet().equals(dest)) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            return state;
        }
        boolean ladder = MovementHelper.isClimbable(fromDown.getBlock());

        Rotation rotation = RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
                VecUtils.getBlockPosCenter(positionToPlace),
                ctx.playerRotations());
        if (!ladder) {
            state.setTarget(new MovementState.MovementTarget(ctx.playerRotations().withPitch(rotation.getPitch()), true));
        }

        boolean blockIsThere = MovementHelper.canWalkOn(ctx, src) || ladder;
        if (ladder) {
            if (ctx.playerFeet().equals(dest)) {
                return state.setStatus(MovementStatus.SUCCESS);
            }

            MovementHelper.moveTowards(ctx, state, dest);
            state.setInput(Input.JUMP, true);
            return state;
        } else {
            // Get ready to place a throwaway block
            if (!((Baritone) baritone).getInventoryBehavior().selectThrowawayForLocation(true, src.x, src.y, src.z, true)) {
                MovementHelper.logNoThrowaway(state, baritone, src);
                return state.setStatus(MovementStatus.UNREACHABLE);
            }

            placing = true;
            // in water sneak pulls down exactly as hard as jump pushes up (goDownInWater and jumpInLiquid are 0.04 each), so
            // holding both is a bot that treads water. rise first, crouch the tick we're out of the water: the crouch lands
            // a tick after the key (see the comment below) which is about when the apex is above the new floor
            state.setInput(Input.SNEAK, !ctx.player().isInWater());
            // since (lower down) we only right click once player.isSneaking, and that happens the tick after we request to sneak

            double diffX = ctx.player().position().x - (dest.getX() + 0.5);
            double diffZ = ctx.player().position().z - (dest.getZ() + 0.5);
            double dist = Math.sqrt(diffX * diffX + diffZ * diffZ);
            double flatMotion = Math.sqrt(ctx.player().getDeltaMovement().x * ctx.player().getDeltaMovement().x + ctx.player().getDeltaMovement().z * ctx.player().getDeltaMovement().z);
            if (dist > 0.17) {//why 0.17? because it seemed like a good number, that's why
                //[explanation added after baritone port lol] also because it needs to be less than 0.2 because of the 0.3 sneak limit
                //and 0.17 is reasonably less than 0.2

                // If it's been more than forty ticks of trying to jump and we aren't done yet, go forward, maybe we are stuck
                state.setInput(Input.MOVE_FORWARD, true);

                // revise our target to both yaw and pitch if we're going to be moving forward
                state.setTarget(new MovementState.MovementTarget(rotation, true));
            } else if (flatMotion < 0.05) {
                // If our Y coordinate is above our goal, stop jumping
                state.setInput(Input.JUMP, ctx.player().position().y < dest.getY());
            }


            if (!blockIsThere) {
                BlockState frState = BlockStateInterface.get(ctx, src);
                Block fr = frState.getBlock();
                // TODO: Evaluate usage of getMaterial().isReplaceable()
                if (!(fr instanceof AirBlock || frState.canBeReplaced())) {
                    RotationUtils.reachable(ctx, src, ctx.playerController().getBlockReachDistance())
                            .map(rot -> new MovementState.MovementTarget(rot, true))
                            .ifPresent(state::setTarget);
                    state.setInput(Input.JUMP, false); // breaking is like 5x slower when you're jumping
                    state.setInput(Input.CLICK_LEFT, true);
                    blockIsThere = false;
                } else {
                    boolean apex = ctx.player().isCrouching() && (ctx.isLookingAt(src.below()) || ctx.isLookingAt(src)) && ctx.player().position().y > dest.getY() + 0.1;
                    // a mob standing in the cell eats the click, so no click until it moves. a bat in a 1x1 shaft never
                    // moves, so critters get punched, and that is asked every tick: only being allowed to swing at the
                    // top of each jump would take forever. anything else is still only counted at the top of the jump
                    // like before, gets PlaceWait's patience, and then the planner gets to find a way around
                    MovementHelper.Obstruction in = MovementHelper.obstruction(ctx, src, true);
                    boolean critters = in.cell() == HitBlocker.Cell.HIT || in.cell() == HitBlocker.Cell.CLEARING;
                    if (apex || critters) {
                        switch (state.placeInto(in.cell())) {
                            case PLACE:
                                state.setInput(Input.CLICK_RIGHT, true);
                                break;
                            case HIT:
                                MovementHelper.swingAtBlocker(state, ctx, in.target());
                                break;
                            case WAIT:
                                break;
                            default:
                                logDebug("something stood in the way of the pillar block at " + src.x + " " + src.y + " " + src.z + ", giving up");
                                return state.setStatus(MovementStatus.UNREACHABLE);
                        }
                    }
                }
            }
        }

        // If we are at our goal and the block below us is placed
        if (ctx.playerFeet().equals(dest) && blockIsThere) {
            return state.setStatus(MovementStatus.SUCCESS);
        }

        return state;
    }

    @Override
    protected boolean prepared(MovementState state) {
        if (ctx.playerFeet().equals(src) || ctx.playerFeet().equals(src.below())) {
            Block block = BlockStateInterface.getBlock(ctx, src.below());
            if (MovementHelper.isClimbable(block)) {
                state.setInput(Input.SNEAK, true);
            }
        }
        if (MovementHelper.isWater(ctx, dest.above())) {
            return true;
        }
        return super.prepared(state);
    }
}
