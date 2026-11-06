package adris.altoclef.util.helpers;

import adris.altoclef.tasks.speedrun.gamer.GolemRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

// where a mob could be standing right next to our column. the golem fight and the perch both build for the high side, so both ask
// this (one place for the slab and the ceiling rules, the only thing that differs is how much air the mob needs)
public final class MobFooting {
    private MobFooting() {
    }

    // the height a mob would stand at on this block, NaN when it is not a footing: nothing to stand on, or fewer than airCells free
    // cells above the real top (a slab is a half block step, a ceiling can take the room away)
    public static double footing(Level level, BlockPos at, int airCells) {
        if (!level.hasChunkAt(at)) {
            return Double.NaN;
        }
        var shape = level.getBlockState(at).getCollisionShape(level, at);
        if (shape.isEmpty()) {
            return Double.NaN;
        }
        double top = at.getY() + shape.max(Direction.Axis.Y);
        for (int up = 1; up <= airCells; up++) {
            BlockPos cell = at.above(up);
            if (!level.getBlockState(cell).getCollisionShape(level, cell).isEmpty()) {
                return Double.NaN;
            }
        }
        return top;
    }

    // the feet height a mob standing at mobFeetY could have right beside our column: where it is now, or the highest ground a step up
    // from there on any of the 8 sides it can walk up
    public static double groundBeside(Level level, BlockPos here, double mobFeetY, int airCells) {
        int stepY = (int) Math.floor(mobFeetY + 1.0e-3);
        double[] sides = new double[8];
        int n = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                sides[n++] = footing(level, new BlockPos(here.getX() + dx, stepY, here.getZ() + dz), airCells);
            }
        }
        return GolemRules.groundBeside(mobFeetY, sides);
    }
}
