package adris.altoclef.util.helpers;

import adris.altoclef.tasks.speedrun.gamer.KitPlanner;
import adris.altoclef.util.helpers.WeaponPick.Candidate;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ToolWearTest {
    @BeforeClass
    public static void bootstrap() {
        ItemFixture.boot();
    }

    @Test
    public void theLineIsEightyFivePercentOfTheDurability() {
        assertEquals(0.85, ToolWear.WORN_FRACTION, 0);
        // a stone pick has 131 uses: 111 damage is fine, 112 is past the line
        assertFalse(ToolWear.wornOut(111, 131));
        assertTrue(ToolWear.wornOut(112, 131));
        // standing exactly on the line counts as worn
        assertFalse(ToolWear.wornOut(84, 100));
        assertTrue(ToolWear.wornOut(85, 100));
        assertTrue(ToolWear.wornOut(100, 100));
        // an item with no durability never wears out, whatever its damage reads
        assertFalse(ToolWear.wornOut(0, 0));
        assertFalse(ToolWear.wornOut(5, 0));
        assertFalse(ToolWear.wornOut(0, 131));
    }

    // the weapon pick only shows its wear reading by what it prefers: a sword beats any axe, so the fresh axe being picked
    // over the sword means the sword read as worn
    private static boolean weaponPickCallsItWorn(int damage, int maxDamage) {
        Candidate sword = new Candidate(Items.IRON_SWORD, damage, maxDamage);
        Candidate axe = new Candidate(Items.IRON_AXE, 0, 250);
        return WeaponPick.best(List.of(sword, axe)) == Items.IRON_AXE;
    }

    @Test
    public void theKitPlannerAndTheWeaponPickAgreeOnEveryDamageOfEveryDurability() {
        // wooden pick, stone pick, iron pick, diamond, netherite, and the odd small numbers that make a rounding slip show
        for (int max : new int[]{1, 2, 7, 59, 100, 131, 250, 1561, 2031}) {
            for (int damage = 0; damage <= max; damage++) {
                String at = damage + " of " + max;
                boolean line = ToolWear.wornOut(damage, max);
                assertEquals("kit planner at " + at, line, KitPlanner.wornOut(Items.STONE_PICKAXE, damage, max));
                assertEquals("weapon pick at " + at, line, weaponPickCallsItWorn(damage, max));
                // and the tool side of the weapon pick drops a worn tool from the list the same way
                Candidate pick = new Candidate(Items.IRON_PICKAXE, damage, max);
                if (line) {
                    assertNull("tool list at " + at, WeaponPick.bestTool(List.of(pick)));
                } else {
                    assertSame("tool list at " + at, Items.IRON_PICKAXE, WeaponPick.bestTool(List.of(pick)));
                }
            }
        }
    }

    @Test
    public void theKitPlannerOnlyJudgesTheThreePicksItMakes() {
        // same line, narrower question: it only keeps count of its own wooden, stone and iron picks
        for (var pick : List.of(Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE)) {
            assertTrue(pick.toString(), KitPlanner.wornOut(pick, 90, 100));
            assertFalse(pick.toString(), KitPlanner.wornOut(pick, 80, 100));
        }
        for (var other : List.of(Items.DIAMOND_PICKAXE, Items.IRON_AXE, Items.STONE_SWORD, Items.STICK)) {
            assertFalse(other.toString(), KitPlanner.wornOut(other, 99, 100));
        }
    }
}
