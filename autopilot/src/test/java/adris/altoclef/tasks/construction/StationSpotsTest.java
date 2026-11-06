package adris.altoclef.tasks.construction;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class StationSpotsTest {

    private static final double STAND_EYE = 1.62;
    private static final double SNEAK_EYE = 1.27;

    enum Kind {
        AIR, FULL, FENCE, SLAB_BOTTOM
    }

    // everything at or under floorY is stone, the rest is air, and tests carve whatever they need out of that
    static final class FakeWorld implements StationSpots.Cells {
        final int floorY;
        final Map<String, Kind> overrides = new HashMap<>();
        // when set, only these cells accept a block (a way to say "everything else has a mob in it")
        Set<String> onlyPlaceable;
        final Set<String> hidden = new HashSet<>();
        // stone we may not mine (bedrock, a chest, the ore we want)
        final Set<String> unminable = new HashSet<>();
        // cells with water, lava, gravel in them: opening a neighbour lets them in
        final Set<String> wet = new HashSet<>();
        // a station of ours or any workbench standing there (StationHook.keepStanding)
        final Set<String> stations = new HashSet<>();

        FakeWorld(int floorY) {
            this.floorY = floorY;
        }

        static String key(int x, int y, int z) {
            return x + "," + y + "," + z;
        }

        FakeWorld put(int x, int y, int z, Kind kind) {
            overrides.put(key(x, y, z), kind);
            return this;
        }

        Kind at(int x, int y, int z) {
            Kind k = overrides.get(key(x, y, z));
            if (k != null) {
                return k;
            }
            return y <= floorY ? Kind.FULL : Kind.AIR;
        }

        @Override
        public boolean placeable(int x, int y, int z) {
            if (onlyPlaceable != null && !onlyPlaceable.contains(key(x, y, z))) {
                return false;
            }
            return at(x, y, z) == Kind.AIR;
        }

        @Override
        public boolean faceSturdy(int x, int y, int z, int fx, int fy, int fz) {
            return switch (at(x, y, z)) {
                case FULL -> true;
                // the top of a bottom slab is half a block down, its bottom is the sturdy side
                case SLAB_BOTTOM -> fy == -1;
                default -> false;
            };
        }

        @Override
        public boolean passable(int x, int y, int z) {
            return at(x, y, z) == Kind.AIR;
        }

        @Override
        public boolean standable(int x, int y, int z) {
            return at(x, y - 1, z) == Kind.FULL && passable(x, y, z) && passable(x, y + 1, z);
        }

        @Override
        public boolean carvable(int x, int y, int z) {
            return at(x, y, z) == Kind.FULL && !unminable.contains(key(x, y, z));
        }

        @Override
        public boolean floods(int x, int y, int z) {
            return wet.contains(key(x, y, z));
        }

        @Override
        public boolean keep(int x, int y, int z) {
            return stations.contains(key(x, y, z));
        }

        @Override
        public boolean visible(double ex, double ey, double ez, int sx, int sy, int sz, int fx, int fy, int fz, int cx, int cy, int cz) {
            return !hidden.contains(key(sx, sy, sz));
        }
    }

    // feet at (0,65,0), standing on the floor (top at y=65)
    private static StationSpots.Stance flat() {
        return new StationSpots.Stance(0, 65, 0, 0.5, 65 + SNEAK_EYE, 0.5);
    }

    private static Optional<StationSpots.Spot> best(FakeWorld w, StationSpots.Stance me) {
        return StationSpots.best(w, me, new StationSpots.Bans());
    }

    @Test
    public void flatGroundPutsItOnOurLevelNextToUs() {
        StationSpots.Spot s = best(new FakeWorld(64), flat()).orElseThrow();
        assertEquals(65, s.y());
        assertTrue(s.isTopFace());
        // the next cell over, not some corner of the room
        assertEquals(1, Math.abs(s.x()) + Math.abs(s.z()));
    }

    @Test
    public void feetLevelBeatsEveryOtherLevelAtAnyDistance() {
        // a step off our level is a worse answer than the floor on it, even all the way out at the edge of the scan
        double farthest = 2.0 * StationSpots.RADIUS * StationSpots.RADIUS;
        assertTrue(StationSpots.score(farthest, 0) < StationSpots.score(1, 1));
        assertTrue(StationSpots.score(farthest, 0) < StationSpots.score(0, -1));
        assertTrue(StationSpots.score(1, 0) < StationSpots.score(1, 1));
        // a climb is a climb whichever way it goes
        assertEquals(StationSpots.score(2, 1), StationSpots.score(2, -1), 0);
    }

    // a two wide ledge one up beside us, and a floor cell on our own level three out. the ledge one is a lot closer
    @Test
    public void ourLevelWinsEvenWhenTheLedgeIsRightNextToUs() {
        FakeWorld w = new FakeWorld(64);
        for (int x = 1; x <= 2; x++) {
            for (int z = 0; z <= 1; z++) {
                w.put(x, 65, z, Kind.FULL);
            }
        }
        // the ledge cell is good on its own: floor under it, air over it, a walk to the cell beside it
        w.onlyPlaceable = new HashSet<>(Set.of(FakeWorld.key(1, 66, 0)));
        assertEquals(new StationSpots.Spot(1, 66, 0, 1, 65, 0), best(w, flat()).orElseThrow());
        w.onlyPlaceable.add(FakeWorld.key(3, 65, 0));
        assertEquals(new StationSpots.Spot(3, 65, 0, 3, 64, 0), best(w, flat()).orElseThrow());
    }

    @Test
    public void aHoleNextToUsLosesToTheFloorOnOurLevel() {
        FakeWorld w = new FakeWorld(64);
        // a pit one deep on every side at distance 1: those cells are placeable and supported, but a level lower
        w.put(1, 64, 0, Kind.AIR).put(-1, 64, 0, Kind.AIR).put(0, 64, 1, Kind.AIR).put(0, 64, -1, Kind.AIR);
        StationSpots.Spot s = best(w, flat()).orElseThrow();
        assertEquals(65, s.y());
    }

    @Test
    public void aSturdyTopIsRequiredNotJustAnythingSolid() {
        // standing on a fence line: every neighbouring floor cell is a fence, whose top is not a face to build on
        FakeWorld w = new FakeWorld(63);
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                w.put(x, 64, z, Kind.FENCE);
            }
        }
        StationSpots.Stance onFence = new StationSpots.Stance(0, 65, 0, 0.5, 65 + SNEAK_EYE, 0.5);
        assertTrue(best(w, onFence).isEmpty());
        // a bottom slab is not a floor either
        FakeWorld slabs = new FakeWorld(63);
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                slabs.put(x, 64, z, Kind.SLAB_BOTTOM);
            }
        }
        assertTrue(best(slabs, onFence).isEmpty());
    }

    // standing on a fence line, a one cell gap in it used to be the answer. it is a pit now: a table down there has fence on
    // every side, nothing to stand beside it
    @Test
    public void aGapInTheFenceLineIsAPitNotASpot() {
        FakeWorld w = new FakeWorld(63);
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                w.put(x, 64, z, Kind.FENCE);
            }
        }
        w.put(2, 64, 0, Kind.AIR);
        StationSpots.Stance onFence = new StationSpots.Stance(0, 65, 0, 0.5, 65 + SNEAK_EYE, 0.5);
        StationSpots.Refusals why = new StationSpots.Refusals();
        assertTrue(StationSpots.best(w, onFence, new StationSpots.Bans(), null, why).isEmpty());
        // every cell on our level has fence under it, so the gap is not the only one with nothing to stand beside it
        assertTrue(why.summary(), why.count(StationSpots.Refusal.BOXED_IN) > 0);
    }

    @Test
    public void ourOwnTwoCellsAreNeverTheAnswer() {
        FakeWorld w = new FakeWorld(64);
        w.onlyPlaceable = new HashSet<>(Set.of(FakeWorld.key(0, 65, 0), FakeWorld.key(0, 66, 0)));
        assertTrue(best(w, flat()).isEmpty());
        // and the cell next to us is fine once it is allowed
        w.onlyPlaceable.add(FakeWorld.key(1, 65, 0));
        assertEquals(new StationSpots.Spot(1, 65, 0, 1, 64, 0), best(w, flat()).orElseThrow());
    }

    @Test
    public void ownCellPredicateCoversFeetAndHeadOnly() {
        StationSpots.Stance me = flat();
        assertTrue(me.isOwnCell(0, 65, 0));
        assertTrue(me.isOwnCell(0, 66, 0));
        assertFalse(me.isOwnCell(0, 64, 0));
        assertFalse(me.isOwnCell(0, 67, 0));
        assertFalse(me.isOwnCell(1, 65, 0));
    }

    @Test
    public void aBanTakesTheColumnTwoEitherWay() {
        StationSpots.Bans bans = new StationSpots.Bans();
        bans.ban(3, 65, 3);
        for (int y = 63; y <= 67; y++) {
            assertTrue("y " + y, bans.isBanned(3, y, 3));
        }
        assertFalse(bans.isBanned(3, 62, 3));
        assertFalse(bans.isBanned(3, 68, 3));
        // the next column over is a different spot
        assertFalse(bans.isBanned(4, 65, 3));
        assertFalse(bans.isBanned(3, 65, 4));
        bans.clear();
        assertFalse(bans.isBanned(3, 65, 3));
    }

    @Test
    public void aBannedSpotIsNotPickedAgain() {
        FakeWorld w = new FakeWorld(64);
        StationSpots.Bans bans = new StationSpots.Bans();
        StationSpots.Spot first = StationSpots.best(w, flat(), bans).orElseThrow();
        bans.ban(first.x(), first.y(), first.z());
        StationSpots.Spot second = StationSpots.best(w, flat(), bans).orElseThrow();
        assertNotEquals(first, second);
        assertFalse(bans.isBanned(second.x(), second.y(), second.z()));
    }

    @Test
    public void everySpotBannedMeansNoSpot() {
        FakeWorld w = new FakeWorld(64);
        StationSpots.Bans bans = new StationSpots.Bans();
        for (int x = -StationSpots.RADIUS; x <= StationSpots.RADIUS; x++) {
            for (int z = -StationSpots.RADIUS; z <= StationSpots.RADIUS; z++) {
                bans.ban(x, 65, z);
            }
        }
        assertTrue(StationSpots.best(w, flat(), bans).isEmpty());
    }

    // the wall pass is for a cell over a hole (or a fence top, a slab): no floor to click, a pillar beside it to click, and
    // ground next to it to stand on, so it still passes for a spot we can use
    @Test
    public void aWallSideIsTheFallbackWhenNoFloorWorks() {
        FakeWorld w = new FakeWorld(64);
        // a stone pillar three blocks east, up through our level, and a hole in the floor in front of it
        w.put(3, 65, 0, Kind.FULL).put(3, 66, 0, Kind.FULL).put(2, 64, 0, Kind.AIR);
        w.onlyPlaceable = new HashSet<>(Set.of(FakeWorld.key(2, 65, 0)));
        StationSpots.Spot s = best(w, flat()).orElseThrow();
        assertFalse(s.isTopFace());
        // the cell west of the pillar, clicking the pillar's west face
        assertEquals(new StationSpots.Spot(2, 65, 0, 3, 65, 0), s);
        assertEquals(-1, s.faceX());
        assertEquals(0, s.faceY());
    }

    // the same wall with no ground to stand on beside the cell: a station hanging off a cliff
    @Test
    public void aWallSideStillNeedsSomewhereToStand() {
        FakeWorld w = new FakeWorld(64);
        w.put(3, 65, 0, Kind.FULL).put(3, 66, 0, Kind.FULL);
        for (int x = 1; x <= 2; x++) {
            for (int z = -1; z <= 1; z++) {
                w.put(x, 64, z, Kind.AIR);
            }
        }
        // (2,65,0) has air under it and under every cell beside it that we could stand in
        w.put(2, 63, 0, Kind.AIR);
        w.onlyPlaceable = new HashSet<>(Set.of(FakeWorld.key(2, 65, 0)));
        assertTrue(best(w, flat()).isEmpty());
    }

    @Test
    public void aFloorBeatsAWallWheneverThereIsOne() {
        FakeWorld w = new FakeWorld(64);
        w.put(1, 65, 0, Kind.FULL).put(1, 66, 0, Kind.FULL);
        StationSpots.Spot s = best(w, flat()).orElseThrow();
        assertTrue(s.isTopFace());
    }

    @Test
    public void aHiddenFaceIsNotClickable() {
        FakeWorld w = new FakeWorld(64);
        // the floor next to us is out of sight (behind something), the rest is fine
        w.hidden.add(FakeWorld.key(1, 64, 0));
        w.hidden.add(FakeWorld.key(-1, 64, 0));
        w.hidden.add(FakeWorld.key(0, 64, 1));
        w.hidden.add(FakeWorld.key(0, 64, -1));
        StationSpots.Spot s = best(w, flat()).orElseThrow();
        assertFalse(w.hidden.contains(FakeWorld.key(s.sx(), s.sy(), s.sz())));
    }

    @Test
    public void thingsOutOfReachAreNotClickable() {
        FakeWorld w = new FakeWorld(64);
        w.onlyPlaceable = new HashSet<>(Set.of(FakeWorld.key(4, 65, 4)));
        // 5.7 blocks from the eye to the middle of that floor
        assertTrue(best(w, flat()).isEmpty());
        w.onlyPlaceable = new HashSet<>(Set.of(FakeWorld.key(2, 65, 1)));
        assertTrue(best(w, flat()).isPresent());
    }

    @Test
    public void weDoNotPlaceTheLastDoorOutOfACorridor() {
        // stone on three sides at feet and head height, the only way out is south
        FakeWorld w = new FakeWorld(64);
        for (int y = 65; y <= 66; y++) {
            w.put(1, y, 0, Kind.FULL).put(-1, y, 0, Kind.FULL).put(0, y, -1, Kind.FULL);
        }
        w.onlyPlaceable = new HashSet<>(Set.of(FakeWorld.key(0, 65, 1)));
        assertFalse(StationSpots.leavesAWayOut(w, flat(), 0, 65, 1));
        assertTrue(best(w, flat()).isEmpty());
        // a second way out and it is fine
        w.put(1, 65, 0, Kind.AIR).put(1, 66, 0, Kind.AIR);
        assertTrue(StationSpots.leavesAWayOut(w, flat(), 0, 65, 1));
        assertTrue(best(w, flat()).isPresent());
    }

    @Test
    public void aBlockOnTheFloorBelowUsNeverClosesAnExit() {
        // stone around us except east, and the cell east of us but a level down is not the doorway (feet and head are)
        FakeWorld w = new FakeWorld(63);
        for (int y = 65; y <= 66; y++) {
            w.put(-1, y, 0, Kind.FULL).put(0, y, 1, Kind.FULL).put(0, y, -1, Kind.FULL);
        }
        assertTrue(StationSpots.leavesAWayOut(w, flat(), 1, 64, 0));
        assertFalse(StationSpots.leavesAWayOut(w, flat(), 1, 65, 0));
        assertFalse(StationSpots.leavesAWayOut(w, flat(), 1, 66, 0));
    }

    // ---- standing points ----

    @Test
    public void aStandpointComesFromTheNearestPatchWithASpot() {
        FakeWorld w = new FakeWorld(64);
        // only the strip around x=10 takes a block, six blocks is as far as we walk and the first place the strip is in reach
        w.onlyPlaceable = new HashSet<>();
        for (int x = 9; x <= 11; x++) {
            for (int z = -1; z <= 1; z++) {
                w.onlyPlaceable.add(FakeWorld.key(x, 65, z));
            }
        }
        StationSpots.Stand stand = StationSpots.standpoint(w, flat(), new StationSpots.Bans(), SNEAK_EYE).orElseThrow();
        assertEquals(6, stand.x());
        assertEquals(0, stand.z());
        assertEquals(65, stand.y());
    }

    @Test
    public void aStandpointIsAtLeastTwoAwayAndNoMoreThanSix() {
        FakeWorld w = new FakeWorld(64);
        // spots exist right where we stand, so the nearest candidate counts, but never closer than MIN_MOVE
        StationSpots.Stand stand = StationSpots.standpoint(w, flat(), new StationSpots.Bans(), SNEAK_EYE).orElseThrow();
        int far = Math.max(Math.abs(stand.x()), Math.abs(stand.z()));
        assertTrue(far >= StationSpots.MIN_MOVE && far <= StationSpots.MAX_MOVE);
    }

    // the walk to a standpoint is baritone's own and digs or bridges if that is cheaper, so a stand it would have to dig to is out
    @Test
    public void aStandpointBehindAWallIsNotOneWeCanWalkTo() {
        FakeWorld w = new FakeWorld(64);
        w.onlyPlaceable = new HashSet<>();
        for (int z = -2; z <= 2; z++) {
            w.onlyPlaceable.add(FakeWorld.key(5, 65, z));
        }
        // clear of the wall the east side is a fine stand
        assertTrue(StationSpots.standpoint(w, flat(), new StationSpots.Bans(), SNEAK_EYE).isPresent());
        for (int z = -8; z <= 8; z++) {
            for (int y = 65; y <= 67; y++) {
                w.put(2, y, z, Kind.FULL);
            }
        }
        assertTrue(StationSpots.standpoint(w, flat(), new StationSpots.Bans(), SNEAK_EYE).isEmpty());
    }

    // a hole dug beside our head has no cell to stand beside it at its own level, so it is no spot even though we dug it
    @Test
    public void aCarvedHoleStillNeedsAStandingCellBesideIt() {
        FakeWorld w = shaft();
        w.put(1, 66, 0, Kind.AIR);
        assertTrue(StationSpots.best(w, inShaft(), new StationSpots.Bans(), new StationSpots.Cell(1, 66, 0)).isEmpty());
    }

    @Test
    public void noStandpointWhenNothingAnywhereTakesABlock() {
        FakeWorld w = new FakeWorld(64);
        w.onlyPlaceable = new HashSet<>();
        assertTrue(StationSpots.standpoint(w, flat(), new StationSpots.Bans(), SNEAK_EYE).isEmpty());
    }

    @Test
    public void standpointsSkipBannedColumns() {
        FakeWorld w = new FakeWorld(64);
        StationSpots.Bans bans = new StationSpots.Bans();
        StationSpots.Stand first = StationSpots.standpoint(w, flat(), bans, SNEAK_EYE).orElseThrow();
        bans.ban(first.x(), first.y(), first.z());
        StationSpots.Stand second = StationSpots.standpoint(w, flat(), bans, SNEAK_EYE).orElseThrow();
        assertNotEquals(first, second);
    }

    @Test
    public void aStandpointNeedsAFullBlockUnderIt() {
        // nothing but air under y=64 means no full block to stand on
        FakeWorld w = new FakeWorld(0);
        assertFalse(w.standable(5, 65, 0));
        assertTrue(new FakeWorld(64).standable(5, 65, 0));
    }

    // ---- carving ----

    // a 1x1 shaft in solid rock. our two cells, and open air straight up and down. nothing around takes a block
    private static FakeWorld shaft() {
        FakeWorld w = new FakeWorld(200);
        for (int y = 60; y <= 80; y++) {
            w.put(0, y, 0, Kind.AIR);
        }
        return w;
    }

    private static StationSpots.Stance inShaft() {
        return new StationSpots.Stance(0, 65, 0, 0.5, 65 + SNEAK_EYE, 0.5);
    }

    private static List<StationSpots.Cell> carves(FakeWorld w) {
        return StationSpots.carveCandidates(w, inShaft(), new StationSpots.Bans());
    }

    @Test
    public void aShaftHasNothingClickableButPlentyToCarve() {
        FakeWorld w = shaft();
        assertTrue(best(w, inShaft()).isEmpty());
        assertFalse(carves(w).isEmpty());
    }

    @Test
    public void carveOrderIsFeetThenTwoOutAndTwoOutNeedsAStandingCellBetween() {
        List<StationSpots.Cell> list = carves(shaft());
        // four at feet level and nothing else: never beside the head (no cell to stand beside it at its level), and nothing two
        // out, the cell between is stone
        assertEquals(4, list.size());
        for (int i = 0; i < 4; i++) {
            assertEquals(65, list.get(i).y());
            assertEquals(1, Math.abs(list.get(i).x()) + Math.abs(list.get(i).z()));
        }
        // a tunnel east: now the cell two out that way is a candidate, after the near ones
        FakeWorld tunnel = shaft();
        tunnel.put(1, 65, 0, Kind.AIR).put(1, 66, 0, Kind.AIR);
        List<StationSpots.Cell> withTunnel = carves(tunnel);
        assertEquals(new StationSpots.Cell(2, 65, 0), withTunnel.get(withTunnel.size() - 1));
        assertFalse(withTunnel.contains(new StationSpots.Cell(1, 65, 0)));
        // the tunnel's floor gone: the cell between is open but nobody stands in it, so there is no way to use a station past it
        tunnel.put(1, 64, 0, Kind.AIR);
        assertFalse(carves(tunnel).contains(new StationSpots.Cell(2, 65, 0)));
    }

    @Test
    public void carvingTheHoleMakesItTheSpot() {
        FakeWorld w = shaft();
        StationSpots.Cell hole = carves(w).get(0);
        w.put(hole.x(), hole.y(), hole.z(), Kind.AIR);
        // dug by us: it needn't leave a way out, the shaft is the way out
        StationSpots.Spot s = StationSpots.best(w, inShaft(), new StationSpots.Bans(), hole).orElseThrow();
        assertEquals(new StationSpots.Spot(hole.x(), hole.y(), hole.z(), hole.x(), hole.y() - 1, hole.z()), s);
        assertTrue(s.isTopFace());
        // an ordinary hole doesn't get the pass: a table there would close the last horizontal door
        assertTrue(StationSpots.best(w, inShaft(), new StationSpots.Bans()).isEmpty());
    }

    @Test
    public void theCarveExemptionIsOnlyForTheCellWeDug() {
        FakeWorld w = shaft();
        w.put(1, 65, 0, Kind.AIR);
        // (1,65,0) is open but it isn't the one we dug (that was somewhere else), so it still has to leave a way out
        assertTrue(StationSpots.best(w, inShaft(), new StationSpots.Bans(), new StationSpots.Cell(0, 65, 1)).isEmpty());
        assertTrue(StationSpots.best(w, inShaft(), new StationSpots.Bans(), new StationSpots.Cell(1, 65, 0)).isPresent());
    }

    @Test
    public void bedrockContainersAndOreAreNeverCarved() {
        FakeWorld w = shaft();
        assertEquals(4, carves(w).size());
        w.unminable.add(FakeWorld.key(1, 65, 0));
        w.unminable.add(FakeWorld.key(-1, 65, 0));
        w.unminable.add(FakeWorld.key(0, 65, 1));
        // three of four off limits leaves the last
        assertEquals(List.of(new StationSpots.Cell(0, 65, -1)), carves(w));
        w.unminable.add(FakeWorld.key(0, 65, -1));
        assertTrue(carves(w).isEmpty());
    }

    @Test
    public void aCarveNeedsASturdyFloorUnderIt() {
        FakeWorld w = shaft();
        // a hole under the east neighbour: opening it gives a pit, not a floor
        w.put(1, 64, 0, Kind.AIR);
        assertFalse(carves(w).contains(new StationSpots.Cell(1, 65, 0)));
        // a fence top is not a floor either
        w.put(-1, 64, 0, Kind.FENCE);
        assertFalse(carves(w).contains(new StationSpots.Cell(-1, 65, 0)));
        assertTrue(carves(w).contains(new StationSpots.Cell(0, 65, 1)));
    }

    @Test
    public void aCarveNextToWaterOrFallingBlocksIsOut() {
        FakeWorld w = shaft();
        // water behind the east wall: opening (1,65,0) lets it pour in
        w.wet.add(FakeWorld.key(2, 65, 0));
        assertFalse(carves(w).contains(new StationSpots.Cell(1, 65, 0)));
        // gravel above the north one falls into the hole
        w.wet.add(FakeWorld.key(0, 66, -1));
        assertFalse(carves(w).contains(new StationSpots.Cell(0, 65, -1)));
        // lava over the west one
        w.wet.add(FakeWorld.key(-1, 66, 0));
        assertFalse(carves(w).contains(new StationSpots.Cell(-1, 65, 0)));
        // south is fine
        assertTrue(carves(w).contains(new StationSpots.Cell(0, 65, 1)));
    }

    @Test
    public void bannedCarvesAreSkipped() {
        FakeWorld w = shaft();
        StationSpots.Bans bans = new StationSpots.Bans();
        StationSpots.Cell first = StationSpots.carveCandidates(w, inShaft(), bans).get(0);
        bans.ban(first.x(), first.y(), first.z());
        assertFalse(StationSpots.carveCandidates(w, inShaft(), bans).contains(first));
    }

    // the table we just crafted the furnace at stands beside our feet in the shaft. it is a full block, breakable, no block entity,
    // so carvable alone says yes. it is never the hole the furnace goes in
    @Test
    public void ourTableIsNeverCarvedForRoom() {
        FakeWorld w = shaft();
        StationSpots.Cell table = new StationSpots.Cell(1, 65, 0);
        assertTrue(carves(w).contains(table));
        assertEquals(table, carves(w).get(0));
        w.stations.add(FakeWorld.key(1, 65, 0));
        assertFalse(carves(w).contains(table));
        // the other walls still are
        assertTrue(carves(w).contains(new StationSpots.Cell(-1, 65, 0)));
    }

    @Test
    public void openAirAroundMeNeedsNoCarving() {
        // nothing solid to mine on the floor level of an open room: the neighbours are air, not candidates
        FakeWorld w = new FakeWorld(64);
        assertTrue(StationSpots.carveCandidates(w, flat(), new StationSpots.Bans()).isEmpty());
    }

    // ---- a spot we can use as it is ----

    private static StationSpots.Refusals ask(FakeWorld w, StationSpots.Stance me) {
        StationSpots.Refusals why = new StationSpots.Refusals();
        StationSpots.best(w, me, new StationSpots.Bans(), null, why);
        return why;
    }

    private static void station(FakeWorld w, int x, int y, int z) {
        w.put(x, y, z, Kind.FULL);
        w.stations.add(FakeWorld.key(x, y, z));
    }

    @Test
    public void aPitBesideUsIsNeverPicked() {
        FakeWorld w = new FakeWorld(64);
        // one deep, ground on every side of it. a table in there is a table we dig for
        w.put(1, 64, 0, Kind.AIR);
        w.onlyPlaceable = new HashSet<>(Set.of(FakeWorld.key(1, 64, 0)));
        assertTrue(best(w, flat()).isEmpty());
        assertEquals(1, ask(w, flat()).count(StationSpots.Refusal.BOXED_IN));
        // two deep is out of the scan altogether
        w.put(1, 63, 0, Kind.AIR);
        w.onlyPlaceable = new HashSet<>(Set.of(FakeWorld.key(1, 63, 0)));
        assertTrue(best(w, flat()).isEmpty());
        assertEquals(0, ask(w, flat()).count(StationSpots.Refusal.BOXED_IN));
    }

    @Test
    public void aCellWithRockOverItIsRefused() {
        FakeWorld w = new FakeWorld(64);
        w.put(1, 66, 0, Kind.FULL);
        w.onlyPlaceable = new HashSet<>(Set.of(FakeWorld.key(1, 65, 0)));
        assertTrue(best(w, flat()).isEmpty());
        assertEquals(1, ask(w, flat()).count(StationSpots.Refusal.ROOFED));
        w.put(1, 66, 0, Kind.AIR);
        assertTrue(best(w, flat()).isPresent());
    }

    @Test
    public void aCellWhoseNeighboursAreAllWallIsRefused() {
        FakeWorld w = new FakeWorld(64);
        // (3,65,0) is open and has a floor, and stone on the three sides we don't come from: only (2,65,0) is left to stand on,
        // so wall it too and nothing is
        w.put(4, 65, 0, Kind.FULL).put(3, 65, 1, Kind.FULL).put(3, 65, -1, Kind.FULL).put(2, 65, 0, Kind.FULL);
        w.onlyPlaceable = new HashSet<>(Set.of(FakeWorld.key(3, 65, 0)));
        assertTrue(best(w, flat()).isEmpty());
        assertEquals(1, ask(w, flat()).count(StationSpots.Refusal.BOXED_IN));
    }

    // a one wide tunnel that runs on past the edge of what we look at: along it is fine while a way out remains, and the dead
    // end is not, however far down the tunnel the table would go
    @Test
    public void aTunnelTakesAStationOnlyWhileAWayOutRemains() {
        FakeWorld w = new FakeWorld(200);
        for (int x = -8; x <= 8; x++) {
            w.put(x, 65, 0, Kind.AIR).put(x, 66, 0, Kind.AIR);
        }
        StationSpots.Spot s = best(w, flat()).orElseThrow();
        assertEquals(0, s.z());
        assertEquals(65, s.y());
        assertEquals(1, Math.abs(s.x()));
        // bricked up on the west side we stand at a dead end: the table would be the plug between us and the rest of the tunnel
        w.put(-1, 65, 0, Kind.FULL).put(-1, 66, 0, Kind.FULL);
        StationSpots.Refusals why = ask(w, flat());
        assertTrue(best(w, flat()).isEmpty());
        assertTrue(why.summary(), why.count(StationSpots.Refusal.BOXED_IN) > 0);
        assertTrue(why.summary(), why.count(StationSpots.Refusal.SEALS_US_IN) > 0);
    }

    // the only standing cell beside the spot is on the far side of the spot itself
    @Test
    public void aStandingCellWeCannotWalkToDoesNotCount() {
        FakeWorld w = new FakeWorld(200);
        for (int x = -3; x <= 5; x++) {
            w.put(x, 65, 0, Kind.AIR).put(x, 66, 0, Kind.AIR);
        }
        // a hole in the tunnel floor at x=1 and the spot at x=2: the room east of it is behind the table
        w.put(1, 64, 0, Kind.AIR);
        w.onlyPlaceable = new HashSet<>(Set.of(FakeWorld.key(2, 65, 0)));
        StationSpots.Refusals why = ask(w, flat());
        assertTrue(best(w, flat()).isEmpty());
        assertEquals(1, why.count(StationSpots.Refusal.BOXED_IN));
    }

    @Test
    public void aSecondStationNextToTheFirstIsFineWhileBothKeepAStandingCell() {
        FakeWorld w = new FakeWorld(64);
        station(w, 1, 65, 0);
        w.onlyPlaceable = new HashSet<>(Set.of(FakeWorld.key(2, 65, 0)));
        assertEquals(new StationSpots.Spot(2, 65, 0, 2, 64, 0), best(w, flat()).orElseThrow());
    }

    @Test
    public void aSecondStationNeverTakesTheFirstOnesOnlyStandingCell() {
        FakeWorld w = new FakeWorld(64);
        // a table in a nook: stone on three sides, the cell we are about to take is the only way to it
        station(w, 3, 65, 0);
        w.put(4, 65, 0, Kind.FULL).put(3, 65, 1, Kind.FULL).put(3, 65, -1, Kind.FULL);
        w.onlyPlaceable = new HashSet<>(Set.of(FakeWorld.key(2, 65, 0)));
        assertTrue(best(w, flat()).isEmpty());
        assertEquals(1, ask(w, flat()).count(StationSpots.Refusal.CROWDS_A_STATION));
        // open a second side and the nook has a door left
        w.put(3, 65, 1, Kind.AIR);
        assertTrue(best(w, flat()).isPresent());
    }

    @Test
    public void nothingGoesOnTopOfAStation() {
        // the floor is at y=65 here, the table is part of it, and the cell on top has plenty of floor beside it
        FakeWorld w = new FakeWorld(65);
        station(w, 2, 65, 0);
        StationSpots.Stance me = new StationSpots.Stance(0, 66, 0, 0.5, 66 + SNEAK_EYE, 0.5);
        w.onlyPlaceable = new HashSet<>(Set.of(FakeWorld.key(2, 66, 0)));
        assertTrue(StationSpots.best(w, me, new StationSpots.Bans()).isEmpty());
        assertEquals(1, ask(w, me).count(StationSpots.Refusal.CROWDS_A_STATION));
        // a plain block there is a perfectly good floor
        w.stations.clear();
        assertTrue(StationSpots.best(w, me, new StationSpots.Bans()).isPresent());
    }

    @Test
    public void refusalsAreCountedAndWordedBiggestFirst() {
        // a fence line: every neighbour on our level has no floor to click
        FakeWorld w = new FakeWorld(63);
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                w.put(x, 64, z, Kind.FENCE);
            }
        }
        StationSpots.Stance onFence = new StationSpots.Stance(0, 65, 0, 0.5, 65 + SNEAK_EYE, 0.5);
        StationSpots.Refusals why = ask(w, onFence);
        assertTrue(why.count(StationSpots.Refusal.NO_FLOOR) > 0);
        // dy = -1 is the fence itself, in the way
        assertTrue(why.count(StationSpots.Refusal.IN_THE_WAY) > 0);
        assertEquals(0, why.count(StationSpots.Refusal.ROOFED));
        assertTrue(why.summary(), why.summary().contains("no floor"));
        assertEquals("nothing in range", new StationSpots.Refusals().summary());
        StationSpots.Refusals counted = new StationSpots.Refusals();
        counted.add(StationSpots.Refusal.ROOFED);
        counted.add(StationSpots.Refusal.NO_FLOOR);
        counted.add(StationSpots.Refusal.ROOFED);
        counted.add(StationSpots.Refusal.NO_FLOOR);
        counted.add(StationSpots.Refusal.NO_FLOOR);
        counted.add(StationSpots.Refusal.BOXED_IN);
        assertEquals("3 no floor, 2 roofed, 1 boxed in", counted.summary());
        assertEquals(6, counted.total());
        // a ban is its own reason
        StationSpots.Bans bans = new StationSpots.Bans();
        for (int x = -StationSpots.RADIUS; x <= StationSpots.RADIUS; x++) {
            for (int z = -StationSpots.RADIUS; z <= StationSpots.RADIUS; z++) {
                bans.ban(x, 65, z);
            }
        }
        StationSpots.Refusals banned = new StationSpots.Refusals();
        assertTrue(StationSpots.best(new FakeWorld(64), flat(), bans, null, banned).isEmpty());
        assertTrue(banned.summary(), banned.count(StationSpots.Refusal.BANNED) > 0);
    }

    // random 9x9x5 rooms around a standing player. whatever spot comes back has a floor under it (or a wall to click), air over
    // it, a standing cell beside it that a walk from the feet gets to with the block in place, and a click that reaches
    @Test
    public void everyPickedSpotCanBeUsedAsItIs() {
        java.util.Random rng = new java.util.Random(26_10_10);
        int picked = 0;
        for (int round = 0; round < 400; round++) {
            FakeWorld w = new FakeWorld(64);
            double air = 0.35 + rng.nextDouble() * 0.5;
            for (int x = -6; x <= 6; x++) {
                for (int z = -6; z <= 6; z++) {
                    for (int y = 63; y <= 68; y++) {
                        w.put(x, y, z, rng.nextDouble() < air ? Kind.AIR : Kind.FULL);
                    }
                }
            }
            // somewhere to stand
            w.put(0, 64, 0, Kind.FULL).put(0, 65, 0, Kind.AIR).put(0, 66, 0, Kind.AIR);
            if (rng.nextInt(3) == 0) {
                w.hidden.add(FakeWorld.key(rng.nextInt(5) - 2, 64, rng.nextInt(5) - 2));
            }
            Optional<StationSpots.Spot> found = best(w, flat());
            if (found.isEmpty()) {
                continue;
            }
            picked++;
            StationSpots.Spot s = found.get();
            String where = "round " + round + " " + s;
            assertTrue(where, w.placeable(s.x(), s.y(), s.z()));
            assertTrue(where, Math.abs(s.y() - 65) <= 1);
            assertTrue(where, w.passable(s.x(), s.y() + 1, s.z()));
            assertTrue(where, StationSpots.faceDistSq(flat(), s) <= StationSpots.REACH * StationSpots.REACH);
            assertTrue(where, w.faceSturdy(s.sx(), s.sy(), s.sz(), s.faceX(), s.faceY(), s.faceZ()));
            assertFalse(where, w.hidden.contains(FakeWorld.key(s.sx(), s.sy(), s.sz())));
            assertTrue(where, StationSpots.leavesAWayOut(w, flat(), s.x(), s.y(), s.z()));
            // the oracle: the block goes down for real, and a plain walk over standable cells has to reach a cell beside it
            w.put(s.x(), s.y(), s.z(), Kind.FULL);
            assertTrue(where, walkReaches(w, s));
        }
        // the sweep is worth nothing if the rooms never gave an answer
        assertTrue("picked " + picked, picked > 40);
    }

    // breadth first from the feet over standable cells, one step up or down, inside the flood box. a cell beside the spot at its level
    private static boolean walkReaches(FakeWorld w, StationSpots.Spot s) {
        java.util.ArrayDeque<int[]> open = new java.util.ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        open.add(new int[]{0, 65, 0});
        seen.add(FakeWorld.key(0, 65, 0));
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!open.isEmpty()) {
            int[] c = open.poll();
            if (c[1] == s.y() && Math.abs(c[0] - s.x()) + Math.abs(c[2] - s.z()) == 1) {
                return true;
            }
            for (int[] d : dirs) {
                for (int up : new int[]{0, 1, -1}) {
                    int nx = c[0] + d[0];
                    int ny = c[1] + up;
                    int nz = c[2] + d[1];
                    if (Math.abs(nx) > StationSpots.FLOOD_RADIUS || Math.abs(nz) > StationSpots.FLOOD_RADIUS || Math.abs(ny - 65) > 3
                            || seen.contains(FakeWorld.key(nx, ny, nz)) || !w.standable(nx, ny, nz)) {
                        continue;
                    }
                    boolean room = up == 0 || (up > 0 ? w.passable(c[0], c[1] + 2, c[2]) : w.passable(nx, c[1] + 1, nz));
                    if (room) {
                        seen.add(FakeWorld.key(nx, ny, nz));
                        open.add(new int[]{nx, ny, nz});
                    }
                }
            }
        }
        return false;
    }

    @Test
    public void faceDistanceIsToTheMiddleOfTheFace() {
        StationSpots.Stance me = new StationSpots.Stance(0, 65, 0, 0.5, 66.5, 0.5);
        // the top face of (0,64,0) sits at y=65, 1.5 under the eye
        double d = StationSpots.faceDistSq(me, new StationSpots.Spot(0, 65, 0, 0, 64, 0));
        assertEquals(1.5 * 1.5, d, 1e-9);
    }

    // open water as far as the eye goes: no floor anywhere, then land strips where a test puts them. feet at (0,64,0), afloat
    private static FakeWorld pool() {
        return new FakeWorld(-1000);
    }

    private static void bank(FakeWorld w, int x0, int x1, int z0, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                w.put(x, 63, z, Kind.FULL);
            }
        }
    }

    private static StationSpots.Stance afloat() {
        return new StationSpots.Stance(0, 64, 0, 0.5, 64 + STAND_EYE, 0.5);
    }

    @Test
    public void shoreTenBlocksOffIsTheSwim() {
        FakeWorld w = pool();
        bank(w, 10, 14, -3, 3);
        StationSpots.Stand s = StationSpots.nearestShore(w, afloat(), StationSpots.SHORE_RADIUS).orElseThrow();
        assertEquals(10, s.x());
        assertEquals(0, s.z());
        assertEquals(64, s.y());
    }

    @Test
    public void noLandInRangeIsNoShore() {
        FakeWorld w = pool();
        bank(w, 40, 44, -3, 3);
        assertTrue(StationSpots.nearestShore(w, afloat(), StationSpots.SHORE_RADIUS).isEmpty());
    }

    @Test
    public void theNearerOfTwoShoresWins() {
        FakeWorld w = pool();
        bank(w, 20, 24, -3, 3);
        bank(w, -12, -8, -3, 3);
        StationSpots.Stand s = StationSpots.nearestShore(w, afloat(), StationSpots.SHORE_RADIUS).orElseThrow();
        assertEquals(-8, s.x());
    }

    // a lone pillar is a stand but not a place to put a table. a real bank further out beats it
    @Test
    public void aBankWithFloorBesideBeatsALonePillar() {
        FakeWorld w = pool();
        w.put(5, 63, 0, Kind.FULL);
        bank(w, 12, 16, -3, 3);
        StationSpots.Stand s = StationSpots.nearestShore(w, afloat(), StationSpots.SHORE_RADIUS).orElseThrow();
        assertEquals(12, s.x());
    }

    @Test
    public void aLonePillarBeatsNothing() {
        FakeWorld w = pool();
        w.put(5, 63, 0, Kind.FULL);
        StationSpots.Stand s = StationSpots.nearestShore(w, afloat(), StationSpots.SHORE_RADIUS).orElseThrow();
        assertEquals(5, s.x());
    }

    // the radius is a circle, a corner of the square is further than it says
    @Test
    public void aCornerOfTheSquareIsOutOfRange() {
        FakeWorld w = pool();
        bank(w, 27, 29, 27, 29);
        assertTrue(StationSpots.nearestShore(w, afloat(), StationSpots.SHORE_RADIUS).isEmpty());
    }
}
