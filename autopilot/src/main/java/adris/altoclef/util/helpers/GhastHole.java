package adris.altoclef.util.helpers;

import java.util.List;

// the heal-up hold's hiding spot from a ghast: a hole straight down. the ghast only charges with a line on us, the charge
// counts back down out of sight, and it only picks a target within 4 of its own height, so a ghast up high that loses us
// does not get us back. pure: the hold reads the blocks into Cells, this says yes or no
public final class GhastHole {
    // at 2 deep a ghast at a normal angle still sees our eyes over the rim, at 3 it has to be nearly overhead
    public static final int DEPTH = 3;
    // the ghast does not even start a charge at anything further than this
    public static final double SIGHT_RANGE = 64;
    // a ghast ball hit this recently still counts as being shot at, the ghast that threw it can be off in the fog
    public static final int HIT_TICKS = 200;

    private GhastHole() {
    }

    // one block of the column. liquidBeside: lava or water touching one of its four sides, which comes in the moment it is broken
    public record Cell(boolean solid, boolean liquid, boolean falling, boolean magma, boolean liquidBeside) {
    }

    // dug: the DEPTH cells under our feet, top first. floor: the one under those, what we stand on in the hole
    public static boolean columnOk(List<Cell> dug, Cell floor) {
        if (dug.size() != DEPTH) return false;
        for (Cell c : dug) {
            // not solid is a cave under us (dropping into that is not a hole), gravel falls in after us, magma burns the whole heal
            if (!c.solid() || c.liquid() || c.falling() || c.magma() || c.liquidBeside()) return false;
        }
        // lava under the floor reads as a floor that is not solid. standing on magma to heal is not healing
        return floor.solid() && !floor.liquid() && !floor.magma();
    }

    // a ghast sees us now, or one of its balls hit us a moment ago. a negative age is a clock that went backwards
    public static boolean hideWanted(boolean ghastInSight, long ticksSinceHit) {
        return ghastInSight || (ticksSinceHit >= 0 && ticksSinceHit <= HIT_TICKS);
    }
}
