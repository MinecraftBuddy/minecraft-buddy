package adris.altoclef.util.snapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// one decision the autopilot took (Snapshots.chose, Snapshots.hit): a cell it chose to stand in, hide in or build at, or a swing,
// with what the harness needs to judge it again. the setters return this, so a call site reads as one line; off, Snapshots hands
// out OFF, whose setters do nothing
public class SnapshotPick {
    // "blaze.cover", "ghast.cover", "pillbox", "hit", ...
    public final String kind;
    public long tick;
    // the cell, as {x, y, z} (feet), null for a hit
    public int[] cell;
    // other named cells of the same decision: a corner's peek, a pillbox's look out or front
    public final Map<String, int[]> cells = new LinkedHashMap<>();
    // blocks the decision is about to place (a pillbox's or a brew box's), {x, y, z} each
    public final List<int[]> blocks = new ArrayList<>();
    // the spawner it was planned against
    public int[] spawner;
    // a hit: the target's entity id and type, the eye the swing came from, the target's box {minX, minY, minZ, maxX, maxY,
    // maxZ} and the reach the swing had
    public int target = -1;
    public String targetType;
    public double[] eye;
    public double[] box;
    public double reach;

    public SnapshotPick(String kind) {
        this.kind = kind;
    }

    public SnapshotPick with(String name, int[] c) {
        if (c != null) cells.put(name, c.clone());
        return this;
    }

    public SnapshotPick with(String name, int x, int y, int z) {
        cells.put(name, new int[]{x, y, z});
        return this;
    }

    public SnapshotPick blocks(List<int[]> placed) {
        if (placed != null) for (int[] b : placed) blocks.add(b.clone());
        return this;
    }

    public SnapshotPick spawner(int x, int y, int z) {
        spawner = new int[]{x, y, z};
        return this;
    }

    // the recorder's stand-in while off: nothing is kept
    static final SnapshotPick OFF = new SnapshotPick("off") {
        @Override
        public SnapshotPick with(String name, int[] c) {
            return this;
        }

        @Override
        public SnapshotPick with(String name, int x, int y, int z) {
            return this;
        }

        @Override
        public SnapshotPick blocks(List<int[]> placed) {
            return this;
        }

        @Override
        public SnapshotPick spawner(int x, int y, int z) {
            return this;
        }
    };
}
