package adris.altoclef.util.baritone;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import java.util.Objects;

public class GoalFollowEntity implements Goal {

    private final Entity _entity;
    private final double _closeEnoughDistance;
    // where the entity was when this goal was made. the goal itself follows it live, this is only for equals
    private final BlockPos _madeAt;

    public GoalFollowEntity(Entity entity, double closeEnoughDistance) {
        _entity = entity;
        _closeEnoughDistance = closeEnoughDistance;
        _madeAt = entity.blockPosition();
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        return _entity.blockPosition().equals(p) || p.closerToCenterThan(_entity.position(), _closeEnoughDistance);
    }

    @Override
    public double heuristic(int x, int y, int z) {
        //synchronized (BaritoneHelper.MINECRAFT_LOCK) {
        double xDiff = x - _entity.position().x();
        int yDiff = y - _entity.blockPosition().getY();
        double zDiff = z - _entity.position().z();
        return GoalBlock.calculate(xDiff, yDiff, zDiff);
        //}
    }

    // GetToEntityTask makes a new one every time it asks, so the path a handover parked (PathingBehavior.handoverCancel)
    // could never match by identity. same entity, same distance, and it stood in the same block when each was made: a path
    // to a mob that has walked off since is not the same path any more
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof GoalFollowEntity other && other._entity.getId() == _entity.getId()
                && Double.compare(other._closeEnoughDistance, _closeEnoughDistance) == 0 && other._madeAt.equals(_madeAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(_entity.getId(), _closeEnoughDistance, _madeAt);
    }
}
