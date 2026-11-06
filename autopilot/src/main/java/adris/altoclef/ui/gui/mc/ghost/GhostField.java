package adris.altoclef.ui.gui.mc.ghost;

import adris.altoclef.ui.gui.mc.ghost.GhostScan.Ghost;
import baritone.api.schematic.ISchematic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

// the ghost of one placed schematic, cut into 16^3 sections around the camera. a section is scanned (GhostScan) and
// baked once, then only again when something marks it dirty: a block change in it, its chunk arriving, the slow
// refresh, or the target moving. the frame only walks the baked pieces. game thread only. pure apart from BlockState,
// the baking (the minecraft models) comes in through Baker so the tests don't need a client
public final class GhostField<P extends GhostField.Piece> {

    public interface Piece {
        int x();

        int y();

        int z();

        // how many quads it costs to draw, for the cap
        int quads();
    }

    public interface Baker<P> {
        List<P> bake(List<Ghost> ghosts);
    }

    // ticks before a clean section gets looked at again anyway, for whatever changed without telling us. every
    // setBlock reaches blockChanged now, so this is a backstop: one section per REFRESH_EVERY ticks, not one a tick
    // forever (that was ~0.75 ms every tick on a big build)
    static final int REFRESH_TICKS = 100;
    static final int REFRESH_EVERY = 10;

    final class Section {
        final int sx;
        final int sy;
        final int sz;
        boolean dirty = true;
        int scannedTick;
        List<P> pieces = List.of();
        int ghosts;

        Section(int sx, int sy, int sz) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
        }
    }

    private final Baker<P> baker;
    private final GhostScan scan = new GhostScan();
    private final Map<Long, Section> sections = new HashMap<>();
    private final List<Section> order = new ArrayList<>();

    private Object path;
    private ISchematic schematic;
    private int ox;
    private int oy;
    private int oz;
    private int distance = 64;
    private boolean rangeStale = true;
    private int camSX;
    private int camSY;
    private int camSZ;
    private boolean hasCamera;
    private int tick;
    private int refreshedTick;
    // how many times the target changed, so the renderer can log the first full build of each one
    private int generation;

    public GhostField(Baker<P> baker) {
        this.baker = baker;
    }

    // the schematic (by identity: McSchematics hands out a new wrapper when the turn or flip changes) and its corner.
    // any change drops everything. keeping the old pieces up while rescanning showed the old and the new spot mixed
    // for a couple of seconds, a short blank reads better. the rescan goes nearest the camera first anyway. true when
    // anything changed
    public boolean target(Object path, ISchematic s, int x, int y, int z) {
        if (s == schematic && x == ox && y == oy && z == oz && Objects.equals(path, this.path)) {
            return false;
        }
        sections.clear();
        order.clear();
        this.path = path;
        schematic = s;
        ox = x;
        oy = y;
        oz = z;
        rangeStale = true;
        generation++;
        return true;
    }

    public void clear() {
        if (schematic != null) {
            target(null, null, 0, 0, 0);
        }
    }

    public boolean active() {
        return schematic != null;
    }

    public int generation() {
        return generation;
    }

    public void distance(int blocks) {
        if (blocks != distance) {
            distance = blocks;
            rangeStale = true;
        }
    }

    public int distance() {
        return distance;
    }

    public void camera(double x, double y, double z) {
        int sx = floor(x) >> 4;
        int sy = floor(y) >> 4;
        int sz = floor(z) >> 4;
        if (!hasCamera || sx != camSX || sy != camSY || sz != camSZ) {
            hasCamera = true;
            camSX = sx;
            camSY = sy;
            camSZ = sz;
            rangeStale = true;
        }
    }

    // a block changed. the sections next door care too when it's on the edge, their culling looked at it
    public void blockChanged(int x, int y, int z) {
        int sx = x >> 4;
        int sy = y >> 4;
        int sz = z >> 4;
        dirty(sx, sy, sz);
        int lx = x & 15;
        int ly = y & 15;
        int lz = z & 15;
        if (lx == 0) {
            dirty(sx - 1, sy, sz);
        } else if (lx == 15) {
            dirty(sx + 1, sy, sz);
        }
        if (ly == 0) {
            dirty(sx, sy - 1, sz);
        } else if (ly == 15) {
            dirty(sx, sy + 1, sz);
        }
        if (lz == 0) {
            dirty(sx, sy, sz - 1);
        } else if (lz == 15) {
            dirty(sx, sy, sz + 1);
        }
    }

    // a chunk came in. everything in that column was scanned as "not loaded" (nothing), go again
    public void chunkLoaded(int chunkX, int chunkZ) {
        for (Section s : order) {
            if (s.sx == chunkX && s.sz == chunkZ) {
                s.dirty = true;
            }
        }
    }

    // everything again, keeping what's drawn until each section is redone
    public void dirtyAll() {
        for (Section s : order) {
            s.dirty = true;
        }
    }

    private void dirty(int sx, int sy, int sz) {
        Section s = sections.get(key(sx, sy, sz));
        if (s != null) {
            s.dirty = true;
        }
    }

    // keeps the section set in step with the camera, then scans dirty sections nearest first until the budget is
    // gone (always at least one, or a slow machine would never see anything). returns how many it scanned
    public int tick(GhostScan.World world, LongSupplier nanos, long budgetNanos) {
        tick++;
        if (schematic == null || !hasCamera) {
            return 0;
        }
        if (rangeStale) {
            refreshRange();
        }
        long start = nanos.getAsLong();
        int scanned = 0;
        for (Section s : order) {
            if (!s.dirty) {
                continue;
            }
            if (scanned > 0 && nanos.getAsLong() - start >= budgetNanos) {
                return scanned;
            }
            scan(s, world);
            scanned++;
        }
        if (scanned == 0 && tick - refreshedTick >= REFRESH_EVERY) {
            Section oldest = null;
            for (Section s : order) {
                if (tick - s.scannedTick >= REFRESH_TICKS && (oldest == null || s.scannedTick < oldest.scannedTick)) {
                    oldest = s;
                }
            }
            if (oldest != null) {
                refreshedTick = tick;
                scan(oldest, world);
                scanned++;
            }
        }
        return scanned;
    }

    private void scan(Section s, GhostScan.World world) {
        List<Ghost> ghosts = scan.section(schematic, ox, oy, oz, world, s.sx, s.sy, s.sz);
        s.ghosts = ghosts.size();
        s.pieces = ghosts.isEmpty() ? List.of() : baker.bake(ghosts);
        s.dirty = false;
        s.scannedTick = tick;
    }

    // the sections that touch both the footprint and the ball around the camera, nearest first. the ones that are
    // still in range keep what they had
    private void refreshRange() {
        rangeStale = false;
        order.clear();
        int r = (distance + 15) >> 4;
        int minSX = Math.max(camSX - r, ox >> 4);
        int maxSX = Math.min(camSX + r, (ox + schematic.widthX() - 1) >> 4);
        int minSY = Math.max(camSY - r, oy >> 4);
        int maxSY = Math.min(camSY + r, (oy + schematic.heightY() - 1) >> 4);
        int minSZ = Math.max(camSZ - r, oz >> 4);
        int maxSZ = Math.min(camSZ + r, (oz + schematic.lengthZ() - 1) >> 4);
        Map<Long, Section> kept = new HashMap<>();
        for (int sx = minSX; sx <= maxSX; sx++) {
            for (int sy = minSY; sy <= maxSY; sy++) {
                for (int sz = minSZ; sz <= maxSZ; sz++) {
                    if (!inBall(sx, sy, sz)) {
                        continue;
                    }
                    long k = key(sx, sy, sz);
                    Section s = sections.get(k);
                    kept.put(k, s != null ? s : new Section(sx, sy, sz));
                }
            }
        }
        sections.clear();
        sections.putAll(kept);
        order.addAll(kept.values());
        order.sort(Comparator.comparingLong(this::sectionDistance));
    }

    // the nearest point of the section box to the camera section's middle, against the distance plus a section of
    // slack (the camera wanders around inside its own section before this runs again)
    private boolean inBall(int sx, int sy, int sz) {
        long reach = distance + 16L;
        return sectionDistance(sx, sy, sz) <= reach * reach;
    }

    private long sectionDistance(Section s) {
        return sectionDistance(s.sx, s.sy, s.sz);
    }

    private long sectionDistance(int sx, int sy, int sz) {
        long dx = gap(sx, camSX);
        long dy = gap(sy, camSY);
        long dz = gap(sz, camSZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private static long gap(int s, int cam) {
        int c = (cam << 4) + 8;
        int min = s << 4;
        int max = min + 15;
        return c < min ? min - c : c > max ? c - max : 0;
    }

    // every piece within the distance of the camera, nearest section first, until the quad cap. returns the quads
    public int forEachVisible(double camX, double camY, double camZ, int maxQuads, Consumer<P> out) {
        double reach = (double) distance * distance;
        int quads = 0;
        for (Section s : order) {
            for (P p : s.pieces) {
                double dx = p.x() + 0.5 - camX;
                double dy = p.y() + 0.5 - camY;
                double dz = p.z() + 0.5 - camZ;
                if (dx * dx + dy * dy + dz * dz > reach) {
                    continue;
                }
                if (quads + p.quads() > maxQuads) {
                    return quads;
                }
                quads += p.quads();
                out.accept(p);
            }
        }
        return quads;
    }

    // nothing left to scan in range, the first build of a target is done
    public boolean settled() {
        if (schematic == null || rangeStale) {
            return false;
        }
        for (Section s : order) {
            if (s.dirty) {
                return false;
            }
        }
        return true;
    }

    public int sectionCount() {
        return order.size();
    }

    public int ghostCount() {
        int n = 0;
        for (Section s : order) {
            n += s.ghosts;
        }
        return n;
    }

    // what everything in range would cost to draw, before the distance cut and the cap
    public int quadCount() {
        int n = 0;
        for (Section s : order) {
            for (P p : s.pieces) {
                n += p.quads();
            }
        }
        return n;
    }

    public int pieceCount() {
        int n = 0;
        for (Section s : order) {
            n += s.pieces.size();
        }
        return n;
    }

    // for the tests: is the section holding this block dirty (null when it isn't tracked)
    Boolean dirtyAt(int x, int y, int z) {
        Section s = sections.get(key(x >> 4, y >> 4, z >> 4));
        return s == null ? null : s.dirty;
    }

    private static long key(int sx, int sy, int sz) {
        // same packing as SectionPos.asLong, 22 / 20 / 22 bits
        return ((long) sx & 0x3FFFFF) << 42 | ((long) sy & 0xFFFFF) | ((long) sz & 0x3FFFFF) << 20;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
