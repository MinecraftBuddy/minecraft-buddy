package adris.altoclef.util.helpers;

// the run's rim shield with a leash: up for RIM_HOLD_TICKS in a row, then down until nobody wants it at all. the foe does not
// matter: two cubes taking turns as the closest one must not make a fresh leash every swap. the shield pauses pathing, so
// without the cap a cube that keeps pace would pin the run on the rim for good
public final class RimHold {
    private int held;

    // foe: the id the shield wants this tick, -1 for none. true when the shield may be up
    public boolean step(int foe) {
        if (foe < 0) {
            reset();
            return false;
        }
        if (held >= CombatRules.RIM_HOLD_TICKS) return false;
        held++;
        return true;
    }

    public void reset() {
        held = 0;
    }
}
