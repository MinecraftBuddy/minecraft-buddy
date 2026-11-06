package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

// the "is there anything to hunt around here" question, asked from outside the food task (the planner wants it before it
// decides to start one). same edible test the hunt uses, so the answer never says yes to something the hunt would refuse
public final class PreyScan {
    private PreyScan() {
    }

    // not a baby (no meat), not carrying a passenger (chicken jockey, the blacklist in the food task only knows the closest one),
    // not somebody's (keptAnimal)
    static boolean edible(Entity entity) {
        return entity instanceof LivingEntity living && !living.isBaby() && !entity.isVehicle() && !keptAnimal(entity);
    }

    // how far out from an animal the pen test looks for the fence on each side
    static final int PEN_REACH = 10;

    // a farm animal somebody keeps: a name tag, a lead, or a fence on all four sides of it (a pen). on a server those are
    // another player's animals, and killing them is griefing, so no hunt goes for them. a hostile animal (a hoglin) is
    // still fought
    public static boolean keptAnimal(Entity entity) {
        if (!(entity instanceof Animal) || entity instanceof Enemy) {
            return false;
        }
        if (entity.hasCustomName() || (entity instanceof Leashable leashable && leashable.isLeashed())) {
            return true;
        }
        Level level = entity.level();
        return fencedIn(entity.blockPosition(), pos -> isFence(level.getBlockState(pos)));
    }

    static boolean isFence(BlockState state) {
        return state.getBlock() instanceof FenceBlock || state.getBlock() instanceof FenceGateBlock
                || state.getBlock() instanceof WallBlock || state.getBlock() instanceof IronBarsBlock;
    }

    // a fence (or gate, wall, bars) within PEN_REACH along each of the four ways from the feet, a block up or down for
    // uneven ground. one open way is open ground: a field's fence on one side is no pen
    static boolean fencedIn(BlockPos feet, Predicate<BlockPos> fence) {
        for (Direction way : Direction.Plane.HORIZONTAL) {
            boolean closed = false;
            for (int step = 1; step <= PEN_REACH && !closed; step++) {
                BlockPos at = feet.relative(way, step);
                closed = fence.test(at) || fence.test(at.above()) || fence.test(at.below());
            }
            if (!closed) {
                return false;
            }
        }
        return true;
    }

    // an edible, unbanned animal loaded within `radius` blocks (FoodHunt.distance, so a cow up a cliff counts for more than a cow along)
    public static boolean near(AltoClef mod, double radius) {
        var me = mod.getPlayer().position();
        // no rabbits here: the food task still eats one it finds, but one hopping by is not worth dropping the plan for
        for (Class<? extends Entity> kind : List.of(Pig.class, Cow.class, Chicken.class, Sheep.class)) {
            // every animal gets the radius check, not just the euclidean closest: the cow straight above us is closest and
            // out of range while the one on level ground further off is the meal
            if (anyInRange(new ArrayList<>(mod.getEntityTracker().getTrackedEntities(kind)), e -> e.position().subtract(me), radius,
                    e -> edible(e) && e.isAlive() && mod.getEntityTracker().isEntityReachable(e))) {
                return true;
            }
        }
        return false;
    }

    // any one of them qualifies and is in range: the whole list gets asked, not just whoever is closest as the crow flies
    static <T> boolean anyInRange(List<T> all, Function<T, Vec3> offset, double radius, Predicate<T> ok) {
        for (T t : all) {
            if (inRange(offset.apply(t), radius) && ok.test(t)) {
                return true;
            }
        }
        return false;
    }

    static boolean inRange(Vec3 offset, double radius) {
        return FoodHunt.distance(offset.x, offset.y, offset.z) <= radius;
    }
}
