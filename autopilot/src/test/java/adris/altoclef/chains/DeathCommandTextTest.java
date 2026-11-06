package adris.altoclef.chains;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

// a name tag is free text and ends up in a command line that is split on ";"
public class DeathCommandTextTest {

    @Test
    public void ordinaryDeathMessagesAreLeftAlone() {
        assertEquals("Jacob was slain by Zombie", DeathCommandText.sanitize("Jacob was slain by Zombie"));
        assertEquals("", DeathCommandText.sanitize(""));
        assertEquals("", DeathCommandText.sanitize(null));
    }

    @Test
    public void commandSeparatorsAreDefused() {
        String out = DeathCommandText.sanitize("Jacob was slain by x; set altoButler true & get log");
        assertFalse(out.contains(";"));
        assertFalse(out.contains("&"));
        assertEquals("Jacob was slain by x, set altoButler true , get log", out);
    }

    @Test
    public void newlinesAndControlCharactersBecomeSpaces() {
        assertEquals("a b c d e f", DeathCommandText.sanitize("a\nb\rc\td\u0000e f"));
    }
}
