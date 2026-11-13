package adris.altoclef.util.baritone;

import adris.altoclef.util.helpers.CubeWay;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.world.entity.Entity;

import java.util.Objects;

/**
 * Tuning blaze_stand_goal (13 Nov 2026, off; the A/B block standplek): the walk to a blaze aims at a stand on the ground under
 * it, as the magma cube hunt does (CubeWay.stand: feet within STAND_FLAT of its column and STAND_UP of the ground), not at the
 * blaze in the air. GoalFollowEntity's goal is a cell whose middle is within reach of the blaze's feet, which a blaze hovering
 * two to four blocks over the floor leaves out of every cell on that floor, so the search ran its 2 s out ("no path") and the
 * blaze was banned: in route-61, 202 of 234 no-path verdicts on a blaze had a plain walk to such a stand, a median 7.7 blocks
 * off and 2 above. The ground is read when the goal is made (GetToEntityTask makes one at each new search); the column
 * follows the blaze.
 */
public class GoalBlazeStand implements Goal {
    private final Entity _entity;
    private final int _groundY;

    public GoalBlazeStand(Entity entity, int groundY) {
        _entity = entity;
        _groundY = groundY;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        return CubeWay.stand(x, y, z, _entity.getBlockX(), _groundY, _entity.getBlockZ());
    }

    @Override
    public double heuristic(int x, int y, int z) {
        return GoalBlock.calculate(x - _entity.getX(), y - _groundY, z - _entity.getZ());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        return o instanceof GoalBlazeStand other && other._entity.getId() == _entity.getId() && other._groundY == _groundY;
    }

    @Override
    public int hashCode() {
        return Objects.hash(_entity.getId(), _groundY);
    }

    @Override
    public String toString() {
        return "GoalBlazeStand{ground=" + _groundY + "}";
    }
}
