package adris.altoclef.tasks.container;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

// a smelt task that restarts (the pickup, a mob, a death) has empty caches. what it remembers of its furnace must not read as
// an empty furnace, that was 37 raw iron mined twice
public class StationMemoryTest {
    // 22:12:04 and 22:12:19: the furnace held 37 raw iron, the new task saw nothing and went mining
    @Test
    public void aRestartedLoadRemembersTheOreItAlreadyLoaded() {
        assertEquals(37, StationMemory.known(false, 0, 37), 0);
    }

    @Test
    public void whatTheScreenShowsBeatsTheMemory() {
        assertEquals(12, StationMemory.known(false, 12, 37), 0);
        // an open screen with nothing in the slot is a real zero, not a reason to trust a stale look
        assertEquals(0, StationMemory.known(true, 0, 37), 0);
    }

    @Test
    public void aFurnaceNeverSeenHoldsNothing() {
        assertEquals(0, StationMemory.known(false, 0, 0), 0);
        assertEquals(0, StationMemory.known(false, 0, -3), 0);
    }

    // the coal in the fuel slot is not coal to go and mine: 37 smelts needed, 48 already in
    @Test
    public void fuelAlreadyInTheFurnaceIsNotFuelToFind() {
        assertEquals(0, StationMemory.fuelStillNeeded(37, 48), 0);
        assertEquals(13, StationMemory.fuelStillNeeded(37, 24), 0);
        assertEquals(37, StationMemory.fuelStillNeeded(37, 0), 0);
    }
}
