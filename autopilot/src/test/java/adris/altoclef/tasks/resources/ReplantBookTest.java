package adris.altoclef.tasks.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class ReplantBookTest {
    private final List<Integer> duds = new ArrayList<>();

    private ReplantBook<Integer> book(int budget) {
        return new ReplantBook<>(budget, duds::add);
    }

    // cells are just their distance from us
    private static double dist(Integer cell) {
        return cell * cell;
    }

    // a replant that actually runs every tick on whatever it picked
    private static void spend(ReplantBook<Integer> book, int from, int to) {
        for (int t = from; t <= to; t++) {
            book.pick(t, ReplantBookTest::dist, 100);
        }
    }

    @Test
    public void picksTheNearestHoleInRange() {
        ReplantBook<Integer> book = book(100);
        book.add(5);
        book.add(2);
        book.add(30);
        assertEquals(Integer.valueOf(2), book.pick(0, ReplantBookTest::dist, 100));
    }

    @Test
    public void nothingOutOfRangeIsPicked() {
        ReplantBook<Integer> book = book(100);
        book.add(30);
        assertNull(book.pick(0, ReplantBookTest::dist, 100));
        assertFalse(book.committed());
        assertTrue(book.any());
    }

    @Test
    public void staysOnItsHoleWhenAnotherOneGetsCloser() {
        ReplantBook<Integer> book = book(100);
        book.add(8);
        assertEquals(Integer.valueOf(8), book.pick(0, ReplantBookTest::dist, 100));
        book.add(1);
        assertEquals(Integer.valueOf(8), book.pick(1, ReplantBookTest::dist, 100));
    }

    @Test
    public void aCommittedHoleStaysEvenWhenItWalksOutOfRange() {
        ReplantBook<Integer> book = book(100);
        book.add(8);
        book.pick(0, ReplantBookTest::dist, 100);
        book.refresh(1, c -> true);
        assertTrue(book.committed());
        assertEquals(0, book.near(c -> 400, 100));
        assertEquals(Integer.valueOf(8), book.pick(2, c -> 400, 100));
    }

    @Test
    public void aPlantedOrTrampledHoleIsForgotten() {
        ReplantBook<Integer> book = book(100);
        book.add(1);
        book.add(2);
        book.pick(0, ReplantBookTest::dist, 100);
        // 1 got planted, 2 got trampled into dirt: neither is a hole anymore
        Set<Integer> stillHoles = new HashSet<>();
        book.refresh(1, stillHoles::contains);
        assertFalse(book.any());
        assertFalse(book.committed());
        assertTrue(duds.isEmpty());
    }

    @Test
    public void aHoleThatEatsItsWholeBudgetIsGivenUpOnForGood() {
        ReplantBook<Integer> book = book(100);
        book.add(1);
        book.add(4);
        spend(book, 0, 101);
        book.refresh(101, c -> true);
        assertFalse(book.committed());
        assertEquals(List.of(1), duds);
        // the next one gets its turn, and the dud can't sneak back in
        assertEquals(Integer.valueOf(4), book.pick(102, ReplantBookTest::dist, 100));
        book.add(1);
        book.refresh(103, c -> true);
        assertEquals(1, book.near(ReplantBookTest::dist, 100));
    }

    @Test
    public void everyHoleBeingADudEndsTheLoop() {
        ReplantBook<Integer> book = book(10);
        book.add(1);
        book.add(2);
        int now = 0;
        // the whole book, one dud at a time, then it is empty and replanting is over
        for (int i = 0; i < 2; i++) {
            assertTrue(book.pick(now, ReplantBookTest::dist, 100) != null);
            spend(book, now, now + 11);
            now += 12;
            book.refresh(now, c -> true);
        }
        assertFalse(book.any());
        assertNull(book.pick(now, ReplantBookTest::dist, 100));
        assertEquals(2, duds.size());
    }

    // time spent on other things (pickups, eating) with the replant not running is not time spent on the cell
    @Test
    public void tickGapsDoNotEatTheBudget() {
        ReplantBook<Integer> book = book(100);
        book.add(1);
        book.pick(0, ReplantBookTest::dist, 100);
        book.pick(500, ReplantBookTest::dist, 100);
        book.refresh(501, c -> true);
        assertTrue(book.committed());
        assertTrue(duds.isEmpty());
    }

    // the client predicts the plant, the server throws it out: the cell looks planted for a tick and then it is a hole again
    @Test
    public void aPlantThatKeepsReverting() {
        ReplantBook<Integer> book = book(1000);
        book.add(1);
        boolean[] planted = {false};
        int now = 0;
        for (int round = 0; round < 3; round++) {
            assertEquals(Integer.valueOf(1), book.pick(now, ReplantBookTest::dist, 100));
            planted[0] = true;
            book.refresh(++now, c -> !planted[0]);
            assertFalse(book.any());
            planted[0] = false;
            book.refresh(++now, c -> !planted[0]);
        }
        assertEquals(List.of(1), duds);
        assertFalse(book.any());
        assertNull(book.pick(now, ReplantBookTest::dist, 100));
    }

    @Test
    public void aPlantThatSticksIsNotWatchedForever() {
        ReplantBook<Integer> book = book(100);
        book.add(1);
        book.pick(0, ReplantBookTest::dist, 100);
        book.refresh(1, c -> false);
        book.refresh(200, c -> true);
        assertFalse(book.any());
        assertTrue(duds.isEmpty());
    }

    @Test
    public void aFreshPlantIsSettlingUntilItHasStuck() {
        ReplantBook<Integer> book = book(100);
        book.add(1);
        book.pick(0, ReplantBookTest::dist, 100);
        assertFalse(book.settling());
        book.refresh(1, c -> false);
        assertTrue(book.settling());
        book.refresh(100, c -> false);
        assertFalse(book.settling());
    }

    @Test
    public void releaseDropsTheWalkButKeepsTheHoles() {
        ReplantBook<Integer> book = book(100);
        book.add(3);
        book.pick(0, ReplantBookTest::dist, 100);
        book.release();
        assertFalse(book.committed());
        assertTrue(book.any());
    }
}
