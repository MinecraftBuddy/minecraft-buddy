package adris.altoclef.trackers;

import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

// the bookkeeping behind "don't wander until somebody has looked". strings stand in for blocks so no game is needed
public class ScanCoverageTest {

    @Test
    public void nothingIsCoveredBeforeTheFirstScan() {
        ScanCoverage<String> c = new ScanCoverage<>();
        assertFalse(c.coversAll(new String[]{"log"}));
        assertTrue(c.anyUncovered(Set.of("log")));
    }

    @Test
    public void aScanCoversWhatItWasSnapshottedWith() {
        ScanCoverage<String> c = new ScanCoverage<>();
        c.scanned(List.of("log", "coal"));
        assertTrue(c.coversAll(new String[]{"log", "coal"}));
        assertFalse(c.anyUncovered(Set.of("log", "coal")));
        // iron got tracked after the snapshot, the scan never saw it
        assertTrue(c.anyUncovered(Set.of("log", "coal", "iron")));
        assertFalse(c.coversAll(new String[]{"log", "iron"}));
    }

    @Test
    public void forgettingMakesTheNextTrackAFirstLookAgain() {
        ScanCoverage<String> c = new ScanCoverage<>();
        c.scanned(List.of("log"));
        c.forget("log");
        assertFalse(c.covers("log"));
        assertTrue(c.anyUncovered(Set.of("log")));
        c.scanned(List.of("log"));
        assertTrue(c.covers("log"));
    }

    @Test
    public void forgettingOneLeavesTheRest() {
        ScanCoverage<String> c = new ScanCoverage<>();
        c.scanned(List.of("log", "coal"));
        c.forget("log");
        assertTrue(c.covers("coal"));
        assertFalse(c.anyUncovered(Set.of("coal")));
    }

    @Test
    public void clearingForgetsEverything() {
        ScanCoverage<String> c = new ScanCoverage<>();
        c.scanned(List.of("log", "coal"));
        c.clear();
        assertFalse(c.covers("log"));
        assertFalse(c.covers("coal"));
    }

    @Test
    public void nothingTrackedMeansNothingToLookAt() {
        ScanCoverage<String> c = new ScanCoverage<>();
        assertFalse(c.anyUncovered(Set.of()));
        assertTrue(c.coversAll(new String[0]));
    }
}
