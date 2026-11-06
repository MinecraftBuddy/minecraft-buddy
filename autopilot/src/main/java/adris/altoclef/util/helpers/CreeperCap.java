package adris.altoclef.util.helpers;

// a lit creeper above us in a one wide shaft with no way sideways. a block in the cell over our head breaks its line to us:
// it un-swells, the blast sees us at about nothing through a block, and it can't drop down on top of us. pure, the chain does
// the actual placing
public final class CreeperCap {

    // the fuse is 30 ticks and a raised shield needs about 5 of them to block, so the cap gets this long to land before the
    // shield has to take over. the equip is one tick, the click another, the rest is slack for a refused first try
    public static final long CAP_TICKS = 12;
    // above us means this far over in y (feet to feet) and no more than this off in x/z. wider than that is a creeper beside
    // the shaft and not in it
    public static final double MIN_ABOVE = 1;
    public static final double MAX_OFF = 1.5;

    // a creeper this close horizontally is on top of us (or in the cell we would wall), nothing to put between us
    public static final double MIN_BESIDE = 1.5;

    private CreeperCap() {
    }

    // noHeading = CreeperEscape found nowhere to sprint. dy = creeper feet minus ours, horizontal = how far off to the side
    public static boolean creeperAbove(boolean noHeading, double dy, double horizontal) {
        return noHeading && dy > MIN_ABOVE && horizontal < MAX_OFF;
    }

    // still inside the cap's share of the fuse. since = ticks since the step started (negative = the clock went backwards)
    public static boolean inWindow(long since) {
        return since >= 0 && since < CAP_TICKS;
    }

    // the wall for a creeper down the tunnel with us: one block in the cell next to us, toward the creeper, at its foot level.
    // the blast is centred on its feet and the damage rays start there, so a block at that height over the near edge of the
    // cell covers the lot. one block and not a seal on purpose: it keeps its line over the top, finishes swelling and pops
    // harmlessly, a full seal would un-swell it and leave a live one behind the wall. dx/dz = creeper minus us, playerY/creeperY = absolute feet y (cells are compared, not the raw difference: a slab at 64.5 is still the 64 cell).
    // null = no cell (it has a way out, it is on top of us, or it is above us and that is the cap). else {x, y, z} offsets from
    // our own block: the dominant horizontal axis, and our feet or head cell for the height
    public static int[] besideCell(boolean noHeading, double dx, double playerY, double creeperY, double dz) {
        double horizontal = Math.hypot(dx, dz);
        if (!noHeading || horizontal < MIN_BESIDE || creeperAbove(true, creeperY - playerY, horizontal)) return null;
        int up = (int) Math.max(0, Math.min(1, Math.floor(creeperY) - Math.floor(playerY)));
        if (Math.abs(dx) >= Math.abs(dz)) return new int[]{dx > 0 ? 1 : -1, up, 0};
        return new int[]{0, up, dz > 0 ? 1 : -1};
    }
}
