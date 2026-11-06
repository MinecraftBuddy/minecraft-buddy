package adris.altoclef.ui.gui.preview;

import adris.altoclef.ui.gui.kit.Anim;
import adris.altoclef.ui.gui.kit.AwtFonts;
import adris.altoclef.ui.gui.kit.Button;
import adris.altoclef.ui.gui.kit.Frame;
import adris.altoclef.ui.gui.kit.GlyphAtlas;
import adris.altoclef.ui.gui.kit.Keys;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.kit.ShapeAtlas;
import adris.altoclef.ui.gui.kit.Theme;
import adris.altoclef.ui.gui.update.UpdateCard;
import adris.altoclef.ui.gui.update.WhatsNewPanel;
import baritone.utils.update.Snapshot;
import baritone.utils.update.UpdateState;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

// the title screen card and the release notes, one png per state in build/gui-preview/update_*.png. the pictures are
// the point, the asserts are there so a card that threw or grew past the logo fails the test instead of saving a lie
public class UpdateCardPreviewTest {

    private static final long T0 = 10_000_000_000L;
    // the panorama is mostly sky and grass, two bands stand in for it
    private static final int SKY = 0xFF7FA7D9;
    private static final int GROUND = 0xFF4E7A3C;

    private static final String NOTES = "Descent floors\n\nThe bot no longer digs through the floor of a cave it is standing in "
            + "when the goal is below it. It walks to the edge and drops instead, which is what you would do.\n\n"
            + "Schematica\n\nThe first two slices of the builder rewrite. Layers build bottom up again and the "
            + "scaffold comes down behind you.\n\nFixes\n\nThe chest bag slots pin to the exact run.\n"
            + "Junk is thrown by list order again.\nA very long token with no spaces to see the wrap chop it: "
            + "https://github.com/AverWasTaken/soprano/releases/tag/v1.1.7-this-is-on-purpose\n";

    private static final class Harness implements UpdateCard.Actions {
        final List<String> actions = new ArrayList<>();
        final GlyphAtlas glyphs;
        final ShapeAtlas shapes = new ShapeAtlas();
        final Theme theme;
        final UpdateCard card;
        final int winW;
        final int winH;
        long now = T0;
        float mx = -100;
        float my = -100;

        Harness(float scale, int winW, int winH) throws Exception {
            Anim.enabled = true;
            glyphs = new GlyphAtlas(AwtFonts.load());
            theme = new Theme(scale, glyphs);
            card = new UpdateCard(theme, this);
            this.winW = winW;
            this.winH = winH;
        }

        void show(Snapshot s, int maxDesignW) {
            card.setMaxWidth(maxDesignW);
            card.show(s);
            // 6 gui units at gui scale 2, which is what scale 1 stands for
            card.setBounds(12, 12, card.preferredWidth(), card.preferredHeight());
        }

        Java2dCanvas render() {
            Java2dCanvas c = new Java2dCanvas(winW, winH, glyphs, shapes);
            c.setTheme(theme);
            theme.update(now);
            c.clear(SKY);
            c.fillRect(0, winH * 0.55f, winW, winH * 0.45f, GROUND);
            int before = Safe.reports();
            card.mouseMove(mx, my);
            card.draw(c, new Frame(now, 1f / 60f, mx, my));
            if (Safe.reports() != before) {
                throw new AssertionError("the card threw while drawing: " + Safe.lastReport());
            }
            return c;
        }

        Path save(String name) throws Exception {
            Files.createDirectories(Preview.OUT);
            Path p = Preview.OUT.resolve(name + ".png");
            Java2dCanvas c = render();
            // crop to the card and a margin so the png is the card and not a screen of sky
            int pad = theme.px(24);
            int w = Math.min(winW, card.w() + 2 * pad + 12);
            int h = Math.min(winH, card.h() + 2 * pad + 12);
            javax.imageio.ImageIO.write(c.image().getSubimage(0, 0, w, h), "png", p.toFile());
            return p;
        }

        void click(Button b) {
            float cx = b.x() + b.w() / 2f;
            float cy = b.y() + b.h() / 2f;
            mx = cx;
            my = cy;
            card.mouseMove(cx, cy);
            card.mouseDown(cx, cy, 0, 0);
            card.mouseUp(cx, cy, 0, 0);
            now += Anim.ms(16);
        }

        @Override
        public void update() {
            actions.add("update");
        }

        @Override
        public void recheck() {
            actions.add("recheck");
        }

        @Override
        public void whatsNew() {
            actions.add("whatsNew");
        }

        @Override
        public void openPage(String url) {
            actions.add("openPage " + url);
        }

        @Override
        public void quit() {
            actions.add("quit");
        }

        @Override
        public void openMods(Path folder) {
            actions.add("openMods " + folder);
        }

        @Override
        public void beginSwitch() {
            actions.add("beginSwitch");
        }

        @Override
        public void cancelSwitch() {
            actions.add("cancelSwitch");
        }
    }

    private static final Snapshot.Release RELEASE = new Snapshot.Release("1.1.7", "v1.1.7 · Descent floors and schematica", NOTES,
            "https://github.com/AverWasTaken/soprano/releases/tag/v1.1.7", 10_300_000L);
    private static final Snapshot.Release BARE = new Snapshot.Release("1.1.7", null, "", null, 0);
    private static final Path MODS = Path.of("C:", "Users", "you", "AppData", "Roaming", ".minecraft", "mods");

    private static Snapshot snap(UpdateState state, boolean canInstall, String reason, Snapshot.Release r, long done, long total,
                                 String error, boolean unfinished) {
        return new Snapshot(state, "1.1.6", canInstall, reason, r, done, total, error, unfinished, MODS,
                "soprano-1.1.6-fabric.jar", "soprano-1.1.7-fabric.jar.new", "soprano-1.1.7-fabric.jar", Snapshot.RELEASES_PAGE,
                null, false);
    }

    private static final Snapshot.Release NIGHTLY = new Snapshot.Release("1.1.7-nightly.1", "Soprano v1.1.7-nightly.1 (26.3)", NOTES,
            "https://github.com/AverWasTaken/soprano/releases/tag/soprano-v1.1.7-nightly.1", 10_300_000L);
    private static final Snapshot.Release STABLE = new Snapshot.Release("1.1.7", "Soprano v1.1.7 (26.3)", NOTES,
            "https://github.com/AverWasTaken/soprano/releases/tag/soprano-v1.1.7", 10_200_000L);
    private static final Snapshot.Release STABLE_ANY = new Snapshot.Release("1.1.7-anyversion", "Soprano v1.1.7 Any Version (base 26.3)",
            NOTES, "https://github.com/AverWasTaken/soprano/releases/tag/soprano-v1.1.7-anyversion", 10_200_000L);

    // up to date with a way over to the other track
    private static Snapshot upToDate(String current, Snapshot.Release target) {
        return new Snapshot(UpdateState.UP_TO_DATE, current, false, null, null, 0, 0, null, false, MODS,
                null, null, null, Snapshot.RELEASES_PAGE, target, false);
    }

    // mid switch: release is the target, the same as beginSwitch publishes
    private static Snapshot switching(UpdateState state, String current, Snapshot.Release target, boolean canInstall, String reason,
                                      String error) {
        return new Snapshot(state, current, canInstall, reason, target, 0, 0, error, false, MODS,
                null, null, null, Snapshot.RELEASES_PAGE, target, true);
    }

    private static List<Object[]> states() {
        List<Object[]> out = new ArrayList<>();
        out.add(new Object[]{"disabled", snap(UpdateState.DISABLED, false, null, null, 0, 0, null, false)});
        out.add(new Object[]{"checking", snap(UpdateState.CHECKING, false, null, null, 0, 0, null, false)});
        out.add(new Object[]{"up_to_date", snap(UpdateState.UP_TO_DATE, false, null, null, 0, 0, null, false)});
        out.add(new Object[]{"check_failed", snap(UpdateState.CHECK_FAILED, false, null, null, 0, 0, "couldn't reach github", false)});
        out.add(new Object[]{"available", snap(UpdateState.AVAILABLE, true, null, RELEASE, 0, 0, null, false)});
        out.add(new Object[]{"available_dev", snap(UpdateState.AVAILABLE, false, "pretending to be 1.1.5, install is off", RELEASE, 0, 0, null, false)});
        out.add(new Object[]{"downloading", snap(UpdateState.DOWNLOADING, true, null, RELEASE, 4_400_000L, 10_300_000L, null, false)});
        out.add(new Object[]{"ready", snap(UpdateState.READY, false, null, RELEASE, 10_300_000L, 10_300_000L, null, false)});
        out.add(new Object[]{"ready_retry", snap(UpdateState.READY, false, null, BARE, 0, 0,
                "couldn't start the swap helper, it'll try again next launch", true)});
        out.add(new Object[]{"stuck", snap(UpdateState.STUCK, false, null, BARE, 0, 0, null, true)});
        out.add(new Object[]{"failed", snap(UpdateState.FAILED, true, null, RELEASE, 0, 0, "the jar's checksum didn't match", false)});
        out.add(new Object[]{"failed_dev", snap(UpdateState.FAILED, false, "pretending to be 1.1.5, install is off", RELEASE, 0, 0,
                "the download didn't finish", false)});
        return out;
    }

    @Test
    public void everyStateAtScaleOne() throws Exception {
        Harness h = new Harness(1f, 960, 540);
        for (Object[] s : states()) {
            h.show((Snapshot) s[1], UpdateCard.RICH_W);
            h.save("update_" + s[0]);
            assertTrue("card wider than its cap: " + s[0], h.card.w() <= h.theme.px(UpdateCard.RICH_W));
        }
        h.theme.setDark(false);
        for (Object[] s : states()) {
            h.show((Snapshot) s[1], UpdateCard.RICH_W);
            h.save("update_light_" + s[0]);
        }
    }

    // 1080p at gui scale 4 is the common default: the card is at 2.0, the logo starts 112 gui units in and the margin is
    // 4, which leaves 416 px, or 208 design px. the available buttons sit side by side, the dev ones stack
    @Test
    public void narrowCapStacksTheButtons() throws Exception {
        Harness h = new Harness(2f, 1920, 1080);
        h.show(snap(UpdateState.AVAILABLE, true, null, RELEASE, 0, 0, null, false), 208);
        h.save("update_available_1080p_scale4");
        assertTrue(h.card.w() <= 416);
        assertEquals(2, h.card.buttons().size());
        assertEquals(h.card.buttons().get(0).y(), h.card.buttons().get(1).y());
        h.show(snap(UpdateState.AVAILABLE, false, "pretending to be 1.1.5, install is off", RELEASE, 0, 0, null, false), 208);
        h.save("update_available_dev_1080p_scale4");
        assertTrue(h.card.w() <= 416);
        assertTrue(h.card.buttons().get(0).y() < h.card.buttons().get(1).y());
    }

    // the default 854x480 window at gui scale 2: the card is at 1.0 and only 146 design px fit before the logo, which is
    // under the 180 floor. the card goes as narrow as the floor lets it and no wider, buttons stacked, text wrapped
    @Test
    public void tinyWindowKeepsTheCardAtTheFloor() throws Exception {
        Harness h = new Harness(1f, 854, 480);
        for (Object[] s : states()) {
            h.show((Snapshot) s[1], 146);
            assertTrue("card over the floor: " + s[0], h.card.w() <= h.theme.px(UpdateCard.MIN_W) || h.card.compact());
        }
        h.show(snap(UpdateState.AVAILABLE, false, "pretending to be 1.1.5, install is off", RELEASE, 0, 0, null, false), 146);
        h.save("update_available_dev_854x480");
        assertTrue(h.card.buttons().get(0).y() < h.card.buttons().get(1).y());
        // the floor is the floor because the longest button has to fit on it whole
        for (Button b : h.card.buttons()) {
            assertTrue(b.text() + " ellipsized at the floor", b.w() >= b.preferredWidth());
        }
        // a cap between the floor and the rich minimum is honoured exactly
        h.show(snap(UpdateState.STUCK, false, null, BARE, 0, 0, null, true), 200);
        h.save("update_stuck_cap200");
        assertTrue(h.card.w() <= h.theme.px(200));
        assertTrue(h.card.w() > h.theme.px(UpdateCard.MIN_W));
    }

    @Test
    public void buttonsFireTheRightActions() throws Exception {
        Harness h = new Harness(1f, 960, 540);
        h.show(snap(UpdateState.AVAILABLE, true, null, RELEASE, 0, 0, null, false), UpdateCard.RICH_W);
        h.click(h.card.buttons().get(0));
        h.click(h.card.buttons().get(1));
        h.show(snap(UpdateState.AVAILABLE, false, "no jar", RELEASE, 0, 0, null, false), UpdateCard.RICH_W);
        h.click(h.card.buttons().get(0));
        h.show(snap(UpdateState.CHECK_FAILED, false, null, null, 0, 0, null, false), UpdateCard.RICH_W);
        assertTrue(h.card.compact());
        h.click(h.card.buttons().get(0));
        h.show(snap(UpdateState.READY, false, null, BARE, 0, 0, null, false), UpdateCard.RICH_W);
        h.click(h.card.buttons().get(0));
        h.show(snap(UpdateState.STUCK, false, null, BARE, 0, 0, null, true), UpdateCard.RICH_W);
        h.click(h.card.buttons().get(0));
        h.show(snap(UpdateState.FAILED, false, "no jar", BARE, 0, 0, "boom", false), UpdateCard.RICH_W);
        h.click(h.card.buttons().get(0));
        assertEquals(List.of("update", "whatsNew", "openPage " + RELEASE.htmlUrl(), "recheck", "quit", "openMods " + MODS,
                "openPage " + Snapshot.RELEASES_PAGE), h.actions);
        // the bare release from a launch-time reconcile has no page of its own, so the releases list is the fallback
        assertEquals("Soprano v1.1.6, couldn't check", summaryOf(h, snap(UpdateState.CHECK_FAILED, false, null, null, 0, 0, null, false)));
    }

    private static String summaryOf(Harness h, Snapshot s) {
        h.show(s, UpdateCard.RICH_W);
        return h.card.summary();
    }

    @Test
    public void keyboardWalksTheButtonsAndOffTheEnd() throws Exception {
        Harness h = new Harness(1f, 960, 540);
        h.show(snap(UpdateState.AVAILABLE, true, null, RELEASE, 0, 0, null, false), UpdateCard.RICH_W);
        assertTrue(h.card.hasFocusable());
        h.card.focusEnd(false);
        assertEquals("Update", h.card.focusedLabel());
        assertTrue(h.card.cycleFocus(false));
        assertEquals("What's new", h.card.focusedLabel());
        assertFalse(h.card.cycleFocus(false));
        assertNull(h.card.focusedLabel());
        h.card.focusEnd(true);
        assertEquals("What's new", h.card.focusedLabel());
        assertTrue(h.card.cycleFocus(true));
        assertTrue(h.card.keyDown(Keys.ENTER, 0));
        assertEquals(List.of("update"), h.actions);
        h.save("update_available_focused");
        // the ring on a primary is blue on blue (kit wide), the ghost shows it plainly
        h.card.cycleFocus(false);
        h.save("update_available_focused_ghost");
        h.show(snap(UpdateState.UP_TO_DATE, false, null, null, 0, 0, null, false), UpdateCard.RICH_W);
        assertFalse(h.card.hasFocusable());
    }

    // ----- stable <-> nightly

    // 1080p at gui scale 2 leaves 688 design px before the logo, gui scale 3 leaves 368. 400 is a normal window
    private static final int ROOMY = 400;

    @Test
    public void upToDateOffersTheOtherTrack() throws Exception {
        Harness h = new Harness(1f, 960, 540);
        h.show(upToDate("1.1.6", null), ROOMY);
        int plainH = h.card.h();
        assertTrue(h.card.buttons().isEmpty());
        h.show(upToDate("1.1.6", NIGHTLY), ROOMY);
        h.save("update_up_to_date_switch_nightly");
        assertTrue(h.card.compact());
        assertEquals(1, h.card.buttons().size());
        assertEquals("Switch to nightly", h.card.buttons().get(0).text());
        assertTrue(h.card.w() <= h.theme.px(ROOMY));
        // one row, the button doesn't make the card any taller
        assertEquals(plainH, h.card.h());
        h.click(h.card.buttons().get(0));
        assertEquals(List.of("beginSwitch"), h.actions);
        h.card.focusEnd(false);
        assertEquals("Switch to nightly", h.card.focusedLabel());
        assertEquals("Soprano v1.1.6, up to date", h.card.summary());
        h.show(upToDate("1.1.8-nightly.2", STABLE), ROOMY);
        h.save("update_up_to_date_switch_stable");
        assertEquals("Switch to stable", h.card.buttons().get(0).text());
        assertEquals(plainH, h.card.h());
        // an update always wins, the switch is only on the up to date row
        h.show(new Snapshot(UpdateState.CHECK_FAILED, "1.1.6", false, null, null, 0, 0, null, false, MODS, null, null, null,
                Snapshot.RELEASES_PAGE, NIGHTLY, false), ROOMY);
        assertEquals(List.of("Retry"), labels(h));
    }

    // the longest name there is, on the widest cap and the narrowest ones. the button never takes the card under the logo
    @Test
    public void noRoomDropsTheSwitchRowThenTheCard() throws Exception {
        Harness h = new Harness(1f, 960, 540);
        Snapshot s = upToDate("1.1.6", NIGHTLY);
        h.show(s, UpdateCard.MIN_W);
        assertEquals(1, h.card.buttons().size());
        h.show(s, UpdateCard.MIN_W - 1);
        assertEquals(0, h.card.buttons().size());
        assertTrue(!h.card.hidden());
        h.show(s, UpdateCard.MIN_COMPACT_W - 1);
        assertTrue(h.card.hidden());
        assertEquals(0, h.card.preferredWidth());
        h.show(snap(UpdateState.AVAILABLE, true, null, RELEASE, 0, 0, null, false), UpdateCard.MIN_COMPACT_W - 1);
        assertTrue(h.card.hidden());
        h.show(s, UpdateCard.MIN_W);
        assertTrue(!h.card.hidden());
    }

    @Test
    public void longNightlyNameKeepsClearOfTheLogo() throws Exception {
        Snapshot s = upToDate("1.1.8-nightly.1-anyversion", STABLE_ANY);
        Harness h = new Harness(1f, 960, 540);
        h.show(s, ROOMY);
        h.save("update_up_to_date_switch_long");
        assertTrue(h.card.w() <= h.theme.px(ROOMY));
        h.show(s, UpdateCard.RICH_W);
        h.save("update_up_to_date_switch_long_300");
        assertTrue(h.card.w() <= h.theme.px(UpdateCard.RICH_W));
        h.show(s, UpdateCard.MIN_W);
        h.save("update_up_to_date_switch_long_narrow");
        assertTrue("card " + h.card.w() + " over " + h.theme.px(UpdateCard.MIN_W), h.card.w() <= h.theme.px(UpdateCard.MIN_W));
        Button b = h.card.buttons().get(0);
        assertTrue("the button went under the row", b.y() > h.card.y() + h.theme.px(8));
        assertTrue(b.text() + " ellipsized", b.w() >= b.preferredWidth());
        Harness big = new Harness(2f, 1920, 1080);
        big.show(s, 208);
        big.save("update_up_to_date_switch_long_1080p_scale4");
        assertTrue(big.card.w() <= big.theme.px(208));
        // a normal name on the narrow cap still gets its button on the one row
        h.show(upToDate("1.1.6", NIGHTLY), UpdateCard.MIN_W);
        h.save("update_up_to_date_switch_narrow");
        assertTrue(h.card.w() <= h.theme.px(UpdateCard.MIN_W));
    }

    @Test
    public void switchingCardSaysWhereItGoes() throws Exception {
        Harness h = new Harness(1f, 960, 540);
        h.show(switching(UpdateState.AVAILABLE, "1.1.6", NIGHTLY, true, null, null), UpdateCard.RICH_W);
        h.save("update_switch_to_nightly");
        assertTrue(h.card.summary(), h.card.summary().startsWith("Nightly v1.1.7-nightly.1, nightly builds can be buggy"));
        assertEquals(List.of("Switch", "What's new", "Cancel"), labels(h));
        assertTrue(h.card.w() <= h.theme.px(UpdateCard.RICH_W));
        for (Button b : List.copyOf(h.card.buttons())) {
            h.click(b);
        }
        assertEquals(List.of("update", "whatsNew", "cancelSwitch"), h.actions);
        h.show(switching(UpdateState.AVAILABLE, "1.1.8-nightly.2", STABLE, true, null, null), UpdateCard.RICH_W);
        h.save("update_switch_to_stable");
        assertTrue(h.card.summary(), h.card.summary().startsWith("Stable v1.1.7, you have v1.1.8-nightly.2"));
        assertEquals(List.of("Switch", "What's new", "Cancel"), labels(h));
        // can't install: the page instead of switch, cancel still there
        h.show(switching(UpdateState.AVAILABLE, "1.1.6", NIGHTLY, false, "pretending to be 1.1.6, install is off", null),
                UpdateCard.RICH_W);
        h.save("update_switch_to_nightly_dev");
        assertEquals(List.of("Open download page", "What's new", "Cancel"), labels(h));
        assertTrue(h.card.w() <= h.theme.px(UpdateCard.RICH_W));
        // a failed switch retries the same build or backs out
        h.show(switching(UpdateState.FAILED, "1.1.6", NIGHTLY, true, null, "the jar's checksum didn't match"), UpdateCard.RICH_W);
        h.save("update_switch_failed");
        assertEquals(List.of("Retry", "Open download page", "Cancel"), labels(h));
        assertTrue(h.card.summary().startsWith("The switch didn't finish"));
        // the narrow caps stack and stay inside
        for (int cap : new int[]{UpdateCard.MIN_W, 208}) {
            h.show(switching(UpdateState.AVAILABLE, "1.1.8-nightly.1-anyversion", STABLE_ANY, true, null, null), cap);
            assertTrue(h.card.w() <= h.theme.px(cap));
        }
        h.save("update_switch_to_stable_narrow");
        Harness big = new Harness(2f, 1920, 1080);
        big.show(switching(UpdateState.AVAILABLE, "1.1.6", NIGHTLY, true, null, null), 208);
        big.save("update_switch_to_nightly_1080p_scale4");
        assertTrue(big.card.w() <= big.theme.px(208));
    }

    private static List<String> labels(Harness h) {
        List<String> out = new ArrayList<>();
        for (Button b : h.card.buttons()) {
            out.add(b.text());
        }
        return out;
    }

    @Test
    public void downloadCounterMovesWithoutRelayout() throws Exception {
        Harness h = new Harness(1f, 960, 540);
        h.show(snap(UpdateState.DOWNLOADING, true, null, RELEASE, 0, 10_300_000L, null, false), UpdateCard.RICH_W);
        int w = h.card.w();
        for (int i = 0; i <= 4; i++) {
            h.show(snap(UpdateState.DOWNLOADING, true, null, RELEASE, 10_300_000L * i / 4, 10_300_000L, null, false), UpdateCard.RICH_W);
            h.save(String.format("update_downloading_%d", i));
            assertEquals(w, h.card.w());
        }
        assertEquals("4.2 of 9.8 MB", UpdateCard.counter(4_400_000L, 10_300_000L));
        assertEquals("4.2 MB so far", UpdateCard.counter(4_400_000L, 0));
    }

    // ----- the release notes

    private static final class Notes implements WhatsNewPanel.Actions {
        final List<String> actions = new ArrayList<>();
        final GlyphAtlas glyphs;
        final ShapeAtlas shapes = new ShapeAtlas();
        final Theme theme;
        final WhatsNewPanel panel;
        final int winW;
        final int winH;
        long now = T0;

        Notes(float scale, int winW, int winH, Snapshot s) throws Exception {
            Anim.enabled = true;
            glyphs = new GlyphAtlas(AwtFonts.load());
            theme = new Theme(scale, glyphs);
            panel = new WhatsNewPanel(theme, s, this);
            this.winW = winW;
            this.winH = winH;
            panel.setBounds(0, 0, winW, winH);
        }

        Path save(String name) throws Exception {
            Java2dCanvas c = new Java2dCanvas(winW, winH, glyphs, shapes);
            c.setTheme(theme);
            theme.update(now);
            c.clear(SKY);
            c.fillRect(0, winH * 0.55f, winW, winH * 0.45f, GROUND);
            // vanilla dims and blurs the panorama under a screen, this is roughly that
            c.fillRect(0, 0, winW, winH, 0x80000000);
            int before = Safe.reports();
            panel.mouseMove(-100, -100);
            panel.draw(c, new Frame(now, 1f / 60f, -100, -100));
            if (Safe.reports() != before) {
                throw new AssertionError("the notes threw while drawing: " + Safe.lastReport());
            }
            Files.createDirectories(Preview.OUT);
            Path p = Preview.OUT.resolve(name + ".png");
            c.save(p);
            return p;
        }

        @Override
        public void update() {
            actions.add("update");
        }

        @Override
        public void openPage(String url) {
            actions.add("openPage " + url);
        }

        @Override
        public void back() {
            actions.add("back");
        }
    }

    @Test
    public void notesScrollAndTheButtonsWork() throws Exception {
        Notes n = new Notes(1f, 960, 540, snap(UpdateState.AVAILABLE, true, null, RELEASE, 0, 0, null, false));
        assertTrue(n.panel.noteLines().size() > 10);
        assertTrue(n.panel.scroller().scrollable());
        n.save("update_notes");
        n.panel.scroll(n.panel.panelX() + 40, n.panel.panelY() + n.panel.panelH() / 2f, -3f);
        // the bar fades in from the first frame after the scroll, so one throwaway frame then the shot
        n.save("update_notes_scrolled");
        n.now += Anim.ms(400);
        n.save("update_notes_scrolled");
        assertTrue(n.panel.scroller().offset(n.now) > 0f);
        assertEquals("Update", n.panel.primary().text());
        n.panel.keyDown(Keys.TAB, 0);
        n.panel.keyDown(Keys.TAB, 0);
        n.panel.keyDown(Keys.ENTER, 0);
        assertEquals(List.of("update"), n.actions);
        n.panel.mouseDown(n.panel.back().x() + 5, n.panel.back().y() + 5, 0, 0);
        n.panel.mouseUp(n.panel.back().x() + 5, n.panel.back().y() + 5, 0, 0);
        assertEquals(List.of("update", "back"), n.actions);
    }

    @Test
    public void notesSaySwitchWhileSwitching() throws Exception {
        Notes n = new Notes(1f, 960, 540, switching(UpdateState.AVAILABLE, "1.1.6", NIGHTLY, true, null, null));
        assertEquals("Switch", n.panel.primary().text());
        n.save("update_notes_switch");
    }

    @Test
    public void bareReleaseAndDevNotes() throws Exception {
        Notes dev = new Notes(1.5f, 1920, 1080, snap(UpdateState.AVAILABLE, false, "no jar", RELEASE, 0, 0, null, false));
        assertEquals("Open download page", dev.panel.primary().text());
        dev.save("update_notes_dev_1080p_scale4");
        Notes bare = new Notes(1f, 960, 540, snap(UpdateState.AVAILABLE, false, "no jar", BARE, 0, 0, null, false));
        assertTrue(bare.panel.noteLines().isEmpty());
        assertFalse(bare.panel.scroller().scrollable());
        bare.save("update_notes_empty");
        bare.panel.mouseDown(bare.panel.primary().x() + 5, bare.panel.primary().y() + 5, 0, 0);
        bare.panel.mouseUp(bare.panel.primary().x() + 5, bare.panel.primary().y() + 5, 0, 0);
        assertEquals(List.of("openPage " + Snapshot.RELEASES_PAGE), bare.actions);
    }
}
