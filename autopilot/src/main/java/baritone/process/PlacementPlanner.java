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

package baritone.process;

import baritone.api.behavior.look.IAimProcessor;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.accessor.IBlockItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

// "simulate before you stand": where do we have to be so that one click puts a stair in facing the right way.
// vanilla reads the facing off our yaw and the half off where the ray lands, and the server only knows the rotation we
// last sent it, so the answer is a spot plus an aim, worked out here with the same raytrace and getStateForPlacement
// the click will go through. main thread only, it borrows the player's rotation for the simulation
final class PlacementPlanner {

    // how far each way the real eye may end up from the middle of the block we stop in. the pather gets us into the
    // block, not onto its centre
    static final double EYE_SLOP = 0.2;
    // randomLooking113 is about a degree a tick, so three is plenty of room to the next quadrant
    static final float ANGLE_MARGIN = 3;
    // reach minus this for a hypothetical eye, the real one is never exactly where we guessed
    static final double REACH_MARGIN = 0.3;
    // how far around the cell we look for somewhere to stand. the feet stay inside the box the builder's place scan
    // (searchForPlacables) looks at, cells from 5 below the feet to 1 above, or we'd get there and never look
    static final int HORIZONTAL = 4;
    static final int BELOW = 1;
    static final int ABOVE = 3;
    // feet cells kept per planned cell, the pather picks the cheapest of them
    static final int MAX_FEET = 6;
    // candidates that pass the cheap checks and get the full simulation, per cell. most of the cost is here
    static final int MAX_EVALUATED = 40;

    private final IPlayerContext ctx;

    PlacementPlanner(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    // where to click and from which way, plus the hotbar slot that does it (-1 for a hypothetical stack) and whether the
    // click has to go out sneaking
    record Recipe(BlockPos placeAgainst, Direction side, Vec3 aim, Rotation rot, int slot, boolean sneak) {
        BlockPos target() {
            return placeAgainst.relative(side);
        }
    }

    // a spot to stand in and the click that works from all of it
    record Feet(BetterBlockPos feet, Recipe recipe) {
    }

    // the one shared question: from this eye, which face and aim point make a click with one of these stacks put the
    // right state into cell. aim turns a wanted rotation into the one we'd actually end up with (the aim processor's
    // noise for the real eye, nothing for a guess). margin asks for room to the next quadrant on both angles.
    // stacks are indexed by hotbar slot, nulls and non blocks are skipped. preferAgainst goes first so a cell placeable
    // from two faces doesn't flip between them. sneak is the shift key the click goes out with, and the eye has to be the
    // one that comes with it (eyeHeight)
    Optional<Recipe> plan(BlockStateInterface bsi, Vec3 eye, BlockPos cell, BlockState desired, List<ItemStack> stacks, UnaryOperator<Rotation> aim,
                          boolean margin, double reach, Direction preferAgainst, boolean sneak) {
        return plan(bsi, eye, cell, desired, stacks, aim, margin, reach, preferAgainst, sneak, r -> true);
    }

    // accept gets the last word on each face and aim that works, so the planner can ask for one that also works from
    // the corners instead of giving up on a spot because the first face it found doesn't
    Optional<Recipe> plan(BlockStateInterface bsi, Vec3 eye, BlockPos cell, BlockState desired, List<ItemStack> stacks, UnaryOperator<Rotation> aim,
                          boolean margin, double reach, Direction preferAgainst, boolean sneak, Predicate<Recipe> accept) {
        if (!desired.canSurvive(ctx.world(), cell)) {
            return Optional.empty();
        }
        if (!sneak && BuilderProcess.fluidSource(desired)) {
            // unsneaked the bucket goes into whatever we clicked first, a cauldron or a stair that takes water
            return Optional.empty();
        }
        for (Direction against : facesFirst(preferAgainst)) {
            BlockPos placeAgainst = cell.relative(against);
            BlockState againstState = ctx.world().getBlockState(placeAgainst);
            if (MovementHelper.isReplaceable(placeAgainst.getX(), placeAgainst.getY(), placeAgainst.getZ(), againstState, bsi)) {
                continue;
            }
            if (!sneak && PlaceRules.usable(againstState)) {
                continue; // unsneaked that's a use, the chest opens and nothing gets placed
            }
            VoxelShape shape = againstState.getShape(ctx.world(), placeAgainst);
            if (shape.isEmpty()) {
                continue;
            }
            AABB aabb = shape.bounds();
            Direction side = against.getOpposite();
            for (Vec3 mult : aabbSideMultipliers(against)) {
                Vec3 point = new Vec3(
                        placeAgainst.getX() + aabb.minX * mult.x + aabb.maxX * (1 - mult.x),
                        placeAgainst.getY() + aabb.minY * mult.y + aabb.maxY * (1 - mult.y),
                        placeAgainst.getZ() + aabb.minZ * mult.z + aabb.maxZ * (1 - mult.z));
                Rotation wanted = RotationUtils.calcRotationFromVec3d(eye, point, ctx.playerRotations());
                Rotation rot = aim.apply(wanted);
                // the cheap half first: would a click at the point we're aiming for even come out right. the ray is
                // the expensive half and most spots fail on facing alone
                BlockHitResult guess = new BlockHitResult(point, side, placeAgainst, false);
                if (slotThatPlaces(desired, guess, rot, stacks, margin, sneak) < 0) {
                    continue;
                }
                BlockHitResult hit = clip(eye, rot, reach);
                if (hit == null || !hit.getBlockPos().equals(placeAgainst) || hit.getDirection() != side) {
                    continue;
                }
                int slot = slotThatPlaces(desired, hit, rot, stacks, margin, sneak);
                if (slot >= 0) {
                    Recipe recipe = new Recipe(placeAgainst, side, point, wanted, slot, sneak);
                    if (accept.test(recipe)) {
                        return Optional.of(recipe);
                    }
                }
            }
        }
        return Optional.empty();
    }

    // feet cells near cell that the click works from, nearest to the cell first. empty if none
    List<Feet> planFeet(BlockPos cell, BlockState desired, BlockStateInterface bsi, CellWants wants) {
        ItemStack stack = stackFor(desired);
        if (stack == null) {
            return List.of();
        }
        List<ItemStack> stacks = List.of(stack);
        double reach = ctx.playerController().getBlockReachDistance();
        // only for picking and sorting candidates, each spot gets its real eye in planAt
        double eyeHeight = ctx.player().getEyeHeight(Pose.CROUCHING);
        // a sneaking second go only where a sneak changes anything, it's a whole second simulation per spot
        boolean sneakMatters = PlaceRules.readsSneak(desired.getBlock()) || usableNeighbour(cell);
        List<BetterBlockPos> candidates = new ArrayList<>();
        for (int dx = -HORIZONTAL; dx <= HORIZONTAL; dx++) {
            for (int dy = -BELOW; dy <= ABOVE; dy++) {
                for (int dz = -HORIZONTAL; dz <= HORIZONTAL; dz++) {
                    int x = cell.getX() + dx;
                    int y = cell.getY() + dy;
                    int z = cell.getZ() + dz;
                    if (standable(bsi, wants, cell, x, y, z) && eyeDistSq(cell, x, y, z, eyeHeight) <= (reach + 1) * (reach + 1)) {
                        candidates.add(new BetterBlockPos(x, y, z));
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(f -> eyeDistSq(cell, f.x, f.y, f.z, eyeHeight)));
        List<Feet> out = new ArrayList<>();
        int evaluated = 0;
        for (BetterBlockPos f : candidates) {
            if (out.size() >= MAX_FEET || evaluated++ >= MAX_EVALUATED) {
                break;
            }
            double floorY = standHeight(bsi, f);
            boolean mustSneak = mustSneakAt(bsi, f);
            // unsneaked first, a crouch nobody needs is how the twerking started
            Optional<Recipe> recipe = mustSneak ? Optional.empty() : planAt(bsi, f, floorY, cell, desired, stacks, reach, false);
            if (recipe.isEmpty() && (mustSneak || sneakMatters)) {
                recipe = planAt(bsi, f, floorY, cell, desired, stacks, reach, true);
            }
            recipe.ifPresent(r -> out.add(new Feet(f, r)));
        }
        return out;
    }

    private Optional<Recipe> planAt(BlockStateInterface bsi, BetterBlockPos f, double floorY, BlockPos cell, BlockState desired, List<ItemStack> stacks,
                                    double reach, boolean sneak) {
        double eyeY = floorY + eyeHeight(new Vec3(f.x + 0.5, floorY, f.z + 0.5), sneak);
        Vec3 eye = new Vec3(f.x + 0.5, eyeY, f.z + 0.5);
        return plan(bsi, eye, cell, desired, stacks, r -> r, true, reach - REACH_MARGIN, null, sneak,
                r -> worksFromAllOf(f, eyeY, desired, stacks, r, reach - REACH_MARGIN));
    }

    // hanging off a ladder the sneak is what holds us there (BuilderProcess.hanging), and a spot with no floor yet is the
    // edge of whatever the path put down for us, a sneak is what keeps us on it
    private static boolean mustSneakAt(BlockStateInterface bsi, BetterBlockPos f) {
        return hangable(bsi.get0(f.x, f.y, f.z)) || !MovementHelper.canWalkOn(bsi, f.x, f.y - 1, f.z);
    }

    // the eye we'd really have with our feet here: crouched when sneaking, and crouched anyway under a ceiling too low to
    // stand under, vanilla's updatePlayerPose does that whatever the shift key says
    double eyeHeight(Vec3 feet, boolean sneak) {
        if (sneak) {
            return ctx.player().getEyeHeight(Pose.CROUCHING);
        }
        AABB standing = ctx.player().getDimensions(Pose.STANDING).makeBoundingBox(feet).deflate(1.0E-7);
        // noCollision like canPlayerFitWithinBlocksAndEntitiesWhen, a boat over our head crouches us as much as a slab
        return ctx.player().getEyeHeight(ctx.world().noCollision(ctx.player(), standing) ? Pose.STANDING : Pose.CROUCHING);
    }

    // anything round the cell a click could land on that would rather be used than built on
    boolean usableNeighbour(BlockPos cell) {
        for (Direction d : Direction.values()) {
            if (PlaceRules.usable(ctx.world().getBlockState(cell.relative(d)))) {
                return true;
            }
        }
        return false;
    }

    // where our feet actually end up in that cell. a bottom slab or a stair under us is half a block lower than the
    // cell, and IPlayerContext.playerFeet still calls it this cell, so the eye has to come down with it
    private double standHeight(BlockStateInterface bsi, BetterBlockPos f) {
        BlockPos under = new BlockPos(f.x, f.y - 1, f.z);
        VoxelShape shape = bsi.get0(under).getCollisionShape(ctx.world(), under);
        if (shape.isEmpty()) {
            return f.y; // nothing yet, the throwaway that goes there is a full block
        }
        double top = shape.max(Direction.Axis.Y);
        return top > 1 ? f.y : f.y - 1 + top;
    }

    // the same face and aim point from the corners of where we might actually stop
    private boolean worksFromAllOf(BetterBlockPos f, double eyeY, BlockState desired, List<ItemStack> stacks, Recipe recipe, double reach) {
        for (int i = 0; i < 4; i++) {
            double ox = (i & 1) == 0 ? -EYE_SLOP : EYE_SLOP;
            double oz = (i & 2) == 0 ? -EYE_SLOP : EYE_SLOP;
            Vec3 eye = new Vec3(f.x + 0.5 + ox, eyeY, f.z + 0.5 + oz);
            Rotation rot = RotationUtils.calcRotationFromVec3d(eye, recipe.aim(), ctx.playerRotations());
            BlockHitResult hit = clip(eye, rot, reach);
            if (hit == null || !hit.getBlockPos().equals(recipe.placeAgainst()) || hit.getDirection() != recipe.side()) {
                return false;
            }
            if (slotThatPlaces(desired, hit, rot, stacks, true, recipe.sneak()) < 0) {
                return false;
            }
        }
        return true;
    }

    // somewhere a player fits, with something under it or at least a spot a throwaway can go. a cell the build wants a
    // block in doesn't count as a spot for scaffolding, that's the place and break loop again (roof stairs still get
    // their spots off scaffolding outside the build)
    static boolean standable(BlockStateInterface bsi, CellWants wants, BlockPos cell, int x, int y, int z) {
        if (x == cell.getX() && z == cell.getZ() && (y == cell.getY() || y + 1 == cell.getY())) {
            return false; // our body would be in it
        }
        if (y + 1 == cell.getY() && bsi.get0(cell.getX(), cell.getY() + 1, cell.getZ()).getBlock() instanceof AirBlock) {
            return false; // the scan skips a head height cell with air over it, same rule here
        }
        if (!MovementHelper.canWalkThrough(bsi, x, y, z) || !MovementHelper.canWalkThrough(bsi, x, y + 1, z)) {
            return false;
        }
        if (hangable(bsi.get0(x, y, z))) {
            // hanging off it, sneaking holds us still (BuilderProcess.steady). the click scan uses the real eye wherever
            // in the cell we stopped, so a guess that's a bit off fixes itself
            return true;
        }
        BlockState floor = bsi.get0(x, y - 1, z);
        if (climbable(floor)) {
            // canWalkOn says yes for pathing's sake, but the top of a ladder is a 3/16 plate and we're 0.6 wide, so we
            // drop into the ladder cell and hang there bobbing. scaffolding is no better, the sneak we click with
            // sinks us through it
            return false;
        }
        if (MovementHelper.canWalkOn(bsi, x, y - 1, z)) {
            return true;
        }
        if (x == cell.getX() && y - 1 == cell.getY() && z == cell.getZ()) {
            return false; // standing on the cell we're about to fill
        }
        if (!MovementHelper.isReplaceable(x, y - 1, z, floor, bsi)) {
            return false;
        }
        BlockState want = wants.at(x, y - 1, z, floor);
        return want == null || want.getBlock() instanceof AirBlock;
    }

    // ladders by class too, the tests don't load block tags and a ladder is the one that matters
    static boolean climbable(BlockState state) {
        return state.getBlock() instanceof LadderBlock || state.is(BlockTags.CLIMBABLE);
    }

    // climbable and sneaking holds us still in it. scaffolding is the one where a sneak takes us down instead
    // (LivingEntity.handleOnClimbable leaves it out of the no sliding rule)
    static boolean hangable(BlockState state) {
        return climbable(state) && !(state.getBlock() instanceof ScaffoldingBlock);
    }

    private static double eyeDistSq(BlockPos cell, int x, int y, int z, double eyeHeight) {
        double dx = x + 0.5 - (cell.getX() + 0.5);
        double dy = y + eyeHeight - (cell.getY() + 0.5);
        double dz = z + 0.5 - (cell.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    // the desired block's own item, a stand in for whatever slot will hold it once we get there. null for no item
    private static ItemStack stackFor(BlockState desired) {
        Item vine = BuilderProcess.vineItem(desired.getBlock());
        ItemStack stack = new ItemStack(vine != null ? vine : desired.getBlock().asItem());
        return stack.isEmpty() || !(stack.getItem() instanceof BlockItem) ? null : stack;
    }

    // first slot whose stack would place desired off this hit with this rotation, -1 if none. with margin, the rotation
    // nudged ANGLE_MARGIN each way has to agree too, so aim noise can't tip a stair into the next quadrant
    int slotThatPlaces(BlockState desired, BlockHitResult hit, Rotation rot, List<ItemStack> stacks, boolean margin, boolean sneak) {
        // a bucket has nothing to aim, so no margin either
        boolean pour = BuilderProcess.fluidSource(desired);
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (stack == null || stack.isEmpty() || !(pour ? BuilderProcess.pours(stack) != null : stack.getItem() instanceof BlockItem)) {
                continue;
            }
            if (pour) {
                if (placesRight(desired, hit, rot, stack, sneak)) {
                    return i;
                }
                continue;
            }
            if (!placesRight(desired, hit, rot, stack, sneak)) {
                continue;
            }
            if (margin && !(placesRight(desired, hit, nudge(rot, -1, -1), stack, sneak) && placesRight(desired, hit, nudge(rot, 1, 1), stack, sneak)
                    && placesRight(desired, hit, nudge(rot, -1, 1), stack, sneak) && placesRight(desired, hit, nudge(rot, 1, -1), stack, sneak))) {
                continue;
            }
            return i;
        }
        return -1;
    }

    private static Rotation nudge(Rotation rot, int yaw, int pitch) {
        return new Rotation(rot.getYaw() + yaw * ANGLE_MARGIN, Rotation.clampPitch(rot.getPitch() + pitch * ANGLE_MARGIN));
    }

    // getStateForPlacement with the player turned to rot for the duration, the same trick upstream's
    // hasAnyItemThatWouldPlace does. the state depends on the facing of the player sometimes
    boolean placesRight(BlockState desired, BlockHitResult hit, Rotation rot, ItemStack stack, boolean sneak) {
        BlockState would = simulate(hit, rot, stack, sneak);
        if (would == null) {
            return false;
        }
        if (BuilderProcess.valid(would, desired, true)) {
            return true;
        }
        // the first half of a chest pair can only come out single, same rule as the bcc's fine. the clicked pos and not
        // one out of the face, a click on short grass puts the chest in the grass
        BlockPos partner = BuilderProcess.chestPartner(context(hit, stack, sneak).getClickedPos(), desired);
        return partner != null && BuilderProcess.chestHalfWaiting(would, desired, ctx.world().getBlockState(partner), true);
    }

    // null if vanilla would refuse the click
    BlockState simulate(BlockHitResult hit, Rotation rot, ItemStack stack, boolean sneak) {
        BlockState fluid = BuilderProcess.pours(stack);
        if (fluid != null) {
            return pour(hit, fluid);
        }
        if (!(stack.getItem() instanceof BlockItem)) {
            return null;
        }
        float yaw = ctx.player().getYRot();
        float pitch = ctx.player().getXRot();
        ctx.player().setYRot(rot.getYaw());
        ctx.player().setXRot(rot.getPitch());
        try {
            BlockPlaceContext place = context(hit, stack, sneak);
            if (!place.canPlace()) {
                return null;
            }
            BlockItem item = (BlockItem) stack.getItem();
            place = item.updatePlacementContext(place);
            if (place == null) {
                return null;
            }
            // the block's own getStateForPlacement only ever gives a torch item the standing torch. the wall one is
            // the item's call, off which way we look. only these go through the item though: plain BlockItem's
            // version also checks entities, us included, and we're not even standing where the plan imagines us
            return item instanceof StandingAndWallBlockItem
                    ? ((IBlockItem) item).baritone$placementState(place)
                    : item.getBlock().getStateForPlacement(place);
        } finally {
            ctx.player().setYRot(yaw);
            ctx.player().setXRot(pitch);
        }
    }

    // with the sneak the click will go out with, not whatever the live player happens to be doing at plan time (usually
    // walking). a sneak changes things: a chest clicked on its side only joins the one it was clicked on, unsneaked it
    // grabs any neighbour it likes. PlaceRules.readsSneak has the rest of them
    private BlockPlaceContext context(BlockHitResult hit, ItemStack stack, boolean sneak) {
        return new BlockPlaceContext(ctx.world(), ctx.player(), InteractionHand.MAIN_HAND, stack, hit) {
            @Override
            public boolean isSecondaryUseActive() {
                return sneak;
            }
        };
    }

    // BucketItem.emptyContents with us sneaking: the block we clicked never gets the fluid (no waterlogged stairs by
    // accident), it goes one out of the face, and lands if that cell is air or something the fluid washes away.
    // no BlockPlaceContext, a bucket isn't a block and the click goes through use(), not useOn()
    private BlockState pour(BlockHitResult hit, BlockState fluid) {
        BlockState there = ctx.world().getBlockState(hit.getBlockPos().relative(hit.getDirection()));
        return BuilderProcess.pourInto(there, fluid) ? fluid : null;
    }

    // RayTraceUtils.rayTraceTowards but from any eye, not just the player's. same fluid rule as objectMouseOver, which
    // is what the click goes through (altoclef flips it to source blocks for its bucket tasks)
    private BlockHitResult clip(Vec3 eye, Rotation rot, double reach) {
        Vec3 dir = RotationUtils.calcLookDirectionFromRotation(rot);
        Vec3 end = eye.add(dir.x * reach, dir.y * reach, dir.z * reach);
        HitResult hit = ctx.world().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, RayTraceUtils.fluidHandling, ctx.player()));
        return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK ? block : null;
    }

    static UnaryOperator<Rotation> noise(IAimProcessor aim) {
        return aim::peekRotation;
    }

    static Direction[] facesFirst(Direction first) {
        Direction[] all = Direction.values();
        if (first == null) {
            return all;
        }
        Direction[] ordered = new Direction[all.length];
        ordered[0] = first;
        int i = 1;
        for (Direction d : all) {
            if (d != first) {
                ordered[i++] = d;
            }
        }
        return ordered;
    }

    static Vec3[] aabbSideMultipliers(Direction side) {
        switch (side) {
            case UP:
                return new Vec3[]{new Vec3(0.5, 1, 0.5), new Vec3(0.1, 1, 0.5), new Vec3(0.9, 1, 0.5), new Vec3(0.5, 1, 0.1), new Vec3(0.5, 1, 0.9)};
            case DOWN:
                return new Vec3[]{new Vec3(0.5, 0, 0.5), new Vec3(0.1, 0, 0.5), new Vec3(0.9, 0, 0.5), new Vec3(0.5, 0, 0.1), new Vec3(0.5, 0, 0.9)};
            case NORTH:
            case SOUTH:
            case EAST:
            case WEST:
                double x = side.getStepX() == 0 ? 0.5 : (1 + side.getStepX()) / 2D;
                double z = side.getStepZ() == 0 ? 0.5 : (1 + side.getStepZ()) / 2D;
                return new Vec3[]{new Vec3(x, 0.25, z), new Vec3(x, 0.75, z)};
            default: // null
                throw new IllegalStateException("Unexpected side " + side);
        }
    }

    // what the build wants at a cell, null outside it. the bcc's getSchematic
    @FunctionalInterface
    interface CellWants {
        BlockState at(int x, int y, int z, BlockState current);
    }
}
