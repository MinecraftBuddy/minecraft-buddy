package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.BotFacade;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.WorldAccess;
import adris.altoclef.ui.gui.facade.WorldAccess.EntityEntry;
import adris.altoclef.ui.gui.facade.WorldAccess.PlayerEntry;
import adris.altoclef.ui.gui.kit.Anim;
import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.VirtualList;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.EntityGroups;
import adris.altoclef.ui.gui.model.EntityGroups.EntityRow;
import adris.altoclef.ui.gui.model.EntityGroups.Group;
import adris.altoclef.ui.gui.model.EntityGroups.GroupRow;
import adris.altoclef.ui.gui.model.EntityGroups.Row;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

// everything the follow tab shows: the strip, the quick actions and the two lists. the lists are rebuilt from the
// facade every 10 ticks, keyed by name and type so expansion, scroll and hover survive the refresh
final class FollowContent extends Widget {

    // a heading is one label line, SECTION_BELOW then separates it from its list
    private static final int HEAD_LINE = 20;
    private static final int REFRESH_TICKS = 10;

    private final BotFacade facade;
    private final ShellContext ctx;
    private final Button stopBtn;
    private final Button allPlayersBtn;
    private final Button allEntitiesBtn;
    private final VirtualList<PlayerEntry> playerList;
    private final VirtualList<Row> entityList;
    private final Set<String> expanded = new HashSet<>();
    private final Set<String> closed = new HashSet<>();
    private final Set<String> memberOnly = new HashSet<>();
    private final Map<String, Anim> chevrons = new HashMap<>();
    private String pressedType;
    // the press landed on a Stop for this player ("p:name") or member ("e:id"), the release decides
    private String pressedStopKey;
    private int laidPlayerRows;

    private List<PlayerEntry> rawPlayers = List.of();
    private List<EntityEntry> rawEntities = List.of();
    private List<PlayerEntry> shownPlayers = List.of();
    private int shownEntityCount;
    private String following;
    private String query = "";
    private int ticks;
    private long lastNow;

    private boolean stripOn;
    private int stripX;
    private int stripY;
    private int stripW;
    private int stripH;
    private int pHdrX;
    private int pHdrY;
    private int eHdrX;
    private int eHdrY;

    FollowContent(Theme theme, BotFacade facade, ShellContext ctx) {
        super(theme);
        this.facade = facade;
        this.ctx = ctx;
        stopBtn = new Button(theme, "Stop", Button.Kind.DANGER, null, () -> act(() -> facade.world().stopFollowing()));
        allPlayersBtn = new Button(theme, "Follow all players", Button.Kind.SECONDARY, null, () -> act(() -> facade.world().followAllPlayers()));
        allEntitiesBtn = new Button(theme, "Follow all entities", Button.Kind.SECONDARY, null, () -> act(() -> facade.world().followAllEntities()));

        playerList = new VirtualList<>(theme, theme.px(Theme.ROW_H), this::drawPlayer);
        playerList.setKeyFunction(PlayerEntry::name);
        // not selectable: the selection is an index, and both lists re-sort under it every refresh
        playerList.onClick((p, i) -> {
            if (!p.self()) {
                act(() -> facade.world().followPlayer(p.name()));
            }
        });
        // the red Stop on the row you are following is ours too, same press and release rule as Follow all
        playerList.onRowMouseDown((p, i, mx, my, button, mods) -> {
            if (button == 0 && !p.self() && p.distance() >= 0 && EntityGroups.followsPlayer(following, p.name())
                    && inButton(playerList, mx, my, i)) {
                pressedStopKey = "p:" + p.name();
                pressedType = null;
                return true;
            }
            return false;
        });

        entityList = new VirtualList<>(theme, theme.px(Theme.ROW_H), this::drawEntityRow);
        entityList.setKeyFunction(Row::key);
        // the "Follow all" press is ours, the release decides (same button, same row, whatever the list did meanwhile)
        entityList.onRowMouseDown((row, i, mx, my, button, mods) -> {
            if (button == 0 && row instanceof GroupRow gr && !gr.group().memberOnly() && inButton(entityList, mx, my, i)) {
                pressedType = gr.group().typeId();
                pressedStopKey = null;
                return true;
            }
            if (button == 0 && row instanceof EntityRow er && EntityGroups.followsEntity(following, er.entry())
                    && inButton(entityList, mx, my, i)) {
                pressedStopKey = "e:" + er.entry().id();
                pressedType = null;
                return true;
            }
            return false;
        });
        entityList.onClick((row, i) -> {
            if (row instanceof GroupRow gr) {
                toggle(gr.group().typeId());
            } else if (row instanceof EntityRow er) {
                act(() -> facade.world().followEntity(er.entry().id()));
            }
        });
        refresh();
    }

    VirtualList<PlayerEntry> playerList() {
        return playerList;
    }

    VirtualList<Row> entityList() {
        return entityList;
    }

    Button stopButton() {
        return stopBtn;
    }

    Button allPlayersButton() {
        return allPlayersBtn;
    }

    Button allEntitiesButton() {
        return allEntitiesBtn;
    }

    boolean stripVisible() {
        return stripOn;
    }

    boolean expanded(String typeId) {
        return open(typeId, memberOnly.contains(typeId));
    }

    // a group is open when the player opened it, or a search found a mob inside it by name and they have not closed it
    private boolean open(String typeId, boolean viaSearch) {
        return expanded.contains(typeId) || (viaSearch && !closed.contains(typeId));
    }

    void setQuery(String q) {
        query = q == null ? "" : q;
        // a group closed mid search stays closed while they keep typing
        if (query.isEmpty()) {
            closed.clear();
        }
        rebuild();
    }

    @Override
    public boolean mouseUp(float mx, float my, int button, int mods) {
        String type = button == 0 ? pressedType : null;
        String stopKey = button == 0 ? pressedStopKey : null;
        if (button == 0) {
            pressedType = null;
            pressedStopKey = null;
        }
        // always forward: a press that never gets its release must not leave a button held
        boolean kids = super.mouseUp(mx, my, button, mods);
        if (type != null) {
            int i = entityList.indexAt(mx, my);
            if (i >= 0 && entityList.items().get(i) instanceof GroupRow gr && gr.group().typeId().equals(type) && inButton(entityList, mx, my, i)) {
                // whatever the button says now is what it does: a refresh between press and release may have flipped it
                boolean stop = EntityGroups.followsGroup(following, gr.group());
                act(() -> stop ? facade.world().stopFollowing() : facade.world().followType(type));
            }
            return true;
        }
        if (stopKey != null) {
            if (stopHit(stopKey, mx, my)) {
                act(() -> facade.world().stopFollowing());
            }
            return true;
        }
        return kids;
    }

    // released on the same row's button, and that row is still the one being followed
    private boolean stopHit(String key, float mx, float my) {
        if (key.startsWith("p:")) {
            int i = playerList.indexAt(mx, my);
            return i >= 0 && playerList.items().get(i).name().equals(key.substring(2)) && inButton(playerList, mx, my, i)
                    && EntityGroups.followsPlayer(following, playerList.items().get(i).name());
        }
        int i = entityList.indexAt(mx, my);
        return i >= 0 && entityList.items().get(i) instanceof EntityRow er && ("e:" + er.entry().id()).equals(key)
                && inButton(entityList, mx, my, i) && EntityGroups.followsEntity(following, er.entry());
    }

    void shown() {
        ticks = 0;
        pressedType = null;
        pressedStopKey = null;
        refresh();
    }

    // ----- data

    void refresh() {
        WorldAccess world = facade.world();
        String f = null;
        try {
            f = world.following();
            rawPlayers = new ArrayList<>(world.players());
            rawEntities = new ArrayList<>(world.entities());
        } catch (Throwable t) {
            Safe.report("follow refresh", t);
        }
        boolean stripChanged = (f != null) != (following != null);
        following = f;
        rebuild();
        if (stripChanged) {
            setBounds(x, y, w(), h());
        }
    }

    private void rebuild() {
        shownPlayers = EntityGroups.players(rawPlayers, query);
        if (!shownPlayers.equals(playerList.items())) {
            playerList.setItems(shownPlayers);
        }
        List<Group> groups = EntityGroups.group(rawEntities, query);
        int n = 0;
        for (Group g : groups) {
            n += g.count();
        }
        shownEntityCount = n;
        memberOnly.clear();
        Set<String> opened = new HashSet<>();
        for (Group g : groups) {
            if (g.memberOnly()) {
                memberOnly.add(g.typeId());
            }
            if (open(g.typeId(), g.memberOnly())) {
                opened.add(g.typeId());
            }
        }
        List<Row> rows = EntityGroups.rows(groups, opened);
        if (!rows.equals(entityList.items())) {
            entityList.setItems(rows);
        }
        // the stacked layout sizes the players list by how many players there are
        if (Math.min(4, shownPlayers.size()) != laidPlayerRows) {
            setBounds(x, y, w, h);
        }
    }

    private void toggle(String typeId) {
        if (expanded(typeId)) {
            expanded.remove(typeId);
            closed.add(typeId);
        } else {
            expanded.add(typeId);
            closed.remove(typeId);
        }
        rebuild();
    }

    // the chevron follows whatever open means right now, so a search opening a group animates too
    private float chevronValue(Group g, long now) {
        boolean open = open(g.typeId(), g.memberOnly());
        Anim a = chevrons.computeIfAbsent(g.typeId(), k -> new Anim(open ? 1f : 0f));
        a.set(open ? 1f : 0f, now, Theme.ANIM_FAST);
        return a.value(now);
    }

    private void act(Supplier<Result> action) {
        Result r;
        try {
            r = action.get();
        } catch (Throwable t) {
            Safe.report("follow action", t);
            r = Result.fail("That did not work. Try the chat command instead.");
        }
        if (r != null) {
            Result shown = r;
            Safe.run("follow toast", () -> ctx.toast(shown));
        }
        refresh();
    }

    @Override
    public void tick() {
        if (++ticks % REFRESH_TICKS == 0) {
            refresh();
        }
        super.tick();
    }

    // ----- layout

    private int headH() {
        return theme.px(HEAD_LINE) + theme.px(Theme.SECTION_BELOW);
    }

    @Override
    protected void layout() {
        int pad = theme.px(Theme.PAD);
        int gap = theme.px(Theme.GAP);
        int cx = x + pad;
        int cw = Math.max(0, w - 2 * pad);
        int cy = y + pad;

        stripOn = following != null;
        stopBtn.setVisible(stripOn);
        if (stripOn) {
            stripX = cx;
            stripY = cy;
            stripW = cw;
            stripH = theme.px(Theme.ROW_H + Theme.SPACE_2);
            int bh = theme.px(Theme.CONTROL_H);
            int bw = stopBtn.preferredWidth();
            stopBtn.setBounds(cx + cw - theme.px(Theme.SPACE_2) - bw, cy + (stripH - bh) / 2, bw, bh);
            cy += stripH + gap;
        }

        laidPlayerRows = Math.min(4, shownPlayers.size());

        int ch = theme.px(Theme.CONTROL_H);
        int b1 = allPlayersBtn.preferredWidth();
        int b2 = allEntitiesBtn.preferredWidth();
        if (b1 + gap + b2 > cw) {
            // a tiny panel: the labels ellipsize before the buttons fall off the edge
            b1 = Math.max(0, (cw - gap) / 2);
            b2 = Math.max(0, cw - gap - b1);
        }
        allPlayersBtn.setBounds(cx, cy, b1, ch);
        allEntitiesBtn.setBounds(cx + b1 + gap, cy, b2, ch);
        cy += ch + theme.px(Theme.SECTION);

        int bottom = y + h - pad;
        int hdr = headH();
        int sectionGap = theme.px(Theme.SECTION);
        if (w >= theme.px(900)) {
            int colGap = theme.px(Theme.SECTION);
            int pw = Math.round((cw - colGap) * 0.42f);
            int ew = cw - colGap - pw;
            place(cx, cy, pw, bottom - cy, hdr, true);
            place(cx + pw + colGap, cy, ew, bottom - cy, hdr, false);
        } else {
            int avail = Math.max(0, bottom - cy - sectionGap);
            // whole rows only, a sliver of a third row looks like a bug
            int rowH = theme.px(Theme.ROW_H);
            int fit = (int) ((avail * 0.5f - hdr) / rowH);
            int rows = Math.max(2, Math.min(Math.min(4, shownPlayers.size()), fit));
            int ph = hdr + rows * rowH;
            place(cx, cy, cw, ph, hdr, true);
            place(cx, cy + ph + sectionGap, cw, bottom - (cy + ph + sectionGap), hdr, false);
        }
    }

    private void place(int sx, int sy, int sw, int sh, int hdr, boolean players) {
        VirtualList<?> list = players ? playerList : entityList;
        // the list keeps its scrollbar strip, so it is widened by it and the card ends up as wide as the buttons above
        list.setBounds(sx, sy + hdr, sw + ListCard.gutter(theme), Math.max(0, sh - hdr));
        if (players) {
            pHdrX = sx;
            pHdrY = sy;
        } else {
            eHdrX = sx;
            eHdrY = sy;
        }
    }

    @Override
    public List<Widget> children() {
        return List.of(stopBtn, allPlayersBtn, allEntitiesBtn, playerList, entityList);
    }

    // ----- drawing

    private static float base(Canvas c, TextStyle s, float top, float height) {
        return top + height / 2f + (c.ascent(s) - c.descent(s)) / 2f;
    }

    @Override
    public void draw(Canvas c, Frame f) {
        lastNow = f.nowNanos();
        if (stripOn) {
            drawStrip(c);
        }
        allPlayersBtn.draw(c, f);
        allEntitiesBtn.draw(c, f);
        drawHeader(c, "Players", EntityGroups.nearbyPlayers(shownPlayers), pHdrX, pHdrY);
        drawHeader(c, "Entities", shownEntityCount, eHdrX, eHdrY);
        ListCard.card(theme, c, playerList, f.nowNanos(), playerList.items().size() * playerList.rowHeight());
        playerList.draw(c, f);
        ListCard.card(theme, c, entityList, f.nowNanos(), entityList.items().size() * entityList.rowHeight());
        entityList.draw(c, f);
        if (playerList.items().isEmpty()) {
            drawEmpty(c, playerList, query.isEmpty() ? "No players nearby" : "No players match",
                    query.isEmpty() ? "Anyone on the server shows up here." : "Try a different name.");
        }
        if (entityList.items().isEmpty()) {
            drawEmpty(c, entityList, query.isEmpty() ? "Nothing nearby" : "No entities match",
                    query.isEmpty() ? "Walk somewhere livelier and it fills in." : "Try a mob name, like zombie.");
        }
        if (stripOn) {
            stopBtn.draw(c, f);
        }
    }

    // one card, not a tinted bar: it is the only thing on this page that is not a row or a button
    private void drawStrip(Canvas c) {
        theme.card(c, stripX, stripY, stripW, stripH, theme.pxf(Theme.CARD_RADIUS), false);
        float is = theme.px(Theme.ICON);
        float lead = stripX + theme.px(Theme.SPACE_3);
        c.icon(Icon.DOT, lead, stripY + (stripH - is) / 2f, is, theme.color(Theme.ACCENT));
        float tx = lead + is + theme.px(Theme.SPACE_2);
        float by = base(c, theme.label, stripY, stripH);
        tx += c.text("Following ", tx, by, theme.label, theme.color(Theme.TEXT_2));
        float room = stopBtn.x() - theme.px(Theme.SPACE_3) - tx;
        c.text(c.ellipsize(following, theme.label, Math.max(0, room)), tx, by, theme.label, theme.color(Theme.TEXT));
    }

    private void drawHeader(Canvas c, String title, int count, int hx, int hy) {
        float by = base(c, theme.label, hy, theme.px(HEAD_LINE));
        float adv = c.text(title, hx, by, theme.label, theme.color(Theme.TEXT));
        c.text(" · " + count + " nearby", hx + adv, by, theme.label, theme.color(Theme.TEXT_3));
    }

    private void drawEmpty(Canvas c, VirtualList<?> list, String line, String hint) {
        if (list.h() < theme.px(40)) {
            return;
        }
        float cy = list.y() + Math.min(list.h() / 2f, theme.px(60));
        float cw = list.w() - ListCard.gutter(theme);
        float w1 = c.measure(line, theme.body);
        c.text(line, list.x() + (cw - w1) / 2f, cy - theme.px(2), theme.body, theme.color(Theme.TEXT_2));
        float w2 = c.measure(hint, theme.small);
        c.text(hint, list.x() + (cw - w2) / 2f, cy + theme.px(18), theme.small, theme.color(Theme.TEXT_3));
    }

    // the row button lives in a fixed slot at the right edge and covers the distance while it is there, so nothing
    // in the row ever shifts when the mouse comes by
    private int buttonW() {
        // sized for the longer label so "Follow" and "Follow all" share one slot
        return (int) Math.ceil(theme.measurer.measure("Follow all", theme.label)) + theme.px(Theme.PAD + Theme.SPACE_1);
    }

    private int buttonH() {
        return theme.px(Theme.CONTROL_H - Theme.SPACE_1);
    }

    private int rowW(VirtualList<?> list) {
        return list.w() - ListCard.gutter(theme);
    }

    private float buttonLeft(int rx, int rw) {
        return rx + rw - theme.px(Theme.SPACE_2) - buttonW();
    }

    // same rectangle drawAction paints, for the row at this index of that list
    private boolean inButton(VirtualList<?> list, float mx, float my, int index) {
        float right = list.x() + rowW(list) - theme.px(Theme.SPACE_2);
        float rowTop = list.y() + index * (float) list.rowHeight() - list.scroller().offset(lastNow);
        float top = rowTop + (list.rowHeight() - buttonH()) / 2f;
        return mx >= right - buttonW() && mx < right && my >= top && my < top + buttonH();
    }

    // the one action a row can offer: blue when it would start following, red when it would stop
    private void drawAction(Canvas c, Frame f, String label, boolean stop, int rx, int ry, int rw, int rh) {
        float bx = rx + rw - theme.px(Theme.SPACE_2) - buttonW();
        float by = ry + (rh - buttonH()) / 2f;
        boolean over = f.mouseX() >= bx && f.mouseX() < bx + buttonW() && f.mouseY() >= by && f.mouseY() < by + buttonH();
        float r = theme.pxf(Theme.CONTROL_RADIUS);
        int fill;
        int text;
        if (stop) {
            fill = over ? theme.color(Theme.DANGER_FILL) : theme.color(Theme.RAISED);
            text = theme.color(Theme.DANGER);
        } else {
            fill = over ? theme.color(Theme.ACCENT_HOVER) : theme.color(Theme.ACCENT);
            text = theme.color(Theme.ON_ACCENT);
        }
        c.fillRoundRect(bx, by, buttonW(), buttonH(), r, fill);
        if (stop) {
            c.strokeRoundRect(bx, by, buttonW(), buttonH(), r, Theme.fade(theme.color(Theme.HAIRLINE_STRONG), 0.6f));
        }
        float tw = c.measure(label, theme.label);
        c.text(label, bx + (buttonW() - tw) / 2f, base(c, theme.label, by, buttonH()), theme.label, text);
    }

    // where the distance ends, flush with the row's padding
    private float distanceRight(int rx, int rw) {
        return rx + rw - theme.px(Theme.PAD);
    }

    // hover is the usual step, the row you are following is the one tinted blue. both follow the card's corners
    private void rowBg(Canvas c, int rx, int ry, int rw, int rh, boolean followed, boolean hot, int idx, int count) {
        if (followed) {
            ListCard.fill(theme, c, rx, ry, rw, rh, idx == 0, idx == count - 1, theme.color(Theme.ACCENT_SOFT));
        } else if (hot) {
            ListCard.fill(theme, c, rx, ry, rw, rh, idx == 0, idx == count - 1, theme.color(Theme.HOVER));
        }
    }

    private void chip(Canvas c, String text, float px, int ry, int rh, boolean lit) {
        TextStyle s = theme.smallLabel.asTabular();
        float tw = c.measure(text, s);
        float ph = theme.px(18);
        float cw = tw + 2 * theme.px(Theme.SPACE_2);
        c.fillRoundRect(px, ry + (rh - ph) / 2f, cw, ph, ph / 2f, theme.color(Theme.RAISED));
        // RAISED is a hair off PANEL in light and off HOVER in dark, the outline is what keeps a chip from vanishing
        c.strokeRoundRect(px, ry + (rh - ph) / 2f, cw, ph, ph / 2f, theme.color(lit ? Theme.HAIRLINE_STRONG : Theme.HAIRLINE));
        c.text(text, px + theme.px(Theme.SPACE_2), base(c, s, ry, rh), s, theme.color(Theme.TEXT_2));
    }

    private float chipW(Canvas c, String text) {
        return c.measure(text, theme.smallLabel.asTabular()) + 2 * theme.px(Theme.SPACE_2);
    }

    private void drawPlayer(Canvas c, Frame f, PlayerEntry p, int idx, int rx, int ry, int rw, int rh, boolean hot) {
        boolean near = p.distance() >= 0;
        boolean followed = EntityGroups.followsPlayer(following, p.name());
        boolean lit = (hot && !p.self()) || followed;
        rowBg(c, rx, ry, rw, rh, followed, hot && !p.self(), idx, playerList.items().size());
        ListCard.separator(theme, c, rx, ry, rw, idx);
        float face = theme.px(Theme.ICON_LG);
        float fx = rx + theme.px(Theme.PAD);
        float fy = ry + (rh - face) / 2f;
        c.playerFace(p.name(), fx, fy, face);
        if (!near && !p.self()) {
            c.fillRoundRect(fx, fy, face, face, face / 6f, Theme.alpha(theme.color(Theme.PANEL), 0.6f));
        }
        float tx = fx + face + theme.px(Theme.SPACE_3);
        boolean button = over(f, rx, ry, rw, rh) && !p.self() && near;
        float right = distanceRight(rx, rw);
        float limit = right;
        if (!p.self()) {
            String d = near ? EntityGroups.meters(p.distance()) : "not nearby";
            TextStyle ds = near ? theme.body.asTabular() : theme.small;
            float dw = c.measure(d, ds);
            if (!button) {
                c.text(d, right - dw, base(c, ds, ry, rh), ds, near ? theme.color(Theme.TEXT_2) : theme.color(Theme.TEXT_3));
            }
            // the hover button is wider than any distance, so the name stops short of it either way
            limit = near ? Math.min(right - dw - theme.px(Theme.SPACE_3), buttonLeft(rx, rw) - theme.px(Theme.SPACE_2))
                    : right - dw - theme.px(Theme.SPACE_3);
        }
        int nameCol = near || p.self() ? theme.color(Theme.TEXT) : theme.color(Theme.TEXT_3);
        float chipRoom = p.self() ? chipW(c, "you") + theme.px(Theme.SPACE_2) : 0f;
        String name = c.ellipsize(p.name(), theme.label, Math.max(0, limit - tx - chipRoom));
        c.text(name, tx, base(c, theme.label, ry, rh), theme.label, nameCol);
        if (p.self()) {
            chip(c, "you", tx + c.measure(name, theme.label) + theme.px(Theme.SPACE_2), ry, rh, lit);
        }
        if (button) {
            drawAction(c, f, followed ? "Stop" : "Follow", followed, rx, ry, rw, rh);
        }
    }

    private static boolean over(Frame f, int rx, int ry, int rw, int rh) {
        return f.mouseX() >= rx && f.mouseX() < rx + rw && f.mouseY() >= ry && f.mouseY() < ry + rh;
    }

    private void drawEntityRow(Canvas c, Frame f, Row row, int idx, int rx, int ry, int rw, int rh, boolean hot) {
        boolean followed = false;
        if (row instanceof GroupRow gr) {
            followed = EntityGroups.followsGroup(following, gr.group()) || EntityGroups.followsOneOf(following, gr.group());
        } else if (row instanceof EntityRow er) {
            followed = EntityGroups.followsEntity(following, er.entry());
        }
        rowBg(c, rx, ry, rw, rh, followed, hot, idx, entityList.items().size());
        ListCard.separator(theme, c, rx, ry, rw, idx);
        if (row instanceof GroupRow gr) {
            drawGroup(c, f, gr.group(), rx, ry, rw, rh, hot || followed);
        } else if (row instanceof EntityRow er) {
            drawMember(c, f, er.entry(), rx, ry, rw, rh);
        }
    }

    private void drawGroup(Canvas c, Frame f, Group g, int rx, int ry, int rw, int rh, boolean lit) {
        float cs = theme.px(Theme.ICON);
        float cx = rx + theme.px(Theme.PAD);
        float cy = ry + (rh - cs) / 2f;
        float open = chevronValue(g, f.nowNanos());
        c.icon(Icon.CHEVRON_RIGHT, cx, cy, cs, Theme.fade(theme.color(Theme.TEXT_3), 1f - open));
        c.icon(Icon.CHEVRON_DOWN, cx, cy, cs, Theme.fade(theme.color(Theme.TEXT_3), open));

        float tile = theme.px(Theme.ICON_LG);
        float tx = cx + cs + theme.px(Theme.SPACE_2);
        float ty = ry + (rh - tile) / 2f;
        c.fillRoundRect(tx, ty, tile, tile, theme.pxf(Theme.ROW_RADIUS), theme.color(Theme.RAISED));
        if (g.iconItemId() != null) {
            float is = theme.itemIconPx();
            c.itemIcon(g.iconItemId(), tx + (tile - is) / 2f, ty + (tile - is) / 2f, is);
        } else {
            float is = theme.px(Theme.ICON);
            c.icon(Icon.ENTITY, tx + (tile - is) / 2f, ty + (tile - is) / 2f, is, theme.color(Theme.TEXT_2));
        }

        // a group that is only here for one named member would follow the whole type, which is not what the search said
        boolean button = over(f, rx, ry, rw, rh) && !g.memberOnly();
        float nx = tx + tile + theme.px(Theme.SPACE_3);
        String d = EntityGroups.meters(g.nearest());
        float dw = c.measure(d, theme.body.asTabular());
        float right = distanceRight(rx, rw);
        if (!button) {
            c.text(d, right - dw, base(c, theme.body, ry, rh), theme.body.asTabular(), theme.color(Theme.TEXT_2));
        }
        String count = "x" + g.count();
        float pw = chipW(c, count);
        float textLimit = g.memberOnly() ? right - dw - theme.px(Theme.SPACE_3) : Math.min(right - dw - theme.px(Theme.SPACE_3), buttonLeft(rx, rw) - theme.px(Theme.SPACE_2));
        float room = textLimit - nx - pw - theme.px(Theme.SPACE_2);
        String name = c.ellipsize(g.typeName(), theme.label, Math.max(0, room));
        c.text(name, nx, base(c, theme.label, ry, rh), theme.label, theme.color(Theme.TEXT));
        chip(c, count, nx + c.measure(name, theme.label) + theme.px(Theme.SPACE_2), ry, rh, lit);
        if (button) {
            boolean stop = EntityGroups.followsGroup(following, g);
            drawAction(c, f, stop ? "Stop" : "Follow all", stop, rx, ry, rw, rh);
        }
    }

    private void drawMember(Canvas c, Frame f, EntityEntry e, int rx, int ry, int rw, int rh) {
        boolean followed = EntityGroups.followsEntity(following, e);
        boolean button = over(f, rx, ry, rw, rh);
        // a child starts under its group's icon tile: the row padding, a chevron and the gap after it
        float nx = rx + theme.px(Theme.PAD) + theme.px(Theme.ICON) + theme.px(Theme.SPACE_2);
        float right = distanceRight(rx, rw);
        String d = EntityGroups.meters(e.distance());
        float dw = c.measure(d, theme.body.asTabular());
        if (!button) {
            c.text(d, right - dw, base(c, theme.body, ry, rh), theme.body.asTabular(), theme.color(Theme.TEXT_2));
        }
        float room = Math.min(right - dw - theme.px(Theme.SPACE_3), buttonLeft(rx, rw) - theme.px(Theme.SPACE_2)) - nx;
        String label = EntityGroups.label(e);
        boolean named = EntityGroups.named(e);
        float typeW = named ? c.measure(e.typeName(), theme.small) + theme.px(Theme.SPACE_2) : 0f;
        String shown = c.ellipsize(label, theme.body, Math.max(0, room - typeW));
        c.text(shown, nx, base(c, theme.body, ry, rh), theme.body, theme.color(Theme.TEXT));
        if (named) {
            float tx = nx + c.measure(shown, theme.body) + theme.px(Theme.SPACE_2);
            c.text(e.typeName(), tx, base(c, theme.small, ry, rh), theme.small, theme.color(Theme.TEXT_2));
        }
        if (button) {
            drawAction(c, f, followed ? "Stop" : "Follow", followed, rx, ry, rw, rh);
        }
    }
}
