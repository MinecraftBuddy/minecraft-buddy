package adris.altoclef.trace;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VitalsTest {

    private static String plain(String cursorItem, int cursorCount, String task, int x, int z, Vitals.Gamer gamer) {
        return Vitals.format(17f, 0f, 14, 3.2f, 10, "iron_pickaxe", cursorItem, cursorCount, x, 64, z,
                "overworld", "UserTaskChain", task, gamer);
    }

    @Test
    public void theExactLineWithAGamerRun() {
        String line = plain(null, 0, "<CollectIron> mining", -12, 38,
                new Vitals.Gamer("IRON", 1, "iron_ingot", 4, 6, 12));
        assertEquals("vitals hp=17.0 abs=0.0 hunger=14 sat=3.2 armor=10 held=iron_pickaxe cursor=none pos=-12,64,38"
                + " dim=overworld chain=UserTaskChain task=\"<CollectIron> mining\" gamer=IRON att=1"
                + " head=iron_ingot x4 food=6/12", line);
    }

    @Test
    public void noGamerGroupWithoutAGamerRun() {
        String line = plain(null, 0, "<Idle>", 0, 0, null);
        assertFalse(line.contains("gamer="));
        assertFalse(line.contains("att="));
        assertFalse(line.contains("food="));
        assertTrue(line.endsWith("task=\"<Idle>\""));
    }

    @Test
    public void anEmptyCursorIsNone() {
        assertTrue(plain(null, 0, "t", 0, 0, null).contains(" cursor=none "));
        assertTrue(plain("", 5, "t", 0, 0, null).contains(" cursor=none "));
        assertTrue(plain("dirt", 0, "t", 0, 0, null).contains(" cursor=none "));
    }

    @Test
    public void aFullCursorIsItemAndCount() {
        assertTrue(plain("cobblestone", 12, "t", 0, 0, null).contains(" cursor=cobblestone x12 "));
    }

    @Test
    public void doubleQuotesAreStrippedFromTheTask() {
        String line = plain(null, 0, "<Mine \"iron\"> go \"now\"", 0, 0, null);
        assertTrue(line.contains("task=\"<Mine iron> go now\""));
        assertEquals(2, line.chars().filter(c -> c == '"').count());
    }

    @Test
    public void aNewlineInTheTaskDoesNotBreakTheLine() {
        assertFalse(plain(null, 0, "a\nb\r\nc", 0, 0, null).contains("\n"));
    }

    @Test
    public void numbersRoundToOneDecimal() {
        String line = Vitals.format(16.96f, 4.04f, 20, 0.05f, 0, "air", null, 0, 1, 2, 3, "nether", "c", "t", null);
        assertTrue(line.contains("hp=17.0 abs=4.0 hunger=20 sat=0.1 armor=0"));
    }

    @Test
    public void negativeCoordinatesKeepTheirSign() {
        String line = plain(null, 0, "t", -1000, -7, null);
        assertTrue(line.contains(" pos=-1000,64,-7 "));
    }

    @Test
    public void aGamerRunWithNoHeadSaysNone() {
        String line = plain(null, 0, "t", 0, 0, new Vitals.Gamer("NETHER", 3, null, 0, 0, 0));
        assertTrue(line.endsWith("gamer=NETHER att=3 head=none food=0/0"));
    }

    @Test
    public void pathAndStandComeLastSoOldGrepsStillWork() {
        String line = Vitals.format(17f, 0f, 14, 3.2f, 10, "iron_pickaxe", null, 0, 1, 64, 2, "overworld", "UserTaskChain",
                "<Idle>", new Vitals.Gamer("IRON", 1, "iron_ingot", 4, 6, 12), "live", 137);
        assertTrue(line.contains(" task=\"<Idle>\" gamer=IRON att=1 head=iron_ingot x4 food=6/12 path=live stand=137"));
        assertTrue(line.endsWith(" path=live stand=137"));
    }

    @Test
    public void wetComesLastAndOnlyWhenAsked() {
        String line = Vitals.format(1f, 0f, 1, 0f, 0, "air", null, 0, 0, 0, 0, "overworld", "c", "t", null, "none", 0, true);
        assertTrue(line.endsWith(" path=none stand=0 wet=true"));
        assertTrue(Vitals.format(1f, 0f, 1, 0f, 0, "air", null, 0, 0, 0, 0, "overworld", "c", "t", null, "none", 0, false).endsWith(" wet=false"));
        assertFalse(Vitals.format(1f, 0f, 1, 0f, 0, "air", null, 0, 0, 0, 0, "overworld", "c", "t", null, "none", 0).contains("wet="));
    }

    @Test
    public void noPathWordMeansNeitherFieldIsPrinted() {
        String line = Vitals.format(1f, 0f, 1, 0f, 0, "air", null, 0, 0, 0, 0, "overworld", "c", "t", null, null, 5);
        assertFalse(line.contains("path="));
        assertFalse(line.contains("stand="));
        assertTrue(Vitals.format(1f, 0f, 1, 0f, 0, "air", null, 0, 0, 0, 0, "overworld", "c", "t", null, "none", 0)
                .endsWith(" path=none stand=0"));
    }

    @Test
    public void missingWordsDoNotPrintNull() {
        String line = Vitals.format(1f, 0f, 1, 0f, 0, null, null, 0, 0, 0, 0, null, null, null, null);
        assertFalse(line.contains("null"));
        assertTrue(line.contains("held=none"));
        assertTrue(line.contains("chain=none"));
        assertTrue(line.endsWith("task=\"\""));
    }
}
