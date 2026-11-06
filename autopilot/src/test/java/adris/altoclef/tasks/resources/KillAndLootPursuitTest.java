package adris.altoclef.tasks.resources;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class KillAndLootPursuitTest {
    @Test
    public void aDeadTargetIsNotPursuedAnymore() {
        assertEquals(7, KillAndLootTask.pursuedId(true, true, 7));
        // the kill walk still holds it, but nobody is ticking the kill walk while we wander
        assertEquals(-1, KillAndLootTask.pursuedId(true, false, 7));
        assertEquals(-1, KillAndLootTask.pursuedId(false, false, -1));
    }
}
