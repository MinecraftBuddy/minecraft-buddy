/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package adris.altoclef.util.helpers;

import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class ItemHelperCombatTest {

    @BeforeClass
    public static void bootstrap() {
        // items carry their attribute modifiers as components, which need the registries
        ItemFixture.boot();
    }

    @Test
    public void swordDamageMatchesVanilla() {
        // vanilla 1.21.4 hit values: fists 1, then 4/5/6/7/8, gold is wood with a nicer paint job
        assertEquals(1f, ItemHelper.getAttackDamage(Items.STICK), 1e-6);
        assertEquals(4f, ItemHelper.getAttackDamage(Items.WOODEN_SWORD), 1e-6);
        assertEquals(4f, ItemHelper.getAttackDamage(Items.GOLDEN_SWORD), 1e-6);
        assertEquals(5f, ItemHelper.getAttackDamage(Items.STONE_SWORD), 1e-6);
        assertEquals(6f, ItemHelper.getAttackDamage(Items.IRON_SWORD), 1e-6);
        assertEquals(7f, ItemHelper.getAttackDamage(Items.DIAMOND_SWORD), 1e-6);
        assertEquals(8f, ItemHelper.getAttackDamage(Items.NETHERITE_SWORD), 1e-6);
    }

    @Test
    public void nonSwordsAreNotSubtractedLikeTheyAreSwords() {
        // the old helper took 3 off swords only, so an axe came out as its full 6 and a sword as bonus-only
        assertEquals(7f, ItemHelper.getAttackDamage(Items.WOODEN_AXE), 1e-6);
        assertEquals(10f, ItemHelper.getAttackDamage(Items.NETHERITE_AXE), 1e-6);
    }

    @Test
    public void attackSpeedDoesNotLeakIntoDamage() {
        // swords carry an attack speed modifier right next to the damage one, only the damage one counts
        assertNotEquals(4f - 2.4f, ItemHelper.getAttackDamage(Items.WOODEN_SWORD), 1e-6);
    }

    @Test
    public void bestSwordIsTheStrongestNotTheLastOne() {
        // the bug: walking netherite -> wood and overwriting ended on the worst one owned
        List<Item> owned = List.of(Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.WOODEN_SWORD);
        assertSame(Items.DIAMOND_SWORD, ItemHelper.getBestSword(owned));
        assertSame(Items.NETHERITE_SWORD, ItemHelper.getBestSword(List.of(Items.WOODEN_SWORD, Items.NETHERITE_SWORD, Items.STONE_SWORD)));
    }

    @Test
    public void bestSwordIgnoresOrderAndNonSwords() {
        assertSame(Items.STONE_SWORD, ItemHelper.getBestSword(List.of(Items.DIAMOND_PICKAXE, Items.STONE_SWORD, Items.NETHERITE_AXE)));
        assertSame(Items.IRON_SWORD, ItemHelper.getBestSword(List.of(Items.WOODEN_SWORD, Items.IRON_SWORD)));
        assertSame(Items.IRON_SWORD, ItemHelper.getBestSword(List.of(Items.IRON_SWORD, Items.WOODEN_SWORD)));
    }

    @Test
    public void bestSwordIsNullWithoutSwords() {
        assertNull(ItemHelper.getBestSword(List.of()));
        assertNull(ItemHelper.getBestSword(List.of(Items.STICK, Items.DIAMOND_PICKAXE)));
    }

    @Test
    public void tiesKeepTheFirstOne() {
        // the kill tasks put the hand item first so two equal swords never get swapped
        assertSame(Items.WOODEN_SWORD, ItemHelper.getBestSword(List.of(Items.WOODEN_SWORD, Items.GOLDEN_SWORD)));
        assertSame(Items.GOLDEN_SWORD, ItemHelper.getBestSword(List.of(Items.GOLDEN_SWORD, Items.WOODEN_SWORD)));
    }
}
