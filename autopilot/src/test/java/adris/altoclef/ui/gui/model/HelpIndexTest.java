package adris.altoclef.ui.gui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import adris.altoclef.ui.gui.facade.CommandsAccess.CommandInfo;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class HelpIndexTest {

    private static CommandInfo cmd(String shortDesc, List<String> longDesc, String... names) {
        return new CommandInfo(List.of(names), shortDesc, longDesc);
    }

    private static List<CommandInfo> sample() {
        List<CommandInfo> l = new ArrayList<>();
        l.add(cmd("Stop everything", List.of("Cancels the current task."), "stop", "cancel"));
        l.add(cmd("Go to a coordinate or block", List.of("Path to a position."), "goto", "go", "path"));
        l.add(cmd("Mine some blocks", List.of("Digs the goto spot for you."), "mine", "m"));
        l.add(cmd("Follow entity things", List.of("Follow players."), "follow", "f"));
        l.add(cmd("Open the click gui", List.of("Opens the panel."), "gui", "clickgui"));
        l.add(cmd("Beat the game", List.of("Starts the speedrun brain."), "gamer"));
        return l;
    }

    private static List<String> names(List<CommandInfo> l) {
        return l.stream().map(CommandInfo::name).toList();
    }

    @Test
    public void emptyQueryIsAlphabetical() {
        assertEquals(List.of("follow", "gamer", "goto", "gui", "mine", "stop"), names(HelpIndex.rank(sample(), "")));
        assertEquals(List.of("follow", "gamer", "goto", "gui", "mine", "stop"), names(HelpIndex.rank(sample(), "  ")));
        assertEquals(List.of("follow", "gamer", "goto", "gui", "mine", "stop"), names(HelpIndex.rank(sample(), null)));
    }

    @Test
    public void exactBeatsPrefixBeatsContains() {
        List<CommandInfo> l = new ArrayList<>();
        l.add(cmd("a", List.of(), "legoto"));
        l.add(cmd("a", List.of(), "gotoish"));
        l.add(cmd("a", List.of(), "goto"));
        assertEquals(List.of("goto", "gotoish", "legoto"), names(HelpIndex.rank(l, "goto")));
    }

    @Test
    public void goRanksTheWayTheBriefSays() {
        // goto is a name prefix, mine only says goto in its long description so it trails
        List<CommandInfo> r = HelpIndex.rank(sample(), "go");
        assertEquals("goto", r.get(0).name());
        assertTrue(names(r).contains("mine"));
        assertEquals("mine", r.get(r.size() - 1).name());
    }

    @Test
    public void nameThenAliasThenShortThenLong() {
        List<CommandInfo> l = new ArrayList<>();
        l.add(cmd("nothing", List.of("mentions zap in the fine print"), "d"));
        l.add(cmd("zap is in the short line", List.of(), "c"));
        l.add(cmd("nothing", List.of(), "b", "zapper"));
        l.add(cmd("nothing", List.of(), "zapx"));
        l.add(cmd("nothing", List.of(), "a", "xxzap"));
        l.add(cmd("nothing", List.of(), "xzapx"));
        assertEquals(List.of("zapx", "xzapx", "b", "a", "c", "d"), names(HelpIndex.rank(l, "zap")));
    }

    @Test
    public void tiesAreAlphabetical() {
        List<CommandInfo> l = new ArrayList<>();
        l.add(cmd("x", List.of(), "beta"));
        l.add(cmd("x", List.of(), "alpha"));
        l.add(cmd("x", List.of(), "Gamma"));
        assertEquals(List.of("alpha", "beta", "Gamma"), names(HelpIndex.rank(l, "x")));
    }

    @Test
    public void noResults() {
        assertTrue(HelpIndex.rank(sample(), "xyz").isEmpty());
        assertTrue(HelpIndex.rank(List.of(), "go").isEmpty());
        assertTrue(HelpIndex.rank(null, "go").isEmpty());
    }

    @Test
    public void aLeadingHashInTheSearchIsIgnored() {
        assertEquals("goto", HelpIndex.rank(sample(), "#goto").get(0).name());
    }

    @Test
    public void cleanLineStripsPrefixAndPadding() {
        assertEquals("goto 1 2 3", HelpIndex.cleanLine("  #goto 1 2 3 "));
        assertEquals("goto 1 2 3", HelpIndex.cleanLine("# goto 1 2 3"));
        assertEquals("goto", HelpIndex.cleanLine("##goto"));
        assertEquals("", HelpIndex.cleanLine("#"));
        assertEquals("", HelpIndex.cleanLine(null));
    }

    @Test
    public void linesSplitUsageHeadingsAndGaps() {
        List<HelpIndex.Line> l = HelpIndex.lines(List.of("", "Path to a place.", "", "", "Usage:", "> goto <y>", ">goto <x> <z>", ">", ""));
        assertEquals(List.of(
                new HelpIndex.Line(HelpIndex.Kind.TEXT, "Path to a place."),
                new HelpIndex.Line(HelpIndex.Kind.GAP, ""),
                new HelpIndex.Line(HelpIndex.Kind.HEADING, "Usage"),
                new HelpIndex.Line(HelpIndex.Kind.USAGE, "goto <y>"),
                new HelpIndex.Line(HelpIndex.Kind.USAGE, "goto <x> <z>")), l);
    }

    @Test
    public void usageLinesSplitOffTheirExplanation() {
        List<HelpIndex.Line> l = HelpIndex.lines(List.of("> goto <x> <z> - Go to an X,Z position", "> goto - bare - dashes - inside", "> stop"));
        assertEquals(new HelpIndex.Line(HelpIndex.Kind.USAGE, "goto <x> <z>", "Go to an X,Z position"), l.get(0));
        assertEquals(new HelpIndex.Line(HelpIndex.Kind.USAGE, "goto", "bare - dashes - inside"), l.get(1));
        assertEquals(new HelpIndex.Line(HelpIndex.Kind.USAGE, "stop", ""), l.get(2));
    }

    @Test
    public void theFinePrintOnlyMatchesAtTheStartOfAWord() {
        List<CommandInfo> l = new ArrayList<>();
        l.add(cmd("nothing", List.of("> get iron_ingot 3"), "get"));
        l.add(cmd("nothing", List.of("Save, list and go to waypoints."), "wp"));
        assertEquals(List.of("wp"), names(HelpIndex.rank(l, "go")));
        assertEquals(List.of("get"), names(HelpIndex.rank(l, "ingot")));
    }

    @Test
    public void aLongSentenceEndingInAColonIsStillText() {
        List<HelpIndex.Line> l = HelpIndex.lines(List.of("Here are the things you can do:"));
        assertEquals(HelpIndex.Kind.TEXT, l.get(0).kind());
    }

    @Test
    public void nullLongDescIsEmpty() {
        assertTrue(HelpIndex.lines(null).isEmpty());
    }
}
