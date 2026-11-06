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

package baritone.pathing.movement;

import baritone.Baritone;
import baritone.altoclef.AltoClefSettings;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.*;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementState.MovementTarget;
import baritone.pathing.path.SprintPolicy;
import baritone.pathing.precompute.Ternary;
import baritone.utils.BlockStateInterface;
import baritone.utils.FallingColumn;
import baritone.utils.ToolSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import baritone.behavior.LegacyCombat;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.material.*;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.*;
import java.util.stream.Stream;

import static baritone.api.utils.RotationUtils.DEG_TO_RAD_F;
import static baritone.pathing.movement.Movement.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP;
import static baritone.pathing.precompute.Ternary.*;

/**
 * Static helpers for cost calculation
 *
 * @author leijurv
 */
public interface MovementHelper extends ActionCosts, Helper {

    // (the buddy's fork: the player is in lava right now, set every tick by WorldSurvivalChain. then lava beside a block is no
    // reason to leave it: an escape walled in by nylium and a wart block, every one of them beside the lava it stood in, found
    // no path 66 times and burned to death under a one block ceiling, attempt 236, 4 Nov 2026)
    java.util.concurrent.atomic.AtomicBoolean IN_LAVA = new java.util.concurrent.atomic.AtomicBoolean();

    // (the buddy's fork: a block under an open cell that lava sits beside: once it is gone the lava pours over and down into
    // the cell. a trench dug under a fungus canopy opened one, and lava a block up and over came in on the buddy, attempt 236)
    static boolean lavaPoursIn(BlockStateInterface bsi, int x, int y, int z) {
        if (IN_LAVA.get()) return false;
        BlockState above = bsi.get0(x, y, z);
        if (!above.getFluidState().isEmpty() || !above.canBeReplaced()) return false;
        return lava(bsi.get0(x + 1, y, z)) || lava(bsi.get0(x - 1, y, z)) || lava(bsi.get0(x, y, z + 1)) || lava(bsi.get0(x, y, z - 1));
    }

    private static boolean lava(BlockState state) {
        return state.getBlock() == Blocks.LAVA;
    }

    static boolean avoidBreaking(CalculationContext context, int x, int y, int z, BlockState state) {
        // same as the one below except the "is this block ever ok to break" bit is precomputed instead of a list scan every time
        return !context.bsi.worldBorder.canPlaceAt(x, z)
                || context.precomputedData.neverBreak(context.bsi, state)
                || lavaPoursIn(context.bsi, x, y + 1, z)
                || avoidAdjacentBreaking(context.bsi, x, y + 1, z, true)
                || avoidAdjacentBreaking(context.bsi, x + 1, y, z, false)
                || avoidAdjacentBreaking(context.bsi, x - 1, y, z, false)
                || avoidAdjacentBreaking(context.bsi, x, y, z + 1, false)
                || avoidAdjacentBreaking(context.bsi, x, y, z - 1, false);
    }

    static boolean avoidBreaking(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        if (!bsi.worldBorder.canPlaceAt(x, z)) {
            return true;
        }
        // the context version gets this from its snapshot, this one is for the executor and altoclef who ask the live thing
        if (AltoClefSettings.getInstance().shouldAvoidBreaking(x, y, z)) {
            return true;
        }
        Block b = state.getBlock();
        return Baritone.settings().blocksToDisallowBreaking.value.contains(b)
                // altoclef only, stock leaves frames to the world
                || (b instanceof EndPortalFrameBlock && AltoClefSettings.getInstance().hasEndPortalFrameRules())
                || b == Blocks.ICE // ice becomes water, and water can mess up the path
                || b instanceof InfestedBlock // obvious reasons
                || lavaPoursIn(bsi, x, y + 1, z)
                // call context.get directly with x,y,z. no need to make 5 new BlockPos for no reason
                || avoidAdjacentBreaking(bsi, x, y + 1, z, true)
                || avoidAdjacentBreaking(bsi, x + 1, y, z, false)
                || avoidAdjacentBreaking(bsi, x - 1, y, z, false)
                || avoidAdjacentBreaking(bsi, x, y, z + 1, false)
                || avoidAdjacentBreaking(bsi, x, y, z - 1, false);
    }

    static boolean avoidAdjacentBreaking(BlockStateInterface bsi, int x, int y, int z, boolean directlyAbove) {
        // returns true if you should avoid breaking a block that's adjacent to this one (e.g. lava that will start flowing if you give it a path)
        // this is only called for north, south, east, west, and up. this is NOT called for down.
        // we assume that it's ALWAYS okay to break the block thats ABOVE liquid
        BlockState state = bsi.get0(x, y, z);
        Block block = state.getBlock();
        if (directlyAbove && block instanceof EndPortalFrameBlock && AltoClefSettings.getInstance().hasEndPortalFrameRules()) {
            return true; // mining out from under a portal frame is how you lose an end portal
        }
        if (!directlyAbove // it is fine to mine a block that has a falling block directly above, this (the cost of breaking the stacked fallings) is included in cost calculations
                // therefore if directlyAbove is true, we will actually ignore if this is falling
                && block instanceof FallingBlock // obviously, this check is only valid for falling blocks
                && Baritone.settings().avoidUpdatingFallingBlocks.value // and if the setting is enabled
                && FallingBlock.isFree(bsi.get0(x, y - 1, z))) { // and if it would fall (i.e. it's unsupported)
            return true; // dont break a block that is adjacent to unsupported gravel because it can cause really weird stuff
        }
        // only pure liquids for now
        // waterlogged blocks can have closed bottom sides and such
        if (block instanceof LiquidBlock) {
            // (in lava already, more of it next to the way out changes nothing: see IN_LAVA)
            if (IN_LAVA.get() && block == Blocks.LAVA) return false;
            return liquidBesideBlocks(directlyAbove, Baritone.settings().strictLiquidCheck.value, block == Blocks.LAVA,
                    state.getValue(LiquidBlock.LEVEL), bsi.get0(x, y - 1, z).getBlock() instanceof LiquidBlock);
        }
        return !state.getFluidState().isEmpty();
    }

    // stock baritone lets a block go that has flowing liquid beside it when more liquid is under that, on the guess that it
    // keeps falling and never turns sideways. for lava that guess is a death: the cell beside a lava fall had lava under it,
    // the step next to it was dug, and the fall poured into the hole and over us (practice run 116). lava is never assumed to
    // stay put, water keeps the old rule
    static boolean liquidBesideBlocks(boolean directlyAbove, boolean strict, boolean lava, int level, boolean liquidBelow) {
        if (directlyAbove || strict || lava) {
            return true;
        }
        if (level == 0) {
            return true; // source blocks like to flow horizontally
        }
        // everything else will prefer flowing down
        return !liquidBelow; // assume everything is in a static state
    }

    static boolean canWalkThrough(IPlayerContext ctx, BetterBlockPos pos) {
        return canWalkThrough(new BlockStateInterface(ctx), pos.x, pos.y, pos.z);
    }

    // whether a boat can float at this position with someone in it. surface water only, and the block above
    // the headroom that a swimmer needs has to be open as well because a rider sits taller than the two
    // blocks a water path node checks, and would suffocate under a low ceiling.
    // bubble columns are the real killer: the whirlpool over a magma block drags a boat under and dumps the
    // passenger, the soul sand kind launches it.
    // and the boat is 1.375 wide, so centered on this block its box pokes 0.19 into all eight neighbours,
    // at water level and the block above. a bank at water level there means the item refuses to place it
    // and a moving boat scrapes to a stop, which is exactly what happened along a river. so every
    // neighbour has to be open, which also makes A* route a block off the shore where the boat price applies
    static boolean canFloatBoat(BlockStateInterface bsi, int x, int y, int z) {
        return canFloatBoat(bsi, x, y, z, 0, 0);
    }

    // same, but the neighbours on the (landDx, landDz) side get a pass. that's the first water block off the
    // shore, whose bank is always right there at water level: the boat gets placed well past the middle of
    // that block so its box clears the bank, and we're off it a tick later
    static boolean canFloatBoat(BlockStateInterface bsi, int x, int y, int z, int landDx, int landDz) {
        BlockState here = bsi.get0(x, y, z);
        if (!isRealWater(here) || isRealWater(bsi.get0(x, y + 1, z))) {
            return false;
        }
        if (isBlockNormalCube(bsi.get0(x, y + 2, z))) {
            return false;
        }
        Block under = bsi.get0(x, y - 1, z).getBlock();
        if (under == Blocks.MAGMA_BLOCK || under == Blocks.SOUL_SAND) {
            // the column hasn't necessarily formed yet (it needs a block update) but it will the moment we get there
            return false;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockState side = bsi.get0(x + dx, y, z + dz);
                if (side.getBlock() == Blocks.BUBBLE_COLUMN) {
                    return false;
                }
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (dx * landDx + dz * landDz > 0 || landDx == 2) {
                    continue; // the shore side we're launching from, or (2, 2) for "don't care about any of them"
                }
                if (!boatCanOverlap(side) || !boatCanOverlap(bsi.get0(x + dx, y + 1, z + dz))) {
                    return false;
                }
            }
        }
        return true;
    }

    // nothing the boat's box would bump: water, air, and the flowers-and-grass kind of block
    static boolean boatCanOverlap(BlockState state) {
        return isRealWater(state) || fullyPassableBlockState(state) == YES;
    }

    // isWater says yes to lava while altoclef's swim through lava is on, and a boat on lava is a burning boat
    static boolean isRealWater(BlockState state) {
        Fluid f = state.getFluidState().getType();
        return f == Fluids.WATER || f == Fluids.FLOWING_WATER;
    }

    static boolean canWalkThrough(BlockStateInterface bsi, int x, int y, int z) {
        return canWalkThrough(bsi, x, y, z, bsi.get0(x, y, z));
    }

    static boolean canWalkThrough(CalculationContext context, int x, int y, int z, BlockState state) {
        // position rules never go in PrecomputedData, that table is per blockstate and shared by every search
        if (context.altoActive && context.altoAvoidsWalkThrough(x, y, z)) {
            return false;
        }
        return context.precomputedData.canWalkThrough(context.bsi, x, y, z, state);
    }

    static boolean canWalkThrough(CalculationContext context, int x, int y, int z) {
        return canWalkThrough(context, x, y, z, context.get(x, y, z));
    }

    static boolean canWalkThrough(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        if (AltoClefSettings.getInstance().shouldAvoidWalkThroughForce(x, y, z)) {
            return false;
        }
        Ternary canWalkThrough = canWalkThroughBlockState(state);
        if (canWalkThrough == YES) {
            return true;
        }
        if (canWalkThrough == NO) {
            return false;
        }
        return canWalkThroughPosition(bsi, x, y, z, state);
    }

    static Ternary canWalkThroughBlockState(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof AirBlock) {
            return YES;
        }
        if (block == Blocks.LAVA && AltoClefSettings.getInstance().canSwimThroughLava()) {
            return MAYBE; // PrecomputedData is keyed on this toggle, and canWalkThroughPosition has the rest
        }
        if (block instanceof BaseFireBlock || block == Blocks.COBWEB || block == Blocks.END_PORTAL || block == Blocks.COCOA || block instanceof AbstractSkullBlock || block == Blocks.BUBBLE_COLUMN || block instanceof ShulkerBoxBlock || block instanceof SlabBlock || block instanceof TrapDoorBlock || block == Blocks.HONEY_BLOCK || block == Blocks.END_ROD || block == Blocks.SWEET_BERRY_BUSH || block == Blocks.POINTED_DRIPSTONE || block instanceof AmethystClusterBlock || block instanceof AzaleaBlock) {
            return NO;
        }
        if (block == Blocks.BIG_DRIPLEAF) {
            return NO;
        }
        if (block == Blocks.POWDER_SNOW) {
            return NO;
        }
        if (Baritone.settings().blocksToAvoid.value.contains(block)) {
            return NO;
        }
        if (block instanceof DoorBlock) {
            return DoorBlock.isWoodenDoor(state) ? YES : NO;
        }
        if (block instanceof FenceGateBlock) {
            return YES;
        }
        if (block instanceof CarpetBlock) {
            return MAYBE;
        }
        if (block instanceof SnowLayerBlock) {
            // snow layers cached as the top layer of a packed chunk have no metadata, we can't make a decision based on their depth here
            // it would otherwise make long distance pathing through snowy biomes impossible
            return MAYBE;
        }
        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty()) {
            if (fluidState.getType().getAmount(fluidState) != 8) {
                return NO;
            } else {
                return MAYBE;
            }
        }
        if (block instanceof CauldronBlock) {
            return NO;
        }
        if (state.isPathfindable(PathComputationType.LAND)) {
            return YES;
        } else {
            return NO;
        }
    }

    static boolean canWalkThroughPosition(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        Block block = state.getBlock();

        if (block == Blocks.LAVA && AltoClefSettings.getInstance().canSwimThroughLava()) {
            // only the top of the lake, you can't swim through lava you're already under
            return bsi.get0(x, y + 1, z).getFluidState().isEmpty();
        }

        if (block instanceof CarpetBlock) {
            return canWalkOn(bsi, x, y - 1, z);
        }

        if (block instanceof SnowLayerBlock) {
            // if they're cached as a top block, we don't know their metadata
            // default to true (mostly because it would otherwise make long distance pathing through snowy biomes impossible)
            if (!bsi.worldContainsLoadedChunk(x, z)) {
                return true;
            }
            // the check in BlockSnow.isPassable is layers < 5
            // while actually, we want < 3 because 3 or greater makes it impassable in a 2 high ceiling
            if (state.getValue(SnowLayerBlock.LAYERS) >= 3) {
                return false;
            }
            // ok, it's low enough we could walk through it, but is it supported?
            return canWalkOn(bsi, x, y - 1, z);
        }

        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty()) {
            if (isFlowing(x, y, z, state, bsi)) {
                return false;
            }
            // Everything after this point has to be a special case as it relies on the water not being flowing, which means a special case is needed.
            if (Baritone.settings().assumeWalkOnWater.value) {
                return false;
            }

            BlockState up = bsi.get0(x, y + 1, z);
            if (!up.getFluidState().isEmpty() || up.getBlock() instanceof LilyPadBlock) {
                return false;
            }
            return fluidState.getType() instanceof WaterFluid;
        }

        return state.isPathfindable(PathComputationType.LAND);
    }

    static Ternary fullyPassableBlockState(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof AirBlock) { // early return for most common case
            return YES;
        }
        // exceptions - blocks that are isPassable true, but we can't actually jump through
        if (block instanceof BaseFireBlock
                || block == Blocks.TRIPWIRE
                || block == Blocks.COBWEB
                || block == Blocks.VINE
                || block == Blocks.LADDER
                || block == Blocks.COCOA
                || block instanceof AzaleaBlock
                || block instanceof DoorBlock
                || block instanceof FenceGateBlock
                || block instanceof SnowLayerBlock
                || !state.getFluidState().isEmpty()
                || block instanceof TrapDoorBlock
                || block instanceof EndPortalBlock
                || block instanceof SkullBlock
                || block instanceof ShulkerBoxBlock) {
            return NO;
        }
        // door, fence gate, liquid, trapdoor have been accounted for, nothing else uses the world or pos parameters
        // at least in 1.12.2 vanilla, that is.....
        if (state.isPathfindable(PathComputationType.LAND)) {
            return YES;
        } else {
            return NO;
        }
    }

    /**
     * canWalkThrough but also won't impede movement at all. so not including doors or fence gates (we'd have to right click),
     * not including water, and not including ladders or vines or cobwebs (they slow us down)
     */
    static boolean fullyPassable(CalculationContext context, int x, int y, int z) {
        return fullyPassable(context, x, y, z, context.get(x, y, z));
    }

    static boolean fullyPassable(CalculationContext context, int x, int y, int z, BlockState state) {
        if (context.altoActive && context.altoAvoidsWalkThrough(x, y, z)) {
            return false;
        }
        return context.precomputedData.fullyPassable(context.bsi, x, y, z, state);
    }

    static boolean fullyPassable(IPlayerContext ctx, BlockPos pos) {
        if (AltoClefSettings.getInstance().shouldAvoidWalkThroughForce(pos)) {
            return false;
        }
        BlockState state = ctx.world().getBlockState(pos);
        Ternary fullyPassable = fullyPassableBlockState(state);
        if (fullyPassable == YES) {
            return true;
        }
        if (fullyPassable == NO) {
            return false;
        }
        return state.isPathfindable(PathComputationType.LAND);
    }

    // is the column beside a straight lane, on the side the player leans to, free of anything solid at feet and head height.
    // x y z is the lane cell, lean is where the player stands relative to its centre. a diagonal has no side to speak of
    static boolean sideOpen(IPlayerContext ctx, int x, int y, int z, int dirX, int dirZ, double leanX, double leanZ) {
        int[] side = SprintPolicy.sideStep(dirX, dirZ, leanX, leanZ);
        if (side[0] == 0 && side[1] == 0) {
            return true;
        }
        for (int up = 0; up < 2; up++) {
            BlockPos pos = new BlockPos(x + side[0], y + up, z + side[1]);
            BlockState state = ctx.world().getBlockState(pos);
            // lava, fire and a cobweb have no collision either, but nobody wants a hitbox in them
            if (!state.getCollisionShape(ctx.world(), pos).isEmpty() || avoidWalkingInto(state) || !state.getFluidState().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * params retained for backwards compatibility
     */
    static boolean fullyPassablePosition(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        return state.isPathfindable(PathComputationType.LAND);
    }

    static boolean isReplaceable(int x, int y, int z, BlockState state, BlockStateInterface bsi) {
        // for MovementTraverse and MovementAscend
        // block double plant defaults to true when the block doesn't match, so don't need to check that case
        // all other overrides just return true or false
        // the only case to deal with is snow
        /*
         *  public boolean isReplaceable(IBlockAccess worldIn, BlockPos pos)
         *     {
         *         return ((Integer)worldIn.getBlockState(pos).getValue(LAYERS)).intValue() == 1;
         *     }
         */
        Block block = state.getBlock();
        if (block instanceof AirBlock) {
            // early return for common cases hehe
            return true;
        }
        if (block instanceof SnowLayerBlock) {
            // as before, default to true (mostly because it would otherwise make long distance pathing through snowy biomes impossible)
            if (!bsi.worldContainsLoadedChunk(x, z)) {
                return true;
            }
            return state.getValue(SnowLayerBlock.LAYERS) == 1;
        }
        if (block == Blocks.LARGE_FERN || block == Blocks.TALL_GRASS) {
            return true;
        }
        return state.canBeReplaced();
    }

    static boolean isDoorPassable(BlockState state, Direction side) {
        boolean open = state.getValue(DoorBlock.OPEN);
        Direction closedFacing = state.getValue(HorizontalDirectionalBlock.FACING);
        Direction openFacing = state.getValue(DoorBlock.HINGE) == DoorHingeSide.LEFT
                ? closedFacing.getClockWise()
                : closedFacing.getCounterClockWise();

        return side != (open ? openFacing : closedFacing);
    }

    static boolean isGatePassable(IPlayerContext ctx, BlockPos gatePos, BlockPos playerPos) {
        if (playerPos.equals(gatePos)) {
            return false;
        }

        BlockState state = BlockStateInterface.get(ctx, gatePos);
        if (!(state.getBlock() instanceof FenceGateBlock)) {
            return true;
        }

        return state.getValue(FenceGateBlock.OPEN);
    }

    static boolean avoidWalkingInto(BlockState state) {
        Block block = state.getBlock();
        return !state.getFluidState().isEmpty()
                || (block == Blocks.MAGMA_BLOCK && !Baritone.settings().allowWalkOnMagmaBlocks.value)
                || block == Blocks.CACTUS
                || block == Blocks.SWEET_BERRY_BUSH
                || block instanceof BaseFireBlock
                || (block instanceof EndPortalFrameBlock && AltoClefSettings.getInstance().hasEndPortalFrameRules())
                || block == Blocks.END_PORTAL
                || block == Blocks.COBWEB
                || block == Blocks.BUBBLE_COLUMN;
    }

    // can a swimmer's box pass through this block. not canWalkThrough: that one turns down water with more water
    // over it (it's asking about standing, not floating) and flowing water. plants in the water are fine,
    // a waterlogged anything is as solid as its shape
    static boolean swimmerFits(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.WATER || block == Blocks.KELP || block == Blocks.KELP_PLANT || block == Blocks.SEAGRASS || block == Blocks.TALL_SEAGRASS) {
            return true;
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        return canWalkThrough(bsi, x, y, z, state) && !avoidWalkingInto(state);
    }

    /**
     * Can I walk on this block without anything weird happening like me falling
     * through? Includes water because we know that we automatically jump on
     * water
     * <p>
     * If changing something in this function remember to also change it in precomputed data
     *
     * @param bsi   Block state provider
     * @param x     The block's x position
     * @param y     The block's y position
     * @param z     The block's z position
     * @param state The state of the block at the specified location
     * @return Whether or not the specified block can be walked on
     */
    static boolean canWalkOn(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        AltoClefSettings alto = AltoClefSettings.getInstance();
        if (alto.canWalkOnForce(x, y, z)) {
            return true;
        }
        if (alto.shouldAvoidWalkThroughForce(x, y + 1, z)) {
            return false; // standing here would put you somewhere you're not allowed
        }
        Ternary canWalkOn = canWalkOnBlockState(state);
        if (canWalkOn == YES) {
            return true;
        }
        if (canWalkOn == NO) {
            return false;
        }
        return canWalkOnPosition(bsi, x, y, z, state);
    }

    static Ternary canWalkOnBlockState(BlockState state) {
        Block block = state.getBlock();
        // not a full block, so isBlockNormalCube says no. you can very much stand on one though. altoclef only, and
        // PrecomputedData is keyed on the toggle, stock soprano never routes over a frame
        if (block instanceof EndPortalFrameBlock && AltoClefSettings.getInstance().hasEndPortalFrameRules()) {
            return YES;
        }
        if (block == Blocks.END_PORTAL && AltoClefSettings.getInstance().isCanWalkOnEndPortal()) {
            return YES; // PrecomputedData is keyed on this toggle too
        }
        if (isBlockNormalCube(state) && (block != Blocks.MAGMA_BLOCK || Baritone.settings().allowWalkOnMagmaBlocks.value) && block != Blocks.BUBBLE_COLUMN && block != Blocks.HONEY_BLOCK) {
            return YES;
        }
        if (block instanceof AzaleaBlock) {
            return YES;
        }
        if (block == Blocks.LADDER || (isClimbable(block) && Baritone.settings().allowVines.value)) { // TODO reconsider this
            return YES;
        }
        if (block == Blocks.FARMLAND || block == Blocks.DIRT_PATH || block == Blocks.SOUL_SAND) {
            return YES;
        }
        if (block == Blocks.ENDER_CHEST || block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
            return YES;
        }
        if (block == Blocks.GLASS || block instanceof StainedGlassBlock) {
            return YES;
        }
        if (block instanceof StairBlock) {
            return YES;
        }
        if (isWater(state)) {
            return MAYBE;
        }
        if (MovementHelper.isLava(state) && Baritone.settings().assumeWalkOnLava.value) {
            return MAYBE;
        }
        if (block instanceof SlabBlock) {
            if (!Baritone.settings().allowWalkOnBottomSlab.value) {
                if (state.getValue(SlabBlock.TYPE) != SlabType.BOTTOM) {
                    return YES;
                }
                return NO;
            }
            return YES;
        }
        return NO;
    }

    static boolean canWalkOnPosition(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        Block block = state.getBlock();
        if (isWater(state)) {
            // since this is called literally millions of times per second, the benefit of not allocating millions of useless "pos.up()"
            // BlockPos s that we'd just garbage collect immediately is actually noticeable. I don't even think its a decrease in readability
            BlockState upState = bsi.get0(x, y + 1, z);
            Block up = upState.getBlock();
            if (up == Blocks.LILY_PAD || up instanceof CarpetBlock) {
                return true;
            }
            if (MovementHelper.isFlowing(x, y, z, state, bsi) || upState.getFluidState().getType() == Fluids.FLOWING_WATER) {
                // the only scenario in which we can walk on flowing water is if it's under still water with jesus off
                return isWater(upState) && !Baritone.settings().assumeWalkOnWater.value;
            }
            // if assumeWalkOnWater is on, we can only walk on water if there isn't water above it
            // if assumeWalkOnWater is off, we can only walk on water if there is water above it
            return isWater(upState) ^ Baritone.settings().assumeWalkOnWater.value;
        }

        if (MovementHelper.isLava(state) && !MovementHelper.isFlowing(x, y, z, state, bsi) && Baritone.settings().assumeWalkOnLava.value) { // if we get here it means that assumeWalkOnLava must be true, so put it last
            return true;
        }

        return false; // If we don't recognise it then we want to just return false to be safe.
    }

    static boolean canWalkOn(CalculationContext context, int x, int y, int z, BlockState state) {
        if (context.altoActive) {
            if (context.altoForcesWalkOn(x, y, z)) {
                return true;
            }
            if (context.altoAvoidsWalkThrough(x, y + 1, z)) {
                return false;
            }
        }
        return context.precomputedData.canWalkOn(context.bsi, x, y, z, state);
    }

    static boolean canWalkOn(CalculationContext context, int x, int y, int z) {
        return canWalkOn(context, x, y, z, context.get(x, y, z));
    }

    static boolean canWalkOn(IPlayerContext ctx, BetterBlockPos pos, BlockState state) {
        return canWalkOn(new BlockStateInterface(ctx), pos.x, pos.y, pos.z, state);
    }

    static boolean canWalkOn(IPlayerContext ctx, BlockPos pos) {
        return canWalkOn(new BlockStateInterface(ctx), pos.getX(), pos.getY(), pos.getZ());
    }

    static boolean canWalkOn(IPlayerContext ctx, BetterBlockPos pos) {
        return canWalkOn(new BlockStateInterface(ctx), pos.x, pos.y, pos.z);
    }

    static boolean canWalkOn(BlockStateInterface bsi, int x, int y, int z) {
        return canWalkOn(bsi, x, y, z, bsi.get0(x, y, z));
    }

    static boolean canUseFrostWalker(CalculationContext context, BlockState state) {
        return context.frostWalker != 0
                && state == FrostedIceBlock.meltsInto()
                && state.getValue(LiquidBlock.LEVEL) == 0;
    }

    static boolean canUseFrostWalker(IPlayerContext ctx, BlockPos pos) {
        boolean hasFrostWalker = false;
        OUTER: for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemEnchantments itemEnchantments = ctx
                .player()
                .getItemBySlot(slot)
                .getEnchantments();
            for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                if (enchant.is(Enchantments.FROST_WALKER)) {
                    hasFrostWalker = true;
                    break OUTER;
                }
            }
        }
        BlockState state = BlockStateInterface.get(ctx, pos);
        return hasFrostWalker
                && state == FrostedIceBlock.meltsInto()
                && state.getValue(LiquidBlock.LEVEL) == 0;
    }

    /**
     * If movements make us stand/walk on this block, will it have a top to walk on?
     */
    static boolean mustBeSolidToWalkOn(CalculationContext context, int x, int y, int z, BlockState state) {
        Block block = state.getBlock();
        if (isClimbable(block)) {
            return false;
        }
        if (!state.getFluidState().isEmpty()) {
            // used for frostwalker so only includes blocks where we are still on ground when leaving them to any side
            if (block instanceof SlabBlock) {
                if (state.getValue(SlabBlock.TYPE) != SlabType.BOTTOM) {
                    return true;
                }
            } else if (block instanceof StairBlock) {
                if (state.getValue(StairBlock.HALF) == Half.TOP) {
                    return true;
                }
                StairsShape shape = state.getValue(StairBlock.SHAPE);
                if (shape == StairsShape.INNER_LEFT || shape == StairsShape.INNER_RIGHT) {
                    return true;
                }
            } else if (block instanceof TrapDoorBlock) {
                if (!state.getValue(TrapDoorBlock.OPEN) && state.getValue(TrapDoorBlock.HALF) == Half.TOP) {
                    return true;
                }
            } else if (block == Blocks.SCAFFOLDING) {
                return true;
            } else if (block instanceof LeavesBlock) {
                return true;
            }
            if (context.assumeWalkOnWater) {
                return false;
            }
            Block blockAbove = context.getBlock(x, y + 1, z);
            if (blockAbove instanceof LiquidBlock) {
                return false;
            }
        }
        return true;
    }

    static boolean canPlaceAgainst(BlockStateInterface bsi, int x, int y, int z) {
        return canPlaceAgainst(bsi, x, y, z, bsi.get0(x, y, z));
    }

    static boolean canPlaceAgainst(CalculationContext context, int x, int y, int z) {
        return canPlaceAgainst(context, x, y, z, context.get(x, y, z));
    }

    static boolean canPlaceAgainst(CalculationContext context, int x, int y, int z, BlockState state) {
        // precomputed version of the one below. ascend asks this five times per direction, mostly about air
        return context.bsi.worldBorder.canPlaceAt(x, z)
                && context.precomputedData.canPlaceAgainst(context.bsi, state)
                && !(context.altoActive && context.isPlaceProtected(x, y, z));
    }

    static boolean canPlaceAgainst(BlockStateInterface bsi, BlockPos pos) {
        return canPlaceAgainst(bsi, pos.getX(), pos.getY(), pos.getZ());
    }

    static boolean canPlaceAgainst(IPlayerContext ctx, BlockPos pos) {
        return canPlaceAgainst(new BlockStateInterface(ctx), pos);
    }

    static boolean canPlaceAgainst(BlockStateInterface bsi, int x, int y, int z, BlockState state) {
        if (!bsi.worldBorder.canPlaceAt(x, z) || AltoClefSettings.getInstance().shouldAvoidPlacingAt(x, y, z)) {
            return false;
        }
        // can we look at the center of a side face of this block and likely be able to place?
        // (thats how this check is used)
        // therefore dont include weird things that we technically could place against (like carpet) but practically can't
        return isBlockNormalCube(state) || state.getBlock() == Blocks.GLASS || state.getBlock() instanceof StainedGlassBlock;
    }

    // would an unsneaked right click do something to this block instead of placing against it. the click goes to the
    // block first and the item only gets a turn if the block shrugs. block entities cover chests, furnaces, shelves,
    // pots and friends, the menu provider catches the menus that have no block entity, the rest is the long tail of
    // things that open, flip, eat, ring or blow up. MovementTraverse keeps its own shorter list, its god bridge cost
    // was tuned on that one
    static boolean opensWhenClicked(BlockState state, Level level, BlockPos pos) {
        if (state.hasBlockEntity() || state.getMenuProvider(level, pos) != null) {
            return true;
        }
        Block block = state.getBlock();
        return block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock
                || block instanceof ButtonBlock || block instanceof LeverBlock || block instanceof BedBlock
                || block instanceof AnvilBlock || block instanceof CakeBlock || block instanceof CandleCakeBlock
                || block instanceof AbstractCauldronBlock || block instanceof DiodeBlock || block instanceof FlowerPotBlock
                || clicky(block);
    }

    // a constant Set of these would make loading MovementHelper pull in Blocks, and the plain unit tests can't have that
    private static boolean clicky(Block block) {
        return block == Blocks.CRAFTING_TABLE || block == Blocks.CARTOGRAPHY_TABLE || block == Blocks.SMITHING_TABLE
                || block == Blocks.LOOM || block == Blocks.GRINDSTONE || block == Blocks.STONECUTTER || block == Blocks.NOTE_BLOCK
                || block == Blocks.RESPAWN_ANCHOR || block == Blocks.COMPOSTER || block == Blocks.TNT || block == Blocks.BELL
                || block == Blocks.DAYLIGHT_DETECTOR || block == Blocks.JUKEBOX || block == Blocks.LECTERN
                || block == Blocks.DRAGON_EGG || block == Blocks.REDSTONE_WIRE;
    }

    /**
     * Can we climb up this block by pressing space while inside it?
     * Also doubles as "If I start a movement on this, can weird things happen?"
     * because movements can end/start on these blocks despite them not being canWalkOn.
     */
    // the sneaking back place gets away with these because sneak turns the click into a placement. an unsneaked click
    // would open the thing and walk us off the edge. block entities cover chests, furnaces, barrels and friends.
    // an iron door just sits there when clicked, so that one's a placement
    public static boolean opensWhenClicked(BlockState state) {
        Block block = state.getBlock();
        return state.hasBlockEntity() || block == Blocks.CRAFTING_TABLE || block == Blocks.CARTOGRAPHY_TABLE
                || block == Blocks.SMITHING_TABLE || block == Blocks.LOOM || block == Blocks.NOTE_BLOCK || block == Blocks.RESPAWN_ANCHOR
                || DoorBlock.isWoodenDoor(state) || block instanceof FenceGateBlock
                || (block instanceof TrapDoorBlock && block != Blocks.IRON_TRAPDOOR)
                || block instanceof ButtonBlock || block instanceof LeverBlock || block instanceof RepeaterBlock;
    }

    static boolean isClimbable(Block block) {
        return block == Blocks.LADDER
            || block == Blocks.VINE
            || block == Blocks.WEEPING_VINES
            || block == Blocks.WEEPING_VINES_PLANT
            || block == Blocks.TWISTING_VINES
            || block == Blocks.TWISTING_VINES_PLANT;
    }

    // vanilla digs 5x slower with your eyes in water and another 5x slower off the ground. getMiningDurationTicks is
    // cached per block and has no idea where we stand, so this is applied per movement on top of it, at the square
    // the player is in while breaking. only ever inflates, so it can't strand the planner
    static double waterBreakMultiplier(CalculationContext context, int srcX, int srcY, int srcZ) {
        double setting = context.waterBreakCostMultiplier;
        if (setting <= 1) {
            return 1;
        }
        // it's the eyes (the block above our feet) that have to be under for the 5x, not the feet. a dry square with water
        // over our head is just as slow. two lookups and out, which is every move that isn't in or under water
        boolean feetWet = isWater(context.get(srcX, srcY, srcZ));
        boolean eyesWet = isWater(context.get(srcX, srcY + 1, srcZ));
        if (!feetWet && !eyesWet) {
            return 1;
        }
        // wading with your head dry is not slowed down, floating is
        // canWalkOn says yes to water with more water on top (baritone calls that wading), but vanilla isn't on the ground there
        BlockState below = context.get(srcX, srcY - 1, srcZ);
        boolean floating = isWater(below) || !canWalkOn(context, srcX, srcY - 1, srcZ, below);
        double mult = eyesWet ? setting : 1;
        return floating ? mult * 5 : mult;
    }

    // hardness is the break part of a movement's cost. zero (nothing to break) and COST_INF are left alone
    // the water slowdown is a slowdown of the swinging, the wait after a break is the same wait wherever you stand, so
    // that one goes on after the multiplier
    static double waterBreakCost(CalculationContext context, int srcX, int srcY, int srcZ, double hardness) {
        if (hardness <= 0 || hardness >= COST_INF) {
            return hardness;
        }
        double idle = isMultiTickBreak(context, hardness) ? context.breakIdleTicks : 0;
        return hardness * waterBreakMultiplier(context, srcX, srcY, srcZ) + idle;
    }

    // the wait after the last break of a movement is spent doing the next thing (walking, falling, jumping), so that much
    // of it is not paid for twice. rawHardness is what getMiningDurationTicks said, before waterBreakCost put the wait on
    static double breakWaitCredit(CalculationContext context, double rawHardness, double overlapTicks) {
        return rawHardness > 0 && isMultiTickBreak(context, rawHardness) ? Math.min(context.breakIdleTicks, overlapTicks) : 0;
    }

    // getMiningDurationTicks is one tick for a break that is over on the first click and more than that for anything
    // else, so if what's left after the penalty is more than that one tick it was a break that has to be waited out
    static boolean isMultiTickBreak(CalculationContext context, double hardness) {
        return hardness - context.breakBlockAdditionalCost > 1.5;
    }

    static double getMiningDurationTicks(CalculationContext context, int x, int y, int z, boolean includeFalling) {
        return getMiningDurationTicks(context, x, y, z, context.get(x, y, z), includeFalling);
    }

    // what stands in the way of the moves out of a start a search got nowhere from, for the log: the feet and head block of
    // each side and the blocks over and under, each with its break ticks or why it cannot be broken. a search from y -1 in
    // deepslate with a diamond pickaxe went nowhere for 170 game minutes and the log said only "0.0 blocks" (the runner, 3 Nov
    // 2026, attempt 91)
    static String blockedReport(CalculationContext context, int x, int y, int z) {
        StringBuilder sb = new StringBuilder();
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        String[] names = {"east", "west", "south", "north"};
        for (int i = 0; i < sides.length; i++) {
            sb.append(names[i]).append(' ').append(breakWhy(context, x + sides[i][0], y, z + sides[i][1]))
                    .append('/').append(breakWhy(context, x + sides[i][0], y + 1, z + sides[i][1])).append(", ");
        }
        sb.append("up ").append(breakWhy(context, x, y + 2, z)).append(", down ").append(breakWhy(context, x, y - 1, z));
        sb.append(", allowBreak ").append(context.allowBreak);
        return sb.toString();
    }

    private static String breakWhy(CalculationContext context, int x, int y, int z) {
        BlockState state = context.get(x, y, z);
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (canWalkThrough(context, x, y, z, state)) return id + "=open";
        if (!state.getFluidState().isEmpty()) return id + "=fluid";
        if (context.breakCostMultiplierAt(x, y, z, state) >= COST_INF) return id + (context.isBreakProtected(x, y, z) ? "=protected" : "=nobreak");
        if (avoidBreaking(context, x, y, z, state)) return id + "=avoid";
        if (context.toolSet.getStrVsBlock(state) <= 0) return id + "=unbreakable";
        return id + "=" + Math.round(getMiningDurationTicks0(context, x, y, z, state, false));
    }

    static double getMiningDurationTicks(CalculationContext context, int x, int y, int z, BlockState state, boolean includeFalling) {
        long[] keys = includeFalling ? context.miningKeysFalling : context.miningKeys;
        if (keys == null || context.miningOwner != Thread.currentThread()) {
            return getMiningDurationTicks0(context, x, y, z, state, includeFalling);
        }
        // y is shifted so it's never negative, which means the all ones key would need y=4095. no
        long key = ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y - context.minY) & 0xFFF);
        int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> (64 - context.miningCacheBits));
        double[] vals = includeFalling ? context.miningValsFalling : context.miningVals;
        if (keys[slot] == key) {
            return vals[slot];
        }
        double result = getMiningDurationTicks0(context, x, y, z, state, includeFalling);
        keys[slot] = key;
        vals[slot] = result;
        return result;
    }

    static double getMiningDurationTicks0(CalculationContext context, int x, int y, int z, BlockState state, boolean includeFalling) {
        Block block = state.getBlock();
        if (!canWalkThrough(context, x, y, z, state)) {
            if (!state.getFluidState().isEmpty()) {
                return COST_INF;
            }
            double mult = context.breakCostMultiplierAt(x, y, z, state);
            if (mult >= COST_INF) {
                return COST_INF;
            }
            if (avoidBreaking(context, x, y, z, state)) {
                return COST_INF;
            }
            double strVsBlock = context.toolSet.getStrVsBlock(state);
            if (strVsBlock <= 0) {
                return COST_INF;
            }
            // vanilla adds the per tick progress until it reaches 1, so the break lands on a whole tick and 7.5 ticks of
            // swinging is 8. the click that starts a break does no damage and takes a tick of its own. a break that is
            // done on that first click (progress of 1 or more a tick) is just the one tick
            double swing = 1 / strVsBlock;
            double result = swing <= 1 ? 1 : Math.ceil(swing - 1e-9) + 1;
            result += context.breakBlockAdditionalCost;
            result *= mult;
            if (includeFalling) {
                BlockState above = context.get(x, y + 1, z);
                if (FallingColumn.isFalling(above)) {
                    // the whole stack comes down the hole we just made, so it gets mined too, one block at a time. and
                    // nobody swings at a block that is still on its way down, so every block waits its turn to land
                    double stack = getMiningDurationTicks(context, x, y + 1, z, above, true);
                    result += stack > 0 && stack < COST_INF ? stack + FallingColumn.LAND_WAIT_TICKS : stack;
                }
            }
            return result;
        }
        return 0; // we won't actually mine it, so don't check fallings above
    }

    static boolean isBottomSlab(BlockState state) {
        return state.getBlock() instanceof SlabBlock
                && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM;
    }

    /**
     * AutoTool for a specific block
     *
     * @param ctx The player context
     * @param b   the blockstate to mine
     */
    public static void switchToBestToolFor(IPlayerContext ctx, BlockState b) {
        switchToBestToolFor(ctx, b, new ToolSet(ctx.player()), BaritoneAPI.getSettings().preferSilkTouch.value);
    }

    /**
     * AutoTool for a specific block with precomputed ToolSet data
     *
     * @param ctx The player context
     * @param b   the blockstate to mine
     * @param ts  previously calculated ToolSet
     */
    static void switchToBestToolFor(IPlayerContext ctx, BlockState b, ToolSet ts, boolean preferSilkTouch) {
        if (AltoClefSettings.getInstance().isInteractionPaused()) {
            return; // paused means hands off the hotbar
        }
        if (Baritone.settings().autoTool.value && !Baritone.settings().assumeExternalAutoTool.value) {
            // the state, not the block: the Block overload rebuilds a default state and loses what a forced tool predicate looks at
            ctx.player().getInventory().setSelectedSlot(ts.getBestSlot(b, preferSilkTouch, false));
        }
    }

    static void moveTowards(IPlayerContext ctx, MovementState state, BlockPos pos) {
        state.setTarget(new MovementTarget(
                RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
                        VecUtils.getBlockPosCenter(pos),
                        ctx.playerRotations()).withPitch(ctx.playerRotations().getPitch()),
                false
        )).setInput(Input.MOVE_FORWARD, true);
    }

    static void moveTowardsWithoutRotation(IPlayerContext ctx, MovementState state, float idealYaw) {
        MovementOption.getOptions(
                Mth.sin(ctx.playerRotations().getYaw() * DEG_TO_RAD_F),
                Mth.cos(ctx.playerRotations().getYaw() * DEG_TO_RAD_F),
                Baritone.settings().allowSprint.value
        ).min(Comparator.comparing(option -> option.distanceToSq(
                Mth.sin(idealYaw * DEG_TO_RAD_F),
                Mth.cos(idealYaw * DEG_TO_RAD_F)
        ))).ifPresent(selection -> selection.setInputs(state));
    }

    static void moveTowardsWithoutRotation(IPlayerContext ctx, MovementState state, BlockPos dest) {
        float idealYaw = RotationUtils.calcRotationFromVec3d(
                ctx.playerHead(),
                VecUtils.getBlockPosCenter(dest),
                ctx.playerRotations()
        ).getYaw();
        moveTowardsWithoutRotation(ctx, state, idealYaw);
    }

    static void moveTowardsWithSlightRotation(IPlayerContext ctx, MovementState state, BlockPos dest) {
        float idealYaw = RotationUtils.calcRotationFromVec3d(
                ctx.playerHead(),
                VecUtils.getBlockPosCenter(dest),
                ctx.playerRotations()
        ).getYaw();
        float distance = Rotation.yawDistanceFromOffset(ctx.playerRotations().getYaw(), idealYaw) % 45f;
        float newYaw = distance > 0f ?
                distance > 22.5f ? distance - 45f : distance :
                distance < -22.5f ? distance + 45f : distance;
        state.setTarget(new MovementTarget(new Rotation(
                ctx.playerRotations().getYaw() - newYaw,
                ctx.playerRotations().getPitch()
        ), true));
        moveTowardsWithoutRotation(ctx, state, idealYaw);
    }

    /**
     * Returns whether or not the specified block is
     * water, regardless of whether or not it is flowing.
     *
     * @param state The block state
     * @return Whether or not the block is water
     */
    static boolean isWater(BlockState state) {
        Fluid f = state.getFluidState().getType();
        if (f == Fluids.WATER || f == Fluids.FLOWING_WATER) {
            return true;
        }
        // altoclef's swim through lava pretends it's water. air and friends never make it past the first two compares
        return (f == Fluids.LAVA || f == Fluids.FLOWING_LAVA) && AltoClefSettings.getInstance().canSwimThroughLava();
    }

    /**
     * Returns whether or not the block at the specified pos is
     * water, regardless of whether or not it is flowing.
     *
     * @param ctx The player context
     * @param bp  The block pos
     * @return Whether or not the block is water
     */
    static boolean isWater(IPlayerContext ctx, BlockPos bp) {
        return isWater(BlockStateInterface.get(ctx, bp));
    }

    static boolean isLava(BlockState state) {
        Fluid f = state.getFluidState().getType();
        return f == Fluids.LAVA || f == Fluids.FLOWING_LAVA;
    }

    /**
     * Returns whether or not the specified pos has a liquid
     *
     * @param ctx The player context
     * @param p   The pos
     * @return Whether or not the block is a liquid
     */
    static boolean isLiquid(IPlayerContext ctx, BlockPos p) {
        return isLiquid(BlockStateInterface.get(ctx, p));
    }

    static boolean isLiquid(BlockState blockState) {
        return !blockState.getFluidState().isEmpty();
    }

    static boolean possiblyFlowing(BlockState state) {
        FluidState fluidState = state.getFluidState();
        return fluidState.getType() instanceof FlowingFluid
                && fluidState.getType().getAmount(fluidState) != 8;
    }

    static boolean isFlowing(int x, int y, int z, BlockState state, BlockStateInterface bsi) {
        FluidState fluidState = state.getFluidState();
        if (!(fluidState.getType() instanceof FlowingFluid)) {
            return false;
        }
        if (fluidState.getType().getAmount(fluidState) != 8) {
            return true;
        }
        return possiblyFlowing(bsi.get0(x + 1, y, z))
                || possiblyFlowing(bsi.get0(x - 1, y, z))
                || possiblyFlowing(bsi.get0(x, y, z + 1))
                || possiblyFlowing(bsi.get0(x, y, z - 1));
    }

    static boolean isBlockNormalCube(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof BambooStalkBlock
                || block instanceof MovingPistonBlock
                || block instanceof ScaffoldingBlock
                || block instanceof ShulkerBoxBlock
                || block instanceof PointedDripstoneBlock
                || block instanceof AmethystClusterBlock) {
            return false;
        }
        try {
            return Block.isShapeFullBlock(state.getCollisionShape(null, null));
        } catch (Exception ignored) {
            // if we can't get the collision shape, assume it's bad and add to blocksToAvoid
        }
        return false;
    }

    static boolean openDoors(IPlayerContext ctx, MovementState state, BetterBlockPos from, BetterBlockPos to) {
        Direction direction = Stream.of(Direction.values())
                .filter(d -> from.relative(d).equals(to))
                .findFirst()
                .get();

        for (BetterBlockPos pos : new BetterBlockPos[]{from, to, from.above(), to.above()}) {
            Direction side = pos.equals(to) || pos.equals(to.above()) ? direction : direction.getOpposite();
            BlockState door = BlockStateInterface.get(ctx, pos);
            if (DoorBlock.isWoodenDoor(door) && !isDoorPassable(door, side)) {
                state.setTarget(new MovementState.MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.calculateBlockCenter(ctx.world(), pos), ctx.playerRotations()), true))
                        .setInput(Input.CLICK_RIGHT, true);
                return false;
            }
        }
        return true;
    }

    // every movement wants this one: the block is for the path in front of us, so protected throwaways are fair game
    public static PlaceResult attemptToPlaceABlock(MovementState state, IBaritone baritone, BlockPos placeAt, boolean preferDown, boolean wouldSneak) {
        return attemptToPlaceABlock(state, baritone, placeAt, preferDown, wouldSneak, true, true);
    }

    // once per 5 seconds (NoThrowawayLogGate), at normal log level: the next run's logs say why we had nothing to place
    // instead of a silent no-op. the guard used to live on the movement, and every replan makes fresh movements
    public static void logNoThrowaway(MovementState state, IBaritone baritone, BlockPos placeAt) {
        if (!NoThrowawayLogGate.due(System.currentTimeMillis())) {
            return;
        }
        String why = ((Baritone) baritone).getInventoryBehavior().whyNoThrowaway(true, placeAt.getX(), placeAt.getY(), placeAt.getZ());
        Helper.HELPER.logDirect("can't place a block at " + placeAt.getX() + " " + placeAt.getY() + " " + placeAt.getZ() + " mid movement: " + why);
    }

    // nothing in the bag we're allowed to put there: the movement can't finish, and must not say it's about to place
    private static PlaceResult noThrowaway(MovementState state, IBaritone baritone, BlockPos placeAt, boolean allowProtected) {
        Helper.HELPER.logDebug("bb pls get me some blocks. dirt, netherrack, cobble");
        if (allowProtected) { // backfill shrugging at an empty bag isn't news
            logNoThrowaway(state, baritone, placeAt);
        }
        state.setStatus(MovementStatus.UNREACHABLE);
        return PlaceResult.NO_OPTION;
    }

    // would the server say no because somebody is standing in the cell. our own body is left out (the movements already
    // back off for that, see ascend), it's the zombie that wandered into the bridge we're after
    static boolean entityInTheWay(IPlayerContext ctx, BlockPos pos) {
        return !ctx.world().isUnobstructed(ctx.player(), Shapes.block().move(pos.getX(), pos.getY(), pos.getZ()));
    }

    // what's in the cell and, if it's a critter we may punch, which one. target is null unless cell is HIT
    record Obstruction(HitBlocker.Cell cell, LivingEntity target) {
    }

    // the same list the server walks in isUnobstructed (alive, blocksBuilding, not riding with us, actually overlapping),
    // just sorted into punchable and not. hit false says nobody is punchable, for callers that must never swing. same
    // when we can't swing right now: the old wait and give up is better than burning the hit clock on swings that
    // never go out
    static Obstruction obstruction(IPlayerContext ctx, BlockPos pos, boolean hit) {
        LocalPlayer player = ctx.player();
        hit = hit && !player.isHandsBusy() && !AltoClefSettings.getInstance().isInteractionPaused();
        VoxelShape cell = Shapes.block().move(pos.getX(), pos.getY(), pos.getZ());
        int blockers = 0;
        int stubborn = 0;
        int alive = 0;
        LivingEntity target = null;
        for (Entity e : ctx.world().getEntities(player, cell.bounds())) {
            if (e.isRemoved() || !e.blocksBuilding || e.isPassengerOfSameVehicle(player)
                    || !Shapes.joinIsNotEmpty(cell, Shapes.create(e.getBoundingBox()), BooleanOp.AND)) {
                continue;
            }
            blockers++;
            if (!hit || !hittableBlocker(ctx, e)) {
                stubborn++;
            } else if (!((LivingEntity) e).isDeadOrDying()) {
                alive++;
                target = (LivingEntity) e;
            }
        }
        HitBlocker.Cell verdict = HitBlocker.cell(blockers, stubborn, alive);
        return new Obstruction(verdict, verdict == HitBlocker.Cell.HIT ? target : null);
    }

    // exact types on purpose: "any animal" would have us slapping hoglins, goats, bees and somebody's horse
    static boolean hittableBlocker(IPlayerContext ctx, Entity e) {
        Class<?> type = e.getClass();
        boolean critter = type == Bat.class || type == Chicken.class || type == Rabbit.class;
        if (!critter) {
            return false;
        }
        double reach = ctx.player().entityInteractionRange();
        // eye to the nearest bit of its box, same as SelfDefenseProcess.reachDistance and the server
        boolean inReach = e.getBoundingBox().distanceToSqr(ctx.player().getEyePosition()) <= reach * reach;
        boolean leashed = e instanceof Leashable leashable && leashable.isLeashed();
        return HitBlocker.hittable(true, e.hasCustomName(), leashed, e.isPassenger() || e.isVehicle(), inReach);
    }

    // straight to the attack packet (LegacyCombat.hit) like BoatTrip and SelfDefenseProcess. not CLICK_LEFT: that only
    // knows blocks. and no looking at it either, the movement is aiming its place click and the attack packet doesn't care
    // where we look
    static void swingAtBlocker(MovementState state, IPlayerContext ctx, LivingEntity target) {
        LocalPlayer player = ctx.player();
        // obstruction already said no target when the hands are busy or alto paused us, this is just belt and braces
        if (target == null || player.isHandsBusy() || AltoClefSettings.getInstance().isInteractionPaused()) {
            return;
        }
        if (state.swingDue(player.getAttackStrengthScale(0.5f), player.tickCount)) {
            LegacyCombat.hit(ctx.minecraft().gameMode, player, target, InteractionHand.MAIN_HAND);
        }
    }

    // the real one. a mob in the cell means the click goes out and nothing happens, forever, so a ready or attempting
    // answer turns into BLOCKED (hold still, no click) for a while and then into UNREACHABLE so the planner goes around.
    // backfill comes through here and must never swing at anything, so only the movement overload punches
    static PlaceResult attemptToPlaceABlock(MovementState state, IBaritone baritone, BlockPos placeAt, boolean preferDown, boolean wouldSneak, boolean allowProtected) {
        return attemptToPlaceABlock(state, baritone, placeAt, preferDown, wouldSneak, allowProtected, false);
    }

    private static PlaceResult attemptToPlaceABlock(MovementState state, IBaritone baritone, BlockPos placeAt, boolean preferDown, boolean wouldSneak, boolean allowProtected, boolean hit) {
        PlaceResult result = findPlace(state, baritone, placeAt, preferDown, wouldSneak, allowProtected);
        if (result != PlaceResult.READY_TO_PLACE && result != PlaceResult.ATTEMPTING) {
            return result;
        }
        Obstruction in = obstruction(baritone.getPlayerContext(), placeAt, hit);
        switch (state.placeInto(in.cell())) {
            case PLACE:
                return result;
            case HIT:
                swingAtBlocker(state, baritone.getPlayerContext(), in.target());
                if (wouldSneak) {
                    state.setInput(Input.SNEAK, true);
                }
                return PlaceResult.BLOCKED;
            case WAIT:
                if (wouldSneak) {
                    state.setInput(Input.SNEAK, true);
                }
                return PlaceResult.BLOCKED;
            default:
                Helper.HELPER.logDebug("something stood in the way of the block at " + placeAt.getX() + " " + placeAt.getY() + " " + placeAt.getZ() + " and wouldn't leave, giving up on this one");
                state.setStatus(MovementStatus.UNREACHABLE);
                return PlaceResult.NO_OPTION;
        }
    }

    private static PlaceResult findPlace(MovementState state, IBaritone baritone, BlockPos placeAt, boolean preferDown, boolean wouldSneak, boolean allowProtected) {
        IPlayerContext ctx = baritone.getPlayerContext();
        Optional<Rotation> direct = RotationUtils.reachable(ctx, placeAt, wouldSneak); // we assume that if there is a block there, it must be replacable
        boolean found = false;
        if (direct.isPresent()) {
            state.setTarget(new MovementTarget(direct.get(), true));
            found = true;
        }
        for (int i = 0; i < 5; i++) {
            BlockPos against1 = placeAt.relative(HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i]);
            if (MovementHelper.canPlaceAgainst(ctx, against1)) {
                if (!((Baritone) baritone).getInventoryBehavior().selectThrowawayForLocation(false, placeAt.getX(), placeAt.getY(), placeAt.getZ(), allowProtected)) { // get ready to place a throwaway block
                    return noThrowaway(state, baritone, placeAt, allowProtected);
                }
                double faceX = (placeAt.getX() + against1.getX() + 1.0D) * 0.5D;
                double faceY = (placeAt.getY() + against1.getY() + 0.5D) * 0.5D;
                double faceZ = (placeAt.getZ() + against1.getZ() + 1.0D) * 0.5D;
                Rotation place = RotationUtils.calcRotationFromVec3d(wouldSneak ? RayTraceUtils.inferSneakingEyePosition(ctx.player()) : ctx.playerHead(), new Vec3(faceX, faceY, faceZ), ctx.playerRotations());
                Rotation actual = baritone.getLookBehavior().getAimProcessor().peekRotation(place);
                HitResult res = RayTraceUtils.rayTraceTowards(ctx.player(), actual, ctx.playerController().getBlockReachDistance(), wouldSneak);
                if (res != null && res.getType() == HitResult.Type.BLOCK && ((BlockHitResult) res).getBlockPos().equals(against1) && ((BlockHitResult) res).getBlockPos().relative(((BlockHitResult) res).getDirection()).equals(placeAt)) {
                    state.setTarget(new MovementTarget(place, true));
                    found = true;

                    if (!preferDown) {
                        // if preferDown is true, we want the last option
                        // if preferDown is false, we want the first
                        break;
                    }
                }
            }
        }
        if (ctx.getSelectedBlock().isPresent()) {
            BlockPos selectedBlock = ctx.getSelectedBlock().get();
            Direction side = ((BlockHitResult) ctx.objectMouseOver()).getDirection();
            // only way for selectedBlock.equals(placeAt) to be true is if it's replaceable
            if (selectedBlock.equals(placeAt) || (MovementHelper.canPlaceAgainst(ctx, selectedBlock) && selectedBlock.relative(side).equals(placeAt))) {
                // the answer used to be dropped on the floor, so a bag with nothing to spare still said ready and the click went
                // out with whatever was in hand (the cobble the recipe was saving, or a pickaxe)
                if (!((Baritone) baritone).getInventoryBehavior().selectThrowawayForLocation(true, placeAt.getX(), placeAt.getY(), placeAt.getZ(), allowProtected)) {
                    return noThrowaway(state, baritone, placeAt, allowProtected);
                }
                if (wouldSneak) {
                    state.setInput(Input.SNEAK, true);
                }
                return PlaceResult.READY_TO_PLACE;
            }
        }
        if (found) {
            if (!((Baritone) baritone).getInventoryBehavior().selectThrowawayForLocation(true, placeAt.getX(), placeAt.getY(), placeAt.getZ(), allowProtected)) {
                return noThrowaway(state, baritone, placeAt, allowProtected);
            }
            if (wouldSneak) {
                state.setInput(Input.SNEAK, true);
            }
            return PlaceResult.ATTEMPTING;
        }
        return PlaceResult.NO_OPTION;
    }

    public enum PlaceResult {
        // BLOCKED: a mob is in the cell, hold still and do not click
        READY_TO_PLACE, ATTEMPTING, NO_OPTION, BLOCKED;
    }

    static boolean isTransparent(Block b) {

        return b instanceof AirBlock ||
                b == Blocks.LAVA ||
                b == Blocks.WATER;
    }

    static List<BetterBlockPos> steppingOnBlocks(IPlayerContext ctx) {
        List<BetterBlockPos> blocks = new ArrayList<>();
        for (byte x = -1; x <= 1; x++) {
            for (byte z = -1; z <= 1; z++) {
                if (ctx.player().getBoundingBox().intersects(Vec3.atLowerCornerOf(ctx.player().blockPosition()).add(x, 0, z), Vec3.atLowerCornerOf(ctx.player().blockPosition()).add(x + 1, 1, z + 1))) {
                    blocks.add(new BetterBlockPos(ctx.player().getBlockX() + x, ctx.player().getBlockY() - 1, ctx.player().getBlockZ() + z));
                }
            }
        }
        return blocks;
    }
}
