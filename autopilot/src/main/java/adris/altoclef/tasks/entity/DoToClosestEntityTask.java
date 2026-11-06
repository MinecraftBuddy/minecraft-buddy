package adris.altoclef.tasks.entity;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.AbstractDoToClosestObjectTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.ui.HudText;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Finds the closest entity and runs a task on that entity
 */
@SuppressWarnings("ALL")
public class DoToClosestEntityTask extends AbstractDoToClosestObjectTask<Entity> {

    private final Class[] _targetEntities;

    private final Supplier<Vec3> _getOriginPos;

    private final Function<Entity, Task> _getTargetTask;

    private final Predicate<Entity> _shouldInteractWith;

    public DoToClosestEntityTask(Supplier<Vec3> getOriginSupplier, Function<Entity, Task> getTargetTask, Predicate<Entity> shouldInteractWith, Class... entities) {
        _getOriginPos = getOriginSupplier;
        _getTargetTask = getTargetTask;
        _shouldInteractWith = shouldInteractWith;
        _targetEntities = entities;
    }

    public DoToClosestEntityTask(Supplier<Vec3> getOriginSupplier, Function<Entity, Task> getTargetTask, Class... entities) {
        this(getOriginSupplier, getTargetTask, entity -> true, entities);
    }

    public DoToClosestEntityTask(Function<Entity, Task> getTargetTask, Predicate<Entity> shouldInteractWith, Class... entities) {
        this(null, getTargetTask, shouldInteractWith, entities);
    }

    public DoToClosestEntityTask(Function<Entity, Task> getTargetTask, Class... entities) {
        this(null, getTargetTask, entity -> true, entities);
    }

    @Override
    protected Vec3 getPos(AltoClef mod, Entity obj) {
        return obj.position();
    }

    @Override
    protected Optional<Entity> getClosestTo(AltoClef mod, Vec3 pos) {
        if (!mod.getEntityTracker().entityFound(_targetEntities)) return Optional.empty();
        return mod.getEntityTracker().getClosestEntity(pos, _shouldInteractWith, _targetEntities);
    }

    @Override
    protected Vec3 getOriginPos(AltoClef mod) {
        if (_getOriginPos != null) {
            return _getOriginPos.get();
        }
        return mod.getPlayer().position();
    }

    @Override
    protected Task getGoalTask(Entity obj) {
        return _getTargetTask.apply(obj);
    }

    // mobs move and get kited or parked next to on purpose (a blaze hovering out of reach, the chicken we wait by for eggs),
    // so "not getting closer" is how a fight looks, not a stuck walk. switching targets mid fight is the thing to never do
    @Override
    protected boolean repicksWhenStalled() {
        return false;
    }

    @Override
    protected boolean isValid(AltoClef mod, Entity obj) {
        return obj.isAlive() && mod.getEntityTracker().isEntityReachable(obj);
    }

    @Override
    protected void onStart(AltoClef mod) {
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DoToClosestEntityTask task) {
            return Arrays.equals(task._targetEntities, _targetEntities);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Doing something to closest entity...";
    }

    protected Class<?> targetClass() {
        return _targetEntities == null || _targetEntities.length == 0 ? null : _targetEntities[0];
    }

    @Override
    protected String toHudString() {
        return "Finding the nearest " + (targetClass() == null ? "mob" : HudText.entityClass(targetClass()));
    }

    @Override
    protected boolean isHudPlumbing() {
        return true;
    }
}