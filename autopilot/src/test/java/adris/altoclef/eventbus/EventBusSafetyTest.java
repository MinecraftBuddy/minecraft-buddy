package adris.altoclef.eventbus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

// a bad subscriber used to leave the bus locked and kill everybody after it, and deleted subscriptions never left
public class EventBusSafetyTest {

    static class Ping {
        final int n;

        Ping(int n) {
            this.n = n;
        }
    }

    private final List<Throwable> errors = new ArrayList<>();

    @Before
    public void setUp() {
        EventBus.clear();
        EventBus.setErrorHandler(errors::add);
    }

    @After
    public void tearDown() {
        EventBus.clear();
        EventBus.setErrorHandler(Throwable::printStackTrace);
    }

    @Test
    public void aThrowingSubscriberDoesNotStopTheOthers() {
        List<Integer> got = new ArrayList<>();
        EventBus.subscribe(Ping.class, e -> got.add(e.n));
        EventBus.subscribe(Ping.class, e -> {
            throw new IllegalStateException("boom");
        });
        EventBus.subscribe(Ping.class, e -> got.add(e.n * 10));
        EventBus.publish(new Ping(2));
        assertEquals(List.of(2, 20), got);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0) instanceof IllegalStateException);
    }

    @Test
    public void publishNeverThrowsEvenIfTheErrorHandlerDoes() {
        EventBus.setErrorHandler(t -> {
            throw new RuntimeException("handler broke too");
        });
        EventBus.subscribe(Ping.class, e -> {
            throw new IllegalStateException("boom");
        });
        EventBus.publish(new Ping(1));
    }

    @Test
    public void theLockIsResetAfterAThrowSoNewSubscribersLandRightAway() {
        EventBus.subscribe(Ping.class, e -> {
            throw new IllegalStateException("boom");
        });
        EventBus.publish(new Ping(1));
        // the old bus stayed locked here and parked this in the to-add list forever
        List<Integer> got = new ArrayList<>();
        EventBus.subscribe(Ping.class, e -> got.add(e.n));
        assertEquals(2, EventBus.subscriberCount(Ping.class));
        EventBus.publish(new Ping(5));
        assertEquals(List.of(5), got);
    }

    @Test
    public void unsubscribedOnesAreSweptOut() {
        List<Integer> got = new ArrayList<>();
        Subscription<Ping> a = EventBus.subscribe(Ping.class, e -> got.add(e.n));
        EventBus.subscribe(Ping.class, e -> got.add(e.n + 100));
        EventBus.publish(new Ping(1));
        EventBus.unsubscribe(a);
        EventBus.publish(new Ping(2));
        EventBus.publish(new Ping(3));
        assertEquals(List.of(1, 101, 102, 103), got);
        assertEquals(1, EventBus.subscriberCount(Ping.class));
    }

    @Test
    public void subscribingInsideASubscriberWaitsForTheEndOfThePublish() {
        List<Integer> got = new ArrayList<>();
        EventBus.subscribe(Ping.class, e -> {
            if (e.n == 1) {
                EventBus.subscribe(Ping.class, e2 -> got.add(e2.n));
            }
        });
        EventBus.publish(new Ping(1));
        assertTrue(got.isEmpty());
        EventBus.publish(new Ping(2));
        assertEquals(List.of(2), got);
    }

    @Test
    public void aSubscriberThatPublishesAgainDoesNotBreakTheLists() {
        List<Integer> got = new ArrayList<>();
        EventBus.subscribe(Ping.class, e -> {
            if (e.n == 1) {
                // nested publish, then a subscribe while the outer one is still going
                EventBus.publish(new Ping(10));
                EventBus.subscribe(Ping.class, e2 -> got.add(e2.n));
            }
        });
        EventBus.publish(new Ping(1));
        EventBus.publish(new Ping(2));
        assertEquals(List.of(2), got);
    }

    @Test
    public void clearDropsEverybody() {
        List<Integer> got = new ArrayList<>();
        EventBus.subscribe(Ping.class, e -> got.add(e.n));
        EventBus.clear();
        EventBus.publish(new Ping(1));
        assertTrue(got.isEmpty());
        assertEquals(0, EventBus.subscriberCount(Ping.class));
    }
}
