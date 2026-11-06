package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.StationHook.Kind;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

// plain alto's small book: what the placer reported and which station a task is working at, in memory, record only unless the one
// switch in StationHook is on
public class StationBookTest {
    private static final String OVERWORLD = "OVERWORLD";
    private static final String NETHER = "NETHER";
    private static final Function<BlockPos, StationBook.Standing> STANDS = p -> StationBook.Standing.STANDS;

    @Before
    @After
    public void clean() {
        StationHook.clear();
        StationHook.clearPlain();
        StationHook.plainAnswers(false);
    }

    @Test
    public void aStationToldTwiceIsOneEntryAndKeysOnKindDimensionAndPosition() {
        StationBook book = new StationBook();
        assertTrue(book.report(Kind.TABLE, new BlockPos(1, 64, 1), OVERWORLD, 100));
        assertFalse(book.report(Kind.TABLE, new BlockPos(1, 64, 1), OVERWORLD, 130));
        assertEquals("the first placed tick wins", 100, book.placedTick(Kind.TABLE, new BlockPos(1, 64, 1), OVERWORLD));
        assertTrue(book.report(Kind.FURNACE, new BlockPos(1, 64, 1), OVERWORLD, 100));
        assertTrue(book.report(Kind.TABLE, new BlockPos(1, 64, 1), NETHER, 100));
        assertTrue(book.report(Kind.TABLE, new BlockPos(2, 64, 1), OVERWORLD, 100));
        assertEquals(4, book.size());
    }

    @Test
    public void aLeaseIsPerStationAndLapses() {
        StationBook book = new StationBook();
        book.report(Kind.FURNACE, new BlockPos(4, 64, 0), OVERWORLD, 100);
        book.report(Kind.FURNACE, new BlockPos(30, 64, 0), OVERWORLD, 100);
        assertTrue(book.touch(Kind.FURNACE, new BlockPos(4, 64, 0), OVERWORLD, 200));
        assertFalse("a station the book never heard of", book.touch(Kind.FURNACE, new BlockPos(9, 64, 0), OVERWORLD, 200));
        assertTrue(book.leased(Kind.FURNACE, new BlockPos(4, 64, 0), OVERWORLD, 240, 40));
        assertFalse(book.leased(Kind.FURNACE, new BlockPos(4, 64, 0), OVERWORLD, 241, 40));
        assertFalse("the other furnace was not touched", book.leased(Kind.FURNACE, new BlockPos(30, 64, 0), OVERWORLD, 210, 40));
        assertFalse("a clock that went backwards holds nothing", book.leased(Kind.FURNACE, new BlockPos(4, 64, 0), OVERWORLD, 150, 40));
    }

    @Test
    public void nearestIsTheClosestStandingOneOfTheKindInThisDimensionWithinTheRadius() {
        StationBook book = new StationBook();
        book.report(Kind.TABLE, new BlockPos(15, 64, 0), OVERWORLD, 1);
        book.report(Kind.TABLE, new BlockPos(4, 64, 0), OVERWORLD, 1);
        book.report(Kind.TABLE, new BlockPos(2, 64, 0), NETHER, 1);
        book.report(Kind.FURNACE, new BlockPos(1, 64, 0), OVERWORLD, 1);
        assertEquals(new BlockPos(4, 64, 0), book.nearest(Kind.TABLE, OVERWORLD, 0.5, 64.5, 0.5, 21, p -> true, STANDS));
        assertEquals(new BlockPos(15, 64, 0), book.nearest(Kind.TABLE, OVERWORLD, 0.5, 64.5, 0.5, 21, p -> !p.equals(new BlockPos(4, 64, 0)), STANDS));
        assertNull(book.nearest(Kind.TABLE, OVERWORLD, 0.5, 64.5, 0.5, 3, p -> true, STANDS));
        assertEquals(new BlockPos(2, 64, 0), book.nearest(Kind.TABLE, NETHER, 0.5, 64.5, 0.5, 21, p -> true, STANDS));
        assertNull(book.nearest(Kind.SMOKER, OVERWORLD, 0.5, 64.5, 0.5, 128, p -> true, STANDS));
    }

    // gone for sure is forgotten on the spot, not known is left alone and not offered
    @Test
    public void aGoneStationIsForgottenAndAnUnknownOneIsNotOffered() {
        StationBook book = new StationBook();
        book.report(Kind.TABLE, new BlockPos(4, 64, 0), OVERWORLD, 1);
        book.report(Kind.TABLE, new BlockPos(8, 64, 0), OVERWORLD, 1);
        Function<BlockPos, StationBook.Standing> eyes = p -> p.getX() == 4 ? StationBook.Standing.UNKNOWN : StationBook.Standing.GONE;
        assertNull(book.nearest(Kind.TABLE, OVERWORLD, 0.5, 64.5, 0.5, 21, p -> true, eyes));
        assertTrue("unknown stays", book.has(Kind.TABLE, new BlockPos(4, 64, 0), OVERWORLD));
        assertFalse("gone goes", book.has(Kind.TABLE, new BlockPos(8, 64, 0), OVERWORLD));
        assertEquals(new BlockPos(4, 64, 0), book.nearest(Kind.TABLE, OVERWORLD, 0.5, 64.5, 0.5, 21, p -> true, STANDS));
    }

    @Test
    public void theBookIsCappedAndClearable() {
        StationBook book = new StationBook();
        for (int i = 0; i < StationBook.MAX + 10; i++) {
            book.report(Kind.TABLE, new BlockPos(i, 64, 0), OVERWORLD, i);
        }
        assertEquals(StationBook.MAX, book.size());
        assertFalse("the oldest went", book.has(Kind.TABLE, new BlockPos(0, 64, 0), OVERWORLD));
        assertTrue(book.has(Kind.TABLE, new BlockPos(StationBook.MAX + 9, 64, 0), OVERWORLD));
        book.clear();
        assertEquals(0, book.size());
    }

    // ---- through StationHook

    // the default: reports and leases are stored, and every question is answered as it was before the book existed
    @Test
    public void plainAltoRecordsAndAnswersNothingByDefault() {
        StationHook.reportPlaced(Kind.TABLE, new BlockPos(4, 64, 0), OVERWORLD, 100);
        StationHook.touch(Kind.TABLE, new BlockPos(4, 64, 0), OVERWORLD, 110);
        assertTrue(StationHook.plainBook().has(Kind.TABLE, new BlockPos(4, 64, 0), OVERWORLD));
        assertTrue(StationHook.plainBook().leased(Kind.TABLE, new BlockPos(4, 64, 0), OVERWORLD, 120, 40));
        assertNull(StationHook.ownedWithin(Kind.TABLE, OVERWORLD, 0.5, 64.5, 0.5, 128, p -> true, STANDS));
        assertNull(StationHook.standingNear(Kind.TABLE, 0.5, 64.5, 0.5));
        assertNull(StationHook.standingWithin(Kind.TABLE, 0.5, 64.5, 0.5, 128));
        assertEquals(0, StationHook.countWithin(Kind.TABLE, 0.5, 64.5, 0.5, 128, p -> true));
        assertFalse(StationHook.ours(new BlockPos(4, 64, 0)));
        assertFalse(StationHook.pickingUp(new BlockPos(4, 64, 0)));
        assertFalse(StationHook.pickingUp(Kind.TABLE));
        assertFalse("still not a gamer run", StationHook.installed());
        assertEquals(0, StationHook.cobbleOwed());
    }

    @Test
    public void withTheSwitchOnPlainAltoAnswersFromItsBook() {
        StationHook.reportPlaced(Kind.TABLE, new BlockPos(4, 64, 0), OVERWORLD, 100);
        StationHook.plainAnswers(true);
        assertEquals(new BlockPos(4, 64, 0), StationHook.ownedWithin(Kind.TABLE, OVERWORLD, 0.5, 64.5, 0.5, 21, p -> true, STANDS));
        assertNull(StationHook.ownedWithin(Kind.TABLE, NETHER, 0.5, 64.5, 0.5, 21, p -> true, STANDS));
        assertNull(StationHook.ownedWithin(Kind.FURNACE, OVERWORLD, 0.5, 64.5, 0.5, 21, p -> true, STANDS));
        assertNull(StationHook.ownedWithin(null, OVERWORLD, 0.5, 64.5, 0.5, 21, p -> true, STANDS));
        // everything else the hook answers is still as it was: it is not a gamer run
        assertFalse(StationHook.installed());
        assertNull(StationHook.standingNear(Kind.TABLE, 0.5, 64.5, 0.5));
        assertFalse(StationHook.ours(new BlockPos(4, 64, 0)));
    }

    @Test
    public void clearingTheWorldEmptiesTheBook() {
        StationHook.reportPlaced(Kind.TABLE, new BlockPos(4, 64, 0), OVERWORLD, 100);
        StationHook.clearPlain();
        assertEquals(0, StationHook.plainBook().size());
    }

    // a run's registry replaces the book: reports and touches go there, the book hears nothing, and the answer is the registry's
    @Test
    public void anInstalledSourceReplacesTheBook() {
        int[] heard = {0, 0};
        BlockPos at = new BlockPos(7, 64, 7);
        StationHook.plainAnswers(true);
        StationHook.install(new StationHook.Source() {
            @Override
            public BlockPos standingWithin(Kind kind, double x, double y, double z, double radius) {
                return at;
            }

            @Override
            public boolean pickingUp(Kind kind) {
                return false;
            }

            @Override
            public boolean pickingUp(BlockPos pos) {
                return false;
            }

            @Override
            public boolean ours(BlockPos pos) {
                return false;
            }

            @Override
            public boolean givenUp(BlockPos pos) {
                return false;
            }

            @Override
            public void placed(Kind kind, BlockPos pos, String dimension, long now) {
                heard[0]++;
            }

            @Override
            public void touch(Kind kind, BlockPos pos, String dimension, long now) {
                heard[1]++;
            }
        });
        StationHook.reportPlaced(Kind.TABLE, new BlockPos(4, 64, 0), OVERWORLD, 100);
        StationHook.touch(Kind.TABLE, new BlockPos(4, 64, 0), OVERWORLD, 101);
        assertEquals(1, heard[0]);
        assertEquals(1, heard[1]);
        assertEquals(0, StationHook.plainBook().size());
        assertEquals(at, StationHook.ownedWithin(Kind.TABLE, OVERWORLD, 0.5, 64.5, 0.5, 21, p -> true, p -> StationBook.Standing.GONE));
        assertTrue(StationHook.installed());
    }
}
