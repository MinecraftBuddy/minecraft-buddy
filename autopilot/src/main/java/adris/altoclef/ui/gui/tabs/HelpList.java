package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.CommandsAccess.CommandInfo;
import adris.altoclef.ui.gui.kit.Anim;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.TextMeasurer;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.VirtualView;
import adris.altoclef.ui.gui.model.HelpIndex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

// the command list. rows are one ROW_H until you click one, then it grows in place to show the long description and the
// usage lines. VirtualList is fixed height so this is its own thing on top of VirtualView (scrollbar, wheel and the
// row fade come for free). only one row is open at a time. rows cost nothing off screen except the O(n) walk to find
// where they are, and n is the number of commands
final class HelpList extends VirtualView<CommandInfo> {

    private record Piece(HelpIndex.Kind kind, List<String> text, String note, int y, int h) {
    }

    private static final class Detail {
        final int width;
        final List<Piece> pieces;
        final int height;
        final Anim[] hover;

        Detail(int width, List<Piece> pieces, int height) {
            this.width = width;
            this.pieces = pieces;
            this.height = height;
            this.hover = new Anim[pieces.size()];
            for (int i = 0; i < hover.length; i++) {
                hover[i] = new Anim(0f);
            }
        }
    }

    private static final class RowState {
        final Anim open = new Anim(0f);
        final Anim hover = new Anim(0f);
        Detail detail;
    }

    private final Consumer<String> onUse;
    private final Map<String, RowState> states = new HashMap<>();
    private String expanded;
    // the keyboard row is a command, not an index, so a filter does not slide the highlight onto a stranger
    private String kbName;
    private int pressedPiece = -1;

    // a click on a row moves rows above it (the old open row folds up), so the clicked row is pinned to where it was
    // on screen and then eased to wherever shows all of it
    private String anchorName;
    private final Anim anchor = new Anim(0f);

    private float locRel;

    HelpList(Theme theme, Consumer<String> onUse) {
        super(theme);
        this.onUse = onUse;
        setKeyFunction(CommandInfo::name);
    }

    // ----- metrics

    private int padX() {
        return theme.px(Theme.PAD);
    }

    private int cardPad() {
        return theme.px(Theme.PAD);
    }

    private int headH() {
        return theme.px(Theme.ROW_H);
    }

    private int lineH() {
        return theme.px(20);
    }

    private int codeH() {
        return theme.px(28);
    }

    private int textW() {
        return Math.max(theme.px(40), w - 2 * padX() - 2 * cardPad());
    }

    String expandedName() {
        return expanded;
    }

    String selectedName() {
        return kbName;
    }

    // ----- items

    @Override
    protected void itemsChanged() {
        anchorName = null;
        if (expanded != null && indexOf(expanded) < 0) {
            // filtered away while open: it must not come back open with nobody remembering it was
            RowState gone = states.get(expanded);
            if (gone != null) {
                gone.open.snap(0f);
            }
            expanded = null;
        }
        if (kbName != null && indexOf(kbName) < 0) {
            kbName = null;
        }
    }

    private int indexOf(String name) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private RowState state(CommandInfo c) {
        return states.computeIfAbsent(c.name(), k -> new RowState());
    }

    private Detail detailFor(CommandInfo c) {
        RowState s = state(c);
        int tw = textW();
        if (s.detail == null || s.detail.width != tw) {
            s.detail = buildDetail(c, tw);
        }
        return s.detail;
    }

    private Detail buildDetail(CommandInfo c, int tw) {
        List<HelpIndex.Line> lines = HelpIndex.lines(c.longDesc());
        if (lines.isEmpty()) {
            boolean hasShort = c.shortDesc() != null && !c.shortDesc().isBlank();
            lines = List.of(new HelpIndex.Line(HelpIndex.Kind.TEXT, hasShort ? c.shortDesc() : "No more details for this command."));
        }
        TextMeasurer m = theme.measurer;
        List<Piece> pieces = new ArrayList<>();
        int yy = theme.px(Theme.SPACE_2);
        int gap = theme.px(Theme.SPACE_1);
        for (HelpIndex.Line l : lines) {
            switch (l.kind()) {
                case TEXT -> {
                    List<String> wrapped = wrap(m, l.text(), theme.body, tw);
                    int h = wrapped.size() * lineH();
                    pieces.add(new Piece(l.kind(), wrapped, "", yy, h));
                    yy += h + gap;
                }
                case HEADING -> {
                    int h = theme.px(18);
                    pieces.add(new Piece(l.kind(), List.of(l.text()), "", yy, h));
                    yy += h + gap;
                }
                case USAGE -> {
                    pieces.add(new Piece(l.kind(), List.of(l.text()), l.note(), yy, codeH()));
                    yy += codeH() + gap;
                }
                case GAP -> yy += theme.px(6);
                default -> {
                }
            }
        }
        return new Detail(tw, pieces, yy - (pieces.isEmpty() ? 0 : gap) + cardPad());
    }

    // greedy word wrap. a word wider than the line is cut by characters, there is no hyphenation here
    static List<String> wrap(TextMeasurer m, String s, TextStyle style, float maxW) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            String tryLine = line.length() == 0 ? word : line + " " + word;
            if (m.measure(tryLine, style) <= maxW) {
                line.setLength(0);
                line.append(tryLine);
                continue;
            }
            if (line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
            }
            String rest = word;
            while (m.measure(rest, style) > maxW && rest.length() > 1) {
                int cut = rest.length() - 1;
                while (cut > 1 && m.measure(rest.substring(0, cut), style) > maxW) {
                    cut--;
                }
                out.add(rest.substring(0, cut));
                rest = rest.substring(cut);
            }
            line.append(rest);
        }
        if (line.length() > 0 || out.isEmpty()) {
            out.add(line.toString());
        }
        return out;
    }

    // ----- geometry

    private int heightOf(int i) {
        CommandInfo c = items.get(i);
        RowState s = states.get(c.name());
        if (s == null) {
            return headH();
        }
        float v = s.open.value(now);
        if (v <= 0.001f) {
            return headH();
        }
        return headH() + Math.round(detailFor(c).height * v);
    }

    private float rowTop(int idx) {
        float top = 0f;
        for (int i = 0; i < idx; i++) {
            top += heightOf(i);
        }
        return top;
    }

    @Override
    protected int contentHeight() {
        int total = theme.px(Theme.SPACE_2);
        for (int i = 0; i < items.size(); i++) {
            total += heightOf(i);
        }
        return total;
    }

    @Override
    protected int step() {
        return headH();
    }

    // row under the y, or -1. locRel is how far down inside that row the point is
    private int locate(float my) {
        float rel = my - y + scroller.offset(now);
        float top = 0f;
        for (int i = 0; i < items.size(); i++) {
            int rh = heightOf(i);
            if (rel < top + rh) {
                locRel = rel - top;
                return rel >= 0 ? i : -1;
            }
            top += rh;
        }
        return -1;
    }

    private int pieceAt(int row, float mx, float relInRow) {
        if (row < 0 || row >= items.size() || relInRow < headH()) {
            return -1;
        }
        // the hover fill hangs a SPACE_2 out past the text on each side, so the click area does too
        float cx = x + padX() + cardPad() - theme.px(Theme.SPACE_2);
        if (mx < cx || mx >= cx + textW() + 2 * theme.px(Theme.SPACE_2)) {
            return -1;
        }
        Detail d = detailFor(items.get(row));
        float rel = relInRow - headH();
        for (int p = 0; p < d.pieces.size(); p++) {
            Piece pc = d.pieces.get(p);
            if (pc.kind() == HelpIndex.Kind.USAGE && rel >= pc.y() && rel < pc.y() + pc.h()) {
                return p;
            }
        }
        return -1;
    }

    // screen centre of a row's header, for tests and previews to click on. null if the command is not in the list
    float[] headerCenter(String name) {
        int idx = indexOf(name);
        if (idx < 0) {
            return null;
        }
        return new float[] {x + w / 2f, y + rowTop(idx) - scroller.offset(now) + headH() / 2f};
    }

    // screen centre of the nth usage line of a command, same idea
    float[] usageCenter(String name, int nth) {
        int idx = indexOf(name);
        if (idx < 0) {
            return null;
        }
        int seen = 0;
        for (Piece pc : detailFor(items.get(idx)).pieces) {
            if (pc.kind() == HelpIndex.Kind.USAGE && seen++ == nth) {
                float cy = y + rowTop(idx) - scroller.offset(now) + headH() + pc.y() + pc.h() / 2f;
                return new float[] {x + padX() + cardPad() + textW() / 2f, cy};
            }
        }
        return null;
    }

    // ----- open and close

    void toggle(String name) {
        int idx = indexOf(name);
        if (idx < 0) {
            return;
        }
        float topNow = rowTop(idx);
        float screenY = topNow - scroller.offset(now);
        CommandInfo c = items.get(idx);
        boolean closing = name.equals(expanded);
        if (expanded != null) {
            RowState old = states.get(expanded);
            if (old != null) {
                old.open.set(0f, now, Theme.ANIM);
            }
        }
        float landY = screenY;
        if (closing) {
            expanded = null;
            // a keyboard close with the header scrolled out of sight must not leave it pinned out of sight
            landY = Math.max(0f, Math.min(landY, h - headH()));
        } else {
            expanded = name;
            RowState s = state(c);
            s.open.set(1f, now, Theme.ANIM);
            // all of the open row on screen if it fits, else its top at the top
            float full = headH() + detailFor(c).height;
            landY = full <= h ? Math.min(screenY, h - full) : 0f;
            landY = Math.max(0f, landY);
        }
        anchorName = name;
        anchor.snap(screenY);
        anchor.set(landY, now, Theme.ANIM);
    }

    private boolean animating() {
        for (RowState s : states.values()) {
            if (s.open.running(now)) {
                return true;
            }
        }
        return anchor.running(now);
    }

    private void applyAnchor() {
        if (anchorName == null) {
            return;
        }
        int idx = indexOf(anchorName);
        if (idx < 0) {
            anchorName = null;
            return;
        }
        scroller.snapTo(rowTop(idx) - anchor.value(now));
        if (!animating()) {
            anchorName = null;
        }
    }

    // ----- drawing

    @Override
    public void draw(Canvas c, Frame f) {
        stamp(f);
        resize();
        applyAnchor();
        resize();
        float off = scroller.offset(now);
        int n = items.size();

        int first = -1;
        int last = 0;
        float firstTop = 0f;
        float top = 0f;
        for (int i = 0; i < n; i++) {
            float bottom = top + heightOf(i);
            if (bottom > off && top < off + h) {
                if (first < 0) {
                    first = i;
                    firstTop = top;
                }
                last = i + 1;
            }
            if (top >= off + h) {
                break;
            }
            top = bottom;
        }
        if (first >= 0) {
            stampVisible(first, last, now);
        }

        boolean overBar = scroller.scrollable() && f.mouseX() >= x + w - theme.px(14);
        int hoverIdx = -1;
        float hoverRel = 0f;
        if (hovered && !overBar) {
            hoverIdx = locate(f.mouseY());
            hoverRel = locRel;
        }

        c.pushClip(x, y, w, h);
        if (n > 0) {
            // one card around every row, as tall as they are (the contentHeight's trailing SPACE_2 stays as air under
            // it). it scrolls with them, so its edges are only there while the first and last row are
            theme.card(c, x + padX(), Math.round(y - off), w - 2 * padX(), contentHeight() - theme.px(Theme.SPACE_2),
                    theme.pxf(Theme.CARD_RADIUS), false);
        }
        if (first >= 0) {
            float cursor = firstTop;
            for (int i = first; i < last; i++) {
                CommandInfo item = items.get(i);
                int rh = heightOf(i);
                Canvas g = rowCanvas(c, fadeOf(item, now));
                int ry = Math.round(y + cursor - off);
                boolean inHead = i == hoverIdx && hoverRel < headH();
                boolean kb = focused && item.name().equals(kbName);
                int hotPiece = i == hoverIdx ? pieceAt(i, f.mouseX(), hoverRel) : -1;
                try {
                    drawRow(g, item, i, ry, rh, inHead || kb, hotPiece);
                } catch (Throwable t) {
                    Safe.report("help row", t);
                }
                cursor += rh;
            }
        }
        c.popClip();
        drawEmpty(c);
        drawScrollbar(c);
    }

    private void drawRow(Canvas g, CommandInfo cmd, int idx, int ry, int rh, boolean hot, int hotPiece) {
        RowState s = state(cmd);
        float open = s.open.value(now);
        s.hover.set(hot ? 1f : 0f, now, Theme.ANIM_FAST);
        float hv = s.hover.value(now);
        int x0 = x + padX();
        int w0 = w - 2 * padX();
        int pad = cardPad();
        int head = headH();

        // inside the list's card an open row is just taller, with a hairline under its header. the hover fill takes the
        // card's corners, and only the header of an open row lights up, so only a closed last row reaches the bottom
        if (hv > 0.01f) {
            ListCard.fill(theme, g, x0, ry, w0, head, idx == 0, idx == items.size() - 1 && rh == head,
                    Theme.fade(theme.color(Theme.HOVER), hv * (1f - open * 0.6f)));
        }
        ListCard.separator(theme, g, x0, ry, w0, idx);
        if (open > 0.01f) {
            g.fillRect(x0 + 1, ry + head, w0 - 2, 1, Theme.fade(theme.color(Theme.HAIRLINE), open));
        }

        // name, then each alias as a chip, then the one line description in its own column
        float colW = Math.min(theme.px(260), w0 * 0.4f);
        float nameX = x0 + pad;
        float baseL = baseline(g, theme.label, ry, head);
        float nameRoom = colW - pad;
        String name = theme.measurer.ellipsize(cmd.name(), theme.label, nameRoom);
        float nameW = g.text(name, nameX, baseL, theme.label, theme.color(Theme.TEXT));
        List<String> names = cmd.names();
        float chipX = nameX + nameW + theme.px(Theme.SPACE_2);
        float chipEnd = nameX + nameRoom;
        float chipH = theme.px(18);
        int rim = theme.color(hv > 0.5f ? Theme.HAIRLINE_STRONG : Theme.HAIRLINE);
        for (int i = 1; i < names.size(); i++) {
            String chip = names.get(i);
            float cw = g.measure(chip, theme.smallLabel) + 2 * theme.px(Theme.SPACE_2);
            // a chip that does not fit whole is not drawn, a clipped one reads as a bug. a "+2" stands in for the rest
            if (chipX + cw > chipEnd) {
                chip = "+" + (names.size() - i);
                cw = g.measure(chip, theme.smallLabel) + 2 * theme.px(Theme.SPACE_2);
                if (chipX + cw > chipEnd) {
                    break;
                }
                i = names.size();
            }
            g.fillRoundRect(chipX, ry + (head - chipH) / 2f, cw, chipH, chipH / 2f, theme.color(Theme.RAISED));
            g.strokeRoundRect(chipX, ry + (head - chipH) / 2f, cw, chipH, chipH / 2f, rim);
            g.text(chip, chipX + theme.px(Theme.SPACE_2), baseline(g, theme.smallLabel, ry, head), theme.smallLabel, theme.color(Theme.TEXT_2));
            chipX += cw + theme.px(Theme.SPACE_1);
        }

        int chev = theme.px(Theme.ICON);
        float chevX = x0 + w0 - pad - chev;
        g.icon(open > 0.5f ? Icon.CHEVRON_DOWN : Icon.CHEVRON_RIGHT, chevX, ry + (head - chev) / 2f, chev, theme.color(Theme.TEXT_3));

        float descX = x0 + colW;
        float descRoom = chevX - theme.px(Theme.SPACE_3) - descX;
        if (descRoom > theme.px(24) && cmd.shortDesc() != null) {
            g.text(theme.measurer.ellipsize(cmd.shortDesc(), theme.body, descRoom), descX, baseline(g, theme.body, ry, head), theme.body, theme.color(Theme.TEXT_2));
        }

        if (open > 0.01f) {
            Detail d = detailFor(cmd);
            g.pushClip(x0, ry + head, w0, Math.max(0, rh - head));
            try {
                drawDetail(g, d, x0 + pad, ry + head, w0 - 2 * pad, open, hotPiece);
            } finally {
                g.popClip();
            }
        }
    }

    private void drawDetail(Canvas g, Detail d, float dx, float dy, float dw, float open, int hotPiece) {
        float r = theme.pxf(Theme.ROW_RADIUS);
        TextStyle code = theme.small.asTabular();
        for (int p = 0; p < d.pieces.size(); p++) {
            Piece pc = d.pieces.get(p);
            float py = dy + pc.y();
            switch (pc.kind()) {
                case TEXT -> {
                    for (int k = 0; k < pc.text().size(); k++) {
                        g.text(pc.text().get(k), dx, baseline(g, theme.body, py + k * lineH(), lineH()), theme.body,
                                Theme.fade(theme.color(Theme.TEXT_2), open));
                    }
                }
                case HEADING -> g.text(pc.text().get(0), dx, baseline(g, theme.smallLabel, py, pc.h()), theme.smallLabel,
                        Theme.fade(theme.color(Theme.TEXT_3), open));
                case USAGE -> {
                    d.hover[p].set(p == hotPiece ? 1f : 0f, now, Theme.ANIM_FAST);
                    float hv = d.hover[p].value(now);
                    // flat lines on the block, the only chrome is a hover fill: they are rows, not buttons
                    if (hv > 0.01f) {
                        g.fillRoundRect(dx - theme.px(Theme.SPACE_2), py, dw + 2 * theme.px(Theme.SPACE_2), pc.h(), r,
                                Theme.fade(theme.color(Theme.HOVER), open * hv));
                    }
                    float hintW = g.measure("Use", theme.small);
                    float right = dx + dw - hintW - theme.px(Theme.SPACE_3);
                    String line = theme.measurer.ellipsize(pc.text().get(0), code, right - dx);
                    float lineW = g.text(line, dx, baseline(g, code, py, pc.h()), code, Theme.fade(theme.color(Theme.TEXT), open));
                    // what the command does, in the quiet voice, only if there is room left for it
                    float noteX = dx + lineW + theme.px(Theme.SPACE_3);
                    if (!pc.note().isEmpty() && right - noteX > theme.px(32)) {
                        g.text(theme.measurer.ellipsize(pc.note(), theme.small, right - noteX), noteX,
                                baseline(g, theme.small, py, pc.h()), theme.small, Theme.fade(theme.color(Theme.TEXT_3), open));
                    }
                    if (hv > 0.01f) {
                        g.text("Use", dx + dw - hintW, baseline(g, theme.small, py, pc.h()), theme.small,
                                Theme.fade(theme.color(Theme.TEXT_2), open * hv));
                    }
                }
                default -> {
                }
            }
        }
    }

    // ----- input

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        if (!contains(mx, my)) {
            return false;
        }
        if (super.mouseDown(mx, my, button, mods)) {
            // thumb or track: the user is driving the scroll now
            anchorName = null;
            return true;
        }
        pressedIndex = -1;
        pressedPiece = -1;
        if (button != 0) {
            return true;
        }
        int i = locate(my);
        if (i < 0) {
            return true;
        }
        press(i);
        pressedPiece = locRel < headH() ? -1 : pieceAt(i, mx, locRel);
        if (locRel >= headH() && pressedPiece < 0) {
            // a click on the explanation text is not a click on the row, or reading would fold it shut
            pressedIndex = -1;
        }
        return true;
    }

    @Override
    public boolean mouseUp(float mx, float my, int button, int mods) {
        if (super.mouseUp(mx, my, button, mods)) {
            return true;
        }
        // dragged off sideways and let go: not a click
        int under = contains(mx, my) ? locate(my) : -1;
        int piece = under < 0 ? -1 : (locRel < headH() ? -1 : pieceAt(under, mx, locRel));
        boolean hit = button == 0 && releasedOn(under) && piece == pressedPiece;
        int was = pressedPiece;
        pressedIndex = -1;
        pressedPiece = -1;
        if (!hit) {
            return false;
        }
        CommandInfo c = items.get(under);
        if (was < 0) {
            Safe.run("help toggle", () -> toggle(c.name()));
        } else {
            String line = detailFor(c).pieces.get(was).text().get(0);
            Safe.run("help use", () -> onUse.accept(line));
        }
        return true;
    }

    @Override
    public boolean scroll(float mx, float my, float dy) {
        if (contains(mx, my) && scroller.scrollable()) {
            anchorName = null;
        }
        return super.scroll(mx, my, dy);
    }

    @Override
    public boolean keyDown(int key, int mods) {
        if (!focused || items.isEmpty()) {
            return super.keyDown(key, mods);
        }
        int sel = kbName == null ? -1 : indexOf(kbName);
        switch (key) {
            case Keys.DOWN -> sel = Math.min(items.size() - 1, sel + 1);
            case Keys.UP -> sel = Math.max(0, sel < 0 ? 0 : sel - 1);
            case Keys.ENTER, Keys.KP_ENTER, Keys.SPACE -> {
                if (sel >= 0) {
                    toggle(items.get(sel).name());
                }
                return true;
            }
            default -> {
                boolean handled = super.keyDown(key, mods);
                if (handled) {
                    // paging moved the view, the old selection is somewhere behind us
                    anchorName = null;
                    kbName = null;
                }
                return handled;
            }
        }
        kbName = items.get(sel).name();
        anchorName = null;
        float top = rowTop(sel);
        scroller.ensureVisible(top, top + headH(), now);
        return true;
    }

    @Override
    public void setFocused(boolean f) {
        super.setFocused(f);
        if (!f) {
            pressedIndex = -1;
        }
    }
}
