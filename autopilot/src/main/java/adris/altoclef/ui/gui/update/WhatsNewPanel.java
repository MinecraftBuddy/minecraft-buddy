package adris.altoclef.ui.gui.update;

import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Control;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.Scroller;
import adris.altoclef.ui.gui.kit.TextMeasurer;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.TextWrap;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;
import baritone.utils.update.Snapshot;

import java.util.ArrayList;
import java.util.List;

// the release notes: a floating card in the middle of the window with the title, the notes as plain wrapped text that
// scrolls, and back / update along the bottom. no links in the notes on purpose, the one link is the download page
// button and that goes through the game's own "open this url?" screen
public final class WhatsNewPanel extends Control {

    public interface Actions {
        void update();

        void openPage(String url);

        void back();
    }

    private static final int MAX_W = 560;
    private static final int MIN_H = 220;
    private static final int PAD = 20;
    private static final int WHEEL_LINES = 3;

    private final Snapshot snapshot;
    private final Button back;
    private final Button primary;
    private final List<Widget> kids;
    private final Scroller scroller = new Scroller();
    private final String title;
    private final String subtitle;
    private List<String> noteLines = List.of();
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int bodyTop;
    private int bodyH;
    private int lastWrapW = -1;
    private Widget focusedKid;
    private boolean draggingThumb;

    public WhatsNewPanel(Theme theme, Snapshot s, Actions actions) {
        super(theme);
        this.snapshot = s;
        Snapshot.Release r = s.release();
        String version = r == null || r.version() == null ? "" : "v" + r.version();
        String t = r == null || r.title() == null || r.title().isBlank() ? version : r.title().strip();
        title = t.isEmpty() ? "What's new" : t;
        // the version is the subtitle unless the title already says it ("v1.1.7 · descent floors" is how releases are named)
        StringBuilder sub = new StringBuilder();
        if (!version.isEmpty() && !t.contains(version)) {
            sub.append(version);
        }
        if (r != null && r.sizeBytes() > 0) {
            if (!sub.isEmpty()) {
                sub.append(" · ");
            }
            sub.append(UpdateCard.mb(r.sizeBytes())).append(" MB");
        }
        subtitle = sub.toString();
        back = new Button(theme, "Back", Button.Kind.GHOST, null, actions::back);
        if (s.canInstall()) {
            // same install either way, a switch just shouldn't call itself an update
            primary = new Button(theme, s.switching() ? "Switch" : "Update", Button.Kind.PRIMARY, null, actions::update);
        } else {
            String url = r != null && r.htmlUrl() != null && !r.htmlUrl().isBlank() ? r.htmlUrl() : s.releasesPageUrl();
            primary = new Button(theme, "Open download page", Button.Kind.PRIMARY, null, () -> actions.openPage(url));
        }
        kids = List.of(back, primary);
    }

    @Override
    public List<Widget> children() {
        return kids;
    }

    // the bounds are the whole window, the card centres itself in them
    @Override
    protected void layout() {
        TextMeasurer m = theme.measurer;
        int pad = theme.px(PAD);
        int gap = theme.px(Theme.GAP);
        int ch = theme.px(Theme.CONTROL_H);
        panelW = Math.min(theme.px(MAX_W), Math.max(theme.px(UpdateCard.MIN_W), w - 2 * theme.px(Theme.PAD)));
        int inner = panelW - 2 * pad;
        if (inner != lastWrapW) {
            lastWrapW = inner;
            String notes = snapshot.release() == null ? null : snapshot.release().notesPlain();
            noteLines = notes == null || notes.isBlank() ? List.of() : TextWrap.wrap(m, notes, theme.body, inner);
        }
        int headH = Math.round(m.lineHeight(theme.title)) + (subtitle.isEmpty() ? 0 : theme.px(Theme.SPACE_1) + Math.round(m.lineHeight(theme.small)));
        int footH = ch;
        // header, hairline, body, hairline, footer. the body gets what the window leaves, down to a few lines
        int chrome = pad + headH + theme.px(Theme.SPACE_3) + 1 + theme.px(Theme.SPACE_3) + theme.px(Theme.SPACE_3) + 1 + theme.px(Theme.SPACE_3) + footH + pad;
        int contentH = Math.max(Math.round(m.lineHeight(theme.body)), Math.round(noteLines.size() * m.lineHeight(theme.body)));
        int maxH = Math.max(theme.px(MIN_H), Math.round(h * 0.8f));
        panelH = Math.min(maxH, chrome + contentH);
        bodyH = Math.max(theme.px(Theme.ROW_H), panelH - chrome);
        panelX = x + (w - panelW) / 2;
        panelY = y + (h - panelH) / 2;
        bodyTop = panelY + pad + headH + theme.px(Theme.SPACE_3) + 1 + theme.px(Theme.SPACE_3);
        scroller.setSizes(contentH, bodyH);
        int by = panelY + panelH - pad - ch;
        back.setBounds(panelX + pad, by, back.preferredWidth(), ch);
        int pw = Math.min(inner - back.preferredWidth() - gap, primary.preferredWidth());
        primary.setBounds(panelX + panelW - pad - pw, by, pw, ch);
    }

    @Override
    public boolean contains(float px, float py) {
        return visible && px >= panelX && py >= panelY && px < panelX + panelW && py < panelY + panelH;
    }

    private boolean inBody(float px, float py) {
        return px >= panelX && px < panelX + panelW && py >= bodyTop && py < bodyTop + bodyH;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        stamp(f);
        TextMeasurer m = theme.measurer;
        int pad = theme.px(PAD);
        int inner = panelW - 2 * pad;
        theme.floating(c, panelX, panelY, panelW, panelH, theme.pxf(Theme.PANEL_RADIUS));
        float ty = panelY + pad;
        String shownTitle = c.ellipsize(title, theme.title, inner);
        c.text(shownTitle, panelX + pad, ty + m.ascent(theme.title), theme.title, theme.color(Theme.TEXT));
        ty += m.lineHeight(theme.title);
        if (!subtitle.isEmpty()) {
            ty += theme.px(Theme.SPACE_1);
            c.text(subtitle, panelX + pad, ty + m.ascent(theme.small), theme.small, theme.color(Theme.TEXT_2));
        }
        int rule1 = bodyTop - theme.px(Theme.SPACE_3) - 1;
        c.fillRect(panelX + pad, rule1, inner, 1, theme.color(Theme.HAIRLINE));
        int rule2 = bodyTop + bodyH + theme.px(Theme.SPACE_3);
        c.fillRect(panelX + pad, rule2, inner, 1, theme.color(Theme.HAIRLINE));
        drawBody(c, pad, inner);
        for (Widget k : kids) {
            k.draw(c, f);
        }
    }

    private void drawBody(Canvas c, int pad, int inner) {
        TextMeasurer m = theme.measurer;
        TextStyle body = theme.body;
        if (noteLines.isEmpty()) {
            c.text("no notes for this release", panelX + pad, bodyTop + m.ascent(body), body, theme.color(Theme.TEXT_3));
            return;
        }
        float lh = m.lineHeight(body);
        float off = scroller.offset(now);
        c.pushClip(panelX, bodyTop, panelW, bodyH);
        try {
            int first = Math.max(0, (int) Math.floor(off / lh));
            int last = Math.min(noteLines.size() - 1, (int) Math.ceil((off + bodyH) / lh));
            for (int i = first; i <= last; i++) {
                String line = noteLines.get(i);
                if (!line.isEmpty()) {
                    c.text(line, panelX + pad, bodyTop + i * lh - off + m.ascent(body), body, theme.color(Theme.TEXT));
                }
            }
        } finally {
            c.popClip();
        }
        // same bar as the lists: thin, in the right gutter, fades in while you scroll
        float vis = scroller.visibility(now, hovered);
        if (vis < 0.01f || !scroller.scrollable()) {
            return;
        }
        float th = scroller.thumbHeight(bodyH, minThumb());
        float tt = scroller.thumbTop(bodyH, minThumb(), now);
        float bw = theme.px(Theme.SCROLLBAR_W);
        c.fillRoundRect(barX(), bodyTop + tt, bw, th, bw / 2f, Theme.fade(theme.color(Theme.TEXT_3), vis));
    }

    private float minThumb() {
        return theme.px(24);
    }

    private float barX() {
        return panelX + panelW - theme.px(Theme.SCROLLBAR_W) - theme.px(6);
    }

    // the thumb is a few px wide, so the grab area gets a gutter's worth of slack on the left
    private boolean onThumb(float mx, float my) {
        if (!scroller.scrollable()) {
            return false;
        }
        float top = bodyTop + scroller.thumbTop(bodyH, minThumb(), now);
        return mx >= barX() - theme.px(6) && mx < panelX + panelW
                && my >= top && my < top + scroller.thumbHeight(bodyH, minThumb());
    }

    // ----- input

    @Override
    public boolean scroll(float mx, float my, float dy) {
        if (!contains(mx, my)) {
            return false;
        }
        scroller.scrollBy(-dy * WHEEL_LINES * theme.measurer.lineHeight(theme.body), now);
        return true;
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (button == 0 && onThumb(mx, my)) {
            draggingThumb = true;
            scroller.touch(now);
            return true;
        }
        boolean kid = super.mouseDown(mx, my, button, mods);
        if (!kid) {
            // a click on the text, or outside the card, takes the ring off whatever had it
            blur();
        }
        return kid || contains(mx, my);
    }

    @Override
    public boolean mouseDrag(float mx, float my, int button, float dx, float dy) {
        if (!draggingThumb) {
            return false;
        }
        scroller.dragThumb(dy, bodyH, minThumb(), now);
        return true;
    }

    @Override
    public boolean mouseUp(float mx, float my, int button, int mods) {
        if (draggingThumb) {
            draggingThumb = false;
            return true;
        }
        return super.mouseUp(mx, my, button, mods);
    }

    // tab walks back then update, enter or space presses, page and arrow keys move the notes. true when the key was ours
    @Override
    public boolean keyDown(int key, int mods) {
        if (key == Keys.TAB) {
            cycleFocus(Keys.shift(mods));
            return true;
        }
        if (focusedKid != null && focusedKid.keyDown(key, mods)) {
            return true;
        }
        float lh = theme.measurer.lineHeight(theme.body);
        switch (key) {
            case Keys.DOWN -> scroller.scrollBy(lh * WHEEL_LINES, now);
            case Keys.UP -> scroller.scrollBy(-lh * WHEEL_LINES, now);
            case Keys.PAGE_DOWN -> scroller.scrollBy(bodyH, now);
            case Keys.PAGE_UP -> scroller.scrollBy(-bodyH, now);
            case Keys.HOME -> scroller.scrollTo(0, now);
            case Keys.END -> scroller.scrollTo(scroller.maxOffset(), now);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void cycleFocus(boolean backwards) {
        List<Widget> all = new ArrayList<>();
        collectFocusable(all);
        if (all.isEmpty()) {
            return;
        }
        int i = all.indexOf(focusedKid);
        int n = all.size();
        int next = i < 0 ? (backwards ? n - 1 : 0) : (i + (backwards ? -1 : 1) + n) % n;
        focus(all.get(next));
    }

    public void focus(Widget w) {
        if (focusedKid != null && focusedKid != w) {
            focusedKid.setFocused(false);
        }
        focusedKid = w;
        if (w != null) {
            w.setFocused(true);
        }
    }

    public void blur() {
        focus(null);
    }

    // test hooks
    public Scroller scroller() {
        return scroller;
    }

    public List<String> noteLines() {
        return noteLines;
    }

    public Button primary() {
        return primary;
    }

    public Button back() {
        return back;
    }

    public int panelX() {
        return panelX;
    }

    public int panelY() {
        return panelY;
    }

    public int panelW() {
        return panelW;
    }

    public int panelH() {
        return panelH;
    }
}
