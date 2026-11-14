package adris.altoclef.util.helpers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

// (the buddy's fork, Tuning pickless_way_out, a fix of 14 Nov 2026, on) the way the buddy came down from the sky: the cells the
// feet stood in since they were last in the open (LogReach.ROOF or less under the sky's height), one each STEP blocks, a loop
// cut out where the trail comes back within STEP of an older cell. with no pickaxe above wood the way back up is that trail
// backwards, cells the buddy stood in, most of them open still: on a test heel run with no pickaxe at all at y 10 to 26 in a
// dripstone cave baritone priced the rock at 153 a block, every climb by hand got "no way up in three game minutes" and every
// log was banned. a jump of more than JUMP blocks in a tick (a death, a teleport) or another dimension ends the trail; a trail
// past KEEP cells loses its oldest and with them its open start. pure, the caller passes the feet each tick
public final class DownTrail {
    public static final int STEP = 3;
    public static final int KEEP = 4096;
    public static final double JUMP = 24;

    // the one the game feeds (AltoClef's tick) and the way out reads
    public static final DownTrail GLOBAL = new DownTrail();

    private final ArrayDeque<int[]> cells = new ArrayDeque<>();
    private String dim;
    // the oldest cell is not the open one any more (the trail ran past KEEP)
    private boolean cut;

    public static boolean on() {
        return Tuning.get("pickless_way_out", 0) > 0;
    }

    // one tick: the feet, and whether they are in the open
    public synchronized void note(String dimension, int x, int y, int z, boolean open) {
        if (dimension == null || !dimension.equals(dim)) {
            clear();
            dim = dimension;
        }
        int[] last = cells.peekLast();
        if (last != null && dist(last, x, y, z) > JUMP) {
            clear();
            last = null;
        }
        if (open) {
            cells.clear();
            cut = false;
            cells.addLast(new int[]{x, y, z});
            return;
        }
        // no open cell to start from (the buddy came into the world under a roof, or a jump ended the trail): no trail
        if (last == null || dist(last, x, y, z) < STEP) return;
        // back within STEP of an older cell: the loop since then is cut out
        int i = 0;
        for (int[] c : cells) {
            if (dist(c, x, y, z) < STEP) {
                while (cells.size() > i + 1) cells.removeLast();
                return;
            }
            i++;
        }
        cells.addLast(new int[]{x, y, z});
        if (cells.size() > KEEP) {
            cells.removeFirst();
            cut = true;
        }
    }

    // the way back up: the cells from the newest to the oldest, the open one last (unless cut); empty with no trail
    public synchronized List<int[]> wayBack() {
        List<int[]> out = new ArrayList<>(cells.size());
        Iterator<int[]> it = cells.descendingIterator();
        while (it.hasNext()) {
            int[] c = it.next();
            out.add(new int[]{c[0], c[1], c[2]});
        }
        return out;
    }

    // the trail still starts in the open
    public synchronized boolean fromOpen() {
        return !cut && !cells.isEmpty();
    }

    public synchronized int size() {
        return cells.size();
    }

    public synchronized void clear() {
        cells.clear();
        cut = false;
    }

    private static double dist(int[] c, int x, int y, int z) {
        double dx = c[0] - x, dy = c[1] - y, dz = c[2] - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // AltoClef's tick: the player's feet, in the open when no more than LogReach.ROOF under the sky's height over the column
    public static void tick(adris.altoclef.AltoClef mod) {
        var player = mod.getPlayer();
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (player == null || level == null || !on()) return;
        net.minecraft.core.BlockPos feet = player.blockPosition();
        int sky = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ());
        GLOBAL.note(level.dimension().identifier().toString(), feet.getX(), feet.getY(), feet.getZ(), sky - feet.getY() <= LogReach.ROOF);
    }
}
