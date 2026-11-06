package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.GetDownColumnTask;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasksystem.Task;
import net.minecraft.core.BlockPos;

import java.util.Optional;

// the walk back to a portal far under us. a plain goal on it heads for whatever is straight above, and when that's a lake nothing
// can be dug and the search just shuttles along the bank. so go down from dry land like the room search does, then walk the last bit.
// not the room task itself: that one writes strongholdStart and carries a whole search with it
public final class PortalDig {
    static final int WALK = Integer.MIN_VALUE;
    // same idea as noDryUntil, the far shore chunks may just not be loaded yet
    private static final double NO_DRY_RETRY_SECONDS = 30;
    // near lets go of the walk this much later than it takes it (one dig segment): a route that climbs one block over the line
    // must not get marched back to the column, then handed back, then marched back
    private static final double HANDED_OVER_SLACK = 8;

    // the clock counts ticks we really dug: a gear errand or a fight in the middle must not read as a stuck column when we get back
    private int ticks;
    private double noDryUntil;
    private boolean gaveUp;
    private boolean handedOver;
    private RoomCoverage.DigWatch watch;
    private int centreX;
    private int centreZ;
    private int digX;
    private int digZ;
    private int segmentY;
    private int lowestY = Integer.MAX_VALUE;
    private Task digTask;
    private int digTaskY;

    // null = the plain walk
    public Task step(AltoClef mod, GamerContext ctx, BlockPos portal) {
        if (portal == null) {
            return null;
        }
        double px = mod.getPlayer().getX();
        double py = mod.getPlayer().getY();
        double pz = mod.getPlayer().getZ();
        boolean hadColumn = hasColumn();
        int segment = plan(px, py, pz, portal.getX(), portal.getY(), portal.getZ(), StrongholdRoomSearchTask.probe(mod));
        if (segment == WALK) {
            return null;
        }
        if (!hadColumn) {
            ctx.log("portal: " + Math.round(Math.hypot(portal.getX() - px, portal.getZ() - pz)) + " away and " + Math.round(py - portal.getY())
                    + " below, digging down from dry land at " + digX + ", " + digZ);
        }
        int y = (int) Math.floor(py);
        if (y < lowestY) {
            lowestY = y;
            ctx.progress("dug down");
        }
        // a new task per tick restarts its state, and a shift already dropped the old one
        if (digTask == null || segment != digTaskY) {
            digTaskY = segment;
            digTask = new GetDownColumnTask(digX, digZ, segment, false);
        }
        return digTask;
    }

    // the whole decision with no game in it: the y to dig to on the current column, or WALK
    int plan(double px, double py, double pz, int fx, int fy, int fz, RoomRetarget.Probe probe) {
        if (gaveUp || walking(px, py, pz, fx, fy, fz)) {
            return WALK;
        }
        double now = ticks++ / 20.0;
        if (watch == null && !pick(py, fy, fx, fz, probe, now)) {
            return WALK;
        }
        watch.update(px, py, pz, digX, digZ, now);
        if (watch.stuck(now)) {
            int[] cell = watch.shift(centreX, centreZ, now);
            if (cell == null) {
                // every cell had its minute. the walk may be bad but it's what's left, and it stays that way till we die
                gaveUp = true;
                return WALK;
            }
            digX = cell[0];
            digZ = cell[1];
            segmentY = RoomCoverage.firstSegmentY((int) Math.floor(py), fy);
            digTask = null;
        }
        segmentY = RoomCoverage.nextSegmentY(segmentY, py, RoomCoverage.onColumn(px, pz, digX, digZ), fy);
        return segmentY;
    }

    private boolean walking(double px, double py, double pz, int fx, int fy, int fz) {
        if (RoomRetarget.near(px, py, pz, fx, fy, fz)) {
            handedOver = true;
        } else if (Math.hypot(fx - px, fz - pz) > RoomRetarget.NEAR_HORIZONTAL + HANDED_OVER_SLACK
                || Math.abs(fy - py) > RoomRetarget.NEAR_VERTICAL + HANDED_OVER_SLACK) {
            // properly away again, an errand say. the way back is the same long walk as before
            handedOver = false;
        }
        return handedOver;
    }

    private boolean pick(double py, int fy, int fx, int fz, RoomRetarget.Probe probe, double now) {
        if (now < noDryUntil) {
            return false;
        }
        Optional<int[]> column = RoomRetarget.pickColumn(probe, fx, fz, fx, fz);
        if (column.isEmpty()) {
            noDryUntil = now + NO_DRY_RETRY_SECONDS;
            return false;
        }
        centreX = digX = column.get()[0];
        centreZ = digZ = column.get()[1];
        int entryY = (int) Math.floor(py);
        watch = new RoomCoverage.DigWatch(entryY, now);
        segmentY = RoomCoverage.firstSegmentY(entryY, fy);
        return true;
    }

    // a phase restart means the walk stalled, so it doesn't get to hold on through the slack. inside near it's still the walk
    public void walkStalled() {
        handedOver = false;
    }

    boolean hasColumn() {
        return watch != null;
    }

    int digX() {
        return digX;
    }

    int digZ() {
        return digZ;
    }
}
