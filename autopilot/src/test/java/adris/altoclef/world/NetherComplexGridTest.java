package adris.altoclef.world;

import adris.altoclef.world.NetherComplexGrid.Cell;
import adris.altoclef.world.NetherComplexGrid.Point;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NetherComplexGridTest {
    private static final int CELL_BLOCKS = 27 * 16;

    private static int chunkOf(int block) {
        return Math.floorDiv(block, 16);
    }

    @Test
    public void cellOfMatchesFloorDivisionEverywhere() {
        Random rng = new Random(7);
        for (int i = 0; i < 20000; i++) {
            int x = rng.nextInt(2_000_000) - 1_000_000;
            int z = rng.nextInt(2_000_000) - 1_000_000;
            Cell c = NetherComplexGrid.cellOf(x, z);
            assertEquals(Math.floorDiv(x, CELL_BLOCKS), c.cx());
            assertEquals(Math.floorDiv(z, CELL_BLOCKS), c.cz());
        }
    }

    @Test
    public void cellEdgesAreOnMultiplesOfFourThirtyTwo() {
        assertEquals(new Cell(0, 0), NetherComplexGrid.cellOf(0, 0));
        assertEquals(new Cell(0, 0), NetherComplexGrid.cellOf(431, 431));
        assertEquals(new Cell(1, 1), NetherComplexGrid.cellOf(432, 432));
        assertEquals(new Cell(-1, -1), NetherComplexGrid.cellOf(-1, -1));
        assertEquals(new Cell(-1, -1), NetherComplexGrid.cellOf(-432, -432));
        assertEquals(new Cell(-2, -2), NetherComplexGrid.cellOf(-433, -433));
        assertEquals(new Cell(-1, 0), NetherComplexGrid.cellOf(-1, 0));
        assertEquals(new Cell(0, -1), NetherComplexGrid.cellOf(15, -16));
    }

    @Test
    public void cellKeyRoundTripsIncludingNegatives() {
        for (Cell c : new Cell[]{new Cell(0, 0), new Cell(-3, 7), new Cell(12, -400), new Cell(-1, -1)}) {
            assertEquals(c, Cell.parse(c.key()));
        }
        assertEquals("-3,7", new Cell(-3, 7).key());
        assertEquals(new Cell(2, -5), Cell.parse(" 2 , -5 "));
    }

    @Test
    public void cellCentreIsTheMiddleOfTheCandidateSquare() {
        // candidate chunks are 0..22 so the middle is chunk 11, whose block centre is 11*16+8 = 184
        assertEquals(new Point(184, 184), NetherComplexGrid.cellCentre(new Cell(0, 0)));
        assertEquals(new Point(184 + 432, 184 - 864), NetherComplexGrid.cellCentre(new Cell(1, -2)));
        assertEquals(new Point(184 - 432, 184 - 432), NetherComplexGrid.cellCentre(new Cell(-1, -1)));
        for (int cx = -4; cx <= 4; cx++) {
            for (int cz = -4; cz <= 4; cz++) {
                Cell c = new Cell(cx, cz);
                Point p = NetherComplexGrid.cellCentre(c);
                assertEquals(c, NetherComplexGrid.cellOf(p.x(), p.z()));
            }
        }
    }

    // ---- sweepWaypoints ----

    @Test
    public void defaultSweepIsThreeByThreeNineChunksApart() {
        List<Point> pts = NetherComplexGrid.sweepWaypoints(new Cell(0, 0), 9);
        assertEquals(9, pts.size());
        Set<Integer> xs = new HashSet<>();
        Set<Integer> zs = new HashSet<>();
        for (Point p : pts) {
            xs.add(p.x());
            zs.add(p.z());
            // chunk centres
            assertEquals(8, Math.floorMod(p.x(), 16));
            assertEquals(8, Math.floorMod(p.z(), 16));
        }
        // chunks 2, 11, 20 of the cell, 9 apart and centred on the candidate square
        assertEquals(Set.of(2 * 16 + 8, 11 * 16 + 8, 20 * 16 + 8), xs);
        assertEquals(xs, zs);
        assertEquals(new Point(40, 40), pts.get(0));
    }

    @Test
    public void sweepIsASerpentineWhereNeighboursAreOneStepApart() {
        for (int spacing = 1; spacing <= 27; spacing++) {
            List<Point> pts = NetherComplexGrid.sweepWaypoints(new Cell(0, 0), spacing);
            for (int i = 1; i < pts.size(); i++) {
                Point a = pts.get(i - 1);
                Point b = pts.get(i);
                boolean sameX = a.x() == b.x();
                boolean sameZ = a.z() == b.z();
                assertTrue("spacing " + spacing + " step " + i + " moves along exactly one axis", sameX ^ sameZ);
                int step = Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z());
                assertTrue("spacing " + spacing + " step " + i + " is " + step, step > 0 && step <= spacing * 16 + 16);
            }
        }
    }

    @Test
    public void sweepVisitsEveryGridPointOnceAndStaysInsideTheCandidateSquare() {
        for (int spacing = 1; spacing <= 30; spacing++) {
            List<Point> pts = NetherComplexGrid.sweepWaypoints(new Cell(0, 0), spacing);
            assertEquals("no duplicates at spacing " + spacing, pts.size(), new HashSet<>(pts).size());
            int n = (int) Math.round(Math.sqrt(pts.size()));
            assertEquals(pts.size(), n * n);
            for (Point p : pts) {
                int cx = chunkOf(p.x());
                int cz = chunkOf(p.z());
                assertTrue(cx >= 0 && cx <= 22 && cz >= 0 && cz <= 22);
            }
        }
    }

    @Test
    public void sweepReachesEveryCandidateChunkWithinHalfAnSpacing() {
        for (int spacing = 2; spacing <= 14; spacing++) {
            List<Point> pts = NetherComplexGrid.sweepWaypoints(new Cell(0, 0), spacing);
            int reach = (spacing + 1) / 2;
            for (int ox = 0; ox <= 22; ox++) {
                for (int oz = 0; oz <= 22; oz++) {
                    boolean covered = false;
                    for (Point p : pts) {
                        if (Math.abs(chunkOf(p.x()) - ox) <= reach && Math.abs(chunkOf(p.z()) - oz) <= reach) {
                            covered = true;
                            break;
                        }
                    }
                    assertTrue("spacing " + spacing + " misses chunk " + ox + "," + oz, covered);
                }
            }
        }
    }

    @Test
    public void tinySpacingStillCoversAndHugeSpacingIsOneCentrePoint() {
        List<Point> one = NetherComplexGrid.sweepWaypoints(new Cell(0, 0), 100);
        assertEquals(1, one.size());
        assertEquals(new Point(184, 184), one.get(0));
        assertEquals(one, NetherComplexGrid.sweepWaypoints(new Cell(0, 0), 27));
        assertEquals(NetherComplexGrid.sweepWaypoints(new Cell(0, 0), 1), NetherComplexGrid.sweepWaypoints(new Cell(0, 0), 0));
        assertEquals(NetherComplexGrid.sweepWaypoints(new Cell(0, 0), 1), NetherComplexGrid.sweepWaypoints(new Cell(0, 0), -5));
    }

    @Test
    public void sweepOfANegativeCellIsTheSameShapeShifted() {
        List<Point> base = NetherComplexGrid.sweepWaypoints(new Cell(0, 0), 9);
        List<Point> shifted = NetherComplexGrid.sweepWaypoints(new Cell(-3, 5), 9);
        assertEquals(base.size(), shifted.size());
        for (int i = 0; i < base.size(); i++) {
            assertEquals(base.get(i).x() - 3 * CELL_BLOCKS, shifted.get(i).x());
            assertEquals(base.get(i).z() + 5 * CELL_BLOCKS, shifted.get(i).z());
            assertEquals(new Cell(-3, 5), NetherComplexGrid.cellOf(shifted.get(i).x(), shifted.get(i).z()));
        }
    }

    @Test
    public void sweepStartsAtTheCornerNearestThePlayer() {
        Cell cell = new Cell(0, 0);
        List<Point> base = NetherComplexGrid.sweepWaypoints(cell, 9);
        int lo = 40;
        int hi = 328;
        int[][] corners = {{lo, lo}, {hi, lo}, {lo, hi}, {hi, hi}};
        int[][] players = {{-300, -300}, {900, -200}, {-100, 800}, {1000, 1000}};
        for (int i = 0; i < 4; i++) {
            List<Point> pts = NetherComplexGrid.sweepWaypoints(cell, 9, players[i][0], players[i][1]);
            assertEquals(new Point(corners[i][0], corners[i][1]), pts.get(0));
            // same points, different order
            assertEquals(new HashSet<>(base), new HashSet<>(pts));
            for (int k = 1; k < pts.size(); k++) {
                boolean sameX = pts.get(k - 1).x() == pts.get(k).x();
                boolean sameZ = pts.get(k - 1).z() == pts.get(k).z();
                assertTrue(sameX ^ sameZ);
            }
        }
    }

    @Test
    public void sweepWithAPlayerInTheMiddleKeepsTheDefaultOrder() {
        List<Point> base = NetherComplexGrid.sweepWaypoints(new Cell(0, 0), 9);
        assertEquals(base, NetherComplexGrid.sweepWaypoints(new Cell(0, 0), 9, 184, 184));
        assertEquals(base, NetherComplexGrid.sweepWaypoints(new Cell(0, 0), 9, -999999, -999999));
    }

    // ---- nextCell ----

    @Test
    public void nextCellWithNothingVisitedIsTheHomeCellWhenCloser() {
        assertEquals(Optional.of(new Cell(0, 0)), NetherComplexGrid.nextCell(10, 10, Set.of()));
        assertEquals(Optional.of(new Cell(-1, -1)), NetherComplexGrid.nextCell(-250, -250, Set.of()));
    }

    @Test
    public void nextCellCanBeTheNeighbourWhenThePlayerStandsOnItsSide() {
        // far corner of cell (0,0): cell (1,1)'s candidate middle is nearer than (0,0)'s
        assertEquals(Optional.of(new Cell(1, 1)), NetherComplexGrid.nextCell(430, 430, Set.of()));
    }

    @Test
    public void nextCellSkipsVisitedAndTiesGoToTheSpiralOrder() {
        // standing on the home candidate middle: the four edge neighbours tie at 432. the spiral meets (0,-1) first
        Set<String> visited = Set.of(new Cell(0, 0).key());
        assertEquals(Optional.of(new Cell(0, -1)), NetherComplexGrid.nextCell(184, 184, visited));
        Set<String> more = Set.of(new Cell(0, 0).key(), new Cell(0, -1).key());
        assertEquals(Optional.of(new Cell(1, 0)), NetherComplexGrid.nextCell(184, 184, more));
    }

    @Test
    public void nextCellNeverReturnsAVisitedKeyAndIsAlwaysTheNearest() {
        Random rng = new Random(99);
        for (int run = 0; run < 400; run++) {
            Set<String> visited = new HashSet<>();
            int count = rng.nextInt(60);
            for (int i = 0; i < count; i++) {
                visited.add(new Cell(rng.nextInt(9) - 4, rng.nextInt(9) - 4).key());
            }
            int px = rng.nextInt(4000) - 2000;
            int pz = rng.nextInt(4000) - 2000;
            Cell got = NetherComplexGrid.nextCell(px, pz, visited).orElseThrow();
            assertFalse("handed out a visited cell", visited.contains(got.key()));
            // brute force over a window big enough to hold the answer
            double best = Double.MAX_VALUE;
            for (int cx = -30; cx <= 30; cx++) {
                for (int cz = -30; cz <= 30; cz++) {
                    Cell c = new Cell(cx, cz);
                    if (visited.contains(c.key())) {
                        continue;
                    }
                    Point m = NetherComplexGrid.cellCentre(c);
                    best = Math.min(best, Math.hypot(m.x() - px, m.z() - pz));
                }
            }
            Point gm = NetherComplexGrid.cellCentre(got);
            assertEquals("run " + run, best, Math.hypot(gm.x() - px, gm.z() - pz), 1e-9);
        }
    }

    @Test
    public void nextCellWalksOutwardsWhenEverythingNearbyIsVisited() {
        Set<String> visited = new HashSet<>();
        for (int cx = -5; cx <= 5; cx++) {
            for (int cz = -5; cz <= 5; cz++) {
                visited.add(new Cell(cx, cz).key());
            }
        }
        Cell got = NetherComplexGrid.nextCell(184, 184, visited).orElseThrow();
        assertEquals(6, Math.max(Math.abs(got.cx()), Math.abs(got.cz())));
        assertFalse(visited.contains(got.key()));
    }

    @Test
    public void nextCellCoversTheWholeWorldGivenTime() {
        // keep asking and marking visited: 400 cells, no repeats, each one a real cell, always an answer
        Set<String> visited = new HashSet<>();
        int px = 0;
        int pz = 0;
        for (int i = 0; i < 400; i++) {
            Cell c = NetherComplexGrid.nextCell(px, pz, visited).orElseThrow();
            assertTrue(visited.add(c.key()));
            Point m = NetherComplexGrid.cellCentre(c);
            px = m.x();
            pz = m.z();
        }
        assertEquals(400, visited.size());
    }

    @Test
    public void nextCellWorksFarFromTheOriginAndWithNegativeCoordinates() {
        Cell c = NetherComplexGrid.nextCell(-29_000_000, 29_000_000, Set.of()).orElseThrow();
        assertEquals(NetherComplexGrid.cellOf(-29_000_000, 29_000_000), c);
        Set<String> visited = new HashSet<>(Set.of(c.key()));
        Cell d = NetherComplexGrid.nextCell(-29_000_000, 29_000_000, visited).orElseThrow();
        assertFalse(visited.contains(d.key()));
    }

    // ---- spacing 14 and the deferred pick

    @Test
    public void spacingFourteenIsATwoByTwoAtChunksFourAndEighteen() {
        List<Point> pts = NetherComplexGrid.sweepWaypoints(new Cell(0, 0), 14);
        assertEquals(4, pts.size());
        assertEquals(List.of(new Point(72, 72), new Point(72, 296), new Point(296, 296), new Point(296, 72)), pts);
    }

    @Test
    public void awayFromStartsAtTheCornerFarthestFromThePocketAndSweepsTheWholeGrid() {
        Cell c = new Cell(0, 0);
        List<Point> away = NetherComplexGrid.sweepWaypointsAwayFrom(c, 14, 80, 80);
        assertEquals(new Point(296, 296), away.get(0));
        assertEquals(new HashSet<>(NetherComplexGrid.sweepWaypoints(c, 14)), new HashSet<>(away));
        assertEquals(new Point(72, 72), NetherComplexGrid.sweepWaypointsAwayFrom(c, 14, 290, 290).get(0));
        // consecutive legs stay axis aligned, it is still a serpentine
        for (int i = 1; i < away.size(); i++) {
            int dx = Math.abs(away.get(i).x() - away.get(i - 1).x());
            int dz = Math.abs(away.get(i).z() - away.get(i - 1).z());
            assertTrue(dx == 0 || dz == 0);
        }
    }

    private static Set<String> cellsAround(Cell home, int rings) {
        Set<String> out = new HashSet<>();
        for (int dx = -rings; dx <= rings; dx++) {
            for (int dz = -rings; dz <= rings; dz++) {
                out.add(new Cell(home.cx() + dx, home.cz() + dz).key());
            }
        }
        return out;
    }

    @Test
    public void deferredCellComesUpOnlyWhenNothingFreshIsNearThePortal() {
        Point portal = new Point(100, 100);
        Set<String> deferred = Set.of("1,0");
        Set<String> visited = cellsAround(new Cell(0, 0), NetherComplexGrid.FRESH_RINGS);
        visited.removeAll(deferred);
        // the 5x5 around the portal is all spoken for, fresh cells only exist further out: the deferred one wins
        assertEquals("1,0", NetherComplexGrid.nextCell(100, 100, visited, deferred, portal).orElseThrow().key());
        // one fresh cell left inside the rings: fresh first
        visited.remove("2,2");
        Cell pick = NetherComplexGrid.nextCell(100, 100, visited, deferred, portal).orElseThrow();
        assertFalse(pick.key().equals("1,0"));
        assertFalse(visited.contains(pick.key()));
        // portal unknown: fresh always wins
        visited.add("2,2");
        assertFalse(NetherComplexGrid.nextCell(100, 100, visited, deferred, null).orElseThrow().key().equals("1,0"));
    }

    @Test
    public void deferredCellsAreNeverTheFreshPickAndEmptyDeferredIsThePlainPick() {
        Set<String> visited = new HashSet<>();
        Cell pick = NetherComplexGrid.nextCell(100, 100, visited, Set.of("0,0"), new Point(100, 100)).orElseThrow();
        assertFalse(pick.key().equals("0,0"));
        assertEquals(NetherComplexGrid.nextCell(100, 100, visited),
                NetherComplexGrid.nextCell(100, 100, visited, Set.of(), new Point(100, 100)));
        // nearest deferred when several, by distance from the player
        Set<String> all = cellsAround(new Cell(0, 0), 3);
        all.removeAll(Set.of("3,3", "1,0"));
        assertEquals("1,0", NetherComplexGrid.nextCell(100, 100, all, Set.of("3,3", "1,0"), new Point(100, 100)).orElseThrow().key());
    }
}
