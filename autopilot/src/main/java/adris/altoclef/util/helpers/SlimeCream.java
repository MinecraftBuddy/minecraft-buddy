package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import net.minecraft.world.item.Items;

// Tuning route_slime_cream (off by default; test slijm, steering 128, 9 Nov 2026): a slime ball in the bag stands for the
// brew's magma cream. the route's phase 3 then fetches the wart alone, and the brew crafts the cream in the box from the
// ball and a blaze powder of the first rods. why: the cream came in 5 of 16 runs at the blazes, 318 magma cubes killed for
// 36 cream, and without it no brew
public final class SlimeCream {
    private SlimeCream() {
    }

    public static boolean on() {
        return Tuning.get("route_slime_cream", 0) > 0;
    }

    // the cream the brew can count on: one in the bag, or with the switch a slime ball to craft it from
    public static boolean have(AltoClef mod) {
        return have(mod.getItemStorage().hasItem(Items.MAGMA_CREAM), mod.getItemStorage().hasItem(Items.SLIME_BALL), on());
    }

    // no cream yet, but a ball to craft it from: the brew crafts before it asks for a cube
    public static boolean craft(AltoClef mod) {
        return craft(mod.getItemStorage().hasItem(Items.MAGMA_CREAM), mod.getItemStorage().hasItem(Items.SLIME_BALL), on());
    }

    // pure
    static boolean have(boolean cream, boolean ball, boolean on) {
        return cream || on && ball;
    }

    static boolean craft(boolean cream, boolean ball, boolean on) {
        return !cream && on && ball;
    }
}
