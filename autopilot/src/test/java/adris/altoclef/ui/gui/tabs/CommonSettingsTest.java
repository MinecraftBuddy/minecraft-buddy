package adris.altoclef.ui.gui.tabs;

import adris.altoclef.ui.gui.facade.SettingsAccess.Kind;
import adris.altoclef.ui.gui.facade.SettingsAccess.SettingView;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.StubCanvas;
import adris.altoclef.ui.gui.kit.TextMeasurer;
import adris.altoclef.ui.gui.kit.TextStyle;
import adris.altoclef.ui.gui.kit.Widget;
import adris.altoclef.ui.gui.preview.FakeFacade;
import adris.altoclef.ui.gui.preview.Preview;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class CommonSettingsTest {
    private SettingsTab tab;

    private Preview open(int width, int height) throws Exception {
        return new Preview(width, height, new FakeFacade(), p -> {
            tab = new SettingsTab(p.theme, p.facade, p.ctx);
            return List.of(tab);
        });
    }

    private Widget tile(String name) {
        int i = 0;
        for (CommonSettings.Spec spec : CommonSettings.SPECS) {
            if (spec.name().equals(name)) {
                return tab.common().children().get(i);
            }
            i++;
        }
        throw new AssertionError(name);
    }

    @Test
    public void commonTilesAndAdvancedRowsCoverTheSettingsWithoutDuplicates() throws Exception {
        Preview p = open(1920, 1080);
        p.settle();
        assertEquals(12, tab.common().size());
        assertEquals(p.facade.settings.all().size(), tab.common().size() + tab.list().items().size());
        assertTrue(tab.list().items().stream().noneMatch(v -> tab.common().containsSetting(v.name())));
        assertTrue(tab.list().items().stream().anyMatch(v -> v.name().equals("costHeuristic")));
    }

    @Test
    public void clickAndKeyboardSaveAndStayInSyncWithSearch() throws Exception {
        Preview p = open(1920, 1080);
        p.settle();
        Widget boat = tile("allowBoats");
        p.click(boat.x() + boat.w() / 2f, boat.y() + boat.h() / 2f);
        assertEquals("true", p.facade.settings.get("allowBoats").value());
        assertTrue(p.facade.actions.contains("set allowBoats true"));
        tab.onSearch("allowBoats");
        p.settle();
        assertFalse(tab.common().visible());
        assertTrue(tab.rows().stream().anyMatch(r -> "allowBoats".equals(r.name()) && r.toggle().on()));
        tab.onSearch("");
        p.settle();
        p.shell.focus(boat);
        p.key(Keys.SPACE);
        assertEquals("false", p.facade.settings.get("allowBoats").value());
        p.settle();
        assertEquals("allowBoats", tab.settingsContent().detail().name());
    }

    @Test
    public void rejectedTileChangeKeepsItsSavedState() throws Exception {
        Preview p = open(1920, 1080);
        p.settle();
        Widget boat = tile("allowBoats");
        p.facade.settings.map.remove("allowBoats");
        p.click(boat.x() + boat.w() / 2f, boat.y() + boat.h() / 2f);
        p.settle();
        assertEquals("false", tab.settingsContent().detail().value());
        assertFalse(p.facade.actions.contains("set allowBoats true"));
    }

    @Test
    public void releasingInTheDescriptionPanelDoesNotToggleAPartlyClippedTile() throws Exception {
        Preview p = open(1920, 1080);
        p.settle();
        SettingsBrowser browser = tab.settingsContent().browser();
        browser.setBounds(browser.x(), browser.y(), browser.w(), p.theme.px(60));
        p.settle();
        Widget boat = tile("allowBoats");
        p.press(boat.x() + boat.w() / 2f, boat.y() + p.theme.px(4));
        float releaseY = browser.y() + browser.h() + p.theme.px(2);
        assertTrue(boat.contains(boat.x() + boat.w() / 2f, releaseY));
        p.release(boat.x() + boat.w() / 2f, releaseY);
        assertEquals("false", p.facade.settings.get("allowBoats").value());
    }

    @Test
    public void keyboardFocusRevealsOffscreenTilesAndScrollingReachesTheAdvancedRows() throws Exception {
        Preview p = open(960, 540);
        p.settle();
        SettingsBrowser browser = tab.settingsContent().browser();
        Widget last = tile("renderPath");
        p.shell.focus(last);
        p.advance(500);
        p.settle();
        assertTrue(browser.scroller().target() > 0);
        assertTrue(last.y() >= browser.y());
        assertTrue(last.y() + last.h() <= browser.y() + browser.h());
        p.shell.focus(browser);
        p.key(Keys.END);
        p.advance(500);
        p.settle();
        assertTrue(tab.list().liveRows() > 0);
        assertTrue(tab.rows().size() < 20);
    }

    @Test
    public void commonGridInBothAppearancesAndMinimumSize() throws Exception {
        Preview p = open(1920, 1080);
        p.settle();
        Widget parkour = tile("allowParkour");
        p.hover(parkour.x() + parkour.w() / 2f, parkour.y() + parkour.h() / 2f);
        for (boolean dark : new boolean[]{true, false}) {
            p.shell.setDarkMode(dark);
            p.settle();
            p.advance(500);
            p.savePanel("settings_grid_" + (dark ? "dark" : "light"));
        }
        p.shell.setBounds(p.shell.x(), p.shell.y(), p.theme.px(560), p.theme.px(360));
        p.settle();
        p.savePanel("settings_grid_minimum");
        SettingsBrowser browser = tab.settingsContent().browser();
        assertTrue(browser.h() >= p.theme.px(48));
        assertTrue(browser.scroller().scrollable());
    }

    private static final class MetricsCanvas extends StubCanvas {
        record Text(String value, float baseline, TextStyle style, float[] clip) { }
        final TextMeasurer metrics;
        final ArrayDeque<float[]> clips = new ArrayDeque<>();
        final List<Text> drawn = new ArrayList<>();

        MetricsCanvas(TextMeasurer metrics) { this.metrics = metrics; }
        @Override public float ascent(TextStyle style) { return metrics.ascent(style); }
        @Override public float descent(TextStyle style) { return metrics.descent(style); }
        @Override public float measure(String text, TextStyle style) { return metrics.measure(text, style); }
        @Override public void pushClip(float x, float y, float w, float h) { clips.push(new float[]{x, y, w, h}); }
        @Override public void popClip() { clips.pop(); }
        @Override public float text(String text, float x, float baseline, TextStyle style, int color) {
            drawn.add(new Text(text, baseline, style, clips.peek()));
            return measure(text, style);
        }
    }

    @Test
    public void longDescriptionsFitInsideTheirOwnClipAtEveryScale() throws Exception {
        for (int height : new int[]{540, 1080, 1440, 2160}) {
            Preview p = open(height * 16 / 9, height);
            p.settle();
            SettingView climb = new SettingView("allowClimbJumps", Kind.BOOLEAN, "Boolean", "false", "false", false, List.of());
            for (boolean compact : new boolean[]{false, true}) {
                if (compact) {
                    p.shell.setBounds(p.shell.x(), p.shell.y(), p.theme.px(560), p.theme.px(360));
                }
                SettingsContent content = tab.settingsContent();
                content.showDetail(climb, "Jump onto ladders and vines across a gap, and off of them again, instead of walking the long way round. Only actually takes effect if allowParkour is also true. ".repeat(6));
                MetricsCanvas c = new MetricsCanvas(p.theme.measurer);
                content.draw(c, p.frame());
                MetricsCanvas.Text title = c.drawn.stream().filter(t -> t.value().equals("allowClimbJumps")).findFirst().orElseThrow();
                assertNotNull("description needs its own clip", title.clip());
                List<MetricsCanvas.Text> detail = c.drawn.stream().filter(t -> t.clip() == title.clip()).toList();
                assertTrue(detail.size() >= 3);
                for (MetricsCanvas.Text text : detail) {
                    assertTrue(text.baseline() - c.ascent(text.style()) >= title.clip()[1]);
                    assertTrue(text.baseline() + c.descent(text.style()) <= title.clip()[1] + title.clip()[3] - p.theme.px(6));
                }
                assertTrue(tab.settingsContent().browser().y() + tab.settingsContent().browser().h() < title.clip()[1]);
                assertTrue(c.clips.isEmpty());
            }
        }
    }
}
