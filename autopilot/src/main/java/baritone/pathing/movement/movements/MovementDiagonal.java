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
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.CliffAvoidance;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.MutableMoveResult;
import com.google.common.collect.ImmutableSet;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MovementDiagonal extends Movement {

    private static final double SQRT_2 = Math.sqrt(2);

    // edging around a corner needs steering fixes and bleeds momentum, an open diagonal doesn't, so only the edging ones pay
    private static final double CORNER_EDGING_PENALTY = 1.2;

    private Boolean lavaShore;

    @Override
    public void reset() {
        super.reset();
        lavaShore = null;
    }

    public MovementDiagonal(IBaritone baritone, BetterBlockPos start, Direction dir1, Direction dir2, int dy) {
        this(baritone, start, start.relative(dir1), start.relative(dir2), dir2, dy);
        // super(start, start.offset(dir1).offset(dir2), new BlockPos[]{start.offset(dir1), start.offset(dir1).up(), start.offset(dir2), start.offset(dir2).up(), start.offset(dir1).offset(dir2), start.offset(dir1).offset(dir2).up()}, new BlockPos[]{start.offset(dir1).offset(dir2).down()});
    }

    private MovementDiagonal(IBaritone baritone, BetterBlockPos start, BetterBlockPos dir1, BetterBlockPos dir2, Direction drr2, int dy) {
        this(baritone, start, dir1.relative(drr2).above(dy), dir1, dir2);
    }

    private MovementDiagonal(IBaritone baritone, BetterBlockPos start, BetterBlockPos end, BetterBlockPos dir1, BetterBlockPos dir2) {
        super(baritone, start, end, new BetterBlockPos[]{dir1, dir1.above(), dir2, dir2.above(), end, end.above()});
    }

    @Override
    protected boolean safeToCancel(MovementState state) {
        //too simple. backfill does not work after cornering with this
        //return context.precomputedData.canWalkOn(ctx, ctx.playerFeet().down());
        LocalPlayer player = ctx.player();
        double offset = 0.25;
        double x = player.position().x;
        double y = player.position().y - 1;
        double z = player.position().z;
        //standard
        if (ctx.playerFeet().equals(src)) {
            return true;
        }
        //both corners are walkable
        if (MovementHelper.canWalkOn(ctx, new BlockPos(src.x, src.y - 1, dest.z))
                && MovementHelper.canWalkOn(ctx, new BlockPos(dest.x, src.y - 1, src.z))) {
            return true;
        }
        //we are in a likely unwalkable corner, check for a supporting block
        if (ctx.playerFeet().equals(new BetterBlockPos(src.x, src.y, dest.z))
                || ctx.playerFeet().equals(new BetterBlockPos(dest.x, src.y, src.z))) {
            return (MovementHelper.canWalkOn(ctx, new BetterBlockPos(x + offset, y, z + offset))
                    || MovementHelper.canWalkOn(ctx, new BetterBlockPos(x + offset, y, z - offset))
                    || MovementHelper.canWalkOn(ctx, new BetterBlockPos(x - offset, y, z + offset))
                    || MovementHelper.canWalkOn(ctx, new BetterBlockPos(x - offset, y, z - offset)));
        }
        return true;
    }

    @Override
    public double calculateCost(CalculationContext context) {
        MutableMoveResult result = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, dest.x, dest.z, result);
        if (result.y != dest.y) {
            return COST_INF; // doesn't apply to us, this position is incorrect
        }
        return result.cost;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        BetterBlockPos diagA = new BetterBlockPos(src.x, src.y, dest.z);
        BetterBlockPos diagB = new BetterBlockPos(dest.x, src.y, src.z);
        if (dest.y < src.y) {
            return ImmutableSet.of(src, dest.above(), diagA, diagB, dest, diagA.below(), diagB.below());
        }
        if (dest.y > src.y) {
            return ImmutableSet.of(src, src.above(), diagA, diagB, dest, diagA.above(), diagB.above());
        }
        return ImmutableSet.of(src, dest, diagA, diagB);
    }

    public static void cost(CalculationContext context, int x, int y, int z, int destX, int destZ, MutableMoveResult res) {
        BlockState destIntoUpper = context.get(destX, y + 1, destZ);
        if (!MovementHelper.canWalkThrough(context, destX, y + 1, destZ, destIntoUpper)) {
            return;
        }
        BlockState destInto = context.get(destX, y, destZ);
        BlockState fromDown;
        boolean ascend = false;
        BlockState destWalkOn;
        boolean descend = false;
        boolean frostWalker = false;
        boolean sneaking = false;
        if (!MovementHelper.canWalkThrough(context, destX, y, destZ, destInto)) {
            ascend = true;
            if (!context.allowDiagonalAscend || !MovementHelper.canWalkThrough(context, x, y + 2, z) || !MovementHelper.canWalkOn(context, destX, y, destZ, destInto) || !MovementHelper.canWalkThrough(context, destX, y + 2, destZ)) {
                return;
            }
            destWalkOn = destInto;
            fromDown = context.get(x, y - 1, z);
        } else {
            destWalkOn = context.get(destX, y - 1, destZ);
            fromDown = context.get(x, y - 1, z);
            boolean standingOnABlock = MovementHelper.mustBeSolidToWalkOn(context, x, y - 1, z, fromDown);
            frostWalker = standingOnABlock && MovementHelper.canUseFrostWalker(context, destWalkOn);
            if (!frostWalker && !MovementHelper.canWalkOn(context, destX, y - 1, destZ, destWalkOn)) {
                descend = true;
                if (!context.allowDiagonalDescend || !MovementHelper.canWalkOn(context, destX, y - 2, destZ) || !MovementHelper.canWalkThrough(context, destX, y - 1, destZ, destWalkOn)) {
                    return;
                }
            }
            frostWalker &= !context.assumeWalkOnWater; // do this after checking for descends because jesus can't prevent the water from freezing, it just prevents us from relying on the water freezing
        }
        BlockState startState = context.get(x, y, z);
        if (isBlockingDoor(startState, x, z, destX, destZ) || isBlockingDoor(ascend ? destIntoUpper : destInto, destX, destZ, x, y)) {
            return; // Easier to just traverse instead
        }
        double multiplier = WALK_ONE_BLOCK_COST;
        // For either possible soul sand, that affects half of our walking
        if (destWalkOn.is(Blocks.SOUL_SAND)) {
            multiplier += (context.soulSandWalkCost - WALK_ONE_BLOCK_COST) / 2;
        } else if (context.allowWalkOnMagmaBlocks && destWalkOn.is(Blocks.MAGMA_BLOCK)) {
            multiplier += (SNEAK_ONE_BLOCK_COST - WALK_ONE_BLOCK_COST) / 2;
            sneaking = true;
        } else if (frostWalker) {
            // frostwalker lets us walk on water without the penalty
        } else if (destWalkOn.getBlock() == Blocks.WATER) {
            multiplier += context.walkOnWaterOnePenalty * SQRT_2;
        }
        Block fromDownBlock = fromDown.getBlock();
        if (MovementHelper.isClimbable(fromDownBlock)) {
            return;
        }
        if (fromDownBlock == Blocks.SOUL_SAND) {
            multiplier += (context.soulSandWalkCost - WALK_ONE_BLOCK_COST) / 2;
        } else if (context.allowWalkOnMagmaBlocks && fromDownBlock.equals(Blocks.MAGMA_BLOCK)) {
            multiplier += (SNEAK_ONE_BLOCK_COST - WALK_ONE_BLOCK_COST) / 2;
            sneaking = true;
        }
        BlockState cuttingOver1 = context.get(x, y - 1, destZ);
        if ((!context.allowWalkOnMagmaBlocks && cuttingOver1.is(Blocks.MAGMA_BLOCK)) || MovementHelper.isLava(cuttingOver1)) {
            return;
        }
        BlockState cuttingOver2 = context.get(destX, y - 1, z);
        if ((!context.allowWalkOnMagmaBlocks && cuttingOver2.is(Blocks.MAGMA_BLOCK)) || MovementHelper.isLava(cuttingOver2)) {
            return;
        }
        boolean water = false;
        Block startIn = startState.getBlock();
        if (MovementHelper.isWater(startState) || MovementHelper.isWater(destInto)) {
            if (ascend) {
                return;
            }
            // Ignore previous multiplier
            // Whatever we were walking on (possibly soul sand) doesn't matter as we're actually floating on water
            // Not even touching the blocks below
            multiplier = context.swimsLava(destInto, startState) ? context.lavaSwimCost : context.waterCost(destX, y, destZ);
            water = true;
        }
        BlockState pb0 = context.get(x, y, destZ);
        BlockState pb2 = context.get(destX, y, z);
        if (water && context.allowSwimming && !swimCornersOpen(context, x, y, z, destX, destZ, pb0, pb2)) {
            // a swimmer can't edge. it floats with the box at head height and coasts on momentum, so a corner it
            // would edge past on land is one it pins itself on. mining the corner out instead means standing up in
            // the water to do it (see shouldSwim), slow and soggy. traverse twice around it instead
            return;
        }
        if (ascend) {
            boolean ATop = MovementHelper.canWalkThrough(context, x, y + 2, destZ);
            boolean AMid = MovementHelper.canWalkThrough(context, x, y + 1, destZ);
            boolean ALow = MovementHelper.canWalkThrough(context, x, y, destZ, pb0);
            boolean BTop = MovementHelper.canWalkThrough(context, destX, y + 2, z);
            boolean BMid = MovementHelper.canWalkThrough(context, destX, y + 1, z);
            boolean BLow = MovementHelper.canWalkThrough(context, destX, y, z, pb2);
            ALow &= !isBlockingDoor(pb0, x, destZ, destX, z);
            BLow &= !isBlockingDoor(pb2, destX, z, x, destZ);
            // going up a diagonal the 0.6 wide box sweeps through both corner columns, feet to head, right as it leaves
            // the ground. a solid block in either one is a wall we grind into and then slide back down off of, and that
            // includes a ceiling one (head bonk), so both corners have to be open. traverse + ascend picks up the rest
            if (!(ATop && AMid && ALow) || !(BTop && BMid && BLow)
                    || MovementHelper.avoidWalkingInto(pb0) // bad
                    || MovementHelper.avoidWalkingInto(pb2) // bad
                    || (ATop && AMid && MovementHelper.canWalkOn(context, x, y, destZ, pb0)) // we could just ascend
                    || (BTop && BMid && MovementHelper.canWalkOn(context, destX, y, z, pb2))) { // we could just ascend
                return;
            }
            // same slab rules as MovementAscend: a bottom slab is a half block lip that vanilla steps up by itself, so no
            // jump and no jump penalty going onto one, and nothing but another slab can be climbed from one
            boolean fromSlab = MovementHelper.isBottomSlab(fromDown);
            boolean toSlab = MovementHelper.isBottomSlab(destWalkOn);
            if (fromSlab && !toSlab) {
                return;
            }
            res.cost = multiplier * SQRT_2;
            if (!toSlab || fromSlab) {
                res.cost += JUMP_ONE_BLOCK_COST + context.jumpPenalty;
            }
            res.x = destX;
            res.z = destZ;
            res.y = y + 1;
            return;
        }
        double optionA = MovementHelper.getMiningDurationTicks(context, x, y, destZ, pb0, false);
        double optionB = MovementHelper.getMiningDurationTicks(context, destX, y, z, pb2, false);
        optionA += isBlockingDoor(pb0, x, destZ, destX, z) ? 1 : 0;
        optionB += isBlockingDoor(pb2, destX, z, x, destZ) ? 1 : 0;
        if (optionA != 0 && optionB != 0) {
            // check these one at a time -- if pb0 and pb2 were nonzero, we already know that (optionA != 0 && optionB != 0)
            // so no need to check pb1 as well, might as well return early here
            return;
        }
        BlockState pb1 = context.get(x, y + 1, destZ);
        optionA += MovementHelper.getMiningDurationTicks(context, x, y + 1, destZ, pb1, true);
        if (optionA != 0 && optionB != 0) {
            // same deal, if pb1 makes optionA nonzero and option B already was nonzero, pb3 can't affect the result
            return;
        }
        BlockState pb3 = context.get(destX, y + 1, z);
        if (optionA == 0 && ((MovementHelper.avoidWalkingInto(pb2) && pb2.getBlock() != Blocks.WATER) || MovementHelper.avoidWalkingInto(pb3))) {
            // at this point we're done calculating optionA, so we can check if it's actually possible to edge around in that direction
            return;
        }
        optionB += MovementHelper.getMiningDurationTicks(context, destX, y + 1, z, pb3, true);
        if (optionA != 0 && optionB != 0) {
            // and finally, if the cost is nonzero for both ways to approach this diagonal, it's not possible
            return;
        }
        if (optionB == 0 && ((MovementHelper.avoidWalkingInto(pb0) && pb0.getBlock() != Blocks.WATER) || MovementHelper.avoidWalkingInto(pb1))) {
            // and now that option B is fully calculated, see if we can edge around that way
            return;
        }
        if (optionA != 0 || optionB != 0) {
            // the corner makes us edge round it at a walk, so this is just walk speed over the diagonal. the cost used
            // to scale the multiplier by root 2 here and then scale it by root 2 again below, which priced an edge at
            // 9.3 against 7.1 for two sprinted straights and had the planner walking L shapes round every corner
            if (context.preferFasterPathing) {
                multiplier *= CORNER_EDGING_PENALTY;
            }
            if (MovementHelper.isClimbable(startIn)) {
                // edging around doesn't work if doing so would climb a ladder or vine instead of moving sideways
                return;
            }
        } else {
            // only can sprint if not edging around
            if (context.canSprint && !water && !sneaking) {
                // If we aren't edging around anything, and we aren't in water
                // We can sprint =D
                // Don't check for soul sand, since we can sprint on that too
                multiplier *= SPRINT_MULTIPLIER;
            }
        }
        res.cost = multiplier * SQRT_2;
        res.cost += context.boatHandoff(x, y, z, destX, descend ? y - 1 : y, destZ);
        if (descend) {
            res.cost += Math.max(FALL_N_BLOCKS_COST[1], CENTER_AFTER_FALL_COST);
            res.y = y - 1;
        } else {
            res.y = y;
        }
        res.x = destX;
        res.z = destZ;
    }

    // both corner columns, feet and head, same rule as the diagonal ascend
    private static boolean swimCornersOpen(CalculationContext context, int x, int y, int z, int destX, int destZ, BlockState pb0, BlockState pb2) {
        return MovementHelper.swimmerFits(context.bsi, x, y, destZ, pb0)
                && MovementHelper.swimmerFits(context.bsi, destX, y, z, pb2)
                && MovementHelper.swimmerFits(context.bsi, x, y + 1, destZ, context.get(x, y + 1, destZ))
                && MovementHelper.swimmerFits(context.bsi, destX, y + 1, z, context.get(destX, y + 1, z));
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        if (ctx.playerFeet().equals(dest)) {
            return state.setStatus(MovementStatus.SUCCESS);
        } else if (!playerInValidPosition() && !(MovementHelper.isLiquid(ctx, src) && getValidPositions().contains(ctx.playerFeet().above()))) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }

        if (!isBlockingDoor(BlockStateInterface.get(ctx, new BetterBlockPos(src.x, src.y, dest.z)), src.x, dest.z, dest.x, src.z)
            && (!MovementHelper.openDoors(ctx, state, src, new BetterBlockPos(src.x, src.y, dest.z))
                || !MovementHelper.openDoors(ctx, state, new BetterBlockPos(src.x, dest.y, dest.z), dest)
        )) {
            return state;
        }
        if (!isBlockingDoor(BlockStateInterface.get(ctx, new BetterBlockPos(dest.x, src.y, src.z)), dest.x, src.z, src.x, dest.z)
            && (!MovementHelper.openDoors(ctx, state, src, new BetterBlockPos(dest.x, src.y, src.z))
                || !MovementHelper.openDoors(ctx, state, new BetterBlockPos(dest.x, dest.y, src.z), dest)
        )) {
            return state;
        }

        if (dest.y > src.y && ctx.player().position().y < src.y + 0.1 && ctx.player().horizontalCollision) {
            state.setInput(Input.JUMP, true);
        }
        if (sprint()) {
            state.setInput(Input.SPRINT, true);
        }
        state.setInput(Input.SNEAK, Baritone.settings().allowWalkOnMagmaBlocks.value && MovementHelper.steppingOnBlocks(ctx).stream().anyMatch(block -> ctx.world().getBlockState(block).is(Blocks.MAGMA_BLOCK)));
        MovementHelper.moveTowards(ctx, state, dest);
        return state;
    }

    private boolean sprint() {
        if (MovementHelper.isLiquid(ctx, ctx.playerFeet()) && !Baritone.settings().sprintInWater.value) {
            return false;
        }
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        return cornersOpen(bsi) && !onLavaShore(bsi);
    }

    // same idea as MovementTraverse.onLavaShore, once per movement
    public boolean onLavaShore(BlockStateInterface bsi) {
        if (lavaShore == null) {
            lavaShore = ctx.world().dimension() == Level.NETHER
                    && CliffAvoidance.diagonalByLava(CliffAvoidance.of(bsi), src.x, src.y, src.z, dest.x, dest.y, dest.z);
        }
        return lavaShore;
    }

    // the two corner columns, feet and head. a sprint round the corner would clip any of them, so the sprint is only on when all
    // four are open, and the executor asks the same thing to carry a hop's sprint into this diagonal
    public boolean cornersOpen(BlockStateInterface bsi) {
        for (int i = 0; i < 4; i++) {
            if (!MovementHelper.canWalkThrough(bsi, positionsToBreak[i].x, positionsToBreak[i].y, positionsToBreak[i].z)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected boolean prepared(MovementState state) {
        return true;
    }

    @Override
    public List<BlockPos> toBreak(BlockStateInterface bsi) {
        if (toBreakCached != null) {
            return toBreakCached;
        }
        List<BlockPos> result = new ArrayList<>();
        for (int i = 4; i < 6; i++) {
            if (!MovementHelper.canWalkThrough(bsi, positionsToBreak[i].x, positionsToBreak[i].y, positionsToBreak[i].z)) {
                result.add(positionsToBreak[i]);
            }
        }
        toBreakCached = result;
        return result;
    }

    @Override
    public List<BlockPos> toWalkInto(BlockStateInterface bsi) {
        if (toWalkIntoCached == null) {
            toWalkIntoCached = new ArrayList<>();
        }
        List<BlockPos> result = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            if (!MovementHelper.canWalkThrough(bsi, positionsToBreak[i].x, positionsToBreak[i].y, positionsToBreak[i].z)) {
                result.add(positionsToBreak[i]);
            }
        }
        toWalkIntoCached = result;
        return toWalkIntoCached;
    }

    /**
     * We can always pass wooden doors in src or dest and we can also pass
     * wooden doors in the A or B columns, unless their hinge points towards
     * the other column.
     * This function checks whether {@code other} is the diagonal neighbor of
     * {@code door} in the direction of the hinge of {@code state}.
     */
    private static boolean isBlockingDoor(BlockState state, int doorX, int doorZ, int otherX, int otherZ) {
        if (!(state.getBlock() instanceof DoorBlock)) {
            return false;
        }

        Vec3i offset = state.getValue(HorizontalDirectionalBlock.FACING).getUnitVec3i();
        int ox = offset.getX();
        int oz = offset.getZ();

        int nbrX, nbrZ;
        if (state.getValue(DoorBlock.HINGE) == DoorHingeSide.LEFT) {
            nbrX = doorX - ox + oz;
            nbrZ = doorZ - oz - ox;
        } else {
            nbrX = doorX - ox - oz;
            nbrZ = doorZ - oz + ox;
        }

        return nbrX == otherX && nbrZ == otherZ;
    }
}
