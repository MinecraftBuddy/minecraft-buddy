package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import java.util.Optional;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;

public class ShearSheepTask extends AbstractDoToEntityTask {

    public ShearSheepTask() {
        super(0, -1, -1);
    }

    @Override
    protected boolean isSubEqual(AbstractDoToEntityTask other) {
        return other instanceof ShearSheepTask;
    }

    @Override
    protected Task onEntityInteract(AltoClef mod, Entity entity) {
        if (!mod.getItemStorage().hasItem(Items.SHEARS)) {
            Debug.logWarning("Failed to shear sheep because you have no shears.");
            return null;
        }
        if (mod.getSlotHandler().forceEquipItem(Items.SHEARS)) {
            mod.getController().interact(mod.getPlayer(), entity, new EntityHitResult(entity), InteractionHand.MAIN_HAND);
        }


        return null;
    }

    @Override
    protected Optional<Entity> getEntityTarget(AltoClef mod) {
        return mod.getEntityTracker().getClosestEntity(mod.getPlayer().position(), ShearSheepTask::readyToShear, Sheep.class);
    }

    // shared with CollectWoolTask, so it never picks us for a flock with nothing to shear and the two cannot drift apart
    public static boolean readyToShear(Entity entity) {
        return entity instanceof Sheep sheep && sheep.readyForShearing() && !sheep.isSheared();
    }

    @Override
    protected String toHudString() {
        return "Shearing a Sheep";
    }

    @Override
    protected String toDebugString() {
        return "Shearing Sheep";
    }
}
