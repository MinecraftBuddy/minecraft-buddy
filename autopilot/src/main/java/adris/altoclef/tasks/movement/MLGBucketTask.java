package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.*;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class MLGBucketTask extends Task {

    private static MLGClutchConfig _config;

    static {
        ConfigHelper.loadConfig("configs/mlg_clutch_settings.json", MLGClutchConfig::new, MLGClutchConfig.class, newConfig -> _config = newConfig);
    }

    // every cell a click of ours really put water in this fall. a list because the task clicks every tick it can reach
    // and #gamer carries two buckets, so one fall can place twice
    private final List<BlockPos> _placedCells = new ArrayList<>();
    private BlockPos _movingTorwards;

    private static boolean isLava(BlockPos pos) {
        assert Minecraft.getInstance().level != null;
        return Minecraft.getInstance().level.getBlockState(pos).getBlock() == Blocks.LAVA;
    }

    private static boolean lavaWillProtect(BlockPos pos) {
        assert Minecraft.getInstance().level != null;
        BlockState state = Minecraft.getInstance().level.getBlockState(pos);
        if (state.getBlock() == Blocks.LAVA) {
            int level = state.getFluidState().getAmount();
            return level == 0 || level >= _config.lavaLevelOrGreaterWillCancelFallDamage;
        }
        return false;
    }

    private static boolean isWater(BlockPos pos) {
        assert Minecraft.getInstance().level != null;
        return Minecraft.getInstance().level.getBlockState(pos).getBlock() == Blocks.WATER;
    }

    /**
     * Can we reach this block while falling, or will gravity pull us too far?
     */
    private static boolean canTravelToInAir(BlockPos pos) {
        Entity player = Minecraft.getInstance().player;
        assert player != null;
        double verticalDist = player.position().y() - pos.getY() - 1;
        double verticalVelocity = -1 * player.getDeltaMovement().y;
        double grav = EntityHelper.ENTITY_GRAVITY;
        double movementSpeedPerTick = _config.averageHorizontalMovementSpeedPerTick; // Calculated, but also somewhat conservative
        // 1d projectile motion
        double ticksToTravelSq = (-verticalVelocity + Math.sqrt(verticalVelocity * verticalVelocity + 2 * grav * verticalDist)) / grav;
        double maxMoveDistanceSq = movementSpeedPerTick * movementSpeedPerTick * ticksToTravelSq * ticksToTravelSq;
        // We need to get within 1 block, so subtract a "radius" or something idk
        double horizontalDistance = WorldHelper.distanceXZ(player.position(), WorldHelper.toVec3d(pos)) - 0.8;
        if (horizontalDistance < 0)
            horizontalDistance = 0;
        return maxMoveDistanceSq > horizontalDistance * horizontalDistance;
    }

    private static boolean isFallDeadly(BlockPos pos) {
        Player player = Minecraft.getInstance().player;
        double damage = calculateFallDamageToLandOn(pos);
        assert Minecraft.getInstance().level != null;
        Block b = Minecraft.getInstance().level.getBlockState(pos).getBlock();
        if (b == Blocks.HAY_BLOCK) {
            damage *= 0.2f;
        }
        assert player != null;
        double resultingHealth = player.getHealth() - (float) damage;
        return resultingHealth < _config.preferLavaWhenFallDropsHealthBelowThreshold;
    }

    private static double calculateFallDamageToLandOn(BlockPos pos) {
        ClientLevel world = Minecraft.getInstance().level;
        Player player = Minecraft.getInstance().player;
        assert player != null;
        double totalFallDistance = player.fallDistance + (player.getY() - pos.getY() - 1);
        // 26.3 does floor(fall + 1e-6 - safe), but our total stops at the top of a full block and a slab or farmland lands
        // lower than that, so the ceil stays as the deliberate "assume MORE damage" margin (it's still an upper bound)
        double baseFallDamage = Mth.ceil(totalFallDistance - 3.0F);
        assert world != null;
        return EntityHelper.calculateResultingPlayerDamage(player, world.damageSources().fall(), baseFallDamage);
    }

    private static void moveLeftRight(AltoClef mod, int delta) {
        if (delta == 0) {
            mod.getInputControls().release(Input.MOVE_LEFT);
            mod.getInputControls().release(Input.MOVE_RIGHT);
        } else if (delta > 0) {
            mod.getInputControls().release(Input.MOVE_LEFT);
            mod.getInputControls().hold(Input.MOVE_RIGHT);
        } else {
            mod.getInputControls().hold(Input.MOVE_LEFT);
            mod.getInputControls().release(Input.MOVE_RIGHT);
        }
    }

    private static void moveForwardBack(AltoClef mod, int delta) {
        if (delta == 0) {
            mod.getInputControls().release(Input.MOVE_FORWARD);
            mod.getInputControls().release(Input.MOVE_BACK);
        } else if (delta > 0) {
            mod.getInputControls().hold(Input.MOVE_FORWARD);
            mod.getInputControls().release(Input.MOVE_BACK);
        } else {
            mod.getInputControls().release(Input.MOVE_FORWARD);
            mod.getInputControls().hold(Input.MOVE_BACK);
        }
    }

    private Task onTickInternal(AltoClef mod, BlockPos oldMovingTorwards) {
        Optional<BlockPos> willLandOn = getBlockWeWillLandOn(mod);
        Optional<BlockPos> bestClutchPos = getBestConeClutchBlock(mod, oldMovingTorwards);
        // Move torwards our best "clutch" position
        if (bestClutchPos.isPresent()) {
            _movingTorwards = bestClutchPos.get().mutable();
            if (!_movingTorwards.equals(oldMovingTorwards)) {
                if (oldMovingTorwards == null)
                    Debug.logMessage("(NEW clutch target: " + _movingTorwards + ")");
                else
                    Debug.logMessage("(changed clutch target: " + _movingTorwards + ")");
            }
        } else if (oldMovingTorwards != null) {
            Debug.logMessage("(LOST clutch position!)");
        }
        if (willLandOn.isPresent()) {
            handleJumpForLand(mod, willLandOn.get());
            return placeMLGBucketTask(mod, willLandOn.get());
        } else {
            setDebugState("Wait for it...");
            // We must trigger jump as soon as we enter a "climbable" object
            mod.getInputControls().release(Input.JUMP);
            return null;
        }
    }

    private Task placeMLGBucketTask(AltoClef mod, BlockPos toPlaceOn) {
        if (!hasClutchItem(mod)) {
            setDebugState("No clutch item");
            return null;
        }
        // If our raycast hit a non-solid block, go DOWN one.
        if (!WorldHelper.isSolid(mod, toPlaceOn)) {
            toPlaceOn = toPlaceOn.below();
        }
        BlockPos willLandIn = toPlaceOn.above();
        // If we're water, we're ok. Do nothing.
        BlockState willLandInState = mod.getWorld().getBlockState(willLandIn);
        if (willLandInState.getBlock() == Blocks.WATER) {
            // We good.
            setDebugState("Waiting to fall into water");
            mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
            return null;
        }

        IPlayerContext ctx = mod.getClientBaritone().getPlayerContext();
        Optional<Rotation> reachable = RotationUtils.reachable(ctx, toPlaceOn);
        if (reachable.isPresent()) {
            setDebugState("Performing MLG");
            LookHelper.lookAt(mod, reachable.get());
            // Try water by default
            boolean water = !WorldHelper.waterEvaporates(mod.getWorld()) && mod.getSlotHandler().forceEquipItem(Items.WATER_BUCKET);
            boolean hasClutch = water;
            if (!hasClutch) {
                // Go through our "clutch" items and see if any fit
                if (!_config.clutchItems.isEmpty()) {
                    for (Item tryEquip : _config.clutchItems) {
                        if (mod.getSlotHandler().forceEquipItem(tryEquip)) {
                            hasClutch = true;
                            break;
                        }
                    }
                }
            }
            if (hasClutch) {
                Debug.logMessage("HIT: " + willLandIn);
                if (water) {
                    recordPour(mod);
                }
                mod.getInputControls().tryPress(Input.CLICK_RIGHT);
                //mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            } else {
                setDebugState("NOT LOOKING CORRECTLY!");
            }
        } else {
            setDebugState("Waiting to reach target block...");
        }
        return null;
    }

    // where this click really puts the water: the bucket's own ray (outline, no fluids, vanilla reach) from where we are
    // and look right now, lookAt already turned us. willLandIn is where we hoped it'd go, which misses a waterlogged
    // block, a side face, and the second bucket. a cell that's already a source isn't ours to take
    private void recordPour(AltoClef mod) {
        Player player = mod.getPlayer();
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(player.blockInteractionRange()));
        BlockHitResult hit = mod.getWorld().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockPos cell = BucketPour.waterGoesTo(mod.getWorld(), hit.getBlockPos(), hit.getDirection());
        if (cell != null && !BucketPour.isWaterSource(mod.getWorld(), cell) && !_placedCells.contains(cell)) {
            _placedCells.add(cell.immutable());
        }
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // ALWAYS faster
        mod.getInputControls().hold(Input.SPRINT);
        // Check AROUND player instead of directly under.
        // We may crop the edge of a block or wall.
        BlockPos oldMovingTorwards = _movingTorwards != null ? _movingTorwards.mutable() : null;
        _movingTorwards = null;
        Task result = onTickInternal(mod, oldMovingTorwards);

        handleForwardVelocity(mod, !Objects.equals(oldMovingTorwards, _movingTorwards));
        handleCancellingSidewaysVelocity(mod);

        return result;
    }

    private void handleForwardVelocity(AltoClef mod, boolean newForwardTarget) {
        if (mod.getPlayer().onGround() || _movingTorwards == null || WorldHelper.inRangeXZ(mod.getPlayer(), _movingTorwards, 0.05f)) {
            moveForwardBack(mod, 0);
            return;
        }
        Rotation look = LookHelper.getLookRotation();
        look = new Rotation(look.getYaw(), 0);
        Vec3 forwardFacing = LookHelper.toVec3d(look).multiply(1, 0, 1).normalize();
        Vec3 delta = WorldHelper.toVec3d(_movingTorwards).subtract(mod.getPlayer().position()).multiply(1, 0, 1);
        Vec3 velocity = mod.getPlayer().getDeltaMovement().multiply(1, 0, 1);
        Vec3 pd = delta.subtract(velocity.scale(3f));
        double forwardStrength = pd.dot(forwardFacing);
        if (newForwardTarget) {
            LookHelper.lookAt(mod, _movingTorwards);
        }
        Debug.logInternal("F:" + forwardStrength);
        moveForwardBack(mod, (int) Math.signum(forwardStrength));
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        // the chain copies these every tick, so an interrupt and restart losing them loses nothing
        _placedCells.clear();
        // hold shift while falling.
        // Look down at first, might help
        mod.getPlayer().setXRot(90);
    }

    /**
     * We will land in this block, handle our jump.
     * <p>
     * Twisted vines require we press space ONLY when we're inside the vines
     */
    private void handleJumpForLand(AltoClef mod, BlockPos willLandOn) {
        BlockPos willLandIn = WorldHelper.isSolid(mod, willLandOn) ? willLandOn.above() : willLandOn;
        BlockState s = mod.getWorld().getBlockState(willLandIn);
        if (s.getBlock() == Blocks.LAVA) {
            // ALWAYS hold jump for lava
            mod.getInputControls().hold(Input.JUMP);
            return;
        }
        AABB blockBounds;
        try {
            blockBounds = s.getCollisionShape(mod.getWorld(), willLandIn).bounds();
        } catch (UnsupportedOperationException ex) {
            blockBounds = AABB.ofSize(WorldHelper.toVec3d(willLandIn), 1, 1, 1);
        }
        boolean inside = mod.getPlayer().getBoundingBox().intersects(blockBounds);
        if (inside)
            mod.getInputControls().hold(Input.JUMP);
        else
            mod.getInputControls().release(Input.JUMP);
    }

    private static Optional<BlockPos> getBlockWeWillLandOn(AltoClef mod) {
        Vec3 velCheck = mod.getPlayer().getDeltaMovement();
        AABB b = mod.getPlayer().getBoundingBox().move(velCheck);
        Vec3 c = b.getCenter();
        Vec3[] coords = new Vec3[]{
                c,
                new Vec3(b.minX, c.y, b.minZ),
                new Vec3(b.maxX, c.y, b.minZ),
                new Vec3(b.minX, c.y, b.maxZ),
                new Vec3(b.maxX, c.y, b.maxZ),
        };
        BlockHitResult result = null;
        double bestSqDist = Double.POSITIVE_INFINITY;
        for (Vec3 rayOrigin : coords) {
            ClipContext rctx = castDown(rayOrigin);
            BlockHitResult hit = mod.getWorld().clip(rctx);
            if (hit.getType() == HitResult.Type.BLOCK) {
                double curDis = hit.getLocation().distanceToSqr(rayOrigin);
                if (curDis < bestSqDist) {
                    result = hit;
                    bestSqDist = curDis;
                }
            }
        }

        if (result == null || result.getType() != HitResult.Type.BLOCK) {
            return Optional.empty();
        }
        return Optional.ofNullable(result.getBlockPos());
    }

    /**
     * While falling to a target, we look towards the center and press forwards.
     * However, if we change our direction we end up moving sideways with respect to our look direction, which
     * often messes us up.
     * <p>
     * This will nudge the bot left/right so we're no longer "slipping" to the side.
     */
    private void handleCancellingSidewaysVelocity(AltoClef mod) {
        if (_movingTorwards == null) {
            moveLeftRight(mod, 0);
            return;
        }
        // Cancel our left/right velocity with respect to block
        Vec3 velocity = mod.getPlayer().getDeltaMovement();
        Vec3 deltaTarget = WorldHelper.toVec3d(_movingTorwards).subtract(mod.getPlayer().position());
        // "right" velocity relative to delta
        Rotation look = LookHelper.getLookRotation();
        Vec3 forwardFacing = LookHelper.toVec3d(look).multiply(1, 0, 1).normalize();
        Vec3 rightVelocity = MathsHelper.projectOntoPlane(velocity, forwardFacing).multiply(1, 0, 1); // Flatten
        // Also consider how much further to the right we should move
        Vec3 rightDelta = MathsHelper.projectOntoPlane(deltaTarget, forwardFacing).multiply(1, 0, 1);
        // Do a little PD loop
        Vec3 pd = rightDelta.subtract(rightVelocity.scale(2));
        // We're traveling too fast sideways
        Vec3 faceRight = forwardFacing.cross(new Vec3(0, 1, 0));
        boolean moveRight = pd.dot(faceRight) > 0;
        if (moveRight) {
            moveLeftRight(mod, 1);
        } else {
            moveLeftRight(mod, -1);
        }
    }

    private Optional<BlockPos> getBestConeClutchBlock(AltoClef mod, BlockPos oldClutchTarget) {
        double pitchHalfWidth = _config.epicClutchConePitchAngle;
        double dpitchStart = pitchHalfWidth / _config.epicClutchConePitchResolution;

        // Our priority is:
        // - Safe to land (water)
        // - Highest block
        // IF WE HAVE MLG
        // - Closer to player

        ConeClutchContext cctx = new ConeClutchContext(mod);

        // Always check our previous best so we don't lose it
        if (oldClutchTarget != null)
            cctx.checkBlock(mod, oldClutchTarget);

        // Perform cone
        for (double pitch = dpitchStart; pitch <= pitchHalfWidth; pitch += pitchHalfWidth / _config.epicClutchConePitchResolution) {
            double pitchProgress = (pitch - dpitchStart) / (pitchHalfWidth - dpitchStart);
            double yawResolution = _config.epicClutchConeYawDivisionStart + pitchProgress * (_config.epicClutchConeYawDivisionEnd - _config.epicClutchConeYawDivisionStart); // lerp from start to end
            for (double yaw = 0; yaw < 360; yaw += 360.0 / yawResolution) {
                ClipContext rctx = castCone(yaw, pitch);
                cctx.checkRay(mod, rctx);
            }
        }

        // Perform NEARBY sweep
        //int nearbySweepSize =
        Vec3 center = mod.getPlayer().position();
        for (int dx = -2; dx <= 2; ++dx) {
            for (int dz = -2; dz <= 2; ++dz) {
                ClipContext ctx = castDown(center.add(dx, 0, dz));
                cctx.checkRay(mod, ctx);
            }
        }

        return Optional.ofNullable(cctx.bestBlock);
    }

    private static ClipContext castDown(Vec3 origin) {
        Entity player = Minecraft.getInstance().player;
        assert player != null;
        return new ClipContext(origin, origin.add(0, -1 * _config.castDownDistance, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player);
    }

    private ClipContext castCone(double yaw, double pitch) {
        Entity player = Minecraft.getInstance().player;
        assert player != null;
        Vec3 origin = player.position();
        double dy = _config.epicClutchConeCastHeight;
        double dH = dy * Math.sin(Math.toRadians(pitch)); // horizontal distance
        double yawRad = Math.toRadians(yaw);
        double dx = dH * Math.cos(yawRad);
        double dz = dH * Math.sin(yawRad);
        Vec3 end = origin.add(dx, -1 * dy, dz);
        return new ClipContext(origin, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getClientBaritone().getPathingBehavior().forceCancel();
        _movingTorwards = null;
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        moveLeftRight(mod, 0);
        moveForwardBack(mod, 0);
        mod.getInputControls().release(Input.SPRINT);
        mod.getInputControls().release(Input.JUMP);
    }

    // would the block under us kill us, by the same check the landing picker uses when it has nothing to place. the chain
    // asks so an off-plan deadly fall still gets the steering toward lava or water, even with an empty hand
    public static boolean fallIsDeadly(AltoClef mod) {
        return getBlockWeWillLandOn(mod).map(MLGBucketTask::isFallDeadly).orElse(false);
    }

    // what the fall we are in ends on: hp it costs after armor and effects, how many blocks it is in all, and whether the
    // landing is something water has to fix whatever the damage says (lava, fire, a cactus, a dripstone point)
    public record FallForecast(double damage, double blocks, boolean hazard) {
    }

    // empty when the cast down finds nothing (void), which the chain reads as "clutch". same landing pick and damage math
    // as fallIsDeadly, so the two never disagree about where we come down
    public static Optional<FallForecast> forecast(AltoClef mod) {
        return getBlockWeWillLandOn(mod).map(pos -> {
            BlockState state = mod.getWorld().getBlockState(pos);
            Block block = state.getBlock();
            double damage = calculateFallDamageToLandOn(pos);
            if (block == Blocks.HAY_BLOCK) {
                damage *= 0.2f;
            }
            // coming down in water ends the fall right there, so it is not a long one either
            boolean water = state.getFluidState().is(FluidTags.WATER);
            if (water) {
                damage = 0;
            }
            boolean hazard = block == Blocks.LAVA || block == Blocks.MAGMA_BLOCK || block == Blocks.CACTUS || block == Blocks.POINTED_DRIPSTONE;
            double blocks = water ? 0 : mod.getPlayer().fallDistance + (mod.getPlayer().getY() - pos.getY() - 1);
            return new FallForecast(damage, blocks, hazard);
        });
    }

    // static so the chain can ask before it commits to taking over the fall
    public static boolean hasClutchItem(AltoClef mod) {
        if (!WorldHelper.waterEvaporates(mod.getWorld()) && mod.getItemStorage().hasItem(Items.WATER_BUCKET)) {
            return true;
        }
        return _config.clutchItems.stream().anyMatch(item -> mod.getItemStorage().hasItem(item));
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return landed(mod);
    }

    // down, one way or another. the chain's water pickup starts its clock off this too
    public static boolean landed(AltoClef mod) {
        return mod.getPlayer().isSwimming() || mod.getPlayer().isInWater() || mod.getPlayer().onGround() || mod.getPlayer().onClimbable();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof MLGBucketTask;
    }

    @Override
    protected String toHudString() {
        return "Breaking the fall with water";
    }

    @Override
    protected String toDebugString() {
        String result = "Epic gaemer moment";
        if (_movingTorwards != null) {
            result += " (CLUTCH AT: " + _movingTorwards + ")";
        }
        return result;
    }

    public List<BlockPos> placedCells() {
        return _placedCells;
    }

    private static class MLGClutchConfig {
        public double castDownDistance = 40;
        public double averageHorizontalMovementSpeedPerTick = 0.25; // How "far" the player moves horizontally per tick. Set too low and the bot will ignore viable clutches. Set too high and the bot will go for clutches it can't reach.
        public double epicClutchConeCastHeight = 40; // How high the "epic clutch" ray cone is
        public double epicClutchConePitchAngle = 25; // How wide (degrees) the "epic clutch" ray cone is
        public int epicClutchConePitchResolution = 8; // How many divisions in each direction the cone's pitch has
        public int epicClutchConeYawDivisionStart = 6; // How many divisions to start the cone clutch at in the center
        public int epicClutchConeYawDivisionEnd = 20; // How many divisions to move the cone clutch at torwars the end
        public int preferLavaWhenFallDropsHealthBelowThreshold = 3; // If a fall results in our player's health going below this value, consider it deadly.
        public int lavaLevelOrGreaterWillCancelFallDamage = 5; // Lava at this level will cancel our fall damage if we hold space.
        public List<Item> clutchItems = List.of(Items.HAY_BLOCK, Items.TWISTING_VINES);
    }

    // if it's not lava and is not deadly, land on it if it's higher than before OR if our best alternative is lava or a
    // fall that kills us (only ever set without a clutch item, so the clutch item case is untouched)
    static boolean replacesBest(boolean lava, boolean isDeadlyFall, boolean hasClutchItem, boolean closestSoFar,
                                boolean highestSoFar, boolean bestIsLava, boolean bestIsDeadlyFall) {
        return !lava && !isDeadlyFall && ((closestSoFar && hasClutchItem) && highestSoFar || bestIsLava || bestIsDeadlyFall);
    }

    class ConeClutchContext {
        private final boolean hasClutchItem;
        public BlockPos bestBlock = null;
        private double highestY = Double.NEGATIVE_INFINITY;
        private double closestXZ = Double.POSITIVE_INFINITY;
        private boolean bestBlockIsSafe = false;
        private boolean bestBlockIsDeadlyFall = false;
        private boolean bestBlockIsLava = false;

        public ConeClutchContext(AltoClef mod) {
            hasClutchItem = hasClutchItem(mod);
        }

        public void checkBlock(AltoClef mod, BlockPos check) {
            // Already checked
            if (Objects.equals(bestBlock, check))
                return;
            if (WorldHelper.isAir(mod, check)) {
                Debug.logMessage("(MLG Air block checked for landing, the block broke. We'll try another): " + check);
                return;
            }
            boolean lava = isLava(check);
            boolean lavaWillProtect = lava && lavaWillProtect(check);
            boolean water = isWater(check);
            boolean isDeadlyFall = !hasClutchItem && isFallDeadly(check);
            // Prioritize safe blocks ALWAYS
            if (bestBlockIsSafe && !water)
                return;
            double height = check.getY();
            double distSqXZ = WorldHelper.distanceXZSquared(WorldHelper.toVec3d(check), mod.getPlayer().position());
            boolean highestSoFar = height > highestY;
            boolean closestSoFar = distSqXZ < closestXZ;
            // We found a new contender
            if (
                    bestBlock == null || // No target was found.
                            (water && !bestBlockIsSafe) || // We ALWAYS land in water if we can
                            (lava && lavaWillProtect && bestBlockIsDeadlyFall && !hasClutchItem) || // Land in lava if our best alternative is death by fall damage
                            replacesBest(lava, isDeadlyFall, hasClutchItem, closestSoFar, highestSoFar, bestBlockIsLava, bestBlockIsDeadlyFall)
            ) {
                if (canTravelToInAir((lava || water) ? check.below() : check)) {
                    if (highestSoFar) {
                        highestY = height;
                    }
                    if (closestSoFar) {
                        closestXZ = distSqXZ;
                    }
                    bestBlockIsSafe = water;
                    bestBlockIsDeadlyFall = isDeadlyFall;
                    bestBlockIsLava = lava;
                    bestBlock = check;
                }
            }
        }

        public void checkRay(AltoClef mod, ClipContext rctx) {
            BlockHitResult hit = mod.getWorld().clip(rctx);
            if (hit.getType() == HitResult.Type.BLOCK) {
                BlockPos check = hit.getBlockPos();
                // For now, REQUIRE we land on this
                if (hit.getDirection().getStepY() <= 0)
                    return;
                checkBlock(mod, check);
            }
        }
    }

}
