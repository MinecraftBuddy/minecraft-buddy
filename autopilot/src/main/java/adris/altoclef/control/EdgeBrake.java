package adris.altoclef.control;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import baritone.api.utils.input.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// backstop for a hard cancel that lands mid sneak-bridge: with no path segment nobody holds sneak, the last bit of
// momentum carries the bot off the unfinished end. this only holds sneak (vanilla backs off edges on its own) while the
// bot is sliding toward a drop with nobody steering it
public final class EdgeBrake {
    // our own pin owner, so the magma sneak's pin on the same key is never touched
    public static final Object OWNER = new Object();

    // a soft shove on a rim is well under walking speed and still walks us off, so this only has to beat the noise
    private static final double START_SPEED = 0.01;
    // the jar zeroes a player's flat speed under 0.003 (LivingEntity.aiStep), letting go any sooner hands the tail back its slide
    private static final double STOP_SPEED = 0.003;
    // ground friction slide is about 2.2x the speed, 3 is margin
    private static final double SLIDE_TICKS = 3;
    private static final long LOG_GAP_MS = 2000;

    private boolean _engaged;
    private long _lastLog;

    // pure half. the gates have to all hold either way, the hysteresis is only in the speed and the ground ahead
    public static boolean decide(boolean wasEngaged, boolean userTask, boolean onGround, boolean blocked, boolean pathing,
                                 boolean realMoveKey, double speed, boolean supportedAhead, boolean dropAhead) {
        if (!userTask || !onGround || blocked || pathing || realMoveKey) {
            return false;
        }
        if (wasEngaged) {
            return speed >= STOP_SPEED && !supportedAhead;
        }
        return speed > START_SPEED && !supportedAhead && dropAhead;
    }

    // every tick, outside the task runner block, so a gate going false always lets go
    public void tick(AltoClef mod, boolean userTask) {
        InputControls keys = mod.getInputControls();
        LocalPlayer player = mod.getPlayer();
        if (player == null || mod.getWorld() == null) {
            letGo(keys);
            return;
        }
        boolean pathing = mod.getClientBaritone().getPathingBehavior().isPathing();
        Vec3 vel = player.getDeltaMovement();
        double speed = Math.hypot(vel.x, vel.z);
        boolean realMoveKey = keys.isHeldDown(Input.MOVE_FORWARD) || keys.isHeldDown(Input.MOVE_BACK)
                || keys.isHeldDown(Input.MOVE_LEFT) || keys.isHeldDown(Input.MOVE_RIGHT);
        boolean blocked = isBlocked(mod, player);
        boolean gatesOpen = userTask && player.onGround() && !blocked && !pathing && !realMoveKey;
        // the world lookups are not free, skip them for a bot that is standing still or not ours to brake
        boolean supported = true;
        boolean drop = false;
        if (gatesOpen && speed >= STOP_SPEED) {
            Level level = mod.getWorld();
            AABB ahead = player.getBoundingBox().move(vel.x * SLIDE_TICKS, 0, vel.z * SLIDE_TICKS);
            int y = (int) Math.floor(player.getY() - 0.01);
            supported = supportedUnder(level, ahead, y);
            drop = !supported && dropUnder(level, ahead.getCenter(), y);
        }
        boolean engage = decide(_engaged, userTask, player.onGround(), blocked, pathing, realMoveKey, speed,
                supported, drop);
        if (engage) {
            keys.pin(Input.SNEAK, OWNER);
            long now = System.currentTimeMillis();
            if (!_engaged && now - _lastLog >= LOG_GAP_MS) {
                _lastLog = now;
                Debug.logInternal("edge brake: no path, drifting toward a drop");
            }
        } else {
            letGo(keys);
        }
        _engaged = engage;
    }

    // also called on world leave and shutdown: no tick runs out of game, so the null player branch never gets to
    public void letGo(InputControls keys) {
        // never touches a sneak we didn't pin
        if (keys != null && keys.isPinnedBy(Input.SNEAK, OWNER)) {
            keys.unpin(Input.SNEAK, OWNER);
        }
        _engaged = false;
    }

    private static boolean isBlocked(AltoClef mod, LocalPlayer player) {
        if (player.isInWater() || player.isInLava() || player.isPassenger() || player.getAbilities().flying
                || player.isFallFlying() || player.onClimbable()) {
            return true;
        }
        return mod.getWorld().getBlockState(player.blockPosition()).is(Blocks.POWDER_SNOW);
    }

    // any of the corner columns of the projected box has something to stand on at foot level
    private static boolean supportedUnder(Level level, AABB box, int y) {
        int x0 = (int) Math.floor(box.minX);
        int x1 = (int) Math.floor(box.maxX);
        int z0 = (int) Math.floor(box.minZ);
        int z1 = (int) Math.floor(box.maxZ);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x : new int[]{x0, x1}) {
            for (int z : new int[]{z0, z1}) {
                pos.set(x, y, z);
                if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    // the column under the projected centre: a drop is no floor within 4 blocks of the feet, or fire/lava/magma on the way
    // down. water and portals and powder snow count as a floor, a one block step down never trips it
    private static boolean dropUnder(Level level, Vec3 center, int y) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int x = (int) Math.floor(center.x);
        int z = (int) Math.floor(center.z);
        for (int dy = 0; dy < 4; dy++) {
            pos.set(x, y - dy, z);
            BlockState state = level.getBlockState(pos);
            if (state.getFluidState().is(FluidTags.LAVA) || state.getBlock() instanceof BaseFireBlock
                    || state.is(Blocks.MAGMA_BLOCK)) {
                return true;
            }
            if (isFloor(level, pos, state)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFloor(Level level, BlockPos pos, BlockState state) {
        return !state.getCollisionShape(level, pos).isEmpty() || state.getFluidState().is(FluidTags.WATER)
                || state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL) || state.is(Blocks.POWDER_SNOW);
    }
}
