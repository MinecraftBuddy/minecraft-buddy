package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import baritone.Baritone;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.utils.BetterBlockPos;
import baritone.behavior.InventoryBehavior;
import baritone.pathing.movement.movements.LandingWater;
import baritone.pathing.movement.movements.MovementFall;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// the fall nobody planned (knockback, a slip, a mob), with no water to put down: hang a ladder or vine on a wall next to the
// column. baritone's MovementFall already does all of that for a fall it planned (when to click, where to aim, fetching the
// ladder after) so this just builds one for the floor under us and lets it drive, see MovementFall.unplanned
public class LadderClutchFallTask extends Task {

    // how far down we look for the floor. a ladder placed that far up is no good anyway, LadderClutch says so
    private static final double LOOK_DOWN = 256;

    // a fall is seconds and the ladder pickup has its own clock. this chain sits above the user's task and holds the wheel till we
    // say done, so whatever goes wrong in here it doesn't get to park the bot for the rest of the game
    private static final int MAX_TICKS = 600;

    private final MovementFall fall;
    private boolean done;
    private int ticks;

    private LadderClutchFallTask(MovementFall fall) {
        this.fall = fall;
    }

    // a task for this fall if a ladder or vine can save it from where we are, otherwise null (and nothing was touched,
    // other than a ladder that was in the main inventory moving to the hotbar, that's what the clutch has to click from)
    public static LadderClutchFallTask probe(AltoClef mod) {
        if (!mod.getClientBaritone().getInventoryBehavior().clutchAllowed()) {
            return null;
        }
        LocalPlayer player = mod.getPlayer();
        if (player.isFallFlying() || player.isPassenger()) {
            return null;
        }
        InventoryBehavior inv = mod.getClientBaritone().getInventoryBehavior();
        if (inv.pickClutchItem(false) == null) {
            // the hotbar is all the click can use, and baritone only tidies it up while pathing. a fall doesn't wait for
            // the usual inventory move delay, this goes now and the next tick's probe finds it
            inv.fetchClutchItemNow();
            return null;
        }
        BlockPos landing = landingCell(mod, player.position());
        if (landing == null || waterSavesIt(mod)) {
            return null;
        }
        MovementFall fall = MovementFall.unplanned(mod.getClientBaritone(), new BetterBlockPos(landing), (int) (player.getY() - landing.getY()));
        return fall.canClutch() ? new LadderClutchFallTask(fall) : null;
    }

    // the cell we'd end up standing in if we just kept falling, null if that's water or lava, a floor that isn't a full
    // block (slabs and carpets put the floor somewhere the clutch math doesn't know about) or nothing at all
    private static BlockPos landingCell(AltoClef mod, Vec3 pos) {
        ClipContext clip = new ClipContext(pos, pos.add(0, -LOOK_DOWN, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, mod.getPlayer());
        BlockHitResult hit = mod.getWorld().clip(clip);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockState floor = mod.getWorld().getBlockState(hit.getBlockPos());
        if (!floor.getFluidState().isEmpty() || floor.getCollisionShape(mod.getWorld(), hit.getBlockPos()).max(Direction.Axis.Y) != 1.0) {
            return null;
        }
        return hit.getBlockPos().above();
    }

    // the center ray only sees the water right under the middle of us, the hitbox sits in up to four columns
    private static boolean waterSavesIt(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        return LandingWater.landsInWater(mod.getWorld(), player.getX(), player.getY(), player.getZ(), player.getDeltaMovement().y, (int) LOOK_DOWN);
    }

    @Override
    protected void onStart(AltoClef mod) {
        // the executor would run the movement it still thinks is current right over ours
        mod.getClientBaritone().getPathingBehavior().forceCancel();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        // the floor we probed is a guess: a hit pushes us over a stream, or water flows in under us. nothing on the wall
        // yet means nothing to fetch, so just let go. once something's up updateUnplanned sees the water and goes for it
        if (++ticks > MAX_TICKS) {
            Debug.logInternal("ladder clutch: still not done after " + MAX_TICKS / 20 + " s, letting go");
            mod.getClientBaritone().getInputOverrideHandler().clearAllKeys();
            done = true;
            return null;
        }
        LocalPlayer player = mod.getPlayer();
        boolean airborne = !player.onGround() && !player.isInWater() && player.getDeltaMovement().y < 0;
        if (airborne && !fall.clutchClicked() && waterSavesIt(mod)) {
            mod.getClientBaritone().getInputOverrideHandler().clearAllKeys();
            done = true;
            return null;
        }
        MovementStatus status = fall.update();
        if (status.isComplete()) {
            done = true;
        }
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getClientBaritone().getInputOverrideHandler().clearAllKeys();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return done;
    }

    public boolean finished() {
        return done;
    }

    // the chain hands the same instance back every tick, and a fresh one for the next fall has to replace a finished one
    @Override
    protected boolean isEqual(Task other) {
        return other == this;
    }

    @Override
    protected String toHudString() {
        return "Hanging a ladder to break the fall";
    }

    @Override
    protected String toDebugString() {
        return "Ladder clutch";
    }
}
