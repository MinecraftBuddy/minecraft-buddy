package adris.altoclef.util.helpers;

import baritone.api.utils.Dimension;

import java.util.OptionalInt;

// the tunnel a stone miner committed to: the floor y (see StoneDigRank.tunnelFloor) and where we were when we picked it.
// latched is per task, so a new collect starts with no opinion. the floor itself is kept after an unlatch for reuse, see
// there. pure, the caller feeds it positions
public final class StoneTunnel {
    // past this far from where we latched, the floor we worked out is somebody else's terrain
    public static final int MAX_WANDER = 24;

    private boolean latched;
    // a floor was ever picked, latched or not anymore
    private boolean known;
    private int floorY;
    private int atX;
    private int atZ;
    private Dimension dimension;

    public void latch(int floorY, int x, int z, Dimension dimension) {
        this.latched = true;
        this.known = true;
        this.floorY = floorY;
        this.atX = x;
        this.atZ = z;
        this.dimension = dimension;
    }

    // stop using it, but remember where it was
    public void unlatch() {
        latched = false;
    }

    // forget it ever existed
    public void clear() {
        latched = false;
        known = false;
        dimension = null;
    }

    public boolean latched() {
        return latched;
    }

    public int floor() {
        return floorY;
    }

    // walked off far enough or went through a portal: that floor means nothing here
    public boolean left(int x, int z, Dimension now) {
        return latched && away(x, z, now);
    }

    // the floor we had around here, when we stand at or over it. working the floor out from the world again goes wrong
    // in a dug out room: nothing beside the feet is stone anymore, so it looks like the surface and the next stone down
    // is under our own floor. two deeper every time we asked
    public OptionalInt reuse(int x, int z, int feetY, Dimension now) {
        if (!known || away(x, z, now) || feetY < floorY) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(floorY);
    }

    private boolean away(int x, int z, Dimension now) {
        long dx = x - atX;
        long dz = z - atZ;
        return now != dimension || dx * dx + dz * dz > (long) MAX_WANDER * MAX_WANDER;
    }
}
