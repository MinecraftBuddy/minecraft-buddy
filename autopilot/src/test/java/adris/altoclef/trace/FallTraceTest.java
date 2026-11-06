package adris.altoclef.trace;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FallTraceTest {

    private static void push(FallTrace t, double y) {
        t.push(0, y, 0, 0, -0.5, 0, false, 0, (byte) 0, 0, 0, false, false, "lava", null, null, null, null, null, false, false, -1, 0, 0, 0);
    }

    @Test
    public void firesOncePerFall() {
        FallTrace t = new FallTrace();
        assertFalse(t.falling(true, 3));
        assertTrue(t.falling(true, 4.5f));
        for (int i = 0; i < 40; i++) {
            assertFalse(t.falling(true, 10 + i));
        }
    }

    @Test
    public void rearmsOnceFallDistanceResets() {
        FallTrace t = new FallTrace();
        assertTrue(t.falling(true, 5));
        for (int i = 0; i < 5; i++) {
            assertFalse(t.falling(true, 0));
        }
        assertTrue(t.falling(true, 5));
    }

    @Test
    public void quietOutsideTheNether() {
        FallTrace t = new FallTrace();
        assertFalse(t.falling(false, 30));
    }

    @Test
    public void keepsTheLastSixtyOldestFirst() {
        FallTrace t = new FallTrace();
        for (int i = 0; i < 100; i++) {
            push(t, i);
        }
        assertEquals(FallTrace.SIZE, t.size());
        assertTrue(t.line(0, o -> "m").contains("t-59 pos 0.00 40.00"));
        assertTrue(t.line(59, o -> "m").contains("t-0 pos 0.00 99.00"));
    }

    @Test
    public void hurtLineNamesTheAttacker() {
        FallTrace t = new FallTrace();
        t.push(1, 2, 3, 0, 0, 0, true, 0, (byte) (FallTrace.FORWARD | FallTrace.SPRINT), 90, 45, true, true, "netherrack", "mv", null, null, "goal", String.class, false, true, 77, 4, 5, 6);
        String line = t.line(0, o -> "ascend");
        assertTrue(line.startsWith("[falltrace]"));
        assertTrue(line.contains("keys WR"));
        assertTrue(line.contains("move ascend"));
        assertTrue(line.contains("HURT by #77 at 4.00 5.00 6.00"));
        assertTrue(line.contains("ground true vcoll below fall"));
        assertTrue(line.contains("look 90.00/45.00"));
        assertTrue(line.contains("goal goal input String HURT by"));
    }

    @Test
    public void verticalCollisionSaysWhichWay() {
        assertEquals("-", FallTrace.collWord(false, false));
        assertEquals("above", FallTrace.collWord(true, false));
        assertEquals("below", FallTrace.collWord(true, true));
    }

    // sneak gets its own letter so a slide off an edge shows whether shift was still held
    @Test
    public void sneakShowsAsC() {
        assertEquals("WC", FallTrace.keyWord((byte) (FallTrace.FORWARD | FallTrace.SNEAK)));
        assertEquals("WSADRJC", FallTrace.keyWord((byte) 127));
    }

    @Test
    public void lineSaysWhetherTheShieldWasUp() {
        FallTrace t = new FallTrace();
        t.push(0, 0, 0, 0, 0, 0, true, 0, (byte) 0, 0, 0, false, false, "stone", null, null, null, null, null, true, false, -1, 0, 0, 0);
        push(t, 1);
        assertTrue(t.line(0, o -> "m").endsWith("blocking true"));
        assertTrue(t.line(1, o -> "m").endsWith("blocking false"));
    }

    @Test
    public void carrySaysNothingUntilAsked() {
        FallTrace t = new FallTrace();
        push(t, 1);
        assertNull(t.carriedLine(o -> "m"));
    }

    // the numbers count up from the entry, and it stops by itself
    @Test
    public void carryPrintsTheNextFortyThenStops() {
        FallTrace t = new FallTrace();
        push(t, 1);
        t.carryOn();
        for (int i = 1; i <= FallTrace.AFTER_LAVA; i++) {
            push(t, 100 + i);
            String line = t.carriedLine(o -> "m");
            assertTrue(line, line.startsWith("[falltrace] t+" + i + " pos 0.00 " + (100 + i) + ".00"));
        }
        push(t, 500);
        assertNull(t.carriedLine(o -> "m"));
    }

    @Test
    public void aSecondEntryStartsTheCarryOver() {
        FallTrace t = new FallTrace();
        push(t, 1);
        t.carryOn();
        for (int i = 0; i < 30; i++) {
            push(t, 2);
            t.carriedLine(o -> "m");
        }
        t.carryOn();
        push(t, 3);
        assertTrue(t.carriedLine(o -> "m").startsWith("[falltrace] t+1 "));
    }

    @Test
    public void statusAndPlaceRideAlongAndDashWhenMissing() {
        FallTrace t = new FallTrace();
        t.push(0, 65, 0, 0, 0, 0, true, 0, (byte) FallTrace.SNEAK, 0, 0, false, false, "netherrack", "mv", "RUNNING", "AVOIDED", "goal", null, false, false, -1, 0, 0, 0);
        t.push(0, 64, 0, 0, -0.1, 0, false, 0, (byte) 0, 0, 0, false, false, "air", null, null, null, null, null, false, false, -1, 0, 0, 0);
        assertTrue(t.line(0, o -> "traverse").contains("keys C look 0.00/0.00 under netherrack move traverse status RUNNING place AVOIDED goal goal"));
        assertTrue(t.line(1, o -> "traverse").contains("move none status - place - goal none input -"));
    }
}
