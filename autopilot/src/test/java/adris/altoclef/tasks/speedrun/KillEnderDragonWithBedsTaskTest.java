package adris.altoclef.tasks.speedrun;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class KillEnderDragonWithBedsTaskTest {

    @Test
    public void aPlacedBedCountsAsOneLeft() {
        assertEquals(1, KillEnderDragonWithBedsTask.countBeds(0, true));
        assertEquals(0, KillEnderDragonWithBedsTask.countBeds(0, false));
        assertEquals(4, KillEnderDragonWithBedsTask.countBeds(3, true));
    }
}
