package baritone.pathing.movement;

import adris.altoclef.Debug;
import baritone.altoclef.AltoClefSettings;
import baritone.api.IBaritone;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.behavior.LookBehavior;
import baritone.pathing.movement.MovementHelper.PlaceResult;
import baritone.utils.BlockStateInterface;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

// the once per stalled bridge dump (MovementTraverse.bridgeStall). a place result alone can't say why no block ever
// went down, so this says what each place candidate saw, where the head really pointed, who asked it to point there, and
// whether a click went out. reads only, no side effects
public final class BridgeStallLog {

    private BridgeStallLog() {
    }

    public static void log(IBaritone baritone, BlockPos src, BlockPos dest, MovementState state, PlaceResult place, int ticks, boolean wouldSneak) {
        IPlayerContext ctx = baritone.getPlayerContext();
        LocalPlayer player = ctx.player();
        BlockPos placeAt = dest.below();
        BlockPos goalLook = src.below();
        Vec3 eye = ctx.playerHead();
        Vec3 sneakEye = RayTraceUtils.inferSneakingEyePosition(player);
        // same face and dist2 as the back place branch in MovementTraverse.bridge
        double faceX = (dest.getX() + src.getX() + 1.0D) * 0.5D;
        double faceZ = (dest.getZ() + src.getZ() + 1.0D) * 0.5D;
        double dist2 = Math.max(Math.abs(player.getX() - faceX), Math.abs(player.getZ() - faceZ));
        Rotation real = ctx.playerRotations();
        Rotation target = state.getTarget().getRotation().orElse(null);
        Debug.logInternal(String.format(Locale.ROOT,
                "bridge stall: %d ticks still over the drop at %s, place %s, pos %.3f %.3f %.3f, eye %.3f %.3f %.3f, sneak eye y %.3f, dist2 %.3f, crouching %b, shift %b, pose %s",
                ticks, dest.toShortString(), place, player.getX(), player.getY(), player.getZ(), eye.x, eye.y, eye.z, sneakEye.y, dist2,
                player.isCrouching(), player.isShiftKeyDown(), player.getPose()));
        Debug.logInternal(String.format(Locale.ROOT,
                "bridge stall: player yaw/pitch %s, playerRotations %s, movement target %s, really close %b, looking at %s %b, click right %b, click left %b",
                rot(player.getYRot(), player.getXRot()), rot(real), target == null ? "none" : rot(target),
                target != null && real.isReallyCloseTo(target), goalLook.toShortString(), ctx.isLookingAt(goalLook),
                state.getInputStates().getOrDefault(Input.CLICK_RIGHT, false), state.getInputStates().getOrDefault(Input.CLICK_LEFT, false)));
        Debug.logInternal("bridge stall: mouse over " + hit(ctx.objectMouseOver()) + ", look " + ((LookBehavior) baritone.getLookBehavior()).lookTrace());
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        for (int i = 0; i < 5; i++) {
            Debug.logInternal("bridge stall: candidate " + candidate(baritone, ctx, bsi, placeAt, Movement.HORIZONTALS_BUT_ALSO_DOWN_____SO_EVERY_DIRECTION_EXCEPT_UP[i], wouldSneak));
        }
    }

    // the findPlace loop body, said out loud: which half of canPlaceAgainst said no, and where the peeked ray landed
    private static String candidate(IBaritone baritone, IPlayerContext ctx, BlockStateInterface bsi, BlockPos placeAt, Direction dir, boolean wouldSneak) {
        BlockPos against = placeAt.relative(dir);
        BlockState state = bsi.get0(against);
        boolean cube = MovementHelper.isBlockNormalCube(state) || state.getBlock() == Blocks.GLASS
                || state.getBlock() instanceof StainedGlassBlock;
        boolean avoid = AltoClefSettings.getInstance().shouldAvoidPlacingAt(against.getX(), against.getY(), against.getZ());
        boolean border = bsi.worldBorder.canPlaceAt(against.getX(), against.getZ());
        String head = String.format(Locale.ROOT, "%s %s %s cube %b avoid %b border %b",
                dir, against.toShortString(), BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath(), cube, avoid, border);
        if (!MovementHelper.canPlaceAgainst(bsi, against)) {
            return head + " -> can't place against";
        }
        double faceX = (placeAt.getX() + against.getX() + 1.0D) * 0.5D;
        double faceY = (placeAt.getY() + against.getY() + 0.5D) * 0.5D;
        double faceZ = (placeAt.getZ() + against.getZ() + 1.0D) * 0.5D;
        Vec3 from = wouldSneak ? RayTraceUtils.inferSneakingEyePosition(ctx.player()) : ctx.playerHead();
        Rotation want = RotationUtils.calcRotationFromVec3d(from, new Vec3(faceX, faceY, faceZ), ctx.playerRotations());
        Rotation peeked = baritone.getLookBehavior().getAimProcessor().peekRotation(want);
        HitResult res = RayTraceUtils.rayTraceTowards(ctx.player(), peeked, ctx.playerController().getBlockReachDistance(), wouldSneak);
        boolean ok = res instanceof BlockHitResult b && res.getType() == HitResult.Type.BLOCK && b.getBlockPos().equals(against)
                && b.getBlockPos().relative(b.getDirection()).equals(placeAt);
        return head + " want " + rot(want) + " peeked " + rot(peeked) + " hit " + hit(res) + " -> " + (ok ? "ok" : "miss");
    }

    private static String hit(HitResult res) {
        if (res == null) {
            return "null";
        }
        if (res instanceof BlockHitResult b && res.getType() == HitResult.Type.BLOCK) {
            return "block " + b.getBlockPos().toShortString() + " " + b.getDirection();
        }
        return res.getType().name().toLowerCase(Locale.ROOT);
    }

    private static String rot(Rotation r) {
        return rot(r.getYaw(), r.getPitch());
    }

    private static String rot(float yaw, float pitch) {
        return String.format(Locale.ROOT, "%.3f/%.3f", yaw, pitch);
    }
}
