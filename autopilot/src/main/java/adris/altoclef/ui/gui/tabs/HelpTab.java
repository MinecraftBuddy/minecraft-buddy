package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.BotFacade;
import adris.altoclef.ui.gui.facade.CommandsAccess.CommandInfo;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.kit.TextField;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.HelpHistory;
import adris.altoclef.ui.gui.model.HelpIndex;
import adris.altoclef.ui.gui.model.Wording;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

// a live command line on top, every command below it. the command line is the signature of the whole gui: each button
// elsewhere is a chat command, and this is where you can type one yourself
public final class HelpTab implements Tab {

    private static final String IDLE_HINT = "Enter runs it. Up arrow brings back earlier commands.";

    // the command line: a quiet # in front so it reads as chat, and a terminal style history on the arrow keys
    private static final class HelpField extends TextField {
        private final HelpHistory history;
        private final float hashW;

        HelpField(Theme theme, String placeholder, Consumer<String> onCommit, HelpHistory history) {
            super(theme, placeholder, onCommit);
            this.history = history;
            this.hashW = theme.measurer.measure("#", theme.body);
        }

        @Override
        protected int leftInset() {
            return theme.px(10) + Math.round(hashW) + theme.px(6);
        }

        @Override
        protected void drawAdornments(Canvas c, Frame f) {
            c.text("#", x + theme.px(10), baseline(c, theme.body, y, h), theme.body, theme.color(Theme.TEXT_3));
        }

        @Override
        public boolean keyDown(int key, int mods) {
            // glfw also reports caps lock and num lock in mods, those must not break the arrow keys
            if (focused && (mods & 15) == 0) {
                if (key == Keys.UP) {
                    recall(history.back(text()));
                    return true;
                }
                if (key == Keys.DOWN && history.browsing()) {
                    recall(history.forward(text()));
                    return true;
                }
            }
            return super.keyDown(key, mods);
        }

        // red only belongs to the line that failed, a different line has not failed yet
        private void recall(String line) {
            if (!line.equals(text())) {
                setText(line);
                setInvalid(false);
            }
        }

        // the # in front is already drawn, a second one would read as "# #goto"
        @Override
        public boolean charTyped(char c) {
            if (focused && c == '#' && text().isEmpty()) {
                return true;
            }
            return super.charTyped(c);
        }
    }

    private final class HelpPanel extends Widget {
        private final HelpField field;
        private final Button runButton;
        private final HelpList list;

        HelpPanel() {
            super(HelpTab.this.theme);
            field = new HelpField(HelpTab.this.theme, "Run a command, like goto 100 64 -30", s -> run(), history);
            field.setOnChange(s -> {
                field.setInvalid(false);
                history.reset();
            });
            runButton = new Button(HelpTab.this.theme, "Run", Button.Kind.PRIMARY, null, HelpTab.this::run);
            list = new HelpList(HelpTab.this.theme, HelpTab.this::useLine);
        }

        @Override
        protected void layout() {
            int pad = theme.px(Theme.PAD);
            int ch = theme.px(Theme.CONTROL_H);
            int gap = theme.px(Theme.GAP);
            int runW = Math.max(runButton.preferredWidth(), theme.px(64));
            field.setBounds(x + pad, y + pad, w - 2 * pad - runW - gap, ch);
            runButton.setBounds(x + w - pad - runW, y + pad, runW, ch);
            int top = resultTop() + theme.px(18) + theme.px(Theme.SPACE_2);
            list.setBounds(x, top, w, Math.max(0, y + h - top));
        }

        int resultTop() {
            return y + theme.px(Theme.PAD) + theme.px(Theme.CONTROL_H) + theme.px(Theme.SPACE_1);
        }

        @Override
        public List<Widget> children() {
            return List.of(field, runButton, list);
        }

        @Override
        public void draw(Canvas c, Frame f) {
            runButton.setEnabled(!HelpIndex.cleanLine(field.text()).isEmpty());
            list.draw(c, f);
            field.draw(c, f);
            runButton.draw(c, f);
            drawResult(c);
        }

        private void drawResult(Canvas c) {
            String text = inlineText();
            int col = last == null ? theme.color(Theme.TEXT_3) : last.ok() ? theme.color(Theme.ACCENT_TEXT) : theme.color(Theme.DANGER);
            int pad = theme.px(Theme.PAD);
            float room = w - 2 * pad;
            String shown = theme.measurer.ellipsize(text, theme.small, room);
            float base = baseline(c, theme.small, resultTop(), theme.px(18));
            c.text(shown, x + pad, base, theme.small, col);
        }

        private float baseline(Canvas c, TextStyle s, float top, float height) {
            return top + height / 2f + (c.ascent(s) - c.descent(s)) / 2f;
        }
    }

    private final Theme theme;
    private final BotFacade facade;
    private final ShellContext ctx;
    private final HelpHistory history = new HelpHistory();
    private final HelpPanel panel;
    private List<CommandInfo> all = List.of();
    private String query = "";
    private String shownQuery = "";
    private Result last;
    private String lastLine = "";

    public HelpTab(Theme theme, BotFacade facade, ShellContext ctx) {
        this.theme = theme;
        this.facade = facade;
        this.ctx = ctx;
        loadCommands();
        panel = new HelpPanel();
        refilter();
    }

    private void loadCommands() {
        try {
            List<CommandInfo> list = facade.commands().commands();
            List<CommandInfo> kept = new ArrayList<>();
            if (list != null) {
                // a command with no name cannot be listed, searched or run: skip it rather than trip over it later
                for (CommandInfo c : list) {
                    if (c != null && c.names() != null && !c.names().isEmpty() && c.names().get(0) != null) {
                        kept.add(scrubbed(c));
                    }
                }
            }
            all = List.copyOf(kept);
        } catch (Throwable t) {
            Safe.report("help commands", t);
            all = List.of();
        }
    }

    // descriptions still say altoclef in places, and the gui is all soprano. done up front so search sees what you see
    private static CommandInfo scrubbed(CommandInfo c) {
        List<String> longDesc = new ArrayList<>();
        if (c.longDesc() != null) {
            for (String l : c.longDesc()) {
                longDesc.add(Wording.soprano(l));
            }
        }
        return new CommandInfo(c.names(), Wording.soprano(c.shortDesc()), longDesc);
    }

    // ----- Tab

    @Override
    public String id() {
        return "help";
    }

    @Override
    public String title() {
        return "Help";
    }

    @Override
    public Icon icon() {
        return Icon.HELP;
    }

    @Override
    public Widget content() {
        return panel;
    }

    @Override
    public boolean hasSearch() {
        return true;
    }

    @Override
    public String searchPlaceholder() {
        return "Search commands";
    }

    @Override
    public void onSearch(String q) {
        query = q == null ? "" : q;
        refilter();
    }

    @Override
    public void onShown() {
        if (all.isEmpty()) {
            loadCommands();
            refilter();
        }
    }

    // ----- behaviour

    private void refilter() {
        List<CommandInfo> ranked = HelpIndex.rank(all, query);
        String q = query.trim();
        panel.list.setEmptyText(all.isEmpty() ? "No commands registered"
                : q.isEmpty() ? "" : "No command matches \"" + q + "\"");
        panel.list.setItems(ranked);
        // a new query starts at the best hit, not wherever the old list happened to be scrolled. the shell also sends
        // an empty query on every tab switch, which must not throw away where you were
        if (!q.equals(shownQuery)) {
            shownQuery = q;
            panel.list.scroller().snapTo(0f);
        }
    }

    private void run() {
        String line = HelpIndex.cleanLine(panel.field.text());
        if (line.isEmpty()) {
            return;
        }
        Result r;
        try {
            r = facade.commands().run(line);
        } catch (Throwable t) {
            Safe.report("help run", t);
            r = null;
        }
        if (r == null) {
            r = Result.fail("Could not run that. Try it in chat with a # in front.", line);
        }
        last = r;
        lastLine = line;
        history.add(line);
        panel.field.setInvalid(!r.ok());
        if (r.ok()) {
            panel.field.setText("");
        }
        ctx.toast(r);
    }

    // a usage line from an open row goes into the command line to be edited, not run
    private void useLine(String line) {
        panel.field.setText(line);
        panel.field.setInvalid(false);
        history.reset();
        ctx.focus(panel.field);
    }

    // the small line under the command line
    public String inlineText() {
        if (last == null) {
            return IDLE_HINT;
        }
        if (!last.ok()) {
            String m = last.message();
            return m == null || m.isBlank() ? "That did not work. Check the command and try again." : firstLine(m);
        }
        String m = last.message();
        String base = "Ran " + lastLine;
        if (m == null || m.isBlank() || m.startsWith("Ran ")) {
            return base;
        }
        return base + " · " + firstLine(m);
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return (nl < 0 ? s : s.substring(0, nl)).trim();
    }

    // ----- for tests and previews

    public TextField field() {
        return panel.field;
    }

    public Button runButton() {
        return panel.runButton;
    }

    public Result lastResult() {
        return last;
    }

    public HelpHistory history() {
        return history;
    }

    // screen coordinates to click on, null when that command is not showing
    public float[] headerCenter(String command) {
        return panel.list.headerCenter(command);
    }

    public float[] usageCenter(String command, int nth) {
        return panel.list.usageCenter(command, nth);
    }

    public String expandedCommand() {
        return panel.list.expandedName();
    }

    public List<CommandInfo> showing() {
        return panel.list.items();
    }

    HelpList list() {
        return panel.list;
    }
}
