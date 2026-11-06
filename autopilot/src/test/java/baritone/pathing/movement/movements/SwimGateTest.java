package baritone.pathing.movement.movements;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the swim gate is a hook on Movement and the pillar is the only one that shuts it. a movement is built around a live player
// so there's no running one of these here, but nobody else quietly overriding it is checkable. the actual crouch in water
// is a play-test
public class SwimGateTest {

    private static boolean declaresSwimGate(Class<?> movement) {
        for (java.lang.reflect.Method m : movement.getDeclaredMethods()) {
            if (m.getName().equals("swimAllowed")) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void onlyThePillarShutsTheSwimGate() {
        assertTrue(declaresSwimGate(MovementPillar.class));
        Class<?>[] others = {MovementTraverse.class, MovementAscend.class, MovementDescend.class, MovementDiagonal.class,
                MovementDownward.class, MovementFall.class, MovementParkour.class, MovementClimb.class};
        for (Class<?> other : others) {
            assertFalse(other.getSimpleName() + " touches the swim gate", declaresSwimGate(other));
        }
    }
}
