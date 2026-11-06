package adris.altoclef.ui.gui.mc.ghost;

import adris.altoclef.ui.gui.mc.ghost.GhostScan.Ghost;
import adris.altoclef.util.helpers.ItemFixture;
import baritone.api.schematic.ISchematic;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static adris.altoclef.ui.gui.mc.ghost.GhostScanTest.STONE;
import static adris.altoclef.ui.gui.mc.ghost.GhostScanTest.box;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// the section bookkeeping: what gets scanned when, the caps, and what throws the cache away
public class GhostFieldTest {

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    record Piece(int x, int y, int z, int quads) implements GhostField.Piece {
    }

    private int bakes;

    private GhostField<Piece> field() {
        return new GhostField<>(ghosts -> {
            bakes++;
            List<Piece> out = new ArrayList<>();
            for (Ghost g : ghosts) {
                out.add(new Piece(g.x(), g.y(), g.z(), 6));
            }
            return out;
        });
    }

    // a clock that moves a set amount every time it's read
    private static final class Clock {
        long now;
        final long step;

        Clock(long step) {
            this.step = step;
        }

        long read() {
            return now += step;
        }
    }

    private static int scanAll(GhostField<Piece> f, GhostScan.World world) {
        int total = 0;
        int n;
        while ((n = f.tick(world, () -> 0, Long.MAX_VALUE)) > 0 && !f.settled()) {
            total += n;
        }
        return total + n;
    }

    private static List<Piece> visible(GhostField<Piece> f, double x, double y, double z, int cap) {
        List<Piece> out = new ArrayList<>();
        f.forEachVisible(x, y, z, cap, out::add);
        return out;
    }

    @Test
    public void nothingWithoutATargetOrACamera() {
        GhostField<Piece> f = field();
        GhostScanTest.FakeWorld world = new GhostScanTest.FakeWorld();
        assertEquals(0, f.tick(world, () -> 0, Long.MAX_VALUE));
        f.target("a", box(4, 4, 4, STONE), 0, 64, 0);
        assertEquals(0, f.tick(world, () -> 0, Long.MAX_VALUE));
        f.camera(0, 64, 0);
        assertEquals(1, f.tick(world, () -> 0, Long.MAX_VALUE));
        assertTrue(f.settled());
        // 4x4x4 of stone in the air: the 2x2x2 middle can't be seen, the other 56 are ghosts
        assertEquals(56, f.ghostCount());
    }

    @Test
    public void onlySectionsNearTheCameraAreTracked() {
        GhostField<Piece> f = field();
        GhostScanTest.FakeWorld world = new GhostScanTest.FakeWorld();
        // 256 long in x, camera at the west end, distance 32: sections 0..3 (the ball plus a section of slack)
        f.distance(32);
        f.target("a", box(256, 1, 1, STONE), 0, 64, 0);
        f.camera(8, 64, 0);
        scanAll(f, world);
        assertTrue(f.sectionCount() <= 4);
        assertNull(f.dirtyAt(200, 64, 0));
        // and the frame cuts at the distance itself, block by block
        for (Piece p : visible(f, 8, 64, 0, Integer.MAX_VALUE)) {
            assertTrue(p.x() + 0.5 - 8 <= 32);
        }
        // walking east brings the far end in and lets the west go
        f.camera(200, 64, 0);
        scanAll(f, world);
        assertNull(f.dirtyAt(0, 64, 0));
        assertFalse(f.dirtyAt(200, 64, 0));
    }

    @Test
    public void theQuadCapStopsTheFrameNearestFirst() {
        GhostField<Piece> f = field();
        GhostScanTest.FakeWorld world = new GhostScanTest.FakeWorld();
        f.distance(128);
        f.target("a", box(64, 1, 1, STONE), 0, 64, 0);
        f.camera(0, 64, 0);
        scanAll(f, world);
        // 6 quads each, a cap of 100 is 16 blocks, and they're the ones in the camera's own section
        List<Piece> shown = visible(f, 0, 64, 0, 100);
        assertEquals(16, shown.size());
        for (Piece p : shown) {
            assertTrue(p.x() < 16);
        }
        assertEquals(96, f.forEachVisible(0, 64, 0, 100, p -> { }));
    }

    @Test
    public void theScanBudgetSpreadsABigBuildOverTicks() {
        GhostField<Piece> f = field();
        GhostScanTest.FakeWorld world = new GhostScanTest.FakeWorld();
        f.target("a", box(64, 16, 16, STONE), 0, 64, 0);
        f.camera(0, 64, 0);
        // every clock read is 1 ms, budget 2 ms: the read after the first scan says 1 ms, so a second one fits, the
        // read after that says 2 ms and stops it
        Clock clock = new Clock(1_000_000);
        assertEquals(2, f.tick(world, clock::read, 2_000_000));
        assertFalse(f.settled());
        // a budget of nothing still does one, or a slow machine would never draw anything
        assertEquals(1, f.tick(world, clock::read, 0));
    }

    @Test
    public void aBlockChangeOnlyRescansItsSectionAndTheNeighbourOnTheEdge() {
        GhostField<Piece> f = field();
        GhostScanTest.FakeWorld world = new GhostScanTest.FakeWorld();
        f.target("a", box(48, 1, 1, STONE), 0, 64, 0);
        f.camera(16, 64, 0);
        scanAll(f, world);
        int before = bakes;
        f.blockChanged(20, 64, 0);
        assertTrue(f.dirtyAt(20, 64, 0));
        assertFalse(f.dirtyAt(0, 64, 0));
        assertFalse(f.dirtyAt(40, 64, 0));
        // x=16 is the west edge of section 1, section 0 culled against it
        f.blockChanged(16, 64, 0);
        assertTrue(f.dirtyAt(0, 64, 0));
        assertFalse(f.dirtyAt(40, 64, 0));
        assertEquals(2, f.tick(world, () -> 0, Long.MAX_VALUE));
        assertEquals(before + 2, bakes);
        // changes outside the tracked sections are nobody's business
        f.blockChanged(5000, 64, 0);
        assertEquals(0, f.tick(world, () -> 0, Long.MAX_VALUE));
    }

    @Test
    public void aChunkArrivingRescansItsColumn() {
        GhostField<Piece> f = field();
        GhostScanTest.FakeWorld world = new GhostScanTest.FakeWorld();
        world.unload(1, 0);
        f.target("a", box(32, 32, 1, STONE), 0, 64, 0);
        f.camera(0, 64, 0);
        scanAll(f, world);
        int before = f.ghostCount();
        world.unloaded.clear();
        f.chunkLoaded(1, 0);
        assertTrue(f.dirtyAt(20, 64, 0));
        assertTrue(f.dirtyAt(20, 80, 0));
        assertFalse(f.dirtyAt(0, 64, 0));
        scanAll(f, world);
        assertEquals(before * 2, f.ghostCount());
    }

    @Test
    public void moveTurnAndFileChangesInvalidate() {
        GhostField<Piece> f = field();
        GhostScanTest.FakeWorld world = new GhostScanTest.FakeWorld();
        ISchematic s = box(4, 1, 1, STONE);
        f.target("a", s, 0, 64, 0);
        f.camera(0, 64, 0);
        scanAll(f, world);
        int generation = f.generation();
        // the same thing again is free
        assertFalse(f.target("a", s, 0, 64, 0));
        assertEquals(generation, f.generation());
        // moved: the old pieces go straight away, no old and new spot mixed on screen
        assertTrue(f.target("a", s, 1, 64, 0));
        assertTrue(visible(f, 0, 64, 0, Integer.MAX_VALUE).isEmpty());
        scanAll(f, world);
        assertEquals(1, visible(f, 0, 64, 0, Integer.MAX_VALUE).get(0).x());
        // turned: McSchematics hands out a new wrapper, which is a new target
        assertTrue(f.target("a", box(1, 1, 4, STONE), 1, 64, 0));
        assertTrue(visible(f, 0, 64, 0, Integer.MAX_VALUE).isEmpty());
        assertTrue(f.generation() > generation);
        // another file starts from nothing, no half of the old one on screen
        assertTrue(f.target("b", box(4, 1, 1, STONE), 1, 64, 0));
        assertTrue(visible(f, 0, 64, 0, Integer.MAX_VALUE).isEmpty());
        f.clear();
        assertFalse(f.active());
        assertEquals(0, f.tick(world, () -> 0, Long.MAX_VALUE));
    }

    @Test
    public void aResourceReloadRebakesEverythingWithoutBlankingIt() {
        GhostField<Piece> f = field();
        GhostScanTest.FakeWorld world = new GhostScanTest.FakeWorld();
        f.target("a", box(32, 1, 1, STONE), 0, 64, 0);
        f.camera(0, 64, 0);
        scanAll(f, world);
        int before = bakes;
        f.dirtyAll();
        assertTrue(f.dirtyAt(0, 64, 0));
        assertEquals(32, visible(f, 0, 64, 0, Integer.MAX_VALUE).size());
        scanAll(f, world);
        assertEquals(before + 2, bakes);
    }

    @Test
    public void aDistanceChangeRefreshesTheRange() {
        GhostField<Piece> f = field();
        GhostScanTest.FakeWorld world = new GhostScanTest.FakeWorld();
        f.distance(16);
        f.target("a", box(256, 1, 1, STONE), 0, 64, 0);
        f.camera(8, 64, 0);
        scanAll(f, world);
        int small = f.sectionCount();
        f.distance(128);
        scanAll(f, world);
        assertTrue(f.sectionCount() > small);
    }

    @Test
    public void aCleanSectionIsLookedAtAgainEventually() {
        GhostField<Piece> f = field();
        GhostScanTest.FakeWorld world = new GhostScanTest.FakeWorld();
        f.target("a", box(4, 1, 1, STONE), 0, 64, 0);
        f.camera(0, 64, 0);
        scanAll(f, world);
        // something changed without an event
        world.set(0, 64, 0, STONE);
        for (int i = 1; i < GhostField.REFRESH_TICKS; i++) {
            assertEquals(0, f.tick(world, () -> 0, Long.MAX_VALUE));
        }
        assertEquals(1, f.tick(world, () -> 0, Long.MAX_VALUE));
        assertEquals(3, f.ghostCount());
    }

    @Test
    public void theBackstopRefreshIsOneSectionEveryFewTicksNotEveryTick() {
        GhostField<Piece> f = field();
        GhostScanTest.FakeWorld world = new GhostScanTest.FakeWorld();
        // 4 sections, all of them old enough at once
        f.target("a", box(64, 1, 1, STONE), 0, 64, 0);
        f.camera(0, 64, 0);
        scanAll(f, world);
        int scans = 0;
        for (int i = 0; i < GhostField.REFRESH_TICKS + 4 * GhostField.REFRESH_EVERY; i++) {
            scans += f.tick(world, () -> 0, Long.MAX_VALUE);
        }
        assertEquals(4, scans);
    }
}
