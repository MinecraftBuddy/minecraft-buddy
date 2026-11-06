package adris.altoclef.tasks.speedrun.gamer.portal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.Mat;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlan.P;
import adris.altoclef.tasks.speedrun.gamer.portal.LPortalPlanTest.World;
import adris.altoclef.tasks.speedrun.gamer.portal.LakeSurvey.Lake;
import java.util.function.ToIntFunction;
import org.junit.Test;

public class LakeSurveyTest {
    private static final P SEED = new P(20, 63, 5);
    private static final ToIntFunction<P> OPEN_SKY = p -> 0;

    private static Lake run(World w) {
        LakeSurvey s = LakeSurvey.of(w, SEED, 0, 0, p -> true, OPEN_SKY);
        int guard = 0;
        while (!s.step(6, w, l -> true)) {
            assertTrue("never finishes", ++guard < 100000);
        }
        return s.lake();
    }

    @Test
    public void aCleanPoolIsAGoodLakeWithNothingToDo() {
        Lake lake = run(LPortalPlanTest.pool());
        assertTrue(lake.good());
        assertFalse(lake.unknown());
        assertEquals(0, lake.cost());
        assertEquals(0, lake.fills());
        assertTrue(lake.cells().contains(SEED));
    }

    @Test
    public void aPoolUnderWaterIsABadLakeSoNobodyWalksToIt() {
        World w = LPortalPlanTest.pool();
        for (int x = 10; x < 40; x++) {
            for (int z = 0; z <= 10; z++) {
                w.over.put(new P(x, 64, z), Mat.WATER);
            }
        }
        Lake lake = run(w);
        assertNull(lake.site());
        assertFalse("water is a no, not a maybe", lake.unknown());
    }

    @Test
    public void waterAroundThePoolSpoilsEveryRowEvenWithAirOverTheLava() {
        World w = LPortalPlanTest.pool();
        // a ring of water on the shore all the way around: there are rows and layouts, every one reads a wet cell
        for (int x = 6; x < 44; x++) {
            for (int z = -4; z <= 14; z++) {
                boolean inPool = x >= 10 && x < 40 && z >= 0 && z <= 10;
                if (!inPool) {
                    w.over.put(new P(x, 64, z), Mat.WATER);
                }
            }
        }
        Lake lake = run(w);
        assertNull(lake.site());
        assertFalse(lake.unknown());
    }

    @Test
    public void aBoxThatIsntLoadedIsUnknownNotBad() {
        World w = LPortalPlanTest.pool();
        LakeSurvey s = LakeSurvey.of(w, SEED, 0, 0, p -> true, OPEN_SKY);
        while (!s.step(6, w, l -> false)) {
            // keep going
        }
        Lake lake = s.lake();
        assertNull(lake.site());
        assertTrue(lake.unknown());
    }

    // the east side of the seed's read box isn't loaded, every build box is
    private static Lake runWithEastUnloaded(World w, int firstUnloadedX) {
        LakeSurvey s = LakeSurvey.of(w, SEED, 0, 0, p -> p.x() < firstUnloadedX, OPEN_SKY);
        while (!s.step(6, w, l -> true)) {
            // keep going
        }
        return s.lake();
    }

    private static final int READ_EDGE = SEED.x() + LakeSurvey.READ_BOX;

    @Test
    public void noSiteWithTheReadBoxPartlyUnloadedIsUnknownNotBad() {
        // the same water ring as above, a no when we can see it all
        World w = LPortalPlanTest.pool();
        for (int x = 6; x < 44; x++) {
            for (int z = -4; z <= 14; z++) {
                boolean inPool = x >= 10 && x < 40 && z >= 0 && z <= 10;
                if (!inPool) {
                    w.over.put(new P(x, 64, z), Mat.WATER);
                }
            }
        }
        Lake lake = runWithEastUnloaded(w, READ_EDGE);
        assertNull(lake.site());
        assertTrue(lake.unknown());
    }

    @Test
    public void aSiteIsASiteEvenWithTheReadBoxPartlyUnloaded() {
        Lake lake = runWithEastUnloaded(LPortalPlanTest.pool(), READ_EDGE);
        assertTrue(lake.good());
        assertFalse(lake.unknown());
    }

    private static World puddle() {
        World w = new World();
        w.over.put(SEED, Mat.LAVA);
        w.over.put(new P(SEED.x() + 1, SEED.y(), SEED.z()), Mat.LAVA);
        w.over.put(new P(SEED.x(), SEED.y(), SEED.z() + 1), Mat.LAVA);
        return w;
    }

    @Test
    public void aPuddleThatEndsInSightIsANoEvenAtTheViewEdge() {
        Lake lake = runWithEastUnloaded(puddle(), READ_EDGE);
        assertNull(lake.site());
        assertFalse("a walk to read three blocks of lava is a walk wasted", lake.unknown());
    }

    @Test
    public void aFewCellsRightAgainstTheUnloadedChunkMayBeTheCornerOfABigLake() {
        Lake lake = runWithEastUnloaded(puddle(), SEED.x() + 2);
        assertNull(lake.site());
        assertTrue(lake.unknown());
    }

    @Test
    public void aFewOpenCellsWithARoofedNeckToMoreLavaAreNotAPuddle() {
        // four open cells, then the lake carries on under a rock roof, way past the grid around the seed
        World w = new World();
        for (int x = SEED.x(); x <= SEED.x() + 3; x++) {
            w.over.put(new P(x, SEED.y(), SEED.z()), Mat.LAVA);
        }
        for (int x = SEED.x() + 4; x <= SEED.x() + 8; x++) {
            w.over.put(new P(x, SEED.y(), SEED.z()), Mat.LAVA);
            w.over.put(new P(x, SEED.y() + 1, SEED.z()), Mat.SOLID);
        }
        Lake lake = runWithEastUnloaded(w, READ_EDGE);
        assertNull(lake.site());
        assertTrue(lake.unknown());
    }

    @Test
    public void theFloodLeavesTheGridAroundTheSeed() {
        // a one wide lake running off east, so only the flood can read the far end
        World w = new World();
        int cells = LakeSurvey.NEAR_SEED + 14;
        for (int x = 0; x < cells; x++) {
            w.over.put(new P(SEED.x() + x, SEED.y(), SEED.z()), Mat.LAVA);
        }
        LakeSurvey s = LakeSurvey.of(w, SEED, 0, 0, p -> true, OPEN_SKY);
        assertEquals(cells, s.sources());
        assertTrue(s.lake().cells().contains(new P(SEED.x() + cells - 1, SEED.y(), SEED.z())));
    }

    private static LakeSurvey finished(World w, ToIntFunction<P> depth) {
        LakeSurvey s = LakeSurvey.of(w, SEED, 0, 0, p -> true, depth);
        while (!s.step(6, w, l -> true)) {
            // keep going
        }
        return s;
    }

    @Test
    public void aSiteUnderTooMuchGroundIsNoSiteAndSaysSo() {
        LakeSurvey s = finished(LPortalPlanTest.pool(), p -> LavaHunt.MAX_DEPTH + 1);
        assertNull(s.lake().site());
        assertFalse("too deep is a no, not a maybe", s.lake().unknown());
        assertTrue(s.tally(), s.tally().contains("too_deep="));
        assertFalse(s.tally(), s.tally().contains("ok="));
    }

    @Test
    public void aSiteRightAtTheCapStaysAndCarriesItsDepth() {
        LakeSurvey s = finished(LPortalPlanTest.pool(), p -> LavaHunt.MAX_DEPTH);
        assertTrue(s.lake().good());
        assertEquals(LavaHunt.MAX_DEPTH, s.lake().depth());
        assertEquals("the depth stays out of the work count, pick reads that", 0, s.lake().cost());
    }

    @Test
    public void theShallowEndOfALakeBeatsTheDeepOne() {
        // same work on every row, the ground over it climbs to the east
        Lake lake = finished(LPortalPlanTest.pool(), p -> Math.min(p.x() - 10, LavaHunt.MAX_DEPTH)).lake();
        assertTrue(lake.good());
        assertEquals(LavaHunt.mid(lake.site()).x() - 10, lake.depth());
        assertTrue(lake.depth() <= 2);
    }

    @Test
    public void theTallySaysWhyAndCountsTheRowCheckToo() {
        World w = LPortalPlanTest.pool();
        LakeSurvey s = finished(w, OPEN_SKY);
        String t = s.tally();
        assertTrue(t, t.matches("\\d+ layouts, \\d+ quick \\| .*"));
        assertTrue(t, t.contains("row_not_lava="));
        assertTrue(t, t.contains("ok="));
        // biggest first
        String[] rows = t.substring(t.indexOf('|') + 2).split(" ");
        for (int i = 1; i < rows.length; i++) {
            assertTrue(t, Integer.parseInt(rows[i - 1].split("=")[1]) >= Integer.parseInt(rows[i].split("=")[1]));
        }
    }

    @Test
    public void aFullSurveyNoLandsInTheTallyUnderItsOwnName() {
        // the same water ring as above: rows are fine, every full look reads a wet cell
        World w = LPortalPlanTest.pool();
        for (int x = 6; x < 44; x++) {
            for (int z = -4; z <= 14; z++) {
                boolean inPool = x >= 10 && x < 40 && z >= 0 && z <= 10;
                if (!inPool) {
                    w.over.put(new P(x, 64, z), Mat.WATER);
                }
            }
        }
        String t = finished(w, OPEN_SKY).tally();
        assertTrue(t, t.contains("wet_box="));
    }

    @Test
    public void anUnloadedBoxShowsUpInTheTally() {
        World w = LPortalPlanTest.pool();
        LakeSurvey s = LakeSurvey.of(w, SEED, 0, 0, p -> true, OPEN_SKY);
        while (!s.step(6, w, l -> false)) {
            // keep going
        }
        assertTrue(s.tally(), s.tally().contains("box_unloaded="));
    }

    @Test
    public void aSurveyIsDoneAFewLayoutsAtATime() {
        World w = LPortalPlanTest.pool();
        LakeSurvey s = LakeSurvey.of(w, SEED, 0, 0, p -> true, OPEN_SKY);
        assertFalse(s.step(1, w, l -> true));
        assertNotNull(s.lake());
    }
}
