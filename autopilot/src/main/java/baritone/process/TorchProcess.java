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

import baritone.Baritone;
import baritone.altoclef.AltoClefBridge;
import baritone.altoclef.AltoClefSettings;
import baritone.api.pathing.movement.IMovement;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.path.PathExecutor;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.InputOverrideHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

// #autoTorch: lights up the dark spots around us while some other process does the actual work. port of upstream's
// TorchProcess (#5118), minus the bits that would click whatever was in front of the crosshair
public final class TorchProcess extends BaritoneProcessHelper {

    // how many movements ahead we look for blocks the path still wants to place or break
    private static final int PATH_LOOKAHEAD = 20;

    private final TorchMemory memory = new TorchMemory();
    // memory runs on player ticks. a respawn or a new world is a new player counting from 0, so memory starts over with it.
    // weak so a world we left doesn't hang around in here until the next join
    private WeakReference<LocalPlayer> player = new WeakReference<>(null);
    // we forced CLICK_RIGHT and still owe the release
    private boolean clicking;
    private long pauseAskedAt = Long.MIN_VALUE;
    // whether that ask came while the movement said it was safe, only those can be stuck, see pauseStuck
    private boolean pauseAskedSafe;
    private long scannedAt = Long.MIN_VALUE;
    private Spot spot;

    private record Spot(BlockPos cell, BlockPos support, Direction face, Rotation rotation) {
    }

    // what the path still needs, as BlockPos.asLong. BetterBlockPos hashes differently from BlockPos, longs don't care
    private record PathCells(Set<Long> places, Set<Long> breaks) {
    }

    public TorchProcess(Baritone baritone) {
        super(baritone);
    }

    // the version specific line: 1.21.4 has no copper torch
    public static boolean isTorch(ItemStack stack) {
        return stack.is(Items.TORCH) || stack.is(Items.SOUL_TORCH) || stack.is(Items.COPPER_TORCH);
    }

    @Override
    public boolean isActive() {
        // first, before anything can return early: whatever happens this tick, last tick's click has had its turn
        releaseClick();
        if (ctx.player() == null || ctx.world() == null || !Baritone.settings().autoTorch.value) {
            return false;
        }
        long now = now();
        judgeLastClick(now);
        return !memory.coolingDown(now) && bodyFree() && placeFits() && hasTorch() && AltoClefBridge.torchingAllowed();
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        // we only help someone else's job along. and while something holds left click it's digging, aiming at our support
        // now would have BlockBreakHelper mine it out from under the torch. torches go in the gaps between digs
        if (!baritone.getPathingControlManager().otherNonTemporaryActive(this) || handsTaken()) {
            return drop();
        }
        long now = now();
        Spot target = spot(now);
        if (target == null) {
            return drop();
        }
        if (!isSafeToCancel) {
            // the pause only lands once the movement says it's safe. grabbing the camera mid jump would be rude
            return pause(now, false);
        }
        if (pauseStuck(now)) {
            // let the real process tick and get a new path going, we come back for a look in a few ticks
            memory.foundNothing(now);
            return drop();
        }
        boolean landed = pauseAskedAt == now - 1 && baritone.getPathingBehavior().pausedLastTick();
        if (!memory.settled(now, landed, horizontalSpeed())) {
            // keep holding the pause or the path walks off again. no aiming yet, the eye is still moving
            return pause(now, true);
        }
        baritone.getLookBehavior().updateTarget(target.rotation(), true);
        if (memory.aimTimedOut(target.cell().asLong(), now)) {
            return drop();
        }
        // picked on the aim ticks, so a torch coming up from the inventory has landed by the time we click
        selectTorch(true);
        if (readyToClick(target, now)) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            clicking = true;
            memory.clicked(target.cell().asLong(), now);
            spot = null;
        }
        return pause(now, true);
    }

    @Override
    public void onLostControl() {
        releaseClick();
        memory.stopAiming();
        spot = null;
    }

    @Override
    public String displayName0() {
        return "Placing torches";
    }

    @Override
    public boolean isTemporary() {
        return true;
    }

    // above the -1 jobs so we get to pause them, under self defense (-0.4, a zombie beats a dark corner) and #pause and
    // elytra (0). upstream had 5, which placed torches straight through #pause and grabbed the camera mid flight
    @Override
    public double priority() {
        return -0.5;
    }

    private long now() {
        if (ctx.player() != player.get()) {
            player = new WeakReference<>(ctx.player());
            memory.reset();
            spot = null;
            scannedAt = Long.MIN_VALUE;
            pauseAskedAt = Long.MIN_VALUE;
        }
        return ctx.player().tickCount;
    }

    // a path that gave up mid movement leaves PathingBehavior's safe to cancel flag stuck on false with nothing to tick
    // it back, while isSafeToCancel says yes because there's no path. asking for a pause then just holds the real process
    // off forever without the pause ever landing
    private boolean pauseStuck(long now) {
        return pauseAskedAt == now - 1 && pauseAskedSafe && !baritone.getPathingBehavior().pausedLastTick() && baritone.getPathingBehavior().getCurrent() == null;
    }

    private PathingCommand pause(long now, boolean safe) {
        pauseAskedAt = now;
        pauseAskedSafe = safe;
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private static PathingCommand defer() {
        return new PathingCommand(null, PathingCommandType.DEFER);
    }

    // the target only sticks while we're the ones working on it. after someone else had the wheel we're somewhere else,
    // pick fresh
    private PathingCommand drop() {
        memory.stopAiming();
        spot = null;
        return defer();
    }

    private double horizontalSpeed() {
        Vec3 motion = ctx.player().getDeltaMovement();
        return Math.sqrt(motion.x * motion.x + motion.z * motion.z);
    }

    private void releaseClick() {
        if (clicking) {
            clicking = false;
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        }
    }

    private void judgeLastClick(long now) {
        OptionalLong cell = memory.due(now);
        if (cell.isPresent()) {
            // anything in the cell counts. if it's not our torch then gravel or the player filled it, it's off the list anyway
            memory.verdict(!ctx.world().getBlockState(BlockPos.of(cell.getAsLong())).isAir(), now);
        }
        memory.prune(now);
    }

    // a torch only goes down while we're standing on solid ground with our hands free
    private boolean bodyFree() {
        LocalPlayer p = ctx.player();
        return p.onGround() && !p.isFallFlying() && !p.isInWater() && !p.isInLava() && !p.onClimbable() && !p.isPassenger()
                && !p.isSleeping() && !p.isUsingItem() && !p.isBlocking() && !p.isSpectator() && p.getAbilities().mayBuild;
    }

    // the nether's block light only goes to 15 and torches don't stop anything spawning there, and the end is the dragon
    // fight with nothing to mine. builds are left alone, a torch is a block the schematic didn't ask for. and no bucket
    // in the off hand: a click the main hand can't place falls through to it, that's a lava bucket emptied in the tunnel
    private boolean placeFits() {
        Level world = ctx.world();
        return world.dimension() != Level.NETHER && world.dimension() != Level.END
                && ctx.minecraft().gui.screen() == null
                && !baritone.getBuilderProcess().isActive()
                && !(ctx.player().getItemBySlot(EquipmentSlot.OFFHAND).getItem() instanceof BucketItem);
    }

    private boolean hasTorch() {
        return selectTorch(false);
    }

    private boolean selectTorch(boolean select) {
        return baritone.getInventoryBehavior().throwaway(select, TorchProcess::isTorch);
    }

    private boolean handsTaken() {
        InputOverrideHandler keys = baritone.getInputOverrideHandler();
        // our own right click was let go of in isActive, so a held one now belongs to someone else
        return keys.isInputForcedDown(Input.CLICK_LEFT) || keys.isInputForcedDown(Input.CLICK_RIGHT);
    }

    // only on a tick after one where we asked for the pause and it really landed. the tick it lands PathingBehavior
    // clears every key, our click included, see pausedLastTick
    private boolean readyToClick(Spot target, long now) {
        if (pauseAskedAt != now - 1 || !baritone.getPathingBehavior().pausedLastTick()) {
            return false;
        }
        if (!baritone.getInputOverrideHandler().getBlockPlaceHelper().ready() || baritone.getInventoryBehavior().handWaitingOnSwap()) {
            return false;
        }
        return hits(ctx.objectMouseOver(), target.support(), target.face());
    }

    private static boolean hits(HitResult hit, BlockPos support, Direction face) {
        return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK
                && block.getBlockPos().equals(support) && block.getDirection() == face;
    }

    // onTick can come round more than once a tick, one scan is enough. the target we're on sticks while it still works,
    // a fresh scan every tick can flip to another cell as we slide and the aim never gets to finish on either
    private Spot spot(long now) {
        if (scannedAt == now) {
            return spot;
        }
        scannedAt = now;
        if (spot != null) {
            spot = recheck(spot, now);
            if (spot != null) {
                return spot;
            }
        }
        if (!memory.scanDue(now)) {
            return null;
        }
        spot = findSpot(now);
        if (spot == null) {
            memory.foundNothing(now);
        }
        return spot;
    }

    // everything the scan asked, for just this cell, support and face, raytrace included from where the eye is now. comes
    // back with the rotation redone, null once it doesn't hold any more
    private Spot recheck(Spot old, long now) {
        double reach = ctx.playerController().getBlockReachDistance();
        BlockPos cell = old.cell();
        if (ctx.playerHead().distanceToSqr(Vec3.atCenterOf(cell)) > reach * reach) {
            return null;
        }
        int radius = TorchRules.radius(Baritone.settings().autoTorchRadius.value, reach);
        PathCells path = pathCells(ctx.playerFeet(), radius);
        int level = TorchRules.clampLevel(Baritone.settings().autoTorchLightLevel.value);
        if (!wantsTorch(cell, level, now, path) || !survives(cell, old.face())) {
            return null;
        }
        return aimAt(cell, old.support(), old.face(), path, reach);
    }

    private Spot findSpot(long now) {
        double reach = ctx.playerController().getBlockReachDistance();
        int radius = TorchRules.radius(Baritone.settings().autoTorchRadius.value, reach);
        int level = TorchRules.clampLevel(Baritone.settings().autoTorchLightLevel.value);
        BetterBlockPos feet = ctx.playerFeet();
        Vec3 eyes = ctx.playerHead();
        PathCells path = pathCells(feet, radius);
        List<BlockPos> cells = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos cell = feet.offset(dx, dy, dz);
                    if (eyes.distanceToSqr(Vec3.atCenterOf(cell)) <= reach * reach && wantsTorch(cell, level, now, path)) {
                        cells.add(cell);
                    }
                }
            }
        }
        cells.sort(Comparator.comparingDouble(feet::distSqr));
        for (BlockPos cell : cells) {
            Spot found = aim(cell, path, reach);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private boolean wantsTorch(BlockPos cell, int level, long now, PathCells path) {
        Level world = ctx.world();
        if (!world.getBlockState(cell).isAir()
                || !TorchRules.dark(world.getBrightness(LightLayer.BLOCK, cell), world.getBrightness(LightLayer.SKY, cell), level)) {
            return false;
        }
        long key = cell.asLong();
        // cells the path digs through are fine, a torch is walk through and the path won't break it
        return !memory.skip(key, now) && !path.places().contains(key)
                && world.getWorldBorder().isWithinBounds(cell) && !AltoClefSettings.getInstance().shouldAvoidPlacingAt(cell)
                && !nearFluid(cell) && !occupied(cell);
    }

    // not where we're standing. straight down into our own cell is the worst aim there is, the yaw means nothing and every
    // bit of slide swings it round. in a 1x2 tunnel that leaves the cells behind us, same as a player would do it
    private boolean occupied(BlockPos cell) {
        AABB box = ctx.player().getBoundingBox();
        return TorchRules.overlaps(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, cell.getX(), cell.getY(), cell.getZ());
    }

    // water washes a torch off, lava burns it, and the raytrace looks straight through both
    private boolean nearFluid(BlockPos cell) {
        if (!ctx.world().getFluidState(cell).isEmpty()) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            if (!ctx.world().getFluidState(cell.relative(direction)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // floor first, then the walls. canSurvive is the game's own answer per face, so no guessing what a torch sticks to
    private Spot aim(BlockPos cell, PathCells path, double reach) {
        if (survives(cell, Direction.UP)) {
            Spot floor = aimAt(cell, cell.below(), Direction.UP, path, reach);
            if (floor != null) {
                return floor;
            }
        }
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            if (survives(cell, facing)) {
                Spot wall = aimAt(cell, cell.relative(facing.getOpposite()), facing, path, reach);
                if (wall != null) {
                    return wall;
                }
            }
        }
        return null;
    }

    // up is a floor torch, a side is a wall torch on the block behind that face
    private boolean survives(BlockPos cell, Direction face) {
        return face == Direction.UP ? Blocks.TORCH.defaultBlockState().canSurvive(ctx.world(), cell) : WallTorchBlock.canSurvive(ctx.world(), cell, face);
    }

    // same raytrace precheck as findPlace, but it has to be this exact block and this exact face. findPlace takes any
    // face that ends up in the cell, and its crosshair fallback clicks whatever we happen to be looking at
    private Spot aimAt(BlockPos cell, BlockPos support, Direction face, PathCells path, double reach) {
        if (!goodSupport(support, path)) {
            return null;
        }
        Vec3 target = Vec3.atCenterOf(support).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        Rotation rotation = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), target, ctx.playerRotations());
        Rotation actual = baritone.getLookBehavior().getAimProcessor().peekRotation(rotation);
        HitResult hit = RayTraceUtils.rayTraceTowards(ctx.player(), actual, reach, false);
        return hits(hit, support, face) ? new Spot(cell, support, face, rotation) : null;
    }

    // no support the click would open instead of placing on, and none the path is about to dig out, that falls, or that
    // is the ore we're mining. those last three take the torch down with them and drop it on the floor
    private boolean goodSupport(BlockPos support, PathCells path) {
        BlockState state = ctx.world().getBlockState(support);
        return !path.breaks().contains(support.asLong()) && !fallsAway(support, state, path) && !mining(state)
                && ctx.world().getWorldBorder().isWithinBounds(support) && !AltoClefSettings.getInstance().shouldAvoidPlacingAt(support)
                && !MovementHelper.opensWhenClicked(state, ctx.world(), support);
    }

    // sand and gravel hold a torch just fine while something holds them up, caves are full of gravel floors. one hanging
    // over air (or over a block the path is about to dig) drops the moment our torch's block update pokes it
    private boolean fallsAway(BlockPos support, BlockState state, PathCells path) {
        if (!(state.getBlock() instanceof FallingBlock)) {
            return false;
        }
        BlockPos below = support.below();
        return path.breaks().contains(below.asLong()) || FallingBlock.isFree(ctx.world().getBlockState(below));
    }

    private boolean mining(BlockState state) {
        BlockOptionalMetaLookup filter = baritone.getMineProcess().filter();
        return filter != null && filter.has(state);
    }

    // blocks the rest of the path places, and the ones it breaks (so the supports it would dig out from under a torch).
    // only the nearby stretch of this segment and the queued next one, a movement further off than the scan can't touch
    // anything we'd torch
    private PathCells pathCells(BetterBlockPos feet, int radius) {
        Set<Long> places = new HashSet<>();
        Set<Long> breaks = new HashSet<>();
        int budget = walk(baritone.getPathingBehavior().getCurrent(), feet, radius, PATH_LOOKAHEAD, places, breaks);
        if (budget > 0) {
            walk(baritone.getPathingBehavior().getNext(), feet, radius, budget, places, breaks);
        }
        return new PathCells(places, breaks);
    }

    // returns how much of the lookahead is left, 0 once a movement wandered out of range
    private static int walk(PathExecutor exec, BetterBlockPos feet, int radius, int budget, Set<Long> places, Set<Long> breaks) {
        if (exec == null || exec.finished() || exec.failed()) {
            return budget;
        }
        List<IMovement> movements = exec.getPath().movements();
        int far = radius + 2;
        for (int i = Math.max(0, exec.getPosition()); i < movements.size() && budget > 0; i++, budget--) {
            Movement movement = (Movement) movements.get(i);
            if (movement.getSrc().distSqr(feet) > far * far) {
                return 0;
            }
            if (movement.getPositionToPlace() != null) {
                places.add(movement.getPositionToPlace().asLong());
            }
            for (BlockPos pos : movement.toBreakAll()) {
                breaks.add(pos.asLong());
            }
        }
        return budget;
    }
}
