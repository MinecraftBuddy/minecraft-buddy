package adris.altoclef.tasks.speedrun.gamer;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// the far rule's "is that need really over": gone from the list by name, for the soft hold, or a side job that ended
public class NeedEndTest {
    private static final KitNeed WOOL = new KitNeed("wool", 3);
    private static final KitNeed MORE_WOOL = new KitNeed("wool", 6);
    private static final KitNeed FOOD = new KitNeed(KitNeed.FOOD, 20);
    private static final KitNeed LOG = new KitNeed("log", 4);
    private static final long HOLD = FurnacePlan.SOFT_HOLD_TICKS;

    @Test
    public void nothingIsOverBeforeTheKitWorkedOnAnything() {
        NeedEnd e = new NeedEnd();
        assertFalse(e.done(List.of(WOOL), 0));
        assertFalse(e.done(List.of(), 1000));
    }

    @Test
    public void aNeedThatOnlyMovedDownTheListIsNotOver() {
        NeedEnd e = new NeedEnd();
        e.working(WOOL, 0);
        // the food gate jumps in front, the kit runs the food for a while
        for (long t = 1; t < 200; t++) {
            assertFalse(e.done(List.of(FOOD, WOOL), t));
            e.working(FOOD, t);
        }
        // bed wool turning into stock-up wool is the same sheep
        assertFalse(e.done(List.of(MORE_WOOL), 300));
    }

    @Test
    public void aNeedThatIsGoneIsOverAfterTheHold() {
        NeedEnd e = new NeedEnd();
        e.working(WOOL, 0);
        assertFalse(e.done(List.of(LOG), 100));
        // the kit gets on with the logs meanwhile, that does not replace the wool before the hold is out
        e.working(LOG, 100);
        assertFalse(e.done(List.of(LOG), 100 + HOLD - 1));
        assertTrue(e.done(List.of(LOG), 100 + HOLD));
        // and after it the logs are what we are on
        e.working(LOG, 100 + HOLD);
        assertFalse(e.done(List.of(LOG), 100 + HOLD + 1));
    }

    @Test
    public void aNeedThatDropsOutForATickIsNotOver() {
        NeedEnd e = new NeedEnd();
        e.working(WOOL, 0);
        assertFalse(e.done(List.of(LOG), 10));
        assertFalse(e.done(List.of(WOOL), 11));
        // the hold starts over
        assertFalse(e.done(List.of(LOG), 12));
        assertFalse(e.done(List.of(LOG), 12 + HOLD - 1));
        assertTrue(e.done(List.of(LOG), 12 + HOLD));
    }

    @Test
    public void aSideJobThatWasTheOutingIsOverOnceWhenItEnds() {
        NeedEnd e = new NeedEnd();
        // the village chest with no kit need running: its end is the end
        e.sideEnded();
        assertTrue(e.done(List.of(WOOL), 5));
        assertFalse(e.done(List.of(WOOL), 6));
        e.sideEnded();
        e.reset();
        assertFalse(e.done(List.of(LOG), 1000));
    }

    @Test
    public void aSideJobThatCutIntoTheWoolEndingIsNotTheWoolEnding() {
        NeedEnd e = new NeedEnd();
        e.working(WOOL, 0);
        // a bed punched (or a coal detour) half way through the shearing: back to the sheep, not home
        e.sideEnded();
        assertFalse(e.done(List.of(WOOL, LOG), 5));
        assertFalse(e.done(List.of(WOOL, LOG), 6));
        // ...but if the wool got done meanwhile, the side job ending is the end of both
        e.sideEnded();
        assertTrue(e.done(List.of(LOG), 7));
    }
}
