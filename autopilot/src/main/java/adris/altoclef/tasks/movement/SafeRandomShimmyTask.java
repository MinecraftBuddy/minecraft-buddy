package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.input.Input;

/**
 * Will move around randomly while holding shift
 * Used to escape weird situations where baritone doesn't work.
 */
public class SafeRandomShimmyTask extends Task {

    private final TimerGame _lookTimer;

    public SafeRandomShimmyTask(float randomLookInterval) {
        _lookTimer = new TimerGame(randomLookInterval);
    }

    public SafeRandomShimmyTask() {
        this(5);
    }

    @Override
    protected void onStart(AltoClef mod) {
        _lookTimer.reset();
    }

    @Override
    protected Task onTick(AltoClef mod) {

        if (_lookTimer.elapsed()) {
            Debug.logMessage("Random Orientation");
            _lookTimer.reset();
            LookHelper.randomOrientation(mod);
        }

        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
        // (the buddy's fork: no step and no click with a drop or lava beside the feet. on a fortress platform over a lava sea the
        // shimmy broke the rim's fence and walked off it, twice in the blaze drills, 4 Nov 2026; the sneak alone did not hold it)
        // (and none at all in a blaze fight with a corner: its random clicks dug a pit in a fortress platform, two deep, that the
        // run could not leave, and it stood there in the fire, corner17 run 4)
        net.minecraft.core.BlockPos fight = adris.altoclef.util.helpers.BlazeShelter.fresh(mod.getWorld().getGameTime());
        boolean edge = nearDrop(mod) || (fight != null && mod.getPlayer().position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(fight))
                <= adris.altoclef.util.helpers.BlazeShelter.RANGE);
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, !edge);
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, !edge);
        if (edge) setDebugState("Shimmying held: a drop beside us");
        return null;
    }

    // a cell round the feet (or under them) with no floor in DROP blocks below, or lava or fire there
    static final int DROP = 4;

    static boolean nearDrop(AltoClef mod) {
        var level = mod.getWorld();
        net.minecraft.core.BlockPos feet = mod.getPlayer().blockPosition();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                boolean floor = false;
                for (int dy = 0; dy >= -DROP && !floor; dy--) {
                    net.minecraft.core.BlockPos p = feet.offset(dx, dy, dz);
                    var state = level.getBlockState(p);
                    if (state.is(net.minecraft.world.level.block.Blocks.LAVA) || state.is(net.minecraft.world.level.block.Blocks.FIRE)
                            || state.is(net.minecraft.world.level.block.Blocks.SOUL_FIRE)) return true;
                    if (dy < 0 && !state.getCollisionShape(level, p).isEmpty()) floor = true;
                }
                if (!floor) return true;
            }
        }
        return false;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
        mod.getClientBaritone().getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof SafeRandomShimmyTask;
    }

    @Override
    protected String toHudString() {
        return "Shuffling around";
    }

    @Override
    protected String toDebugString() {
        return "Shimmying";
    }
}
