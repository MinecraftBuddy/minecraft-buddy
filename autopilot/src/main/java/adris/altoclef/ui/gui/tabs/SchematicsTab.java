package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.BotFacade;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.SchematicsAccess;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Footprint;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Listing;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Materials;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Placement;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Row;
import adris.altoclef.ui.gui.facade.SchematicsAccess.State;
import adris.altoclef.ui.gui.facade.SchematicsAccess.Terrain;
import adris.altoclef.ui.gui.facade.WorldAccess.Position;
import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Canvas;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.IconButton;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.VirtualList;
import adris.altoclef.ui.gui.kit.Widget;

import java.util.List;

// the schematics folder on the left, what the picked file costs and where it goes on the right, one button to build
// it there. everything the tab shows is a snapshot from the facade read on tick, so a scan or a count that is still
// running never freezes the panel and never blanks the list: the old rows stay up with a small "Refreshing" label
public final class SchematicsTab implements Tab, SchematicPane.Listener {

    // the second click has to come within this many ticks of the first, or the button goes back to sleep
    private static final int ARM_TICKS = 60;
    // how long the pointer rests on a row before its full path (or its error) pops up
    private static final int TIP_TICKS = 10;

    private final Theme theme;
    private final BotFacade facade;
    private final ShellContext ctx;
    private final Button importBtn;
    private final Button folderBtn;
    private final IconButton refreshBtn;
    private final SchematicRows rows;
    private final SchematicList list;
    private final SchematicPane pane;
    private final View view;

    private String query = "";
    private String selectedPath;
    private List<Row> allRows = List.of();
    private List<Row> shownRows = List.of();
    private boolean loading;
    private String countLine = "";
    private String listState = "";
    private int armTicks;
    private int hoverRow = -1;
    private int hoverTicks;
    // the tooltip of the pane control under the pointer, null off the controls. shares the rest timer with the rows
    private String hoverTip;
    // terrain() samples chunks on every call, so only the tick asks for it; a poll from a click reuses the last one
    private Terrain lastTerrain;
    private boolean sampleTerrain;
    private Materials lastMaterials;

    public SchematicsTab(Theme theme, BotFacade facade, ShellContext ctx) {
        this.theme = theme;
        this.facade = facade;
        this.ctx = ctx;
        importBtn = new Button(theme, "Import", Button.Kind.SECONDARY, null, this::importFiles);
        folderBtn = new Button(theme, "Open folder", Button.Kind.GHOST, null, this::openFolder);
        refreshBtn = new IconButton(theme, Icon.RESET, "Refresh", this::refresh);
        rows = new SchematicRows(theme, () -> selectedPath, () -> shownRows.size());
        list = new SchematicList();
        pane = new SchematicPane(theme, this);
        view = new View();
    }

    @Override
    public String id() {
        return "schematics";
    }

    @Override
    public String title() {
        return "Schematics";
    }

    @Override
    public Icon icon() {
        return Icon.SCHEMATICS;
    }

    @Override
    public Widget content() {
        return view;
    }

    @Override
    public boolean hasSearch() {
        return true;
    }

    @Override
    public String searchPlaceholder() {
        return "Search schematics";
    }

    @Override
    public void onSearch(String q) {
        query = q == null ? "" : q;
        disarm();
        poll();
    }

    @Override
    public void onShown() {
        disarm();
        hoverRow = -1;
        hoverTip = null;
        poll();
    }

    @Override
    public void onHidden() {
        disarm();
        // a drag can't outlive the tab: the release would land on whatever tab is up next
        pane.map().cancelDrag();
    }

    // ----- data

    private SchematicsAccess schematics() {
        return facade.schematics();
    }

    // reads the listing and the selected file's count. both are snapshots, so this is cheap enough for every tick.
    // the no-arg one is for clicks: everything but the terrain, which is the tick's alone
    private void poll() {
        poll(false);
    }

    private void poll(boolean terrain) {
        sampleTerrain = terrain;
        Safe.run("schematics poll", () -> {
            readListing();
            updateListText();
            refreshPane();
        });
    }

    private void readListing() {
        Listing l = schematics().list();
        List<Row> all = l == null || l.rows() == null ? List.of() : l.rows();
        loading = l != null && l.state() == State.LOADING;
        allRows = all;
        List<Row> shown = SchematicRules.filter(all, query);
        // only a real change reaches the list, so rows keep their fade state and a refresh never flashes them
        if (!shown.equals(list.items())) {
            list.setItems(shown);
            hoverRow = -1;
        }
        shownRows = shown;
        // a file that vanished with the refresh takes its selection with it
        if (selectedPath != null && rowFor(selectedPath) == null) {
            selectedPath = null;
            disarm();
            showGhost();
        }
        syncCursor();
    }

    // the list's keyboard cursor is an index, the selection is a path: point one at the other after every change
    private void syncCursor() {
        Row sel = rowFor(selectedPath);
        // an immutable list throws on indexOf(null), so nothing selected is spelled out
        list.setSelected(sel == null ? -1 : shownRows.indexOf(sel));
    }

    private void updateListText() {
        int broken = 0;
        for (Row r : allRows) {
            if (!r.ok()) {
                broken++;
            }
        }
        if (loading && allRows.isEmpty()) {
            listState = "Reading the schematics folder…";
            countLine = "";
            return;
        }
        listState = allRows.isEmpty() ? "" : shownRows.isEmpty() ? "No schematics match" : "";
        String counted = SchematicRules.count(shownRows.size(), allRows.size(), broken);
        countLine = loading ? counted + " · Refreshing…" : counted;
    }

    private void refreshPane() {
        // with nothing in the folder there is nothing to pick, so the pane would be a blank box next to the hint
        pane.setVisible(!allRows.isEmpty());
        Row sel = rowFor(selectedPath);
        boolean world = hasWorld();
        // have is re-read on every materials() call, so only the selected file is asked each tick
        Materials m = sel != null && sel.ok() ? schematics().materials(sel.relativePath()) : null;
        lastMaterials = m;
        // the unchecked set is read every poll too: a box ticked here shows at once, and one the bot cleared on its
        // own (saying no to a nether run) shows on the next tick
        pane.show(sel, m, sel != null && sel.ok() ? schematics().excluded(sel.relativePath()) : null, world);
        pane.setMoving(movingNow() != null);
        refreshSite(sel, world);
    }

    // where the file goes and the map around it. terrain() is asked once a tick (never from a click, never per
    // frame) and is centred on the footprint, the part of the world the build cares about; a click's poll shows the
    // last snapshot until the next tick recentres it
    private void refreshSite(Row sel, boolean world) {
        Placement pl = null;
        Footprint fp = null;
        Position player = null;
        float yaw = 0f;
        boolean here = false;
        if (sel != null && sel.ok() && world) {
            pl = schematics().placement(sel.relativePath());
            fp = schematics().footprint(sel.relativePath());
            here = pl != null && pl.inThisDimension();
            if (here && sampleTerrain) {
                int cx = fp != null ? fp.x() + fp.sizeX() / 2 : pl.x();
                int cz = fp != null ? fp.z() + fp.sizeZ() / 2 : pl.z();
                lastTerrain = schematics().terrain(cx, cz, Terrain.RADIUS_CHUNKS);
            }
            player = facade.world().playerPos();
            yaw = facade.world().playerYaw();
        }
        if (!here) {
            lastTerrain = null;
        }
        pane.site(pl, fp, lastTerrain, player, yaw, schematics().building());
    }

    private Row rowFor(String path) {
        if (path == null) {
            return null;
        }
        for (Row r : allRows) {
            if (r.relativePath().equals(path)) {
                return r;
            }
        }
        return null;
    }

    private boolean hasWorld() {
        try {
            return facade.world().playerPos() != null;
        } catch (Throwable t) {
            Safe.report("schematics world", t);
            return false;
        }
    }

    private void select(Row r) {
        String next = r == null ? null : r.relativePath();
        if (next == null ? selectedPath == null : next.equals(selectedPath)) {
            return;
        }
        selectedPath = next;
        disarm();
        showGhost();
        syncCursor();
        poll();
    }

    // the ghost follows the pick and stays up after the gui closes, so you can walk around the thing you're about to build
    private void showGhost() {
        Safe.run("schematics ghost", () -> schematics().setGhost(selectedPath));
    }

    // ----- actions

    private void refresh() {
        Safe.run("schematics refresh", () -> schematics().refresh());
        poll();
    }

    private void importFiles() {
        Safe.run("schematics import", () -> schematics().importFiles(report -> {
            // the facade calls this on the game thread once the picker is done, the same toast a drop gets
            ctx.toast(report == null ? Result.fail("Nothing came back from the picker") : report.toResult());
            poll();
        }));
    }

    private void openFolder() {
        Result r;
        try {
            r = schematics().openFolder();
        } catch (Throwable t) {
            Safe.report("schematics folder", t);
            r = Result.fail("Could not open the folder");
        }
        ctx.toast(r == null ? Result.fail("Could not open the folder") : r);
    }

    // building is also demolishing whatever stands in the footprint, so the first click only arms the button
    @Override
    public void build() {
        Row sel = rowFor(selectedPath);
        if (sel == null || !sel.ok()) {
            // the button is greyed out for this, but a click that lands anyway shouldn't just do nothing
            disarm();
            ctx.toast(Result.fail(sel == null ? "Pick a schematic first" : "That schematic didn't load, nothing to build"));
            return;
        }
        if (!pane.armed()) {
            pane.setArmed(true, where(sel));
            armTicks = 0;
            return;
        }
        disarm();
        startBuild(sel);
    }

    // the second click builds wherever the map says, so the first one says where that is
    private String where(Row sel) {
        try {
            if (schematics().atFeet(sel.relativePath())) {
                return "at your feet";
            }
            Placement pl = schematics().placement(sel.relativePath());
            return pl == null ? null : "at " + pl.x() + " " + pl.y() + " " + pl.z();
        } catch (Throwable t) {
            Safe.report("schematics where", t);
            return null;
        }
    }

    // the one place a build starts from. gathering lives behind the facade (McSchematics hands survival builds to
    // SchematicBuildTask), so the tab stays one call and the preview fake doesn't need to know
    private void startBuild(Row sel) {
        Result r;
        try {
            r = schematics().build(sel.relativePath());
        } catch (Throwable t) {
            Safe.report("schematics build", t);
            r = Result.fail("Could not start building: " + t.getClass().getSimpleName());
        }
        ctx.toast(r == null ? Result.fail("Could not start building, the build didn't answer") : r);
        poll();
    }

    // ----- the placement controls. each edit is saved by the facade, the poll after it redraws the map

    @Override
    public void move(int dx, int dy, int dz, int mods) {
        // shift is the coarse step: eight blocks, half a chunk, enough to cross a footprint in a few clicks
        int n = Keys.shift(mods) ? 8 : 1;
        edit("schematics move", path -> schematics().move(path, dx * n, dy * n, dz * n));
    }

    @Override
    public void rotate(boolean clockwise) {
        edit("schematics rotate", path -> schematics().rotate(path, clockwise));
    }

    @Override
    public void mirror() {
        edit("schematics mirror", path -> schematics().mirror(path));
    }

    @Override
    public void placeAtFeet() {
        edit("schematics feet", path -> schematics().placeAtFeet(path));
    }

    // move mode lives in the facade: starting it swaps this screen for the free camera one, so the toast is only
    // seen when it didn't start
    @Override
    public void toggleMoving() {
        disarm();
        if (movingNow() != null) {
            Safe.run("schematics stop moving", () -> schematics().stopMoving());
            poll();
            return;
        }
        Row sel = rowFor(selectedPath);
        if (sel == null || !sel.ok()) {
            return;
        }
        Result r;
        try {
            r = schematics().startMoving(sel.relativePath());
        } catch (Throwable t) {
            Safe.report("schematics move mode", t);
            r = Result.fail("Could not start moving");
        }
        if (r == null || !r.ok()) {
            ctx.toast(r == null ? Result.fail("Could not start moving") : r);
        }
        poll();
    }

    // a box on the list. it is an edit like a nudge: saved by the facade, disarms the button, the poll after it redraws
    @Override
    public void setIncluded(String itemId, boolean included) {
        edit("schematics include", path -> schematics().setIncluded(path, itemId, included));
    }

    private String movingNow() {
        try {
            return schematics().moving();
        } catch (Throwable t) {
            Safe.report("schematics moving", t);
            return null;
        }
    }

    private void edit(String what, java.util.function.Consumer<String> action) {
        Row sel = rowFor(selectedPath);
        if (sel == null || !sel.ok()) {
            return;
        }
        disarm();
        Safe.run(what, () -> action.accept(sel.relativePath()));
        poll();
    }

    private void disarm() {
        if (pane.armed()) {
            pane.setArmed(false, null);
        }
        armTicks = 0;
    }

    // ----- the list

    private final class SchematicList extends VirtualList<Row> {

        SchematicList() {
            super(SchematicsTab.this.theme, SchematicsTab.this.theme.px(Theme.ROW_H + Theme.SPACE_2), SchematicsTab.this.rows);
            setSelectable(true);
            setKeyFunction(Row::relativePath);
            onClick((r, i) -> select(r));
        }

        @Override
        public void draw(Canvas c, Frame f) {
            ListCard.card(theme, c, this, f.nowNanos(), items.size() * rowHeight());
            super.draw(c, f);
        }
    }

    // ----- the view

    private final class View extends Widget {

        private final List<Widget> kids;
        private int barY;
        private int countX;
        private int listW;

        View() {
            super(SchematicsTab.this.theme);
            kids = List.of(importBtn, folderBtn, refreshBtn, list, pane);
        }

        @Override
        protected void layout() {
            int pad = theme.px(Theme.PAD);
            int gap = theme.px(Theme.GAP);
            int ch = theme.px(Theme.CONTROL_H);
            int top = y + pad;
            barY = top;
            int iw = importBtn.preferredWidth();
            importBtn.setBounds(x + pad, top, iw, ch);
            int fw = folderBtn.preferredWidth();
            folderBtn.setBounds(importBtn.x() + iw + gap, top, fw, ch);
            refreshBtn.setBounds(folderBtn.x() + fw + theme.px(Theme.SPACE_1), top, ch, ch);
            countX = refreshBtn.x() + ch + gap;
            int lt = top + ch + theme.px(Theme.SECTION);
            int lh = Math.max(0, y + h - lt - pad);
            // the list takes 37% of the content with a floor, so a squashed window keeps the pane's numbers readable
            int inner = w - 2 * pad;
            listW = Math.max(theme.px(200), Math.min(theme.px(320), Math.round(inner * 0.37f)));
            int gutter = ListCard.gutter(theme);
            list.setBounds(x + pad, lt, listW + gutter, lh);
            int px = x + pad + listW + gutter + theme.px(6);
            pane.setBounds(px, lt, Math.max(theme.px(120), x + w - pad - px), lh);
        }

        @Override
        public List<Widget> children() {
            return kids;
        }

        @Override
        public Widget mouseMove(float mx, float my) {
            Widget d = super.mouseMove(mx, my);
            int i = list.indexAt(mx, my);
            if (i != hoverRow) {
                hoverRow = i;
                hoverTicks = 0;
            }
            String tip = pane.visible() ? pane.tipAt(mx, my) : null;
            if (!java.util.Objects.equals(tip, hoverTip)) {
                hoverTip = tip;
                hoverTicks = 0;
            }
            return d;
        }

        @Override
        public void tick() {
            super.tick();
            poll(true);
            if (pane.armed() && ++armTicks >= ARM_TICKS) {
                disarm();
            }
            if ((hoverRow >= 0 || hoverTip != null) && hoverTicks < TIP_TICKS) {
                hoverTicks++;
            }
        }

        @Override
        public void draw(Canvas c, Frame f) {
            for (Widget k : kids) {
                if (k.visible()) {
                    k.draw(c, f);
                }
            }
            int pad = theme.px(Theme.PAD);
            int ch = theme.px(Theme.CONTROL_H);
            if (!countLine.isEmpty()) {
                float room = x + w - pad - countX;
                String s = c.ellipsize(countLine, theme.small, Math.max(0, room));
                float tw = c.measure(s, theme.small);
                c.text(s, x + w - pad - tw, mid(c, theme.small, barY, ch), theme.small, theme.color(Theme.TEXT_3));
            }
            if (list.items().isEmpty()) {
                drawListState(c);
            }
            drawTip(c, f);
        }

        // the first scan, an empty folder and a search with no hits all take the list's place. the empty folder one
        // says how to fill it, that is the whole point of an empty screen
        private void drawListState(Canvas c) {
            String lead;
            String rest = "";
            String path = "";
            if (!listState.isEmpty()) {
                lead = listState;
            } else {
                lead = "No schematics yet";
                rest = "Drop .litematic, .schem or .schematic files on the game window, or click Import.";
                try {
                    path = schematics().folderPath();
                } catch (Throwable t) {
                    Safe.report("schematics folder path", t);
                }
            }
            // the hint sits on the list's card when there is a pane beside it, across the whole content when there isn't
            int spanW = pane.visible() ? listW : w - 2 * theme.px(Theme.PAD);
            float room = Math.max(theme.px(80), Math.min(theme.px(420), spanW - 2 * theme.px(24)));
            java.util.ArrayList<String> lines = new java.util.ArrayList<>(List.of(lead));
            if (!rest.isEmpty()) {
                lines.addAll(HelpList.wrap(c, rest, theme.small, room));
            }
            if (path != null && !path.isEmpty()) {
                lines.add(c.ellipsize(path, theme.small, room));
            }
            float lh = c.lineHeight(theme.small) + theme.px(2);
            float top = list.y() + (list.h() - lines.size() * lh) / 2f;
            float cx = list.x() + spanW / 2f;
            for (int i = 0; i < lines.size(); i++) {
                String s = lines.get(i);
                float tw = c.measure(s, theme.small);
                c.text(s, cx - tw / 2f, top + i * lh + c.ascent(theme.small), theme.small, theme.color(i == 0 ? Theme.TEXT_2 : Theme.TEXT_3));
            }
        }

        // a path that got cut, or a file that didn't parse, shows the whole story in a small floating card by the pointer
        private void drawTip(Canvas c, Frame f) {
            String text = tipText();
            if (text == null) {
                return;
            }
            TextStyle s = theme.small;
            float maxW = theme.px(360);
            List<String> lines = HelpList.wrap(c, text, s, maxW - 2 * theme.px(10));
            float tw = 0;
            for (String l : lines) {
                tw = Math.max(tw, c.measure(l, s));
            }
            float lh = c.lineHeight(s);
            float bw = tw + 2 * theme.px(10);
            float bh = lines.size() * lh + 2 * theme.px(6);
            float bx = f.mouseX() + theme.px(12);
            float by = f.mouseY() + theme.px(16);
            if (bx + bw > x + w - theme.px(8)) {
                bx = x + w - theme.px(8) - bw;
            }
            if (by + bh > y + h - theme.px(8)) {
                by = f.mouseY() - theme.px(8) - bh;
            }
            theme.floating(c, bx, by, bw, bh, theme.pxf(Theme.CONTROL_RADIUS));
            for (int i = 0; i < lines.size(); i++) {
                c.text(lines.get(i), bx + theme.px(10), by + theme.px(6) + i * lh + c.ascent(s), s, theme.color(Theme.TEXT));
            }
        }

        private float mid(Canvas c, TextStyle s, float top, float height) {
            return top + height / 2f + (c.ascent(s) - c.descent(s)) / 2f;
        }
    }

    // what the tooltip says for the row or the pane control under the pointer, null when there is none or it has
    // not rested long enough
    String tipText() {
        if (hoverTicks < TIP_TICKS) {
            return null;
        }
        if (hoverTip != null) {
            return hoverTip;
        }
        if (hoverRow < 0 || hoverRow >= shownRows.size()) {
            return null;
        }
        Row r = shownRows.get(hoverRow);
        String text = !r.ok() ? r.error() : rows.clipped(hoverRow) ? r.relativePath() : null;
        return text == null || text.isEmpty() ? null : text;
    }

    // test hooks
    Button importButton() {
        return importBtn;
    }

    Button folderButton() {
        return folderBtn;
    }

    IconButton refreshButton() {
        return refreshBtn;
    }

    VirtualList<Row> list() {
        return list;
    }

    SchematicPane pane() {
        return pane;
    }

    Button buildButton() {
        return pane.buildButton();
    }

    Button moveButton() {
        return pane.moveButton();
    }

    String selectedPath() {
        return selectedPath;
    }

    String countLine() {
        return countLine;
    }

    String listState() {
        return listState;
    }

    boolean armed() {
        return pane.armed();
    }

    Materials lastMaterials() {
        return lastMaterials;
    }

    boolean warned(int index) {
        return SchematicRows.warns(shownRows.get(index));
    }

    // the middle of row i, for tests that click rows
    float rowMidY(int i) {
        return list.y() + i * list.rowHeight() + list.rowHeight() / 2f;
    }
}
