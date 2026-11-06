package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.BotFacade;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.facade.SettingsAccess.SettingView;
import adris.altoclef.ui.gui.kit.Icon;
import adris.altoclef.ui.gui.kit.RowWidgets;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.ShellContext;
import adris.altoclef.ui.gui.kit.Tab;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.model.SettingsFilter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// every setting there is, with the right control for its type. the facade hands over a snapshot when the tab opens;
// after that each change goes through set or reset (same road as #set), gets toasted, and the one setting that changed
// is re-read and pushed into the row, the pinned key card and the counts. the list is never rebuilt for a single edit
public final class SettingsTab implements Tab, SettingHost {

    private static final String KEY = "guiKey";
    private static final String KEY_ENABLED = "guiKeyEnabled";
    private static final int DEFAULT_KEY = 344;

    private final BotFacade facade;
    private final ShellContext ctx;
    private final List<SettingRow> rows = new ArrayList<>();
    private final Map<String, String> descriptions = new HashMap<>();
    private final RowWidgets<SettingView> list;
    private final SettingsToolbar toolbar;
    private final SettingKeyCard keyCard;
    private final CommonSettings common;
    private final SettingsContent content;

    private List<SettingView> all = List.of();
    private String category = SettingsFilter.ALL;
    private boolean modifiedOnly;
    private boolean advanced = true;
    private String query = "";

    public SettingsTab(Theme theme, BotFacade facade, ShellContext ctx) {
        this.facade = facade;
        this.ctx = ctx;
        list = new RowWidgets<>(theme, theme.px(56), () -> {
            SettingRow row = new SettingRow(theme, ctx, facade.keyNames(), this);
            rows.add(row);
            return row;
        });
        list.setKeyFunction(SettingView::name);
        toolbar = new SettingsToolbar(theme, ctx, this::pickCategory, this::pickModifiedOnly, this::pickAdvanced);
        keyCard = new SettingKeyCard(theme, ctx, facade.keyNames(), currentKey(), currentKeyEnabled(), this::keyCaptured, this::keyToggled);
        common = new CommonSettings(theme, ctx, this);
        content = new SettingsContent(theme, toolbar, keyCard, list, common, this::flushEdits);
        reload();
    }

    @Override
    public String id() {
        return "settings";
    }

    @Override
    public String title() {
        return "Settings";
    }

    @Override
    public Icon icon() {
        return Icon.SETTINGS;
    }

    @Override
    public Widget content() {
        return content;
    }

    @Override
    public boolean hasSearch() {
        return true;
    }

    @Override
    public String searchPlaceholder() {
        return "Search settings";
    }

    @Override
    public void onSearch(String q) {
        flushEdits();
        query = q == null ? "" : q;
        refilter();
    }

    @Override
    public void onShown() {
        reload();
    }

    @Override
    public void onHidden() {
        flushEdits();
    }

    // ----- state

    private void reload() {
        List<SettingView> got = null;
        try {
            got = facade.settings().all();
        } catch (Throwable t) {
            Safe.report("settings snapshot", t);
        }
        List<SettingView> sorted = new ArrayList<>(got == null ? List.of() : got);
        sorted.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        all = sorted;
        syncKeyCard();
        refilter();
    }

    private void refilter() {
        List<SettingView> shown = SettingsFilter.apply(all, category, modifiedOnly, advanced, query, this::description);
        boolean overview = query.isBlank() && category.equals(SettingsFilter.ALL) && !modifiedOnly;
        common.setItems(shown);
        common.setVisible(overview);
        list.setItems(overview ? shown.stream().filter(v -> !common.containsSetting(v.name())).toList() : shown);
        content.browser().refresh(true);
        if (content.detail() != null && shown.stream().noneMatch(v -> v.name().equals(content.detail().name()))) {
            content.clearDetail();
        }
        list.setEmptyText(emptyText());
        content.setCountLine(countLine(shown.size()));
        pushToolbar();
    }

    private void pushToolbar() {
        toolbar.setData(SettingsFilter.counts(all, advanced), category, modifiedOnly, advanced);
    }

    // the dropdown already says how many settings there are. this line only turns up when a search or the modified
    // filter has narrowed things down, and an empty result says so in the list itself
    private String countLine(int n) {
        boolean searching = !query.isBlank();
        if (n == 0 || !(searching || modifiedOnly)) {
            return "";
        }
        if (searching) {
            return n + (modifiedOnly ? " matching, modified only" : " matching");
        }
        return n + " modified";
    }

    private String emptyText() {
        if (all.isEmpty()) {
            return "Settings are not available right now.";
        }
        if (!query.isBlank()) {
            return "No setting matches \"" + query.trim() + "\". Try a shorter word or pick All.";
        }
        if (modifiedOnly) {
            return "Nothing modified yet. Settings you change show up here.";
        }
        return "Nothing in this category.";
    }

    private void pickCategory(String c) {
        flushEdits();
        category = c;
        refilter();
    }

    private void pickModifiedOnly(boolean on) {
        flushEdits();
        modifiedOnly = on;
        refilter();
    }

    private void pickAdvanced(boolean on) {
        flushEdits();
        advanced = on;
        refilter();
    }

    // test hook: the toolbar's toggle is a widget, tests flip the flag directly
    void setAdvanced(boolean on) {
        advanced = on;
        refilter();
    }

    // an edit still in a box when the list is about to change under it gets committed first, not dropped
    void flushEdits() {
        flushEdits(Float.NaN, Float.NaN);
    }

    // a click lands at mx, my: a box under it is being clicked into, not left
    void flushEdits(float mx, float my) {
        for (SettingRow row : new ArrayList<>(rows)) {
            row.flush(mx, my);
        }
    }

    @Override
    public boolean mouseOverRows(float x, float y) {
        return content.browser().contains(x, y);
    }

    // ----- host

    @Override
    public String description(String name) {
        return descriptions.computeIfAbsent(name, n -> {
            try {
                String d = facade.settings().description(n);
                return d == null ? "" : d;
            } catch (Throwable t) {
                Safe.report("setting description", t);
                return "";
            }
        });
    }

    @Override
    public void hover(SettingView view) {
        SettingView shown = content.detail();
        if (shown != view) {
            content.showDetail(view, description(view.name()));
        }
    }

    @Override
    public Result set(String name, String text) {
        Result r;
        try {
            r = facade.settings().set(name, text);
        } catch (Throwable t) {
            Safe.report("setting set", t);
            r = Result.fail("Could not change " + name + ".");
        }
        // a rejected edit says why on its own row, a toast on top would only say it twice
        if (r.ok()) {
            ctx.toast(r);
            refreshOne(name);
        }
        return r;
    }

    @Override
    public Result reset(String name) {
        Result r;
        try {
            r = facade.settings().reset(name);
        } catch (Throwable t) {
            Safe.report("setting reset", t);
            r = Result.fail("Could not reset " + name + ".");
        }
        if (r.ok()) {
            ctx.toast(r);
            refreshOne(name);
        }
        return r;
    }

    // re-read one setting and push it everywhere it shows up
    private void refreshOne(String name) {
        SettingView v = null;
        try {
            v = facade.settings().get(name);
        } catch (Throwable t) {
            Safe.report("setting get", t);
        }
        if (v == null) {
            return;
        }
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).name().equals(name)) {
                List<SettingView> next = new ArrayList<>(all);
                next.set(i, v);
                all = next;
                break;
            }
        }
        // in place, not setItems: a blur commit lands in the middle of a draw, and rebinding the list then would pull
        // the row map out from under the loop that is drawing it
        List<SettingView> shown = list.items();
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).name().equals(name)) {
                shown.set(i, v);
            }
        }
        for (SettingRow row : rows) {
            row.update(v);
        }
        common.update(v);
        SettingView d = content.detail();
        if (d != null && d.name().equals(name)) {
            content.showDetail(v, description(name));
        }
        pushToolbar();
        if (name.equals(KEY) || name.equals(KEY_ENABLED)) {
            syncKeyCard();
        }
        if (name.equals(KEY)) {
            int key = parseKey(v.value());
            Safe.run("key rebound", () -> ctx.openKeyRebound(key));
        }
    }

    // ----- the pinned key card

    private int currentKey() {
        SettingView v = peek(KEY);
        return v == null ? DEFAULT_KEY : parseKey(v.value());
    }

    private boolean currentKeyEnabled() {
        SettingView v = peek(KEY_ENABLED);
        return v == null || Boolean.parseBoolean(v.value());
    }

    private SettingView peek(String name) {
        try {
            return facade.settings().get(name);
        } catch (Throwable t) {
            Safe.report("setting get", t);
            return null;
        }
    }

    private void syncKeyCard() {
        keyCard.setKey(currentKey());
        keyCard.setEnabled(currentKeyEnabled());
    }

    // the card has no line of its own to say why, so here a failure is a toast
    private void keyCaptured(int key) {
        Result r = set(KEY, String.valueOf(key));
        if (!r.ok()) {
            ctx.toast(r);
            syncKeyCard();
        }
    }

    private void keyToggled(boolean on) {
        Result r = set(KEY_ENABLED, String.valueOf(on));
        if (!r.ok()) {
            ctx.toast(r);
            syncKeyCard();
        }
    }

    private static int parseKey(String s) {
        try {
            return Integer.parseInt(s.trim().toLowerCase(Locale.ROOT));
        } catch (NumberFormatException | NullPointerException e) {
            return -1;
        }
    }

    // ----- for tests

    RowWidgets<SettingView> list() {
        return list;
    }

    CommonSettings common() {
        return common;
    }

    SettingsToolbar toolbar() {
        return toolbar;
    }

    SettingKeyCard keyCard() {
        return keyCard;
    }

    SettingsContent settingsContent() {
        return content;
    }

    List<SettingRow> rows() {
        return rows;
    }

    String category() {
        return category;
    }
}
