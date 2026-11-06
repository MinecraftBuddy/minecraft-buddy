package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.ui.HudText;

import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;

/**
 * Kill all entities of a type
 */
public class KillEntitiesTask extends DoToClosestEntityTask {

    private Predicate<Entity> _keepChasing = entity -> true;

    public KillEntitiesTask(Predicate<Entity> shouldKill, Class<?>... entities) {
        super(KillEntityTask::new, shouldKill, entities);
    }

    // keepChasing is asked about the target we are already after, not just when picking one. false and we drop it, this
    // is how the mob defense gives up on something that wandered off instead of following it to the edge of the map
    public KillEntitiesTask(Predicate<Entity> shouldKill, Predicate<Entity> keepChasing, Class<?>... entities) {
        super(KillEntityTask::new, shouldKill.and(keepChasing), entities);
        _keepChasing = keepChasing;
    }

    public KillEntitiesTask(Class<?>... entities) {
        super(KillEntityTask::new, entities);
    }

    @Override
    protected boolean isValid(AltoClef mod, Entity obj) {
        return super.isValid(mod, obj) && _keepChasing.test(obj);
    }

    @Override
    protected String toHudString() {
        // this one only picks the next target, the fight itself is the line under it
        return "Fighting " + HudText.pluralMob(HudText.entityClass(targetClass()));
    }
}
