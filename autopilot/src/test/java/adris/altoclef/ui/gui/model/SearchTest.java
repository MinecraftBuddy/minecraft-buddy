package adris.altoclef.ui.gui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class SearchTest {

    private static final List<String> ALIASES = List.of("go", "path");

    @Test
    public void tiersRankInOrder() {
        int exact = Search.score("goto", "goto", ALIASES, "Go to a coordinate");
        int prefix = Search.score("got", "goto", ALIASES, "Go to a coordinate");
        int contains = Search.score("oto", "goto", ALIASES, "Go to a coordinate");
        int aliasExact = Search.score("path", "goto", ALIASES, "Go to a coordinate");
        int aliasPrefix = Search.score("pa", "goto", ALIASES, "Go to a coordinate");
        int aliasContains = Search.score("ath", "goto", ALIASES, "Go to a coordinate");
        int text = Search.score("coordinate", "goto", ALIASES, "Go to a coordinate");
        assertTrue(exact > prefix);
        assertTrue(prefix > contains);
        assertTrue(contains > aliasExact);
        assertTrue(aliasExact > aliasPrefix);
        assertTrue(aliasPrefix > aliasContains);
        assertTrue(aliasContains > text);
        assertTrue(text > 0);
    }

    @Test
    public void caseAndPaddingDoNotMatter() {
        assertEquals(Search.NAME_EXACT, Search.score("  GoTo ", "goto", null, null));
    }

    @Test
    public void emptyQueryScoresNothing() {
        assertEquals(0, Search.score("", "goto", ALIASES, "x"));
        assertEquals(0, Search.score("   ", "goto", ALIASES, "x"));
        assertEquals(0, Search.score(null, "goto", ALIASES, "x"));
    }

    @Test
    public void noMatchIsZero() {
        assertEquals(0, Search.score("zzz", "goto", ALIASES, "Go to a coordinate"));
        assertFalse(Search.matches("zzz", "goto", ALIASES, "Go to a coordinate"));
        assertTrue(Search.matches("coord", "goto", ALIASES, "Go to a coordinate"));
    }

    @Test
    public void textMatchesWordsInAnyOrder() {
        assertTrue(Search.matches("block go", "x", null, "Go to a block"));
        assertFalse(Search.matches("block nope", "x", null, "Go to a block"));
    }

    @Test
    public void nullAliasesAndTextAreFine() {
        assertEquals(0, Search.score("a", "zzz", null, null));
        assertEquals(Search.NAME_PREFIX, Search.score("z", "zzz", null, null));
    }
}
