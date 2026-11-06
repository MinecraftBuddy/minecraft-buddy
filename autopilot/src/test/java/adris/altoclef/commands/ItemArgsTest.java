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

package adris.altoclef.commands;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import baritone.api.utils.Dimension;
import java.util.List;
import org.junit.Test;

// the pair grammar and its completion. no minecraft needed, which is the whole reason ItemArgs is its own class
public class ItemArgsTest {

    private static final List<String> NAMES = List.of(
            "armor", "diamond", "diamond_pickaxe", "iron_helmet", "iron_ingot", "log", "planks", "stick");

    private static List<String> entries(String rest) throws ItemArgs.ParseException {
        return ItemArgs.parse(rest).stream().map(e -> e.name() + "x" + e.count()).toList();
    }

    private static List<String> complete(String rest) {
        return ItemArgs.complete(rest, NAMES, List.of()).toList();
    }

    @Test
    public void pairsWithAndWithoutCounts() throws Exception {
        assertEquals(List.of("iron_ingotx3", "diamondx2"), entries("iron_ingot 3 diamond 2"));
        assertEquals(List.of("logx1"), entries("log"));
        assertEquals(List.of("logx1", "stickx1"), entries("log stick"));
        assertEquals(List.of("logx16", "stickx1", "planksx4"), entries("log 16 stick planks 4"));
    }

    @Test
    public void bracketFormStillWorks() throws Exception {
        assertEquals(List.of("iron_ingotx3", "diamondx2"), entries("[iron_ingot 3, diamond 2]"));
        assertEquals(List.of("iron_ingotx3", "diamondx2"), entries("[iron_ingot 3,diamond 2]"));
        assertEquals(List.of("stickx1", "logx2"), entries("[stick, log 2]"));
    }

    @Test
    public void sameNameAddsUpAndKeepsOrder() throws Exception {
        assertEquals(List.of("stickx5", "logx1"), entries("stick 2 log stick 3"));
    }

    @Test
    public void namespaceAndCaseAreIgnored() throws Exception {
        assertEquals(List.of("iron_ingotx2"), entries("minecraft:IRON_INGOT 2"));
        assertEquals("iron_ingot", ItemArgs.normalize("Minecraft:Iron_Ingot"));
        // other namespaces are not ours to strip
        assertEquals("mod:thing", ItemArgs.normalize("mod:thing"));
    }

    @Test
    public void badInputIsRefused() {
        String[] bad = {"", "   ", "3", "3 iron_ingot", "iron_ingot 0", "iron_ingot -2", "iron_ingot 99999999999", "[]", "log 2 3"};
        for (String line : bad) {
            try {
                ItemArgs.parse(line);
                fail("should have refused: \"" + line + "\"");
            } catch (ItemArgs.ParseException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    @Test
    public void completesTheWordBeingTyped() {
        assertEquals(List.of("diamond", "diamond_pickaxe"), complete("dia"));
        assertEquals(List.of("iron_helmet", "iron_ingot"), complete("iron"));
        assertEquals(NAMES, complete(""));
        assertTrue(complete("nope").isEmpty());
    }

    @Test
    public void completesLaterWordsOfAList() {
        // after an item the next word is a count or another item, so items are on offer
        assertEquals(List.of("diamond", "diamond_pickaxe"), complete("iron_ingot dia"));
        assertEquals(List.of("stick"), complete("iron_ingot 3 st"));
        assertEquals(NAMES, complete("iron_ingot 3 "));
        assertEquals(NAMES, complete("iron_ingot "));
    }

    @Test
    public void nothingToCompleteForACount() {
        assertTrue(complete("iron_ingot 3").isEmpty());
        assertTrue(complete("iron_ingot 12").isEmpty());
        // two counts in a row, the command will refuse that anyway
        assertTrue(complete("iron_ingot 3 4 ").isEmpty());
        assertTrue(complete("3 ").isEmpty());
    }

    @Test
    public void completesInsideBrackets() {
        // vanilla swaps the whole word, so the bracket the user typed has to come back with the suggestion
        assertEquals(List.of("[diamond", "[diamond_pickaxe"), complete("[dia"));
        assertEquals(List.of("diamond", "diamond_pickaxe"), complete("[iron_ingot 3, dia"));
        assertEquals(List.of("stick"), complete("[iron_ingot 3,st"));
    }

    @Test
    public void completionIgnoresNamespaceAndCase() {
        assertEquals(List.of("iron_helmet", "iron_ingot"), complete("minecraft:Iron"));
    }

    @Test
    public void firstOnlyNamesAreOnlyTheFirstWord() {
        List<String> sets = List.of("iron", "diamond");
        assertEquals(List.of("iron", "iron_helmet", "iron_ingot"), ItemArgs.complete("iron", NAMES, sets).toList());
        assertEquals(List.of("iron_helmet", "iron_ingot"), ItemArgs.complete("log iron", NAMES, sets).toList());
    }

    @Test
    public void duplicatesAreNotSuggestedTwice() {
        assertEquals(List.of("iron_ingot"), ItemArgs.complete("iron_i", NAMES, List.of("iron_ingot")).toList());
    }

    @Test
    public void dimensionNames() {
        assertEquals(Dimension.OVERWORLD, CrossDimensionGoto.parseDimension("overworld"));
        assertEquals(Dimension.NETHER, CrossDimensionGoto.parseDimension("Nether"));
        assertEquals(Dimension.END, CrossDimensionGoto.parseDimension("the_end"));
        assertEquals(Dimension.END, CrossDimensionGoto.parseDimension("end"));
        assertNull(CrossDimensionGoto.parseDimension("diamond"));
        assertTrue(CrossDimensionGoto.endsInDimension(List.of("1", "2", "nether")));
        assertTrue(CrossDimensionGoto.endsInDimension(List.of("the_end")));
        assertTrue(!CrossDimensionGoto.endsInDimension(List.of("nether", "1")));
        assertTrue(!CrossDimensionGoto.endsInDimension(List.of()));
        for (String name : CrossDimensionGoto.DIMENSION_NAMES) {
            assertNotNull(name, CrossDimensionGoto.parseDimension(name));
        }
    }

    @Test
    public void gotoRefusesWhatItCannotUse() {
        // these all fail before any task is built, so they run without minecraft
        List<List<String>> bad = List.of(
                List.of(),
                List.of("~", "~", "~", "nether"),
                List.of("1", "2", "3", "4", "nether"),
                List.of("1", "x", "nether"));
        for (List<String> words : bad) {
            try {
                CrossDimensionGoto.parse(words);
                fail("should have refused: " + words);
            } catch (CrossDimensionGoto.ParseException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }
}
