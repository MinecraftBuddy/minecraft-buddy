package baritone.process;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.trackers.LandMemory;
import adris.altoclef.trackers.LandMemoryTestAccess;
import baritone.process.ExploreProcess.FrontierScan;
import baritone.process.ExploreProcess.IChunkFilter;
import baritone.process.ExploreProcess.ScanOutcome;
import baritone.process.ExploreProcess.Status;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.level.ChunkPos;
import org.junit.Test;

// the land scan against the real LandMemory, with a fake cache: everything the memory has seen is explored, the rest isn't
public class ExploreLandScanTest {

    private final LandMemory land = new LandMemory(null);
    private final Set<ChunkPos> cached = new HashSet<>();
    private final Set<ChunkPos> regionsLoading = new HashSet<>();

    private final IChunkFilter filter = new IChunkFilter() {
        @Override
        public Status isAlreadyExplored(int x, int z) {
            ChunkPos pos = new ChunkPos(x, z);
            if (regionsLoading.contains(pos)) {
                return Status.UNKNOWN;
            }
            return cached.contains(pos) ? Status.EXPLORED : Status.NOT_EXPLORED;
        }

        @Override
        public int countRemain() {
            return Integer.MAX_VALUE;
        }
    };

    private FrontierScan scan(int want) {
        return ExploreProcess.scanFrontier(0, 0, want, land, filter);
    }

    private void land(int x, int z) {
        LandMemoryTestAccess.note(land, x, z, 0, 9);
    }

    private void water(int x, int z) {
        LandMemoryTestAccess.note(land, x, z, 9, 9);
    }

    @Test
    public void noLandMeansNoFrontierAndNoHang() {
        FrontierScan scan = scan(10);
        assertTrue(scan.found().isEmpty());
        assertFalse(scan.awaiting());
    }

    @Test
    public void takesFewerThanItWantedInsteadOfGoingOn() {
        // one land chunk, four unseen sides. 10 wanted, the old loop would walk the universe for the other 6
        land(0, 0);
        FrontierScan scan = scan(10);
        assertEquals(4, scan.found().size());
    }

    @Test
    public void doesNotLeakToTheRingBehindTheFrontier() {
        // land out to x = 3, water everywhere around it, one unseen chunk at the east end
        for (int x = 0; x <= 3; x++) {
            land(x, 0);
            water(x, 1);
            water(x, -1);
        }
        water(-1, 0);
        FrontierScan scan = scan(10);
        assertEquals(java.util.List.of(new ChunkPos(4, 0)), scan.found());
    }

    @Test
    public void waterEverywhereIsNoFrontier() {
        land(0, 0);
        water(1, 0);
        water(-1, 0);
        water(0, 1);
        water(0, -1);
        assertTrue(scan(10).found().isEmpty());
    }

    @Test
    public void unknownChunksBehindWaterStayOut() {
        water(0, 0);
        water(1, 0);
        assertTrue(scan(10).found().isEmpty());
    }

    @Test
    public void banThenExhaustionIsNoFrontier() {
        land(0, 0);
        water(1, 0);
        water(-1, 0);
        water(0, -1);
        assertEquals(java.util.List.of(new ChunkPos(0, 1)), scan(10).found());
        assertEquals(ScanOutcome.GO, ScanOutcome.of(scan(10), 0));
        land.ban(new ChunkPos(0, 1));
        FrontierScan empty = scan(10);
        assertTrue(empty.found().isEmpty());
        // the first empty one could be a world still loading, the second is a crossing and never a pause
        assertEquals(ScanOutcome.EMPTY, ScanOutcome.of(empty, 0));
        assertEquals(ScanOutcome.CROSS, ScanOutcome.of(empty, 1));
    }

    @Test
    public void aLoadingRegionIsNeverACrossing() {
        land(0, 0);
        regionsLoading.add(new ChunkPos(1, 0));
        regionsLoading.add(new ChunkPos(-1, 0));
        regionsLoading.add(new ChunkPos(0, 1));
        regionsLoading.add(new ChunkPos(0, -1));
        assertEquals(ScanOutcome.AWAIT, ScanOutcome.of(scan(10), 5));
    }

    @Test
    public void aBannedChunkDoesNotCloseTheRest() {
        land(0, 0);
        land.ban(new ChunkPos(1, 0));
        assertEquals(3, scan(10).found().size());
    }

    @Test
    public void landWeCanSeeAcrossARiverKeepsItsFrontier() {
        land(0, 0);
        water(1, 0);
        land(2, 0);
        // the river chunk itself is seen, the far bank's far side is fair game
        assertTrue(scan(10).found().contains(new ChunkPos(3, 0)));
        assertFalse(scan(10).found().contains(new ChunkPos(1, 0)));
    }

    @Test
    public void anEarlierSessionsChunksCountAsLand() {
        // cached from before, never loaded this session. the scan walks through them to the real edge
        land(0, 0);
        water(-1, 0);
        water(0, 1);
        water(0, -1);
        cached.add(new ChunkPos(1, 0));
        cached.add(new ChunkPos(2, 0));
        FrontierScan scan = scan(10);
        assertTrue(scan.found().contains(new ChunkPos(3, 0)));
    }

    @Test
    public void anOldChunkTouchingWaterDoesNotLeak() {
        land(0, 0);
        water(-1, 0);
        water(0, 1);
        water(0, -1);
        water(2, 1);
        cached.add(new ChunkPos(1, 0));
        // (2,0) is next to the old chunk and has known water beside it, no walk out to it
        assertFalse(scan(10).found().contains(new ChunkPos(2, 0)));
    }

    @Test
    public void aLoadingRegionHoldsUpOnlyItself() {
        land(0, 0);
        regionsLoading.add(new ChunkPos(1, 0));
        FrontierScan scan = scan(10);
        assertTrue(scan.awaiting());
        assertEquals(3, scan.found().size());
    }

    @Test
    public void onlyLoadingRegionsIsAWaitNotAnEmptyAnswer() {
        land(0, 0);
        regionsLoading.add(new ChunkPos(1, 0));
        regionsLoading.add(new ChunkPos(-1, 0));
        regionsLoading.add(new ChunkPos(0, 1));
        regionsLoading.add(new ChunkPos(0, -1));
        FrontierScan scan = scan(10);
        assertTrue(scan.awaiting());
        assertTrue(scan.found().isEmpty());
    }

    @Test
    public void aReachThatKeepsGrowingStillEnds() {
        // everything is a frontier, everything is explored, every look pushes the land out one more
        ExploreProcess.LandGate runaway = new ExploreProcess.LandGate() {
            private int reach;

            @Override
            public boolean active() {
                return true;
            }

            @Override
            public boolean frontier(int chunkX, int chunkZ) {
                return true;
            }

            @Override
            public void explored(int chunkX, int chunkZ) {
                reach = Math.max(reach, Math.abs(chunkX) + Math.abs(chunkZ) + 1);
            }

            @Override
            public int reach(int originChunkX, int originChunkZ) {
                return reach;
            }

            @Override
            public net.minecraft.core.BlockPos startCrossing(int x, int z) {
                return null;
            }
        };
        IChunkFilter allExplored = new IChunkFilter() {
            @Override
            public Status isAlreadyExplored(int x, int z) {
                return Status.EXPLORED;
            }

            @Override
            public int countRemain() {
                return Integer.MAX_VALUE;
            }
        };
        FrontierScan scan = ExploreProcess.scanFrontier(0, 0, 10, runaway, allExplored);
        assertTrue(scan.found().isEmpty());
        assertFalse(scan.awaiting());
        assertTrue(runaway.reach(0, 0) <= ExploreProcess.FRONTIER_MAX_RING + 1);
    }

    @Test
    public void stopsAtTheRingThatFilledTheOrder() {
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                land(x, z);
            }
        }
        // the block's corners are out at ring 10, its edges start at ring 6, that's the first ring with anything on it
        FrontierScan scan = scan(1);
        assertEquals(4, scan.found().size());
        for (ChunkPos chunk : scan.found()) {
            assertEquals(6, Math.abs(chunk.x()) + Math.abs(chunk.z()));
        }
    }
}
