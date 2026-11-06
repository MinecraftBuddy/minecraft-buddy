package adris.altoclef.butler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

// the format is user text, so brackets and dots in it have to stay literal
public class WhisperCheckerTest {

    @Test
    public void defaultFormatSplitsOnTheSpaces() {
        WhisperChecker.MessageResult r = WhisperChecker.tryParse("bot", WhisperChecker.DEFAULT_FORMAT, "jacob bot stop");
        assertEquals("jacob", r.from);
        assertEquals("stop", r.message);
    }

    @Test
    public void bracketsInTheFormatAreLiteral() {
        WhisperChecker.MessageResult r = WhisperChecker.tryParse("bot", "[{from} -> {to}] {message}", "[jacob -> bot] get iron_pickaxe");
        assertEquals("jacob", r.from);
        assertEquals("get iron_pickaxe", r.message);
    }

    @Test
    public void aWhisperToSomeoneElseIsRejected() {
        assertNull(WhisperChecker.tryParse("bot", "[{from} -> {to}] {message}", "[jacob -> alice] hi"));
    }

    @Test
    public void literalTextHasToMatchExactly() {
        // a "." is a dot and not "any character"
        assertNull(WhisperChecker.tryParse("bot", "{from}.{message}", "jacobXhi"));
        WhisperChecker.MessageResult r = WhisperChecker.tryParse("bot", "{from}.{message}", "jacob.hi");
        assertEquals("jacob", r.from);
        assertEquals("hi", r.message);
    }

    @Test
    public void placeholdersCanComeInAnyOrder() {
        WhisperChecker.MessageResult r = WhisperChecker.tryParse("bot", "{message} (from {from})", "stop now (from jacob)");
        assertEquals("jacob", r.from);
        assertEquals("stop now", r.message);
    }
}
