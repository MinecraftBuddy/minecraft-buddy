package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GamerFacts;
import adris.altoclef.tasks.speedrun.gamer.RunState;
import adris.altoclef.tasks.speedrun.gamer.config.GamerConfig;
import adris.altoclef.util.helpers.ItemFixture;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RoomRetargetTest {
    @BeforeClass
    public static void bootstrap() {
        ItemFixture.boot();
    }

    // a flat world at y 63: every column is stone, except where the water test says otherwise. water is 4 deep
    private static final class World implements RoomRetarget.Probe {
        boolean unloaded;
        java.util.function.BiPredicate<Integer, Integer> water = (x, z) -> false;
        java.util.function.BiPredicate<Integer, Integer> loadedAt = (x, z) -> true;

        @Override
        public boolean loaded(int x, int z) {
            return !unloaded && loadedAt.test(x, z);
        }

        @Override
        public int topY(int x, int z) {
            return 63;
        }

        @Override
        public boolean solid(int x, int y, int z) {
            return y <= 63 && !(water.test(x, z) && y > 59);
        }

        @Override
        public boolean fluid(int x, int y, int z) {
            return water.test(x, z) && y > 59 && y <= 63;
        }
    }

    @Test
    public void nearNeedsBothSidewaysAndUp() {
        assertTrue(RoomRetarget.near(0, 60, 0, 30, 50, 30));
        // 201 blocks off is the ocean case
        assertFalse(RoomRetarget.near(0, 61, 0, 150, -29, 130));
        // right above it but 50 up, a dig first
        assertFalse(RoomRetarget.near(0, 60, 0, 3, 10, 3));
        assertTrue(RoomRetarget.near(0, 60, 0, 3, 48, 3));
        assertFalse(RoomRetarget.near(0, 60, 0, 49, 60, 0));
    }

    @Test
    public void approachProgressNeedsThreeBlocks() {
        assertTrue(RoomRetarget.progressed(Double.MAX_VALUE, 100));
        assertTrue(RoomRetarget.progressed(100, 97));
        assertFalse(RoomRetarget.progressed(100, 98.5));
        assertFalse(RoomRetarget.progressed(100, 102));
    }

    @Test
    public void dryFrameColumnIsTakenAsIs() {
        Optional<int[]> col = RoomRetarget.pickColumn(new World(), 100, 100, 200, 200);
        assertArrayEquals(new int[]{100, 100}, col.orElseThrow());
    }

    @Test
    public void oceanPicksTheNearestShore() {
        World w = new World();
        // water everywhere with x < 120
        w.water = (x, z) -> x < 120;
        int[] col = RoomRetarget.pickColumn(w, 100, 100, 100, 100).orElseThrow();
        assertEquals(120, col[0]);
        assertEquals(100, col[1]);
    }

    @Test
    public void iceOverWaterIsNotLand() {
        // top block solid but the dig column goes through water under it
        RoomRetarget.Probe w = new RoomRetarget.Probe() {
            public boolean loaded(int x, int z) {
                return true;
            }

            public int topY(int x, int z) {
                return 63;
            }

            public boolean solid(int x, int y, int z) {
                return y <= 63;
            }

            public boolean fluid(int x, int y, int z) {
                return x < 120 && y == 61;
            }
        };
        int[] col = RoomRetarget.pickColumn(w, 100, 100, 100, 100).orElseThrow();
        assertEquals(120, col[0]);
    }

    @Test
    public void lavaUnderTheTopRejects() {
        World w = new World();
        assertTrue(RoomRetarget.dry(w, 5, 5));
        RoomRetarget.Probe lava = new RoomRetarget.Probe() {
            public boolean loaded(int x, int z) {
                return true;
            }

            public int topY(int x, int z) {
                return 63;
            }

            public boolean solid(int x, int y, int z) {
                return true;
            }

            public boolean fluid(int x, int y, int z) {
                return y == 62;
            }
        };
        assertFalse(RoomRetarget.dry(lava, 5, 5));
    }

    @Test
    public void staysOffTheRingMiddle() {
        World w = new World();
        // the frame is on the ring middle itself: the pick backs off to 6 blocks
        int[] col = RoomRetarget.pickColumn(w, 100, 100, 100, 100).orElseThrow();
        assertTrue(Math.hypot(col[0] - 100, col[1] - 100) >= RoomRetarget.KEEP_OFF_RING);
        assertTrue(Math.hypot(col[0] - 100, col[1] - 100) < RoomRetarget.KEEP_OFF_RING + 1.5);
    }

    @Test
    public void unloadedOrAllWaterFindsNothing() {
        World none = new World();
        none.unloaded = true;
        assertTrue(RoomRetarget.pickColumn(none, 0, 0, 0, 0).isEmpty());
        World sea = new World();
        sea.water = (x, z) -> true;
        assertTrue(RoomRetarget.pickColumn(sea, 0, 0, 0, 0).isEmpty());
        // dry land beyond the search radius does not count
        World far = new World();
        far.water = (x, z) -> Math.abs(x) <= RoomRetarget.SEARCH_RADIUS && Math.abs(z) <= RoomRetarget.SEARCH_RADIUS;
        assertTrue(RoomRetarget.pickColumn(far, 0, 0, 0, 0).isEmpty());
    }

    @Test
    public void cornersOutsideTheRadiusAreNotPicked() {
        World w = new World();
        // only the far diagonal is dry: inside the square, outside the circle
        w.water = (x, z) -> !(x == 40 && z == 40);
        assertTrue(RoomRetarget.pickColumn(w, 0, 0, 0, 0).isEmpty());
    }

    @Test
    public void retargetWipesTheOldSearch() {
        RunState state = new RunState();
        state.strongholdStart = new RunState.Pos(1, 2, 3);
        state.roomChunksVisited.add("1,1");
        StrongholdRoomSearchTask task = new StrongholdRoomSearchTask(ctx(state));
        task.resetForRetarget(new RunState.Pos(40, 70, 50));
        assertEquals(40, state.strongholdStart.x);
        assertEquals(50, state.strongholdStart.z);
        assertTrue(state.roomChunksVisited.isEmpty());
        assertEquals(1, task.retargets());
        task.resetForRetarget(new RunState.Pos(41, 70, 50));
        assertEquals(2, task.retargets());
    }

    private static GamerContext ctx(RunState state) {
        return new GamerContext() {
            public GamerConfig cfg() {
                return new GamerConfig();
            }

            public RunState state() {
                return state;
            }

            public GamerFacts facts() {
                return null;
            }

            public int attempt() {
                return 1;
            }

            public double secondsInPhase() {
                return 0;
            }

            public void save() {
            }

            public void progress(String what) {
            }

            public void fail(String reason) {
            }

            public void log(String line) {
            }

            public void walkOnEndPortal(boolean on) {
            }
        };
    }
}
