package adris.altoclef.tasks.speedrun.gamer.portal;

import adris.altoclef.tasks.speedrun.gamer.portal.PortalMold.Cast;
import adris.altoclef.tasks.speedrun.gamer.portal.PortalMold.Inv;
import adris.altoclef.tasks.speedrun.gamer.portal.PortalMold.Kind;
import adris.altoclef.tasks.speedrun.gamer.portal.PortalMold.Layout;
import adris.altoclef.tasks.speedrun.gamer.portal.PortalMold.Mat;
import adris.altoclef.tasks.speedrun.gamer.portal.PortalMold.P;
import adris.altoclef.tasks.speedrun.gamer.portal.PortalMold.Step;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PortalMoldTest {
    // a flat lava pool 7 deep in front of a straight shore, laid out in layout coordinates so any orientation works
    static final class World implements PortalMold.Terrain {
        final Layout l;
        final Map<P, Mat> over = new HashMap<>();
        final Set<P> waterSources = new HashSet<>();
        boolean lava;
        boolean water = true;
        int empty = 1;
        final List<String> log = new ArrayList<>();

        World(Layout l) {
            this.l = l;
        }

        @Override
        public Mat at(int x, int y, int z) {
            P p = new P(x, y, z);
            Mat o = over.get(p);
            if (o != null) {
                return o;
            }
            if (waterSources.contains(p)) {
                return Mat.WATER;
            }
            int dx = x - l.ox();
            int dz = z - l.oz();
            int u = dx * l.ax() + dz * l.az();
            int d = dx * l.nx() + dz * l.nz();
            int h = y - l.oy();
            if (h < -3 || u < -9 || u > 14 || d < -7 || d > 12) {
                return Mat.STONE;
            }
            if (d >= 1) {
                return h <= 0 ? Mat.STONE : Mat.AIR;
            }
            if (h == -3) {
                return Mat.STONE;
            }
            return h <= 0 ? Mat.LAVA : Mat.AIR;
        }

        Mat cell(int u, int h, int d) {
            P p = l.cell(u, h, d);
            return at(p.x(), p.y(), p.z());
        }

        void set(P p, Mat m) {
            over.put(p, m);
            waterSources.remove(p);
        }

        WaterFlow.World flow() {
            return (x, y, z) -> switch (at(x, y, z)) {
                case AIR, WATER_FLOW -> WaterFlow.Kind.AIR;
                case LAVA -> WaterFlow.Kind.LAVA;
                case WATER -> WaterFlow.Kind.WATER;
                default -> WaterFlow.Kind.SOLID;
            };
        }

        // run the water for a few generations, cement the lava it touched, and say which cells it wet
        Set<P> wash(int gens) {
            int[][] src = new int[waterSources.size()][];
            int i = 0;
            for (P s : waterSources) {
                src[i++] = new int[]{s.x(), s.y(), s.z()};
            }
            WaterFlow.Result r = WaterFlow.run(flow(), src, gens);
            for (long k : r.obsidian) {
                over.put(new P(WaterFlow.x(k), WaterFlow.y(k), WaterFlow.z(k)), Mat.OBSIDIAN);
            }
            Set<P> wet = new HashSet<>();
            for (long k : r.water.keySet()) {
                wet.add(new P(WaterFlow.x(k), WaterFlow.y(k), WaterFlow.z(k)));
            }
            return wet;
        }

        Inv inv() {
            return new Inv(lava, water, empty);
        }
    }

    // plays the steps the way the real task does, minus the walking. returns the step count
    static int play(World w, int maxSteps) {
        P skin = null;
        List<P> candidates = PortalMold.skinSources(w.l, w);
        if (!candidates.isEmpty()) {
            skin = candidates.get(0);
        }
        long skinAge = -1;
        int steps = 0;
        int waits = 0;
        while (steps++ < maxSteps) {
            Step s = PortalMold.next(w.l, w, w.inv(), skin, skinAge);
            w.log.add(s.toString());
            if (skinAge >= 0) {
                skinAge += 30;
            }
            switch (s.kind()) {
                case DONE -> {
                    return steps;
                }
                case STUCK -> fail("stuck: " + s + "\n" + String.join("\n", w.log));
                case WAIT -> {
                    if (++waits > 3) {
                        fail("waiting forever on " + s + "\n" + String.join("\n", w.log));
                    }
                }
                case SKIN_PLACE -> {
                    assertEquals(Mat.AIR, w.at(s.cell().x(), s.cell().y(), s.cell().z()));
                    assertTrue(w.water);
                    w.water = false;
                    w.empty++;
                    w.waterSources.add(s.cell());
                    w.wash(Integer.MAX_VALUE);
                    skinAge = 0;
                }
                case SKIN_WAIT -> {
                }
                case SKIN_SCOOP, WATER_SCOOP -> {
                    assertTrue("nothing to scoop with", w.empty >= 1);
                    assertEquals(Mat.WATER, w.at(s.cell().x(), s.cell().y(), s.cell().z()));
                    w.waterSources.remove(s.cell());
                    w.water = true;
                    w.empty--;
                    skinAge = -1;
                }
                case GUIDE_PLACE -> {
                    Mat m = w.at(s.cell().x(), s.cell().y(), s.cell().z());
                    assertTrue("placing a guide into " + m + " at " + s, m == Mat.AIR || m == Mat.WATER_FLOW);
                    w.set(s.cell(), Mat.STONE);
                }
                case GUIDE_BREAK -> w.set(s.cell(), Mat.AIR);
                case LAVA_SCOOP -> {
                    assertTrue(w.empty >= 1);
                    assertEquals(Mat.LAVA, w.at(s.cell().x(), s.cell().y(), s.cell().z()));
                    assertEquals(Mat.AIR, w.at(s.cell().x(), s.cell().y() + 1, s.cell().z()));
                    w.set(s.cell(), Mat.AIR);
                    w.lava = true;
                    w.empty--;
                }
                case LAVA_PLACE -> {
                    assertTrue(w.lava);
                    assertTrue("water has to be in hand before lava goes out", w.water || !w.waterSources.isEmpty());
                    assertEquals(Mat.AIR, w.at(s.cell().x(), s.cell().y(), s.cell().z()));
                    checkClick(w, s);
                    w.lava = false;
                    w.empty++;
                    // beside water already? then it is obsidian before it ever flows
                    boolean wet = false;
                    int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};
                    for (int[] a : around) {
                        if (w.at(s.cell().x() + a[0], s.cell().y() + a[1], s.cell().z() + a[2]) == Mat.WATER) {
                            wet = true;
                        }
                    }
                    w.set(s.cell(), wet ? Mat.OBSIDIAN : Mat.LAVA);
                }
                case WATER_PLACE -> {
                    assertTrue(w.water);
                    Mat m = w.at(s.cell().x(), s.cell().y(), s.cell().z());
                    assertTrue(m == Mat.AIR || m == Mat.WATER_FLOW);
                    checkClick(w, s);
                    w.water = false;
                    w.empty++;
                    w.waterSources.add(s.cell());
                    Set<P> wet = w.wash(2);
                    // two flow steps is about what a bot that scoops straight away allows
                    for (int[] f : PortalMold.FRAME) {
                        P fp = w.l.cell(f);
                        if (wet.contains(fp) && !fp.equals(s.cell())) {
                            Mat now = w.at(fp.x(), fp.y(), fp.z());
                            assertTrue("water leaked into frame cell " + fp + " (" + now + ") after " + s, now == Mat.OBSIDIAN);
                        }
                    }
                }
                case LAVA_UNDO -> fail("undo on the happy path: " + s);
                case LIGHT -> {
                    for (int[] c : PortalMold.INTERIOR) {
                        w.set(w.l.cell(c), Mat.PORTAL);
                    }
                }
                default -> fail("unhandled " + s);
            }
        }
        fail("ran out of steps\n" + String.join("\n", w.log));
        return steps;
    }

    // the block we click must be solid and the cell we fill must be right next to the face we click
    static void checkClick(World w, Step s) {
        assertNotNull(s.support());
        assertTrue("clicking air at " + s, w.at(s.support().x(), s.support().y(), s.support().z()).solid());
        assertEquals(s.cell().x(), s.support().x() + s.fx());
        assertEquals(s.cell().y(), s.support().y() + s.fy());
        assertEquals(s.cell().z(), s.support().z() + s.fz());
    }

    static Layout straight() {
        return new Layout(0, 64, 0, 1, 0, 0, 1);
    }

    static void assertBuilt(World w) {
        for (int[] f : PortalMold.FRAME) {
            assertEquals("frame " + f[0] + "," + f[1], Mat.OBSIDIAN, w.cell(f[0], f[1], f[2]));
        }
        for (int[] c : PortalMold.INTERIOR) {
            assertEquals("opening " + c[0] + "," + c[1], Mat.PORTAL, w.cell(c[0], c[1], c[2]));
        }
    }

    @Test
    public void buildsThePortalOnAStraightShore() {
        World w = new World(straight());
        int steps = play(w, 120);
        assertBuilt(w);
        assertTrue("took " + steps + " steps", steps < 80);
    }

    @Test
    public void buildsItTurnedAroundEveryWay() {
        Layout[] layouts = {
                new Layout(10, 70, -5, 0, 1, -1, 0),
                new Layout(10, 70, -5, 0, -1, 1, 0),
                new Layout(-3, 40, 8, -1, 0, 0, -1),
                new Layout(-3, 40, 8, 0, 1, 1, 0)
        };
        for (Layout l : layouts) {
            World w = new World(l);
            play(w, 120);
            assertBuilt(w);
        }
    }

    @Test
    public void shoreWaterOnlyConvertsAFewCells() {
        World w = new World(straight());
        List<P> skin = PortalMold.skinSources(w.l, w);
        assertFalse(skin.isEmpty());
        Set<P> cemented = PortalMold.skinObsidian(w.l, w, skin.get(0));
        P b1 = w.l.cell(1, 0, 0);
        P b2 = w.l.cell(2, 0, 0);
        assertTrue(cemented.contains(b1));
        assertTrue(cemented.contains(b2));
        // the point of aiming it is not eating the pool
        assertTrue("converted " + cemented.size(), cemented.size() <= 8);
        assertTrue(PortalMold.scoopCells(w.l, w, cemented).size() >= PortalMold.SCOOP_NEEDED);
    }

    @Test
    public void everyCastClicksARealFaceBesideItsCell() {
        for (Cast c : PortalMold.CASTS) {
            assertAdjacent(c.lavaSupport, c.t, c.name + " lava");
            assertAdjacent(c.waterSupport, c.w, c.name + " water");
        }
    }

    private static void assertAdjacent(int[] a, int[] b, String what) {
        int dist = Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]);
        assertEquals(what, 1, dist);
    }

    @Test
    public void theMoldNeverSitsOnTheOpeningOrTheFrame() {
        Set<String> frame = new HashSet<>();
        for (int[] f : PortalMold.FRAME) {
            frame.add(f[0] + "," + f[1] + "," + f[2]);
        }
        for (int[] c : PortalMold.INTERIOR) {
            assertFalse(frame.contains(c[0] + "," + c[1] + "," + c[2]));
        }
        // the corners are not frame cells, the water lives there
        assertFalse(frame.contains("0,4,0"));
        assertFalse(frame.contains("3,4,0"));
        assertFalse(frame.contains("0,0,0"));
        assertEquals(10, PortalMold.FRAME.length);
        assertEquals(6, PortalMold.INTERIOR.length);
        assertEquals(8, PortalMold.SLAB.length);
        // water cells and pads are never frame cells either
        for (Cast c : PortalMold.CASTS) {
            // a side cast waters the cell above it (the next frame cell up, or the corner for the third), the top row uses the corners
            boolean corner = c.w[1] == 4 && (c.w[0] == 0 || c.w[0] == 3);
            assertTrue(c.name, frame.contains(c.w[0] + "," + c.w[1] + "," + c.w[2]) || corner);
            assertFalse(c.name, c.top && !corner);
            if (c.pad != null) {
                assertFalse(frame.contains(c.pad[0] + "," + c.pad[1] + "," + c.pad[2]));
                assertEquals("pad is on the shore row", 1, c.pad[2]);
            }
        }
    }

    @Test
    public void findsRowsOnlyWhereTheyFit() {
        World w = new World(straight());
        List<P> seeds = new ArrayList<>();
        for (int u = -3; u <= 6; u++) {
            seeds.add(w.l.cell(u, 0, 0));
        }
        List<Layout> found = PortalMold.find(w, seeds, 0, 5, 10);
        assertFalse(found.isEmpty());
        for (Layout l : found) {
            assertTrue(PortalMold.fits(l, w));
        }
        // a row that is only 3 wide is no row
        World narrow = new World(straight());
        for (int u = 0; u <= 3; u++) {
            narrow.set(narrow.l.cell(u, 0, 0), u == 3 ? Mat.STONE : Mat.LAVA);
            narrow.set(narrow.l.cell(u, 0, -1), Mat.STONE);
            narrow.set(narrow.l.cell(u, 0, -2), Mat.STONE);
        }
        assertFalse(PortalMold.fits(narrow.l, narrow));
    }

    @Test
    public void refusesWhenWaterIsAlreadyAround() {
        World w = new World(straight());
        w.set(w.l.cell(-6, 1, 4), Mat.WATER_FLOW);
        assertFalse(PortalMold.fits(w.l, w));
    }

    @Test
    public void refusesAShallowPoolWithNoLavaToScoop() {
        World w = new World(straight());
        // only the span itself is lava, everything behind it is stone: nothing to fill the buckets from
        for (int u = -9; u <= 14; u++) {
            for (int d = -1; d >= -7; d--) {
                w.set(w.l.cell(u, 0, d), Mat.STONE);
            }
        }
        assertFalse(PortalMold.fits(w.l, w));
    }

    @Test
    public void sideCastsStandTwoRowsBackAndNotNextToTheirCells() {
        for (Cast c : PortalMold.CASTS) {
            if (c.top) {
                continue;
            }
            assertTrue(c.name + " stands at d=" + c.stand[2], c.stand[2] >= 2);
            assertTrue(c.name + " is beside its lava", manhattan(c.stand, c.t) > 1);
            assertTrue(c.name + " is beside its water", manhattan(c.stand, c.w) > 1);
        }
    }

    // the clicks aim at the face centre, from the stand's eye that has to be inside survival reach
    @Test
    public void sideCastClicksAreInReachFromTheStand() {
        for (Cast c : PortalMold.CASTS) {
            if (c.top) {
                continue;
            }
            double[] eye = {c.stand[0] + 0.5, c.stand[1] + 1.62, c.stand[2] + 0.5};
            assertTrue(c.name + " lava", reach(eye, c.lavaSupport, c.t) <= 4.5);
            assertTrue(c.name + " water", reach(eye, c.waterSupport, c.w) <= 4.5);
        }
    }

    private static double reach(double[] eye, int[] support, int[] into) {
        double[] p = new double[3];
        for (int i = 0; i < 3; i++) {
            p[i] = support[i] + 0.5 + (into[i] - support[i]) * 0.5;
        }
        return Math.sqrt(Math.pow(p[0] - eye[0], 2) + Math.pow(p[1] - eye[1], 2) + Math.pow(p[2] - eye[2], 2));
    }

    private static int manhattan(int[] a, int[] b) {
        return Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]);
    }

    @Test
    public void refusesARowWithNowhereToStandTwoBack() {
        // no floor under either side stand, or something at feet, head, over the head, or high on the shore row where
        // the upper clicks go through
        int[][] cases = {{0, 0, 2}, {3, 0, 2}, {0, 1, 2}, {3, 1, 2}, {0, 2, 2}, {3, 2, 2}, {0, 3, 2}, {3, 3, 2},
                {0, 3, 1}, {3, 3, 1}, {0, 4, 1}, {3, 4, 1}};
        for (int[] k : cases) {
            World w = new World(straight());
            w.set(w.l.cell(k[0], k[1], k[2]), k[1] == 0 ? Mat.AIR : Mat.STONE);
            assertFalse("u=" + k[0] + " h=" + k[1] + " d=" + k[2], PortalMold.fits(w.l, w));
        }
        assertTrue(PortalMold.fits(straight(), new World(straight())));
    }

    // every cell between the stand's eye and the face centre we aim at (LookHelper aims at exactly that point) is one
    // fits() made sure is air, or the cast's own t and w (empty, or lava the pour ray looks through). anywhere baritone
    // can park us in the stand cell without poking out of it
    @Test
    public void sideCastClicksSeeTheirFaceFromAnywhereOnTheStand() {
        for (Cast c : PortalMold.CASTS) {
            if (c.top) {
                continue;
            }
            Set<String> clear = new HashSet<>();
            for (int h = 1; h <= 3; h++) {
                clear.add(c.stand[0] + "," + h + ",2");
            }
            for (int h = 1; h <= 4; h++) {
                clear.add(c.stand[0] + "," + h + ",1");
            }
            clear.add(c.t[0] + "," + c.t[1] + "," + c.t[2]);
            clear.add(c.w[0] + "," + c.w[1] + "," + c.w[2]);
            for (double ou = 0.3; ou <= 0.71; ou += 0.2) {
                for (double od = 0.3; od <= 0.71; od += 0.2) {
                    double[] eye = {c.stand[0] + ou, c.stand[1] + 1.62, c.stand[2] + od};
                    assertRayClear(c.name + " lava", eye, c.lavaSupport, c.t, clear);
                    assertRayClear(c.name + " water", eye, c.waterSupport, c.w, clear);
                }
            }
        }
    }

    private static void assertRayClear(String what, double[] eye, int[] support, int[] into, Set<String> clear) {
        double[] face = new double[3];
        for (int i = 0; i < 3; i++) {
            face[i] = support[i] + 0.5 + (into[i] - support[i]) * 0.5;
        }
        // f stays under 1, the face itself is the block we click
        for (int s = 0; s < 1000; s++) {
            double f = s / 1000.0;
            String cell = (int) Math.floor(eye[0] + (face[0] - eye[0]) * f) + ","
                    + (int) Math.floor(eye[1] + (face[1] - eye[1]) * f) + ","
                    + (int) Math.floor(eye[2] + (face[2] - eye[2]) * f);
            assertTrue(what + " goes through " + cell, clear.contains(cell));
        }
    }

    private static PortalMold.Box bodyAt(Layout l, double u, double d) {
        // a standing player on the shore row, centre at (u, d) in layout coordinates. only straight() is used here
        P o = l.cell(0, 1, 0);
        return new PortalMold.Box(o.x() + u - 0.3, o.y(), o.z() + d - 0.3, o.x() + u + 0.3, o.y() + 1.8, o.z() + d + 0.3);
    }

    @Test
    public void aBodyOnTheStandIsNeverInTheCastCells() {
        Layout l = straight();
        for (Cast c : PortalMold.CASTS) {
            if (c.top) {
                continue;
            }
            // anywhere baritone could park us in the stand cell
            for (double du = 0; du < 1; du += 0.1) {
                for (double dd = 0; dd < 1; dd += 0.1) {
                    assertFalse(c.name, PortalMold.inTheWay(l, bodyAt(l, c.stand[0] + du, c.stand[2] + dd), c));
                }
            }
        }
        // one row back is where it used to stand, and a centre short of 1.3 is in t
        Cast left1 = PortalMold.CASTS.get(0);
        assertTrue(PortalMold.inTheWay(l, bodyAt(l, 0.5, 1.2), left1));
    }

    @Test
    public void lavaCountsOnlyWhenItIsInOurBox() {
        World w = new World(straight());
        Layout l = w.l;
        PortalMold.Box onStand = bodyAt(l, 0.5, 2.5);
        // the pool under the shore and lava in t are not on us
        w.set(l.cell(0, 1, 0), Mat.LAVA);
        assertFalse(PortalMold.lavaOnUs(w, onStand));
        // right beside a flow in the next cell is still not in it, that's how the scoop stands at the pool
        w.set(l.cell(0, 1, 1), Mat.LAVA_FLOW);
        assertFalse(PortalMold.lavaOnUs(w, bodyAt(l, 0.5, 2.35)));
        // poking into it is
        assertTrue(PortalMold.lavaOnUs(w, bodyAt(l, 0.5, 2.2)));
        // and so is the flow reaching our own cell
        w.set(l.cell(0, 2, 2), Mat.LAVA_FLOW);
        assertTrue(PortalMold.lavaOnUs(w, onStand));
    }

    @Test
    public void retreatsUpTheColumnOrPastTheFlow() {
        World w = new World(straight());
        Layout l = w.l;
        w.set(l.cell(0, 1, 2), Mat.LAVA_FLOW);
        w.set(l.cell(0, 1, 1), Mat.LAVA_FLOW);
        P spot = PortalMold.retreatSpot(l, w, l.cell(0, 1, 2));
        assertNotNull(spot);
        int[] at = l.local(spot);
        assertTrue("retreated to " + at[0] + "," + at[1] + "," + at[2], at[2] >= 4 || at[0] <= -2 || at[0] >= 2);
        assertTrue(PortalMold.safeToStand(w, spot));
        // our column is a wall past d=3: sideways it is, and never into the cell next to the flow
        for (int d = 4; d <= 8; d++) {
            w.set(l.cell(0, 1, d), Mat.STONE);
            w.set(l.cell(0, 2, d), Mat.STONE);
            w.set(l.cell(0, 3, d), Mat.STONE);
        }
        at = l.local(PortalMold.retreatSpot(l, w, l.cell(0, 1, 2)));
        assertEquals(2, at[2]);
        assertTrue(Math.abs(at[0]) >= 2);
    }

    @Test
    public void noRetreatWhenEverythingAroundIsLava() {
        World w = new World(straight());
        Layout l = w.l;
        for (int u = -9; u <= 14; u++) {
            for (int d = 1; d <= 12; d++) {
                w.set(l.cell(u, 1, d), Mat.LAVA);
            }
        }
        assertEquals(null, PortalMold.retreatSpot(l, w, l.cell(0, 1, 2)));
    }

    @Test
    public void stepsBackOffAStandOntoSomethingSafe() {
        World w = new World(straight());
        Cast left1 = PortalMold.CASTS.get(0);
        P back = PortalMold.stepBackSpot(w.l, w, left1.stand);
        assertNotNull(back);
        assertEquals(w.l.cell(0, 1, 3), back);
    }

    @Test
    public void localUndoesCell() {
        Layout[] layouts = {straight(), new Layout(10, 70, -5, 0, 1, -1, 0), new Layout(-3, 40, 8, -1, 0, 0, -1)};
        for (Layout l : layouts) {
            int[] back = l.local(l.cell(-2, 3, 5));
            assertEquals(-2, back[0]);
            assertEquals(3, back[1]);
            assertEquals(5, back[2]);
        }
    }

    @Test
    public void picksTheBaseBackUpWhereItLeftOffAfterAnInterruption() {
        // re-derive from a half built world: the left column done, nothing in the bags, water source sitting out
        World half = new World(straight());
        for (int[] b : PortalMold.BASE) {
            half.set(half.l.cell(b), Mat.OBSIDIAN);
        }
        for (int[] g : PortalMold.SLAB) {
            half.set(half.l.cell(g), Mat.STONE);
        }
        half.set(half.l.cell(0, 1, 0), Mat.OBSIDIAN);
        Step s = PortalMold.next(half.l, half, half.inv(), null, -1);
        assertEquals(Kind.LAVA_SCOOP, s.kind());
        half.lava = true;
        half.empty = 0;
        s = PortalMold.next(half.l, half, half.inv(), null, -1);
        // left1 is done, so the next cast is left2: lava into (0,2)
        assertEquals(Kind.LAVA_PLACE, s.kind());
        assertEquals(half.l.cell(0, 2, 0), s.cell());
    }
}
