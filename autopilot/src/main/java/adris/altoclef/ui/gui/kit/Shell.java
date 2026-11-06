package adris.altoclef.ui.gui.kit;

import adris.altoclef.ui.gui.facade.KeyNames;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.StatusAccess;
import adris.altoclef.ui.gui.model.KeyBind;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

// the chrome around the tabs: panel, sidebar, header with search, status strip, toast layer and the overlay layer for
// dropdowns. it also owns focus and routes every key and click, so a tab never has to care who is focused. the scrim
// behind the panel is the screen's job. everything a tab, the status facade or a callback throws is caught and logged
// once, the panel stays up
public class Shell extends Widget implements ShellContext {

    // design px, about the height of the word it replaced plus its descender room
    private static final int LOGO = 26;

    private final List<Tab> tabs;
    private final Widget[] contents;
    private final StatusAccess status;
    private final KeyNames keyNames;
    private final KeyBind openKey;
    private final Clipboard clipboard;
    private final Runnable onCloseRequest;

    private final SearchField search;
    private final KeyCapture keyCapture;
    private final Segmented appearance;
    private final Button stop;
    private final Toast toast;
    private final List<Widget> kids = new ArrayList<>();
    private final FadeCanvas fade = new FadeCanvas();
    private final FadeCanvas contentFade = new FadeCanvas();

    private final Spring openAnim = new Spring(1f);
    private final Spring barY = new Spring(0f);
    private final Spring contentAnim = new Spring(1f);
    private final Anim[] tabHover;
    private final Anim[] tabActive;
    private final Anim busyAnim = new Anim(0f);

    private int active;
    private int hoverTab = -1;
    private Widget focused;
    private Widget overlay;
    private boolean closing;
    private boolean closeFired;
    private long lastNow;
    private Consumer<Boolean> onAppearanceChanged;
    private int winW;
    private int winH;
    private boolean keyCaption = true;

    private StatusAccess.Activity activity = new StatusAccess.Activity(false, "Idle", "", null);
    private float lineW;
    private float detailW;
    private float phaseW;

    private int sideW;
    private int headerH;
    // the title screen's logo in the sidebar header instead of the word. baked at the device size it's drawn at, a
    // new scale bakes a new one (the old texture waits for the atlases' next release, it's 26 px, whatever).
    // null + logoBroken = the png didn't load, so the header falls back to the word
    private PixelImage logo;
    private int logoPx;
    private boolean logoBroken;
    private int statusH;
    private int contentX;
    private int contentY;
    private int contentW;
    private int contentH;

    // a user-dragged size, -1 until they touch an edge. survives window resizes, which is the point of dragging
    private int manualW = -1;
    private int manualH = -1;
    private int resizeDir;
    private int resizeStartW;
    private int resizeStartH;
    private int resizeStartX;
    private int resizeStartY;
    private float resizeGrabX;
    private float resizeGrabY;
    private boolean resizing;
    private boolean moving;
    private int movePanelX;
    private int movePanelY;
    private float moveGrabX;
    private float moveGrabY;

    private static final int EDGE_LEFT = 1;
    private static final int EDGE_RIGHT = 2;
    private static final int EDGE_TOP = 4;
    private static final int EDGE_BOTTOM = 8;

    public Shell(Theme theme, List<Tab> tabs, StatusAccess status, KeyNames keyNames, int currentOpenKey,
                 IntConsumer onRebind, Clipboard clipboard, Runnable onCloseRequest) {
        super(theme);
        this.tabs = List.copyOf(tabs);
        this.status = status;
        this.keyNames = keyNames;
        this.openKey = new KeyBind(currentOpenKey);
        this.clipboard = clipboard;
        this.onCloseRequest = onCloseRequest;
        TextField.defaultClipboard = clipboard;

        contents = new Widget[this.tabs.size()];
        tabHover = new Anim[this.tabs.size()];
        tabActive = new Anim[this.tabs.size()];
        for (int i = 0; i < contents.length; i++) {
            Tab t = this.tabs.get(i);
            Widget content = null;
            try {
                content = t.content();
            } catch (Throwable e) {
                Safe.report("tab content " + i, e);
            }
            contents[i] = content != null ? content : new Blank(theme);
            tabHover[i] = new Anim(0f);
            tabActive[i] = new Anim(i == 0 ? 1f : 0f);
        }

        search = new SearchField(theme, "Search", q -> {
            if (this.tabs.isEmpty()) {
                return;
            }
            Tab t = this.tabs.get(active);
            Safe.run("tab search", () -> t.onSearch(q));
        });
        keyCapture = new KeyCapture(theme, openKey, keyNames, k -> {
            // the key is still physically down and will start repeating: swallow it until it comes up
            suppressedKey = k;
            if (onRebind != null) {
                Safe.run("rebind", () -> onRebind.accept(k));
            }
        }, this);
        appearance = new Segmented(theme, List.of("Light", "Dark"), theme.dark() ? 1 : 0, i -> {
            setDarkMode(i == 1);
            if (onAppearanceChanged != null) {
                Safe.run("appearance", () -> onAppearanceChanged.accept(theme.dark()));
            }
        });
        stop = new Button(theme, "Stop", Button.Kind.GHOST, Icon.STOP, this::stopClicked);
        toast = new Toast(theme);

        kids.add(contents.length > 0 ? contents[0] : new Blank(theme));
        kids.add(search);
        kids.add(keyCapture);
        kids.add(appearance);
        kids.add(stop);

        if (!this.tabs.isEmpty()) {
            Tab first = this.tabs.get(0);
            applySearchFor(first);
            Safe.run("tab shown", first::onShown);
        }
    }

    // a tab with no content widget still gets a slot
    private static final class Blank extends Widget {
        Blank(Theme theme) {
            super(theme);
        }

        @Override
        public void draw(Canvas c, Frame f) {
        }
    }

    // ----- accessors the screen and the tests use

    public int activeIndex() {
        return active;
    }

    public Widget focusedWidget() {
        return focused;
    }

    public SearchField search() {
        return search;
    }

    public Button stopButton() {
        return stop;
    }

    public KeyCapture keyCapture() {
        return keyCapture;
    }

    public Segmented appearance() {
        return appearance;
    }

    public void setOnAppearanceChanged(Consumer<Boolean> onChange) {
        onAppearanceChanged = onChange;
    }

    public void setDarkMode(boolean dark) {
        theme.setDark(dark, lastNow);
        appearance.setSelected(dark ? 1 : 0);
    }

    public Toast toast() {
        return toast;
    }

    public Widget overlay() {
        return overlay;
    }

    public boolean closing() {
        return closing;
    }

    public int contentX() {
        return contentX;
    }

    public int contentY() {
        return contentY;
    }

    public int contentW() {
        return contentW;
    }

    public int contentH() {
        return contentH;
    }

    // a group label takes a section's worth of room above it and a little under, so it reads as a heading and not
    // as a row that lost its icon
    private int groupH() {
        return theme.px(Theme.SECTION + Theme.SECTION_BELOW);
    }

    public int tabRowY(int i) {
        // with group labels the first section's room above its label is partly the header's own bottom margin;
        // without them the rows start under the header strip, not inside it
        int top = y + headerH + (groupStart(0) ? -theme.px(Theme.SPACE_2) : theme.px(Theme.SPACE_1));
        for (int n = 0; n <= i; n++) {
            if (groupStart(n)) {
                top += groupH();
            }
            if (n < i) {
                top += tabRowH() + theme.px(2);
            }
        }
        return top;
    }

    public int tabRowH() {
        int groups = 0;
        for (int i = 0; i < tabs.size(); i++) {
            if (groupStart(i)) {
                groups++;
            }
        }
        int room = h - statusH - headerH - theme.px(120 + tabs.size() * 2) - groups * groupH();
        return Math.max(theme.px(18), Math.min(theme.px(Theme.ROW_H), room / Math.max(1, tabs.size())));
    }

    private String group(int i) {
        return switch (tabs.get(i).id()) {
            case "get", "goto", "follow" -> "Actions";
            case "waypoints", "schematics", "stats" -> "Library";
            case "help", "settings" -> "Preferences";
            default -> "";
        };
    }

    // group labels cost a section each, and under about 480 design px that comes straight out of the rows. a 540p
    // window lands just under it, so there the sidebar is a plain list
    private boolean groupStart(int i) {
        return h >= theme.px(480) && !group(i).isEmpty() && (i == 0 || !group(i).equals(group(i - 1)));
    }

    public int sidebarW() {
        return sideW;
    }

    // the other capture control (settings tab) changed the bind, keep the close key honest
    public void setOpenKey(int key) {
        openKey.key = key;
    }

    // same, for a rebind that came from a key press (a tab's own KeyCapture): the key is still down and will repeat
    public void openKeyRebound(int key) {
        openKey.key = key;
        suppressedKey = key;
    }

    // guiKeyEnabled off means the key does nothing at all, closing included
    public void setOpenKeyEnabled(boolean enabled) {
        openKey.enabled = enabled;
    }

    // the screen forwards key releases so a freshly bound key held down does not close the panel through key repeat
    public void keyUp(int key) {
        if (key == suppressedKey) {
            suppressedKey = -2;
        }
    }

    private int suppressedKey = -2;
    private boolean disposed;

    public int openKey() {
        return openKey.key;
    }

    // ----- sizing

    // about 62% by 72% of the window, clamped, centred, and only until the player drags an edge (then their size
    // sticks). min and max are in design pixels so they scale with everything else
    public void setViewport(int windowW, int windowH) {
        // the screen may call this every frame, relaying out every time would also snap the sliding tab bar
        if (windowW == winW && windowH == winH && w > 0) {
            return;
        }
        winW = windowW;
        winH = windowH;
        int pw = manualW > 0 ? manualW : Math.round(windowW * 0.58f);
        int ph = manualH > 0 ? manualH : Math.round(windowH * 0.66f);
        pw = Math.max(theme.px(640), Math.min(theme.px(manualW > 0 ? 1500 : 1040), pw));
        ph = Math.max(theme.px(440), Math.min(theme.px(manualH > 0 ? 1000 : 680), ph));
        int margin = theme.px(8);
        pw = Math.min(pw, Math.max(1, windowW - 2 * margin));
        ph = Math.min(ph, Math.max(1, windowH - 2 * margin));
        setBounds((windowW - pw) / 2, (windowH - ph) / 2, pw, ph);
    }

    // which edge (or corner) the pointer is on, 0 when it is inside the panel proper
    private int edgeAt(float mx, float my) {
        int g = theme.px(6);
        int e = 0;
        if (mx >= x && mx < x + g) {
            e |= EDGE_LEFT;
        } else if (mx >= x + w - g && mx < x + w) {
            e |= EDGE_RIGHT;
        }
        if (my >= y && my < y + g) {
            e |= EDGE_TOP;
        } else if (my >= y + h - g && my < y + h) {
            e |= EDGE_BOTTOM;
        }
        return e;
    }

    private void resizePanel(float mx, float my) {
        int minW = theme.px(560);
        int minH = theme.px(360);
        int nx = resizeStartX;
        int ny = resizeStartY;
        int nw = resizeStartW;
        int nh = resizeStartH;
        if ((resizeDir & EDGE_RIGHT) != 0) {
            nw = Math.max(minW, resizeStartW + Math.round(mx - resizeGrabX));
        }
        if ((resizeDir & EDGE_BOTTOM) != 0) {
            nh = Math.max(minH, resizeStartH + Math.round(my - resizeGrabY));
        }
        // the left and top edges move the far side: the panel keeps its right/bottom pinned
        if ((resizeDir & EDGE_LEFT) != 0) {
            nw = Math.max(minW, resizeStartW - Math.round(mx - resizeGrabX));
            nx = resizeStartX + (resizeStartW - nw);
        }
        if ((resizeDir & EDGE_TOP) != 0) {
            nh = Math.max(minH, resizeStartH - Math.round(my - resizeGrabY));
            ny = resizeStartY + (resizeStartH - nh);
        }
        nw = Math.min(nw, Math.max(1, winW - nx));
        nh = Math.min(nh, Math.max(1, winH - ny));
        manualW = nw;
        manualH = nh;
        setBounds(nx, ny, nw, nh);
    }

    // drag the header strip to move the whole panel. the search box lives in the same strip, so it is excluded at
    // the moment the drag starts; once dragging, the panel follows the pointer
    private boolean inHeader(float mx, float my) {
        return my >= y && my < y + headerH && !search.contains(mx, my);
    }

    private void movePanel(float mx, float my) {
        int nx = movePanelX + Math.round(mx - moveGrabX);
        int ny = movePanelY + Math.round(my - moveGrabY);
        // keep a sliver of the header on screen so the panel can never be dragged somewhere unreachable
        nx = Math.max(-(w - theme.px(80)), Math.min(winW - theme.px(80), nx));
        ny = Math.max(0, Math.min(winH - theme.px(40), ny));
        setBounds(nx, ny, w, h);
    }

    @Override
    protected void layout() {
        sideW = theme.px(Theme.SIDEBAR_W);
        headerH = theme.px(Theme.HEADER_H);
        statusH = theme.px(Theme.STATUS_H);
        contentX = x + sideW + 1;
        contentY = y + headerH + 1;
        contentW = Math.max(0, w - sideW - 1);
        contentH = Math.max(0, y + h - statusH - contentY);

        placeContent(active);
        toast.setBounds(contentX, contentY, contentW, contentH);

        int sw = Math.min(theme.px(260), Math.max(theme.px(120), contentW - 2 * theme.px(Theme.PAD) - theme.px(120)));
        int ch = theme.px(Theme.CONTROL_H);
        search.setBounds(x + w - theme.px(Theme.PAD) - sw, y + (headerH - ch) / 2, sw, ch);

        // the key name is the useful half of the footer row: when the caption and the name cannot both fit (0.75
        // scale, "Right Shift"), the caption goes and the keycap takes the row
        int footerLabelW = Math.round(theme.measurer.measure("Open key", theme.small));
        int capW = sideW - 2 * theme.px(Theme.PAD) - footerLabelW - theme.px(Theme.GAP);
        keyCaption = capW >= keyCapture.neededWidth();
        if (!keyCaption) {
            capW = sideW - 2 * theme.px(Theme.PAD);
        }
        keyCapture.setBounds(x + sideW - theme.px(Theme.PAD) - capW, y + h - statusH - theme.px(Theme.PAD) - ch, capW, ch);
        appearance.setBounds(x + theme.px(Theme.PAD), keyCapture.y() - theme.px(Theme.GAP) - ch,
                sideW - 2 * theme.px(Theme.PAD), ch);

        // the resize grip lives in the corner, so the stop button stops short of it
        int sh = theme.px(28);
        int sbw = stop.preferredWidth();
        stop.setBounds(x + w - theme.px(Theme.PAD + Theme.SPACE_3) - sbw, y + h - statusH + (statusH - sh) / 2, sbw, sh);
        barY.snap(tabRowY(active) + tabRowH() / 2f);
    }

    private void placeContent(int i) {
        if (i >= 0 && i < contents.length) {
            Widget c = contents[i];
            Safe.run("tab layout", () -> c.setBounds(contentX, contentY, contentW, contentH));
        }
    }

    // ----- open and close

    public void open(long nowNanos) {
        if (disposed) {
            // reopened after dispose: the visible tab was told it went away, tell it it is back
            disposed = false;
            if (active >= 0 && active < tabs.size()) {
                Tab t = tabs.get(active);
                Safe.run("tab shown", t::onShown);
            }
        }
        closing = false;
        closeFired = false;
        openAnim.snap(0f);
        openAnim.set(1f, nowNanos, Theme.ANIM_SLOW);
        lastNow = nowNanos;
        // the key that opened us is still down: its repeats must not close us again, and the char that rides
        // along with a letter bind must not land in the search box
        suppressedKey = openKey.key;
    }

    public void close(long nowNanos) {
        if (closing) {
            return;
        }
        closing = true;
        closeFired = false;
        openAnim.set(0f, nowNanos, Theme.ANIM_SLOW);
        lastNow = nowNanos;
    }

    // the close animation has played out
    public boolean closed(long nowNanos) {
        return closing && !openAnim.running(nowNanos) && openAnim.target() == 0f;
    }

    // for the screen being removed: tells the visible tab it is going away
    public void dispose() {
        disposed = true;
        if (active >= 0 && active < tabs.size()) {
            Tab t = tabs.get(active);
            Safe.run("tab hidden", t::onHidden);
        }
    }

    // ----- tabs

    public void switchTab(int i) {
        if (i < 0 || i >= tabs.size() || i == active) {
            return;
        }
        blur();
        closeOverlay();
        Tab old = tabs.get(active);
        Safe.run("tab hidden", old::onHidden);
        active = i;
        kids.set(0, contents[i]);
        placeContent(i);
        Tab t = tabs.get(i);
        applySearchFor(t);
        Safe.run("tab shown", t::onShown);
        barY.set(tabRowY(i) + tabRowH() / 2f, lastNow, Theme.ANIM);
        contentAnim.snap(0f);
        contentAnim.set(1f, lastNow, Theme.ANIM);
    }

    // the search box belongs to the shell, the tab only says whether it wants one
    private void applySearchFor(Tab t) {
        boolean has = Safe.test("tab hasSearch", t::hasSearch);
        search.setVisible(has);
        search.setText("");
        try {
            search.setPlaceholder(t.searchPlaceholder());
        } catch (Throwable e) {
            Safe.report("tab placeholder", e);
        }
        if (has) {
            Safe.run("tab search reset", () -> t.onSearch(""));
        }
    }

    // ----- status

    private void stopClicked() {
        Result r = null;
        try {
            r = status.stop();
        } catch (Throwable e) {
            Safe.report("status stop", e);
            r = Result.fail("Could not stop. Try #stop in chat.");
        }
        if (r != null) {
            toast.show(r);
        }
    }

    private void pollStatus() {
        StatusAccess.Activity a;
        try {
            a = status.activity();
        } catch (Throwable e) {
            Safe.report("status activity", e);
            return;
        }
        if (a == null || a.equals(activity)) {
            return;
        }
        activity = a;
        // red only means something when there is something to stop
        stop.setKind(a.busy() ? Button.Kind.DANGER : Button.Kind.GHOST);
        TextMeasurer m = theme.measurer;
        lineW = a.line() == null || a.line().isEmpty() ? 0f : m.measure(a.line(), theme.body);
        detailW = a.detail() == null || a.detail().isEmpty() ? 0f : m.measure(a.detail(), theme.small);
        phaseW = a.gamerPhase() == null ? 0f : m.measure(a.gamerPhase(), theme.smallLabel);
    }

    // ----- ShellContext

    @Override
    public Theme theme() {
        return theme;
    }

    @Override
    public Clipboard clipboard() {
        return clipboard;
    }

    @Override
    public void toast(Result result) {
        toast.show(result);
    }

    @Override
    public void toast(String line, String command) {
        toast.show(line, command);
    }

    @Override
    public void focus(Widget widget) {
        if (widget == null) {
            blur();
            return;
        }
        // the flag check matters: a recycled row widget can be defocused behind our back and come straight back
        if (focused == widget && widget.isFocused()) {
            return;
        }
        if (focused != null && focused != widget) {
            focused.setFocused(false);
        }
        focused = widget;
        widget.setFocused(true);
    }

    @Override
    public void blur() {
        if (focused != null) {
            focused.setFocused(false);
            focused = null;
        }
    }

    @Override
    public void openOverlay(Widget w) {
        closeOverlay();
        overlay = w;
        w.setVisible(true);
    }

    @Override
    public void closeOverlay() {
        if (overlay != null) {
            overlay.setVisible(false);
            overlay = null;
        }
    }

    @Override
    public void requestClose() {
        close(lastNow);
    }

    // ----- drawing

    @Override
    public void draw(Canvas c, Frame f) {
        try {
            drawShell(c, f);
        } catch (Throwable t) {
            Safe.report("shell draw", t);
        }
    }

    private void drawShell(Canvas c, Frame f) {
        long now = f.nowNanos();
        lastNow = now;
        theme.update(now);
        if (closing && !closeFired && !openAnim.running(now) && openAnim.target() == 0f) {
            closeFired = true;
            if (onCloseRequest != null) {
                Safe.run("close request", onCloseRequest);
            }
        }
        float a = openAnim.value(now);
        if (a <= 0.003f) {
            return;
        }
        Canvas g = c;
        if (a < 0.999f) {
            g = fade.reset(c, a, (1f - a) * theme.px(8));
        }

        float r = theme.pxf(Theme.PANEL_RADIUS);
        g.shadow(x, y, w, h, r, theme.color(Theme.SHADOW));
        g.fillRoundRect(x, y, w, h, r, theme.color(Theme.PANEL));

        drawSidebar(g, f, now, r);
        drawHeader(g, f, now);

        g.pushClip(contentX, contentY, contentW, contentH);
        Widget content = kids.get(0);
        float contentA = contentAnim.value(now);
        Canvas target = contentA >= 0.999f ? g : contentFade.reset(g, contentA, (1f - contentA) * theme.px(6));
        Safe.run("tab draw", () -> content.draw(target, f));
        g.popClip();

        drawStatus(g, f, now);
        toast.draw(g, f);
        g.strokeRoundRect(x, y, w, h, r, Theme.fade(theme.color(Theme.HAIRLINE_STRONG), 0.5f));
        drawResizeGrip(g);

        Widget o = overlay;
        if (o != null && o.visible()) {
            Safe.run("overlay draw", () -> o.draw(drawTarget(c, a), f));
        }
    }

    // fresh FadeCanvas state for a nested draw, or the plain canvas once the panel is fully open
    private Canvas drawTarget(Canvas c, float a) {
        if (a >= 0.999f) {
            return c;
        }
        return fade.reset(c, a, (1f - a) * theme.px(8));
    }

    // the png is white, so the text colour as the tint gives a white logo in dark mode and a near black one in light
    private void drawLogo(Canvas g, int pad) {
        int lp = theme.px(LOGO);
        if (!logoBroken && (logo == null || logoPx != lp)) {
            try {
                logo = BrandArt.logo(lp);
                logoPx = lp;
            } catch (RuntimeException e) {
                Safe.report("sidebar logo", e);
                logo = null;
                logoBroken = true;
            }
        }
        if (logo == null) {
            g.text("soprano", x + pad, baselineIn(g, theme.title, y, headerH), theme.title, theme.color(Theme.TEXT));
            return;
        }
        g.image(logo, x + pad, y + Math.round((headerH - lp) / 2f), lp, lp, theme.color(Theme.TEXT));
    }

    private void drawSidebar(Canvas g, Frame f, long now, float r) {
        // only the top left corner is round: the panel's own corners cover the rest, so square off the others
        g.fillRoundRect(x, y, sideW, h - statusH, r, theme.color(Theme.SIDEBAR));
        g.fillRect(x + sideW / 2f, y, sideW / 2f, h - statusH, theme.color(Theme.SIDEBAR));
        g.fillRect(x, y + (h - statusH) / 2f, sideW / 2f, (h - statusH) / 2f, theme.color(Theme.SIDEBAR));
        g.fillRect(x + sideW, y, 1, h - statusH, theme.color(Theme.HAIRLINE));

        // one left edge for everything in here: the wordmark, the group labels, the row icons and the footer all
        // start at pad. rows are inset by half of it so the selection pill has a margin
        int pad = theme.px(Theme.PAD);
        int rowInset = theme.px(Theme.SPACE_2);
        drawLogo(g, pad);

        int rowH = tabRowH();
        // the selected row is the one lit thing in the sidebar, and it follows the same spring as the segment and
        // switch controls
        if (!tabs.isEmpty()) {
            float py = barY.value(now) - rowH / 2f;
            g.fillRoundRect(x + rowInset, py, sideW - 2 * rowInset, rowH, theme.pxf(Theme.ROW_RADIUS), theme.color(Theme.ACCENT));
        }
        for (int i = 0; i < tabs.size(); i++) {
            Tab t = tabs.get(i);
            tabHover[i].set(i == hoverTab ? 1f : 0f, now, Theme.ANIM_FAST);
            tabActive[i].set(i == active ? 1f : 0f, now, Theme.ANIM_FAST);
            float hv = tabHover[i].value(now);
            float av = tabActive[i].value(now);
            float ry = tabRowY(i);
            if (groupStart(i)) {
                g.text(group(i), x + pad, ry - theme.px(Theme.SECTION_BELOW) - g.descent(theme.smallLabel), theme.smallLabel, theme.color(Theme.TEXT_3));
            }
            float rx = x + rowInset;
            float rw = sideW - 2 * rowInset;
            if (hv > 0.01f && av < 0.99f) {
                g.fillRoundRect(rx, ry, rw, rowH, theme.pxf(Theme.ROW_RADIUS), Theme.fade(theme.color(Theme.HOVER), hv * 0.7f * (1f - av)));
            }
            int col = Theme.lerp(Theme.lerp(theme.color(Theme.TEXT_2), theme.color(Theme.TEXT), hv * 0.7f), theme.color(Theme.ON_ACCENT), av);
            int is = theme.px(Theme.ICON);
            Icon icon = tabIcon(t);
            if (icon != null) {
                g.icon(icon, x + pad, ry + (rowH - is) / 2f, is, col);
            }
            String title = tabTitle(t);
            g.text(title, x + pad + is + theme.px(Theme.SPACE_3), baselineIn(g, theme.label, ry, rowH), theme.label, col);
        }

        appearance.draw(g, f);
        if (keyCaption) {
            g.text("Open key", x + pad, baselineIn(g, theme.small, keyCapture.y(), keyCapture.h()), theme.small, theme.color(Theme.TEXT_3));
        }
        keyCapture.draw(g, f);
    }

    private static Icon tabIcon(Tab t) {
        try {
            return t.icon();
        } catch (Throwable e) {
            Safe.report("tab icon", e);
            return null;
        }
    }

    private static String tabTitle(Tab t) {
        try {
            String s = t.title();
            return s == null ? "" : s;
        } catch (Throwable e) {
            Safe.report("tab title", e);
            return "";
        }
    }

    // three dots climbing the bottom-right corner, the quiet way of saying "you can drag this"
    private void drawResizeGrip(Canvas g) {
        int m = theme.px(6);
        int s = theme.px(2);
        for (int i = 0; i < 3; i++) {
            int o = i * theme.px(4);
            g.fillRect(x + w - m - o, y + h - m - o, s, s, theme.color(Theme.HAIRLINE_STRONG));
        }
    }

    // the header is the same surface as the content, like a unified toolbar: only the hairline under it says where
    // the scrolling starts
    private void drawHeader(Canvas g, Frame f, long now) {
        g.fillRect(contentX, y + headerH, contentW, 1, theme.color(Theme.HAIRLINE));
        if (tabs.isEmpty()) {
            return;
        }
        int pad = theme.px(Theme.PAD);
        String title = tabTitle(tabs.get(active));
        float room = contentW - 2 * pad - (search.visible() ? search.w() + pad : 0);
        title = theme.measurer.ellipsize(title, theme.title, Math.max(0f, room));
        g.text(title, contentX + pad, baselineIn(g, theme.title, y, headerH), theme.title, theme.color(Theme.TEXT));
        if (search.visible()) {
            search.draw(g, f);
        }
    }

    private void drawStatus(Canvas g, Frame f, long now) {
        pollStatusIfIdle();
        int top = y + h - statusH;
        g.fillRect(x, top, w, 1, theme.color(Theme.HAIRLINE));
        int pad = theme.px(Theme.PAD);
        busyAnim.set(activity.busy() ? 1f : 0f, now, Theme.ANIM);
        float bv = busyAnim.value(now);
        int is = theme.px(12);
        float iy = top + 1 + (statusH - 1 - is) / 2f;
        g.icon(Icon.RING, x + pad, iy, is, Theme.fade(theme.color(Theme.HAIRLINE_STRONG), 1f - bv));
        g.icon(Icon.DOT, x + pad, iy, is, Theme.fade(theme.color(Theme.ACCENT), bv));

        float right = stop.x() - theme.px(Theme.GAP);
        String phase = activity.gamerPhase();
        if (phase != null) {
            float pw = phaseW + theme.px(16);
            float ph = theme.px(22);
            float px = right - pw;
            g.fillRoundRect(px, top + 1 + (statusH - 1 - ph) / 2f, pw, ph, ph / 2f, theme.color(Theme.RAISED));
            g.text(phase, px + theme.px(8), baselineIn(g, theme.smallLabel, top + 1, statusH - 1), theme.smallLabel, theme.color(Theme.TEXT_2));
            right = px - theme.px(Theme.GAP);
        }

        float tx = x + pad + is + theme.px(10);
        float room = Math.max(0f, right - tx);
        String line = activity.line() == null ? "" : activity.line();
        float base = baselineIn(g, theme.body, top + 1, statusH - 1);
        String shown = lineW <= room ? line : theme.measurer.ellipsize(line, theme.body, room);
        g.text(shown, tx, base, theme.body, theme.color(Theme.TEXT));
        String detail = activity.detail();
        if (detail != null && !detail.isEmpty() && lineW < room) {
            float dx = tx + lineW + theme.px(6);
            String dot = "\u00b7";
            float dotW = g.measure(dot, theme.body);
            float dRoom = right - dx - dotW - theme.px(6);
            if (dRoom > theme.px(24)) {
                g.text(dot, dx, base, theme.body, theme.color(Theme.TEXT_3));
                String d = detailW <= dRoom ? detail : theme.measurer.ellipsize(detail, theme.small, dRoom);
                g.text(d, dx + dotW + theme.px(6), baselineIn(g, theme.small, top + 1, statusH - 1), theme.small, theme.color(Theme.TEXT_2));
            }
        }
        stop.draw(g, f);
    }

    private void pollStatusIfIdle() {
        // tick polls every 50 ms, this only matters before the first tick
        if (!polled) {
            polled = true;
            pollStatus();
        }
    }

    private boolean polled;

    private static float baselineIn(Canvas c, TextStyle s, float top, float height) {
        return top + height / 2f + (c.ascent(s) - c.descent(s)) / 2f;
    }

    // ----- input

    @Override
    public List<Widget> children() {
        return kids;
    }

    @Override
    public void tick() {
        pollStatus();
        for (Widget k : kids) {
            Safe.run("tick", k::tick);
        }
    }

    @Override
    public Widget mouseMove(float mx, float my) {
        try {
            Widget o = overlay;
            if (o != null && o.visible()) {
                o.mouseMove(mx, my);
            }
            hoverTab = tabAt(mx, my);
            return super.mouseMove(mx, my);
        } catch (Throwable t) {
            Safe.report("shell mouseMove", t);
            return null;
        }
    }

    private int tabAt(float mx, float my) {
        if (mx < x + theme.px(8) || mx >= x + sideW - theme.px(8)) {
            return -1;
        }
        for (int i = 0; i < tabs.size(); i++) {
            float ry = tabRowY(i);
            if (my >= ry && my < ry + tabRowH()) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseDown(float mx, float my, int button, int mods) {
        try {
            Widget o = overlay;
            if (o != null && o.visible()) {
                if (o.contains(mx, my)) {
                    o.mouseDown(mx, my, button, mods);
                } else {
                    // the click that dismisses a popup does nothing else, or clicking the dropdown again would reopen it
                    closeOverlay();
                }
                return true;
            }
            if (!contains(mx, my)) {
                return false;
            }
            // grabbing an edge starts a resize, not a click into the tab under it
            int edge = button == 0 ? edgeAt(mx, my) : 0;
            if (edge != 0) {
                resizing = true;
                resizeDir = edge;
                resizeStartW = w;
                resizeStartH = h;
                resizeStartX = x;
                resizeStartY = y;
                resizeGrabX = mx;
                resizeGrabY = my;
                return true;
            }
            int t = tabAt(mx, my);
            if (t >= 0) {
                switchTab(t);
                return true;
            }
            // dragging the header moves the panel; the search box sitting in it still wants its click
            if (button == 0 && inHeader(mx, my)) {
                moving = true;
                movePanelX = x;
                movePanelY = y;
                moveGrabX = mx;
                moveGrabY = my;
                return true;
            }
            super.mouseDown(mx, my, button, mods);
            retarget(mx, my);
            return true;
        } catch (Throwable t) {
            Safe.report("shell mouseDown", t);
            return true;
        }
    }

    // after a click: whatever focus-on-click widget is under the pointer gets focus, otherwise focus is dropped
    private void retarget(float mx, float my) {
        List<Widget> all = new ArrayList<>();
        collectFocusable(all);
        all.add(keyCapture);
        Widget hit = null;
        for (int i = all.size() - 1; i >= 0; i--) {
            Widget k = all.get(i);
            if (k instanceof FocusOnClick && k.contains(mx, my)) {
                hit = k;
                break;
            }
        }
        if (hit == null) {
            blur();
        } else {
            focus(hit);
        }
    }

    @Override
    public boolean mouseUp(float mx, float my, int button, int mods) {
        try {
            resizing = false;
            moving = false;
            Widget o = overlay;
            if (o != null && o.visible()) {
                return o.mouseUp(mx, my, button, mods);
            }
            return super.mouseUp(mx, my, button, mods);
        } catch (Throwable t) {
            Safe.report("shell mouseUp", t);
            return false;
        }
    }

    @Override
    public boolean mouseDrag(float mx, float my, int button, float dx, float dy) {
        try {
            if (resizing) {
                resizePanel(mx, my);
                return true;
            }
            if (moving) {
                movePanel(mx, my);
                return true;
            }
            Widget o = overlay;
            if (o != null && o.visible()) {
                return o.mouseDrag(mx, my, button, dx, dy);
            }
            return super.mouseDrag(mx, my, button, dx, dy);
        } catch (Throwable t) {
            Safe.report("shell mouseDrag", t);
            return false;
        }
    }

    @Override
    public boolean scroll(float mx, float my, float dy) {
        try {
            Widget o = overlay;
            if (o != null && o.visible()) {
                o.scroll(mx, my, dy);
                return true;
            }
            return super.scroll(mx, my, dy);
        } catch (Throwable t) {
            Safe.report("shell scroll", t);
            return false;
        }
    }

    // search, the tab's content, then stop. the footer key capture is click only
    @Override
    public void collectFocusable(List<Widget> out) {
        if (search.visible()) {
            search.collectFocusable(out);
        }
        kids.get(0).collectFocusable(out);
        appearance.collectFocusable(out);
        stop.collectFocusable(out);
    }

    private void cycleFocus(boolean back) {
        List<Widget> list = new ArrayList<>();
        Safe.run("collect focusable", () -> collectFocusable(list));
        if (list.isEmpty()) {
            return;
        }
        int n = list.size();
        int i = list.indexOf(focused);
        int next = i < 0 ? (back ? n - 1 : 0) : (i + (back ? -1 : 1) + n) % n;
        focus(list.get(next));
    }

    @Override
    public boolean keyDown(int key, int mods) {
        try {
            return route(key, mods);
        } catch (Throwable t) {
            Safe.report("shell keyDown", t);
            return true;
        }
    }

    // focus on a widget that was hidden or recycled (a pooled settings row, say) is not focus any more
    private void validateFocus() {
        if (focused != null && (!focused.visible() || !focused.isFocused())) {
            blur();
        }
    }

    private boolean route(int key, int mods) {
        Widget o = overlay;
        if (o != null && o.visible()) {
            if (!o.keyDown(key, mods) && key == Keys.ESCAPE) {
                closeOverlay();
            }
            return true;
        }
        validateFocus();
        // a rebind in progress owns every key, tab and esc included
        if (focused instanceof KeyCapture kc && kc.capturing()) {
            boolean spent = Safe.test("key capture", () -> kc.keyDown(key, mods));
            // whatever ended the capture (bound, cancelled with esc, cleared) is still down and will repeat
            suppressedKey = key;
            return spent;
        }
        if (key == suppressedKey) {
            return true;
        }
        suppressedKey = -2;
        if (key == Keys.TAB) {
            cycleFocus(Keys.shift(mods));
            return true;
        }
        // ctrl 1..6 jumps to a tab, like a browser
        if (Keys.ctrl(mods) && key >= Keys.ONE && key < Keys.ONE + Math.min(9, tabs.size())) {
            switchTab(key - Keys.ONE);
            return true;
        }
        // enter in the search is the tab's to act on (the get tab adds the first match)
        if (focused == search && Keys.isEnter(key) && !tabs.isEmpty()) {
            Tab t = tabs.get(active);
            String q = search.text();
            Safe.run("search submit", () -> t.onSearchSubmit(q));
            return true;
        }
        if (key == Keys.ESCAPE) {
            if (focused != null) {
                if (Safe.test("esc focused", () -> focused.keyDown(key, mods))) {
                    return true;
                }
                blur();
                return true;
            }
            if (search.visible() && search.keyDown(key, mods)) {
                return true;
            }
            requestClose();
            return true;
        }
        if (focused != null) {
            Widget f = focused;
            if (Safe.test("key", () -> f.keyDown(key, mods))) {
                if (f instanceof KeyCapture) {
                    // enter or space just started a capture, the repeat must not bind itself
                    suppressedKey = key;
                }
                return true;
            }
        }
        // the open key closes it again, but never while you are typing: a bind on a letter would otherwise eat text
        if (openKey.closes(key) && !(focused instanceof TextField)) {
            requestClose();
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(char c) {
        try {
            Widget o = overlay;
            if (o != null && o.visible()) {
                o.charTyped(c);
                return true;
            }
            validateFocus();
            // the char of the key that opened us (a letter bind), or anything typed during the close fade
            if (suppressedKey != -2 || closing) {
                return true;
            }
            if (focused != null) {
                Widget f = focused;
                // the char that comes with a rebind keypress is not search text
                if (Safe.test("char", () -> f.charTyped(c)) || f instanceof TextField || f instanceof KeyCapture) {
                    return true;
                }
                // a click on a list leaves it focused, typing should still reach the search box
            }
            if (c > 32 && c != 127 && search.visible()) {
                focus(search);
                return search.charTyped(c);
            }
            return false;
        } catch (Throwable t) {
            Safe.report("shell charTyped", t);
            return true;
        }
    }
}
