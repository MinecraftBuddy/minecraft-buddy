package adris.altoclef.tasks.resources;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

// (Tuning stone_pick_first) the wooden pickaxe a task wants is a stone one once the bag holds the stone for it
public class SatisfyMiningRequirementStoneTest {

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void threeStoneMakeItAStonePickaxe() {
        assertFalse(SatisfyMiningRequirementTask.stoneInstead(0));
        assertFalse(SatisfyMiningRequirementTask.stoneInstead(2));
        assertTrue(SatisfyMiningRequirementTask.stoneInstead(3));
        assertTrue(SatisfyMiningRequirementTask.stoneInstead(64));
    }
}
