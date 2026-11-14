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
    // (kit_diamond_off_rock) look 98: the kit's diamond pickaxe and a wooden one in the bag, no stone or iron one; the chain kept
    // wood for last and dug the rock with the diamond one, 1561 uses to 0
    private static final ToolPick.Option DIAMOND = new ToolPick.Option(true, true, false, 8.0, false, false, 1561, 1000);
    private static final ToolPick.Option WOODEN = new ToolPick.Option(true, true, false, 2.0, false, true, 59, 50);
    private static final ToolPick.Option STONE_PICK = new ToolPick.Option(true, true, false, 4.0, false, false, 131, 100);
    private static final ToolPick.Option SWORD = new ToolPick.Option(true, true, false, 1.5, true, false, 1561, 1000);

    @Test
    public void theKitsDiamondPickaxeStaysOffTheRockWhileAnotherDigsIt() {
        List<ToolPick.Option> bag = List.of(DIAMOND, WOODEN);
        assertEquals(0, ToolPick.best(bag, 1.0, true, false));
        assertEquals(1, ToolPick.best(ToolPick.withoutKit(bag, List.of(true, false)), 1.0, true, false));
        // a stone one is first either way
        List<ToolPick.Option> stone = List.of(DIAMOND, STONE_PICK, WOODEN);
        assertEquals(1, ToolPick.best(ToolPick.withoutKit(stone, List.of(true, false, false)), 1.0, true, false));
    }

    @Test
    public void theKitsDiamondPickaxeDigsWhenNothingElseCan() {
        // alone, or with only a sword beside it
        List<ToolPick.Option> alone = List.of(DIAMOND);
        assertEquals(0, ToolPick.best(ToolPick.withoutKit(alone, List.of(true)), 1.0, true, false));
        List<ToolPick.Option> sword = List.of(SWORD, DIAMOND);
        assertEquals(1, ToolPick.best(ToolPick.withoutKit(sword, List.of(false, true)), 1.0, true, false));
    }
}
