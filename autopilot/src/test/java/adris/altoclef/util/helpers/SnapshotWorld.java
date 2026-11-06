package adris.altoclef.util.helpers;

import adris.altoclef.util.snapshot.SnapshotFrame;
import adris.altoclef.util.snapshot.SnapshotPalette;
import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.phys.Vec3;

// one frame's blocks as the planners' cells, both readings (SnapshotPalette): walk as CliffAvoidance.of has it (what the tasks feed
// the planners), body by collision (SafeStand.cells: where a knock stops, what a body stands on). in the box each cell is its
// palette entry's; under an open column what the recorder found down there; over the box open; beside it solid, as an unloaded
// chunk reads (known says which cells were really seen). sight is the body reading with the cells beside and over the box open:
// a ghast hovers in open air, and a wall nobody saw is no cover
final class SnapshotWorld {
    final SnapshotFrame frame;
    private final Cell[] walkOf;
    private final Cell[] bodyOf;
    private final boolean[] fullOf;
    final Cells walk;
    final Cells body;
    final Cells sight;
    // solid only where the block is whole: a ray that passes such a world passed no block anywhere it could have
    final Cells fullOnly;

    SnapshotWorld(SnapshotFrame frame, List<SnapshotPalette.Entry> palette) {
        this.frame = frame;
        walkOf = new Cell[palette.size()];
        bodyOf = new Cell[palette.size()];
        fullOf = new boolean[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            walkOf[i] = cell(palette.get(i).walk());
            bodyOf[i] = cell(palette.get(i).body());
            fullOf[i] = palette.get(i).full() && palette.get(i).body() == 'S';
        }
        walk = (x, y, z) -> at(x, y, z, walkOf, Cell.SOLID);
        body = (x, y, z) -> at(x, y, z, bodyOf, Cell.SOLID);
        sight = (x, y, z) -> at(x, y, z, bodyOf, Cell.OPEN);
        fullOnly = (x, y, z) -> {
            int i = paletteAt(x, y, z);
            return i >= 0 && fullOf[i] ? Cell.SOLID : Cell.OPEN;
        };
    }

    static Cell cell(char c) {
        return switch (c) {
            case 'L' -> Cell.LAVA;
            case 'W' -> Cell.WATER;
            case 'O' -> Cell.OPEN;
            default -> Cell.SOLID;
        };
    }

    private boolean inFootprint(int x, int z) {
        SnapshotFrame f = frame;
        return x >= f.ox && x < f.ox + f.sx && z >= f.oz && z < f.oz + f.sz;
    }

    // the palette index at a cell in the box or of the cell that ends an open column under it, -1 for open or unknown
    private int paletteAt(int x, int y, int z) {
        SnapshotFrame f = frame;
        if (f.cells == null || !inFootprint(x, z) || y >= f.oy + f.sy) return -1;
        if (y >= f.oy) return f.cells[f.index(x, y, z)];
        int b = f.below[(z - f.oz) * f.sx + (x - f.ox)];
        if (b == 0 || b == SnapshotFrame.NONE_BELOW) return -1;
        return (f.oy - y) == (b >>> 16) ? (b & 0xFFFF) : -1;
    }

    private Cell at(int x, int y, int z, Cell[] of, Cell beside) {
        SnapshotFrame f = frame;
        if (f.cells == null || !inFootprint(x, z)) return beside;
        if (y >= f.oy + f.sy) return beside == Cell.SOLID ? Cell.OPEN : beside;
        if (y >= f.oy) return of[f.cells[f.index(x, y, z)]];
        int b = f.below[(z - f.oz) * f.sx + (x - f.ox)];
        // (not looked at: the bottom cell of the box is not open, so nothing under it is reached)
        if (b == 0) return Cell.SOLID;
        if (b == SnapshotFrame.NONE_BELOW) return Cell.OPEN;
        int depth = b >>> 16;
        int d = f.oy - y;
        if (d < depth) return Cell.OPEN;
        if (d == depth) return of[b & 0xFFFF];
        return Cell.SOLID;
    }

    // whether the cells within r (flat) of (x, z) at heights y - down to y + up were all seen
    boolean known(int x, int y, int z, int r, int down, int up) {
        SnapshotFrame f = frame;
        if (f.cells == null) return false;
        return x - r >= f.ox && x + r < f.ox + f.sx && z - r >= f.oz && z + r < f.oz + f.sz && y + up < f.oy + f.sy && y - down >= f.oy;
    }

    boolean inBox(double x, double y, double z) {
        return frame.cells != null && frame.inBox((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    // the cells of a spawner in the box
    List<int[]> blocksNamed(List<SnapshotPalette.Entry> palette, String id) {
        Set<Integer> want = new HashSet<>();
        for (int i = 0; i < palette.size(); i++) if (palette.get(i).id().equals(id)) want.add(i);
        List<int[]> out = new ArrayList<>();
        SnapshotFrame f = frame;
        if (want.isEmpty() || f.cells == null) return out;
        for (int i = 0; i < f.cells.length; i++) {
            if (!want.contains((int) f.cells[i])) continue;
            int x = i % f.sx, z = (i / f.sx) % f.sz, y = i / (f.sx * f.sz);
            out.add(new int[]{f.ox + x, f.oy + y, f.oz + z});
        }
        return out;
    }

    // nothing solid between a and b, the end cells included (a voxel walk, as the game's clip goes block by block). the start
    // cell is left out: an eye inside a block's cell (a slab's) sees out of it
    static boolean clear(Cells cells, Vec3 a, Vec3 b) {
        int x = (int) Math.floor(a.x), y = (int) Math.floor(a.y), z = (int) Math.floor(a.z);
        int ex = (int) Math.floor(b.x), ey = (int) Math.floor(b.y), ez = (int) Math.floor(b.z);
        double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
        int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        double tDeltaX = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx);
        double tDeltaY = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy);
        double tDeltaZ = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
        double tMaxX = dx == 0 ? Double.MAX_VALUE : ((dx > 0 ? x + 1 - a.x : a.x - x) * tDeltaX);
        double tMaxY = dy == 0 ? Double.MAX_VALUE : ((dy > 0 ? y + 1 - a.y : a.y - y) * tDeltaY);
        double tMaxZ = dz == 0 ? Double.MAX_VALUE : ((dz > 0 ? z + 1 - a.z : a.z - z) * tDeltaZ);
        for (int i = 0; i < 512; i++) {
            if (x == ex && y == ey && z == ez) return true;
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                if (tMaxX > 1) return true;
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                if (tMaxY > 1) return true;
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                if (tMaxZ > 1) return true;
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
            if (cells.at(x, y, z) == Cell.SOLID) return false;
        }
        return true;
    }
}
