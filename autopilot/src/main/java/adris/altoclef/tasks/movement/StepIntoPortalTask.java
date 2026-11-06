package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.ArrivalRules;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.input.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

// a portal dumped us next to lava: walk straight back into it instead of planning anything (the planner has nothing to go on
// this early) and then stand still until it sends us home. the wait lives in ArrivalRules, it has to outlast the transition
public class StepIntoPortalTask extends Task {

    private final BlockPos portal;
    private int waited;
    private int backoff;

    public StepIntoPortalTask(BlockPos portal) {
        this.portal = portal;
    }

    @Override
    protected void onStart(AltoClef mod) {
        if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
            mod.getClientBaritone().getPathingBehavior().forceCancel();
        }
        mod.getClientBaritone().getCustomGoalProcess().onLostControl();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (backoff > 0) {
            // standing in a portal that is still on cooldown keeps the cooldown up, so we leave until it runs down
            backoff--;
            mod.getInputControls().hold(Input.MOVE_BACK);
            mod.getInputControls().release(Input.MOVE_FORWARD);
            return null;
        }
        mod.getInputControls().release(Input.MOVE_BACK);
        if (WorldHelper.isStandingInPortal(mod)) {
            // the teleport needs us to stay put
            release(mod);
            if (++waited > ArrivalRules.PORTAL_WAIT_TICKS) {
                waited = 0;
                backoff = ArrivalRules.PORTAL_BACKOFF_TICKS;
            }
            return null;
        }
        float yaw = LookHelper.getLookRotation(mod, new Vec3(portal.getX() + 0.5, portal.getY() + 0.5, portal.getZ() + 0.5)).getYaw();
        mod.getInputControls().forceLook(yaw, mod.getPlayer().getXRot());
        mod.getInputControls().hold(Input.MOVE_FORWARD);
        mod.getInputControls().hold(Input.JUMP);
        return null;
    }

    private static void release(AltoClef mod) {
        mod.getInputControls().release(Input.MOVE_FORWARD);
        mod.getInputControls().release(Input.JUMP);
        mod.getInputControls().release(Input.MOVE_BACK);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        release(mod);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof StepIntoPortalTask t && t.portal.equals(portal);
    }

    @Override
    protected String toHudString() {
        return "Stepping back into the portal";
    }

    @Override
    protected String toDebugString() {
        return "Stepping back into the portal at " + portal.toShortString();
    }
}
