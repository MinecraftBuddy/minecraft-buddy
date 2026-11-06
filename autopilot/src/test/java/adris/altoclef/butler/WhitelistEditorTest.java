package adris.altoclef.butler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

// the whitelist command's file edits, on plain lists and on a real file. no game involved
public class WhitelistEditorTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final List<String> HEADER = List.of("# Add butler whitelisted players here.", "# Anything after a pound sign is ignored.");

    @Test
    public void addingAppendsAndKeepsTheComments() {
        List<String> lines = List.of("# friends", "alice # best friend", "");
        WhitelistEditor.Edit edit = WhitelistEditor.add(lines, "Bob");
        assertEquals(WhitelistEditor.Outcome.ADDED, edit.outcome());
        assertEquals(List.of("# friends", "alice # best friend", "", "Bob"), edit.lines());
        // the list we passed in is not touched
        assertEquals(3, lines.size());
    }

    @Test
    public void addingTwiceIsAlreadyThereWhateverTheCase() {
        List<String> lines = List.of("Alice");
        for (String again : new String[]{"Alice", "alice", "ALICE"}) {
            WhitelistEditor.Edit edit = WhitelistEditor.add(lines, again);
            assertEquals(again, WhitelistEditor.Outcome.ALREADY_THERE, edit.outcome());
            assertEquals(lines, edit.lines());
        }
    }

    @Test
    public void aNameInsideACommentIsNotOnTheList() {
        List<String> lines = List.of("# alice used to be here", "bob # alice is his friend");
        assertEquals(WhitelistEditor.Outcome.ADDED, WhitelistEditor.add(lines, "alice").outcome());
        assertEquals(WhitelistEditor.Outcome.NOT_THERE, WhitelistEditor.remove(lines, "alice").outcome());
    }

    @Test
    public void removingTakesTheWholeLineAndNothingElse() {
        List<String> lines = List.of("# friends", "alice # best friend", "BOB", "carol");
        WhitelistEditor.Edit edit = WhitelistEditor.remove(lines, "bob");
        assertEquals(WhitelistEditor.Outcome.REMOVED, edit.outcome());
        assertEquals(List.of("# friends", "alice # best friend", "carol"), edit.lines());
        WhitelistEditor.Edit withComment = WhitelistEditor.remove(lines, "ALICE");
        assertEquals(List.of("# friends", "BOB", "carol"), withComment.lines());
    }

    @Test
    public void removingSomeoneWhoIsNotThereChangesNothing() {
        List<String> lines = List.of("# nobody", "alice");
        WhitelistEditor.Edit edit = WhitelistEditor.remove(lines, "zed");
        assertEquals(WhitelistEditor.Outcome.NOT_THERE, edit.outcome());
        assertEquals(lines, edit.lines());
    }

    @Test
    public void duplicateLinesAllGoOnRemove() {
        List<String> lines = List.of("bob", "alice", "Bob");
        assertEquals(List.of("alice"), WhitelistEditor.remove(lines, "BOB").lines());
    }

    @Test
    public void namesSkipCommentsAndBlanks() {
        assertEquals(List.of("alice", "bob"), WhitelistEditor.names(List.of("# hi", "", "  alice  ", "bob # x", "   # y")));
    }

    @Test
    public void onlyPlausibleNamesPass() {
        for (String ok : new String[]{"ayvyr", "Jacob", "a", "Steve_123", "xX_gamer_Xx", "0123456789abcdef", ".BedrockGuy"}) {
            assertTrue(ok, WhitelistEditor.isValidName(ok));
        }
        for (String bad : new String[]{null, "", " ", "a b", "ayvyr ", "seventeen_chars_x", "bob#", "# comment", "bo\nb", "bob;get", "§4bob", "bób", "/bob", "a,b"}) {
            assertFalse(String.valueOf(bad), WhitelistEditor.isValidName(bad));
        }
    }

    @Test
    public void aFileRoundTripsAndKeepsItsHeader() throws IOException {
        Path file = tmp.getRoot().toPath().resolve("sub").resolve("altoclef_butler_whitelist.txt");
        // not there yet: the first add creates it
        assertEquals(WhitelistEditor.Outcome.ADDED, WhitelistEditor.apply(file, "ayvyr", true));
        assertEquals(List.of("ayvyr"), Files.readAllLines(file, StandardCharsets.UTF_8));

        Files.write(file, (String.join("\n", HEADER) + "\nalice").getBytes(StandardCharsets.UTF_8));
        assertEquals(WhitelistEditor.Outcome.ADDED, WhitelistEditor.apply(file, "Bob", true));
        assertEquals(List.of(HEADER.get(0), HEADER.get(1), "alice", "Bob"), Files.readAllLines(file, StandardCharsets.UTF_8));

        assertEquals(WhitelistEditor.Outcome.ALREADY_THERE, WhitelistEditor.apply(file, "bob", true));
        assertEquals(WhitelistEditor.Outcome.REMOVED, WhitelistEditor.apply(file, "ALICE", false));
        assertEquals(WhitelistEditor.Outcome.NOT_THERE, WhitelistEditor.apply(file, "alice", false));
        assertEquals(List.of(HEADER.get(0), HEADER.get(1), "Bob"), Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    @Test
    public void whatTheEditorWritesIsWhatTheListLoaderReads() throws IOException {
        // UserListFile is what the butler actually asks, fed the same lines the real loader would hand it
        Path file = tmp.newFile("wl.txt").toPath();
        WhitelistEditor.apply(file, "Alice", true);
        WhitelistEditor.apply(file, "bob", true);
        UserListFile list = new UserListFile();
        list.onLoadStart();
        for (String name : WhitelistEditor.names(WhitelistEditor.read(file))) {
            list.addLine(name);
        }
        assertTrue(list.containsUser("ALICE"));
        assertTrue(list.containsUser("Bob"));
        assertFalse(list.containsUser("carol"));
        WhitelistEditor.apply(file, "alice", false);
        UserListFile after = new UserListFile();
        after.onLoadStart();
        WhitelistEditor.names(WhitelistEditor.read(file)).forEach(after::addLine);
        assertFalse(after.containsUser("alice"));
        assertTrue(after.containsUser("bob"));
    }
}
