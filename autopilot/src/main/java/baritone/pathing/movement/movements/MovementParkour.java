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
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.pathing.movement.SolidFloor;
import baritone.utils.BlockStateInterface;
import baritone.utils.ExperimentalMovement;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.WaterFluid;

import java.util.HashSet;
import java.util.Set;

public class MovementParkour extends Movement {

    private static final BetterBlockPos[] EMPTY = new BetterBlockPos[]{};

    private final Direction direction;
    private final int dist;
    private final boolean ascend;

    private MovementParkour(IBaritone baritone, BetterBlockPos src, int dist, Direction dir, boolean ascend) {
        super(baritone, src, src.relative(dir, dist).above(ascend ? 1 : 0), EMPTY, src.relative(dir, dist).below(ascend ? 0 : 1));
        this.direction = dir;
        this.dist = dist;
        this.ascend = ascend;
    }

    public static MovementParkour cost(CalculationContext context, BetterBlockPos src, Direction direction) {
        MutableMoveResult res = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, direction, res);
        int dist = Math.abs(res.x - src.x) + Math.abs(res.z - src.z);
        return new MovementParkour(context.getBaritone(), src, dist, direction, res.y > src.y);
    }

    public static void cost(CalculationContext context, int x, int y, int z, Direction dir, MutableMoveResult res) {
        if (!context.allowParkour) {
            return;
        }
        if (!context.allowJumpAtBuildLimit && y >= context.maxY) {
            return;
        }
        int xDiff = dir.getStepX();
        int zDiff = dir.getStepZ();
        if (!MovementHelper.fullyPassable(context, x + xDiff, y, z + zDiff)) {
            // most common case at the top -- the adjacent block isn't air
            return;
        }
        BlockState adj = context.get(x + xDiff, y - 1, z + zDiff);
        if (MovementHelper.canWalkOn(context, x + xDiff, y - 1, z + zDiff, adj)) { // don't parkour if we could just traverse (for now)
            // second most common case -- we could just traverse not parkour
            return;
        }
        if (MovementHelper.avoidWalkingInto(adj) && !(adj.getFluidState().getType() instanceof WaterFluid)) { // magma sucks
            return;
        }
        if (!MovementHelper.fullyPassable(context, x + xDiff, y + 1, z + zDiff)) {
            return;
        }
        if (!MovementHelper.fullyPassable(context, x + xDiff, y + 2, z + zDiff)) {
            return;
        }
        if (!MovementHelper.fullyPassable(context, x, y + 2, z)) {
            return;
        }
        BlockState standingOn = context.get(x, y - 1, z);
        if (MovementHelper.isClimbable(standingOn.getBlock()) || standingOn.getBlock() instanceof StairBlock || MovementHelper.isBottomSlab(standingOn)) {
            return;
        }
        // we can't jump from (frozen) water with assumeWalkOnWater because we can't be sure it will be frozen
        if (context.assumeWalkOnWater && !standingOn.getFluidState().isEmpty()) {
            return;
        }
        if (!context.get(x, y, z).getFluidState().isEmpty()) {
            return; // can't jump out of water
        }
        int maxJump;
        if (context.allowWalkOnMagmaBlocks && standingOn.is(Blocks.MAGMA_BLOCK)) {
            maxJump = 2;
        } else if (standingOn.getBlock() == Blocks.SOUL_SAND) {
            maxJump = 2; // 1 block gap
        } else if (context.canSprint) {
            maxJump = 4;
        } else {
            maxJump = 3;
        }

        // check parkour jumps from smallest to largest for obstacles/walls and landing positions
        int verifiedMaxJump = 1; // i - 1 (when i = 2)
        for (int i = 2; i <= maxJump; i++) {
            int destX = x + xDiff * i;
            int destZ = z + zDiff * i;

            // check head/feet
            if (!MovementHelper.fullyPassable(context, destX, y + 1, destZ)) {
                break;
            }
            if (!MovementHelper.fullyPassable(context, destX, y + 2, destZ)) {
                break;
            }

            // check for ascend landing position
            BlockState destInto = context.bsi.get0(destX, y, destZ);
            if (!MovementHelper.fullyPassable(context, destX, y, destZ, destInto)) {
                // a bottom slab is a half block step: we'd come down at y + 0.5, the feet read as cell y, and the executor takes
                // that for a fall. same reason neos and momentum jumps won't land on one
                if (i <= 3 && context.allowParkourAscend && context.canSprint && MovementHelper.canWalkOn(context, destX, y, destZ, destInto) && !MovementHelper.isBottomSlab(destInto) && checkOvershootSafety(context.bsi, destX + xDiff, y + 1, destZ + zDiff)) {
                    res.x = destX;
                    res.y = y + 1;
                    res.z = destZ;
                    res.cost = context.biasJump(i * SPRINT_ONE_BLOCK_COST + context.jumpPenalty);
                    return;
                }
                break;
            }

            // check for flat landing position
            BlockState landingOn = context.bsi.get0(destX, y - 1, destZ);
            // a bottom slab comes down half a block low (see the ascend landing above). not a landing, but the jump can still go over it
            // farmland needs to be canWalkOn otherwise farm can never work at all, but we want to specifically disallow ending a jump on farmland haha
            // frostwalker works here because we can't jump from possibly unfrozen water
            if ((landingOn.getBlock() != Blocks.FARMLAND && !MovementHelper.isBottomSlab(landingOn) && MovementHelper.canWalkOn(context, destX, y - 1, destZ, landingOn))
                    || (Math.min(16, context.frostWalker + 2) >= i && MovementHelper.canUseFrostWalker(context, landingOn))
            ) {
                if (checkOvershootSafety(context.bsi, destX + xDiff, y, destZ + zDiff)) {
                    res.x = destX;
                    res.y = y;
                    res.z = destZ;
                    double hop = hopPrice(context, x, y, z, xDiff, zDiff, i, standingOn, landingOn);
                    res.cost = context.biasJump((hop >= 0 ? hop : costFromJumpDistance(i)) + context.jumpPenalty);
                    return;
                }
                break;
            }

            if (!MovementHelper.fullyPassable(context, destX, y + 3, destZ)) {
                break;
            }

            verifiedMaxJump = i;
        }

        // parkour place starts here
        if (!context.allowParkourPlace) {
            return;
        }
        // check parkour jumps from largest to smallest for positions to place blocks
        for (int i = verifiedMaxJump; i > 1; i--) {
            int destX = x + i * xDiff;
            int destZ = z + i * zDiff;
            BlockState toReplace = context.get(destX, y - 1, destZ);
            double placeCost = context.costOfPlacingAt(destX, y - 1, destZ, toReplace);
            if (placeCost >= COST_INF) {
                continue;
            }
            if (!MovementHelper.isReplaceable(destX, y - 1, destZ, toReplace, context.bsi)) {
                continue;
            }
            if (!checkOvershootSafety(context.bsi, destX + xDiff, y, destZ + zDiff)) {
                continue;
            }
            for (int j = 0; j < 5; j++) {
                int againstX = destX + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[j].getStepX();
                int againstY = y - 1 + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[j].getStepY();
                int againstZ = destZ + HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[j].getStepZ();
                if (againstX == destX - xDiff && againstZ == destZ - zDiff) { // we can't turn around that fast
                    continue;
                }
                if (MovementHelper.canPlaceAgainst(context, againstX, againstY, againstZ)) {
                    res.x = destX;
                    res.y = y;
                    res.z = destZ;
                    res.cost = context.biasJump(costFromJumpDistance(i) + placeCost + context.jumpPenalty);
                    return;
                }
            }
        }
    }

    static boolean checkOvershootSafety(BlockStateInterface bsi, int x, int y, int z) {
        // we're going to walk into these two blocks after the landing of the parkour anyway, so make sure they aren't avoidWalkingInto
        return !MovementHelper.avoidWalkingInto(bsi.get0(x, y, z)) && !MovementHelper.avoidWalkingInto(bsi.get0(x, y + 1, z));
    }

    // 1 or 2 when feet are that many cells past dest along the jump, 0 for anywhere else. a sprint jump flies a block or two
    // further than a landing was planned for if we were quick off the edge
    static int overshotBy(BlockPos dest, Direction dir, BlockPos feet) {
        if (feet.getY() != dest.getY()) {
            return 0;
        }
        for (int k = 1; k <= 2; k++) {
            if (feet.getX() == dest.getX() + dir.getStepX() * k && feet.getZ() == dest.getZ() + dir.getStepZ() * k) {
                return k;
            }
        }
        return 0;
    }

    // what the executor's sprint hop (SprintJump, which wants the same plain floors at both ends, the head room to fly and some
    // straight floor past the landing to slide out on) costs over a 1 or 2 block gap, or a negative number for "it'll be walked".
    // it only hops on the way in already sprinting, and the path has to actually go straight on past the landing, which the
    // planner can't know, so this is the usual case and not a promise
    private static double hopPrice(CalculationContext context, int x, int y, int z, int xDiff, int zDiff, int dist, BlockState standingOn, BlockState landingOn) {
        if (dist > 3 || !context.canSprint || !ExperimentalMovement.sprintJumping()
                || !SolidFloor.plain(standingOn, context.bsi.access, new BlockPos(x, y - 1, z))
                || !SolidFloor.plain(landingOn, context.bsi.access, new BlockPos(x + xDiff * dist, y - 1, z + zDiff * dist))
                || !MovementHelper.fullyPassable(context, x, y + 3, z) || !MovementHelper.fullyPassable(context, x + xDiff, y + 3, z + zDiff)) {
            return -1;
        }
        // the cells past the landing: none and the sim says no (a wall as far as it knows), one and it has to brake to stop
        // on it, two and it can fly on
        int runway = 0;
        for (int k = 0; k <= 2; k++) {
            int cx = x + xDiff * (dist + k);
            int cz = z + zDiff * (dist + k);
            if (k > 0 && !SolidFloor.plain(context.get(cx, y - 1, cz), context.bsi.access, new BlockPos(cx, y - 1, cz))) {
                break;
            }
            if (!MovementHelper.fullyPassable(context, cx, y, cz) || !MovementHelper.fullyPassable(context, cx, y + 1, cz)
                    || !MovementHelper.fullyPassable(context, cx, y + 2, cz) || !MovementHelper.fullyPassable(context, cx, y + 3, cz)) {
                break;
            }
            runway = k;
        }
        if (runway == 0) {
            return -1;
        }
        // the air time is what a hop costs, 12 ticks whatever the gap, less the half block of run up we'd have covered at
        // sprint speed on the way to the middle of the block. with room to fly on it covers 3 or more blocks in that time, so
        // the sprint price is the floor. with one cell of floor it brakes to land short, and the air time is the price
        double sprint = dist * SPRINT_ONE_BLOCK_COST;
        return runway >= 2 ? sprint : Math.max(sprint, HOP_AIR_TICKS - 0.5 * SPRINT_ONE_BLOCK_COST);
    }

    private static final double HOP_AIR_TICKS = 12;

    private static double costFromJumpDistance(int dist) {
        switch (dist) {
            case 2:
                return WALK_ONE_BLOCK_COST * 2; // IDK LOL
            case 3:
                return WALK_ONE_BLOCK_COST * 3;
            case 4:
                return SPRINT_ONE_BLOCK_COST * 4;
            default:
                throw new IllegalStateException("LOL " + dist);
        }
    }


    @Override
    public double calculateCost(CalculationContext context) {
        MutableMoveResult res = new MutableMoveResult();
        cost(context, src.x, src.y, src.z, direction, res);
        if (res.x != dest.x || res.y != dest.y || res.z != dest.z) {
            return COST_INF;
        }
        return res.cost;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        for (int i = 0; i <= dist; i++) {
            for (int y = 0; y < 2; y++) {
                set.add(src.relative(direction, i).above(y));
            }
        }
        return set;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        // once this movement is instantiated, the state is default to PREPPING
        // but once it's ticked for the first time it changes to RUNNING
        // since we don't really know anything about momentum, it suffices to say Parkour can only be canceled on the 0th tick
        return state.getStatus() != MovementStatus.RUNNING;
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        if (ctx.playerFeet().y < src.y) {
            // we have fallen
            logDebug("sorry");
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        if (!MovementHelper.openDoors(ctx, state, src, src.relative(direction))) {
            return state;
        }
        if (dist >= 4 || ascend) {
            state.setInput(Input.SPRINT, true);
        }
        if (Baritone.settings().allowWalkOnMagmaBlocks.value && ctx.world().getBlockState(ctx.playerFeet().below()).is(Blocks.MAGMA_BLOCK)) {
            state.setInput(Input.SNEAK, true);
        }

        MovementHelper.moveTowards(ctx, state, dest);
        if (ctx.playerFeet().equals(dest)) {
            Block d = BlockStateInterface.getBlock(ctx, dest);
            if (d == Blocks.VINE || d == Blocks.LADDER) {
                // it physically hurt me to add support for parkour jumping onto a vine
                // but i did it anyway
                return state.setStatus(MovementStatus.SUCCESS);
            }
            if (ctx.player().position().y - ctx.playerFeet().getY() < 0.094) { // lilypads
                state.setStatus(MovementStatus.SUCCESS);
            }
        } else if (overshotBy(dest, direction, ctx.playerFeet()) > 0 && ctx.player().onGround()
                && ctx.player().position().y - ctx.playerFeet().getY() < 0.094
                && MovementHelper.canWalkOn(ctx, ctx.playerFeet().below())
                && checkOvershootSafety(((Baritone) baritone).bsi, ctx.playerFeet().getX(), ctx.playerFeet().getY(), ctx.playerFeet().getZ())) {
            // came down past dest. without this the "walk back toward the source" branch below took over and the bot
            // went back for the cell it had already jumped over. same idea as overshootTraverse. a cell with no floor of
            // its own (we're hanging on to the edge of dest) still walks back, that one isn't a place to start the next move from
            return state.setStatus(MovementStatus.SUCCESS);
        } else if (!ctx.playerFeet().equals(src)) {
            if (ctx.playerFeet().equals(src.relative(direction)) || ctx.player().position().y - src.y > 0.0001) {
                if (Baritone.settings().allowPlace.value // see PR #3775
                        && !MovementHelper.canWalkOn(ctx, dest.below())
                        && !ctx.player().onGround()) {
                    // this runs every tick we're in the air, so the retry is built in: ATTEMPTING keeps aiming (the rotation
                    // is forced) and the click goes out on the first tick the crosshair is actually on the face.
                    // clicking before that would put the block against whatever we happen to be looking at
                    if (!((Baritone) baritone).getInventoryBehavior().hasGenericThrowaway()) {
                        // just the log, not UNREACHABLE: we're already mid jump and might still make it
                        MovementHelper.logNoThrowaway(state, baritone, dest.below());
                    } else if (MovementHelper.attemptToPlaceABlock(state, baritone, dest.below(), true, false) == PlaceResult.READY_TO_PLACE) {
                        // go in the opposite order to check DOWN before all horizontals -- down is preferable because you don't have to look to the side while in midair, which could mess up the trajectory
                        state.setInput(Input.CLICK_RIGHT, true);
                    }
                }
                // prevent jumping too late by checking for ascend
                if (dist == 3 && !ascend) { // this is a 2 block gap, dest = src + direction * 3
                    double xDiff = (src.x + 0.5) - ctx.player().position().x;
                    double zDiff = (src.z + 0.5) - ctx.player().position().z;
                    double distFromStart = Math.max(Math.abs(xDiff), Math.abs(zDiff));
                    if (distFromStart < 0.7) {
                        return state;
                    }
                }

                state.setInput(Input.JUMP, true);
            } else if (!ctx.playerFeet().equals(dest.relative(direction, -1))) {
                state.setInput(Input.SPRINT, false);
                if (ctx.playerFeet().equals(src.relative(direction, -1))) {
                    MovementHelper.moveTowards(ctx, state, src);
                } else {
                    MovementHelper.moveTowards(ctx, state, src.relative(direction, -1));
                }
            }
        }
        return state;
    }
}
