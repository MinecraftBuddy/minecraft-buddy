package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.time.TimerGame;
import java.util.Optional;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.item.Items;

public class KillEndermanTask extends ResourceTask {

    private final int _count;

    private final TimerGame _lookDelay = new TimerGame(0.2);

    public KillEndermanTask(int count) {
        super(new ItemTarget(Items.ENDER_PEARL, count));
        _count = count;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {

    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // Dimension
        if (!mod.getEntityTracker().entityFound(Enderman.class)) {
            // the target dimension is null unless somebody forced one, and a null dimension is an NPE in the switch
            // of DefaultGoToDimensionTask. nothing forced = hunt right here, like KillAndLootTask does
            if (isInWrongDimension(mod)) {
                return getToCorrectDimensionTask(mod);
            }
            return new TimeoutWanderTask();
        }

        // Kill the angry one
        Optional<Entity> enderman = mod.getEntityTracker().getClosestEntity(Enderman.class);
        if (enderman.isPresent()) {
            Enderman endermanEntity = (Enderman) enderman.get();
            final int TOO_FAR_AWAY = 256;
            if (endermanEntity.isCreepy() && endermanEntity.position().closerThan(mod.getPlayer().position(), TOO_FAR_AWAY)) {
                return new KillEntityTask(endermanEntity);
            }
        }
        // Attack the closest one
        return new KillEntitiesTask(Enderman.class);
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {

    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof KillEndermanTask task) {
            return task._count == _count;
        }
        return false;
    }

    @Override
    protected String toHudString() {
        return "Hunting Endermen for " + HudText.count(_count, "Ender Pearl");
    }

    @Override
    protected String toDebugStringName() {
        return "Hunting enderman for " + _count + " pearls.";
    }
}