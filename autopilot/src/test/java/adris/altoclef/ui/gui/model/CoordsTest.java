package adris.altoclef.ui.gui.model;

import adris.altoclef.ui.gui.facade.WorldAccess.Position;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CoordsTest {

    // x 120.5 floors to block 120, z -30.5 floors to block -31: the same block baritone would call "here"
    private static final Position P = new Position(120.5, 64.0, -30.5);

    @Test
    public void splitsEveryShapeOfPaste() {
        assertEquals(List.of("120", "64", "-30"), Coords.split("120 64 -30"));
        assertEquals(List.of("120", "64", "-30"), Coords.split("120, 64, -30"));
        assertEquals(List.of("120", "64", "-30"), Coords.split("120,64,-30"));
        assertEquals(List.of("120", "-30"), Coords.split("120 -30"));
        assertEquals(List.of("120", "64", "-30"), Coords.split("120/64/-30"));
        assertEquals(List.of("120", "64", "-30"), Coords.split("(120, 64, -30)"));
        assertEquals(List.of("120", "64", "-30"), Coords.split("  [120]  [64]\t[-30] "));
        assertEquals(List.of("120", "64", "-30"), Coords.split("x: 120 y: 64 z: -30"));
        assertEquals(List.of("~", "~5", "~-5"), Coords.split("~ ~5 ~-5"));
        assertEquals(List.of("12"), Coords.split("12"));
        assertTrue(Coords.split("").isEmpty());
        assertTrue(Coords.split(null).isEmpty());
        assertTrue(Coords.split(" ,, ").isEmpty());
    }

    @Test
    public void absoluteNumbers() {
        Coords.Resolved r = Coords.resolve("120", "64", "-30", P);
        assertTrue(r.ok());
        assertTrue(r.complete());
        assertEquals(120, (int) r.x());
        assertEquals(64, (int) r.y());
        assertEquals(-30, (int) r.z());
    }

    @Test
    public void relativeUsesTheBlockUnderThePlayer() {
        Coords.Resolved r = Coords.resolve("~", "~", "~", P);
        assertEquals(120, (int) r.x());
        assertEquals(64, (int) r.y());
        assertEquals(-31, (int) r.z());
        r = Coords.resolve("~10", "~-4", "~-10", P);
        assertEquals(130, (int) r.x());
        assertEquals(60, (int) r.y());
        assertEquals(-41, (int) r.z());
        r = Coords.resolve("~+5", "", "~5", P);
        assertEquals(125, (int) r.x());
        assertNull(r.y());
        assertEquals(-26, (int) r.z());
    }

    @Test
    public void decimalsFloorLikeBaritone() {
        Coords.Resolved r = Coords.resolve("5.9", "", "-5.5", P);
        assertEquals(5, (int) r.x());
        assertEquals(-6, (int) r.z());
        // ~0.5 from block 120 stays 120, ~-0.5 falls to 119
        assertEquals(120, (int) Coords.resolve("~0.5", "", "0", P).x());
        assertEquals(119, (int) Coords.resolve("~-0.5", "", "0", P).x());
        assertEquals(1500, (int) Coords.resolve("1.5k", "", "0", P).x());
        assertEquals(-2000000, (int) Coords.resolve("-2m", "", "0", P).x());
    }

    @Test
    public void blankMeansUnset() {
        Coords.Resolved r = Coords.resolve("12", "", "34", P);
        assertTrue(r.complete());
        assertNull(r.y());
        r = Coords.resolve("", "70", "", P);
        assertTrue(r.complete());
        assertNull(r.x());
        assertNull(r.z());
        assertEquals(70, (int) r.y());
        r = Coords.resolve(null, null, null, P);
        assertTrue(r.ok());
        assertFalse(r.complete());
        assertEquals("Enter x and z, or just a y level", r.missing());
    }

    @Test
    public void halfAnAnswerSaysWhatIsMissing() {
        Coords.Resolved r = Coords.resolve("12", "", "", P);
        assertTrue(r.ok());
        assertFalse(r.complete());
        assertEquals("Add a z to go with the x", r.missing());
        assertEquals(2, r.missingField());
        r = Coords.resolve("", "", "12", P);
        assertEquals("Add an x to go with the z", r.missing());
        assertEquals(0, r.missingField());
        r = Coords.resolve("12", "64", "", P);
        assertFalse(r.complete());
        assertEquals(2, r.missingField());
    }

    @Test
    public void mistakesNameTheBox() {
        Coords.Resolved r = Coords.resolve("12", "abc", "34", P);
        assertFalse(r.ok());
        assertEquals("Y needs a number or ~", r.error());
        assertEquals(1, r.errorField());
        assertEquals("X needs a number or ~", Coords.resolve("1-2", "", "3", P).error());
        assertEquals("Z needs a number or ~", Coords.resolve("1", "", "~~", P).error());
        assertEquals("X needs a number or ~", Coords.resolve("e5", "", "3", P).error());
        assertEquals("X needs a number or ~", Coords.resolve(".", "", "3", P).error());
        assertEquals("X needs a number or ~", Coords.resolve("-", "", "3", P).error());
    }

    @Test
    public void absurdNumbersAreTooFar() {
        Coords.Resolved r = Coords.resolve("99999999999", "", "0", P);
        assertEquals("X is too far. Stay within 30,000,000.", r.error());
        assertTrue(Coords.resolve("30000000", "", "-30000000", P).ok());
        assertFalse(Coords.resolve("30000001", "", "0", P).ok());
        // 100 blocks past the border via a relative offset from the edge
        Position edge = new Position(29_999_990, 64, 0);
        assertFalse(Coords.resolve("~100", "", "0", edge).ok());
    }

    @Test
    public void relativeNeedsAPlayer() {
        Coords.Resolved r = Coords.resolve("~5", "", "0", null);
        assertEquals("Join a world to use ~", r.error());
        assertTrue(Coords.resolve("5", "", "0", null).ok());
    }

    @Test
    public void halfTypedNegativesAreNotMistakesYet() {
        assertTrue(Coords.pending("-"));
        assertTrue(Coords.pending("~-"));
        assertTrue(Coords.pending("~+"));
        assertFalse(Coords.pending("~"));
        assertFalse(Coords.pending(""));
        assertFalse(Coords.pending("-5"));
        assertFalse(Coords.pending("abc"));
        assertNull(Coords.fieldError(0, "", P));
        assertNull(Coords.fieldError(0, "   ", P));
    }

    @Test
    public void tidyTurnsPastedDecimalsIntoWholeBlocks() {
        assertEquals("120", Coords.tidy("120.5"));
        assertEquals("-31", Coords.tidy("-30.5"));
        assertEquals("64", Coords.tidy("64.000"));
        assertEquals("~5", Coords.tidy("~5"));
        assertEquals("~", Coords.tidy("~"));
        assertEquals("abc", Coords.tidy("abc"));
        assertEquals("1500", Coords.tidy("1.5k"));
        // a relative decimal is the same block as its floor, and the box can only type whole numbers
        assertEquals("~1", Coords.tidy("~1.5"));
        assertEquals("~-2", Coords.tidy("~-1.5"));
        assertEquals("~5000", Coords.tidy("~5k"));
        assertEquals("~", Coords.tidy("~0.5"));
        assertEquals("1e3", Coords.tidy("1e3"));
    }

    @Test
    public void pasteMeansTwoOrThreeNumbers() {
        assertEquals(List.of("120", "64", "-30"), Coords.pasteParts("120 64 -30"));
        assertEquals(List.of("120", "-30"), Coords.pasteParts("120, -30"));
        assertEquals(List.of("~", "~5"), Coords.pasteParts("~ ~5"));
        assertTrue(Coords.pasteParts("hello world").isEmpty());
        assertTrue(Coords.pasteParts("120 abc").isEmpty());
        assertTrue(Coords.pasteParts("1 2 3 4").isEmpty());
        assertTrue(Coords.pasteParts("120").isEmpty());
        assertTrue(Coords.pasteParts("").isEmpty());
        assertTrue(Coords.pasteParts(null).isEmpty());
    }

    @Test
    public void pasteFindsTheNumbersInAnF3Line() {
        assertEquals(List.of("120.50", "64.00", "-30.50"),
                Coords.pasteParts("/execute in minecraft:overworld run tp @s 120.50 64.00 -30.50 0.00 0.00"));
        assertEquals(List.of("1", "2", "3"), Coords.pasteParts("base at 1 2 3 ok"));
        assertEquals(List.of("120", "-30"), Coords.pasteParts("home 120 -30"));
        assertEquals(List.of("120", "-30"), Coords.pasteParts("Coords: 120 -30"));
        assertEquals(List.of("120", "64", "-30"), Coords.pasteParts("Coords: 120, 64, -30 (overworld)"));
        // a lone number among words is not a position
        assertTrue(Coords.pasteParts("abc 1 def").isEmpty());
        assertTrue(Coords.pasteParts("hello 12").isEmpty());
    }

    @Test
    public void tokensAreWhatTheGrammarSays() {
        assertTrue(Coords.isToken("12"));
        assertTrue(Coords.isToken("-12"));
        assertTrue(Coords.isToken("~"));
        assertTrue(Coords.isToken("~-5"));
        assertTrue(Coords.isToken("1.5k"));
        assertFalse(Coords.isToken("1e3"));
        assertFalse(Coords.isToken("abc"));
        assertFalse(Coords.isToken("-"));
        assertFalse(Coords.isToken(""));
        assertFalse(Coords.isToken(null));
    }

    @Test
    public void yUsesTheBlockTheFeetAreIn() {
        // a path block is 0.9375 high: baritone's playerFeet still calls that block 64, not 63
        Position onPath = new Position(0.5, 63.9375, 0.5);
        assertEquals(64, Coords.block(1, onPath));
        assertEquals(64, (int) Coords.resolve("", "~", "", onPath).y());
        assertEquals(65, (int) Coords.resolve("", "~1", "", onPath).y());
        assertEquals(63, Coords.block(1, new Position(0, 63.8, 0)));
        assertEquals(-1, Coords.block(0, new Position(-0.5, 0, 0)));
        assertEquals(120, Coords.block(0, P));
        assertEquals(-31, Coords.block(2, P));
    }

    @Test
    public void distances() {
        Coords.Resolved r = Coords.resolve("120", "", "-30", P);
        // the middle of block 120, -30 is 0.0 east and 1.0 south of the player standing at 120.5, -30.5
        assertEquals(1.0, Coords.distance(r, P), 0.001);
        r = Coords.resolve("520", "", "-30", P);
        assertEquals(400.0, Coords.distance(r, P), 0.01);
        r = Coords.resolve("", "20", "", P);
        assertEquals(44.0, Coords.distance(r, P), 0.001);
        // y counts when it is given
        r = Coords.resolve("120", "164", "-30", P);
        assertEquals(Math.sqrt(100 * 100 + 1), Coords.distance(r, P), 0.001);
        assertEquals(-1, Coords.distance(Coords.resolve("1", "", "2", P), null), 0);
        assertEquals(-1, Coords.distance(Coords.resolve("1", "", "", P), P), 0);
    }

    @Test
    public void wordsForDistances() {
        assertEquals("0 blocks", Coords.blocks(0));
        assertEquals("1 block", Coords.blocks(0.6));
        assertEquals("412 blocks", Coords.blocks(411.7));
        assertEquals("12,400 blocks", Coords.blocks(12400));
        assertEquals("412 m", Coords.formatDistance(412.2));
        assertEquals("999 m", Coords.formatDistance(999.2));
        assertEquals("1.0 km", Coords.formatDistance(999.6));
        assertEquals("1.2 km", Coords.formatDistance(1234));
        assertEquals("48.9 km", Coords.formatDistance(48_900));
        assertEquals("120 km", Coords.formatDistance(120_000));
        assertEquals("", Coords.formatDistance(-1));
        assertEquals("the nether", Coords.dimensionName("the_nether"));
        assertEquals("overworld", Coords.dimensionName("overworld"));
    }
}
