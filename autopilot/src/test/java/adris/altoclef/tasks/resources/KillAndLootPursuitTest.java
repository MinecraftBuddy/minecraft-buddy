package adris.altoclef.tasks.resources;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KillAndLootPursuitTest {
    @Test
    public void aDeadTargetIsNotPursuedAnymore() {
        assertEquals(7, KillAndLootTask.pursuedId(true, true, 7));
        // the kill walk still holds it, but nobody is ticking the kill walk while we wander
        assertEquals(-1, KillAndLootTask.pursuedId(true, false, 7));
        assertEquals(-1, KillAndLootTask.pursuedId(false, false, -1));
    }

    @Test
    public void slimesAreDugForOnlyUnderTheirOwnConditions() {
        Class<?> slime = net.minecraft.world.entity.monster.cubemob.Slime.class;
        assertTrue(KillAndLootTask.slimeDigWanted(slime, true, true, true));
        // the switch off, ores shown to the client (a branch mine would be x-ray), the nether: the old wander
        assertFalse(KillAndLootTask.slimeDigWanted(slime, false, true, true));
        assertFalse(KillAndLootTask.slimeDigWanted(slime, true, false, true));
        assertFalse(KillAndLootTask.slimeDigWanted(slime, true, true, false));
        // any other mob is looked for on the ground as before
        assertFalse(KillAndLootTask.slimeDigWanted(net.minecraft.world.entity.monster.zombie.Zombie.class, true, true, true));
    }
}
