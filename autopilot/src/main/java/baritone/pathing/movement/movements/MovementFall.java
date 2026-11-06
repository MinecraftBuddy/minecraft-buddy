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
import baritone.behavior.InventoryBehavior;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.MovementState;
import baritone.pathing.movement.MovementState.MovementTarget;
import baritone.utils.BlockStateInterface;
import baritone.utils.ExperimentalMovement;
import baritone.utils.pathing.MutableMoveResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.WaterFluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class MovementFall extends Movement {

    private static final ItemStack STACK_BUCKET_WATER = new ItemStack(Items.WATER_BUCKET);
    private static final ItemStack STACK_BUCKET_EMPTY = new ItemStack(Items.BUCKET);

    public MovementFall(IBaritone baritone, BetterBlockPos src, BetterBlockPos dest) {
        super(baritone, src, dest, MovementFall.buildPositionsToBreak(src, dest));
    }

    @Override
    public double calculateCost(CalculationContext context) {
        MutableMoveResult result = new MutableMoveResult();
        MovementDescend.cost(context, src.x, src.y, src.z, dest.x, dest.z, result);
        if (result.y != dest.y) {
            return COST_INF; // doesn't apply to us, this position is a descend not a fall
        }
        hurt |= result.damage > 0;
        return result.cost;
    }

    @Override
    protected Set<BetterBlockPos> calculateValidPositions() {
        Set<BetterBlockPos> set = new HashSet<>();
        set.add(src);
        for (int y = src.y - dest.y; y >= 0; y--) {
            set.add(dest.above(y));
        }
        return set;
    }

    enum FallMode {
        NONE, BUCKET, CLUTCH, HURT
    }

    // a deliberate fall that hurts (fastMode). nobody is saving us, we just fall. sticky, because the plan
    // changing under us at the edge (health went down) is the one thing we check for, see updateState
    private boolean hurt;

    // what the fall costs in hearts, priced once when the movement starts and never again. fallMode() builds a fresh
    // context every tick, so the damage it sees moves with our health: one hit mid air and the fall gets priced out into
    // NONE, which would hand it to nobody. -1 is not priced yet
    private double plannedDamage = -1;

    // the hp floor the planner priced that damage against, snapshotted with it (see MutableMoveResult.hurtFloor)
    private double plannedFloor;

    // built by unplanned(), see updateUnplanned
    private boolean unplanned;

    // we clicked the water bucket at dest this fall. only that water is ours to take back, a pool we landed in isn't
    private boolean poured;

    // the clutch ran out of things to try (nothing in reach in time, nothing to hang on), so it's the bucket or nothing
    private boolean clutchGaveUp;

    // hp was under fastModeMinHealth the last tick we stood on the ledge, so this fall gets no clutch start to finish
    // (canAffordClutch). asked only on the ledge: once we're off it a hit must not take away the save we're in
    private boolean lowAtLedge;

    // same idea for the bucket: a miss from here would kill us at the hp we had on the ledge (canAffordBucketMiss). only acted
    // on while standing on the ledge, a fall that starts in the air has already been agreed to
    private boolean bucketRiskyAtLedge;

    // the ladder we right-clicked onto the wall, and whether the world agreed that it's there. vines drop nothing without
    // shears so they're never recorded, and a ladder somebody else put up never is either, it's theirs
    private BlockPos ladder;
    private boolean ladderIn;
    // any clutch click at all, vines too. before the first one an unplanned clutch can still just walk away
    private boolean clutchClicked;
    // our own clock, so the pickup can give up. pickupStart is -1 until we land with a ladder to fetch, goneAt is the tick
    // the block disappeared and the drop starts counting
    private int ticks, clickedAt, pickupStart = -1, goneAt = -1, pickupMisses;
    // the click lands a tick after we ask for it, so give the block a few before deciding the click missed
    private static final int LADDER_CONFIRM_TICKS = 4;
    // all in, mining plus waiting on the drop. a stuck movement is worse than a lost ladder
    private static final int PICKUP_TICKS = 60;
    // drops can't be picked up for 10 ticks, then it's however long the walk takes
    private static final int DROP_TICKS = 30;
    private static final int PICKUP_MAX_MISSES = 10;

    @Override
    public void reset() {
        super.reset();
        clutchGaveUp = false;
        lowAtLedge = false;
        bucketRiskyAtLedge = false;
        hurt = false;
        poured = false;
        plannedDamage = -1;
        ladder = null;
        ladderIn = false;
        clutchClicked = false;
        ticks = clickedAt = pickupMisses = 0;
        pickupStart = goneAt = -1;
    }

    private FallMode fallMode() {
        CalculationContext context = new CalculationContext(baritone);
        MutableMoveResult result = new MutableMoveResult();
        // the clutch health gate is whatever the ledge said, not this tick's hp (see lowAtLedge)
        if (MovementDescend.dynamicFallCost(context, src.x, src.y, src.z, dest.x, dest.z, 0, context.get(dest.x, src.y - 2, dest.z), result, lowAtLedge)) {
            return FallMode.BUCKET;
        }
        if (result.damage > 0) {
            hurt = true;
            return FallMode.HURT; // no bucket, no clutch, nothing to place, so nothing to get wrong either
        }
        if (!result.clutch) {
            return FallMode.NONE;
        }
        if (clutchGaveUp) {
            return context.hasWaterBucket ? FallMode.BUCKET : FallMode.NONE;
        }
        return FallMode.CLUTCH;
    }

    // price the fall once, see plannedDamage. a bucket fall is free, so zero hearts however far it is
    private void snapshotDamage() {
        if (plannedDamage >= 0) {
            return;
        }
        CalculationContext context = new CalculationContext(baritone);
        MutableMoveResult result = new MutableMoveResult();
        boolean bucket = MovementDescend.dynamicFallCost(context, src.x, src.y, src.z, dest.x, dest.z, 0, context.get(dest.x, src.y - 2, dest.z), result, lowAtLedge);
        plannedDamage = bucket ? 0 : result.damage;
        plannedFloor = result.hurtFloor;
    }

    // the same question the planner asked, with the health we have right now
    private boolean stillPossible() {
        CalculationContext context = new CalculationContext(baritone);
        MutableMoveResult result = new MutableMoveResult();
        MovementDescend.cost(context, src.x, src.y, src.z, dest.x, dest.z, result);
        return result.y == dest.y && result.cost < COST_INF;
    }

    @Override
    public MovementState updateState(MovementState state) {
        if (unplanned) {
            return updateUnplanned(state);
        }
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        ticks++;
        BlockPos playerFeet = ctx.playerFeet();
        boolean atLedge = playerFeet.equals(src) && ctx.player().onGround();
        // the first tick counts as the ledge too: a fall that starts with us already in the air never stands on src, and
        // it should still get the gate for the hp we started it with
        if (atLedge || ticks == 1) {
            double health = ctx.player().getHealth() + ctx.player().getAbsorptionAmount();
            lowAtLedge = !ExperimentalMovement.canAffordClutch(health, Baritone.settings().fastModeMinHealth.value);
            bucketRiskyAtLedge = !ExperimentalMovement.canAffordBucketMiss(health, src.y - dest.y);
        }
        snapshotDamage();
        confirmLadder();

        Rotation toDest = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), VecUtils.getBlockPosCenter(dest), ctx.playerRotations());
        Rotation targetRotation = null;
        BlockState destState = ctx.world().getBlockState(dest);
        Block destBlock = destState.getBlock();

        if (ctx.world().getBlockState(dest.below()).is(Blocks.MAGMA_BLOCK) && MovementHelper.steppingOnBlocks(ctx).stream().allMatch(block -> MovementHelper.canWalkThrough(ctx, block))) {
            state.setInput(Input.SNEAK, true);
        }

        boolean isWater = destState.getFluidState().getType() instanceof WaterFluid;
        if ((hurt || lowAtLedge || bucketRiskyAtLedge) && !isWater && atLedge && !stillPossible()) {
            // we got hurt (or something else dropped our health) since the plan was made, and this fall would now cost us
            // more than we agreed to, or it was a clutch and a clutch is off at this hp, or a missed bucket would kill us now.
            // nothing has happened yet, so the executor can just replan
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        FallMode mode = isWater ? FallMode.NONE : fallMode();
        AltoClefSettings alto = AltoClefSettings.getInstance();
        if (mode == FallMode.BUCKET && alto.shouldNotPlaceBucketButStillFall() && !bucketOnHotbar()) {
            // altoclef does its own water bucket mlg for a bucket it keeps off the hotbar, we just fall and don't insist on
            // having one. a hotbar bucket is ours: the chain stands down for it (see ownsFall) so nobody cancels the path
            mode = FallMode.NONE;
        }
        boolean paused = alto.isInteractionPaused(); // no hotbar swaps while paused, and the click gets eaten by the input handler
        if (mode == FallMode.BUCKET && !playerFeet.equals(dest)) {
            if (!Inventory.isHotbarSlot(ctx.player().getInventory().findSlotMatchingItem(STACK_BUCKET_WATER)) || ctx.world().dimension() == Level.NETHER) {
                return state.setStatus(MovementStatus.UNREACHABLE);
            }

            if (!paused && ctx.player().position().y - dest.getY() < ctx.playerController().getBlockReachDistance() && !ctx.player().onGround()) {
                ctx.player().getInventory().setSelectedSlot(ctx.player().getInventory().findSlotMatchingItem(STACK_BUCKET_WATER));

                targetRotation = new Rotation(toDest.getYaw(), 90.0F);

                if (ctx.isLookingAt(dest) || ctx.isLookingAt(dest.below())) {
                    state.setInput(Input.CLICK_RIGHT, true);
                    poured = true;
                }
            }
        }

        if (!MovementHelper.openDoors(ctx, state, src, new BetterBlockPos(dest.x, src.y, dest.z))) {
            return state;
        }

        if (targetRotation != null) {
            state.setTarget(new MovementTarget(targetRotation, true));
        } else {
            state.setTarget(new MovementTarget(toDest, false));
        }
        boolean canFetch = isWater && canFetch(paused);
        if (canFetch && pickUpWater(state)) {
            return state; // not done until the bucket's full again, same as the ladder
        }
        if (playerFeet.equals(dest) && (ctx.player().position().y - playerFeet.getY() < 0.094 || isWater)) { // 0.094 because lilypads
            if (isWater) {
                // nothing to fetch, so done once we've stopped coming down. with something to fetch, pickUpWater has it
                // the moment we stop, so this only ever skips it while we're still falling
                if (!canFetch && FallCover.notFalling(ctx.player().onGround(), ctx.player().isInWater(), ctx.player().getDeltaMovement().y)) {
                    return state.setStatus(MovementStatus.SUCCESS);
                } // don't else return state; we need to stay centered because this water might be flowing under the surface
            } else {
                return pickUpLadder(state) ? state.setStatus(MovementStatus.SUCCESS) : state;
            }
        }
        if (mode == FallMode.CLUTCH && !playerFeet.equals(dest) && clutch(state)) {
            return state; // it's doing the steering, the centering below would fight it
        }
        Vec3 destCenter = VecUtils.getBlockPosCenter(dest); // we are moving to the 0.5 center not the edge (like if we were falling on a ladder)
        if (Math.abs(ctx.player().position().x + ctx.player().getDeltaMovement().x - destCenter.x) > 0.1 || Math.abs(ctx.player().position().z + ctx.player().getDeltaMovement().z - destCenter.z) > 0.1) {
            if (!ctx.player().onGround() && Math.abs(ctx.player().getDeltaMovement().y) > 0.4) {
                state.setInput(Input.SNEAK, true);
            }
            state.setInput(Input.MOVE_FORWARD, true);
        }
        Vec3i avoid = Optional.ofNullable(avoid()).map(Direction::getUnitVec3i).orElse(null);
        if (avoid == null) {
            avoid = src.subtract(dest);
        } else {
            double dist = Math.abs(avoid.getX() * (destCenter.x - avoid.getX() / 2.0 - ctx.player().position().x)) + Math.abs(avoid.getZ() * (destCenter.z - avoid.getZ() / 2.0 - ctx.player().position().z));
            if (dist < 0.6) {
                state.setInput(Input.MOVE_FORWARD, true);
            } else if (!ctx.player().onGround()) {
                state.setInput(Input.SNEAK, false);
            }
        }
        if (targetRotation == null) {
            Vec3 destCenterOffset = new Vec3(destCenter.x + 0.125 * avoid.getX(), destCenter.y, destCenter.z + 0.125 * avoid.getZ());
            state.setTarget(new MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), destCenterOffset, ctx.playerRotations()), false));
        }
        return state;
    }

    // the click is a guess until the block shows up. it was air when we clicked, so a ladder there later is ours
    private void confirmLadder() {
        if (ladder == null || ladderIn) {
            return;
        }
        if (ctx.world().getBlockState(ladder).is(Blocks.LADDER)) {
            ladderIn = true;
        } else if (ticks - clickedAt > LADDER_CONFIRM_TICKS) {
            ladder = null; // the click missed, nothing to fetch
        }
    }

    // after landing: break the ladder we hung on the wall, then stand in the drop until it's in the inventory (same idea as
    // the bucket pickup, the movement isn't done until the thing we spent is back). returns true once the movement can succeed.
    // every way this can go wrong ends in true, a lost ladder beats a path that never moves again
    private boolean pickUpLadder(MovementState state) {
        if (ladder == null || !Baritone.settings().pickupLadders.value) {
            return true;
        }
        if (!ladderIn) {
            return false; // landed before the click showed up, confirmLadder settles it within a few ticks
        }
        if (pickupStart < 0) {
            pickupStart = ticks;
        }
        if (ticks - pickupStart > PICKUP_TICKS) {
            return true;
        }
        BlockState there = ctx.world().getBlockState(ladder);
        if (there.is(Blocks.LADDER)) {
            MovementHelper.switchToBestToolFor(ctx, there);
            Optional<Rotation> look = RotationUtils.reachable(ctx, ladder, ctx.playerController().getBlockReachDistance());
            if (look.isEmpty()) {
                return ++pickupMisses > PICKUP_MAX_MISSES; // behind something, or the look is still catching up
            }
            pickupMisses = 0;
            state.setTarget(new MovementTarget(look.get(), true));
            if (ctx.isLookingAt(ladder) || ctx.playerRotations().isReallyCloseTo(look.get())) {
                state.setInput(Input.CLICK_LEFT, true);
            }
            return false;
        }
        if (goneAt < 0) {
            goneAt = ticks;
        }
        // a drop can't be picked up for 10 ticks, and the pickup box is a block wider than us. nobody to wait for means
        // creative, a full inventory that ate it already, or the gamerule being off
        Vec3 me = ctx.player().position();
        ItemEntity drop = null;
        double best = Double.MAX_VALUE;
        for (ItemEntity item : ctx.world().getEntitiesOfClass(ItemEntity.class, new AABB(ladder).inflate(1.5), e -> e.getItem().is(Items.LADDER))) {
            double d = Math.hypot(item.getX() - me.x, item.getZ() - me.z);
            if (d < best) {
                best = d;
                drop = item;
            }
        }
        if (drop == null || ticks - goneAt > DROP_TICKS) {
            return true;
        }
        if (best > 1 && floorToward(drop.position(), me)) {
            // rolled out of reach, go and stand on it
            state.setTarget(new MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), drop.position(), ctx.playerRotations()), false));
            state.setInput(Input.MOVE_FORWARD, true);
        }
        return false;
    }

    // a ladder is not worth a walk off the ledge, so only chase the drop over floor. a block ahead and the drop's own
    // block both need something to stand on, otherwise we sit here and let the timeout end it
    private boolean floorToward(Vec3 drop, Vec3 me) {
        double dx = drop.x - me.x;
        double dz = drop.z - me.z;
        double len = Math.hypot(dx, dz);
        if (len < 1.0E-6) {
            return true;
        }
        int y = ctx.playerFeet().getY();
        BlockPos ahead = BlockPos.containing(me.x + dx / len * 0.8, y, me.z + dz / len * 0.8);
        BlockPos there = BlockPos.containing(drop.x, y, drop.z);
        return MovementHelper.canWalkOn(ctx, ahead.below()) && MovementHelper.canWalkOn(ctx, there.below());
    }

    // the cells above the landing that are air with something next to them to hang a climbable on, as LadderClutch's mask.
    // -1 if one is already there
    private int clutchMask(BlockStateInterface bsi, Vec3 pos) {
        int mask = 0;
        for (int k = 0; k < LadderClutch.CELLS && dest.y + k < src.y; k++) {
            BlockState there = bsi.get0(dest.above(k));
            if (MovementHelper.isClimbable(there.getBlock())) {
                return -1;
            }
            if (there.isAir() && !AltoClefSettings.getInstance().shouldAvoidPlacingAt(dest.getX(), dest.getY() + k, dest.getZ()) && !walls(bsi, dest.above(k), pos, false).isEmpty()) {
                mask |= 1 << k;
            }
        }
        return mask;
    }

    // ladder clutch, one tick of it. the whole idea: a vine or ladder in one of the last few cells before the floor wipes the
    // fall distance (see LadderClutch for how, there's two ways and one of them works at terminal velocity). minecraft picks
    // what's under the crosshair at the start of a tick, before our onTick, and then does handleKeybinds (the click, so
    // the block is in the world) before the player ticks. so the click goes on the tick we want the climbable to exist for,
    // and the aim goes on the tick before that, pointed from where our eye is going to be
    // returns true if it took over the inputs this tick
    private boolean clutch(MovementState state) {
        LocalPlayer player = ctx.player();
        if (player.onGround() || player.getDeltaMovement().y >= 0) {
            return false;
        }
        InventoryBehavior inv = ((Baritone) baritone).getInventoryBehavior();
        // asked again here and not just by the planner, the unplanned route never went through one
        Item item = inv.clutchAllowed() ? inv.pickClutchItem(false) : null;
        if (item == null) {
            return false; // the last one's already in the wall, or somebody ate the vines
        }
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        Vec3 pos = player.position();
        Vec3 mot = player.getDeltaMovement();
        int mask = clutchMask(bsi, pos);
        if (mask < 0) {
            return false; // already one in here (us, a tick ago), physics takes it from here
        }
        double reach = ctx.playerController().getBlockReachDistance();
        // what's under the crosshair right now is exactly what a click will hit
        BlockHitResult hit = ctx.objectMouseOver() instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK ? b : null;
        int aimed = hit == null ? -1 : aimedCell(bsi, hit, mask);
        if (aimed >= 0) {
            LadderClutch.Plan now = LadderClutch.pick(pos.y, mot.y, player.fallDistance, dest.y, 1 << aimed, 0, reach);
            boolean roomy = item != Items.LADDER || gap(dest.above(aimed), hit.getDirection().getOpposite(), pos) >= LadderClutch.LADDER_CLEARANCE + 0.01;
            if (now != null && now.place() == 0 && roomy) {
                inv.pickClutchItem(true);
                if (item == Items.LADDER && !ladderIn) {
                    BlockPos placed = dest.above(aimed);
                    ladder = new BlockPos(placed.getX(), placed.getY(), placed.getZ()); // no BetterBlockPos in vanilla's hands
                    clickedAt = ticks;
                }
                clutchClicked = true;
                state.setTarget(new MovementTarget(ctx.playerRotations(), true));
                state.setInput(Input.CLICK_RIGHT, true);
                return true;
            }
        }
        LadderClutch.Plan plan = LadderClutch.pick(pos.y, mot.y, player.fallDistance, dest.y, mask, 1, reach);
        if (plan == null) {
            clutchGaveUp = true;
            return false;
        }
        BlockPos cell = dest.above(plan.cell());
        boolean ladder = item == Items.LADDER;
        if (plan.place() > 1) {
            if (ladder && plan.place() > 3) {
                // sneaking is a third of the air control, and the ladder needs us about 0.6 off the wall (see LadderClutch.WALL_GAP)
                // not within the last couple ticks though, the crouch is a lower eye and the aim is worked out for a standing one
                List<Direction> sides = walls(bsi, cell, pos, false);
                steer(state, cell, sides.get(0), pos, mot);
            }
            return ladder; // a vine doesn't care where we are, the plain centering is fine
        }
        inv.pickClutchItem(true);
        return aim(state, bsi, cell, ladder, pos, mot, reach);
    }

    // line the crosshair up with the wall next to this cell for the next tick, and only if the ray from where we'll be
    // really ends up on the face (same check as attemptToPlaceABlock, except from the next eye instead of this one)
    private boolean aim(MovementState state, BlockStateInterface bsi, BlockPos cell, boolean ladder, Vec3 pos, Vec3 mot, double reach) {
        Vec3 eye = new Vec3(pos.x + mot.x, pos.y + mot.y + ctx.player().getEyeHeight(), pos.z + mot.z);
        Vec3 feet = new Vec3(eye.x, pos.y + mot.y, eye.z);
        for (Direction side : walls(bsi, cell, feet, ladder)) {
            for (double height : AIM_HEIGHTS) {
                Rotation want = RotationUtils.calcRotationFromVec3d(eye, faceSpot(cell, side, eye, height), ctx.playerRotations());
                Rotation actual = baritone.getLookBehavior().getAimProcessor().peekRotation(want);
                Vec3 dir = RotationUtils.calcLookDirectionFromRotation(actual);
                HitResult res = ctx.world().clip(new ClipContext(eye, eye.add(dir.scale(reach)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, ctx.player()));
                if (res instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK && b.getBlockPos().equals(cell.relative(side)) && b.getDirection() == side.getOpposite()) {
                    state.setTarget(new MovementTarget(want, true));
                    return true;
                }
            }
        }
        // nothing lines up. sit tight, if it stays that way the plan runs out of ticks and the clutch gives up
        return true;
    }

    private static final double[] AIM_HEIGHTS = {LadderClutch.AIM_HEIGHT, 0.5, 0.2};

    // a hair into the wall, so the ray ends up past the face instead of rounding off short of it
    private static Vec3 faceSpot(BlockPos cell, Direction side, Vec3 eye, double height) {
        double x = side.getStepX() != 0 ? cell.getX() + 0.5 + 0.51 * side.getStepX() : Mth.clamp(eye.x, cell.getX() + 0.15, cell.getX() + 0.85);
        double z = side.getStepZ() != 0 ? cell.getZ() + 0.5 + 0.51 * side.getStepZ() : Mth.clamp(eye.z, cell.getZ() + 0.15, cell.getZ() + 0.85);
        return new Vec3(x, cell.getY() + height, z);
    }

    // which cell a click on the crosshair would put something in, if that's one we planned for
    private int aimedCell(BlockStateInterface bsi, BlockHitResult hit, int mask) {
        Direction face = hit.getDirection();
        BlockPos cell = hit.getBlockPos().relative(face);
        int k = cell.getY() - dest.y;
        if (!face.getAxis().isHorizontal() || cell.getX() != dest.x || cell.getZ() != dest.z || k < 0 || k >= LadderClutch.CELLS || (mask & (1 << k)) == 0) {
            return -1;
        }
        BlockPos wall = hit.getBlockPos();
        return MovementHelper.canPlaceAgainst(bsi, wall) && MovementDescend.clutchWall(bsi.get0(wall)) ? k : -1;
    }

    // walls we could hang something on next to this cell, best first. a ladder is only roomy if we're not so close to the wall
    // that its slab would be inside our hitbox (the placement gets refused, or we'd land on top of it)
    private List<Direction> walls(BlockStateInterface bsi, BlockPos cell, Vec3 pos, boolean roomy) {
        List<Direction> out = new ArrayList<>(4);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos wall = cell.relative(side);
            if (MovementHelper.canPlaceAgainst(bsi, wall) && MovementDescend.clutchWall(bsi.get0(wall)) && (!roomy || gap(cell, side, pos) >= LadderClutch.LADDER_CLEARANCE + 0.02)) {
                out.add(side);
            }
        }
        out.sort(Comparator.comparingDouble(side -> Math.abs(gap(cell, side, pos) - LadderClutch.WALL_GAP)));
        return out;
    }

    // how far we are from the wall plane on this side of the cell
    private static double gap(BlockPos cell, Direction side, Vec3 pos) {
        if (side.getStepX() != 0) {
            return side.getStepX() * (cell.getX() + 0.5 + 0.5 * side.getStepX() - pos.x);
        }
        return side.getStepZ() * (cell.getZ() + 0.5 + 0.5 * side.getStepZ() - pos.z);
    }

    // walk to the spot WALL_GAP off the wall and in the middle along it. air drag is 0.91, so with no more input we'd coast
    // v / 0.09 further, that's the spot we judge
    private void steer(MovementState state, BlockPos cell, Direction side, Vec3 pos, Vec3 mot) {
        double goalX = cell.getX() + 0.5 + side.getStepX() * (0.5 - LadderClutch.WALL_GAP);
        double goalZ = cell.getZ() + 0.5 + side.getStepZ() * (0.5 - LadderClutch.WALL_GAP);
        double ex = goalX - (pos.x + mot.x / 0.09);
        double ez = goalZ - (pos.z + mot.z / 0.09);
        if (Math.hypot(ex, ez) < 0.03) {
            return;
        }
        Vec3 head = ctx.playerHead();
        state.setTarget(new MovementTarget(RotationUtils.calcRotationFromVec3d(head, new Vec3(pos.x + ex, head.y, pos.z + ez), ctx.playerRotations()), false));
        state.setInput(Input.MOVE_FORWARD, true);
        state.setInput(Input.SNEAK, true);
    }

    private Direction avoid() {
        for (int i = 0; i < 15; i++) {
            BlockState state = ctx.world().getBlockState(ctx.playerFeet().below(i));
            if (state.getBlock() == Blocks.LADDER) {
                return state.getValue(LadderBlock.FACING);
            }
        }
        return null;
    }

    private boolean bucketOnHotbar() {
        return Inventory.isHotbarSlot(ctx.player().getInventory().findSlotMatchingItem(STACK_BUCKET_WATER));
    }

    private boolean emptyBucketOnHotbar() {
        return Inventory.isHotbarSlot(ctx.player().getInventory().findSlotMatchingItem(STACK_BUCKET_EMPTY));
    }

    private boolean destIsSource() {
        return ctx.world().getFluidState(dest).getType() == Fluids.WATER;
    }

    // there's water of ours at dest to take back. flowing water can't go in a bucket, and the click is eaten while paused
    private boolean canFetch(boolean paused) {
        return poured && !paused && destIsSource() && emptyBucketOnHotbar();
    }

    // take the water at dest back from wherever we ended up next to it. this used to want our feet exactly in dest and
    // y >= 0, and standing still in water is never y >= 0 (FallCover.NOT_FALLING_Y), so a block of drift or just standing
    // there never clicked and the movement timed out with the water left behind. returns true while it has the inputs
    private boolean pickUpWater(MovementState state) {
        LocalPlayer player = ctx.player();
        BlockPos feet = ctx.playerFeet();
        Vec3 water = VecUtils.getBlockPosCenter(dest);
        // the bucket's own reach, not baritone's setting, it's vanilla doing the ray
        double reach = player.blockInteractionRange();
        boolean near = FallCover.nextToDest(feet.getX(), feet.getY(), feet.getZ(), dest.x, dest.y, dest.z);
        if (!FallCover.pickupReady(destIsSource(), near, ctx.playerHead().distanceTo(water), reach, player.onGround(), player.isInWater(), player.getDeltaMovement().y, emptyBucketOnHotbar())) {
            return false;
        }
        player.getInventory().setSelectedSlot(player.getInventory().findSlotMatchingItem(STACK_BUCKET_EMPTY));
        state.setTarget(new MovementTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), water, ctx.playerRotations()), true));
        // click once the crosshair is really on it, asked with the empty bucket's own ray (source water counts). isLookingAt
        // can't see water with fluidHandling at NONE, and an exact rotation never comes with randomLooking113 on
        if (aimedAtDest(reach)) {
            state.setInput(Input.CLICK_RIGHT, true);
        }
        return true;
    }

    private boolean aimedAtDest(double reach) {
        Vec3 eye = ctx.playerHead();
        Vec3 dir = RotationUtils.calcLookDirectionFromRotation(ctx.playerRotations());
        HitResult res = ctx.world().clip(new ClipContext(eye, eye.add(dir.scale(reach)), ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, ctx.player()));
        return res instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK && b.getBlockPos().equals(dest);
    }

    // altoclef's mlg chain asks this before jumping in on a fall. true if this is the fall we planned, we're in it, and we
    // are doing something about it (or it's safe), so a second brain stealing the rotation and cancelling the path is the
    // one thing that can only make it worse. a knockback out of the column or a shove too hard to be ours says no
    public boolean ownsFall(Vec3 pos, Vec3 mot) {
        if (unplanned || !FallCover.inColumn(src.x, src.y, src.z, dest.x, dest.y, dest.z, pos.x, pos.y, pos.z, mot.x, mot.z)) {
            return false;
        }
        boolean water = ctx.world().getBlockState(dest).getFluidState().getType() instanceof WaterFluid;
        snapshotDamage();
        // a fall we planned to eat stays HURT for as long as it's in the air, whatever our health does to a fresh context.
        // it's still ours while the hearts leave us at or above the floor we agreed to (the planner's gate, absorption
        // counted the same way). once a hit since then breaks that, it's an emergency again and alto can clutch it
        boolean planned = !water && plannedDamage > 0;
        FallMode mode = water ? FallMode.NONE : planned ? FallMode.HURT : fallMode();
        double health = ctx.player().getHealth() + ctx.player().getAbsorptionAmount();
        boolean affordable = planned && ExperimentalMovement.canAffordFall(health, plannedDamage, plannedFloor);
        return FallCover.handles(mode, clutchGaveUp, bucketOnHotbar(), AltoClefSettings.getInstance().shouldNotPlaceBucketButStillFall(), affordable);
    }

    // the water this chain placed is ours to fetch, unless this movement is about to (the isWater branch of updateState).
    // two clicks on one bucket is how you place it again
    public boolean picksUpWaterAt(BlockPos placed) {
        if (unplanned) {
            return false;
        }
        boolean at = placed.getX() == dest.x && placed.getY() == dest.y && placed.getZ() == dest.z;
        // the same question updateState asks before pickUpWater, or the chain waits on a click nobody makes
        boolean ours = canFetch(AltoClefSettings.getInstance().isInteractionPaused());
        return FallCover.pickupIsBaritones(at, ours, emptyBucketOnHotbar());
    }

    // a fall nobody planned (knockback, a slip, a mob) for altoclef to clutch with a ladder or vine. landing is the cell
    // above the floor we're about to hit. the movement is only ever asked for the clutch and the pickup, never for a path
    public static MovementFall unplanned(IBaritone baritone, BetterBlockPos landing, int above) {
        // the clutch only ever looks at the bottom few cells, so a short fake column keeps buildPositionsToBreak small
        MovementFall fall = new MovementFall(baritone, landing.above(Math.max(LadderClutch.CELLS + 1, Math.min(above, 8))), landing);
        fall.unplanned = true;
        return fall;
    }

    // could a ladder or vine save this fall from where we are right now: something to hang it on near the floor, and
    // LadderClutch says there's a tick that gets it there in time
    public boolean canClutch() {
        LocalPlayer player = ctx.player();
        if (player.onGround() || player.getDeltaMovement().y >= 0) {
            return false;
        }
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        Vec3 pos = player.position();
        int mask = clutchMask(bsi, pos);
        if (mask <= 0) {
            return false;
        }
        return LadderClutch.pick(pos.y, player.getDeltaMovement().y, player.fallDistance, dest.y, mask, 0, ctx.playerController().getBlockReachDistance()) != null;
    }

    // something might be on the wall already. after that the clutch has to see it through, updateUnplanned fetches the ladder
    public boolean clutchClicked() {
        return clutchClicked;
    }

    // all there is to an unplanned fall: clutch on the way down, fetch the ladder once we're standing. no centering, no
    // bucket, nothing that wants a dest, the floor is where it is
    private MovementState updateUnplanned(MovementState state) {
        state.setStatus(MovementStatus.RUNNING);
        ticks++;
        confirmLadder();
        LocalPlayer player = ctx.player();
        if (player.onGround() || player.isInWater() || player.isInLava() || FallCover.restingOnLadder(player.onClimbable(), feetOverFloor(player))) {
            return pickUpLadder(state) ? state.setStatus(MovementStatus.SUCCESS) : state;
        }
        clutch(state);
        return state;
    }

    // how far the feet are over the block under their cell, MAX_VALUE when that block is nothing you could stand on
    private double feetOverFloor(LocalPlayer player) {
        BlockPos below = BlockPos.containing(player.getX(), player.getY(), player.getZ()).below();
        if (ctx.world().getBlockState(below).getCollisionShape(ctx.world(), below).isEmpty()) {
            return Double.MAX_VALUE;
        }
        return player.getY() - below.getY() - 1;
    }

    @Override
    public boolean safeToCancel(MovementState state) {
        // if we haven't started walking off the edge yet, or if we're in the process of breaking blocks before doing the fall
        // then it's safe to cancel this. fetching the ladder is on the ground, nothing to lose but a ladder
        return ctx.playerFeet().equals(src) || state.getStatus() != MovementStatus.RUNNING || pickupStart >= 0;
    }

    private static BetterBlockPos[] buildPositionsToBreak(BetterBlockPos src, BetterBlockPos dest) {
        BetterBlockPos[] toBreak;
        int diffX = src.getX() - dest.getX();
        int diffZ = src.getZ() - dest.getZ();
        int diffY = Math.abs(src.getY() - dest.getY());
        toBreak = new BetterBlockPos[diffY + 2];
        for (int i = 0; i < toBreak.length; i++) {
            toBreak[i] = new BetterBlockPos(src.getX() - diffX, src.getY() + 1 - i, src.getZ() - diffZ);
        }
        return toBreak;
    }

    @Override
    protected boolean prepared(MovementState state) {
        if (state.getStatus() == MovementStatus.WAITING) {
            return true;
        }
        // only break if one of the first three needs to be broken
        // specifically ignore the last one which might be water
        for (int i = 0; i < 4 && i < positionsToBreak.length; i++) {
            if (!MovementHelper.canWalkThrough(ctx, positionsToBreak[i])) {
                return super.prepared(state);
            }
        }
        return true;
    }
}
