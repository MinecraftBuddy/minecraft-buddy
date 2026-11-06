package adris.altoclef.tasks.speedrun.gamer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.ItemFixture;
import java.util.List;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

// the pre-portal gate packs a crafting table only when the nether fallback (NetherGoldTask) could need one: no gold piece on or
// held, and not the gold for one either
public class PiglinTableGateTest {
    private static final List<KitNeed> TABLE = List.of(new KitNeed("crafting_table", 1));

    @BeforeClass
    public static void boot() {
        ItemFixture.boot();
    }

    @Test
    public void noGoldNoPieceNoTableAsksForATable() {
        assertEquals(TABLE, PiglinGold.tableForNether(new FakeFacts()));
        assertEquals(TABLE, PiglinGold.tableForNether(new FakeFacts().give(Items.GOLD_INGOT, 3)));
    }

    @Test
    public void aTableInTheBagOrOnTheGroundIsEnough() {
        assertTrue(PiglinGold.tableForNether(new FakeFacts().give(Items.CRAFTING_TABLE, 1)).isEmpty());
        FakeFacts placed = new FakeFacts();
        placed.tablePlaced = true;
        assertTrue(PiglinGold.tableForNether(placed).isEmpty());
    }

    @Test
    public void goldForAPieceMeansTheGateCraftsItAndNeedsNoTable() {
        // (the gate's own craft brings its table along, the nether is not where it is needed)
        assertTrue(PiglinGold.tableForNether(new FakeFacts().give(Items.GOLD_INGOT, 4)).isEmpty());
        assertTrue(PiglinGold.tableForNether(new FakeFacts().give(Items.GOLD_NUGGET, 36)).isEmpty());
        assertTrue(PiglinGold.tableForNether(new FakeFacts().give(Items.RAW_GOLD, 4)).isEmpty());
    }

    @Test
    public void aPieceOnOrInTheBagNeedsNoTable() {
        assertTrue(PiglinGold.tableForNether(new FakeFacts().give(Items.GOLDEN_HELMET, 1)).isEmpty());
        FakeFacts worn = new FakeFacts().give(Items.GOLDEN_BOOTS, 1);
        worn.worn.add(Items.GOLDEN_BOOTS);
        assertTrue(PiglinGold.tableForNether(worn).isEmpty());
    }

    @Test
    public void holdsPieceIsTheBagOnly() {
        assertTrue(PiglinGold.holdsPiece(new FakeFacts().give(Items.GOLDEN_LEGGINGS, 1)));
        assertFalse(PiglinGold.holdsPiece(new FakeFacts().give(Items.GOLD_INGOT, 9)));
    }
}
