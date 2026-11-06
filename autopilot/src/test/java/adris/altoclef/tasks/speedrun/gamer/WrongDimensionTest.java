package adris.altoclef.tasks.speedrun.gamer;

import baritone.api.utils.Dimension;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WrongDimensionTest {
    @Test
    public void overworldPhasesFireInTheNether() {
        for (GamerPhase p : new GamerPhase[]{GamerPhase.GATHER, GamerPhase.IRON, GamerPhase.LOCATE, GamerPhase.ROOM, GamerPhase.OPEN, GamerPhase.END_PREP}) {
            assertTrue(p.name(), WrongDimension.fires(p, Dimension.NETHER));
        }
    }

    @Test
    public void legitNetherPhasesNeverFire() {
        for (GamerPhase p : new GamerPhase[]{GamerPhase.PORTAL, GamerPhase.NETHER, GamerPhase.EYES, GamerPhase.RETURN, GamerPhase.DRAGON}) {
            assertFalse(p.name(), WrongDimension.fires(p, Dimension.NETHER));
        }
    }

    @Test
    public void onlyTheNetherCounts() {
        for (GamerPhase p : GamerPhase.values()) {
            assertFalse(WrongDimension.fires(p, Dimension.OVERWORLD));
            assertFalse(WrongDimension.fires(p, Dimension.END));
        }
    }

    @Test
    public void sixOverworldPhases() {
        assertEquals(6, Arrays.stream(GamerPhase.values()).filter(WrongDimension::overworldPhase).count());
    }
}
