package adris.altoclef.util.helpers;

import adris.altoclef.AltoClef;
import adris.altoclef.trackers.BanPolicy;
import baritone.api.utils.Dimension;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;

// a lava scoop the client believed and the server did not. a flowing cell the client has as a source gets raytraced, the
// client predicts a full bucket, the server's ray misses and nothing happens, and nothing tells us: no inventory resync on
// a failed use, so the fake bucket sits in hand until the next full refresh. the cell is the tell, a real scoop leaves it
// air or flowing within a few ticks and a phantom one snaps back to a source.
// lives out here, not in the scoop task, because the cast interrupts the collect the same second it clicks
public final class PhantomScoops {
    // the block ack and the server's update are a round trip away at worst, this is plenty
    static final int CHECK_AFTER = 15;
    // two phantoms on a cell and it is banned (strike counts failures past `allowed`)
    static final int ALLOWED = 1;
    // nobody checks a click this old, the world has moved on
    static final int STALE = 200;

    private static Dimension dim;
    private static int x, y, z;
    private static int clickedAt = Integer.MIN_VALUE;
    // bumped on every phantom, the cast compares it to know its failed pours were not the cell's fault
    private static int epoch;

    private PhantomScoops() {
    }

    // the pure part: strike only for a lava cell the client still has as a source, where the click's ray hit the cell we
    // recorded. water refills so a source after a water scoop says nothing
    public static boolean phantom(boolean cellStillSource, boolean isLava, boolean hitMatchesRecorded) {
        return isLava && cellStillSource && hitMatchesRecorded;
    }

    public static synchronized boolean pending() {
        return clickedAt != Integer.MIN_VALUE;
    }

    // a scoop click went out and its ray hit this cell
    public static synchronized void arm(Dimension d, int cx, int cy, int cz, int now) {
        dim = d;
        x = cx;
        y = cy;
        z = cz;
        clickedAt = now;
    }

    // the recorded cell once it is due, and gone from the holder. null while it isn't due (or after it went stale)
    static synchronized int[] takeDue(int now) {
        if (clickedAt == Integer.MIN_VALUE) {
            return null;
        }
        int age = now - clickedAt;
        if (age < 0 || age > STALE) {
            clickedAt = Integer.MIN_VALUE;
            return null;
        }
        if (age < CHECK_AFTER) {
            return null;
        }
        clickedAt = Integer.MIN_VALUE;
        return new int[]{x, y, z};
    }

    static synchronized Dimension armedDim() {
        return dim;
    }

    public static synchronized int epoch() {
        return epoch;
    }

    static synchronized void reset() {
        clickedAt = Integer.MIN_VALUE;
        dim = null;
        epoch = 0;
    }

    // every client tick, cheap when nothing is armed
    public static void tick(AltoClef mod) {
        if (mod.getWorld() == null || mod.getPlayer() == null) {
            return;
        }
        int[] due = takeDue(WorldHelper.getTicks());
        if (due == null) {
            return;
        }
        Dimension now = WorldHelper.getCurrentDimension();
        BlockPos at = new BlockPos(due[0], due[1], due[2]);
        BlockState state = mod.getWorld().getBlockState(at);
        boolean lava = state.getFluidState().is(FluidTags.LAVA);
        boolean source = lava && state.getFluidState().isSource();
        if (!phantom(source, lava, now == armedDim())) {
            return;
        }
        synchronized (PhantomScoops.class) {
            epoch++;
        }
        BanPolicy.blockStrike(mod.getBans(), now, at.getX(), at.getY(), at.getZ(), ALLOWED,
                mod.getPlayer().distanceToSqr(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5),
                "the lava scoop never took (the server has it flowing)");
        // the fake bucket goes away now, not at the next duct tape refresh, so nothing pours it
        mod.getSlotHandler().refreshInventory();
    }
}
