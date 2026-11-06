package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.entity.KillEntitiesTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.DropExpect;
import adris.altoclef.util.helpers.DropWatch;
import adris.altoclef.util.helpers.WorldHelper;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import java.util.Optional;

public class KillAndLootTask extends ResourceTask {

    private final Class<?> _toKill;

    private final KillEntitiesTask _killTask;
    // -1 = any of the class. otherwise this one animal, and part of isEqual so a new target is a new task (the runner
    // keeps the old object when they compare equal, and the old one would still be chasing the wrong pig)
    private final int _onlyId;

    // the kill we are watching for (see DropExpect). the mob we last saw alive and close, and where it died once it did
    private static final double WATCH_RANGE = 8;
    private static final double DROP_RADIUS = 10;
    // a drop lying around its death spot counts as "still collecting" for this long, then it is the plan's problem
    private static final int COLLECT_TICKS = 100;
    private final DropExpect _expect = new DropExpect();
    private Entity _watched;
    private Vec3 _diedAt;
    private int _diedTick;

    public KillAndLootTask(Class<?> toKill, Predicate<Entity> shouldKill, ItemTarget... itemTargets) {
        super(itemTargets.clone());
        _toKill = toKill;
        _onlyId = -1;
        _killTask = new KillEntitiesTask(shouldKill, _toKill);
    }

    // shouldKill is also asked about the one we are already chasing, so a target that wanders somewhere bad is dropped
    public KillAndLootTask(Class<?> toKill, Predicate<Entity> shouldKill, boolean dropWhenFalse, ItemTarget... itemTargets) {
        super(itemTargets.clone());
        _toKill = toKill;
        _onlyId = -1;
        _killTask = dropWhenFalse ? new KillEntitiesTask(shouldKill, shouldKill, _toKill) : new KillEntitiesTask(shouldKill, _toKill);
    }

    // picking and keeping can differ (a looser line for the one we are already on, so a target on the edge does not flip)
    public KillAndLootTask(Class<?> toKill, Predicate<Entity> shouldKill, Predicate<Entity> keepChasing, ItemTarget... itemTargets) {
        super(itemTargets.clone());
        _toKill = toKill;
        _onlyId = -1;
        _killTask = new KillEntitiesTask(shouldKill, keepChasing, _toKill);
    }

    // this animal and no other
    public KillAndLootTask(Entity target, ItemTarget... itemTargets) {
        super(itemTargets.clone());
        _toKill = target.getClass();
        _onlyId = target.getId();
        int id = _onlyId;
        _killTask = new KillEntitiesTask(entity -> entity.getId() == id, _toKill);
    }

    public KillAndLootTask(Class<?> toKill, ItemTarget... itemTargets) {
        super(itemTargets.clone());
        _toKill = toKill;
        _onlyId = -1;
        _killTask = new KillEntitiesTask(_toKill);
    }

    // the id of the one the kill walk is after right now, -1 for none. a dead or unloaded one is none: the kill walk only lets
    // go of it on its own tick, and with nothing left to kill we wander instead of ticking it, so the corpse stays "pursued"
    public int pursuingId() {
        Entity target = _killTask.pursuing();
        return pursuedId(target != null, target != null && target.isAlive(), target == null ? -1 : target.getId());
    }

    static int pursuedId(boolean exists, boolean alive, int id) {
        return exists && alive ? id : -1;
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        _expect.clear();
        _watched = null;
        _diedAt = null;
    }

    // the mob we are after dying near us is the cue to stand still for its loot. ResourceTask picks a drop up before this
    // runs, so all this decides is what we do in the gap before the item exists: used to be the wander below, and the
    // plan above switching to the next cow / a carrot, with the beef still on its way
    private void noteDeath(AltoClef mod) {
        Entity mob = _watched;
        if (mob != null && !mob.isAlive()) {
            _watched = null;
            _diedAt = mob.position();
            _diedTick = WorldHelper.getTicks();
            _expect.expectKill(_diedTick);
        }
        if (_watched == null) {
            Entity next = null;
            if (_onlyId >= 0) {
                next = mod.getWorld().getEntity(_onlyId);
            } else {
                Optional<Entity> closest = mod.getEntityTracker().getClosestEntity(_toKill);
                next = closest.orElse(null);
            }
            if (next != null && next.isAlive() && next.distanceTo(mod.getPlayer()) <= WATCH_RANGE) {
                _watched = next;
            }
        } else if (_watched.distanceTo(mod.getPlayer()) > WATCH_RANGE) {
            // wandered off. a mob that vanishes out there is not a kill of ours
            _watched = null;
        }
    }

    // the plan above asks this before it throws the hunt away for "the animal died": the loot of the kill is the job now
    public boolean awaitingDrop(AltoClef mod) {
        noteDeath(mod);
        if (_expect.isLive(WorldHelper.getTicks())) {
            return true;
        }
        return _diedAt != null && WorldHelper.getTicks() - _diedTick < COLLECT_TICKS && !isFinished(mod)
                && DropWatch.seen(mod, _diedAt, DROP_RADIUS, _itemTargets);
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        noteDeath(mod);
        if (_expect.isLive() && _expect.hold(WorldHelper.getTicks(), DropWatch.anyNear(mod, _diedAt, DROP_RADIUS))) {
            setDebugState("Waiting for the loot to show up");
            return null;
        }
        if (!mod.getEntityTracker().entityFound(_toKill)) {
            if (isInWrongDimension(mod)) {
                setDebugState("Going to correct dimension.");
                return getToCorrectDimensionTask(mod);
            }
            setDebugState("Searching for mob...");
            return new TimeoutWanderTask();
        }
        // We found the mob!
        return _killTask;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {

    }

    // the kill is not done until its loot is: no finishing in the gap between the mob dying and the drop showing up
    @Override
    public boolean isFinished(AltoClef mod) {
        return super.isFinished(mod) && !_expect.isLive(WorldHelper.getTicks());
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof KillAndLootTask task) {
            return task._toKill.equals(_toKill) && task._onlyId == _onlyId;
        }
        return false;
    }

    @Override
    protected String toHudString() {
        return "Hunting " + HudText.plural(HudText.entityClass(_toKill)) + " for " + HudText.some(_itemTargets);
    }

    @Override
    protected String toDebugStringName() {
        return "Collect items from " + _toKill.toGenericString();
    }
}
