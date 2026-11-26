package baritone.utils.pathing;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// (Tuning fireproof_blaze_avoid) the blazes leave the path's avoidance only with the switch on, fire resistance on the buddy
// and more than its last 30 s left; and what their spheres did to a step's cost at the camp below a spawner
public class AvoidanceBlazeTest {

    @Test
    public void blazesFreeOnlyUnderAFireResistanceThatHolds() {
        assertTrue(Avoidance.blazesFree(true, true, false));
        assertFalse("ending soon: the avoidance is back before the effect ends", Avoidance.blazesFree(true, true, true));
        assertFalse("no fire resistance", Avoidance.blazesFree(true, false, false));
        assertFalse("the switch off", Avoidance.blazesFree(false, true, false));
    }

    @Test
    public void sixBlazesAndTheSpawnerMakeAStepCost128Walks() {
        // the camp of heel-256 t8 (8739,55,1403), the spawner 7 east and 3 up, six blazes about it: each sphere doubles
        Long2DoubleOpenHashMap map = new Long2DoubleOpenHashMap();
        map.defaultReturnValue(1.0D);
        new Avoidance(new BlockPos(8746, 58, 1403), 2.0D, 16).applySpherical(map);
        // all within 8 of the step at 8740,55,1403
        int[][] blazes = {{8744, 59, 1401}, {8745, 60, 1405}, {8746, 58, 1402}, {8743, 58, 1404}, {8745, 59, 1404}, {8742, 59, 1403}};
        for (int[] b : blazes) {
            new Avoidance(new BlockPos(b[0], b[1], b[2]), 2.0D, 8).applySpherical(map);
        }
        assertEquals(128.0D, map.get(BetterBlockPos.longHash(8740, 55, 1403)), 1e-9);

        // the same without the blazes (fire resistance holding, the switch on): the spawner's own factor alone
        Long2DoubleOpenHashMap spawnerOnly = new Long2DoubleOpenHashMap();
        spawnerOnly.defaultReturnValue(1.0D);
        new Avoidance(new BlockPos(8746, 58, 1403), 2.0D, 16).applySpherical(spawnerOnly);
        assertEquals(2.0D, spawnerOnly.get(BetterBlockPos.longHash(8740, 55, 1403)), 1e-9);
    }
}
