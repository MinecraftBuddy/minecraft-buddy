package adris.altoclef.util.helpers;

// a drop we have started walking to stays the answer until it is in the bag, gone, or we ran out of patience. before this
// every tick re-asked "what is nearest" and a block one step from the drop could take it away halfway (raw_iron picked up,
// interrupted by the ore next to it, picked up again, three times in three seconds). the patience is for the drop that
// sits in leaves or a hole and never comes: after 5 seconds it is somebody else's problem. pure, the caller says when a tick went by.
// the give-up itself is a ban now (BanPolicy.dropGaveUp), this only keeps the clock
public final class DropPatience {
    public static final int TICKS = 100;
    // 30 s. walking closer resets the clock, so this is what stops a drop we keep almost reaching
    public static final int HARD_CAP = 600;

    private int lockedId = -1;
    private int waited;
    private int total;
    private double bestDist = Double.POSITIVE_INFINITY;

    // we are going for this drop (entity id). asking again for the same one does not restart the clock
    public void lock(int id) {
        if (lockedId != id) {
            lockedId = id;
            waited = 0;
            total = 0;
            bestDist = Double.POSITIVE_INFINITY;
        }
    }

    // one tick spent on the locked drop. a counter and not a start time so a pause (eating, a fight) doesn't eat the patience
    public void tick() {
        waited++;
        total++;
    }

    // how far the locked drop is from us right now. getting half a block closer than ever before is a walk that works, so
    // the clock starts over: a drop 30 blocks off takes longer than 5 seconds and that is fine, standing still is not
    public void progress(double dist) {
        if (dist < bestDist - 0.5) {
            bestDist = dist;
            waited = 0;
        }
    }

    public boolean isLocked() {
        return lockedId != -1;
    }

    public int lockedId() {
        return lockedId;
    }

    public boolean expired() {
        return isLocked() && (waited > TICKS || total > HARD_CAP);
    }

    // gone, picked up, or no longer ours to chase. not a failure
    public void unlock() {
        lockedId = -1;
    }

    // the locked drop never came. hands back its id for the ban (-1 when nothing was locked), the ban is what keeps us off it
    public int giveUp() {
        int id = lockedId;
        unlock();
        return id;
    }

    public void clear() {
        unlock();
    }

    // vanilla picks an item up when it is inside the player box grown by 1 sideways and half a block up and down, so a drop
    // this close is in the bag in a few ticks without us walking at it, and walking at it lets go of the mouse button.
    // dx, dy, dz are drop minus us (feet)
    public static boolean alreadyInReach(double dx, double dy, double dz) {
        return Math.abs(dx) <= 1.0 && Math.abs(dz) <= 1.0 && dy >= -0.5 && dy <= 1.8;
    }
}
