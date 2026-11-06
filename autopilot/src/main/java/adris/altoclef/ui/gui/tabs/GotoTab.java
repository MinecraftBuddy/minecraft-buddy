package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.BotFacade;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.WorldAccess;
import adris.altoclef.ui.gui.kit.Anim;
import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Clipboard;
import adris.altoclef.ui.gui.kit.Easing;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.VirtualList;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.Coords;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// three boxes and a go. everything typed is checked live against the same grammar #goto uses, so the red box and the
// straight line under it are never out of step with what the button would do
public final class GotoTab implements Tab {

    // the section heading is one label line tall
    private static final int HEADER_H = 20;
    private static final long MOTIF_NANOS = Anim.ms(400);
    // start, two gentle bends, end: a path seen from above
    private static final float[][] PATH = {{0.04f, 0.80f}, {0.36f, 0.76f}, {0.64f, 0.26f}, {0.96f, 0.22f}};

    private final Theme theme;
    private final BotFacade facade;
    private final ShellContext ctx;
    private final GotoField[] fields = new GotoField[3];
    private final Button here;
    private final Button clear;
    private final Button setGoal;
    private final Button go;
    private final VirtualList<GotoRecent.Entry> recent;
    private final Map<Integer, int[]> goZones = new HashMap<>();
    private final View view;

    private Coords.Resolved resolved = new Coords.Resolved(null, null, null, null, -1);
    private String submitError;
    private int submitField = -1;
    private String submitTexts = "";
    private long motifStart = -1;
    private List<GotoRecent.Entry> shownRecent = List.of();

    public GotoTab(Theme theme, BotFacade facade, ShellContext ctx) {
        this.theme = theme;
        this.facade = facade;
        this.ctx = ctx;
        for (int i = 0; i < 3; i++) {
            final int field = i;
            GotoField f = new GotoField(theme, text -> Coords.fieldError(field, text, pos()), () -> run(true));
            f.field().withClipboard(new SpreadClipboard());
            f.field().setOnChange(s -> {
                submitError = null;
                submitField = -1;
                refresh();
            });
            fields[i] = f;
        }
        fields[0].field().setPlaceholder("number or ~");
        fields[1].field().setPlaceholder("optional");
        fields[2].field().setPlaceholder("number or ~");
        here = new Button(theme, "My position", Button.Kind.GHOST, Icon.PIN, this::fillHere);
        clear = new Button(theme, "Clear", Button.Kind.GHOST, null, this::clearAll);
        setGoal = new Button(theme, "Set goal only", Button.Kind.SECONDARY, null, () -> run(false));
        go = new Button(theme, "Go", Button.Kind.PRIMARY, null, () -> run(true));
        recent = new VirtualList<>(theme, theme.px(Theme.ROW_H), this::drawRecent);
        recent.setSelectable(true);
        recent.setKeyFunction(e -> e);
        recent.onClick((e, i) -> fill(e));
        recent.onRowMouseDown(this::recentMouse);
        view = new View();
    }

    @Override
    public String id() {
        return "goto";
    }

    @Override
    public String title() {
        return "Go to";
    }

    @Override
    public Icon icon() {
        return Icon.GOTO;
    }

    @Override
    public Widget content() {
        return view;
    }

    @Override
    public void onShown() {
        motifStart = -1;
        refresh();
    }

    // ----- data

    private WorldAccess.Position pos() {
        try {
            return facade.world().playerPos();
        } catch (Throwable t) {
            Safe.report("goto player pos", t);
            return null;
        }
    }

    private String dimension() {
        try {
            String d = facade.world().dimension();
            return d == null ? "" : d;
        } catch (Throwable t) {
            Safe.report("goto dimension", t);
            return "";
        }
    }

    private Coords.Resolved resolveNow() {
        return Coords.resolve(fields[0].text(), fields[1].text(), fields[2].text(), pos());
    }

    // live: red boxes, the line under them, and the recent list when it changed
    private String texts() {
        return fields[0].text() + "|" + fields[1].text() + "|" + fields[2].text();
    }

    private void refresh() {
        // a nudge changes the text without telling onChange, so the nag also ends when the boxes are not what was sent
        if (submitError != null && !texts().equals(submitTexts)) {
            submitError = null;
            submitField = -1;
        }
        resolved = resolveNow();
        WorldAccess.Position p = pos();
        int missing = submitField >= 0 ? submitField : -1;
        for (int i = 0; i < 3; i++) {
            String text = fields[i].text();
            boolean bad = Coords.fieldError(i, text, p) != null
                    && !(fields[i].field().isFocused() && Coords.pending(text));
            fields[i].field().setInvalid(bad || i == missing);
        }
        List<GotoRecent.Entry> now = GotoRecent.snapshot();
        if (!now.equals(shownRecent)) {
            shownRecent = now;
            recent.setItems(now);
        }
        // the motif takes its place when empty, an invisible list must not be a tab stop
        recent.setVisible(!now.isEmpty());
    }

    private void fill(GotoRecent.Entry e) {
        fields[0].setValue(e.x() == null ? "" : e.x().toString());
        fields[1].setValue(e.y() == null ? "" : e.y().toString());
        fields[2].setValue(e.z() == null ? "" : e.z().toString());
        submitError = null;
        submitField = -1;
        refresh();
    }

    private void fillHere() {
        WorldAccess.Position p = pos();
        if (p == null) {
            ctx.toast(Result.fail("You are not in a world yet."));
            return;
        }
        for (int i = 0; i < 3; i++) {
            fields[i].setValue(Integer.toString(Coords.block(i, p)));
        }
        submitError = null;
        submitField = -1;
        refresh();
    }

    private void clearAll() {
        for (GotoField f : fields) {
            f.setValue("");
        }
        submitError = null;
        submitField = -1;
        refresh();
        ctx.focus(fields[0].field());
    }

    private void run(boolean walk) {
        Coords.Resolved r = resolveNow();
        if (!r.ok()) {
            submitError = r.error();
            submitField = -1;
            submitTexts = texts();
            refresh();
            return;
        }
        if (!r.complete()) {
            submitError = r.missing();
            submitField = r.missingField();
            submitTexts = texts();
            refresh();
            return;
        }
        Result res;
        try {
            res = walk ? facade.world().goTo(r.x(), r.y(), r.z()) : facade.world().setGoal(r.x(), r.y(), r.z());
        } catch (Throwable t) {
            Safe.report("goto action", t);
            res = Result.fail("Could not do that. Try #goto in chat.");
        }
        ctx.toast(res);
        if (res != null && res.ok()) {
            GotoRecent.push(r.x(), r.y(), r.z(), dimension());
            refresh();
        }
    }

    // the one line under the boxes: a mistake, what is missing, or how far it is
    String statusLine() {
        if (submitError != null) {
            return submitError;
        }
        if (liveError() != null) {
            return liveError();
        }
        if (resolved.complete()) {
            double d = Coords.distance(resolved, pos());
            String dim = Coords.dimensionName(dimension());
            return d < 0 ? "Ready to go" : "Straight line " + Coords.blocks(d) + (dim.isEmpty() ? "" : " · " + dim);
        }
        String missing = resolved.missing();
        return missing != null ? missing : "Enter x and z, or just a y level";
    }

    boolean statusIsError() {
        return submitError != null || liveError() != null;
    }

    // a half typed "-" is not an error yet
    private String liveError() {
        int f = resolved.errorField();
        if (resolved.error() == null || (f >= 0 && fields[f].field().isFocused() && Coords.pending(fields[f].text()))) {
            return null;
        }
        return resolved.error();
    }

    // ----- paste

    // a paste of "x y z" into any box spreads over all three. the box's own char filter would turn "120 64" into
    // "12064", so the clipboard door does the splitting and hands the field nothing
    private final class SpreadClipboard implements Clipboard {

        @Override
        public String get() {
            String raw = ctx.clipboard() == null ? "" : ctx.clipboard().get();
            List<String> parts = Coords.pasteParts(raw);
            if (parts.size() == 2) {
                spread(parts.get(0), "", parts.get(1));
                return "";
            }
            if (parts.size() == 3) {
                spread(parts.get(0), parts.get(1), parts.get(2));
                return "";
            }
            List<String> one = Coords.split(raw);
            if (one.size() == 1 && Coords.isToken(one.get(0))) {
                return Coords.tidy(one.get(0));
            }
            // not coordinates: leave the boxes alone, but say so, or the paste looks broken
            if (!raw.isBlank()) {
                ctx.toast(Result.fail("Not coordinates. Paste x y z, like 120 64 -30."));
            }
            return "";
        }

        @Override
        public void set(String text) {
            if (ctx.clipboard() != null) {
                ctx.clipboard().set(text);
            }
        }
    }

    private void spread(String x, String y, String z) {
        fields[0].setValue(Coords.tidy(x));
        fields[1].setValue(Coords.tidy(y));
        fields[2].setValue(Coords.tidy(z));
        submitError = null;
        submitField = -1;
        refresh();
    }

    // ----- recent rows

    private boolean recentMouse(GotoRecent.Entry e, int index, float mx, float my, int button, int mods) {
        if (button != 0) {
            return true;
        }
        int[] z = goZones.get(index);
        if (z != null && mx >= z[0] && mx < z[0] + z[2] && my >= z[1] && my < z[1] + z[3]) {
            fill(e);
            // the same numbers in another dimension are somewhere else entirely
            if (sameDimension(e)) {
                run(true);
            } else {
                ctx.toast("That spot is in " + Coords.dimensionName(e.dimension()) + ". Switch dimensions to go there.", null);
            }
        } else {
            fill(e);
        }
        return true;
    }

    private boolean sameDimension(GotoRecent.Entry e) {
        String now = dimension();
        // a spot saved without a dimension name has nothing to disagree with
        return now.isEmpty() || e.dimension() == null || e.dimension().isEmpty() || now.equals(e.dimension());
    }

    private void drawRecent(Canvas c, Frame f, GotoRecent.Entry e, int index, int rx, int ry, int rw, int rh, boolean hot) {
        if (hot) {
            ListCard.fill(theme, c, rx, ry, rw, rh, index == 0, index == shownRecent.size() - 1, theme.color(Theme.HOVER));
        }
        ListCard.separator(theme, c, rx, ry, rw, index);
        TextStyle body = theme.body.asTabular();
        TextStyle small = theme.small.asTabular();
        float tx = rx + theme.px(Theme.PAD);
        float base = mid(c, body, ry, rh);
        String coords = GotoRecent.label(e);
        c.text(coords, tx, base, body, theme.color(Theme.TEXT));
        float cw = c.measure(coords, body);

        int gw = theme.px(40);
        int gh = theme.px(Theme.CONTROL_H - Theme.SPACE_1);
        int gx = rx + rw - theme.px(Theme.SPACE_2) - gw;
        int gy = ry + (rh - gh) / 2;
        goZones.put(index, new int[]{gx, gy, gw, gh});
        boolean live = sameDimension(e);
        boolean over = live && f.mouseX() >= gx && f.mouseX() < gx + gw && f.mouseY() >= gy && f.mouseY() < gy + gh;
        if (over) {
            c.fillRoundRect(gx, gy, gw, gh, theme.pxf(Theme.CONTROL_RADIUS), theme.color(Theme.PRESSED));
        }
        float lw = c.measure("Go", theme.label);
        int goCol = !live ? theme.color(Theme.TEXT_3) : over ? theme.color(Theme.TEXT) : theme.color(Theme.TEXT_2);
        c.text("Go", gx + (gw - lw) / 2f, mid(c, theme.label, gy, gh), theme.label, goCol);

        String dim = Coords.dimensionName(e.dimension());
        String tail = " · " + (dim.isEmpty() ? "" : dim + " · ") + GotoRecent.ago(e.millis(), GotoRecent.clock.getAsLong());
        float room = gx - theme.px(Theme.SPACE_2) - (tx + cw);
        c.text(c.ellipsize(tail, small, Math.max(0, room)), tx + cw, base, small, theme.color(Theme.TEXT_2));
    }

    private static float mid(Canvas c, TextStyle s, float top, float height) {
        return top + height / 2f + (c.ascent(s) - c.descent(s)) / 2f;
    }

    // ----- the view

    private final class View extends Widget {

        private final List<Widget> kids;
        private int labelTop;
        private int statusTop;
        private int cardBottom;
        private int headerTop;

        View() {
            super(GotoTab.this.theme);
            kids = List.of(fields[0], fields[1], fields[2], go, setGoal, here, clear, recent);
        }

        @Override
        protected void layout() {
            int pad = theme.px(Theme.PAD);
            int gap = theme.px(Theme.GAP);
            int ch = theme.px(Theme.CONTROL_H);
            int cardPad = theme.px(Theme.PAD);
            int inner = w - 2 * pad - 2 * cardPad;
            int left = x + pad + cardPad;
            labelTop = y + pad + cardPad;
            int fy = labelTop + theme.px(16) + theme.px(Theme.SPACE_1);
            int fw = Math.max(1, (inner - 2 * gap) / 3);
            for (int i = 0; i < 3; i++) {
                fields[i].setBounds(left + i * (fw + gap), fy, fw, ch);
            }
            statusTop = fy + ch + theme.px(Theme.SPACE_2);
            int by = statusTop + theme.px(16) + theme.px(Theme.SPACE_3);
            int gw = Math.max(go.preferredWidth(), theme.px(72));
            go.setBounds(left, by, gw, ch);
            setGoal.setBounds(go.x() + gw + gap, by, setGoal.preferredWidth(), ch);
            here.setBounds(setGoal.x() + setGoal.w() + gap, by, here.preferredWidth(), ch);
            clear.setBounds(here.x() + here.w() + gap, by, clear.preferredWidth(), ch);
            cardBottom = by + ch + cardPad;
            headerTop = cardBottom + theme.px(Theme.SECTION);
            int lt = headerTop + theme.px(HEADER_H) + theme.px(Theme.SECTION_BELOW);
            // widened by the scrollbar strip so the card is as wide as the one above it
            recent.setBounds(x + pad, lt, w - 2 * pad + ListCard.gutter(theme), Math.max(0, y + h - lt - theme.px(Theme.PAD)));
        }

        @Override
        public List<Widget> children() {
            return kids;
        }

        @Override
        public void tick() {
            super.tick();
            Safe.run("goto refresh", GotoTab.this::refresh);
        }

        @Override
        public void draw(Canvas c, Frame f) {
            int pad = theme.px(Theme.PAD);
            theme.card(c, x + pad, y + pad, w - 2 * pad, cardBottom - y - pad, theme.pxf(Theme.CARD_RADIUS), false);
            String[] names = {"X", "Y", "Z"};
            for (int i = 0; i < 3; i++) {
                c.text(names[i], fields[i].x(), mid(c, theme.smallLabel, labelTop, theme.px(16)),
                        theme.smallLabel, theme.color(Theme.TEXT_3));
            }
            for (Widget k : kids) {
                if (k != recent) {
                    k.draw(c, f);
                }
            }
            drawStatus(c);
            if (shownRecent.isEmpty()) {
                drawMotif(c, f, x + pad, cardBottom, w - 2 * pad, y + h - cardBottom - pad);
                return;
            }
            float hy = mid(c, theme.label, headerTop, theme.px(HEADER_H));
            float adv = c.text("Recent", x + pad, hy, theme.label, theme.color(Theme.TEXT));
            c.text(" · " + shownRecent.size(), x + pad + adv, hy, theme.label, theme.color(Theme.TEXT_3));
            ListCard.card(theme, c, recent, f.nowNanos(), shownRecent.size() * recent.rowHeight());
            recent.draw(c, f);
        }

        private void drawStatus(Canvas c) {
            String line = statusLine();
            int col = statusIsError() ? theme.color(Theme.DANGER) : theme.color(Theme.TEXT_3);
            int left = fields[0].x();
            String shown = c.ellipsize(line, theme.small.asTabular(), w - 2 * (left - x));
            c.text(shown, left, mid(c, theme.small, statusTop, theme.px(16)), theme.small.asTabular(), col);
        }

        // a path drawn from above, start to end, once. it is the empty state, so it stays calm
        private void drawMotif(Canvas c, Frame f, float rx, float ry, float rw, float rh) {
            float capH = theme.px(20);
            float bw = Math.min(rw, theme.px(220));
            float bh = Math.min(Math.max(theme.px(36), rh - capH - theme.px(12)), theme.px(88));
            float by = ry + theme.px(Theme.SECTION);
            float bx = rx + (rw - bw) / 2f;
            if (motifStart < 0) {
                motifStart = f.nowNanos();
            }
            float p = Anim.enabled ? Easing.outCubic((f.nowNanos() - motifStart) / (float) MOTIF_NANOS) : 1f;

            float r = Math.max(4f, theme.px(5));
            float[][] pts = samplePath(bx + r, by + r, bw - 2 * r, bh - 2 * r);
            float[] cum = new float[pts.length];
            for (int i = 1; i < pts.length; i++) {
                cum[i] = cum[i - 1] + (float) Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]);
            }
            float t = Math.max(3f, theme.px(3));
            float reach = p * cum[cum.length - 1];
            // a disc every pixel or so along the curve: smooth because the dabs overlap, no line primitive needed
            float nextDab = 0f;
            for (int i = 0; i < pts.length && cum[i] <= reach; i++) {
                if (cum[i] >= nextDab) {
                    c.fillRoundRect(pts[i][0] - t / 2f, pts[i][1] - t / 2f, t, t, t / 2f, theme.color(Theme.HAIRLINE_STRONG));
                    nextDab = cum[i] + 1f;
                }
            }
            float[] first = pts[0];
            float[] last = pts[pts.length - 1];
            dot(c, first[0], first[1], r, theme.color(Theme.PATH_START), 1f);
            // the red end lands as the line reaches it
            dot(c, last[0], last[1], r, theme.color(Theme.PATH_END), Math.max(0f, Math.min(1f, (p - 0.9f) * 10f)));

            String cap = "Nowhere yet. Pick a spot and press Go.";
            float cw = c.measure(cap, theme.body);
            c.text(cap, rx + (rw - cw) / 2f, mid(c, theme.body, by + bh + theme.px(16), capH), theme.body, theme.color(Theme.TEXT_3));
        }

        // PATH with its two corners rounded off: straight, curve, straight, curve, straight, sampled finely
        private float[][] samplePath(float ix, float iy, float iw, float ih) {
            int n = PATH.length;
            float[][] p = new float[n][2];
            for (int i = 0; i < n; i++) {
                p[i][0] = ix + PATH[i][0] * iw;
                p[i][1] = iy + PATH[i][1] * ih;
            }
            List<float[]> out = new java.util.ArrayList<>();
            float[] cursor = p[0];
            for (int i = 1; i < n - 1; i++) {
                float in = (float) Math.hypot(p[i][0] - p[i - 1][0], p[i][1] - p[i - 1][1]);
                float outLen = (float) Math.hypot(p[i + 1][0] - p[i][0], p[i + 1][1] - p[i][1]);
                float cut = Math.min(in, outLen) * 0.45f;
                float[] a = {p[i][0] + (p[i - 1][0] - p[i][0]) * cut / in, p[i][1] + (p[i - 1][1] - p[i][1]) * cut / in};
                float[] b = {p[i][0] + (p[i + 1][0] - p[i][0]) * cut / outLen, p[i][1] + (p[i + 1][1] - p[i][1]) * cut / outLen};
                line(out, cursor, a);
                int steps = Math.max(8, (int) (cut * 3));
                for (int k = 1; k <= steps; k++) {
                    float u = k / (float) steps;
                    float v = 1f - u;
                    out.add(new float[]{v * v * a[0] + 2 * u * v * p[i][0] + u * u * b[0], v * v * a[1] + 2 * u * v * p[i][1] + u * u * b[1]});
                }
                cursor = b;
            }
            line(out, cursor, p[n - 1]);
            return out.toArray(new float[0][]);
        }

        private void line(List<float[]> out, float[] from, float[] to) {
            int steps = Math.max(1, (int) (Math.hypot(to[0] - from[0], to[1] - from[1]) * 2));
            for (int k = out.isEmpty() ? 0 : 1; k <= steps; k++) {
                float u = k / (float) steps;
                out.add(new float[]{from[0] + (to[0] - from[0]) * u, from[1] + (to[1] - from[1]) * u});
            }
        }

        private void dot(Canvas c, float cx, float cy, float r, int argb, float a) {
            if (a <= 0f) {
                return;
            }
            c.fillRoundRect(cx - r * 2f, cy - r * 2f, r * 4f, r * 4f, r * 2f, Theme.alpha(argb, 0.25f * a));
            c.fillRoundRect(cx - r, cy - r, r * 2f, r * 2f, r, Theme.alpha(argb, a));
        }
    }

    // test hooks
    GotoField fieldAt(int i) {
        return fields[i];
    }

    Button goButton() {
        return go;
    }

    Button hereButton() {
        return here;
    }

    Button clearButton() {
        return clear;
    }

    Button setGoalButton() {
        return setGoal;
    }

    VirtualList<GotoRecent.Entry> recentList() {
        return recent;
    }

    int[] goZone(int index) {
        return goZones.get(index);
    }

    Coords.Resolved current() {
        return resolved;
    }
}
