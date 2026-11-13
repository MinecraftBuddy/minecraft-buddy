package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;

import adris.altoclef.util.helpers.ToolPick.RockOption;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

// (Tuning gold_rock_iron) the gold mine's rock goes to an iron pickaxe the kit can spare, else to the wear chain's stone one
public class ToolPickRockTest {

    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static RockOption iron(int left, boolean worn) {
        return new RockOption(true, true, worn, left);
    }

    private static final RockOption STONE = new RockOption(false, false, false, 100);

    @Test
    public void aFreeIronPickaxeDigsTheRockTheMostWornFirst() {
        // three unworn iron pickaxes, the kit keeps two: one is free, and the one with the fewest uses left goes
        List<RockOption> bag = List.of(STONE, iron(240, false), iron(120, false), iron(200, false));
        assertEquals(2, ToolPick.rockPick(bag, 1));
    }

    @Test
    public void noneFreeIsTheStonePickaxe() {
        // two unworn iron pickaxes (or one and the diamond one) and the kit keeps two: none is free
        List<RockOption> bag = List.of(STONE, iron(240, false), iron(120, false));
        assertEquals(-1, ToolPick.rockPick(bag, 0));
    }

    @Test
    public void aWornIronPickaxeIsNoLongerTheKitsAndDigsOn() {
        // worn past ToolWear's line the kit counts it no more, so it digs the rock until it is through
        List<RockOption> bag = List.of(STONE, iron(240, false), iron(30, true), iron(220, false));
        assertEquals(2, ToolPick.rockPick(bag, 0));
        assertEquals(2, ToolPick.rockPick(bag, 1));
    }

    @Test
    public void aPickaxeKeptBackOrNoIronIsNone() {
        // itemSaver keeps a nearly dead one back; no iron at all
        assertEquals(-1, ToolPick.rockPick(List.of(STONE, new RockOption(true, false, true, 5)), 1));
        assertEquals(-1, ToolPick.rockPick(List.of(STONE), 3));
    }
}
