package adris.altoclef.world;

import adris.altoclef.world.StrongholdRoomPlan.Chunk;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StrongholdRoomPlanTest {
    private static int cheb(Chunk a, Chunk b) {
        return Math.max(Math.abs(a.cx() - b.cx()), Math.abs(a.cz() - b.cz()));
    }

    private static long d2(Chunk a, Chunk b) {
        long dx = a.cx() - b.cx();
        long dz = a.cz() - b.cz();
        return dx * dx + dz * dz;
    }

    @Test
    public void radiusZeroIsJustTheStart() {
        Chunk s = new Chunk(5, -9);
        assertEquals(List.of(s), StrongholdRoomPlan.spiralOrder(s, 0));
        assertEquals(List.of(s), StrongholdRoomPlan.spiralOrder(s, -3));
    }

    @Test
    public void spiralCoversTheWholeSquareOnceStartingAtTheStart() {
        for (Chunk start : new Chunk[]{new Chunk(0, 0), new Chunk(-114, 57), new Chunk(3, -3), new Chunk(-1, -1)}) {
            List<Chunk> order = StrongholdRoomPlan.spiralOrder(start, 7);
            assertEquals(225, order.size());
            assertEquals(start, order.get(0));
            Set<Chunk> unique = new HashSet<>(order);
            assertEquals(225, unique.size());
            for (Chunk c : order) {
                assertTrue(cheb(start, c) <= 7);
            }
        }
    }

    @Test
    public void spiralGoesRingByRingThenNearestFirst() {
        Chunk start = new Chunk(-20, 31);
        List<Chunk> order = StrongholdRoomPlan.spiralOrder(start, 7);
        for (int i = 1; i < order.size(); i++) {
            Chunk a = order.get(i - 1);
            Chunk b = order.get(i);
            int ra = cheb(start, a);
            int rb = cheb(start, b);
            assertTrue("ring never goes back at " + i, ra <= rb);
            if (ra == rb) {
                assertTrue("distance never goes back inside a ring at " + i, d2(start, a) <= d2(start, b));
            }
        }
        // ring one: the four edge neighbours (distance 1) before the four diagonals (distance 2)
        for (int i = 1; i <= 4; i++) {
            assertEquals(1, d2(start, order.get(i)));
        }
        for (int i = 5; i <= 8; i++) {
            assertEquals(2, d2(start, order.get(i)));
        }
    }

    @Test
    public void spiralIsDeterministicAndTheTieBreakIsZThenX() {
        Chunk start = new Chunk(0, 0);
        assertEquals(StrongholdRoomPlan.spiralOrder(start, 7), StrongholdRoomPlan.spiralOrder(start, 7));
        List<Chunk> order = StrongholdRoomPlan.spiralOrder(start, 3);
        // the first four neighbours all tie on ring and distance: smaller z first, then smaller x
        assertEquals(new Chunk(0, -1), order.get(1));
        assertEquals(new Chunk(-1, 0), order.get(2));
        assertEquals(new Chunk(1, 0), order.get(3));
        assertEquals(new Chunk(0, 1), order.get(4));
    }

    @Test
    public void nextFromTheStartIsTheStart() {
        Chunk s = new Chunk(10, 10);
        assertEquals(Optional.of(s), StrongholdRoomPlan.next(s, 7, Set.of(), s));
    }

    @Test
    public void nextIsTheNearestUnvisitedToThePlayer() {
        Chunk s = new Chunk(0, 0);
        Set<String> seen = new HashSet<>();
        seen.add(s.key());
        // player stands 5 east of the start: that chunk itself is free, it wins
        assertEquals(Optional.of(new Chunk(5, 0)), StrongholdRoomPlan.next(s, 7, seen, new Chunk(5, 0)));
        seen.add(new Chunk(5, 0).key());
        // now the neighbours of the player's chunk. all four edge neighbours tie on distance, the spiral order breaks it
        // and the one nearer the start (4,0) comes first in that
        assertEquals(Optional.of(new Chunk(4, 0)), StrongholdRoomPlan.next(s, 7, seen, new Chunk(5, 0)));
    }

    @Test
    public void nextFromOutsideTheSquareStillPicksAChunkInsideIt() {
        Chunk s = new Chunk(0, 0);
        Optional<Chunk> n = StrongholdRoomPlan.next(s, 7, Set.of(), new Chunk(40, 3));
        assertEquals(Optional.of(new Chunk(7, 3)), n);
        assertEquals(Optional.of(new Chunk(-7, -7)), StrongholdRoomPlan.next(s, 7, Set.of(), new Chunk(-100, -100)));
    }

    @Test
    public void walkingNextVisitsEveryChunkExactlyOnceThenStops() {
        Chunk start = new Chunk(-31, 12);
        Set<String> seen = new HashSet<>();
        Chunk player = start;
        for (int i = 0; i < 225; i++) {
            Optional<Chunk> n = StrongholdRoomPlan.next(start, 7, seen, player);
            assertTrue("ran dry early at " + i, n.isPresent());
            assertFalse("never hands out a visited chunk", seen.contains(n.get().key()));
            seen.add(n.get().key());
            player = n.get();
        }
        assertEquals(225, seen.size());
        assertTrue(StrongholdRoomPlan.next(start, 7, seen, player).isEmpty());
    }

    @Test
    public void visitedKeysOutsideTheSquareAreIgnored() {
        Chunk s = new Chunk(0, 0);
        Set<String> seen = new HashSet<>(Set.of(new Chunk(50, 50).key(), "garbage"));
        assertEquals(Optional.of(s), StrongholdRoomPlan.next(s, 7, seen, s));
    }

    @Test
    public void keyIsCxCommaCz() {
        assertEquals("-3,12", new Chunk(-3, 12).key());
    }
}
