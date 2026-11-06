package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.util.ItemTarget;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

// the world half of DropExpect: is there a drop we want near the spot where the block was / the mob died
public final class DropWatch {
    private DropWatch() {
    }

    // any item entity near the spot. the cue for "the spawn packet arrived", which is all the wait is for: a block that
    // only sometimes drops what we want (seeds from grass, apples from leaves) must not cost the full wait every time
    public static boolean anyNear(AltoClef mod, Vec3 spot, double radius) {
        if (spot == null) {
            return false;
        }
        for (ItemEntity drop : mod.getEntityTracker().getDroppedItems()) {
            if (drop.isAlive() && drop.position().distanceTo(spot) <= radius) {
                return true;
            }
        }
        return false;
    }

    // a drop we WANT near the spot
    public static boolean seen(AltoClef mod, Vec3 spot, double radius, ItemTarget... wanted) {
        if (spot == null || wanted == null || wanted.length == 0 || !mod.getEntityTracker().itemDropped(wanted)) {
            return false;
        }
        return mod.getEntityTracker().getClosestItemDrop(spot, d -> d.isAlive() && d.position().distanceTo(spot) <= radius, wanted).isPresent();
    }
}
