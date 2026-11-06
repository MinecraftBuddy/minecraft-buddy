package adris.altoclef.util.helpers;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

// which mob the aura swings at. in a committed fight that is the fight's own target, as long as it is within arm's reach, so
// the task and the aura stop picking different mobs (the task walked at the skeleton while the aura hit the nearest zombie).
// anything else is the nearest, same as it always was
public final class AuraPick {

    private AuraPick() {
    }

    // committedId < 0 is no fight. null for no targets at all
    public static <T> T pick(List<T> targets, ToIntFunction<T> id, int committedId, Predicate<T> inReach, ToDoubleFunction<T> distSq) {
        T nearest = null;
        for (T target : targets) {
            if (committedId >= 0 && id.applyAsInt(target) == committedId && inReach.test(target)) return target;
            if (nearest == null || distSq.applyAsDouble(target) < distSq.applyAsDouble(nearest)) nearest = target;
        }
        return nearest;
    }
}
