package baritone.behavior.trip;

import baritone.api.pathing.goals.GoalBlock;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

// the promise is "never ask twice in one trip, and the answer sticks". this is where that's checked instead of hoped for
public class TripBookTest {

    private static final GatherPlan STUCK_PLAN = new GatherPlan(Trip.Ask.STUCK, 10, 13, false);
    private static final GatherPlan FASTER_PLAN = new GatherPlan(Trip.Ask.FASTER, 10, 13, true);
    private static final String OVERWORLD = "overworld";

    @Test
    public void asksOncePerTripWhateverHappens() {
        TripBook book = new TripBook();
        Trip trip = book.start(new GoalBlock(1, 2, 3), OVERWORLD);
        assertNotNull(book.ask(trip.id, Trip.Ask.STUCK, STUCK_PLAN));
        // the bot replans constantly, every failed search would like to ask again
        assertNull(book.ask(trip.id, Trip.Ask.STUCK, STUCK_PLAN));
        assertNull(book.ask(trip.id, Trip.Ask.FASTER, FASTER_PLAN));
        book.answer(trip.id, true);
        assertNull(book.ask(trip.id, Trip.Ask.STUCK, STUCK_PLAN));
        assertEquals(1, book.current().prompts);
    }

    @Test
    public void fasterThenStuckIsStillOneQuestion() {
        TripBook book = new TripBook();
        Trip trip = book.start(new GoalBlock(1, 2, 3), OVERWORLD);
        assertNotNull(book.ask(trip.id, Trip.Ask.FASTER, FASTER_PLAN));
        book.answer(trip.id, false);
        assertNull(book.ask(trip.id, Trip.Ask.STUCK, STUCK_PLAN));
        assertEquals(Trip.Decision.NO, book.current().decision);
        assertEquals(Trip.Ask.FASTER, book.current().asked);
    }

    @Test
    public void yesSticksThroughGatherAndResume() {
        TripBook book = new TripBook();
        Trip trip = book.start(new GoalBlock(1, 2, 3), OVERWORLD);
        book.ask(trip.id, Trip.Ask.STUCK, STUCK_PLAN);
        assertEquals(Trip.Decision.YES, book.answer(trip.id, true).decision);
        Trip gathering = book.startGather(trip.id, STUCK_PLAN, 4);
        assertTrue(gathering.gathering);
        assertEquals(1, gathering.gathers);
        // no second gather while the first runs
        assertNull(book.startGather(trip.id, STUCK_PLAN, 4));
        Trip back = book.gatherDone(trip.id);
        assertFalse(back.gathering);
        assertEquals(Trip.Decision.YES, back.decision);
        // the resume is the same trip, the same goal object, the same id
        assertSame(trip.goal, book.current().goal);
        assertEquals(trip.id, book.current().id);
        // and a later re-gather doesn't ask
        assertNotNull(book.startGather(trip.id, STUCK_PLAN, 4));
        assertEquals(1, book.current().prompts);
    }

    @Test
    public void gathersAreCapped() {
        TripBook book = new TripBook();
        Trip trip = book.start(new GoalBlock(1, 2, 3), OVERWORLD);
        book.ask(trip.id, Trip.Ask.STUCK, STUCK_PLAN);
        book.answer(trip.id, true);
        int max = 1 + 3; // the first gather plus gatherMaxRetries
        for (int i = 0; i < max; i++) {
            assertNotNull("gather " + i, book.startGather(trip.id, STUCK_PLAN, max));
            book.gatherDone(trip.id);
        }
        assertNull(book.startGather(trip.id, STUCK_PLAN, max));
        assertEquals(Trip.Decision.NO, book.refuse(trip.id).decision);
    }

    @Test
    public void noSticksAndIsSaidOnce() {
        TripBook book = new TripBook();
        Trip trip = book.start(new GoalBlock(1, 2, 3), OVERWORLD);
        book.ask(trip.id, Trip.Ask.STUCK, STUCK_PLAN);
        book.answer(trip.id, false);
        assertNull(book.startGather(trip.id, STUCK_PLAN, 4));
        assertNotNull(book.tellNo(trip.id));
        assertNull(book.tellNo(trip.id));
        assertEquals(Trip.Decision.NO, book.current().decision);
    }

    @Test
    public void aFailedGatherIsANo() {
        TripBook book = new TripBook();
        Trip trip = book.start(new GoalBlock(1, 2, 3), OVERWORLD);
        book.ask(trip.id, Trip.Ask.STUCK, STUCK_PLAN);
        book.answer(trip.id, true);
        book.startGather(trip.id, STUCK_PLAN, 4);
        Trip refused = book.refuse(trip.id);
        assertEquals(Trip.Decision.NO, refused.decision);
        assertFalse(refused.gathering);
        assertNull(book.ask(trip.id, Trip.Ask.STUCK, STUCK_PLAN));
    }

    @Test
    public void aStaleClickDoesNothing() {
        TripBook book = new TripBook();
        Trip old = book.start(new GoalBlock(1, 2, 3), OVERWORLD);
        book.ask(old.id, Trip.Ask.STUCK, STUCK_PLAN);
        Trip fresh = book.start(new GoalBlock(4, 5, 6), OVERWORLD);
        book.ask(fresh.id, Trip.Ask.STUCK, STUCK_PLAN);
        assertNull(book.answer(old.id, true));
        assertEquals(Trip.Decision.PENDING, book.current().decision);
        assertEquals(fresh.id, book.current().id);
        assertNotNull(book.answer(fresh.id, false));
        // the second click on the same button
        assertNull(book.answer(fresh.id, true));
        assertEquals(Trip.Decision.NO, book.current().decision);
    }

    @Test
    public void endingIsFinal() {
        TripBook book = new TripBook();
        Trip trip = book.start(new GoalBlock(1, 2, 3), OVERWORLD);
        book.ask(trip.id, Trip.Ask.STUCK, STUCK_PLAN);
        assertSame(trip.id, book.end().id);
        assertNull(book.current());
        assertNull(book.answer(trip.id, true));
        assertNull(book.end());
        // ending somebody else's trip by id doesn't touch the new one
        Trip next = book.start(new GoalBlock(1, 2, 3), OVERWORLD);
        assertFalse(book.end(trip.id));
        assertSame(next, book.current());
        assertTrue(book.end(next.id));
    }

    @Test
    public void anEqualGoalFromSomebodyElseIsNotTheTrip() {
        TripBook book = new TripBook();
        GoalBlock mine = new GoalBlock(1, 2, 3);
        book.start(mine, OVERWORLD);
        // an altoclef task going for the same block makes its own goal object. that one never asks
        GoalBlock theirs = new GoalBlock(1, 2, 3);
        assertNull(book.forGoal(theirs));
        assertNotNull(book.forGoal(mine));
        assertNull(book.forGoal(null));
    }

    @Test
    public void fasterIsCheckedOnce() {
        TripBook book = new TripBook();
        Trip trip = book.start(new GoalBlock(1, 2, 3), OVERWORLD);
        assertNotNull(book.checkFaster(trip.id));
        assertNull(book.checkFaster(trip.id));
    }

    @Test
    public void idsNeverRepeat() {
        TripBook book = new TripBook();
        long a = book.start(new GoalBlock(1, 2, 3), OVERWORLD).id;
        book.end();
        long b = book.start(new GoalBlock(1, 2, 3), OVERWORLD).id;
        assertTrue(b != a);
    }

    @Test
    public void aGiveUpBeforeAskingIsQuietFromThenOn() {
        TripBook book = new TripBook();
        Trip trip = book.start(new GoalBlock(1, 2, 3), OVERWORLD);
        // the what-if found nothing worth asking about. that's the trip's answer, said once, never asked after
        assertEquals(Trip.Decision.NO, book.refuse(trip.id).decision);
        assertNotNull(book.tellNo(trip.id));
        assertNull(book.tellNo(trip.id));
        assertNull(book.ask(trip.id, Trip.Ask.STUCK, STUCK_PLAN));
        assertNull(book.ask(trip.id, Trip.Ask.FASTER, FASTER_PLAN));
        assertNull(book.refuse(trip.id));
    }

    @Test
    public void aStaleGatherCantFinishTheNextTrip() {
        TripBook book = new TripBook();
        Trip old = book.start(new GoalBlock(1, 2, 3), OVERWORLD);
        book.ask(old.id, Trip.Ask.STUCK, STUCK_PLAN);
        book.answer(old.id, true);
        assertNotNull(book.startGather(old.id, STUCK_PLAN, 4));
        Trip next = book.start(new GoalBlock(4, 5, 6), OVERWORLD);
        assertNull(book.gatherDone(old.id));
        assertNull(book.refuse(old.id));
        assertNull(book.tellNo(old.id));
        assertSame(next, book.current());
        // and the new trip isn't gathering, so a done for it is nothing too
        assertNull(book.gatherDone(next.id));
    }
}
