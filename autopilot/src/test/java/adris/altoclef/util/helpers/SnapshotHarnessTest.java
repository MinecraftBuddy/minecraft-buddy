package adris.altoclef.util.helpers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.snapshot.SnapshotFrame;
import adris.altoclef.util.snapshot.SnapshotPalette;
import adris.altoclef.util.snapshot.SnapshotPick;
import adris.altoclef.util.snapshot.SnapshotWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.Test;

// the snapshot harness on hand-made snapshots (no recording of a real run): written as the recorder writes them, read back,
// rebuilt as cells and held to the invariants. a cover on a bridge in a blaze's sight and a swing through a wall must be
// flagged, a cover behind a wall on wide ground must not, and a ghast that sees a cover the blazes do not pushes the buddy off
// an edge the blaze fight's own test (a hit of 2.5 from any side) passes
public class SnapshotHarnessTest {
    static final SnapshotPalette.Entry AIR = new SnapshotPalette.Entry("minecraft:air", "", 'O', 'O', false);
    static final SnapshotPalette.Entry BRICKS = new SnapshotPalette.Entry("minecraft:nether_bricks", "", 'S', 'S', true);
    static final SnapshotPalette.Entry LAVA = new SnapshotPalette.Entry("minecraft:lava", "level=0", 'L', 'L', false);

    // the recorder's box (Snapshots: HALF, GRID, DOWN, UP, BELOW)
    static final int HALF = 18, GRID = 4, DOWN = 8, UP = 9, BELOW = 48;

    interface World {
        SnapshotPalette.Entry at(int x, int y, int z);
    }

    // a lava sea at y 31 under everything
    static SnapshotPalette.Entry sea(int y) {
        return y == 31 ? LAVA : y < 31 ? BRICKS : AIR;
    }

    // a bridge three wide along x at y 63, the sea under it
    static final World BRIDGE = (x, y, z) -> y == 63 && Math.abs(z) <= 1 ? BRICKS : sea(y);

    // a yard, x and z -10 to 10 at y 63, a wall four high at x 3 from z -10 to 7, the sea round it
    static final World YARD = (x, y, z) -> {
        if (Math.abs(x) <= 10 && Math.abs(z) <= 10) {
            if (y == 63) return BRICKS;
            if (x == 3 && z <= 7 && y >= 64 && y <= 67) return BRICKS;
        }
        return sea(y);
    };

    // a frame as the recorder makes one: the box round the buddy, the columns under it
    static SnapshotFrame frame(SnapshotPalette pal, World w, double bx, double by, double bz, long tick) {
        SnapshotFrame f = new SnapshotFrame();
        f.tick = tick;
        f.gameTime = 1000 + tick;
        f.wallMillis = 1_700_000_000_000L + tick * 50;
        f.x = bx;
        f.y = by;
        f.z = bz;
        f.eyeY = by + 1.62;
        f.hp = 14;
        f.reach = 3;
        f.onGround = true;
        f.held = "diamond_sword";
        f.armour = new String[]{"iron_boots", "", "iron_chestplate", ""};
        f.bag.put("cobblestone", 64);
        int fx = (int) Math.floor(bx), fy = (int) Math.floor(by), fz = (int) Math.floor(bz);
        f.sx = 2 * HALF + 1;
        f.sz = 2 * HALF + 1;
        f.sy = DOWN + UP + 1;
        f.ox = Math.floorDiv(fx, GRID) * GRID + GRID / 2 - HALF;
        f.oz = Math.floorDiv(fz, GRID) * GRID + GRID / 2 - HALF;
        f.oy = Math.floorDiv(fy, 2) * 2 - DOWN;
        f.cells = new short[f.sx * f.sy * f.sz];
        for (int y = 0; y < f.sy; y++)
            for (int z = 0; z < f.sz; z++)
                for (int x = 0; x < f.sx; x++)
                    f.cells[(y * f.sz + z) * f.sx + x] = (short) pal.add(w.at(f.ox + x, f.oy + y, f.oz + z));
        f.below = new int[f.sx * f.sz];
        for (int z = 0; z < f.sz; z++) {
            for (int x = 0; x < f.sx; x++) {
                SnapshotPalette.Entry bottom = w.at(f.ox + x, f.oy, f.oz + z);
                if (bottom.walk() != 'O' || bottom.body() != 'O') continue;
                int found = SnapshotFrame.NONE_BELOW;
                for (int d = 1; d <= BELOW; d++) {
                    SnapshotPalette.Entry e = w.at(f.ox + x, f.oy - d, f.oz + z);
                    if (e.walk() == 'O' && e.body() == 'O') continue;
                    found = d << 16 | pal.add(e);
                    break;
                }
                f.below[z * f.sx + x] = found;
            }
        }
        f.chain = "UserTaskChain";
        f.tasks.add("CollectBlazeRodsTask");
        f.combat = "NONE";
        return f;
    }

    static SnapshotFrame.Ent ent(String type, int id, double x, double y, double z, double eye, double w, double h, float hp) {
        SnapshotFrame.Ent e = new SnapshotFrame.Ent();
        e.type = type;
        e.id = id;
        e.x = x;
        e.y = y;
        e.z = z;
        e.eye = eye;
        e.width = w;
        e.height = h;
        e.hp = hp;
        e.angry = true;
        e.los = true;
        return e;
    }

    static SnapshotFrame.Ent blaze(int id, double x, double y, double z) {
        return ent("blaze", id, x, y, z, 1.5, 0.6, 1.8, 20);
    }

    static SnapshotPick pick(String kind, int x, int y, int z, long tick) {
        SnapshotPick p = new SnapshotPick(kind);
        p.tick = tick;
        p.cell = new int[]{x, y, z};
        return p;
    }

    static SnapshotPick hit(double ex, double ey, double ez, double[] box, long tick) {
        SnapshotPick p = new SnapshotPick("hit");
        p.tick = tick;
        p.target = 7;
        p.targetType = "blaze";
        p.eye = new double[]{ex, ey, ez};
        p.box = box;
        p.reach = 3;
        return p;
    }

    // written and read back, as a file from the rig would be
    static SnapshotFile roundTrip(String name, SnapshotPalette pal, List<SnapshotFrame> frames) throws IOException {
        SnapshotWriter.Header h = new SnapshotWriter.Header();
        h.reason = "death";
        h.written = "2026-11-06T12:00:00";
        h.build = "test";
        h.dimension = "nether";
        h.tuning = Map.of("safe_stand", "1");
        h.half = HALF;
        h.down = DOWN;
        h.up = UP;
        h.below = BELOW;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SnapshotWriter.write(out, h, pal.entries(), frames);
        return SnapshotFile.read(name, new GZIPInputStream(new ByteArrayInputStream(out.toByteArray())));
    }

    static List<SnapshotChecks.Violation> check(SnapshotFile file) {
        return SnapshotChecks.run(file, SnapshotChecks.ALL, new int[1]);
    }

    static long count(List<SnapshotChecks.Violation> vs, String check) {
        return vs.stream().filter(v -> v.check().equals(check)).count();
    }

    @Test
    public void aFileComesBackAsItWasWritten() throws IOException {
        SnapshotPalette pal = new SnapshotPalette();
        SnapshotFrame a = frame(pal, YARD, 5.5, 64, 0.5, 100);
        a.entities.add(blaze(7, 8.5, 64.5, 0.5));
        a.picks.add(pick("blaze.cover", 1, 64, 0, 100));
        // (the same box a step on: the file says "same" and the reader fills it in)
        SnapshotFrame b = frame(pal, YARD, 5.2, 64, 0.8, 110);
        SnapshotFile file = roundTrip("fixture.json.gz", pal, List.of(a, b));
        assertEquals("death", file.reason);
        assertEquals(1.0, file.tuning("safe_stand", 0), 0);
        assertEquals(pal.size(), file.palette.size());
        assertEquals(2, file.frames.size());
        SnapshotFrame ra = file.frames.get(0), rb = file.frames.get(1);
        assertArrayEquals(a.cells, ra.cells);
        assertArrayEquals(a.below, ra.below);
        assertArrayEquals(a.cells, rb.cells);
        assertEquals(0.5, file.before(ra), 1e-9);
        assertEquals("blaze", ra.entities.get(0).type);
        assertEquals(66.0, ra.entities.get(0).eyeY(), 1e-9);
        assertArrayEquals(new int[]{1, 64, 0}, ra.picks.get(0).cell);
        assertEquals(64, (int) ra.bag.get("cobblestone"));
        // the cells as the world had them: the wall, the floor, the sea under the yard's edge
        SnapshotWorld w = new SnapshotWorld(ra, file.palette);
        assertEquals(baritone.pathing.movement.CliffAvoidance.Cell.SOLID, w.walk.at(3, 65, 0));
        assertEquals(baritone.pathing.movement.CliffAvoidance.Cell.OPEN, w.body.at(0, 64, 0));
        assertEquals(baritone.pathing.movement.CliffAvoidance.Cell.LAVA, w.body.at(14, 31, 0));
        assertEquals(baritone.pathing.movement.CliffAvoidance.Cell.OPEN, w.body.at(14, 40, 0));
    }

    @Test
    public void aCoverOnABridgeInABlazesSightIsFlagged() throws IOException {
        SnapshotPalette pal = new SnapshotPalette();
        SnapshotFrame f = frame(pal, BRIDGE, 0.5, 64, 0.5, 200);
        f.entities.add(blaze(7, 6.5, 65, 0.5));
        f.picks.add(pick("blaze.cover", 0, 64, 1, 200));
        List<SnapshotChecks.Violation> vs = check(roundTrip("bridge.json.gz", pal, List.of(f)));
        assertEquals(vs.toString(), 1, count(vs, "pick-hidden-from-blazes"));
        assertEquals(vs.toString(), 1, count(vs, "pick-knock-safe"));
        assertEquals(vs.toString(), 0, count(vs, "pick-standable"));
        assertTrue(vs.get(0).line().contains("bridge.json.gz"));
    }

    @Test
    public void aCoverBehindAWallOnWideGroundIsClean() throws IOException {
        SnapshotPalette pal = new SnapshotPalette();
        SnapshotFrame f = frame(pal, YARD, 5.5, 64, 0.5, 300);
        f.entities.add(blaze(7, 8.5, 64.5, 0.5));
        f.picks.add(pick("blaze.cover", 1, 64, 0, 300));
        // (a swing at the blaze from 1.7 blocks in the open)
        f.picks.add(hit(6.5, 65.62, 0.5, new double[]{8.2, 64.5, 0.2, 8.8, 66.3, 0.8}, 300));
        SnapshotFile file = roundTrip("yard.json.gz", pal, List.of(f));
        List<SnapshotChecks.Violation> vs = check(file);
        assertTrue(vs.toString(), vs.isEmpty());
        // (the replay did pick a cover there: the nearest the blaze does not see, round the wall's end)
        SnapshotWorld w = new SnapshotWorld(file.frames.get(0), file.palette);
        SafeStand.forced = true;
        try {
            List<net.minecraft.world.phys.Vec3> eyes = List.of(new net.minecraft.world.phys.Vec3(8.5, 66, 0.5));
            int[] c = BlazeCover.pick(w.walk, 5, 64, 0, List.of(), new HashSet<>(), (x, y, z) -> BlazePillbox.hidden(w.sight, eyes, x, y, z));
            assertNotNull(c);
            assertTrue(c[0] <= 2);
        } finally {
            SafeStand.forced = null;
        }
    }

    @Test
    public void aGhastPushesOffAnEdgeTheBlazeFightsTestPasses() throws IOException {
        SnapshotPalette pal = new SnapshotPalette();
        SnapshotFrame f = frame(pal, YARD, 5.5, 64, 0.5, 400);
        f.entities.add(blaze(7, 8.5, 64.5, 0.5));
        // (behind the wall from the blaze, two from the yard's edge: a hit of 2.5 from any side keeps the buddy on it)
        f.picks.add(pick("blaze.cover", 1, 64, 8, 400));
        SnapshotFile quiet = roundTrip("quiet.json.gz", pal, List.of(f));
        assertEquals(0, count(check(quiet), "pick-knock-safe"));
        assertTrue(SnapshotChecks.knock(new SnapshotChecks.Ctx(quiet, 0, new ArrayList<>(), new int[1]),
                new SnapshotWorld(quiet.frames.get(0), quiet.palette).body, new SnapshotWorld(quiet.frames.get(0), quiet.palette).sight,
                new int[]{1, 64, 8}) == null);
        // a ghast out over the sea on the other side sees the cell, and its ball carries 4.5
        f.entities.add(ent("ghast", 9, 1.5, 70, -30, 2.6, 4, 4, 10));
        List<SnapshotChecks.Violation> vs = check(roundTrip("ghast.json.gz", pal, List.of(f)));
        assertEquals(vs.toString(), 1, count(vs, "pick-knock-safe"));
        assertTrue(vs.toString(), vs.stream().anyMatch(v -> v.reason().contains("ghast")));
        // (and the cover BlazeCover picks now, from where the buddy stands, has the same fault: the replay finds it unasked)
        assertTrue(vs.toString(), count(vs, "replay-blaze-cover") >= 1);
    }

    @Test
    public void swingsOutOfReachOrThroughAWallAreFlagged() throws IOException {
        SnapshotPalette pal = new SnapshotPalette();
        SnapshotFrame f = frame(pal, YARD, 5.5, 64, 0.5, 500);
        // (4.5 blocks off in the open, and 2.5 off with the wall between)
        f.picks.add(hit(5.5, 65.62, 0.5, new double[]{10.0, 64, 0.2, 10.6, 65.8, 0.8}, 500));
        f.picks.add(hit(4.5, 65.62, 0.5, new double[]{1.4, 64, 0.2, 2.0, 65.8, 0.8}, 500));
        List<SnapshotChecks.Violation> vs = check(roundTrip("hits.json.gz", pal, List.of(f)));
        assertEquals(vs.toString(), 1, count(vs, "hit-in-reach"));
        assertEquals(vs.toString(), 1, count(vs, "hit-not-through-blocks"));
    }

    @Test
    public void theReplayTestReadsAFolderAndKeepsABaseline() throws IOException {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("snapshots");
        java.nio.file.Path lane = java.nio.file.Files.createDirectories(dir.resolve("t1").resolve("game-logs").resolve("snapshots"));
        SnapshotPalette pal = new SnapshotPalette();
        SnapshotFrame f = frame(pal, BRIDGE, 0.5, 64, 0.5, 200);
        f.entities.add(blaze(7, 6.5, 65, 0.5));
        f.picks.add(pick("blaze.cover", 0, 64, 1, 200));
        SnapshotWriter.Header h = new SnapshotWriter.Header();
        h.reason = "death";
        try (var out = java.nio.file.Files.newOutputStream(lane.resolve("snap-20261106-120000-000-death.json.gz"))) {
            SnapshotWriter.write(out, h, pal.entries(), List.of(f));
        }
        java.nio.file.Path baseline = dir.resolve("baseline.txt");
        try {
            System.setProperty("snapshots", dir.toString());
            System.setProperty("snapshots.report", dir.resolve("report.txt").toString());
            boolean failed = false;
            try {
                new SnapshotReplayTest().everySnapshotHoldsTheInvariants();
            } catch (AssertionError e) {
                failed = true;
                assertTrue(e.getMessage(), e.getMessage().contains("pick-knock-safe") && e.getMessage().contains("snap-20261106-120000-000-death.json.gz"));
            }
            assertTrue(failed);
            // (accepted as they are, and the same run passes)
            System.setProperty("snapshots.baseline", baseline.toString());
            System.setProperty("snapshots.baseline.write", "1");
            new SnapshotReplayTest().everySnapshotHoldsTheInvariants();
            System.clearProperty("snapshots.baseline.write");
            new SnapshotReplayTest().everySnapshotHoldsTheInvariants();
            assertTrue(java.nio.file.Files.readAllLines(baseline).size() >= 2);
        } finally {
            System.clearProperty("snapshots");
            System.clearProperty("snapshots.report");
            System.clearProperty("snapshots.baseline");
            System.clearProperty("snapshots.baseline.write");
        }
    }

    @Test
    public void runsRoundTrip() {
        short[] cells = {3, 3, 3, 5, 7, 7, 0};
        String text = SnapshotWriter.runs(cells);
        assertEquals("3*3,5,7*2,0", text);
        int[] back = SnapshotWriter.unruns(text, cells.length);
        for (int i = 0; i < cells.length; i++) assertEquals(cells[i], back[i]);
    }
}
