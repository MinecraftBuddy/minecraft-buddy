/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.IFollowProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Follow an entity
 *
 * @author leijurv
 */
public final class FollowProcess extends BaritoneProcessHelper implements IFollowProcess {

    private Predicate<Entity> filter;
    private List<Entity> cache;
    private boolean into; // walk straight into the target, regardless of settings
    private final TargetLock<Entity> lock;
    private Object lockWorld;

    public FollowProcess(Baritone baritone) {
        super(baritone);
        this.lock = new TargetLock<>(Entity::getUUID, e -> e.distanceToSqr(ctx.player()), Comparator.comparingInt(Entity::getId));
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        scanWorld();
        Goal goal = new GoalComposite(cache.stream().map(this::towards).toArray(Goal[]::new));
        return new PathingCommand(goal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private Goal towards(Entity following) {
        BlockPos pos;
        if (Baritone.settings().followOffsetDistance.value == 0 || into) {
            pos = following.blockPosition();
        } else {
            GoalXZ g = GoalXZ.fromDirection(following.position(), Baritone.settings().followOffsetDirection.value, Baritone.settings().followOffsetDistance.value);
            pos = new BetterBlockPos(g.getX(), following.position().y, g.getZ());
        }
        if (into) {
            return new GoalBlock(pos);
        }
        return new GoalNear(pos, Baritone.settings().followRadius.value);
    }


    private boolean followable(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (!entity.isAlive()) {
            return false;
        }
        if (entity.equals(ctx.player())) {
            return false;
        }
        int maxDist = Baritone.settings().followTargetMaxDistance.value;
        if (maxDist != 0 && entity.distanceToSqr(ctx.player()) > maxDist * maxDist) {
            return false;
        }
        // this used to also check that the entity is in ctx.entitiesStream(), which it is, because that's where
        // scanWorld got it from. for every entity, twice a tick. 2000 mobs in a farm = 8 million equals a tick
        return true;
    }

    private void scanWorld() {
        List<Entity> all = ctx.entitiesStream().collect(Collectors.toList());
        List<Entity> valid = all.stream()
                .filter(this::followable)
                .filter(this.filter)
                .distinct()
                .collect(Collectors.toList());
        if (ctx.world() != lockWorld) {
            // dimension change or reconnect: whoever we had is an entity from the old world, its position means nothing here
            lock.clear();
            lockWorld = ctx.world();
        }
        cache = targets(into, lock, valid, ctx.player().tickCount, isGone(lock.locked(), all));
    }

    static <T> List<T> targets(boolean into, TargetLock<T> lock, List<T> valid, int now, boolean gone) {
        if (into) {
            // pickup wants whichever item is nearest right now, out of all of them. no lock
            return valid;
        }
        T target = lock.update(valid, now, gone);
        return target == null ? List.of() : List.of(target);
    }

    private static boolean isGone(Entity entity, List<Entity> all) {
        if (entity == null) {
            return false;
        }
        // removed alone isn't enough, the client removes entities that just walked out of a loaded chunk too
        if (entity instanceof LivingEntity && ((LivingEntity) entity).isDeadOrDying()) {
            return true;
        }
        // still in the world but filtered out (too far, filter said no): that's a real answer, not a chunk edge blip.
        // the grace is only for the ones that vanished
        for (Entity e : all) {
            if (e.getUUID().equals(entity.getUUID())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isActive() {
        if (filter == null) {
            return false;
        }
        scanWorld();
        return !cache.isEmpty();
    }

    @Override
    public void onLostControl() {
        filter = null;
        cache = null;
        lock.clear();
        // or the old ClientLevel sticks around in here long after we left it
        lockWorld = null;
    }

    @Override
    public String displayName0() {
        return "Following " + cache;
    }

    @Override
    public void follow(Predicate<Entity> filter) {
        this.filter = filter;
        this.into = false;
        lock.clear();
    }

    @Override
    public void pickup(Predicate<ItemStack> filter) {
        this.filter = e -> e instanceof ItemEntity && filter.test(((ItemEntity) e).getItem());
        this.into = true;
        lock.clear();
    }

    @Override
    public List<Entity> following() {
        return cache;
    }

    @Override
    public Predicate<Entity> currentFilter() {
        return filter;
    }
}
