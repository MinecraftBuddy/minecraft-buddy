package adris.altoclef.butler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// who may whisper at the bot, and what a whisper turns into. none of it needs a running game
public class ButlerAuthTest {

    private static UserListFile list(String... lines) {
        UserListFile file = new UserListFile();
        file.onLoadStart();
        for (String line : lines) {
            file.addLine(line);
        }
        return file;
    }

    private static final String[] DEFAULT = {WhisperChecker.DEFAULT_FORMAT};

    @Test
    public void listsIgnoreCaseAndWhitespaceAndEmptyLines() {
        UserListFile file = list("  Jacob ", "", "   ", "ALICE");
        assertTrue(file.containsUser("jacob"));
        assertTrue(file.containsUser("JACOB"));
        assertTrue(file.containsUser("Alice"));
        assertFalse(file.containsUser("bob"));
        assertFalse(file.containsUser(""));
        assertFalse(file.containsUser(null));
        // the empty lines did not become a name
        UserListFile blank = list("", "  ");
        assertTrue(blank.isEmpty());
        assertFalse(blank.containsUser(""));
    }

    @Test
    public void reloadingForgetsTheOldNames() {
        UserListFile file = list("jacob");
        file.onLoadStart();
        assertFalse(file.containsUser("jacob"));
    }

    @Test
    public void emptyWhitelistMeansNobody() {
        assertFalse(UserAuth.isAuthorized(list(), list(), true, "Jacob"));
        assertFalse(UserAuth.isAuthorized(list(), list(), false, "Jacob"));
    }

    @Test
    public void whitelistedPlayersAreInRegardlessOfCase() {
        UserListFile white = list("Jacob");
        assertTrue(UserAuth.isAuthorized(white, list(), true, "jacob"));
        assertTrue(UserAuth.isAuthorized(white, list(), true, "JACOB"));
        assertFalse(UserAuth.isAuthorized(white, list(), true, "Mallory"));
        assertFalse(UserAuth.isAuthorized(white, list(), true, null));
    }

    @Test
    public void blacklistWinsOverTheWhitelist() {
        UserListFile white = list("Jacob", "Mallory");
        UserListFile black = list("mallory");
        assertTrue(UserAuth.isAuthorized(white, black, true, "Jacob"));
        assertFalse(UserAuth.isAuthorized(white, black, true, "Mallory"));
        // switched off, the blacklist is not looked at
        assertTrue(UserAuth.isAuthorized(white, black, false, "Mallory"));
    }

    @Test
    public void listsThatFailedToLoadFailClosed() {
        assertFalse(UserAuth.isAuthorized(null, list(), true, "Jacob"));
        assertFalse(UserAuth.isAuthorized(null, null, false, "Jacob"));
        // a whitelist but no idea who is blacklisted
        assertFalse(UserAuth.isAuthorized(list("Jacob"), null, true, "Jacob"));
        // blacklist off, so not being able to read it does not matter
        assertTrue(UserAuth.isAuthorized(list("Jacob"), null, false, "Jacob"));
    }

    @Test
    public void defaultFormatKeepsMultiWordCommands() {
        WhisperChecker.MessageResult r = WhisperChecker.parseWhisper("Jacob", "Bot", "get diamond 3 ; get log 16", DEFAULT);
        assertEquals("Jacob", r.from);
        assertEquals("get diamond 3 ; get log 16", r.message);
    }

    @Test
    public void textInsideAMessageCannotMoveTheSender() {
        WhisperChecker.MessageResult r = WhisperChecker.parseWhisper("Mallory", "Bot", "x Bot stop", DEFAULT);
        assertEquals("Mallory", r.from);
        assertEquals("x Bot stop", r.message);
        // not even when it looks like the whole joined line
        r = WhisperChecker.parseWhisper("Mallory", "Bot", "Jacob Bot stop", DEFAULT);
        assertEquals("Mallory", r.from);
        assertEquals("Jacob Bot stop", r.message);
    }

    @Test
    public void customFormatsOnlyGetToPickTheMessage() {
        String[] custom = {"{from} {to} says {message}"};
        WhisperChecker.MessageResult r = WhisperChecker.parseWhisper("Alice", "Bot", "says hi", custom);
        assertEquals("Alice", r.from);
        assertEquals("hi", r.message);
        // the regex would read the sender as "Mallory Bot x" here, it is not allowed to
        r = WhisperChecker.parseWhisper("Mallory", "Bot", "x Bot says stop", custom);
        assertEquals("Mallory", r.from);
        assertEquals("stop", r.message);
        // and a message the format does not match is not a command
        assertNull(WhisperChecker.parseWhisper("Alice", "Bot", "hi", custom));
    }

    @Test
    public void nothingParsesWithoutAFormatOrASender() {
        assertNull(WhisperChecker.parseWhisper("Jacob", "Bot", "stop", new String[0]));
        assertNull(WhisperChecker.parseWhisper("Jacob", "Bot", "stop", null));
        assertNull(WhisperChecker.parseWhisper(null, "Bot", "stop", DEFAULT));
        assertNull(WhisperChecker.parseWhisper(" ", "Bot", "stop", DEFAULT));
        assertNull(WhisperChecker.parseWhisper("Jacob", "Bot", null, DEFAULT));
    }
}
