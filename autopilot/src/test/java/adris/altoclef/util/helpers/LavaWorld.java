package adris.altoclef.util.helpers;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// a tiny voxel world for the lava rules: air, stone and lava. lava is a liquid, so it is neither something to stand on nor
// somewhere a body fits, same as the real terrain reads it
class LavaWorld implements ItemPickupRules.Terrain, ItemPickupRules.Lava {

    enum Cell { AIR, STONE, LAVA }

    private final Map<Long, Cell> cells = new HashMap<>();
    private final Set<Long> magma = new HashSet<>();

    private static long key(int x, int y, int z) {
        return ((long) x & 0xFFFFF) | (((long) y & 0xFFFFF) << 20) | (((long) z & 0xFFFFF) << 40);
    }

    LavaWorld set(int x, int y, int z, Cell c) {
        cells.put(key(x, y, z), c);
        return this;
    }

    // stone, as far as anything but the magma question goes
    LavaWorld putMagma(int x, int y, int z) {
        magma.add(key(x, y, z));
        return set(x, y, z, Cell.STONE);
    }

    LavaWorld fill(int x1, int y1, int z1, int x2, int y2, int z2, Cell c) {
        for (int x = x1; x <= x2; x++)
            for (int y = y1; y <= y2; y++)
                for (int z = z1; z <= z2; z++)
                    set(x, y, z, c);
        return this;
    }

    // stone from topY - 4 up to and including topY over a square around the origin, air above
    static LavaWorld flat(int topY) {
        return new LavaWorld().fill(-5, topY - 4, -5, 25, topY, 25, Cell.STONE);
    }

    private Cell cell(int x, int y, int z) {
        return cells.getOrDefault(key(x, y, z), Cell.AIR);
    }

    @Override
    public boolean water(int x, int y, int z) {
        return false;
    }

    @Override
    public boolean solid(int x, int y, int z) {
        return cell(x, y, z) == Cell.STONE;
    }

    @Override
    public boolean magma(int x, int y, int z) {
        return solid(x, y, z) && magma.contains(key(x, y, z));
    }

    @Override
    public boolean open(int x, int y, int z) {
        return cell(x, y, z) == Cell.AIR;
    }

    @Override
    public boolean at(int x, int y, int z) {
        return cell(x, y, z) == Cell.LAVA;
    }
}
