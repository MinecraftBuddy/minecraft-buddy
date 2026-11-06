package adris.altoclef.ui.gui.kit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

// escape on a half typed value puts the last committed one back and is consumed; on a clean box it is not the
// field's key, so the shell can blur or close
public class EscapeRevertsTest {

    private static Theme theme() {
        return new Theme(1f, new StubCanvas(800, 600));
    }

    @Test
    public void textFieldRevertsToTheCommittedText() {
        List<String> commits = new ArrayList<>();
        TextField f = new TextField(theme(), "", commits::add);
        f.setBounds(0, 0, 200, 30);
        f.setFocused(true);
        f.setText("home");
        assertFalse(f.dirty());
        assertFalse(f.keyDown(Keys.ESCAPE, 0));
        f.charTyped('s');
        assertTrue(f.dirty());
        assertTrue(f.keyDown(Keys.ESCAPE, 0));
        assertEquals("home", f.text());
        assertTrue(commits.isEmpty());
        f.charTyped('!');
        f.keyDown(Keys.ENTER, 0);
        assertEquals(List.of("home!"), commits);
        f.charTyped('x');
        assertTrue(f.keyDown(Keys.ESCAPE, 0));
        assertEquals("home!", f.text());
    }

    @Test
    public void numberFieldRevertsToTheLastValueThatParsed() {
        List<String> commits = new ArrayList<>();
        NumberField n = new NumberField(theme(), NumberField.NumberFormat.INT, commits::add);
        n.setBounds(0, 0, 200, 30);
        n.setValue("64");
        n.field().setFocused(true);
        n.field().charTyped('1');
        assertEquals("641", n.text());
        assertTrue(n.field().keyDown(Keys.ESCAPE, 0));
        assertEquals("64", n.text());
        assertTrue(commits.isEmpty());
        // a rejected enter does not become the thing escape goes back to
        n.field().edit().selectAll();
        n.field().charTyped('-');
        n.field().keyDown(Keys.ENTER, 0);
        assertTrue(commits.isEmpty());
        assertTrue(n.field().keyDown(Keys.ESCAPE, 0));
        assertEquals("64", n.text());
        assertFalse(n.field().keyDown(Keys.ESCAPE, 0));
    }
}
