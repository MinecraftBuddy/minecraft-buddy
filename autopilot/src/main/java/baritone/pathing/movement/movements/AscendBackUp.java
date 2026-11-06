package baritone.pathing.movement.movements;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

// the pure half of MovementAscend's "we might be standing where the block goes, step back". backing up only ever fixes our
// own box in the cell (a mob in it is not moved by us walking away), and a sneak walk back with nothing behind ends with
// the bot pinned on the far edge of the source block by a hair. so back up only while we are actually in the way, and
// stop well before the back edge
public final class AscendBackUp {

    // how far inside the source block the box centre has to stay. sneak pins the box, not the centre, so past this the
    // centre is out over whatever is behind
    public static final double EDGE_MARGIN = 0.15;
    // the old patience, kept
    public static final int PATIENCE = 10;

    private AscendBackUp() {
    }

    public static boolean mayBack(int ticksWithoutPlacement, boolean boxInPlaceCell, double centreToBackEdge) {
        return ticksWithoutPlacement > PATIENCE && boxInPlaceCell && centreToBackEdge >= EDGE_MARGIN;
    }

    // our box overlaps the cell the block goes in
    public static boolean boxInCell(AABB box, BlockPos cell) {
        return box.intersects(new AABB(cell));
    }

    // distance from the box centre to the source block's edge on the side away from dest. dx/dz is src -> dest, one of
    // them is 0. negative when the centre is already past that edge
    public static double centreToBackEdge(double cx, double cz, int srcX, int srcZ, int dx, int dz) {
        if (dx != 0) {
            return dx > 0 ? cx - srcX : srcX + 1 - cx;
        }
        return dz > 0 ? cz - srcZ : srcZ + 1 - cz;
    }
}
