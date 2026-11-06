package adris.altoclef.tasks.speedrun.gamer.end;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LandingLogTest {

    @Test
    public void aGoodLandingIsOneLineWithTheNumbers() {
        LandingLog log = new LandingLog();
        assertNull(log.tick(0, false, false, true, false, false, 200, 20));
        assertNull(log.tick(1, true, false, true, false, false, 200, 20));
        assertNull(log.tick(2, true, false, true, true, false, 200, 20));
        log.clicked();
        assertNull(log.tick(3, true, false, true, false, false, 150, 19.6));
        assertNull(log.tick(4, true, false, true, true, false, 150, 19.6));
        log.clicked();
        assertNull(log.tick(5, true, false, true, false, false, 100, 19.2));
        String line = log.tick(6, false, false, true, false, false, 90, 19.2);
        assertTrue(line, line.contains("started at the stand true"));
        assertTrue(line, line.contains("beds placed 2"));
        assertTrue(line, line.contains("clicks 2"));
        assertTrue(line, line.contains("200 -> 90"));
        assertTrue(line, line.contains("self damage 0.8"));
        assertFalse(line, line.contains("lost to"));
        // and only one
        assertNull(log.tick(7, false, false, true, false, false, 90, 19.2));
    }

    @Test
    public void aBedAlreadyThereIsNotPlacedAgain() {
        LandingLog log = new LandingLog();
        log.tick(1, true, false, true, true, false, 200, 20);
        assertTrue(log.finish().contains("beds placed 0"));
        assertNull(log.finish());
    }

    @Test
    public void healingIsNotTakenOffTheSelfDamageAndDamageBeforeTheFirstClickIsNotOurs() {
        LandingLog log = new LandingLog();
        log.tick(1, true, false, true, true, false, 200, 20);
        log.tick(2, true, false, true, true, false, 200, 15);
        log.clicked();
        log.tick(3, true, false, true, true, false, 150, 14);
        log.tick(4, true, false, true, true, false, 150, 16);
        log.tick(5, true, false, true, true, false, 150, 15);
        assertTrue(log.finish().contains("self damage 2.0"));
    }

    @Test
    public void aBlastLandingOnTheLastTickStillCounts() {
        LandingLog log = new LandingLog();
        log.tick(1, true, false, true, true, false, 200, 20);
        log.clicked();
        String line = log.tick(2, false, false, true, false, false, 150, 19.6);
        assertTrue(line, line.contains("self damage 0.4"));
        assertTrue(line, line.contains("200 -> 150"));
    }

    @Test
    public void aLostLandingSaysWhy() {
        assertEquals("breath", LandingLog.lostTo(true, false, true, true));
        assertEquals("never reached the stand", LandingLog.lostTo(false, false, true, false));
        assertEquals("sat early", LandingLog.lostTo(false, true, true, false));
        assertEquals("something else took over", LandingLog.lostTo(false, true, false, true));
        assertEquals("flew off", LandingLog.lostTo(false, true, false, false));
    }

    @Test
    public void theLandingEndingOnASitIsSatEarly() {
        LandingLog log = new LandingLog();
        log.tick(1, true, false, true, true, false, 200, 20);
        assertTrue(log.tick(2, false, true, true, true, false, 200, 20).contains("lost to sat early"));
    }

    @Test
    public void missedTicksAreAGap() {
        LandingLog log = new LandingLog();
        log.tick(1, true, false, true, true, false, 200, 20);
        log.tick(20, true, false, true, true, false, 200, 20);
        assertTrue(log.tick(21, false, false, true, true, false, 200, 20).contains("something else took over"));
    }

    @Test
    public void aNewLandingStartsClean() {
        LandingLog log = new LandingLog();
        log.tick(1, true, false, false, false, true, 200, 20);
        log.clicked();
        log.finish();
        log.tick(50, true, false, true, true, false, 120, 20);
        String line = log.finish();
        assertTrue(line, line.contains("started at the stand true"));
        assertTrue(line, line.contains("clicks 0"));
        assertTrue(line, line.contains("120 -> 120"));
        assertFalse(line, line.contains("breath"));
    }
}
