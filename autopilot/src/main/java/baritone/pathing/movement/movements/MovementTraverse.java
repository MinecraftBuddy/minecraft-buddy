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
import baritone.altoclef.AltoClefSettings;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.BridgeStallLog;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.CliffAvoidance;
import baritone.pathing.movement.GodBridge;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.utils.BlockStateInterface;
import baritone.utils.EdgeSettle;
import baritone.utils.ExperimentalMovement;
import com.google.common.collect.ImmutableSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.Set;

public class MovementTraverse extends Movement {

    /**
     * Did we have to place a bridge block or was it always there
     */
    private boolean wasTheBridgeBlockAlwaysThere = true;

    // god bridging (see GodBridge): undecided until the first tick that needs a block, then riding it or done with it for
    // this movement. a clicked block that never showed up is a miss, godClicked remembers we just clicked
    private enum God { UNDECIDED, RIDING, DONE }

    private God god = God.UNDECIDED;
    private boolean godClicked;
    // a movement's src and dest never change, so the lava scan is once per movement and not once per tick
    private Boolean lavaShore;
    // what the bridge placer said this tick. nobody acts on it, the falltrace prints it so a slide off a bridge says whether
    // the block was coming or refused
    private PlaceResult lastPlace;
    // the bridge stall bail (bridgeStall): a second of hanging still over a real drop with no block appearing. the bot
    // sits on the clamp with the timeout counting down, there's nothing to wait for
    static final int STALL_TICKS = 20;
    // less than this from where the stall started is standing still, the clamp lets a hundredth or two of creep through
    static final double STALL_MOVE = 0.03;
    private int stallTicks;
    private Vec3 stallAt;
    private boolean bailedOverDrop;

    public MovementTraverse(IBaritone baritone, BetterBlockPos from, BetterBlockPos to) {
        super(baritone, from, to, new BetterBlockPos[]{to.above(), to}, to.below());
    }

    @Override
    public void reset() {
        super.reset();
        wasTheBridgeBlockAlwaysThere = true;
        god = God.UNDECIDED;
        godClicked = false;
        lavaShore = null;
        lastPlace = null;
        stallTicks = 0;
        stallAt = null;
        bailedOverDrop = false;
    }

    public PlaceResult lastPlace() {
        return lastPlace;
    }

    @Override
    public double calculateCost(CalculationContext context) {
        return cost(context, src.x, src.y, src.z, dest.x, dest.z);
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        return ImmutableSet.of(src, dest);
    }

    public static double cost(CalculationContext context, int x, int y, int z, int destX, int destZ) {
        BlockState pb0 = context.get(destX, y + 1, destZ);
        BlockState pb1 = context.get(destX, y, destZ);
        BlockState destOn = context.get(destX, y - 1, destZ);
        BlockState srcDown = context.get(x, y - 1, z);
        Block srcDownBlock = srcDown.getBlock();
        boolean standingOnABlock = MovementHelper.mustBeSolidToWalkOn(context, x, y - 1, z, srcDown);
        boolean frostWalker = standingOnABlock && !context.assumeWalkOnWater && MovementHelper.canUseFrostWalker(context, destOn);
        if (frostWalker || MovementHelper.canWalkOn(context, destX, y - 1, destZ, destOn)) { //this is a walk, not a bridge
            double WC = WALK_ONE_BLOCK_COST;
            boolean water = false;
            boolean sneaking = false;
            if (context.swimsLava(pb1, pb0) || MovementHelper.isLava(context.get(x, y, z))) {
                // isWater says yes to lava while swim through lava is on, the price must not. leaving the lake is still
                // half a step in it, same as the diagonal, or straight exits look free next to diagonal ones
                WC = context.lavaSwimCost;
                water = true;
            } else if (MovementHelper.isWater(pb0) || MovementHelper.isWater(pb1)) {
                WC = context.waterCost(destX, y, destZ);
                water = true;
            } else {
                if (destOn.getBlock() == Blocks.SOUL_SAND) {
                    WC += (context.soulSandWalkCost - WALK_ONE_BLOCK_COST) / 2;
                } else if (frostWalker) {
                    // with frostwalker we can walk on water without the penalty, if we are sure we won't be using jesus
                } else if (destOn.getBlock() == Blocks.WATER) {
                    WC += context.walkOnWaterOnePenalty;
                }
                if (srcDownBlock == Blocks.SOUL_SAND) {
                    WC += (context.soulSandWalkCost - WALK_ONE_BLOCK_COST) / 2;
                } else if (context.allowWalkOnMagmaBlocks && srcDownBlock.equals(Blocks.MAGMA_BLOCK)) {
                    sneaking = true;
                    WC += (SNEAK_ONE_BLOCK_COST - WALK_ONE_BLOCK_COST) / 2;
                }
            }
            double hardness1 = MovementHelper.getMiningDurationTicks(context, destX, y, destZ, pb1, false);
            if (hardness1 >= COST_INF) {
                return COST_INF;
            }
            double hardness2 = MovementHelper.getMiningDurationTicks(context, destX, y + 1, destZ, pb0, true); // only include falling on the upper block to break
            // the last break's wait is spent walking, so it is not paid for twice
            boolean walkCoversTheWait = (hardness1 <= 0 || MovementHelper.isMultiTickBreak(context, hardness1))
                    && (hardness2 <= 0 || MovementHelper.isMultiTickBreak(context, hardness2));
            // we break from src, and swinging a pickaxe underwater is a bad time
            hardness1 = MovementHelper.waterBreakCost(context, x, y, z, hardness1);
            hardness2 = MovementHelper.waterBreakCost(context, x, y, z, hardness2);
            if (hardness1 == 0 && hardness2 == 0) {
                if (!water && !sneaking && context.canSprint) {
                    // If there's nothing in the way, and this isn't water, and we aren't sneak placing
                    // We can sprint =D
                    // Don't check for soul sand, since we can sprint on that too
                    WC *= SPRINT_MULTIPLIER;
                }
                return WC + context.boatHandoff(x, y, z, destX, y, destZ);
            }
            if (MovementHelper.isClimbable(srcDownBlock)) {
                hardness1 *= 5;
                hardness2 *= 5;
            }
            // two blocks of stone with an iron pick: 2 * (8 swings + the click + 5 idle) = 28 before the penalty, and the real
            // thing is 27.6 because the walk fits inside the last idle. a tunnel pays the same since the idle carries over
            return WC + context.boatHandoff(x, y, z, destX, y, destZ) + hardness1 + hardness2 - (walkCoversTheWait ? Math.min(WC, context.breakIdleTicks) : 0);
        } else {//this is a bridge, so we need to place a block
            if (MovementHelper.isClimbable(srcDownBlock)) {
                return COST_INF;
            }
            if (MovementHelper.isReplaceable(destX, y - 1, destZ, destOn, context.bsi)) {
                boolean throughWater = MovementHelper.isWater(pb0) || MovementHelper.isWater(pb1);
                if (MovementHelper.isWater(destOn) && throughWater) {
                    // this happens when assume walk on water is true and this is a traverse in water, which isn't allowed
                    return COST_INF;
                }
                double placeCost = context.costOfPlacingAt(destX, y - 1, destZ, destOn);
                if (placeCost >= COST_INF) {
                    return COST_INF;
                }
                double hardness1 = MovementHelper.getMiningDurationTicks(context, destX, y, destZ, pb1, false);
                if (hardness1 >= COST_INF) {
                    return COST_INF;
                }
                double hardness2 = MovementHelper.getMiningDurationTicks(context, destX, y + 1, destZ, pb0, true); // only include falling on the upper block to break
                hardness1 = MovementHelper.waterBreakCost(context, x, y, z, hardness1);
                hardness2 = MovementHelper.waterBreakCost(context, x, y, z, hardness2);
                double WC = context.swimsLava(pb1, pb0) ? context.lavaSwimCost : throughWater ? context.waterWalkSpeed : WALK_ONE_BLOCK_COST;
                for (int i = 0; i < 5; i++) {
                    int againstX = destX + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getStepX();
                    int againstY = y - 1 + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getStepY();
                    int againstZ = destZ + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i].getStepZ();
                    if (againstX == x && againstZ == z) { // this would be a backplace
                        continue;
                    }
                    if (MovementHelper.canPlaceAgainst(context, againstX, againstY, againstZ)) { // found a side place option
                        return WC + placeCost + hardness1 + hardness2;
                    }
                }
                // now that we've checked all possible directions to side place, we actually need to backplace
                if (srcDownBlock == Blocks.SOUL_SAND || (srcDownBlock instanceof SlabBlock && srcDown.getValue(SlabBlock.TYPE) != SlabType.DOUBLE)) {
                    return COST_INF; // can't sneak and backplace against soul sand or half slabs (regardless of whether it's top half or bottom half) =/
                }
                if (!standingOnABlock) { // standing on water / swimming
                    return COST_INF; // this is obviously impossible
                }
                Block blockSrc = context.getBlock(x, y, z);
                if ((blockSrc == Blocks.LILY_PAD || blockSrc instanceof CarpetBlock) && !srcDown.getFluidState().isEmpty()) {
                    return COST_INF; // we can stand on these but can't place against them
                }
                if (context.allowGodBridge && !throughWater && godStance(context.get(x, y, z))
                        && godFooting(srcDown, context.allowWalkOnMagmaBlocks)
                        // air under src is the block the traverse before this one places, it'll be there by the time we are
                        // (a stone stand-in so the border and place protection still get asked)
                        && MovementHelper.canPlaceAgainst(context, x, y - 1, z, srcDown.getBlock() instanceof AirBlock ? Blocks.STONE.defaultBlockState() : srcDown)) {
                    return WC + placeCost + hardness1 + hardness2; // walking backwards across, no sneak (see GodBridge)
                }
                WC = WC * (SNEAK_ONE_BLOCK_COST / WALK_ONE_BLOCK_COST);//since we are sneak backplacing, we are sneaking lol
                return WC + placeCost + hardness1 + hardness2;
            }
            return COST_INF;
        }
    }

    // feet below the cell we're traversing to, the ledge or the next surface cell after we sank. forward into a wall is what
    // triggers the hop
    // sprinting only ever looked straight ahead, so a hit or a bit of drift beside lava went in sideways. walk those
    public boolean onLavaShore() {
        if (lavaShore == null) {
            lavaShore = ctx.world().dimension() == Level.NETHER
                    && CliffAvoidance.traverseByLava(CliffAvoidance.of(new BlockStateInterface(ctx)), src.x, src.y, src.z, dest.x, dest.z);
        }
        return lavaShore;
    }

    static boolean pushesForwardWhileRising(boolean inLava, int feetY, int destY) {
        return inLava && feetY < destY;
    }

    // the other way round: bobbing on top of the lava a cell over the dest. idling there is a plan to burn, nothing else
    // moves a body floating in lava, so keep leaning at the dest and let the lava carry us down onto it
    static boolean pushesForwardWhileAbove(boolean inLava, int feetY, int destY) {
        return inLava && feetY > destY;
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        // per tick: a tick that never got to the placer says so, instead of repeating the last answer
        lastPlace = null;
        BlockState pb0 = BlockStateInterface.get(ctx, positionsToBreak[0]);
        BlockState pb1 = BlockStateInterface.get(ctx, positionsToBreak[1]);
        if (state.getStatus() != MovementStatus.RUNNING) {
            // if the setting is enabled
            if (!Baritone.settings().walkWhileBreaking.value) {
                return state;
            }
            // and if we're prepping (aka mining the block in front)
            if (state.getStatus() != MovementStatus.PREPPING) {
                return state;
            }
            // and nothing is about to land in them. the column drops into the cell we are strolling toward
            if (waitingOnFall) {
                return state;
            }
            // and if it's fine to walk into the blocks in front
            if (MovementHelper.avoidWalkingInto(pb0)) {
                return state;
            }
            if (MovementHelper.avoidWalkingInto(pb1)) {
                return state;
            }
            if (AltoClefSettings.getInstance().shouldAvoidWalkThroughForce(positionsToBreak[0]) || AltoClefSettings.getInstance().shouldAvoidWalkThroughForce(positionsToBreak[1])) {
                return state; // altoclef says don't walk there, so no walking while it's still being dug out
            }
            // and we aren't already pressed up against the block
            double dist = Math.max(Math.abs(ctx.player().position().x - (dest.getX() + 0.5D)), Math.abs(ctx.player().position().z - (dest.getZ() + 0.5D)));
            if (dist < 0.83) {
                return state;
            }
            if (!state.getTarget().getRotation().isPresent()) {
                // this can happen rarely when the server lags and doesn't send the falling sand entity until you've already walked through the block and are now mining the next one
                return state;
            }

            // combine the yaw to the center of the destination, and the pitch to the specific block we're trying to break
            // it's safe to do this since the two blocks we break (in a traverse) are right on top of each other and so will have the same yaw
            float yawToDest = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.calculateBlockCenter(ctx.world(), dest), ctx.playerRotations()).getYaw();
            float pitchToBreak = state.getTarget().getRotation().get().getPitch();
            if ((MovementHelper.isBlockNormalCube(pb0) || pb0.getBlock() instanceof AirBlock && (MovementHelper.isBlockNormalCube(pb1) || pb1.getBlock() instanceof AirBlock))) {
                // in the meantime, before we're right up against the block, we can break efficiently at this angle
                pitchToBreak = 26;
            }

            return state.setTarget(new MovementState.MovementTarget(new Rotation(yawToDest, pitchToBreak), true))
                    .setInput(Input.MOVE_FORWARD, true)
                    .setInput(Input.SPRINT, !onLavaShore());
        }

        Block fd = BlockStateInterface.get(ctx, src.below()).getBlock();
        boolean ladder = MovementHelper.isClimbable(fd);

        //sneak may have been set to true in the PREPPING state while mining an adjacent block, but we still want it to be true if the player is about to go on magma
        state.setInput(Input.SNEAK, Baritone.settings().allowWalkOnMagmaBlocks.value && MovementHelper.steppingOnBlocks(ctx).stream().anyMatch(block -> ctx.world().getBlockState(block).is(Blocks.MAGMA_BLOCK)));

        if (!MovementHelper.openDoors(ctx, state, src, dest)) {
            return state;
        }

        if (pb0.getBlock() instanceof FenceGateBlock || pb1.getBlock() instanceof FenceGateBlock) {
            BlockPos blocked = !MovementHelper.isGatePassable(ctx, positionsToBreak[0], src.above()) ? positionsToBreak[0]
                    : !MovementHelper.isGatePassable(ctx, positionsToBreak[1], src) ? positionsToBreak[1]
                    : null;
            if (blocked != null) {
                Optional<Rotation> rotation = RotationUtils.reachable(ctx, blocked);
                if (rotation.isPresent()) {
                    return state.setTarget(new MovementState.MovementTarget(rotation.get(), true)).setInput(Input.CLICK_RIGHT, true);
                }
            }
        }

        boolean isTheBridgeBlockThere = MovementHelper.canWalkOn(ctx, positionToPlace) || ladder || MovementHelper.canUseFrostWalker(ctx, positionToPlace);
        BlockPos feet = ctx.playerFeet();
        if (feet.getY() != dest.getY() && !ladder) {
            logDebug("Wrong Y coordinate");
            // a jump-held bob pops us clear of the lava for a few ticks, isInLava goes false and we used to idle up there
            // until we sank again. lava in our cell or right under it is still the lava case
            boolean inLava = ctx.player().isInLava() || (!ctx.player().onGround()
                    && (MovementHelper.isLava(BlockStateInterface.get(ctx, feet)) || MovementHelper.isLava(BlockStateInterface.get(ctx, feet.below()))));
            if (feet.getY() < dest.getY()) {
                if (pushesForwardWhileRising(inLava, feet.getY(), dest.getY())) {
                    // jump alone just bobs in place at the surface. vanilla hops 0.3 up out of a fluid when we're pressed
                    // against something we could stand on top of, so lean on the wall (water has Movement.update's bob)
                    MovementHelper.moveTowards(ctx, state, dest);
                }
                return state.setInput(Input.JUMP, true);
            }
            if (!ctx.player().onGround() && pushesForwardWhileAbove(inLava, feet.getY(), dest.getY())) {
                MovementHelper.moveTowards(ctx, state, dest);
                return state;
            }
            if (ctx.player().onGround()) {
                // standing a block above the whole move, usually a path that started from the block under a lip we were
                // perched on. no input here walks us down to it, so idling just burned the movement timeout. fail now,
                // the replan starts from where we actually are
                return state.setStatus(MovementStatus.FAILED);
            }
            return state;
        }

        if (isTheBridgeBlockThere) {
            // a block showed up, that is progress
            stallTicks = 0;
            stallAt = null;
            if (feet.equals(dest)) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            if (Baritone.settings().overshootTraverse.value && (feet.equals(dest.offset(getDirection())) || feet.equals(dest.offset(getDirection()).offset(getDirection())))) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            Block low = BlockStateInterface.get(ctx, src).getBlock();
            Block high = BlockStateInterface.get(ctx, src.above()).getBlock();
            if (ctx.player().position().y > src.y + 0.1D && !ctx.player().onGround() && (MovementHelper.isClimbable(low) || MovementHelper.isClimbable(high))) {
                // hitting W could cause us to climb the ladder instead of going forward
                // wait until we're on the ground
                return state;
            }
            BlockPos into = dest.subtract(src).offset(dest);
            BlockState intoBelow = BlockStateInterface.get(ctx, into);
            BlockState intoAbove = BlockStateInterface.get(ctx, into.above());
            if (wasTheBridgeBlockAlwaysThere && (!MovementHelper.isLiquid(ctx, feet) || Baritone.settings().sprintInWater.value) && (!MovementHelper.avoidWalkingInto(intoBelow) || MovementHelper.isWater(intoBelow)) && !MovementHelper.avoidWalkingInto(intoAbove) && !onLavaShore()) {
                state.setInput(Input.SPRINT, true);
            }

            BlockState destDown = BlockStateInterface.get(ctx, dest.below());
            if (feet.getY() != dest.getY() && ladder && MovementHelper.isClimbable(destDown.getBlock())) {
                state.setInput(Input.JUMP, true);
            }
            MovementHelper.moveTowards(ctx, state, positionsToBreak[0]);
            return state;
        } else {
            return bridgeStall(bridge(state, feet), feet);
        }
    }

    // the block under dest isn't there: place it, sneaking out over the gap if we have to
    private MovementState bridge(MovementState state, BlockPos feet) {
        wasTheBridgeBlockAlwaysThere = false;
        if (godBridge(state)) {
            return state;
        }
        Block standingOn = BlockStateInterface.get(ctx, feet.below()).getBlock();
        if (standingOn.equals(Blocks.SOUL_SAND) || standingOn instanceof SlabBlock) { // see issue #118
            double dist = Math.max(Math.abs(dest.getX() + 0.5 - ctx.player().position().x), Math.abs(dest.getZ() + 0.5 - ctx.player().position().z));
            if (dist < 0.85) { // 0.5 + 0.3 + epsilon
                MovementHelper.moveTowards(ctx, state, dest);
                return state.setInput(Input.MOVE_FORWARD, false)
                        .setInput(Input.MOVE_BACK, true);
            }
        }
        double dist1 = Math.max(Math.abs(ctx.player().position().x - (dest.getX() + 0.5D)), Math.abs(ctx.player().position().z - (dest.getZ() + 0.5D)));
        PlaceResult p = MovementHelper.attemptToPlaceABlock(state, baritone, dest.below(), false, !Baritone.settings().assumeSafeWalk.value);
        lastPlace = p;
        if ((p == PlaceResult.READY_TO_PLACE || dist1 < 0.6) && !Baritone.settings().assumeSafeWalk.value) {
            state.setInput(Input.SNEAK, true);
        }
        switch (p) {
            case READY_TO_PLACE: {
                if (ctx.player().isCrouching() || Baritone.settings().assumeSafeWalk.value) {
                    state.setInput(Input.CLICK_RIGHT, true);
                }
                return state;
            }
            case ATTEMPTING: {
                if (dist1 > 0.83) {
                    // might need to go forward a bit
                    float yaw = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(dest), ctx.playerRotations()).getYaw();
                    if (Math.abs(state.getTarget().rotation.getYaw() - yaw) < 0.1) {
                        // but only if our attempted place is straight ahead
                        return state.setInput(Input.MOVE_FORWARD, true);
                    }
                } else if (ctx.playerRotations().isReallyCloseTo(state.getTarget().rotation)) {
                    // well i guess theres something in the way
                    return state.setInput(Input.CLICK_LEFT, true);
                }
                return state;
            }
            case BLOCKED: {
                // something is standing in the cell: no click, no walking forward into the gap. it moves or we give up
                // on this movement (that's PlaceWait, 20 ticks)
                return state;
            }
            default:
                break;
        }
        if (state.getStatus() == MovementStatus.UNREACHABLE) {
            // findPlace already said there's nothing we may put here. the click below would go out with whatever is in hand
            return state;
        }
        if (feet.equals(dest)) {
            // If we are in the block that we are trying to get to, we are sneaking over air and we need to place a block beneath us against the one we just walked off of
            // Out.log(from + " " + to + " " + faceX + "," + faceY + "," + faceZ + " " + whereAmI);
            double faceX = (dest.getX() + src.getX() + 1.0D) * 0.5D;
            double faceY = (dest.getY() + src.getY() - 1.0D) * 0.5D;
            double faceZ = (dest.getZ() + src.getZ() + 1.0D) * 0.5D;
            // faceX, faceY, faceZ is the middle of the face between from and to
            BlockPos goalLook = src.below(); // this is the block we were just standing on, and the one we want to place against

            Rotation backToFace = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), new Vec3(faceX, faceY, faceZ), ctx.playerRotations());
            float pitch = backToFace.getPitch();
            double dist2 = Math.max(Math.abs(ctx.player().position().x - faceX), Math.abs(ctx.player().position().z - faceZ));
            if (dist2 < 0.29) { // see issue #208
                float yaw = RotationUtils.calcRotationFromVec3d(VecUtils.getBlockPosCenter(dest), ctx.playerHead(), ctx.playerRotations()).getYaw();
                state.setTarget(new MovementState.MovementTarget(new Rotation(yaw, pitch), true));
                state.setInput(Input.MOVE_BACK, true);
            } else {
                state.setTarget(new MovementState.MovementTarget(backToFace, true));
            }
            if (ctx.isLookingAt(goalLook)) {
                // findPlace came back empty handed, so nothing picked the block for this cell. pick it now, same as its
                // ready path does right before the click (a creative hand over lands in the slot this tick too). on a
                // builder path a block that has to be aimed is a no, the builder places those itself
                BlockPos placeAt = dest.below();
                if (!((Baritone) baritone).getInventoryBehavior().selectThrowawayForLocation(true, placeAt.getX(), placeAt.getY(), placeAt.getZ(), true)) {
                    MovementHelper.logNoThrowaway(state, baritone, placeAt);
                    return state.setStatus(MovementStatus.UNREACHABLE);
                }
                return state.setInput(Input.CLICK_RIGHT, true); // wait to right click until we are able to place
            }
            // Out.log("Trying to look at " + goalLook + ", actually looking at" + Baritone.whatAreYouLookingAt());
            if (ctx.playerRotations().isReallyCloseTo(state.getTarget().rotation)) {
                state.setInput(Input.CLICK_LEFT, true);
            }
            return state;
        }
        MovementHelper.moveTowardsWithSlightRotation(ctx, state, dest);
        return state;
    }

    // a sneak bridge out over a real drop that sits still while no block shows up isn't going to place. waiting out the
    // movement timeout ends in the executor's cancel letting go of sneak while we hang there, so give up here instead,
    // still on sneak, and EdgeSettle walks us back onto src. counted after the whole place decision, whatever it said
    // (ready, attempting and no option take turns while the aim wobbles), only standing still and nothing placed count
    private MovementState bridgeStall(MovementState state, BlockPos feet) {
        Vec3 at = ctx.player().position();
        boolean overDrop = state.getStatus() == MovementStatus.RUNNING && feet.equals(dest)
                && EdgeSettle.hangingOver(ctx.world(), dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5);
        boolean moved = stallAt == null || at.distanceToSqr(stallAt) > STALL_MOVE * STALL_MOVE;
        stallTicks = stallTicks(stallTicks, overDrop, moved);
        if (stallTicks == 0) {
            stallAt = at;
        }
        if (!bridgeGivesUp(stallTicks)) {
            return state;
        }
        BridgeStallLog.log(baritone, src, dest, state, lastPlace, stallTicks, !Baritone.settings().assumeSafeWalk.value);
        bailedOverDrop = true;
        // the executor remembers this edge (rememberFailure, not timed out). a flat traverse isn't a climb edge, so
        // nothing forgives it and the replan prices this bridge up instead of picking it again straight away
        return state.setStatus(MovementStatus.UNREACHABLE);
    }

    // ticks in a row over the drop without moving. moving or not being over it starts over
    static int stallTicks(int before, boolean overDrop, boolean moved) {
        return !overDrop || moved ? 0 : before + 1;
    }

    static boolean bridgeGivesUp(int stallTicks) {
        return stallTicks >= STALL_TICKS;
    }

    // set the tick the stall bail fires. PathingBehavior asks so a planned next path doesn't grab the wheel that same
    // tick, its keys would count as steering and EdgeSettle would never get to walk us back
    public boolean bailedOverDrop() {
        return bailedOverDrop;
    }

    // plain footing for the god bridge: the speed model leans on 0.6 friction and no speed factor, and magma wants the
    // sneak held (see the top of updateState). the cost and the executor both ask this so they agree
    public static boolean godFooting(BlockState srcDown, boolean allowMagma) {
        Block block = srcDown.getBlock();
        return block.getFriction() == 0.6F && block.getSpeedFactor() == 1.0F && !(allowMagma && block == Blocks.MAGMA_BLOCK)
                && !MovementHelper.opensWhenClicked(srcDown);
    }

    // the planner's half of the executor's isInWater / onClimbable check, close but not all of it (an open trapdoor over a
    // ladder, a body in water with dry feet): standing in a ladder or in water is not a stance the backwards walk works from,
    // and the cost should say so or it prices a god bridge the executor turns down
    static boolean godStance(BlockState feet) {
        Block block = feet.getBlock();
        return feet.getFluidState().isEmpty()
                && !MovementHelper.isClimbable(block) && block != Blocks.SCAFFOLDING && block != Blocks.CAVE_VINES && block != Blocks.CAVE_VINES_PLANT;
    }

    // is this the back place the sneaking code would have done: nothing but src.below() to place against. same loop as cost
    private boolean noSidePlace() {
        BlockStateInterface bsi = ((Baritone) baritone).bsi;
        BlockPos below = dest.below();
        for (Direction direction : HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP) {
            BlockPos against = below.relative(direction);
            if (against.getX() == src.x && against.getZ() == src.z) {
                continue;
            }
            if (MovementHelper.canPlaceAgainst(bsi, against.getX(), against.getY(), against.getZ())) {
                return false;
            }
        }
        return true;
    }

    // decided once per movement, on the first tick that needs a block. the cost says walk speed for exactly these
    private boolean godEligible() {
        if (!ExperimentalMovement.allowGodBridge()) {
            return false; // the flag off path asks nothing else
        }
        BlockState srcDown = BlockStateInterface.get(ctx, src.below());
        if (!godFooting(srcDown, Baritone.settings().allowWalkOnMagmaBlocks.value)
                || !MovementHelper.canPlaceAgainst(((Baritone) baritone).bsi, src.x, src.y - 1, src.z)
                || !noSidePlace()) {
            return false;
        }
        Vec3 pos = ctx.player().position();
        Vec3 vel = ctx.player().getDeltaMovement();
        int dx = dest.x - src.x;
        int dz = dest.z - src.z;
        // the geometry is for a standing player on the ground with the usual box. no water, ladders or riding. a crouch
        // left over from a sneaking block is fine, it's gone a tick after the sneak is, long before the next face is in reach
        if (!ctx.player().onGround() || ctx.player().isPassenger() || ctx.player().onClimbable() || ctx.player().isInWater()
                || MovementHelper.isLiquid(ctx, ctx.playerFeet()) || ctx.player().getBbWidth() > 0.6F
                || Math.abs(ctx.player().getEyeHeight(Pose.STANDING) - GodBridge.EYE) > 0.01
                || AltoClefSettings.getInstance().isInteractionPaused()) {
            return false;
        }
        return Math.abs(GodBridge.lateral(pos.x, pos.z, src.x, src.z, dx, dz)) <= GodBridge.ENTRY_LATERAL
                && Math.abs(GodBridge.lateralSpeed(vel.x, vel.z, dx, dz)) <= GodBridge.ENTRY_LATERAL_SPEED;
    }

    private void godAbandon(MovementState state) {
        god = God.DONE;
        state.setInput(Input.SNEAK, true); // the sneaking back place picks it up from here, this very tick
    }

    // walk backwards over the gap and place against the far face of src.below() when it can be hit, see GodBridge. returns
    // false to let the sneaking back place have the tick. once it has had one, it keeps the rest of this movement
    private boolean godBridge(MovementState state) {
        if (god == God.UNDECIDED) {
            god = godEligible() ? God.RIDING : God.DONE;
        }
        if (god != God.RIDING) {
            return false;
        }
        if (godClicked) {
            godAbandon(state); // we clicked last tick and still no block. the click didn't take, so don't try it again
            return false;
        }
        Vec3 pos = ctx.player().position();
        Vec3 vel = ctx.player().getDeltaMovement();
        int dx = dest.x - src.x;
        int dz = dest.z - src.z;
        if (!ctx.player().onGround() || ctx.player().isInWater() || Math.abs(GodBridge.lateral(pos.x, pos.z, src.x, src.z, dx, dz)) > GodBridge.BAIL_LATERAL
                || MovementHelper.entityInTheWay(ctx, positionToPlace) || AltoClefSettings.getInstance().isInteractionPaused()
                || !((Baritone) baritone).getInventoryBehavior().selectThrowawayForLocation(true, positionToPlace.x, positionToPlace.y, positionToPlace.z, true)) {
            godAbandon(state);
            return false;
        }
        // sprint is only off after the executor gets its turn, and the speed attribute still has the bonus until then
        ctx.player().setSprinting(false);
        double accel = GodBridge.accel(ctx.player().getAttributeValue(Attributes.MOVEMENT_SPEED));
        double e = GodBridge.pastEdge(pos.x, pos.z, src.x, src.z, dx, dz);
        HitResult hit = ctx.objectMouseOver();
        boolean onFace = hit != null && hit.getType() == HitResult.Type.BLOCK && ((BlockHitResult) hit).getBlockPos().equals(src.below())
                && ((BlockHitResult) hit).getDirection() == Direction.getNearest(dx, 0, dz, Direction.UP);
        // a swap from the main inventory isn't done the tick we ask for it, and a click with the wrong thing in hand is a
        // click that places nothing with no sneak to catch us. so not ready until the block is really in hand
        boolean ready = ((Baritone) baritone).getInputOverrideHandler().getBlockPlaceHelper().ready()
                && ctx.player().getMainHandItem().getItem() instanceof BlockItem;
        GodBridge.Plan plan = GodBridge.decide(e, GodBridge.along(vel.x, vel.z, dx, dz), accel, onFace, ready);
        if (plan.abandon) {
            godAbandon(state);
            return false;
        }
        state.setTarget(new MovementState.MovementTarget(new Rotation(GodBridge.facingYaw(dx, dz), (float) plan.pitch), true));
        state.setInput(Input.MOVE_BACK, true);
        if (plan.sneak) {
            state.setInput(Input.SNEAK, true);
        }
        if (plan.click) {
            state.setInput(Input.CLICK_RIGHT, true);
            godClicked = true;
        }
        return true;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        // if we're in the process of breaking blocks before walking forwards
        // or if this isn't a sneak place (the block is already there)
        // then it's safe to cancel this
        return state.getStatus() != MovementStatus.RUNNING || MovementHelper.canWalkOn(ctx, dest.below());
    }

    @Override
    protected boolean prepared(MovementState state) {
        if (ctx.playerFeet().equals(src) || ctx.playerFeet().equals(src.below())) {
            Block block = BlockStateInterface.getBlock(ctx, src.below());
            if (MovementHelper.isClimbable(block)) {
                state.setInput(Input.SNEAK, true);
            }
        }
        return super.prepared(state);
    }
}
